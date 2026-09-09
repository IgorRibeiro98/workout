#!/usr/bin/env bash
#
# O modelo de permissão host ↔ container funciona com uid diferente (T16.8.1 §3).
#
#   host: operador (uid arbitrário) ─┐
#                                    ├─ grupo compartilhado ─▶ /data (2770) + service account (640)
#   container: node (uid 1000)      ─┘
#
# ## O que este teste existe para impedir
#
# A T16.8 documentava `/opt/spark/data` como `1000:1000` e a service account como `spark:spark`
# `600`. Isso só funciona enquanto o `adduser spark` receber, por acaso, o uid 1000 — o que
# acontece numa VPS recém-criada e **não** acontece numa VPS onde já exista um usuário. Na
# segunda, uma das duas coisas quebra:
#
#   - o backend (uid 1000) não abre `/opt/spark/data` de `spark` (uid 1001), modo 700;
#   - ou o operador (uid 1001) não lê o `firebase-admin.json` de modo 600 do uid 1000.
#
# Nenhuma das duas aparece em revisão de código, e a segunda só aparece na primeira requisição
# autenticada. Uma configuração que funciona porque dois usuários receberam o mesmo uid não é uma
# configuração de produção.
#
# ## Como o desencontro é construído aqui
#
# O teste **não** depende do uid da máquina que o roda: ele cria a assimetria de propósito, dentro
# de um volume Docker, com um uid de operador declaradamente diferente de 1000. Roda igual num
# runner de CI (uid 1001) e numa máquina de desenvolvimento (uid 1000).
#
# Nenhuma credencial real: a service account é sintética, gerada aqui, com uma chave RSA
# descartável. Ela é estruturalmente válida para o Admin SDK e não existe em projeto nenhum.
#
# Uso:  ops/tests/permissions.test.sh

set -euo pipefail

: "${SPARK_IMAGE:?SPARK_IMAGE é obrigatório}"

# A chave HMAC dos tombstones de exclusão de conta (T17.13.1 §4).
#
# A imagem declara `ENV NODE_ENV=production`, e produção exige uma chave própria (T17.10 §136).
# Sem ela, os **quatro** containers deste teste morreriam no startup por esse motivo — e os dois
# controles negativos (§3 "sem group_add" e §4 "credencial ilegível") passariam sem provar nada:
# eles verificam que o container **não** está rodando, e um container que não sobe por falta de
# chave satisfaz essa condição pelo motivo errado. É esse falso verde que esta variável impede.
#
# Sintética e não secreta: o nome diz o que ela é, e nada aqui a distingue de um valor de teste.
: "${SPARK_ACCOUNT_DELETION_HMAC_KEY:=ci-only-account-deletion-hmac-not-for-production}"

# Deliberadamente diferentes de 1000 (o uid do `node` na imagem) e um do outro.
OPERATOR_UID=1234
OPERATOR_GID=1234
SHARED_GID=2345

VOLUME="spark-perm-$$"
PREFIX="spark-perm-$$"
PORT="${SPARK_PERM_TEST_PORT:-18091}"

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

cleanup() {
  docker rm -f "${PREFIX}-backend" "${PREFIX}-sem-grupo" "${PREFIX}-segredo-600" \
    > /dev/null 2>&1 || true
  docker volume rm "$VOLUME" > /dev/null 2>&1 || true
}
trap cleanup EXIT

docker volume create "$VOLUME" > /dev/null

echo "=== permissões host ↔ container com uid diferente ==="
echo "operador simulado: uid ${OPERATOR_UID}; container: uid 1000; grupo compartilhado: ${SHARED_GID}"

# --- provisionamento, como o documento manda ---------------------------------------------------
#
# `2770` no diretório de dados: setgid, para que o que o container criar herde o grupo
# compartilhado. `640` na service account, no mesmo grupo — e não `600` do dono.
docker run --rm --user 0 -v "${VOLUME}:/srv" --entrypoint sh "$SPARK_IMAGE" -c "
  set -e
  mkdir -p /srv/data /srv/secrets
  chown ${OPERATOR_UID}:${SHARED_GID} /srv/data /srv/secrets
  chmod 2770 /srv/data
  # 2750, e nao 750: o setgid tambem aqui. Sem ele, o arquivo de service account que o operador
  # criar nasce com o grupo PRIMARIO do operador, e nao com o grupo compartilhado -- e o container,
  # que entra pelo grupo compartilhado, nao consegue le-lo mesmo com o arquivo em 640.
  #
  # Este teste pegou exatamente esse erro na primeira execucao: o modelo estava certo no diretorio
  # de dados e errado no de segredos, e a diferenca so aparecia como EACCES no startup.
  chmod 2750 /srv/secrets
