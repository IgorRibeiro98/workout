#!/usr/bin/env bash
# Auditoria de custo e de recursos inesperados (T18.3 §16). Somente leitura.
#
#   SPARK_GCP_PROJECT=... ops/gcp/cost-audit.sh
#
# Responde: billing está ativo onde deve? existe budget? os limites que seguram o custo (max
# instances = 1, min = 0, região única) continuam? existe algum recurso que o Spark não usa e que
# custa por existir (VM, Cloud SQL, GKE, Redis, um segundo bucket, um segundo repositório, um
# Scheduler a mais)? Nada aqui altera billing, budget ou recurso — a auditoria reporta; a decisão é
# humana.
#
# Budget é ALERTA, nunca teto: o Google Cloud não interrompe serviços ao estourar um budget. O que
# limita o custo do Spark de verdade é `max-instances=1` nos dois serviços, scale-to-zero, um
# bucket e um repositório — e é isso que esta auditoria confere.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"
# shellcheck source=ops/gcp/lib.audit.sh
. "${SCRIPT_DIR}/lib.audit.sh"

require_cmd gcloud
require_cmd jq

# ---------------------------------------------------------------- billing e budget
audit_section "billing"
BILLING="$(gcloud_json_or_empty billing projects describe "${SPARK_GCP_PROJECT}")"
if [ -z "${BILLING}" ]; then
  audit_not_verified "billing do projeto ${SPARK_GCP_PROJECT}: sem acesso à API de billing"
  BILLING_ACCOUNT=""
else
  audit_expect "billing habilitado em ${SPARK_GCP_PROJECT}" "true" "$(printf '%s' "${BILLING}" | jq -r '.billingEnabled')"
  BILLING_ACCOUNT="$(printf '%s' "${BILLING}" | jq -r '.billingAccountName // ""')"
  audit_pass "conta de billing: ${BILLING_ACCOUNT:-nenhuma}"
fi

audit_section "budget (alerta, nunca teto)"
if [ -z "${BILLING_ACCOUNT}" ]; then
  audit_not_verified "budget: conta de billing desconhecida"
else
  BUDGETS="$(gcloud billing budgets list --billing-account="${BILLING_ACCOUNT#billingAccounts/}" --format=json 2> /dev/null || printf 'ERR')"
  case "${BUDGETS}" in
    ERR) audit_not_verified "budget: API billingbudgets desabilitada ou sem permissão — habilite/configure pela Console (ver docs/operations/OPERATIONS_CHECKLIST.md)" ;;
    *)
      count="$(printf '%s' "${BUDGETS}" | jq 'length')"
      if [ "${count}" -ge 1 ]; then
        audit_pass "budget(s) na conta de billing: ${count} — $(printf '%s' "${BUDGETS}" | jq -r '[.[] | (.displayName + " (" + ((.amount.specifiedAmount.units // "?") | tostring) + " " + (.amount.specifiedAmount.currencyCode // "") + ")")] | join(", ")')"
      else
        audit_drift "nenhum budget configurado na conta de billing (crie um: é o único aviso de custo fora dos alertas de métrica)"
      fi
      ;;
  esac
fi

# ---------------------------------------------------------------- Cloud Run: o que existe, e os tetos
audit_section "cloud run (região ${SPARK_GCP_REGION})"
SERVICES="$(gcloud_json_or_empty run services list --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}")"
if [ -z "${SERVICES}" ]; then
  audit_not_verified "cloud run services: não listados"
else
  names="$(printf '%s' "${SERVICES}" | jq -r '[.[] | .metadata.name] | sort | join(",")')"
  audit_expect "serviços existentes" "$(printf '%s\n%s' "${SPARK_RUN_API_SERVICE}" "${SPARK_RUN_MAINTENANCE_SERVICE}" | sort | paste -sd, -)" "${names}"
  while IFS=$'\t' read -r name max min; do
    [ -n "${name}" ] || continue
    audit_expect "${name} maxScale" "1" "${max}"
    audit_expect "${name} minScale" "0" "${min}"
  done < <(printf '%s' "${SERVICES}" | jq -r '.[] | [.metadata.name, (.spec.template.metadata.annotations["autoscaling.knative.dev/maxScale"] // ""), (.spec.template.metadata.annotations["autoscaling.knative.dev/minScale"] // "0")] | @tsv')
fi
JOBS="$(gcloud_json_or_empty run jobs list --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}")"
if [ -z "${JOBS}" ]; then
  audit_not_verified "cloud run jobs: não listados"
else
  audit_expect "jobs existentes" "$(printf '%s\n%s\n%s' "${SPARK_RUN_BACKUP_JOB}" "${SPARK_RUN_MIGRATE_JOB}" "${SPARK_RUN_STORAGE_AUDIT_JOB}" | sort | paste -sd, -)" "$(printf '%s' "${JOBS}" | jq -r '[.[] | .metadata.name] | sort | join(",")')"
