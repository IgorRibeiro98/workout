# Cloud Run — deploy produtivo do Spark Backend (T18.2)

> Este documento descreve a topologia **Cloud Run**, a partir da T18.2. A topologia **VPS/Docker
> Compose** continua documentada e funcional em
> [`PRODUCTION_DEPLOYMENT.md`](./PRODUCTION_DEPLOYMENT.md) — as duas coexistem; nenhuma delas é
> obrigatória, e o Cloud Run é a recomendada a partir desta tarefa. Ver o vocabulário de estado em
> [`README.md`](./README.md).

## 1. Arquitetura

```text
Spark Android
      │
      │ HTTPS
      ▼
┌─────────────────────────────┐
│ Cloud Run — spark-backend   │  ← público (allow-unauthenticated), Firebase Bearer obrigatório
│                             │
│ NestJS                      │
│ Firebase Auth (ADC)         │
│ Sync / Social / Backup / IA │
└─────────────┬───────────────┘
              │
              ├──────► Neon PostgreSQL          (pooled — DATABASE_MIGRATION_MODE=verify)
              ├──────► Google Cloud Storage     (bucket privado — ADC)
              ├──────► Firebase / FCM            (ADC)
              └──────► Gemini                    (API key via Secret Manager)

Artifact Registry
       │  imagem imutável, identificada por git SHA/digest
       ├────► Cloud Run API (spark-backend)
       ├────► Cloud Run Maintenance (spark-maintenance, privado)
       └────► Cloud Run Migration Job (spark-db-migrate)

Cloud Scheduler ──OIDC──► spark-maintenance (privado, run.invoker apenas)
```

Três superfícies de execução, uma imagem só:

| Serviço/Job | Tipo | Tráfego | Comando | Identidade |
| --- | --- | --- | --- | --- |
| `spark-backend` | Cloud Run Service | público (Firebase Bearer nas rotas de produto) | `node dist/main.js` (default do Dockerfile) | `spark-backend-runtime` |
| `spark-maintenance` | Cloud Run Service | privado (só `run.invoker` do Scheduler) | `node dist/maintenance-main.js` | `spark-backend-runtime` |
| `spark-db-migrate` | Cloud Run Job | nenhum (não é HTTP) | `node dist/cli/migrate-database.js` | `spark-backend-migrator` |

Nenhum dos três monta a API pública e a manutenção no mesmo processo, e os três compartilham o
**mesmo digest de imagem** em cada deploy — a diferença é comando e identidade, nunca o artefato.

## 2. Pré-requisito

Esta tarefa (T18.2) parte do commit aprovado da T18.1.1. Não iniciá-la enquanto a T18.1.1 tiver
bloqueante aberto.

## 3. Bootstrap (uma vez por projeto)

```bash
export SPARK_GCP_PROJECT=<project-id>
export SPARK_GCP_REGION=southamerica-east1   # default; só declare para mudar
ops/gcp/bootstrap-cloud-run.sh
```

Idempotente (§4): recurso existente é reutilizado, recurso ausente é criado. Garante, nesta ordem:

1. APIs habilitadas (`run`, `artifactregistry`, `secretmanager`, `cloudscheduler`, `cloudbuild`, `iam`).
2. Repositório Artifact Registry `spark` (Docker, regional, `southamerica-east1`).
3. Três Service Accounts: `spark-backend-runtime`, `spark-backend-migrator`,
   `spark-maintenance-scheduler`.
4. Os quatro secrets do Secret Manager (`spark-database-url`, `spark-database-url-direct`,
   `spark-gemini-api-key`, `spark-account-deletion-hmac-key`) — a chave HMAC é **gerada** com
   `openssl rand -hex 32` na primeira execução, e nunca rotacionada automaticamente depois (§20 —
   trocá-la sem migração destruiria o reconhecimento de tombstones históricos).
5. IAM por secret: `spark-backend-runtime` só acessa os três da API; `spark-backend-migrator` só o
   secret direto (§9/§21).
6. IAM mínimo de Firebase Admin (`roles/firebaseauth.admin`, `roles/firebasecloudmessaging.admin`)
   e de bucket (`roles/storage.objectAdmin` sobre `spark-private-assets-prod`) para a runtime SA.

**Nunca imprime valor de secret.** Os valores reais de `DATABASE_URL`, `DATABASE_URL_DIRECT` e
`GEMINI_API_KEY` são responsabilidade do operador, fora deste script e fora do Git:

