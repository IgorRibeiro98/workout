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
       │  imagem imutável, identificada por git SHA/digest (política de retenção nativa, T18.3)
       ├────► Cloud Run API (spark-backend)
       ├────► Cloud Run Maintenance (spark-maintenance, privado)
       ├────► Cloud Run Job spark-db-migrate   (migration, SA migrator)
       ├────► Cloud Run Job spark-db-backup    (DR do PostgreSQL, SA backup — T18.3)
       └────► Cloud Run Job spark-storage-audit (PostgreSQL ↔ GCS, somente leitura — T18.3)

Cloud Scheduler ──OIDC──► spark-maintenance (privado, run.invoker apenas)       a cada minuto
Cloud Scheduler ──OAuth──► Job spark-db-backup (run.invoker sobre o Job)         03:15 UTC, diário
```

Cinco superfícies de execução, uma imagem só:

| Serviço/Job | Tipo | Tráfego | Comando | Identidade |
| --- | --- | --- | --- | --- |
| `spark-backend` | Cloud Run Service | público (Firebase Bearer nas rotas de produto) | `node dist/main.js` (default do Dockerfile) | `spark-backend-runtime` |
| `spark-maintenance` | Cloud Run Service | privado (só `run.invoker` do Scheduler) | `node dist/maintenance-main.js` | `spark-backend-runtime` |
| `spark-db-migrate` | Cloud Run Job | nenhum (não é HTTP) | `node dist/cli/migrate-database.js` | `spark-backend-migrator` |
| `spark-db-backup` (T18.3) | Cloud Run Job | nenhum | `node dist/cli/db-backup.js` | `spark-backend-backup` |
| `spark-storage-audit` (T18.3) | Cloud Run Job | nenhum | `node dist/cli/storage-audit.js` | `spark-backend-runtime` |

Nenhum deles monta a API pública e a manutenção no mesmo processo, e todos compartilham o **mesmo
digest de imagem** em cada deploy — a diferença é comando e identidade, nunca o artefato. A imagem
traz `pg_dump`/`pg_restore`/`psql` 18 (PGDG) para os dois comandos de DR.

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

1. Os dois projetos são validados (`gcloud projects describe`) antes de qualquer recurso ser criado
   ou qualquer IAM ser aplicado — ver §3.1 quando o projeto Firebase é diferente do projeto GCP.
2. APIs habilitadas (`run`, `artifactregistry`, `secretmanager`, `cloudscheduler`, `cloudbuild`, `iam`).
3. Repositório Artifact Registry `spark` (Docker, regional, `southamerica-east1`).
4. Quatro Service Accounts: `spark-backend-runtime`, `spark-backend-migrator`,
   `spark-maintenance-scheduler` e, desde a T18.3, `spark-backend-backup`.
5. Os quatro secrets do Secret Manager (`spark-database-url`, `spark-database-url-direct`,
   `spark-gemini-api-key`, `spark-account-deletion-hmac-key`) — a chave HMAC é **gerada** com
   `openssl rand -hex 32` na primeira execução, e nunca rotacionada automaticamente depois (§20 —
   trocá-la sem migração destruiria o reconhecimento de tombstones históricos).
6. IAM por secret: `spark-backend-runtime` só acessa os três da API; `spark-backend-migrator` e
   `spark-backend-backup` só o secret direto (§9/§21; T18.3 §2).
7. IAM mínimo de Firebase Admin (`roles/firebaseauth.admin`, `roles/firebasecloudmessaging.admin`,
   aplicado no projeto **Firebase** — §3.1) e de bucket (`roles/storage.objectAdmin` sobre
   `spark-private-assets-prod`, no projeto GCP) para a runtime SA. A backup SA recebe
   `roles/storage.objectAdmin` **condicionado** ao prefixo `system/dr/postgres/` (IAM Condition
   sobre `resource.name`) e `roles/storage.legacyBucketReader` (listar nomes; nunca ler conteúdo de
   `social/` ou `backups/`).
8. A política de retenção do Artifact Registry (`ops/gcp/artifact-registry-retention.sh --apply`,
   T18.3 §17).

Depois do bootstrap, `ops/gcp/iam-audit.sh` confirma que o IAM real é exatamente este — e reporta
`DRIFT` para qualquer papel a mais (por exemplo, a runtime SA com acesso ao secret direto, que foi o
drift real encontrado na T18.3).

### 3.1 Cross-project: infraestrutura GCP ≠ projeto Firebase (T18.2.1)

O projeto GCP que hospeda a infraestrutura (Cloud Run, Artifact Registry, Secret Manager, Service
Accounts, Cloud Scheduler, GCS) pode ser **diferente** do projeto Firebase usado pelo Android para
identidade e FCM. É o caso real do Spark:

```text
Firebase / identidade                    Cloud Run / infraestrutura
spark-36b11                              project-47b17b25-909d-4ae8-943
      ▲                                        │
      │ Firebase ID Tokens, Admin, FCM         ├── Artifact Registry
      │                                        ├── Secret Manager
      └──────── IAM cross-project ─────────────┤   (runtime SA do projeto GCP
               (sem Service Account             │    recebe papel no projeto Firebase)
                duplicada, sem JSON key)        ├── Cloud Scheduler
                                                 ├── GCS
                                                 └── Cloud Run