fi
# Serviços em OUTRAS regiões custam sem que ninguém olhe para eles.
OTHER_REGIONS="$(gcloud run services list --project "${SPARK_GCP_PROJECT}" --format='value(metadata.labels."cloud.googleapis.com/location")' 2> /dev/null | sort -u | grep -v "^${SPARK_GCP_REGION}$" || true)"
audit_expect "serviços Cloud Run fora de ${SPARK_GCP_REGION}" "" "$(printf '%s' "${OTHER_REGIONS}" | paste -sd, -)"

# ---------------------------------------------------------------- Scheduler
audit_section "cloud scheduler"
SCHEDULERS="$(gcloud scheduler jobs list --project "${SPARK_GCP_PROJECT}" --location "${SPARK_GCP_REGION}" --format='value(name.basename())' 2> /dev/null | sort | paste -sd, - || printf 'ERR')"
if [ "${SCHEDULERS}" = "ERR" ]; then
  audit_not_verified "scheduler: não listado"
else
  audit_expect "jobs do scheduler" "$(printf '%s\n%s' "${SPARK_BACKUP_SCHEDULER_JOB}" "${SPARK_SCHEDULER_JOB}" | sort | paste -sd, -)" "${SCHEDULERS}"
fi

# ---------------------------------------------------------------- storage e registry
audit_section "buckets e artifact registry"
BUCKETS="$(gcloud storage buckets list --project "${SPARK_GCP_PROJECT}" --format='value(name)' 2> /dev/null | sort | paste -sd, - || printf 'ERR')"
if [ "${BUCKETS}" = "ERR" ]; then
  audit_not_verified "buckets: não listados"
else
  audit_expect "buckets do projeto" "${SPARK_GCS_BUCKET}" "${BUCKETS}"
fi
REPOS="$(gcloud artifacts repositories list --project "${SPARK_GCP_PROJECT}" --format='value(name.basename())' 2> /dev/null | sort | paste -sd, - || printf 'ERR')"
if [ "${REPOS}" = "ERR" ]; then
  audit_not_verified "artifact registry: não listado"
else
  audit_expect "repositórios do Artifact Registry" "${SPARK_AR_REPO}" "${REPOS}"
  size="$(gcloud artifacts repositories describe "${SPARK_AR_REPO}" --project "${SPARK_GCP_PROJECT}" --location "${SPARK_GCP_REGION}" --format='value(sizeBytes)' 2> /dev/null || true)"
  [ -z "${size}" ] || audit_pass "tamanho do repositório ${SPARK_AR_REPO}: $(( size / 1024 / 1024 )) MB (política de retenção auditada por artifact-registry-retention.sh)"
fi

# ---------------------------------------------------------------- recursos que custam por existir
audit_section "recursos inesperados (custam por existir)"
ENABLED="$(gcloud services list --enabled --project "${SPARK_GCP_PROJECT}" --format='value(config.name)' 2> /dev/null || printf 'ERR')"
if [ "${ENABLED}" = "ERR" ]; then
  audit_not_verified "APIs habilitadas: não listadas"
else
  for api in run.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com cloudscheduler.googleapis.com storage.googleapis.com logging.googleapis.com monitoring.googleapis.com; do
    if printf '%s\n' "${ENABLED}" | grep -qx "${api}"; then
      audit_pass "API esperada habilitada: ${api}"
    else
      audit_drift "API esperada DESABILITADA: ${api}"
    fi
  done
  # Uma API habilitada não custa; um recurso nela custa. Para cada família cara, listar instâncias.
  check_none() {
    # check_none <descrição> <api> <comando gcloud list...>
    local description="$1" api="$2"
    shift 2
    if ! printf '%s\n' "${ENABLED}" | grep -qx "${api}"; then
      audit_pass "${description}: API ${api} desabilitada — nada pode existir"
      return
    fi
    local listed
    listed="$(gcloud "$@" --project "${SPARK_GCP_PROJECT}" --format='value(name)' 2> /dev/null || printf 'ERR')"
    case "${listed}" in
      ERR) audit_not_verified "${description}: não listado" ;;
      '')  audit_pass "${description}: nenhum" ;;
      *)   audit_drift "${description}: existem recursos — $(printf '%s' "${listed}" | paste -sd, -)" ;;
    esac
  }
  check_none "instâncias Compute Engine" compute.googleapis.com compute instances list
  check_none "instâncias Cloud SQL" sqladmin.googleapis.com sql instances list
  check_none "clusters GKE" container.googleapis.com container clusters list
  check_none "instâncias Memorystore Redis" redis.googleapis.com redis instances list --region "${SPARK_GCP_REGION}"
  check_none "VPC connectors" vpcaccess.googleapis.com compute networks vpc-access connectors list --region "${SPARK_GCP_REGION}"
fi

audit_finish "cost-audit"
