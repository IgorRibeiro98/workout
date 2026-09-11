#!/usr/bin/env bash
#
# Os alertas da T18.3 §15, provados sem GCP: `monitoring-alerts.sh` cria o canal, as métricas
# log-based (uma por evento estruturado) e as políticas — idempotente — e `--list` não cria nada.
# Um `gcloud` fake registra cada chamada e guarda os arquivos de política que recebeu, para que o
# teste leia o JSON que iria para o Cloud Monitoring.
#
# Uso: ops/tests/monitoring-alerts.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
FAKE_BIN_DIR="${WORK}/bin"
POLICIES="${WORK}/policies"
mkdir -p "${FAKE_BIN_DIR}" "${POLICIES}"

cat > "${FAKE_BIN_DIR}/gcloud" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GCLOUD_CALL_LOG}"
# Guarda o JSON de cada política criada/atualizada, pelo displayName.
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  if [ "${args[$i]}" = "--policy-from-file" ]; then
    file="${args[$((i + 1))]}"
    name="$(jq -r .displayName "${file}")"
    cp "${file}" "${POLICIES}/${name}.json"
  fi
done
case "$*" in
  "beta monitoring channels list"*)
    [ -n "${FAKE_CHANNEL_EXISTS:-}" ] && printf 'projects/infra-project/notificationChannels/111\n'
    exit 0 ;;
  "beta monitoring channels create"*) printf 'projects/infra-project/notificationChannels/222\n'; exit 0 ;;
  "logging metrics describe"*) exit 1 ;;
  "alpha monitoring policies list"*) exit 0 ;;
esac
exit 0
FAKE
chmod +x "${FAKE_BIN_DIR}/gcloud"

GCLOUD_CALL_LOG="${WORK}/calls.log"
export GCLOUD_CALL_LOG POLICIES
run_alerts() {
  : > "${GCLOUD_CALL_LOG}"
  PATH="${FAKE_BIN_DIR}:${PATH}" SPARK_GCP_PROJECT=infra-project SPARK_GCP_REGION=southamerica-east1 "$@" \
    "${OPS_DIR}/gcp/monitoring-alerts.sh" "${ALERT_ARGS[@]}" > "${WORK}/out" 2>&1
}

echo "=== criação completa ==="
ALERT_ARGS=()
CODIGO=0
run_alerts env SPARK_ALERT_EMAIL=ops@example.com || CODIGO=$?
check "termina com sucesso" "0" "${CODIGO}"
check "cria o canal de e-mail com o endereço" "sim" "$(grep -q 'beta monitoring channels create .*--type email --channel-labels email_address=ops@example.com' "${GCLOUD_CALL_LOG}" && echo sim || echo não)"
check "cria as 10 métricas log-based" "10" "$(grep -c '^logging metrics create spark_' "${GCLOUD_CALL_LOG}" || true)"
for metric in spark_db_backup_completed spark_db_backup_failed spark_migration_failed spark_maintenance_completed spark_maintenance_failed spark_db_backup_stale spark_db_size_critical spark_restore_drill_failed spark_scheduler_failed spark_revision_start_failed; do
  check "métrica ${metric}" "sim" "$(grep -q "^logging metrics create ${metric} " "${GCLOUD_CALL_LOG}" && echo sim || echo não)"
done
check "a métrica de backup filtra o evento estruturado do Job" "sim" "$(grep '^logging metrics create spark_db_backup_failed' "${GCLOUD_CALL_LOG}" | grep -q 'resource.type="cloud_run_job" AND jsonPayload.event="db_backup_failed"' && echo sim || echo não)"
check "a métrica de tamanho só conta PLAN/ACTION_REQUIRED" "sim" "$(grep '^logging metrics create spark_db_size_critical' "${GCLOUD_CALL_LOG}" | grep -q 'jsonPayload.level="PLAN" OR jsonPayload.level="ACTION_REQUIRED"' && echo sim || echo não)"
check "cria 12 políticas" "12" "$(grep -c '^alpha monitoring policies create' "${GCLOUD_CALL_LOG}" || true)"
for policy in spark-run-5xx spark-run-revision-failed spark-job-migrate-failed spark-job-migrate-task-failed spark-job-backup-failed spark-job-backup-task-failed spark-db-backup-stale spark-maintenance-stale spark-maintenance-failed spark-scheduler-failed spark-db-size-critical spark-restore-drill-failed; do
  check "política ${policy} gravada" "sim" "$( [ -f "${POLICIES}/${policy}.json" ] && echo sim || echo não )"
done
check "toda política notifica o canal criado" "12" "$(grep -l '"projects/infra-project/notificationChannels/222"' "${POLICIES}"/*.json | wc -l | tr -d ' ')"
check "5xx: limiar 5 em 5 min, na API" "sim" "$(jq -e '.conditions[0].conditionThreshold | .thresholdValue == 5 and (.filter | test("service_name=\"spark-backend\"")) and (.aggregations[0].alignmentPeriod == "300s")' "${POLICIES}/spark-run-5xx.json" > /dev/null && echo sim || echo não)"
check "maintenance-stale é ausência de dados por 600s" "sim" "$(jq -e '.conditions[0].conditionAbsent.duration == "600s"' "${POLICIES}/spark-maintenance-stale.json" > /dev/null && echo sim || echo não)"
check "job-migrate-task-failed usa a métrica nativa de tarefas do Job" "sim" "$(jq -e '.conditions[0].conditionThreshold.filter | test("job/completed_task_attempt_count") and test("job_name=\"spark-db-migrate\"") and test("result=\"failed\"")' "${POLICIES}/spark-job-migrate-task-failed.json" > /dev/null && echo sim || echo não)"
check "toda política fecha sozinha em 30 min e tem documentação" "12" "$(jq -e '.alertStrategy.autoClose == "1800s" and (.documentation.content | length > 0)' "${POLICIES}"/*.json 2>/dev/null | grep -c true || true)"
check "nenhuma política ou métrica menciona segredo" "não" "$(grep -qiE 'password|secret|token' "${POLICIES}"/*.json && echo sim || echo não)"

echo
echo "=== idempotência: canal já existe → não cria outro ==="
CODIGO=0
run_alerts env SPARK_ALERT_EMAIL=ops@example.com FAKE_CHANNEL_EXISTS=1 || CODIGO=$?
check "termina com sucesso" "0" "${CODIGO}"
check "não cria canal" "não" "$(grep -q 'channels create' "${GCLOUD_CALL_LOG}" && echo sim || echo não)"
check "usa o canal existente nas políticas" "12" "$(grep -l '"projects/infra-project/notificationChannels/111"' "${POLICIES}"/*.json | wc -l | tr -d ' ')"

echo
echo "=== --list não cria nem altera nada; sem e-mail é recusado ==="
ALERT_ARGS=(--list)
CODIGO=0
run_alerts env || CODIGO=$?
check "--list termina com sucesso" "0" "${CODIGO}"
check "--list só lista" "0" "$(grep -cE 'create|update' "${GCLOUD_CALL_LOG}" || true)"
ALERT_ARGS=()
CODIGO=0
run_alerts env || CODIGO=$?
check "sem SPARK_ALERT_EMAIL é recusado" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...antes de criar qualquer coisa" "0" "$(grep -cE 'create|update' "${GCLOUD_CALL_LOG}" || true)"

finish_checks "alertas: canal, 10 métricas e 12 políticas criados de forma idempotente, sem tocar em nada com --list"
