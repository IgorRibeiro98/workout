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
# T18.3.2 acrescenta duas propriedades:
#   - os Jobs spark-db-backup/spark-storage-audit só recebem o digest novo DEPOIS da troca de
#     tráfego (um deploy abortado nunca os deixa numa imagem que ninguém validou), e o backup do
#     gate roda com a imagem que o Job já tinha;
#   - o deploy exige procedência do commit: presente em origin/main e com o workflow `backend`
#     verde, com SPARK_DEPLOY_ALLOW_UNVERIFIED=1 como saída de emergência documentada.
#
# T19.10 corrige a procedência para ancestralidade: o commit publicado nem sempre é o commit que
# `backend.yml` rodaria (um release Android-only nunca dispara esse workflow), então o gate agora
# exige CI verde no commit backend-relevante MAIS RECENTE na ancestralidade do commit publicado —
# nunca "o próprio commit" cegamente, nunca "algum CI verde recente" solto. Ver
# `ops/lib.deploy-gate.sh` e a seção "T19.10 §F1/§6-7" abaixo.
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
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend ' | grep -q 'DATABASE_URL=spark-database-url:7,GROQ_API_KEY=spark-groq-api-key:7,ACCOUNT_DELETION_HMAC_KEY=spark-account-deletion-hmac-key:7' && echo sim || echo não)"
check "manutenção: os três secrets pinados na versão 7" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-maintenance ' | grep -q 'spark-account-deletion-hmac-key:7' && echo sim || echo não)"
check "migration: secret direto pinado na versão 7" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-migrate ' | grep -q 'DATABASE_URL_DIRECT=spark-database-url-direct:7' && echo sim || echo não)"
check "o deploy imprime a correlação revision → versão de secret" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'secrets pinados nesta release: spark-database-url=v7 spark-database-url-direct=v7 spark-groq-api-key=v7 spark-account-deletion-hmac-key=v7' && echo sim || echo não)"
check "§17 build sem provenance/SBOM" "sim" \
  "$(printf '%s\n' "$DOCKER" | grep '^build ' | grep -q -- '--provenance=false --sbom=false' && echo sim || echo não)"
check "o digest usado nos jobs/serviços é o do Artifact Registry, nunca o da tag local" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-migrate ' | grep -q -- '--image southamerica-east1-docker.pkg.dev/infra-project/spark/spark-backend@sha256:' && echo sim || echo não)"
check "nenhuma referência de imagem sem registry" "0" \
  "$(printf '%s\n' "$LOG" | grep -c -- '--image spark-backend@' || true)"

echo
echo "=== §2 Jobs de DR e auditoria ==="
check "spark-db-backup publicado com a backup SA" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-backup ' | grep -q 'service-account spark-backend-backup@infra-project.iam.gserviceaccount.com' && echo sim || echo não)"
check "spark-db-backup roda dist/cli/db-backup.js" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-backup ' | grep -q -- '--args dist/cli/db-backup.js' && echo sim || echo não)"
check "spark-db-backup recebe só o secret direto (pinado)" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-backup ' | grep -q -- '--set-secrets DATABASE_URL_DIRECT=spark-database-url-direct:7 ' && echo sim || echo não)"
check "spark-db-backup nunca recebe chave de IA/HMAC/pooled" "não" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-db-backup ' | grep -qE 'GEMINI|GROQ|HMAC|DATABASE_URL=spark-database-url:' && echo sim || echo não)"
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
AUDIT_DEPLOY_LINE="$(line_no "${LOGFILE}" 'run jobs deploy spark-storage-audit')"
TRAFFIC_LINE="$(line_no "${LOGFILE}" 'update-traffic spark-backend')"
# T18.3.2 — a ordem invertida: o backup pré-deploy roda com a imagem que o Job JÁ tem (a última
# validada), e o digest desta release só chega aos Jobs depois que o tráfego mudou. O ponto de
# recuperação deste deploy não pode ser produzido por código que este deploy ainda não validou.
check "o Job de backup rodou com a imagem já validada (nenhum deploy dele antes da execução)" "sim" \
  "$( [ "${BACKUP_DEPLOY_LINE}" -gt "${BACKUP_LINE}" ] && echo sim || echo não )"
