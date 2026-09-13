#!/usr/bin/env bash
# Bootstrap idempotente da integração GitHub Actions → Cloud Run via WIF (T18.3.2). Prova, sem
# `gcloud` real nem rede, que `ops/gcp/bootstrap-github-deploy.sh`:
#
#   1. cria a Service Account de deploy quando ausente, e reutiliza quando já existe;
#   2. cria o Workload Identity Pool/Provider quando ausentes, e reutiliza quando compatíveis;
#   3. o Provider restringe repositório == IgorRibeiro98/workout, ref == refs/heads/main e
#      environment == production — nunca o pool inteiro;
#   4. um Provider existente com issuer/condição DIFERENTE do esperado é DRIFT: o script aborta e
#      nunca corrige em silêncio (nunca chama `providers update-oidc`);
#   5. nunca cria chave de Service Account, nunca concede Owner/Editor/secretAccessor;
#   6. workloadIdentityUser vai só ao principal esperado, sobre a SA de deploy;
#   7. serviceAccountUser vai só às quatro SAs de workload (runtime/migrator/backup/scheduler) —
#      nunca a outra SA do projeto;
#   8. uma chave de usuário já existente para a SA de deploy é detectada e aborta o bootstrap;
#   9. rodar o bootstrap duas vezes seguidas é seguro (idempotente) e não recria nada.
#
# Uso: ops/tests/bootstrap-github-deploy.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

FAKE_BIN_DIR="$(mktemp -d)"
trap 'rm -rf "${FAKE_BIN_DIR}"' EXIT
install_wif_fakes "${FAKE_BIN_DIR}"

BOOTSTRAP="${OPS_DIR}/gcp/bootstrap-github-deploy.sh"

run_bootstrap() {
  GCLOUD_CALL_LOG="$(mktemp)"
  export GCLOUD_CALL_LOG
  local out code
  out="$(
    PATH="${FAKE_BIN_DIR}:${PATH}" \
      SPARK_GCP_PROJECT=infra-project \
      SPARK_GCP_REGION=southamerica-east1 \
      "$@" "${BOOTSTRAP}" 2>&1
  )" && code=0 || code=$?
  LOG="$(cat "${GCLOUD_CALL_LOG}")"
  SAIDA="${out}"
  CODIGO="${code}"
  rm -f "${GCLOUD_CALL_LOG}"
}

DEPLOYER_EMAIL="spark-github-deployer@infra-project.iam.gserviceaccount.com"
PRINCIPAL_SET="principalSet://iam.googleapis.com/projects/965678405850/locations/global/workloadIdentityPools/github-actions/attribute.repository/IgorRibeiro98/workout"

echo "=== primeira execução: nada existe ainda ==="
run_bootstrap env GCLOUD_WIF_POOL_MISSING=1 GCLOUD_WIF_PROVIDER_MISSING=1 GCLOUD_DEPLOYER_SA_MISSING=1

check "bootstrap termina com sucesso" "0" "${CODIGO}"

check "cria o Workload Identity Pool" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q '^iam workload-identity-pools create github-actions' && echo sim || echo não)"

check "cria o Provider com create-oidc" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q '^iam workload-identity-pools providers create-oidc workout' && echo sim || echo não)"

check "o Provider é criado restrito a repository/ref/environment" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'providers create-oidc workout' | grep -q -- "--attribute-condition=assertion.repository == 'IgorRibeiro98/workout' && assertion.ref == 'refs/heads/main' && assertion.environment == 'production'" && echo sim || echo não)"

check "o issuer é o do GitHub Actions" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'providers create-oidc workout' | grep -q -- '--issuer-uri=https://token.actions.githubusercontent.com' && echo sim || echo não)"

check "cria a Service Account de deploy (exclusiva)" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q "^iam service-accounts create spark-github-deployer " && echo sim || echo não)"

check "workloadIdentityUser vai ao principalSet do repositório, sobre a SA de deploy" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q "iam service-accounts add-iam-policy-binding ${DEPLOYER_EMAIL} .*roles/iam.workloadIdentityUser.*--member ${PRINCIPAL_SET}" && echo sim || echo não)"

