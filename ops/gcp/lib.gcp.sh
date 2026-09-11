#!/usr/bin/env bash
# Configuração e helpers compartilhados dos scripts Cloud Run do Spark (T18.2).
#
# Este arquivo não faz nada sozinho: ele é lido com `source` por `bootstrap-cloud-run.sh`,
# `deploy-cloud-run.sh`, `smoke-cloud-run.sh` e `rollback-cloud-run.sh`. Nenhum segredo mora aqui —
# só nomes de recursos e números de configuração, que §12/§58 exigem centralizados num lugar só e
# nunca espalhados entre scripts.

set -euo pipefail

# ---------------------------------------------------------------- identidade do projeto

# Nunca um default aqui: aplicar a configuração de produção contra o projeto GCP errado por causa
# de um valor esquecido é exatamente o tipo de engano que este script existe para impedir.
#
# SPARK_GCP_PROJECT     → projeto onde a infraestrutura roda: Cloud Run, Artifact Registry, Secret
#                         Manager, Service Accounts, Cloud Scheduler, GCS.
# SPARK_FIREBASE_PROJECT → projeto que emite os Firebase ID Tokens e hospeda Firebase
#                         Authentication/FCM. Pode ser um projeto GCP diferente de
#                         SPARK_GCP_PROJECT (T18.2.1) — é o caso real do Spark, onde a
#                         infraestrutura roda em `project-...` e o Firebase é `spark-36b11`. Sem
#                         declaração explícita, cai no mesmo projeto de infraestrutura: uma
#                         instalação onde os dois são o mesmo projeto continua funcionando sem
#                         configuração adicional.
SPARK_GCP_PROJECT="${SPARK_GCP_PROJECT:?defina SPARK_GCP_PROJECT (o project id do GCP)}"
SPARK_FIREBASE_PROJECT="${SPARK_FIREBASE_PROJECT:-${SPARK_GCP_PROJECT}}"
SPARK_GCP_REGION="${SPARK_GCP_REGION:-southamerica-east1}"

# ---------------------------------------------------------------- Artifact Registry

SPARK_AR_REPO="${SPARK_AR_REPO:-spark}"
SPARK_AR_IMAGE="${SPARK_AR_IMAGE:-spark-backend}"
SPARK_AR_HOST="${SPARK_GCP_REGION}-docker.pkg.dev"
# `<sha>` é resolvido por quem chama (`deploy-cloud-run.sh`) a partir de `git rev-parse HEAD` — não
# existe `latest` como identidade de deploy em lugar nenhum destes scripts (§2/§58 do enunciado).
# Consumida por deploy-cloud-run.sh depois do `source` — ShellCheck não enxerga isso ao analisar
# este arquivo isoladamente (falso positivo entre arquivos).
# shellcheck disable=SC2034
SPARK_AR_IMAGE_BASE="${SPARK_AR_HOST}/${SPARK_GCP_PROJECT}/${SPARK_AR_REPO}/${SPARK_AR_IMAGE}"

# ---------------------------------------------------------------- Cloud Run — serviços e job

SPARK_RUN_API_SERVICE="${SPARK_RUN_API_SERVICE:-spark-backend}"
# Serviço temporário só do primeiro deploy (T18.2.1 §deploy-cloud-run): valida a mesma
# imagem/configuração com um smoke antes de criar SPARK_RUN_API_SERVICE de verdade — nunca serve
# tráfego de produção, e é removido depois de validar (sucesso) ou antes de abortar (falha).
SPARK_RUN_API_VALIDATE_SERVICE="${SPARK_RUN_API_VALIDATE_SERVICE:-${SPARK_RUN_API_SERVICE}-validate}"
SPARK_RUN_MAINTENANCE_SERVICE="${SPARK_RUN_MAINTENANCE_SERVICE:-spark-maintenance}"
SPARK_RUN_MIGRATE_JOB="${SPARK_RUN_MIGRATE_JOB:-spark-db-migrate}"

# ---------------------------------------------------------------- Service Accounts

SPARK_SA_RUNTIME="${SPARK_SA_RUNTIME:-spark-backend-runtime}"
SPARK_SA_MIGRATOR="${SPARK_SA_MIGRATOR:-spark-backend-migrator}"
SPARK_SA_SCHEDULER="${SPARK_SA_SCHEDULER:-spark-maintenance-scheduler}"

sa_email() { printf '%s@%s.iam.gserviceaccount.com' "$1" "${SPARK_GCP_PROJECT}"; }

# ---------------------------------------------------------------- Secret Manager

