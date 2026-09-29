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

# T18.3.2: a identidade de DEPLOY, separada das quatro de cima (que executam workload, nunca
# publicam). Só ela é impersonada pelo GitHub Actions via Workload Identity Federation — nunca por
# chave JSON.
SPARK_SA_GITHUB_DEPLOYER="${SPARK_SA_GITHUB_DEPLOYER:-spark-github-deployer}"

sa_email() { printf '%s@%s.iam.gserviceaccount.com' "$1" "${SPARK_GCP_PROJECT}"; }

# ---------------------------------------------------------------- GitHub Actions / Workload Identity Federation (T18.3.2)
#
# Nomes estáveis e documentados (ops/gcp/bootstrap-github-deploy.sh, docs/operations/AGENT_DEPLOYMENT.md).
# Mudar qualquer um destes depois do primeiro bootstrap real exige recriar o Provider — o comentário
# do próprio bootstrap explica por quê (a condição de atributo é imutável em segurança: nunca
# corrigida em silêncio).
SPARK_GITHUB_REPO="${SPARK_GITHUB_REPO:-IgorRibeiro98/workout}"
SPARK_WIF_POOL="${SPARK_WIF_POOL:-github-actions}"
SPARK_WIF_PROVIDER="${SPARK_WIF_PROVIDER:-workout}"
# Produção só é alcançável a partir deste ref e deste GitHub Environment — os dois entram na
# condição de atributo do Provider (§ deploy-backend.yml exige environment: production).
SPARK_GITHUB_DEPLOY_REF="${SPARK_GITHUB_DEPLOY_REF:-refs/heads/main}"
SPARK_GITHUB_DEPLOY_ENVIRONMENT="${SPARK_GITHUB_DEPLOY_ENVIRONMENT:-production}"

# ---------------------------------------------------------------- Secret Manager

SPARK_SECRET_DATABASE_URL="${SPARK_SECRET_DATABASE_URL:-spark-database-url}"
SPARK_SECRET_DATABASE_URL_DIRECT="${SPARK_SECRET_DATABASE_URL_DIRECT:-spark-database-url-direct}"
SPARK_SECRET_GEMINI_API_KEY="${SPARK_SECRET_GEMINI_API_KEY:-spark-gemini-api-key}"
# T19.H4: a chave da Groq, com o mesmo desenho da do Gemini — secret próprio, valor posto pelo
# operador fora de qualquer script, Secret Accessor só para a runtime SA.
SPARK_SECRET_GROQ_API_KEY="${SPARK_SECRET_GROQ_API_KEY:-spark-groq-api-key}"
SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY="${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY:-spark-account-deletion-hmac-key}"

