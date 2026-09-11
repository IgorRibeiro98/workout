# `ops/` — scripts operacionais do Spark (T16.8; PostgreSQL desde a T18.0.2)

Scripts que rodam **na VPS**, contra o banco e o backup de produção — a topologia Docker Compose.
Eles são versionados de propósito: infraestrutura escrita em um terminal e não guardada em lugar
nenhum é infraestrutura que ninguém consegue repetir depois de um desastre.

> Para a topologia **Cloud Run** (T18.2), os scripts equivalentes vivem em
> [`ops/gcp/`](./gcp/) — bootstrap, deploy, smoke e rollback. Ver
> [`docs/operations/CLOUD_RUN_DEPLOYMENT.md`](../docs/operations/CLOUD_RUN_DEPLOYMENT.md).

O banco é o PostgreSQL de `DATABASE_URL` (T18.0). Os scripts o alcançam com `pg_dump`,
`pg_restore` e `psql` rodando **por container** (`SPARK_PG_TOOLS_IMAGE`, `postgres:17-alpine`),
com `--network host`, e leem a connection string de `SPARK_DATABASE_URL`, de `DATABASE_URL` ou do
`.env` do compose — nunca a imprimem. Não há mais arquivo de banco em `/opt/spark/data`: o que
vive lá é o ledger de exclusões, e o modelo de permissão por grupo compartilhado continua valendo
para ele e para a mídia.

**Nenhum segredo mora aqui.** Configuração e credencial vêm de um arquivo fora do Git —
`ops/spark-backup.env.example` mostra a forma, e o real vive em `/opt/spark/secrets/backup.env`
com permissão `600`.

| Script | O que faz |
| --- | --- |
| `lib.sh` | Funções compartilhadas: configuração, log, `flock`, ferramentas do PostgreSQL por container (`pg_run`), indireção do restic. Não roda sozinho. |
| `snapshot.sh` | Snapshot **consistente** do PostgreSQL com o banco ativo (`pg_dump --format=custom`, transação única de leitura) + verificação do arquivo (`pg_restore --list`, presença de `schema_migrations`). Imprime o caminho em stdout. |
| `backup.sh` | `snapshot.sh` → ledger de exclusões → manifesto → restic (criptografado, off-site) → retenção → estado. Diário e antes de cada deploy. |
| `restore.sh` | Restaura do off-site (ou de um arquivo), **verifica**, e só com `--install` troca o banco de produção (`pg_restore --single-transaction`, depois de preservar um dump do estado atual) — instalando também o ledger de exclusões e rodando a reconciliação anti-ressurreição antes de declarar a restauração completa (T17.13.1). |
| `verify-backup.sh` | Ensaio de restauração: restaura o dump num banco **descartável** (`SPARK_DRILL_DATABASE_URL`) e **sobe o backend real sobre ele** exigindo `/health/ready`. Recusa rodar sobre o banco de produção. |
| `check-health.sh` | Saúde do backend (**por dentro do container**: produção não publica porta), alcance do PostgreSQL pelas ferramentas do operador, permissões, disco e idade do último backup. Sai ≠ 0 quando algo está errado. O health público é separado e opcional (`SPARK_PUBLIC_HEALTH_URL`). |
| `deploy.sh` | Backup pré-deploy → build com tag do commit → `up` → health **interno** → rollback se falhar. |
| `tests/permissions.test.sh` | Prova o modelo de grupo compartilhado com uid diferente entre host e container (ledger e service account). Precisa de `SPARK_TEST_DATABASE_URL`. Roda no CI. |
| `tests/backup-status.test.sh` | Prova que todo desfecho do backup — sucesso e as seis falhas — chega a `backup-status.json`. Precisa de `SPARK_TEST_DATABASE_URL`. Roda no CI. |
| `systemd/` | Unidades e timers para o backup diário e a verificação horária. |

## Convenções

- **stdout é dado, stderr é narrativa.** `snapshot.sh` e `restore.sh` imprimem o caminho do arquivo
  produzido em stdout; todo log vai para stderr. Misturar os dois fazia o chamador receber o log
  como se fosse o caminho — um defeito real, encontrado no smoke local da T16.8.
- **Nada destrutivo sem verificação.** `restore.sh --install` tira um dump do banco atual antes de
  restaurar, restaura em transação única e recusa rodar com o backend de pé; `backup.sh` só poda o
  repositório **depois** de o backup novo estar confirmado.
- **Falha é ruidosa.** Código de saída ≠ 0, mensagem em stderr e estado gravado em disco. Backup
  que falha em silêncio é pior que não ter backup: ele dá confiança.
- **`shellcheck` é gate de CI** (`.github/workflows/backend.yml`).
- **Restauração não termina em lembrete.** `restore.sh --install` executa
  `node dist/cli/reconcile-account-deletions.js` sobre o banco recém-instalado, e uma falha ali
  falha o `--install` inteiro. Antes da T17.13.1 o script imprimia "rode a reconciliação depois" e
  apontava para um runbook que descrevia um comando que não existia — na prática, restaurar um
  snapshot anterior a uma exclusão devolvia a conta excluída ao ar. Ver
  [`docs/runbooks/account-deletion-dr.md`](../docs/runbooks/account-deletion-dr.md).

## Uso local, sem VPS

```bash
docker build -t spark-backend:local backend
# Um PostgreSQL local já migrado (backend/docker-compose.yml) e um banco descartável para o ensaio.
export SPARK_DATABASE_URL=postgresql://spark:spark@127.0.0.1:5432/spark_dev
export SPARK_DRILL_DATABASE_URL=postgresql://spark:spark@127.0.0.1:5432/spark_drill
export SPARK_DATA_DIR=/tmp/spark/data SPARK_STAGING_DIR=/tmp/spark/backups \
       SPARK_STATE_DIR=/tmp/spark/state SPARK_IMAGE=spark-backend:local \
       SPARK_COMPOSE_DIR=/tmp/sem-compose SPARK_BACKUP_ENV_FILE=/dev/null

ops/snapshot.sh                                   # pg_dump + verificação do arquivo, sem off-site
ops/verify-backup.sh --from-file <snapshot>       # ensaio completo, sem credencial de storage
```

O que este diretório **não** faz ainda: PITR/arquivamento de WAL e a política definitiva de backup
do PostgreSQL gerenciado — isso é a T18.3. O `pg_dump` diário é o backup lógico completo que
substitui o snapshot do SQLite com as mesmas garantias verificadas no CI.

Documentação: [`../docs/operations/`](../docs/operations/).
