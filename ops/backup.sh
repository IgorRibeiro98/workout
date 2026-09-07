#!/usr/bin/env bash
#
# Backup off-site do SQLite do servidor (T16.8 §26–§42).
#
#   spark.db ──▶ snapshot consistente ──▶ integrity_check ──▶ manifesto
#            ──▶ restic (compressão + criptografia) ──▶ storage off-site ──▶ retenção ──▶ estado
#
# ## O que este backup NÃO é
#
# Ele não é o backup da T16.4. Aquele protege o **usuário** contra perder o aparelho: o dado dele
# passa a existir no servidor. Este protege a **infraestrutura** contra perder a VPS: o banco do
# servidor — que contém os backups de todo mundo, o estado de sync, o change log e os tombstones —
# passa a existir fora dela. Confundir os dois dá uma falsa sensação de segurança nos dois sentidos.
#
# ## Off-site é obrigatório
#
# Uma cópia em `/opt/spark/backups` na mesma VPS não é backup: o disco que morre leva os dois. O
# destino é `RESTIC_REPOSITORY`, e o restic o criptografa **antes** de enviar — o provedor de
# armazenamento nunca vê dado pessoal em claro (§147).
#
# Uso:  ops/backup.sh [--tag <rótulo>]
#       ops/backup.sh --tag pre-deploy      (usado por ops/deploy.sh antes de migration)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file

TAG="scheduled"
while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="${2:?--tag exige um valor}"; shift 2 ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

# Política de retenção (§37). Números pequenos e deliberados: o banco do Spark é de um grupo
# pequeno, e retenção infinita é custo sem benefício. A janela combinada — uma semana de diários,
# um mês de semanais, um ano de mensais — cobre tanto o erro percebido no dia seguinte quanto o
# percebido meses depois.
KEEP_DAILY="${SPARK_BACKUP_KEEP_DAILY:-7}"
KEEP_WEEKLY="${SPARK_BACKUP_KEEP_WEEKLY:-4}"
KEEP_MONTHLY="${SPARK_BACKUP_KEEP_MONTHLY:-12}"

STATUS_FILE="${SPARK_STATE_DIR}/backup-status.json"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
STARTED_EPOCH="$(date -u +%s)"

mkdir -p "$SPARK_STATE_DIR" "$SPARK_STAGING_DIR"
chmod 700 "$SPARK_STATE_DIR" "$SPARK_STAGING_DIR" 2> /dev/null || true

# Estado legível por máquina (§39/§137): início, desfecho, duração, tamanho e identificador do
# snapshot. **Nunca conteúdo** — nem nome de conta, nem uid, nem nada do banco.
write_status() {
  local outcome="$1" snapshot_id="${2:-}" size_bytes="${3:-0}" message="${4:-}"
  local finished_epoch; finished_epoch="$(date -u +%s)"
  local tmp="${STATUS_FILE}.tmp"
  cat > "$tmp" <<JSON
{
  "startedAt": "${STARTED_AT}",
  "finishedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "durationSeconds": $((finished_epoch - STARTED_EPOCH)),
  "outcome": "${outcome}",
  "tag": "${TAG}",
  "snapshotId": "${snapshot_id}",
  "sizeBytes": ${size_bytes},
  "message": "${message}",
  "lastSuccessfulBackupAt": "${LAST_SUCCESS_AT:-}",
  "lastSuccessfulBackupEpoch": ${LAST_SUCCESS_EPOCH:-0}
}
JSON
  mv "$tmp" "$STATUS_FILE"
  chmod 600 "$STATUS_FILE"
}

