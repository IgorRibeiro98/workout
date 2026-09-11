#!/usr/bin/env bash
# Dublês de `gcloud`, `docker`, `git` e `curl` para os testes offline de `ops/gcp/` (T18.2.1/T18.3).
#
# Lido com `source` pelos testes. `install_fakes <dir>` grava os quatro executáveis em <dir>; o
# teste prefixa o PATH com ele. Nenhum fake fala com rede, projeto ou daemon real.
#
# ## O que o gcloud fake sabe fazer
#
# Grava cada chamada (args separados por espaço) em `$GCLOUD_CALL_LOG` e responde:
#
#   projects describe X                 falha só se X == GCLOUD_FAIL_PROJECT
#   ... describe <nome> (sem --format)   falha se <nome> está em GCLOUD_MISSING (lista separada por
#                                        espaço) — é a checagem de existência (resource_exists);
#                                        com GCLOUD_DESCRIBE_FAILS_ALL=1 todo describe falha (o
#                                        caminho "não existe, criar" do bootstrap)
#   ... describe <nome> --format=value(status.url)              https://<nome>-abc123-uc.a.run.app
#   ... describe <nome> --format=value(status.traffic[0]...)    <nome>-00001-xyz
#   ... describe <nome> --format=value(spec.template...image)   fake.registry/img@sha256:<zeros>
#   secrets versions list <secret> ...                           GCLOUD_SECRET_VERSION (default 3),
#                                                                vazio se <secret> em GCLOUD_SECRET_NO_VERSION
#   storage ls gs://<bucket>/<prefixo>/                          um "diretório" por id em FAKE_DR_BACKUPS
#                                                                (+ os criados por `run jobs execute <backup job>`)
#   storage cat .../<id>/manifest.json                           manifesto com createdAtEpochMs de FAKE_DR_CREATED_MS
#                                                                (ou "agora" para ids criados pelo fake execute)
#   storage objects describe .../database.dump --format=value(size)   100
#   run jobs execute <job> --wait                                falha se <job> em GCLOUD_JOB_EXECUTE_FAILS;
#                                                                para o job de backup, registra um backup
#                                                                novo "agora" (salvo GCLOUD_BACKUP_JOB_NO_EFFECT=1)
#   auth print-identity-token                                    fake-identity-token
#   --data-file=-                                                drena stdin (nunca fecha o pipe cedo)
#   qualquer outra coisa                                         sucesso silencioso
#
# `curl` responde 200 para /health/*, 401 para o resto (500 para tudo com CURL_FAIL_HEALTH=1) e
# grava os argumentos em `$CURL_CALL_LOG` quando definido — é como o teste vê os headers.

set -euo pipefail

install_fakes() {
  local dir="$1"
  mkdir -p "${dir}"

  cat > "${dir}/git" <<'FAKE_GIT'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  status) exit 0 ;;
  rev-parse) printf 'abcdef123456\n' ;;
esac
exit 0
FAKE_GIT

  cat > "${dir}/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
[ -z "${DOCKER_CALL_LOG:-}" ] || printf '%s\n' "$*" >> "${DOCKER_CALL_LOG}"
if [ "${1:-}" = "inspect" ]; then
  printf 'fake.registry/spark-backend@sha256:0000000000000000000000000000000000000000000000000000000000000000\n'
fi
exit 0
FAKE_DOCKER

  cat > "${dir}/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
[ -z "${CURL_CALL_LOG:-}" ] || printf '%s\n' "$*" >> "${CURL_CALL_LOG}"
url="${!#}"
if [ -n "${CURL_FAIL_HEALTH:-}" ]; then
  printf '500'
  exit 0
fi
case "${url}" in
  *"/health/live") printf '200' ;;
  *"/health/ready") printf '200' ;;
  *) printf '401' ;;
esac
FAKE_CURL

  cat > "${dir}/gcloud" <<'FAKE_GCLOUD'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GCLOUD_CALL_LOG}"

for arg in "$@"; do
  if [ "${arg}" = "--data-file=-" ]; then
    cat > /dev/null
    break
  fi
done

STATE_DIR="${GCLOUD_STATE_DIR:-${GCLOUD_CALL_LOG}.state}"
mkdir -p "${STATE_DIR}"

if [ "${1:-}" = "auth" ] && [ "${2:-}" = "print-identity-token" ]; then
  printf 'fake-identity-token\n'
  exit 0
fi

if [ "${1:-}" = "projects" ] && [ "${2:-}" = "describe" ]; then
  [ "${3:-}" = "${GCLOUD_FAIL_PROJECT:-}" ] && exit 1
  exit 0
fi

if [ "${1:-}" = "secrets" ] && [ "${2:-}" = "versions" ] && [ "${3:-}" = "list" ]; then
  secret="${4:-}"
  for missing in ${GCLOUD_SECRET_NO_VERSION:-}; do
    [ "${secret}" = "${missing}" ] && exit 0
  done
  printf '%s\n' "${GCLOUD_SECRET_VERSION:-3}"
  exit 0
fi

now_ms() { printf '%s' "$(( $(date +%s) * 1000 ))"; }

