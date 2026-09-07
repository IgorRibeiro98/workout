#!/usr/bin/env bash
# Funções compartilhadas pelos scripts operacionais do Spark (T16.8).
#
# Este arquivo não faz nada sozinho: ele é lido com `source` pelos demais scripts de `ops/`.
# Nenhum segredo mora aqui. Configuração vem de variáveis de ambiente, opcionalmente carregadas de
# um arquivo fora do Git (ver `ops/spark-backup.env.example`).

# `pipefail` importa mais que o resto: sem ele, `a | b` esconde a falha de `a`, e um backup que
# falhou no meio de um pipe pareceria bem-sucedido.
set -euo pipefail

# ---------------------------------------------------------------- configuração

# Diretório de dados montado no container em `/data`. É onde `spark.db` vive de verdade.
SPARK_DATA_DIR="${SPARK_DATA_DIR:-/opt/spark/data}"
# Área de trabalho do backup: snapshot temporário + manifesto, antes de irem para o off-site.
SPARK_STAGING_DIR="${SPARK_STAGING_DIR:-/opt/spark/backups}"
# Estado operacional legível por máquina (idade do último backup, por exemplo).
SPARK_STATE_DIR="${SPARK_STATE_DIR:-/opt/spark/state}"
# Diretório do projeto na VPS, onde vivem o compose e o Caddyfile.
SPARK_COMPOSE_DIR="${SPARK_COMPOSE_DIR:-/opt/spark/repo/backend}"
SPARK_COMPOSE_FILE="${SPARK_COMPOSE_FILE:-docker-compose.prod.yml}"
SPARK_SERVICE="${SPARK_SERVICE:-backend}"
# Imagem usada para abrir o SQLite quando o container não está de pé.
SPARK_IMAGE="${SPARK_IMAGE:-spark-backend:latest}"

DB_FILENAME="${DB_FILENAME:-spark.db}"

# ---------------------------------------------------------------- saída

# Log operacional vai para **stderr**, não para stdout.
#
# Não é preferência de estilo: `ops/snapshot.sh` e `ops/restore.sh` imprimem o caminho do arquivo
# que produziram em stdout, e quem os chama captura esse caminho. Misturar log com dado no mesmo
# canal fazia o chamador receber o log inteiro como se fosse o caminho — um defeito real, pego
# pelo smoke local desta tarefa. stdout é o canal de dado; stderr é o canal de narrativa.
log()  { printf '%s [spark] %s\n'       "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
# Erro também em stderr: é isso que faz o cron/systemd notarem e alertarem, em vez de o backup
# falhar em silêncio (§40).
fail() { printf '%s [spark] ERRO: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; exit 1; }

require_cmd() {
  command -v "$1" > /dev/null 2>&1 || fail "comando obrigatório não encontrado: $1"
}

# ---------------------------------------------------------------- exclusão mútua

# Um job de backup por vez (§135).
#
# Dois `VACUUM INTO` simultâneos não corrompem o banco — o SQLite cuida disso —, mas desperdiçam
# disco e I/O e podem deixar o repositório restic com snapshots redundantes. `flock` sem espera:
# se já há um rodando, este simplesmente não roda, e diz por quê.
acquire_lock() {
  local lock_file="${1:-${SPARK_STATE_DIR}/backup.lock}"
  mkdir -p "$(dirname "$lock_file")"
  exec 9> "$lock_file"
  flock -n 9 || fail "já existe um backup em andamento (lock: ${lock_file})"
}

# ---------------------------------------------------------------- SQLite

# Executa um script Node com o `better-sqlite3` da imagem do backend.
#
# Por que não o CLI `sqlite3` do host: a versão instalada na VPS não é necessariamente a mesma que
# escreveu o arquivo, e um backup consistente é exatamente o lugar onde essa diferença não pode
# existir. Usar a **mesma biblioteca do processo que é dono do banco** elimina a pergunta.
#
# Prefere `exec` no container em pé — mesma máquina, mesmo namespace de arquivo, mesmo build. Se
# ele não estiver rodando, cai para um container efêmero da mesma imagem com o diretório montado.
sqlite_node() {
  local script="$1"
  if compose_running; then
    ( cd "$SPARK_COMPOSE_DIR" && \
      docker compose -f "$SPARK_COMPOSE_FILE" exec -T "$SPARK_SERVICE" node -e "$script" )
  else
    docker run --rm \
      -v "${SPARK_DATA_DIR}:/data" \
      --entrypoint node \
      "$SPARK_IMAGE" -e "$script"
  fi
}

compose_running() {
  [ -d "$SPARK_COMPOSE_DIR" ] || return 1
  local running
  running="$( cd "$SPARK_COMPOSE_DIR" && \
    docker compose -f "$SPARK_COMPOSE_FILE" ps -q "$SPARK_SERVICE" 2> /dev/null || true )"
  [ -n "$running" ]
}

# ---------------------------------------------------------------- restic

# O comando do restic, indireto de propósito (§31).
#
# O backend **não sabe** para onde o backup vai, e estes scripts também não precisam saber: o
# destino é `RESTIC_REPOSITORY`, e trocá-lo de S3 para B2, para R2 ou para outro servidor não muda
# uma linha de código. `SPARK_RESTIC_CMD` permite rodar o restic por container em uma VPS onde
# instalar o binário não seja desejável.
restic_cmd() {
  if [ -n "${SPARK_RESTIC_CMD:-}" ]; then
    # shellcheck disable=SC2086
    # Intencional: a variável carrega um comando com argumentos e precisa ser dividida em palavras.
    $SPARK_RESTIC_CMD "$@"
  else
    require_cmd restic
    restic "$@"
  fi
}

# As credenciais do off-site precisam existir, e nenhuma delas pode estar no Git.
require_restic_env() {
  [ -n "${RESTIC_REPOSITORY:-}" ] || fail "RESTIC_REPOSITORY não configurado"
  # A senha do repositório é o que torna o backup off-site criptografado (§33/§147). Sem ela o
  # restic sequer abre o repositório — mas falhar aqui dá uma mensagem que o operador entende.
  [ -n "${RESTIC_PASSWORD:-}" ] || [ -n "${RESTIC_PASSWORD_FILE:-}" ] || \
    fail "RESTIC_PASSWORD ou RESTIC_PASSWORD_FILE não configurado (o backup off-site é criptografado)"
}

# Carrega configuração de um arquivo fora do repositório, quando ele existe.
load_env_file() {
  local file="${1:-${SPARK_BACKUP_ENV_FILE:-/opt/spark/secrets/backup.env}}"
  if [ -f "$file" ]; then
    # Caminho vem de configuração operacional; ele não é conhecido em tempo de análise estática.
    set -a
    # shellcheck disable=SC1090
    . "$file"
    set +a
  fi
}

timestamp() { date -u +%Y%m%dT%H%M%SZ; }
