#!/usr/bin/env bash
# Rollback do Cloud Run: tráfego da API **e** os Jobs agendados (T18.2 §55; Jobs desde a T18.3.2).
#
# Nunca faz rebuild. Rollback é reapontar o que já existe — a imagem antiga nunca precisa ser
# reconstruída.
#
# ## Por que os Jobs entram
#
# `spark-db-backup` (backup diário de DR) e `spark-storage-audit` rodam a **mesma imagem** da API.
# Um rollback que movesse só o tráfego deixaria o backup — a última linha de defesa contra perda de
# dado — rodando exatamente o código que acabou de ser considerado ruim o bastante para sair de
# produção. Eles voltam para o digest da revision alvo, que é o digest que estava servindo quando
# aquela revision era a atual.
#
# `spark-db-migrate` fica de fora de propósito: ele só é executado pelo deploy, e o §56 continua
# valendo — rollback de aplicação não desfaz migration.
#
# Uso:
#   ops/gcp/rollback-cloud-run.sh <revision>
#   ops/gcp/rollback-cloud-run.sh --list   # lista as revisions disponíveis, com a tráfego atual

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/gcp/lib.gcp.sh
. "${SCRIPT_DIR}/lib.gcp.sh"

require_cmd gcloud

if [ "${1:-}" = "--list" ]; then
  gcloud run revisions list \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --service "${SPARK_RUN_API_SERVICE}"
  exit 0
fi

REVISION="${1:?uso: rollback-cloud-run.sh <revision> (ou --list)}"

require_api_revision "${REVISION}"

log "movendo 100% do tráfego de ${SPARK_RUN_API_SERVICE} para ${REVISION}"
gcloud run services update-traffic "${SPARK_RUN_API_SERVICE}" \
  --project "${SPARK_GCP_PROJECT}" \
  --region "${SPARK_GCP_REGION}" \
  --to-revisions "${REVISION}=100"

# O digest que aquela revision serve — a única fonte correta para os Jobs. `jobs update` altera
# **só** a imagem: Service Account, secrets, comando, memória e timeout de cada Job continuam como
# o deploy os deixou.
REVISION_IMAGE="$(gcloud run revisions describe "${REVISION}" \
  --project "${SPARK_GCP_PROJECT}" --region "${SPARK_GCP_REGION}" \
  --format='value(spec.containers[0].image)' 2> /dev/null || true)"

if [ -z "${REVISION_IMAGE}" ]; then
  # Sem o digest não há o que reapontar, e inventar um seria pior: o tráfego já voltou, e o
  # operador precisa saber que os Jobs continuam na imagem nova.
  log "AVISO: não foi possível ler a imagem de ${REVISION} — os Jobs ${SPARK_RUN_BACKUP_JOB} e ${SPARK_RUN_STORAGE_AUDIT_JOB} CONTINUAM no digest anterior ao rollback. Reaponte-os à mão."
else
  for job in "${SPARK_RUN_BACKUP_JOB}" "${SPARK_RUN_STORAGE_AUDIT_JOB}"; do
    if resource_exists run jobs describe "${job}" --region "${SPARK_GCP_REGION}"; then
      log "reapontando o Job ${job} para ${REVISION_IMAGE}"
      gcloud run jobs update "${job}" \
        --project "${SPARK_GCP_PROJECT}" \
        --region "${SPARK_GCP_REGION}" \
        --image "${REVISION_IMAGE}" \
        --quiet
    else
      log "Job ${job} não existe neste projeto — nada a reapontar"
    fi
  done
fi

log "rollback concluído. Confirme com: gcloud run services describe ${SPARK_RUN_API_SERVICE} --region ${SPARK_GCP_REGION}"
log "Lembrete (§56): rollback de aplicação não desfaz migration. O schema precisa continuar compatível com ${REVISION} — migrations são additive por decisão da T18.2 §11."
