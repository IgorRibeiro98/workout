# Spark — Recuperação de desastre

> **Estado:** `IMPLEMENTED` (procedimento, scripts, CLIs e ensaios; PostgreSQL desde a T18.0.2;
> DR independente do provedor desde a T18.3) · ensaio de ponta a ponta `VERIFIED` no CI
> (`ops/gcp/dr-backup-drill.sh`: `pg_dump` real → destino limpo → dado de volta → conta excluída não
> ressuscita) · backup real no bucket e ensaio contra o bucket real: ver o relatório da T18.3 para o
> estado (`VERIFIED` ou `NOT VERIFIED`) no momento da leitura.
>
> Desde a T18.0 o banco **não vive na VPS**: ele é o PostgreSQL de `DATABASE_URL` (Neon). Desde a
> T18.1, com `OBJECT_STORAGE_PROVIDER=gcs`, mídia, backups pessoais e o ledger de exclusões vivem no
> bucket. Desde a T18.2 não há VPS: Cloud Run. **A seção "Cloud Run" abaixo é a topologia real**;
> o procedimento VPS continua documentado para quem usa `docker-compose.prod.yml`.

## Cloud Run — o desenho de DR (T18.3)

```text
Cloud Scheduler (spark-db-backup-daily, 03:15 UTC)
      │  OAuth · roles/run.invoker sobre o Job
      ▼
Cloud Run Job spark-db-backup  (SA spark-backend-backup: só o secret DIRETO + o prefixo de DR do bucket)
      │  pg_dump --format=custom  (conexão direta/admin; senha só por variável da libpq, nunca argv)
      │  pg_restore --list · SHA-256 · upload · RELEITURA (tamanho + hash) · manifesto por ÚLTIMO
      ▼
gs://spark-private-assets-prod/system/dr/postgres/<backupId>/
      ├── database.dump
      └── manifest.json   ← sem ele o backup NÃO existe (retenção ignora, auditor aponta)
      │
      │  retenção: os 7 válidos mais recentes; nunca o último; incompletos/inválidos nunca tocados
      ▼
maintenance (a cada 30 min): idade do backup válido mais recente → db_backup_stale se > 26 h
deploy: gate — nenhuma migration sem backup válido ≤ 24 h (senão executa um e espera)
```

Por que independente do Neon: o backup é um `pg_dump` lógico guardado **fora** da conta do
provedor do banco. Perder o projeto Neon (conta, credencial, erro do provedor) não perde o backup;
e o ensaio abaixo prova que ele restaura num PostgreSQL qualquer.

O que **não** está no dump — e sobrevive por outro caminho:

| O quê | Onde vive | Sobrevive a um restore do PostgreSQL? |
| --- | --- | --- |
| fotos dos check-ins, documentos de backup pessoal | bucket (`social/`, `backups/`) | sim — o dump só tem a metadata; `spark-storage-audit` aponta referências quebradas |
| ledger anti-ressurreição | bucket (`system/deletion-tombstones/`) | sim — é **por isso** que ele está fora do banco |
| os próprios dumps de DR | bucket (`system/dr/postgres/`) | sim |
| secrets (URLs, Gemini, HMAC) | Secret Manager | sim |

### RPO e RTO (Cloud Run)

```text
backup diário (03:15 UTC) + backup pré-deploy quando o último tem > 24 h
RPO                                     até 24 h de estado REMOTO (o Room de cada aparelho não é afetado)

RTO — o PostgreSQL foi perdido:
  criar um banco novo (Neon ou outro PG 18)           5 min
  restaurar (db-restore-drill.js, ~1 GB de dump)     5–15 min
  reconcile-account-deletions                        1 min
  versões novas dos dois secrets + deploy            10 min
  ────────────────────────────────────────────────────────────
                                                     ~30–45 min, sem DNS a propagar
```

Aparelhos com cursor à frente do change log restaurado recebem `CURSOR_EXPIRED` e pedem rebaseline
explícito — o desenho da T16.7, não um efeito colateral.

### Antes de tudo: você tem estes?

```text
[ ] acesso ao projeto GCP (gcloud auth login) — o bucket, os secrets, o Cloud Run
[ ] a chave HMAC continua no Secret Manager (spark-account-deletion-hmac-key) — insubstituível
[ ] acesso ao provedor do banco (Neon), ou a capacidade de subir um PostgreSQL 18 em outro lugar
[ ] docker na máquina (para o ensaio local) — opcional para a recuperação real
```

