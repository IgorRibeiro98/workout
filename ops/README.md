# `ops/` — scripts operacionais do Spark (T16.8)

Scripts que rodam **na VPS**, contra o banco e o backup de produção. Eles são versionados de
propósito: infraestrutura escrita em um terminal e não guardada em lugar nenhum é infraestrutura
que ninguém consegue repetir depois de um desastre.

**Nenhum segredo mora aqui.** Configuração e credencial vêm de um arquivo fora do Git —
`ops/spark-backup.env.example` mostra a forma, e o real vive em `/opt/spark/secrets/backup.env`
com permissão `600`.

| Script | O que faz |
| --- | --- |
| `lib.sh` | Funções compartilhadas: configuração, log, `flock`, acesso ao SQLite, indireção do restic. Não roda sozinho. |
| `snapshot.sh` | Snapshot **consistente** do SQLite com o banco ativo (`VACUUM INTO`) + `integrity_check` + `foreign_key_check`. Imprime o caminho em stdout. |
| `backup.sh` | `snapshot.sh` → manifesto → restic (criptografado, off-site) → retenção → estado. Diário e antes de cada deploy. |
| `restore.sh` | Restaura do off-site (ou de um arquivo), **verifica**, e só com `--install` troca o banco de produção — instalando também o ledger de exclusões e rodando a reconciliação anti-ressurreição antes de declarar a restauração completa (T17.13.1). |
| `verify-backup.sh` | Ensaio de restauração: restaura, verifica e **sobe o backend real sobre a cópia** exigindo `/health/ready`. Não toca em produção. |
| `check-health.sh` | Saúde do backend (**por dentro do container**: produção não publica porta), acessibilidade do banco, permissões, disco e idade do último backup. Sai ≠ 0 quando algo está errado. O health público é separado e opcional (`SPARK_PUBLIC_HEALTH_URL`). |
| `deploy.sh` | Backup pré-deploy → build com tag do commit → `up` → health **interno** → rollback se falhar. |
| `tests/permissions.test.sh` | Prova o modelo de grupo compartilhado com uid diferente entre host e container. Roda no CI. |
| `tests/backup-status.test.sh` | Prova que todo desfecho do backup — sucesso e as seis falhas — chega a `backup-status.json`. Roda no CI. |
| `systemd/` | Unidades e timers para o backup diário e a verificação horária. |

## Convenções

- **stdout é dado, stderr é narrativa.** `snapshot.sh` e `restore.sh` imprimem o caminho do arquivo
  produzido em stdout; todo log vai para stderr. Misturar os dois fazia o chamador receber o log
  como se fosse o caminho — um defeito real, encontrado no smoke local da T16.8.
- **Nada destrutivo sem verificação.** `restore.sh` preserva o banco anterior e recusa rodar com o
  backend de pé; `backup.sh` só poda o repositório **depois** de o backup novo estar confirmado.
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
export SPARK_DATA_DIR=/tmp/spark/data SPARK_STAGING_DIR=/tmp/spark/backups \
       SPARK_STATE_DIR=/tmp/spark/state SPARK_IMAGE=spark-backend:local \
       SPARK_COMPOSE_DIR=/tmp/sem-compose SPARK_BACKUP_ENV_FILE=/dev/null

ops/snapshot.sh                                   # snapshot + integridade, sem off-site
ops/verify-backup.sh --from-file <snapshot>       # ensaio completo, sem credencial de storage
```

Documentação: [`../docs/operations/`](../docs/operations/).
