#!/usr/bin/env bash
#
# Verificação operacional periódica (T16.8 §74/§75/§79/§83/§138).
#
# Responde, em uma execução, as perguntas que a operação do Spark realmente precisa fazer:
#
#   o backend está no ar?          → /health/live   (por dentro do container, sem porta publicada)
#   o banco está acessível?        → /health/ready  (idem)
#   a internet chega até ele?      → SPARK_PUBLIC_HEALTH_URL, quando houver domínio e TLS
#   o último backup funcionou?     → estado gravado por ops/backup.sh
#   quanto disco resta?            → df da partição de dados
#   o banco está crescendo demais? → tamanho de spark.db e do WAL
#
# ## Por que não Prometheus
#
# Porque a stack de observabilidade não pode ser maior que o serviço observado (§80). Este script
# é a menor coisa que responde às perguntas acima; sai com código diferente de zero quando algo
# está errado, o que é tudo de que um `cron`, um `systemd OnFailure=` ou um monitor externo precisa.
# Se um dia houver consumidor real de métricas, `/metrics` é uma decisão nova (§81).
#
# Uso:  ops/check-health.sh [--quiet]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

# Limiares. Deliberadamente conservadores: SQLite numa VPS pequena falha por disco cheio antes de
# falhar por qualquer outra coisa, e ele falha de forma silenciosa até deixar de ser silenciosa.
DISK_WARN_PERCENT="${SPARK_DISK_WARN_PERCENT:-80}"
DISK_FAIL_PERCENT="${SPARK_DISK_FAIL_PERCENT:-90}"
# Backup diário: acima de 36 h já houve pelo menos uma execução perdida sem ninguém notar (§138).
BACKUP_STALE_HOURS="${SPARK_BACKUP_STALE_HOURS:-36}"

# O health **público**, opcional e deliberadamente separado do health do backend (T16.8.1 §2).
#
# São duas perguntas diferentes, e tratá-las como uma só é o que faz um problema de DNS parecer um
# backend morto — ou o contrário. `spark_health` (ops/lib.sh) responde "o processo implantado
# serve?", por dentro do container, sem depender de porta publicada. Este responde "a internet
# chega até ele?", e só existe depois que houver domínio e certificado. Vazio = ainda não há.
PUBLIC_HEALTH_URL="${SPARK_PUBLIC_HEALTH_URL:-}"

problems=0
note() { [ "$QUIET" -eq 1 ] || printf '  %s\n' "$*"; }
problem() { printf 'PROBLEMA: %s\n' "$*" >&2; problems=$((problems + 1)); }

# --- backend -------------------------------------------------------------------------------
#
# Por dentro do container, por padrão: em produção o backend **não publica porta** (§13.7), e um
# `curl http://127.0.0.1:8080` no host testaria algo que não existe. Ver `spark_health` em
# `ops/lib.sh`.
if spark_health live 2> /dev/null | grep -q '"status":"ok"'; then
  note "liveness ok"
else
  problem "o backend não respondeu /health/live (serviço '${SPARK_SERVICE}' em ${SPARK_COMPOSE_DIR})"
fi

READY_BODY="$(spark_health ready 2> /dev/null || true)"
if printf '%s' "$READY_BODY" | grep -q '"status":"ok"'; then
  note "readiness ok (banco acessível, migrations aplicadas)"
else
  # Readiness falso com liveness ok quase sempre significa banco: arquivo sumiu, permissão errada
  # ou migration pendente. O RUNBOOK trata os três.
  problem "readiness falhou: ${READY_BODY:-sem resposta}"
fi

# --- proxy / endereço público ----------------------------------------------------------------
#
# Verificação **adicional**, nunca substituta. Ela só roda quando o operador declarou o endereço:
# antes de existir domínio e certificado não há o que verificar, e exigi-la faria o bootstrap de
# uma VPS nova falhar por uma etapa que ainda nem começou.
if [ -n "$PUBLIC_HEALTH_URL" ]; then
  if curl -fsS --max-time 10 "${PUBLIC_HEALTH_URL%/}/health/ready" 2> /dev/null \
     | grep -q '"status":"ok"'; then
    note "health público ok (${PUBLIC_HEALTH_URL})"
  else
    # Com o backend ready acima e este falhando, o problema está entre a internet e o container:
    # DNS, certificado, firewall ou o próprio Caddy. O RUNBOOK separa os casos.
    problem "o endereço público não respondeu /health/ready: ${PUBLIC_HEALTH_URL}"
  fi
