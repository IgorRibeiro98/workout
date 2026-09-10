#!/usr/bin/env bash
#
# Ensaio de restauração (T16.8 §43/§112/§161; PostgreSQL desde a T18.0.2).
#
#   backup off-site ──▶ restauração ──▶ pg_restore num banco DESCARTÁVEL ──▶ backend sobe sobre ele ──▶ ready
#                                    └─▶ mídia social restaurada e legível pelo processo
#
# ## Por que este script existe
#
# Um backup só está validado quando alguém o restaurou. "O job saiu com código 0", "o arquivo
# apareceu no storage" e "`pg_restore --list` leu o índice" não provam que o conteúdo restaura,
# que o schema está aplicado ou que a senha de criptografia que o operador tem é a que o
# repositório usa (§113). Este ensaio prova tudo isso de uma vez, e não toca em produção em momento
# nenhum.
#
# ## O banco descartável
#
# O dump é restaurado em `SPARK_DRILL_DATABASE_URL` — um banco **separado**, que o operador cria
# para isto (`CREATE DATABASE spark_drill`, ou um branch no Neon) e pode apagar depois. O script
# recusa rodar se ele for o mesmo banco de produção: um ensaio que sobrescrevesse produção seria o
# desastre que ele existe para prevenir. O conteúdo anterior do banco descartável é substituído
# (`--clean --if-exists`): ele é do ensaio, e só do ensaio.
#
# Sobe o backend real sobre o banco restaurado, em uma porta separada, e exige `/health/ready` —
# é o mesmo processo de produção lendo o mesmo conteúdo que uma recuperação de verdade produziria.
#
# Uso:
#   SPARK_DRILL_DATABASE_URL=postgresql://... ops/verify-backup.sh              # snapshot mais recente
#   SPARK_DRILL_DATABASE_URL=... ops/verify-backup.sh --snapshot <id>
#   SPARK_DRILL_DATABASE_URL=... ops/verify-backup.sh --from-file /caminho/spark.dump   # local, sem storage
#   SPARK_DRILL_DATABASE_URL=... ops/verify-backup.sh --from-file /caminho/spark.dump --media-from /caminho/media

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file
require_cmd docker

SNAPSHOT="latest"
FROM_FILE=""
FROM_MEDIA=""
PORT="${SPARK_DRILL_PORT:-18080}"
CONTAINER="spark-restore-drill-$$"

# A chave HMAC dos tombstones, para o container do ensaio (T17.13.1 §4).
#
# O ensaio sobe o backend em `NODE_ENV=production` de propósito — é a configuração real que
# precisa abrir o banco restaurado — e produção exige uma chave própria (T17.10 §136). Sem ela o
# container não subiria, e o ensaio acusaria "o backend não ficou ready sobre o banco restaurado":
# um backup perfeitamente válido reprovado por um erro de configuração do próprio ensaio.
#
# O default é **sintético e do ensaio**, e nunca de produção. Ele não precisa casar com a chave
# real: nada aqui calcula ou compara tombstone — o que se verifica é que o banco abre, as
# migrations aplicam e o `/health/ready` responde. O operador que quiser exercitar o ensaio com a
# chave real da VPS exporta `SPARK_ACCOUNT_DELETION_HMAC_KEY` antes de chamar este script.
: "${SPARK_ACCOUNT_DELETION_HMAC_KEY:=drill-only-account-deletion-hmac-not-for-production}"

while [ $# -gt 0 ]; do
  case "$1" in
    --snapshot)   SNAPSHOT="${2:?}"; shift 2 ;;
    --from-file)  FROM_FILE="${2:?}"; shift 2 ;;
    --media-from) FROM_MEDIA="${2:?}"; shift 2 ;;
    --port)       PORT="${2:?}"; shift 2 ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

DRILL_URL="${SPARK_DRILL_DATABASE_URL:-}"
[ -n "$DRILL_URL" ] || fail "SPARK_DRILL_DATABASE_URL é obrigatório: o banco DESCARTÁVEL onde o dump será restaurado"
# A defesa contra restaurar sobre produção (T18.0.3 P0). `same_postgres_database` não compara só a
# string: um endpoint Neon pooled e o direto do mesmo projeto são hosts diferentes com o mesmo
# `/spark` no fim — e essa checagem falha **antes** de qualquer `pg_restore --clean`, aqui, bem
# antes do primeiro container do ensaio subir.
if PRODUCTION_URL="$(spark_database_url)" && same_postgres_database "$PRODUCTION_URL" "$DRILL_URL"; then
  fail "SPARK_DRILL_DATABASE_URL aponta para o mesmo banco de produção (mesmo nome de banco, mesma URL, ou host pooled/direto do mesmo banco); o ensaio nunca restaura sobre produção"
