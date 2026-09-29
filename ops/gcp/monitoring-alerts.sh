#!/usr/bin/env bash
# Alertas operacionais do Spark no Cloud Monitoring (T18.3 §15). Idempotente.
#
#   SPARK_GCP_PROJECT=... SPARK_ALERT_EMAIL=ops@exemplo.com ops/gcp/monitoring-alerts.sh
#   SPARK_GCP_PROJECT=... ops/gcp/monitoring-alerts.sh --list        # o que existe (somente leitura)
#
# O que ele cria (ou atualiza, se já existir com o mesmo displayName):
#
#   canal      spark-ops-email (e-mail)                     ← SPARK_ALERT_EMAIL
#   métricas   log-based, uma por evento estruturado do backend (para painéis e para a política de
#              ausência) — as políticas de evento alertam por LOG, sem depender delas
#   políticas  spark-run-5xx                 ≥ 5 respostas 5xx em 5 min na API
#              spark-run-revision-failed     revision do Cloud Run que não sobe
#              spark-job-migrate-failed      migration_failed OU task do Job com result=failed
#              spark-job-backup-failed       db_backup_failed OU task do Job com result=failed
#              spark-db-backup-stale         db_backup_stale (o maintenance mediu a idade do último válido)
#              spark-maintenance-stale       45 min sem resposta 2xx do spark-maintenance (o Scheduler parou, ou o serviço) — CRITICAL
#              spark-maintenance-failed      maintenance_failed (stage=database: o banco não respondeu; stage=workers: um worker)
#              spark-scheduler-failed        erro do Cloud Scheduler (HTTP != 2xx no alvo) — WARNING: um erro isolado
#                                            com o ciclo seguinte bem-sucedido não é incidente
#              spark-db-size-critical        database_size_threshold_exceeded em PLAN/ACTION_REQUIRED
#              spark-db-compute-long-uptime  database_compute_long_uptime: o compute do banco não dorme há horas (T19.H6)
#              spark-restore-drill-failed    RESTORE_DRILL_FAIL registrado por dr-restore-drill.sh --record
#
# Pouco ruído de propósito (§15): contagens por 5 min, auto-close em 30 min, e nada dispara por um
# único 5xx. O que exige Console — verificar o e-mail do canal — está em OBSERVABILITY.md e é
# NOT VERIFIED até um alerta real chegar.
#
# T19.H6: a janela de ausência vem de `lib.gcp.sh` (SPARK_MAINTENANCE_ABSENCE_ALERT_SECONDS) e só é
# aplicada se for coerente com o cron do Scheduler e com o stale do heartbeat.

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
# A janela do alerta de ausência precisa falar da mesma cadência que o Scheduler e o heartbeat
# (T19.H6) — conferido antes de criar ou atualizar qualquer coisa.
require_coherent_maintenance_timing

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
ensure_metric spark_db_compute_long_uptime "T19.H6: compute do banco acordado além do limite" "${MAINT_FILTER} AND jsonPayload.event=\"database_compute_long_uptime\""
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

# log_policy <displayName> <filtro de LOG> <documentação> — alerta por entrada de log (Cloud
# Monitoring "log-based alerting"). Não depende de métrica: dispara na primeira entrada que casa
# com o filtro, com um limite de uma notificação a cada 5 min por política. É a forma escolhida
# para os eventos estruturados do backend — uma métrica log-based recém-criada leva minutos para
# ficar visível ao Monitoring (visto na execução real), e um alerta que espera por isso é um alerta
# que pode não existir quando o primeiro incidente acontecer.
log_policy() {
  local name="$1" filter="$2" doc="$3"
  jq -n --arg name "${name}" --arg filter "${filter}" --arg doc "${doc}" --arg channel "${CHANNEL}" '{
    displayName: $name, combiner: "OR",
    conditions: [{ displayName: $name, conditionMatchedLog: { filter: $filter } }],
    notificationChannels: [$channel],
    alertStrategy: { notificationRateLimit: { period: "300s" }, autoClose: "1800s" },
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

# Uma métrica log-based recém-criada leva até ~10 min para o Cloud Monitoring a enxergar ("Cannot
# find metric(s) that match type"), e uma política que a referencia é recusada até lá — visto na
# primeira execução real. Por isso cada política tenta de novo, com espera, antes de desistir:
# SPARK_ALERT_RETRIES tentativas (12) com SPARK_ALERT_RETRY_SLEEP segundos (30) entre elas.
apply_policy() {
  local json="$1" name file existing attempt output
  name="$(printf '%s' "${json}" | jq -r .displayName)"
  file="${WORK}/${name}.json"
  printf '%s' "${json}" > "${file}"
  existing="$(gcloud alpha monitoring policies list --project "${SPARK_GCP_PROJECT}" \
    --filter="displayName=\"${name}\"" --format='value(name)' 2> /dev/null | head -1)"
  for attempt in $(seq 1 "${SPARK_ALERT_RETRIES:-12}"); do
    if [ -n "${existing}" ]; then
      if output="$(gcloud alpha monitoring policies update "${existing}" --project "${SPARK_GCP_PROJECT}" \
          --policy-from-file "${file}" 2>&1)"; then
        log "política atualizada: ${name}"
        return 0
      fi
    else
      if output="$(gcloud alpha monitoring policies create --project "${SPARK_GCP_PROJECT}" \
          --policy-from-file "${file}" 2>&1)"; then
        log "política criada: ${name}"
        return 0
      fi
    fi
    case "${output}" in
      *"Cannot find metric"*)
        log "política ${name}: a métrica ainda não está visível (tentativa ${attempt}); aguardando ${SPARK_ALERT_RETRY_SLEEP:-30}s"
        sleep "${SPARK_ALERT_RETRY_SLEEP:-30}"
        ;;
      *)
        printf '%s\n' "${output}" >&2
        fail "política ${name}: falha ao aplicar"
        ;;
    esac
  done
  fail "política ${name}: a métrica não ficou visível depois de ${SPARK_ALERT_RETRIES:-12} tentativas"
}

