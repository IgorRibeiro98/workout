#!/usr/bin/env bash
# Deploy reproduzível do Spark Backend no Cloud Run (T18.2 §5/§10).
#
#   árvore Git limpa
#         ↓
#   build da imagem (tag = git SHA)
#         ↓
#   push para o Artifact Registry
#         ↓
#   resolve o digest exato
#         ↓
#   atualiza o Job spark-db-migrate com o MESMO digest
#         ↓
#   executa o Job e espera SUCCESS
#         ↓
#   SPARK_RUN_API_SERVICE já existe?
#     SIM (deploy seguinte)                    NÃO (primeiro deploy — T18.2.1)
#     ─────────────────────                    ──────────────────────────────
#     deploy do candidate, SEM tráfego          deploy da MESMA imagem/config num serviço
#     (--no-traffic --tag candidate)            TEMPORÁRIO (SPARK_RUN_API_VALIDATE_SERVICE)
#         ↓                                         ↓
#     smoke contra o candidate                  smoke contra o serviço temporário
#         ↓                                         ↓
#     100% do tráfego para o candidate          remove o serviço temporário, cria
#                                                SPARK_RUN_API_SERVICE de verdade (já validado)
#         ↓                                         ↓
#              deploy do spark-maintenance com o MESMO digest (privado, sem tráfego a mover)
#
# Por que o primeiro deploy é um caminho à parte: `--no-traffic` não tem efeito na primeira
# revision de um serviço Cloud Run novo — ela recebe 100% do (único) tráfego que existe,
# independente da flag. O par candidate→smoke→promove tráfego protege uma SUBSTITUIÇÃO; no
# primeiro deploy não há nada para substituir, então validar precisa acontecer ANTES de
# SPARK_RUN_API_SERVICE existir — daí o serviço temporário.
#
# Falha cedo: migration FAIL, candidate/validação health FAIL ou smoke FAIL abortam antes de
# qualquer tráfego real ser movido ou de SPARK_RUN_API_SERVICE ser criado (§5/§10 — bloqueantes do
# enunciado da T18.2; T18.2.1 estende a garantia ao primeiro deploy).
#
# Uso:
#   SPARK_GCP_PROJECT=meu-projeto ops/gcp/deploy-cloud-run.sh
#   ops/gcp/deploy-cloud-run.sh --skip-maintenance   # só a API + migration, sem tocar spark-maintenance

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BACKEND_DIR="${REPO_ROOT}/backend"

require_cmd gcloud
require_cmd docker
require_cmd git

SKIP_MAINTENANCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-maintenance) SKIP_MAINTENANCE=1; shift ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

# ---------------------------------------------------------------- 1. árvore Git limpa

cd "${REPO_ROOT}"
if [ -n "$(git status --porcelain)" ]; then
  fail "árvore Git suja — commite ou descarte as mudanças antes de fazer deploy (§10)."
fi

GIT_SHA="$(git rev-parse --short=12 HEAD)"
log "commit: ${GIT_SHA}"
log "projeto GCP (infraestrutura): ${SPARK_GCP_PROJECT} | projeto Firebase (FIREBASE_PROJECT_ID): ${SPARK_FIREBASE_PROJECT}"

IMAGE_TAG="${SPARK_AR_IMAGE_BASE}:${GIT_SHA}"

# ---------------------------------------------------------------- 2. build

log "build da imagem: ${IMAGE_TAG}"
docker build -t "${IMAGE_TAG}" "${BACKEND_DIR}"

# ---------------------------------------------------------------- 3. push

log "autenticando o Docker no Artifact Registry (${SPARK_AR_HOST})"
gcloud auth configure-docker "${SPARK_AR_HOST}" --quiet --project "${SPARK_GCP_PROJECT}" > /dev/null

log "push: ${IMAGE_TAG}"
docker push "${IMAGE_TAG}"

# ---------------------------------------------------------------- 4. resolver digest
#
# O deploy rastreia até um digest exato, nunca até `latest` nem só a tag mutável do SHA (§2/§10 —
# em tese uma tag pode ser sobrescrita; o digest não pode).

IMAGE_DIGEST="$(docker inspect --format='{{index .RepoDigests 0}}' "${IMAGE_TAG}")"
[ -n "${IMAGE_DIGEST}" ] || fail "não foi possível resolver o digest da imagem recém-publicada"
log "digest: ${IMAGE_DIGEST}"

RUNTIME_SA_EMAIL="$(sa_email "${SPARK_SA_RUNTIME}")"
MIGRATOR_SA_EMAIL="$(sa_email "${SPARK_SA_MIGRATOR}")"
SCHEDULER_SA_EMAIL="$(sa_email "${SPARK_SA_SCHEDULER}")"

# ---------------------------------------------------------------- 5. migration job — mesmo digest

log "atualizando o Job ${SPARK_RUN_MIGRATE_JOB} com o digest ${IMAGE_DIGEST}"
gcloud run jobs deploy "${SPARK_RUN_MIGRATE_JOB}" \
  --project "${SPARK_GCP_PROJECT}" \
  --region "${SPARK_GCP_REGION}" \
  --image "${IMAGE_DIGEST}" \
  --command node \
  --args dist/cli/migrate-database.js \
  --service-account "${MIGRATOR_SA_EMAIL}" \
  --set-secrets "DATABASE_URL_DIRECT=${SPARK_SECRET_DATABASE_URL_DIRECT}:latest" \
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