```bash
printf '%s' "postgresql://...">/dev/null  # nunca conte um secret em ARGV visível no histórico
gcloud secrets versions add spark-database-url --data-file=- <<< "postgresql://usuario:senha@host-pooled/spark"
gcloud secrets versions add spark-database-url-direct --data-file=- <<< "postgresql://usuario:senha@host-direct/spark"
gcloud secrets versions add spark-gemini-api-key --data-file=- <<< "<chave real>"
```

## 4. Deploy

```bash
ops/gcp/deploy-cloud-run.sh
```

Ordem obrigatória (§10), cada etapa falha cedo:

```text
árvore Git limpa
      ↓
build (tag = git SHA)
      ↓
push no Artifact Registry
      ↓
resolve o digest exato
      ↓
atualiza spark-db-migrate com o MESMO digest
      ↓
executa o Job — falha bloqueia o deploy, nenhuma API nova recebe este digest
      ↓
deploy do candidate da API — --no-traffic --tag candidate
      ↓
smoke contra o candidate (ops/gcp/smoke-cloud-run.sh)
      ↓
falha do smoke → tráfego antigo permanece, nada mudou
      ↓
100% do tráfego para o candidate
      ↓
deploy de spark-maintenance com o MESMO digest (privado, sem troca de tráfego)
      ↓
garante o job do Cloud Scheduler
```

`--skip-maintenance` pula as duas últimas etapas quando só a API muda.

### Rastreabilidade

A imagem é identificada por `southamerica-east1-docker.pkg.dev/<project>/spark/spark-backend:<git-sha>`,
e o deploy real rastreia até o **digest** resolvido depois do push — nunca até `latest`, e nunca só
até a tag mutável do SHA (uma tag pode em tese ser sobrescrita; um digest, não).

### Rollback

```bash
ops/gcp/rollback-cloud-run.sh --list                # revisions disponíveis
ops/gcp/rollback-cloud-run.sh <revision-anterior>    # move 100% do tráfego de volta
```

Nunca rebuilda a imagem antiga — usa a revision que já existe. `deploy-cloud-run.sh` imprime a
revision anterior antes de trocar o tráfego, exatamente para este comando.

**Rollback de aplicação ≠ rollback de migration (§56/§11).** Migrations continuam obrigatoriamente
*additive* e compatíveis com o código anterior — é isso que permite código antigo (pós-rollback) e
schema novo coexistirem. Uma mudança destrutiva de schema usa expand → migrate → contract em
deploys separados; não há down migration automática.

## 5. Migrations

| | Runtime mode | Endpoint | Secret |
| --- | --- | --- | --- |
| `spark-backend` (API) | `DATABASE_MIGRATION_MODE=verify` | pooled (`DATABASE_URL`) | `spark-database-url` |
| `spark-maintenance` | `DATABASE_MIGRATION_MODE=verify` | pooled | `spark-database-url` |
| `spark-db-migrate` (Job) | CLI dedicado (`migrate:database`) | direto (`DATABASE_URL_DIRECT`) | `spark-database-url-direct` |

Em `verify`, `PostgresService.initialize()` **nunca chama `runMigrations()`** — ela só abre o pool
e confia no schema já estar no nível esperado. Não é um crash de bootstrap: a mesma verificação que
sempre alimentou `/health/ready` (`PostgresService.checkHealth()`, que compara `schema_migrations`
contra as migrations carregadas do disco) passa a decidir também isso. Schema pendente → o processo
sobe, e `/health/ready` responde `503` — exatamente como qualquer outra dependência externa fora do
ar, nunca um `process.exit`.

`npm run migrate:database` (`src/cli/migrate-database.ts`) é o comando dedicado:

```bash
DATABASE_URL_DIRECT=postgresql://... node dist/cli/migrate-database.js
```

Não lê `AppConfig` — só `DATABASE_URL_DIRECT`, sem fallback para a pooled. Sem a variável, sai com
código 1 e não toca o banco. Abre `DATABASE_URL_DIRECT`, aplica migrations pendentes (o mesmo
`postgres-migration-runner.ts` da API, com o mesmo advisory lock, serializando execuções
concorrentes), confere o schema resultante e encerra — código 0 em sucesso, != 0 em qualquer falha.

