#!/usr/bin/env bash
# Alertas operacionais do Spark no Cloud Monitoring (T18.3 §15). Idempotente.
#
#   SPARK_GCP_PROJECT=... SPARK_ALERT_EMAIL=ops@exemplo.com ops/gcp/monitoring-alerts.sh
#   SPARK_GCP_PROJECT=... ops/gcp/monitoring-alerts.sh --list        # o que existe (somente leitura)
#
# O que ele cria (ou atualiza, se já existir com o mesmo displayName):
#
#   canal      spark-ops-email (e-mail)                     ← SPARK_ALERT_EMAIL
#   métricas   log-based, uma por evento estruturado do backend (jsonPayload.event=...)
#   políticas  spark-run-5xx                 ≥ 5 respostas 5xx em 5 min na API
#              spark-run-revision-failed     revision do Cloud Run que não sobe
#              spark-job-migrate-failed      migration_failed OU task do Job com result=failed
#              spark-job-backup-failed       db_backup_failed OU task do Job com result=failed
#              spark-db-backup-stale         db_backup_stale (o maintenance mediu a idade do último válido)
#              spark-maintenance-stale       10 min sem maintenance_completed (o Scheduler parou, ou o serviço)
#              spark-maintenance-failed      maintenance_failed
#              spark-scheduler-failed        erro do Cloud Scheduler (HTTP != 2xx no alvo)
#              spark-db-size-critical        database_size_threshold_exceeded em PLAN/ACTION_REQUIRED
#              spark-restore-drill-failed    RESTORE_DRILL_FAIL registrado por dr-restore-drill.sh --record
#
# Pouco ruído de propósito (§15): contagens por 5 min, auto-close em 30 min, e nada dispara por um
# único 5xx. O que exige Console — verificar o e-mail do canal — está em OBSERVABILITY.md e é
# NOT VERIFIED até um alerta real chegar.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

require_cmd gcloud
require_cmd jq

LIST_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --list) LIST_ONLY=1; shift ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

if [ "${LIST_ONLY}" -eq 1 ]; then
  log "canais:"
  gcloud beta monitoring channels list --project "${SPARK_GCP_PROJECT}" --format='table(displayName,type,enabled)' >&2
  log "métricas log-based:"
  gcloud logging metrics list --project "${SPARK_GCP_PROJECT}" --filter='name:spark_' --format='table(name,filter)' >&2
  log "políticas:"
  gcloud alpha monitoring policies list --project "${SPARK_GCP_PROJECT}" --format='table(displayName,enabled,conditions[0].displayName)' >&2
  exit 0
fi

require_var SPARK_ALERT_EMAIL

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

# ---------------------------------------------------------------- canal de notificação
CHANNEL="$(gcloud beta monitoring channels list --project "${SPARK_GCP_PROJECT}" \
  --filter="type=\"email\" AND labels.email_address=\"${SPARK_ALERT_EMAIL}\"" \
  --format='value(name)' 2> /dev/null | head -1)"
if [ -z "${CHANNEL}" ]; then
  log "criando o canal de e-mail spark-ops-email → ${SPARK_ALERT_EMAIL}"
  CHANNEL="$(gcloud beta monitoring channels create --project "${SPARK_GCP_PROJECT}" \
    --display-name spark-ops-email --type email \
    --channel-labels "email_address=${SPARK_ALERT_EMAIL}" \
    --format='value(name)')"
else
  log "canal de e-mail já existe: ${CHANNEL}"
fi
[ -n "${CHANNEL}" ] || fail "não foi possível obter o canal de notificação"

# ---------------------------------------------------------------- métricas log-based
ensure_metric() {
  local name="$1" description="$2" filter="$3"
  if gcloud logging metrics describe "${name}" --project "${SPARK_GCP_PROJECT}" > /dev/null 2>&1; then
    gcloud logging metrics update "${name}" --project "${SPARK_GCP_PROJECT}" \
      --description "${description}" --log-filter "${filter}" > /dev/null
    log "métrica atualizada: ${name}"
  else
    gcloud logging metrics create "${name}" --project "${SPARK_GCP_PROJECT}" \
      --description "${description}" --log-filter "${filter}" > /dev/null
    log "métrica criada: ${name}"
  fi
}

