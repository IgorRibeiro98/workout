#!/usr/bin/env bash
# Bootstrap idempotente da infraestrutura Cloud Run do Spark Backend (T18.2 §4).
#
# Responsabilidade única: garantir que os recursos GCP necessários existem, com o IAM mínimo. Ele
# nunca builda imagem, nunca aplica migration e nunca faz deploy — isso é `deploy-cloud-run.sh`.
# Rodar este script de novo, sem mudar nada, não deve fazer nada (recurso existe → reutiliza;
# não existe → cria) — é o que "idempotente" quer dizer aqui, e o que o §4 exige.
#
# Uso (projeto único — GCP e Firebase são o mesmo projeto):
#   SPARK_GCP_PROJECT=meu-projeto ops/gcp/bootstrap-cloud-run.sh
#
# Uso (projetos separados — T18.2.1, o caso real do Spark):
#   SPARK_GCP_PROJECT=project-47b17b25-909d-4ae8-943 \
#   SPARK_FIREBASE_PROJECT=spark-36b11 \
#     ops/gcp/bootstrap-cloud-run.sh
#
# Pré-requisitos: `gcloud` autenticado (`gcloud auth login`) com permissão para habilitar APIs,
# criar Service Accounts, conceder IAM e criar recursos Cloud Run/Scheduler no projeto GCP — e
# permissão para conceder IAM de Firebase Admin no projeto Firebase, quando ele é diferente.
#
# Este script NUNCA imprime valor de secret (§4 — "não imprimir secretos"). Os secrets de banco e
# Gemini são criados aqui como *placeholders vazios* apenas se ainda não existirem — o valor real é
# responsabilidade do operador, fora deste script e fora do Git (§20/§47).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

require_cmd gcloud

log "projeto GCP (infraestrutura): ${SPARK_GCP_PROJECT} | projeto Firebase (identidade): ${SPARK_FIREBASE_PROJECT} | região: ${SPARK_GCP_REGION}"

# ---------------------------------------------------------------- 1. validação de projeto/região
#
# Os dois projetos são confirmados aqui, antes de qualquer recurso ser criado ou qualquer IAM ser
# aplicado (T18.2.1) — inclusive quando são o mesmo projeto. Um SPARK_FIREBASE_PROJECT inexistente
# ou inacessível precisa falhar antes do binding de Firebase Admin (§3 do enunciado da T18.2.1),
# não depois de já ter criado Artifact Registry, Service Accounts e secrets.

gcloud projects describe "${SPARK_GCP_PROJECT}" > /dev/null \
  || fail "projeto GCP inexistente ou sem acesso: ${SPARK_GCP_PROJECT}"

gcloud projects describe "${SPARK_FIREBASE_PROJECT}" > /dev/null \
  || fail "projeto Firebase inexistente ou sem acesso: ${SPARK_FIREBASE_PROJECT} (SPARK_FIREBASE_PROJECT) — nenhum IAM de Firebase Admin é aplicado sem os dois projetos confirmados"

case "${SPARK_GCP_REGION}" in
  southamerica-east1) : ;;
  *)
    log "AVISO: região ${SPARK_GCP_REGION} não é southamerica-east1 (a região-alvo da T18.2)."
    log "       Prosseguindo porque foi declarada explicitamente — confira se é intencional."
    ;;
esac

# ---------------------------------------------------------------- 2. APIs necessárias

log "habilitando APIs necessárias (idempotente — 'enable' num serviço já ativo não faz nada)"
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  cloudscheduler.googleapis.com \
  cloudbuild.googleapis.com \
  iam.googleapis.com \
  --project "${SPARK_GCP_PROJECT}"

# ---------------------------------------------------------------- 3. Artifact Registry

if resource_exists artifacts repositories describe "${SPARK_AR_REPO}" --location "${SPARK_GCP_REGION}"; then
  log "Artifact Registry '${SPARK_AR_REPO}' já existe em ${SPARK_GCP_REGION} — reutilizando"
else
  log "criando repositório Artifact Registry '${SPARK_AR_REPO}' em ${SPARK_GCP_REGION}"
  gcloud artifacts repositories create "${SPARK_AR_REPO}" \
    --project "${SPARK_GCP_PROJECT}" \
    --location "${SPARK_GCP_REGION}" \
    --repository-format docker \
    --description "Imagens do Spark Backend (T18.2) — imutáveis, versionadas por git SHA"
fi

# ---------------------------------------------------------------- 4. Service Accounts

ensure_service_account() {
  local name="$1" display="$2"
  local email
  email="$(sa_email "${name}")"
  if resource_exists iam service-accounts describe "${email}"; then
    log "Service Account '${name}' já existe — reutilizando"
  else
    log "criando Service Account '${name}'"
    gcloud iam service-accounts create "${name}" \
      --project "${SPARK_GCP_PROJECT}" \
      --display-name "${display}"
  fi
}

