-- ============================================================================
-- Spark Backend — 0002_object_storage.sql (T18.1)
--
-- O documento canônico de um backup deixa de morar no PostgreSQL e passa a
-- morar no Object Storage (bucket privado do GCS em produção; disco local em
-- desenvolvimento e CI). O banco continua sendo a autoridade de metadata,
-- ownership, hashes e estado — ele só deixa de duplicar o conteúdo pesado.
--
-- O que muda, e o que NÃO muda:
--
--   backup_snapshots.storage_key   NOVA — o objeto que guarda o documento
--                                  canônico (`backups/xx/yy/<backupId>.json`).
--                                  NULL nos snapshots anteriores à T18.1.
--   backup_snapshots.payload       MANTIDA — snapshots legados continuam
--                                  restauráveis a partir dela até serem
--                                  migrados pelo comando operacional
--                                  `migrate-backup-payloads-to-object-storage`.
--                                  Backups novos gravam NULL aqui.
--   backup_items.payload           passa a ser NULLABLE — backups novos guardam
--                                  em backup_items só identidade, versão de
--                                  schema e content_hash. O payload de cada
--                                  agregado já está no documento canônico, e
--                                  duplicá-lo no banco era o custo que esta
--                                  migration existe para eliminar.
--
-- Nenhum dado é apagado aqui. A migração dos snapshots existentes para o
-- Object Storage é uma operação explícita, idempotente e retomável, feita por
-- comando — nunca por SQL: upload para bucket não cabe numa transação DDL.
-- ============================================================================

ALTER TABLE backup_snapshots
  ADD COLUMN storage_key TEXT;

-- Um objeto pertence a exatamente um snapshot. Parcial porque os legados são NULL.
CREATE UNIQUE INDEX idx_backup_snapshots_storage_key
  ON backup_snapshots (storage_key)
  WHERE storage_key IS NOT NULL;

ALTER TABLE backup_items
  ALTER COLUMN payload DROP NOT NULL;