Nada aqui exige a senha antiga do banco: o dump não a contém, e o banco novo terá a sua.

### Ensaio (faça isto ANTES de precisar)

```bash
export SPARK_GCP_PROJECT=project-47b17b25-909d-4ae8-943
gcloud auth application-default login          # ADC para o SDK do GCS dentro do container
ops/gcp/dr-restore-drill.sh --record            # o backup válido mais recente
ops/gcp/dr-restore-drill.sh --backup-id 2026-09-11T031500Z
```

O que ele faz: sobe um `postgres:18-alpine` descartável local (a major do Neon; `SPARK_DRILL_PG_IMAGE`
para outra) → roda `dist/cli/db-restore-drill.js`
**na imagem que está em produção** (ela lê o bucket com a sua ADC) → `CREATE DATABASE spark_drill_<ts>`
→ verifica SHA-256 e tamanho contra o manifesto → `pg_restore --single-transaction` (sem `--clean`:
não há o que limpar) → confere `schema_migrations` e a lista de tabelas **exatamente** iguais ao
manifesto (é a prova do destino limpo) → aplica as migrations que o código atual tiver a mais →
readiness (a mesma de `/health/ready`) → consultas essenciais → sobe o backend real sobre o banco
restaurado e confere `/health/ready` 200 e `/v1/*` 401 → **`RESTORE_DRILL_PASS`** → derruba tudo.
Nada aponta para produção: o script não recebe `DATABASE_URL`, e a CLI tampouco. `--record` grava o
veredito no Cloud Logging (`spark-dr-drill`), onde o alerta `spark-restore-drill-failed` o vigia.

O mesmo ensaio, com `pg_dump`/`pg_restore` reais, roda em todo CI (`ops/gcp/dr-backup-drill.sh`),
incluindo o cenário "snapshot antigo + tabela criada depois → a tabela NÃO existe no restaurado" e
o cenário "conta excluída depois do backup → não ressuscita".

### Procedimento — o PostgreSQL foi perdido (ou precisa voltar a um ponto anterior)

**Nunca restaure por cima do banco atual.** O destino é sempre um banco NOVO e limpo; o banco
antigo, se ainda existir, fica intacto até você decidir apagá-lo.

1. **Pare as escritas** (opcional, mas evita divergência durante a troca):
   `gcloud run services update spark-backend --region southamerica-east1 --update-env-vars SYNC_WRITE_ENABLED=false`
   — a Outbox dos aparelhos fica pendente (503), nada é perdido.

2. **Escolha o backup**: `ops/gcp/dr-status.sh --backups-only` lista os válidos (id, idade, schema,
   commit). Prefira o mais recente válido, salvo se o incidente for "dado corrompido às X horas".

3. **Restaure num banco novo.** A CLI é a mesma do ensaio, apontada para o servidor de destino
   (um projeto/branch novo no Neon, ou qualquer PostgreSQL 18). A conexão administrativa é um banco
   de **manutenção** desse servidor (`neondb`/`postgres`, com `CREATEDB`); o banco de destino nasce
   dentro da CLI, com nome no padrão `spark_drill_*`, e fica (`SPARK_DRILL_KEEP_DATABASE=true`):

   ```bash
   SPARK_DRILL_ADMIN_URL='postgresql://<user>:<senha>@<host-novo>/neondb?sslmode=require' \
   SPARK_DRILL_DATABASE=spark_drill_restored_20260911 \
   SPARK_DRILL_KEEP_DATABASE=true \
   SPARK_DR_BACKUP_ID=2026-09-11T031500Z \
   OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=spark-private-assets-prod \
   GOOGLE_APPLICATION_CREDENTIALS=$HOME/.config/gcloud/application_default_credentials.json \
     node dist/cli/db-restore-drill.js
   ```

   (ou o equivalente com `docker run` da imagem de produção, como `ops/gcp/dr-restore-drill.sh`
   faz). A CLI recusa: nome fora de `spark_drill_*`, nome igual ao banco do manifesto, conexão
   administrativa igual ao banco de produção do manifesto, banco de destino já existente (sem
   `SPARK_DRILL_REPLACE_EXISTING=true`), checksum divergente. Termina em `RESTORE_DRILL_PASS` com
   o banco `spark_drill_restored_20260911` pronto e migrado até o schema do código atual.

