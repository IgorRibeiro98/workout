#!/usr/bin/env bash
# O portão de procedência do deploy (T18.3.2; ancestralidade de backend na T19.10).
#
# Lido com `source` por `ops/deploy.sh` (VPS) e `ops/gcp/deploy-cloud-run.sh` (Cloud Run), **depois**
# do lib de cada um: ele usa `log`, `fail` e `require_cmd`, que os dois definem. Mora aqui, e não
# duplicado nos dois scripts, porque uma regra de "o que pode ir para produção" que existe em duas
# cópias vira duas regras diferentes na primeira alteração.
#
# ## O que ele acrescenta
#
# Os dois deploys já exigiam árvore Git limpa — o que garante que a **tag** descreve exatamente o
# que está sendo construído, e nada além disso. Um commit local, nunca revisado, nunca integrado e
# nunca testado pelo CI satisfaz essa exigência perfeitamente. O portão fecha os dois buracos que
# sobravam:
#
#   1. o commit precisa estar em `origin/main` — foi revisado e integrado, não só salvo;
#   2. o workflow `backend` precisa ter **concluído com sucesso** no commit backend-relevante mais
#      recente na ancestralidade dele — o `npm test`, o `docker build`, o shellcheck e os ensaios de
#      backup/DR passaram sobre este código exato.
#
# ## Por que "commit backend-relevante mais recente" e não "o próprio commit" (T19.10 §F1)
#
# `backend.yml` só dispara em mudanças de `backend/**`, `ops/**` e nos dois workflows abaixo (os
# mesmos `paths:` do próprio `.github/workflows/backend.yml` — mantidos em `SPARK_CI_PATHS` para não
# divergirem). Um release commit que só mexe em Android/versão (`165a3933`, T19.10) não dispara esse
# workflow — não porque o backend regrediu, mas porque ele não mudou. Exigir `backend.yml` NAQUELE
# commit é exigir um workflow que o próprio GitHub nunca roda, e todo release Android-only ficava
# bloqueado (o incidente real que motivou esta função).
#
# A correção NÃO é "aceitar qualquer CI verde recente" (isso aceitaria um backend verde seguido de
# uma mudança de backend não testada) nem "ignorar procedência quando o commit é só Android" (isso
# abriria uma segunda porta sem gate). Em vez disso: caminha a ancestralidade de `sha` com
# `git log -1 -- <caminhos>` e usa o commit mais recente que TOCA essas superfícies como a
# procedência de backend. Esse comando, por construção, só pode devolver um ancestral de `sha` (ou o
# próprio `sha`) — nunca um commit mais novo, nunca um commit de outra linha de história — o que
# satisfaz T19.10 §6.5 sem uma segunda chamada a `merge-base`. Se `sha` em si toca essas superfícies,
# o commit backend-relevante É `sha`, e o comportamento é idêntico ao de antes (CI do próprio
# commit, obrigatório).
#
# `--branch main`: sem ele, uma execução verde de `backend.yml` num PR/branch de feature (antes do
# merge) casaria pelo SHA e liberaria o deploy sem o commit ter passado pelo CI de `main` — CI de
# outra branch (T19.10 §6.15).
#
# ## A saída de emergência
#
# `SPARK_DEPLOY_ALLOW_UNVERIFIED=1` pula o portão inteiro, com aviso no log. Ela existe porque uma
# correção de indisponibilidade não pode esperar um CI de 15 minutos, e porque um portão sem saída
# documentada vira um portão que alguém remove sob pressão. Usá-la é uma decisão registrada: o
# aviso fica no log do deploy, junto do commit que subiu sem verificação. Ela NÃO é o caminho normal
# de um release Android-only — esse caso é resolvido pela ancestralidade acima, sem bypass nenhum.

set -euo pipefail

# O workflow que precisa estar verde. Um lugar só: quem renomear o arquivo corrige aqui.
SPARK_CI_WORKFLOW="${SPARK_CI_WORKFLOW:-backend.yml}"

# As superfícies que fazem `backend.yml` disparar (`on.push.paths` do próprio arquivo). Mudar essa
# lista sem mudar `.github/workflows/backend.yml` (ou vice-versa) reabre exatamente o buraco do §F1:
# um commit que o workflow real ignoraria, mas que este gate trataria como "precisa de CI próprio".
SPARK_CI_PATHS=(
  backend
  ops
  .github/workflows/backend.yml
  .github/workflows/deploy-backend.yml
)

