#!/usr/bin/env bash
# O ensaio de DR de ponta a ponta, com pg_dump/pg_restore REAIS e a imagem REAL — sem GCP (T18.3 §7/§23).
#
#   seed ──▶ backup (db-backup.js, provider local) ──▶ manifesto/sha256 conferidos
#     ──▶ "produção" perde dado E ganha uma tabela nova (schema expandido depois do snapshot)
#     ──▶ restore drill (db-restore-drill.js) num banco NOVO
#     ──▶ o dado semeado voltou · a tabela nova NÃO existe no restaurado · produção segue intocada
#     ──▶ anti-ressurreição: ledger externo + reconcile-account-deletions sobre o restaurado
#     ──▶ backend real sobe sobre o restaurado: /health/ready 200, /v1 fechado
#     ──▶ retenção: só os N mais recentes ficam; backup com banco inalcançável falha fechado
#
# É o que o CI roda (job `dr-drill`), e o que o operador pode rodar localmente contra qualquer
# PostgreSQL 17 descartável. Nunca recebe a URL de produção real: `SPARK_TEST_DATABASE_URL` é o
# banco que FAZ O PAPEL de produção neste ensaio (o `spark_dev` do serviço do CI).
#
# Uso:
#   SPARK_IMAGE=spark-backend:local \
#   SPARK_TEST_DATABASE_URL=postgresql://spark:spark@127.0.0.1:5432/spark_dev \
#   SPARK_DRILL_ADMIN_URL=postgresql://spark:spark@127.0.0.1:5432/postgres \
#   SPARK_CI_ACCOUNT_DELETION_HMAC_KEY=<chave sintética> \
#     ops/gcp/dr-backup-drill.sh

set -euo pipefail

