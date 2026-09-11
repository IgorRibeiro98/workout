#!/usr/bin/env bash
# Vocabulário de veredito das auditorias operacionais (T18.3 §16/§18/§25).
#
# Lido com `source` por `config-drift-audit.sh`, `iam-audit.sh` e `cost-audit.sh`, depois de
# `lib.gcp.sh`. Três estados, e a distinção não é decorativa:
#
#   PASS          a realidade é o esperado
#   DRIFT         a realidade difere do esperado — alguém precisa olhar (nunca corrigido aqui)
#   NOT_VERIFIED  não foi possível decidir (API desabilitada, sem permissão, recurso ausente)
#
# Código de saída: 1 com qualquer DRIFT; 2 só com NOT_VERIFIED; 0 quando tudo PASS. Nenhuma
# auditoria altera recurso, IAM, billing ou configuração — elas leem e reportam.

set -euo pipefail

AUDIT_PASS=0
AUDIT_DRIFT=0
AUDIT_NOT_VERIFIED=0

audit_pass()         { AUDIT_PASS=$((AUDIT_PASS + 1));                 printf '  PASS          %s\n' "$*" >&2; }
audit_drift()        { AUDIT_DRIFT=$((AUDIT_DRIFT + 1));               printf '  DRIFT         %s\n' "$*" >&2; }
audit_not_verified() { AUDIT_NOT_VERIFIED=$((AUDIT_NOT_VERIFIED + 1)); printf '  NOT_VERIFIED  %s\n' "$*" >&2; }
audit_section()      { printf '\n=== %s ===\n' "$*" >&2; }

# audit_expect <descrição> <esperado> <obtido>  → PASS se iguais, DRIFT caso contrário.
audit_expect() {
  local description="$1" expected="$2" actual="$3"
  if [ "${expected}" = "${actual}" ]; then
    audit_pass "${description} = ${actual}"
  else
    audit_drift "${description}: esperado '${expected}', real '${actual}'"
  fi
}

# audit_expect_min <descrição> <mínimo> <obtido>  → PASS se obtido ≥ mínimo (inteiros).
audit_expect_min() {
  local description="$1" minimum="$2" actual="$3"
  case "${actual}" in
    ''|*[!0-9]*) audit_not_verified "${description}: valor não numérico ('${actual}')"; return ;;
  esac
  if [ "${actual}" -ge "${minimum}" ]; then
    audit_pass "${description} = ${actual} (≥ ${minimum})"
  else
    audit_drift "${description}: esperado ≥ ${minimum}, real ${actual}"
  fi
}

# O veredito final. Imprime o resumo e sai com o código correspondente.
audit_finish() {
  local name="$1"
  printf '\n=== %s: PASS=%d DRIFT=%d NOT_VERIFIED=%d ===\n' "${name}" "${AUDIT_PASS}" "${AUDIT_DRIFT}" "${AUDIT_NOT_VERIFIED}" >&2
  if [ "${AUDIT_DRIFT}" -gt 0 ]; then
    printf '%s: DRIFT\n' "${name}"
    exit 1
  fi
  if [ "${AUDIT_NOT_VERIFIED}" -gt 0 ]; then
    printf '%s: NOT_VERIFIED\n' "${name}"
    exit 2
  fi
  printf '%s: PASS\n' "${name}"
  exit 0
}

# `gcloud ... --format=json`, ou vazio quando o comando falha OU não devolve JSON (para quem chama
# decidir NOT_VERIFIED). `--quiet` desliga qualquer prompt interativo ("habilitar a API? (y/N)"),
# que num terminal viraria uma pergunta e num script viraria texto no lugar do JSON.
gcloud_json_or_empty() {
  local output
  # Só a saída de um comando que TERMINOU BEM conta: `gcloud billing budgets list` com a API
  # desabilitada imprime `[]` em stdout e sai com 1 — e `[]` seria lido como "nenhum budget" (DRIFT)
  # em vez de "não consegui saber" (NOT_VERIFIED).
  if ! output="$(gcloud --quiet "$@" --format=json 2> /dev/null)"; then
    return 0
  fi
  if printf '%s' "${output}" | jq -e . > /dev/null 2>&1; then
    printf '%s' "${output}"
  fi
}
