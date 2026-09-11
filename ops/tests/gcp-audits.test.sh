#!/usr/bin/env bash
#
# As três auditorias da T18.3 (§16/§18/§25) provadas sem GCP: `config-drift-audit.sh`,
# `iam-audit.sh` e `cost-audit.sh` leem um `gcloud` fake que devolve FIXTURES (arquivos nomeados
# pelos argumentos do comando). Um cenário "tudo certo" precisa dar PASS; cada drift injetado
# precisa virar DRIFT; uma API/permissão ausente precisa virar NOT_VERIFIED — nunca PASS por
# omissão. E nenhuma das três emite um comando que altera alguma coisa.
#
# Uso: ops/tests/gcp-audits.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
FAKE_BIN_DIR="${WORK}/bin"
FIXTURES="${WORK}/fixtures"
mkdir -p "${FAKE_BIN_DIR}" "${FIXTURES}"

# O gcloud fake das auditorias: a chave é a linha de comando sem `--project/--region/--location`,
# com tudo que não é [A-Za-z0-9.] trocado por `_`. Sem fixture → exit 1 (a auditoria trata como
# "não lido"). Cada chamada é registrada em GCLOUD_CALL_LOG, para provar que nada é alterado.
cat > "${FAKE_BIN_DIR}/gcloud" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GCLOUD_CALL_LOG}"
key="$(printf '%s ' "$@" | sed -E 's/--(project|region|location)(=| )[^ ]* //g; s/ $//' | tr -c 'A-Za-z0-9.\n' '_')"
file="${FIXTURES}/${key}"
if [ -f "${file}" ]; then
  cat "${file}"
  exit 0
fi
exit 1
FAKE
chmod +x "${FAKE_BIN_DIR}/gcloud"

# fxfile <args...> — o caminho da fixture de uma chamada (mesma normalização do fake).
fxfile() {
  local key
  key="$(printf '%s ' "$@" | sed -E 's/--(project|region|location)(=| )[^ ]* //g; s/ $//' | tr -c 'A-Za-z0-9.\n' '_')"
  printf '%s/%s' "${FIXTURES}" "${key}"
}
# fx <args...> — grava stdin como fixture da chamada com esses argumentos.
fx() { cat > "$(fxfile "$@")"; }
# mutate <jq-filter> <args...> — reescreve uma fixture JSON com um filtro jq.
mutate() {
  local filter="$1"; shift
  local file
  file="$(fxfile "$@")"
  jq "${filter}" "${file}" > "${file}.tmp" && mv "${file}.tmp" "${file}"
}

P=infra-project
F=firebase-project
RUNTIME="spark-backend-runtime@${P}.iam.gserviceaccount.com"
MIGRATOR="spark-backend-migrator@${P}.iam.gserviceaccount.com"
SCHEDULER="spark-maintenance-scheduler@${P}.iam.gserviceaccount.com"
BACKUP="spark-backend-backup@${P}.iam.gserviceaccount.com"
DIGEST="sha256:bfe4d582edb73532e062c90b41c4574d2cf6d86d5d02c54d74ae809d787d527c"
IMAGE="southamerica-east1-docker.pkg.dev/${P}/spark/spark-backend@${DIGEST}"

