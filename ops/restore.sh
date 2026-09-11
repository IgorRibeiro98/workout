#!/usr/bin/env bash
#
# Restauração do servidor a partir do backup off-site (T16.8 §43/§44/§130; PostgreSQL desde a
# T18.0.2).
#
#   restic (off-site) ──▶ diretório temporário ──▶ pg_restore --list ──▶ [--install] ──▶ PostgreSQL
#                                                └─▶ mídia social  ────▶ [--install] ──▶ /media
#                                                └─▶ ledger de exclusões ▶ [--install] ──▶ /data
#
# ## O banco **e** a mídia (T17.9 §138)
#
# Desde a T17.9 o Feed tem fotos, e elas vivem fora do banco. Restaurar só o banco produz um Feed
# que aponta para arquivos que não existem: a restauração passa, `/health/ready` responde, e cada
# card com foto fica sem imagem. O runbook só pode declarar o Feed recuperado quando os dois
# voltaram — e é por isso que este script extrai, verifica e (com `--install`) instala os dois.
#
# ## O que ele nunca faz
#
# Ele **não** descarta o estado atual do banco sem antes preservá-lo. `--install` tira um dump do
# banco de produção (`pre-restore-<timestamp>.dump`, em `$SPARK_STAGING_DIR`) **antes** de tocar em
# qualquer coisa: se a restauração for a errada, o estado anterior ainda está lá. "Restaurar
# destruindo o que existia sem validar" é exatamente o modo de falha que transforma um incidente
# recuperável em perda definitiva (§21/§130).
#
# Também não instala com o backend em execução: o processo tem conexões abertas e migrations que
# rodam no startup — o `pg_restore` precisa ser o único escritor.
#
# A restauração do banco é `--single-transaction --exit-on-error`: ou o dump inteiro entra, ou
# nada entra. Não existe "restaurou pela metade".
#
# Uso:
#   ops/restore.sh --to /tmp/drill                          # só extrai e verifica (padrão seguro)
#   ops/restore.sh --snapshot <id> --to /tmp/drill
#   ops/restore.sh --from-file /caminho/spark.dump --media-from /caminho/media --to /tmp/drill
#   ops/restore.sh --to /tmp/drill --install                # troca o banco e a mídia de produção

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file
require_cmd docker

SNAPSHOT="latest"
TARGET_DIR=""
FROM_FILE=""
FROM_MEDIA=""
INSTALL=0

while [ $# -gt 0 ]; do
  case "$1" in
    --snapshot)   SNAPSHOT="${2:?--snapshot exige um valor}"; shift 2 ;;
    --to)         TARGET_DIR="${2:?--to exige um caminho}"; shift 2 ;;
    --from-file)  FROM_FILE="${2:?--from-file exige um caminho}"; shift 2 ;;
    # Ensaio local com mídia (T17.9 §142): o par de `--from-file`, para exercitar a restauração
    # completa sem credencial de storage.
    --media-from) FROM_MEDIA="${2:?--media-from exige um caminho}"; shift 2 ;;
    --install)    INSTALL=1; shift ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

[ -n "$TARGET_DIR" ] || fail "--to é obrigatório (diretório de trabalho da restauração)"

mkdir -p "$TARGET_DIR"
chmod 700 "$TARGET_DIR" 2> /dev/null || true

RESTORED_DB=""
RESTORED_MEDIA=""
RESTORED_LEDGER=""

if [ -n "$FROM_FILE" ]; then
  # Caminho sem off-site: usado pelo ensaio local e pelo CI, onde não há — e não deve haver —
  # credencial de storage real (§163/§164).
  [ -f "$FROM_FILE" ] || fail "arquivo não encontrado: ${FROM_FILE}"
  RESTORED_DB="${TARGET_DIR}/${DUMP_FILENAME}"
  cp "$FROM_FILE" "$RESTORED_DB"
  log "restaurado de arquivo local: ${FROM_FILE}"
  # O ensaio local pode trazer a mídia junto, por `--media-from`.
  if [ -n "$FROM_MEDIA" ]; then
    [ -d "$FROM_MEDIA" ] || fail "diretório de mídia não encontrado: ${FROM_MEDIA}"
    RESTORED_MEDIA="${TARGET_DIR}/media"
    mkdir -p "$RESTORED_MEDIA"
    cp -a "${FROM_MEDIA}/." "$RESTORED_MEDIA/"
    log "mídia restaurada de: ${FROM_MEDIA}"
  fi
