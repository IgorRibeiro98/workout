#!/usr/bin/env bash
#
# O ensaio de restauração nunca aponta para o banco de produção (T18.0.3 P0).
#
# Antes, `ops/verify-backup.sh` recusava só quando `DATABASE_URL` e `SPARK_DRILL_DATABASE_URL`
# eram a **mesma string**. Isso não pega o caso perigoso de verdade: no Neon, o endpoint pooled
# (`ep-xxx-pooler.../spark`) e o direto (`ep-xxx.../spark`) do mesmo projeto são hosts diferentes
# que servem o mesmo banco. Um operador que colasse a URL pooled em produção e a direta no ensaio —
# ou vice-versa — passaria pela checagem antiga e restauraria (`pg_restore --clean`) sobre
# produção.
#
# Este teste prova duas coisas, sem depender de rede, Docker de verdade rodando algo, nem
# `SPARK_IMAGE`:
#
#   1. `same_postgres_database`/`pg_url_database` (ops/lib.sh) identificam o banco pelo nome
#      (pathname da URL), não só pela string inteira — e continuam aceitando bancos com nomes
#      diferentes.
#   2. `ops/verify-backup.sh`, chamado de ponta a ponta com um par de URLs que apontam para o
#      mesmo banco, recusa **antes** de qualquer `pg_restore`: a única coisa que a checagem faz
#      antes de falhar é validar argumentos e resolver `DATABASE_URL` — nenhum container do
#      ensaio chega a subir.
#
# Uso:  ops/tests/database-identity.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/lib.sh
. "${OPS_DIR}/lib.sh"

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

perigoso() {
  same_postgres_database "$1" "$2" && echo sim || echo não
}

echo "=== pg_url_database extrai o nome do banco ==="

check "URL com porta e query string" "spark" \
  "$(pg_url_database 'postgresql://spark:segredo@ep-xxx-pooler.us-east-2.aws.neon.tech:5432/spark?sslmode=require')"
check "URL sem porta, sem query string" "spark" \
  "$(pg_url_database 'postgresql://spark:segredo@127.0.0.1/spark')"
check "URL sem path é vazia (não é 'ausente' == 'ausente')" "" \
  "$(pg_url_database 'postgresql://spark:segredo@127.0.0.1:5432')"
check "database com underscore" "spark_drill" \
  "$(pg_url_database 'postgresql://spark:segredo@127.0.0.1:5432/spark_drill')"

echo
echo "=== same_postgres_database: o mínimo exigido pela T18.0.3 P0 ==="

# 1. mesma URL, byte a byte.
check "mesma URL exata é perigosa" "sim" \
  "$(perigoso 'postgresql://spark:segredo@127.0.0.1:5432/spark' 'postgresql://spark:segredo@127.0.0.1:5432/spark')"

# 2. pooled x direct do MESMO projeto Neon, mesmo database — o cenário do enunciado.
check "pooled x direct, mesmo database, é perigoso mesmo com hosts diferentes" "sim" \
  "$(perigoso \
    'postgresql://spark:segredo@ep-cool-darkness-12345-pooler.us-east-2.aws.neon.tech/spark' \
    'postgresql://spark:outrasenha@ep-cool-darkness-12345.us-east-2.aws.neon.tech/spark')"

# 3. hosts totalmente diferentes (nem pooled/direct do mesmo projeto), mesmo nome de banco: ainda
#    perigoso — a defesa é deliberadamente conservadora, e não tenta reconhecer padrão de host.
check "hosts sem relação nenhuma, mesmo nome de banco, ainda é perigoso" "sim" \
  "$(perigoso 'postgresql://a:b@host-um.example.com:5432/spark?sslmode=require' \
    'postgresql://c:d@host-dois.example.internal/spark')"

# 4. o exemplo permitido do enunciado: spark x spark_drill.
check "spark x spark_drill é permitido" "não" \
  "$(perigoso 'postgresql://spark:segredo@127.0.0.1:5432/spark' \
    'postgresql://spark:segredo@127.0.0.1:5432/spark_drill')"