O Job `spark-db-migrate` usa a Service Account `spark-backend-migrator`, que só tem acesso ao
secret `spark-database-url-direct` — nunca aos outros três. `spark-backend-runtime` (API e
Maintenance) nunca tem acesso a `spark-database-url-direct`.

## 6. Firebase Admin — ADC

```text
FIREBASE_ADMIN_CREDENTIAL_MODE
├── file   (default — VPS, desenvolvimento, teste: GOOGLE_APPLICATION_CREDENTIALS + cert(path))
└── adc    (Cloud Run: nenhum arquivo, identidade da Service Account anexada)
```

Cloud Run declara `FIREBASE_ADMIN_CREDENTIAL_MODE=adc` e **nunca** define
`GOOGLE_APPLICATION_CREDENTIALS`. `FirebaseAuthTokenVerifier.adminApp()` monta a credencial com
`applicationDefault()` do Admin SDK em vez de `cert(caminho)` — o mesmo mecanismo que
`GcsObjectStorageClient` já usa desde a T18.1. Um único arquivo continua sendo o dono da
inicialização (`firebase-auth-token-verifier.ts`); `FirebasePushGateway` continua só localizando o
app já inicializado por nome (`spark-backend-auth`) — nenhuma segunda `initializeApp()` nasce em
lugar nenhum.

O preflight de startup (`REQUIRE_FIREBASE_ADMIN=true`, obrigatório em produção) também entende os
dois modos: em `file`, valida arquivo/JSON/forma/`cert()`; em `adc`, só confirma que
`applicationDefault()` + `initializeApp()` não lançam — **sem chamada de rede** (§18): a resolução
real da credencial (variável de ambiente, arquivo bem-conhecido, metadata server) só acontece no
primeiro uso de verdade, e testá-la aqui exigiria exatamente a chamada remota que o preflight
existe para evitar. Uma credencial ADC inutilizável em runtime continua surgindo como `503` em
`verifyIdToken`/`deleteUser`, como sempre foi.

Ver [`FIREBASE_AUTH_SETUP.md`](../FIREBASE_AUTH_SETUP.md) para o passo a passo completo dos dois
modos.

### IAM mínimo (§19)

| Capacidade | Papel | Por quê |
| --- | --- | --- |
| `verifyIdToken` | nenhum (chaves públicas do Google) | validação local contra JWKS público |
| `deleteUser` | `roles/firebaseauth.admin` | escrita no Firebase Authentication |
| FCM (`SOCIAL_PUSH_ENABLED=true`) | `roles/firebasecloudmessaging.admin` | envio de mensagens |

Nunca `roles/owner` nem `roles/editor`. `spark-backend-runtime` é a única Service Account com estes
dois papéis — `spark-backend-migrator` e `spark-maintenance-scheduler` não os têm.

## 7. Secret Manager

| Secret | Consumido por | IAM |
| --- | --- | --- |
| `spark-database-url` | API, Maintenance | `spark-backend-runtime` |
| `spark-database-url-direct` | Migration Job | `spark-backend-migrator` **apenas** |
| `spark-gemini-api-key` | API, Maintenance | `spark-backend-runtime` |
| `spark-account-deletion-hmac-key` | API, Maintenance | `spark-backend-runtime` |

`roles/secretmanager.secretAccessor` é concedido **por secret**, nunca
`roles/secretmanager.admin` sobre o projeto. Nenhum valor de secret aparece em Git, na imagem, em
`docker build --build-arg` ou em log — a chave HMAC é gerada uma única vez com
`openssl rand -hex 32` e nunca impressa pelos scripts de `ops/gcp/`.

## 8. Object Storage

`OBJECT_STORAGE_PROVIDER=gcs`, `GCS_BUCKET_NAME=spark-private-assets-prod`, ADC — sem mudança de
desenho desde a T18.1; ver [`PRODUCTION_DEPLOYMENT.md`](./PRODUCTION_DEPLOYMENT.md) §GCS e o código
em `backend/src/object-storage/`. O que muda no Cloud Run é só a origem da identidade: a Service
Account anexada à revision, nunca `gcloud auth application-default login` (isso é só para a máquina
do operador).

### Smoke com identidade real (§25/§52)

```bash
# Rodando como spark-backend-runtime (dentro do Cloud Run, ou impersonando a SA localmente):
OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=spark-private-assets-prod \
  node dist/cli/object-storage-smoke.js
```