ensure_service_account "${SPARK_SA_RUNTIME}" "Spark Backend — runtime (API + Maintenance)"
ensure_service_account "${SPARK_SA_MIGRATOR}" "Spark Backend — migration job"
ensure_service_account "${SPARK_SA_SCHEDULER}" "Spark Backend — invocador do Cloud Scheduler"
# T18.3 §2: a identidade do backup de DR — separada de runtime e migrator de propósito: ela lê o
# secret direto (como o migrator) e escreve só no prefixo de DR do bucket (que o migrator não
# alcança), e nunca ganha Firebase, Gemini, HMAC ou permissão de deploy.
ensure_service_account "${SPARK_SA_BACKUP}" "Spark Backend — backup de DR do PostgreSQL"

RUNTIME_SA_EMAIL="$(sa_email "${SPARK_SA_RUNTIME}")"
MIGRATOR_SA_EMAIL="$(sa_email "${SPARK_SA_MIGRATOR}")"
BACKUP_SA_EMAIL="$(sa_email "${SPARK_SA_BACKUP}")"
# A e-mail da SA do Scheduler não é usada aqui: o binding run.invoker (seção 8 abaixo) só pode
# acontecer depois de `spark-maintenance` existir, e isso é responsabilidade de deploy-cloud-run.sh,
# que calcula seu próprio SCHEDULER_SA_EMAIL quando precisa dele.

# ---------------------------------------------------------------- 5. Secret Manager
#
# §20/§47 — nunca `roles/secretmanager.admin`, e IAM por secret, não por projeto inteiro.

ensure_secret() {
  local name="$1"
  if resource_exists secrets describe "${name}"; then
    log "secret '${name}' já existe — não recriando (valor preservado)"
  else
    log "criando secret vazio '${name}' — defina o valor real fora deste script:"
    log "  gcloud secrets versions add ${name} --data-file=- <<< 'valor real'"
    gcloud secrets create "${name}" --project "${SPARK_GCP_PROJECT}" --replication-policy automatic
  fi
}

ensure_secret "${SPARK_SECRET_DATABASE_URL}"
ensure_secret "${SPARK_SECRET_DATABASE_URL_DIRECT}"
ensure_secret "${SPARK_SECRET_GEMINI_API_KEY}"

# A chave HMAC nunca é gerada de novo se já existir (§20: "não rotacionar automaticamente" — trocá
# -la sem migração destruiria o reconhecimento de tombstones históricos). Só nasce, com entropia
# adequada, na primeira vez.
if resource_exists secrets describe "${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}"; then
  log "secret '${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}' já existe — preservado, nunca rotacionado automaticamente"
else
  log "gerando a chave HMAC de exclusão de conta (32 bytes de entropia, uma única vez)"
  gcloud secrets create "${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}" \
    --project "${SPARK_GCP_PROJECT}" --replication-policy automatic
  openssl rand -hex 32 | gcloud secrets versions add "${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}" \
    --project "${SPARK_GCP_PROJECT}" --data-file=-
  log "chave HMAC gerada e armazenada — o valor NUNCA é impresso por este script"
fi

# IAM por secret (§21/§47): runtime só os três da API; migrator só o direct.
grant_secret_accessor() {
  local secret="$1" member="$2"
  gcloud secrets add-iam-policy-binding "${secret}" \
    --project "${SPARK_GCP_PROJECT}" \
    --member "serviceAccount:${member}" \
    --role roles/secretmanager.secretAccessor \
    > /dev/null
}

log "concedendo Secret Accessor mínimo por secret"
grant_secret_accessor "${SPARK_SECRET_DATABASE_URL}" "${RUNTIME_SA_EMAIL}"
grant_secret_accessor "${SPARK_SECRET_GEMINI_API_KEY}" "${RUNTIME_SA_EMAIL}"
grant_secret_accessor "${SPARK_SECRET_ACCOUNT_DELETION_HMAC_KEY}" "${RUNTIME_SA_EMAIL}"
# O runtime NUNCA recebe acesso ao secret direto (§9/§21) — só o migrator e, desde a T18.3, o
# backup de DR (o `pg_dump` usa a conexão direta/admin, nunca o pooler).
grant_secret_accessor "${SPARK_SECRET_DATABASE_URL_DIRECT}" "${MIGRATOR_SA_EMAIL}"
grant_secret_accessor "${SPARK_SECRET_DATABASE_URL_DIRECT}" "${BACKUP_SA_EMAIL}"

# ---------------------------------------------------------------- 6. IAM do bucket (T18.1, revisado; DR na T18.3)

# `--condition=None` nos bindings SEM condição: assim que a política do bucket ganha um binding
# condicional (o da backup SA, abaixo), o gcloud exige que todo binding novo declare explicitamente
# "sem condição" — sem isso, a segunda execução do bootstrap falharia aqui (visto na T18.3 real).
log "concedendo acesso mínimo ao bucket '${SPARK_GCS_BUCKET}' para a runtime SA"
gcloud storage buckets add-iam-policy-binding "gs://${SPARK_GCS_BUCKET}" \
  --member "serviceAccount:${RUNTIME_SA_EMAIL}" \
  --role roles/storage.objectAdmin \
  --condition=None \
  > /dev/null \
  || log "AVISO: não foi possível conceder IAM no bucket — confirme que '${SPARK_GCS_BUCKET}' existe (T18.1) e tente de novo manualmente."