# ---------------------------------------------------------------- fixtures do cenário PASS
build_pass_fixtures() {
  [ -n "${FIXTURES}" ] && [ -d "${FIXTURES}" ] && rm -rf "${FIXTURES}"
  mkdir -p "${FIXTURES}"
  for project in "${P}" "${F}"; do
    printf 'ACTIVE\n' | fx projects describe "${project}" --format=value\(lifecycleState\)
  done
  for sa in "${RUNTIME}" "${MIGRATOR}" "${SCHEDULER}" "${BACKUP}"; do
    printf 'False\n' | fx iam service-accounts describe "${sa}" --format=value\(disabled\)
    printf '' | fx iam service-accounts keys list --iam-account "${sa}" --managed-by user --format=value\(name\)
  done
  for secret in spark-database-url spark-database-url-direct spark-gemini-api-key spark-account-deletion-hmac-key; do
    printf '3\n' | fx secrets versions list "${secret}" --filter=state:enabled --sort-by=~createTime --limit=1 --format=value\(name.basename\(\)\)
  done

  service_json() {
    # service_json <nome> <ai_enabled> <concurrency> <timeout|null>
    local name="$1" ai="$2" conc="$3" timeout="$4"
    jq -n --arg name "${name}" --arg image "${IMAGE}" --arg sa "${RUNTIME}" --arg ai "${ai}" --argjson conc "${conc}" --argjson timeout "${timeout}" --arg p "${P}" --arg f "${F}" '{
      metadata: { name: $name },
      spec: { template: {
        metadata: { annotations: { "autoscaling.knative.dev/maxScale": "1" } },
        spec: { serviceAccountName: $sa, containerConcurrency: $conc, timeoutSeconds: $timeout, containers: [{
          image: $image, resources: { limits: { cpu: "1", memory: "512Mi" } },
          env: [
            {name:"NODE_ENV",value:"production"},{name:"DATABASE_MIGRATION_MODE",value:"verify"},
            {name:"OBJECT_STORAGE_PROVIDER",value:"gcs"},{name:"GCS_BUCKET_NAME",value:"spark-private-assets-prod"},
            {name:"REQUIRE_FIREBASE_ADMIN",value:"true"},{name:"FIREBASE_ADMIN_CREDENTIAL_MODE",value:"adc"},
            {name:"FIREBASE_PROJECT_ID",value:$f},{name:"AI_ENABLED",value:$ai},{name:"REQUIRE_GEMINI",value:"false"},
            {name:"SYNC_WRITE_ENABLED",value:"true"},{name:"MAINTENANCE_MODE",value:"false"},
            {name:"BACKGROUND_JOBS_MODE",value:"disabled"},{name:"SOCIAL_PUSH_ENABLED",value:"false"},
            {name:"DATABASE_POOL_MIN",value:"0"},{name:"DATABASE_POOL_MAX",value:"5"},
            {name:"DATABASE_URL",valueFrom:{secretKeyRef:{name:"spark-database-url",key:"3"}}},
            {name:"GEMINI_API_KEY",valueFrom:{secretKeyRef:{name:"spark-gemini-api-key",key:"3"}}},
            {name:"ACCOUNT_DELETION_HMAC_KEY",valueFrom:{secretKeyRef:{name:"spark-account-deletion-hmac-key",key:"3"}}}
          ]
        }]}
      }},
      status: { latestReadyRevisionName: ($name + "-00001-abc"), traffic: [{ revisionName: ($name + "-00001-abc"), percent: 100 }] }
    }'
  }
  service_json spark-backend true 20 180 | fx run services describe spark-backend --format=json
  service_json spark-maintenance false 1 300 | fx run services describe spark-maintenance --format=json
  printf '{"bindings":[{"role":"roles/run.invoker","members":["allUsers"]}]}\n' | fx run services get-iam-policy spark-backend --format=json
  printf '{"bindings":[{"role":"roles/run.invoker","members":["serviceAccount:%s"]}]}\n' "${SCHEDULER}" | fx run services get-iam-policy spark-maintenance --format=json

  job_json() {
    # job_json <sa> <args> <env-json>
    jq -n --arg image "${IMAGE}" --arg sa "$1" --arg args "$2" --argjson env "$3" '{
      spec: { template: { spec: { template: { spec: { serviceAccountName: $sa, containers: [{ image: $image, args: [$args], env: $env }] } } } } }
    }'
  }
  job_json "${MIGRATOR}" dist/cli/migrate-database.js '[{"name":"DATABASE_URL_DIRECT","valueFrom":{"secretKeyRef":{"name":"spark-database-url-direct","key":"3"}}}]' \
    | fx run jobs describe spark-db-migrate --format=json
  job_json "${BACKUP}" dist/cli/db-backup.js '[{"name":"DATABASE_URL_DIRECT","valueFrom":{"secretKeyRef":{"name":"spark-database-url-direct","key":"3"}}},{"name":"OBJECT_STORAGE_PROVIDER","value":"gcs"},{"name":"GCS_BUCKET_NAME","value":"spark-private-assets-prod"},{"name":"SPARK_DR_RETENTION_COUNT","value":"7"}]' \
    | fx run jobs describe spark-db-backup --format=json
  job_json "${RUNTIME}" dist/cli/storage-audit.js '[{"name":"DATABASE_URL","valueFrom":{"secretKeyRef":{"name":"spark-database-url","key":"3"}}},{"name":"OBJECT_STORAGE_PROVIDER","value":"gcs"},{"name":"GCS_BUCKET_NAME","value":"spark-private-assets-prod"},{"name":"DATABASE_MIGRATION_MODE","value":"verify"}]' \
    | fx run jobs describe spark-storage-audit --format=json
  printf '{"bindings":[{"role":"roles/run.invoker","members":["serviceAccount:%s"]}]}\n' "${SCHEDULER}" | fx run jobs get-iam-policy spark-db-backup --format=json

  jq -n --arg sa "${SCHEDULER}" '{schedule:"* * * * *",state:"ENABLED",httpTarget:{uri:"https://spark-maintenance-abc.a.run.app/internal/maintenance/run",oidcToken:{serviceAccountEmail:$sa}}}' \
    | fx scheduler jobs describe spark-maintenance-cycle --format=json
  jq -n --arg sa "${SCHEDULER}" --arg p "${P}" '{schedule:"15 3 * * *",state:"ENABLED",httpTarget:{uri:("https://southamerica-east1-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/" + $p + "/jobs/spark-db-backup:run"),oauthToken:{serviceAccountEmail:$sa}}}' \
    | fx scheduler jobs describe spark-db-backup-daily --format=json

  printf '{"location":"SOUTHAMERICA-EAST1","public_access_prevention":"enforced","uniform_bucket_level_access":true,"soft_delete_policy":{"retentionDurationSeconds":"604800"}}\n' \
    | fx storage buckets describe gs://spark-private-assets-prod --format=json
  printf '{"format":"DOCKER","cleanupPolicies":{"spark-keep-recent-releases":{"action":"KEEP"}},"sizeBytes":"125000000"}\n' \
    | fx artifacts repositories describe spark --format=json
  printf '125000000\n' | fx artifacts repositories describe spark --format=value\(sizeBytes\)

  # IAM (iam-audit.sh)
  jq -n --arg r "${RUNTIME}" '{bindings:[{role:"roles/owner",members:["user:operador@example.com"]}]}' | fx projects get-iam-policy "${P}" --format=json
  jq -n --arg r "${RUNTIME}" '{bindings:[{role:"roles/firebaseauth.admin",members:[("serviceAccount:"+$r)]},{role:"roles/firebasecloudmessaging.admin",members:[("serviceAccount:"+$r)]}]}' | fx projects get-iam-policy "${F}" --format=json
  for secret in spark-database-url spark-gemini-api-key spark-account-deletion-hmac-key; do
    jq -n --arg r "${RUNTIME}" '{bindings:[{role:"roles/secretmanager.secretAccessor",members:[("serviceAccount:"+$r)]}]}' | fx secrets get-iam-policy "${secret}" --format=json
  done
  jq -n --arg m "${MIGRATOR}" --arg b "${BACKUP}" '{bindings:[{role:"roles/secretmanager.secretAccessor",members:[("serviceAccount:"+$b),("serviceAccount:"+$m)]}]}' | fx secrets get-iam-policy spark-database-url-direct --format=json
  jq -n --arg r "${RUNTIME}" --arg b "${BACKUP}" '{bindings:[
      {role:"roles/storage.objectAdmin",members:[("serviceAccount:"+$r)]},
      {role:"roles/storage.legacyBucketReader",members:[("serviceAccount:"+$b)]},
      {role:"roles/storage.objectAdmin",members:[("serviceAccount:"+$b)],condition:{title:"spark-dr-prefix-only",expression:"resource.name.startsWith(\"projects/_/buckets/spark-private-assets-prod/objects/system/dr/postgres/\")"}},
      {role:"roles/storage.legacyBucketOwner",members:["projectOwner:infra-project"]}
    ]}' | fx storage buckets get-iam-policy gs://spark-private-assets-prod --format=json

  # cost-audit.sh
  printf '{"billingEnabled":true,"billingAccountName":"billingAccounts/ABC-123"}\n' | fx billing projects describe "${P}" --format=json
  printf '[{"displayName":"spark-mensal","amount":{"specifiedAmount":{"units":"20","currencyCode":"BRL"}}}]\n' | fx billing budgets list --billing-account=ABC-123 --format=json
  jq -n '[{metadata:{name:"spark-backend"},spec:{template:{metadata:{annotations:{"autoscaling.knative.dev/maxScale":"1"}}}}},{metadata:{name:"spark-maintenance"},spec:{template:{metadata:{annotations:{"autoscaling.knative.dev/maxScale":"1"}}}}}]' \
    | fx run services list --format=json
  jq -n '[{metadata:{name:"spark-db-backup"}},{metadata:{name:"spark-db-migrate"}},{metadata:{name:"spark-storage-audit"}}]' | fx run jobs list --format=json
  printf 'southamerica-east1\n' | fx run services list --format=value\(metadata.labels.\"cloud.googleapis.com/location\"\)
  printf 'spark-db-backup-daily\nspark-maintenance-cycle\n' | fx scheduler jobs list --format=value\(name.basename\(\)\)
  printf 'spark-private-assets-prod\n' | fx storage buckets list --format=value\(name\)
  printf 'spark\n' | fx artifacts repositories list --format=value\(name.basename\(\)\)
  printf 'run.googleapis.com\nartifactregistry.googleapis.com\nsecretmanager.googleapis.com\ncloudscheduler.googleapis.com\nstorage.googleapis.com\nlogging.googleapis.com\nmonitoring.googleapis.com\n' \
    | fx services list --enabled --format=value\(config.name\)
}

