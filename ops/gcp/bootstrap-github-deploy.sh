#!/usr/bin/env bash
# Bootstrap idempotente da integração GitHub Actions → Cloud Run via Workload Identity Federation
# (T18.3.2).
#
# Responsabilidade única: garantir que o GitHub Actions consegue publicar produção SEM chave JSON
# de Service Account, com o IAM mínimo necessário para `ops/gcp/deploy-cloud-run.sh` — nunca mais
# que isso. Ele nunca builda imagem, nunca faz deploy e nunca toca nos quatro Service Accounts de
# WORKLOAD (runtime/migrator/scheduler/backup) além de conceder `serviceAccountUser` sobre eles ao
# deployer. Rodar este script de novo, sem mudar nada, não deve fazer nada de novo — recurso
# existente é reutilizado; recurso ausente é criado; um Provider que já existe mas com uma
# configuração de segurança DIFERENTE da esperada é DRIFT e aborta (nunca corrigido em silêncio).
#
# Uso:
#   SPARK_GCP_PROJECT=project-47b17b25-909d-4ae8-943 ops/gcp/bootstrap-github-deploy.sh
#   ops/gcp/bootstrap-github-deploy.sh --verify   # só lê e reporta PASS/DRIFT/NOT_VERIFIED
#
# Pré-requisitos: `gcloud` autenticado com permissão para habilitar APIs, criar Workload Identity
# Pools/Providers, criar Service Accounts e conceder IAM no projeto de infraestrutura
# (SPARK_GCP_PROJECT). Rode `ops/gcp/bootstrap-cloud-run.sh` primeiro — este script assume que o
# Artifact Registry, os quatro Service Accounts de workload, os quatro secrets e o bucket já
# existem, e só concede acesso de LEITURA/USO mínimo a eles para a identidade de deploy.
#
# Este script NUNCA cria uma chave de Service Account e nunca concede Owner, Editor,
# secretAccessor ou serviceAccountKeyAdmin ao deployer — verificado também por
# `ops/tests/bootstrap-github-deploy.test.sh` e pelo modo `--verify`.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

require_cmd gcloud

VERIFY_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --verify) VERIFY_ONLY=1; shift ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

log "projeto GCP: ${SPARK_GCP_PROJECT} | repositório autorizado: ${SPARK_GITHUB_REPO} | ref: ${SPARK_GITHUB_DEPLOY_REF} | environment: ${SPARK_GITHUB_DEPLOY_ENVIRONMENT}"

gcloud projects describe "${SPARK_GCP_PROJECT}" > /dev/null \
  || fail "projeto GCP inexistente ou sem acesso: ${SPARK_GCP_PROJECT}"

PROJECT_NUMBER="$(gcloud projects describe "${SPARK_GCP_PROJECT}" --format='value(projectNumber)')"
case "${PROJECT_NUMBER}" in
  ''|*[!0-9]*) fail "não foi possível resolver o project number de ${SPARK_GCP_PROJECT} (obtido: '${PROJECT_NUMBER}')" ;;
esac

POOL_ID="${SPARK_WIF_POOL}"
PROVIDER_ID="${SPARK_WIF_PROVIDER}"
DEPLOYER_SA_EMAIL="$(sa_email "${SPARK_SA_GITHUB_DEPLOYER}")"
DEPLOYER_MEMBER="serviceAccount:${DEPLOYER_SA_EMAIL}"
POOL_RESOURCE="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL_ID}"
PROVIDER_RESOURCE="${POOL_RESOURCE}/providers/${PROVIDER_ID}"
EXPECTED_ISSUER="https://token.actions.githubusercontent.com"
# As TRÊS restrições exigidas pelo enunciado (repositório + ref + Environment do GitHub) — nunca
# confiar no pool inteiro nem só no repositório. `assertion.environment` só existe no token OIDC
# quando o job declara `environment:` — é exatamente o que `deploy-backend.yml` faz.
EXPECTED_CONDITION="assertion.repository == '${SPARK_GITHUB_REPO}' && assertion.ref == '${SPARK_GITHUB_DEPLOY_REF}' && assertion.environment == '${SPARK_GITHUB_DEPLOY_ENVIRONMENT}'"
ATTRIBUTE_MAPPING="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref,attribute.environment=assertion.environment"
# `principalSet`, nunca `principal://.../subject/*`: a identidade externa autorizada é "qualquer
# token deste repositório que passe na condição acima", não um subject único e nem o pool inteiro.
PRINCIPAL_SET="principalSet://iam.googleapis.com/${POOL_RESOURCE}/attribute.repository/${SPARK_GITHUB_REPO}"

