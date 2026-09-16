-- ============================================================================
-- Spark Backend — 0007_multiplayer_rooms.sql (T19.5)
--
-- Treino em dupla entre DOIS aparelhos. O servidor coordena; ele NÃO executa.
--
--   multiplayer_rooms          quem abriu, com que treino, em que estado
--   multiplayer_room_members   quem participa (no máximo HOST + GUEST, por schema)
--   multiplayer_room_events    o que aconteceu, em que ordem (sequence por sala)
--
-- O que estas tabelas NÃO guardam, por decisão e não por omissão: WorkoutSession,
-- série, peso, repetição, RPE, PR, XP. Cada aparelho continua sendo a autoridade
-- da própria execução (Room local); o que atravessa a rede é o mínimo para que o
-- outro aparelho saiba "quem está aqui, quem já fez a série N do exercício X, e
-- em que ordem isso aconteceu". O payload de cada evento é validado por
-- allowlist no serviço, e o teste da T19.5 prova que carga e repetição são
-- recusadas na fronteira.
--
-- ## Ordering
--
-- `sequence` é atribuída pelo servidor, por sala, sob `SELECT ... FOR UPDATE` na
-- linha da sala (`next_sequence`). É a única ordem que existe: o relógio do
-- aparelho não ordena nada. `PRIMARY KEY (room_id, sequence)` torna um buraco ou
-- uma duplicata irrepresentáveis.
--
-- ## Idempotência
--
-- `UNIQUE (room_id, event_id)`: o mesmo evento reenviado — retry depois de
-- timeout, reconnect, morte de processo — recebe de volta a sequence que já
-- tinha, e nunca uma segunda linha. O `event_id` é do cliente e determinístico
-- por (sala, série), o que faz do reenvio integral após reconexão uma operação
-- segura.
--
-- ## Dois membros, por construção
--
-- `UNIQUE (room_id, role)` com `role IN ('HOST', 'GUEST')`: uma sala tem no
-- máximo um dono e um convidado. Não há terceiro papel, então não há terceiro
-- membro — TRIO é outra migration, outra tarefa.
-- ============================================================================

CREATE TABLE multiplayer_rooms (
  id                 TEXT    PRIMARY KEY,
  host_uid           TEXT    NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  status             TEXT    NOT NULL DEFAULT 'WAITING'
                       CHECK (status IN ('WAITING', 'ACTIVE', 'CLOSED', 'EXPIRED')),
  -- O treino que os dois vão fazer: o mesmo snapshot portável do compartilhamento
  -- (T17.7, `WorkoutTemplateShareSnapshotV1`) — só catálogo canônico, sem carga,
  -- sem nota, sem máquina, sem syncId. Quem entra cria a própria cópia local.
  workout_json       TEXT    NOT NULL,
  workout_hash       TEXT    NOT NULL,
  client_request_id  TEXT    NOT NULL,
  -- A próxima sequence a atribuir. Lida e incrementada sob lock da linha.
  next_sequence      BIGINT  NOT NULL DEFAULT 1,
  created_at         BIGINT  NOT NULL,
  updated_at         BIGINT  NOT NULL,
  -- Uma sala WAITING expira sem ninguém entrar; uma ACTIVE expira por teto
  -- absoluto de duração. Lida preguiçosamente: a transição é gravada no primeiro
  -- acesso depois do prazo, nunca por cron.
  expires_at         BIGINT  NOT NULL,
  closed_at          BIGINT,
  close_reason       TEXT
                       CHECK (close_reason IS NULL OR close_reason IN (
                         'HOST_CLOSED', 'ALL_LEFT', 'INVITE_DECLINED', 'HOST_LEFT_WAITING',
                         'SUPERSEDED', 'EXPIRED', 'UNAVAILABLE'
                       ))
);

-- Idempotência de criação: o mesmo `clientRequestId` do mesmo host é a mesma sala.
CREATE UNIQUE INDEX idx_multiplayer_rooms_host_client_request
  ON multiplayer_rooms (host_uid, client_request_id);

-- "A sala aberta deste host" e a expiração preguiçosa.
CREATE INDEX idx_multiplayer_rooms_host_status
  ON multiplayer_rooms (host_uid, status, created_at);

CREATE TABLE multiplayer_room_members (
  room_id       TEXT    NOT NULL REFERENCES multiplayer_rooms(id) ON DELETE CASCADE,
  member_uid    TEXT    NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  role          TEXT    NOT NULL CHECK (role IN ('HOST', 'GUEST')),
  status        TEXT    NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'FINISHED', 'LEFT')),
  invited_at    BIGINT  NOT NULL,
  joined_at     BIGINT,
  finished_at   BIGINT,
  left_at       BIGINT,
  -- Presença: o último poll autenticado deste membro. "Conectado" é derivado
  -- (`now - last_seen_at < limiar`), nunca escrito.
  last_seen_at  BIGINT,
  PRIMARY KEY (room_id, member_uid),
  UNIQUE (room_id, role)
);

-- Os convites pendentes de uma conta, e a sala em que ela está.
CREATE INDEX idx_multiplayer_room_members_member
  ON multiplayer_room_members (member_uid, status);

CREATE TABLE multiplayer_room_events (
  room_id       TEXT    NOT NULL REFERENCES multiplayer_rooms(id) ON DELETE CASCADE,
  sequence      BIGINT  NOT NULL,
  event_id      TEXT    NOT NULL,
  actor_uid     TEXT    NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  type          TEXT    NOT NULL CHECK (type IN (
                  'MEMBER_JOINED', 'WORKOUT_STARTED', 'SET_COMPLETED',
                  'MEMBER_FINISHED', 'MEMBER_LEFT', 'ROOM_CLOSED'
                )),
  payload_json  TEXT    NOT NULL,
  created_at    BIGINT  NOT NULL,
  PRIMARY KEY (room_id, sequence),
  UNIQUE (room_id, event_id)
);

-- O purge de conta apaga eventos pelo autor (inventário de uid, T17.13.1 §22).
CREATE INDEX idx_multiplayer_room_events_actor
  ON multiplayer_room_events (actor_uid);