GCLOUD_CALL_LOG="${WORK}/calls.log"
export GCLOUD_CALL_LOG FIXTURES
run_audit() {
  # run_audit <script>  → imprime "código|última linha de stdout"
  local script="$1" code=0
  : > "${GCLOUD_CALL_LOG}"
  PATH="${FAKE_BIN_DIR}:${PATH}" SPARK_GCP_PROJECT="${P}" SPARK_FIREBASE_PROJECT="${F}" SPARK_GCP_REGION=southamerica-east1 \
    "${OPS_DIR}/gcp/${script}" > "${WORK}/audit.out" 2> "${WORK}/audit.err" || code=$?
  printf '%s|%s' "${code}" "$(tail -1 "${WORK}/audit.out")"
}
mutating_calls() { grep -cE '(add-iam-policy-binding|remove-iam-policy-binding|update|delete|create|deploy|execute|set-cleanup-policies|enable) ' "${GCLOUD_CALL_LOG}" || true; }

echo "=== cenário PASS: as três auditorias ==="
build_pass_fixtures
for script in config-drift-audit.sh iam-audit.sh cost-audit.sh; do
  RESULT="$(run_audit "${script}")"
  check "${script} → PASS (código 0)" "0|${script%.sh}: PASS" "${RESULT}"
  check "${script} não emite comando que altera algo" "0" "$(mutating_calls)"
  if grep -qE '^  (DRIFT|NOT_VERIFIED) ' "${WORK}/audit.err"; then
    printf '  (detalhe) %s\n' "$(grep -E '^  (DRIFT|NOT_VERIFIED) ' "${WORK}/audit.err" | head -5)" >&2
  fi
