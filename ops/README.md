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
| `restore.sh` | Restaura do off-site (ou de um arquivo), **verifica**, e só com `--install` troca o banco de produção. |
| `verify-backup.sh` | Ensaio de restauração: restaura, verifica e **sobe o backend real sobre a cópia** exigindo `/health/ready`. Não toca em produção. |
| `check-health.sh` | Saúde do backend, acessibilidade do banco, disco e idade do último backup. Sai ≠ 0 quando algo está errado. |
| `deploy.sh` | Backup pré-deploy → build com tag do commit → `up` → healthcheck → rollback se falhar. |
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