4. **Reconcilie as exclusões** — obrigatório, antes de apontar qualquer coisa para o banco:

   ```bash
   DATABASE_URL='postgresql://<user>:<senha>@<host-novo>/spark_drill_restored_20260911?sslmode=require' \
   OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=spark-private-assets-prod \
   ACCOUNT_DELETION_HMAC_KEY="$(gcloud secrets versions access latest --secret spark-account-deletion-hmac-key --project "$SPARK_GCP_PROJECT")" \
   NODE_ENV=production DATABASE_MIGRATION_MODE=verify BACKGROUND_JOBS_MODE=disabled \
     node dist/cli/reconcile-account-deletions.js
   ```

   O ledger no bucket conhece toda exclusão feita depois do backup; a reconciliação purga essas
   contas de novo e regrava o tombstone. **Com a chave HMAC errada ela encontra zero e reporta
   sucesso** — é por isso que a chave vem do Secret Manager direto para a variável do processo,
   nunca digitada, nunca gravada em arquivo nem no histórico do shell. `contas reconciliadas
   (purgadas de novo): N` é o número a registrar no incidente.

5. **Aponte produção para o banco novo**: versões novas dos dois secrets de URL
   (`gcloud secrets versions add spark-database-url --data-file=-` com a URL pooled do banco novo;
   idem `spark-database-url-direct` com a direta) e `ops/gcp/deploy-cloud-run.sh`. O deploy pina as
   versões novas, roda o gate de DR (que vai executar um backup do banco novo), a migration (no-op)
   e o smoke. Reative `SYNC_WRITE_ENABLED=true` se tiver desligado.

6. **Valide**: `ops/gcp/smoke-cloud-run.sh <url>`, `ops/gcp/config-drift-audit.sh`,
   `gcloud run jobs execute spark-storage-audit … --wait` (referências a fotos/backups pessoais que o
   restore trouxe de volta e que o bucket não tem mais aparecem como `MISSING_OBJECT` — são as
   exclusões/coletas ocorridas depois do backup; nada a fazer além de registrar).

7. **Retome o ritmo**: `ops/gcp/dr-status.sh` → um backup válido novo existe (o gate do deploy o
   produziu). O banco antigo, se existir, pode ser apagado depois de dias — nunca no mesmo dia.

### Procedimento — o bucket foi perdido

Não há cópia do bucket fora do bucket (decisão registrada: soft delete de 7 dias +
`public_access_prevention` + UBLA são a proteção contra exclusão acidental; um segundo bucket em
outra região é custo sem incidente que o justifique hoje). Perder o bucket perde fotos, backups
pessoais, o ledger e os dumps de DR. O que resta é o PostgreSQL (com a metadata apontando para
objetos inexistentes → `spark-storage-audit` classifica tudo como `MISSING_OBJECT`) e o histórico
de exclusões em `account_deletion_tombstones` no banco. Este é o único cenário sem recuperação
completa, e está registrado como risco aceito.

### Procedimento — o projeto GCP foi perdido

Bootstrap num projeto novo (`ops/gcp/bootstrap-cloud-run.sh`), valores novos dos secrets, e o
banco continua no Neon (não é afetado). O bucket não sobrevive ao projeto — ver acima. A chave HMAC
precisa ser **a mesma** (recupere-a do gerenciador de senhas onde uma cópia deve existir) ou toda
exclusão histórica deixa de ser reconhecida.

---

# VPS / Docker Compose — o procedimento original (T16.8 → T18.0.2)

> Aplica-se só à topologia `docker-compose.prod.yml`. No Cloud Run, use a seção acima.

## O princípio

Uma VPS não é backup. Um volume Docker não é backup. Um snapshot do provedor não é, sozinho, uma
estratégia de recuperação.

Este documento existe para responder a uma pergunta com um procedimento, e não com uma intenção:
**a VPS morreu; o que eu faço agora?**

## Contra o que estamos protegidos

