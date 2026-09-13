#!/usr/bin/env bash
# Dublês de `gcloud`, `docker`, `git`, `gh` e `curl` para os testes offline de `ops/gcp/`
# (T18.2.1/T18.3).
#
# Lido com `source` pelos testes. `install_fakes <dir>` grava os cinco executáveis em <dir>; o
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
#   run revisions describe <rev> --format=value(metadata.labels."serving.knative.dev/service")
#                                                                o serviço dono (<rev> sem -00001-xyz);
#                                                                falha se <rev> em GCLOUD_MISSING;
#                                                                `--service` é recusado como no real
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

  # `git` fake. `-C <dir>` é aceito e ignorado (os testes não têm repositório de verdade).
  #   GIT_FETCH_FAILS=1        → `git fetch` falha (sem rede/credencial)
  #   GIT_NOT_ANCESTOR=1       → o commit NÃO está em origin/main
  cat > "${dir}/git" <<'FAKE_GIT'
#!/usr/bin/env bash
set -euo pipefail
[ "${1:-}" != "-C" ] || shift 2
case "${1:-}" in
  status) exit 0 ;;
  rev-parse)
    # `--short=N` (a tag da imagem) e o SHA completo (o portão de procedência, T18.3.2) precisam
    # ter tamanhos diferentes de propósito: é o que faz um dublê pegar o defeito real de passar o
    # curto para `gh run list --commit`, que só casa pelo SHA de 40 caracteres.
    case "${2:-}" in
      --short=*) printf 'abcdef123456\n' ;;
      *) printf '0123456789abcdef0123456789abcdef01234567\n' ;;
    esac
    ;;
  fetch) [ -z "${GIT_FETCH_FAILS:-}" ] || exit 1 ;;
  merge-base) [ -z "${GIT_NOT_ANCESTOR:-}" ] || exit 1 ;;
  diff) exit 0 ;;
esac
exit 0
FAKE_GIT

  # `gh`, para o portão de procedência do deploy (T18.3.2). `gh run list ... --jq` devolve a linha
  # já reduzida pelo `--jq` do gh real: `status:conclusion` por execução, separadas por espaço.
  #   GH_NO_RUN=1              → nenhuma execução para aquele commit
  #   GH_RUN_RESULT=...        → o que a consulta devolve (default: completed:success)
  #   GH_RUN_LIST_FAILS=1      → a consulta falha (gh não autenticado, sem rede)
  cat > "${dir}/gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail
[ -z "${GH_CALL_LOG:-}" ] || printf '%s\n' "$*" >> "${GH_CALL_LOG}"
if [ "${1:-}" = "run" ] && [ "${2:-}" = "list" ]; then
  [ -z "${GH_RUN_LIST_FAILS:-}" ] || exit 1
  # A API real do GitHub casa `--commit` pelo SHA **completo**: um valor mais curto nunca encontra
  # a execução, mesmo que ela exista — foi esse o defeito real (não coberto até este dublê aprender
  # a distinguir tamanho), e é o que a linha abaixo agora reproduz.
  commit="" prev=""
  for arg in "$@"; do
    [ "${prev}" != "--commit" ] || commit="${arg}"
    prev="${arg}"
  done
  if [ "${#commit}" -ne 40 ]; then
    printf '\n'; exit 0
  fi
  [ -z "${GH_NO_RUN:-}" ] || { printf '\n'; exit 0; }
  printf '%s\n' "${GH_RUN_RESULT:-completed:success}"
  exit 0
fi
exit 0
FAKE_GH

  cat > "${dir}/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
[ -z "${DOCKER_CALL_LOG:-}" ] || printf '%s\n' "$*" >> "${DOCKER_CALL_LOG}"
if [ "${1:-}" = "inspect" ]; then
  # Como o containerd real: a tag local também aparece em RepoDigests, sem registry — o deploy
  # precisa escolher a entrada do Artifact Registry, nunca `index 0`.
  printf 'spark-backend@sha256:0000000000000000000000000000000000000000000000000000000000000000\n'
  printf 'southamerica-east1-docker.pkg.dev/infra-project/spark/spark-backend@sha256:0000000000000000000000000000000000000000000000000000000000000000\n'
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

# `run revisions describe` não aceita --service — o gcloud real recusa, e o fake recusa igual
# (foi exatamente o que um ensaio real de rollback pegou; o fake permissivo tinha escondido).
if [ "${1:-}" = "run" ] && [ "${2:-}" = "revisions" ] && [ "${3:-}" = "describe" ]; then
  for arg in "$@"; do
    if [ "${arg}" = "--service" ] || [ "${arg#--service=}" != "${arg}" ]; then
      echo 'ERROR: (gcloud.run.revisions.describe) unrecognized arguments: --service' >&2
      exit 2
    fi
  done
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
    # o serviço dono de uma revision: `<serviço>-00001-xyz` → `<serviço>`; ausente = vazio + falha
    *serving.knative.dev/service*)
      for missing in ${GCLOUD_MISSING:-}; do
        [ "${name}" = "${missing}" ] && exit 1
      done
      printf '%s\n' "$(printf '%s' "${name}" | sed -E 's/-[0-9]{5}-[a-z0-9]+$//')"
      ;;
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

  chmod +x "${dir}/git" "${dir}/gh" "${dir}/docker" "${dir}/curl" "${dir}/gcloud"
}

