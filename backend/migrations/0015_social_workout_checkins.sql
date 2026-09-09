-- T17.8 — Check-ins de treino + Feed Social
--
-- Aditiva, como todas as migrations sociais desde a 0007: ela **cria**, e não altera nem apaga
-- nada da T16 ou da T17.0–T17.7 (§125/§126). Não há DROP, DELETE, UPDATE nem ALTER aqui.
--
-- O agregado é um artefato social explícito. Ele guarda uma **referência** à sessão canônica
-- (`source_session_sync_id`) e nenhum dado de treino: sem nome de treino, exercício, série,
-- repetição, carga, duração, volume, nota, horário do treino ou medida corporal (§41–§48). Também
-- não guarda texto livre, foto nem vídeo (§38) — a ausência é a decisão da fase, e é o que mantém
-- moderação de UGC, denúncia por post e sanitização fora do escopo.

CREATE TABLE social_workout_checkins (
    id                      TEXT    PRIMARY KEY,

    -- O autor é um perfil social. `ON DELETE CASCADE` é o que faz a exclusão de conta (T17.6)
    -- levar os check-ins junto (§62) e o que garante que o feed nunca encontre autor órfão (§63):
    -- o `JOIN` com `social_profiles` deixa de casar no mesmo instante em que o perfil some.
    author_uid              TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- Referência interna à sessão canônica em `sync_entities`. Ela existe para a UNIQUE abaixo e
    -- **nunca** sai em DTO, notificação ou log (§13/§51). Sem FK de propósito: `sync_entities` é
    -- domínio de treino, e uma FK daqui para lá acoplaria a exclusão de uma sessão à existência de
    -- um artefato social — que é exatamente o que §68 separa.
    source_session_sync_id  TEXT    NOT NULL,

    client_request_id       TEXT    NOT NULL,

    -- Dois estados, e só dois (§37). Uma máquina de estados maior não teria transição para
    -- descrever: publicar e excluir são tudo o que existe nesta fase.
    status                  TEXT    NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'DELETED')),

    -- O instante da **publicação** (§49). Não é o instante do treino, e não deriva dele.
    created_at              INTEGER NOT NULL,
    deleted_at              INTEGER,

    -- §29 — um treino concluído gera no máximo um check-in. A garantia é do banco, e não da ordem
    -- em que dois SELECT aconteceram: duas requisições simultâneas da mesma conta pela mesma
    -- sessão fazem a segunda falhar aqui. A linha permanece depois do soft delete, e é por isso
    -- que excluir é definitivo para aquela sessão.
    CONSTRAINT uq_workout_checkins_author_session UNIQUE (author_uid, source_session_sync_id),

    -- §30/§31/§32 — a mesma intenção do usuário produz o mesmo check-in, e reusar o identificador
    -- para outra sessão é conflito em vez de uma segunda publicação.
    CONSTRAINT uq_workout_checkins_author_request UNIQUE (author_uid, client_request_id)
);

-- As duas UNIQUE acima já indexam `(author_uid, source_session_sync_id)` e
-- `(author_uid, client_request_id)`, que são as buscas de idempotência. Os dois índices abaixo
-- existem para a **consulta do feed**, e só ela (§127):
--
--   1. varrer as publicações recentes e filtrar pelos autores elegíveis;
--   2. percorrer autor a autor, quando o conjunto de amigos é pequeno.
--
-- O planejador escolhe entre os dois conforme a seletividade. Não há índice isolado em
-- `source_session_sync_id`: nenhuma consulta pergunta "de quem é esta sessão" — a pergunta é
-- sempre por dono **e** sessão, e a UNIQUE já responde.
CREATE INDEX idx_workout_checkins_feed ON social_workout_checkins (status, created_at DESC);
CREATE INDEX idx_workout_checkins_author ON social_workout_checkins (author_uid, status, created_at DESC);
