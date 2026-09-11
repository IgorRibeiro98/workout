#!/usr/bin/env bash
# Auditoria de drift de configuração: o esperado (lib.gcp.sh) × o real no GCP (T18.3 §25).
#
#   SPARK_GCP_PROJECT=... SPARK_FIREBASE_PROJECT=... ops/gcp/config-drift-audit.sh
#
# Somente leitura. Compara projeto, região, Cloud Run (serviços e jobs: imagem, SA, CPU, memória,
# instâncias, concorrência, timeout, env não-secreta, referências de secret PINADAS), Service
# Accounts, secrets (versão habilitada = a pinada), bucket (proteções), Artifact Registry
# (política), Scheduler (os dois jobs) e IAM de invocação. Nunca corrige nada.
#
# Saída: uma linha por verificação (PASS / DRIFT / NOT_VERIFIED) e o veredito final em stdout.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"
# shellcheck source=ops/gcp/lib.audit.sh
. "${SCRIPT_DIR}/lib.audit.sh"

require_cmd gcloud
require_cmd jq

RUNTIME_SA_EMAIL="$(sa_email "${SPARK_SA_RUNTIME}")"
MIGRATOR_SA_EMAIL="$(sa_email "${SPARK_SA_MIGRATOR}")"
SCHEDULER_SA_EMAIL="$(sa_email "${SPARK_SA_SCHEDULER}")"
BACKUP_SA_EMAIL="$(sa_email "${SPARK_SA_BACKUP}")"

# ---------------------------------------------------------------- projetos e região
audit_section "projetos"
for project in "${SPARK_GCP_PROJECT}" "${SPARK_FIREBASE_PROJECT}"; do
  state="$(gcloud projects describe "${project}" --format='value(lifecycleState)' 2> /dev/null || true)"
  if [ -z "${state}" ]; then
    audit_not_verified "projeto ${project}: sem acesso"
  else
    audit_expect "projeto ${project} lifecycleState" "ACTIVE" "${state}"
  fi
done

# ---------------------------------------------------------------- Service Accounts
audit_section "service accounts"
for sa in "${RUNTIME_SA_EMAIL}" "${MIGRATOR_SA_EMAIL}" "${SCHEDULER_SA_EMAIL}" "${BACKUP_SA_EMAIL}"; do
  # `disabled` só aparece na resposta quando é true; a existência é conferida pelo e-mail.
  email="$(gcloud iam service-accounts describe "${sa}" --project "${SPARK_GCP_PROJECT}" --format='value(email)' 2> /dev/null || true)"
  if [ -z "${email}" ]; then
    audit_drift "service account ${sa} inexistente ou sem acesso"
  else
    disabled="$(gcloud iam service-accounts describe "${sa}" --project "${SPARK_GCP_PROJECT}" --format='value(disabled)' 2> /dev/null || true)"
    audit_expect "service account ${sa%%@*} disabled" "False" "${disabled:-False}"
  fi
done

# ---------------------------------------------------------------- secrets: versão habilitada mais recente
audit_section "secrets (versão habilitada)"
declare -A EXPECTED_SECRET_VERSION
for secret in "${SPARK_SECRET_DATABASE_URL}" "${SPARK_SECRET_DATABASE_URL_DIRECT}" "${SPARK_SECRET_GEMINI_API_KEY}" "${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}"; do
  version="$(gcloud secrets versions list "${secret}" --project "${SPARK_GCP_PROJECT}" --filter='state:enabled' --sort-by='~createTime' --limit=1 --format='value(name.basename())' 2> /dev/null || true)"
  if [ -z "${version}" ]; then
    audit_drift "secret ${secret}: nenhuma versão habilitada (ou sem acesso)"
    EXPECTED_SECRET_VERSION["${secret}"]=""
  else
    audit_pass "secret ${secret}: versão habilitada mais recente = ${version}"
    EXPECTED_SECRET_VERSION["${secret}"]="${version}"
  fi
done