check "nunca concede workloadIdentityUser ao pool inteiro (sem 'attribute.repository/\\*' nem 'principal://')" "não" \
  "$(printf '%s\n' "$LOG" | grep 'workloadIdentityUser' | grep -qE 'principal://|attribute\.repository/\*' && echo sim || echo não)"

check "serviceAccountUser vai à runtime SA" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q "iam service-accounts add-iam-policy-binding spark-backend-runtime@infra-project.iam.gserviceaccount.com --project infra-project --member serviceAccount:${DEPLOYER_EMAIL} --role roles/iam.serviceAccountUser" && echo sim || echo não)"

check "serviceAccountUser vai à migrator SA" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q "iam service-accounts add-iam-policy-binding spark-backend-migrator@infra-project.iam.gserviceaccount.com .*roles/iam.serviceAccountUser" && echo sim || echo não)"

check "serviceAccountUser vai à backup SA" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q "iam service-accounts add-iam-policy-binding spark-backend-backup@infra-project.iam.gserviceaccount.com .*roles/iam.serviceAccountUser" && echo sim || echo não)"

check "serviceAccountUser vai à scheduler SA" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q "iam service-accounts add-iam-policy-binding spark-maintenance-scheduler@infra-project.iam.gserviceaccount.com .*roles/iam.serviceAccountUser" && echo sim || echo não)"

check "serviceAccountUser é concedido exatamente 4 vezes (nunca indiscriminadamente)" "4" \
  "$(printf '%s\n' "$LOG" | grep -c 'roles/iam.serviceAccountUser' || true)"

check "run.admin é concedido ao deployer, no projeto" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q "projects add-iam-policy-binding infra-project --member serviceAccount:${DEPLOYER_EMAIL} --role roles/run.admin" && echo sim || echo não)"

check "cloudscheduler.admin é concedido ao deployer" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q "roles/cloudscheduler.admin" && echo sim || echo não)"

check "artifactregistry.writer é escopado ao repositório spark" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q "artifacts repositories add-iam-policy-binding spark --project infra-project --location southamerica-east1 --member serviceAccount:${DEPLOYER_EMAIL} --role roles/artifactregistry.writer" && echo sim || echo não)"

check "secretmanager.viewer é concedido nos 4 secrets (nunca secretAccessor)" "4" \
  "$(printf '%s\n' "$LOG" | grep -c "secrets add-iam-policy-binding .* roles/secretmanager.viewer" || true)"

check "nunca secretAccessor para o deployer" "não" \
  "$(printf '%s\n' "$LOG" | grep 'add-iam-policy-binding' | grep -q "roles/secretmanager.secretAccessor" && echo sim || echo não)"

check "leitura do bucket é condicionada ao prefixo de DR (objectViewer)" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'storage buckets add-iam-policy-binding' | grep 'roles/storage.objectViewer' | grep -q 'resource.name.startsWith("projects/_/buckets/spark-private-assets-prod/objects/system/dr/postgres/")' && echo sim || echo não)"

check "legacyBucketReader (listar nomes) também é concedido ao deployer" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'storage buckets add-iam-policy-binding.*roles/storage.legacyBucketReader' && echo sim || echo não)"

check "NUNCA concede Owner" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'roles/owner' && echo sim || echo não)"

check "NUNCA concede Editor" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'roles/editor' && echo sim || echo não)"

check "NUNCA cria chave de Service Account" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'keys create' && echo sim || echo não)"

check "confirma ausência de chave de usuário (keys list --managed-by user)" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'iam service-accounts keys list --iam-account '"${DEPLOYER_EMAIL}"'.*--managed-by user' && echo sim || echo não)"

echo
echo "=== recursos já existentes e compatíveis: reutiliza, não recria ==="
run_bootstrap

