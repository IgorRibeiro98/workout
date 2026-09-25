#!/usr/bin/env bash
#
# O smoke do Cloud Run provado offline (T19.H5 §52/§53): sem token ele continua o de sempre (e
# confere que "Compartilhar progresso" exige conta); com o token de uma conta de teste, ele confere
# o contrato v2 de /v1/social/me/progress-sharing — versão, os quinze interruptores, as oito
# disponibilidades e os motivos — sem imprimir o corpo, que são as escolhas de privacidade da conta.
#
# Nenhuma rede: o `curl` daqui é um dublê que responde o corpo pedido em `SMOKE_SHARING_BODY`.
#
# Uso: ops/tests/smoke-cloud-run.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

FAKE_BIN_DIR="$(mktemp -d)"
trap 'rm -rf "${FAKE_BIN_DIR}"' EXIT
install_fakes "${FAKE_BIN_DIR}"

# O `curl` dos outros testes só devolve status. Este também escreve o corpo em `-o`, e só responde
# 200 com `Authorization: Bearer` — como o servidor de verdade.
cat > "${FAKE_BIN_DIR}/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
[ -z "${CURL_CALL_LOG:-}" ] || printf '%s\n' "$*" >> "${CURL_CALL_LOG}"
out=""
auth="não"
while [ "$#" -gt 1 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -H) case "$2" in "Authorization: Bearer "*) auth="sim" ;; esac; shift 2 ;;
    *) shift ;;
  esac
done
url="$1"
case "${url}" in
  *"/health/live" | *"/health/ready") printf '200'; exit 0 ;;
esac
if [ "${auth}" != "sim" ]; then
  printf '401'
  exit 0
fi
case "${url}" in
  *"/v1/auth/me") printf '200' ;;
  *"/v1/social/me/progress-sharing")
    [ -z "${out}" ] || printf '%s' "${SMOKE_SHARING_BODY:-}" > "${out}"
    printf '%s' "${SMOKE_SHARING_STATUS:-200}"
    ;;
  *) printf '401' ;;
esac
FAKE_CURL
chmod +x "${FAKE_BIN_DIR}/curl"

run_smoke() {
  CURL_CALL_LOG="$(mktemp)"
  SMOKE_OUT="$(mktemp)"
  export CURL_CALL_LOG
  CODIGO=0
  PATH="${FAKE_BIN_DIR}:${PATH}" \
    SPARK_GCP_PROJECT=infra-project \
    SPARK_GCP_REGION=southamerica-east1 \
    "${OPS_DIR}/gcp/smoke-cloud-run.sh" https://candidate.example > "${SMOKE_OUT}" 2>&1 || CODIGO=$?
  LOG="$(cat "${CURL_CALL_LOG}")"
  SAIDA="$(cat "${SMOKE_OUT}")"
  rm -f "${CURL_CALL_LOG}" "${SMOKE_OUT}"
}

TOKEN="token-de-conta-de-teste-nao-imprimir"
PRIVATE_TZ="Pacific/Chatham"

# A resposta que o backend da T19.H5 dá. `weekTimeZone` carrega um valor que só existe aqui: se ele
# aparecer na saída, o corpo vazou para o log.
V2_BODY="$(cat <<JSON
{"contractVersion":2,
 "settings":{"shareLevel":true,"shareConsistencyStreak":false,"shareWeeklyWorkoutCount":false,
  "shareHighlightedAchievements":false,"shareWeeklyTrainingMinutes":false,
  "shareWeeklyCompletedSets":false,"shareWeeklyVolume":false,"shareTotalWorkouts":true,
  "shareWorkoutName":false,"shareWorkoutTime":false,"shareWorkoutDuration":false,
  "shareWorkoutExercises":false,"shareWorkoutSets":false,"shareWorkoutWeights":false,
  "shareWorkoutVolume":false,"weekTimeZone":"${PRIVATE_TZ}","consistency":null,"updatedAt":1},
 "availability":{"level":"AVAILABLE","consistencyStreak":"AVAILABLE","weeklyWorkoutCount":"AVAILABLE",
  "highlightedAchievements":"AVAILABLE","weeklyTrainingMinutes":"AVAILABLE",
  "weeklyCompletedSets":"AVAILABLE","weeklyVolume":"AVAILABLE","totalWorkouts":"AVAILABLE"},
 "availabilityReasons":{}}
JSON
)"

