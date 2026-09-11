#!/usr/bin/env bash
#
# Primeiro deploy do spark-backend vs. deploy seguinte (T18.2.1): `--no-traffic` não tem efeito na
# primeira revision de um serviço Cloud Run novo — ela recebe 100% do único tráfego que existe,
# então o par candidate→smoke→promove tráfego não protege nada nesse caso: o tráfego "de produção"
# já estaria lá antes do smoke rodar. `ops/gcp/deploy-cloud-run.sh` agora distingue os dois
# caminhos. Este teste prova, sem `gcloud`/`docker`/`git`/`curl` reais, que:
#
#   1. Deploy seguinte (serviço já existe): fluxo original — candidate --no-traffic --tag candidate
#      → smoke → update-traffic 100%. O serviço de validação nunca é tocado.
#   2. Primeiro deploy (serviço não existe): a mesma imagem/configuração é validada num serviço
#      temporário (SPARK_RUN_API_VALIDATE_SERVICE) primeiro; só depois do smoke passar nele é que
#      o serviço real é criado — sem --no-traffic/--tag, que não teriam efeito. O serviço de
#      validação é removido depois de validar.
#   3. Primeiro deploy com smoke FALHANDO no serviço de validação: o serviço real NUNCA é criado, e
#      o serviço de validação é removido antes do script abortar.
#
# Nenhum dos dois caminhos altera migrations, secrets ou IAM — só a sequência de `gcloud run
# deploy`/`describe`/`delete` da API é diferente, e é exatamente isso que este teste verifica.
#
# Uso: ops/tests/deploy-first-run.test.sh

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

# `git`: árvore sempre limpa, SHA sempre o mesmo — o deploy real de verdade não é o que este teste
# verifica (isso é `ops/gcp/rollback-cloud-run.sh`/execução manual), só a sequência de comandos.
cat > "${FAKE_BIN_DIR}/git" <<'FAKE_GIT'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  status) exit 0 ;;
  rev-parse) printf 'abcdef123456\n' ;;
esac
exit 0
FAKE_GIT
chmod +x "${FAKE_BIN_DIR}/git"

# `docker`: build/push são sucesso silencioso; `inspect --format=...` devolve um digest fixo — o
# script só verifica que a string não é vazia, o valor exato não importa para este teste.
cat > "${FAKE_BIN_DIR}/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
if [ "${1:-}" = "inspect" ]; then
  printf 'fake.registry/spark-backend@sha256:0000000000000000000000000000000000000000000000000000000000000000\n'
fi
exit 0
FAKE_DOCKER
chmod +x "${FAKE_BIN_DIR}/docker"

# `curl`: só o que `ops/gcp/smoke-cloud-run.sh` chama — `-s -o /dev/null -w '%{http_code}' -X
# METHOD [-H header] URL`, sempre com a URL como último argumento. /health/live e /health/ready
# respondem 200; todo o resto responde 401 — a menos que CURL_FAIL_HEALTH esteja definido, usado
# para simular um serviço de validação que reprova o smoke (cenário 3).
cat > "${FAKE_BIN_DIR}/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
url="${!#}"
if [ -n "${CURL_FAIL_HEALTH:-}" ]; then
  printf '500'
  exit 0
fi
case "${url}" in
  *"/health/live") printf '200' ;;
  *"/health/ready") printf '200' ;;
  *) printf '401' ;;
esac
FAKE_CURL
chmod +x "${FAKE_BIN_DIR}/curl"

# `gcloud`: registra cada chamada em GCLOUD_CALL_LOG.
#   - `run services describe <nome> --region R` SEM --format: é a checagem de existência
#     (resource_exists) — falha (não existe) só quando <nome> é igual a GCLOUD_MISSING_SERVICE.
#   - `run services describe <nome> ... --format=value(status.url)`: sempre sucesso, devolve uma
#     URL fake derivada do nome.
#   - `run services describe <nome> ... --format=value(status.traffic[0].revisionName)`: sempre
#     sucesso, devolve uma revision fake derivada do nome.
#   - qualquer outro comando (deploy, delete, jobs deploy/execute, update-traffic, auth
#     configure-docker): sucesso.
cat > "${FAKE_BIN_DIR}/gcloud" <<'FAKE_GCLOUD'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GCLOUD_CALL_LOG}"

is_describe=0
has_format=0
format_value=""
name=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[$i]}" in
    describe)
      is_describe=1
      name="${args[$((i + 1))]:-}"
      ;;
    --format=*)
      has_format=1
      format_value="${args[$i]#--format=}"
      ;;
  esac
done