check "bootstrap termina com sucesso (tudo já existe)" "0" "${CODIGO}"
check "NÃO recria o pool" "0" "$(printf '%s\n' "$LOG" | grep -c '^iam workload-identity-pools create ' || true)"
check "NÃO recria o provider" "0" "$(printf '%s\n' "$LOG" | grep -c 'providers create-oidc' || true)"
check "NÃO recria a Service Account" "0" "$(printf '%s\n' "$LOG" | grep -c '^iam service-accounts create ' || true)"
check "ainda assim concede/reafirma o IAM mínimo (idempotente)" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'roles/run.admin' && echo sim || echo não)"

echo
echo "=== segunda execução seguida: idempotente de ponta a ponta ==="
run_bootstrap
SEGUNDA_CODIGO="${CODIGO}"
run_bootstrap
check "duas execuções seguidas terminam com sucesso" "0 0" "${SEGUNDA_CODIGO} ${CODIGO}"

echo
echo "=== DRIFT: provider existe com issuer diferente do esperado — aborta, nunca corrige ==="
run_bootstrap env FAKE_WIF_ISSUER=https://outro-issuer.invalid

check "bootstrap falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "a mensagem aponta DRIFT DE SEGURANÇA" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'DRIFT DE SEGURANÇA' && echo sim || echo não)"
check "nunca chama update-oidc (nenhuma correção silenciosa)" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'update-oidc' && echo sim || echo não)"
check "nenhum IAM foi concedido depois do DRIFT (aborta cedo)" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'add-iam-policy-binding' && echo sim || echo não)"

echo
echo "=== DRIFT: provider existe com attribute-condition diferente do esperado ==="
run_bootstrap env FAKE_WIF_CONDITION="assertion.repository == 'outro/repo'"

check "bootstrap falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "a mensagem aponta DRIFT DE SEGURANÇA e mostra esperado/real" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'DRIFT DE SEGURANÇA' && printf '%s' "$SAIDA" | grep -q 'esperado:' && echo sim || echo não)"

echo
echo "=== o pool existe mas está soft-deleted (state != ACTIVE) — aborta ==="
run_bootstrap env FAKE_WIF_POOL_STATE=DELETED

check "bootstrap falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "a mensagem menciona o estado" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'não está ACTIVE' && echo sim || echo não)"

echo
echo "=== chave de usuário já existente para a SA de deploy — o bootstrap recusa continuar ==="
run_bootstrap env GCLOUD_DEPLOYER_HAS_KEY=1

check "bootstrap falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "a mensagem explica o motivo (chave de usuário existente)" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'existe chave JSON de usuário' && echo sim || echo não)"

echo
echo "=== projeto GCP inexistente: aborta antes de qualquer recurso ==="
run_bootstrap env GCLOUD_PROJECT_MISSING=1

check "bootstrap falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "nenhum recurso WIF foi tocado" "não" \
  "$(printf '%s\n' "$LOG" | grep -qE 'workload-identity-pools (create|providers)' && echo sim || echo não)"

echo
echo "=== --verify nunca escreve (sem add-iam-policy-binding, sem create) ==="
GCLOUD_CALL_LOG="$(mktemp)"
export GCLOUD_CALL_LOG
PATH="${FAKE_BIN_DIR}:${PATH}" \
  SPARK_GCP_PROJECT=infra-project SPARK_GCP_REGION=southamerica-east1 \
  "${BOOTSTRAP}" --verify > /tmp/verify.out 2>&1 || true
LOG="$(cat "${GCLOUD_CALL_LOG}")"
rm -f "${GCLOUD_CALL_LOG}"
check "--verify nunca cria nada" "0" "$(printf '%s\n' "$LOG" | grep -cE '(iam service-accounts create|workload-identity-pools create|providers create-oidc)' || true)"
check "--verify nunca concede IAM" "0" "$(printf '%s\n' "$LOG" | grep -c 'add-iam-policy-binding' || true)"
check "--verify imprime um veredito PASS/DRIFT/NOT_VERIFIED" "sim" \
  "$(grep -qE '^bootstrap-github-deploy --verify: (PASS|DRIFT|NOT_VERIFIED)$' /tmp/verify.out && echo sim || echo não)"
rm -f /tmp/verify.out

finish_checks "bootstrap-github-deploy.sh: WIF idempotente, IAM mínimo, sem chave, drift nunca corrigido em silêncio"