" > /dev/null

# A service account sintética é gerada **pelo operador**, como na VPS real.
docker run --rm --user "${OPERATOR_UID}:${OPERATOR_GID}" --group-add "$SHARED_GID" \
  -v "${VOLUME}:/srv" --entrypoint node "$SPARK_IMAGE" -e "
    const { generateKeyPairSync } = require('node:crypto');
    const { writeFileSync, chmodSync } = require('node:fs');
    const { privateKey } = generateKeyPairSync('rsa', {
      modulusLength: 2048,
      privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
      publicKeyEncoding: { type: 'spki', format: 'pem' },
    });
    writeFileSync('/srv/secrets/firebase-admin.json', JSON.stringify({
      type: 'service_account',
      project_id: 'ci-only-not-a-real-firebase-project',
      private_key_id: '0'.repeat(40),
      private_key: privateKey,
      client_email: 'ci\100ci-only-not-a-real-firebase-project.iam.gserviceaccount.com',
      client_id: '000000000000000000000',
      token_uri: 'https://oauth2.googleapis.com/token',
    }, null, 2));
    chmodSync('/srv/secrets/firebase-admin.json', 0o640);
  " > /dev/null

# `640` no grupo compartilhado, e não `600` do dono: é o que torna a credencial legível pelo
# container sem torná-la legível pela máquina inteira.
MODO_SEGREDO="$(docker run --rm --user 0 -v "${VOLUME}:/srv" --entrypoint stat "$SPARK_IMAGE" \
  -c %a /srv/secrets/firebase-admin.json)"
check "service account é 640" "640" "$MODO_SEGREDO"

# --- 1. o backend sobe com uid diferente do operador -------------------------------------------
#
# `REQUIRE_FIREBASE_ADMIN=true`: com a verificação de startup da T16.8.1 §8, uma credencial que o
# container não conseguisse ler **derrubaria** o processo. Chegar a `/health/ready` prova as duas
# coisas de uma vez — o banco abre e a service account é legível.
echo "[1/4] backend sobe no grupo compartilhado"
docker run -d --name "${PREFIX}-backend" \
  --group-add "$SHARED_GID" \
  -p "127.0.0.1:${PORT}:8080" \
  -v "${VOLUME}:/srv" \
  -e DATABASE_PATH=/srv/data/spark.db \
  -e NODE_ENV=production \
  -e ACCOUNT_DELETION_HMAC_KEY="$SPARK_ACCOUNT_DELETION_HMAC_KEY" \
  -e LOG_LEVEL=warn \
  -e REQUIRE_FIREBASE_ADMIN=true \
  -e GOOGLE_APPLICATION_CREDENTIALS=/srv/secrets/firebase-admin.json \
  "$SPARK_IMAGE" > /dev/null

pronto=nao
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/health/ready" 2> /dev/null | grep -q '"status":"ok"'; then
    pronto=sim; break
  fi
  sleep 2
done
if [ "$pronto" != "sim" ]; then
  docker logs "${PREFIX}-backend" >&2 || true
fi
check "backend fica ready (abre o banco e lê a credencial)" "sim" "$pronto"

UID_CONTAINER="$(docker exec "${PREFIX}-backend" id -u 2> /dev/null || echo desconhecido)"
check "o container não roda como root" "sim" \
  "$( [ "$UID_CONTAINER" != "0" ] && echo sim || echo não )"
check "o uid do container difere do operador" "sim" \
  "$( [ "$UID_CONTAINER" != "$OPERATOR_UID" ] && echo sim || echo não )"

