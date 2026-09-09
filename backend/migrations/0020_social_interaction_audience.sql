-- T17.12 — Interações contextuais em Squads: reações e comentários por audiência
--
-- Aditiva e não destrutiva (§25): a T17.11 deixou reagir e comentar como privilégio de relação
-- **direta** (§70 daquela fase) justamente porque o mesmo check-in pode estar no Feed de amigos e
-- em vários Squads ao mesmo tempo, e uma reação/comentário sem audiência vazaria de um Squad para
-- outro. Esta migration acrescenta essa audiência às duas tabelas que a T17.9 criou — nenhuma
-- tabela nova, nenhum segundo Feed, nenhum segundo Post.
--
-- ## Por que um rebuild em 12 passos, e não `ALTER TABLE ADD COLUMN`
--
-- `social_checkin_comments` precisa de um `CHECK` que compara **duas** colunas
-- (`audience_type` e `group_id`): `FRIEND` exige `group_id IS NULL`, `GROUP` exige `group_id IS NOT
-- NULL` (§27). O SQLite recusa `ALTER TABLE ADD COLUMN` com um `CHECK` que referencie outra coluna
-- além da que está sendo criada — só um `CREATE TABLE` novo pode expressar isso. O mesmo rebuild
-- resolve `social_checkin_reactions`, que também precisa perder a `PRIMARY KEY (checkin_id,
-- reactor_uid)`: essa chave é exatamente a regra antiga (uma reação por pessoa por post, *sem*
-- audiência) que §12/§93 pedem para evoluir.
--
-- É o mesmo procedimento que a 0014 usou para acrescentar `WORKOUT_SHARE_RECEIVED` e a 0019 para
-- `GROUP_INVITATION_RECEIVED`: `INSERT INTO <tabela>_new ... FROM <tabela>` antes do `DROP TABLE`,
-- e `RENAME TO` depois. O teste estrutural da T17.10 (`social-migrations.spec.ts`) só aceita um
-- `DROP TABLE` que siga essa forma.
--
-- ## Backfill (§24/§26)
--
-- Toda reação e todo comentário anteriores a esta migration viram `audience_type = 'FRIEND'` com
-- `group_id = NULL` — a única leitura possível: antes desta fase, toda interação só podia nascer de
-- uma relação direta (o próprio autor ou um amigo), que é exatamente o que `FRIEND` significa agora.
-- Nenhuma linha é apagada, nenhum `id` de comentário muda.
--
-- ## A uniqueness de reação, sem o problema do `NULL` (§93/§94)
--
-- `UNIQUE(checkin_id, reactor_uid, group_id)` sozinha **não** resolveria isto: o SQLite trata cada
-- `NULL` como distinto de qualquer outro em uma `UNIQUE` comum, então duas reações `FRIEND` da
-- mesma pessoa no mesmo post (`group_id IS NULL` nas duas) passariam pelo índice sem conflito.
--
-- A solução são dois índices únicos **parciais**, cada um só sobre a partição que faz sentido:
--
-- ```text
-- idx_checkin_reactions_friend_unique  UNIQUE (checkin_id, reactor_uid)             WHERE audience_type = 'FRIEND'
-- idx_checkin_reactions_group_unique   UNIQUE (checkin_id, reactor_uid, group_id)   WHERE audience_type = 'GROUP'
-- ```
--
-- Dentro da partição `FRIEND`, `group_id` nunca entra na comparação — a unicidade é por
-- `(checkin_id, reactor_uid)`, do jeito que sempre foi. Dentro da partição `GROUP`, `group_id`
-- nunca é `NULL` (o `CHECK` da tabela garante isso), então a comparação de igualdade do índice
-- funciona normalmente. `CheckInInteractionRepository.putReaction` usa
-- `ON CONFLICT (...) WHERE audience_type = '...'` para mirar o índice certo — a mesma sintaxe de
-- upsert sobre índice parcial que o SQLite oferece desde a 3.24.

