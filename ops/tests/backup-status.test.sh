#!/usr/bin/env bash
#
# Todo desfecho do backup chega ao estado operacional (T16.8.1 §9).
#
#   sucesso  ──▶ outcome=SUCCESS + lastSuccessfulBackup* atualizados
#   falha    ──▶ outcome=FAILURE + lastSuccessfulBackup* PRESERVADOS + exit != 0
#
# ## Por que este teste existe
#
# `ops/check-health.sh` responde "o último backup funcionou?" lendo `backup-status.json`. Se um
# caminho de falha não escrever ali, o arquivo continua dizendo `SUCCESS` — e o monitoramento passa
# a afirmar que existe backup quando não existe. Isso é pior que não ter backup nenhum, porque
# produz confiança.
#
# O defeito real que este teste fecha: `ops/backup.sh` usava `trap ... ERR`, e `fail()` faz `exit`.
# `exit` **não** dispara o trap de ERR, então credencial ausente, restic recusado e `snapshot_id`
# vazio saíam com código diferente de zero deixando o estado anterior intacto.
#
# ## O que ele NÃO usa
#
# Nenhuma credencial de storage real, nenhum repositório restic real, nenhuma VPS (§163/§164). O
# restic é substituído por um dublê via `SPARK_RESTIC_CMD` — que é o mesmo mecanismo de indireção
# que existe para rodar o restic em container, e não um seam criado para teste.
#
# Uso:  SPARK_IMAGE=... SPARK_TEST_DATABASE_URL=postgresql://... ops/tests/backup-status.test.sh
#
# O banco é o PostgreSQL de `SPARK_TEST_DATABASE_URL` (T18.0.2), já migrado — o snapshot exige
# `schema_migrations`. O "banco indisponível" do desfecho [3/7] é um endereço em que nada escuta.

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${SPARK_IMAGE:?SPARK_IMAGE é obrigatório}"
: "${SPARK_TEST_DATABASE_URL:?SPARK_TEST_DATABASE_URL é obrigatório (PostgreSQL migrado, alcançável pela rede do host)}"

ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT" 2> /dev/null || true' EXIT

STATE="${ROOT}/state"
STAGING="${ROOT}/backups"
# O diretório de dados só carrega o ledger de exclusões desde a T18.0; para este teste ele pode
# estar vazio (um servidor onde ninguém excluiu conta ainda), e isso não é erro.
SOURCE_DATA_DIR="${ROOT}/data"
mkdir -p "$STATE" "$STAGING" "$SOURCE_DATA_DIR"

# Um endereço em que nenhum PostgreSQL escuta: `pg_dump` falha ao conectar, que é o mesmo caminho
# de um banco fora do ar, de credencial revogada ou de firewall fechado.
UNREACHABLE_DATABASE_URL="postgresql://spark:spark@127.0.0.1:1/indisponivel"

STATUS_FILE="${STATE}/backup-status.json"

# Um endereço de repositório e uma senha reconhecíveis: o teste procura os dois **dentro** do
# arquivo de estado, porque uma mensagem de erro que carregasse a saída do restic vazaria ambos.
FAKE_REPOSITORY="s3:https://storage.exemplo.invalid/spark-SEGREDO-REPOSITORIO"
FAKE_PASSWORD="SENHA-RESTIC-QUE-NAO-PODE-VAZAR"

# --- dublê do restic --------------------------------------------------------------------------
#
# Comportamento por `FAKE_RESTIC_MODE`, e sempre imprimindo o endereço do repositório e a senha na
# saída — como o restic real faria ao reclamar de credencial. É isso que torna verificável a regra
# "o estado não carrega segredo".
cat > "${ROOT}/fake-restic" <<'FAKE'
#!/usr/bin/env bash
command="${1:-}"
printf 'restic (dublê): comando=%s repositorio=%s senha=%s\n' \
  "$command" "${RESTIC_REPOSITORY:-}" "${RESTIC_PASSWORD:-}" >&2
case "${FAKE_RESTIC_MODE:-ok}" in
  falha-backup)
    [ "$command" = "backup" ] && { echo "Fatal: unable to open repository ${RESTIC_REPOSITORY}" >&2; exit 1; }
    ;;
  falha-forget)
    [ "$command" = "forget" ] && { echo "Fatal: prune failed on ${RESTIC_REPOSITORY}" >&2; exit 1; }
    ;;
  sem-snapshot-id)
    [ "$command" = "backup" ] && { echo '{"message_type":"summary"}'; exit 0; }
    ;;