# --- 2. o operador alcança o que o container escreveu ------------------------------------------
#
# É o que `ops/snapshot.sh` e `ops/backup.sh` precisam: ler o arquivo que o container criou e
# removê-lo do diretório de dados depois de copiá-lo.
echo "[2/4] o operador lê e move o que o container criou"
OPERADOR_OK="$(docker run --rm --user "${OPERATOR_UID}:${OPERATOR_GID}" --group-add "$SHARED_GID" \
  -v "${VOLUME}:/srv" --entrypoint sh "$SPARK_IMAGE" -c '
    set -e
    # ler o banco que o container criou
    head -c 16 /srv/data/spark.db > /dev/null
    # criar, copiar e remover no diretório de dados — o ciclo do snapshot
    printf "snapshot" > /srv/data/.teste-snapshot
    install -m 600 /srv/data/.teste-snapshot /tmp/recolhido
    rm -f /srv/data/.teste-snapshot
    echo sim
  ' 2> /dev/null || echo nao)"
check "o operador lê o banco e recolhe um snapshot" "sim" "$OPERADOR_OK"

# --- 3. controle negativo: sem o grupo, não há acesso ------------------------------------------
#
# Sem isto, o teste passaria mesmo se as permissões fossem `777` — e provaria apenas que o
# container existe. É esta verificação que impede a "solução" proibida.
echo "[3/4] controle negativo: sem o grupo compartilhado, não sobe"
docker run -d --name "${PREFIX}-sem-grupo" \
  -v "${VOLUME}:/srv" \
  -e DATABASE_PATH=/srv/data/spark.db \
  -e NODE_ENV=production \
  -e ACCOUNT_DELETION_HMAC_KEY="$SPARK_ACCOUNT_DELETION_HMAC_KEY" \
  -e LOG_LEVEL=warn \
  "$SPARK_IMAGE" > /dev/null 2>&1 || true
sleep 6
SEM_GRUPO_RODANDO="$(docker inspect -f '{{.State.Running}}' "${PREFIX}-sem-grupo" 2> /dev/null || echo false)"
check "sem group_add o backend não serve (as permissões não são abertas)" "false" "$SEM_GRUPO_RODANDO"

# --- 4. controle negativo: credencial obrigatória ilegível derruba o startup --------------------
#
# T16.8.1 §8 encontrando T16.8.1 §3: a service account em `600` do operador é exatamente a
# configuração que a T16.8 documentava, e ela é ilegível para o container. Antes, o processo subia
# e respondia `503` em toda requisição autenticada; agora ele não sobe.
echo "[4/4] controle negativo: service account ilegível impede o startup"
docker run --rm --user 0 -v "${VOLUME}:/srv" --entrypoint chmod "$SPARK_IMAGE" \
  600 /srv/secrets/firebase-admin.json > /dev/null

docker run -d --name "${PREFIX}-segredo-600" \
  --group-add "$SHARED_GID" \
  -v "${VOLUME}:/srv" \
  -e DATABASE_PATH=/srv/data/spark.db \
  -e NODE_ENV=production \
  -e ACCOUNT_DELETION_HMAC_KEY="$SPARK_ACCOUNT_DELETION_HMAC_KEY" \
  -e LOG_LEVEL=warn \
  -e REQUIRE_FIREBASE_ADMIN=true \
  -e GOOGLE_APPLICATION_CREDENTIALS=/srv/secrets/firebase-admin.json \
  "$SPARK_IMAGE" > /dev/null 2>&1 || true
sleep 6
SEGREDO_RODANDO="$(docker inspect -f '{{.State.Running}}' "${PREFIX}-segredo-600" 2> /dev/null || echo false)"
check "credencial obrigatória ilegível derruba o processo" "false" "$SEGREDO_RODANDO"

SAIDA_SEGREDO="$(docker logs "${PREFIX}-segredo-600" 2>&1 || true)"
check "o motivo aparece no log de saída" "sim" \
  "$( printf '%s' "$SAIDA_SEGREDO" | grep -q 'REQUIRE_FIREBASE_ADMIN' && echo sim || echo não )"
check "o motivo não expõe o conteúdo da credencial" "não" \
  "$( printf '%s' "$SAIDA_SEGREDO" | grep -q 'BEGIN PRIVATE KEY' && echo sim || echo não )"

echo
if [ "$failures" -gt 0 ]; then
  printf '=== %d verificação(ões) de permissão falharam ===\n' "$failures" >&2
  exit 1
fi
echo "=== o modelo de grupo compartilhado funciona com uid diferente, sem 777 e sem root ==="
