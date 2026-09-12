#!/usr/bin/env bash
# Deploy reproduzível do Spark Backend no Cloud Run (T18.2 §5/§10; endurecido na T18.3).
#
#   árvore Git limpa + commit em origin/main com o CI `backend` verde (T18.3.2)
#         ↓
#   build da imagem (tag = git SHA; sem provenance/SBOM — um digest por release)
#         ↓
#   push para o Artifact Registry
#         ↓
#   resolve o digest exato
#         ↓
#   resolve a versão HABILITADA de cada secret (revision X → secret versão Y, nunca :latest)
#         ↓
#   garante que o Job spark-db-backup EXISTE (sem trocar a imagem de um que já existe)
#         ↓
#   GATE de DR: existe backup válido com ≤ SPARK_DR_MAX_BACKUP_AGE_HOURS?
#     não → SPARK_DR_PREDEPLOY_POLICY=run-backup: executa spark-db-backup e espera SUCCESS
#           SPARK_DR_PREDEPLOY_POLICY=fail:       aborta
#         ↓
#   atualiza o Job spark-db-migrate com o MESMO digest
#         ↓
#   executa o Job e espera SUCCESS
#         ↓
#   SPARK_RUN_API_SERVICE já existe?
#     SIM (deploy seguinte)                    NÃO (primeiro deploy — T18.2.1)
#     ─────────────────────                    ──────────────────────────────
#     deploy do candidate, SEM tráfego          deploy da MESMA imagem/config num serviço
#     (--no-traffic --tag candidate)            TEMPORÁRIO e PRIVADO (SPARK_RUN_API_VALIDATE_SERVICE)
#         ↓                                         ↓
#     smoke contra o candidate                  smoke contra o temporário, com o identity token
#         ↓                                     do operador em X-Serverless-Authorization
#     100% do tráfego para o candidate               ↓
#                                                remove o temporário, cria SPARK_RUN_API_SERVICE
#                                                de verdade (já validado)
#         ↓                                         ↓
#     Jobs spark-db-backup e spark-storage-audit recebem o MESMO digest — depois do tráfego, para
#     que um deploy abortado nunca os deixe numa imagem que não foi validada (T18.3.2)
#         ↓
#              deploy do spark-maintenance com o MESMO digest (privado, sem tráfego a mover)
#         ↓
#   garante os dois jobs do Cloud Scheduler (manutenção a cada minuto; backup de DR diário)
#
# Por que o primeiro deploy é um caminho à parte: `--no-traffic` não tem efeito na primeira
# revision de um serviço Cloud Run novo — ela recebe 100% do (único) tráfego que existe,
# independente da flag. O par candidate→smoke→promove tráfego protege uma SUBSTITUIÇÃO; no
# primeiro deploy não há nada para substituir, então validar precisa acontecer ANTES de
# SPARK_RUN_API_SERVICE existir — daí o serviço temporário, que desde a T18.3 §21 é PRIVADO: o
# smoke o chama com o identity token do operador, e ninguém mais o alcança.
#
# Falha cedo: gate de DR, migration FAIL, candidate/validação health FAIL ou smoke FAIL abortam
# antes de qualquer tráfego real ser movido ou de SPARK_RUN_API_SERVICE ser criado.
#
# Uso:
#   SPARK_GCP_PROJECT=meu-projeto ops/gcp/deploy-cloud-run.sh
#   ops/gcp/deploy-cloud-run.sh --skip-maintenance   # só a API + jobs, sem tocar spark-maintenance
#
# Emergência (documentada, e registrada no log do deploy): SPARK_DEPLOY_ALLOW_UNVERIFIED=1 pula a
# verificação de procedência do commit — origin/main e CI verde. Nada mais é afrouxado por ela.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"
# shellcheck source=ops/gcp/lib.dr.sh
. "${SCRIPT_DIR}/lib.dr.sh"
# shellcheck source=ops/lib.deploy-gate.sh
. "${SCRIPT_DIR}/../lib.deploy-gate.sh"

REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BACKEND_DIR="${REPO_ROOT}/backend"

require_cmd gcloud
require_cmd docker
require_cmd git
require_cmd jq

SKIP_MAINTENANCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-maintenance) SKIP_MAINTENANCE=1; shift ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

