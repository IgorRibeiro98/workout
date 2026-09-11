#!/usr/bin/env bash
# Rollback de tráfego do Cloud Run (T18.2 §55).
#
# Nunca faz rebuild. Rollback é mover 100% do tráfego de volta para uma revision que já existe —
# a imagem antiga nunca precisa ser reconstruída, só reapontada.
#
# Uso:
#   ops/gcp/rollback-cloud-run.sh <revision>
#   ops/gcp/rollback-cloud-run.sh --list   # lista as revisions disponíveis, com a tráfego atual

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

require_cmd gcloud

if [ "${1:-}" = "--list" ]; then
  gcloud run revisions list \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --service "${SPARK_RUN_API_SERVICE}"
  exit 0
fi

REVISION="${1:?uso: rollback-cloud-run.sh <revision> (ou --list)}"

resource_exists run revisions describe "${REVISION}" \
  --region "${SPARK_GCP_REGION}" --service "${SPARK_RUN_API_SERVICE}" \
  || fail "revision inexistente: ${REVISION} — confira com --list"

log "movendo 100% do tráfego de ${SPARK_RUN_API_SERVICE} para ${REVISION}"
gcloud run services update-traffic "${SPARK_RUN_API_SERVICE}" \
  --project "${SPARK_GCP_PROJECT}" \
  --region "${SPARK_GCP_REGION}" \
  --to-revisions "${REVISION}=100"

log "rollback concluído. Confirme com: gcloud run services describe ${SPARK_RUN_API_SERVICE} --region ${SPARK_GCP_REGION}"
log "Lembrete (§56): rollback de aplicação não desfaz migration. O schema precisa continuar compatível com ${REVISION} — migrations são additive por decisão da T18.2 §11."