else
  require_restic_env
  log "restaurando o snapshot '${SNAPSHOT}' do repositório off-site"
  restic_cmd restore "$SNAPSHOT" --host spark --tag "spark-db" --target "$TARGET_DIR" > /dev/null
  # O restic reconstrói a árvore de caminhos original; o dump pode estar em qualquer profundidade.
  RESTORED_DB="$(find "$TARGET_DIR" -name "$DUMP_FILENAME" -type f | head -1)"
  [ -n "$RESTORED_DB" ] || fail "o snapshot não contém ${DUMP_FILENAME}"
  MANIFEST="$(find "$TARGET_DIR" -name manifest.json -type f | head -1)"
  [ -n "$MANIFEST" ] && log "manifesto: $(tr -d '\n ' < "$MANIFEST")"

  # O ledger anti-ressurreição (T17.13.1 §14). Um snapshot anterior a esta fase, ou de um servidor
  # onde ninguém nunca excluiu conta, não o contém — e isso é diferente de "o arquivo sumiu".
  RESTORED_LEDGER="$(find "$TARGET_DIR" -name "$TOMBSTONES_FILENAME" -type f | head -1)"
  if [ -n "$RESTORED_LEDGER" ]; then
    log "ledger de exclusões no snapshot: $(wc -l < "$RESTORED_LEDGER" | tr -d ' ') registro(s)"
  else
    log "aviso: o snapshot não contém ${TOMBSTONES_FILENAME}"
  fi

  # A mídia (T17.9 §138). O restic reconstrói a árvore de caminhos original, então o diretório
  # aparece em `$TARGET_DIR` sob o caminho absoluto que ele tinha na VPS. Procuramos pelo
  # subdiretório `checkins`, que é a estrutura que o provider `local` de Object Storage cria
  # (preservada na T18.1 justamente para isto) — e não pelo nome do diretório de mídia, que é
  # configurável e pode ter mudado entre o backup e a restauração. Com o provider `gcs` não há
  # mídia em disco nenhum: os objetos vivem no bucket e não passam por aqui (T18.1).
  RESTORED_MEDIA="$(find "$TARGET_DIR" -type d -name checkins | head -1)"
  if [ -n "$RESTORED_MEDIA" ]; then
    RESTORED_MEDIA="$(dirname "$RESTORED_MEDIA")"
    log "mídia encontrada no snapshot: $(find "$RESTORED_MEDIA" -type f | wc -l | tr -d ' ') arquivo(s)"
  else
    # Um snapshot anterior à T17.9, ou de um servidor que nunca recebeu foto. Não é erro.
    log "aviso: o snapshot não contém mídia social"
  fi
fi

