#!/usr/bin/env bash
# Retenção de imagens no Artifact Registry (T18.3 §17): a política nativa, e a auditoria de que
# ela nunca alcança um digest em uso.
#
#   ops/gcp/artifact-registry-retention.sh            # auditoria (somente leitura)
#   ops/gcp/artifact-registry-retention.sh --apply    # aplica/atualiza a política nativa (idempotente)
#
# ## A política (`artifact-registry-cleanup-policy.json`)
#
#   keep   as 10 versões mais recentes (Keep vence Delete, sempre)
#   delete tagged com mais de 90 dias
#
# **Nenhuma regra apaga untagged**, de propósito: as imagens publicadas antes da T18.3 foram
# construídas com provenance/SBOM (o BuildKit publica um índice tagueado + manifestos FILHOS
# untagged), e as revisions ativas apontam para o índice — apagar um filho untagged "velho" quebraria
# o pull de uma revision que está servindo. `deploy-cloud-run.sh` passou a buildar com
# `--provenance=false --sbom=false` (uma versão tagueada por release, sem filhos), então nada novo
# fica untagged; os poucos filhos antigos custam alguns MB e são inofensivos. Rollback pela revision
# anterior continua possível enquanto o digest dela estiver entre as 10 mais recentes; além disso,
# a reconstrução a partir do git SHA é o caminho — documentado em CLOUD_RUN_DEPLOYMENT.md.
#
# ## A auditoria
#
# Lê os digests que as revisions ativas (com tráfego e latest ready) de `spark-backend` e
# `spark-maintenance` e os Jobs usam, e confere que TODOS estão entre as `keepCount` versões
# mais recentes do repositório — ou seja, protegidos pela regra Keep. Um digest ativo fora da
# janela é FAIL, e a resposta é subir `keepCount` antes de a limpeza rodar, nunca o contrário.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

require_cmd gcloud
require_cmd jq

POLICY_FILE="${SCRIPT_DIR}/artifact-registry-cleanup-policy.json"
[ -f "${POLICY_FILE}" ] || fail "política não encontrada: ${POLICY_FILE}"

APPLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1; shift ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

KEEP_COUNT="$(jq -r '[.[] | select(.action.type == "Keep") | .mostRecentVersions.keepCount] | max // 0' "${POLICY_FILE}")"
case "${KEEP_COUNT}" in ''|*[!0-9]*|0) fail "a política precisa de uma regra Keep com mostRecentVersions.keepCount > 0" ;; esac

if [ "${APPLY}" -eq 1 ]; then
  log "aplicando a política de limpeza do Artifact Registry '${SPARK_AR_REPO}' (keepCount=${KEEP_COUNT})"
  gcloud artifacts repositories set-cleanup-policies "${SPARK_AR_REPO}" \
    --project "${SPARK_GCP_PROJECT}" \
    --location "${SPARK_GCP_REGION}" \
    --policy "${POLICY_FILE}" \
    --no-dry-run \
    --quiet
fi

# ---------------------------------------------------------------- auditoria

log "auditando: digests em uso × as ${KEEP_COUNT} versões mais recentes de ${SPARK_AR_IMAGE_BASE}"

# As versões (digests) mais recentes do repositório, da mais nova para a mais antiga.
RECENT_DIGESTS="$(gcloud artifacts docker images list "${SPARK_AR_IMAGE_BASE}" \
  --project "${SPARK_GCP_PROJECT}" \
  --sort-by='~UPDATE_TIME' \
  --limit "${KEEP_COUNT}" \
  --format='value(version)' 2> /dev/null || true)"

# Os digests que produção usa agora: revisions com tráfego + latest ready dos dois serviços, e os
# Jobs. `image` nas revisions é `...@sha256:...`; extraímos só o digest.
active_digests() {
  local service job
  for service in "${SPARK_RUN_API_SERVICE}" "${SPARK_RUN_MAINTENANCE_SERVICE}"; do
    gcloud run services describe "${service}" \
      --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
      --format='value(status.traffic[].revisionName,status.latestReadyRevisionName)' 2> /dev/null \
      | tr ';,' '\n' | sed '/^$/d' | sort -u \
      | while IFS= read -r revision; do
          gcloud run revisions describe "${revision}" \
            --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
            --format='value(status.imageDigest)' 2> /dev/null
        done
  done
  for job in "${SPARK_RUN_MIGRATE_JOB}" "${SPARK_RUN_BACKUP_JOB}" "${SPARK_RUN_STORAGE_AUDIT_JOB}"; do
    gcloud run jobs describe "${job}" \
      --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
      --format='value(spec.template.spec.template.spec.containers[0].image)' 2> /dev/null
  done
}

failures=0
checked=0
while IFS= read -r image; do
  [ -n "${image}" ] || continue
  digest="${image##*@}"
  case "${digest}" in sha256:*) : ;; *) log "  ? imagem sem digest (${image}) — não auditável"; continue ;; esac
  checked=$((checked + 1))
  if printf '%s\n' "${RECENT_DIGESTS}" | grep -qx "${digest}"; then
    log "  ok    ${digest:0:19}… está entre as ${KEEP_COUNT} mais recentes (protegido pela regra Keep)"
  else
    log "  FALHA ${digest:0:19}… em uso por uma revision/job e FORA da janela de ${KEEP_COUNT} versões"
    failures=$((failures + 1))
  fi
done < <(active_digests | sort -u)

CURRENT_POLICY="$(gcloud artifacts repositories describe "${SPARK_AR_REPO}" \
  --project "${SPARK_GCP_PROJECT}" --location "${SPARK_GCP_REGION}" \
  --format='value(cleanupPolicies)' 2> /dev/null || true)"
if [ -z "${CURRENT_POLICY}" ] || [ "${CURRENT_POLICY}" = "null" ]; then
  log "  AVISO política de limpeza ausente no repositório — rode com --apply"
  failures=$((failures + 1))
fi

if [ "${failures}" -gt 0 ]; then
  fail "retenção do Artifact Registry: ${failures} problema(s) em ${checked} digest(s) auditado(s)"
fi
log "retenção do Artifact Registry: PASS — ${checked} digest(s) em uso, todos protegidos; política presente"
