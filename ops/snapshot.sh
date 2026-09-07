#!/usr/bin/env bash
#
# Snapshot consistente do SQLite do servidor + verificação de integridade (T16.8 §26/§27/§44).
#
#   spark.db (ativo, WAL)  ──VACUUM INTO──▶  snapshot.db  ──integrity_check──▶  pronto
#
# ## Por que não `cp spark.db backup.db`
#
# Em WAL, o arquivo principal **não** contém as transações que ainda estão no `-wal`. Copiá-lo
# sozinho com o banco ativo produz um arquivo que abre, parece íntegro e está desatualizado — ou
# rasgado no meio de uma transação, se a cópia levar tempo. É o pior tipo de backup: um que só
# falha no dia em que é usado.
#
# ## Por que `VACUUM INTO`
#
# É o mecanismo oficial do SQLite para produzir uma cópia consistente de um banco **em uso**: roda
# dentro de uma transação de leitura, então enxerga um ponto único no tempo, e escreve um arquivo
# novo já compactado e desfragmentado. Em WAL ele não bloqueia escritores (§28) — leitores e
# escritores continuam trabalhando enquanto ele copia.
#
# A alternativa seria a Backup API (`db.backup()` do better-sqlite3), igualmente correta. `VACUUM
# INTO` venceu por ser uma instrução única e síncrona, sem callback de progresso a gerenciar, e por
# entregar um arquivo menor — o que importa quando ele vai subir por uma rede de VPS.
#
# Uso:
#   ops/snapshot.sh [caminho-de-destino]
#
# Sem argumento, escreve em `$SPARK_STAGING_DIR/spark-<timestamp>.db` e imprime o caminho.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file

DESTINATION="${1:-${SPARK_STAGING_DIR}/spark-$(timestamp).db}"
mkdir -p "$(dirname "$DESTINATION")"
chmod 700 "$(dirname "$DESTINATION")" 2> /dev/null || true

[ -e "$DESTINATION" ] && fail "destino já existe: ${DESTINATION}"

# O snapshot nasce **dentro** do diretório de dados porque é o único lugar visível ao mesmo tempo
# pelo container (em `/data`) e pelo host. Ele sai de lá logo em seguida: um snapshot esquecido ao
# lado do banco dobra o uso de disco justamente na partição que não pode encher (§74).
SNAPSHOT_NAME=".snapshot-$(timestamp)-$$.db"
CONTAINER_PATH="/data/${SNAPSHOT_NAME}"
HOST_PATH="${SPARK_DATA_DIR}/${SNAPSHOT_NAME}"

cleanup() { rm -f "$HOST_PATH" 2> /dev/null || true; }
trap cleanup EXIT

log "gerando snapshot consistente de ${SPARK_DATA_DIR}/${DB_FILENAME}"

# `readonly: true` é a garantia mais forte deste script: o caminho de backup **não pode** escrever
# no banco de produção, nem por um erro de digitação futuro. `VACUUM INTO` é permitido a partir de
# uma conexão somente leitura porque só o destino é escrito.
VERIFICATION="$(sqlite_node "
  const Database = require('better-sqlite3');
  const source = new Database('/data/${DB_FILENAME}', { readonly: true, fileMustExist: true });
  source.exec(\"VACUUM INTO '${CONTAINER_PATH}'\");
  source.close();

  // A verificação acontece sobre o **snapshot**, não sobre a produção: é o arquivo que vai para o
  // off-site que precisa provar que serve. Rodar no banco vivo diria algo sobre outro arquivo.
  const copy = new Database('${CONTAINER_PATH}', { readonly: true, fileMustExist: true });
  const integrity = copy.pragma('integrity_check', { simple: true });
  if (integrity !== 'ok') {
    console.error('integrity_check falhou no snapshot: ' + integrity);
    process.exit(1);
  }
  // \`foreign_keys = ON\` é invariante do servidor desde a T16.0; um snapshot com órfão indicaria
  // corrupção silenciosa, e é melhor descobrir aqui do que no dia da restauração.
  const orphans = copy.pragma('foreign_key_check');
  if (orphans.length > 0) {
    console.error('foreign_key_check encontrou ' + orphans.length + ' violação(ões) no snapshot');
    process.exit(1);
  }
  const version = copy.prepare('SELECT MAX(version) AS v FROM schema_migrations').get().v;
  copy.close();

  // O snapshot nasce legível pelo **grupo compartilhado** (T16.8.1 §3), e só por ele.
  //
  // Quem o cria é o processo do container (uid 1000); quem o recolhe é o operador do host, cujo
  // uid é outro. Sem isto, a leitura dependeria do umask do container — herdado, não declarado —
  // e do uid do host coincidir com o do container. O grupo vem do setgid de \`/opt/spark/data\`.
  require('node:fs').chmodSync('${CONTAINER_PATH}', 0o640);
  console.log('integrity_check=ok schema_version=' + version);
")"
# A verificação é narrativa e vai para stderr; stdout deste script carrega **só** o caminho do
# snapshot, porque é isso que `ops/backup.sh` e `ops/verify-backup.sh` capturam dele.
log "$VERIFICATION"

[ -f "$HOST_PATH" ] || fail "o snapshot não apareceu em ${HOST_PATH} (o diretório de dados é o mesmo montado no container?)"

# `install`, e não `mv` (T16.8.1 §3).
#
# O arquivo em `$HOST_PATH` foi criado **pelo container**, então pertence ao uid dele. Um `mv`
# preserva esse dono, e o `chmod 600` seguinte falharia com EPERM em qualquer VPS onde o operador
# não seja, por acaso, o mesmo uid — que é justamente a coincidência que esta fase removeu.
#
# `install` copia o conteúdo para um arquivo **novo**, de quem está rodando o script, já com o
# modo final. Ler o original exige apenas o grupo compartilhado; removê-lo, apenas permissão de
# escrita no diretório de dados. Nenhum dos dois exige `chown`, `sudo` ou uid combinado.
install -m 600 "$HOST_PATH" "$DESTINATION"
rm -f "$HOST_PATH"
trap - EXIT

log "snapshot pronto: ${DESTINATION} ($(du -h "$DESTINATION" | cut -f1))"
printf '%s\n' "$DESTINATION"