case "${SPARK_DR_PREDEPLOY_POLICY}" in
  run-backup|fail) : ;;
  *) fail "SPARK_DR_PREDEPLOY_POLICY inválida: '${SPARK_DR_PREDEPLOY_POLICY}' (use run-backup ou fail)" ;;
esac

# ---------------------------------------------------------------- 1. árvore Git limpa

cd "${REPO_ROOT}"
if [ -n "$(git status --porcelain)" ]; then
  fail "árvore Git suja — commite ou descarte as mudanças antes de fazer deploy (§10)."
fi

GIT_SHA="$(git rev-parse --short=12 HEAD)"
# `gh run list --commit` faz correspondência **exata**: um SHA curto nunca casa com o `headSha`
# completo que o GitHub Actions guarda, e a consulta volta vazia — não "CI não passou", "CI não
# existe". A tag da imagem continua curta (convenção; o próprio digest é o identificador exato do
# que sobe); só o portão de procedência precisa do SHA completo.
GIT_SHA_FULL="$(git rev-parse HEAD)"
log "commit: ${GIT_SHA} (${GIT_SHA_FULL})"

# Árvore limpa garante que a TAG descreve o que está sendo construído; ela não diz nada sobre o
# commit ter sido revisado ou testado. `require_reviewed_commit` é quem exige isso (T18.3.2):
# presente em origin/main e com o workflow `backend` verde. Emergência:
# SPARK_DEPLOY_ALLOW_UNVERIFIED=1.
require_reviewed_commit "${REPO_ROOT}" "${GIT_SHA_FULL}"
log "projeto GCP (infraestrutura): ${SPARK_GCP_PROJECT} | projeto Firebase (FIREBASE_PROJECT_ID): ${SPARK_FIREBASE_PROJECT}"

IMAGE_TAG="${SPARK_AR_IMAGE_BASE}:${GIT_SHA}"

# ---------------------------------------------------------------- 2. build
#
# `--provenance=false --sbom=false` (T18.3 §17): sem eles o BuildKit publica um índice + manifestos
# filhos untagged (atestações) por release, e a regra "apagar untagged antigas" da retenção do
# Artifact Registry apagaria pedaços de uma imagem tagueada. Um release, um digest.

log "build da imagem: ${IMAGE_TAG}"
docker build --provenance=false --sbom=false -t "${IMAGE_TAG}" "${BACKEND_DIR}"

# ---------------------------------------------------------------- 3. push

log "autenticando o Docker no Artifact Registry (${SPARK_AR_HOST})"
gcloud auth configure-docker "${SPARK_AR_HOST}" --quiet --project "${SPARK_GCP_PROJECT}" > /dev/null

log "push: ${IMAGE_TAG}"
docker push "${IMAGE_TAG}"

# ---------------------------------------------------------------- 4. resolver digest
#
# O deploy rastreia até um digest exato, nunca até `latest` nem só a tag mutável do SHA (§2/§10 —
# em tese uma tag pode ser sobrescrita; o digest não pode).

#
# `RepoDigests` lista um digest por repositório que a imagem conhece — com o image store do
# containerd, a tag LOCAL também aparece (`spark-backend@sha256:…`, sem registry), e `index 0`
# devolvia justamente essa (visto no deploy real da T18.3: o Cloud Run tentou puxar de
# `mirror.gcr.io/library/spark-backend`). Só serve a entrada do Artifact Registry.
IMAGE_DIGEST="$(docker inspect --format='{{range .RepoDigests}}{{println .}}{{end}}' "${IMAGE_TAG}" \
  | grep -m1 "^${SPARK_AR_IMAGE_BASE}@sha256:" || true)"
[ -n "${IMAGE_DIGEST}" ] || fail "não foi possível resolver o digest da imagem recém-publicada em ${SPARK_AR_IMAGE_BASE}"
log "digest: ${IMAGE_DIGEST}"

RUNTIME_SA_EMAIL="$(sa_email "${SPARK_SA_RUNTIME}")"
MIGRATOR_SA_EMAIL="$(sa_email "${SPARK_SA_MIGRATOR}")"
SCHEDULER_SA_EMAIL="$(sa_email "${SPARK_SA_SCHEDULER}")"
BACKUP_SA_EMAIL="$(sa_email "${SPARK_SA_BACKUP}")"