# ---------------------------------------------------------------- Coach IA: provider (T19.H4)
#
# O provider de produção é decidido AQUI, num lugar só e versionado — nunca por uma variável posta
# à mão no Cloud Run (o `--set-env-vars` do deploy substitui o conjunto inteiro, e ela sumiria no
# deploy seguinte) nem por um override esquecido num shell (o próximo deploy com o default
# desfaria a troca em silêncio). Trocar de provider é editar este default + deploy; um override
# por ambiente (`SPARK_AI_PROVIDER=gemini ops/gcp/deploy-cloud-run.sh`) é só para emergência, e vale
# até o próximo deploy.
#
# O modelo também é declarado aqui, e não herdado do default do código: a revision passa a dizer,
# no próprio ambiente, qual provider e qual modelo ela atende (§53) — e o `config-drift-audit`
# confere os dois.
#
# groq/openai/gpt-oss-120b desde 2026-09-25: escolhido pelo benchmark da T19.H4 (90% fim a fim, zero
# violação crítica aceita, p95 ~5,7 s contra 20–24 s do Gemini free) com o Zero Data Retention da
# organização Groq verificado pelo dono da conta no mesmo dia. Voltar ao Gemini é trocar este
# default para `gemini` + deploy — a chave dele continua em `spark-gemini-api-key`.
SPARK_AI_PROVIDER="${SPARK_AI_PROVIDER:-groq}"
SPARK_GEMINI_MODEL="${SPARK_GEMINI_MODEL:-gemini-3.5-flash}"
SPARK_GROQ_MODEL="${SPARK_GROQ_MODEL:-openai/gpt-oss-120b}"
# Teto de saída POR provider (o raciocínio do modelo conta dentro dele nos dois). Medido na T19.H4
# (2026-09-24): com `thinkingLevel=MEDIUM`, o `gemini-3.5-flash` truncou 4 de 6 respostas em 2 048
# tokens e completou todas em 8 192, usando 2,3k–3,9k. Na Groq free o teto pedido é RESERVADO no
# TPM de 8 000 do plano na chegada da chamada (medido: 13+10 tokens com teto 100 baixaram o saldo em
# 113) — 3 000 = 8 000 − a maior entrada do Coach (~5 000). Benchmark de 2026-09-25 (GPT-OSS 120B,
# 49 chamadas): saída p95 2 354, máx. 2 800, nenhuma truncada.
SPARK_GEMINI_MAX_OUTPUT_TOKENS="${SPARK_GEMINI_MAX_OUTPUT_TOKENS:-8192}"
SPARK_GROQ_MAX_OUTPUT_TOKENS="${SPARK_GROQ_MAX_OUTPUT_TOKENS:-3000}"
# Quota do Spark ABAIXO da capacidade real do provider (T19.H4 §45): senão todos os usuários batem
# no limite externo (um 429 opaco) antes da proteção interna. Gemini free: 20 requisições/dia por
# modelo para o projeto inteiro — e o dia do Gemini vira à meia-noite do Pacífico, o do Spark à
# meia-noite UTC; 15 deixa margem para o smoke de cada deploy e para esse desencontro de janelas.
# Groq free: o teto real é o TPD (200 000 tokens/dia) ÷ tokens por chamada. O benchmark de
# 2026-09-25 (GPT-OSS 120B, 29 + 20 cenários) mediu p95 de 5 679 / 5 424 tokens por chamada → 80% do
# TPD dá 28–29 chamadas seguras por dia; 25 fica abaixo disso. A quota por conta impede que uma pessoa sozinha
# esgote a global.
SPARK_GEMINI_MAX_REQUESTS_GLOBAL_DAY="${SPARK_GEMINI_MAX_REQUESTS_GLOBAL_DAY:-15}"
SPARK_GROQ_MAX_REQUESTS_GLOBAL_DAY="${SPARK_GROQ_MAX_REQUESTS_GLOBAL_DAY:-25}"
SPARK_AI_MAX_REQUESTS_PER_USER_DAY="${SPARK_AI_MAX_REQUESTS_PER_USER_DAY:-8}"
# §41 — `true` é a decisão escrita de aceitar o risco de um modelo Preview em produção. O backend
# recusa subir com um modelo Preview sem ela.
SPARK_GROQ_ALLOW_PREVIEW_MODEL="${SPARK_GROQ_ALLOW_PREVIEW_MODEL:-false}"
# O smoke do provider antes do tráfego (§70): o `/health/ready` não prova que a chave externa
# existe, vale, tem quota e aceita o schema do Coach. `fail` (default) bloqueia a troca de tráfego;
# `warn` só registra (emergência: publicar um hotfix com o provider fora); `skip` não roda.
SPARK_RUN_AI_SMOKE_JOB="${SPARK_RUN_AI_SMOKE_JOB:-spark-ai-provider-smoke}"
SPARK_AI_SMOKE_POLICY="${SPARK_AI_SMOKE_POLICY:-fail}"