-- -------------------------------------------------------------------------------------------------
-- 1. Reações — perde a PK antiga, ganha audiência e os dois índices parciais (§12/§13/§93/§94)
-- -------------------------------------------------------------------------------------------------
CREATE TABLE social_checkin_reactions_new (
    checkin_id    TEXT    NOT NULL REFERENCES social_workout_checkins (id) ON DELETE CASCADE,
    reactor_uid   TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    type          TEXT    NOT NULL CHECK (type IN ('FIRE', 'MUSCLE', 'CLAP')),

    -- §6/§93 — só duas audiências. `GROUP` exige `group_id`; `FRIEND` proíbe.
    audience_type TEXT    NOT NULL DEFAULT 'FRIEND' CHECK (audience_type IN ('FRIEND', 'GROUP')),
    group_id      TEXT    REFERENCES social_groups (id) ON DELETE CASCADE,

    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL,

    CHECK (
      (audience_type = 'FRIEND' AND group_id IS NULL)
      OR (audience_type = 'GROUP' AND group_id IS NOT NULL)
    )
);

INSERT INTO social_checkin_reactions_new
  (checkin_id, reactor_uid, type, audience_type, group_id, created_at, updated_at)
  SELECT checkin_id, reactor_uid, type, 'FRIEND', NULL, created_at, updated_at
    FROM social_checkin_reactions;

DROP TABLE social_checkin_reactions;
ALTER TABLE social_checkin_reactions_new RENAME TO social_checkin_reactions;

-- §93/§94 — a uniqueness por audiência, e só ela garante "uma reação por pessoa por post por
-- audiência" sem o problema do `NULL` explicado acima.
CREATE UNIQUE INDEX idx_checkin_reactions_friend_unique
    ON social_checkin_reactions (checkin_id, reactor_uid)
    WHERE audience_type = 'FRIEND';
CREATE UNIQUE INDEX idx_checkin_reactions_group_unique
    ON social_checkin_reactions (checkin_id, reactor_uid, group_id)
    WHERE audience_type = 'GROUP';

-- A exclusão de conta continua varrendo por `reactor_uid`, através das duas audiências (§73).
CREATE INDEX idx_checkin_reactions_reactor ON social_checkin_reactions (reactor_uid);
-- §43/§143/§144 — "as reações desta pessoa neste Squad", varrida por `leave`/`remove member`.
CREATE INDEX idx_checkin_reactions_group ON social_checkin_reactions (group_id, reactor_uid);
-- §92 — a leitura por post é sempre "este post, esta audiência".
CREATE INDEX idx_checkin_reactions_lookup
    ON social_checkin_reactions (checkin_id, audience_type, group_id);

-- -------------------------------------------------------------------------------------------------
-- 2. Comentários — ganham audiência e o `CHECK` cruzado (§17/§27)
-- -------------------------------------------------------------------------------------------------
CREATE TABLE social_checkin_comments_new (
    id            TEXT    PRIMARY KEY,
    checkin_id    TEXT    NOT NULL REFERENCES social_workout_checkins (id) ON DELETE CASCADE,
    author_uid    TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    body          TEXT    NOT NULL CHECK (length(body) > 0 AND length(body) <= 300),

    audience_type TEXT    NOT NULL DEFAULT 'FRIEND' CHECK (audience_type IN ('FRIEND', 'GROUP')),
    group_id      TEXT    REFERENCES social_groups (id) ON DELETE CASCADE,

    created_at    INTEGER NOT NULL,
    deleted_at    INTEGER,

    CHECK (
      (audience_type = 'FRIEND' AND group_id IS NULL)
      OR (audience_type = 'GROUP' AND group_id IS NOT NULL)
    )
);

INSERT INTO social_checkin_comments_new
  (id, checkin_id, author_uid, body, audience_type, group_id, created_at, deleted_at)
  SELECT id, checkin_id, author_uid, body, 'FRIEND', NULL, created_at, deleted_at
    FROM social_checkin_comments;

DROP TABLE social_checkin_comments;
ALTER TABLE social_checkin_comments_new RENAME TO social_checkin_comments;

-- §92 — a conversa de um post, nesta audiência, em ordem cronológica.
CREATE INDEX idx_checkin_comments_thread
    ON social_checkin_comments (checkin_id, audience_type, group_id, created_at, id);
-- A exclusão de conta continua varrendo por `author_uid`, através das duas audiências (§73).
CREATE INDEX idx_checkin_comments_author ON social_checkin_comments (author_uid);
-- §43/§143/§144 — "os comentários desta pessoa neste Squad", varrida por `leave`/`remove member`.
CREATE INDEX idx_checkin_comments_group ON social_checkin_comments (group_id, author_uid);
