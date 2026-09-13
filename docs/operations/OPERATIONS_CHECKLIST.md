# Spark — Checklist operacional (Cloud Run, T18.3)

> Um roteiro por situação, com comandos copiáveis. Cada comando é somente leitura, salvo onde
> está escrito **muda**. Nenhum imprime segredo. Pré-requisitos: `gcloud` autenticado, `jq`,
> `docker` (para o ensaio de restauração), e:
>
> ```bash
> export SPARK_GCP_PROJECT=project-47b17b25-909d-4ae8-943
> export SPARK_FIREBASE_PROJECT=spark-36b11
> export SPARK_GCP_REGION=southamerica-east1
> ```

## Toda semana (5 minutos)

```bash
ops/gcp/dr-status.sh                       # existe backup válido ≤ 24 h? o maintenance está vivo?
ops/gcp/config-drift-audit.sh              # a configuração real é a esperada?
ops/gcp/iam-audit.sh                       # as permissões continuam mínimas?
ops/gcp/cost-audit.sh                      # max-instances=1, um bucket, um repositório, budget, nada inesperado?
gcloud run jobs execute spark-storage-audit --region "$SPARK_GCP_REGION" --project "$SPARK_GCP_PROJECT" --wait   # muda: nada (só lê); gera relatório
gcloud logging read 'jsonPayload.event="storage_audit_completed"' --project "$SPARK_GCP_PROJECT" --limit 1 --format='value(timestamp,jsonPayload.status,jsonPayload.ORPHAN_OBJECT,jsonPayload.MISSING_OBJECT,jsonPayload.TOMBSTONE_INCONSISTENT)'
```

Esperado: `dr-status: OK`, três `PASS`, `storage_audit_completed` com `status=CLEAN`. Qualquer
`DRIFT` ou achado → [`RUNBOOK.md`](./RUNBOOK.md).

## Todo mês (30 minutos)

```bash
ops/gcp/dr-restore-drill.sh --record       # muda: nada em produção (PostgreSQL local descartável); registra o veredito no Cloud Logging
ops/gcp/artifact-registry-retention.sh     # a política protege todos os digests em uso?
ops/gcp/monitoring-alerts.sh --list        # os 12 alertas e o canal continuam lá?
```

Esperado: `RESTORE_DRILL_PASS`, `retenção do Artifact Registry: PASS`.

## Antes de um deploy

```bash
git status --porcelain                     # vazio: o deploy recusa árvore suja
ops/gcp/dr-status.sh --backups-only        # com backup ≤ 24 h o deploy segue direto; sem, ele executa um e espera
ops/gcp/config-drift-audit.sh              # não faça deploy por cima de um drift que você não entendeu
```

## O deploy

**Caminho canônico (T18.3.2): GitHub Actions**, sem sessão pessoal de `gcloud`, sem Docker local,
sem chave de Service Account:

```bash
gh workflow run deploy-backend.yml --ref main
gh run watch "$(gh run list --workflow deploy-backend.yml --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
```

