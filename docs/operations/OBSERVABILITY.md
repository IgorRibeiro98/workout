# Spark — Observabilidade operacional (T18.3)

> **Estado:** `IMPLEMENTED` (código, scripts, testes offline) · alertas reais no Cloud Monitoring
> aplicados por `ops/gcp/monitoring-alerts.sh` · **`VERIFIED` em 2026-09-28** para o canal de e-mail
> e três políticas (`spark-run-5xx`, `spark-scheduler-failed`, `spark-maintenance-stale`), que
> dispararam no incidente real da franquia do Neon e chegaram ao operador; as demais `NOT VERIFIED`
> até dispararem. Ver o vocabulário em [`README.md`](./README.md).

Este documento responde: **como eu sei que algo quebrou antes de um usuário me contar?** — e, para
cada sinal, o que fazer com ele.

## O modelo

```text
processo (API / maintenance / jobs)
   │  logs JSON estruturados (pino em stdout; `event`, `operation`, `status`, `durationMs`, ...)
   ▼
Cloud Logging
   │  métricas log-based (uma por evento crítico)
   ▼
Cloud Monitoring ── políticas de alerta ── canal de e-mail (spark-ops-email)
```

Duas camadas, deliberadamente:

1. **O processo mede a si mesmo e persiste.** O ciclo de manutenção grava o próprio heartbeat em
   `server_metadata` e o expõe em `GET /internal/maintenance/status`; mede `pg_database_size` e o
   frescor do backup de DR. Nada disso depende do Cloud Monitoring existir — `ops/gcp/dr-status.sh`
   lê tudo pela CLI.
2. **O Cloud Monitoring reage aos eventos.** Um alerta de evento é *log-based alerting* (casa a
   entrada de log diretamente, sem depender de métrica); os de taxa/ausência usam métricas
   **nativas** do Cloud Run (`request_count`, `job/completed_task_attempt_count`). As métricas
   log-based `spark_*` existem para painéis e consultas — nenhuma política depende delas, porque
   uma métrica log-based recém-criada leva dezenas de minutos para o alerting a reconhecer.

## Os eventos estruturados

Todo evento carrega, quando aplicável: `event`, `operation`, `status`, `durationMs`, `backupId`,
`objectKey`, `sizeBytes`, `sha256`, `databaseSizeBytes`, `level`, `threshold`, `errorName`,
`imageDigest`. Nunca: connection string, senha, HMAC, chave de provider de IA (Gemini/Groq), token, conteúdo de usuário.

| Evento | Emitido por | Significa |
| --- | --- | --- |
| `db_backup_started` / `db_backup_completed` / `db_backup_failed` | Job `spark-db-backup` (`db-backup.js`) | backup de DR do PostgreSQL |
| `db_backup_retention_removed` / `db_backup_retention_ignored` | idem | retenção agiu / pasta inválida ignorada |
| `db_restore_started` / `db_restore_completed` / `db_restore_failed` | `db-restore-drill.js` | ensaio de restauração (status `RESTORE_DRILL_PASS`/`FAIL`) |
| `db_restore_drill_completed` | `ops/gcp/dr-restore-drill.sh --record` | veredito do ensaio do operador, no log `spark-dr-drill` |
| `maintenance_started` / `maintenance_completed` / `maintenance_failed` | `spark-maintenance` | um ciclo (a cada 15 min desde a T19.H6). `maintenance_completed` carrega `databaseStartedAt`/`databaseUptimeMs` — o compute do banco que atendeu o ciclo; `maintenance_failed` carrega `stage`: `database` (o banco não respondeu — conexão, `BEGIN` ou lock — com o `errorCode` SQLSTATE/de rede, nunca a mensagem) ou `workers` |
| `maintenance_stale` | `spark-maintenance` | o ciclo voltou depois de mais de `MAINTENANCE_STALE_AFTER_MS` (35 min) sem sucesso |
| `maintenance_locked_stale` (ERROR) | `spark-maintenance` | a chamada não conseguiu o lock **e** o heartbeat já está velho: o ciclo está preso; `POST /internal/maintenance/run` responde **503** (o Scheduler registra erro; `spark-maintenance-stale` dispara quando a ausência de `2xx` passa de 45 min) |
| `database_compute_long_uptime` (ERROR) | `spark-maintenance` | o compute do PostgreSQL está de pé há mais de `DATABASE_COMPUTE_UPTIME_WARN_MS` (6 h) sem suspender — uma vez por compute (T19.H6) |
| `database_size_checked` / `database_size_threshold_exceeded` | `spark-maintenance` | `pg_database_size` e o nível (ver limiares) |
| `db_backup_freshness_checked` / `db_backup_stale` | `spark-maintenance` | idade do backup válido mais recente × `DR_BACKUP_MAX_AGE_MS` |
| `storage_audit_completed` / `storage_audit_issue` | Job `spark-storage-audit` | auditoria PostgreSQL ↔ GCS (contagens por classe; as chaves vão no relatório em stdout) |
| `migration_started` / `migration_completed` / `migration_failed` | Job `spark-db-migrate` | migration (JSON em stdout) |