fi

# Diretório de trabalho do ensaio. Configurável porque ele precisa ser um caminho **do host**
# quando o restic roda em container (`SPARK_RESTIC_CMD`): o alvo da restauração tem de estar
# montado, ou o restic escreve dentro de um container efêmero e o arquivo somem com ele.
DRILL_DIR="${SPARK_DRILL_DIR:-$(mktemp -d)}"
mkdir -p "$DRILL_DIR"
cleanup() {
  docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
  # A limpeza nunca pode transformar um ensaio aprovado em falha: quando o restic roda em
  # container, parte da árvore restaurada pode pertencer a outro usuário, e um `rm` que falha aí
  # não diz nada sobre a validade do backup. O resíduo é temporário e visível; o veredito é o que
  # importa.
  rm -rf "$DRILL_DIR" 2> /dev/null || log "aviso: não foi possível limpar ${DRILL_DIR}"
}
trap cleanup EXIT

log "=== ensaio de restauração ==="

# --- 1. restaurar o arquivo e verificar o índice --------------------------------------------
if [ -n "$FROM_FILE" ]; then
  MEDIA_ARGS=()
  [ -n "$FROM_MEDIA" ] && MEDIA_ARGS=(--media-from "$FROM_MEDIA")
  RESTORED="$("${SCRIPT_DIR}/restore.sh" --from-file "$FROM_FILE" "${MEDIA_ARGS[@]}" --to "${DRILL_DIR}/restored" | tail -1)"
else
  RESTORED="$("${SCRIPT_DIR}/restore.sh" --snapshot "$SNAPSHOT" --to "${DRILL_DIR}/restored" | tail -1)"
fi
[ -f "$RESTORED" ] || fail "a restauração não produziu um arquivo"

# A mídia restaurada, quando o snapshot a contém (T17.9 §138/§142). Procurada pelo subdiretório
# `checkins`, que é a estrutura que o provider `local` de Object Storage cria (preservada na
# T18.1) — e não pelo nome do diretório, que é configurável e pode ter mudado entre o backup e a
# restauração. Com o provider `gcs` o snapshot não tem mídia: ela vive no bucket (T18.1).
RESTORED_MEDIA=""
MEDIA_ROOT_MARKER="$(find "${DRILL_DIR}/restored" -type d -name checkins 2> /dev/null | head -1)"
[ -n "$MEDIA_ROOT_MARKER" ] && RESTORED_MEDIA="$(dirname "$MEDIA_ROOT_MARKER")"

# --- 2. restaurar o conteúdo no banco descartável ------------------------------------------
#
# As mesmas opções de `restore.sh --install`: é a restauração de verdade, só que no banco do
# ensaio. Uma opção que só existisse aqui faria o ensaio provar um caminho que a produção não usa.
RESTORED_DIR="$(cd "$(dirname "$RESTORED")" && pwd)"
RESTORED_NAME="$(basename "$RESTORED")"
log "restaurando o dump no banco descartável (transação única)"
pg_run "$DRILL_URL" "
  set -e
  pg_restore -d \"\$SPARK_PG_CONN\" --clean --if-exists --no-owner --no-privileges \\
    --single-transaction --exit-on-error '/restore/${RESTORED_NAME}'
" -v "${RESTORED_DIR}:/restore:ro" \
  || fail "pg_restore falhou no banco descartável: o dump não restaura"

# O que um dump do Spark precisa ter depois de restaurado: o histórico de migrations e a tabela
# de metadata do servidor. Contagens, nunca conteúdo (§161).
RESTORED_STATE="$(pg_run "$DRILL_URL" "
  psql -d \"\$SPARK_PG_CONN\" -X -q -t -A -v ON_ERROR_STOP=1 -c \"
    SELECT 'schema_version=' || COALESCE(MAX(version), 0) || ' migrations=' || COUNT(*) FROM schema_migrations
  \"
  psql -d \"\$SPARK_PG_CONN\" -X -q -t -A -v ON_ERROR_STOP=1 -c \"
    SELECT 'server_metadata_rows=' || COUNT(*) FROM server_metadata
  \"
" | tr '\n' ' ')" || fail "o banco restaurado não tem schema_migrations/server_metadata"
log "conteúdo restaurado: ${RESTORED_STATE}"
printf '%s' "$RESTORED_STATE" | grep -Eq 'migrations=[1-9]' \
  || fail "o banco restaurado não tem nenhuma migration registrada"