```

Declare os dois nomes explicitamente:

```bash
export SPARK_GCP_PROJECT=project-47b17b25-909d-4ae8-943
export SPARK_FIREBASE_PROJECT=spark-36b11
export SPARK_GCP_REGION=southamerica-east1

ops/gcp/bootstrap-cloud-run.sh
# depois de garantir os valores reais dos secrets (§3):
ops/gcp/deploy-cloud-run.sh
```

Sem `SPARK_FIREBASE_PROJECT` declarado, `ops/gcp/lib.gcp.sh` faz
`SPARK_FIREBASE_PROJECT="${SPARK_FIREBASE_PROJECT:-${SPARK_GCP_PROJECT}}"` — uma instalação de
projeto único (o caso mais simples, e o único que existia antes da T18.2.1) continua funcionando
sem nenhuma variável nova.

A divisão de responsabilidade:

| | `SPARK_GCP_PROJECT` | `SPARK_FIREBASE_PROJECT` |
| --- | --- | --- |
| Cloud Run (API, manutenção, Job de migration) | ✓ | |
| Artifact Registry | ✓ | |
| Secret Manager (os quatro secrets) | ✓ | |
| Service Accounts (as três) | ✓ | |
| Cloud Scheduler | ✓ | |
| GCS (`spark-private-assets-prod`) | ✓ | |
| `FIREBASE_PROJECT_ID` (API, manutenção) | | ✓ |
| `roles/firebaseauth.admin` | | ✓ |
| `roles/firebasecloudmessaging.admin` | | ✓ |

O binding de Firebase Admin é concedido **no projeto Firebase**, com o membro sendo a runtime
Service Account **do projeto GCP** — `serviceAccount:spark-backend-runtime@<gcp-project>.iam.gserviceaccount.com`.
Não existe, e não é criada, uma segunda `spark-backend-runtime` dentro do projeto Firebase: uma
Service Account recebe papéis num projeto diferente do seu perfeitamente bem, sem chave JSON, sem
impersonation e sem nada além de permissão para quem já a chama (`gcloud`, com as credenciais do
operador que roda o bootstrap).

`bootstrap-cloud-run.sh` valida os dois projetos (`gcloud projects describe`) antes de habilitar
qualquer API, criar qualquer recurso ou aplicar qualquer IAM — um `SPARK_FIREBASE_PROJECT`
inexistente ou inacessível falha imediatamente, nunca depois de a infraestrutura já ter sido criada
no projeto GCP.

O Migration Job (`spark-db-migrate`) não ganha nenhuma configuração de Firebase — ele só recebe
`DATABASE_URL_DIRECT`, como sempre.

Teste: `ops/tests/gcp-cross-project.test.sh` — com um `gcloud` fake (sem rede, sem projeto real).

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

Ordem obrigatória (§10; endurecida na T18.3), cada etapa falha cedo:

```text
árvore Git limpa
      ↓
build (tag = git SHA; --provenance=false --sbom=false: um digest por release)
      ↓
push no Artifact Registry
      ↓
resolve o digest exato
      ↓
resolve a versão HABILITADA de cada secret → revision X usa secret:versão Y, nunca :latest (T18.3 §19)
      ↓
atualiza os Jobs spark-db-backup e spark-storage-audit com o MESMO digest
      ↓
