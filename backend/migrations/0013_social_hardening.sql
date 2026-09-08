-- T17.6 — Hardening Social: Bloqueio, Denúncia e Exclusão de Conta
--
-- Esta migração cria o suporte server-authoritative para:
--   1. Bloqueio bilateral entre contas (social_blocks) com proteção contra self-block;
--   2. Registro minimalista de denúncias para revisão operacional sem texto livre (social_reports);
--   3. Tombstones HMAC irreversíveis contra ressurreição de contas em DR (account_deletion_tombstones);
--   4. Fila efêmera para retry assíncrono de deleção no Firebase Admin (account_deletion_jobs).

-- -------------------------------------------------------------------------------------------------
-- 1. Bloqueios Sociais
-- -------------------------------------------------------------------------------------------------
CREATE TABLE social_blocks (
    id          TEXT    PRIMARY KEY,
    blocker_uid TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    blocked_uid TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    created_at  INTEGER NOT NULL,
    CHECK (blocker_uid != blocked_uid)
);

CREATE UNIQUE INDEX idx_social_blocks_pair ON social_blocks (blocker_uid, blocked_uid);
CREATE INDEX idx_social_blocks_blocked ON social_blocks (blocked_uid);

-- -------------------------------------------------------------------------------------------------
-- 2. Denúncias de Abuso (Reports)
-- -------------------------------------------------------------------------------------------------
CREATE TABLE social_reports (
    id           TEXT    PRIMARY KEY,
    reporter_uid TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    reported_uid TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    reason       TEXT    NOT NULL,
    status       TEXT    NOT NULL DEFAULT 'PENDING',
    created_at   INTEGER NOT NULL,
    CHECK (reporter_uid != reported_uid)
);

CREATE INDEX idx_social_reports_reporter ON social_reports (reporter_uid, reported_uid, created_at);
CREATE INDEX idx_social_reports_reported ON social_reports (reported_uid);

-- -------------------------------------------------------------------------------------------------
-- 3. Tombstones de Exclusão de Conta (Anti-Ressurreição / HMAC)
-- -------------------------------------------------------------------------------------------------
CREATE TABLE account_deletion_tombstones (
    id         TEXT    PRIMARY KEY,
    uid_hash   TEXT    NOT NULL UNIQUE,
    deleted_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX idx_account_deletion_tombstones_hash ON account_deletion_tombstones (uid_hash);

-- -------------------------------------------------------------------------------------------------
-- 4. Jobs Efêmeros de Deleção Pendente no Firebase Admin
-- -------------------------------------------------------------------------------------------------
CREATE TABLE account_deletion_jobs (
    id              TEXT    PRIMARY KEY,
    firebase_uid    TEXT    NOT NULL UNIQUE,
    uid_hash        TEXT    NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at INTEGER NOT NULL,
    created_at      INTEGER NOT NULL
);

CREATE INDEX idx_account_deletion_jobs_due ON account_deletion_jobs (next_attempt_at);