# Uso: require_reviewed_commit <diretório do repositório> <sha>
require_reviewed_commit() {
  local repo_dir="$1" sha="$2" runs backend_sha

  if [ "${SPARK_DEPLOY_ALLOW_UNVERIFIED:-0}" = "1" ]; then
    log "AVISO: SPARK_DEPLOY_ALLOW_UNVERIFIED=1 — procedência de ${sha} NÃO verificada (origin/main + CI verde). Deploy de emergência: registre o motivo no runbook."
    return 0
  fi

  require_cmd git
  require_cmd gh

  log "procedência: conferindo que ${sha} está em origin/main"
  # `--quiet` e sem escrita no working tree: consultar o remoto não altera nada local além das refs
  # remotas. `FETCH_HEAD` é o que acabou de ser lido — não um `origin/main` que pode estar velho.
  git -C "${repo_dir}" fetch --quiet origin main \
    || fail "não foi possível consultar origin/main (rede ou credencial). A procedência do commit não é verificável — corrija, ou rode com SPARK_DEPLOY_ALLOW_UNVERIFIED=1 conscientemente."
  git -C "${repo_dir}" merge-base --is-ancestor "${sha}" FETCH_HEAD \
    || fail "o commit ${sha} NÃO está em origin/main: produção recebe apenas o que foi integrado. Faça push/merge e repita (ou SPARK_DEPLOY_ALLOW_UNVERIFIED=1 em emergência)."

  log "procedência: localizando o commit backend-relevante mais recente até ${sha}"
  backend_sha="$(
    git -C "${repo_dir}" log --format=%H -1 "${sha}" -- "${SPARK_CI_PATHS[@]}" 2> /dev/null
  )" || fail "não foi possível ler o histórico de ${sha} para localizar o commit backend-relevante mais recente."

  if [ -z "${backend_sha}" ]; then
    fail "HEAD ${sha}: nenhum commit na ancestralidade toca ${SPARK_CI_PATHS[*]} — não há procedência de backend nenhuma para verificar. Isto não deveria acontecer num repositório com backend em produção; se for esperado, use SPARK_DEPLOY_ALLOW_UNVERIFIED=1 conscientemente."
  fi

  if [ "${backend_sha}" = "${sha}" ]; then
    log "procedência: ${sha} altera ${SPARK_CI_PATHS[*]} diretamente — exigindo '${SPARK_CI_WORKFLOW}' no próprio commit"
  else
    log "procedência: ${sha} não altera ${SPARK_CI_PATHS[*]} — procedência de backend é ${backend_sha} (o commit backend-relevante mais recente na ancestralidade)"
  fi

  log "procedência: conferindo o workflow '${SPARK_CI_WORKFLOW}' em ${backend_sha} (branch main)"
  # `--jq` é o do próprio `gh`: não exige o binário `jq` na máquina que faz o deploy. `--branch main`
  # recusa uma execução verde de outra branch/PR que só coincide no SHA (T19.10 §6.15).
  runs="$(
    cd "${repo_dir}" && gh run list \
      --workflow "${SPARK_CI_WORKFLOW}" \
      --commit "${backend_sha}" \
      --branch main \
      --limit 20 \
      --json status,conclusion \
      --jq 'map(.status + ":" + (.conclusion // "")) | join(" ")' 2> /dev/null
  )" || fail "não foi possível consultar o GitHub Actions para ${backend_sha} (o 'gh' está autenticado?). Sem isso não há como afirmar que o CI passou — corrija, ou rode com SPARK_DEPLOY_ALLOW_UNVERIFIED=1 conscientemente."

  runs="$(printf '%s' "${runs}" | tr -d '\r')"
  case "${runs}" in
    '')
      fail "HEAD ${sha}: procedência de backend é ${backend_sha}, mas nenhuma execução de '${SPARK_CI_WORKFLOW}' em main foi encontrada para esse commit. Motivo mais comum: o commit backend-relevante nunca foi integrado em main com CI, ou o workflow foi renomeado depois dele. Corrija (ou SPARK_DEPLOY_ALLOW_UNVERIFIED=1 conscientemente)."
      ;;
    *completed:success*)
      log "procedência: CI verde em ${backend_sha}"
      ;;
    *queued:*|*in_progress:*|*waiting:*|*requested:*|*pending:*)
      fail "HEAD ${sha}: procedência de backend é ${backend_sha}, e o CI de '${SPARK_CI_WORKFLOW}' ainda está rodando lá (${runs}). Espere o resultado — um deploy não corre na frente do gate que ele depende."
      ;;
    *)
      fail "HEAD ${sha}: procedência de backend é ${backend_sha}, e o CI de '${SPARK_CI_WORKFLOW}' não passou lá (${runs}). Corrija e repita (ou SPARK_DEPLOY_ALLOW_UNVERIFIED=1 em emergência)."
      ;;
  esac
}
