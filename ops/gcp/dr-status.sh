#!/usr/bin/env bash
# "Existe backup recente? Consigo restaurá-lo? A manutenção parou?" — as respostas, em um comando
# (T18.3 §12/§15). Somente leitura.
#
#   ops/gcp/dr-status.sh                 # backups de DR no bucket + heartbeat do maintenance
#   ops/gcp/dr-status.sh --backups-only  # só o bucket (sem identity token, sem Cloud Run)
#
# Sai com 0 quando há backup válido dentro da janela E o maintenance não está stale; 1 caso
# contrário — para caber num cron/alerta externo além do Cloud Monitoring.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"
# shellcheck source=ops/gcp/lib.dr.sh
. "${SCRIPT_DIR}/lib.dr.sh"

require_cmd gcloud
require_cmd jq

BACKUPS_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --backups-only) BACKUPS_ONLY=1; shift ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

problems=0
MAX_AGE_SECONDS=$(( SPARK_DR_MAX_BACKUP_AGE_HOURS * 3600 ))

log "=== backups de DR em gs://${SPARK_GCS_BUCKET}/${SPARK_DR_PREFIX} ==="
valid=0
invalid=0
while IFS= read -r backup_id; do
  [ -n "${backup_id}" ] || continue
  if created="$(dr_backup_is_valid "${backup_id}")"; then
    valid=$((valid + 1))
    manifest="$(dr_manifest_json "${backup_id}")"
    log "  válido    ${backup_id}  idade=$(dr_age_seconds "${created}")s  bytes=$(printf '%s' "${manifest}" | jq -r .dumpSizeBytes)  schema=v$(printf '%s' "${manifest}" | jq -r .schema.schemaVersion)  commit=$(printf '%s' "${manifest}" | jq -r '.gitCommit // "-"')"
  else
    invalid=$((invalid + 1))
    log "  INVÁLIDO  ${backup_id}  (manifesto ausente/ilegível, dump ausente ou tamanho divergente)"
  fi
done < <(dr_backup_ids)

if latest="$(dr_latest_valid_backup)"; then
  age="$(dr_age_seconds "${latest##* }")"
  if [ "${age}" -le "${MAX_AGE_SECONDS}" ]; then
    log "backup mais recente válido: ${latest%% *} (${age}s) — dentro da janela de ${SPARK_DR_MAX_BACKUP_AGE_HOURS}h: OK"
  else
    log "backup mais recente válido: ${latest%% *} (${age}s) — ACIMA da janela de ${SPARK_DR_MAX_BACKUP_AGE_HOURS}h: STALE"
    problems=$((problems + 1))
  fi
else
  log "NENHUM backup válido: rode ops/gcp/dr-backup-now.sh"
  problems=$((problems + 1))
fi
log "válidos=${valid} inválidos=${invalid} retenção=${SPARK_DR_RETENTION_COUNT}"

if [ "${BACKUPS_ONLY}" -eq 0 ]; then
  log "=== heartbeat de ${SPARK_RUN_MAINTENANCE_SERVICE} (GET /internal/maintenance/status) ==="
  MAINTENANCE_URL="$(gcloud run services describe "${SPARK_RUN_MAINTENANCE_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
    --format='value(status.url)' 2> /dev/null || true)"
  if [ -z "${MAINTENANCE_URL}" ]; then
    log "  serviço não encontrado — NOT_VERIFIED"
    problems=$((problems + 1))
  else
    # O serviço é privado: o identity token do operador vai no header, e nunca é impresso.
    STATUS_JSON="$(curl -fsS --max-time 20 \
      -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
      "${MAINTENANCE_URL}/internal/maintenance/status" 2> /dev/null || true)"
    if [ -z "${STATUS_JSON}" ]; then
      log "  sem resposta do status (IAM run.invoker do operador? serviço fora?) — NOT_VERIFIED"
      problems=$((problems + 1))
    else
      log "  stale=$(printf '%s' "${STATUS_JSON}" | jq -r .stale) ageMs=$(printf '%s' "${STATUS_JSON}" | jq -r '.ageMs // "-"') lastDurationMs=$(printf '%s' "${STATUS_JSON}" | jq -r '.lastDurationMs // "-"') lastErrorName=$(printf '%s' "${STATUS_JSON}" | jq -r '.lastErrorName // "-"')"
      log "  databaseSize: $(printf '%s' "${STATUS_JSON}" | jq -c '.databaseSize // "não medido ainda"')"
      log "  drBackup:     $(printf '%s' "${STATUS_JSON}" | jq -c '.drBackup // "não verificado ainda"')"
      if [ "$(printf '%s' "${STATUS_JSON}" | jq -r .stale)" != "false" ]; then
        log "  MAINTENANCE STALE — confira o Cloud Scheduler (${SPARK_SCHEDULER_JOB}) e os logs"
        problems=$((problems + 1))
      fi
    fi
  fi
fi

if [ "${problems}" -gt 0 ]; then
  fail "dr-status: ${problems} problema(s)"
fi
log "dr-status: OK"
