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
# T18.3: o Job de backup de DR do PostgreSQL e o Job do auditor PostgreSQL ↔ GCS. Mesma imagem,
# comandos próprios (`dist/cli/db-backup.js`, `dist/cli/storage-audit.js`).
SPARK_RUN_BACKUP_JOB="${SPARK_RUN_BACKUP_JOB:-spark-db-backup}"
SPARK_RUN_STORAGE_AUDIT_JOB="${SPARK_RUN_STORAGE_AUDIT_JOB:-spark-storage-audit}"

# ---------------------------------------------------------------- Service Accounts

SPARK_SA_RUNTIME="${SPARK_SA_RUNTIME:-spark-backend-runtime}"
SPARK_SA_MIGRATOR="${SPARK_SA_MIGRATOR:-spark-backend-migrator}"
SPARK_SA_SCHEDULER="${SPARK_SA_SCHEDULER:-spark-maintenance-scheduler}"
# T18.3 §2: a identidade do backup de DR. Só o secret direto e o prefixo de DR do bucket — nada
# de Firebase, Gemini, HMAC ou deploy.
SPARK_SA_BACKUP="${SPARK_SA_BACKUP:-spark-backend-backup}"

sa_email() { printf '%s@%s.iam.gserviceaccount.com' "$1" "${SPARK_GCP_PROJECT}"; }

# ---------------------------------------------------------------- Secret Manager

SPARK_SECRET_DATABASE_URL="${SPARK_SECRET_DATABASE_URL:-spark-database-url}"
SPARK_SECRET_DATABASE_URL_DIRECT="${SPARK_SECRET_DATABASE_URL_DIRECT:-spark-database-url-direct}"
SPARK_SECRET_GEMINI_API_KEY="${SPARK_SECRET_GEMINI_API_KEY:-spark-gemini-api-key}"
SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY="${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY:-spark-account-deletion-hmac-key}"

# ---------------------------------------------------------------- Object Storage

SPARK_GCS_BUCKET="${SPARK_GCS_BUCKET:-spark-private-assets-prod}"
# O namespace dos backups de DR dentro do bucket (T18.3 §2). Precisa ser o mesmo que
# `backend/src/dr/dr-manifest.ts` (`DR_POSTGRES_PREFIX`) — o Job grava aqui, o gate do deploy e o
# `dr-status.sh` leem daqui.
SPARK_DR_PREFIX="${SPARK_DR_PREFIX:-system/dr/postgres/}"
# Soft delete do bucket (T18.3 §16/§25): a proteção contra exclusão acidental de foto, backup
# pessoal, ledger e dump de DR. Sete dias é o default do GCS e o mínimo aceito pela auditoria.
SPARK_GCS_SOFT_DELETE_MIN_SECONDS="${SPARK_GCS_SOFT_DELETE_MIN_SECONDS:-604800}"

# ---------------------------------------------------------------- Cloud Scheduler

SPARK_SCHEDULER_JOB="${SPARK_SCHEDULER_JOB:-spark-maintenance-cycle}"
# Um ciclo por minuto, como o `AccountDeletionReconciler` já rodava em modo `interval` — §36 pede
# esta cadência como ponto de partida, e cada chamada é bounded (§37/§38): os dois workers de baixa
# frequência (mídia, backup) só executam quando o próprio ciclo decide que já é hora, via o CAS em
# `server_metadata` que `MaintenanceCoordinator.claimDue` já aplica.
SPARK_SCHEDULER_CRON="${SPARK_SCHEDULER_CRON:-* * * * *}"
# T18.3 §9: o backup de DR tem o próprio agendamento — uma vez por dia, de madrugada (UTC), nunca
# acoplado ao ciclo de 1 minuto da manutenção. O Scheduler dispara o Cloud Run Job pela API de
# administração (`jobs.run`), com token OAuth da mesma Service Account do Scheduler.
SPARK_BACKUP_SCHEDULER_JOB="${SPARK_BACKUP_SCHEDULER_JOB:-spark-db-backup-daily}"
SPARK_BACKUP_SCHEDULER_CRON="${SPARK_BACKUP_SCHEDULER_CRON:-15 3 * * *}"

