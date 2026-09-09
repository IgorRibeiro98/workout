#!/usr/bin/env bash
#
# Ensaio de restauração (T16.8 §43/§112/§161).
#
#   backup off-site ──▶ restauração ──▶ integrity_check ──▶ backend sobe sobre a cópia ──▶ ready
#                                    └─▶ mídia social restaurada e legível pelo processo
#
# ## Por que este script existe
#
# Um backup só está validado quando alguém o restaurou. "O job saiu com código 0" e "o arquivo
# apareceu no storage" não provam que o banco abre, que o schema está aplicado ou que a senha de
# criptografia que o operador tem é a que o repositório usa (§113). Este ensaio prova as quatro
# coisas de uma vez, e não toca em produção em momento nenhum.
#
# Sobe o backend real sobre a **cópia restaurada**, em uma porta separada, e exige
# `/health/ready` — é o mesmo processo de produção lendo o mesmo arquivo que uma recuperação
# de verdade produziria.
#
# Uso:
#   ops/verify-backup.sh                              # ensaia o snapshot mais recente do off-site
#   ops/verify-backup.sh --snapshot <id>
#   ops/verify-backup.sh --from-file /caminho/spark.db  # ensaio local, sem credencial de storage
#   ops/verify-backup.sh --from-file /caminho/spark.db --media-from /caminho/media

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib.sh
. "${SCRIPT_DIR}/lib.sh"

load_env_file

SNAPSHOT="latest"
FROM_FILE=""
FROM_MEDIA=""
PORT="${SPARK_DRILL_PORT:-18080}"
CONTAINER="spark-restore-drill-$$"

# A chave HMAC dos tombstones, para o container do ensaio (T17.13.1 §4).
#
# O ensaio sobe o backend em `NODE_ENV=production` de propósito — é a configuração real que
# precisa abrir o banco restaurado — e produção exige uma chave própria (T17.10 §136). Sem ela o
# container não subiria, e o ensaio acusaria "o backend não ficou ready sobre o banco restaurado":
# um backup perfeitamente válido reprovado por um erro de configuração do próprio ensaio.
#
# O default é **sintético e do ensaio**, e nunca de produção. Ele não precisa casar com a chave
# real: nada aqui calcula ou compara tombstone — o que se verifica é que o banco abre, as
# migrations aplicam e o `/health/ready` responde. O operador que quiser exercitar o ensaio com a
# chave real da VPS exporta `SPARK_ACCOUNT_DELETION_HMAC_KEY` antes de chamar este script.
: "${SPARK_ACCOUNT_DELETION_HMAC_KEY:=drill-only-account-deletion-hmac-not-for-production}"

while [ $# -gt 0 ]; do
  case "$1" in
    --snapshot)   SNAPSHOT="${2:?}"; shift 2 ;;
    --from-file)  FROM_FILE="${2:?}"; shift 2 ;;
    --media-from) FROM_MEDIA="${2:?}"; shift 2 ;;
    --port)       PORT="${2:?}"; shift 2 ;;
    *) fail "argumento desconhecido: $1" ;;
  esac
done

# Diretório de trabalho do ensaio. Configurável porque ele precisa ser um caminho **do host**
# quando o restic roda em container (`SPARK_RESTIC_CMD`): o alvo da restauração tem de estar
# montado, ou o restic escreve dentro de um container efêmero e o arquivo somem com ele.
DRILL_DIR="${SPARK_DRILL_DIR:-$(mktemp -d)}"
mkdir -p "$DRILL_DIR"
cleanup() {
  docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
  # A limpeza nunca pode transformar um ensaio aprovado em falha: quando o restic roda em
  # container, parte da árvore restaurada pode pertencer a outro usuário, e um `rm` que falha aí
  # não diz nada sobre a validade do backup. O resíduo é temporário e visível; o veredito é o que
  # importa.
  rm -rf "$DRILL_DIR" 2> /dev/null || log "aviso: não foi possível limpar ${DRILL_DIR}"
}
trap cleanup EXIT

log "=== ensaio de restauração ==="

# --- 1. restaurar e verificar integridade --------------------------------------------------
if [ -n "$FROM_FILE" ]; then
  MEDIA_ARGS=()
  [ -n "$FROM_MEDIA" ] && MEDIA_ARGS=(--media-from "$FROM_MEDIA")
  RESTORED="$("${SCRIPT_DIR}/restore.sh" --from-file "$FROM_FILE" "${MEDIA_ARGS[@]}" --to "${DRILL_DIR}/restored" | tail -1)"
else
  RESTORED="$("${SCRIPT_DIR}/restore.sh" --snapshot "$SNAPSHOT" --to "${DRILL_DIR}/restored" | tail -1)"
fi
[ -f "$RESTORED" ] || fail "a restauração não produziu um arquivo"

# A mídia restaurada, quando o snapshot a contém (T17.9 §138/§142). Procurada pelo subdiretório
# `checkins`, que é a estrutura que `LocalSocialMediaStore` cria — e não pelo nome do diretório,
# que é configurável e pode ter mudado entre o backup e a restauração.
RESTORED_MEDIA=""
MEDIA_ROOT_MARKER="$(find "${DRILL_DIR}/restored" -type d -name checkins 2> /dev/null | head -1)"
[ -n "$MEDIA_ROOT_MARKER" ] && RESTORED_MEDIA="$(dirname "$MEDIA_ROOT_MARKER")"