# ---------------------------------------------------------------- 5. versões dos secrets (T18.3 §19)
#
# Cada revision/job passa a declarar `secret:<versão>`. Nenhum valor é lido; `resolve_secret_version`
# só lista metadata. Sem versão habilitada o deploy para AQUI — antes de qualquer revision.

DATABASE_URL_VERSION="$(resolve_secret_version "${SPARK_SECRET_DATABASE_URL}")"
DATABASE_URL_DIRECT_VERSION="$(resolve_secret_version "${SPARK_SECRET_DATABASE_URL_DIRECT}")"
GEMINI_API_KEY_VERSION="$(resolve_secret_version "${SPARK_SECRET_GEMINI_API_KEY}")"
HMAC_KEY_VERSION="$(resolve_secret_version "${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}")"

API_SECRETS="DATABASE_URL=${SPARK_SECRET_DATABASE_URL}:${DATABASE_URL_VERSION},GEMINI_API_KEY=${SPARK_SECRET_GEMINI_API_KEY}:${GEMINI_API_KEY_VERSION},ACCOUNT_DELETION_HMAC_KEY=${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}:${HMAC_KEY_VERSION}"
DIRECT_SECRET="DATABASE_URL_DIRECT=${SPARK_SECRET_DATABASE_URL_DIRECT}:${DATABASE_URL_DIRECT_VERSION}"

log "versões de secret desta release (rastreabilidade revision → secret): ${SPARK_SECRET_DATABASE_URL}=v${DATABASE_URL_VERSION} ${SPARK_SECRET_DATABASE_URL_DIRECT}=v${DATABASE_URL_DIRECT_VERSION} ${SPARK_SECRET_GEMINI_API_KEY}=v${GEMINI_API_KEY_VERSION} ${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}=v${HMAC_KEY_VERSION}"

# ---------------------------------------------------------------- 6. Jobs de DR e auditoria: só EXISTÊNCIA aqui (T18.3.2)
#
# O digest novo chega a estes Jobs **depois** da troca de tráfego (seção 12), e a ordem é o
# conserto: antes, eles recebiam o digest logo no começo do deploy, e qualquer aborto posterior —
# gate de DR reprovado, migration FAIL, smoke FAIL — deixava o backup diário e a auditoria rodando
# numa imagem que nunca foi validada, indefinidamente, sem que ninguém percebesse (o deploy falhou;
# quem olharia os Jobs?).
#
# O backup pré-deploy do gate roda com a imagem que o Job **já tem**, e isso é melhor do que o
# comportamento anterior, não pior: ele é o ponto de recuperação DESTE deploy, e um ponto de
# recuperação produzido por código ainda não validado é exatamente o que não se quer ter quando o
# deploy dá errado. A única coisa que precisa acontecer antes do gate é o Job **existir** — no
# primeiro deploy não há imagem anterior para preservar, então ali ele nasce já com esta.
deploy_backup_job() {
  log "Job ${SPARK_RUN_BACKUP_JOB} → digest ${IMAGE_DIGEST}"
  gcloud run jobs deploy "${SPARK_RUN_BACKUP_JOB}" \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --image "${IMAGE_DIGEST}" \
    --command node \
    --args dist/cli/db-backup.js \
    --service-account "${BACKUP_SA_EMAIL}" \
    --cpu "${SPARK_RUN_BACKUP_CPU}" \
    --memory "${SPARK_RUN_BACKUP_MEMORY}" \
    --set-secrets "${DIRECT_SECRET}" \
    --set-env-vars "OBJECT_STORAGE_PROVIDER=gcs,GCS_BUCKET_NAME=${SPARK_GCS_BUCKET},SPARK_DR_RETENTION_COUNT=${SPARK_DR_RETENTION_COUNT},SPARK_GIT_COMMIT=${GIT_SHA},SPARK_IMAGE_DIGEST=${IMAGE_DIGEST##*@},LOG_LEVEL=info" \
    --max-retries 0 \
    --task-timeout "${SPARK_RUN_BACKUP_TIMEOUT}" \
    --quiet
}