# Traduz SPARK_AI_PROVIDER para o que o deploy e as auditorias precisam, num lugar só:
#   AI_KEY_ENV / AI_KEY_SECRET — a variável e o secret da chave do provider selecionado
#   AI_MODEL_ENV / AI_MODEL    — a variável e o valor do modelo
#   AI_ENV                     — o trecho de env não-secreta da revision (vírgulas, sem espaços)
# O provider que NÃO atende não recebe nada: nem chave, nem modelo (§53).
# As cinco variáveis são a saída da função, lidas por `deploy-cloud-run.sh` e `config-drift-audit.sh`
# depois do `source` — o ShellCheck não enxerga isso analisando este arquivo isoladamente.
# shellcheck disable=SC2034
resolve_ai_provider() {
  case "${SPARK_AI_PROVIDER}" in
    gemini)
      AI_KEY_ENV=GEMINI_API_KEY
      AI_KEY_SECRET="${SPARK_SECRET_GEMINI_API_KEY}"
      AI_MODEL_ENV=GEMINI_MODEL
      AI_MODEL="${SPARK_GEMINI_MODEL}"
      AI_ENV="AI_PROVIDER=gemini,GEMINI_MODEL=${AI_MODEL},GEMINI_MAX_OUTPUT_TOKENS=${SPARK_GEMINI_MAX_OUTPUT_TOKENS},AI_MAX_REQUESTS_GLOBAL_DAY=${SPARK_GEMINI_MAX_REQUESTS_GLOBAL_DAY},AI_MAX_REQUESTS_PER_USER_DAY=${SPARK_AI_MAX_REQUESTS_PER_USER_DAY},REQUIRE_AI_PROVIDER=false"
      ;;
    groq)
      AI_KEY_ENV=GROQ_API_KEY
      AI_KEY_SECRET="${SPARK_SECRET_GROQ_API_KEY}"
      AI_MODEL_ENV=GROQ_MODEL
      AI_MODEL="${SPARK_GROQ_MODEL}"
      case "${SPARK_GROQ_ALLOW_PREVIEW_MODEL}" in
        true|false) : ;;
        *) fail "SPARK_GROQ_ALLOW_PREVIEW_MODEL inválido: '${SPARK_GROQ_ALLOW_PREVIEW_MODEL}' (use true ou false)" ;;
      esac
      AI_ENV="AI_PROVIDER=groq,GROQ_MODEL=${AI_MODEL},GROQ_MAX_OUTPUT_TOKENS=${SPARK_GROQ_MAX_OUTPUT_TOKENS},GROQ_ALLOW_PREVIEW_MODEL=${SPARK_GROQ_ALLOW_PREVIEW_MODEL},AI_MAX_REQUESTS_GLOBAL_DAY=${SPARK_GROQ_MAX_REQUESTS_GLOBAL_DAY},AI_MAX_REQUESTS_PER_USER_DAY=${SPARK_AI_MAX_REQUESTS_PER_USER_DAY},REQUIRE_AI_PROVIDER=false"
      ;;
    *) fail "SPARK_AI_PROVIDER inválido: '${SPARK_AI_PROVIDER}' (use gemini ou groq)" ;;
  esac
  [ -n "${AI_MODEL}" ] || fail "modelo vazio para SPARK_AI_PROVIDER=${SPARK_AI_PROVIDER}"
  local number
  for number in "${SPARK_GEMINI_MAX_OUTPUT_TOKENS}" "${SPARK_GROQ_MAX_OUTPUT_TOKENS}" \
                "${SPARK_GEMINI_MAX_REQUESTS_GLOBAL_DAY}" "${SPARK_GROQ_MAX_REQUESTS_GLOBAL_DAY}" \
                "${SPARK_AI_MAX_REQUESTS_PER_USER_DAY}"; do
    case "${number}" in
      ''|*[!0-9]*) fail "teto de saída/quota do Coach inválido: '${number}' (inteiro)" ;;
    esac
  done
  case "${AI_MODEL}" in
    *,*|*' '*) fail "modelo inválido para --set-env-vars: '${AI_MODEL}'" ;;
  esac
}

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
# Um ciclo a cada 15 minutos (T19.H6). Todo ciclo toca o PostgreSQL — conexão, BEGIN, lock,
# heartbeat —, mesmo quando nenhum worker tem o que fazer. Com o ciclo de 1 minuto da T18.2, o Neon
# (que suspende depois de 5 minutos sem consulta) nunca dormiu: a franquia mensal de compute do
# plano Free acabou em ~17 dias e toda rota autenticada virou 503 (2026-09-28). A cada 15 minutos o
# banco dorme ~10 por ciclo sem tráfego. Cada chamada continua bounded (§37/§38): os workers de
# baixa frequência (mídia, backup, tamanho do banco, frescor do DR) só executam quando o próprio
# ciclo decide que já é hora, via o CAS de `MaintenanceCoordinator.claimDue`.
#
# O cron, o stale do heartbeat e a janela do alerta de ausência são UMA expectativa operacional:
# mudar um exige mudar os outros, e `require_coherent_maintenance_timing` recusa o deploy e os
# alertas quando eles não batem. Sobrescrever continua possível — deliberadamente, os três juntos.
SPARK_SCHEDULER_CRON="${SPARK_SCHEDULER_CRON:-*/15 * * * *}"
# Sem ciclo bem-sucedido há mais que isto, o heartbeat diz `stale` (ms). Vai explícito para a
# revision do spark-maintenance (`MAINTENANCE_STALE_AFTER_MS`; o default do backend é o mesmo
# número). 35 min = o ciclo normal (15) + um ciclo perdido (15) + margem de cold start/deploy (5):
# um ciclo isolado que falha nunca vira stale; dois seguidos viram.
SPARK_MAINTENANCE_STALE_AFTER_MS="${SPARK_MAINTENANCE_STALE_AFTER_MS:-2100000}"
# Sem nenhuma resposta 2xx do spark-maintenance por este tempo, `spark-maintenance-stale` dispara
# (segundos; `monitoring-alerts.sh`). 45 min = três ciclos: nunca entre ciclos normais, nunca por um
# ciclo isolado que falha, e sempre depois de o heartbeat já dizer `stale`.
SPARK_MAINTENANCE_ABSENCE_ALERT_SECONDS="${SPARK_MAINTENANCE_ABSENCE_ALERT_SECONDS:-2700}"
# T18.3 §9: o backup de DR tem o próprio agendamento — uma vez por dia, de madrugada (UTC), nunca
# acoplado ao ciclo da manutenção (e a cadência da manutenção nunca mexe nele). O Scheduler dispara
# o Cloud Run Job pela API de administração (`jobs.run`), com token OAuth da mesma Service Account
# do Scheduler.
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
# Recursos do Job de backup: o dump nasce em tmpfs (conta como memória) e é carregado inteiro para
# o upload — ou seja, um dump de N bytes ocupa 2 × N ao mesmo tempo. É esta memória que define o
# teto `SPARK_DR_MAX_DUMP_BYTES` (256 MiB por default, em `backend/src/config/dr-job-config.ts`):
# 2 × 256 MiB mais o runtime do Node cabem em 1 GiB. **Subir um exige subir o outro** — e o teto
# continua ordens de grandeza acima do dump real, já que o limiar mais grave de tamanho do banco é
# 450 MB e o formato custom é comprimido. Timeout em segundos.
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