check "o digest novo só chega ao Job de backup DEPOIS da troca de tráfego" "sim" \
  "$( [ -n "${TRAFFIC_LINE}" ] && [ "${TRAFFIC_LINE}" -lt "${BACKUP_DEPLOY_LINE}" ] && echo sim || echo não )"
check "...e ao Job de auditoria também" "sim" \
  "$( [ -n "${AUDIT_DEPLOY_LINE}" ] && [ "${TRAFFIC_LINE}" -lt "${AUDIT_DEPLOY_LINE}" ] && echo sim || echo não )"
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
echo "=== T18.3.2 deploy abortado nunca deixa os Jobs numa imagem não validada ==="
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GCLOUD_JOB_EXECUTE_FAILS=spark-db-migrate || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; cleanup_logs
check "migration que falha aborta o deploy" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...e o Job de backup NÃO recebeu o digest novo" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs deploy spark-db-backup' && echo sim || echo não)"
check "...nem o Job de auditoria" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs deploy spark-storage-audit' && echo sim || echo não)"

# Primeiro deploy: o Job de backup ainda não existe, e aí não há imagem anterior a preservar — ele
# nasce com esta, antes do gate, senão o gate não teria o que executar.
CODIGO=0
run_deploy GCLOUD_MISSING=spark-db-backup || CODIGO=$?
LOGFILE="${GCLOUD_CALL_LOG}"; LOG="$(cat "${GCLOUD_CALL_LOG}")"
FIRST_BACKUP_DEPLOY="$(line_no "${LOGFILE}" 'run jobs deploy spark-db-backup')"
FIRST_BACKUP_EXEC="$(line_no "${LOGFILE}" 'run jobs execute spark-db-backup')"
cleanup_logs
check "Job de backup inexistente é criado antes do gate poder executá-lo" "sim" \
  "$( [ -n "${FIRST_BACKUP_DEPLOY}" ] && [ -n "${FIRST_BACKUP_EXEC}" ] && [ "${FIRST_BACKUP_DEPLOY}" -lt "${FIRST_BACKUP_EXEC}" ] && echo sim || echo não )"

echo
echo "=== T18.3.2 procedência do commit: origin/main + CI verde ==="
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GIT_NOT_ANCESTOR=1 || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; DOCKER="$(cat "${DOCKER_CALL_LOG}")"
cleanup_logs
check "commit fora de origin/main aborta o deploy" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...antes do build" "0" "$(printf '%s\n' "$DOCKER" | grep -c '^build ' || true)"
check "...com a mensagem certa" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'NÃO está em origin/main' && echo sim || echo não)"

CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GH_RUN_RESULT=completed:failure || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; DOCKER="$(cat "${DOCKER_CALL_LOG}")"
cleanup_logs
check "CI vermelho aborta o deploy" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...antes do build" "0" "$(printf '%s\n' "$DOCKER" | grep -c '^build ' || true)"
check "...dizendo que o CI não passou" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'não passou' && echo sim || echo não)"

CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GH_RUN_RESULT=in_progress: || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "CI ainda rodando aborta o deploy" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...explicando que o deploy não corre na frente do gate" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'ainda está rodando' && echo sim || echo não)"

CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GH_NO_RUN=1 || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "commit sem execução do workflow aborta o deploy" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...apontando a saída de emergência" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'SPARK_DEPLOY_ALLOW_UNVERIFIED=1' && echo sim || echo não)"

CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GH_RUN_LIST_FAILS=1 || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "consulta ao GitHub que falha aborta o deploy (nunca segue no escuro)" "sim" \
  "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"

CODIGO=0
run_deploy --full FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" \
  GIT_NOT_ANCESTOR=1 GH_RUN_RESULT=completed:failure SPARK_DEPLOY_ALLOW_UNVERIFIED=1 || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "a saída de emergência libera o deploy" "0" "${CODIGO}"
