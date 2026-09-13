#!/usr/bin/env bash
# Verificação estática de `.github/workflows/deploy-backend.yml` (T18.3.2).
#
# Checks de texto simples (grep), no mesmo estilo de `ops-scripts-safety.test.sh` — nenhum parser
# YAML improvisado (§31 do enunciado da T18.3.2: "evitar parser YAML frágil se já existir
# alternativa adequada"). O que importa aqui é robusto o bastante para texto: presença/ausência de
# chave de nível superior, e frases proibidas que nunca deveriam aparecer neste arquivo.
#
# Uso: ops/tests/deploy-backend-workflow.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${OPS_DIR}/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

WORKFLOW="${REPO_ROOT}/.github/workflows/deploy-backend.yml"
[ -f "${WORKFLOW}" ] || { echo "FALHA: ${WORKFLOW} não existe" >&2; exit 1; }

# O YAML documenta, em comentário, o que ele deliberadamente NÃO faz (ex.: "sem credentials_json",
# "nenhuma flag de emergência (--skip-maintenance...)"). As checagens de AUSÊNCIA abaixo precisam
# olhar só para código de verdade — do contrário a própria explicação vira falso positivo. Mesmo
# filtro de `ops-scripts-safety.test.sh`.
code_only() { grep -vE '^\s*#' "${WORKFLOW}"; }

echo "=== gatilho: só workflow_dispatch, nunca push/pull_request ==="

check "existe 'on:'" "sim" \
  "$(grep -qE '^on:$' "${WORKFLOW}" && echo sim || echo não)"
check "workflow_dispatch está declarado" "sim" \
  "$(grep -qE '^\s*workflow_dispatch:' "${WORKFLOW}" && echo sim || echo não)"
check "NÃO existe gatilho 'push:' de nível de bloco 'on'" "não" \
  "$(awk '/^on:/{flag=1;next} /^[a-zA-Z]/{flag=0} flag' "${WORKFLOW}" | grep -qE '^\s*push:' && echo sim || echo não)"
check "NÃO existe gatilho 'pull_request:' no bloco 'on'" "não" \
  "$(awk '/^on:/{flag=1;next} /^[a-zA-Z]/{flag=0} flag' "${WORKFLOW}" | grep -qE '^\s*pull_request:' && echo sim || echo não)"

echo
echo "=== permissões mínimas ==="

check "permissions.contents == read" "sim" \
  "$(grep -qE '^\s*contents:\s*read\s*$' "${WORKFLOW}" && echo sim || echo não)"
check "permissions.actions == read" "sim" \
  "$(grep -qE '^\s*actions:\s*read\s*$' "${WORKFLOW}" && echo sim || echo não)"
check "permissions.id-token == write" "sim" \
  "$(grep -qE '^\s*id-token:\s*write\s*$' "${WORKFLOW}" && echo sim || echo não)"
check "NÃO usa write-all" "não" \
  "$(code_only | grep -q 'write-all' && echo sim || echo não)"

echo
echo "=== concorrência de produção ==="

check "concurrency.group é spark-backend-production" "sim" \
  "$(grep -qE '^\s*group:\s*spark-backend-production\s*$' "${WORKFLOW}" && echo sim || echo não)"
check "cancel-in-progress é false" "sim" \
  "$(grep -qE '^\s*cancel-in-progress:\s*false\s*$' "${WORKFLOW}" && echo sim || echo não)"
check "NÃO existe cancel-in-progress: true" "não" \
  "$(grep -qE '^\s*cancel-in-progress:\s*true\s*$' "${WORKFLOW}" && echo sim || echo não)"

echo
echo "=== GitHub Environment ==="

check "declara environment: production" "sim" \
  "$(grep -qE '^\s*environment:\s*production\s*$' "${WORKFLOW}" && echo sim || echo não)"

echo
echo "=== OIDC / Workload Identity Federation, nunca chave ==="

check "usa google-github-actions/auth" "sim" \
  "$(grep -q 'google-github-actions/auth@' "${WORKFLOW}" && echo sim || echo não)"
check "usa workload_identity_provider" "sim" \
  "$(grep -q 'workload_identity_provider:' "${WORKFLOW}" && echo sim || echo não)"
check "NÃO usa credentials_json" "não" \
  "$(code_only | grep -qi 'credentials_json' && echo sim || echo não)"
check "NÃO referencia GCP_CREDENTIALS" "não" \
  "$(code_only | grep -q 'GCP_CREDENTIALS' && echo sim || echo não)"
check "NÃO menciona secretAccessor" "não" \
  "$(code_only | grep -qi 'secretAccessor' && echo sim || echo não)"