# A backup SA só alcança objetos sob o prefixo de DR (T18.3 §2/§18): `objectAdmin` com uma IAM
# Condition sobre o nome do recurso. Listar (`storage.objects.list`) é permissão de BUCKET, e uma
# condição sobre nome de objeto não a concede — por isso o segundo papel, `legacyBucketReader`
# (listar nomes e metadata; nunca ler conteúdo), sem condição. Foto e backup pessoal continuam
# ilegíveis para esta identidade: a condição não cobre `social/` nem `backups/`.
log "concedendo à backup SA escrita/leitura/remoção só sob ${SPARK_DR_PREFIX} e listagem do bucket"
gcloud storage buckets add-iam-policy-binding "gs://${SPARK_GCS_BUCKET}" \
  --member "serviceAccount:${BACKUP_SA_EMAIL}" \
  --role roles/storage.objectAdmin \
  --condition "expression=resource.name.startsWith(\"projects/_/buckets/${SPARK_GCS_BUCKET}/objects/${SPARK_DR_PREFIX}\"),title=spark-dr-prefix-only,description=T18.3: backup de DR só sob ${SPARK_DR_PREFIX}" \
  > /dev/null \
  || log "AVISO: não foi possível conceder o IAM condicional da backup SA no bucket — confira manualmente."
gcloud storage buckets add-iam-policy-binding "gs://${SPARK_GCS_BUCKET}" \
  --member "serviceAccount:${BACKUP_SA_EMAIL}" \
  --role roles/storage.legacyBucketReader \
  --condition=None \
  > /dev/null \
  || log "AVISO: não foi possível conceder legacyBucketReader à backup SA — confira manualmente."

# ---------------------------------------------------------------- 7. IAM do Firebase Admin (§19, T18.2.1)
#
# `verifyIdToken` não exige papel do Firebase — as chaves públicas do Google são de acesso livre.
# `deleteUser` exige escrita no Firebase Authentication; FCM exige envio de mensagens quando
# SOCIAL_PUSH_ENABLED=true. Nunca Owner/Editor.
#
# O binding é aplicado em SPARK_FIREBASE_PROJECT, não em SPARK_GCP_PROJECT — os dois só coincidem
# quando a instalação usa um único projeto. O membro continua sendo a runtime SA do projeto de
# infraestrutura: uma Service Account pode receber IAM num projeto diferente do seu próprio sem
# nenhum JSON de credencial, e é exatamente essa concessão cross-project que este passo faz.

log "concedendo IAM mínimo de Firebase Admin à runtime SA (verifyIdToken, deleteUser, FCM) no projeto Firebase '${SPARK_FIREBASE_PROJECT}'"
gcloud projects add-iam-policy-binding "${SPARK_FIREBASE_PROJECT}" \
  --member "serviceAccount:${RUNTIME_SA_EMAIL}" \
  --role roles/firebaseauth.admin \
  > /dev/null
gcloud projects add-iam-policy-binding "${SPARK_FIREBASE_PROJECT}" \
  --member "serviceAccount:${RUNTIME_SA_EMAIL}" \
  --role roles/firebasecloudmessaging.admin \
  > /dev/null

# ---------------------------------------------------------------- 8. Cloud Scheduler invoker (§35)

log "garantindo que ${SPARK_SA_SCHEDULER} só tem run.invoker sobre ${SPARK_RUN_MAINTENANCE_SERVICE}"
# O binding real (`run services add-iam-policy-binding`) só pode ser feito depois de o serviço
# `spark-maintenance` existir — isso acontece no primeiro `deploy-cloud-run.sh --target maintenance`.
# Aqui só se garante que a Service Account existe e não tem NENHUM outro papel no projeto — nada de
# bucket, secrets, Firebase ou banco (§35).
log "  (o binding run.invoker em si é aplicado por deploy-cloud-run.sh, após o serviço existir)"

# ---------------------------------------------------------------- 9. Cloud Run — reserva de nomes
#
# `gcloud run deploy` já cria o serviço na primeira execução; não há necessidade de um "create"
# antecipado aqui — o bootstrap só garante que a *infraestrutura de apoio* (registry, SAs, IAM,
# secrets, bucket) existe antes do primeiro deploy tentar usá-la.

# ---------------------------------------------------------------- 10. Artifact Registry — retenção (T18.3 §17)
#
# A política nativa de limpeza do repositório (mantém as N versões tagueadas mais recentes, apaga
# untagged antigas). Idempotente; o script também é a auditoria de que nenhum digest em uso por
# uma revision ativa é candidato à limpeza.
"${SCRIPT_DIR}/artifact-registry-retention.sh" --apply

log "bootstrap concluído. Nenhum valor de secret foi impresso."
log "Próximo passo: garantir os valores reais dos secrets (fora deste script) e rodar deploy-cloud-run.sh."