# ---------------------------------------------------------------- Cloud Run: helpers
env_value() { printf '%s' "$1" | jq -r --arg n "$2" '[.[] | select(.name == $n and has("value")) | .value] | first // ""'; }
secret_ref() { printf '%s' "$1" | jq -r --arg n "$2" '[.[] | select(.name == $n and has("valueFrom")) | .valueFrom.secretKeyRef | "\(.name):\(.key)"] | first // ""'; }

audit_secret_refs() {
  # audit_secret_refs <rótulo> <env-json> <ENV=secret> ...
  local label="$1" envs="$2"
  shift 2
  local pair name secret ref
  for pair in "$@"; do
    name="${pair%%=*}"; secret="${pair#*=}"
    ref="$(secret_ref "${envs}" "${name}")"
    case "${ref}" in
      "${secret}:latest") audit_drift "${label} ${name} referencia ${secret}:latest — versão não pinada (T18.3 §19)" ;;
      "${secret}:"*)
        if [ -n "${EXPECTED_SECRET_VERSION[${secret}]:-}" ] && [ "${ref#*:}" != "${EXPECTED_SECRET_VERSION[${secret}]}" ]; then
          audit_drift "${label} ${name} pinado em ${ref}, mas a versão habilitada mais recente é ${EXPECTED_SECRET_VERSION[${secret}]} (deploy pendente após rotação?)"
        else
          audit_pass "${label} ${name} ← ${ref} (pinado)"
        fi
        ;;
      "") audit_drift "${label} ${name}: sem referência de secret" ;;
      *)  audit_drift "${label} ${name} referencia ${ref}, esperado ${secret}:<versão>" ;;
    esac
  done
}

audit_env_values() {
  # audit_env_values <rótulo> <env-json> <NOME=valor> ...
  local label="$1" envs="$2"
  shift 2
  local pair
  for pair in "$@"; do
    audit_expect "${label} env ${pair%%=*}" "${pair#*=}" "$(env_value "${envs}" "${pair%%=*}")"
  done
}

COMMON_API_ENV=(
  NODE_ENV=production DATABASE_MIGRATION_MODE=verify OBJECT_STORAGE_PROVIDER=gcs
  "GCS_BUCKET_NAME=${SPARK_GCS_BUCKET}" REQUIRE_FIREBASE_ADMIN=true FIREBASE_ADMIN_CREDENTIAL_MODE=adc
  "FIREBASE_PROJECT_ID=${SPARK_FIREBASE_PROJECT}" REQUIRE_GEMINI=false SYNC_WRITE_ENABLED=true
  MAINTENANCE_MODE=false BACKGROUND_JOBS_MODE=disabled
  "DATABASE_POOL_MIN=${SPARK_DATABASE_POOL_MIN}" "DATABASE_POOL_MAX=${SPARK_DATABASE_POOL_MAX}"
)