if [ "${1:-}" = "storage" ]; then
  case "${2:-}" in
    ls)
      target="${3:-}"
      for id in ${FAKE_DR_BACKUPS:-}; do
        printf '%s%s/\n' "${target}" "${id}"
      done
      if [ -f "${STATE_DIR}/backups" ]; then
        while IFS= read -r id; do
          [ -n "${id}" ] && printf '%s%s/\n' "${target}" "${id}"
        done < "${STATE_DIR}/backups"
      fi
      exit 0
      ;;
    cat)
      path="${3:-}"
      id="$(printf '%s' "${path}" | sed 's#.*/\([^/]*\)/manifest.json$#\1#')"
      created="${FAKE_DR_CREATED_MS:-$(now_ms)}"
      if [ -f "${STATE_DIR}/backups" ] && grep -qx "${id}" "${STATE_DIR}/backups"; then
        created="$(cat "${STATE_DIR}/created-${id}")"
      fi
      printf '{"formatVersion":1,"backupId":"%s","createdAtEpochMs":%s,"dumpSizeBytes":100,"sha256":"%s","schema":{"schemaVersion":2},"postgresVersion":"PostgreSQL 17","gitCommit":"abcdef123456"}\n' \
        "${id}" "${created}" "$(printf 'a%.0s' $(seq 1 64))"
      exit 0
      ;;
    objects)
      printf '100\n'
      exit 0
      ;;
  esac
  exit 0
fi

if [ "${1:-}" = "run" ] && [ "${2:-}" = "jobs" ] && [ "${3:-}" = "execute" ]; then
  job="${4:-}"
  for failing in ${GCLOUD_JOB_EXECUTE_FAILS:-}; do
    [ "${job}" = "${failing}" ] && exit 1
  done
  if [ "${job}" = "${FAKE_BACKUP_JOB_NAME:-spark-db-backup}" ] && [ -z "${GCLOUD_BACKUP_JOB_NO_EFFECT:-}" ]; then
    id="$(date -u +%Y-%m-%dT%H%M%SZ)"
    printf '%s\n' "${id}" >> "${STATE_DIR}/backups"
    now_ms > "${STATE_DIR}/created-${id}"
  fi
  exit 0
fi

is_describe=0
has_format=0
format_value=""
name=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[$i]}" in
    describe)
      is_describe=1
      name="${args[$((i + 1))]:-}"
      ;;
    --format=*)
      has_format=1
      format_value="${args[$i]#--format=}"
      ;;
    --format)
      has_format=1
      format_value="${args[$((i + 1))]:-}"
      ;;
  esac
done

if [ "${is_describe}" -eq 1 ] && [ "${has_format}" -eq 0 ]; then
  [ -n "${GCLOUD_DESCRIBE_FAILS_ALL:-}" ] && exit 1
  for missing in ${GCLOUD_MISSING:-} ${GCLOUD_MISSING_SERVICE:-}; do
    [ "${name}" = "${missing}" ] && exit 1
  done
  exit 0
fi

FAKE_DIGEST='sha256:0000000000000000000000000000000000000000000000000000000000000000'

if [ "${is_describe}" -eq 1 ] && [ "${has_format}" -eq 1 ]; then
  case "${format_value}" in
    *status.url*) printf 'https://%s-abc123-uc.a.run.app\n' "${name}" ;;
    *status.traffic*) printf '%s-00001-xyz\n' "${name}" ;;
    *containers*image*) printf 'fake.registry/spark-backend@%s\n' "${FAKE_DIGEST}" ;;
    *imageDigest*) printf 'fake.registry/spark-backend@%s\n' "${FAKE_DIGEST}" ;;
    *lifecycleState*) printf 'ACTIVE\n' ;;
    *cleanupPolicies*) printf '{"spark-keep-recent-releases": {"action": "KEEP"}}\n' ;;
  esac
  exit 0
fi

# `artifacts docker images list ... --format=value(version)`: as versões (digests) do repositório.
if [ "${1:-}" = "artifacts" ] && [ "${2:-}" = "docker" ] && [ "${3:-}" = "images" ] && [ "${4:-}" = "list" ]; then
  case "${format_value}" in
    *version*) printf '%s\n' "${FAKE_DIGEST}" ;;
  esac
  exit 0
fi

exit 0
FAKE_GCLOUD

  chmod +x "${dir}/git" "${dir}/docker" "${dir}/curl" "${dir}/gcloud"
}

# Uma verificação com contagem de falhas — o mesmo `check` dos outros testes de ops/.
fakes_failures=0
check() {
  local descricao="$1" esperado="$2" obtido="$3"
  if [ "$esperado" = "$obtido" ]; then
    printf '  ok    %s\n' "$descricao"
  else
    printf '  FALHA %s (esperado "%s", obtido "%s")\n' "$descricao" "$esperado" "$obtido" >&2
    fakes_failures=$((fakes_failures + 1))
  fi
}

finish_checks() {
  local title="$1"
  echo
  if [ "${fakes_failures}" -gt 0 ]; then
    printf '=== %d verificação(ões) falharam ===\n' "${fakes_failures}" >&2
    exit 1
  fi
  printf '=== %s ===\n' "${title}"
}