Consulta rápida:

```bash
# os últimos ciclos de manutenção
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="spark-maintenance" AND jsonPayload.event="maintenance_completed"' \
  --project "$SPARK_GCP_PROJECT" --limit 5 --format='value(timestamp,jsonPayload.durationMs,jsonPayload.accountDeletionJobsProcessed)'

# o último backup de DR
gcloud logging read 'resource.type="cloud_run_job" AND jsonPayload.event="db_backup_completed"' \
  --project "$SPARK_GCP_PROJECT" --limit 1 --format='value(timestamp,jsonPayload.backupId,jsonPayload.sizeBytes,jsonPayload.sha256)'

# tamanho do banco e nível
gcloud logging read 'jsonPayload.event="database_size_checked"' \
  --project "$SPARK_GCP_PROJECT" --limit 1 --format='value(timestamp,jsonPayload.databaseSizeMb,jsonPayload.level)'

# o banco dorme? — compute por ciclo nas últimas 24 h (ver "O banco dorme?" abaixo)
gcloud logging read 'resource.labels.service_name="spark-maintenance" AND jsonPayload.event="maintenance_completed"' \
  --project "$SPARK_GCP_PROJECT" --freshness=1d --limit 200 --format=json \
  | jq '{ciclos: length, computesDistintos: ([.[].jsonPayload.databaseStartedAt] | unique | length)}'
```

## O heartbeat do maintenance

`GET /internal/maintenance/status` (serviço privado; o operador chama com o próprio identity token):

```bash
MAINT_URL="$(gcloud run services describe spark-maintenance --region southamerica-east1 --project "$SPARK_GCP_PROJECT" --format='value(status.url)')"
curl -sS -H "Authorization: Bearer $(gcloud auth print-identity-token)" "$MAINT_URL/internal/maintenance/status" | jq
```

```json
{
  "lastStartedAt": 1789140000000, "lastCompletedAt": 1789140000420, "lastSuccessAt": 1789140000420,
  "lastFailureAt": null, "lastDurationMs": 420, "lastErrorName": null,
  "staleAfterMs": 2100000, "ageMs": 12000, "stale": false,
  "databaseSize": { "checkedAt": 1789139000000, "sizeBytes": 31457280, "level": "NORMAL" },
  "drBackup": { "checkedAt": 1789139000000, "latestBackupId": "2026-09-11T031500Z", "latestCreatedAt": 1789097700000, "ageMs": 42300000, "maxAgeMs": 93600000, "stale": false }
}
```

- `stale: true` = nenhum sucesso registrado, ou o último há mais de `MAINTENANCE_STALE_AFTER_MS`
  (35 min). O Scheduler roda a cada 15 minutos (T19.H6): 35 = o ciclo normal + um ciclo perdido +
  margem de cold start/deploy. Um ciclo isolado que falha nunca deixa o heartbeat `stale`; dois
  seguidos deixam. O valor vai explícito na revision do `spark-maintenance`, a partir de
  `ops/gcp/lib.gcp.sh`, junto com o cron e a janela do alerta de ausência.
