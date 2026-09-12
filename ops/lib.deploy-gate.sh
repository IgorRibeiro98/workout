#!/usr/bin/env bash
# O portão de procedência do deploy (T18.3.2).
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
#   2. o workflow `backend` precisa ter **concluído com sucesso** naquele commit — o `npm test`, o
#      `docker build`, o shellcheck e os ensaios de backup/DR passaram sobre este código exato.
#
# ## A saída de emergência
#
# `SPARK_DEPLOY_ALLOW_UNVERIFIED=1` pula o portão inteiro, com aviso no log. Ela existe porque uma
# correção de indisponibilidade não pode esperar um CI de 15 minutos, e porque um portão sem saída
# documentada vira um portão que alguém remove sob pressão. Usá-la é uma decisão registrada: o
# aviso fica no log do deploy, junto do commit que subiu sem verificação.

set -euo pipefail

# O workflow que precisa estar verde. Um lugar só: quem renomear o arquivo corrige aqui.
SPARK_CI_WORKFLOW="${SPARK_CI_WORKFLOW:-backend.yml}"

# Uso: require_reviewed_commit <diretório do repositório> <sha>
require_reviewed_commit() {
  local repo_dir="$1" sha="$2" runs

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

  log "procedência: conferindo o workflow '${SPARK_CI_WORKFLOW}' em ${sha}"
  # `--jq` é o do próprio `gh`: não exige o binário `jq` na máquina que faz o deploy.
  runs="$(
    cd "${repo_dir}" && gh run list \
      --workflow "${SPARK_CI_WORKFLOW}" \
      --commit "${sha}" \
      --limit 20 \
      --json status,conclusion \
      --jq 'map(.status + ":" + (.conclusion // "")) | join(" ")' 2> /dev/null
  )" || fail "não foi possível consultar o GitHub Actions (o 'gh' está autenticado?). Sem isso não há como afirmar que o CI passou — corrija, ou rode com SPARK_DEPLOY_ALLOW_UNVERIFIED=1 conscientemente."

  runs="$(printf '%s' "${runs}" | tr -d '\r')"
  case "${runs}" in
    '')
      fail "nenhuma execução do workflow '${SPARK_CI_WORKFLOW}' para ${sha}. O workflow só roda em mudanças de backend/ e ops/ — se este deploy é deliberado sobre um commit que não as toca, use SPARK_DEPLOY_ALLOW_UNVERIFIED=1."
      ;;
    *completed:success*)
      log "procedência: CI verde em ${sha}"
      ;;
    *queued:*|*in_progress:*|*waiting:*|*requested:*|*pending:*)
      fail "o CI de ${sha} ainda está rodando (${runs}). Espere o resultado — um deploy não corre na frente do gate que ele depende."
      ;;
    *)
      fail "o CI de ${sha} não passou (${runs}). Corrija e repita (ou SPARK_DEPLOY_ALLOW_UNVERIFIED=1 em emergência)."
      ;;
  esac
}
