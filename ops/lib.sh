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

# Diretório operacional montado no container em `/data` (T18.0.2).
#
# Desde a T18.0 o banco **não** vive aqui: ele é o PostgreSQL apontado por `DATABASE_URL`. O que
# resta neste diretório é o que continua sendo arquivo — hoje, o ledger anti-ressurreição de
# exclusões de conta. O modelo de permissão (setgid + grupo compartilhado, T16.8.1 §3) continua
# valendo para ele.
SPARK_DATA_DIR="${SPARK_DATA_DIR:-/opt/spark/data}"
# Diretório de mídia social montado no container em `/media` (T17.9 §26/§135).
#
# Separado do resto de propósito. O snapshot do banco é um `pg_dump` **completo** a cada execução;
# a mídia é grande, imutável depois de escrita, e o restic a deduplica entre snapshots. Duas coisas
# com ciclos de vida diferentes, dois caminhos no mesmo `restic backup`.
SPARK_MEDIA_DIR="${SPARK_MEDIA_DIR:-/opt/spark/media}"
# O ledger anti-ressurreição de exclusões de conta (T17.13.1 §8/§14).
#
# Ele vive **fora** do banco, em `$SPARK_DATA_DIR`, de propósito: numa restauração o conteúdo do
# banco é substituído por uma cópia anterior, e com ela voltariam as contas já excluídas,
# inclusive a tabela `account_deletion_tombstones` da versão restaurada. O único registro que
# sobrevive à troca é este arquivo.
#
# O caminho precisa ser o mesmo que o backend usa (`DELETION_TOMBSTONES_FILE_PATH`, que dentro do
# container é `/data/deletion_tombstones.tsv`). Se um deploy mudar aquele, mude este junto.
SPARK_TOMBSTONES_FILE="${SPARK_TOMBSTONES_FILE:-${SPARK_DATA_DIR}/deletion_tombstones.tsv}"
# Diretório de segredos da VPS — o mesmo default de `docker-compose.prod.yml`. É de lá que a
# reconciliação pós-restore lê `ACCOUNT_DELETION_HMAC_KEY`, pelo mesmo `backend.env` que o compose
# entrega ao serviço: uma chave só, um lugar só.
SPARK_SECRETS_DIR="${SPARK_SECRETS_DIR:-/opt/spark/secrets}"
# Nome do ledger dentro do snapshot. Fixo: é o que `ops/restore.sh` procura na árvore restaurada.
TOMBSTONES_FILENAME="${TOMBSTONES_FILENAME:-deletion_tombstones.tsv}"
# Área de trabalho do backup: snapshot temporário + manifesto, antes de irem para o off-site.
SPARK_STAGING_DIR="${SPARK_STAGING_DIR:-/opt/spark/backups}"
# Estado operacional legível por máquina (idade do último backup, por exemplo).
SPARK_STATE_DIR="${SPARK_STATE_DIR:-/opt/spark/state}"
# Diretório do projeto na VPS, onde vivem o compose e o Caddyfile.
SPARK_COMPOSE_DIR="${SPARK_COMPOSE_DIR:-/opt/spark/repo/backend}"
SPARK_COMPOSE_FILE="${SPARK_COMPOSE_FILE:-docker-compose.prod.yml}"
SPARK_SERVICE="${SPARK_SERVICE:-backend}"
# Imagem do backend, usada pelo ensaio de restauração e pela reconciliação pós-restore.
SPARK_IMAGE="${SPARK_IMAGE:-spark-backend:latest}"
# Imagem que traz `pg_dump`, `pg_restore` e `psql` (T18.0.2).
#
# Os utilitários do PostgreSQL rodam por container, e não pelo pacote do host: a versão do
# `pg_dump` precisa ser **igual ou mais nova** que a do servidor, e a que o `apt` da VPS instala não
# tem essa garantia. Versão fixada pelo mesmo motivo do Caddy — uma ferramenta que se atualiza
# sozinha muda de comportamento sem deploy. Suba a major aqui quando subir a do servidor.
SPARK_PG_TOOLS_IMAGE="${SPARK_PG_TOOLS_IMAGE:-postgres:17-alpine}"
# Arquivo de exclusão mútua do backup. Configuração, e não parâmetro de função: ele precisa ser o
# mesmo para todas as execuções da máquina, e uma função que aceitasse outro por chamada tornaria
# possível dois backups simultâneos com locks diferentes — que é exatamente o que ele impede.
SPARK_LOCK_FILE="${SPARK_LOCK_FILE:-${SPARK_STATE_DIR}/backup.lock}"

