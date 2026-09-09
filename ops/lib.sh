#!/usr/bin/env bash
# Funções compartilhadas pelos scripts operacionais do Spark (T16.8 / T16.8.1).
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
# Diretório de mídia social montado no container em `/media` (T17.9 §26/§135).
#
# Separado do banco de propósito. O snapshot do SQLite é uma **cópia completa** a cada execução
# (`VACUUM INTO`); a mídia é grande, imutável depois de escrita, e o restic a deduplica entre
# snapshots. Se as fotos vivessem dentro de `/opt/spark/data`, cada `VACUUM INTO` continuaria
# copiando só o banco — mas o `restic backup` do diretório inteiro passaria a arrastar mídia e
# banco no mesmo caminho, e a restauração perderia a distinção entre "o banco está íntegro" e "os
# arquivos vieram junto". Duas coisas com ciclos de vida diferentes, dois caminhos.
SPARK_MEDIA_DIR="${SPARK_MEDIA_DIR:-/opt/spark/media}"
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
# Arquivo de exclusão mútua do backup. Configuração, e não parâmetro de função: ele precisa ser o
# mesmo para todas as execuções da máquina, e uma função que aceitasse outro por chamada tornaria
# possível dois backups simultâneos com locks diferentes — que é exatamente o que ele impede.
SPARK_LOCK_FILE="${SPARK_LOCK_FILE:-${SPARK_STATE_DIR}/backup.lock}"

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
  mkdir -p "$(dirname "$SPARK_LOCK_FILE")"
  exec 9> "$SPARK_LOCK_FILE"
  flock -n 9 || fail "já existe um backup em andamento (lock: ${SPARK_LOCK_FILE})"
}

# ---------------------------------------------------------------- Compose

# Executa um comando do Compose que **altera** a pilha (`up`, `down`, `restart`).
#
# Existe como função para que ninguém precise repetir o `cd` e o `-f`: repetir era a origem do
# `A && B || C` que o ShellCheck acusava (SC2015), uma forma em que a parte `|| C` roda também
# quando `cd` dá certo e o comando falha — mascarando a falha do comando como "diretório ausente".
#
# Aqui **não** há valor de reserva para variável nenhuma: `docker-compose.prod.yml` declara
# `SPARK_IMAGE_TAG`, `SPARK_DOMAIN`, `SPARK_ACME_EMAIL` e `SPARK_DATA_GID` como obrigatórias
# (`${VAR:?}`), e é isso que impede um `up` de subir com tag indefinida, domínio errado ou fora do
# grupo compartilhado. Quem as fornece é `backend/.env` na VPS, mais o `SPARK_IMAGE_TAG` que
# `ops/deploy.sh` passa por deploy.
compose_cmd() {
  ( cd "$SPARK_COMPOSE_DIR" && docker compose -f "$SPARK_COMPOSE_FILE" "$@" )
}

# Executa um comando do Compose **somente leitura** (`ps`, `exec`, `logs`, `images`).
#
# ## Por que isto precisa existir
#
# O Compose interpola o arquivo inteiro antes de rodar qualquer subcomando. Uma variável `${VAR:?}`
# não resolvida derruba até um `ps` — então `ops/check-health.sh` rodando pelo systemd, sem
# `SPARK_IMAGE_TAG` no ambiente, não conseguia sequer perguntar se o backend estava vivo. O
# healthcheck reportava "o backend não respondeu" com o backend perfeitamente no ar.
#
# A ordem de precedência é a mesma do Compose: ambiente > `.env` do projeto > reserva. As reservas
# só existem para o que **não** afeta um container que já está rodando — consultar um processo em
# execução não depende de qual tag construiu a imagem dele.
#
# Elas ficam deliberadamente fora de `compose_cmd`: uma reserva silenciosa num `up` é exatamente o
# tipo de default escondido que a T16.8 proíbe.
compose_query() {
  (
    cd "$SPARK_COMPOSE_DIR" || return 1

    # O mesmo arquivo que o Compose leria sozinho — carregado aqui para que as reservas abaixo não
    # passem por cima do que o operador configurou.
    if [ -f .env ]; then
      set -a
      # O `.env` do projeto é configuração da VPS: ele não existe no repositório nem em tempo de
      # análise estática, então não há arquivo para o ShellCheck seguir.
      # shellcheck disable=SC1091
      . ./.env
      set +a
    fi

    : "${SPARK_IMAGE_TAG:=indefinida}"
    : "${SPARK_DOMAIN:=spark.invalid}"
    : "${SPARK_ACME_EMAIL:=ops@spark.invalid}"
    : "${SPARK_DATA_GID:=0}"
    export SPARK_IMAGE_TAG SPARK_DOMAIN SPARK_ACME_EMAIL SPARK_DATA_GID

    docker compose -f "$SPARK_COMPOSE_FILE" "$@"
  )
}

compose_running() {
  [ -d "$SPARK_COMPOSE_DIR" ] || return 1
  local running
  # A falha vira `return 1` explicitamente: "não consegui perguntar" e "não está rodando" levam à
  # mesma conclusão operacional, e nenhuma delas pode ser confundida com sucesso.
  running="$( compose_query ps -q "$SPARK_SERVICE" 2> /dev/null )" || return 1
  [ -n "$running" ]
}

