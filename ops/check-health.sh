#!/usr/bin/env bash
#
# Verificação operacional periódica (T16.8 §74/§75/§79/§83/§138).
#
# Responde, em uma execução, as perguntas que a operação do Spark realmente precisa fazer:
#
#   o backend está no ar?          → /health/live
#   o banco está acessível?        → /health/ready
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
HEALTH_URL="${SPARK_HEALTH_URL:-http://127.0.0.1:8080}"

problems=0
note() { [ "$QUIET" -eq 1 ] || printf '  %s\n' "$*"; }
problem() { printf 'PROBLEMA: %s\n' "$*" >&2; problems=$((problems + 1)); }

# --- backend -------------------------------------------------------------------------------
if curl -fsS --max-time 10 "${HEALTH_URL}/health/live" 2> /dev/null | grep -q '"status":"ok"'; then
  note "liveness ok"
else
  problem "o backend não respondeu /health/live em ${HEALTH_URL}"
fi

READY_BODY="$(curl -fsS --max-time 10 "${HEALTH_URL}/health/ready" 2> /dev/null || true)"
if printf '%s' "$READY_BODY" | grep -q '"status":"ok"'; then
  note "readiness ok (banco acessível, migrations aplicadas)"
else
  # Readiness falso com liveness ok quase sempre significa banco: arquivo sumiu, permissão errada
  # ou migration pendente. O RUNBOOK trata os três.
  problem "readiness falhou: ${READY_BODY:-sem resposta}"
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