log()  { printf '%s [dr-drill] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
fail() { printf '%s [dr-drill] ERRO: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; exit 1; }
require_var() { [ -n "${!1:-}" ] || fail "variável obrigatória vazia ou ausente: $1"; }

require_var SPARK_IMAGE
require_var SPARK_TEST_DATABASE_URL
require_var SPARK_DRILL_ADMIN_URL
require_var SPARK_CI_ACCOUNT_DELETION_HMAC_KEY
command -v docker > /dev/null 2>&1 || fail "docker é obrigatório"
command -v jq > /dev/null 2>&1 || fail "jq é obrigatório"
command -v openssl > /dev/null 2>&1 || fail "openssl é obrigatório"
command -v sha256sum > /dev/null 2>&1 || fail "sha256sum é obrigatório"

PROD_URL="${SPARK_TEST_DATABASE_URL}"
ADMIN_URL="${SPARK_DRILL_ADMIN_URL}"
[ "${PROD_URL}" != "${ADMIN_URL}" ] || fail "SPARK_DRILL_ADMIN_URL não pode ser o próprio banco de 'produção' do ensaio"
DRILL_DB="spark_drill_ci_$$"
DRILL_URL="$(printf '%s' "${ADMIN_URL}" | sed 's#/[^/?]*\(?.*\)\?$#/'"${DRILL_DB}"'\1#')"
[ "${DRILL_URL}" != "${ADMIN_URL}" ] || fail "não foi possível derivar a URL do banco de ensaio de SPARK_DRILL_ADMIN_URL"
APP_PORT="${SPARK_DRILL_APP_PORT:-18083}"
APP_CONTAINER="spark-dr-drill-app-$$"

WORK="$(mktemp -d)"
OBJECTS="${WORK}/objects"
mkdir -p "${OBJECTS}"
# O container roda como `node` (uid 1000) e o runner tem outro uid: o diretório de objetos entra
# pelo grupo compartilhado (setgid + --group-add), nunca por 777 (T16.8.1 §3).
chmod 2770 "${OBJECTS}"
OBJECTS_GID="$(stat -c %g "${OBJECTS}")"

cleanup() {
  docker rm -f "${APP_CONTAINER}" > /dev/null 2>&1 || true
  psql_admin "DROP DATABASE IF EXISTS ${DRILL_DB} WITH (FORCE)" > /dev/null 2>&1 || true
  if [ -n "${WORK}" ] && [ -d "${WORK}" ]; then
    rm -rf "${WORK}"
  fi
}
trap cleanup EXIT

# --- helpers: tudo pela imagem real (que traz psql/pg_dump/pg_restore 17); URL nunca no argv ------

run_image() {
  # run_image <url-env-name> <url> [docker args...] -- <cmd...>
  local url_name="$1" url="$2"
  shift 2
  local docker_args=()
  while [ $# -gt 0 ] && [ "$1" != "--" ]; do docker_args+=("$1"); shift; done
  [ "${1:-}" = "--" ] && shift
  env "${url_name}=${url}" docker run --rm --network host \
    --group-add "${OBJECTS_GID}" \
    -v "${OBJECTS}:/objects" \
    -e "${url_name}" \
    "${docker_args[@]}" \
    --entrypoint "$1" "${SPARK_IMAGE}" "${@:2}"
}

psql_on() {
  # psql_on <url> <sql> — imprime a saída sem cabeçalhos.
  local url="$1" sql="$2"
  PGURL="${url}" docker run --rm --network host -e PGURL --entrypoint sh "${SPARK_IMAGE}" \
    -c 'psql "$PGURL" -X -q -t -A -v ON_ERROR_STOP=1 -c "$1"' sh "${sql}"
}
psql_prod()  { psql_on "${PROD_URL}" "$1"; }
psql_admin() { psql_on "${ADMIN_URL}" "$1"; }
psql_drill() { psql_on "${DRILL_URL}" "$1"; }

backup_now() {
  # backup_now <retention> [extra env...] — roda o Job de backup com o provider local.
  local retention="$1"
  shift
  run_image DATABASE_URL_DIRECT "${PROD_URL}" \
    -e OBJECT_STORAGE_PROVIDER=local -e SOCIAL_MEDIA_ROOT=/objects \
    -e "SPARK_DR_RETENTION_COUNT=${retention}" -e SPARK_GIT_COMMIT=abcdef1234567 -e LOG_LEVEL=warn "$@" \
    -- node dist/cli/db-backup.js
}

failures=0
check() {
  local descricao="$1" esperado="$2" obtido="$3"
  if [ "$esperado" = "$obtido" ]; then
    printf '  ok    %s\n' "$descricao"
  else
    printf '  FALHA %s (esperado "%s", obtido "%s")\n' "$descricao" "$esperado" "$obtido" >&2
    failures=$((failures + 1))
  fi
}

# ================================================================ 0. "produção" migrada
log "migrando o banco que faz o papel de produção (migrate-database.js)"
run_image DATABASE_URL_DIRECT "${PROD_URL}" -- node dist/cli/migrate-database.js > /dev/null

# ================================================================ 1. seed
NOW_MS="$(( $(date +%s) * 1000 ))"
UID_SEED="uid-dr-drill-$$"
log "semeando dado reconhecível (perfil social + metadata)"
psql_prod "DELETE FROM social_profiles WHERE owner_uid LIKE 'uid-dr-drill-%'" > /dev/null
psql_prod "INSERT INTO server_metadata (key, value, updated_at) VALUES ('dr_drill_marker', 'presente-$$', ${NOW_MS}) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at" > /dev/null
psql_prod "INSERT INTO social_profiles (owner_uid, social_id, display_name, friend_code, status, created_at, updated_at) VALUES ('${UID_SEED}', '22222222-2222-4222-8222-222222222222', 'Drill', 'SPK-DRILL$$', 'ACTIVE', ${NOW_MS}, ${NOW_MS})" > /dev/null
psql_prod "DROP TABLE IF EXISTS future_migration_table" > /dev/null

# ================================================================ 2. backup
log "backup de DR #1 (pg_dump real, provider local em ${OBJECTS})"
backup_now 7 > "${WORK}/backup1.out"
check "backup #1 termina com DB_BACKUP_OK" "sim" "$(grep -q '^DB_BACKUP_OK' "${WORK}/backup1.out" && echo sim || echo não)"
BACKUP1="$(sed -n 's/^DB_BACKUP_OK backupId=\([^ ]*\).*/\1/p' "${WORK}/backup1.out")"
[ -n "${BACKUP1}" ] || fail "não foi possível ler o backupId do backup #1"
MANIFEST1="${OBJECTS}/system/dr/postgres/${BACKUP1}/manifest.json"
DUMP1="${OBJECTS}/system/dr/postgres/${BACKUP1}/database.dump"
check "manifest.json existe" "sim" "$( [ -f "${MANIFEST1}" ] && echo sim || echo não )"
check "database.dump existe e não é vazio" "sim" "$( [ -s "${DUMP1}" ] && echo sim || echo não )"
check "sha256 do manifesto == sha256 do dump" "$(sha256sum "${DUMP1}" | cut -d' ' -f1)" "$(jq -r .sha256 "${MANIFEST1}")"
check "dumpSizeBytes == tamanho real" "$(stat -c %s "${DUMP1}")" "$(jq -r .dumpSizeBytes "${MANIFEST1}")"
check "formato custom declarado" "pg_dump-custom" "$(jq -r .dumpFormat "${MANIFEST1}")"
check "o manifesto não contém a connection string" "não" "$(grep -q 'postgresql://' "${MANIFEST1}" && echo sim || echo não)"
check "o manifesto não contém a senha" "não" "$(grep -q 'spark:spark' "${MANIFEST1}" && echo sim || echo não)"
SCHEMA_VERSION="$(jq -r .schema.schemaVersion "${MANIFEST1}")"
check "schemaVersion do manifesto == MAX(version) de produção" "$(psql_prod 'SELECT MAX(version) FROM schema_migrations')" "${SCHEMA_VERSION}"

# ================================================================ 3. o desastre — e o schema expandido depois do snapshot
log "destruindo o dado em 'produção' e criando uma tabela que o snapshot não conhece"
psql_prod "DELETE FROM social_profiles WHERE owner_uid = '${UID_SEED}'" > /dev/null
psql_prod "DELETE FROM server_metadata WHERE key = 'dr_drill_marker'" > /dev/null
psql_prod "CREATE TABLE future_migration_table (id INT PRIMARY KEY)" > /dev/null
check "produção não tem mais o perfil semeado" "0" "$(psql_prod "SELECT COUNT(*) FROM social_profiles WHERE owner_uid = '${UID_SEED}'")"

# ================================================================ 4. restore em destino limpo
log "restore drill (db-restore-drill.js) em ${DRILL_DB}"
run_image SPARK_DRILL_ADMIN_URL "${ADMIN_URL}" \
  -e OBJECT_STORAGE_PROVIDER=local -e SOCIAL_MEDIA_ROOT=/objects \
  -e "SPARK_DRILL_DATABASE=${DRILL_DB}" -e SPARK_DRILL_KEEP_DATABASE=true -e LOG_LEVEL=warn \
  -- node dist/cli/db-restore-drill.js > "${WORK}/drill.out" 2> "${WORK}/drill.err" || true
if grep -q '^RESTORE_DRILL_PASS' "${WORK}/drill.out"; then
  DRILL_VERDICT=sim
else
  DRILL_VERDICT=não
  cat "${WORK}/drill.err" >&2
fi
check "o ensaio termina em RESTORE_DRILL_PASS" "sim" "${DRILL_VERDICT}"
check "o ensaio restaurou o backup #1" "${BACKUP1}" "$(sed -n 's/^RESTORE_DRILL_PASS backupId=\([^ ]*\).*/\1/p' "${WORK}/drill.out")"
check "o dado semeado voltou no restaurado (perfil)" "1" "$(psql_drill "SELECT COUNT(*) FROM social_profiles WHERE owner_uid = '${UID_SEED}'")"
check "o dado semeado voltou no restaurado (metadata)" "presente-$$" "$(psql_drill "SELECT value FROM server_metadata WHERE key = 'dr_drill_marker'")"
check "a tabela criada DEPOIS do snapshot NÃO existe no restaurado (destino limpo)" "0" \
  "$(psql_drill "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'future_migration_table'")"
check "schema_migrations do restaurado == a do snapshot" "${SCHEMA_VERSION}" "$(psql_drill 'SELECT MAX(version) FROM schema_migrations')"
check "produção NÃO foi tocada pelo ensaio (o perfil continua apagado lá)" "0" "$(psql_prod "SELECT COUNT(*) FROM social_profiles WHERE owner_uid = '${UID_SEED}'")"
check "produção NÃO foi tocada pelo ensaio (a tabela nova continua lá)" "1" \
  "$(psql_prod "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'future_migration_table'")"

# ================================================================ 5. anti-ressurreição (§23)
#
# A conta semeada é "excluída" depois do backup: o tombstone vai para o ledger EXTERNO — que não
# faz parte do dump. Com o provider `local` o ledger é o arquivo `deletion_tombstones.tsv`
# (`FileDeletionTombstoneLedger`); com `gcs` é um objeto em `system/deletion-tombstones/<hmac>` —
# a mesma propriedade, provada com o ledger de Object Storage em
# `backend/test/dr-anti-resurrection.spec.ts`. O banco restaurado trouxe a conta de volta; a
# reconciliação (o mesmo comando do runbook) precisa purgá-la de novo a partir do ledger.
log "anti-ressurreição: ledger externo + reconcile-account-deletions sobre o restaurado"
UID_HASH="$(printf '%s' "${UID_SEED}" | openssl dgst -sha256 -hmac "${SPARK_CI_ACCOUNT_DELETION_HMAC_KEY}" | sed 's/^.*= *//')"
LEDGER_DIR="${WORK}/data"
mkdir -p "${LEDGER_DIR}"
chmod 2770 "${LEDGER_DIR}"
printf '%s\t%s\n' "${UID_HASH}" "${NOW_MS}" > "${LEDGER_DIR}/deletion_tombstones.tsv"
chmod 660 "${LEDGER_DIR}/deletion_tombstones.tsv"

RECONCILE_OUT="$(ACCOUNT_DELETION_HMAC_KEY="${SPARK_CI_ACCOUNT_DELETION_HMAC_KEY}" run_image DATABASE_URL "${DRILL_URL}" \
  -v "${LEDGER_DIR}:/data" \
  -e NODE_ENV=production -e OBJECT_STORAGE_PROVIDER=local -e SOCIAL_MEDIA_ROOT=/objects \
  -e DELETION_TOMBSTONES_FILE_PATH=/data/deletion_tombstones.tsv \
  -e ACCOUNT_DELETION_HMAC_KEY -e DATABASE_MIGRATION_MODE=verify -e BACKGROUND_JOBS_MODE=disabled -e LOG_LEVEL=warn \
  -- node dist/cli/reconcile-account-deletions.js 2>&1)" || { printf '%s\n' "${RECONCILE_OUT}" >&2; fail "a reconciliação falhou"; }
check "a reconciliação purgou exatamente a conta do ledger" "sim" "$(printf '%s' "${RECONCILE_OUT}" | grep -q 'purgadas de novo): 1' && echo sim || echo não)"
check "o perfil ressuscitado foi purgado do restaurado" "0" "$(psql_drill "SELECT COUNT(*) FROM social_profiles WHERE owner_uid = '${UID_SEED}'")"
check "o tombstone voltou à tabela do restaurado (o guard bloqueia a conta)" "1" "$(psql_drill "SELECT COUNT(*) FROM account_deletion_tombstones WHERE uid_hash = '${UID_HASH}'")"

# ================================================================ 6. a aplicação real sobre o restaurado
log "subindo o backend real sobre ${DRILL_DB} (porta ${APP_PORT})"
DATABASE_URL="${DRILL_URL}" ACCOUNT_DELETION_HMAC_KEY="${SPARK_CI_ACCOUNT_DELETION_HMAC_KEY}" \
  docker run -d --name "${APP_CONTAINER}" --network host \
  --group-add "${OBJECTS_GID}" -v "${OBJECTS}:/objects" \
  -e "PORT=${APP_PORT}" -e DATABASE_URL -e DATABASE_MIGRATION_MODE=verify -e NODE_ENV=production \
  -e OBJECT_STORAGE_PROVIDER=local -e SOCIAL_MEDIA_ROOT=/objects -e BACKGROUND_JOBS_MODE=disabled \
  -e ACCOUNT_DELETION_HMAC_KEY -e LOG_LEVEL=warn \
  "${SPARK_IMAGE}" > /dev/null
READY=0
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${APP_PORT}/health/ready" 2> /dev/null | grep -q '"status":"ok"'; then READY=1; break; fi
  sleep 2
done
[ "${READY}" -eq 1 ] || { docker logs "${APP_CONTAINER}" >&2 || true; fail "o backend não ficou ready sobre o restaurado"; }
check "/health/ready 200 sobre o restaurado" "sim" "sim"
for path in /v1/auth/me /v1/backups /v1/sync/pull /v1/social/me; do
  check "${path} fechado (401) sobre o restaurado" "401" "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${APP_PORT}${path}")"
done
docker rm -f "${APP_CONTAINER}" > /dev/null 2>&1 || true

# ================================================================ 7. retenção
log "retenção: dois backups a mais com SPARK_DR_RETENTION_COUNT=2"
sleep 1; backup_now 2 > "${WORK}/backup2.out"
sleep 1; backup_now 2 > "${WORK}/backup3.out"
# O provider local remove os OBJETOS (manifesto e dump); o diretório vazio que sobra não é um
# backup — é o que conta manifestos, não pastas.
count_backups() { find "${OBJECTS}/system/dr/postgres" -mindepth 2 -maxdepth 2 -name manifest.json | wc -l | tr -d ' '; }
check "só os 2 backups válidos mais recentes ficam" "2" "$(count_backups)"
check "o backup #1 (o mais antigo) foi removido pela retenção (manifesto)" "não" "$( [ -f "${MANIFEST1}" ] && echo sim || echo não )"
check "o backup #1 (o mais antigo) foi removido pela retenção (dump)" "não" "$( [ -f "${DUMP1}" ] && echo sim || echo não )"
check "o backup #3 registrou removed=1" "sim" "$(grep -q 'removed=1' "${WORK}/backup3.out" && echo sim || echo não)"

# ================================================================ 8. falha fechada
log "falha fechada: backup com banco inalcançável"
BEFORE_COUNT="$(count_backups)"
if run_image DATABASE_URL_DIRECT "postgresql://spark:spark@127.0.0.1:1/spark_dev" \
  -e OBJECT_STORAGE_PROVIDER=local -e SOCIAL_MEDIA_ROOT=/objects -e LOG_LEVEL=warn \
  -- node dist/cli/db-backup.js > "${WORK}/backup-fail.out" 2>&1; then
  check "backup com banco inalcançável falha (código != 0)" "falha" "sucesso"
else
  check "backup com banco inalcançável falha (código != 0)" "falha" "falha"
fi
check "nenhum backup novo apareceu depois da falha" "${BEFORE_COUNT}" "$(count_backups)"
check "a saída da falha não contém a senha" "não" "$(grep -q 'spark:spark' "${WORK}/backup-fail.out" && echo sim || echo não)"

# ================================================================ 9. produção sem o resíduo do ensaio
psql_prod "DROP TABLE IF EXISTS future_migration_table" > /dev/null

echo
if [ "${failures}" -gt 0 ]; then
  printf '=== %d verificação(ões) falharam ===\n' "${failures}" >&2
  exit 1
fi
echo "=== DR_DRILL_PASS: backup real → destino limpo → dado de volta, schema novo ausente, conta excluída não ressuscita, aplicação ready, retenção e falha fechada ==="