MAINT_FILTER="resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"${SPARK_RUN_MAINTENANCE_SERVICE}\""
ensure_metric spark_db_backup_completed  "T18.3: backup de DR concluído"              'resource.type="cloud_run_job" AND jsonPayload.event="db_backup_completed"'
ensure_metric spark_db_backup_failed     "T18.3: backup de DR falhou"                 'resource.type="cloud_run_job" AND jsonPayload.event="db_backup_failed"'
ensure_metric spark_migration_failed     "T18.3: migration falhou"                    'resource.type="cloud_run_job" AND jsonPayload.event="migration_failed"'
ensure_metric spark_maintenance_completed "T18.3: ciclo de manutenção concluído"      "${MAINT_FILTER} AND jsonPayload.event=\"maintenance_completed\""
ensure_metric spark_maintenance_failed   "T18.3: ciclo de manutenção falhou"          "${MAINT_FILTER} AND jsonPayload.event=\"maintenance_failed\""
ensure_metric spark_db_backup_stale      "T18.3: backup de DR mais antigo que a janela" "${MAINT_FILTER} AND jsonPayload.event=\"db_backup_stale\""
ensure_metric spark_db_size_critical     "T18.3: tamanho do banco em PLAN/ACTION_REQUIRED" "${MAINT_FILTER} AND jsonPayload.event=\"database_size_threshold_exceeded\" AND (jsonPayload.level=\"PLAN\" OR jsonPayload.level=\"ACTION_REQUIRED\")"
ensure_metric spark_restore_drill_failed "T18.3: ensaio de restauração reprovou"      'logName:"logs/spark-dr-drill" AND jsonPayload.status="RESTORE_DRILL_FAIL"'
ensure_metric spark_scheduler_failed     "T18.3: execução do Cloud Scheduler com erro" 'resource.type="cloud_scheduler_job" AND severity>=ERROR'
ensure_metric spark_revision_start_failed "T18.3: revision do Cloud Run não subiu"    'resource.type="cloud_run_revision" AND severity>=ERROR AND (textPayload:"failed to start" OR textPayload:"probe failed" OR textPayload:"STARTUP")'

# ---------------------------------------------------------------- políticas
# threshold_policy <displayName> <filtro de métrica> <limiar> <alinhador> <documentação>
threshold_policy() {
  local name="$1" filter="$2" threshold="$3" aligner="$4" doc="$5"
  jq -n --arg name "${name}" --arg filter "${filter}" --argjson threshold "${threshold}" \
    --arg aligner "${aligner}" --arg doc "${doc}" --arg channel "${CHANNEL}" '{
    displayName: $name, combiner: "OR",
    conditions: [{
      displayName: $name,
      conditionThreshold: {
        filter: $filter, comparison: "COMPARISON_GT", thresholdValue: $threshold, duration: "0s",
        aggregations: [{ alignmentPeriod: "300s", perSeriesAligner: $aligner, crossSeriesReducer: "REDUCE_SUM", groupByFields: [] }],
        trigger: { count: 1 }
      }
    }],
    notificationChannels: [$channel],
    alertStrategy: { autoClose: "1800s" },
    documentation: { content: $doc, mimeType: "text/markdown" }
  }'
}

# absence_policy <displayName> <filtro> <duração> <documentação>
absence_policy() {
  local name="$1" filter="$2" duration="$3" doc="$4"
  jq -n --arg name "${name}" --arg filter "${filter}" --arg duration "${duration}" \
    --arg doc "${doc}" --arg channel "${CHANNEL}" '{
    displayName: $name, combiner: "OR",
    conditions: [{
      displayName: $name,
      conditionAbsent: {
        filter: $filter, duration: $duration,
        aggregations: [{ alignmentPeriod: "60s", perSeriesAligner: "ALIGN_SUM", crossSeriesReducer: "REDUCE_SUM", groupByFields: [] }]
      }
    }],
    notificationChannels: [$channel],
    alertStrategy: { autoClose: "1800s" },
    documentation: { content: $doc, mimeType: "text/markdown" }
  }'
}

apply_policy() {
  local json="$1" name file existing
  name="$(printf '%s' "${json}" | jq -r .displayName)"
  file="${WORK}/${name}.json"
  printf '%s' "${json}" > "${file}"
  existing="$(gcloud alpha monitoring policies list --project "${SPARK_GCP_PROJECT}" \
    --filter="displayName=\"${name}\"" --format='value(name)' 2> /dev/null | head -1)"
  if [ -n "${existing}" ]; then
    gcloud alpha monitoring policies update "${existing}" --project "${SPARK_GCP_PROJECT}" \
      --policy-from-file "${file}" > /dev/null
    log "política atualizada: ${name}"
  else
    gcloud alpha monitoring policies create --project "${SPARK_GCP_PROJECT}" \
      --policy-from-file "${file}" > /dev/null
    log "política criada: ${name}"
  fi
}