Se o Environment `production` tiver Required reviewers configurado, o run fica em "Waiting" até
alguém aprovar — isso é esperado, não uma falha. Ver
[`AGENT_DEPLOYMENT.md`](./AGENT_DEPLOYMENT.md) e
[`CLOUD_RUN_DEPLOYMENT.md` §20](./CLOUD_RUN_DEPLOYMENT.md#20-deploy-via-github-actions-e-workload-identity-federation-t1832).

**Break-glass (GitHub indisponível, investigação operacional, manutenção extraordinária) —**
rodar `ops/gcp/deploy-cloud-run.sh` localmente continua funcionando, com a sessão `gcloud` pessoal
do operador:

```bash
ops/gcp/deploy-cloud-run.sh                # muda: jobs (backup, audit, migrate) + candidate → smoke → tráfego + maintenance + schedulers
```

Nos dois caminhos o motor é o mesmo script, e faz, nesta ordem, o mesmo trabalho — o GitHub Actions
não duplica nada disto, só fornece identidade e orquestração:
build (sem provenance/SBOM) → push → digest → **versões dos secrets** (pinadas; sem versão
habilitada, para) → jobs `spark-db-backup` e `spark-storage-audit` → **gate de DR** (backup válido ≤
`SPARK_DR_MAX_BACKUP_AGE_HOURS`, senão executa `spark-db-backup` e espera; com
`SPARK_DR_PREDEPLOY_POLICY=fail` aborta) → job `spark-db-migrate` (falha aborta) → candidate
`--no-traffic` → smoke → 100% do tráfego → `spark-maintenance` → schedulers.

O deploy imprime `secrets pinados nesta release: …=vN …` — é a correlação revision → versão de
secret. Guarde-a com o relatório do deploy (no caminho do GitHub Actions, isto vai para o log do
job "Deploy"; o Step Summary do run traz commit, revision, digest e tráfego).

## Depois de um deploy

```bash
ops/gcp/smoke-cloud-run.sh "$(gcloud run services describe spark-backend --region "$SPARK_GCP_REGION" --project "$SPARK_GCP_PROJECT" --format='value(status.url)')"
ops/gcp/config-drift-audit.sh
ops/gcp/artifact-registry-retention.sh
gcloud scheduler jobs describe spark-maintenance-cycle --location "$SPARK_GCP_REGION" --project "$SPARK_GCP_PROJECT" --format='value(state,status.code,lastAttemptTime)'
```

## Rollback

```bash
ops/gcp/rollback-cloud-run.sh --list
ops/gcp/rollback-cloud-run.sh <revision-anterior>          # muda: tráfego
ops/gcp/smoke-cloud-run.sh <url do serviço>
```

Rollback de aplicação **não** desfaz migration (as migrations são aditivas por decisão da T18.2).

Ensaio de rollback (prova que voltar funciona, e volta):

```bash
ops/gcp/rollback-drill.sh --to <revision-B>                # muda: tráfego, A → B → A, smoke em cada passo
```

## Rotação de um secret (muda)

1. `gcloud secrets versions add <secret> --data-file=-` com o valor novo (nunca via argv).
2. `ops/gcp/deploy-cloud-run.sh` — o deploy resolve a versão habilitada mais recente e cria
   revisions novas apontando para ela. Até o deploy, produção continua na versão pinada anterior:
   **rotacionar sem deploy não muda nada**, por desenho (§19).
3. `ops/gcp/config-drift-audit.sh` — `PASS` em "pinado" para cada secret; `DRIFT` se alguma
   revision ainda aponta para a versão antiga.
4. Só depois disso desabilite a versão antiga (`gcloud secrets versions disable`).

**`spark-account-deletion-hmac-key` não é rotacionada** — trocá-la faria todos os tombstones
existentes deixarem de casar (ressurreição silenciosa). Ver [`SECURITY.md`](./SECURITY.md).

## Verificar a autenticação Firebase real (NOT VERIFIED até execução manual)

O smoke prova que `/v1/auth/me` responde `401` sem token. Para provar o caminho completo
(Firebase `spark-36b11` → ID token → Cloud Run → Firebase Admin por ADC → `200`):

1. Numa conta de **teste** (nunca a principal), obtenha um ID token — pelo app em debug (log do
   `FirebaseAuth.currentUser.getIdToken`) ou pela REST API do Identity Toolkit com a Web API key
   do projeto. **Não** cole o token em arquivo, issue, commit nem histórico de shell que seja
   sincronizado: use uma variável de ambiente de vida curta.
2. `SPARK_SMOKE_FIREBASE_ID_TOKEN="<token>" ops/gcp/smoke-cloud-run.sh <url do serviço>` — o script
   faz uma única chamada com o header e não imprime o token.
3. Esperado: `OK: GET /v1/auth/me → 200`. Um `503` significa Firebase Admin sem credencial/IAM
   (`roles/firebaseauth.admin` no projeto Firebase para `spark-backend-runtime`); um `401` com token
   válido significa `FIREBASE_PROJECT_ID` errado na revision.
4. Descarte o token (`unset SPARK_SMOKE_FIREBASE_ID_TOKEN`). O token expira em 1 h de qualquer forma.

## Incidente: "não existe backup recente"

```bash
ops/gcp/dr-status.sh
gcloud run jobs executions list --job spark-db-backup --region "$SPARK_GCP_REGION" --project "$SPARK_GCP_PROJECT" --limit 5
gcloud logging read 'resource.type="cloud_run_job" AND jsonPayload.event="db_backup_failed"' --project "$SPARK_GCP_PROJECT" --limit 3 --format='value(timestamp,jsonPayload.step,jsonPayload.errorName,jsonPayload.errorMessage)'
ops/gcp/dr-backup-now.sh                   # muda: um backup novo no bucket
```

`step` diz onde parou: `pg_dump` (banco/credencial/versão), `upload do dump` (IAM do bucket para a
backup SA — condição de prefixo), `releitura` (integridade), `retenção`.

## Incidente: "preciso restaurar produção"

Nunca por cima. Ver [`DISASTER_RECOVERY.md`](./DISASTER_RECOVERY.md), "Cloud Run — o PostgreSQL foi
perdido". Em resumo: banco **novo** → `db-restore-drill.js` com `--keep` (ou o mesmo fluxo à mão)
→ `reconcile-account-deletions` → versões novas dos dois secrets de URL → `deploy-cloud-run.sh`.

## Budget (muda, e só pela Console)

Budget é **alerta, nunca teto**: o Google não interrompe serviço ao estourar. Habilite a API
`billingbudgets.googleapis.com` na conta de billing e crie um budget mensal com alertas em 50/90/100%
(Console → Billing → Budgets & alerts). `ops/gcp/cost-audit.sh` reporta `NOT_VERIFIED` enquanto a
API não estiver acessível e `DRIFT` sem nenhum budget. O que limita o custo de verdade é
`max-instances=1` + scale-to-zero, e é isso que a auditoria confere.
