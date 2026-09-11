#!/usr/bin/env bash
#
# A retenção do Artifact Registry (T18.3 §17), provada sem GCP: a política nativa tem uma regra
# Keep (as N mais recentes) e regras Delete por idade; `--apply` a aplica sem dry-run; a auditoria
# aprova quando todo digest em uso está entre as N mais recentes e REPROVA quando um digest em uso
# ficou fora da janela — o caso em que uma limpeza apagaria a imagem de uma revision ativa.
#
# Uso: ops/tests/artifact-registry-retention.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
FAKE_BIN_DIR="${WORK}/bin"
mkdir -p "${FAKE_BIN_DIR}"

ACTIVE='sha256:1111111111111111111111111111111111111111111111111111111111111111'
cat > "${FAKE_BIN_DIR}/gcloud" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GCLOUD_CALL_LOG}"
case "$*" in
  "artifacts docker images list"*) printf '%s\n' ${FAKE_RECENT_DIGESTS:-}; exit 0 ;;
  "run services describe"*) printf 'spark-x-00001-abc\n'; exit 0 ;;
  "run revisions describe"*) printf 'reg/img@%s\n' "${FAKE_ACTIVE_DIGEST}"; exit 0 ;;
  "run jobs describe"*) printf 'reg/img@%s\n' "${FAKE_ACTIVE_DIGEST}"; exit 0 ;;
  "artifacts repositories describe"*) printf '%s\n' "${FAKE_POLICY:-}"; exit 0 ;;
esac
exit 0
FAKE
chmod +x "${FAKE_BIN_DIR}/gcloud"

GCLOUD_CALL_LOG="${WORK}/calls.log"
export GCLOUD_CALL_LOG
run_retention() {
  : > "${GCLOUD_CALL_LOG}"
  PATH="${FAKE_BIN_DIR}:${PATH}" SPARK_GCP_PROJECT=infra-project SPARK_GCP_REGION=southamerica-east1 "$@" \
    "${OPS_DIR}/gcp/artifact-registry-retention.sh" "${RET_ARGS[@]}" > "${WORK}/out" 2>&1
}

POLICY_FILE="${OPS_DIR}/gcp/artifact-registry-cleanup-policy.json"
echo "=== a política nativa ==="
check "existe uma regra Keep com keepCount ≥ 2 (a atual e a anterior, para rollback)" "sim" \
  "$(jq -e '[.[] | select(.action.type == "Keep") | .mostRecentVersions.keepCount] | max >= 2' "${POLICY_FILE}" > /dev/null && echo sim || echo não)"
check "apaga untagged antigas (≥ 7 dias)" "sim" \
  "$(jq -e '[.[] | select(.action.type == "Delete" and .condition.tagState == "UNTAGGED") | (.condition.olderThan | rtrimstr("s") | tonumber)] | min >= 604800' "${POLICY_FILE}" > /dev/null && echo sim || echo não)"
check "apaga tagged só depois de muito tempo (≥ 60 dias)" "sim" \
  "$(jq -e '[.[] | select(.action.type == "Delete" and .condition.tagState == "TAGGED") | (.condition.olderThan | rtrimstr("s") | tonumber)] | min >= 5184000' "${POLICY_FILE}" > /dev/null && echo sim || echo não)"
check "o deploy builda sem provenance/SBOM (um digest por release, nada untagged por baixo da tag)" "sim" \
  "$(grep -q -- 'docker build --provenance=false --sbom=false' "${OPS_DIR}/gcp/deploy-cloud-run.sh" && echo sim || echo não)"

echo
echo "=== auditoria: digest em uso protegido → PASS ==="
RET_ARGS=()
CODIGO=0
run_retention env FAKE_ACTIVE_DIGEST="${ACTIVE}" FAKE_RECENT_DIGESTS="sha256:2222 ${ACTIVE} sha256:3333" FAKE_POLICY='{"spark-keep-recent-releases":{}}' || CODIGO=$?
check "termina com sucesso" "0" "${CODIGO}"
check "consulta as revisions com tráfego dos dois serviços e os três jobs" "sim" \
  "$(grep -q 'run services describe spark-backend ' "${GCLOUD_CALL_LOG}" && grep -q 'run services describe spark-maintenance ' "${GCLOUD_CALL_LOG}" && grep -q 'run jobs describe spark-db-backup ' "${GCLOUD_CALL_LOG}" && echo sim || echo não)"
check "não aplica política sem --apply" "não" "$(grep -q 'set-cleanup-policies' "${GCLOUD_CALL_LOG}" && echo sim || echo não)"
check "veredito PASS" "sim" "$(grep -q 'retenção do Artifact Registry: PASS' "${WORK}/out" && echo sim || echo não)"

echo
echo "=== auditoria: digest em uso FORA da janela → FALHA ==="
CODIGO=0
run_retention env FAKE_ACTIVE_DIGEST="${ACTIVE}" FAKE_RECENT_DIGESTS="sha256:2222 sha256:3333" FAKE_POLICY='{"x":{}}' || CODIGO=$?
check "falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "explica que o digest em uso está fora da janela" "sim" "$(grep -q 'FORA da janela' "${WORK}/out" && echo sim || echo não)"

echo
echo "=== auditoria: política ausente → FALHA, apontando --apply ==="
CODIGO=0
run_retention env FAKE_ACTIVE_DIGEST="${ACTIVE}" FAKE_RECENT_DIGESTS="${ACTIVE}" FAKE_POLICY='' || CODIGO=$?
check "falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "aponta --apply" "sim" "$(grep -q -- 'rode com --apply' "${WORK}/out" && echo sim || echo não)"

echo
echo "=== --apply aplica a política nativa (sem dry-run) e audita em seguida ==="
RET_ARGS=(--apply)
CODIGO=0
run_retention env FAKE_ACTIVE_DIGEST="${ACTIVE}" FAKE_RECENT_DIGESTS="${ACTIVE}" FAKE_POLICY='{"x":{}}' || CODIGO=$?
check "termina com sucesso" "0" "${CODIGO}"
check "aplica com --policy <arquivo> --no-dry-run" "sim" \
  "$(grep 'artifacts repositories set-cleanup-policies spark ' "${GCLOUD_CALL_LOG}" | grep -q -- "--policy ${POLICY_FILE} --no-dry-run" && echo sim || echo não)"

finish_checks "retenção do Artifact Registry: política nativa com Keep, e auditoria que nunca deixa apagar um digest em uso"