if [ "${is_describe}" -eq 1 ] && [ "${has_format}" -eq 0 ]; then
  [ "${name}" = "${GCLOUD_MISSING_SERVICE:-}" ] && exit 1
  exit 0
fi

if [ "${is_describe}" -eq 1 ] && [ "${has_format}" -eq 1 ]; then
  case "${format_value}" in
    *status.url*) printf 'https://%s-abc123-uc.a.run.app\n' "${name}" ;;
    *status.traffic*) printf '%s-00001-xyz\n' "${name}" ;;
  esac
  exit 0
fi

exit 0
FAKE_GCLOUD
chmod +x "${FAKE_BIN_DIR}/gcloud"

run_deploy() {
  GCLOUD_CALL_LOG="$(mktemp)"
  export GCLOUD_CALL_LOG
  PATH="${FAKE_BIN_DIR}:${PATH}" \
    SPARK_GCP_PROJECT=infra-project \
    SPARK_FIREBASE_PROJECT=firebase-project \
    SPARK_GCP_REGION=southamerica-east1 \
    "${OPS_DIR}/gcp/deploy-cloud-run.sh" --skip-maintenance > "${GCLOUD_CALL_LOG}.out" 2>&1
}

linha_sem() {
  # Uma linha do log que contém $2 e não contém $3 (ex.: "run services describe spark-backend
  # --region ..." sem "--format", para distinguir a checagem de existência da busca de URL).
  grep -F -- "$2" "$1" | grep -F -v -- "$3" | grep -q .
}

echo "=== deploy seguinte: spark-backend já existe (fluxo original) ==="

CODIGO=0
run_deploy || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
rm -f "${GCLOUD_CALL_LOG}" "${GCLOUD_CALL_LOG}.out"

check "deploy termina com sucesso" "0" "${CODIGO}"
check "checa a existência de spark-backend antes de decidir o caminho" "sim" \
  "$(linha_sem <(printf '%s' "$LOG") "run services describe spark-backend --region" "--format" && echo sim || echo não)"
check "candidate é criado com --no-traffic e --tag candidate" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend ' | grep -q -- '--no-traffic' && printf '%s\n' "$LOG" | grep 'run deploy spark-backend ' | grep -q -- '--tag candidate' && echo sim || echo não)"
check "o serviço de validação nunca é tocado" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'spark-backend-validate' && echo sim || echo não)"
check "100% do tráfego é movido para o candidate" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'update-traffic spark-backend .*--to-tags candidate=100' && echo sim || echo não)"
check "nenhum serviço é removido" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'services delete' && echo sim || echo não)"
check "a saída confirma smoke PASS" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'smoke PASS' && echo sim || echo não)"

echo
echo "=== primeiro deploy: spark-backend ainda não existe (T18.2.1) ==="

CODIGO=0
GCLOUD_MISSING_SERVICE=spark-backend run_deploy || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
rm -f "${GCLOUD_CALL_LOG}" "${GCLOUD_CALL_LOG}.out"

check "deploy termina com sucesso" "0" "${CODIGO}"
check "o serviço de validação é criado sem --no-traffic/--tag" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend-validate' | grep -qv -- '--no-traffic\|--tag' && echo sim || echo não)"
check "o smoke roda contra o serviço de validação" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'smoke contra https://spark-backend-validate-abc123-uc.a.run.app' && echo sim || echo não)"
check "o serviço de validação é removido depois do smoke passar" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'services delete spark-backend-validate' && echo sim || echo não)"
check "spark-backend real é criado sem --no-traffic/--tag (não têm efeito no 1º deploy)" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend ' | grep -qv -- '--no-traffic\|--tag' && echo sim || echo não)"
check "nenhum update-traffic é chamado (tráfego automático no 1º deploy)" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'update-traffic' && echo sim || echo não)"

echo
echo "=== primeiro deploy: smoke FALHA no serviço de validação ==="

CODIGO=0
GCLOUD_MISSING_SERVICE=spark-backend CURL_FAIL_HEALTH=1 run_deploy || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
rm -f "${GCLOUD_CALL_LOG}" "${GCLOUD_CALL_LOG}.out"

check "deploy falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "o serviço de validação é removido antes de abortar" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'services delete spark-backend-validate' && echo sim || echo não)"
check "spark-backend real NUNCA é criado" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run deploy spark-backend ' && echo sim || echo não)"
check "a mensagem explica que o serviço real não foi criado" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'NÃO foi criado' && echo sim || echo não)"

echo
if [ "$failures" -gt 0 ]; then
  printf '=== %d verificação(ões) falharam ===\n' "$failures" >&2
  exit 1
fi
echo "=== primeiro deploy e deploy seguinte seguem caminhos distintos e seguros ==="
