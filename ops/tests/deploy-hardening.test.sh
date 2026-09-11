#!/usr/bin/env bash
#
# O deploy endurecido da T18.3, provado sem GCP (dublês de `ops/tests/lib.fakes.sh`):
#
#   §19  versões de secret PINADAS: nenhuma revision/job referencia `:latest`; todas as referências
#        carregam a versão habilitada resolvida; sem versão habilitada o deploy para antes de
#        qualquer revision.
#   §2   o Job spark-db-backup é publicado com a backup SA, o secret direto, o bucket e a
#        proveniência (git commit + digest); spark-storage-audit com a runtime SA.
#   §10  o gate de DR: backup válido recente → migration segue sem backup extra; backup antigo com
#        run-backup → o Job de backup roda e termina ANTES da migration; com fail → aborta sem
#        migration e sem API; nenhum backup → run-backup executa; backup que "termina" sem
#        produzir nada → aborta.
#   §17  o build é sem provenance/SBOM (um digest por release).
#   §9   o Scheduler diário do backup dispara o Job pela API de administração, com OAuth da SA do
#        Scheduler, que recebe run.invoker sobre o Job.
#
# Uso: ops/tests/deploy-hardening.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

FAKE_BIN_DIR="$(mktemp -d)"
trap 'rm -rf "${FAKE_BIN_DIR}"' EXIT
install_fakes "${FAKE_BIN_DIR}"

# run_deploy [--full] [VAR=valor ...]  — roda o deploy com os fakes; `--full` inclui a manutenção.
run_deploy() {
  local skip="--skip-maintenance"
  if [ "${1:-}" = "--full" ]; then skip=""; shift; fi
  GCLOUD_CALL_LOG="$(mktemp)"
  DOCKER_CALL_LOG="$(mktemp)"
  export GCLOUD_CALL_LOG DOCKER_CALL_LOG
  env PATH="${FAKE_BIN_DIR}:${PATH}" \
    SPARK_GCP_PROJECT=infra-project \
    SPARK_FIREBASE_PROJECT=firebase-project \
    SPARK_GCP_REGION=southamerica-east1 \
    "$@" \
    "${OPS_DIR}/gcp/deploy-cloud-run.sh" ${skip:+"${skip}"} > "${GCLOUD_CALL_LOG}.out" 2>&1
}
cleanup_logs() { rm -rf "${GCLOUD_CALL_LOG}" "${GCLOUD_CALL_LOG}.out" "${GCLOUD_CALL_LOG}.state" "${DOCKER_CALL_LOG}"; }
line_no() { grep -n -- "$2" "$1" | head -1 | cut -d: -f1; }

NOW_MS="$(( $(date +%s) * 1000 ))"
FRESH_MS="$(( NOW_MS - 3600 * 1000 ))"          # 1 h atrás
OLD_MS="$(( NOW_MS - 30 * 3600 * 1000 ))"       # 30 h atrás (acima da janela de 24 h)

echo "=== §19 versões de secret pinadas ==="
CODIGO=0
run_deploy --full FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GCLOUD_SECRET_VERSION=7 || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; DOCKER="$(cat "${DOCKER_CALL_LOG}")"
cleanup_logs
check "deploy completo termina com sucesso" "0" "${CODIGO}"
check "resolve a versão habilitada dos quatro secrets" "4" \
  "$(printf '%s\n' "$LOG" | grep -c 'secrets versions list .*--filter=state:enabled' || true)"
check "nenhuma referência a secret usa :latest" "0" \
  "$(printf '%s\n' "$LOG" | grep -c ':latest' || true)"
check "API: os três secrets pinados na versão 7" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend ' | grep -q 'DATABASE_URL=spark-database-url:7,GEMINI_API_KEY=spark-gemini-api-key:7,ACCOUNT_DELETION_HMAC_KEY=spark-account-deletion-hmac-key:7' && echo sim || echo não)"
check "manutenção: os três secrets pinados na versão 7" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-maintenance ' | grep -q 'spark-account-deletion-hmac-key:7' && echo sim || echo não)"
check "migration: secret direto pinado na versão 7" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-migrate ' | grep -q 'DATABASE_URL_DIRECT=spark-database-url-direct:7' && echo sim || echo não)"
check "o deploy imprime a correlação revision → versão de secret" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'secrets pinados nesta release: spark-database-url=v7 spark-database-url-direct=v7 spark-gemini-api-key=v7 spark-account-deletion-hmac-key=v7' && echo sim || echo não)"
check "§17 build sem provenance/SBOM" "sim" \
  "$(printf '%s\n' "$DOCKER" | grep '^build ' | grep -q -- '--provenance=false --sbom=false' && echo sim || echo não)"

