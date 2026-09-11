#!/usr/bin/env bash
#
# As regras de segurança dos scripts operacionais (T18.3 §26), verificadas estaticamente sobre o
# código — não por inspeção humana:
#
#   - todo script de ops/ e ops/gcp/ roda com `set -euo pipefail` (direto, ou pelo `lib` que ele
#     carrega — os libs declaram);
#   - nenhum `rm -rf` de uma variável sem um guard `[ -n "${VAR}" ]` no mesmo script (uma
#     variável vazia viraria `rm -rf ""`/`rm -rf /`);
#   - nenhum segredo entra no argv de `docker run`: `-e NOME=valor` é proibido para
#     DATABASE_URL, DATABASE_URL_DIRECT, ACCOUNT_DELETION_HMAC_KEY, SPARK_PG_CONN — só `-e NOME`;
#   - nenhuma referência `secret:latest` nos scripts de deploy;
#   - o ensaio de restauração nunca conhece a URL de produção (nem DATABASE_URL, nem
#     DATABASE_URL_DIRECT);
#   - o smoke nunca imprime o token de invocação.
#
# Uso: ops/tests/ops-scripts-safety.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

code_only() {
  # O código sem comentários (linhas que começam com #) — as regras precisam poder ser explicadas
  # nos próprios arquivos que as aplicam.
  grep -vE '^[[:space:]]*#' "$1"
}

echo "=== set -euo pipefail ==="
for script in "${OPS_DIR}"/*.sh "${OPS_DIR}"/gcp/*.sh; do
  name="${script#"${OPS_DIR}"/}"
  if code_only "${script}" | grep -q 'set -euo pipefail'; then
    check "${name}: declara set -euo pipefail" "sim" "sim"
  elif code_only "${script}" | grep -qE '^\. "\$\{SCRIPT_DIR\}/(lib\.sh|lib\.gcp\.sh)"'; then
    check "${name}: herda set -euo pipefail do lib que carrega" "sim" "sim"
  else
    check "${name}: declara set -euo pipefail" "sim" "não"
  fi
done

echo
echo "=== rm -rf sem guard ==="
for script in "${OPS_DIR}"/*.sh "${OPS_DIR}"/gcp/*.sh "${OPS_DIR}"/tests/*.sh; do
  name="${script#"${OPS_DIR}"/}"
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    var="$(printf '%s' "${line}" | sed -nE 's/.*rm -rf "?\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?"?.*/\1/p')"
    [ -n "${var}" ] || continue
    # Aceito: um guard explícito `[ -n "${VAR}" ]` no script, OU a variável nasce de `mktemp`
    # (nunca vazia sob `set -e`), OU é o arquivo de log de um teste (`.out`/`.state`).
    if code_only "${script}" | grep -qE "\[ -n \"\\$\{?${var}(:-)?\}?\" \]"; then
      check "${name}: rm -rf \$${var} tem guard [ -n ]" "sim" "sim"
    elif code_only "${script}" | grep -qE "^[[:space:]]*(local )?${var}=\"?\\$\(mktemp( -d)?\)"; then
      check "${name}: rm -rf \$${var} vem de mktemp" "sim" "sim"
    else
      check "${name}: rm -rf \$${var} sem guard" "sim" "não"
    fi
  done < <(code_only "${script}" | grep -E 'rm -rf "?\$' || true)
done

echo
echo "=== segredos fora do argv ==="
for script in "${OPS_DIR}"/*.sh "${OPS_DIR}"/gcp/*.sh; do
  name="${script#"${OPS_DIR}"/}"
  if code_only "${script}" | grep -qE -- '-e "?(DATABASE_URL|DATABASE_URL_DIRECT|ACCOUNT_DELETION_HMAC_KEY|SPARK_PG_CONN|SPARK_DRILL_ADMIN_URL)='; then
    check "${name}: nenhum -e SEGREDO=valor" "sim" "não"
  else
    check "${name}: nenhum -e SEGREDO=valor" "sim" "sim"
  fi
done
check "smoke: o token de invocação nunca é ecoado" "não" \
  "$(code_only "${OPS_DIR}/gcp/smoke-cloud-run.sh" | grep -E 'log .*INVOKER_TOKEN|echo .*INVOKER_TOKEN|printf .*INVOKER_TOKEN' | grep -q . && echo sim || echo não)"

echo
echo "=== nenhum secret:latest ==="
for script in "${OPS_DIR}"/gcp/deploy-cloud-run.sh "${OPS_DIR}"/gcp/bootstrap-cloud-run.sh; do
  name="${script#"${OPS_DIR}"/}"
  check "${name}: sem ':latest'" "0" "$(code_only "${script}" | grep -c ':latest' || true)"
done

echo
echo "=== o ensaio de restauração não conhece produção ==="
check "gcp/dr-restore-drill.sh: não lê DATABASE_URL/DATABASE_URL_DIRECT" "0" \
  "$(code_only "${OPS_DIR}/gcp/dr-restore-drill.sh" | grep -cE '\bDATABASE_URL_DIRECT\b|\$\{?DATABASE_URL\b' || true)"
# Só código: os comentários explicam justamente que a URL de produção não existe ali.
check "db-restore-drill (backend) não lê DATABASE_URL nem DATABASE_URL_DIRECT" "0" \
  "$(cat "${OPS_DIR}/../backend/src/cli/db-restore-drill.ts" "${OPS_DIR}/../backend/src/dr/db-restore-drill.runner.ts" | grep -vE '^[[:space:]]*(\*|//)' | grep -c 'DATABASE_URL' || true)"

echo
echo "=== destinos destrutivos exigem variável não vazia ==="
check "dr-backup-drill: require_var nas quatro entradas" "4" "$(code_only "${OPS_DIR}/gcp/dr-backup-drill.sh" | grep -c '^require_var ' || true)"
# Os padrões abaixo são texto literal a procurar (grep -F): o cifrão escapado é parte do texto.
TO_GUARD="[ -n \"\$TARGET_DIR\" ] || fail"
IDENTITY_GUARD="same_postgres_database \"\$PRODUCTION_URL\" \"\$DRILL_URL\""
check "restore.sh: exige --to explícito" "sim" "$(code_only "${OPS_DIR}/restore.sh" | grep -qF "${TO_GUARD}" && echo sim || echo não)"
check "verify-backup.sh: exige SPARK_DRILL_DATABASE_URL e recusa produção" "sim" \
  "$(code_only "${OPS_DIR}/verify-backup.sh" | grep -qF "${IDENTITY_GUARD}" && echo sim || echo não)"

finish_checks "scripts operacionais: pipefail, rm guardado, segredo fora do argv, sem :latest, ensaio cego para produção"