- Um ciclo que **desiste do lock** com o heartbeat fresco é normal (retry do Scheduler): `2xx`,
  `skipped: true`. Com o heartbeat velho é o ciclo preso: `503`, `errorName:
  MAINTENANCE_LOCKED_STALE`, evento `maintenance_locked_stale`. Foi exatamente o que aconteceu no
  primeiro dia real da T18.3 — o lock de sessão da T18.2 ficou preso no pooler do Neon e todo
  ciclo era `skipped_locked` com `201`; o alerta de ausência de `2xx` não teria visto. Hoje o lock é
  de transação (`pg_try_advisory_xact_lock`) e o `503` torna o estado visível.
- Tudo é lido de `server_metadata` (`maintenance_last_*`, `database_size_*`, `dr_backup_*`):
  sobrevive a restart, revision nova e scale-to-zero.
- `ops/gcp/dr-status.sh` imprime isto junto com a lista de backups do bucket.

## Tamanho do banco (Neon)

Medido por `pg_database_size(current_database())` no ciclo de manutenção, na cadência de
`DATABASE_SIZE_CHECK_INTERVAL_MS` (1 h) — nunca por requisição, nunca a cada minuto. Os limiares
vivem em **um** lugar, `DATABASE_SIZE_THRESHOLDS_MB` (default `300,350,400,450`), e a classificação em
`backend/src/database/database-size.policy.ts`:

| Tamanho | Nível | O que fazer |
| --- | --- | --- |
| < 300 MB | `NORMAL` | nada |
| ≥ 300 MB | `ATTENTION` | acompanhar a tendência (`database_size_checked` semanal) |
| ≥ 350 MB | `INVESTIGATE` | entender o crescimento: `backup_snapshots` (retenção por conta), `sync_changes`, `social_*` |
| ≥ 400 MB | `PLAN` | **alerta** — planejar: retenção, limpeza, ou plano do Neon |
| ≥ 450 MB | `ACTION_REQUIRED` | **alerta** — agir agora; o próximo passo é o provedor recusar escrita |

Só `PLAN` e `ACTION_REQUIRED` alertam (`spark-db-size-critical`); os dois primeiros ficam no log.

## O PostgreSQL é Tier-0 (T19.H6)

| Tier | Dependência | Sem ela |
| --- | --- | --- |
| **0** | PostgreSQL (Neon, `DATABASE_URL`) | **nenhuma rota autenticada responde**: o `BearerAuthGuard` consulta `account_deletion_tombstones` antes de qualquer rota fora de `/v1/account` e falha fechado (`503 ACCOUNT_STATE_UNAVAILABLE`); o maintenance falha (`stage=database`); o backup de DR falha. `/health/ready` responde `database: false` |

Foi o que 2026-09-28 mostrou: a franquia de compute do Neon acabou e sync, social, backup e Coach
caíram juntos, com o Cloud Run inteiro de pé. O app no aparelho não depende de nada disto. A
indisponibilidade do banco nunca se contorna afrouxando o guard — ver
[`RUNBOOK.md`](./RUNBOOK.md) ("Toda rota autenticada responde 503").

### Como `database=false` é detectado — sem nenhum poller novo

`/health/ready` é a verdade sobre o banco, mas **não existe uptime check sobre ele, de propósito**:
um uptime check do Cloud Monitoring consulta de várias regiões a cada 1–15 min, e cada consulta
acorda o Neon — era recriar, para observar o banco, exatamente o polling que esgotou a franquia.
Os sinais que já existem cobrem o caso, do mais rápido ao mais lento:

| Sinal | Quando dispara com o banco fora |
| --- | --- |
| `spark-run-5xx` | ≥ 5 respostas 5xx em 5 min — assim que houver tráfego real (cada request autenticado vira 503) |
| `spark-maintenance-failed` | o primeiro ciclo depois da queda (≤ 15 min): `maintenance_failed` com `stage=database` e o `errorCode` |
| `spark-scheduler-failed` (WARNING) | o mesmo ciclo, visto pelo Scheduler (`INTERNAL`) |
| `spark-maintenance-stale` (CRITICAL) | 45 min sem nenhum `2xx` do maintenance: vários ciclos seguidos perdidos |

A confirmação é humana e instantânea: `curl "$API_URL/health/ready"`.

## O banco dorme? (Neon, T19.H6)