echo
echo "=== §2 Jobs de DR e auditoria ==="
check "spark-db-backup publicado com a backup SA" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-backup ' | grep -q 'service-account spark-backend-backup@infra-project.iam.gserviceaccount.com' && echo sim || echo não)"
check "spark-db-backup roda dist/cli/db-backup.js" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-backup ' | grep -q -- '--args dist/cli/db-backup.js' && echo sim || echo não)"
check "spark-db-backup recebe só o secret direto (pinado)" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-backup ' | grep -q -- '--set-secrets DATABASE_URL_DIRECT=spark-database-url-direct:7 ' && echo sim || echo não)"
check "spark-db-backup nunca recebe Gemini/HMAC/pooled" "não" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-backup ' | grep -qE 'GEMINI|HMAC|DATABASE_URL=spark-database-url:' && echo sim || echo não)"
check "spark-db-backup conhece bucket, retenção, commit e digest" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-backup ' | grep -q 'OBJECT_STORAGE_PROVIDER=gcs,GCS_BUCKET_NAME=spark-private-assets-prod,SPARK_DR_RETENTION_COUNT=7,SPARK_GIT_COMMIT=abcdef123456,SPARK_IMAGE_DIGEST=sha256:' && echo sim || echo não)"
check "spark-db-backup com 1Gi de memória e timeout de 1800s" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-backup ' | grep -q -- '--memory 1Gi .*--task-timeout 1800' && echo sim || echo não)"
check "spark-storage-audit publicado com a runtime SA e a pooled pinada" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-storage-audit ' | grep -q 'service-account spark-backend-runtime@infra-project.iam.gserviceaccount.com .*--set-secrets DATABASE_URL=spark-database-url:7 ' && echo sim || echo não)"
check "a migration continua separada da API (job próprio, migrator SA)" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-migrate ' | grep -q 'service-account spark-backend-migrator@' && echo sim || echo não)"

echo
echo "=== §9 Scheduler diário do backup ==="
check "run.invoker da SA do Scheduler sobre o Job de backup" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs add-iam-policy-binding spark-db-backup .*serviceAccount:spark-maintenance-scheduler@infra-project.iam.gserviceaccount.com --role roles/run.invoker' && echo sim || echo não)"
# O fake responde "existe" a todo describe: o deploy segue o ramo `update`; o `create` é o mesmo
# comando com os mesmos argumentos, e o grep aceita os dois.
check "scheduler spark-db-backup-daily garantido com cron diário e OAuth da SA do Scheduler" "sim" \
  "$(printf '%s\n' "$LOG" | grep -E 'scheduler jobs (create|update) http spark-db-backup-daily ' | grep -q -- '--schedule 15 3 \* \* \* --uri https://southamerica-east1-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/infra-project/jobs/spark-db-backup:run --http-method POST --oauth-service-account-email spark-maintenance-scheduler@infra-project.iam.gserviceaccount.com' && echo sim || echo não)"
check "o scheduler da manutenção continua a cada minuto, por OIDC" "sim" \
  "$(printf '%s\n' "$LOG" | grep -E 'scheduler jobs (create|update) http spark-maintenance-cycle ' | grep -q -- '--schedule \* \* \* \* \* .*--oidc-service-account-email' && echo sim || echo não)"

echo
echo "=== §10 gate de DR: backup válido recente → nenhum backup extra antes da migration ==="
check "o Job de backup NÃO foi executado" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs execute spark-db-backup' && echo sim || echo não)"
check "a migration foi executada" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs execute spark-db-migrate' && echo sim || echo não)"
check "o gate registrou o backup e a idade" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'gate de DR: backup válido 2026-09-11T031500Z' && echo sim || echo não)"