# Os dois tetos de tempo que o Cloud Run torna visíveis, declarados na revision em vez de herdados
# do default do processo (T18.3.2). Iguais aos defaults de `env.schema.ts` de propósito: o valor é
# um só, e declará-lo aqui é o que permite ler de uma revision, sem entrar no container, com que
# janela ela sobe.
#
# `DATABASE_CONNECTION_TIMEOUT_MS`: 15 s absorve o cold start do Neon com `min-instances=0`.
# `SHUTDOWN_TIMEOUT_MS`: 8 s, ABAIXO dos 10 s entre SIGTERM e SIGKILL do Cloud Run — com os dois
# iguais, o log de shutdown forçado disputava o instante do SIGKILL e nunca era escrito.
SPARK_DATABASE_CONNECTION_TIMEOUT_MS="${SPARK_DATABASE_CONNECTION_TIMEOUT_MS:-15000}"
SPARK_SHUTDOWN_TIMEOUT_MS="${SPARK_SHUTDOWN_TIMEOUT_MS:-8000}"

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

# ---------------------------------------------------------------- cadência do maintenance (T19.H6)

# O intervalo, em minutos, entre duas execuções de um cron — só para as formas de cadência
# uniforme: `* * * * *` (1), `*/N * * * *` com N divisor de 60 até 30, e `M * * * *` (60). Qualquer
# outra forma é recusada: a coerência com o stale e com o alerta não pode ser presumida de um cron
# cujo intervalo ninguém calculou.
cron_interval_minutes() {
  local cron="$1" minute hour dom month dow extra step
  read -r minute hour dom month dow extra <<< "${cron}"
  if [ -z "${dow}" ] || [ -n "${extra}" ] || [ "${hour}" != "*" ] || [ "${dom}" != "*" ] \
      || [ "${month}" != "*" ] || [ "${dow}" != "*" ]; then
    fail "cron '${cron}' fora das formas suportadas: '* * * * *', '*/N * * * *' ou 'M * * * *'"
  fi
  case "${minute}" in
    '*') printf '1' ;;
    '*/'*)
      step="${minute#\*/}"
      case "${step}" in
        ''|*[!0-9]*) fail "cron '${cron}': passo inválido" ;;
      esac
      step=$((10#${step}))
      if [ "${step}" -lt 1 ] || [ "${step}" -gt 30 ] || [ $((60 % step)) -ne 0 ]; then
        fail "cron '${cron}': o passo precisa dividir 60 (1–30), senão a cadência não é uniforme"
      fi
      printf '%s' "${step}"
      ;;
    *)
      case "${minute}" in
        ''|*[!0-9]*) fail "cron '${cron}' fora das formas suportadas: '* * * * *', '*/N * * * *' ou 'M * * * *'" ;;
      esac
      [ $((10#${minute})) -le 59 ] || fail "cron '${cron}': minuto inválido"
      printf '60'
      ;;
  esac
}

# A cadência do Scheduler, o stale do heartbeat e a janela do alerta de ausência representam a MESMA
# expectativa operacional (T19.H6 §14). Mudar só o cron é o patch que deixa o heartbeat `stale`
# entre ciclos normais — ou o alerta disparando entre eles. Recusa, antes de tocar em qualquer
# recurso, o conjunto que não for coerente:
#   stale   ≥ 2 × intervalo   um único ciclo perdido nunca vira `stale`;
#   ausência ≥ stale          o alerta nunca chega antes de o heartbeat dizer que parou.
# Os limites de cada número são os que o backend (MAINTENANCE_STALE_AFTER_MS) e o Cloud Monitoring
# (ausência ≥ 120 s) aceitariam — um valor recusado lá seria uma revision que não sobe.
require_coherent_maintenance_timing() {
  local interval_min stale_ms absence_s
  # `|| exit 1` explícito: o `fail` do cron roda num subshell, e o `set -e` não vale quando quem
  # chama está numa lista `&&`/`||` — a recusa não pode depender de como a função foi chamada.
  interval_min="$(cron_interval_minutes "${SPARK_SCHEDULER_CRON}")" || exit 1
  stale_ms="${SPARK_MAINTENANCE_STALE_AFTER_MS}"
  absence_s="${SPARK_MAINTENANCE_ABSENCE_ALERT_SECONDS}"
  case "${stale_ms}" in
    ''|*[!0-9]*) fail "SPARK_MAINTENANCE_STALE_AFTER_MS inválido: '${stale_ms}' (inteiro, em ms)" ;;
  esac
  case "${absence_s}" in
    ''|*[!0-9]*) fail "SPARK_MAINTENANCE_ABSENCE_ALERT_SECONDS inválido: '${absence_s}' (inteiro, em s)" ;;
  esac
  stale_ms=$((10#${stale_ms}))
  absence_s=$((10#${absence_s}))
  if [ "${stale_ms}" -lt 10000 ] || [ "${stale_ms}" -gt 86400000 ]; then
    fail "SPARK_MAINTENANCE_STALE_AFTER_MS=${stale_ms} fora do intervalo aceito pelo backend (10000–86400000 ms)"
  fi
  [ "${absence_s}" -ge 120 ] \
    || fail "SPARK_MAINTENANCE_ABSENCE_ALERT_SECONDS=${absence_s} abaixo do mínimo do Cloud Monitoring (120 s)"
  [ "${stale_ms}" -ge $((2 * interval_min * 60000)) ] \
    || fail "SPARK_MAINTENANCE_STALE_AFTER_MS=${stale_ms} é menor que 2 × o intervalo do Scheduler (${SPARK_SCHEDULER_CRON} = ${interval_min} min): um único ciclo perdido viraria stale"
  [ $((absence_s * 1000)) -ge "${stale_ms}" ] \
    || fail "SPARK_MAINTENANCE_ABSENCE_ALERT_SECONDS=${absence_s} é menor que o stale do heartbeat (${stale_ms} ms): o alerta dispararia antes de o maintenance estar parado"
  log "cadência do maintenance: '${SPARK_SCHEDULER_CRON}' (${interval_min} min) · stale ${stale_ms} ms · alerta de ausência ${absence_s} s"
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
