#!/usr/bin/env bash
# Ensaio de restauração de DR contra o bucket REAL, num PostgreSQL descartável local (T18.3 §5/§6).
#
#   backup no GCS ──▶ dist/cli/db-restore-drill.js (na imagem do backend, com ADC do operador)
#                       ↓ CREATE DATABASE spark_drill_<ts> num postgres:17 efêmero local
#                       ↓ pg_restore --single-transaction · schema == manifesto · migrations · readiness
#                     backend REAL sobe sobre o banco restaurado ──▶ /health/ready 200, /v1 fechado (401)
#                       ↓
#                     RESTORE_DRILL_PASS
#
# Uso:
#   SPARK_GCP_PROJECT=... ops/gcp/dr-restore-drill.sh                    # o backup válido mais recente
#   SPARK_GCP_PROJECT=... ops/gcp/dr-restore-drill.sh --backup-id 2026-09-11T031500Z
#   SPARK_GCP_PROJECT=... ops/gcp/dr-restore-drill.sh --image spark-backend:local   # imagem local
#   SPARK_GCP_PROJECT=... ops/gcp/dr-restore-drill.sh --record          # registra o veredito no Cloud Logging
#
# ## Por que numa máquina do operador, e não no Cloud Run
#
# O ponto do ensaio é provar que o backup restaura SEM o provedor do banco: um PostgreSQL novo,
# em qualquer lugar, a partir só do bucket. Um banco descartável no próprio Neon duplicaria o
# armazenamento do projeto (o plano gratuito tem teto) e provaria menos. Aqui o destino é um
# container `postgres:17-alpine` que nasce e morre com o ensaio; nada aponta para produção — este
# script não recebe `DATABASE_URL` nem `DATABASE_URL_DIRECT`, e a CLI que ele chama tampouco.
#
# Pré-requisitos: docker; gcloud com ADC (`gcloud auth application-default login`) para o SDK do
# GCS dentro do container ler o bucket; `roles/storage.objectViewer` (ou Owner) no bucket.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

require_cmd docker
require_cmd gcloud
require_cmd curl

BACKUP_ID=""
IMAGE=""
RECORD=0
PG_PORT="${SPARK_DRILL_PG_PORT:-15432}"
APP_PORT="${SPARK_DRILL_APP_PORT:-18080}"
while [ $# -gt 0 ]; do
  case "$1" in
    --backup-id) BACKUP_ID="${2:?--backup-id exige um valor}"; shift 2 ;;
    --image)     IMAGE="${2:?--image exige uma imagem}"; shift 2 ;;
    --record)    RECORD=1; shift ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

# A credencial ADC do operador — o SDK do GCS dentro do container a lê por `GOOGLE_APPLICATION_CREDENTIALS`.
ADC_FILE="${GOOGLE_APPLICATION_CREDENTIALS:-${HOME}/.config/gcloud/application_default_credentials.json}"
[ -f "${ADC_FILE}" ] || fail "ADC não encontrada em ${ADC_FILE} — rode: gcloud auth application-default login"

if [ -z "${IMAGE}" ]; then
  # A imagem que ESTÁ em produção: o ensaio prova que a aplicação real restaura e sobe.
  IMAGE="$(gcloud run services describe "${SPARK_RUN_API_SERVICE}" \
    --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
    --format='value(spec.template.spec.containers[0].image)')"
  [ -n "${IMAGE}" ] || fail "não foi possível resolver a imagem em produção de ${SPARK_RUN_API_SERVICE}; use --image"
  log "imagem em produção: ${IMAGE}"
  gcloud auth configure-docker "${SPARK_AR_HOST}" --quiet --project "${SPARK_GCP_PROJECT}" > /dev/null
  docker pull "${IMAGE}" > /dev/null
fi

STAMP="$(date -u +%Y%m%dt%H%M%Sz)"
DRILL_DB="spark_drill_${STAMP}"
PG_CONTAINER="spark-dr-drill-pg-${STAMP}"
APP_CONTAINER="spark-dr-drill-app-${STAMP}"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
VERDICT="RESTORE_DRILL_FAIL"

record() {
  [ "${RECORD}" -eq 1 ] || return 0
  # Um registro estruturado no Cloud Logging, para o alerta de "drill falhou" e para o histórico.
  gcloud logging write spark-dr-drill \
    "{\"event\":\"db_restore_drill_completed\",\"operation\":\"db_restore_drill\",\"status\":\"${VERDICT}\",\"backupId\":\"${BACKUP_ID:-latest}\",\"startedAt\":\"${STARTED_AT}\",\"image\":\"${IMAGE}\"}" \
    --project "${SPARK_GCP_PROJECT}" --payload-type=json \
    --severity "$( [ "${VERDICT}" = "RESTORE_DRILL_PASS" ] && printf 'INFO' || printf 'ERROR' )" \
    > /dev/null 2>&1 || log "aviso: não foi possível registrar o veredito no Cloud Logging"
}

cleanup() {
  # `-v`: o volume anônimo de /media do container do ensaio morre com ele.
  docker rm -f -v "${APP_CONTAINER}" > /dev/null 2>&1 || true
  docker rm -f -v "${PG_CONTAINER}" > /dev/null 2>&1 || true
  record
}
trap cleanup EXIT