user_metric() { printf 'metric.type="logging.googleapis.com/user/%s"' "$1"; }
RUNBOOK="Ver docs/operations/OBSERVABILITY.md e docs/operations/RUNBOOK.md (Cloud Run)."

apply_policy "$(threshold_policy spark-run-5xx \
  "metric.type=\"run.googleapis.com/request_count\" AND resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"${SPARK_RUN_API_SERVICE}\" AND metric.labels.response_code_class=\"5xx\"" \
  5 ALIGN_SUM "≥ 5 respostas 5xx em 5 min em ${SPARK_RUN_API_SERVICE}. ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-run-revision-failed "$(user_metric spark_revision_start_failed) AND resource.type=\"cloud_run_revision\"" 0 ALIGN_SUM "Uma revision do Cloud Run não conseguiu iniciar. ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-job-migrate-failed \
  "$(user_metric spark_migration_failed) AND resource.type=\"cloud_run_job\"" 0 ALIGN_SUM "O Job ${SPARK_RUN_MIGRATE_JOB} registrou migration_failed. O deploy abortou antes da API. ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-job-migrate-task-failed \
  "metric.type=\"run.googleapis.com/job/completed_task_attempt_count\" AND resource.type=\"cloud_run_job\" AND resource.labels.job_name=\"${SPARK_RUN_MIGRATE_JOB}\" AND metric.labels.result=\"failed\"" \
  0 ALIGN_SUM "Uma tentativa do Job ${SPARK_RUN_MIGRATE_JOB} terminou com result=failed (inclusive crash antes de logar). ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-job-backup-failed \
  "$(user_metric spark_db_backup_failed) AND resource.type=\"cloud_run_job\"" 0 ALIGN_SUM "O Job ${SPARK_RUN_BACKUP_JOB} registrou db_backup_failed. Rode ops/gcp/dr-status.sh. ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-job-backup-task-failed \
  "metric.type=\"run.googleapis.com/job/completed_task_attempt_count\" AND resource.type=\"cloud_run_job\" AND resource.labels.job_name=\"${SPARK_RUN_BACKUP_JOB}\" AND metric.labels.result=\"failed\"" \
  0 ALIGN_SUM "Uma tentativa do Job ${SPARK_RUN_BACKUP_JOB} terminou com result=failed. ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-db-backup-stale "$(user_metric spark_db_backup_stale) AND resource.type=\"cloud_run_revision\"" 0 ALIGN_SUM "O maintenance mediu o backup de DR mais recente e ele está acima da janela. Rode ops/gcp/dr-status.sh e ops/gcp/dr-backup-now.sh. ${RUNBOOK}")"
apply_policy "$(absence_policy spark-maintenance-stale "$(user_metric spark_maintenance_completed) AND resource.type=\"cloud_run_revision\"" 600s "10 min sem maintenance_completed: o Cloud Scheduler parou, o serviço não sobe, ou o ciclo está preso. GET /internal/maintenance/status. ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-maintenance-failed "$(user_metric spark_maintenance_failed) AND resource.type=\"cloud_run_revision\"" 0 ALIGN_SUM "Um ciclo de manutenção falhou (maintenance_failed, com errorName). ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-scheduler-failed "$(user_metric spark_scheduler_failed) AND resource.type=\"cloud_scheduler_job\"" 0 ALIGN_SUM "O Cloud Scheduler registrou erro ao chamar o alvo (HTTP != 2xx, timeout, IAM). ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-db-size-critical "$(user_metric spark_db_size_critical) AND resource.type=\"cloud_run_revision\"" 0 ALIGN_SUM "pg_database_size acima de PLAN (400 MB) ou ACTION_REQUIRED (450 MB). Ver os limiares em docs/operations/OBSERVABILITY.md. ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-restore-drill-failed "$(user_metric spark_restore_drill_failed) AND resource.type=\"global\"" 0 ALIGN_SUM "Um ensaio de restauração registrado com --record reprovou. ${RUNBOOK}")"

log "alertas aplicados. Canal: ${CHANNEL} (${SPARK_ALERT_EMAIL})."
log "NOT VERIFIED até um alerta real chegar: confirme o e-mail do canal na Console (Monitoring → Alerting → Notification channels)."