Valida write/read/hash/exists/collision/delete sob `_smoke/`. Nenhum objeto deve sobrar. **Status:
NOT VERIFIED neste ambiente de desenvolvimento** — não há ADC nem rede para o GCP real.

## 9. Account Deletion — ledger

```text
DeletionTombstoneLedgerPort
├── FileDeletionTombstoneLedger           disco local (VPS, desenvolvimento, teste)
└── ObjectStorageDeletionTombstoneLedger  Object Storage (Cloud Run)
```

A escolha segue `OBJECT_STORAGE_PROVIDER` — a mesma variável que decide onde fotos e backups vivem
(`deletion-tombstone-ledger.factory.ts`), e não uma variável própria: no Cloud Run o filesystem do
container não é autoridade de nada durável, e é exatamente o mesmo ambiente em que
`OBJECT_STORAGE_PROVIDER=gcs` já é obrigatório. Duas variáveis controlando a mesma pergunta
divergiriam sem ninguém notar.

Com `gcs`, cada tombstone é um objeto imutável em `system/deletion-tombstones/<hash-hmac>` — nunca
o Firebase UID puro, só o HMAC que já protegia o ledger de disco. `appendDurably` converge: gravar
o mesmo hash duas vezes (retry, reconciliação) nunca lança — a mera existência do objeto já é o
tombstone. Uma falha real de infraestrutura (bucket fora do ar) continua propagando como falha do
ledger, e o job de exclusão continua em `DELETION_PENDING` — nunca `DELETED` sem confirmação do
provider (§29).

### Migração do ledger antigo (§30/§31)

```bash
OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=spark-private-assets-prod \
DELETION_TOMBSTONES_FILE_PATH=/caminho/para/deletion_tombstones.tsv \
  node dist/cli/migrate-deletion-ledger-to-object-storage.js
```

Lê o `.tsv` legado inteiro (a mesma validação estrita de sempre — linha malformada aborta antes de
qualquer escrita), grava cada hash distinto no bucket (convergente, idempotente), e **nunca apaga
nem trunca o arquivo de origem**. Rodar de novo converge. Execute antes do cutover para
`OBJECT_STORAGE_PROVIDER=gcs` em produção.

### Reconciliação de DR (§28/§31)

```bash
node dist/cli/reconcile-account-deletions.js
```

Funciona nos dois providers — a fábrica do ledger é a mesma que a API usa, então a reconciliação
nunca lê `/data/deletion_tombstones.tsv` como autoridade quando o provider ativo é `gcs`. O ledger
continua **independente do PostgreSQL** nos dois casos: é essa independência que sobrevive a um
restore de backup antigo seguido de uma exclusão feita depois do backup.

## 10. Background jobs — sem timers na API

```text
BACKGROUND_JOBS_MODE
├── interval   (default — VPS: cada worker agenda o próprio setInterval)
└── disabled   (Cloud Run: nenhum timer nasce; spark-maintenance chama os métodos de uma passagem)
```

Os quatro workers (`NotificationDispatcher`, `AccountDeletionReconciler`, `SocialMediaCleaner`,
`BackupPayloadCleaner`) continuam existindo como estavam — a lógica de cada um não mudou. O que
muda é **quem os agenda**: `BACKGROUND_JOBS_MODE=disabled` faz os quatro pularem
`onModuleInit`/`onApplicationBootstrap`; os métodos de uma passagem (`runDispatchCycle`,
`processDueJobs`, `sweep`) continuam públicos e chamáveis diretamente.

### `spark-maintenance`

Segundo Cloud Run Service, **privado** (`--no-allow-unauthenticated`), mesma imagem, entrypoint
próprio (`node dist/maintenance-main.js`) — não a API inteira. Ele monta o mesmo grafo de DI da API
via `NestFactory.createApplicationContext(AppModule.forRoot(config))` (sem adaptador HTTP, então
nenhuma rota de produto é exposta) e um módulo HTTP minúsculo com uma única rota:
`POST /internal/maintenance/run`.

Cada chamada executa um ciclo bounded (`MaintenanceCoordinator.runCycle()`):

1. `pg_try_advisory_lock` numa conexão dedicada — se outra execução já está rodando, esta
   **desiste imediatamente** (`skipped: true`), nunca espera. É a defesa contra retry do Scheduler
   ou deploy sobrepondo duas chamadas reais (§39), mesmo com `max instances=1`/`concurrency=1`.
