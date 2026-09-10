#!/usr/bin/env bash
#
# Snapshot consistente do PostgreSQL do servidor + verificação do arquivo (T16.8 §26/§27/§44,
# migrado para PostgreSQL na T18.0.2).
#
#   PostgreSQL (ativo)  ──pg_dump --format=custom──▶  spark.dump  ──pg_restore --list──▶  pronto
#
# ## Por que `pg_dump`, e por que o formato custom
#
# `pg_dump` roda dentro de **uma** transação de leitura (snapshot `REPEATABLE READ`): ele enxerga
# um ponto único no tempo, e o servidor continua aceitando escrita enquanto ele copia — que é o
# mesmo invariante que o `VACUUM INTO` garantia no SQLite (§28), agora dado pelo próprio banco. O
# formato custom é comprimido, carrega o índice do que contém (`pg_restore --list` lê esse índice
# sem restaurar nada) e é o único que permite restauração seletiva e `--single-transaction`.
#
# `--no-owner --no-privileges`: o dump precisa restaurar em um servidor cujo papel (role) tenha
# outro nome — Neon, um PostgreSQL local, o serviço do CI. Dono e GRANT são configuração do
# destino, não conteúdo do backup.
#
# ## O que este script NÃO é
#
# Não é PITR nem arquivamento de WAL: é um backup lógico completo, no ritmo do timer. A janela de
# perda é a do agendamento (ver DISASTER_RECOVERY.md), e a proteção contínua do provedor (o
# branch/PITR do Neon, quando houver) é uma camada **a mais**, não uma substituta desta — ela vive
# na conta do mesmo provedor. O desenho definitivo de backup do PostgreSQL é a T18.3.
#
# Uso:
#   ops/snapshot.sh [caminho-de-destino]
#
# Sem argumento, escreve em `$SPARK_STAGING_DIR/spark-<timestamp>.dump` e imprime o caminho.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file
require_cmd docker
require_database_url

DESTINATION="${1:-${SPARK_STAGING_DIR}/spark-$(timestamp).dump}"
DEST_DIR="$(dirname "$DESTINATION")"
mkdir -p "$DEST_DIR"
chmod 700 "$DEST_DIR" 2> /dev/null || true

[ -e "$DESTINATION" ] && fail "destino já existe: ${DESTINATION}"

# O dump nasce num nome temporário e só ganha o nome final depois de verificado: um `pg_dump`
# interrompido no meio não pode deixar para trás um arquivo que pareça um snapshot.
DEST_DIR_ABS="$(cd "$DEST_DIR" && pwd)"
TMP_NAME=".snapshot-$(timestamp)-$$.dump"
TMP_PATH="${DEST_DIR_ABS}/${TMP_NAME}"

cleanup() { rm -f "$TMP_PATH" 2> /dev/null || true; }
trap cleanup EXIT

log "gerando snapshot consistente do PostgreSQL (pg_dump, formato custom)"

# `\$SPARK_PG_CONN` é expandido dentro do container: a connection string não passa pela linha de
# comando do host. `umask 077`: o dump é dado pessoal do servidor esperando o envio off-site, e
# nasce legível só pelo operador.
VERIFICATION="$(pg_run "$(spark_database_url)" "
  set -e
  umask 077
  pg_dump -d \"\$SPARK_PG_CONN\" --format=custom --no-owner --no-privileges \\
    --file='/out/${TMP_NAME}'

  # A verificação acontece sobre o **arquivo**, não sobre a produção: é ele que vai para o
  # off-site e que precisa provar que serve. \`pg_restore --list\` lê o índice do arquivo inteiro
  # e falha em arquivo truncado ou corrompido — sem tocar em banco nenhum.
  entries=\"\$(pg_restore --list '/out/${TMP_NAME}' | grep -c -v '^;' || true)\"
  [ \"\$entries\" -gt 0 ] || { echo 'o dump não tem entradas' >&2; exit 1; }
  pg_restore --list '/out/${TMP_NAME}' | grep -q 'TABLE DATA .* schema_migrations ' \\
    || { echo 'o dump não contém schema_migrations' >&2; exit 1; }

  version=\"\$(psql -d \"\$SPARK_PG_CONN\" -X -q -t -A -v ON_ERROR_STOP=1 \\
    -c 'SELECT COALESCE(MAX(version), 0) FROM schema_migrations')\"
  echo \"archive=ok toc_entries=\$entries schema_version=\$version\"
" -v "${DEST_DIR_ABS}:/out")"
# A verificação é narrativa e vai para stderr; stdout deste script carrega **só** o caminho do
# snapshot, porque é isso que `ops/backup.sh` e `ops/verify-backup.sh` capturam dele.
log "$VERIFICATION"

[ -s "$TMP_PATH" ] || fail "o dump não apareceu em ${TMP_PATH}"
printf '%s' "$VERIFICATION" | grep -q 'archive=ok' || fail "o dump não passou na verificação"

# `install`, e não `mv`: o arquivo final nasce com o modo declarado (600), de quem está rodando o
# script, independentemente do umask de quem o criou.
install -m 600 "$TMP_PATH" "$DESTINATION"
rm -f "$TMP_PATH"
trap - EXIT

log "snapshot pronto: ${DESTINATION} ($(du -h "$DESTINATION" | cut -f1))"
printf '%s\n' "$DESTINATION"