# --- 2. subir o backend real sobre a cópia -------------------------------------------------
#
# Um diretório separado, e uma cópia do arquivo: o container escreve `-wal` e `-shm`, e o ensaio
# não pode alterar o artefato que acabou de ser verificado.
DATA_DIR="${DRILL_DIR}/data"
mkdir -p "$DATA_DIR"

# O ensaio usa **o mesmo modelo de permissão da produção** (T16.8.1 §3), e não `777`/`666`.
#
# A versão anterior abria tudo para todo mundo. Além de ser o que a T16.8 proíbe em produção, isso
# tornava o ensaio inútil justamente na parte que mais quebra: um `777` passa com qualquer
# combinação de uid, inclusive as que a produção real não teria. O ensaio deixava de provar que o
# container consegue abrir o banco e passava a provar apenas que o container existe.
#
# Aqui: `2770` (setgid) no diretório, `660` no arquivo, dono é quem roda o script, e o container
# entra no grupo por `--group-add`. O uid do container (1000, `node`) e o do operador podem ser
# diferentes — e no CI **são**, que é exatamente o caso que precisava de cobertura.
DRILL_GID="$(id -g)"
chmod 2770 "$DATA_DIR"
cp "$RESTORED" "${DATA_DIR}/${DB_FILENAME}"
chmod 660 "${DATA_DIR}/${DB_FILENAME}"

# A mídia entra no ensaio com o **mesmo** modelo de permissão (T17.9 §142). O ponto do ensaio não é
# só "os arquivos vieram": é que o processo que roda como `node`, com uid diferente do operador,
# consegue **abri-los** depois da restauração. Um `chmod 777` aqui provaria apenas que o container
# existe.
MEDIA_DIR="${DRILL_DIR}/media"
mkdir -p "$MEDIA_DIR"
chmod 2770 "$MEDIA_DIR"
MEDIA_FILE_COUNT=0
if [ -n "$RESTORED_MEDIA" ]; then
  cp -a "${RESTORED_MEDIA}/." "$MEDIA_DIR/"
  find "$MEDIA_DIR" -type d -exec chmod 2770 {} +
  find "$MEDIA_DIR" -type f -exec chmod 660 {} +
  MEDIA_FILE_COUNT="$(find "$MEDIA_DIR" -type f | wc -l | tr -d ' ')"
  log "mídia restaurada para o ensaio: ${MEDIA_FILE_COUNT} arquivo(s)"
else
  log "aviso: o snapshot não trouxe mídia; o ensaio verifica apenas o banco"
fi

log "subindo o backend sobre a cópia restaurada (porta ${PORT}, grupo ${DRILL_GID}, uid do host $(id -u))"
docker run -d --name "$CONTAINER" \
  -p "127.0.0.1:${PORT}:8080" \
  --group-add "$DRILL_GID" \
  -v "${DATA_DIR}:/data" \
  -v "${MEDIA_DIR}:/media" \
  -e DATABASE_PATH="/data/${DB_FILENAME}" \
  -e SOCIAL_MEDIA_ROOT=/media \
  -e NODE_ENV=production \
  -e ACCOUNT_DELETION_HMAC_KEY="$SPARK_ACCOUNT_DELETION_HMAC_KEY" \
  -e LOG_LEVEL=warn \
  "$SPARK_IMAGE" > /dev/null

READY=0
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/health/ready" 2> /dev/null | grep -q '"status":"ok"'; then
    READY=1; break
  fi
  sleep 2
done

if [ "$READY" -ne 1 ]; then
  docker logs "$CONTAINER" >&2 || true
  fail "o backend não ficou ready sobre o banco restaurado"
fi

curl -fsS "http://127.0.0.1:${PORT}/health/live" | grep -q '"status":"ok"' || fail "liveness falhou"

# As rotas de dado precisam continuar **fechadas** num banco restaurado: 401, e não 404 nem 200.
for path in /v1/backups /v1/sync/pull; do
  status="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}${path}")"
  [ "$status" = "401" ] || fail "rota ${path} respondeu ${status} no banco restaurado; esperado 401"
done

# --- 3. a mídia restaurada é legível **pelo processo** (T17.9 §142) --------------------------
#
# O container roda como `node` (uid 1000) e o operador tem outro uid: este passo é o que prova que
# o modelo de grupo compartilhado sobreviveu à restauração. Ler o byte a byte de um arquivo pelo
# processo é a única evidência que vale — "o arquivo está lá" não diz que ele abre.
if [ "$MEDIA_FILE_COUNT" -gt 0 ]; then
  docker exec "$CONTAINER" node -e "
    const fs = require('node:fs');
    const path = require('node:path');
    const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
      const full = path.join(dir, entry.name);
      return entry.isDirectory() ? walk(full) : [full];
    });
    const files = walk('/media');
    if (files.length === 0) { console.error('nenhum arquivo de mídia visível'); process.exit(1); }
    let bytes = 0;
    for (const file of files) { bytes += fs.readFileSync(file).length; }
    // Só formato e tamanho: nenhum caminho de arquivo vai para a saída (§161).
    const head = fs.readFileSync(files[0]).subarray(8, 12).toString('ascii');
    if (head !== 'WEBP') { console.error('a mídia restaurada não é WebP'); process.exit(1); }
    console.log('mídia legível pelo processo: ' + files.length + ' arquivo(s), ' + bytes + ' bytes');
  " || fail "o backend não conseguiu ler a mídia restaurada"
else
  log "aviso: sem mídia no snapshot, a verificação de leitura foi pulada"
fi

log "=== ensaio APROVADO: restauração íntegra, backend ready, rotas protegidas, mídia legível ==="