log "=== ensaio de restauração de DR: bucket gs://${SPARK_GCS_BUCKET}/${SPARK_DR_PREFIX} → ${DRILL_DB} ==="

# --- 1. o PostgreSQL descartável ------------------------------------------------------------
log "subindo o PostgreSQL descartável (${PG_CONTAINER}, porta ${PG_PORT})"
docker run -d --name "${PG_CONTAINER}" \
  -e POSTGRES_USER=spark -e POSTGRES_PASSWORD=spark -e POSTGRES_DB=postgres \
  -p "127.0.0.1:${PG_PORT}:5432" \
  postgres:17-alpine > /dev/null
for _ in $(seq 1 30); do
  if docker exec "${PG_CONTAINER}" pg_isready -U spark -d postgres > /dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "${PG_CONTAINER}" pg_isready -U spark -d postgres > /dev/null 2>&1 \
  || fail "o PostgreSQL descartável não ficou pronto"

ADMIN_URL="postgresql://spark:spark@127.0.0.1:${PG_PORT}/postgres"
DRILL_URL="postgresql://spark:spark@127.0.0.1:${PG_PORT}/${DRILL_DB}"

# --- 2. a CLI de ensaio, na imagem do backend, lendo o bucket real ---------------------------
log "restaurando no destino limpo ${DRILL_DB} (CLI db-restore-drill na imagem)"
# A URL do ensaio (senha sintética de um container efêmero) entra por variável de ambiente sem
# valor no argv, como todas as connection strings destes scripts.
if ! SPARK_DRILL_ADMIN_URL="${ADMIN_URL}" docker run --rm --network host \
  -v "${ADC_FILE}:/adc.json:ro" \
  -e GOOGLE_APPLICATION_CREDENTIALS=/adc.json \
  -e OBJECT_STORAGE_PROVIDER=gcs \
  -e "GCS_BUCKET_NAME=${SPARK_GCS_BUCKET}" \
  -e SPARK_DRILL_ADMIN_URL \
  -e "SPARK_DRILL_DATABASE=${DRILL_DB}" \
  -e SPARK_DRILL_KEEP_DATABASE=true \
  -e "SPARK_DR_BACKUP_ID=${BACKUP_ID}" \
  -e LOG_LEVEL=info \
  --entrypoint node "${IMAGE}" dist/cli/db-restore-drill.js | tee /dev/stderr | grep -q '^RESTORE_DRILL_PASS'; then
  fail "RESTORE_DRILL_FAIL: a CLI de ensaio reprovou (veja a saída acima)"
fi

# --- 3. a aplicação real sobe sobre o restaurado ---------------------------------------------
#
# `DATABASE_MIGRATION_MODE=verify` como em produção; Object Storage LOCAL no volume anônimo
# `/media` da própria imagem (o ensaio nunca escreve no bucket); sem Firebase (não há como emitir
# token aqui) — o que se prova é readiness e que as rotas de dado nascem fechadas.
log "subindo o backend real sobre ${DRILL_DB} (porta ${APP_PORT})"
DATABASE_URL="${DRILL_URL}" ACCOUNT_DELETION_HMAC_KEY="drill-only-account-deletion-hmac-not-for-production" \
  docker run -d --name "${APP_CONTAINER}" --network host \
  -e "PORT=${APP_PORT}" \
  -e DATABASE_URL \
  -e DATABASE_MIGRATION_MODE=verify \
  -e NODE_ENV=production \
  -e OBJECT_STORAGE_PROVIDER=local \
  -e SOCIAL_MEDIA_ROOT=/media \
  -e BACKGROUND_JOBS_MODE=disabled \
  -e ACCOUNT_DELETION_HMAC_KEY \
  -e LOG_LEVEL=warn \
  "${IMAGE}" > /dev/null

READY=0
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${APP_PORT}/health/ready" 2> /dev/null | grep -q '"status":"ok"'; then
    READY=1; break
  fi
  sleep 2
done
if [ "${READY}" -ne 1 ]; then
  docker logs "${APP_CONTAINER}" >&2 || true
  fail "RESTORE_DRILL_FAIL: o backend não ficou ready sobre o banco restaurado"
fi
log "/health/ready 200 sobre ${DRILL_DB}"

for path in /v1/auth/me /v1/backups /v1/sync/pull /v1/social/me; do
  status="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${APP_PORT}${path}")"
  [ "${status}" = "401" ] || fail "RESTORE_DRILL_FAIL: ${path} respondeu ${status} no banco restaurado; esperado 401"
done
log "rotas de dado fechadas (401) sobre o restaurado"

VERDICT="RESTORE_DRILL_PASS"
log "=== RESTORE_DRILL_PASS: backup ${BACKUP_ID:-mais recente} restaurado em destino limpo, aplicação real ready ==="
log "Lembrete: numa recuperação de verdade, rode reconcile-account-deletions contra o banco restaurado antes de apontar DATABASE_URL para ele (docs/operations/DISASTER_RECOVERY.md)."
printf 'RESTORE_DRILL_PASS\n'
