#!/usr/bin/env bash
# Helpers de DR do PostgreSQL sobre o bucket, pela CLI do gcloud (T18.3 §9/§10).
#
# Lido com `source` por `deploy-cloud-run.sh` (gate pré-migration), `dr-status.sh` e
# `dr-backup-now.sh`, depois de `lib.gcp.sh`. Lê o namespace `system/dr/postgres/` exatamente como
# o backend o escreve (`backend/src/dr/dr-manifest.ts`): uma pasta por `backupId`, `database.dump`
# + `manifest.json`, e "válido" significa manifesto que faz parse + dump presente + tamanho
# batendo. É o mesmo critério de `DrBackupStore.statusOf` — dois leitores, um critério.
#
# Nenhuma função aqui escreve no bucket. Nenhuma imprime segredo: o manifesto não contém nenhum.

set -euo pipefail

dr_gs_prefix() { printf 'gs://%s/%s' "${SPARK_GCS_BUCKET}" "${SPARK_DR_PREFIX}"; }

# Os `backupId` presentes no bucket, do MAIS NOVO ao mais antigo (o id é um timestamp ordenável).
dr_backup_ids() {
  gcloud storage ls "$(dr_gs_prefix)" --project "${SPARK_GCP_PROJECT}" 2> /dev/null \
    | sed -n 's#^gs://[^/]*/'"${SPARK_DR_PREFIX}"'\([0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}T[0-9]\{6\}Z\)/$#\1#p' \
    | sort -r
}

dr_manifest_json() {
  local backup_id="$1"
  gcloud storage cat "$(dr_gs_prefix)${backup_id}/manifest.json" --project "${SPARK_GCP_PROJECT}" 2> /dev/null
}

dr_dump_size() {
  local backup_id="$1"
  gcloud storage objects describe "$(dr_gs_prefix)${backup_id}/database.dump" \
    --project "${SPARK_GCP_PROJECT}" --format='value(size)' 2> /dev/null
}

# Um backup é válido? Em caso positivo imprime `<createdAtEpochMs>` em stdout e devolve 0.
#
# Falha fechada: manifesto ausente/ilegível, `backupId` divergente, `sha256` fora do formato, dump
# ausente ou com tamanho diferente do declarado → não é válido. Nada disso é "provavelmente ok".
dr_backup_is_valid() {
  local backup_id="$1" manifest created declared_size actual_size manifest_id sha
  manifest="$(dr_manifest_json "${backup_id}")" || return 1
  [ -n "${manifest}" ] || return 1
  manifest_id="$(printf '%s' "${manifest}" | jq -r '.backupId // empty' 2> /dev/null)" || return 1
  [ "${manifest_id}" = "${backup_id}" ] || return 1
  sha="$(printf '%s' "${manifest}" | jq -r '.sha256 // empty' 2> /dev/null)" || return 1
  case "${sha}" in
    [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) [ "${#sha}" -eq 64 ] || return 1 ;;
    *) return 1 ;;
  esac
  declared_size="$(printf '%s' "${manifest}" | jq -r '.dumpSizeBytes // empty' 2> /dev/null)" || return 1
  created="$(printf '%s' "${manifest}" | jq -r '.createdAtEpochMs // empty' 2> /dev/null)" || return 1
  case "${declared_size}${created}" in ''|*[!0-9]*) return 1 ;; esac
  actual_size="$(dr_dump_size "${backup_id}")" || return 1
  [ -n "${actual_size}" ] || return 1
  [ "${actual_size}" = "${declared_size}" ] || return 1
  printf '%s' "${created}"
}

# O backup válido mais recente: imprime `<backupId> <createdAtEpochMs>`; devolve 1 se não há nenhum.
dr_latest_valid_backup() {
  local backup_id created
  while IFS= read -r backup_id; do
    [ -n "${backup_id}" ] || continue
    if created="$(dr_backup_is_valid "${backup_id}")"; then
      printf '%s %s' "${backup_id}" "${created}"
      return 0
    fi
  done < <(dr_backup_ids)
  return 1
}

# Idade, em segundos, de um instante em epoch millis.
dr_age_seconds() {
  local created_ms="$1" now_s
  now_s="$(date -u +%s)"
  printf '%s' "$(( now_s - created_ms / 1000 ))"
}

# Executa o Job de backup e espera o desfecho. Falha se o Job falhar — nunca "disparado".
dr_run_backup_job_and_wait() {
  gcloud run jobs execute "${SPARK_RUN_BACKUP_JOB}" \
    --project "${SPARK_GCP_PROJECT}" \
    --region "${SPARK_GCP_REGION}" \
    --wait
}