O Neon Free suspende o compute depois de **5 minutos sem consulta** (fixo no plano) e inclui
**100 CU-h por projeto por mês**; esgotada a franquia, o compute fica suspenso até o próximo período
ou um upgrade. O compute mínimo é 0,25 CU: de pé 24/7, ele consome ~6 CU-h/dia — a franquia acaba
em ~17 dias. A conta que decide a cadência de qualquer coisa que consulte o banco:

| Cadência de algo que toca o banco (sem outro tráfego) | Compute acordado | CU-h/dia (0,25 CU) | CU-h / 30 dias |
| --- | --- | --- | --- |
| a cada 1 min (o Scheduler até 2026-09-28) | 24 h/dia — nunca dorme | ~6 | ~180 (1,8× a franquia) |
| a cada 15 min (desde a T19.H6) | ~5 min por ciclo → ~8 h/dia | ~2 | ~60 |
| a cada 30 min | ~4 h/dia | ~1 | ~30 |

A linha de 15 min é a expectativa, não a medição: ela vira evidência com o próprio ciclo. Cada
`maintenance_completed` diz qual compute o atendeu (`databaseStartedAt` =
`pg_postmaster_start_time()`, que o Neon reinicia a cada retomada) e há quanto tempo ele estava de pé
(`databaseUptimeMs`). Sem tráfego, cada ciclo deve encontrar um compute **novo**, com uptime de
segundos; o mesmo compute ciclo após ciclo é o banco que não dorme — e, passado
`DATABASE_COMPUTE_UPTIME_WARN_MS` (6 h), vira `database_compute_long_uptime` e o alerta
`spark-db-compute-long-uptime`, uma vez por compute. Nenhuma credencial do Neon, nenhum serviço novo,
nenhuma consulta a mais: o dado vem na mesma consulta do lock do ciclo.

O consumo em CU-h, o gráfico de atividade do endpoint e o plano **só o console do Neon mostra** — a
API de consumo do Neon exigiria uma chave com acesso amplo à conta, e não vale a credencial para
observar o que o ciclo já observa. Fica no checklist mensal
([`OPERATIONS_CHECKLIST.md`](./OPERATIONS_CHECKLIST.md)): `MANUAL CHECK`.

## Os alertas

`ops/gcp/monitoring-alerts.sh` cria (idempotente) o canal, 11 métricas log-based (painéis) e 13
políticas (9 por log, 3 por métrica nativa, 1 de ausência sobre métrica nativa). Antes de aplicar
qualquer uma, ele confere que a janela de ausência é coerente com o cron do Scheduler e com o stale
do heartbeat (`require_coherent_maintenance_timing`, `ops/gcp/lib.gcp.sh`):

```bash
SPARK_GCP_PROJECT=... SPARK_ALERT_EMAIL=voce@exemplo.com ops/gcp/monitoring-alerts.sh
SPARK_GCP_PROJECT=... ops/gcp/monitoring-alerts.sh --list     # o que existe
```

| Política | Dispara quando | Primeira ação |
| --- | --- | --- |
| `spark-run-5xx` | ≥ 5 respostas 5xx em 5 min na API | logs da API; `ops/gcp/rollback-cloud-run.sh --list` |
| `spark-run-revision-failed` | uma revision não sobe (probe/startup) | logs da revision; o deploy já abortou antes de mover tráfego |
| `spark-job-migrate-failed` / `-task-failed` | `migration_failed` ou task do Job com `result=failed` | RUNBOOK → "O Job spark-db-migrate falhou" |
| `spark-job-backup-failed` / `-task-failed` | `db_backup_failed` ou task do Job com `result=failed` | `ops/gcp/dr-status.sh`; logs do Job; `ops/gcp/dr-backup-now.sh` |
| `spark-db-backup-stale` | o maintenance mediu o backup mais recente acima de 26 h | Scheduler `spark-db-backup-daily` (ENABLED? último status?); `dr-backup-now.sh` |
| `spark-maintenance-stale` (CRITICAL) | 45 min sem resposta 2xx de `spark-maintenance` (métrica nativa `request_count`) — três ciclos de 15 min | Scheduler `spark-maintenance-cycle` (`state`, `schedule`); `spark-maintenance` responde? `/internal/maintenance/status`; o banco (`/health/ready`) |
| `spark-maintenance-failed` | `maintenance_failed` (`errorName`, `stage`) | `stage=database`: o banco não respondeu — `/health/ready` e o Neon; `stage=workers`: um worker quebrou — notificação, exclusão, cleaners |
| `spark-scheduler-failed` (WARNING) | o Scheduler registrou erro (HTTP ≠ 2xx, timeout, IAM) | um erro isolado seguido de um ciclo bem-sucedido não é incidente (T19.H6 §35); vários seguidos viram `spark-maintenance-stale`. `gcloud scheduler jobs describe …`; IAM `run.invoker` |
| `spark-db-size-critical` | nível `PLAN` ou `ACTION_REQUIRED` | tabela acima |
| `spark-db-compute-long-uptime` (WARNING) | `database_compute_long_uptime`: o compute do banco de pé há mais de 6 h sem suspender | RUNBOOK → "O banco não dorme"; a franquia do Neon está sendo consumida a ~6 CU-h/dia |
| `spark-restore-drill-failed` | um `dr-restore-drill.sh --record` reprovou | rodar o ensaio de novo à mão; se reprovar, o backup não serve — investigar antes do próximo backup |