# ---------------------------------------------------------------- DR (T18.3)

# Quantos backups válidos o Job mantém (retenção). Configurável num lugar só.
SPARK_DR_RETENTION_COUNT="${SPARK_DR_RETENTION_COUNT:-7}"
# Idade máxima do backup válido mais recente para o deploy prosseguir com a migration (§10).
SPARK_DR_MAX_BACKUP_AGE_HOURS="${SPARK_DR_MAX_BACKUP_AGE_HOURS:-24}"
# O que o deploy faz sem um backup válido dentro da janela: `run-backup` (executa o Job de backup
# e espera SUCCESS antes da migration — o default) ou `fail` (aborta o deploy). Nunca "segue".
SPARK_DR_PREDEPLOY_POLICY="${SPARK_DR_PREDEPLOY_POLICY:-run-backup}"
# Recursos do Job de backup: o dump nasce em tmpfs (conta como memória) e é carregado para o
# upload — 1 GiB cobre com folga um banco no limiar ACTION_REQUIRED (450 MB) com custom format
# comprimido. Timeout em segundos.
SPARK_RUN_BACKUP_CPU="${SPARK_RUN_BACKUP_CPU:-1}"
SPARK_RUN_BACKUP_MEMORY="${SPARK_RUN_BACKUP_MEMORY:-1Gi}"
SPARK_RUN_BACKUP_TIMEOUT="${SPARK_RUN_BACKUP_TIMEOUT:-1800}"
SPARK_RUN_STORAGE_AUDIT_TIMEOUT="${SPARK_RUN_STORAGE_AUDIT_TIMEOUT:-1800}"

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

# A revision existe E pertence ao serviço da API (T18.3 §22). `gcloud run revisions describe` não
# aceita `--service` (o gcloud real recusa com "unrecognized arguments"; o fake dos testes recusa
# igual): a pertinência vem do label `serving.knative.dev/service`. Assim a revision de OUTRO
# serviço, por typo, nunca chega a um `update-traffic`.
require_api_revision() {
  local revision="$1" owner
  owner="$(gcloud run revisions describe "${revision}" \
    --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
    --format='value(metadata.labels."serving.knative.dev/service")' 2> /dev/null || true)"
  [ -n "${owner}" ] || fail "revision inexistente: ${revision} — confira com rollback-cloud-run.sh --list"
  [ "${owner}" = "${SPARK_RUN_API_SERVICE}" ] \
    || fail "a revision ${revision} pertence ao serviço '${owner}', não a ${SPARK_RUN_API_SERVICE}"
}

# Uma variável obrigatória que não pode estar vazia (T18.3 §26). Para o que um script destrutivo
# usa como alvo: `rm`, `delete`, `DROP` — nunca com um valor que possa ser "".
require_var() {
  local name="$1"
  [ -n "${!name:-}" ] || fail "variável obrigatória vazia ou ausente: ${name}"
}

# ---------------------------------------------------------------- Secret Manager: versão pinada (T18.3 §19)

# A versão HABILITADA mais recente de um secret — o número, e só o número.
#
# O deploy referencia `secret:<versão>` em cada revision, nunca `secret:latest`: uma revision passa
# a declarar exatamente com que versão de cada secret ela sobe, e rotacionar um secret exige um
# deploy deliberado (uma revision nova) em vez de mudar o comportamento da revision atual no
# próximo cold start. Sem versão habilitada, o deploy falha aqui — antes de criar qualquer
# revision que não conseguiria subir. O valor do secret nunca é lido nem impresso.
resolve_secret_version() {
  local secret="$1" version
  version="$(gcloud secrets versions list "${secret}" \
    --project "${SPARK_GCP_PROJECT}" \
    --filter='state:enabled' \
    --sort-by='~createTime' \
    --limit=1 \
    --format='value(name.basename())')"
  case "${version}" in
    ''|*[!0-9]*) fail "secret '${secret}' sem versão habilitada (ou versão ilegível: '${version}') — defina o valor antes do deploy" ;;
  esac
  printf '%s' "${version}"
}