GATE de DR (T18.3 §10): existe backup válido com ≤ SPARK_DR_MAX_BACKUP_AGE_HOURS (24 h)?
   não → SPARK_DR_PREDEPLOY_POLICY=run-backup (default): executa spark-db-backup e espera SUCCESS
         SPARK_DR_PREDEPLOY_POLICY=fail: aborta
      ↓
atualiza spark-db-migrate com o MESMO digest
      ↓
executa o Job — falha bloqueia o deploy, nenhuma API nova recebe este digest
      ↓
spark-backend já existe?
      ↓                                              ↓
     SIM (deploy seguinte)                          NÃO (primeiro deploy — §4.1)
deploy do candidate — --no-traffic --tag candidate    deploy da MESMA imagem/config num serviço
      ↓                                               TEMPORÁRIO e PRIVADO (spark-backend-validate)
smoke contra o candidate                                    ↓
      ↓                                              smoke com o identity token do operador
falha do smoke → tráfego antigo permanece                  ↓
      ↓                                              remove o temporário, cria spark-backend
100% do tráfego para o candidate                      de verdade (já validado)
      ↓                                                     ↓
              deploy de spark-maintenance com o MESMO digest (privado, sem troca de tráfego)
      ↓
garante os dois jobs do Cloud Scheduler (manutenção a cada minuto; backup de DR diário)
```

`--skip-maintenance` pula a manutenção e os schedulers quando só a API muda.

O deploy termina imprimindo `secrets pinados nesta release: spark-database-url=vN …` — a
correlação revision → versão de secret, que `ops/gcp/config-drift-audit.sh` confere depois.

### 4.1 Primeiro deploy: por que `--no-traffic` não basta (T18.2.1)

`--no-traffic` não tem efeito na **primeira** revision de um serviço Cloud Run: sem nenhuma outra
revision para reter o tráfego, o Cloud Run roteia 100% do único tráfego que existe para ela, mesmo
com a flag. O par candidate→smoke→promove tráfego protege uma **substituição** — no primeiro
deploy não há nada para substituir, então validar precisa acontecer **antes** de `spark-backend`
existir.

`ops/gcp/deploy-cloud-run.sh` detecta isso (`gcloud run services describe spark-backend` — existe
ou não) e segue um caminho diferente só no primeiro deploy:

1. A mesma imagem/configuração (`deploy_api_revision`, uma função só — candidate, validação e
   primeiro deploy real nunca divergem entre si) é publicada num serviço **temporário e privado**
   (`--no-allow-unauthenticated`), `spark-backend-validate` (`SPARK_RUN_API_VALIDATE_SERVICE`), sem
   `--no-traffic`/`--tag`. Só o acesso muda entre o temporário e o real: a configuração do processo
   é idêntica.
2. `smoke-cloud-run.sh` roda contra ele com o identity token do operador
   (`gcloud auth print-identity-token`) em `X-Serverless-Authorization` — o Cloud Run valida e
   remove esse header antes de entregar a requisição, então `Authorization` continua livre para o
   Firebase Bearer e as expectativas do smoke não mudam (T18.3 §21). O token entra por variável de
   ambiente e nunca é impresso. Ninguém sem `run.invoker` alcança o temporário.
3. Smoke FALHA → `spark-backend-validate` é removido, `spark-backend` **nunca** é criado, o script
   aborta.
4. Smoke PASSA → `spark-backend-validate` é removido, e só então `spark-backend` é criado com a
   mesma imagem/configuração já validada. Sem `--no-traffic`/`--tag`: não têm efeito no primeiro
   deploy, e a imagem já foi provada boa.

Nenhuma mudança em migrations, secrets ou IAM: o serviço temporário usa a mesma Service Account
(`spark-backend-runtime`) e os mesmos secrets do serviço real — o IAM de Secret Manager, bucket e
Firebase Admin é concedido à Service Account, não a um serviço Cloud Run específico (§6/§7), então
já cobre o temporário sem nenhuma concessão nova.

Teste: `ops/tests/deploy-first-run.test.sh` — com `gcloud`/`docker`/`git`/`curl` fakes, cobre os
dois caminhos (serviço já existe / primeiro deploy) e o primeiro deploy com smoke falhando.

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

| Capacidade | Papel | Projeto | Por quê |
| --- | --- | --- | --- |
| `verifyIdToken` | nenhum (chaves públicas do Google) | — | validação local contra JWKS público |
| `deleteUser` | `roles/firebaseauth.admin` | `SPARK_FIREBASE_PROJECT` | escrita no Firebase Authentication |
| FCM (`SOCIAL_PUSH_ENABLED=true`) | `roles/firebasecloudmessaging.admin` | `SPARK_FIREBASE_PROJECT` | envio de mensagens |

Nunca `roles/owner` nem `roles/editor`. `spark-backend-runtime` é a única Service Account com estes
dois papéis — `spark-backend-migrator` e `spark-maintenance-scheduler` não os têm. Os dois papéis
são concedidos no projeto **Firebase** (`SPARK_FIREBASE_PROJECT`), que pode ser diferente do projeto
GCP de infraestrutura onde a própria Service Account vive — ver §3.1 (T18.2.1).

## 7. Secret Manager

| Secret | Consumido por | IAM |
| --- | --- | --- |
| `spark-database-url` | API, Maintenance, Job storage-audit | `spark-backend-runtime` |
| `spark-database-url-direct` | Jobs migrate e backup | `spark-backend-migrator`, `spark-backend-backup` **apenas** |
| `spark-gemini-api-key` | API, Maintenance | `spark-backend-runtime` |
| `spark-account-deletion-hmac-key` | API, Maintenance | `spark-backend-runtime` |

`roles/secretmanager.secretAccessor` é concedido **por secret**, nunca
`roles/secretmanager.admin` sobre o projeto. Nenhum valor de secret aparece em Git, na imagem, em
`docker build --build-arg` ou em log — a chave HMAC é gerada uma única vez com
`openssl rand -hex 32` e nunca impressa pelos scripts de `ops/gcp/`.

### Versões pinadas (T18.3 §19)

Nenhuma revision ou job referencia `secret:latest`. `deploy-cloud-run.sh` resolve, por metadata
(`gcloud secrets versions list --filter=state:enabled`, sem ler valor), a versão habilitada mais
recente de cada secret e a grava na revision (`--set-secrets DATABASE_URL=spark-database-url:3,…`).
Consequências deliberadas:

- uma revision declara exatamente com que versão de cada secret ela sobe — `gcloud run revisions
  describe <rev>` mostra `secretKeyRef.key = 3`, e `config-drift-audit.sh` confere;
- **rotacionar um secret não muda produção** até um deploy deliberado criar revisions novas
  (ver "Rotação" em [`OPERATIONS_CHECKLIST.md`](./OPERATIONS_CHECKLIST.md));
- rollback para uma revision anterior volta também às versões de secret que ela pinou;
- um secret sem versão habilitada aborta o deploy antes de qualquer revision existir.

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
| `FIREBASE_PROJECT_ID` | `${SPARK_FIREBASE_PROJECT}` — pode ser diferente de `${SPARK_GCP_PROJECT}` (§3.1) |
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
agressivo, Kubernetes/GKE, Terraform, VPC Connector/Cloud NAT/IP estático de saída (Neon, Firebase,
Gemini e GCS são endpoints públicos autenticados — não há requisito concreto para eles). DR,
observabilidade, auditorias e retenção chegaram na T18.3 (§19 abaixo); o resto fica para quando uma
necessidade real exigir.

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

## 18. Estado de verificação

A T18.2 e a T18.2.1 foram implantadas e validadas em Cloud Run real (primeiro deploy, migration job,
`spark-backend-validate`, health, 401 nas rotas `/v1`, `spark-maintenance` privado, Scheduler OIDC —
revision `spark-backend-00001-gs2`, digest `sha256:bfe4d582…`). Os itens da T18.3 que exigem
execução real (backup no bucket, ensaio contra o bucket real, alertas, rollback drill, auditorias
contra o projeto real) têm o estado registrado no relatório final da T18.3 — `VERIFIED` ou
`NOT VERIFIED`, nunca inferido de inspeção.

## 19. Hardening operacional, DR e observabilidade (T18.3)

O que a T18.3 acrescentou a esta topologia — cada item com o documento que o detalha:

| Capacidade | Onde | Documento |
| --- | --- | --- |
| Backup de DR do PostgreSQL independente do Neon (`spark-db-backup`, diário, `pg_dump --format=custom`, SHA-256, manifesto, retenção 7) | `backend/src/dr/`, `ops/gcp/lib.dr.sh` | [`DISASTER_RECOVERY.md`](./DISASTER_RECOVERY.md) |
| Restore só em destino limpo, nunca produção (`db-restore-drill.js`, `ops/gcp/dr-restore-drill.sh`) | idem | idem |
| Ensaio de DR de ponta a ponta no CI, com anti-ressurreição (`ops/gcp/dr-backup-drill.sh`) | CI job `dr-drill` | idem |
| Gate de backup pré-migration no deploy | `deploy-cloud-run.sh` | §4 |
| Versões de secret pinadas por revision | `deploy-cloud-run.sh`, `lib.gcp.sh#resolve_secret_version` | §7 |
| Validação privada no primeiro deploy | `deploy-cloud-run.sh`, `smoke-cloud-run.sh` | §4.1 |
| Heartbeat do maintenance, stale detection, tamanho do banco, frescor do DR (`GET /internal/maintenance/status`) | `MaintenanceCoordinator` | [`OBSERVABILITY.md`](./OBSERVABILITY.md) |
| Logs estruturados e 12 alertas (`ops/gcp/monitoring-alerts.sh`) | Cloud Logging/Monitoring | idem |
| Auditor PostgreSQL ↔ GCS, somente leitura (`spark-storage-audit`) | `backend/src/dr/storage-auditor.ts` | idem |
| Auditorias de drift, IAM e custo; retenção do Artifact Registry | `ops/gcp/*-audit.sh`, `artifact-registry-retention.sh` | [`OPERATIONS_CHECKLIST.md`](./OPERATIONS_CHECKLIST.md) |
| Política de TLS explícita para o PostgreSQL (`verify-full`) | `backend/src/database/postgres-url.ts` | [`SECURITY.md`](./SECURITY.md) |
| Rollback drill (`ops/gcp/rollback-drill.sh`) | `ops/gcp/` | [`OPERATIONS_CHECKLIST.md`](./OPERATIONS_CHECKLIST.md) |