audit_service() {
  # audit_service <nome> <sa> <cpu> <mem> <min> <max> <concurrency> <timeout|-> <AI_ENABLED>
  local name="$1" sa="$2" cpu="$3" mem="$4" min="$5" max="$6" conc="$7" timeout="$8" ai="$9"
  local json
  json="$(gcloud_json_or_empty run services describe "${name}" --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}")"
  if [ -z "${json}" ]; then
    audit_drift "serviço ${name}: inexistente ou sem acesso"
    return
  fi
  local tpl container envs
  tpl="$(printf '%s' "${json}" | jq '.spec.template')"
  container="$(printf '%s' "${tpl}" | jq '.spec.containers[0]')"
  envs="$(printf '%s' "${container}" | jq '.env // []')"

  audit_expect "${name} serviceAccount" "${sa}" "$(printf '%s' "${tpl}" | jq -r '.spec.serviceAccountName // ""')"
  audit_expect "${name} cpu" "${cpu}" "$(printf '%s' "${container}" | jq -r '.resources.limits.cpu // ""')"
  audit_expect "${name} memory" "${mem}" "$(printf '%s' "${container}" | jq -r '.resources.limits.memory // ""')"
  audit_expect "${name} maxScale" "${max}" "$(printf '%s' "${tpl}" | jq -r '.metadata.annotations["autoscaling.knative.dev/maxScale"] // ""')"
  audit_expect "${name} minScale" "${min}" "$(printf '%s' "${tpl}" | jq -r '.metadata.annotations["autoscaling.knative.dev/minScale"] // "0"')"
  audit_expect "${name} containerConcurrency" "${conc}" "$(printf '%s' "${tpl}" | jq -r '.spec.containerConcurrency // ""')"
  if [ "${timeout}" != "-" ]; then
    audit_expect "${name} timeoutSeconds" "${timeout}" "$(printf '%s' "${tpl}" | jq -r '.spec.timeoutSeconds // ""')"
  fi
  local image
  image="$(printf '%s' "${container}" | jq -r '.image // ""')"
  case "${image}" in
    *@sha256:*) audit_pass "${name} imagem por digest (${image##*@})" ;;
    *:latest|*) audit_drift "${name} imagem sem digest: ${image}" ;;
  esac
  audit_env_values "${name}" "${envs}" "${COMMON_API_ENV[@]}" "AI_ENABLED=${ai}"
  # Nunca na API/manutenção: o secret direto e o arquivo de credencial.
  audit_expect "${name} sem DATABASE_URL_DIRECT" "" "$(secret_ref "${envs}" DATABASE_URL_DIRECT)$(env_value "${envs}" DATABASE_URL_DIRECT)"
  audit_expect "${name} sem GOOGLE_APPLICATION_CREDENTIALS" "" "$(env_value "${envs}" GOOGLE_APPLICATION_CREDENTIALS)"
  audit_secret_refs "${name}" "${envs}" \
    "DATABASE_URL=${SPARK_SECRET_DATABASE_URL}" \
    "GEMINI_API_KEY=${SPARK_SECRET_GEMINI_API_KEY}" \
    "ACCOUNT_DELETION_HMAC_KEY=${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}"
}

audit_job() {
  # audit_job <nome> <sa> <args> <secret ENV=secret> [env NOME=valor ...]
  local name="$1" sa="$2" args="$3" secret_pair="$4"
  shift 4
  local json
  json="$(gcloud_json_or_empty run jobs describe "${name}" --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}")"
  if [ -z "${json}" ]; then
    audit_drift "job ${name}: inexistente ou sem acesso"
    return
  fi
  local spec container envs image
  spec="$(printf '%s' "${json}" | jq '.spec.template.spec.template.spec')"
  container="$(printf '%s' "${spec}" | jq '.containers[0]')"
  envs="$(printf '%s' "${container}" | jq '.env // []')"
  audit_expect "${name} serviceAccount" "${sa}" "$(printf '%s' "${spec}" | jq -r '.serviceAccountName // ""')"
  audit_expect "${name} args" "${args}" "$(printf '%s' "${container}" | jq -r '(.args // []) | join(" ")')"
  image="$(printf '%s' "${container}" | jq -r '.image // ""')"
  case "${image}" in
    *@sha256:*) audit_pass "${name} imagem por digest (${image##*@})" ;;
    *) audit_drift "${name} imagem sem digest: ${image}" ;;
  esac
  audit_secret_refs "${name}" "${envs}" "${secret_pair}"
  [ $# -gt 0 ] && audit_env_values "${name}" "${envs}" "$@"
  return 0
}

# ---------------------------------------------------------------- Cloud Run: serviços
audit_section "cloud run — ${SPARK_RUN_API_SERVICE}"
audit_service "${SPARK_RUN_API_SERVICE}" "${RUNTIME_SA_EMAIL}" "${SPARK_RUN_API_CPU}" "${SPARK_RUN_API_MEMORY}" \
  "${SPARK_RUN_API_MIN_INSTANCES}" "${SPARK_RUN_API_MAX_INSTANCES}" "${SPARK_RUN_API_CONCURRENCY}" "${SPARK_RUN_API_TIMEOUT}" true
API_IAM="$(gcloud_json_or_empty run services get-iam-policy "${SPARK_RUN_API_SERVICE}" --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}")"
audit_expect "${SPARK_RUN_API_SERVICE} invokers" "allUsers" "$(printf '%s' "${API_IAM}" | jq -r '[.bindings[]? | select(.role == "roles/run.invoker") | .members[]] | sort | join(",")')"

