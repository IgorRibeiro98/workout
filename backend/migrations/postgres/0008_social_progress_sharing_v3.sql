-- ============================================================================
-- Spark Backend — 0008_social_progress_sharing_v3.sql (T19.H3)
--
-- Compartilhar Progresso V3: onze escolhas novas de privacidade, em dois grupos.
--
--   Estatísticas de treino (perfil agregado, "como esta pessoa vem treinando?")
--     share_weekly_training_minutes   minutos treinados na semana canônica
--     share_weekly_completed_sets     séries concluídas na semana canônica
--     share_weekly_volume             volume (kg × reps) da semana canônica
--     share_total_workouts            treinos concluídos desde sempre
--
--   Detalhes dos check-ins ("o que aconteceu neste treino?")
--     share_workout_name              nome do treino (snapshot da sessão)
--     share_workout_time              horário de início
--     share_workout_duration          duração (fim − início, derivada no servidor)
--     share_workout_exercises         exercícios (nome e grupo muscular do snapshot)
--     share_workout_sets              séries e repetições concluídas
--     share_workout_weights           cargas — só aparecem junto de séries/repetições
--     share_workout_volume            volume total do treino
--
-- TODAS nascem FALSE, e o DEFAULT é o que decide o usuário que já existe: o
-- `ADD COLUMN ... NOT NULL DEFAULT FALSE` preenche cada linha antiga com FALSE.
-- Nenhum deploy começa a publicar carga, horário ou exercício de ninguém.
--
-- O que estas colunas guardam é PREFERÊNCIA, nunca valor. Nenhum minuto, série,
-- carga ou volume é gravado: tudo é derivado NA LEITURA de sync_entities
-- (`WORKOUT_SESSION` COMPLETED), filtrado pela preferência VIGENTE. É isso que
-- faz desligar um campo valer também para check-ins antigos — não existe cópia
-- pública do treino para esquecer de apagar.
--
-- Cascade por `social_progress_settings` → `social_profiles` (0001): o purge de
-- conta e a remoção do perfil social levam as escolhas junto.
-- ============================================================================

ALTER TABLE social_progress_settings
  ADD COLUMN share_weekly_training_minutes BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_weekly_completed_sets   BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_weekly_volume           BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_total_workouts          BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_workout_name            BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_workout_time            BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_workout_duration        BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_workout_exercises       BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_workout_sets            BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_workout_weights         BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN share_workout_volume          BOOLEAN NOT NULL DEFAULT FALSE;
