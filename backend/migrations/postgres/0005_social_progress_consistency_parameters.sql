-- ============================================================================
-- Spark Backend — 0005_social_progress_consistency_parameters.sql (T19.2A)
--
-- Os parâmetros de consistência que o dono declara para que o servidor consiga
-- derivar a sequência semanal (e, a partir dela, o nível e as conquistas de
-- consistência) dos treinos COMPLETED que já chegam por sync.
--
-- O que estas duas estruturas guardam é CONFIGURAÇÃO, nunca progresso:
--
--   tracking_started_at_epoch_day   quando o acompanhamento começou (DataStore
--                                   `consistency_tracking_started_at` do app)
--   social_progress_weekly_goals    a meta vigente a partir de cada segunda-feira
--                                   (`weekly_goal_history` do app)
--
-- São exatamente os dois insumos que `ConsistencyCalculator` lê no aparelho
-- além das sessões. Nenhuma coluna aqui guarda sequência, XP, nível ou
-- conquista: a projeção é derivada NA LEITURA, de sync_entities, e não existe
-- tabela de placar a reconstruir ou a ressuscitar. Um parâmetro não fabrica
-- treino — com qualquer meta e qualquer início, a sequência só cresce com
-- sessões COMPLETED que de fato foram sincronizadas.
--
-- `goal` entre 1 e 7: é o intervalo que a tela de meta semanal do app oferece.
-- `week_start_epoch_day` é validado como segunda-feira no código (o CHECK
-- aritmético está aqui como última linha de defesa: epoch day 4 = 1970-01-05,
-- segunda-feira).
--
-- Cascade por `social_progress_settings` → `social_profiles`: desativar/apagar o
-- perfil social leva os parâmetros junto, e o purge de conta os lista
-- explicitamente (account-uid-inventory.ts).
-- ============================================================================

ALTER TABLE social_progress_settings
  ADD COLUMN tracking_started_at_epoch_day BIGINT;

CREATE TABLE social_progress_weekly_goals (
  owner_uid            TEXT    NOT NULL REFERENCES social_progress_settings(owner_uid) ON DELETE CASCADE,
  week_start_epoch_day BIGINT  NOT NULL CHECK (((week_start_epoch_day - 4) % 7) = 0),
  goal                 INTEGER NOT NULL CHECK (goal >= 1 AND goal <= 7),
  PRIMARY KEY (owner_uid, week_start_epoch_day)
);