esac
[ "$command" = "backup" ] && echo '{"message_type":"summary","snapshot_id":"abc123def456"}'
exit 0
FAKE
chmod +x "${ROOT}/fake-restic"

# --- utilidades do teste ----------------------------------------------------------------------

field() { sed -n "s/.*\"$1\": \"\([^\"]*\)\".*/\1/p" "$STATUS_FILE" | head -1; }
number_field() { sed -n "s/.*\"$1\": \([0-9]*\).*/\1/p" "$STATUS_FILE" | head -1; }

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

# Roda o backup e devolve o código de saída, sem deixar `set -e` derrubar o teste.
run_backup() {
  local database_url="$1" staging="$2" mode="$3" repository="$4"
  set +e
  env \
    SPARK_DATA_DIR="$SOURCE_DATA_DIR" \
    SPARK_DATABASE_URL="$database_url" \
    SPARK_STAGING_DIR="$staging" \
    SPARK_STATE_DIR="$STATE" \
    SPARK_COMPOSE_DIR="${ROOT}/sem-compose" \
    SPARK_IMAGE="$SPARK_IMAGE" \
    SPARK_BACKUP_ENV_FILE=/dev/null \
    SPARK_RESTIC_CMD="${ROOT}/fake-restic" \
    FAKE_RESTIC_MODE="$mode" \
    RESTIC_REPOSITORY="$repository" \
    RESTIC_PASSWORD="$FAKE_PASSWORD" \
    "${OPS_DIR}/backup.sh" --tag teste > /dev/null 2>&1
  local code=$?
  set -e
  printf '%s' "$code"
}

echo "=== estado do backup em todos os desfechos ==="

# --- 1. sucesso, que é a linha de base ---------------------------------------------------------
echo "[1/7] sucesso"
code="$(run_backup "$SPARK_TEST_DATABASE_URL" "$STAGING" ok "$FAKE_REPOSITORY")"
check "sai com 0"                    "0"         "$code"
check "outcome"                      "SUCCESS"   "$(field outcome)"
check "snapshotId do restic"         "abc123def456" "$(field snapshotId)"
if [ -n "$(field lastSuccessfulBackupAt)" ]; then
  check "registra o último sucesso" "sim" "sim"
else
  check "registra o último sucesso" "sim" "não"
fi

SUCESSO_AT="$(field lastSuccessfulBackupAt)"
SUCESSO_EPOCH="$(number_field lastSuccessfulBackupEpoch)"

# A partir daqui, TODA falha precisa preservar estes dois valores: `check-health.sh` mede a idade
# do último sucesso, e zerá-la faria uma falha de hoje parecer "nunca houve backup".
expect_failure() {
  local descricao="$1" code="$2" etapa="$3"
  check "${descricao}: sai com código diferente de zero" "sim" \
    "$( [ "$code" != "0" ] && echo sim || echo não )"
  check "${descricao}: outcome"                          "FAILURE" "$(field outcome)"
  check "${descricao}: preserva o último sucesso (data)"  "$SUCESSO_AT"    "$(field lastSuccessfulBackupAt)"
  check "${descricao}: preserva o último sucesso (epoch)" "$SUCESSO_EPOCH" "$(number_field lastSuccessfulBackupEpoch)"
  check "${descricao}: aponta a etapa"                   "sim" \
    "$( grep -q "etapa '${etapa}'" "$STATUS_FILE" && echo sim || echo não )"
  check "${descricao}: não grava snapshotId"             ""  "$(field snapshotId)"
}

# --- 2. credencial de storage ausente ----------------------------------------------------------
echo "[2/7] credencial ausente"
code="$(run_backup "$SPARK_TEST_DATABASE_URL" "$STAGING" ok "")"
expect_failure "credencial ausente" "$code" "config"

# --- 3. falha do snapshot ----------------------------------------------------------------------
#
# Um PostgreSQL que não responde: `pg_dump` não conecta e não tem o que copiar. É o mesmo caminho
# de falha de um banco fora do ar, de uma credencial revogada ou de um firewall fechado.
echo "[3/7] snapshot"
code="$(run_backup "$UNREACHABLE_DATABASE_URL" "$STAGING" ok "$FAKE_REPOSITORY")"
expect_failure "snapshot" "$code" "snapshot"

