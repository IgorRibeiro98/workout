#!/usr/bin/env bash
#
# Restauração do SQLite do servidor a partir do backup off-site (T16.8 §43/§44/§130).
#
#   restic (off-site) ──▶ diretório temporário ──▶ integrity_check ──▶ [--install] ──▶ /data
#                                                └─▶ mídia social  ──▶ [--install] ──▶ /media
#
# ## O banco **e** a mídia (T17.9 §138)
#
# Desde a T17.9 o Feed tem fotos, e elas vivem fora do SQLite. Restaurar só o banco produz um Feed
# que aponta para arquivos que não existem: `integrity_check` passa, `/health/ready` responde, e
# cada card com foto fica sem imagem. O runbook só pode declarar o Feed recuperado quando os dois
# voltaram — e é por isso que este script extrai, verifica e (com `--install`) instala os dois.
#
# ## O que ele nunca faz
#
# Ele **não** apaga o banco atual. Instalar move o arquivo existente para
# `spark.db.pre-restore-<timestamp>` antes de qualquer coisa: se a restauração for a errada, o
# estado anterior ainda está lá. "Restaurar destruindo o que existia sem validar" é exatamente o
# modo de falha que transforma um incidente recuperável em perda definitiva (§21/§130).
#
# Também não instala sobre um backend em execução: dois processos escrevendo no mesmo arquivo
# durante a troca é como se corrompe um SQLite de propósito.
#
# Uso:
#   ops/restore.sh --to /tmp/drill                      # só extrai e verifica (padrão seguro)
#   ops/restore.sh --snapshot <id> --to /tmp/drill
#   ops/restore.sh --from-file /caminho/spark.db --media-from /caminho/media --to /tmp/drill
#   ops/restore.sh --to /tmp/drill --install            # troca o banco e a mídia de produção

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file

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
  RESTORED_DB="${TARGET_DIR}/${DB_FILENAME}"
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
  # O restic reconstrói a árvore de caminhos original; o banco pode estar em qualquer profundidade.
  RESTORED_DB="$(find "$TARGET_DIR" -name "$DB_FILENAME" -type f | head -1)"
  [ -n "$RESTORED_DB" ] || fail "o snapshot não contém ${DB_FILENAME}"
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
  # subdiretório `checkins`, que é a estrutura que `LocalSocialMediaStore` cria — e não pelo nome
  # do diretório de mídia, que é configurável e pode ter mudado entre o backup e a restauração.
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
# Um arquivo que apareceu no storage não é um backup validado. Só é backup o que abre, passa no
# `integrity_check` e tem schema aplicado.
log "verificando o banco restaurado"
# `--user` é quem está rodando o script, e não o `node` da imagem (T16.8.1 §3).
#
# O arquivo restaurado pertence ao operador e nasce com o modo do snapshot (`600`) — de propósito:
# é dado pessoal do servidor, e a área de restauração não é compartilhada com o container. Um
# container rodando como uid 1000 só conseguiria abri-lo em uma VPS onde o operador fosse, por
# acaso, o uid 1000. Aqui não há grupo compartilhado a usar: o dono é quem lê, e o dono é quem
# chamou o script.
#
# Este era um defeito de verdade no caminho de recuperação, e ele estava escondido: o ensaio de
# backup do CI nunca chegou a executar (o job morria antes, por um erro de working-directory), e a
# máquina de desenvolvimento tem uid 1000.
VERIFICATION="$(docker run --rm \
  --user "$(id -u):$(id -g)" \
  -v "$(cd "$(dirname "$RESTORED_DB")" && pwd):/restore" \
  --entrypoint node "$SPARK_IMAGE" -e "
    const Database = require('better-sqlite3');
    const db = new Database('/restore/$(basename "$RESTORED_DB")', { readonly: true, fileMustExist: true });
    const integrity = db.pragma('integrity_check', { simple: true });
    if (integrity !== 'ok') { console.error('integrity_check: ' + integrity); process.exit(1); }
    if (db.pragma('foreign_key_check').length > 0) {
      console.error('foreign_key_check encontrou violações'); process.exit(1);
    }
    const version = db.prepare('SELECT MAX(version) AS v FROM schema_migrations').get().v;
    // Contagens, nunca conteúdo: provam que o banco restaurado tem dado, sem registrar qual.
    const counts = {};
    for (const table of ['backup_snapshots', 'sync_entities', 'sync_changes', 'sync_mutations']) {
      const found = db.prepare(\"SELECT name FROM sqlite_master WHERE type='table' AND name=?\").get(table);
      counts[table] = found ? db.prepare('SELECT COUNT(*) AS n FROM ' + table).get().n : null;
    }
    db.close();
    console.log(JSON.stringify({ integrity, schemaVersion: version, counts }));
  ")"

log "verificação: ${VERIFICATION}"
printf '%s' "$VERIFICATION" | grep -q '"integrity":"ok"' || fail "integrity_check não retornou ok"

if [ "$INSTALL" -eq 0 ]; then
  log "restauração verificada em ${RESTORED_DB} (sem --install: a produção não foi tocada)"
  [ -n "$RESTORED_MEDIA" ] && log "mídia verificada em ${RESTORED_MEDIA}"
  # stdout continua carregando **só** o caminho do banco: é isso que `ops/verify-backup.sh`
  # captura. A mídia sai em `SPARK_RESTORED_MEDIA`, para quem precisar dela.
  printf '%s\n' "$RESTORED_DB"
  exit 0
fi

# --- instalação em produção ----------------------------------------------------------------
compose_running && fail "o backend está de pé; pare-o antes de instalar (docker compose down)"

PREVIOUS="${SPARK_DATA_DIR}/${DB_FILENAME}"
if [ -f "$PREVIOUS" ]; then
  KEEP="${PREVIOUS}.pre-restore-$(timestamp)"
  # Preservar, nunca apagar (§130): o arquivo anterior pode ser a única cópia de alguma coisa que
  # ninguém percebeu que faltava no backup. Removê-lo é decisão humana, depois da validação.
  mv "$PREVIOUS" "$KEEP"
  log "banco anterior preservado em ${KEEP}"
fi

# `-wal` e `-shm` do banco antigo não podem sobreviver ao arquivo novo: eles descrevem transações
# de outro banco, e o SQLite tentaria aplicá-las.
rm -f "${PREVIOUS}-wal" "${PREVIOUS}-shm"

# O banco restaurado precisa ser aberto pelo processo do container — que roda como `node`, com um
# uid que **não** é o do operador (T16.8.1 §3).
#
# A versão anterior fazia `chown 1000:1000`, e isso estava errado por dois motivos ao mesmo tempo:
# `chown` exige root, então o comando falhava em silêncio para o usuário `spark` (o `|| log` só
# avisava), e o número 1000 presumia que o uid do container fosse fixo e que o do host coincidisse.
# O resultado era um banco instalado que o backend não conseguia abrir — descoberto no pior momento
# possível, que é durante uma recuperação.
#
# O acesso vem do **grupo compartilhado**, o mesmo mecanismo do resto do modelo: `660` no grupo do
# diretório de dados. Nada de `chown`, nada de `sudo`, nada de uid combinado.
DATA_GID="$(spark_data_gid)" \
  || fail "não foi possível ler o grupo de ${SPARK_DATA_DIR} — o diretório de dados existe?"

install -m 660 -g "$DATA_GID" "$RESTORED_DB" "$PREVIOUS" \
  || fail "não foi possível instalar o banco com o grupo ${DATA_GID}; o usuário atual pertence a ele? (ver docs/operations/PRODUCTION_DEPLOYMENT.md, 'usuários, grupos e permissões')"

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
# sobreviveu (banco corrompido, migration ruim, restauração para um ponto anterior), o arquivo
# local conhece exclusões que o snapshot não conhece, e sobrescrevê-lo apagaria exatamente os
# registros que impedem aquelas contas de voltar.
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
# Até esta fase, este script terminava imprimindo "rode a reconciliação depois" e apontando para o
# runbook — que descrevia um comando que não existia. Na prática, uma restauração de um snapshot
# anterior a uma exclusão devolvia a conta excluída ao ar, e a única defesa era a memória de quem
# estava de plantão.
#
# Agora a restauração **não está completa** antes disto. O comando é o mesmo que o runbook manda
# rodar (`dist/cli/reconcile-account-deletions.js`), roda sobre o banco recém-instalado, e uma
# falha aqui falha o `--install` inteiro: ledger ausente, ledger malformado, chave HMAC errada ou
# `foreign_key_check` violado impedem que esta restauração seja declarada boa.
#
# ## A chave HMAC
#
# É ela que liga um hash do ledger a um uid do banco: rodar com a chave errada encontra zero
# correspondências e **reporta sucesso**. Ela precisa ser a mesma do backend em produção, e vem do
# mesmo lugar de onde o compose a lê — o `backend.env` do diretório de segredos.
log "reconciliando tombstones de exclusão sobre o banco restaurado"

RECONCILE_ENV=()
if [ -n "${SPARK_ACCOUNT_DELETION_HMAC_KEY:-}" ]; then
  RECONCILE_ENV=(-e "ACCOUNT_DELETION_HMAC_KEY=${SPARK_ACCOUNT_DELETION_HMAC_KEY}")
elif [ -f "${SPARK_SECRETS_DIR}/backend.env" ]; then
  RECONCILE_ENV=(--env-file "${SPARK_SECRETS_DIR}/backend.env")
else
  fail "não foi possível localizar ACCOUNT_DELETION_HMAC_KEY (nem no ambiente, nem em ${SPARK_SECRETS_DIR}/backend.env).
  A reconciliação sem a chave certa não encontra nada e reporta sucesso — o pior desfecho possível.
  Ver docs/runbooks/account-deletion-dr.md."
fi

docker run --rm   --group-add "$DATA_GID"   -v "${SPARK_DATA_DIR}:/data"   -v "${SPARK_MEDIA_DIR}:/media"   -e NODE_ENV=production   -e "DATABASE_PATH=/data/${DB_FILENAME}"   -e SOCIAL_MEDIA_ROOT=/media   -e "DELETION_TOMBSTONES_FILE_PATH=/data/${TOMBSTONES_FILENAME}"   "${RECONCILE_ENV[@]}"   --entrypoint node   "$SPARK_IMAGE" dist/cli/reconcile-account-deletions.js   || fail "a reconciliação de exclusões falhou; a restauração NÃO está completa (ver docs/runbooks/account-deletion-dr.md)"

log "restauração completa: banco, mídia e ledger instalados, e a reconciliação passou."
log "Suba o backend e confira /health/ready."