# --- verificação obrigatória (§44) ---------------------------------------------------------
#
# Um arquivo que apareceu no storage não é um backup validado. `pg_restore --list` lê o índice do
# arquivo inteiro e falha em arquivo truncado, corrompido ou que não é um dump; a presença de
# `schema_migrations` prova que é um dump **do Spark**, com schema aplicado. O que este passo não
# prova — que o conteúdo restaura e que o backend sobe sobre ele — é o que `ops/verify-backup.sh`
# prova, restaurando num banco descartável.
log "verificando o dump restaurado"
RESTORED_DB_DIR="$(cd "$(dirname "$RESTORED_DB")" && pwd)"
RESTORED_DB_NAME="$(basename "$RESTORED_DB")"
VERIFICATION="$(pg_run "" "
  set -e
  toc=\"\$(pg_restore --list '/restore/${RESTORED_DB_NAME}')\"
  entries=\"\$(printf '%s\n' \"\$toc\" | grep -c -v '^;' || true)\"
  has_migrations=false
  printf '%s\n' \"\$toc\" | grep -q 'TABLE DATA .* schema_migrations ' && has_migrations=true
  # Contagem de tabelas, nunca conteúdo: prova que o dump tem estrutura, sem registrar qual.
  tables=\"\$(printf '%s\n' \"\$toc\" | grep -c ' TABLE ' || true)\"
  [ \"\$entries\" -gt 0 ] || { echo 'dump sem entradas' >&2; exit 1; }
  echo \"{\\\"archive\\\":\\\"ok\\\",\\\"tocEntries\\\":\$entries,\\\"tables\\\":\$tables,\\\"hasSchemaMigrations\\\":\$has_migrations}\"
" -v "${RESTORED_DB_DIR}:/restore:ro")"

log "verificação: ${VERIFICATION}"
printf '%s' "$VERIFICATION" | grep -q '"archive":"ok"' || fail "o dump não passou em pg_restore --list"
printf '%s' "$VERIFICATION" | grep -q '"hasSchemaMigrations":true' \
  || fail "o dump não contém schema_migrations: não é um snapshot do Spark"

if [ "$INSTALL" -eq 0 ]; then
  log "restauração verificada em ${RESTORED_DB} (sem --install: a produção não foi tocada)"
  [ -n "$RESTORED_MEDIA" ] && log "mídia verificada em ${RESTORED_MEDIA}"
  # stdout continua carregando **só** o caminho do dump: é isso que `ops/verify-backup.sh`
  # captura.
  printf '%s\n' "$RESTORED_DB"
  exit 0
fi

# --- instalação em produção ----------------------------------------------------------------
compose_running && fail "o backend está de pé; pare-o antes de instalar (docker compose down)"
require_database_url
PRODUCTION_URL="$(spark_database_url)"
# A URL precisa dizer qual banco está sendo restaurado (T18.3 §4): uma connection string sem path
# cairia no banco default do papel — nunca um alvo aceitável para um `pg_restore --clean`.
require_pg_url_database "$PRODUCTION_URL" "DATABASE_URL"

# --- o destino precisa ser exatamente o que o dump descreve (T18.3 §5) ----------------------
#
# `--clean --if-exists` só recria o que está **no dump**. Uma tabela que o banco ganhou depois
# deste snapshot (uma migration mais nova) sobreviveria à restauração, e `schema_migrations`
# restaurada deixaria de descrevê-la — o risco documentado desde a T18.0.3 e provado por
# `ops/tests/restore-old-snapshot-risk.test.sh`. Aqui ele deixa de ser um risco documentado e
# passa a ser uma recusa: um snapshot mais antigo que o schema atual **não** se instala por cima.
# O caminho para isso é o destino limpo — um banco novo, restaurado do zero e depois apontado
# pela configuração (ver docs/operations/DISASTER_RECOVERY.md).
EXTRA_TABLES="$(pg_dump_extra_tables "$PRODUCTION_URL" "$RESTORED_DB")" \
  || fail "não foi possível comparar o schema do destino com o dump; a restauração NÃO começou"
if [ -n "$EXTRA_TABLES" ]; then
  fail "o banco de destino tem tabelas que este snapshot não contém ($(printf '%s' "$EXTRA_TABLES" | tr '\n' ' ')).
  Restaurar por cima deixaria objetos fora do snapshot e uma schema_migrations que não os descreve.
  Restaure num banco NOVO (destino limpo) e aponte DATABASE_URL para ele — ver docs/operations/DISASTER_RECOVERY.md."
fi

# Preservar, nunca apagar (§130): o estado atual pode ser a única cópia de alguma coisa que ninguém
# percebeu que faltava no backup. O dump pré-restauração fica em `$SPARK_STAGING_DIR`, e removê-lo
# é decisão humana, depois da validação.
KEEP="${SPARK_STAGING_DIR}/pre-restore-$(timestamp).dump"
log "preservando o estado atual do banco em ${KEEP}"
"${SCRIPT_DIR}/snapshot.sh" "$KEEP" > /dev/null \
  || fail "não foi possível preservar o banco atual; a restauração NÃO começou"

# `--clean --if-exists`: os objetos do dump são recriados do zero; `--single-transaction
# --exit-on-error`: tudo ou nada. `--no-owner --no-privileges` pelo mesmo motivo do dump: o papel
# do destino é quem manda. Objetos que existam no banco e **não** no dump (uma migration mais nova
# que a do snapshot) permanecem — o runner de migrations, no próximo startup, parte de
# `schema_migrations` restaurada e reaplica o que faltar.
log "restaurando o dump no PostgreSQL de produção (transação única)"
pg_run "$PRODUCTION_URL" "
  set -e
  pg_restore -d \"\$SPARK_PG_CONN\" --clean --if-exists --no-owner --no-privileges \\
    --single-transaction --exit-on-error '/restore/${RESTORED_DB_NAME}'
" -v "${RESTORED_DB_DIR}:/restore:ro" \
  || fail "pg_restore falhou; o banco não foi alterado (transação única). O estado anterior está em ${KEEP}"

INSTALLED_VERSION="$(pg_schema_version "$PRODUCTION_URL")"
log "banco restaurado: schema_version=${INSTALLED_VERSION}"

# O grupo compartilhado do diretório de dados (T16.8.1 §3): é por ele que o container e o operador
# se encontram, com uids diferentes, no ledger e na mídia.
DATA_GID="$(spark_data_gid)" \
  || fail "não foi possível ler o grupo de ${SPARK_DATA_DIR} — o diretório de dados existe?"

# --- mídia social (T17.9 §138) ---------------------------------------------------------------
#
# O mesmo princípio do banco: preservar, nunca apagar. O diretório anterior é **renomeado**, e não
# removido — se a restauração for a errada, as fotos que existiam ainda estão lá.
if [ -n "$RESTORED_MEDIA" ]; then
  if [ -d "$SPARK_MEDIA_DIR" ] && [ -n "$(ls -A "$SPARK_MEDIA_DIR" 2> /dev/null)" ]; then
    KEEP_MEDIA="${SPARK_MEDIA_DIR}.pre-restore-$(timestamp)"
    mv "$SPARK_MEDIA_DIR" "$KEEP_MEDIA"
    log "mídia anterior preservada em ${KEEP_MEDIA}"
  fi

  mkdir -p "$SPARK_MEDIA_DIR"
  cp -a "${RESTORED_MEDIA}/." "$SPARK_MEDIA_DIR/"
  # O mesmo modelo de permissão do diretório de dados (T16.8.1 §3): o processo do container e o
  # operador do host se encontram pelo **grupo compartilhado**, com uids diferentes. `2770` no
  # diretório (setgid, para que o que o container criar herde o grupo) e `640` nos arquivos.
  chgrp -R "$DATA_GID" "$SPARK_MEDIA_DIR"
  chmod 2770 "$SPARK_MEDIA_DIR"
  find "$SPARK_MEDIA_DIR" -type d -exec chmod 2770 {} +
  find "$SPARK_MEDIA_DIR" -type f -exec chmod 640 {} +
  log "mídia instalada em ${SPARK_MEDIA_DIR} ($(find "$SPARK_MEDIA_DIR" -type f | wc -l | tr -d ' ') arquivo(s))"
else
  log "aviso: nenhuma mídia restaurada; ${SPARK_MEDIA_DIR} permanece como está"
fi

# --- ledger anti-ressurreição (T17.13.1 §14/§15) ---------------------------------------------
#
# ## União, e nunca substituição
#
# O ledger do snapshot é **mais antigo** que o do disco. Numa restauração em que a máquina
# sobreviveu (migration ruim, restauração para um ponto anterior), o arquivo local conhece
# exclusões que o snapshot não conhece, e sobrescrevê-lo apagaria exatamente os registros que
# impedem aquelas contas de voltar.
#
# Por isso os dois são unidos, e a união é feita por concatenação: o leitor consome os hashes como
# conjunto (§13), então um hash repetido não custa nada e reconciliá-lo duas vezes é a mesma
# operação. É esse detalhe do formato que torna a união trivialmente correta.
if [ -n "$RESTORED_LEDGER" ]; then
  if [ -f "$SPARK_TOMBSTONES_FILE" ]; then
    cat "$RESTORED_LEDGER" >> "$SPARK_TOMBSTONES_FILE"
    log "ledger de exclusões unido ao local ($(wc -l < "$SPARK_TOMBSTONES_FILE" | tr -d ' ') registro(s) no total)"
  else
    install -m 660 -g "$DATA_GID" "$RESTORED_LEDGER" "$SPARK_TOMBSTONES_FILE"
    log "ledger de exclusões instalado ($(wc -l < "$SPARK_TOMBSTONES_FILE" | tr -d ' ') registro(s))"
  fi
elif [ ! -f "$SPARK_TOMBSTONES_FILE" ]; then
  # Nem no snapshot, nem no disco. A reconciliação abaixo **falha fechada** (§17), e é isso que se
  # quer: sem o ledger não há como saber quem já foi excluído, e subir assim é o cenário que
  # ressuscita contas. O operador precisa recuperar o arquivo antes de continuar.
  fail "não há ledger de exclusões (nem no snapshot, nem em ${SPARK_TOMBSTONES_FILE}).
  Sem ele não é possível garantir que contas já excluídas não voltem ao ar com esta restauração.
  Recupere ${TOMBSTONES_FILENAME} de outro backup e rode de novo.
  Ver docs/runbooks/account-deletion-dr.md."
fi

# --- reconciliação anti-ressurreição (T17.13.1 §14/§15) --------------------------------------
#
# ## Por que ela roda **aqui**, e não num lembrete
#
# A restauração **não está completa** antes disto. O comando é o mesmo que o runbook manda rodar
# (`dist/cli/reconcile-account-deletions.js`), roda sobre o banco recém-restaurado, e uma falha
# aqui falha o `--install` inteiro: ledger ausente, ledger malformado, chave HMAC errada ou
# integridade referencial violada impedem que esta restauração seja declarada boa.
#
# ## A chave HMAC
#
# É ela que liga um hash do ledger a um uid do banco: rodar com a chave errada encontra zero
# correspondências e **reporta sucesso**. Ela precisa ser a mesma do backend em produção, e vem do
# mesmo lugar de onde o compose a lê — o `backend.env` do diretório de segredos.
log "reconciliando tombstones de exclusão sobre o banco restaurado"

# A chave nunca passa pelo argv do `docker run` (T18.3 §26): `-e ACCOUNT_DELETION_HMAC_KEY` sem
# valor declara que a variável atravessa para o container, e o valor vem do ambiente do próprio
# comando — a forma `-e NOME=valor` grava o segredo no argv, visível a qualquer `ps` da máquina
# (a mesma regra que `pg_run` já aplica à connection string desde a T18.0.3).
RECONCILE_ENV=()
if [ -n "${SPARK_ACCOUNT_DELETION_HMAC_KEY:-}" ]; then
  RECONCILE_ENV=(-e ACCOUNT_DELETION_HMAC_KEY)
elif [ -f "${SPARK_SECRETS_DIR}/backend.env" ]; then
  RECONCILE_ENV=(--env-file "${SPARK_SECRETS_DIR}/backend.env")
else
  fail "não foi possível localizar ACCOUNT_DELETION_HMAC_KEY (nem no ambiente, nem em ${SPARK_SECRETS_DIR}/backend.env).
  A reconciliação sem a chave certa não encontra nada e reporta sucesso — o pior desfecho possível.
  Ver docs/runbooks/account-deletion-dr.md."
fi

# `--network host` pelo mesmo motivo de `pg_run`: o comando precisa alcançar exatamente o endereço
# de `DATABASE_URL`, que não passa pela linha de comando do host (T18.0.3 P1): `-e DATABASE_URL`
# sem valor, com o valor vindo do ambiente do próprio `docker run` — nunca do argv. O mesmo para a
# chave HMAC (T18.3 §26).
DATABASE_URL="$PRODUCTION_URL" ACCOUNT_DELETION_HMAC_KEY="${SPARK_ACCOUNT_DELETION_HMAC_KEY:-}" \
  docker run --rm --network host \
  --group-add "$DATA_GID" \
  -v "${SPARK_DATA_DIR}:/data" \
  -v "${SPARK_MEDIA_DIR}:/media" \
  -e NODE_ENV=production \
  -e DATABASE_URL \
  -e SOCIAL_MEDIA_ROOT=/media \
  -e "DELETION_TOMBSTONES_FILE_PATH=/data/${TOMBSTONES_FILENAME}" \
  "${RECONCILE_ENV[@]}" \
  --entrypoint node \
  "$SPARK_IMAGE" dist/cli/reconcile-account-deletions.js \
  || fail "a reconciliação de exclusões falhou; a restauração NÃO está completa (ver docs/runbooks/account-deletion-dr.md)"

log "restauração completa: banco, mídia e ledger instalados, e a reconciliação passou."
log "Suba o backend e confira /health/ready."
