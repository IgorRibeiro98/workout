#!/usr/bin/env bash
# Executa o backup de DR agora e prova que ele existe (T18.3 §2/§3).
#
#   ops/gcp/dr-backup-now.sh
#
# Dispara o Cloud Run Job `spark-db-backup` (a mesma imagem e Service Account do agendamento
# diário), espera o desfecho, e confere pelo bucket — manifesto + dump com o tamanho declarado —
# que um backup válido NOVO apareceu. "O Job saiu com 0" não basta: só conta o que está no bucket.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"
# shellcheck source=ops/gcp/lib.dr.sh
. "${SCRIPT_DIR}/lib.dr.sh"

require_cmd gcloud
require_cmd jq

BEFORE="$(dr_latest_valid_backup || true)"
log "backup válido mais recente antes: ${BEFORE:-nenhum}"

log "executando ${SPARK_RUN_BACKUP_JOB} e aguardando"
dr_run_backup_job_and_wait || fail "o Job ${SPARK_RUN_BACKUP_JOB} FALHOU — veja: gcloud run jobs executions list --job ${SPARK_RUN_BACKUP_JOB} --region ${SPARK_GCP_REGION}"

AFTER="$(dr_latest_valid_backup || true)"
[ -n "${AFTER}" ] || fail "o Job terminou mas nenhum backup válido existe no bucket"
[ "${AFTER}" != "${BEFORE}" ] || fail "o Job terminou mas o backup válido mais recente não mudou (${AFTER%% *})"

AFTER_ID="${AFTER%% *}"
MANIFEST="$(dr_manifest_json "${AFTER_ID}")"
log "backup novo válido: ${AFTER_ID}"
log "  dump:    gs://${SPARK_GCS_BUCKET}/${SPARK_DR_PREFIX}${AFTER_ID}/database.dump ($(printf '%s' "${MANIFEST}" | jq -r .dumpSizeBytes) bytes)"
log "  sha256:  $(printf '%s' "${MANIFEST}" | jq -r .sha256)"
log "  schema:  v$(printf '%s' "${MANIFEST}" | jq -r .schema.schemaVersion)  postgres: $(printf '%s' "${MANIFEST}" | jq -r .postgresVersion | cut -c1-40)"
log "  commit:  $(printf '%s' "${MANIFEST}" | jq -r '.gitCommit // "-"')  digest: $(printf '%s' "${MANIFEST}" | jq -r '.imageDigest // "-"' | cut -c1-24)…"
log "DB_BACKUP_VERIFIED ${AFTER_ID}"
