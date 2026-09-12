-- ============================================================================
-- Spark Backend — 0003_notification_events_entity_index.sql (T18.3.2)
--
-- O outbox de notificações é consultado por **entidade** em três caminhos que
-- não têm índice nenhum hoje:
--
--   NotificationRepository.cancelEventsForEntity              (desafio cancelado)
--   NotificationRepository.cancelEventsForEntityAndRecipient  (participante saiu)
--   BlockRepository.cleanupSharedRelations                    (bloqueio do par)
--
-- Todos filtram `entity_id` junto com `status = 'PENDING'`, e todos rodam
-- dentro de uma transação que já está segurando outras linhas. Sem índice, cada
-- um deles é um seq scan em `social_notification_events` — uma tabela que só
-- cresce, porque a limpeza guarda 30 dias de histórico.
--
-- Os dois índices que já existem não servem a esta pergunta: o de despacho é
-- `(status, deliver_after, expires_at)` e o de destinatário é
-- `(recipient_uid, status)`. Nenhum começa por `entity_id`, que é a coluna
-- seletiva aqui.
--
-- Índice completo, e não parcial em `PENDING`: `cancelEventsForEntity` também é
-- chamado sem filtro de tipo, e uma futura leitura por entidade em qualquer
-- estado continuaria coberta. O custo de escrita é uma linha por evento criado.
-- ============================================================================

CREATE INDEX idx_social_notification_events_entity
  ON social_notification_events (entity_id, status);