done

echo
echo "=== drift: secret referenciado como :latest ==="
build_pass_fixtures
mutate '(.spec.template.spec.containers[0].env[] | select(.name == "DATABASE_URL") | .valueFrom.secretKeyRef.key) = "latest"' \
  run services describe spark-backend --format=json
RESULT="$(run_audit config-drift-audit.sh)"
check "config-drift-audit → DRIFT (código 1)" "1|config-drift-audit: DRIFT" "${RESULT}"
check "...apontando a versão não pinada" "sim" "$(grep -q 'spark-database-url:latest — versão não pinada' "${WORK}/audit.err" && echo sim || echo não)"

echo
echo "=== drift: max-instances subiu para 2 ==="
build_pass_fixtures
mutate '.spec.template.metadata.annotations["autoscaling.knative.dev/maxScale"] = "2"' \
  run services describe spark-backend --format=json
RESULT="$(run_audit config-drift-audit.sh)"
check "config-drift-audit → DRIFT" "1|config-drift-audit: DRIFT" "${RESULT}"
check "...apontando maxScale" "sim" "$(grep -q "spark-backend maxScale: esperado '1', real '2'" "${WORK}/audit.err" && echo sim || echo não)"

echo
echo "=== drift: o serviço temporário do primeiro deploy sobrou ==="
build_pass_fixtures
printf 'existe\n' | fx run services describe spark-backend-validate
RESULT="$(run_audit config-drift-audit.sh)"
check "config-drift-audit → DRIFT" "1|config-drift-audit: DRIFT" "${RESULT}"
check "...apontando o serviço temporário" "sim" "$(grep -q 'spark-backend-validate ainda existe' "${WORK}/audit.err" && echo sim || echo não)"