# Colapsa espaços internos E remove espaço de borda — o GCP real devolve `attributeCondition` com um
# espaço à direita que `tr -s` sozinho preserva (visto no bootstrap real desta tarefa), e isso não
# pode virar um DRIFT falso nem, pior, mascarar uma condição realmente diferente.
normalize_condition() {
  printf '%s' "$1" | tr -s '[:space:]' ' ' | sed -e 's/^ *//' -e 's/ *$//'
}

RUNTIME_SA_EMAIL="$(sa_email "${SPARK_SA_RUNTIME}")"
MIGRATOR_SA_EMAIL="$(sa_email "${SPARK_SA_MIGRATOR}")"
SCHEDULER_SA_EMAIL="$(sa_email "${SPARK_SA_SCHEDULER}")"
BACKUP_SA_EMAIL="$(sa_email "${SPARK_SA_BACKUP}")"

# ---------------------------------------------------------------- --verify: só leitura
#
# Reaproveita o vocabulário de veredito das auditorias (PASS/DRIFT/NOT_VERIFIED) em vez de inventar
# um terceiro formato de saída. Nunca concede nem corrige nada.
if [ "${VERIFY_ONLY}" -eq 1 ]; then
  require_cmd jq
  # shellcheck source=ops/gcp/lib.audit.sh
  . "${SCRIPT_DIR}/lib.audit.sh"

  audit_section "Workload Identity Pool"
  if POOL_STATE="$(gcloud iam workload-identity-pools describe "${POOL_ID}" --project "${SPARK_GCP_PROJECT}" --location=global --format='value(state)' 2> /dev/null)"; then
    audit_expect "pool ${POOL_ID} state" "ACTIVE" "${POOL_STATE}"
  else
    audit_drift "pool ${POOL_ID}: inexistente ou sem acesso"
  fi

  audit_section "Provider"
  if PROVIDER_JSON="$(gcloud_json_or_empty iam workload-identity-pools providers describe "${PROVIDER_ID}" --project "${SPARK_GCP_PROJECT}" --workload-identity-pool="${POOL_ID}" --location=global)"; then
    if [ -n "${PROVIDER_JSON}" ]; then
      audit_expect "provider ${PROVIDER_ID} state" "ACTIVE" "$(printf '%s' "${PROVIDER_JSON}" | jq -r '.state // ""')"
      audit_expect "provider ${PROVIDER_ID} issuer" "${EXPECTED_ISSUER}" "$(printf '%s' "${PROVIDER_JSON}" | jq -r '.oidc.issuerUri // ""')"
      REAL_CONDITION="$(normalize_condition "$(printf '%s' "${PROVIDER_JSON}" | jq -r '.attributeCondition // ""')")"
      NORM_EXPECTED_CONDITION="$(normalize_condition "${EXPECTED_CONDITION}")"
      audit_section "Repo condition"
      audit_expect "provider ${PROVIDER_ID} attributeCondition" "${NORM_EXPECTED_CONDITION}" "${REAL_CONDITION}"
    else
      audit_not_verified "provider ${PROVIDER_ID}: sem acesso ou resposta vazia"
    fi
  else
    audit_drift "provider ${PROVIDER_ID}: inexistente"
  fi

  audit_section "Deploy SA"
  if gcloud iam service-accounts describe "${DEPLOYER_SA_EMAIL}" --project "${SPARK_GCP_PROJECT}" > /dev/null 2>&1; then
    audit_pass "service account ${DEPLOYER_SA_EMAIL} existe"
  else
    audit_drift "service account ${DEPLOYER_SA_EMAIL}: inexistente"
  fi

  audit_section "SA impersonation"
  SA_POLICY="$(gcloud_json_or_empty iam service-accounts get-iam-policy "${DEPLOYER_SA_EMAIL}" --project "${SPARK_GCP_PROJECT}")"
  if [ -n "${SA_POLICY}" ]; then
    MEMBERS="$(printf '%s' "${SA_POLICY}" | jq -r '[.bindings[]? | select(.role == "roles/iam.workloadIdentityUser") | .members[]] | sort | join(",")')"
    audit_expect "workloadIdentityUser em ${SPARK_SA_GITHUB_DEPLOYER}" "${PRINCIPAL_SET}" "${MEMBERS}"
  else
    audit_not_verified "política de IAM de ${DEPLOYER_SA_EMAIL} não lida"
  fi

  audit_section "Run"
  PROJECT_POLICY="$(gcloud_json_or_empty projects get-iam-policy "${SPARK_GCP_PROJECT}")"
  project_has_role() {
    printf '%s' "${PROJECT_POLICY}" | jq -e --arg r "$1" --arg m "${DEPLOYER_MEMBER}" \
      '.bindings[]? | select(.role == $r) | select(.members | index($m))' > /dev/null 2>&1
  }
  if [ -n "${PROJECT_POLICY}" ]; then
    if project_has_role roles/run.admin; then audit_pass "run.admin concedido ao deployer"; else audit_drift "run.admin ausente para o deployer"; fi
    audit_section "Scheduler"
    if project_has_role roles/cloudscheduler.admin; then audit_pass "cloudscheduler.admin concedido ao deployer"; else audit_drift "cloudscheduler.admin ausente para o deployer"; fi
    audit_section "Forbidden roles"
    for forbidden in roles/owner roles/editor roles/iam.serviceAccountKeyAdmin; do
      if project_has_role "${forbidden}"; then
        audit_drift "papel PROIBIDO concedido ao deployer no projeto: ${forbidden}"
      else
        audit_pass "${forbidden} ausente no projeto (esperado)"
      fi
    done
  else
    audit_not_verified "política de IAM do projeto não lida"
  fi

  audit_section "Artifact Registry"
  AR_POLICY="$(gcloud_json_or_empty artifacts repositories get-iam-policy "${SPARK_AR_REPO}" --project "${SPARK_GCP_PROJECT}" --location "${SPARK_GCP_REGION}")"
  if [ -n "${AR_POLICY}" ]; then
    if printf '%s' "${AR_POLICY}" | jq -e --arg m "${DEPLOYER_MEMBER}" '.bindings[]? | select(.role == "roles/artifactregistry.writer") | select(.members | index($m))' > /dev/null 2>&1; then
      audit_pass "artifactregistry.writer concedido no repositório ${SPARK_AR_REPO}"
    else
      audit_drift "artifactregistry.writer ausente no repositório ${SPARK_AR_REPO}"
    fi
  else
    audit_not_verified "IAM do repositório ${SPARK_AR_REPO} não lido"
  fi

  audit_section "Secrets metadata"
  for secret in "${SPARK_SECRET_DATABASE_URL}" "${SPARK_SECRET_DATABASE_URL_DIRECT}" "${SPARK_SECRET_GEMINI_API_KEY}" "${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}"; do
    SECRET_POLICY="$(gcloud_json_or_empty secrets get-iam-policy "${secret}" --project "${SPARK_GCP_PROJECT}")"
    if [ -z "${SECRET_POLICY}" ]; then
      audit_not_verified "IAM de ${secret} não lido"
      continue
    fi
    if printf '%s' "${SECRET_POLICY}" | jq -e --arg m "${DEPLOYER_MEMBER}" '.bindings[]? | select(.role == "roles/secretmanager.secretAccessor") | select(.members | index($m))' > /dev/null 2>&1; then
      audit_drift "${secret}: deployer tem secretAccessor — PROIBIDO (só metadata/versão)"
    elif printf '%s' "${SECRET_POLICY}" | jq -e --arg m "${DEPLOYER_MEMBER}" '.bindings[]? | select(.role == "roles/secretmanager.viewer") | select(.members | index($m))' > /dev/null 2>&1; then
      audit_pass "${secret}: secretmanager.viewer (metadata apenas)"
    else
      audit_drift "${secret}: secretmanager.viewer ausente para o deployer"
    fi
  done

  audit_section "GCS DR read"
  BUCKET_POLICY="$(gcloud_json_or_empty storage buckets get-iam-policy "gs://${SPARK_GCS_BUCKET}")"
  if [ -n "${BUCKET_POLICY}" ]; then
    COND_EXPR="$(printf '%s' "${BUCKET_POLICY}" | jq -r --arg m "${DEPLOYER_MEMBER}" '[.bindings[]? | select(.role == "roles/storage.objectViewer") | select(.members | index($m)) | select(.condition) | .condition.expression] | first // ""')"
    case "${COND_EXPR}" in
      *"objects/${SPARK_DR_PREFIX}"*) audit_pass "deployer: objectViewer condicionado ao prefixo ${SPARK_DR_PREFIX}" ;;
      "") audit_drift "deployer sem objectViewer condicionado no bucket" ;;
      *) audit_drift "condição do deployer não menciona ${SPARK_DR_PREFIX}: ${COND_EXPR}" ;;
    esac
    if printf '%s' "${BUCKET_POLICY}" | jq -e --arg m "${DEPLOYER_MEMBER}" '.bindings[]? | select(.role == "roles/storage.legacyBucketReader") | select(.members | index($m))' > /dev/null 2>&1; then
      audit_pass "deployer: legacyBucketReader (listar nomes)"
    else
      audit_drift "deployer sem legacyBucketReader no bucket"
    fi
  else
    audit_not_verified "IAM do bucket ${SPARK_GCS_BUCKET} não lido"
  fi

  audit_section "actAs"
  for workload_sa in "${RUNTIME_SA_EMAIL}" "${MIGRATOR_SA_EMAIL}" "${BACKUP_SA_EMAIL}" "${SCHEDULER_SA_EMAIL}"; do
    SA_POLICY_W="$(gcloud_json_or_empty iam service-accounts get-iam-policy "${workload_sa}" --project "${SPARK_GCP_PROJECT}")"
    if [ -z "${SA_POLICY_W}" ]; then
      audit_not_verified "IAM de ${workload_sa} não lido"
      continue
    fi
    if printf '%s' "${SA_POLICY_W}" | jq -e --arg m "${DEPLOYER_MEMBER}" '.bindings[]? | select(.role == "roles/iam.serviceAccountUser") | select(.members | index($m))' > /dev/null 2>&1; then
      audit_pass "serviceAccountUser do deployer sobre ${workload_sa%%@*}"
    else
      audit_drift "serviceAccountUser do deployer AUSENTE sobre ${workload_sa%%@*}"
    fi
  done

  audit_section "Forbidden roles (chave de Service Account)"
  KEYS="$(gcloud iam service-accounts keys list --iam-account "${DEPLOYER_SA_EMAIL}" --project "${SPARK_GCP_PROJECT}" --managed-by user --format='value(name)' 2> /dev/null || printf 'ERR')"
  case "${KEYS}" in
    ERR) audit_not_verified "chaves de ${SPARK_SA_GITHUB_DEPLOYER}: não listadas" ;;
    '')  audit_pass "nenhuma chave JSON de usuário para ${SPARK_SA_GITHUB_DEPLOYER}" ;;
    *)   audit_drift "existe chave JSON de usuário para ${SPARK_SA_GITHUB_DEPLOYER} — PROIBIDO" ;;
  esac

  audit_finish "bootstrap-github-deploy --verify"