check "...e deixa o aviso no log" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'AVISO: SPARK_DEPLOY_ALLOW_UNVERIFIED=1' && echo sim || echo não)"

echo
echo "=== T19.10 §F1/§6-7 procedência por ancestralidade de backend ==="
# Caso 1 — o commit sendo publicado altera backend/ops diretamente: exige CI do próprio commit
# (comportamento idêntico ao pré-T19.10; default do dublê de git já simula isto).
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "Caso 1 — HEAD backend-relevante com CI do próprio HEAD verde: ALLOW" "0" "${CODIGO}"
check "...o log confirma que exigiu CI do próprio commit" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'altera .* diretamente' && echo sim || echo não)"

# Caso 2 — release commit (ex.: version bump Android) depois do último commit de backend: usa o
# commit backend-relevante ANCESTRAL como procedência, nunca o HEAD que o backend.yml nunca rodaria.
GH_LOG="$(mktemp)"
BACKEND_ANCESTOR="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" \
  GIT_BACKEND_SHA="${BACKEND_ANCESTOR}" GH_CALL_LOG="${GH_LOG}" || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; GHCALLS="$(cat "${GH_LOG}")"; cleanup_logs; rm -f "${GH_LOG}"
check "Caso 2 — release sem mudança de backend, ancestral com CI verde: ALLOW" "0" "${CODIGO}"
check "...consultou o SHA ancestral, nunca o HEAD, para o CI" "sim" \
  "$(printf '%s\n' "$GHCALLS" | grep -q -- "--commit ${BACKEND_ANCESTOR}" && echo sim || echo não)"
check "...e nunca teria satisfação sem esse ancestral (HEAD não aparece como --commit)" "não" \
  "$(printf '%s\n' "$GHCALLS" | grep -q -- '--commit 0123456789abcdef0123456789abcdef01234567' && echo sim || echo não)"
check "...o log nomeia o commit ancestral como a procedência usada" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q "procedência de backend é ${BACKEND_ANCESTOR}" && echo sim || echo não)"

# Caso 3 — backend mudou depois do último CI verde (o próprio commit é backend-relevante e não tem
# execução do workflow): DENY. É o mesmo cenário de "commit sem execução do workflow" acima —
# reafirmado aqui com a redação do finding F1.
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GH_NO_RUN=1 || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "Caso 3 — backend-relevante sem CI: DENY" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"

# Caso 6 — o CI verde encontrado é de OUTRA branch (PR/feature, nunca integrado a main pelo CI):
# `--branch main` precisa recusá-lo, não aceitar pelo SHA sozinho.
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GH_RUN_WRONG_BRANCH=1 || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "Caso 6 — CI verde de outra branch: DENY" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...a mensagem não finge evidência que não existe em main" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'nenhuma execução' && echo sim || echo não)"

# Caso 7 (T19.H4) — push com vários commits: `feat(backend)` + `docs` sobem juntos e o GitHub roda
# `backend.yml` uma vez, no topo. O commit backend-relevante não tem execução própria, mas o topo tem
# a mesma superfície de backend e CI verde em main: ALLOW. Era o que travava o deploy do T19.H3.
GH_LOG="$(mktemp)"
PUSH_TOP="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" \
  GIT_BACKEND_SHA="${BACKEND_ANCESTOR}" GIT_ANCESTRY_PATH="${PUSH_TOP}" GH_RUNS_ONLY_FOR="${PUSH_TOP}" \
  GH_CALL_LOG="${GH_LOG}" || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; GHCALLS="$(cat "${GH_LOG}")"; cleanup_logs; rm -f "${GH_LOG}"