Pouco ruído de propósito: contagens por 5 min, auto-close em 30 min, nenhum alerta por um único
5xx. Severidade declarada onde a T19.H6 precisou dela: vários ciclos perdidos (`spark-maintenance-stale`)
são CRITICAL; um erro isolado do Scheduler e o compute sem suspensão são WARNING; as demais políticas
seguem sem severidade declarada.

### O que exige a Console

1. **Confirmar o e-mail do canal** — o Google envia um e-mail de verificação ao criar um canal do
   tipo `email`; sem confirmar, o canal existe e não entrega. Monitoring → Alerting → Notification
   channels → `spark-ops-email` → *Verify*. **VERIFIED** (2026-09-28): os e-mails do incidente
   chegaram.
2. **Um alerta real chegar.** Em 2026-09-28 (franquia de compute do Neon esgotada, toda rota
   autenticada em `503`) dispararam e chegaram `spark-run-5xx`, `spark-scheduler-failed` e
   `spark-maintenance-stale` — **VERIFIED** (confirmado pelo operador; a de ausência ainda com a
   janela antiga de 10 min). As outras dez continuam `NOT VERIFIED`. A forma segura de testar uma
   sem quebrar nada: `gcloud logging write spark-dr-drill '{"event":"db_restore_drill_completed","status":"RESTORE_DRILL_FAIL"}' --payload-type=json --severity=ERROR`
   dispara `spark-restore-drill-failed` em ~5 min sem tocar em produção.

## Auditorias (somente leitura)

| Pergunta | Comando | Saída |
| --- | --- | --- |
| A configuração real é a esperada? | `ops/gcp/config-drift-audit.sh` | `PASS` / `DRIFT` / `NOT_VERIFIED` por item; código 0/1/2 |
| As permissões são as mínimas? | `ops/gcp/iam-audit.sh` | idem — nunca remove nada |
| Existe custo/recurso inesperado? budget? | `ops/gcp/cost-audit.sh` | idem — nunca altera billing |
| A retenção do registry protege os digests em uso? | `ops/gcp/artifact-registry-retention.sh` | `PASS` / falha |
| Existem órfãos ou referências quebradas no bucket? | `gcloud run jobs execute spark-storage-audit --region southamerica-east1 --wait` e depois os logs (`storage_audit_completed`) | contagens por classe; relatório JSON em stdout do Job |

Cadência sugerida: as três auditorias e a retenção **depois de cada deploy** e mensalmente; o
storage audit semanalmente (é uma listagem paga do bucket, bounded). Ver
[`OPERATIONS_CHECKLIST.md`](./OPERATIONS_CHECKLIST.md).

## Investigando um alerta — o roteiro

1. Leia o `documentation` da política (a Console mostra): ele aponta a seção do RUNBOOK.
2. Confirme com a fonte primária, não com o alerta: `dr-status.sh`, `/internal/maintenance/status`,
   `gcloud run jobs executions list`, logs com o filtro do evento.
3. Nada aqui se corrige sozinho — auditorias reportam, alertas avisam. A ação é humana e está no
   [`RUNBOOK.md`](./RUNBOOK.md) (seção Cloud Run) ou no [`DISASTER_RECOVERY.md`](./DISASTER_RECOVERY.md).
