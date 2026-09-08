-- T17.4 — atividade dos amigos e rankings contextuais.
--
-- A T17.0 criou identidade (social_profiles) e privacidade de descoberta (social_privacy_settings).
-- A T17.1 criou o grafo de amizade bilateral (friendships, friend_requests).
-- A T17.2 criou o compartilhamento de progresso no perfil (social_progress_settings).
-- A T17.3 criou desafios privados entre amigos (challenges, challenge_participants).
-- Esta migration adiciona a configuração necessária para duas capacidades sociais opcionais:
--   1. Compartilhamento de atividade recente com fuso horário social explícito;
--   2. Participação recíproca no ranking contextual de 7 dias entre amigos.
--
-- ## Nenhuma tabela de eventos de atividade nem de ranking mora aqui
--
-- Atividade social e pontuação de ranking são PROJEÇÕES on-read do domínio canônico de treino
-- (sync_entities / WORKOUT_SESSION / COMPLETED). Não existe tabela social_activity_events nem
-- ranking_snapshots: criar qualquer uma geraria divergência, drift em sincronização tardia e
-- uma segunda fonte de verdade concorrente.
--
-- ## Preservação de dados existentes
--
-- Nenhuma linha existente é apagada ou alterada destructivamente.
-- activity_sharing_enabled da T17.0 é preservado.
-- friend_ranking_participation_enabled nasce 0 (desligado por padrão).
-- activity_time_zone_id nasce NULL.

ALTER TABLE social_privacy_settings ADD COLUMN activity_time_zone_id TEXT;
ALTER TABLE social_privacy_settings ADD COLUMN friend_ranking_participation_enabled INTEGER NOT NULL DEFAULT 0;
