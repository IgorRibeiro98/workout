#!/usr/bin/env bash
#
# O risco documentado de restaurar um snapshot mais antigo que a migration mais recente de
# produção (T18.0.3 §8; ver docs/operations/BACKUP_AND_RESTORE.md, "Risco conhecido").
#
#   banco em V1  ──pg_dump──▶  snapshot-V1
#   banco recebe V2 (objeto novo, fora do snapshot-V1)
#   banco em V2  ──pg_restore --clean --if-exists de snapshot-V1──▶  banco "restaurado"
#
# Este teste prova, com `pg_dump`/`pg_restore` de verdade (nunca simulados), o mecanismo exato que
# a documentação descreve: `--clean --if-exists` só recria os objetos que estão **no dump** — ele
# não enxerga, e por isso não apaga, o que o banco de destino ganhou depois daquele snapshot. O
# objeto "mais novo que o snapshot" sobrevive à restauração, órfão de qualquer registro em
# `schema_migrations` do dump restaurado.
#
# Não usa as migrations reais do Spark (haveria só uma no repositório hoje, T18.0 tendo
# consolidado tudo numa baseline — não dá para simular "duas migrations" com ela sem inventar uma
# segunda de mentira). O mecanismo provado aqui é do PostgreSQL/`pg_restore`, não específico do
# schema do Spark — e é exatamente o mecanismo que `ops/restore.sh --install` usa.
#
# Cria e descarta seu próprio banco descartável (nunca o de outro job do CI), e nunca toca em
# produção — nenhuma URL de produção é lida aqui.
#
# Uso:  SPARK_TEST_DATABASE_URL=postgresql://... ops/tests/restore-old-snapshot-risk.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/lib.sh
. "${OPS_DIR}/lib.sh"

: "${SPARK_TEST_DATABASE_URL:?SPARK_TEST_DATABASE_URL é obrigatório (PostgreSQL alcançável pela rede do host, para criar o banco descartável deste teste)}"
require_cmd docker

BASE_URL="$SPARK_TEST_DATABASE_URL"
BASE_DB="$(pg_url_database "$BASE_URL")"
[ -n "$BASE_DB" ] || fail "SPARK_TEST_DATABASE_URL sem nome de banco no path; não dá para derivar o banco descartável"

NEW_DB="spark_dr_risk_$$"
# Troca só o nome do banco na URL, preservando autoridade e query string.
AFTER_DB="${BASE_URL#*"/${BASE_DB}"}"
BEFORE_DB="${BASE_URL%"/${BASE_DB}${AFTER_DB}"}"
TEST_URL="${BEFORE_DB}/${NEW_DB}${AFTER_DB}"

WORK="$(mktemp -d)"
cleanup() {
  pg_run "$BASE_URL" "
    psql -d \"\$SPARK_PG_CONN\" -X -q -v ON_ERROR_STOP=1 -c \"DROP DATABASE IF EXISTS ${NEW_DB} WITH (FORCE)\"
  " > /dev/null 2>&1 || true
  rm -rf "$WORK" 2> /dev/null || true
}
trap cleanup EXIT

failures=0
check() {
  local descricao="$1" esperado="$2" obtido="$3"
  if [ "$esperado" = "$obtido" ]; then
    printf '  ok    %s\n' "$descricao"
  else
    printf '  FALHA %s (esperado "%s", obtido "%s")\n' "$descricao" "$esperado" "$obtido" >&2
    failures=$((failures + 1))
  fi
}

echo "=== risco de restaurar um snapshot mais antigo que a migration de produção ==="

log "criando o banco descartável ${NEW_DB}"
pg_run "$BASE_URL" "
  psql -d \"\$SPARK_PG_CONN\" -X -q -v ON_ERROR_STOP=1 -c \"CREATE DATABASE ${NEW_DB}\"
" > /dev/null

log "[V1] schema inicial, com uma linha reconhecível"
pg_run "$TEST_URL" "
  set -e
  psql -d \"\$SPARK_PG_CONN\" -X -q -v ON_ERROR_STOP=1 -c \"
    CREATE TABLE t1 (id INT PRIMARY KEY, val TEXT);
    INSERT INTO t1 VALUES (1, 'v1-original');
    CREATE TABLE schema_migrations (version INT PRIMARY KEY);
    INSERT INTO schema_migrations VALUES (1);
  \"
" > /dev/null

log "pg_dump do snapshot V1"
pg_run "$TEST_URL" "
  pg_dump -d \"\$SPARK_PG_CONN\" --format=custom -f /work/snapshot-v1.dump
" -v "${WORK}:/work" > /dev/null
[ -s "${WORK}/snapshot-v1.dump" ] || fail "o snapshot V1 não foi criado"

log "[V2] uma 'migration' roda depois do snapshot V1 (tabela nova + bookkeeping)"
pg_run "$TEST_URL" "
  psql -d \"\$SPARK_PG_CONN\" -X -q -v ON_ERROR_STOP=1 -c \"
    CREATE TABLE t2 (id INT PRIMARY KEY, criada_depois_do_v1 BOOLEAN NOT NULL DEFAULT true);
    INSERT INTO t2 VALUES (1, true);
    INSERT INTO schema_migrations VALUES (2);
    UPDATE t1 SET val = 'v2-sobrescrito' WHERE id = 1;
  \"
" > /dev/null

log "restaurando o snapshot V1 sobre o banco que já está em V2 (mesmo comando de ops/restore.sh --install)"
pg_run "$TEST_URL" "
  set -e
  pg_restore -d \"\$SPARK_PG_CONN\" --clean --if-exists --no-owner --no-privileges \\
    --single-transaction --exit-on-error /work/snapshot-v1.dump
" -v "${WORK}:/work" > /dev/null

RESULTADO="$(pg_run "$TEST_URL" "
  psql -d \"\$SPARK_PG_CONN\" -X -q -t -A -v ON_ERROR_STOP=1 -c \"
    SELECT
      (SELECT val FROM t1 WHERE id = 1)
      || '|' ||
      (SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 't2'))
      || '|' ||
      (SELECT string_agg(version::text, ',' ORDER BY version) FROM schema_migrations)
  \"
")"

T1_VAL="$(printf '%s' "$RESULTADO" | cut -d'|' -f1)"
T2_EXISTS="$(printf '%s' "$RESULTADO" | cut -d'|' -f2)"
MIGRATIONS="$(printf '%s' "$RESULTADO" | cut -d'|' -f3)"

check "t1 voltou ao conteúdo do snapshot V1 (o que o dump cobre, ele restaura)" \
  "v1-original" "$T1_VAL"
check "t2 (criada DEPOIS do snapshot V1) sobrevive à restauração — --clean --if-exists não a apaga" \
  "true" "$T2_EXISTS"
check "schema_migrations volta a mostrar só a V1 — a bookkeeping 'esquece' a V2" \
  "1" "$MIGRATIONS"

echo
if [ "$failures" -gt 0 ]; then
  printf '=== %d verificação(ões) falharam ===\n' "$failures" >&2
  exit 1
fi
echo "=== confirmado: pg_restore --clean --if-exists não apaga objetos fora do dump, e a"
echo "    bookkeeping de schema_migrations pós-restore diverge do schema real — risco documentado ==="
