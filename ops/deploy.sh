#!/usr/bin/env bash
#
# Deploy do Spark Backend na VPS (T16.8 §21–§24/§104/§107/§109).
#
#   backup pré-deploy ──▶ build da imagem com tag do commit ──▶ up ──▶ health ──▶ [rollback]
#
# ## Duas regras que este script existe para tornar impossíveis de esquecer
#
# 1. **Migration de produção não roda sem ponto de recuperação** (§21). O backup vem primeiro, e
#    uma falha nele aborta o deploy. É a diferença entre "a migration deu errado" e "a migration
#    deu errado e não há para onde voltar".
# 2. **A imagem é identificável** (§23). Nada de `latest` em produção: a tag é o SHA do commit, o
#    que torna "qual código está no ar?" uma pergunta com resposta — e o rollback, uma troca de tag.
#
# O deploy não é blue/green (§22): para um backend, uma VPS e um SQLite, a complexidade de duas
# pilhas paralelas custaria mais do que os poucos segundos de indisponibilidade que ela evitaria.
# O app é local-first: durante esses segundos o usuário continua treinando.
#
# Uso:
#   ops/deploy.sh                  # deploy do commit atual
#   ops/deploy.sh --skip-backup    # SÓ para um deploy sem migration nova, e sob decisão explícita
#   ops/deploy.sh --rollback <tag> # volta para uma imagem anterior

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file

REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
BACKEND_DIR="${REPO_ROOT}/backend"
COMPOSE_FILE="${SPARK_COMPOSE_FILE:-docker-compose.prod.yml}"
HEALTH_URL="${SPARK_HEALTH_URL:-http://127.0.0.1:8080}"
SKIP_BACKUP=0
ROLLBACK_TAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-backup) SKIP_BACKUP=1; shift ;;
    --rollback)    ROLLBACK_TAG="${2:?--rollback exige uma tag}"; shift 2 ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

require_cmd docker
require_cmd git

cd "$BACKEND_DIR"

wait_for_health() {
  local attempts="${1:-45}"
  for _ in $(seq 1 "$attempts"); do
    if curl -fsS --max-time 5 "${HEALTH_URL}/health/ready" 2> /dev/null | grep -q '"status":"ok"'; then
      return 0
    fi
    sleep 2
  done
  return 1
}

current_tag() {
  # A tag que está no ar agora, lida do container em execução — e não de um arquivo de estado que
  # pode ter sido escrito por um deploy que não terminou.
  docker compose -f "$COMPOSE_FILE" ps --format '{{.Image}}' "$SPARK_SERVICE" 2> /dev/null \
    | head -1 | sed 's/.*://' || true
}

# --- rollback --------------------------------------------------------------------------------
if [ -n "$ROLLBACK_TAG" ]; then
  docker image inspect "spark-backend:${ROLLBACK_TAG}" > /dev/null 2>&1 \
    || fail "a imagem spark-backend:${ROLLBACK_TAG} não existe nesta máquina"

  log "rollback para spark-backend:${ROLLBACK_TAG}"
  # ATENÇÃO, e está no runbook: voltar a aplicação NÃO desfaz uma migration (§24). Se a versão que
  # está saindo aplicou uma migration incompatível com a que entra, o caminho é restaurar o backup
  # pré-deploy — não este rollback.
  SPARK_IMAGE_TAG="$ROLLBACK_TAG" docker compose -f "$COMPOSE_FILE" up -d "$SPARK_SERVICE"
  if wait_for_health; then
    log "rollback concluído e saudável"
    exit 0
  fi
  fail "a imagem anterior também não ficou saudável — ver docs/operations/RUNBOOK.md"
fi

# --- deploy ----------------------------------------------------------------------------------
COMMIT="$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD)"
if ! git -C "$REPO_ROOT" diff --quiet || ! git -C "$REPO_ROOT" diff --cached --quiet; then
  # Uma tag de commit que não descreve o que está sendo construído é pior que nenhuma tag: ela
  # mente sobre o que está no ar.
  fail "há alterações não commitadas; a tag da imagem precisa descrever exatamente o que sobe"
fi

PREVIOUS_TAG="$(current_tag)"
log "deploy de ${COMMIT} (versão atual: ${PREVIOUS_TAG:-nenhuma})"

# 1. Ponto de recuperação, antes de qualquer coisa (§21).
if [ "$SKIP_BACKUP" -eq 0 ]; then
  log "backup pré-deploy"
  "${SCRIPT_DIR}/backup.sh" --tag "pre-deploy" \
    || fail "o backup pré-deploy falhou; o deploy foi abortado antes de tocar em produção"
else
  log "AVISO: backup pré-deploy pulado por --skip-backup"
fi

# 2. Construir e etiquetar. Build local na VPS: não obriga registry pago (§108), e a imagem que
#    sobe é a que foi construída a partir do commit conferido acima.
log "construindo spark-backend:${COMMIT}"
docker build -t "spark-backend:${COMMIT}" -t "spark-backend:latest" "$BACKEND_DIR"

# 3. Subir. As migrations rodam no startup do processo, antes de ele escutar a porta — então o
#    readiness abaixo já é a confirmação de que elas terminaram (§19).
log "subindo spark-backend:${COMMIT}"
SPARK_IMAGE_TAG="$COMMIT" docker compose -f "$COMPOSE_FILE" up -d

# 4. Health obrigatório antes de declarar sucesso (§109).
if wait_for_health; then
  log "deploy concluído: spark-backend:${COMMIT} está ready"
  log "rollback, se necessário: ops/deploy.sh --rollback ${PREVIOUS_TAG:-<tag-anterior>}"
  exit 0
fi

# 5. Rollback automático apenas da **aplicação**. O banco não volta sozinho: se a versão nova
#    aplicou migration, desfazê-la é decisão humana com o backup pré-deploy em mãos (§24).
printf 'ERRO: a versão nova não ficou saudável.\n' >&2
docker compose -f "$COMPOSE_FILE" logs --tail 50 "$SPARK_SERVICE" >&2 || true

if [ -n "$PREVIOUS_TAG" ] && [ "$PREVIOUS_TAG" != "$COMMIT" ]; then
  log "voltando para spark-backend:${PREVIOUS_TAG}"
  SPARK_IMAGE_TAG="$PREVIOUS_TAG" docker compose -f "$COMPOSE_FILE" up -d "$SPARK_SERVICE"
  wait_for_health && fail "rollback aplicado; a versão ${COMMIT} não subiu. Se houve migration, ver RUNBOOK."
fi

fail "deploy falhou e o rollback automático não recuperou — ver docs/operations/RUNBOOK.md"
