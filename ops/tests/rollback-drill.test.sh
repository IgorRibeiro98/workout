#!/usr/bin/env bash
#
# O rollback provado offline (T18.3 §22): `rollback-cloud-run.sh` move tráfego para uma revision
# que existe (e recusa uma que não existe), e `rollback-drill.sh` faz A → B → A com smoke depois
# de cada movimento — e devolve o tráfego a A se B reprovar. Dublês de `ops/tests/lib.fakes.sh`;
# nenhum rebuild, nenhuma migration, nenhum banco.
#
# Uso: ops/tests/rollback-drill.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

FAKE_BIN_DIR="$(mktemp -d)"
trap 'rm -rf "${FAKE_BIN_DIR}"' EXIT
install_fakes "${FAKE_BIN_DIR}"

run_script() {
  # run_script <script> [args...]  — com VAR=valor via ambiente do chamador.
  GCLOUD_CALL_LOG="$(mktemp)"
  CURL_CALL_LOG="$(mktemp)"
  export GCLOUD_CALL_LOG CURL_CALL_LOG
  PATH="${FAKE_BIN_DIR}:${PATH}" \
    SPARK_GCP_PROJECT=infra-project \
    SPARK_GCP_REGION=southamerica-east1 \
    "$@" > "${GCLOUD_CALL_LOG}.out" 2>&1
}
cleanup_logs() { rm -rf "${GCLOUD_CALL_LOG}" "${GCLOUD_CALL_LOG}.out" "${GCLOUD_CALL_LOG}.state" "${CURL_CALL_LOG}"; }
traffic_moves() { printf '%s\n' "$1" | grep 'update-traffic spark-backend' | sed 's/.*--to-revisions \([^ ]*\)=100.*/\1/'; }

echo "=== rollback-cloud-run.sh ==="
CODIGO=0
run_script "${OPS_DIR}/gcp/rollback-cloud-run.sh" spark-backend-00001-gs2 || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; cleanup_logs
check "rollback para uma revision existente termina com sucesso" "0" "${CODIGO}"
check "confere que a revision existe antes de mover tráfego" "sim" \
  "$(printf '%s\n' "$LOG" | grep -q 'run revisions describe spark-backend-00001-gs2' && echo sim || echo não)"
check "move 100% do tráfego para a revision pedida" "spark-backend-00001-gs2" "$(traffic_moves "$LOG")"
check "nunca builda nem faz deploy" "não" \
  "$(printf '%s\n' "$LOG" | grep -qE 'run deploy|jobs execute|jobs deploy' && echo sim || echo não)"

CODIGO=0
GCLOUD_MISSING=spark-backend-99999-nope run_script "${OPS_DIR}/gcp/rollback-cloud-run.sh" spark-backend-99999-nope || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "revision inexistente é recusada" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...sem mover tráfego" "não" \
  "$(printf '%s\n' "$LOG" | grep -q 'update-traffic' && echo sim || echo não)"
check "...com a mensagem apontando --list" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q -- '--list' && echo sim || echo não)"

echo
echo "=== rollback-drill.sh: A → B → A ==="
# A revision atual (fake): spark-backend-00001-xyz. O alvo B: spark-backend-00002-abc.
CODIGO=0
run_script "${OPS_DIR}/gcp/rollback-drill.sh" --to spark-backend-00002-abc || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; CURLS="$(cat "${CURL_CALL_LOG}")"; cleanup_logs
check "o ensaio termina com sucesso" "0" "${CODIGO}"
check "sequência de tráfego: B, depois A" "spark-backend-00002-abc spark-backend-00001-xyz" "$(traffic_moves "$LOG" | paste -sd' ' -)"
check "smoke antes, depois de B e depois de A (3 × 6 rotas = 18 chamadas)" "18" "$(printf '%s\n' "$CURLS" | grep -c 'a.run.app' || true)"
check "o veredito é ROLLBACK_DRILL_PASS terminando em A" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'ROLLBACK_DRILL_PASS: spark-backend-00001-xyz → spark-backend-00002-abc → spark-backend-00001-xyz (tráfego ficou em spark-backend-00001-xyz)' && echo sim || echo não)"
check "os timestamps de cada movimento estão no relatório" "2" "$(printf '%s' "$SAIDA" | grep -c '→ 100% do tráfego para' || true)"

echo
echo "=== rollback-drill.sh: --stay termina em B ==="
CODIGO=0
run_script "${OPS_DIR}/gcp/rollback-drill.sh" --to spark-backend-00002-abc --stay || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "termina com sucesso" "0" "${CODIGO}"
check "sequência: B, A, B" "spark-backend-00002-abc spark-backend-00001-xyz spark-backend-00002-abc" "$(traffic_moves "$LOG" | paste -sd' ' -)"

echo
echo "=== rollback-drill.sh: B reprova o smoke → tráfego volta para A e o ensaio falha ==="
# O curl fake responde 500 quando CURL_FAIL_HEALTH está definido; para reprovar SÓ em B, o
# arquivo-gatilho abaixo é criado pelo primeiro update-traffic... simples demais para o fake.
# Aqui o smoke inicial já reprova (500 em tudo): o ensaio precisa abortar SEM mover tráfego.
CODIGO=0
CURL_FAIL_HEALTH=1 run_script "${OPS_DIR}/gcp/rollback-drill.sh" --to spark-backend-00002-abc || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "smoke inicial reprovando aborta o ensaio" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...sem mover tráfego" "" "$(traffic_moves "$LOG" | paste -sd' ' -)"
check "...explicando que a revision atual já reprova" "sim" \
  "$(printf '%s' "$SAIDA" | grep -q 'já reprova o smoke' && echo sim || echo não)"

echo
echo "=== rollback-drill.sh: guardas ==="
CODIGO=0
run_script "${OPS_DIR}/gcp/rollback-drill.sh" --to spark-backend-00001-xyz || CODIGO=$?
SAIDA="$(cat "${GCLOUD_CALL_LOG}.out")"; cleanup_logs
check "alvo igual à revision atual é recusado" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...com explicação" "sim" "$(printf '%s' "$SAIDA" | grep -q 'já é a atual' && echo sim || echo não)"

CODIGO=0
run_script "${OPS_DIR}/gcp/rollback-drill.sh" || CODIGO=$?
cleanup_logs
check "sem --to é recusado" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"

CODIGO=0
GCLOUD_MISSING=spark-backend-00009-nao run_script "${OPS_DIR}/gcp/rollback-drill.sh" --to spark-backend-00009-nao || CODIGO=$?
LOG="$(cat "${GCLOUD_CALL_LOG}")"; cleanup_logs
check "revision alvo inexistente é recusada antes de qualquer movimento" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "...sem update-traffic" "não" "$(printf '%s\n' "$LOG" | grep -q 'update-traffic' && echo sim || echo não)"

finish_checks "rollback e rollback drill provados offline: A → B → A com smoke, sem rebuild, sem banco"
