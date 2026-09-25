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
#   GET  /v1/social/me/progress-sharing → 401, sem token
#
# Com `SPARK_SMOKE_FIREBASE_ID_TOKEN` (conta de TESTE, nunca a principal — ver
# docs/operations/OPERATIONS_CHECKLIST.md), também:
#   GET  /v1/auth/me                     → 200
#   GET  /v1/social/me/progress-sharing  → contrato v2 (T19.H5): `contractVersion` ≥ 2, os quinze
#        interruptores, as oito disponibilidades e `availabilityReasons`. Só leitura; o corpo — as
#        escolhas de privacidade da conta — nunca é impresso. Conta sem perfil social: pulado.
#
# Uso:
#   ops/gcp/smoke-cloud-run.sh <url-base>
#   SPARK_SMOKE_INVOKER_TOKEN=<identity token> ops/gcp/smoke-cloud-run.sh <url-privada>
#
# `SPARK_SMOKE_INVOKER_TOKEN` (T18.3 §21): para um serviço Cloud Run PRIVADO (o temporário do
# primeiro deploy), o identity token do operador vai em `X-Serverless-Authorization`. O Cloud Run
# valida e REMOVE esse header antes de entregar a requisição ao container — `Authorization` continua
# livre para o Firebase Bearer, e as expectativas abaixo (401 sem token de conta, 200 com um real)
# não mudam. O token nunca é impresso e nunca entra no argv: só no header da chamada.
#
# Sai com código != 0 na primeira falha — "candidate health FAIL" não pode deixar o deploy
# continuar (§5/§10).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

require_cmd curl

BASE_URL="${1:?uso: smoke-cloud-run.sh <url-base>}"
BASE_URL="${BASE_URL%/}"

# O header de invocação IAM, quando o alvo é privado. Um array vazio quando não é: `curl` recebe
# exatamente os mesmos argumentos de sempre.
INVOKER_HEADER=()
if [ -n "${SPARK_SMOKE_INVOKER_TOKEN:-}" ]; then
  INVOKER_HEADER=(-H "X-Serverless-Authorization: Bearer ${SPARK_SMOKE_INVOKER_TOKEN}")
  log "alvo privado: usando X-Serverless-Authorization (o token não é impresso)"
fi

check_status() {
  local method="$1" path="$2" expected="$3" auth_header="${4:-}"
  local got
  if [ -n "${auth_header}" ]; then
    got="$(curl -s -o /dev/null -w '%{http_code}' -X "${method}" "${INVOKER_HEADER[@]}" -H "${auth_header}" "${BASE_URL}${path}")"
  else
    got="$(curl -s -o /dev/null -w '%{http_code}' -X "${method}" "${INVOKER_HEADER[@]}" "${BASE_URL}${path}")"
  fi
  if [ "${got}" != "${expected}" ]; then
    fail "smoke falhou: ${method} ${path} esperava ${expected}, recebeu ${got}"
  fi
  log "OK: ${method} ${path} → ${got}"
}

# Os interruptores de "Compartilhar progresso" e as disponibilidades que o app da T19.H5 espera de
# um servidor que declara o contrato v2. Um servidor sem eles é o defeito que a T19.H5 investigou:
# o app oferecia interruptores que o servidor recusava com INVALID_PROGRESS_SETTINGS.
PROGRESS_SHARING_FLAGS='["shareLevel","shareConsistencyStreak","shareWeeklyWorkoutCount",
  "shareHighlightedAchievements","shareWeeklyTrainingMinutes","shareWeeklyCompletedSets",
  "shareWeeklyVolume","shareTotalWorkouts","shareWorkoutName","shareWorkoutTime",
  "shareWorkoutDuration","shareWorkoutExercises","shareWorkoutSets","shareWorkoutWeights",
  "shareWorkoutVolume"]'
PROGRESS_SHARING_AVAILABILITY='["level","consistencyStreak","weeklyWorkoutCount",
  "highlightedAchievements","weeklyTrainingMinutes","weeklyCompletedSets","weeklyVolume",
  "totalWorkouts"]'

# O contrato de "Compartilhar progresso" com a conta real do smoke (T19.H5 §53, Smoke 1). Só leitura.
# O corpo carrega as escolhas de privacidade da conta: vai para um arquivo temporário, é lido pelo
# `jq` e apagado — o log recebe a versão e, numa falha, só os NOMES das chaves que faltam.
check_progress_sharing_contract() {
  require_cmd jq
  local body code version missing
  body="$(mktemp)"
  code="$(curl -s -o "${body}" -w '%{http_code}' "${INVOKER_HEADER[@]}" \
    -H "Authorization: Bearer ${SPARK_SMOKE_FIREBASE_ID_TOKEN}" \
    "${BASE_URL}/v1/social/me/progress-sharing")"
  if [ "${code}" = "404" ]; then
    rm -f "${body}"
    log "conta do smoke sem perfil social: contrato de /v1/social/me/progress-sharing NÃO verificado"
    return 0
  fi
  if [ "${code}" != "200" ]; then
    rm -f "${body}"
    fail "smoke falhou: GET /v1/social/me/progress-sharing esperava 200, recebeu ${code}"
  fi
  version="$(jq -r '.contractVersion // 0' "${body}" 2> /dev/null || printf '0')"
  missing="$(jq -r --argjson flags "${PROGRESS_SHARING_FLAGS}" --argjson avail "${PROGRESS_SHARING_AVAILABILITY}" '
      ($flags - ((.settings // {}) | keys))
      + ($avail - ((.availability // {}) | keys))
      + (if (.availabilityReasons | type) == "object" then [] else ["availabilityReasons"] end)
      | join(",")' "${body}" 2> /dev/null || printf 'corpo ilegível')"
  rm -f "${body}"
  case "${version}" in
    '' | *[!0-9]*) version=0 ;;
  esac
  if [ "${version}" -lt 2 ]; then
    fail "smoke falhou: /v1/social/me/progress-sharing sem contractVersion ≥ 2 (servidor anterior à T19.H5)"
  fi
  if [ -n "${missing}" ]; then
    fail "smoke falhou: /v1/social/me/progress-sharing sem as chaves: ${missing}"
  fi
  log "OK: GET /v1/social/me/progress-sharing → contrato v${version} (15 interruptores, 8 disponibilidades, motivos)"
}

log "smoke contra ${BASE_URL}"

check_status GET /health/live 200
check_status GET /health/ready 200

check_status GET /v1/auth/me 401
check_status GET /v1/backups 401
check_status GET /v1/sync/pull 401
check_status GET /v1/social/me 401
check_status GET /v1/social/me/progress-sharing 401

# Firebase de verdade (§51) é opcional: só roda se um token real foi passado pela variável de
# ambiente — o smoke automático de deploy nunca gera nem imprime um token de conta real.
if [ -n "${SPARK_SMOKE_FIREBASE_ID_TOKEN:-}" ]; then
  check_status GET /v1/auth/me 200 "Authorization: Bearer ${SPARK_SMOKE_FIREBASE_ID_TOKEN}"
  check_progress_sharing_contract
else
  log "SPARK_SMOKE_FIREBASE_ID_TOKEN não definido — pulando a verificação de auth com conta real (§51)"
fi

log "smoke PASS: ${BASE_URL}"
