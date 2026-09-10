#!/usr/bin/env bash
#
# Backup off-site do servidor (T16.8 §26–§42; T16.8.1 §9; PostgreSQL desde a T18.0.2).
#
#   PostgreSQL ──▶ pg_dump (snapshot consistente) ──▶ pg_restore --list ──▶ manifesto ──┐
#   deletion_tombstones.tsv (ledger anti-ressurreição, T17.13.1) ─────────────────────────┼─▶ restic ──▶ off-site
#   /opt/spark/media (fotos dos check-ins, T17.9) ─────────────────────────────────────────┘
#
# ## O que este backup NÃO é
#
# Ele não é o backup da T16.4. Aquele protege o **usuário** contra perder o aparelho: o dado dele
# passa a existir no servidor. Este protege a **infraestrutura** contra perder a VPS: o banco do
# servidor — que contém os backups de todo mundo, o estado de sync, o change log e os tombstones —
# passa a existir fora dela. Confundir os dois dá uma falsa sensação de segurança nos dois sentidos.
#
# ## A mídia entra no mesmo snapshot (T17.9 §135/§136/§137)
#
# Desde a T17.9, o Feed tem fotos, e elas **não** estão no banco (§21): o PostgreSQL guarda
# metadata, e os bytes vivem em `$SPARK_MEDIA_DIR`. Um backup que levasse só o dump restauraria um
# Feed que aponta para arquivos que não existem — íntegro na restauração e quebrado na tela.
#
# O diretório de mídia entra no **mesmo** `restic backup`, como um segundo caminho. Não é uma cópia
# extra em disco (§137): o restic lê o diretório de origem direto, deduplica entre snapshots — as
# fotos são imutáveis depois de escritas, então o segundo backup diário não reenvia nenhuma — e
# criptografa antes de sair da VPS, como já fazia com o banco (§136).
#
# ## Off-site é obrigatório
#
# Uma cópia em `/opt/spark/backups` na mesma VPS não é backup: o disco que morre leva os dois. O
# destino é `RESTIC_REPOSITORY`, e o restic o criptografa **antes** de enviar — o provedor de
# armazenamento nunca vê dado pessoal em claro (§147).
#
# ## Todo desfecho é registrado (T16.8.1 §9)
#
# `backup-status.json` é o que `ops/check-health.sh` lê para responder "o último backup funcionou?".
# Um backup que falha **precisa** deixar `outcome=FAILURE` lá; um que falha em silêncio é pior que
# não ter backup, porque dá confiança. Isso não pode depender de qual caminho de erro foi tomado —
# daí o `trap finalize EXIT`, que roda tanto no `set -e` quanto no `exit` explícito de `fail`.
#
# Uso:  ops/backup.sh [--tag <rótulo>]
#       ops/backup.sh --tag pre-deploy      (usado por ops/deploy.sh antes de migration)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file

TAG="scheduled"
while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="${2:?--tag exige um valor}"; shift 2 ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

# Política de retenção (§37). Números pequenos e deliberados: o banco do Spark é de um grupo
# pequeno, e retenção infinita é custo sem benefício. A janela combinada — uma semana de diários,
# um mês de semanais, um ano de mensais — cobre tanto o erro percebido no dia seguinte quanto o
# percebido meses depois.
KEEP_DAILY="${SPARK_BACKUP_KEEP_DAILY:-7}"
KEEP_WEEKLY="${SPARK_BACKUP_KEEP_WEEKLY:-4}"
KEEP_MONTHLY="${SPARK_BACKUP_KEEP_MONTHLY:-12}"

STATUS_FILE="${SPARK_STATE_DIR}/backup-status.json"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
STARTED_EPOCH="$(date -u +%s)"

# A etapa em curso, de um **vocabulário fechado** (§9: "mensagem técnica segura").
#
# É deliberadamente um rótulo, e não a saída do comando que falhou: a saída do restic pode conter o
# endereço do repositório, um cabeçalho de autorização ou parte de uma credencial de storage, e o
# arquivo de estado é lido por quem estiver diagnosticando. O detalhe fica em stderr, que vai para o
# journal do systemd; o estado carrega apenas onde parou.
STAGE="config"
SUCCEEDED=0
SNAPSHOT_ID=""
SIZE_BYTES=0
WORK_DIR=""
RESTIC_OUTPUT_FILE=""