# --- 3. subir o backend real sobre o banco restaurado ---------------------------------------
#
# A mídia entra no ensaio com o **mesmo modelo de permissão da produção** (T16.8.1 §3, T17.9
# §142), e não `777`/`666`: `2770` (setgid) no diretório, `660` nos arquivos, dono é quem roda o
# script, e o container entra no grupo por `--group-add`. O ponto do ensaio não é só "os arquivos
# vieram": é que o processo que roda como `node`, com uid diferente do operador, consegue
# **abri-los** depois da restauração. Um `chmod 777` aqui provaria apenas que o container existe.
DRILL_GID="$(id -g)"
MEDIA_DIR="${DRILL_DIR}/media"
mkdir -p "$MEDIA_DIR"
chmod 2770 "$MEDIA_DIR"
MEDIA_FILE_COUNT=0
if [ -n "$RESTORED_MEDIA" ]; then
  cp -a "${RESTORED_MEDIA}/." "$MEDIA_DIR/"
  find "$MEDIA_DIR" -type d -exec chmod 2770 {} +
  find "$MEDIA_DIR" -type f -exec chmod 660 {} +
  MEDIA_FILE_COUNT="$(find "$MEDIA_DIR" -type f | wc -l | tr -d ' ')"
  log "mídia restaurada para o ensaio: ${MEDIA_FILE_COUNT} arquivo(s)"
else
  log "aviso: o snapshot não trouxe mídia; o ensaio verifica apenas o banco"
fi

# `--network host` com `PORT` explícita: o processo precisa alcançar exatamente o endereço de
# `SPARK_DRILL_DATABASE_URL` — que pode ser `127.0.0.1` numa máquina de desenvolvimento ou no CI.
log "subindo o backend sobre o banco restaurado (porta ${PORT}, grupo ${DRILL_GID}, uid do host $(id -u))"
# `-e DATABASE_URL` (sem valor) + `DATABASE_URL="$DRILL_URL"` no ambiente do comando (T18.0.3 P1):
# a connection string do banco descartável não passa pela linha de comando do host.
DATABASE_URL="$DRILL_URL" docker run -d --name "$CONTAINER" \
  --network host \
  --group-add "$DRILL_GID" \
  -v "${MEDIA_DIR}:/media" \
  -e "PORT=${PORT}" \
  -e DATABASE_URL \
  -e SOCIAL_MEDIA_ROOT=/media \
  -e NODE_ENV=production \
  -e ACCOUNT_DELETION_HMAC_KEY="$SPARK_ACCOUNT_DELETION_HMAC_KEY" \
  -e LOG_LEVEL=warn \
  "$SPARK_IMAGE" > /dev/null

READY=0
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/health/ready" 2> /dev/null | grep -q '"status":"ok"'; then
    READY=1; break
  fi
  sleep 2
done

if [ "$READY" -ne 1 ]; then
  docker logs "$CONTAINER" >&2 || true
  fail "o backend não ficou ready sobre o banco restaurado"
fi

curl -fsS "http://127.0.0.1:${PORT}/health/live" | grep -q '"status":"ok"' || fail "liveness falhou"

# As rotas de dado precisam continuar **fechadas** num banco restaurado: 401, e não 404 nem 200.
for path in /v1/backups /v1/sync/pull; do
  status="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}${path}")"
  [ "$status" = "401" ] || fail "rota ${path} respondeu ${status} no banco restaurado; esperado 401"
done

# --- 4. a mídia restaurada é legível **pelo processo** (T17.9 §142) --------------------------
#
# O container roda como `node` (uid 1000) e o operador tem outro uid: este passo é o que prova que
# o modelo de grupo compartilhado sobreviveu à restauração. Ler o byte a byte de um arquivo pelo
# processo é a única evidência que vale — "o arquivo está lá" não diz que ele abre.
if [ "$MEDIA_FILE_COUNT" -gt 0 ]; then
  docker exec "$CONTAINER" node -e "
    const fs = require('node:fs');
    const path = require('node:path');
    const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
      const full = path.join(dir, entry.name);
      return entry.isDirectory() ? walk(full) : [full];
    });
    const files = walk('/media');
    if (files.length === 0) { console.error('nenhum arquivo de mídia visível'); process.exit(1); }
    let bytes = 0;
    for (const file of files) { bytes += fs.readFileSync(file).length; }
    // Só formato e tamanho: nenhum caminho de arquivo vai para a saída (§161).
    const head = fs.readFileSync(files[0]).subarray(8, 12).toString('ascii');
    if (head !== 'WEBP') { console.error('a mídia restaurada não é WebP'); process.exit(1); }
    console.log('mídia legível pelo processo: ' + files.length + ' arquivo(s), ' + bytes + ' bytes');
  " || fail "o backend não conseguiu ler a mídia restaurada"
else
  log "aviso: sem mídia no snapshot, a verificação de leitura foi pulada"
fi

log "=== ensaio APROVADO: dump restaurado, backend ready, rotas protegidas, mídia legível ==="