deploy_storage_audit_job() {
  log "Job ${SPARK_RUN_STORAGE_AUDIT_JOB} → digest ${IMAGE_DIGEST}"
  gcloud run jobs deploy "${SPARK_RUN_STORAGE_AUDIT_JOB}" \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --image "${IMAGE_DIGEST}" \
    --command node \
    --args dist/cli/storage-audit.js \
    --service-account "${RUNTIME_SA_EMAIL}" \
    --set-secrets "DATABASE_URL=${SPARK_SECRET_DATABASE_URL}:${DATABASE_URL_VERSION}" \
    --set-env-vars "NODE_ENV=production,OBJECT_STORAGE_PROVIDER=gcs,GCS_BUCKET_NAME=${SPARK_GCS_BUCKET},DATABASE_MIGRATION_MODE=verify,BACKGROUND_JOBS_MODE=disabled,LOG_LEVEL=info" \
    --max-retries 0 \
    --task-timeout "${SPARK_RUN_STORAGE_AUDIT_TIMEOUT}" \
    --quiet
}

if resource_exists run jobs describe "${SPARK_RUN_BACKUP_JOB}" --region "${SPARK_GCP_REGION}"; then
  log "${SPARK_RUN_BACKUP_JOB} já existe — mantém a imagem validada até a troca de tráfego (§T18.3.2)"
else
  log "${SPARK_RUN_BACKUP_JOB} ainda não existe — criando com o digest desta release (não há imagem anterior a preservar)"
  deploy_backup_job
fi

# ---------------------------------------------------------------- 7. gate de DR (T18.3 §10)
#
# Nenhuma migration roda sem um backup independente e válido dentro da janela. Determinístico:
# válido = manifesto íntegro + dump presente com o tamanho declarado (`lib.dr.sh`), a mesma
# definição do backend e do auditor.

MAX_AGE_SECONDS=$(( SPARK_DR_MAX_BACKUP_AGE_HOURS * 3600 ))
dr_gate_satisfied() {
  local latest backup_id created age
  if latest="$(dr_latest_valid_backup)"; then
    backup_id="${latest%% *}"
    created="${latest##* }"
    age="$(dr_age_seconds "${created}")"
    if [ "${age}" -le "${MAX_AGE_SECONDS}" ]; then
      log "gate de DR: backup válido ${backup_id} com ${age}s (≤ ${MAX_AGE_SECONDS}s)"
      return 0
    fi
    log "gate de DR: o backup válido mais recente (${backup_id}) tem ${age}s — acima da janela de ${MAX_AGE_SECONDS}s"
    return 1
  fi
  log "gate de DR: nenhum backup válido em gs://${SPARK_GCS_BUCKET}/${SPARK_DR_PREFIX}"
  return 1
}

if ! dr_gate_satisfied; then
  case "${SPARK_DR_PREDEPLOY_POLICY}" in
    fail)
      fail "gate de DR reprovado e SPARK_DR_PREDEPLOY_POLICY=fail — nenhuma migration roda sem backup recente. Rode ops/gcp/dr-backup-now.sh e repita o deploy."
      ;;
    run-backup)
      log "gate de DR: executando ${SPARK_RUN_BACKUP_JOB} agora e aguardando SUCCESS (SPARK_DR_PREDEPLOY_POLICY=run-backup)"
      dr_run_backup_job_and_wait || fail "o backup de DR pré-deploy FALHOU — a migration NÃO roda sem ponto de recuperação."
      dr_gate_satisfied || fail "o backup pré-deploy terminou mas nenhum backup válido apareceu na janela — deploy abortado."
      ;;
  esac
fi

# ---------------------------------------------------------------- 8. migration job — mesmo digest

log "atualizando o Job ${SPARK_RUN_MIGRATE_JOB} com o digest ${IMAGE_DIGEST}"
gcloud run jobs deploy "${SPARK_RUN_MIGRATE_JOB}" \
  --project "${SPARK_GCP_PROJECT}" \
  --region "${SPARK_GCP_REGION}" \
  --image "${IMAGE_DIGEST}" \
  --command node \
  --args dist/cli/migrate-database.js \
  --service-account "${MIGRATOR_SA_EMAIL}" \
  --set-secrets "${DIRECT_SECRET}" \
  --max-retries 0 \
  --task-timeout 300 \
  --quiet