mkdir -p "$SPARK_STATE_DIR" "$SPARK_STAGING_DIR"
chmod 700 "$SPARK_STATE_DIR" "$SPARK_STAGING_DIR" 2> /dev/null || true

# Estado legível por máquina (§39/§137): início, desfecho, duração, tamanho e identificador do
# snapshot. **Nunca conteúdo** — nem nome de conta, nem uid, nem nada do banco.
write_status() {
  local outcome="$1" snapshot_id="${2:-}" size_bytes="${3:-0}" message="${4:-}"
  local finished_epoch; finished_epoch="$(date -u +%s)"
  local tmp="${STATUS_FILE}.tmp"
  cat > "$tmp" <<JSON
{
  "startedAt": "${STARTED_AT}",
  "finishedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "durationSeconds": $((finished_epoch - STARTED_EPOCH)),
  "outcome": "${outcome}",
  "tag": "${TAG}",
  "snapshotId": "${snapshot_id}",
  "sizeBytes": ${size_bytes},
  "message": "${message}",
  "lastSuccessfulBackupAt": "${LAST_SUCCESS_AT:-}",
  "lastSuccessfulBackupEpoch": ${LAST_SUCCESS_EPOCH:-0}
}
JSON
  mv "$tmp" "$STATUS_FILE"
  chmod 600 "$STATUS_FILE"
}