# ---------------------------------------------------------------- 6. candidate da API (ou primeiro deploy — T18.2.1)
#
# `GOOGLE_APPLICATION_CREDENTIALS` e `DATABASE_URL_DIRECT` NUNCA aparecem nas duas listas abaixo —
# ausência deliberada, não esquecimento (§16/§21/§22 do enunciado da T18.2). `FIREBASE_PROJECT_ID`
# usa SPARK_FIREBASE_PROJECT, não SPARK_GCP_PROJECT (T18.2.1) — os dois só coincidem quando a
# instalação usa um único projeto.
#
# Um lugar só para a imagem/configuração validada: o candidate (deploy seguinte), o serviço de
# validação e o primeiro deploy real usam exatamente esta função — nunca três listas de flags que
# podem divergir entre si.
deploy_api_revision() {
  local service="$1"
  shift
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
    --allow-unauthenticated \
    --set-secrets "DATABASE_URL=${SPARK_SECRET_DATABASE_URL}:latest,GEMINI_API_KEY=${SPARK_SECRET_GEMINI_API_KEY}:latest,ACCOUNT_DELETION_HMAC_KEY=${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}:latest" \
    --set-env-vars "NODE_ENV=production,DATABASE_MIGRATION_MODE=verify,OBJECT_STORAGE_PROVIDER=gcs,GCS_BUCKET_NAME=${SPARK_GCS_BUCKET},REQUIRE_FIREBASE_ADMIN=true,FIREBASE_ADMIN_CREDENTIAL_MODE=adc,FIREBASE_PROJECT_ID=${SPARK_FIREBASE_PROJECT},AI_ENABLED=true,REQUIRE_GEMINI=false,SYNC_WRITE_ENABLED=true,MAINTENANCE_MODE=false,BACKGROUND_JOBS_MODE=disabled,SOCIAL_PUSH_ENABLED=${SPARK_SOCIAL_PUSH_ENABLED:-false},DATABASE_POOL_MIN=${SPARK_DATABASE_POOL_MIN},DATABASE_POOL_MAX=${SPARK_DATABASE_POOL_MAX}" \
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
  deploy_api_revision "${SPARK_RUN_API_SERVICE}" --no-traffic --tag candidate

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
  # ---------------------------------------------------------------- primeiro deploy (T18.2.1)
  #
  # `--no-traffic` não tem efeito na primeira revision de um serviço novo (o Cloud Run sempre
  # roteia 100% do único tráfego que existe para ela) — então o par candidate→smoke→promove
  # tráfego não protege nada aqui: o tráfego "de produção" já estaria naquela revision antes do
  # smoke rodar. A mesma imagem/configuração é validada num serviço TEMPORÁRIO primeiro; só depois
  # do smoke passar nele é que ${SPARK_RUN_API_SERVICE} é criado de verdade.

  log "${SPARK_RUN_API_SERVICE} ainda não existe — primeiro deploy: validando a mesma imagem/configuração em ${SPARK_RUN_API_VALIDATE_SERVICE} antes de criar o serviço real"
  deploy_api_revision "${SPARK_RUN_API_VALIDATE_SERVICE}"

  VALIDATE_URL="$(api_service_url "${SPARK_RUN_API_VALIDATE_SERVICE}")"
  log "URL de validação: ${VALIDATE_URL}"

  log "rodando smoke contra o serviço de validação — falha aqui impede a criação de ${SPARK_RUN_API_SERVICE} (§10/§54)"
  if ! "${SCRIPT_DIR}/smoke-cloud-run.sh" "${VALIDATE_URL}"; then
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
  deploy_api_revision "${SPARK_RUN_API_SERVICE}"

  log "primeiro deploy de ${SPARK_RUN_API_SERVICE} concluído — 100% do tráfego no digest ${IMAGE_DIGEST} (já validado antes de criar o serviço)"
fi

# ---------------------------------------------------------------- 9. spark-maintenance (mesmo digest, privado)

if [ "${SKIP_MAINTENANCE}" -eq 1 ]; then
  log "--skip-maintenance: pulando o deploy de ${SPARK_RUN_MAINTENANCE_SERVICE}"
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
    --set-secrets "DATABASE_URL=${SPARK_SECRET_DATABASE_URL}:latest,GEMINI_API_KEY=${SPARK_SECRET_GEMINI_API_KEY}:latest,ACCOUNT_DELETION_HMAC_KEY=${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}:latest" \
    --set-env-vars "NODE_ENV=production,DATABASE_MIGRATION_MODE=verify,OBJECT_STORAGE_PROVIDER=gcs,GCS_BUCKET_NAME=${SPARK_GCS_BUCKET},REQUIRE_FIREBASE_ADMIN=true,FIREBASE_ADMIN_CREDENTIAL_MODE=adc,FIREBASE_PROJECT_ID=${SPARK_FIREBASE_PROJECT},AI_ENABLED=false,REQUIRE_GEMINI=false,SYNC_WRITE_ENABLED=true,MAINTENANCE_MODE=false,BACKGROUND_JOBS_MODE=disabled,SOCIAL_PUSH_ENABLED=${SPARK_SOCIAL_PUSH_ENABLED:-false},DATABASE_POOL_MIN=${SPARK_DATABASE_POOL_MIN},DATABASE_POOL_MAX=${SPARK_DATABASE_POOL_MAX}" \
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
fi

log "deploy concluído — imagem ${IMAGE_DIGEST}, commit ${GIT_SHA}"