fi

# ---------------------------------------------------------------- 1. APIs necessárias para WIF

log "habilitando APIs necessárias para Workload Identity Federation (idempotente)"
gcloud services enable \
  iam.googleapis.com \
  iamcredentials.googleapis.com \
  sts.googleapis.com \
  --project "${SPARK_GCP_PROJECT}"

# ---------------------------------------------------------------- 2. Workload Identity Pool

if resource_exists iam workload-identity-pools describe "${POOL_ID}" --location=global; then
  log "Workload Identity Pool '${POOL_ID}' já existe — reutilizando"
  POOL_STATE="$(gcloud iam workload-identity-pools describe "${POOL_ID}" \
    --project "${SPARK_GCP_PROJECT}" --location=global --format='value(state)')"
  [ "${POOL_STATE}" = "ACTIVE" ] \
    || fail "Workload Identity Pool '${POOL_ID}' existe mas não está ACTIVE (estado: ${POOL_STATE}) — pode estar soft-deleted; revise manualmente ('gcloud iam workload-identity-pools undelete') antes de repetir"
else
  log "criando Workload Identity Pool '${POOL_ID}'"
  gcloud iam workload-identity-pools create "${POOL_ID}" \
    --project "${SPARK_GCP_PROJECT}" \
    --location=global \
    --display-name="GitHub Actions" \
    --description="Identidades federadas do GitHub Actions para deploy do Spark Backend (T18.3.2) — sem chave JSON"