| Cenário | O que recupera | Onde está |
| --- | --- | --- |
| Container removido | O bind mount (mídia, ledger) sobrevive; o banco está fora da VPS | `PRODUCTION_DEPLOYMENT.md` |
| Deploy ruim | Rollback de imagem por tag | `ops/deploy.sh --rollback <sha>` |
| Migration ruim | Backup pré-deploy (`pg_dump`) | [RUNBOOK.md](./RUNBOOK.md), "migration falhou" |
| Disco da VPS corrompido | Backup off-site (mídia + ledger); o banco está fora da VPS | Este documento |
| **VPS perdida** | **Backup off-site + o banco gerenciado continua no ar** | **Este documento** |
| **Banco (provedor) perdido ou corrompido** | **`pg_dump` off-site, restaurado num PostgreSQL novo** | **Este documento** |
| Erro operacional / exclusão acidental | Backup off-site, snapshot anterior | `ops/restore.sh --snapshot <id>` |

## RPO — quanto se pode perder

```text
Backup do servidor      diário (03:15 UTC) + pré-deploy
RPO off-site            até 24 h
```

**O que isso significa de verdade, e é menos assustador do que parece.** O Spark é local-first: o
Room de cada aparelho é a autoridade operacional, e ele não é afetado por nada aqui. Perder até 24 h
do servidor significa perder até 24 h de *estado remoto* — o que já subiu de sync e de backup nesse
intervalo. Os aparelhos ainda têm o dado, e a Outbox de cada um só é liberada com confirmação do
servidor: o que não foi confirmado continua pendente e sobe de novo.

A consequência prática de restaurar o servidor para um ponto anterior é que aparelhos com cursor à
frente do change log restaurado recebem `CURSOR_EXPIRED` e pedem rebaseline explícito — que é
exatamente o desenho da T16.7, e não um efeito colateral (§46).

**Não prometemos RPO menor porque não existe RPO menor.** Um backup mais frequente é possível
(`spark-backup.timer`), mas enquanto ele for diário, o número é 24 h.

## RTO — quanto tempo leva

```text
provisionar VPS + instalar Docker      15–30 min
clonar repositório e restaurar segredos 10–20 min   ← depende de você ter os segredos
baixar e restaurar o backup             5–15 min    ← depende do tamanho e da rede
subir e validar                          5–10 min
DNS propagar                             minutos a horas   ← fora do seu controle
──────────────────────────────────────────────────
RTO realista                            1–2 h de trabalho, mais a propagação de DNS
```

Sem SLA e sem promessa de automação: é uma recuperação manual, feita por uma pessoa, em um app
pessoal (§47). O maior risco de atraso não é técnico — é não encontrar os segredos.

## Procedimento — a VPS foi perdida

### 0. Antes de tudo: você tem estes três?

```text
[ ] a senha do repositório restic
[ ] a credencial de acesso ao storage off-site
[ ] a service account do Firebase Admin (ou acesso ao console para gerar outra)
[ ] a DATABASE_URL do PostgreSQL (ou acesso ao provedor para criar um banco novo)
```

**Sem a senha do restic, o backup é irrecuperável.** Nenhum procedimento neste documento contorna
isso — é o ponto inteiro da criptografia. Ver "Onde os segredos precisam estar", abaixo.

### 1. Provisionar a máquina

Ubuntu LTS. Siga [PRODUCTION_DEPLOYMENT.md](./PRODUCTION_DEPLOYMENT.md), passos 1 a 6: sistema,
usuário `spark`, SSH por chave, firewall, Docker, diretórios.

### 2. Restaurar os segredos

Em `/opt/spark/secrets`, com permissão `600` (ver [SECURITY.md](./SECURITY.md)):

```text
firebase-admin.json     do gerenciador de segredos, ou gerada de novo no console do Firebase
backend.env             GEMINI_API_KEY + ACCOUNT_DELETION_HMAC_KEY
restic-password         do gerenciador de segredos / cópia física
backup.env              RESTIC_REPOSITORY + credencial do storage
backend/.env            DATABASE_URL (a do banco que sobreviveu, ou a do banco novo do passo 4)
```

**`ACCOUNT_DELETION_HMAC_KEY` é tão insubstituível quanto a senha do restic.** Ela é o que liga cada
linha do ledger de exclusões a um uid do banco. Sem ela o servidor não sobe em produção (é
exigência de startup), e a reconciliação pós-restore roda **sem encontrar nada e reportando
sucesso** — devolvendo ao ar todas as contas que haviam sido excluídas. Ela não está no backup, por
construção: é segredo de runtime.

### 3. Recuperar o repositório e a imagem

```bash
sudo -u spark git clone <url-do-repo> /opt/spark/repo
cd /opt/spark/repo/backend && cp Caddyfile.prod Caddyfile
```

