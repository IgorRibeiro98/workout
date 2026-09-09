-- T17.9 — Conteúdo do check-in: legenda e foto
--
-- Aditiva, como todas as migrations sociais desde a 0007 (§163/§164): ela **acrescenta** uma
-- coluna e cria uma tabela. Não há DROP, DELETE nem ALTER destrutivo, e nenhuma migration já
-- aplicada é reescrita. Um check-in publicado pela T17.8 continua válido exatamente como está:
-- `caption` nasce `NULL`, e a ausência de linha em `social_checkin_media` é a ausência de foto
-- (§6). Não existe backfill artificial — inventar legenda a partir do nome do treino seria
-- publicar texto que o usuário nunca escreveu (§11).

-- -------------------------------------------------------------------------------------------------
-- 1. Legenda (§7/§8/§60)
-- -------------------------------------------------------------------------------------------------
--
-- `NULL` significa "sem legenda", e é o estado de toda publicação anterior. String vazia **não**
-- é um valor aceito: o validador normaliza e recusa, então "sem legenda" tem uma representação
-- só. O `CHECK` é a garantia do banco — o teto de 280 vale mesmo que algum caminho futuro
-- esqueça de validar. `length()` no SQLite conta **caracteres** em TEXT, que é a unidade que o
-- usuário enxerga; a contagem canônica (code points sobre a forma NFC) é do validador.
ALTER TABLE social_workout_checkins
    ADD COLUMN caption TEXT
    CHECK (caption IS NULL OR (length(caption) > 0 AND length(caption) <= 280));

-- -------------------------------------------------------------------------------------------------
-- 2. Mídia (§40/§41)
-- -------------------------------------------------------------------------------------------------
--
-- Os **bytes não moram aqui** (§21). Esta tabela guarda metadata; o arquivo sanitizado vive no
-- armazenamento de mídia (`SocialMediaStore`), endereçado por `storage_key` — um identificador
-- opaco gerado pelo servidor (§24). Um BLOB de 1,5 MB por publicação transformaria `spark.db` no
-- gargalo de I/O de tudo o que o servidor faz, e faria o `VACUUM INTO` do backup diário copiar
-- todas as fotos de todo mundo a cada execução.
CREATE TABLE social_checkin_media (
    id                      TEXT    PRIMARY KEY,

    -- O dono é um perfil social. `ON DELETE CASCADE` é o que faz a exclusão de conta (T17.6)
    -- levar a metadata junto (§111/§114); os **arquivos** são apagados pelo serviço, que coleta
    -- as chaves antes do purge — o SQLite não alcança o sistema de arquivos.
    owner_uid               TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- §32 — o endpoint de mídia não é armazenamento genérico. Todo upload nasce vinculado a uma
    -- sessão candidata do próprio dono, e o anexo exige que o check-in seja **daquela** sessão
    -- (§34). Sem FK para `sync_entities` pelo mesmo motivo da T17.8: aquilo é domínio de treino.
    source_session_sync_id  TEXT    NOT NULL,

    -- §36 — retry do mesmo upload converge no mesmo `mediaId`, e não em um arquivo duplicado.
    client_upload_id        TEXT    NOT NULL,

    -- A chave opaca no armazenamento. Nunca deriva de uid, `displayName`, `friendCode` ou do nome
    -- do arquivo original (§23), e nunca é proposta pelo cliente (§24/§25).
    storage_key             TEXT    NOT NULL UNIQUE,

    -- O tipo **do que foi armazenado**, e não o que o cliente declarou (§14). O servidor decodifica
    -- de verdade e re-encoda; este campo descreve a saída.
    mime_type               TEXT    NOT NULL,
    byte_size               INTEGER NOT NULL,
    width                   INTEGER NOT NULL,
    height                  INTEGER NOT NULL,

    -- §37 — integridade da representação sanitizada. **Não é autorização**: conhecer o hash não
    -- dá acesso a nada, e nenhuma consulta de permissão o consulta.
    content_hash            TEXT    NOT NULL,

    -- §41 — três estados, e só três.
    status                  TEXT    NOT NULL CHECK (status IN ('PENDING', 'ATTACHED', 'DELETED')),

    created_at              INTEGER NOT NULL,
    -- §38 — mídia PENDING expira. `NULL` depois de anexada: o prazo era para o upload órfão.
    expires_at              INTEGER,
    -- §35 — no máximo um check-in por mídia. A `UNIQUE` parcial abaixo é quem garante isso.
    attached_checkin_id     TEXT    REFERENCES social_workout_checkins (id) ON DELETE CASCADE,
    deleted_at              INTEGER,

    -- §36 — a idempotência é do banco, e não da ordem em que dois SELECT aconteceram.
    CONSTRAINT uq_checkin_media_owner_upload UNIQUE (owner_uid, client_upload_id)
);

-- §35/§12 — **uma** foto por publicação, e uma mídia em **um** check-in.
--
-- Índice único parcial: linhas com `attached_checkin_id IS NULL` (PENDING) não participam, então
-- várias mídias podem esperar anexo ao mesmo tempo. Assim que uma é anexada, nenhuma outra pode
-- apontar para o mesmo check-in — o que dá as duas garantias com uma constraint só.
CREATE UNIQUE INDEX idx_checkin_media_one_per_checkin
    ON social_checkin_media (attached_checkin_id)
    WHERE attached_checkin_id IS NOT NULL;

-- §166 — os índices existem para as consultas reais desta fase:
--   1. anexar/servir a mídia de um check-in (coberto pelo índice único acima);
--   2. a quota por conta (§29) e o inventário de arquivos de uma conta excluída (§114);
--   3. a limpeza de PENDING expirada (§39).
CREATE INDEX idx_checkin_media_owner_status ON social_checkin_media (owner_uid, status);
CREATE INDEX idx_checkin_media_expiry ON social_checkin_media (status, expires_at);