log "executando ${SPARK_RUN_MIGRATE_JOB} e aguardando SUCCESS — falha aqui bloqueia o deploy (§10)"
if ! gcloud run jobs execute "${SPARK_RUN_MIGRATE_JOB}" \
  --project "${SPARK_GCP_PROJECT}" \
  --region "${SPARK_GCP_REGION}" \
  --wait; then
  fail "migration job FALHOU — a API NÃO recebe este digest. Corrija a migration antes de repetir o deploy."
fi
log "migration job: SUCCESS"

# ---------------------------------------------------------------- 9. candidate da API (ou primeiro deploy — T18.2.1)
#
# `GOOGLE_APPLICATION_CREDENTIALS` e `DATABASE_URL_DIRECT` NUNCA aparecem nas duas listas abaixo —
# ausência deliberada, não esquecimento (§16/§21/§22 do enunciado da T18.2). `FIREBASE_PROJECT_ID`
# usa SPARK_FIREBASE_PROJECT, não SPARK_GCP_PROJECT (T18.2.1) — os dois só coincidem quando a
# instalação usa um único projeto.
#
# Um lugar só para a imagem/configuração validada: o candidate (deploy seguinte), o serviço de
# validação e o primeiro deploy real usam exatamente esta função — nunca três listas de flags que
# podem divergir entre si. O segundo argumento decide só o acesso (`public`/`private`): a
# configuração do processo é idêntica nos dois casos.
deploy_api_revision() {
  local service="$1" access="$2"
  shift 2
  local access_flag
  case "${access}" in
    public)  access_flag=--allow-unauthenticated ;;
    private) access_flag=--no-allow-unauthenticated ;;
    *) fail "deploy_api_revision: acesso desconhecido '${access}'" ;;
  esac
  gcloud run deploy "${service}" \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --image "${IMAGE_DIGEST}" \
    --service-account "${RUNTIME_SA_EMAIL}" \
    --port "${SPARK_RUN_PORT}" \
    --cpu "${SPARK_RUN_API_CPU}" \
    --memory "${SPARK_RUN_API_MEMORY}" \
    --min-instances "${SPARK_RUN_API_MIN_INSTANCES}" \
    --max-instances "${SPARK_RUN_API_MAX_INSTANCES}" \
    --concurrency "${SPARK_RUN_API_CONCURRENCY}" \
    --timeout "${SPARK_RUN_API_TIMEOUT}" \
    "${access_flag}" \
    --set-secrets "${API_SECRETS}" \
    --set-env-vars "NODE_ENV=production,DATABASE_MIGRATION_MODE=verify,OBJECT_STORAGE_PROVIDER=gcs,GCS_BUCKET_NAME=${SPARK_GCS_BUCKET},REQUIRE_FIREBASE_ADMIN=true,FIREBASE_ADMIN_CREDENTIAL_MODE=adc,FIREBASE_PROJECT_ID=${SPARK_FIREBASE_PROJECT},AI_ENABLED=true,REQUIRE_GEMINI=false,SYNC_WRITE_ENABLED=true,MAINTENANCE_MODE=false,BACKGROUND_JOBS_MODE=disabled,SOCIAL_PUSH_ENABLED=${SPARK_SOCIAL_PUSH_ENABLED:-false},DATABASE_POOL_MIN=${SPARK_DATABASE_POOL_MIN},DATABASE_POOL_MAX=${SPARK_DATABASE_POOL_MAX},DATABASE_CONNECTION_TIMEOUT_MS=${SPARK_DATABASE_CONNECTION_TIMEOUT_MS},SHUTDOWN_TIMEOUT_MS=${SPARK_SHUTDOWN_TIMEOUT_MS}" \
    --quiet \
    "$@"
}

api_service_url() {
  gcloud run services describe "$1" \
    --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
    --format='value(status.url)'
}