echo
echo "=== §10 gate de DR: backup ANTIGO + run-backup → backup roda ANTES da migration ==="
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-09T031500Z FAKE_DR_CREATED_MS="${OLD_MS}" || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
LOGFILE="${GCLOUD_CALL_LOG}"
check "deploy termina com sucesso" "0" "${CODIGO}"
check "o Job de backup foi executado" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs execute spark-db-backup .*--wait' && echo sim || echo não)"
BACKUP_LINE="$(line_no "${LOGFILE}" 'run jobs execute spark-db-backup')"
MIGRATE_LINE="$(line_no "${LOGFILE}" 'run jobs execute spark-db-migrate')"
BACKUP_DEPLOY_LINE="$(line_no "${LOGFILE}" 'run jobs deploy spark-db-backup')"
check "o Job de backup recebeu o digest novo ANTES de ser executado" "sim" \
  "$( [ -n "${BACKUP_DEPLOY_LINE}" ] && [ "${BACKUP_DEPLOY_LINE}" -lt "${BACKUP_LINE}" ] && echo sim || echo não )"
check "o backup terminou ANTES da migration" "sim" \
  "$( [ -n "${BACKUP_LINE}" ] && [ -n "${MIGRATE_LINE}" ] && [ "${BACKUP_LINE}" -lt "${MIGRATE_LINE}" ] && echo sim || echo não )"
check "o gate explicou que o backup mais recente estava acima da janela" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'acima da janela' && echo sim || echo não)"
check "e reconfirmou o gate com o backup novo" "sim" \
  "$(printf '%s' "$SAIDA" | grep -c 'gate de DR: backup válido' | grep -qx '1' && echo sim || echo não)"
cleanup_logs

echo
echo "=== §10 gate de DR: backup ANTIGO + policy=fail → aborta sem migration e sem API ==="
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-09T031500Z FAKE_DR_CREATED_MS="${OLD_MS}" SPARK_DR_PREDEPLOY_POLICY=fail || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs
check "deploy falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "nenhum backup foi executado (policy=fail não executa nada)" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs execute spark-db-backup' && echo sim || echo não)"
check "a migration NÃO foi executada" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs execute spark-db-migrate' && echo sim || echo não)"
check "nenhuma revision da API foi criada" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run deploy spark-backend' && echo sim || echo não)"
check "a mensagem aponta dr-backup-now.sh" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'dr-backup-now.sh' && echo sim || echo não)"

echo
echo "=== §10 gate de DR: NENHUM backup → run-backup executa; backup sem efeito → aborta ==="
CODIGO=0
run_deploy || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs
check "sem nenhum backup, o Job de backup roda" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs execute spark-db-backup' && echo sim || echo não)"
check "e o deploy segue (o fake produz um backup válido)" "0" "${CODIGO}"

CODIGO=0
run_deploy GCLOUD_BACKUP_JOB_NO_EFFECT=1 || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs
check "backup executado mas sem backup válido no bucket → deploy aborta" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "a migration NÃO roda nesse caso" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs execute spark-db-migrate' && echo sim || echo não)"
check "a mensagem diz que nenhum backup válido apareceu" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'nenhum backup válido apareceu' && echo sim || echo não)"

CODIGO=0
run_deploy GCLOUD_JOB_EXECUTE_FAILS=spark-db-backup || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs
check "backup pré-deploy que FALHA aborta o deploy" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...sem migration" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs execute spark-db-migrate' && echo sim || echo não)"

echo
echo "=== §19 secret sem versão habilitada → deploy para antes de qualquer revision ==="
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z GCLOUD_SECRET_NO_VERSION=spark-gemini-api-key || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs
check "deploy falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "nenhum job nem serviço recebeu deploy" "não" \
  "$(printf '%s\n' "$LOG" | grep -qE 'run (jobs )?deploy' && echo sim || echo não)"
check "a mensagem nomeia o secret sem versão" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q "secret 'spark-gemini-api-key' sem versão habilitada" && echo sim || echo não)"

echo
echo "=== política inválida é recusada antes de tudo ==="
CODIGO=0
run_deploy SPARK_DR_PREDEPLOY_POLICY=skip || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
cleanup_logs
check "SPARK_DR_PREDEPLOY_POLICY=skip é recusada" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...sem tocar em nada" "0" "$(printf '%s\n' "$LOG" | grep -c 'run ' || true)"

finish_checks "deploy: secrets pinados, jobs de DR, gate pré-migration e scheduler diário provados sem GCP"
