#!/usr/bin/env bash
# Smoke HTTP contra um serviço Cloud Run do Spark Backend (T18.2 §50/§54).
#
# Roda os testes obrigatórios de superfície pública/privada:
#   GET  /health/live   → 200, sem token
#   GET  /health/ready   → 200, sem token
#   GET  /v1/auth/me     → 401, sem token
#   GET  /v1/backups     → 401, sem token
#   GET  /v1/sync/pull   → 401, sem token
#   GET  /v1/social/me   → 401, sem token
#
# Uso:
#   ops/gcp/smoke-cloud-run.sh <url-base>
#
# Sai com código != 0 na primeira falha — "candidate health FAIL" não pode deixar o deploy
# continuar (§5/§10).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

require_cmd curl

BASE_URL="${1:?uso: smoke-cloud-run.sh <url-base>}"
BASE_URL="${BASE_URL%/}"

check_status() {
  local method="$1" path="$2" expected="$3" auth_header="${4:-}"
  local got
  if [ -n "${auth_header}" ]; then
    got="$(curl -s -o /dev/null -w '%{http_code}' -X "${method}" -H "${auth_header}" "${BASE_URL}${path}")"
  else
    got="$(curl -s -o /dev/null -w '%{http_code}' -X "${method}" "${BASE_URL}${path}")"
  fi
  if [ "${got}" != "${expected}" ]; then
    fail "smoke falhou: ${method} ${path} esperava ${expected}, recebeu ${got}"
  fi
  log "OK: ${method} ${path} → ${got}"
}

log "smoke contra ${BASE_URL}"

check_status GET /health/live 200
check_status GET /health/ready 200

check_status GET /v1/auth/me 401
check_status GET /v1/backups 401
check_status GET /v1/sync/pull 401
check_status GET /v1/social/me 401

# Firebase de verdade (§51) é opcional: só roda se um token real foi passado pela variável de
# ambiente — o smoke automático de deploy nunca gera nem imprime um token de conta real.
if [ -n "${SPARK_SMOKE_FIREBASE_ID_TOKEN:-}" ]; then
  check_status GET /v1/auth/me 200 "Authorization: Bearer ${SPARK_SMOKE_FIREBASE_ID_TOKEN}"
else
  log "SPARK_SMOKE_FIREBASE_ID_TOKEN não definido — pulando a verificação de auth com conta real (§51)"
fi

log "smoke PASS: ${BASE_URL}"