### 4. Restaurar o banco, a mídia e o ledger

Decida primeiro **qual banco** é o destino:

- **a VPS morreu, o banco gerenciado está no ar** — o cenário mais provável. O banco não precisa
  ser restaurado: aponte `DATABASE_URL` para ele. O que falta na VPS nova é a mídia e o ledger, e
  eles vêm do snapshot (ver "só a mídia e o ledger", abaixo). Só restaure o dump por cima se a
  intenção for **voltar o banco** ao ponto do snapshot;
- **o banco foi perdido ou corrompido** — crie um PostgreSQL novo (um projeto/branch no Neon, ou
  qualquer PostgreSQL 17), coloque a `DATABASE_URL` dele em `backend/.env` e restaure o dump.

```bash
cd /opt/spark/repo
set -a; . /opt/spark/secrets/backup.env; set +a

restic snapshots --host spark --tag spark-db     # veja o que existe e escolha
ops/restore.sh --to /tmp/dr                      # extrai e VERIFICA, sem tocar em nada
ops/restore.sh --to /tmp/dr --install            # pg_restore em DATABASE_URL + mídia + ledger
```

`--install` só age depois de `pg_restore --list` passar e de `schema_migrations` existir no dump.
Antes de tocar no banco ele tira um dump do estado atual (`/opt/spark/backups/pre-restore-*.dump`)
e restaura em transação única — ou tudo entra, ou nada muda. Ele recusa rodar com o backend de pé.

Para restaurar **só a mídia e o ledger** (banco gerenciado intacto e mais novo que o snapshot):
extraia com `ops/restore.sh --to /tmp/dr`, copie a árvore `media` restaurada para
`/opt/spark/media` e una o ledger a `/opt/spark/data/deletion_tombstones.tsv`, com o mesmo modelo
de permissão (`2770`/`640` no grupo `spark-data`, ver PRODUCTION_DEPLOYMENT.md) — e rode a
reconciliação (`node dist/cli/reconcile-account-deletions.js`) antes de subir.

Ele também **instala o ledger de exclusões e roda a reconciliação anti-ressurreição** antes de
declarar a restauração completa (T17.13.1). Numa recuperação a partir do zero o ledger vem do
próprio snapshot; se ele não estiver nem lá nem no disco, o `--install` **para** — sem ele não há
como saber quais contas já foram excluídas, e prosseguir as devolveria ao ar. Ver
[../runbooks/account-deletion-dr.md](../runbooks/account-deletion-dr.md).

### 4b. Cloud Run

Ver a seção "Cloud Run — o desenho de DR (T18.3)" no topo deste documento: o PostgreSQL é restaurado
num banco **novo** a partir do dump de DR no bucket, a reconciliação de exclusões roda antes de
apontar produção, e nada é restaurado por cima.

### 5. Subir

```bash
cd /opt/spark/repo
docker build -t spark-backend:recuperacao backend
cd backend
cat > .env <<EOF
SPARK_DOMAIN=api.seudominio.com
SPARK_ACME_EMAIL=voce@seudominio.com
# O gid do grupo compartilhado NESTA máquina — ele quase certamente não é o mesmo da VPS antiga,
# e não precisa ser: o modelo é por grupo, nunca por número fixo.
SPARK_DATA_GID=$(getent group spark-data | cut -d: -f3)
DATABASE_URL=postgresql://...            # o banco do passo 4
EOF
SPARK_IMAGE_TAG=recuperacao docker compose -f docker-compose.prod.yml up -d
```

Antes do DNS, confirme que o backend serve — o health interno não depende de domínio nem de
certificado, e separá-lo do público evita perseguir um problema de DNS achando que é de banco:

```bash
cd /opt/spark/repo && ops/check-health.sh
```

### 6. DNS

Aponte `api.<dominio>` para o IP **novo**. O Caddy emite o certificado sozinho assim que o nome
resolver e a porta 80 estiver acessível.

```bash
dig +short api.seudominio.com          # precisa devolver o IP novo
```

### 7. Validar, nesta ordem

