#!/usr/bin/env bash
# Auditoria de IAM: os papéis das quatro Service Accounts × o mínimo esperado (T18.3 §18).
#
#   SPARK_GCP_PROJECT=... SPARK_FIREBASE_PROJECT=... ops/gcp/iam-audit.sh
#
# Somente leitura — nunca remove nem concede nada (§18: "auditoria primeiro"; remoção em produção é
# decisão humana). Para cada identidade, compara o conjunto REAL de papéis (projeto de infra,
# projeto Firebase, cada secret, o bucket, os serviços/jobs do Cloud Run) com o conjunto ESPERADO:
#
#   spark-backend-runtime        secrets: database-url, gemini, hmac · bucket: objectAdmin ·
#                                Firebase: firebaseauth.admin + firebasecloudmessaging.admin ·
#                                projeto de infra: NENHUM papel
#   spark-backend-migrator       secrets: database-url-direct · nada mais
#   spark-maintenance-scheduler  run.invoker em spark-maintenance e no job spark-db-backup · nada mais
#   spark-backend-backup         secrets: database-url-direct · bucket: objectAdmin (condicionado ao
#                                prefixo de DR) + legacyBucketReader · nada mais
#
# Qualquer papel a mais é DRIFT (com destaque para Owner/Editor/*admin de projeto); qualquer papel
# esperado ausente também é DRIFT. Membros que não são estas quatro SAs (o operador humano, SAs
# gerenciadas pelo Google) são listados como informação, nunca como drift — eles não são objeto
# desta auditoria.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"
# shellcheck source=ops/gcp/lib.audit.sh
. "${SCRIPT_DIR}/lib.audit.sh"

require_cmd gcloud
require_cmd jq

RUNTIME="serviceAccount:$(sa_email "${SPARK_SA_RUNTIME}")"
MIGRATOR="serviceAccount:$(sa_email "${SPARK_SA_MIGRATOR}")"
SCHEDULER="serviceAccount:$(sa_email "${SPARK_SA_SCHEDULER}")"
BACKUP="serviceAccount:$(sa_email "${SPARK_SA_BACKUP}")"