Parâmetros novos, todos em `ops/gcp/lib.gcp.sh` (um lugar só):

| Variável | Default | Papel |
| --- | --- | --- |
| `SPARK_SA_BACKUP` | `spark-backend-backup` | identidade do Job de backup |
| `SPARK_RUN_BACKUP_JOB` / `SPARK_RUN_STORAGE_AUDIT_JOB` | `spark-db-backup` / `spark-storage-audit` | os dois Jobs novos |
| `SPARK_BACKUP_SCHEDULER_JOB` / `SPARK_BACKUP_SCHEDULER_CRON` | `spark-db-backup-daily` / `15 3 * * *` | agendamento diário |
| `SPARK_DR_PREFIX` | `system/dr/postgres/` | namespace no bucket (= `DR_POSTGRES_PREFIX` no backend) |
| `SPARK_DR_RETENTION_COUNT` | `7` | backups válidos mantidos |
| `SPARK_DR_MAX_BACKUP_AGE_HOURS` | `24` | janela do gate de deploy |
| `SPARK_DR_PREDEPLOY_POLICY` | `run-backup` | `run-backup` ou `fail` |
| `SPARK_RUN_BACKUP_MEMORY` / `SPARK_RUN_BACKUP_TIMEOUT` | `1Gi` / `1800` | recursos do Job de backup (o dump nasce em tmpfs) |
| `SPARK_GCS_SOFT_DELETE_MIN_SECONDS` | `604800` | mínimo aceito pela auditoria |

E no backend (`env.schema.ts`): `MAINTENANCE_STALE_AFTER_MS` (5 min), `DATABASE_SIZE_CHECK_INTERVAL_MS`
(1 h), `DATABASE_SIZE_THRESHOLDS_MB` (`300,350,400,450`), `DR_BACKUP_MAX_AGE_MS` (26 h),
`DR_BACKUP_CHECK_INTERVAL_MS` (30 min); e para os Jobs de DR, `SPARK_DR_RETENTION_COUNT`,
`SPARK_DR_WORK_DIR`, `SPARK_DR_MAX_DUMP_BYTES`, `SPARK_GIT_COMMIT`, `SPARK_IMAGE_DIGEST`,
`SPARK_DRILL_ADMIN_URL`, `SPARK_DRILL_DATABASE`, `SPARK_DRILL_KEEP_DATABASE`,
`SPARK_DRILL_REPLACE_EXISTING`, `SPARK_DR_BACKUP_ID`.