# --- 4. falha do envio off-site ----------------------------------------------------------------
echo "[4/7] envio off-site"
code="$(run_backup "$SPARK_TEST_DATABASE_URL" "$STAGING" falha-backup "$FAKE_REPOSITORY")"
expect_failure "envio off-site" "$code" "offsite-upload"

# --- 5. restic aceita mas não devolve snapshot -------------------------------------------------
#
# Sai com 0 e não identifica o snapshot: sem esta verificação, o backup seria dado como bem-sucedido
# sem que exista prova de que algo chegou ao storage.
echo "[5/7] restic sem snapshot_id"
code="$(run_backup "$SPARK_TEST_DATABASE_URL" "$STAGING" sem-snapshot-id "$FAKE_REPOSITORY")"
expect_failure "restic sem snapshot_id" "$code" "offsite-upload"

# --- 6. falha da retenção ----------------------------------------------------------------------
#
# O snapshot subiu, mas o repositório está crescendo sem limite. É falha: precisa aparecer agora,
# e não quando o storage encher.
echo "[6/7] retenção"
code="$(run_backup "$SPARK_TEST_DATABASE_URL" "$STAGING" falha-forget "$FAKE_REPOSITORY")"
expect_failure "retenção" "$code" "offsite-retention"

# --- 7. disco/área de trabalho indisponível ----------------------------------------------------
#
# A simulação de disco: a área de staging existe e o backup não consegue escrever nela. É o que uma
# partição cheia, remontada somente-leitura ou com permissão danificada produz — e disco cheio é o
# modo de falha mais provável desta VPS (§74).
#
# O diretório precisa pertencer a **outro** usuário, e não apenas estar em modo 500: o próprio
# `backup.sh` faz `chmod 700` na área de staging ao começar, então uma simulação feita sobre um
# diretório do próprio usuário seria desfeita pelo script antes de ele tentar escrever. (Essa
# primeira versão do teste passou "por acaso", registrando SUCCESS — exatamente o tipo de teste
# que dá confiança sem verificar nada.) O `chown` sai do Docker, que já é dependência do backup.
echo "[7/7] área de trabalho sem escrita"
READONLY_STAGING="${ROOT}/staging-somente-leitura"
mkdir -p "$READONLY_STAGING"
docker run --rm --user 0 -v "${READONLY_STAGING}:/alvo" --entrypoint sh "$SPARK_IMAGE" -c   'chown 0:0 /alvo && chmod 500 /alvo' > /dev/null

code="$(run_backup "$SPARK_TEST_DATABASE_URL" "$READONLY_STAGING" ok "$FAKE_REPOSITORY")"
expect_failure "área de trabalho" "$code" "workdir"

# Devolvido ao dono original para que a limpeza do teste consiga removê-lo.
docker run --rm --user 0 -v "${READONLY_STAGING}:/alvo" --entrypoint sh "$SPARK_IMAGE" -c   "chown -R $(id -u):$(id -g) /alvo && chmod 700 /alvo" > /dev/null

# --- segredo nenhum no estado ------------------------------------------------------------------
#
# O dublê imprime o endereço do repositório e a senha em toda execução, como o restic real faria ao
# reclamar. Um `write_status` que registrasse a saída do comando que falhou vazaria os dois para um
# arquivo que o operador abre durante o diagnóstico.
echo "[segredos] o estado não carrega credencial"
check "não contém a senha do restic"      "não" \
  "$( grep -q "$FAKE_PASSWORD" "$STATUS_FILE" && echo sim || echo não )"
check "não contém o endereço do repositório" "não" \
  "$( grep -q 'SEGREDO-REPOSITORIO' "$STATUS_FILE" && echo sim || echo não )"
check "não contém cabeçalho de autorização" "não" \
  "$( grep -qi 'authorization' "$STATUS_FILE" && echo sim || echo não )"

# --- o estado continua sendo JSON legível ------------------------------------------------------
if node -e "
  const state = require('node:fs').readFileSync(process.argv[1], 'utf8');
  const parsed = JSON.parse(state);
  if (parsed.outcome !== 'FAILURE') { console.error('outcome inesperado'); process.exit(1); }
" "$STATUS_FILE"; then
  check "o estado é JSON válido" "sim" "sim"
else
  check "o estado é JSON válido" "sim" "não"
fi

echo
if [ "$failures" -gt 0 ]; then
  printf '=== %d verificação(ões) falharam ===\n' "$failures" >&2
  echo "último estado:" >&2
  cat "$STATUS_FILE" >&2
  exit 1
fi
echo "=== todos os desfechos do backup chegam ao estado operacional ==="