# Um sucesso anterior não pode ser apagado por uma falha de hoje: `ops/check-health.sh` mede a
# **idade do último sucesso**, e zerá-la faria uma falha parecer "nunca houve backup".
LAST_SUCCESS_AT=""
LAST_SUCCESS_EPOCH=0
if [ -f "$STATUS_FILE" ]; then
  LAST_SUCCESS_AT="$(sed -n 's/.*"lastSuccessfulBackupAt": "\([^"]*\)".*/\1/p' "$STATUS_FILE" | head -1)"
  LAST_SUCCESS_EPOCH="$(sed -n 's/.*"lastSuccessfulBackupEpoch": \([0-9]*\).*/\1/p' "$STATUS_FILE" | head -1)"
  LAST_SUCCESS_EPOCH="${LAST_SUCCESS_EPOCH:-0}"
fi

on_failure() {
  local code=$?
  # Falha silenciosa em cron é como não ter backup, e pior: dá confiança (§40). O estado fica
  # gravado, a saída vai para stderr, e o código de saída é diferente de zero — que é o que o
  # systemd e o `OnFailure=` enxergam.
  write_status "FAILURE" "" 0 "backup falhou com código ${code}"
  printf 'ERRO: o backup do Spark FALHOU (tag=%s). Estado em %s\n' "$TAG" "$STATUS_FILE" >&2
  exit "$code"
}
trap on_failure ERR

acquire_lock
require_cmd docker
require_restic_env

WORK_DIR="$(mktemp -d "${SPARK_STAGING_DIR}/work-XXXXXX")"
chmod 700 "$WORK_DIR"
cleanup_work() { rm -rf "$WORK_DIR"; rm -f "${RESTIC_OUTPUT_FILE:-}"; }
trap 'cleanup_work' EXIT

# --- 1. snapshot consistente + integridade ------------------------------------------------
SNAPSHOT="${WORK_DIR}/${DB_FILENAME}"
"${SCRIPT_DIR}/snapshot.sh" "$SNAPSHOT" > /dev/null
SIZE_BYTES="$(stat -c %s "$SNAPSHOT")"

# --- 2. manifesto -------------------------------------------------------------------------
#
# O que acompanha o banco (§34): versão do schema, imagem que estava no ar, tamanho e horário. É
# o que permite, meses depois, saber com qual versão do backend aquele arquivo foi escrito.
#
# O que **não** acompanha: nenhum segredo (§35). Service account, chave do Gemini e senha do
# repositório não entram aqui nem no snapshot — eles vivem fora do banco por construção.
SCHEMA_VERSION="$(sqlite_node "
  const Database = require('better-sqlite3');
  const db = new Database('/data/${DB_FILENAME}', { readonly: true, fileMustExist: true });
  console.log(db.prepare('SELECT MAX(version) AS v FROM schema_migrations').get().v);
  db.close();
" | tr -d '\r\n')"

IMAGE_REF="$( cd "$SPARK_COMPOSE_DIR" 2> /dev/null && \
  docker compose -f "$SPARK_COMPOSE_FILE" images -q "$SPARK_SERVICE" 2> /dev/null | head -1 || true )"

cat > "${WORK_DIR}/manifest.json" <<JSON
{
  "createdAt": "${STARTED_AT}",
  "tag": "${TAG}",
  "schemaVersion": ${SCHEMA_VERSION:-0},
  "databaseBytes": ${SIZE_BYTES},
  "backendImageId": "${IMAGE_REF}",
  "host": "$(hostname)"
}
JSON

# --- 3. off-site criptografado ------------------------------------------------------------
log "enviando para o repositório off-site"
# `--host spark` deixa a política de retenção estável mesmo se a VPS for recriada com outro
# hostname: sem isso, a nova máquina começaria uma linhagem separada de snapshots e o `forget`
# manteria as duas para sempre.
# A saída do restic é capturada, e não descartada: quando ele falha, a mensagem dele é a única
# coisa que diz **por quê** (repositório inexistente, credencial errada, storage fora do ar). Um
# backup que falha sem dizer o motivo custa horas na hora errada.
# Fora de `$WORK_DIR`: um arquivo criado ali enquanto o restic lê o diretório entraria no próprio
# snapshot, meio escrito.
RESTIC_OUTPUT_FILE="$(mktemp)"
if ! restic_cmd backup "$WORK_DIR" \
     --host spark \
     --tag "spark-db" --tag "$TAG" \
     --json > "$RESTIC_OUTPUT_FILE" 2>&1; then
  printf 'saída do restic:\n%s\n' "$(tail -30 "$RESTIC_OUTPUT_FILE")" >&2
  fail "o envio para o repositório off-site falhou"
fi

SNAPSHOT_ID="$(sed -n 's/.*"snapshot_id":"\([a-f0-9]*\)".*/\1/p' "$RESTIC_OUTPUT_FILE" | tail -1)"
[ -n "$SNAPSHOT_ID" ] || fail "restic não devolveu um snapshot_id"
rm -f "$RESTIC_OUTPUT_FILE"

# --- 4. retenção --------------------------------------------------------------------------
log "aplicando retenção (${KEEP_DAILY}d/${KEEP_WEEKLY}s/${KEEP_MONTHLY}m)"
# `--prune` só depois de o backup novo estar confirmado: uma limpeza que rode antes pode remover
# a última cópia útil (§133). Nesta ordem, uma falha aqui deixa backup **a mais**, nunca a menos.
restic_cmd forget \
  --host spark --tag "spark-db" \
  --keep-daily "$KEEP_DAILY" \
  --keep-weekly "$KEEP_WEEKLY" \
  --keep-monthly "$KEEP_MONTHLY" \
  --prune > /dev/null

# --- 5. estado ----------------------------------------------------------------------------
LAST_SUCCESS_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
LAST_SUCCESS_EPOCH="$(date -u +%s)"
trap - ERR
write_status "SUCCESS" "$SNAPSHOT_ID" "$SIZE_BYTES" ""

log "backup concluído: snapshot=${SNAPSHOT_ID} bytes=${SIZE_BYTES} tag=${TAG}"