if resource_exists run services describe "${SPARK_RUN_API_SERVICE}" --region "${SPARK_GCP_REGION}"; then
  # ---------------------------------------------------------------- deploy seguinte (fluxo original)

  log "${SPARK_RUN_API_SERVICE} já existe — deploy do candidate (--no-traffic, tag=candidate)"
  deploy_api_revision "${SPARK_RUN_API_SERVICE}" public --no-traffic --tag candidate

  # A URL de uma revision com `--tag candidate` segue o padrão documentado do Cloud Run:
  # `https://<tag>---<url-padrão-do-serviço-sem-o-https://>`. Mais direto e determinístico do que
  # tentar extrair `status.traffic[].url` por tag via `--filter` num `describe` (que descreve **um**
  # recurso, não uma lista, e não filtra elementos de array de forma confiável entre versões do
  # `gcloud`).
  SERVICE_URL="$(api_service_url "${SPARK_RUN_API_SERVICE}")"
  CANDIDATE_URL="${SERVICE_URL/https:\/\//https://candidate---}"
  log "candidate URL: ${CANDIDATE_URL}"

  log "rodando smoke contra o candidate — falha aqui bloqueia a troca de tráfego (§10/§54)"
  if ! "${SCRIPT_DIR}/smoke-cloud-run.sh" "${CANDIDATE_URL}"; then
    fail "smoke do candidate FALHOU — tráfego antigo permanece. Corrija e repita o deploy."
  fi

  log "smoke PASS — movendo 100% do tráfego para o candidate"
  PREVIOUS_REVISION="$(gcloud run services describe "${SPARK_RUN_API_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
    --format='value(status.traffic[0].revisionName)')"
  log "revision anterior, para rollback se necessário: ${PREVIOUS_REVISION}"
  log "  rollback: ops/gcp/rollback-cloud-run.sh ${PREVIOUS_REVISION}"

  gcloud run services update-traffic "${SPARK_RUN_API_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --to-tags candidate=100

  log "100% do tráfego em ${SPARK_RUN_API_SERVICE} está no digest ${IMAGE_DIGEST}"
else
  # ---------------------------------------------------------------- primeiro deploy (T18.2.1; privado desde a T18.3 §21)
  #
  # `--no-traffic` não tem efeito na primeira revision de um serviço novo (o Cloud Run sempre
  # roteia 100% do único tráfego que existe para ela) — então o par candidate→smoke→promove
  # tráfego não protege nada aqui: o tráfego "de produção" já estaria naquela revision antes do
  # smoke rodar. A mesma imagem/configuração é validada num serviço TEMPORÁRIO primeiro; só depois
  # do smoke passar nele é que ${SPARK_RUN_API_SERVICE} é criado de verdade.
  #
  # O temporário é PRIVADO: só quem tem run.invoker (o operador que roda este deploy) o alcança, e o
  # smoke leva o identity token dele em `X-Serverless-Authorization` — o Cloud Run valida e remove o
  # header antes de entregar a requisição ao container, então `Authorization` continua livre para o
  # Firebase Bearer e as expectativas do smoke (401 sem token, 200 com token real) não mudam.

  log "${SPARK_RUN_API_SERVICE} ainda não existe — primeiro deploy: validando a mesma imagem/configuração em ${SPARK_RUN_API_VALIDATE_SERVICE} (privado) antes de criar o serviço real"
  deploy_api_revision "${SPARK_RUN_API_VALIDATE_SERVICE}" private

  VALIDATE_URL="$(api_service_url "${SPARK_RUN_API_VALIDATE_SERVICE}")"
  log "URL de validação (privada): ${VALIDATE_URL}"

  log "rodando smoke contra o serviço de validação — falha aqui impede a criação de ${SPARK_RUN_API_SERVICE} (§10/§54)"
  # O token nunca é impresso nem passa por argv: vai por variável de ambiente do processo filho.
  if ! SPARK_SMOKE_INVOKER_TOKEN="$(gcloud auth print-identity-token)" \
       "${SCRIPT_DIR}/smoke-cloud-run.sh" "${VALIDATE_URL}"; then
    log "removendo ${SPARK_RUN_API_VALIDATE_SERVICE} antes de abortar"
    gcloud run services delete "${SPARK_RUN_API_VALIDATE_SERVICE}" \
      --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" --quiet || true
    fail "smoke do serviço de validação FALHOU — ${SPARK_RUN_API_SERVICE} NÃO foi criado. Corrija e repita o deploy."
  fi

  log "smoke PASS — removendo ${SPARK_RUN_API_VALIDATE_SERVICE} e criando ${SPARK_RUN_API_SERVICE}"
  gcloud run services delete "${SPARK_RUN_API_VALIDATE_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" --quiet

  # Mesma imagem/configuração que acabou de passar no smoke acima — `--no-traffic`/`--tag` não são
  # passados porque não têm efeito no primeiro deploy de um serviço (a única revision recebe 100%
  # do tráfego de qualquer forma).
  deploy_api_revision "${SPARK_RUN_API_SERVICE}" public

  log "primeiro deploy de ${SPARK_RUN_API_SERVICE} concluído — 100% do tráfego no digest ${IMAGE_DIGEST} (já validado antes de criar o serviço)"