echo
echo "=== drift de IAM: a runtime SA tem acesso ao secret direto (o drift REAL encontrado na T18.3) ==="
build_pass_fixtures
mutate ".bindings[0].members += [\"serviceAccount:${RUNTIME}\"]" \
  secrets get-iam-policy spark-database-url-direct --format=json
RESULT="$(run_audit iam-audit.sh)"
check "iam-audit → DRIFT" "1|iam-audit: DRIFT" "${RESULT}"
check "...apontando os accessors do secret direto" "sim" "$(grep -q 'secret spark-database-url-direct accessors: esperado' "${WORK}/audit.err" && echo sim || echo não)"
check "...e nada foi removido (auditoria, nunca remediação)" "0" "$(mutating_calls)"

echo
echo "=== drift de IAM: papel amplo (Editor) numa SA do Spark; chave JSON de usuário ==="
build_pass_fixtures
mutate ".bindings += [{role:\"roles/editor\",members:[\"serviceAccount:${MIGRATOR}\"]}]" \
  projects get-iam-policy "${P}" --format=json
printf 'projects/x/serviceAccounts/y/keys/abc\n' | fx iam service-accounts keys list --iam-account "${BACKUP}" --managed-by user --format=value\(name\)
RESULT="$(run_audit iam-audit.sh)"
check "iam-audit → DRIFT" "1|iam-audit: DRIFT" "${RESULT}"
check "...apontando o Editor" "sim" "$(grep -q 'roles/editor' "${WORK}/audit.err" && echo sim || echo não)"
check "...e a chave JSON" "sim" "$(grep -q 'existe chave JSON gerenciada por usuário' "${WORK}/audit.err" && echo sim || echo não)"

echo
echo "=== NOT_VERIFIED: API de budget indisponível; recurso inesperado é DRIFT ==="
build_pass_fixtures
rm -f "$(fxfile billing budgets list --billing-account=ABC-123 --format=json)"
RESULT="$(run_audit cost-audit.sh)"
check "cost-audit → NOT_VERIFIED (código 2), nunca PASS por omissão" "2|cost-audit: NOT_VERIFIED" "${RESULT}"
check "...explicando a API de budget" "sim" "$(grep -q 'API billingbudgets desabilitada ou sem permissão' "${WORK}/audit.err" && echo sim || echo não)"

build_pass_fixtures
printf 'spark-private-assets-prod\nbucket-esquecido\n' | fx storage buckets list --format=value\(name\)
printf 'run.googleapis.com\nartifactregistry.googleapis.com\nsecretmanager.googleapis.com\ncloudscheduler.googleapis.com\nstorage.googleapis.com\nlogging.googleapis.com\nmonitoring.googleapis.com\nsqladmin.googleapis.com\n' \
  | fx services list --enabled --format=value\(config.name\)
printf 'instancia-cara\n' | fx sql instances list --format=value\(name\)
RESULT="$(run_audit cost-audit.sh)"
check "cost-audit → DRIFT com bucket a mais e Cloud SQL existente" "1|cost-audit: DRIFT" "${RESULT}"
check "...apontando o bucket" "sim" "$(grep -q 'bucket-esquecido' "${WORK}/audit.err" && echo sim || echo não)"
check "...apontando a instância Cloud SQL" "sim" "$(grep -q 'instâncias Cloud SQL: existem recursos — instancia-cara' "${WORK}/audit.err" && echo sim || echo não)"

echo
echo "=== drift: soft delete do bucket abaixo de 7 dias ==="
build_pass_fixtures
printf '{"location":"SOUTHAMERICA-EAST1","public_access_prevention":"enforced","uniform_bucket_level_access":true,"soft_delete_policy":{"retentionDurationSeconds":"0"}}\n' \
  | fx storage buckets describe gs://spark-private-assets-prod --format=json
RESULT="$(run_audit config-drift-audit.sh)"
check "config-drift-audit → DRIFT" "1|config-drift-audit: DRIFT" "${RESULT}"
check "...apontando o soft delete" "sim" "$(grep -q 'soft_delete retentionDurationSeconds: esperado ≥ 604800' "${WORK}/audit.err" && echo sim || echo não)"

finish_checks "auditorias de drift, IAM e custo: PASS/DRIFT/NOT_VERIFIED provados offline, sem nenhuma remediação automática"