SPARK_SECRET_DATABASE_URL="${SPARK_SECRET_DATABASE_URL:-spark-database-url}"
SPARK_SECRET_DATABASE_URL_DIRECT="${SPARK_SECRET_DATABASE_URL_DIRECT:-spark-database-url-direct}"
SPARK_SECRET_GEMINI_API_KEY="${SPARK_SECRET_GEMINI_API_KEY:-spark-gemini-api-key}"
SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY="${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY:-spark-account-deletion-hmac-key}"

# ---------------------------------------------------------------- Object Storage

SPARK_GCS_BUCKET="${SPARK_GCS_BUCKET:-spark-private-assets-prod}"

# ---------------------------------------------------------------- Cloud Scheduler

SPARK_SCHEDULER_JOB="${SPARK_SCHEDULER_JOB:-spark-maintenance-cycle}"
# Um ciclo por minuto, como o `AccountDeletionReconciler` já rodava em modo `interval` — §36 pede
# esta cadência como ponto de partida, e cada chamada é bounded (§37/§38): os dois workers de baixa
# frequência (mídia, backup) só executam quando o próprio ciclo decide que já é hora, via o CAS em
# `server_metadata` que `MaintenanceCoordinator.claimDue` já aplica.
SPARK_SCHEDULER_CRON="${SPARK_SCHEDULER_CRON:-* * * * *}"

# ---------------------------------------------------------------- configuração centralizada do Cloud Run (§12/§58)
#
# Nenhum destes números é repetido em outro script — `bootstrap-cloud-run.sh`,
# `deploy-cloud-run.sh` e a documentação leem daqui. Subir qualquer um deles é decisão
# operacional deliberada (§58), nunca um ajuste incidental de um único script.

SPARK_RUN_API_CPU="${SPARK_RUN_API_CPU:-1}"
SPARK_RUN_API_MEMORY="${SPARK_RUN_API_MEMORY:-512Mi}"
SPARK_RUN_API_MIN_INSTANCES="${SPARK_RUN_API_MIN_INSTANCES:-0}"
SPARK_RUN_API_MAX_INSTANCES="${SPARK_RUN_API_MAX_INSTANCES:-1}"
SPARK_RUN_API_CONCURRENCY="${SPARK_RUN_API_CONCURRENCY:-20}"
SPARK_RUN_API_TIMEOUT="${SPARK_RUN_API_TIMEOUT:-180}"
SPARK_RUN_PORT="${SPARK_RUN_PORT:-8080}"

SPARK_RUN_MAINTENANCE_CPU="${SPARK_RUN_MAINTENANCE_CPU:-1}"
SPARK_RUN_MAINTENANCE_MEMORY="${SPARK_RUN_MAINTENANCE_MEMORY:-512Mi}"
SPARK_RUN_MAINTENANCE_MIN_INSTANCES="${SPARK_RUN_MAINTENANCE_MIN_INSTANCES:-0}"
SPARK_RUN_MAINTENANCE_MAX_INSTANCES="${SPARK_RUN_MAINTENANCE_MAX_INSTANCES:-1}"
SPARK_RUN_MAINTENANCE_CONCURRENCY="${SPARK_RUN_MAINTENANCE_CONCURRENCY:-1}"

# Pool do PostgreSQL — conservador de propósito (§23): com `max instances=1` não há necessidade de
# pool grande, e durante um deploy pode existir revision antiga + candidate + o Job de migration
# simultaneamente, então "só um processo" nunca é a suposição certa.
SPARK_DATABASE_POOL_MIN="${SPARK_DATABASE_POOL_MIN:-0}"
SPARK_DATABASE_POOL_MAX="${SPARK_DATABASE_POOL_MAX:-5}"

# ---------------------------------------------------------------- helpers

# stdout é o canal de dado (o mesmo já é verdade em `ops/lib.sh`); log operacional vai para stderr.
log()  { printf '%s [spark-gcp] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
fail() { printf '%s [spark-gcp] ERRO: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; exit 1; }

require_cmd() {
  command -v "$1" > /dev/null 2>&1 || fail "comando obrigatório não encontrado: $1"
}

gcloud_json() {
  # `--format=json` sempre, para que quem chama faça parsing com `jq` em vez de depender do
  # formato de tabela do `gcloud`, que muda entre versões sem aviso.
  gcloud --project "${SPARK_GCP_PROJECT}" "$@" --format=json
}

# Existe o recurso? Usado pelos dois scripts para decidir "reutiliza" vs "cria" (§4 — bootstrap
# idempotente) sem depender do código de saída de `gcloud describe`, que já cumpre esse papel mas
# fica mais claro nomeado.
resource_exists() {
  gcloud --project "${SPARK_GCP_PROJECT}" "$@" > /dev/null 2>&1
}