# ---------------------------------------------------------------- health

# O corpo do probe de health, executado **dentro** do container (T16.8.1 §2).
#
# Produção não publica a porta do backend: quem escuta 80/443 é o Caddy, e `docker-compose.prod.yml`
# só declara `expose`. Um script operacional que fizesse `curl http://127.0.0.1:8080` no host
# testaria uma porta que, por construção, não existe — e falharia sempre, ou pior, passaria a
# depender de alguém publicá-la "para o health funcionar".
#
# O probe usa o `fetch` global do Node 22, que já está na imagem: nada de instalar curl num
# container que deliberadamente não o tem.
health_probe_script() {
  local endpoint="$1"
  printf '%s' "
    const port = process.env.PORT || 8080;
    fetch('http://127.0.0.1:' + port + '/health/${endpoint}')
      .then(async (response) => {
        process.stdout.write(await response.text());
        process.exit(response.ok ? 0 : 1);
      })
      .catch((error) => {
        process.stderr.write(String((error && error.message) || error) + '\n');
        process.exit(1);
      });
  "
}

# Consulta `/health/live` ou `/health/ready` e imprime o corpo da resposta em stdout.
#
# Dois caminhos, e a ordem é deliberada:
#
#   1. `SPARK_HEALTH_URL` definido → HTTP direto. É o caminho para quem roda a composição **local**
#      (`docker-compose.yml`, que publica 127.0.0.1:8080) ou para um endereço público já com TLS.
#   2. padrão → `docker compose exec` no serviço do backend. Não depende de DNS, de certificado,
#      de proxy nem de porta publicada, e verifica exatamente o container que está implantado.
#
# O health **público** (pelo Caddy, com TLS) é uma verificação diferente e continua separada:
# `SPARK_PUBLIC_HEALTH_URL` em `ops/check-health.sh`. Um responde "o backend serve?", o outro
# responde "a internet chega até ele?" — e confundi-los faz um problema de DNS parecer um backend
# morto.
spark_health() {
  local endpoint="${1:?spark_health exige 'live' ou 'ready'}"

  if [ -n "${SPARK_HEALTH_URL:-}" ]; then
    curl -fsS --max-time "${SPARK_HEALTH_TIMEOUT_SECONDS:-10}" \
      "${SPARK_HEALTH_URL%/}/health/${endpoint}"
    return
  fi

  compose_query exec -T "$SPARK_SERVICE" node -e "$(health_probe_script "$endpoint")" 2> /dev/null
}

# `/health/ready` respondeu `{"status":"ok"}`?
spark_health_ready() {
  spark_health ready 2> /dev/null | grep -q '"status":"ok"'
}

# ---------------------------------------------------------------- permissões

# O grupo compartilhado entre o operador do host e o processo do container (T16.8.1 §3).
#
# ## Por que um grupo, e não um uid combinado
#
# O container roda como `node`, uid 1000, e **não pode rodar como root**. O usuário `spark` do host
# recebe o uid que o `adduser` tiver livre — 1000 numa VPS recém-criada, 1001 ou outro qualquer numa
# VPS onde já exista um usuário. Um desenho que só funciona quando os dois números coincidem por
# acaso não é um desenho: é uma coincidência que a próxima VPS quebra.
#
# A solução é não depender do uid. `/opt/spark/data` pertence a `spark:<grupo compartilhado>` com
# `2770` (setgid, para que o que o container criar herde o grupo), a service account é `640` no
# mesmo grupo, e o container entra nesse grupo por `group_add` no compose. Os dois lados alcançam o
# que precisam **pelo grupo**, com uid diferente e sem `777`, sem `chown` de root em runtime e sem
# rodar nada como root.
#
# Este helper devolve o GID do grupo do diretório de dados — que **é** o grupo compartilhado, por
# construção. Ler do próprio diretório em vez de exigir mais uma variável elimina a possibilidade
# de a configuração e a realidade discordarem.
spark_data_gid() {
  [ -d "$SPARK_DATA_DIR" ] || return 1
  stat -c %g "$SPARK_DATA_DIR"
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
# O container efêmero entra no grupo compartilhado pelo mesmo motivo que o de produção: sem isso
# ele não abriria o banco quando o uid do host não for 1000.
sqlite_node() {
  local script="$1"
  if compose_running; then
    compose_query exec -T "$SPARK_SERVICE" node -e "$script"
  else
    local group_args=()
    local gid
    if gid="$(spark_data_gid)"; then
      group_args=(--group-add "$gid")
    fi
    docker run --rm \
      "${group_args[@]}" \
      -v "${SPARK_DATA_DIR}:/data" \
      --entrypoint node \
      "$SPARK_IMAGE" -e "$script"
  fi
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
    set -a
    # Caminho vem de configuração operacional: ele não é conhecido em tempo de análise estática.
    # shellcheck disable=SC1090
    . "$file"
    set +a
  fi
}

timestamp() { date -u +%Y%m%dT%H%M%SZ; }