# with_severity <CRITICAL|ERROR|WARNING> — a severidade da política (T19.H6 §35): o que é incidente
# e o que é aviso fica escrito na própria política, e não só na cabeça de quem lê o e-mail.
with_severity() { jq --arg severity "$1" '.severity = $severity'; }

user_metric() { printf 'metric.type="logging.googleapis.com/user/%s"' "$1"; }
RUNBOOK="Ver docs/operations/OBSERVABILITY.md e docs/operations/RUNBOOK.md (Cloud Run)."

apply_policy "$(threshold_policy spark-run-5xx \
  "metric.type=\"run.googleapis.com/request_count\" AND resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"${SPARK_RUN_API_SERVICE}\" AND metric.labels.response_code_class=\"5xx\"" \
  5 ALIGN_SUM "≥ 5 respostas 5xx em 5 min em ${SPARK_RUN_API_SERVICE}. ${RUNBOOK}")"
apply_policy "$(log_policy spark-run-revision-failed 'resource.type="cloud_run_revision" AND severity>=ERROR AND (textPayload:"failed to start" OR textPayload:"probe failed" OR textPayload:"STARTUP")' "Uma revision do Cloud Run não conseguiu iniciar. ${RUNBOOK}")"
apply_policy "$(log_policy spark-job-migrate-failed 'resource.type="cloud_run_job" AND jsonPayload.event="migration_failed"' "O Job ${SPARK_RUN_MIGRATE_JOB} registrou migration_failed. O deploy abortou antes da API. ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-job-migrate-task-failed \
  "metric.type=\"run.googleapis.com/job/completed_task_attempt_count\" AND resource.type=\"cloud_run_job\" AND resource.labels.job_name=\"${SPARK_RUN_MIGRATE_JOB}\" AND metric.labels.result=\"failed\"" \
  0 ALIGN_SUM "Uma tentativa do Job ${SPARK_RUN_MIGRATE_JOB} terminou com result=failed (inclusive crash antes de logar). ${RUNBOOK}")"
apply_policy "$(log_policy spark-job-backup-failed 'resource.type="cloud_run_job" AND jsonPayload.event="db_backup_failed"' "O Job ${SPARK_RUN_BACKUP_JOB} registrou db_backup_failed. Rode ops/gcp/dr-status.sh. ${RUNBOOK}")"
apply_policy "$(threshold_policy spark-job-backup-task-failed \
  "metric.type=\"run.googleapis.com/job/completed_task_attempt_count\" AND resource.type=\"cloud_run_job\" AND resource.labels.job_name=\"${SPARK_RUN_BACKUP_JOB}\" AND metric.labels.result=\"failed\"" \
  0 ALIGN_SUM "Uma tentativa do Job ${SPARK_RUN_BACKUP_JOB} terminou com result=failed. ${RUNBOOK}")"