2. `NotificationDispatcher.runDispatchCycle()` — só se `SOCIAL_PUSH_ENABLED=true`.
3. `AccountDeletionReconciler.processDueJobs()` — sempre.
4. `SocialMediaCleaner.sweep()` — só quando um CAS sobre `server_metadata` confirma que já passou
   `SOCIAL_MEDIA_CLEANUP_INTERVAL_MS` desde a última vez (§38 — nenhuma varredura de bucket em
   toda chamada de 1 minuto).
5. `BackupPayloadCleaner.sweep()` — mesmo mecanismo, com `BACKUP_PAYLOAD_CLEANUP_INTERVAL_MS`.

Os mesmos dois valores de configuração servem os dois modos: em `interval` são o período do
`setInterval`; em `disabled`/ciclo de manutenção, a cadência mínima entre duas execuções.

### Cloud Scheduler (§34–§36)

Um único job HTTP, cadência `* * * * *` por padrão, OIDC:

```text
spark-maintenance-scheduler
   └── roles/run.invoker sobre spark-maintenance — nada mais (nenhum bucket, secret, Firebase ou banco)
```

`gcloud scheduler jobs create http ... --oidc-service-account-email ... --oidc-token-audience ...`
— ver `ops/gcp/deploy-cloud-run.sh`. A documentação oficial do Cloud Run recomenda exatamente este
padrão para serviços chamados pelo Scheduler.

## 11. Social Push

`SOCIAL_PUSH_ENABLED=true` só é habilitado em produção Cloud Run depois de confirmar, com evidência
real (não por inspeção de código): ADC do Firebase funcionando, `spark-maintenance` despachando de
verdade, e idempotência de entrega (`notification_deliveries`, já testada offline). Sem essa
confirmação, o primeiro deploy mantém `SOCIAL_PUSH_ENABLED=false` — **NOT VERIFIED / DISABLED** —, e
não é declarado funcional.

## 12. Configuração produtiva — API (`spark-backend`)

| Variável | Valor |
| --- | --- |
| `NODE_ENV` | `production` |
| `PORT` | `8080` |
| `DATABASE_MIGRATION_MODE` | `verify` |
| `OBJECT_STORAGE_PROVIDER` | `gcs` |
| `GCS_BUCKET_NAME` | `spark-private-assets-prod` |
| `REQUIRE_FIREBASE_ADMIN` | `true` |
| `FIREBASE_ADMIN_CREDENTIAL_MODE` | `adc` |
| `FIREBASE_PROJECT_ID` | `<projeto>` |
| `AI_ENABLED` | `true` |
| `REQUIRE_GEMINI` | `false` |
| `SYNC_WRITE_ENABLED` | `true` |
| `MAINTENANCE_MODE` | `false` |
| `BACKGROUND_JOBS_MODE` | `disabled` |
| `SOCIAL_PUSH_ENABLED` | `false` até confirmado (§11) |
| `DATABASE_POOL_MIN` / `DATABASE_POOL_MAX` | `0` / `5` |

**Nunca definidas na API:** `GOOGLE_APPLICATION_CREDENTIALS`, `DATABASE_URL_DIRECT`.

Secrets por referência: `DATABASE_URL` ← `spark-database-url`; `GEMINI_API_KEY` ←
`spark-gemini-api-key`; `ACCOUNT_DELETION_HMAC_KEY` ← `spark-account-deletion-hmac-key`.

### Configuração produtiva — `spark-maintenance`

Mesmas variáveis, com `AI_ENABLED=false` (o Coach não é usado pela manutenção) e sem tráfego
público (`--no-allow-unauthenticated`). Mesma runtime Service Account
(`spark-backend-runtime`) — não faz sentido criar uma conta nova só por estética quando as
permissões exigidas são exatamente as mesmas (§40).

## 13. Cloud Run — parâmetros centralizados

Todos os números abaixo vivem em **um** lugar (`ops/gcp/lib.gcp.sh`) — nenhum script os repete.

