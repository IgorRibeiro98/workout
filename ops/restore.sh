#!/usr/bin/env bash
#
# Restauração do SQLite do servidor a partir do backup off-site (T16.8 §43/§44/§130).
#
#   restic (off-site) ──▶ diretório temporário ──▶ integrity_check ──▶ [--install] ──▶ /data
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
#   ops/restore.sh --from-file /caminho/spark.db --to /tmp/drill
#   ops/restore.sh --to /tmp/drill --install            # troca o banco de produção

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file

SNAPSHOT="latest"
TARGET_DIR=""
FROM_FILE=""
INSTALL=0

while [ $# -gt 0 ]; do
  case "$1" in
    --snapshot)  SNAPSHOT="${2:?--snapshot exige um valor}"; shift 2 ;;
    --to)        TARGET_DIR="${2:?--to exige um caminho}"; shift 2 ;;
    --from-file) FROM_FILE="${2:?--from-file exige um caminho}"; shift 2 ;;
    --install)   INSTALL=1; shift ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

[ -n "$TARGET_DIR" ] || fail "--to é obrigatório (diretório de trabalho da restauração)"

mkdir -p "$TARGET_DIR"
chmod 700 "$TARGET_DIR" 2> /dev/null || true

RESTORED_DB=""

if [ -n "$FROM_FILE" ]; then
  # Caminho sem off-site: usado pelo ensaio local e pelo CI, onde não há — e não deve haver —
  # credencial de storage real (§163/§164).
  [ -f "$FROM_FILE" ] || fail "arquivo não encontrado: ${FROM_FILE}"
  RESTORED_DB="${TARGET_DIR}/${DB_FILENAME}"
  cp "$FROM_FILE" "$RESTORED_DB"
  log "restaurado de arquivo local: ${FROM_FILE}"
else
  require_restic_env
  log "restaurando o snapshot '${SNAPSHOT}' do repositório off-site"
  restic_cmd restore "$SNAPSHOT" --host spark --tag "spark-db" --target "$TARGET_DIR" > /dev/null
  # O restic reconstrói a árvore de caminhos original; o banco pode estar em qualquer profundidade.
  RESTORED_DB="$(find "$TARGET_DIR" -name "$DB_FILENAME" -type f | head -1)"
  [ -n "$RESTORED_DB" ] || fail "o snapshot não contém ${DB_FILENAME}"
  MANIFEST="$(find "$TARGET_DIR" -name manifest.json -type f | head -1)"
  [ -n "$MANIFEST" ] && log "manifesto: $(tr -d '\n ' < "$MANIFEST")"
fi

# --- verificação obrigatória (§44) ---------------------------------------------------------
#
# Um arquivo que apareceu no storage não é um backup validado. Só é backup o que abre, passa no
# `integrity_check` e tem schema aplicado.
log "verificando o banco restaurado"
VERIFICATION="$(docker run --rm \
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

install -m 600 "$RESTORED_DB" "$PREVIOUS"
# O container roda como uid 1000 (`node`); sem isto ele não abre o arquivo que acabou de chegar.
chown 1000:1000 "$PREVIOUS" 2> /dev/null || log "aviso: não foi possível ajustar o dono de ${PREVIOUS}"

log "banco instalado em ${PREVIOUS}. Suba o backend e confira /health/ready."
