# Spark — Observabilidade operacional (T18.3)

> **Estado:** `IMPLEMENTED` (código, scripts, testes offline) · alertas reais no Cloud Monitoring
> `MANUAL SETUP REQUIRED` (`ops/gcp/monitoring-alerts.sh` + confirmação do e-mail do canal) ·
> `NOT VERIFIED` até um alerta real chegar. Ver o vocabulário em [`README.md`](./README.md).

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
`imageDigest`. Nunca: connection string, senha, HMAC, chave do Gemini, token, conteúdo de usuário.

| Evento | Emitido por | Significa |
| --- | --- | --- |
| `db_backup_started` / `db_backup_completed` / `db_backup_failed` | Job `spark-db-backup` (`db-backup.js`) | backup de DR do PostgreSQL |
| `db_backup_retention_removed` / `db_backup_retention_ignored` | idem | retenção agiu / pasta inválida ignorada |
| `db_restore_started` / `db_restore_completed` / `db_restore_failed` | `db-restore-drill.js` | ensaio de restauração (status `RESTORE_DRILL_PASS`/`FAIL`) |
| `db_restore_drill_completed` | `ops/gcp/dr-restore-drill.sh --record` | veredito do ensaio do operador, no log `spark-dr-drill` |
| `maintenance_started` / `maintenance_completed` / `maintenance_failed` | `spark-maintenance` | um ciclo (a cada minuto) |
| `maintenance_stale` | `spark-maintenance` | o ciclo voltou depois de mais de `MAINTENANCE_STALE_AFTER_MS` sem sucesso |
| `maintenance_locked_stale` (ERROR) | `spark-maintenance` | a chamada não conseguiu o lock **e** o heartbeat já está velho: o ciclo está preso; `POST /internal/maintenance/run` responde **503** (o Scheduler registra erro; `spark-maintenance-stale` dispara em 10 min) |
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
  "staleAfterMs": 300000, "ageMs": 12000, "stale": false,
  "databaseSize": { "checkedAt": 1789139000000, "sizeBytes": 31457280, "level": "NORMAL" },
  "drBackup": { "checkedAt": 1789139000000, "latestBackupId": "2026-09-11T031500Z", "latestCreatedAt": 1789097700000, "ageMs": 42300000, "maxAgeMs": 93600000, "stale": false }
}
```

- `stale: true` = nenhum sucesso registrado, ou o último há mais de `MAINTENANCE_STALE_AFTER_MS`
  (5 min). O Scheduler roda a cada minuto; cinco minutos absorvem retry e deploy.
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

## Os alertas

`ops/gcp/monitoring-alerts.sh` cria (idempotente) o canal, 10 métricas log-based (painéis) e 12
políticas (8 por log, 3 por métrica nativa, 1 de ausência sobre métrica nativa):

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
| `spark-maintenance-stale` | 10 min sem resposta 2xx de `spark-maintenance` (métrica nativa `request_count`) | Scheduler `spark-maintenance-cycle`; `spark-maintenance` responde? `/internal/maintenance/status` |
| `spark-maintenance-failed` | `maintenance_failed` (`errorName`) | logs do ciclo; um worker quebrou — notificação, exclusão, cleaners |
| `spark-scheduler-failed` | o Scheduler registrou erro (HTTP ≠ 2xx, timeout, IAM) | `gcloud scheduler jobs describe …`; IAM `run.invoker` |
| `spark-db-size-critical` | nível `PLAN` ou `ACTION_REQUIRED` | tabela acima |
| `spark-restore-drill-failed` | um `dr-restore-drill.sh --record` reprovou | rodar o ensaio de novo à mão; se reprovar, o backup não serve — investigar antes do próximo backup |

Pouco ruído de propósito: contagens por 5 min, auto-close em 30 min, nenhum alerta por um único
5xx.

### O que exige a Console (NOT VERIFIED até acontecer)

1. **Confirmar o e-mail do canal** — o Google envia um e-mail de verificação ao criar um canal do
   tipo `email`; sem confirmar, o canal existe e não entrega. Monitoring → Alerting → Notification
   channels → `spark-ops-email` → *Verify*.
2. **Um alerta real chegar.** Nenhum dos 12 foi visto disparar ainda. A forma segura de testar um
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