# 5. bancos claramente distintos, hosts distintos: permitido.
check "bancos e hosts distintos é permitido" "não" \
  "$(perigoso 'postgresql://a:b@prod.example.com/spark' 'postgresql://c:d@drill.example.com/spark_drill')"

# 6. (T18.3 §4) uma URL sem nome de banco é tratada como PERIGOSA, de qualquer lado: "não sei
#    qual banco é" nunca pode virar "são bancos diferentes".
check "URL sem path do lado do ensaio é perigosa (falha fechada)" "sim" \
  "$(perigoso 'postgresql://a:b@prod.example.com/spark' 'postgresql://c:d@drill.example.com')"
check "URL sem path do lado da produção é perigosa (falha fechada)" "sim" \
  "$(perigoso 'postgresql://a:b@prod.example.com' 'postgresql://c:d@drill.example.com/spark_drill')"
check "URL com path vazio (barra final) é perigosa (falha fechada)" "sim" \
  "$(perigoso 'postgresql://a:b@prod.example.com/' 'postgresql://c:d@drill.example.com/spark_drill')"

echo
echo "=== require_pg_url_database (T18.3 §4) ==="

check "aceita URL com database" "0" \
  "$( ( require_pg_url_database 'postgresql://a:b@h/spark' 'X' > /dev/null 2>&1 ) && echo 0 || echo 1 )"
check "recusa URL sem database" "1" \
  "$( ( require_pg_url_database 'postgresql://a:b@h' 'X' > /dev/null 2>&1 ) && echo 0 || echo 1 )"
check "recusa URL com barra final" "1" \
  "$( ( require_pg_url_database 'postgresql://a:b@h/' 'X' > /dev/null 2>&1 ) && echo 0 || echo 1 )"
MENSAGEM_RECUSA="$( ( require_pg_url_database 'postgresql://a:segredo@h' 'MINHA_VAR' 2>&1 ) || true )"
check "a mensagem nomeia a variável" "sim" \
  "$( printf '%s' "$MENSAGEM_RECUSA" | grep -q 'MINHA_VAR' && echo sim || echo não )"
check "a mensagem nunca carrega a senha" "não" \
  "$( printf '%s' "$MENSAGEM_RECUSA" | grep -q 'segredo' && echo sim || echo não )"

echo
echo "=== spark_database_url prefere a URL direta (T18.3) ==="

check "com DATABASE_URL_DIRECT e DATABASE_URL, devolve a direta" "postgresql://d:d@direct/spark" \
  "$( DATABASE_URL_DIRECT='postgresql://d:d@direct/spark' DATABASE_URL='postgresql://p:p@pooled/spark' SPARK_COMPOSE_DIR=/nao-existe spark_database_url )"
check "DATABASE_URL_DIRECT vazia conta como ausente: devolve a pooled" "postgresql://p:p@pooled/spark" \
  "$( DATABASE_URL_DIRECT='' DATABASE_URL='postgresql://p:p@pooled/spark' SPARK_COMPOSE_DIR=/nao-existe spark_database_url )"
check "SPARK_DATABASE_URL_DIRECT vence tudo" "postgresql://s:s@sdirect/spark" \
  "$( SPARK_DATABASE_URL_DIRECT='postgresql://s:s@sdirect/spark' DATABASE_URL_DIRECT='postgresql://d:d@direct/spark' DATABASE_URL='postgresql://p:p@pooled/spark' SPARK_COMPOSE_DIR=/nao-existe spark_database_url )"

echo
echo "=== ops/verify-backup.sh recusa antes de qualquer pg_restore ==="

