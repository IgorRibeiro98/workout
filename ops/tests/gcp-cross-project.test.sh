#!/usr/bin/env bash
#
# Topologia cross-project GCP/Firebase (T18.2.1): a infraestrutura do Spark Backend pode rodar num
# projeto GCP diferente do projeto Firebase usado pelo Android (identidade/FCM) — é o caso real do
# Spark, onde a infraestrutura roda em `project-...` e o Firebase é `spark-36b11`. Este teste prova,
# sem `gcloud` real nem rede, que:
#
#   1. SPARK_FIREBASE_PROJECT cai em SPARK_GCP_PROJECT quando não declarado (compatibilidade com
#      instalação de projeto único).
#   2. ops/gcp/bootstrap-cloud-run.sh valida os dois projetos antes de aplicar qualquer IAM, e
#      falha antes de tocar infraestrutura se o projeto Firebase for inacessível.
#   3. O binding de Firebase Admin (roles/firebaseauth.admin, roles/firebasecloudmessaging.admin)
#      referencia SPARK_FIREBASE_PROJECT, nunca SPARK_GCP_PROJECT, quando os dois são diferentes —
#      e o membro continua sendo a runtime SA do projeto GCP (cross-project IAM, sem JSON key).
#   4. Toda infraestrutura (Artifact Registry, Service Accounts, Secret Manager) continua
#      referenciando SPARK_GCP_PROJECT, nunca o projeto Firebase.
#   5. ops/gcp/deploy-cloud-run.sh configura FIREBASE_PROJECT_ID a partir de SPARK_FIREBASE_PROJECT.
#
# Um `gcloud` fake grava cada chamada (args, espaço-separados) num arquivo — o suficiente para
# provar qual projeto cada comando alvejou, sem executar nada real.
#
# Uso: ops/tests/gcp-cross-project.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

failures=0
check() {
  local descricao="$1" esperado="$2" obtido="$3"
  if [ "$esperado" = "$obtido" ]; then
    printf '  ok    %s\n' "$descricao"
  else
    printf '  FALHA %s (esperado "%s", obtido "%s")\n' "$descricao" "$esperado" "$obtido" >&2
    failures=$((failures + 1))
  fi
}

FAKE_BIN_DIR="$(mktemp -d)"
trap 'rm -rf "${FAKE_BIN_DIR}"' EXIT

# Fake `gcloud`: registra cada chamada em GCLOUD_CALL_LOG. `projects describe X` falha só quando X
# é igual a GCLOUD_FAIL_PROJECT (simula projeto inexistente/inacessível); qualquer outro comando
# com "describe" no meio (resource_exists de artifact registry, service account, secret) falha
# também — força o script a seguir pelo caminho de "recurso não existe, criar". Todo o resto
# (create, add-iam-policy-binding, enable, versions add) é sucesso.
cat > "${FAKE_BIN_DIR}/gcloud" <<'FAKE_GCLOUD'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GCLOUD_CALL_LOG}"

if [ "${1:-}" = "projects" ] && [ "${2:-}" = "describe" ]; then
  target="${3:-}"
  [ "${target}" = "${GCLOUD_FAIL_PROJECT:-}" ] && exit 1
  exit 0
fi

for arg in "$@"; do
  [ "${arg}" = "describe" ] && exit 1
done

exit 0
FAKE_GCLOUD
chmod +x "${FAKE_BIN_DIR}/gcloud"

echo "=== SPARK_FIREBASE_PROJECT: fallback de compatibilidade (lib.gcp.sh) ==="

FALLBACK_VALUE="$(
  SPARK_GCP_PROJECT=solo-project \
    bash -c '. "'"${OPS_DIR}"'/gcp/lib.gcp.sh"; printf %s "${SPARK_FIREBASE_PROJECT}"'
)"
check "sem SPARK_FIREBASE_PROJECT declarado, cai no projeto GCP" "solo-project" "${FALLBACK_VALUE}"

SEPARATE_VALUE="$(
  SPARK_GCP_PROJECT=infra-project SPARK_FIREBASE_PROJECT=firebase-project \
    bash -c '. "'"${OPS_DIR}"'/gcp/lib.gcp.sh"; printf %s "${SPARK_FIREBASE_PROJECT}"'
)"
check "com SPARK_FIREBASE_PROJECT declarado, mantém o próprio valor" "firebase-project" "${SEPARATE_VALUE}"

echo
echo "=== bootstrap-cloud-run.sh: projetos separados ==="

GCLOUD_CALL_LOG="$(mktemp)"
export GCLOUD_CALL_LOG
SAIDA="$(
  PATH="${FAKE_BIN_DIR}:${PATH}" \
    SPARK_GCP_PROJECT=infra-project \
    SPARK_FIREBASE_PROJECT=firebase-project \
    SPARK_GCP_REGION=southamerica-east1 \
    "${OPS_DIR}/gcp/bootstrap-cloud-run.sh" 2>&1
)" && CODIGO=0 || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
rm -f "${GCLOUD_CALL_LOG}"

check "bootstrap termina com sucesso" "0" "${CODIGO}"
check "valida o projeto GCP" "sim" \
  "$(printf '%s\n' "$LOG" | grep -qx 'projects describe infra-project' && echo sim || echo não)"