fi

# ---------------------------------------------------------------- 3. Provider — nunca corrigido em silêncio
#
# A condição de atributo é a fronteira de segurança inteira (§ nunca confiar no pool inteiro). Se o
# Provider já existe com issuer ou condição DIFERENTES do esperado, este script para: um bootstrap
# que "conserta" isso sozinho poderia alargar silenciosamente quem pode se passar pelo deployer.

if resource_exists iam workload-identity-pools providers describe "${PROVIDER_ID}" \
     --workload-identity-pool="${POOL_ID}" --location=global; then
  log "Provider '${PROVIDER_ID}' já existe — conferindo compatibilidade (nunca corrigido em silêncio)"
  CURRENT_ISSUER="$(gcloud iam workload-identity-pools providers describe "${PROVIDER_ID}" \
    --project "${SPARK_GCP_PROJECT}" --workload-identity-pool="${POOL_ID}" --location=global \
    --format='value(oidc.issuerUri)')"
  CURRENT_CONDITION="$(gcloud iam workload-identity-pools providers describe "${PROVIDER_ID}" \
    --project "${SPARK_GCP_PROJECT}" --workload-identity-pool="${POOL_ID}" --location=global \
    --format='value(attributeCondition)')"
  NORM_CURRENT="$(normalize_condition "${CURRENT_CONDITION}")"
  NORM_EXPECTED="$(normalize_condition "${EXPECTED_CONDITION}")"

  [ "${CURRENT_ISSUER}" = "${EXPECTED_ISSUER}" ] \
    || fail "DRIFT DE SEGURANÇA: Provider '${PROVIDER_ID}' tem issuer '${CURRENT_ISSUER}', esperado '${EXPECTED_ISSUER}'. Revise manualmente — este script nunca corrige um Provider existente."
  [ "${NORM_CURRENT}" = "${NORM_EXPECTED}" ] \
    || fail "$(printf 'DRIFT DE SEGURANÇA: Provider "%s" tem attribute-condition diferente da esperada. Revise manualmente.\n  esperado: %s\n  real:     %s' "${PROVIDER_ID}" "${NORM_EXPECTED}" "${NORM_CURRENT}")"
  log "Provider '${PROVIDER_ID}' compatível com o esperado — reutilizando"