# Nome do dump dentro do snapshot. Fixo: é o que `ops/restore.sh` procura na árvore restaurada.
DUMP_FILENAME="${DUMP_FILENAME:-spark.dump}"

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
# Dois `pg_dump` simultâneos não corrompem nada — cada um roda na sua própria transação de
# leitura —, mas desperdiçam disco, I/O e conexões, e podem deixar o repositório restic com
# snapshots redundantes. `flock` sem espera: se já há um rodando, este simplesmente não roda, e
# diz por quê.
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

# ---------------------------------------------------------------- PostgreSQL

# A connection string do banco de produção, para os scripts operacionais (T18.0.2; T18.3 §4).
#
# Ordem: a URL **direta/admin** primeiro (`SPARK_DATABASE_URL_DIRECT` → `DATABASE_URL_DIRECT` no
# ambiente → `DATABASE_URL_DIRECT` do `.env`), e só depois a pooled (`SPARK_DATABASE_URL` →
# `DATABASE_URL` → `DATABASE_URL` do `.env`). `pg_dump`/`pg_restore` são operações administrativas:
# num pooler (PgBouncer, o endpoint pooled do Neon) elas podem esbarrar em limitação de sessão, e a
# T18.3 pede que prefiram a conexão direta quando ela existe. Vazio conta como ausente — é o que o
# Compose injeta para `${DATABASE_URL_DIRECT:-}`.
#
# O `.env` é a fonte normal na VPS: é o mesmo arquivo de onde `docker-compose.prod.yml` lê a
# variável para o serviço, então backup e servidor apontam para o mesmo banco por construção.
#
# O valor é **segredo** (carrega a senha). Ele nunca é impresso, nunca vai para `backup-status.json`
# e entra nos containers de ferramenta por variável de ambiente, não por argumento — argumento
# aparece em `ps` da máquina inteira.
spark_database_url() {
  local candidate
  for candidate in "${SPARK_DATABASE_URL_DIRECT:-}" "${DATABASE_URL_DIRECT:-}"; do
    if [ -n "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  if [ -f "${SPARK_COMPOSE_DIR}/.env" ]; then
    candidate="$( env_file_value "${SPARK_COMPOSE_DIR}/.env" DATABASE_URL_DIRECT )"
    if [ -n "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  fi
  for candidate in "${SPARK_DATABASE_URL:-}" "${DATABASE_URL:-}"; do
    if [ -n "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  if [ -f "${SPARK_COMPOSE_DIR}/.env" ]; then
    candidate="$( env_file_value "${SPARK_COMPOSE_DIR}/.env" DATABASE_URL )"
    if [ -n "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  fi
  return 1
}

# O valor de uma variável num arquivo `.env`, sem aspas envolventes. Vazio quando ausente.
env_file_value() {
  local file="$1" name="$2"
  sed -n "s/^${name}=//p" "$file" | head -1 \
    | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

require_database_url() {
  spark_database_url > /dev/null \
    || fail "DATABASE_URL não encontrada (nem SPARK_DATABASE_URL, nem DATABASE_URL, nem ${SPARK_COMPOSE_DIR}/.env)"
}

# O nome do banco (o pathname) de uma connection string PostgreSQL (T18.0.3 P0).
#
#   postgresql://usuario:senha@host:porta/NOME_DO_BANCO?parametro=valor
#
# Só o suficiente para o que `same_postgres_database` precisa: tudo depois do primeiro `/` que
# aparece **depois** da autoridade (usuário/senha/host/porta), sem query string nem fragmento.
# Devolve vazio quando a URL não tem path — connection strings sem `/dbname` existem (o driver usa
# o banco padrão do papel), e "vazio" não pode ser tratado como igual a outro "vazio": a chamadora
# decide o que fazer com isso.
pg_url_database() {
  local url="$1"
  local rest="${url#*://}"
  local authority="${rest%%/*}"
  local after_authority="${rest#"$authority"}"
  local path="${after_authority#/}"
  path="${path%%\?*}"
  path="${path%%#*}"
  printf '%s' "$path"
}

# Exige que uma connection string diga, sem ambiguidade, qual database manipula (T18.3 §4).
#
# `postgres://host` e `postgres://host/` são válidas para o driver — ele cai no banco default do
# papel — e é exatamente por isso que uma operação administrativa (backup, restore, migration) as
# recusa: "o banco default de quem estiver logado" não é uma identidade, é uma surpresa. A senha
# nunca é impressa: só o rótulo de qual variável falhou.
require_pg_url_database() {
  local url="$1" label="${2:-connection string}"
  [ -n "$(pg_url_database "$url")" ] \
    || fail "${label} não declara o database no path (postgres://host/NOME): operação administrativa recusada"
}

# Dois endereços PostgreSQL podem ser o MESMO banco (T18.0.3 P0; falha fechada desde a T18.3 §4)?
#
# Comparação de string pura não basta: no Neon, o endpoint pooled (`ep-xxx-pooler.../spark`) e o
# direto (`ep-xxx.../spark`) são hosts **diferentes** que servem o **mesmo** banco. A defesa aqui é
# deliberadamente conservadora — nomes de banco iguais são tratados como o mesmo banco mesmo com
# hosts diferentes. Um falso positivo (recusar um ensaio legítimo porque duas VPS distintas usam o
# nome "spark" por convenção) é um incômodo que o operador contorna nomeando o banco descartável de
# outro jeito; um falso negativo (`pg_restore --clean` sobre produção) não tem contorno.
#
# Uma URL **sem** nome de banco também é tratada como perigosa (T18.3 §4): antes, "vazio" de um
# lado fazia a função responder "bancos diferentes", e um ensaio com uma URL sem path passava pela
# checagem e restaurava no banco default do papel — que pode ser produção. Não saber qual banco é
# não é o mesmo que saber que são diferentes.
same_postgres_database() {
  local url_a="$1" url_b="$2"
  [ "$url_a" = "$url_b" ] && return 0

  local db_a db_b
  db_a="$(pg_url_database "$url_a")"
  db_b="$(pg_url_database "$url_b")"
  [ -z "$db_a" ] && return 0
  [ -z "$db_b" ] && return 0
  [ "$db_a" = "$db_b" ]
}

# As tabelas do schema `public` do banco de destino que o dump NÃO contém (T18.3 §5).
#
#   pg_dump_extra_tables <connection-url> <arquivo.dump>
#
# `pg_restore --clean --if-exists` só recria o que está no dump: um objeto que o destino ganhou
# depois do snapshot (uma migration mais nova) sobrevive à restauração, e `schema_migrations`
# restaurada deixa de descrevê-lo — o risco provado por `ops/tests/restore-old-snapshot-risk.test.sh`.
# Esta função é o que permite a `ops/restore.sh --install` **recusar** exatamente esse caso: uma
# linha por tabela extra em stdout; vazio quando o destino não tem nada além do dump.
#
# O dump é montado somente-leitura; a conexão entra pelo ambiente do container (`pg_run`).
pg_dump_extra_tables() {
  local url="$1" dump="$2"
  local dump_dir dump_name
  dump_dir="$(cd "$(dirname "$dump")" && pwd)"
  dump_name="$(basename "$dump")"
  pg_run "$url" "
    set -e
    pg_restore --list '/restore/${dump_name}' \
      | sed -n 's/^[0-9]*; [0-9]* [0-9]* TABLE public \([^ ]*\) .*$/\1/p' | sort -u > /tmp/dump-tables
    psql -d \"\$SPARK_PG_CONN\" -X -q -t -A -v ON_ERROR_STOP=1 -c \
      \"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY 1\" \
      | sort -u > /tmp/target-tables
    comm -13 /tmp/dump-tables /tmp/target-tables
  " -v "${dump_dir}:/restore:ro"
}

# Executa um script de shell com as ferramentas do PostgreSQL contra uma connection string.
#
#   pg_run <connection-url> <script> [argumentos extras do docker run…]
#
# O script enxerga a conexão em `$SPARK_PG_CONN`, e só nela. `--network host` de propósito: a
# ferramenta precisa alcançar exatamente o endereço que está em `DATABASE_URL` — um banco remoto
# (Neon), um PostgreSQL local na porta 5432 ou o serviço do CI —, e uma rede de bridge traduziria
# `localhost` para o container errado. `--user` é quem chamou o script: um dump escrito num
# diretório montado precisa nascer pertencendo ao operador, sem `chown` depois.
#
# `-e SPARK_PG_CONN` (sem `=valor`) e não `-e "SPARK_PG_CONN=${url}"` (T18.0.3 P1): a segunda forma
# grava a connection string — com a senha — no argv do processo `docker`, visível para qualquer um
# que rode `ps` na máquina. A primeira só declara que a variável deve atravessar para o container, e
# o valor vem do ambiente do próprio comando `docker run` (`SPARK_PG_CONN="$url" docker run ...`),
# nunca da linha de comando.
pg_run() {
  local url="$1" script="$2"
  shift 2
  SPARK_PG_CONN="$url" docker run --rm --network host \
    --user "$(id -u):$(id -g)" \
    -e SPARK_PG_CONN \
    "$@" \
    --entrypoint sh "$SPARK_PG_TOOLS_IMAGE" -c "$script"
}

# A versão do schema aplicada num banco, para o manifesto e para os logs. Só o número.
#
# `\$SPARK_PG_CONN` é expandido **dentro** do container, nunca aqui: a connection string não passa
# pela linha de comando do host.
pg_schema_version() {
  local url="$1"
  pg_run "$url" "
    psql -d \"\$SPARK_PG_CONN\" -X -q -t -A -v ON_ERROR_STOP=1 \\
      -c 'SELECT COALESCE(MAX(version), 0) FROM schema_migrations'
  " | tr -d '[:space:]'
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