audit_section "cloud run — ${SPARK_RUN_MAINTENANCE_SERVICE}"
audit_service "${SPARK_RUN_MAINTENANCE_SERVICE}" "${RUNTIME_SA_EMAIL}" "${SPARK_RUN_MAINTENANCE_CPU}" "${SPARK_RUN_MAINTENANCE_MEMORY}" \
  "${SPARK_RUN_MAINTENANCE_MIN_INSTANCES}" "${SPARK_RUN_MAINTENANCE_MAX_INSTANCES}" "${SPARK_RUN_MAINTENANCE_CONCURRENCY}" - false
MAINT_IAM="$(gcloud_json_or_empty run services get-iam-policy "${SPARK_RUN_MAINTENANCE_SERVICE}" --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}")"
audit_expect "${SPARK_RUN_MAINTENANCE_SERVICE} invokers" "serviceAccount:${SCHEDULER_SA_EMAIL}" "$(printf '%s' "${MAINT_IAM}" | jq -r '[.bindings[]? | select(.role == "roles/run.invoker") | .members[]] | sort | join(",")')"

# O serviço temporário do primeiro deploy nunca sobrevive a um deploy.
if resource_exists run services describe "${SPARK_RUN_API_VALIDATE_SERVICE}" --region "${SPARK_GCP_REGION}"; then
  audit_drift "serviço temporário ${SPARK_RUN_API_VALIDATE_SERVICE} ainda existe — remova-o"
else
  audit_pass "serviço temporário ${SPARK_RUN_API_VALIDATE_SERVICE} ausente"
fi

# ---------------------------------------------------------------- Cloud Run: jobs
audit_section "cloud run — jobs"
audit_job "${SPARK_RUN_MIGRATE_JOB}" "${MIGRATOR_SA_EMAIL}" "dist/cli/migrate-database.js" "DATABASE_URL_DIRECT=${SPARK_SECRET_DATABASE_URL_DIRECT}"
audit_job "${SPARK_RUN_BACKUP_JOB}" "${BACKUP_SA_EMAIL}" "dist/cli/db-backup.js" "DATABASE_URL_DIRECT=${SPARK_SECRET_DATABASE_URL_DIRECT}" \
  OBJECT_STORAGE_PROVIDER=gcs "GCS_BUCKET_NAME=${SPARK_GCS_BUCKET}" "SPARK_DR_RETENTION_COUNT=${SPARK_DR_RETENTION_COUNT}"
audit_job "${SPARK_RUN_STORAGE_AUDIT_JOB}" "${RUNTIME_SA_EMAIL}" "dist/cli/storage-audit.js" "DATABASE_URL=${SPARK_SECRET_DATABASE_URL}" \
  OBJECT_STORAGE_PROVIDER=gcs "GCS_BUCKET_NAME=${SPARK_GCS_BUCKET}" DATABASE_MIGRATION_MODE=verify
BACKUP_JOB_IAM="$(gcloud_json_or_empty run jobs get-iam-policy "${SPARK_RUN_BACKUP_JOB}" --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}")"
audit_expect "${SPARK_RUN_BACKUP_JOB} invokers" "serviceAccount:${SCHEDULER_SA_EMAIL}" "$(printf '%s' "${BACKUP_JOB_IAM}" | jq -r '[.bindings[]? | select(.role == "roles/run.invoker") | .members[]] | sort | join(",")')"

# ---------------------------------------------------------------- Scheduler
audit_section "cloud scheduler"
SCHED="$(gcloud_json_or_empty scheduler jobs describe "${SPARK_SCHEDULER_JOB}" --project "${SPARK_GCP_PROJECT}" --location "${SPARK_GCP_REGION}")"
if [ -z "${SCHED}" ]; then
  audit_drift "scheduler ${SPARK_SCHEDULER_JOB}: inexistente"