check "valida o projeto Firebase" "sim" \
  "$(printf '%s\n' "$LOG" | grep -qx 'projects describe firebase-project' && echo sim || echo não)"
check "roles/firebaseauth.admin é aplicado no projeto Firebase" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q '^projects add-iam-policy-binding firebase-project .*roles/firebaseauth.admin$' && echo sim || echo não)"
check "roles/firebasecloudmessaging.admin é aplicado no projeto Firebase" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q '^projects add-iam-policy-binding firebase-project .*roles/firebasecloudmessaging.admin$' && echo sim || echo não)"
check "o membro do binding é a runtime SA do projeto GCP" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'serviceAccount:spark-backend-runtime@infra-project.iam.gserviceaccount.com' && echo sim || echo não)"
check "nenhum papel de Firebase é aplicado no projeto GCP (infra-project)" "não" \
  "$(printf '%s\n' "$LOG" | grep -q '^projects add-iam-policy-binding infra-project .*roles/firebase' && echo sim || echo não)"
check "Artifact Registry continua no projeto GCP" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'artifacts repositories create spark --project infra-project' && echo sim || echo não)"
check "Service Accounts continuam no projeto GCP" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'iam service-accounts create spark-backend-runtime --project infra-project' && echo sim || echo não)"
check "Secret Manager continua no projeto GCP" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'secrets create spark-database-url --project infra-project' && echo sim || echo não)"
check "nenhum comando referenciou --project firebase-project" "não" \
  "$(printf '%s\n' "$LOG" | grep -q -- '--project firebase-project' && echo sim || echo não)"

echo
echo "=== bootstrap-cloud-run.sh: compatibilidade com projeto único ==="

GCLOUD_CALL_LOG="$(mktemp)"
export GCLOUD_CALL_LOG
SAIDA="$(
  PATH="${FAKE_BIN_DIR}:${PATH}" \
    SPARK_GCP_PROJECT=solo-project \
    SPARK_GCP_REGION=southamerica-east1 \
    "${OPS_DIR}/gcp/bootstrap-cloud-run.sh" 2>&1
)" && CODIGO=0 || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
rm -f "${GCLOUD_CALL_LOG}"

DESCRIBE_COUNT="$(printf '%s\n' "$LOG" | grep -cx 'projects describe solo-project' || true)"

check "bootstrap termina com sucesso (projeto único)" "0" "${CODIGO}"
check "valida o mesmo projeto para GCP e Firebase (duas checagens explícitas)" "2" "${DESCRIBE_COUNT}"
check "Firebase IAM aplicado no único projeto declarado" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q '^projects add-iam-policy-binding solo-project .*roles/firebaseauth.admin$' && echo sim || echo não)"

echo
echo "=== bootstrap-cloud-run.sh: projeto Firebase inacessível falha antes de qualquer IAM ==="

GCLOUD_CALL_LOG="$(mktemp)"
export GCLOUD_CALL_LOG
SAIDA="$(
  PATH="${FAKE_BIN_DIR}:${PATH}" \
    SPARK_GCP_PROJECT=infra-project \
    SPARK_FIREBASE_PROJECT=firebase-inacessivel \
    SPARK_GCP_REGION=southamerica-east1 \
    GCLOUD_FAIL_PROJECT=firebase-inacessivel \
    "${OPS_DIR}/gcp/bootstrap-cloud-run.sh" 2>&1
)" && CODIGO=0 || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
rm -f "${GCLOUD_CALL_LOG}"

check "bootstrap falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "a mensagem aponta o projeto Firebase" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'projeto Firebase inexistente ou sem acesso: firebase-inacessivel' && echo sim || echo não)"
check "nenhum IAM de Firebase foi aplicado" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'roles/firebase' && echo sim || echo não)"
check "nenhum recurso de infraestrutura foi criado" "não" \
  "$(printf '%s\n' "$LOG" | grep -qE 'artifacts repositories create|iam service-accounts create|secrets create' && echo sim || echo não)"

echo
echo "=== deploy-cloud-run.sh: FIREBASE_PROJECT_ID usa SPARK_FIREBASE_PROJECT ==="

DEPLOY_SCRIPT="${OPS_DIR}/gcp/deploy-cloud-run.sh"
COM_FIREBASE_PROJECT="$(grep -Fc "FIREBASE_PROJECT_ID=\${SPARK_FIREBASE_PROJECT}" "${DEPLOY_SCRIPT}" || true)"
COM_GCP_PROJECT="$(grep -Fc "FIREBASE_PROJECT_ID=\${SPARK_GCP_PROJECT}" "${DEPLOY_SCRIPT}" || true)"

check "API e Maintenance recebem FIREBASE_PROJECT_ID de SPARK_FIREBASE_PROJECT" "2" "${COM_FIREBASE_PROJECT}"
check "nenhuma ocorrência usa SPARK_GCP_PROJECT para FIREBASE_PROJECT_ID" "0" "${COM_GCP_PROJECT}"

echo
if [ "$failures" -gt 0 ]; then
  printf '=== %d verificação(ões) falharam ===\n' "$failures" >&2
  exit 1
fi
echo "=== SPARK_GCP_PROJECT e SPARK_FIREBASE_PROJECT são tratados como projetos independentes ==="