apply_policy "$(log_policy spark-db-backup-stale "${MAINT_FILTER} AND jsonPayload.event=\"db_backup_stale\"" "O maintenance mediu o backup de DR mais recente e ele está acima da janela. Rode ops/gcp/dr-status.sh e ops/gcp/dr-backup-now.sh. ${RUNBOOK}")"
# "Ausência" só existe sobre séries temporais, e sobre uma métrica NATIVA: a de contagem de
# requisições 2xx do serviço de manutenção. A janela vem de `lib.gcp.sh` e acompanha o cron do
# Scheduler (T19.H6: a cada 15 min → 45 min, três ciclos): cobre "o Scheduler parou", "o serviço
# não sobe" e "o ciclo falha" (um ciclo que lança responde 5xx) sem disparar entre ciclos normais
# nem por um ciclo isolado que falha. Uma métrica log-based recém-criada pode levar dezenas de
# minutos até o alerting a reconhecer (visto na execução real, mesmo com pontos já ingeridos); a
# nativa existe desde o primeiro deploy.
ABSENCE_MINUTES=$((SPARK_MAINTENANCE_ABSENCE_ALERT_SECONDS / 60))
apply_policy "$(absence_policy spark-maintenance-stale \
  "metric.type=\"run.googleapis.com/request_count\" AND resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"${SPARK_RUN_MAINTENANCE_SERVICE}\" AND metric.labels.response_code_class=\"2xx\"" \
  "${SPARK_MAINTENANCE_ABSENCE_ALERT_SECONDS}s" "${ABSENCE_MINUTES} min sem resposta 2xx de ${SPARK_RUN_MAINTENANCE_SERVICE} (Scheduler: '${SPARK_SCHEDULER_CRON}'): vários ciclos seguidos perdidos — o Cloud Scheduler parou, o serviço não sobe, ou o ciclo está falhando (o banco inclusive: maintenance_failed stage=database). GET /internal/maintenance/status. ${RUNBOOK}" \
  | with_severity CRITICAL)"
apply_policy "$(log_policy spark-maintenance-failed "${MAINT_FILTER} AND jsonPayload.event=\"maintenance_failed\"" "Um ciclo de manutenção falhou (maintenance_failed, com errorName). stage=database: o PostgreSQL não respondeu — confira /health/ready e o provedor (Neon). stage=workers: um worker quebrou. ${RUNBOOK}")"
# Um erro isolado do Scheduler, com o ciclo seguinte bem-sucedido, não é incidente (T19.H6 §35):
# WARNING. Vários ciclos perdidos são `spark-maintenance-stale`, que é CRITICAL.
apply_policy "$(log_policy spark-scheduler-failed 'resource.type="cloud_scheduler_job" AND severity>=ERROR' "O Cloud Scheduler registrou erro ao chamar o alvo (HTTP != 2xx, timeout, IAM). Um erro isolado seguido de um ciclo bem-sucedido não é incidente; vários seguidos viram spark-maintenance-stale. ${RUNBOOK}" \
  | with_severity WARNING)"
apply_policy "$(log_policy spark-db-size-critical "${MAINT_FILTER} AND jsonPayload.event=\"database_size_threshold_exceeded\" AND (jsonPayload.level=\"PLAN\" OR jsonPayload.level=\"ACTION_REQUIRED\")" "pg_database_size acima de PLAN (400 MB) ou ACTION_REQUIRED (450 MB). Ver os limiares em docs/operations/OBSERVABILITY.md. ${RUNBOOK}")"
# O aviso que faltou em 2026-09-28 (T19.H6): o compute do banco acordado há horas, sem nenhuma
# suspensão, consome a franquia mensal de compute do Neon a ~6 CU-h/dia. O maintenance emite o
# evento uma vez por compute (DATABASE_COMPUTE_UPTIME_WARN_MS, 6 h), então isto é um e-mail por
# episódio — nunca um por ciclo.
apply_policy "$(log_policy spark-db-compute-long-uptime "${MAINT_FILTER} AND jsonPayload.event=\"database_compute_long_uptime\"" "O compute do PostgreSQL está acordado há mais de DATABASE_COMPUTE_UPTIME_WARN_MS sem suspender: algo consulta o banco sem parar (cadência do Scheduler, tráfego, um poller novo). No Neon Free isto esgota a franquia mensal de compute e derruba toda rota autenticada. Ver RUNBOOK → 'O banco não dorme'. ${RUNBOOK}" \
  | with_severity WARNING)"
apply_policy "$(log_policy spark-restore-drill-failed 'logName:"logs/spark-dr-drill" AND jsonPayload.status="RESTORE_DRILL_FAIL"' "Um ensaio de restauração registrado com --record reprovou. ${RUNBOOK}")"

log "alertas aplicados. Canal: ${CHANNEL} (${SPARK_ALERT_EMAIL})."
log "NOT VERIFIED até um alerta real chegar: confirme o e-mail do canal na Console (Monitoring → Alerting → Notification channels)."