| | `spark-backend` (API) | `spark-maintenance` |
| --- | --- | --- |
| Região | `southamerica-east1` | `southamerica-east1` |
| CPU | 1 | 1 |
| Memória | 512 MiB | 512 MiB |
| min instances | 0 | 0 |
| max instances | 1 | 1 |
| concurrency | 20 | 1 |
| request timeout | 180 s | (default) |
| billing | request-based | request-based |
| porta | 8080 | 8080 |
| acesso | `allow-unauthenticated` | privado |

Subir qualquer um destes valores é decisão operacional deliberada (§58), documentada quando
acontecer — nunca uma "otimização" automática. O objetivo inicial é custo mínimo com
scale-to-zero.

### O que esta tarefa deliberadamente não faz (§60)

Domínio customizado, Load Balancer, CDN, Cloud Armor, Redis, Cloud SQL, migração Neon → Cloud SQL,
Vertex AI, múltiplas regiões, alta disponibilidade multi-region, `min instances ≥ 1`, autoscaling
agressivo, Kubernetes/GKE, Terraform, observabilidade avançada, políticas finais de DR, otimização
final de custos, VPC Connector/Cloud NAT/IP estático de saída (Neon, Firebase, Gemini e GCS são
endpoints públicos autenticados — não há requisito concreto para eles ainda). Ficam para a T18.3 ou
depois, quando uma necessidade real exigir.

## 14. Probes, graceful shutdown e filesystem

- **Startup/readiness:** `GET /health/ready` — banco alcançável + schema atual, nunca Firebase ou
  Gemini (§43 — o Coach fora do ar não pode derrubar a prontidão de sync/backup).
- **Liveness:** `GET /health/live` — processo vivo, sem chamada externa.
- **SIGTERM:** para de aceitar trabalho novo, drena requisições em andamento até
  `SHUTDOWN_TIMEOUT_MS`, fecha o pool do PostgreSQL e o app do Firebase Admin, encerra — sem
  mudança desde a T16.8, e válido nos dois entrypoints (`main.ts`, `maintenance-main.ts`).
- **Filesystem:** nenhuma escrita local durável na API em modo Cloud Run — mídia e backup vivem no
  bucket, o ledger de exclusão também (quando `OBJECT_STORAGE_PROVIDER=gcs`). `SOCIAL_MEDIA_ROOT`
  fica sem efeito com o provider `gcs`.

## 15. Android

Nenhuma mudança na arquitetura offline-first: Room continua autoridade local, sync continua
eventual, o app continua funcionando sem internet. Depois do primeiro deploy real, validar:

```bash
./gradlew assembleRelease -PsparkBackendBaseUrl=https://<cloud-run-url>
```

`SPARK_BACKEND_BASE_URL`/`BuildConfig` continua vindo de fora do código (`sparkBackendBaseUrl`,
Gradle property) — a URL do Cloud Run precisa passar as mesmas regras de sempre em release: HTTPS,
host público, nunca `localhost`/IP privado (`VerifyReleaseEndpointGate`,
`contracts/endpoint/release-endpoint-cases.tsv`).

## 16. Smoke produtivo

```bash
ops/gcp/smoke-cloud-run.sh https://<url-do-serviço>
```

```text
GET  /health/live      → 200, sem token
GET  /health/ready      → 200, sem token
GET  /v1/auth/me        → 401, sem token
GET  /v1/backups        → 401, sem token
GET  /v1/sync/pull      → 401, sem token
GET  /v1/social/me      → 401, sem token
```

Com uma conta Firebase real de teste (nunca a principal), `SPARK_SMOKE_FIREBASE_ID_TOKEN` habilita
a verificação `GET /v1/auth/me → 200`. O token nunca é registrado nem impresso pelo script além do
uso imediato na chamada.

## 17. Logs

Cloud Run consome logs pela saída padrão — nenhum log durável em arquivo. Pino JSON, `requestId`,
`errorName`, `uidPrefix` e a redação existente continuam sem mudança. Nunca aparecem em log:
secret, connection string, token, URL assinada, chave HMAC.

## 18. NOT VERIFIED neste ambiente

Este documento foi escrito e revisado num ambiente de desenvolvimento sem `gcloud` instalado e sem
acesso à rede do Google Cloud. Todo item que exige execução real contra um projeto GCP —
Artifact Registry, bootstrap, deploy, migration job, candidate/tráfego, smoke real, IAM efetivo,
Cloud Scheduler — é **NOT VERIFIED** por inspeção de código apenas. Ver o relatório final da T18.2
para o checklist completo.