check "NÃO contém private_key (chave JSON de Service Account)" "não" \
  "$(code_only | grep -qi 'private_key' && echo sim || echo não)"
check "NÃO usa GOOGLE_APPLICATION_CREDENTIALS explicitamente" "não" \
  "$(code_only | grep -q 'GOOGLE_APPLICATION_CREDENTIALS' && echo sim || echo não)"

echo
echo "=== nenhuma Action de terceiro em @main ==="

check "nenhum 'uses:' aponta para @main" "não" \
  "$(grep -E '^\s*uses:' "${WORKFLOW}" | grep -q '@main' && echo sim || echo não)"

echo
echo "=== produção só a partir de main ==="

check "há uma guarda comparando github.ref com refs/heads/main" "sim" \
  "$(grep -qF 'github.ref }}" != "refs/heads/main"' "${WORKFLOW}" && echo sim || echo não)"
check "checkout fixa ref: main" "sim" \
  "$(grep -qE '^\s*ref:\s*main\s*$' "${WORKFLOW}" && echo sim || echo não)"
check "há uma guarda comparando HEAD com origin/main depois do checkout" "sim" \
  "$(grep -q 'origin/main' "${WORKFLOW}" && echo sim || echo não)"

echo
echo "=== autoridade única de deploy: ops/gcp/deploy-cloud-run.sh, sem duplicar comandos ==="

check "chama ops/gcp/deploy-cloud-run.sh" "sim" \
  "$(grep -q 'ops/gcp/deploy-cloud-run.sh' "${WORKFLOW}" && echo sim || echo não)"
check "NÃO chama 'gcloud run deploy' diretamente" "não" \
  "$(grep -qE '(^|[^-])gcloud run deploy ' "${WORKFLOW}" && echo sim || echo não)"
check "NÃO chama 'gcloud run jobs deploy' diretamente" "não" \
  "$(grep -q 'gcloud run jobs deploy' "${WORKFLOW}" && echo sim || echo não)"
check "NÃO chama 'gcloud run services update-traffic' diretamente" "não" \
  "$(grep -q 'gcloud run services update-traffic' "${WORKFLOW}" && echo sim || echo não)"
check "NÃO usa --skip-maintenance" "não" \
  "$(code_only | grep -q -- '--skip-maintenance' && echo sim || echo não)"
check "NÃO define SPARK_DEPLOY_ALLOW_UNVERIFIED" "não" \
  "$(code_only | grep -q 'SPARK_DEPLOY_ALLOW_UNVERIFIED' && echo sim || echo não)"

echo
echo "=== validação final independente ==="

check "roda ops/gcp/smoke-cloud-run.sh depois do deploy" "sim" \
  "$(grep -q 'ops/gcp/smoke-cloud-run.sh' "${WORKFLOW}" && echo sim || echo não)"
check "roda ops/gcp/dr-status.sh" "sim" \
  "$(grep -q 'ops/gcp/dr-status.sh' "${WORKFLOW}" && echo sim || echo não)"

echo
echo "=== resumo e rollback informativo (nunca automático) ==="

check "escreve em GITHUB_STEP_SUMMARY" "sim" \
  "$(grep -q 'GITHUB_STEP_SUMMARY' "${WORKFLOW}" && echo sim || echo não)"
check "resumo cita commit/revision/digest/tráfego/DR" "sim" \
  "$(grep -qi 'revision' "${WORKFLOW}" && grep -qi 'digest' "${WORKFLOW}" && grep -qi 'DR' "${WORKFLOW}" && echo sim || echo não)"
check "NÃO chama rollback-cloud-run.sh de forma automática (só imprime o comando canônico)" "sim" \
  "$(! grep -qE '^\s*run:\s*ops/gcp/rollback-cloud-run\.sh' "${WORKFLOW}" && echo sim || echo não)"

echo
echo "=== o novo workflow entra no CI operacional (backend.yml) ==="

BACKEND_WORKFLOW="${REPO_ROOT}/.github/workflows/backend.yml"
check "backend.yml vigia mudanças em deploy-backend.yml (push)" "sim" \
  "$(awk '/^  push:/{flag=1} /^  pull_request:/{flag=0} flag' "${BACKEND_WORKFLOW}" | grep -q '.github/workflows/deploy-backend.yml' && echo sim || echo não)"
check "backend.yml vigia mudanças em deploy-backend.yml (pull_request)" "sim" \
  "$(awk '/^  pull_request:/{flag=1} flag' "${BACKEND_WORKFLOW}" | grep -q '.github/workflows/deploy-backend.yml' && echo sim || echo não)"

finish_checks "deploy-backend.yml: workflow_dispatch exclusivo, OIDC sem chave, autoridade única de deploy, guardas de main"