# Dublê de `gcloud` dedicado a `ops/gcp/bootstrap-github-deploy.sh` (T18.3.2), isolado do dublê de
# `install_fakes` acima — nenhum teste existente chama esta função, então estendê-la aqui não muda
# o comportamento de nenhum dos outros dublês. Casa por SUBSTRING (grep -F) em vez de posição de
# argumento: o script real mistura `--project` antes e depois do subcomando (uma vez direto, outra
# via `resource_exists`, que o antepõe) — combinar por texto evita repetir esse detalhe aqui.
#
#   GCLOUD_PROJECT_MISSING=1        `projects describe` falha (projeto inexistente/sem acesso)
#   FAKE_PROJECT_NUMBER             `--format=value(projectNumber)` (default 965678405850)
#   GCLOUD_WIF_POOL_MISSING=1       pool ainda não existe
#   FAKE_WIF_POOL_STATE             estado do pool (default ACTIVE)
#   GCLOUD_WIF_PROVIDER_MISSING=1   provider ainda não existe
#   FAKE_WIF_ISSUER                 issuer do provider já existente (default: o esperado)
#   FAKE_WIF_CONDITION               attribute-condition do provider já existente (default: a esperada)
#   FAKE_WIF_PROVIDER_STATE          estado do provider (default ACTIVE)
#   GCLOUD_DEPLOYER_SA_MISSING=1     a service account de deploy ainda não existe
#   GCLOUD_DEPLOYER_HAS_KEY=1        `keys list --managed-by user` devolve uma chave (drift)
install_wif_fakes() {
  local dir="$1"
  mkdir -p "${dir}"

  cat > "${dir}/gcloud" <<'FAKE_WIF_GCLOUD'
#!/usr/bin/env bash
set -euo pipefail
ALL="$*"
printf '%s\n' "${ALL}" >> "${GCLOUD_CALL_LOG}"

matches() { printf '%s' "${ALL}" | grep -qF -- "$1"; }

for arg in "$@"; do
  if [ "${arg}" = "--data-file=-" ]; then cat > /dev/null; break; fi
done

if matches "projects describe"; then
  if matches "--format=value(projectNumber)"; then
    printf '%s\n' "${FAKE_PROJECT_NUMBER:-965678405850}"
    exit 0
  fi
  [ -z "${GCLOUD_PROJECT_MISSING:-}" ] || exit 1
  exit 0
fi

if matches "iam workload-identity-pools providers describe"; then
  if matches "--format=value(state)"; then
    printf '%s\n' "${FAKE_WIF_PROVIDER_STATE:-ACTIVE}"; exit 0
  fi
  if matches "--format=value(oidc.issuerUri)"; then
    printf '%s\n' "${FAKE_WIF_ISSUER:-https://token.actions.githubusercontent.com}"; exit 0
  fi
  if matches "--format=value(attributeCondition)"; then
    # O espaço à direita no default reproduz o que o GCP real devolveu no bootstrap real desta
    # tarefa (T18.3.2) — sem ele, este dublê não teria pego o falso DRIFT que `tr -s` sozinho
    # (sem aparar borda) produzia na comparação de compatibilidade.
    printf '%s\n' "${FAKE_WIF_CONDITION:-assertion.repository == 'IgorRibeiro98/workout' && assertion.ref == 'refs/heads/main' && assertion.environment == 'production' }"
    exit 0
  fi
  [ -z "${GCLOUD_WIF_PROVIDER_MISSING:-}" ] || exit 1
  exit 0
fi

if matches "iam workload-identity-pools providers create-oidc"; then exit 0; fi

if matches "iam workload-identity-pools describe"; then
  if matches "--format=value(state)"; then
    printf '%s\n' "${FAKE_WIF_POOL_STATE:-ACTIVE}"; exit 0
  fi
  [ -z "${GCLOUD_WIF_POOL_MISSING:-}" ] || exit 1
  exit 0
fi

if matches "iam workload-identity-pools create"; then exit 0; fi

if matches "iam service-accounts keys list"; then
  [ -z "${GCLOUD_DEPLOYER_HAS_KEY:-}" ] || printf 'projects/x/serviceAccounts/y/keys/deadbeef\n'
  exit 0
fi

if matches "iam service-accounts describe"; then
  [ -z "${GCLOUD_DEPLOYER_SA_MISSING:-}" ] || exit 1
  exit 0
fi

if matches "iam service-accounts create"; then exit 0; fi

if matches "services enable"; then exit 0; fi

# Qualquer add-iam-policy-binding (projeto, secret, SA, bucket, Artifact Registry) e qualquer outro
# comando não coberto acima: sucesso silencioso — só o log importa para as verificações do teste.
exit 0
FAKE_WIF_GCLOUD

  chmod +x "${dir}/gcloud"
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