```bash
curl -s https://api.seudominio.com/health/live      # {"status":"ok"}
curl -s https://api.seudominio.com/health/ready     # {"status":"ok", checks: todos true}

# Firebase Auth ponta a ponta: sem token, 401 (e não 404 nem 503)
curl -s -o /dev/null -w '%{http_code}\n' https://api.seudominio.com/v1/auth/me    # 401
# Com um ID Token real de uma conta de teste:
curl -s -H "Authorization: Bearer <id-token>" https://api.seudominio.com/v1/auth/me   # {"uid": ...}
```

`401` sem token prova que a rota existe e nasce fechada. `503` ali significaria service account
ausente ou inválida — volte ao passo 2.

No app, com uma **conta de teste** (§111):

```text
[ ] entrar na Conta Spark
[ ] Coach responde (ou responde indisponível, se AI_ENABLED=false)
[ ] listar backups: os snapshots anteriores aparecem
[ ] sync: push e pull convergem
[ ] um conflito existente ainda pode ser lido
```

E o portão anti-ressurreição, no servidor (T17.13.1):

```bash
# A reconciliação já rodou dentro do --install. Confira que ela encontrou o ledger:
sort -u /opt/spark/data/deletion_tombstones.tsv | wc -l
psql "$DATABASE_URL" -c "SELECT COUNT(*) FROM account_deletion_tombstones;"
```

A tabela precisa cobrir todas as contas do ledger. Se o número de tombstones for **zero** com um
ledger não vazio, a reconciliação rodou com a chave HMAC errada — pare e volte ao passo 2 antes de
apontar o DNS: o servidor está servindo contas que deveriam estar excluídas.

Nada destrutivo nessa validação: não apague backup, não force restore, não resolva conflito real.

### 8. Retomar o backup

```bash
sudo install -m 644 /opt/spark/repo/ops/systemd/spark-*.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now spark-backup.timer spark-check-health.timer
sudo systemctl start spark-backup.service
ops/verify-backup.sh                    # ensaio na máquina nova
```

**A recuperação não está concluída até o backup voltar a rodar.** Uma VPS restaurada sem backup é
a mesma situação de antes do desastre, com a diferença de que agora você sabe que ela acontece.

## O que a restauração do servidor NÃO faz

Ela **não** restaura o Room de nenhum aparelho (§49). O que volta é:

```text
backup_snapshots     os snapshots que os usuários enviaram (T16.4)
sync_entities        o estado remoto por agregado, com revision e tombstones
sync_changes         o change log
sync_mutations       o ledger de idempotência
ai_usage_daily       contagem de uso do Coach, sem conteúdo
```

E, fora do banco, na VPS, o ledger de exclusões (`deletion_tombstones.tsv`) — que não é dado de
usuário, e sim a lista de quem **não** pode voltar — e a mídia dos check-ins.

O treino de cada pessoa continua no aparelho dela. Quem perdeu o **aparelho** usa o restore da
T16.5, dentro do app.

## Onde os segredos precisam estar

Este documento **não contém segredo algum** (§114). O que ele registra é *onde eles precisam estar
no momento da recuperação*:

| Segredo | Precisa estar disponível fora da VPS | Sem ele |
| --- | --- | --- |
| Senha do repositório restic | **Sim, obrigatoriamente** | O backup é irrecuperável |
| Credencial do storage off-site | Sim | Não dá para baixar o backup |
| Service account do Firebase Admin | Não — pode ser gerada de novo no console | Rota autenticada responde 503 |
| Chave do Gemini | Não — pode ser gerada de novo | Coach indisponível; o resto funciona |
| `DATABASE_URL` / acesso ao provedor do banco | Sim — ou a capacidade de criar um banco novo e restaurar o dump | Sem banco não há servidor |
| `ACCOUNT_DELETION_HMAC_KEY` | **Sim, obrigatoriamente** | Contas excluídas voltam ao ar numa restauração |

Um gerenciador de senhas resolve todos. A senha do restic e a chave HMAC merecem também uma cópia
física em lugar seguro: são as duas cujo esquecimento é definitivo.

## Ensaio periódico

`ops/verify-backup.sh` **é** o ensaio de recuperação executável, e roda contra o backup real sem
tocar em produção. Rode-o depois do primeiro backup e sempre que a senha, o destino ou o schema
mudarem — e, idealmente, uma vez por trimestre por hábito.

A parte que ele não ensaia é a que depende de acesso humano: provisionar máquina, restaurar
segredos, mexer no DNS. Essa é a parte que costuma custar o tempo real de um desastre — vale ler
este documento antes de precisar dele.