fi

# --- disco ---------------------------------------------------------------------------------
if [ -d "$SPARK_DATA_DIR" ]; then
  USED_PERCENT="$(df --output=pcent "$SPARK_DATA_DIR" | tail -1 | tr -dc '0-9')"
  AVAILABLE="$(df -h --output=avail "$SPARK_DATA_DIR" | tail -1 | tr -d ' ')"
  note "disco: ${USED_PERCENT}% usado, ${AVAILABLE} livre em ${SPARK_DATA_DIR}"
  if [ "$USED_PERCENT" -ge "$DISK_FAIL_PERCENT" ]; then
    problem "disco em ${USED_PERCENT}% (limite ${DISK_FAIL_PERCENT}%) — ver RUNBOOK, 'disco cheio'"
  elif [ "$USED_PERCENT" -ge "$DISK_WARN_PERCENT" ]; then
    note "ATENÇÃO: disco em ${USED_PERCENT}%"
  fi

  DB_PATH="${SPARK_DATA_DIR}/${DB_FILENAME}"
  if [ -f "$DB_PATH" ]; then
    # Tamanho, nunca conteúdo. Um WAL persistentemente grande indica checkpoint que não fecha —
    # normalmente um leitor de longa duração segurando a janela.
    note "spark.db: $(du -h "$DB_PATH" | cut -f1); wal: $(du -h "${DB_PATH}-wal" 2> /dev/null | cut -f1 || echo 0)"
  else
    problem "o arquivo do banco não existe em ${DB_PATH}"
  fi
else
  problem "o diretório de dados não existe: ${SPARK_DATA_DIR}"
fi

# --- disco da mídia social (T17.9 / T17.10 §92) -----------------------------------------------
#
# A mídia mora num diretório próprio, e em produção ela costuma ser um **volume separado** — é o
# que `.env.example` recomenda, e é o que faz `restic` deduplicar as fotos entre snapshots sem
# arrastar o banco junto. Vigiar só `$SPARK_DATA_DIR` deixava justamente a partição que **cresce**
# fora da checagem: o banco é kilobytes por conta, e as fotos são megabytes.
#
# Quando as duas apontam para o mesmo sistema de arquivos, a checagem acima já respondeu; repetir
# só produziria um alarme duplicado. `df --output=source` é o que decide.
if [ -d "$SPARK_MEDIA_DIR" ]; then
  MEDIA_SOURCE="$(df --output=source "$SPARK_MEDIA_DIR" | tail -1 | tr -d ' ')"
  DATA_SOURCE=""
  [ -d "$SPARK_DATA_DIR" ] && DATA_SOURCE="$(df --output=source "$SPARK_DATA_DIR" | tail -1 | tr -d ' ')"

  MEDIA_BYTES="$(du -sh "$SPARK_MEDIA_DIR" 2> /dev/null | cut -f1)"
  MEDIA_FILES="$(find "$SPARK_MEDIA_DIR" -type f 2> /dev/null | wc -l | tr -d ' ')"
  note "mídia social: ${MEDIA_FILES:-0} arquivo(s), ${MEDIA_BYTES:-0} em ${SPARK_MEDIA_DIR}"

  if [ "$MEDIA_SOURCE" != "$DATA_SOURCE" ]; then
    MEDIA_USED_PERCENT="$(df --output=pcent "$SPARK_MEDIA_DIR" | tail -1 | tr -dc '0-9')"
    MEDIA_AVAILABLE="$(df -h --output=avail "$SPARK_MEDIA_DIR" | tail -1 | tr -d ' ')"
    note "disco da mídia: ${MEDIA_USED_PERCENT}% usado, ${MEDIA_AVAILABLE} livre"
    if [ "$MEDIA_USED_PERCENT" -ge "$DISK_FAIL_PERCENT" ]; then
      # Disco de mídia cheio **não** derruba o treino nem o sync: o upload falha de forma
      # controlada e a publicação para, com a decisão de volta para o usuário (T17.9). Ainda assim
      # é um problema operacional — o Feed para de aceitar foto até alguém agir.
      problem "disco da mídia em ${MEDIA_USED_PERCENT}% (limite ${DISK_FAIL_PERCENT}%) — ver RUNBOOK, 'disco cheio'"
    elif [ "$MEDIA_USED_PERCENT" -ge "$DISK_WARN_PERCENT" ]; then
      note "ATENÇÃO: disco da mídia em ${MEDIA_USED_PERCENT}%"
    fi
  fi
