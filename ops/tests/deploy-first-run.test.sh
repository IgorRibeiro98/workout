#!/usr/bin/env bash
#
# Primeiro deploy do spark-backend vs. deploy seguinte (T18.2.1; validação privada desde a T18.3 §21):
# `--no-traffic` não tem efeito na primeira revision de um serviço Cloud Run novo — ela recebe 100%
# do único tráfego que existe, então o par candidate→smoke→promove tráfego não protege nada nesse
# caso. `ops/gcp/deploy-cloud-run.sh` distingue os dois caminhos. Este teste prova, sem
# `gcloud`/`docker`/`git`/`curl` reais (dublês de `ops/tests/lib.fakes.sh`), que:
#
#   1. Deploy seguinte (serviço já existe): fluxo original — candidate --no-traffic --tag candidate
#      → smoke → update-traffic 100%. O serviço de validação nunca é tocado, e o smoke do candidate
#      NÃO leva header de invocação (o serviço real é público).
#   2. Primeiro deploy (serviço não existe): a mesma imagem/configuração é validada num serviço
#      temporário e PRIVADO (--no-allow-unauthenticated) primeiro, com o identity token do operador
#      em X-Serverless-Authorization; só depois do smoke passar nele é que o serviço real é criado
#      (público) — sem --no-traffic/--tag, que não teriam efeito. O temporário é removido depois.
#   3. Primeiro deploy com smoke FALHANDO no serviço de validação: o serviço real NUNCA é criado, e
#      o serviço de validação é removido antes do script abortar.
#
# Uso: ops/tests/deploy-first-run.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

FAKE_BIN_DIR="$(mktemp -d)"
trap 'rm -rf "${FAKE_BIN_DIR}"' EXIT
install_fakes "${FAKE_BIN_DIR}"

run_deploy() {
  GCLOUD_CALL_LOG="$(mktemp)"
  CURL_CALL_LOG="$(mktemp)"
  export GCLOUD_CALL_LOG CURL_CALL_LOG
  # Um backup de DR válido e recente já existe: o gate passa sem executar o Job de backup, e este
  # teste continua sendo só sobre a sequência de deploy da API (o gate tem teste próprio).
  PATH="${FAKE_BIN_DIR}:${PATH}" \
    FAKE_DR_BACKUPS=2026-09-11T031500Z \
    SPARK_GCP_PROJECT=infra-project \
    SPARK_FIREBASE_PROJECT=firebase-project \
    SPARK_GCP_REGION=southamerica-east1 \
    "${OPS_DIR}/gcp/deploy-cloud-run.sh" --skip-maintenance > "${GCLOUD_CALL_LOG}.out" 2>&1
}

cleanup_logs() {
  rm -rf "${GCLOUD_CALL_LOG}" "${GCLOUD_CALL_LOG}.out" "${GCLOUD_CALL_LOG}.state" "${CURL_CALL_LOG}"
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
CURLS="$(cat "${CURL_CALL_LOG}")"
cleanup_logs

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
check "o smoke do candidate (serviço público) não leva X-Serverless-Authorization" "não" \
  "$(printf '%s\n' "$CURLS" | grep -q 'X-Serverless-Authorization' && echo sim || echo não)"

echo
echo "=== primeiro deploy: spark-backend ainda não existe (T18.2.1; temporário privado — T18.3 §21) ==="

CODIGO=0
GCLOUD_MISSING_SERVICE=spark-backend run_deploy || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
CURLS="$(cat "${CURL_CALL_LOG}")"
cleanup_logs

check "deploy termina com sucesso" "0" "${CODIGO}"
check "o serviço de validação é criado sem --no-traffic/--tag" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend-validate' | grep -qv -- '--no-traffic\|--tag' && echo sim || echo não)"
check "o serviço de validação é PRIVADO (--no-allow-unauthenticated)" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend-validate' | grep -q -- '--no-allow-unauthenticated' && echo sim || echo não)"
check "o serviço de validação nunca recebe --allow-unauthenticated" "não" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend-validate' | grep -q -- ' --allow-unauthenticated' && echo sim || echo não)"
check "o smoke roda contra o serviço de validação" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'smoke contra https://spark-backend-validate-abc123-uc.a.run.app' && echo sim || echo não)"
check "o deploy obtém o identity token do operador para o smoke" "sim" \
  "$(printf '%s\n' "$LOG" | grep -qx 'auth print-identity-token' && echo sim || echo não)"
check "toda chamada do smoke ao temporário leva X-Serverless-Authorization" "sim" \
  "$(printf '%s\n' "$CURLS" | grep 'spark-backend-validate' | grep -qv 'X-Serverless-Authorization: Bearer fake-identity-token' && echo não || echo sim)"
check "o token nunca aparece na saída do deploy" "não" \
  "$(printf '%s' "$SAIDA" | grep -q 'fake-identity-token' && echo sim || echo não)"
check "o serviço de validação é removido depois do smoke passar" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'services delete spark-backend-validate' && echo sim || echo não)"
check "spark-backend real é criado sem --no-traffic/--tag (não têm efeito no 1º deploy)" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend ' | grep -qv -- '--no-traffic\|--tag' && echo sim || echo não)"
check "spark-backend real é PÚBLICO (--allow-unauthenticated)" "sim" \
  "$(printf '%s\n' "$LOG" | grep 'run deploy spark-backend ' | grep -q -- ' --allow-unauthenticated' && echo sim || echo não)"
check "nenhum update-traffic é chamado (tráfego automático no 1º deploy)" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'update-traffic' && echo sim || echo não)"

echo
echo "=== primeiro deploy: smoke FALHA no serviço de validação ==="

CODIGO=0
GCLOUD_MISSING_SERVICE=spark-backend CURL_FAIL_HEALTH=1 run_deploy || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"
cleanup_logs

check "deploy falha" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "o serviço de validação é removido antes de abortar" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'services delete spark-backend-validate' && echo sim || echo não)"
check "spark-backend real NUNCA é criado" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'run deploy spark-backend ' && echo sim || echo não)"
check "a mensagem explica que o serviço real não foi criado" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'NÃO foi criado' && echo sim || echo não)"

finish_checks "primeiro deploy e deploy seguinte seguem caminhos distintos e seguros"