# Um sucesso anterior não pode ser apagado por uma falha de hoje: `ops/check-health.sh` mede a
# **idade do último sucesso**, e zerá-la faria uma falha parecer "nunca houve backup".
LAST_SUCCESS_AT=""
LAST_SUCCESS_EPOCH=0
if [ -f "$STATUS_FILE" ]; then
  LAST_SUCCESS_AT="$(sed -n 's/.*"lastSuccessfulBackupAt": "\([^"]*\)".*/\1/p' "$STATUS_FILE" | head -1)"
  LAST_SUCCESS_EPOCH="$(sed -n 's/.*"lastSuccessfulBackupEpoch": \([0-9]*\).*/\1/p' "$STATUS_FILE" | head -1)"
  LAST_SUCCESS_EPOCH="${LAST_SUCCESS_EPOCH:-0}"
fi

# O único ponto de saída do script (T16.8.1 §9).
#
# Um `trap ... ERR` não bastava, e foi exatamente o defeito auditado: `fail` faz `exit 1`, e `exit`
# **não** dispara o trap de ERR. Todo caminho que passava por `fail` — credencial ausente, restic
# recusado, `snapshot_id` vazio — saía com código diferente de zero e deixava
# `backup-status.json` com o desfecho anterior. Um backup quebrado registrado como sucesso é o pior
# resultado possível deste script.
#
# `EXIT` cobre os três caminhos de uma vez: `set -e`, `exit` explícito e término normal.
finalize() {
  local code=$?

  rm -rf "${WORK_DIR:-}" 2> /dev/null || true
  rm -f "${RESTIC_OUTPUT_FILE:-}" 2> /dev/null || true

  if [ "$SUCCEEDED" -eq 1 ] && [ "$code" -eq 0 ]; then
    write_status "SUCCESS" "$SNAPSHOT_ID" "$SIZE_BYTES" ""
  else
    # `lastSuccessfulBackupAt`/`Epoch` são reemitidos **sem alteração**: a falha de hoje não pode
    # apagar o sucesso de ontem, porque é a idade dele que diz o tamanho real do risco.
    write_status "FAILURE" "" 0 "falha na etapa '${STAGE}' (código ${code})"
    printf 'ERRO: o backup do Spark FALHOU na etapa %s (tag=%s). Estado em %s\n' \
      "$STAGE" "$TAG" "$STATUS_FILE" >&2
  fi

  exit "$code"
}

# --- 0. exclusão mútua -----------------------------------------------------------------------
#
# Antes do trap, e de propósito: um segundo backup que encontra o lock ocupado **não começou**, e
# sobrescrever o estado do que está rodando faria a execução em andamento parecer falha.
acquire_lock

trap finalize EXIT

require_cmd docker
require_restic_env
# A connection string precisa existir antes de qualquer trabalho: sem ela não há o que copiar, e
# descobrir isso na etapa `snapshot` diria ao operador que o dump falhou, não que falta configuração.
require_database_url

STAGE="workdir"
WORK_DIR="$(mktemp -d "${SPARK_STAGING_DIR}/work-XXXXXX")"
chmod 700 "$WORK_DIR"

# --- 1. snapshot consistente + verificação do arquivo ---------------------------------------
STAGE="snapshot"
SNAPSHOT="${WORK_DIR}/${DUMP_FILENAME}"
"${SCRIPT_DIR}/snapshot.sh" "$SNAPSHOT" > /dev/null
SIZE_BYTES="$(stat -c %s "$SNAPSHOT")"

# O ledger anti-ressurreição entra no mesmo snapshot (T17.13.1 §8/§14).
#
# Sem ele, uma perda total da VPS deixaria o operador com o banco e a mídia de volta e **nenhuma**
# memória de quais contas já tinham sido excluídas — e a reconciliação pós-restore, que é o que
# impede a ressurreição, não teria contra o que reconciliar. Como ela falha fechada quando o ledger
# não existe (§17), a ausência dele no backup transformaria toda recuperação de desastre em um
# bloqueio permanente. Um arquivo de texto de alguns kilobytes é o preço de não ter esse problema.
#
# `cp` para dentro de `$WORK_DIR`, e não um caminho a mais no `restic backup`: o ledger é lido
# continuamente pelo backend e copiá-lo primeiro dá um arquivo estável ao restic. Ele é
# append-only, então a cópia pode perder uma exclusão feita durante o próprio backup — que a
# execução seguinte captura, e que o tombstone do banco cobre nesse intervalo.
#
# Desde a T18.0 ele é o **único** arquivo em `$SPARK_DATA_DIR`: o banco saiu de lá para o
# PostgreSQL, e o ledger ficou, porque é justamente o registro que precisa sobreviver à
# substituição do conteúdo do banco.
TOMBSTONES_ROWS=0
if [ -f "$SPARK_TOMBSTONES_FILE" ]; then
  cp "$SPARK_TOMBSTONES_FILE" "${WORK_DIR}/${TOMBSTONES_FILENAME}"
  TOMBSTONES_ROWS="$( wc -l < "${WORK_DIR}/${TOMBSTONES_FILENAME}" | tr -d ' ' )"
  log "ledger de exclusões incluído: ${TOMBSTONES_ROWS} registro(s)"
else
  # Um servidor onde ninguém excluiu conta ainda não tem o arquivo, e isso não é erro. O restore
  # distingue os dois casos pelo manifesto.
  log "aviso: ${SPARK_TOMBSTONES_FILE} não existe; nenhuma exclusão de conta foi registrada ainda"
fi

# --- 2. manifesto -------------------------------------------------------------------------
#
# O que acompanha o banco (§34): versão do schema, imagem que estava no ar, tamanho e horário. É
# o que permite, meses depois, saber com qual versão do backend aquele arquivo foi escrito.
#
# O que **não** acompanha: nenhum segredo (§35). Service account, chave do Gemini e senha do
# repositório não entram aqui nem no snapshot — eles vivem fora do banco por construção.
STAGE="manifest"
SCHEMA_VERSION="$(pg_schema_version "$(spark_database_url)")"

# Contagem e tamanho da mídia, para o manifesto (§34). Números, nunca nomes de arquivo (§161).
MEDIA_FILES=0
MEDIA_BYTES=0
if [ -d "$SPARK_MEDIA_DIR" ]; then
  MEDIA_FILES="$( find "$SPARK_MEDIA_DIR" -type f | wc -l | tr -d ' ' )"
  MEDIA_BYTES="$( du -sb "$SPARK_MEDIA_DIR" 2> /dev/null | cut -f1 )"
  MEDIA_BYTES="${MEDIA_BYTES:-0}"
fi

IMAGE_REF=""
if compose_running; then
  IMAGE_REF="$( compose_query images -q "$SPARK_SERVICE" 2> /dev/null | head -1 )" || IMAGE_REF=""
fi

cat > "${WORK_DIR}/manifest.json" <<JSON
{
  "createdAt": "${STARTED_AT}",
  "tag": "${TAG}",
  "schemaVersion": ${SCHEMA_VERSION:-0},
  "databaseEngine": "postgresql",
  "dumpFormat": "pg_dump-custom",
  "databaseBytes": ${SIZE_BYTES},
  "backendImageId": "${IMAGE_REF}",
  "mediaDir": "${SPARK_MEDIA_DIR}",
  "mediaFiles": ${MEDIA_FILES:-0},
  "mediaBytes": ${MEDIA_BYTES:-0},
  "deletionLedgerRows": ${TOMBSTONES_ROWS:-0},
  "host": "$(hostname)"
}
JSON

# --- 3. off-site criptografado ------------------------------------------------------------
STAGE="offsite-upload"

# A mídia entra como segundo caminho quando o diretório existe (T17.9 §135). Um servidor que ainda
# não recebeu foto nenhuma não tem o diretório, e isso não é erro — é um servidor sem fotos.
BACKUP_PATHS=("$WORK_DIR")
if [ -d "$SPARK_MEDIA_DIR" ]; then
  BACKUP_PATHS+=("$SPARK_MEDIA_DIR")
  log "incluindo mídia social de ${SPARK_MEDIA_DIR}"
else
  log "aviso: ${SPARK_MEDIA_DIR} não existe; o snapshot leva apenas o banco"
fi

log "enviando para o repositório off-site"
# `--host spark` deixa a política de retenção estável mesmo se a VPS for recriada com outro
# hostname: sem isso, a nova máquina começaria uma linhagem separada de snapshots e o `forget`
# manteria as duas para sempre.
# A saída do restic é capturada, e não descartada: quando ele falha, a mensagem dele é a única
# coisa que diz **por quê** (repositório inexistente, credencial errada, storage fora do ar). Um
# backup que falha sem dizer o motivo custa horas na hora errada. Ela vai para stderr — e nunca
# para `backup-status.json`, que pode conter endereço de repositório e credencial de storage.
# Fora de `$WORK_DIR`: um arquivo criado ali enquanto o restic lê o diretório entraria no próprio
# snapshot, meio escrito.
RESTIC_OUTPUT_FILE="$(mktemp)"
if ! restic_cmd backup "${BACKUP_PATHS[@]}" \
     --host spark \
     --tag "spark-db" --tag "$TAG" \
     --json > "$RESTIC_OUTPUT_FILE" 2>&1; then
  printf 'saída do restic:\n%s\n' "$(tail -30 "$RESTIC_OUTPUT_FILE")" >&2
  fail "o envio para o repositório off-site falhou"
fi

SNAPSHOT_ID="$(sed -n 's/.*"snapshot_id":"\([a-f0-9]*\)".*/\1/p' "$RESTIC_OUTPUT_FILE" | tail -1)"
[ -n "$SNAPSHOT_ID" ] || fail "restic não devolveu um snapshot_id"

# --- 4. retenção --------------------------------------------------------------------------
STAGE="offsite-retention"
log "aplicando retenção (${KEEP_DAILY}d/${KEEP_WEEKLY}s/${KEEP_MONTHLY}m)"
# `--prune` só depois de o backup novo estar confirmado: uma limpeza que rode antes pode remover
# a última cópia útil (§133). Nesta ordem, uma falha aqui deixa backup **a mais**, nunca a menos.
#
# E ela continua sendo uma falha: o snapshot subiu, mas o repositório está crescendo sem limite, e
# isso precisa aparecer em `check-health.sh` em vez de esperar o storage encher.
restic_cmd forget \
  --host spark --tag "spark-db" \
  --keep-daily "$KEEP_DAILY" \
  --keep-weekly "$KEEP_WEEKLY" \
  --keep-monthly "$KEEP_MONTHLY" \
  --prune > /dev/null

# --- 5. estado ----------------------------------------------------------------------------
STAGE="done"
LAST_SUCCESS_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
LAST_SUCCESS_EPOCH="$(date -u +%s)"
SUCCEEDED=1

log "backup concluído: snapshot=${SNAPSHOT_ID} bytes=${SIZE_BYTES} midia=${MEDIA_FILES} arquivo(s)/${MEDIA_BYTES}B tag=${TAG}"