else
  audit_expect "${SPARK_SCHEDULER_JOB} schedule" "${SPARK_SCHEDULER_CRON}" "$(printf '%s' "${SCHED}" | jq -r '.schedule // ""')"
  audit_expect "${SPARK_SCHEDULER_JOB} state" "ENABLED" "$(printf '%s' "${SCHED}" | jq -r '.state // ""')"
  audit_expect "${SPARK_SCHEDULER_JOB} oidc service account" "${SCHEDULER_SA_EMAIL}" "$(printf '%s' "${SCHED}" | jq -r '.httpTarget.oidcToken.serviceAccountEmail // ""')"
  audit_expect "${SPARK_SCHEDULER_JOB} uri (sufixo)" "/internal/maintenance/run" "$(printf '%s' "${SCHED}" | jq -r '.httpTarget.uri // ""' | sed 's#^https://[^/]*##')"
fi
BSCHED="$(gcloud_json_or_empty scheduler jobs describe "${SPARK_BACKUP_SCHEDULER_JOB}" --project "${SPARK_GCP_PROJECT}" --location "${SPARK_GCP_REGION}")"
if [ -z "${BSCHED}" ]; then
  audit_drift "scheduler ${SPARK_BACKUP_SCHEDULER_JOB}: inexistente"
else
  audit_expect "${SPARK_BACKUP_SCHEDULER_JOB} schedule" "${SPARK_BACKUP_SCHEDULER_CRON}" "$(printf '%s' "${BSCHED}" | jq -r '.schedule // ""')"
  audit_expect "${SPARK_BACKUP_SCHEDULER_JOB} state" "ENABLED" "$(printf '%s' "${BSCHED}" | jq -r '.state // ""')"
  audit_expect "${SPARK_BACKUP_SCHEDULER_JOB} oauth service account" "${SCHEDULER_SA_EMAIL}" "$(printf '%s' "${BSCHED}" | jq -r '.httpTarget.oauthToken.serviceAccountEmail // ""')"
  audit_expect "${SPARK_BACKUP_SCHEDULER_JOB} uri (sufixo)" "/jobs/${SPARK_RUN_BACKUP_JOB}:run" "$(printf '%s' "${BSCHED}" | jq -r '.httpTarget.uri // ""' | sed 's#^.*/namespaces/[^/]*##')"
fi

# ---------------------------------------------------------------- bucket
audit_section "bucket gs://${SPARK_GCS_BUCKET}"
BUCKET="$(gcloud_json_or_empty storage buckets describe "gs://${SPARK_GCS_BUCKET}")"
if [ -z "${BUCKET}" ]; then
  audit_drift "bucket ${SPARK_GCS_BUCKET}: inexistente ou sem acesso"
else
  audit_expect "bucket location" "$(printf '%s' "${SPARK_GCP_REGION}" | tr '[:lower:]' '[:upper:]')" "$(printf '%s' "${BUCKET}" | jq -r '.location // ""')"
  audit_expect "bucket public_access_prevention" "enforced" "$(printf '%s' "${BUCKET}" | jq -r '.public_access_prevention // ""')"
  audit_expect "bucket uniform_bucket_level_access" "true" "$(printf '%s' "${BUCKET}" | jq -r '.uniform_bucket_level_access // ""' | tr '[:upper:]' '[:lower:]')"
  audit_expect_min "bucket soft_delete retentionDurationSeconds" "${SPARK_GCS_SOFT_DELETE_MIN_SECONDS}" "$(printf '%s' "${BUCKET}" | jq -r '.soft_delete_policy.retentionDurationSeconds // "0"')"
fi

# ---------------------------------------------------------------- Artifact Registry
audit_section "artifact registry ${SPARK_AR_REPO}"
AR="$(gcloud_json_or_empty artifacts repositories describe "${SPARK_AR_REPO}" --project "${SPARK_GCP_PROJECT}" --location "${SPARK_GCP_REGION}")"
if [ -z "${AR}" ]; then
  audit_drift "repositório ${SPARK_AR_REPO}: inexistente"
else
  audit_expect "repositório format" "DOCKER" "$(printf '%s' "${AR}" | jq -r '.format // ""')"
  policies="$(printf '%s' "${AR}" | jq -r '(.cleanupPolicies // {}) | length')"
  audit_expect_min "repositório cleanupPolicies (regras)" 1 "${policies}"
fi

audit_finish "config-drift-audit"