# roles_of <policy-json> <member>  → papéis do membro, um por linha, ordenados (com "|cond:<título>" quando condicional).
roles_of() {
  printf '%s' "$1" | jq -r --arg m "$2" '
    [.bindings[]? | select(.members | index($m)) |
      (.role + (if .condition then "|cond:" + (.condition.title // "?") else "" end))] | sort | .[]'
}

# audit_roles <escopo> <policy-json> <membro> <esperado (linhas)>
audit_roles() {
  local scope="$1" policy="$2" member="$3" expected="$4"
  local actual
  if [ -z "${policy}" ]; then
    audit_not_verified "${scope}: política não lida (API/permissão)"
    return
  fi
  actual="$(roles_of "${policy}" "${member}")"
  local short="${member#serviceAccount:}"
  short="${short%%@*}"
  if [ "$(printf '%s' "${expected}" | sort)" = "$(printf '%s' "${actual}" | sort)" ]; then
    if [ -n "${actual}" ]; then
      audit_pass "${scope} · ${short}: $(printf '%s' "${actual}" | tr '\n' ' ' | sed 's/ $//')"
    else
      audit_pass "${scope} · ${short}: nenhum papel (esperado)"
    fi
  else
    audit_drift "${scope} · ${short}: esperado [$(printf '%s' "${expected}" | tr '\n' ' ' | sed 's/ $//')], real [$(printf '%s' "${actual}" | tr '\n' ' ' | sed 's/ $//')]"
  fi
}

# ---------------------------------------------------------------- projeto de infraestrutura
audit_section "IAM do projeto ${SPARK_GCP_PROJECT}"
INFRA="$(gcloud_json_or_empty projects get-iam-policy "${SPARK_GCP_PROJECT}")"
for member in "${RUNTIME}" "${MIGRATOR}" "${SCHEDULER}" "${BACKUP}"; do
  audit_roles "projeto infra" "${INFRA}" "${member}" ""
done
if [ -n "${INFRA}" ]; then
  # Papéis largos concedidos a QUALQUER service account do projeto: informação + drift se for uma das nossas.
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    audit_drift "papel amplo no projeto concedido a uma SA do Spark: ${line}"
  done < <(printf '%s' "${INFRA}" | jq -r '
    .bindings[]? | select(.role == "roles/owner" or .role == "roles/editor" or (.role | endswith(".admin"))) |
    .role as $r | .members[] | select(startswith("serviceAccount:spark-")) | . + " → " + $r' 2> /dev/null || true)
  humans="$(printf '%s' "${INFRA}" | jq -r '[.bindings[]? | select(.role == "roles/owner") | .members[] | select(startswith("user:"))] | join(", ")')"
  audit_pass "owners humanos do projeto (informação): ${humans:-nenhum}"
fi

# ---------------------------------------------------------------- projeto Firebase
audit_section "IAM do projeto Firebase ${SPARK_FIREBASE_PROJECT}"
FIREBASE="$(gcloud_json_or_empty projects get-iam-policy "${SPARK_FIREBASE_PROJECT}")"
audit_roles "projeto firebase" "${FIREBASE}" "${RUNTIME}" "$(printf 'roles/firebaseauth.admin\nroles/firebasecloudmessaging.admin')"
for member in "${MIGRATOR}" "${SCHEDULER}" "${BACKUP}"; do
  audit_roles "projeto firebase" "${FIREBASE}" "${member}" ""
done

# ---------------------------------------------------------------- secrets
audit_section "IAM por secret"
audit_secret_members() {
  # audit_secret_members <secret> <membros esperados (linhas)>
  local secret="$1" expected="$2" policy actual
  policy="$(gcloud_json_or_empty secrets get-iam-policy "${secret}" --project "${SPARK_GCP_PROJECT}")"
  if [ -z "${policy}" ]; then
    audit_not_verified "secret ${secret}: política não lida"
    return
  fi
  actual="$(printf '%s' "${policy}" | jq -r '[.bindings[]? | select(.role == "roles/secretmanager.secretAccessor") | .members[]] | sort | .[]')"
  if [ "$(printf '%s' "${expected}" | sort)" = "$(printf '%s' "${actual}" | sort)" ]; then
    audit_pass "secret ${secret} accessors: $(printf '%s' "${actual}" | sed 's/serviceAccount://; s/@.*//' | tr '\n' ' ')"
  else
    audit_drift "secret ${secret} accessors: esperado [$(printf '%s' "${expected}" | sed 's/serviceAccount://; s/@.*//' | tr '\n' ' ')], real [$(printf '%s' "${actual}" | sed 's/serviceAccount://; s/@.*//' | tr '\n' ' ')]"
  fi
  other="$(printf '%s' "${policy}" | jq -r '[.bindings[]? | select(.role != "roles/secretmanager.secretAccessor") | .role] | join(", ")')"
  [ -z "${other}" ] || audit_drift "secret ${secret} tem papéis além de secretAccessor: ${other}"
}
audit_secret_members "${SPARK_SECRET_DATABASE_URL}" "${RUNTIME}"
audit_secret_members "${SPARK_SECRET_DATABASE_URL_DIRECT}" "$(printf '%s\n%s' "${BACKUP}" "${MIGRATOR}")"
audit_secret_members "${SPARK_SECRET_GEMINI_API_KEY}" "${RUNTIME}"
audit_secret_members "${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}" "${RUNTIME}"

# ---------------------------------------------------------------- bucket
audit_section "IAM do bucket gs://${SPARK_GCS_BUCKET}"
BUCKET_IAM="$(gcloud_json_or_empty storage buckets get-iam-policy "gs://${SPARK_GCS_BUCKET}")"
audit_roles "bucket" "${BUCKET_IAM}" "${RUNTIME}" "roles/storage.objectAdmin"
audit_roles "bucket" "${BUCKET_IAM}" "${BACKUP}" "$(printf 'roles/storage.legacyBucketReader\nroles/storage.objectAdmin|cond:spark-dr-prefix-only')"
audit_roles "bucket" "${BUCKET_IAM}" "${MIGRATOR}" ""
audit_roles "bucket" "${BUCKET_IAM}" "${SCHEDULER}" ""
if [ -n "${BUCKET_IAM}" ]; then
  public="$(printf '%s' "${BUCKET_IAM}" | jq -r '[.bindings[]? | .members[] | select(. == "allUsers" or . == "allAuthenticatedUsers")] | length')"
  audit_expect "bucket sem allUsers/allAuthenticatedUsers" "0" "${public}"
  cond_expr="$(printf '%s' "${BUCKET_IAM}" | jq -r --arg m "${BACKUP}" '[.bindings[]? | select(.members | index($m)) | select(.condition) | .condition.expression] | first // ""')"
  case "${cond_expr}" in
    *"objects/${SPARK_DR_PREFIX}"*) audit_pass "condição da backup SA restringe ao prefixo ${SPARK_DR_PREFIX}" ;;
    "") audit_drift "backup SA sem condição de prefixo no bucket" ;;
    *)  audit_drift "condição da backup SA não menciona ${SPARK_DR_PREFIX}: ${cond_expr}" ;;
  esac
fi

# ---------------------------------------------------------------- Cloud Run invokers
audit_section "invocação do Cloud Run"
MAINT_IAM="$(gcloud_json_or_empty run services get-iam-policy "${SPARK_RUN_MAINTENANCE_SERVICE}" --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}")"
audit_roles "${SPARK_RUN_MAINTENANCE_SERVICE}" "${MAINT_IAM}" "${SCHEDULER}" "roles/run.invoker"
if [ -n "${MAINT_IAM}" ]; then
  audit_expect "${SPARK_RUN_MAINTENANCE_SERVICE} invokers (todos)" "${SCHEDULER}" "$(printf '%s' "${MAINT_IAM}" | jq -r '[.bindings[]? | select(.role == "roles/run.invoker") | .members[]] | sort | join(",")')"
fi
BACKUP_JOB_IAM="$(gcloud_json_or_empty run jobs get-iam-policy "${SPARK_RUN_BACKUP_JOB}" --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}")"
audit_roles "job ${SPARK_RUN_BACKUP_JOB}" "${BACKUP_JOB_IAM}" "${SCHEDULER}" "roles/run.invoker"
API_IAM="$(gcloud_json_or_empty run services get-iam-policy "${SPARK_RUN_API_SERVICE}" --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}")"
if [ -n "${API_IAM}" ]; then
  audit_expect "${SPARK_RUN_API_SERVICE} invokers (público por desenho; Firebase Bearer nas rotas)" "allUsers" "$(printf '%s' "${API_IAM}" | jq -r '[.bindings[]? | select(.role == "roles/run.invoker") | .members[]] | sort | join(",")')"
fi

# ---------------------------------------------------------------- chaves JSON
audit_section "chaves de service account (nunca deve haver chave gerenciada pelo usuário)"
for sa in "${RUNTIME}" "${MIGRATOR}" "${SCHEDULER}" "${BACKUP}"; do
  email="${sa#serviceAccount:}"
  keys="$(gcloud iam service-accounts keys list --iam-account "${email}" --project "${SPARK_GCP_PROJECT}" --managed-by user --format='value(name)' 2> /dev/null || printf 'ERR')"
  case "${keys}" in
    ERR) audit_not_verified "chaves de ${email%%@*}: não listadas" ;;
    '')  audit_pass "chaves de ${email%%@*}: nenhuma chave JSON de usuário" ;;
    *)   audit_drift "chaves de ${email%%@*}: existe chave JSON gerenciada por usuário (T18.3 §segurança)" ;;
  esac
done

audit_finish "iam-audit"