check "Caso 7 — CI verde só no topo do push, mesmo backend do commit backend-relevante: ALLOW" "0" "${CODIGO}"
check "...consultou primeiro o commit backend-relevante e depois o descendente" "sim" \
  "$( [ "$(printf '%s\n' "$GHCALLS" | grep -c -- "--commit ${BACKEND_ANCESTOR}")" = "1" ] \
      && [ "$(printf '%s\n' "$GHCALLS" | grep -c -- "--commit ${PUSH_TOP}")" = "1" ] && echo sim || echo não)"
check "...o log diz em qual descendente achou o CI verde" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q "CI verde em ${PUSH_TOP}, descendente de ${BACKEND_ANCESTOR}" && echo sim || echo não)"

# Caso 8 (T19.H4) — o descendente com CI verde tem OUTRO backend (o diff acusa diferença): o verde
# dele não prova nada sobre o backend que vai subir. DENY.
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" \
  GIT_BACKEND_SHA="${BACKEND_ANCESTOR}" GIT_ANCESTRY_PATH="${PUSH_TOP}" GH_RUNS_ONLY_FOR="${PUSH_TOP}" \
  GIT_DIFF_DIFFERS_FOR="${PUSH_TOP}" || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "Caso 8 — CI verde num descendente com backend diferente: DENY" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...a mensagem diz que não achou execução válida" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'nenhuma execução' && echo sim || echo não)"

# Caso 9 (T19.H4) — o descendente tem o mesmo backend, mas o CI dele ainda está rodando: o deploy
# espera, não corre na frente do gate.
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" \
  GIT_BACKEND_SHA="${BACKEND_ANCESTOR}" GIT_ANCESTRY_PATH="${PUSH_TOP}" GH_RUNS_ONLY_FOR="${PUSH_TOP}" \
  GH_RUN_RESULT=in_progress: || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "Caso 9 — CI do topo do push ainda rodando: DENY" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...explicando que o CI ainda está rodando" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'ainda está rodando' && echo sim || echo não)"

# Caso 10 (T19.H4) — CI verde de outra branch no descendente continua não contando (`--branch main`).
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" \
  GIT_BACKEND_SHA="${BACKEND_ANCESTOR}" GIT_ANCESTRY_PATH="${PUSH_TOP}" GH_RUNS_ONLY_FOR="${PUSH_TOP}" \
  GH_RUN_WRONG_BRANCH=1 || CODIGO=$?
cleanup_logs
check "Caso 10 — CI verde do descendente só em outra branch: DENY" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"

# Nenhum commit na ancestralidade toca backend/ops — não há procedência alguma para verificar.
# Sem isto o gate poderia devolver string vazia e silenciosamente pular a checagem de CI.
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GIT_NO_BACKEND_HISTORY=1 || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "nenhum commit backend-relevante na ancestralidade: DENY (fail-closed, não pula a checagem)" "sim" \
  "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...a mensagem explica a ausência de procedência" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'nenhum commit na ancestralidade toca' && echo sim || echo não)"

echo
echo "=== §19 secret sem versão habilitada → deploy para antes de qualquer revision ==="
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z GCLOUD_SECRET_NO_VERSION=spark-groq-api-key || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs
check "deploy falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "nenhum job nem serviço recebeu deploy" "não" \
  "$(printf '%s\n' "$LOG" | grep -qE 'run (jobs )?deploy' && echo sim || echo não)"
check "a mensagem nomeia o secret sem versão" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q "secret 'spark-groq-api-key' sem versão habilitada" && echo sim || echo não)"

echo
echo "=== política inválida é recusada antes de tudo ==="
CODIGO=0
run_deploy SPARK_DR_PREDEPLOY_POLICY=skip || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
cleanup_logs
check "SPARK_DR_PREDEPLOY_POLICY=skip é recusada" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...sem tocar em nada" "0" "$(printf '%s\n' "$LOG" | grep -c 'run ' || true)"