else
  log "criando Provider '${PROVIDER_ID}' restrito a repositório=${SPARK_GITHUB_REPO} ref=${SPARK_GITHUB_DEPLOY_REF} environment=${SPARK_GITHUB_DEPLOY_ENVIRONMENT}"
  gcloud iam workload-identity-pools providers create-oidc "${PROVIDER_ID}" \
    --project "${SPARK_GCP_PROJECT}" \
    --location=global \
    --workload-identity-pool="${POOL_ID}" \
    --display-name="workout (main, production)" \
    --issuer-uri="${EXPECTED_ISSUER}" \
    --attribute-mapping="${ATTRIBUTE_MAPPING}" \
    --attribute-condition="${EXPECTED_CONDITION}"
fi

# ---------------------------------------------------------------- 4. Service Account de deploy — EXCLUSIVA
#
# Nunca as quatro de workload (runtime/migrator/scheduler/backup): elas executam o backend; esta só
# administra o deploy, e é a única impersonada pelo GitHub Actions.

if resource_exists iam service-accounts describe "${DEPLOYER_SA_EMAIL}"; then
  log "Service Account '${SPARK_SA_GITHUB_DEPLOYER}' já existe — reutilizando"
else
  log "criando Service Account '${SPARK_SA_GITHUB_DEPLOYER}' (identidade exclusiva de deploy)"
  gcloud iam service-accounts create "${SPARK_SA_GITHUB_DEPLOYER}" \
    --project "${SPARK_GCP_PROJECT}" \
    --display-name "Spark — GitHub Actions deploy (T18.3.2)"
