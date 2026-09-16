-- ============================================================================
-- Spark Backend — 0006_workout_share_type.sql (T19.3)
--
-- Uma oferta de compartilhamento passa a dizer O QUE transporta: um treino
-- (T17.7) ou um programa inteiro (T19.3). A mesma tabela, o mesmo ciclo de
-- vida, a mesma idempotência, o mesmo bloqueio e o mesmo purge de conta — o
-- que muda é a forma do `snapshot_json`, e é isto que a coluna declara.
--
--   WORKOUT_TEMPLATE   { snapshotVersion, name, shortIdentifier?, exercises[] }
--   WORKOUT_PROGRAM    { snapshotVersion, name, description?, templates[] }
--
-- Uma segunda tabela ("program_shares") duplicaria as cinco transições CAS,
-- o outbox de notificação, o cancelamento por bloqueio e a linha do
-- inventário de purge — quatro lugares para divergir na primeira correção.
--
-- Aditiva, com DEFAULT: toda linha existente é um treino, porque até aqui só
-- treino era compartilhável. Nenhuma linha muda de significado.
-- ============================================================================

ALTER TABLE workout_shares
  ADD COLUMN share_type TEXT NOT NULL DEFAULT 'WORKOUT_TEMPLATE'
    CHECK (share_type IN ('WORKOUT_TEMPLATE', 'WORKOUT_PROGRAM'));

-- Sem índice: a coluna nunca é critério de busca. As listagens filtram por
-- `recipient_uid`/`sender_uid` (índices da 0001) e leem o tipo da linha já
-- encontrada, só para decidir como interpretar o JSON.