echo
echo "=== T19.H4 Coach IA: SPARK_AI_PROVIDER=gemini — só a chave dele, provider e modelo explícitos ==="
CODIGO=0
run_deploy --full FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GCLOUD_SECRET_VERSION=7 SPARK_AI_PROVIDER=gemini GCLOUD_SECRET_NO_VERSION=spark-groq-api-key || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs
check "deploy com gemini termina mesmo com o secret da Groq sem versão (reserva não bloqueia)" "0" "${CODIGO}"
check "o secret da Groq nem é resolvido quando o provider é gemini" "0" \
  "$(printf '%s\n' "$LOG" | grep -c 'secrets versions list spark-groq-api-key' || true)"
check "API: provider, modelo, teto de saída e quota explícitos no ambiente, REQUIRE_AI_PROVIDER no lugar de REQUIRE_GEMINI" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend ' | grep -q 'AI_PROVIDER=gemini,GEMINI_MODEL=gemini-3.5-flash,GEMINI_MAX_OUTPUT_TOKENS=8192,AI_MAX_REQUESTS_GLOBAL_DAY=15,AI_MAX_REQUESTS_PER_USER_DAY=8,REQUIRE_AI_PROVIDER=false' && echo sim || echo não)"
check "API/manutenção nunca recebem REQUIRE_GEMINI nem a chave da Groq" "0" \
  "$(printf '%s\n' "$LOG" | grep -E 'run deploy spark-(backend|maintenance) ' | grep -cE 'REQUIRE_GEMINI|GROQ_API_KEY' || true)"
check "o log do deploy registra provider e modelo, nunca valor de chave" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'Coach IA desta release: provider=gemini model=gemini-3.5-flash' && echo sim || echo não)"

echo
echo "=== T19.H4 Coach IA: provider default (groq) — a revision recebe a chave da Groq e só ela ==="
CODIGO=0
run_deploy --full FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GCLOUD_SECRET_VERSION=7 GCLOUD_SECRET_NO_VERSION=spark-gemini-api-key || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs
check "deploy com groq termina (a chave do Gemini sem versão não importa)" "0" "${CODIGO}"
check "API: DATABASE_URL, GROQ_API_KEY e HMAC pinados — e nada do Gemini" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend ' | grep -q 'DATABASE_URL=spark-database-url:7,GROQ_API_KEY=spark-groq-api-key:7,ACCOUNT_DELETION_HMAC_KEY=spark-account-deletion-hmac-key:7' && echo sim || echo não)"
check "API/manutenção sem GEMINI_API_KEY" "0" \
  "$(printf '%s\n' "$LOG" | grep -E 'run deploy spark-(backend|maintenance) ' | grep -c 'GEMINI_API_KEY' || true)"
check "API: AI_PROVIDER=groq, modelo de produção e Preview recusado por default" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend ' | grep -q 'AI_PROVIDER=groq,GROQ_MODEL=openai/gpt-oss-120b,GROQ_MAX_OUTPUT_TOKENS=3000,GROQ_ALLOW_PREVIEW_MODEL=false,AI_MAX_REQUESTS_GLOBAL_DAY=25,AI_MAX_REQUESTS_PER_USER_DAY=8,REQUIRE_AI_PROVIDER=false' && echo sim || echo não)"
check "o log registra provider=groq e o modelo" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'provider=groq model=openai/gpt-oss-120b' && echo sim || echo não)"
check "o Job de smoke recebe só a chave do provider (nem banco, nem HMAC)" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-ai-provider-smoke ' | grep -q -- '--set-secrets GROQ_API_KEY=spark-groq-api-key:7 ' && echo sim || echo não)"
check "...e nunca DATABASE_URL/HMAC" "0" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-ai-provider-smoke ' | grep -cE 'DATABASE_URL|HMAC' || true)"
check "o Job de smoke roda ai-provider-smoke.js com a runtime SA, o gate de provider e a mesma configuração de IA" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run jobs deploy spark-ai-provider-smoke ' | grep -q -- '--args dist/cli/ai-provider-smoke.js --service-account spark-backend-runtime@infra-project.iam.gserviceaccount.com .*NODE_ENV=production,AI_PROVIDER=groq,GROQ_MODEL=openai/gpt-oss-120b,GROQ_MAX_OUTPUT_TOKENS=3000,GROQ_ALLOW_PREVIEW_MODEL=false,AI_MAX_REQUESTS_GLOBAL_DAY=25,AI_MAX_REQUESTS_PER_USER_DAY=8,REQUIRE_AI_PROVIDER=false,AI_PROVIDER_SMOKE_GATE=provider' && echo sim || echo não)"
check "o smoke do provider roda ANTES da troca de tráfego" "sim" \
  "$( [ "$(line_no <(printf '%s\n' "$LOG") 'run jobs execute spark-ai-provider-smoke')" -lt "$(line_no <(printf '%s\n' "$LOG") 'update-traffic spark-backend')" ] && echo sim || echo não)"