fi

# ---------------------------------------------------------------- 5. Impersonation: só a identidade federada deste repositório

log "concedendo workloadIdentityUser à identidade federada de ${SPARK_GITHUB_REPO} sobre ${SPARK_SA_GITHUB_DEPLOYER} (nunca ao pool inteiro)"
gcloud iam service-accounts add-iam-policy-binding "${DEPLOYER_SA_EMAIL}" \
  --project "${SPARK_GCP_PROJECT}" \
  --role roles/iam.workloadIdentityUser \
  --member "${PRINCIPAL_SET}" \
  > /dev/null

# ---------------------------------------------------------------- 6. IAM mínimo — nível de projeto
#
# Cloud Run e Cloud Scheduler não sustentam condição de recurso na CRIAÇÃO de um serviço/job/agenda
# que ainda não existe — por isso os dois papéis abaixo são de projeto, e nunca Owner/Editor.

log "concedendo run.admin e cloudscheduler.admin ao deployer (nível de projeto — ver matriz IAM no relatório)"
gcloud projects add-iam-policy-binding "${SPARK_GCP_PROJECT}" \
  --member "${DEPLOYER_MEMBER}" --role roles/run.admin > /dev/null
gcloud projects add-iam-policy-binding "${SPARK_GCP_PROJECT}" \
  --member "${DEPLOYER_MEMBER}" --role roles/cloudscheduler.admin > /dev/null

# ---------------------------------------------------------------- 7. Artifact Registry — só o repositório spark

log "concedendo artifactregistry.writer só no repositório '${SPARK_AR_REPO}'"
gcloud artifacts repositories add-iam-policy-binding "${SPARK_AR_REPO}" \
  --project "${SPARK_GCP_PROJECT}" --location "${SPARK_GCP_REGION}" \
  --member "${DEPLOYER_MEMBER}" --role roles/artifactregistry.writer > /dev/null

# ---------------------------------------------------------------- 8. Secret Manager — metadata, NUNCA payload

log "concedendo secretmanager.viewer (metadata/versão; nunca secretAccessor) nos 4 secrets"
for secret in "${SPARK_SECRET_DATABASE_URL}" "${SPARK_SECRET_DATABASE_URL_DIRECT}" \
              "${SPARK_SECRET_GEMINI_API_KEY}" "${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}"; do
  gcloud secrets add-iam-policy-binding "${secret}" \
    --project "${SPARK_GCP_PROJECT}" \
    --member "${DEPLOYER_MEMBER}" --role roles/secretmanager.viewer > /dev/null
done

# ---------------------------------------------------------------- 9. actAs — só nas SAs de workload usadas pelo deploy
#
# `--service-account` (runtime/migrator/backup) e `--oidc-service-account-email`/
# `--oauth-service-account-email` (scheduler) exigem serviceAccountUser do CHAMADOR sobre a SA alvo.
# Nunca concedido sobre todas as Service Accounts do projeto (§10 do enunciado).