# `DATABASE_URL` e `SPARK_DRILL_DATABASE_URL` resolvem para o mesmo banco (mesmo nome, hosts
# diferentes — o cenário pooled/direct). Nem `SPARK_IMAGE` nem um PostgreSQL alcançável são
# necessários: a checagem de identidade precisa falhar antes de precisar de qualquer um dos dois.
SAIDA="$(
  DATABASE_URL='postgresql://spark:segredo@ep-cool-darkness-12345-pooler.us-east-2.aws.neon.tech/spark' \
  SPARK_DRILL_DATABASE_URL='postgresql://spark:outrasenha@ep-cool-darkness-12345.us-east-2.aws.neon.tech/spark' \
  SPARK_BACKUP_ENV_FILE=/dev/null \
  "${OPS_DIR}/verify-backup.sh" 2>&1
)" && CODIGO=0 || CODIGO=$?

check "sai com código diferente de zero" "sim" "$( [ "$CODIGO" != "0" ] && echo sim || echo não )"
check "a mensagem explica o motivo" "sim" \
  "$( printf '%s' "$SAIDA" | grep -q 'mesmo banco de produção' && echo sim || echo não )"
check "nunca chegou a restaurar (nenhum log de restauração)" "não" \
  "$( printf '%s' "$SAIDA" | grep -q 'restaurando o dump' && echo sim || echo não )"
check "a senha da URL de produção não vaza na saída" "não" \
  "$( printf '%s' "$SAIDA" | grep -q 'segredo' && echo sim || echo não )"

# (T18.3 §4) Um ensaio com a URL do banco descartável SEM nome de banco é recusado antes de
# qualquer restauração — e a recusa nomeia a variável, não a senha.
SAIDA_SEM_DB="$(
  DATABASE_URL='postgresql://spark:segredo@127.0.0.1:5432/spark' \
  SPARK_DRILL_DATABASE_URL='postgresql://spark:outrasenha@127.0.0.1:5432' \
  SPARK_BACKUP_ENV_FILE=/dev/null \
  "${OPS_DIR}/verify-backup.sh" 2>&1
)" && CODIGO_SEM_DB=0 || CODIGO_SEM_DB=$?
check "ensaio com URL sem database sai com código diferente de zero" "sim" "$( [ "$CODIGO_SEM_DB" != "0" ] && echo sim || echo não )"
check "a recusa nomeia SPARK_DRILL_DATABASE_URL" "sim" \
  "$( printf '%s' "$SAIDA_SEM_DB" | grep -q 'SPARK_DRILL_DATABASE_URL não declara o database' && echo sim || echo não )"
check "nunca chegou a restaurar" "não" \
  "$( printf '%s' "$SAIDA_SEM_DB" | grep -q 'restaurando o dump' && echo sim || echo não )"
check "nenhuma senha vaza" "não" \
  "$( printf '%s' "$SAIDA_SEM_DB" | grep -qE 'segredo|outrasenha' && echo sim || echo não )"

# O par permitido do enunciado, chamado de ponta a ponta: a checagem de identidade não pode ser o
# que bloqueia — o script segue adiante e falha por outro motivo (sem `SPARK_IMAGE`/backup real),
# nunca pela mensagem de "mesmo banco".
SAIDA_PERMITIDA="$(
  DATABASE_URL='postgresql://spark:segredo@127.0.0.1:5432/spark' \
  SPARK_DRILL_DATABASE_URL='postgresql://spark:segredo@127.0.0.1:5432/spark_drill' \
  SPARK_BACKUP_ENV_FILE=/dev/null \
  "${OPS_DIR}/verify-backup.sh" 2>&1
)" || true
check "spark x spark_drill não é barrado pela checagem de identidade" "não" \
  "$( printf '%s' "$SAIDA_PERMITIDA" | grep -q 'mesmo banco de produção' && echo sim || echo não )"

echo
if [ "$failures" -gt 0 ]; then
  printf '=== %d verificação(ões) falharam ===\n' "$failures" >&2
  exit 1
fi
echo "=== o ensaio de restauração identifica o banco de produção corretamente e falha antes de restaurar ==="
