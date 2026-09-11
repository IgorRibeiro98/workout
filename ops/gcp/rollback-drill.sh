#!/usr/bin/env bash
# Ensaio de rollback do Cloud Run (T18.3 §22): prova, com tráfego real, que voltar funciona.
#
#   revision atual (A) ──▶ revision B ──smoke──▶ rollback para A ──smoke──▶ (opcional) volta a B ──smoke
#
# Uso:
#   ops/gcp/rollback-drill.sh --to <revision-B>              # A→B→A: termina onde começou
#   ops/gcp/rollback-drill.sh --to <revision-B> --stay       # A→B, valida, e fica em B
#
# Nunca rebuilda, nunca aplica migration, nunca toca no banco: só `update-traffic` entre revisions
# que JÁ existem, com `smoke-cloud-run.sh` depois de cada movimento. Se um smoke falhar no meio, o
# script tenta voltar para A antes de abortar — o ensaio nunca deixa produção numa revision que
# acabou de reprovar. Timestamps e revisions vão para stderr, para o relatório do drill.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

require_cmd gcloud

TARGET=""
STAY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --to)   TARGET="${2:?--to exige uma revision}"; shift 2 ;;
    --stay) STAY=1; shift ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done
[ -n "${TARGET}" ] || fail "uso: rollback-drill.sh --to <revision> [--stay]"

service_url() {
  gcloud run services describe "${SPARK_RUN_API_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
    --format='value(status.url)'
}

current_revision() {
  gcloud run services describe "${SPARK_RUN_API_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
    --format='value(status.traffic[0].revisionName)'
}

move_traffic() {
  local revision="$1"
  log "$(date -u +%Y-%m-%dT%H:%M:%SZ) → 100% do tráfego para ${revision}"
  gcloud run services update-traffic "${SPARK_RUN_API_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --to-revisions "${revision}=100" \
    > /dev/null
}

resource_exists run revisions describe "${TARGET}" \
  --region "${SPARK_GCP_REGION}" --service "${SPARK_RUN_API_SERVICE}" \
  || fail "revision inexistente: ${TARGET} — confira com rollback-cloud-run.sh --list"

ORIGIN="$(current_revision)"
[ -n "${ORIGIN}" ] || fail "não foi possível determinar a revision atual de ${SPARK_RUN_API_SERVICE}"
[ "${ORIGIN}" != "${TARGET}" ] || fail "a revision alvo já é a atual (${ORIGIN}); escolha outra para o ensaio"
URL="$(service_url)"

log "=== ensaio de rollback: ${ORIGIN} → ${TARGET} → ${ORIGIN}$( [ "${STAY}" -eq 1 ] && printf ' → %s' "${TARGET}" ) ==="
log "smoke inicial em ${ORIGIN}"
"${SCRIPT_DIR}/smoke-cloud-run.sh" "${URL}" || fail "a revision atual (${ORIGIN}) já reprova o smoke; ensaio abortado sem mover tráfego"

move_traffic "${TARGET}"
if ! "${SCRIPT_DIR}/smoke-cloud-run.sh" "${URL}"; then
  log "smoke FALHOU em ${TARGET} — voltando para ${ORIGIN} antes de abortar"
  move_traffic "${ORIGIN}"
  fail "ROLLBACK_DRILL_FAIL: ${TARGET} reprovou o smoke; tráfego devolvido a ${ORIGIN}"
fi
log "smoke PASS em ${TARGET}"

move_traffic "${ORIGIN}"
"${SCRIPT_DIR}/smoke-cloud-run.sh" "${URL}" || fail "ROLLBACK_DRILL_FAIL: o rollback para ${ORIGIN} reprovou o smoke — INVESTIGUE AGORA, produção está em ${ORIGIN}"
log "smoke PASS depois do rollback para ${ORIGIN}"

if [ "${STAY}" -eq 1 ]; then
  move_traffic "${TARGET}"
  "${SCRIPT_DIR}/smoke-cloud-run.sh" "${URL}" || fail "ROLLBACK_DRILL_FAIL: ${TARGET} reprovou o smoke na volta"
  log "ROLLBACK_DRILL_PASS: ${ORIGIN} → ${TARGET} → ${ORIGIN} → ${TARGET} (tráfego ficou em ${TARGET})"
else
  log "ROLLBACK_DRILL_PASS: ${ORIGIN} → ${TARGET} → ${ORIGIN} (tráfego ficou em ${ORIGIN})"
fi
