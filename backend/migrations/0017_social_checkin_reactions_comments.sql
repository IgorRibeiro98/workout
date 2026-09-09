-- T17.9 — Reações e comentários no check-in
--
-- Aditiva (§163/§164): duas tabelas novas, nenhuma alteração no que já existe. Um check-in da
-- T17.8 continua válido — a ausência de linhas aqui é `reactions = []` e `comments = []` (§6).

-- -------------------------------------------------------------------------------------------------
-- 1. Reações (§61–§66)
-- -------------------------------------------------------------------------------------------------
CREATE TABLE social_checkin_reactions (
    checkin_id  TEXT    NOT NULL REFERENCES social_workout_checkins (id) ON DELETE CASCADE,
    reactor_uid TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- §62 — enum fechado, no banco. O cliente não envia emoji: ele envia um destes três nomes, e
    -- qualquer outro valor é recusado pelo validador **e** pelo `CHECK`. Sem isso, o primeiro
    -- caminho que esquecesse de validar aceitaria texto arbitrário de terceiros no lugar de uma
    -- reação — que é conteúdo livre entrando por uma porta lateral.
    type        TEXT    NOT NULL CHECK (type IN ('FIRE', 'MUSCLE', 'CLAP')),

    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,

    -- §63 — uma reação por pessoa por publicação, e a garantia é do banco. Trocar 🔥 por 💪 é um
    -- `UPDATE` desta linha (§64), nunca uma segunda; e duas requisições simultâneas da mesma conta
    -- não conseguem produzir duas linhas.
    PRIMARY KEY (checkin_id, reactor_uid)
);

-- §166 — a agregação do feed varre por publicação. A PK já cobre `(checkin_id, ...)` como prefixo,
-- e este índice existe para o `reactor_uid` isolado: a exclusão de conta apaga as reações que a
-- pessoa fez em publicações **de outros** (§113), e sem ele isso seria uma varredura completa.
CREATE INDEX idx_checkin_reactions_reactor ON social_checkin_reactions (reactor_uid);

-- -------------------------------------------------------------------------------------------------
-- 2. Comentários (§74–§76)
-- -------------------------------------------------------------------------------------------------
CREATE TABLE social_checkin_comments (
    -- §75 — UUID gerado pelo servidor. O cliente não propõe identidade.
    id          TEXT    PRIMARY KEY,
    checkin_id  TEXT    NOT NULL REFERENCES social_workout_checkins (id) ON DELETE CASCADE,
    author_uid  TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- §76/§77 — texto puro, normalizado e com teto. O `CHECK` é a garantia do banco; a
    -- normalização Unicode, a recusa de caracteres de controle e o limite de quebras de linha são
    -- do validador, porque o SQLite não sabe fazer nenhuma das três.
    body        TEXT    NOT NULL CHECK (length(body) > 0 AND length(body) <= 300),

    created_at  INTEGER NOT NULL,
    -- §97 — soft delete: repetir converge, e a linha continua sendo a prova de que aquele
    -- identificador já existiu.
    deleted_at  INTEGER
);

-- §91/§166 — a listagem é por publicação, em ordem cronológica crescente, com desempate por id.
CREATE INDEX idx_checkin_comments_thread ON social_checkin_comments (checkin_id, created_at, id);
-- §112 — a exclusão de conta remove os comentários que a pessoa fez em publicações de outros.
CREATE INDEX idx_checkin_comments_author ON social_checkin_comments (author_uid);