echo
echo "=== T19.H4 Coach IA: smoke do provider FALHA — tráfego antigo permanece ==="
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GCLOUD_JOB_EXECUTE_FAILS=spark-ai-provider-smoke || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs
check "deploy falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "nenhuma troca de tráfego" "não" "$(printf '%s\n' "$LOG" | grep -q 'update-traffic' && echo sim || echo não)"
check "os Jobs de DR não recebem o digest não validado" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run jobs deploy spark-storage-audit' && echo sim || echo não)"
check "a mensagem aponta o provider e a saída de emergência" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'smoke do provider de IA FALHOU (provider=groq' && printf '%s' "$SAIDA" | grep -q 'SPARK_AI_SMOKE_POLICY=warn' && echo sim || echo não)"

echo
echo "=== T19.H4 Coach IA: SPARK_AI_SMOKE_POLICY=warn segue; skip não chama o provider ==="
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" GCLOUD_JOB_EXECUTE_FAILS=spark-ai-provider-smoke SPARK_AI_SMOKE_POLICY=warn || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs
check "warn: deploy termina mesmo com o smoke do provider falhando" "0" "${CODIGO}"
check "warn: a troca de tráfego acontece" "sim" "$(printf '%s\n' "$LOG" | grep -q 'update-traffic spark-backend' && echo sim || echo não)"
check "warn: o log deixa claro que não há prova de que o Coach responde" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'SPARK_AI_SMOKE_POLICY=warn — seguindo SEM prova' && echo sim || echo não)"
CODIGO=0
run_deploy FAKE_DR_BACKUPS=2026-09-11T031500Z FAKE_DR_CREATED_MS="${FRESH_MS}" SPARK_AI_SMOKE_POLICY=skip || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
cleanup_logs
check "skip: deploy termina" "0" "${CODIGO}"
check "skip: o Job de smoke nunca é publicado nem executado" "0" \
  "$(printf '%s\n' "$LOG" | grep -c 'spark-ai-provider-smoke' || true)"

echo
echo "=== T19.H4 Coach IA: provider ou política inválidos são recusados antes de tudo ==="
recusado_antes_de_tudo() {
  CODIGO=0
  run_deploy "$@" || CODIGO=$?
  LOG="$(cat "${GCLOUD_CALL_LOG}")"; DOCKER="$(cat "${DOCKER_CALL_LOG}")"
  cleanup_logs
  check "$* é recusado" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
  check "...antes de build e de qualquer recurso" "0" \
    "$( { printf '%s\n' "$LOG" | grep -c 'run ' || true; } )$( [ -z "${DOCKER}" ] || echo '+docker')"
}
recusado_antes_de_tudo SPARK_AI_PROVIDER=openai
recusado_antes_de_tudo SPARK_AI_SMOKE_POLICY=talvez
recusado_antes_de_tudo SPARK_AI_PROVIDER=groq SPARK_GROQ_ALLOW_PREVIEW_MODEL=sim
recusado_antes_de_tudo SPARK_GEMINI_MAX_OUTPUT_TOKENS=muito
recusado_antes_de_tudo SPARK_GEMINI_MAX_REQUESTS_GLOBAL_DAY=-1

finish_checks "deploy: secrets pinados, jobs de DR, gate pré-migration e scheduler diário provados sem GCP"