fi

# ---------------------------------------------------------------- 10. Jobs recebem o digest JÁ validado (T18.3.2)
#
# Aqui, e não antes: a imagem passou pela migration e pelo smoke, e 100% do tráfego já está nela.
# `jobs deploy` cria ou atualiza — de um jeito ou de outro, o backup diário e a auditoria passam a
# rodar exatamente o que está servindo.

deploy_backup_job
deploy_storage_audit_job

# ---------------------------------------------------------------- 11. spark-maintenance (mesmo digest, privado)

if [ "${SKIP_MAINTENANCE}" -eq 1 ]; then
  log "--skip-maintenance: pulando o deploy de ${SPARK_RUN_MAINTENANCE_SERVICE} e os jobs do Scheduler"
else
  log "deploy de ${SPARK_RUN_MAINTENANCE_SERVICE} — mesma imagem, comando dedicado, privado"
  gcloud run deploy "${SPARK_RUN_MAINTENANCE_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --image "${IMAGE_DIGEST}" \
    --command node \
    --args dist/maintenance-main.js \
    --no-allow-unauthenticated \
    --service-account "${RUNTIME_SA_EMAIL}" \
    --port "${SPARK_RUN_PORT}" \
    --cpu "${SPARK_RUN_MAINTENANCE_CPU}" \
    --memory "${SPARK_RUN_MAINTENANCE_MEMORY}" \
    --min-instances "${SPARK_RUN_MAINTENANCE_MIN_INSTANCES}" \
    --max-instances "${SPARK_RUN_MAINTENANCE_MAX_INSTANCES}" \
    --concurrency "${SPARK_RUN_MAINTENANCE_CONCURRENCY}" \
    --set-secrets "${API_SECRETS}" \
    --set-env-vars "NODE_ENV=production,DATABASE_MIGRATION_MODE=verify,OBJECT_STORAGE_PROVIDER=gcs,GCS_BUCKET_NAME=${SPARK_GCS_BUCKET},REQUIRE_FIREBASE_ADMIN=true,FIREBASE_ADMIN_CREDENTIAL_MODE=adc,FIREBASE_PROJECT_ID=${SPARK_FIREBASE_PROJECT},AI_ENABLED=false,REQUIRE_GEMINI=false,SYNC_WRITE_ENABLED=true,MAINTENANCE_MODE=false,BACKGROUND_JOBS_MODE=disabled,SOCIAL_PUSH_ENABLED=${SPARK_SOCIAL_PUSH_ENABLED:-false},DATABASE_POOL_MIN=${SPARK_DATABASE_POOL_MIN},DATABASE_POOL_MAX=${SPARK_DATABASE_POOL_MAX},DATABASE_CONNECTION_TIMEOUT_MS=${SPARK_DATABASE_CONNECTION_TIMEOUT_MS},SHUTDOWN_TIMEOUT_MS=${SPARK_SHUTDOWN_TIMEOUT_MS}" \
    --quiet

  log "garantindo run.invoker de ${SPARK_SA_SCHEDULER} sobre ${SPARK_RUN_MAINTENANCE_SERVICE} (§35)"
  gcloud run services add-iam-policy-binding "${SPARK_RUN_MAINTENANCE_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --member "serviceAccount:${SCHEDULER_SA_EMAIL}" \
    --role roles/run.invoker \
    > /dev/null

  MAINTENANCE_URL="$(gcloud run services describe "${SPARK_RUN_MAINTENANCE_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
    --format='value(status.url)')"

  log "garantindo o job do Cloud Scheduler (${SPARK_SCHEDULER_JOB}, cron: ${SPARK_SCHEDULER_CRON})"
  if resource_exists scheduler jobs describe "${SPARK_SCHEDULER_JOB}" --location "${SPARK_GCP_REGION}"; then
    gcloud scheduler jobs update http "${SPARK_SCHEDULER_JOB}" \
      --project "${SPARK_GCP_PROJECT}" \
      --location "${SPARK_GCP_REGION}" \
      --schedule "${SPARK_SCHEDULER_CRON}" \
      --uri "${MAINTENANCE_URL}/internal/maintenance/run" \
      --http-method POST \
      --oidc-service-account-email "${SCHEDULER_SA_EMAIL}" \
      --oidc-token-audience "${MAINTENANCE_URL}" \
      --quiet
  else
    gcloud scheduler jobs create http "${SPARK_SCHEDULER_JOB}" \
      --project "${SPARK_GCP_PROJECT}" \
      --location "${SPARK_GCP_REGION}" \
      --schedule "${SPARK_SCHEDULER_CRON}" \
      --uri "${MAINTENANCE_URL}/internal/maintenance/run" \
      --http-method POST \
      --oidc-service-account-email "${SCHEDULER_SA_EMAIL}" \
      --oidc-token-audience "${MAINTENANCE_URL}"
  fi

  # ---------------------------------------------------------------- 12. Scheduler do backup de DR (T18.3 §9)
  #
  # Um Cloud Run Job é disparado pela API de administração (`jobs.run`), não por HTTP no container:
  # o Scheduler chama `run.googleapis.com` com um token OAuth da Service Account do Scheduler, que
  # para isso precisa de `roles/run.invoker` SOBRE O JOB — e de nada mais.
  log "garantindo run.invoker de ${SPARK_SA_SCHEDULER} sobre o Job ${SPARK_RUN_BACKUP_JOB}"
  gcloud run jobs add-iam-policy-binding "${SPARK_RUN_BACKUP_JOB}" \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --member "serviceAccount:${SCHEDULER_SA_EMAIL}" \
    --role roles/run.invoker \
    > /dev/null

  BACKUP_RUN_URI="https://${SPARK_GCP_REGION}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${SPARK_GCP_PROJECT}/jobs/${SPARK_RUN_BACKUP_JOB}:run"
  log "garantindo o job do Cloud Scheduler do backup de DR (${SPARK_BACKUP_SCHEDULER_JOB}, cron: ${SPARK_BACKUP_SCHEDULER_CRON})"
  if resource_exists scheduler jobs describe "${SPARK_BACKUP_SCHEDULER_JOB}" --location "${SPARK_GCP_REGION}"; then
    gcloud scheduler jobs update http "${SPARK_BACKUP_SCHEDULER_JOB}" \
      --project "${SPARK_GCP_PROJECT}" \
      --location "${SPARK_GCP_REGION}" \
      --schedule "${SPARK_BACKUP_SCHEDULER_CRON}" \
      --uri "${BACKUP_RUN_URI}" \
      --http-method POST \
      --oauth-service-account-email "${SCHEDULER_SA_EMAIL}" \
      --quiet
  else
    gcloud scheduler jobs create http "${SPARK_BACKUP_SCHEDULER_JOB}" \
      --project "${SPARK_GCP_PROJECT}" \
      --location "${SPARK_GCP_REGION}" \
      --schedule "${SPARK_BACKUP_SCHEDULER_CRON}" \
      --uri "${BACKUP_RUN_URI}" \
      --http-method POST \
      --oauth-service-account-email "${SCHEDULER_SA_EMAIL}"
  fi
fi

log "deploy concluído — imagem ${IMAGE_DIGEST}, commit ${GIT_SHA}"
log "secrets pinados nesta release: ${SPARK_SECRET_DATABASE_URL}=v${DATABASE_URL_VERSION} ${SPARK_SECRET_DATABASE_URL_DIRECT}=v${DATABASE_URL_DIRECT_VERSION} ${SPARK_SECRET_GEMINI_API_KEY}=v${GEMINI_API_KEY_VERSION} ${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}=v${HMAC_KEY_VERSION}"