else
  # Ausência não é falha: um servidor que nunca recebeu foto não tem o diretório, e o backend o
  # cria na primeira escrita.
  note "diretório de mídia ainda não existe: ${SPARK_MEDIA_DIR}"
fi

# --- modelo de permissão (T16.8.1 §3) --------------------------------------------------------
#
# O acesso ao banco é por **grupo compartilhado**, não por uid coincidente: `/opt/spark/data`
# pertence a `spark:<grupo>` com `2770`, e o container entra nesse grupo por `group_add`. Esta
# verificação existe porque o modelo tem duas formas de se degradar em silêncio:
#
#   - alguém "resolve" um problema de permissão com `chmod 777` — e o dado pessoal do servidor
#     passa a ser legível por qualquer processo da máquina;
#   - alguém remove o setgid — e o próximo arquivo que o container criar sai com outro grupo, que
#     o operador não consegue ler. O backup só falha na próxima execução.
#
# Nenhuma das duas aparece no readiness: o backend continua servindo normalmente.
if [ -d "$SPARK_DATA_DIR" ]; then
  DATA_MODE="$(stat -c %a "$SPARK_DATA_DIR")"
  # `stat -c %a` devolve 3 dígitos quando não há bit especial e 4 quando há. Normalizar para 4
  # evita comparar "770" com "2770" e concluir errado.
  [ "${#DATA_MODE}" -eq 4 ] || DATA_MODE="0${DATA_MODE}"
  note "permissões de ${SPARK_DATA_DIR}: ${DATA_MODE} (grupo $(spark_data_gid))"

  case "$DATA_MODE" in
    2*) : ;;
    *) problem "${SPARK_DATA_DIR} perdeu o setgid (modo ${DATA_MODE}); o que o container criar sairá com outro grupo — ver PRODUCTION_DEPLOYMENT.md" ;;
  esac
  case "${DATA_MODE: -2:1}" in
    7) : ;;
    *) problem "o grupo compartilhado não tem rwx em ${SPARK_DATA_DIR} (modo ${DATA_MODE}); o backend não conseguirá escrever" ;;
  esac
  case "${DATA_MODE: -1}" in
    0) : ;;
    *) problem "${SPARK_DATA_DIR} está acessível a outros (modo ${DATA_MODE}); dado pessoal do servidor não pode ser legível pela máquina inteira" ;;
  esac
fi

# --- idade do último backup ----------------------------------------------------------------
STATUS_FILE="${SPARK_STATE_DIR}/backup-status.json"
if [ -f "$STATUS_FILE" ]; then
  OUTCOME="$(sed -n 's/.*"outcome": "\([^"]*\)".*/\1/p' "$STATUS_FILE" | head -1)"
  LAST_EPOCH="$(sed -n 's/.*"lastSuccessfulBackupEpoch": \([0-9]*\).*/\1/p' "$STATUS_FILE" | head -1)"
  LAST_EPOCH="${LAST_EPOCH:-0}"

  if [ "$LAST_EPOCH" -eq 0 ]; then
    problem "nenhum backup off-site bem-sucedido registrado"
  else
    AGE_HOURS=$(( ( $(date -u +%s) - LAST_EPOCH ) / 3600 ))
    note "último backup bem-sucedido: há ${AGE_HOURS}h (última execução: ${OUTCOME})"
    if [ "$AGE_HOURS" -ge "$BACKUP_STALE_HOURS" ]; then
      problem "o último backup off-site tem ${AGE_HOURS}h (limite ${BACKUP_STALE_HOURS}h)"
    fi
  fi
  # Uma execução com falha depois de um sucesso antigo continua sendo um problema: sem isto, o
  # backup poderia falhar por dias sob a proteção de um sucesso recente o bastante.
  [ "$OUTCOME" = "FAILURE" ] && problem "a última execução do backup falhou — ver ${STATUS_FILE}"
else
  problem "não há estado de backup em ${STATUS_FILE} (o job de backup já rodou?)"
fi

if [ "$problems" -gt 0 ]; then
  printf '%d problema(s) encontrado(s).\n' "$problems" >&2
  exit 1
fi

[ "$QUIET" -eq 1 ] || log "tudo em ordem"