log "concedendo serviceAccountUser só sobre as quatro SAs de workload usadas pelo deploy"
for workload_sa in "${RUNTIME_SA_EMAIL}" "${MIGRATOR_SA_EMAIL}" "${BACKUP_SA_EMAIL}" "${SCHEDULER_SA_EMAIL}"; do
  gcloud iam service-accounts add-iam-policy-binding "${workload_sa}" \
    --project "${SPARK_GCP_PROJECT}" \
    --member "${DEPLOYER_MEMBER}" --role roles/iam.serviceAccountUser > /dev/null
done

# ---------------------------------------------------------------- 10. Bucket — leitura do prefixo de DR, nunca escrita
#
# O mesmo padrão de IAM Condition já usado para a backup SA (bootstrap-cloud-run.sh), mas
# objectViewer (leitura) em vez de objectAdmin: o deployer só confere se existe backup válido
# recente (o gate de DR de deploy-cloud-run.sh) — nunca grava payload de produção.

log "concedendo leitura condicionada ao prefixo de DR (${SPARK_DR_PREFIX}) no bucket '${SPARK_GCS_BUCKET}' — nunca escrita, nunca fora do prefixo"
gcloud storage buckets add-iam-policy-binding "gs://${SPARK_GCS_BUCKET}" \
  --member "${DEPLOYER_MEMBER}" \
  --role roles/storage.objectViewer \
  --condition "expression=resource.name.startsWith(\"projects/_/buckets/${SPARK_GCS_BUCKET}/objects/${SPARK_DR_PREFIX}\"),title=spark-dr-prefix-only-deployer,description=T18.3.2: deployer só lê metadata/manifesto de DR" \
  > /dev/null \
  || log "AVISO: não foi possível conceder o IAM condicional do deployer no bucket — confirme que '${SPARK_GCS_BUCKET}' existe (T18.1) e tente de novo manualmente."
gcloud storage buckets add-iam-policy-binding "gs://${SPARK_GCS_BUCKET}" \
  --member "${DEPLOYER_MEMBER}" \
  --role roles/storage.legacyBucketReader \
  --condition=None \
  > /dev/null \
  || log "AVISO: não foi possível conceder legacyBucketReader ao deployer — confira manualmente."

# ---------------------------------------------------------------- 11. Nunca chave — confirmado, não só evitado

log "confirmando que não existe chave JSON de usuário para ${SPARK_SA_GITHUB_DEPLOYER}"
EXISTING_KEYS="$(gcloud iam service-accounts keys list --iam-account "${DEPLOYER_SA_EMAIL}" \
  --project "${SPARK_GCP_PROJECT}" --managed-by user --format='value(name)' 2> /dev/null || true)"
if [ -n "${EXISTING_KEYS}" ]; then
  fail "existe chave JSON de usuário para ${SPARK_SA_GITHUB_DEPLOYER} — remova-a. Este bootstrap nunca cria nem depende de chave (Workload Identity Federation apenas)."
fi
log "nenhuma chave JSON — a identidade só é alcançável via Workload Identity Federation"

log "bootstrap concluído. Nenhum valor de secret e nenhuma chave foi impresso ou criado."
log "  Workload Identity Provider: ${PROVIDER_RESOURCE}"
log "  Deploy Service Account:     ${DEPLOYER_SA_EMAIL}"
log "Configure no GitHub (Settings → Environments → production → Variables), sem nenhum segredo:"
log "  SPARK_GCP_PROJECT=${SPARK_GCP_PROJECT}"
log "  SPARK_FIREBASE_PROJECT=${SPARK_FIREBASE_PROJECT}"
log "  SPARK_GCP_REGION=${SPARK_GCP_REGION}"
log "  GCP_WORKLOAD_IDENTITY_PROVIDER=${PROVIDER_RESOURCE}"
log "  GCP_DEPLOY_SERVICE_ACCOUNT=${DEPLOYER_SA_EMAIL}"
log "Próximo passo: ops/gcp/bootstrap-github-deploy.sh --verify, depois configurar o Environment 'production' (Required reviewers) e rodar 'Deploy Spark Backend' pela primeira vez."