# O servidor anterior à T19.H3 (produção até 2026-09-25): sem versão e sem os onze interruptores.
LEGACY_BODY='{"settings":{"shareLevel":true,"shareConsistencyStreak":false,"shareWeeklyWorkoutCount":false,"shareHighlightedAchievements":false,"weekTimeZone":null,"updatedAt":1},"availability":{"level":"AVAILABLE","consistencyStreak":"AVAILABLE","weeklyWorkoutCount":"AVAILABLE","highlightedAchievements":"AVAILABLE"}}'

echo "=== sem token: o smoke de sempre, e Compartilhar progresso exige conta ==="
run_smoke
check "passa" "0" "${CODIGO}"
check "confere GET /v1/social/me/progress-sharing sem token" "sim" \
  "$(printf '%s\n' "${LOG}" | grep -q '/v1/social/me/progress-sharing' && echo sim || echo não)"
check "nenhuma chamada leva Authorization" "não" \
  "$(printf '%s\n' "${LOG}" | grep -q 'Authorization' && echo sim || echo não)"

echo
echo "=== com token de conta de teste: o contrato v2 ==="
SPARK_SMOKE_FIREBASE_ID_TOKEN="${TOKEN}" SMOKE_SHARING_BODY="${V2_BODY}" run_smoke
check "contrato v2 completo passa" "0" "${CODIGO}"
check "...e diz a versão verificada" "sim" \
  "$(printf '%s' "${SAIDA}" | grep -q 'contrato v2' && echo sim || echo não)"
check "...sem imprimir o corpo (escolhas de privacidade da conta)" "não" \
  "$(printf '%s' "${SAIDA}" | grep -q "${PRIVATE_TZ}" && echo sim || echo não)"
check "...e sem imprimir o token" "não" \
  "$(printf '%s' "${SAIDA}" | grep -q "${TOKEN}" && echo sim || echo não)"

SPARK_SMOKE_FIREBASE_ID_TOKEN="${TOKEN}" SMOKE_SHARING_BODY="${LEGACY_BODY}" run_smoke
check "servidor sem contractVersion reprova" "sim" "$( [ "${CODIGO}" != "0" ] && echo sim || echo não )"
check "...dizendo que o servidor é anterior à T19.H5" "sim" \
  "$(printf '%s' "${SAIDA}" | grep -q 'contractVersion' && echo sim || echo não)"

SPARK_SMOKE_FIREBASE_ID_TOKEN="${TOKEN}" \
  SMOKE_SHARING_BODY="$(printf '%s' "${V2_BODY}" | sed 's/"shareWorkoutWeights":false,//')" run_smoke
check "v2 sem um interruptor reprova" "sim" "$( [ "${CODIGO}" != "0" ] && echo sim || echo não )"
check "...nomeando a chave, não o valor" "sim" \
  "$(printf '%s' "${SAIDA}" | grep -q 'shareWorkoutWeights' && echo sim || echo não)"

SPARK_SMOKE_FIREBASE_ID_TOKEN="${TOKEN}" \
  SMOKE_SHARING_BODY="$(printf '%s' "${V2_BODY}" | sed 's/"availabilityReasons":{}/"outraChave":{}/')" run_smoke
check "v2 sem availabilityReasons reprova" "sim" "$( [ "${CODIGO}" != "0" ] && echo sim || echo não )"

SPARK_SMOKE_FIREBASE_ID_TOKEN="${TOKEN}" SMOKE_SHARING_BODY='não é json' run_smoke
check "corpo ilegível reprova" "sim" "$( [ "${CODIGO}" != "0" ] && echo sim || echo não )"

SPARK_SMOKE_FIREBASE_ID_TOKEN="${TOKEN}" SMOKE_SHARING_STATUS=404 run_smoke
check "conta sem perfil social: pula, sem reprovar" "0" "${CODIGO}"
check "...e diz que o contrato não foi verificado" "sim" \
  "$(printf '%s' "${SAIDA}" | grep -q 'NÃO verificado' && echo sim || echo não)"

SPARK_SMOKE_FIREBASE_ID_TOKEN="${TOKEN}" SMOKE_SHARING_STATUS=500 run_smoke
check "5xx reprova" "sim" "$( [ "${CODIGO}" != "0" ] && echo sim || echo não )"

finish_checks "smoke do Cloud Run: sem token igual ao de sempre; com conta de teste, o contrato v2 de progress-sharing"
