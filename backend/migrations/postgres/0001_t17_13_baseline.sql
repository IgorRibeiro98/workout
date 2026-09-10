-- ============================================================================
-- Spark Backend — 0001_t17_13_baseline.sql (PostgreSQL / Neon)
--
-- Schema consolidado do Spark Backend no fechamento da T17.13.
-- Representa a evolução histórica das migrations SQLite 0001 até 0023 em DDL
-- nativo PostgreSQL, preservando todas as constraints, tipos semânticos,
-- chaves estrangeiras com ON DELETE CASCADE e índices parciais de autorização.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Metadata Operacional (T16.0)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS server_metadata (
  key        TEXT   NOT NULL PRIMARY KEY,
  value      TEXT   NOT NULL,
  updated_at BIGINT NOT NULL
);

-- ----------------------------------------------------------------------------
-- 2. Quota e Uso do Coach IA (T16.2)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_usage_daily (
  uid           TEXT    NOT NULL,
  utc_date      TEXT    NOT NULL,
  request_type  TEXT    NOT NULL,
  request_count INTEGER NOT NULL DEFAULT 0 CHECK (request_count >= 0),
  prompt_tokens INTEGER NOT NULL DEFAULT 0 CHECK (prompt_tokens >= 0),
  output_tokens INTEGER NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
  total_tokens  INTEGER NOT NULL DEFAULT 0 CHECK (total_tokens >= 0),
  updated_at    BIGINT  NOT NULL,
  PRIMARY KEY (uid, utc_date, request_type)
);

-- ----------------------------------------------------------------------------
-- 3. Backups e Snapshots Pessoais (T16.4)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS backup_snapshots (
  id                    BIGSERIAL PRIMARY KEY,
  backup_id             TEXT      NOT NULL UNIQUE,
  owner_uid             TEXT      NOT NULL,
  client_backup_id      TEXT      NOT NULL,
  device_id             TEXT      NOT NULL,
  backup_schema_version INTEGER   NOT NULL,
  payload_hash          TEXT      NOT NULL,
  item_count            INTEGER   NOT NULL,
  size_bytes            INTEGER   NOT NULL,
  captured_at           BIGINT,
  payload               TEXT,
  created_at            BIGINT    NOT NULL,
  UNIQUE (owner_uid, client_backup_id)
);

CREATE INDEX IF NOT EXISTS idx_backup_snapshots_owner
  ON backup_snapshots (owner_uid, id DESC);

CREATE TABLE IF NOT EXISTS backup_items (
  snapshot_id           BIGINT  NOT NULL REFERENCES backup_snapshots(id) ON DELETE CASCADE,
  entity_type           TEXT    NOT NULL,
  entity_sync_id        TEXT    NOT NULL,
  entity_schema_version INTEGER NOT NULL,
  payload               TEXT    NOT NULL,
  content_hash          TEXT    NOT NULL,
  PRIMARY KEY (snapshot_id, entity_type, entity_sync_id)
);

-- ----------------------------------------------------------------------------
-- 4. Sync Incremental Multi-device (T16.6 / T16.7)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sync_entities (
  owner_uid             TEXT    NOT NULL,
  entity_type           TEXT    NOT NULL,
  entity_sync_id        TEXT    NOT NULL,
  entity_schema_version INTEGER NOT NULL CHECK (entity_schema_version >= 1),
  server_revision       INTEGER NOT NULL CHECK (server_revision >= 1),
  last_server_sequence  BIGINT  NOT NULL,
  payload               TEXT    NOT NULL,
  payload_hash          TEXT    NOT NULL,
  origin_device_id      TEXT    NOT NULL,
  created_at            BIGINT  NOT NULL,
  updated_at            BIGINT  NOT NULL,
  deleted               BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at            BIGINT,
  deleted_by_device_id  TEXT,
  PRIMARY KEY (owner_uid, entity_type, entity_sync_id)
);

CREATE INDEX IF NOT EXISTS idx_sync_entities_lookup
  ON sync_entities (owner_uid, entity_type, entity_sync_id);

CREATE TABLE IF NOT EXISTS sync_changes (
  server_sequence       BIGSERIAL PRIMARY KEY,
  owner_uid             TEXT      NOT NULL,
  entity_type           TEXT      NOT NULL,
  entity_sync_id        TEXT      NOT NULL,
  entity_schema_version INTEGER   NOT NULL,
  server_revision       INTEGER   NOT NULL,
  operation             TEXT      NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
  payload               TEXT      NOT NULL,
  payload_hash          TEXT      NOT NULL,
  origin_device_id      TEXT      NOT NULL,
  created_at            BIGINT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sync_changes_pull
  ON sync_changes (owner_uid, server_sequence ASC);

CREATE TABLE IF NOT EXISTS sync_mutations (
  id                 BIGSERIAL PRIMARY KEY,
  owner_uid          TEXT      NOT NULL,
  client_mutation_id TEXT      NOT NULL,
  device_id          TEXT      NOT NULL,
  entity_type        TEXT      NOT NULL,
  entity_sync_id     TEXT      NOT NULL,
  operation          TEXT      NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
  base_revision      INTEGER,
  result_revision    INTEGER   NOT NULL,
  result_sequence    BIGINT    NOT NULL,
  payload_hash       TEXT      NOT NULL,
  applied_at         BIGINT    NOT NULL,
  UNIQUE (owner_uid, client_mutation_id)
);

-- ----------------------------------------------------------------------------
-- 5. Fundação Social e Privacidade (T17.0)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_profiles (
  owner_uid    TEXT    PRIMARY KEY,
  social_id    TEXT    NOT NULL UNIQUE,
  friend_code  TEXT    NOT NULL UNIQUE,
  display_name TEXT    NOT NULL,
  status       TEXT    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
  created_at   BIGINT  NOT NULL,
  updated_at   BIGINT  NOT NULL
);

CREATE TABLE IF NOT EXISTS social_privacy_settings (
  owner_uid                            TEXT    PRIMARY KEY REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  discoverability                      TEXT    NOT NULL DEFAULT 'FRIEND_CODE_ONLY' CHECK (discoverability IN ('FRIEND_CODE_ONLY')),
  friend_requests_enabled              BOOLEAN NOT NULL DEFAULT TRUE,
  activity_sharing_enabled             BOOLEAN NOT NULL DEFAULT FALSE,
  activity_time_zone_id                TEXT,
  friend_ranking_participation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at                           BIGINT  NOT NULL
);

CREATE TABLE IF NOT EXISTS social_progress_settings (
  owner_uid                      TEXT    PRIMARY KEY REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  share_level                    BOOLEAN NOT NULL DEFAULT FALSE,
  share_consistency_streak       BOOLEAN NOT NULL DEFAULT FALSE,
  share_weekly_workout_count     BOOLEAN NOT NULL DEFAULT FALSE,
  share_highlighted_achievements BOOLEAN NOT NULL DEFAULT FALSE,
  week_time_zone                 TEXT,
  updated_at                     BIGINT  NOT NULL
);

-- ----------------------------------------------------------------------------
-- 6. Grafo de Amizades (T17.1)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS friend_requests (
  request_id    TEXT   PRIMARY KEY,
  requester_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  recipient_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  status        TEXT   NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
  created_at    BIGINT NOT NULL,
  updated_at    BIGINT NOT NULL,
  CHECK (requester_uid <> recipient_uid)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_friend_requests_pending_pair
  ON friend_requests (requester_uid, recipient_uid)
  WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_friend_requests_incoming
  ON friend_requests (recipient_uid, status, created_at DESC, request_id DESC);

CREATE INDEX IF NOT EXISTS idx_friend_requests_outgoing
  ON friend_requests (requester_uid, status, created_at DESC, request_id DESC);

CREATE TABLE IF NOT EXISTS friendships (
  user_a_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  user_b_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (user_a_uid, user_b_uid),
  CHECK (user_a_uid < user_b_uid)
);

CREATE INDEX IF NOT EXISTS idx_friendships_user_b
  ON friendships (user_b_uid);

-- ----------------------------------------------------------------------------
-- 7. Desafios Sociais (T17.3)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS challenges (
  challenge_id      TEXT    PRIMARY KEY,
  creator_uid       TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
  name              TEXT    NOT NULL,
  type              TEXT    NOT NULL CHECK (type IN ('WORKOUTS_COMPLETED', 'ACTIVE_DAYS')),
  target            INTEGER NOT NULL CHECK (target > 0),
  start_date        TEXT    NOT NULL,
  end_date          TEXT    NOT NULL,
  time_zone_id      TEXT    NOT NULL,
  starts_at         BIGINT  NOT NULL,
  ends_at_exclusive BIGINT  NOT NULL,
  lifecycle         TEXT    NOT NULL CHECK (lifecycle IN ('OPEN', 'CANCELLED')),
  cancelled_at      BIGINT,
  created_at        BIGINT  NOT NULL,
  updated_at        BIGINT  NOT NULL,
  CHECK (ends_at_exclusive > starts_at),
  CHECK (end_date >= start_date),
  CHECK ((lifecycle = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_challenges_creator
  ON challenges (creator_uid, lifecycle, starts_at DESC);

CREATE INDEX IF NOT EXISTS idx_challenges_window
  ON challenges (starts_at, ends_at_exclusive);

CREATE TABLE IF NOT EXISTS challenge_invitations (
  invitation_id TEXT   PRIMARY KEY,
  challenge_id  TEXT   NOT NULL REFERENCES challenges (challenge_id) ON DELETE CASCADE,
  inviter_uid   TEXT   NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
  recipient_uid TEXT   NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
  status        TEXT   NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
  created_at    BIGINT NOT NULL,
  updated_at    BIGINT NOT NULL,
  CHECK (inviter_uid <> recipient_uid),
  UNIQUE (challenge_id, recipient_uid)
);

CREATE INDEX IF NOT EXISTS idx_challenge_invitations_recipient
  ON challenge_invitations (recipient_uid, status, created_at DESC, invitation_id DESC);

CREATE INDEX IF NOT EXISTS idx_challenge_invitations_challenge
  ON challenge_invitations (challenge_id, status);

CREATE TABLE IF NOT EXISTS challenge_participants (
  challenge_id    TEXT   NOT NULL REFERENCES challenges (challenge_id) ON DELETE CASCADE,
  participant_uid TEXT   NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
  role            TEXT   NOT NULL CHECK (role IN ('CREATOR', 'MEMBER')),
  status          TEXT   NOT NULL CHECK (status IN ('JOINED', 'WITHDRAWN')),
  joined_at       BIGINT NOT NULL,
  left_at         BIGINT,
  PRIMARY KEY (challenge_id, participant_uid),
  CHECK ((status = 'WITHDRAWN') = (left_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_challenge_participants_creator
  ON challenge_participants (challenge_id)
  WHERE role = 'CREATOR';

CREATE INDEX IF NOT EXISTS idx_challenge_participants_participant
  ON challenge_participants (participant_uid, status);

CREATE TABLE IF NOT EXISTS challenge_creation_requests (
  owner_uid         TEXT   NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
  client_request_id TEXT   NOT NULL,
  request_hash      TEXT   NOT NULL,
  challenge_id      TEXT   NOT NULL REFERENCES challenges (challenge_id) ON DELETE CASCADE,
  created_at        BIGINT NOT NULL,
  PRIMARY KEY (owner_uid, client_request_id)
);

-- ----------------------------------------------------------------------------
-- 8. Notificações Push Sociais (T17.5 / T17.7 / T17.11)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_notification_preferences (
  owner_uid                     TEXT    PRIMARY KEY REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  push_enabled                  BOOLEAN NOT NULL DEFAULT FALSE,
  friend_request_received       BOOLEAN NOT NULL DEFAULT TRUE,
  friend_request_accepted       BOOLEAN NOT NULL DEFAULT TRUE,
  challenge_invitation_received BOOLEAN NOT NULL DEFAULT TRUE,
  challenge_starting_soon       BOOLEAN NOT NULL DEFAULT TRUE,
  challenge_ended               BOOLEAN NOT NULL DEFAULT TRUE,
  workout_share_received        BOOLEAN NOT NULL DEFAULT TRUE,
  group_invitation_received     BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at                    BIGINT  NOT NULL
);

CREATE TABLE IF NOT EXISTS social_push_devices (
  id                 TEXT    PRIMARY KEY,
  owner_uid          TEXT    NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  device_id          TEXT    NOT NULL,
  fcm_token          TEXT    NOT NULL UNIQUE,
  platform           TEXT    NOT NULL DEFAULT 'ANDROID' CHECK (platform = 'ANDROID'),
  enabled            BOOLEAN NOT NULL DEFAULT TRUE,
  created_at         BIGINT  NOT NULL,
  updated_at         BIGINT  NOT NULL,
  last_registered_at BIGINT  NOT NULL,
  UNIQUE (owner_uid, device_id)
);

CREATE INDEX IF NOT EXISTS idx_social_push_devices_owner
  ON social_push_devices (owner_uid);

CREATE TABLE IF NOT EXISTS social_notification_events (
  id            TEXT   PRIMARY KEY,
  recipient_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  type          TEXT   NOT NULL CHECK (type IN (
    'FRIEND_REQUEST_RECEIVED',
    'FRIEND_REQUEST_ACCEPTED',
    'CHALLENGE_INVITATION_RECEIVED',
    'CHALLENGE_STARTING_SOON',
    'CHALLENGE_ENDED',
    'WORKOUT_SHARE_RECEIVED',
    'GROUP_INVITATION_RECEIVED'
  )),
  entity_id     TEXT   NOT NULL,
  dedupe_key    TEXT   NOT NULL UNIQUE,
  deliver_after BIGINT NOT NULL,
  expires_at    BIGINT NOT NULL,
  status        TEXT   NOT NULL DEFAULT 'PENDING' CHECK (status IN (
    'PENDING',
    'COMPLETED',
    'SUPPRESSED',
    'EXPIRED',
    'CANCELLED'
  )),
  created_at    BIGINT NOT NULL,
  completed_at  BIGINT
);

CREATE INDEX IF NOT EXISTS idx_social_notification_events_dispatch
  ON social_notification_events (status, deliver_after, expires_at);

CREATE INDEX IF NOT EXISTS idx_social_notification_events_recipient
  ON social_notification_events (recipient_uid, status);

CREATE TABLE IF NOT EXISTS social_notification_deliveries (
  event_id               TEXT    NOT NULL REFERENCES social_notification_events(id) ON DELETE CASCADE,
  device_registration_id TEXT    NOT NULL REFERENCES social_push_devices(id) ON DELETE CASCADE,
  status                 TEXT    NOT NULL DEFAULT 'PENDING' CHECK (status IN (
    'PENDING',
    'SENT',
    'FAILED_PERMANENT',
    'FAILED_TRANSIENT'
  )),
  attempt_count          INTEGER NOT NULL DEFAULT 0,
  next_attempt_at        BIGINT,
  last_error_code        TEXT,
  sent_at                BIGINT,
  PRIMARY KEY (event_id, device_registration_id)
);

CREATE INDEX IF NOT EXISTS idx_social_notification_deliveries_pending
  ON social_notification_deliveries (status, next_attempt_at);

-- ----------------------------------------------------------------------------
-- 9. Bloqueios e Denúncias (T17.6 / T17.9)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_blocks (
  id          TEXT   PRIMARY KEY,
  blocker_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  blocked_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  created_at  BIGINT NOT NULL,
  CHECK (blocker_uid <> blocked_uid)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_social_blocks_pair
  ON social_blocks (blocker_uid, blocked_uid);

CREATE INDEX IF NOT EXISTS idx_social_blocks_blocked
  ON social_blocks (blocked_uid);

CREATE TABLE IF NOT EXISTS social_reports (
  id           TEXT   PRIMARY KEY,
  reporter_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  reported_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  reason       TEXT   NOT NULL CHECK (reason IN ('SPAM', 'HARASSMENT', 'INAPPROPRIATE_BEHAVIOR', 'OTHER')),
  status       TEXT   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'REVIEWED', 'DISMISSED')),
  created_at   BIGINT NOT NULL,
  target_type  TEXT   NOT NULL DEFAULT 'USER' CHECK (target_type IN ('USER', 'CHECKIN', 'COMMENT')),
  target_id    TEXT,
  CHECK (reporter_uid <> reported_uid)
);

CREATE INDEX IF NOT EXISTS idx_social_reports_reporter
  ON social_reports (reporter_uid, reported_uid, created_at);

CREATE INDEX IF NOT EXISTS idx_social_reports_reported
  ON social_reports (reported_uid);

CREATE INDEX IF NOT EXISTS idx_social_reports_target
  ON social_reports (reporter_uid, target_type, target_id, created_at);

-- ----------------------------------------------------------------------------
-- 10. Exclusão de Conta e Tombstones (T17.6 / T17.13.1)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_deletion_tombstones (
  id         TEXT   PRIMARY KEY,
  uid_hash   TEXT   NOT NULL UNIQUE,
  deleted_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS account_deletion_jobs (
  id              TEXT    PRIMARY KEY,
  firebase_uid    TEXT    NOT NULL UNIQUE,
  uid_hash        TEXT    NOT NULL,
  attempts        INTEGER NOT NULL DEFAULT 0,
  last_error      TEXT,
  next_attempt_at BIGINT  NOT NULL,
  created_at      BIGINT  NOT NULL,
  phase           TEXT    NOT NULL DEFAULT 'LEDGER_PENDING' CHECK (phase IN ('LEDGER_PENDING', 'FIREBASE_PENDING'))
);

CREATE INDEX IF NOT EXISTS idx_account_deletion_jobs_phase
  ON account_deletion_jobs (phase, next_attempt_at);

-- ----------------------------------------------------------------------------
-- 11. Compartilhamento de Treino (T17.7)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workout_shares (
  id                TEXT    PRIMARY KEY,
  sender_uid        TEXT    NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  recipient_uid     TEXT    NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  snapshot_version  INTEGER NOT NULL,
  snapshot_json     TEXT    NOT NULL,
  snapshot_hash     TEXT    NOT NULL,
  status            TEXT    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'IMPORTED', 'DECLINED', 'CANCELLED', 'EXPIRED')),
  client_request_id TEXT    NOT NULL,
  created_at        BIGINT  NOT NULL,
  accepted_at       BIGINT,
  imported_at       BIGINT,
  declined_at       BIGINT,
  cancelled_at      BIGINT,
  expires_at        BIGINT  NOT NULL,
  CHECK (sender_uid <> recipient_uid)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_workout_shares_sender_client_request
  ON workout_shares (sender_uid, client_request_id);

CREATE INDEX IF NOT EXISTS idx_workout_shares_recipient
  ON workout_shares (recipient_uid, status, expires_at);

CREATE INDEX IF NOT EXISTS idx_workout_shares_sender
  ON workout_shares (sender_uid, status, created_at);

-- ----------------------------------------------------------------------------
-- 12. Check-ins de Treino e Mídia (T17.8 / T17.9 / T17.13.1)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_workout_checkins (
  id                     TEXT    PRIMARY KEY,
  author_uid             TEXT    NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  source_session_sync_id TEXT    NOT NULL,
  client_request_id      TEXT    NOT NULL,
  status                 TEXT    NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'DELETED')),
  created_at             BIGINT  NOT NULL,
  deleted_at             BIGINT,
  caption                TEXT    CHECK (caption IS NULL OR (length(caption) > 0 AND length(caption) <= 280)),
  CONSTRAINT uq_workout_checkins_author_session UNIQUE (author_uid, source_session_sync_id),
  CONSTRAINT uq_workout_checkins_author_request UNIQUE (author_uid, client_request_id)
);

CREATE INDEX IF NOT EXISTS idx_workout_checkins_feed
  ON social_workout_checkins (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_workout_checkins_author
  ON social_workout_checkins (author_uid, status, created_at DESC);

CREATE TABLE IF NOT EXISTS social_checkin_media (
  id                     TEXT    PRIMARY KEY,
  owner_uid              TEXT    NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  source_session_sync_id TEXT    NOT NULL,
  client_upload_id       TEXT    NOT NULL,
  storage_key            TEXT    NOT NULL UNIQUE,
  mime_type              TEXT    NOT NULL,
  byte_size              INTEGER NOT NULL,
  width                  INTEGER NOT NULL,
  height                 INTEGER NOT NULL,
  content_hash           TEXT    NOT NULL,
  status                 TEXT    NOT NULL CHECK (status IN ('PENDING', 'ATTACHED', 'DELETED')),
  created_at             BIGINT  NOT NULL,
  expires_at             BIGINT,
  attached_checkin_id    TEXT    REFERENCES social_workout_checkins(id) ON DELETE CASCADE,
  deleted_at             BIGINT,
  input_content_hash     TEXT,
  CONSTRAINT uq_checkin_media_owner_upload UNIQUE (owner_uid, client_upload_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_checkin_media_one_per_checkin
  ON social_checkin_media (attached_checkin_id)
  WHERE attached_checkin_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_checkin_media_owner_status
  ON social_checkin_media (owner_uid, status);

CREATE INDEX IF NOT EXISTS idx_checkin_media_expiry
  ON social_checkin_media (status, expires_at);

-- ----------------------------------------------------------------------------
-- 13. Grupos / Squads (T17.11 / T17.13.1)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_groups (
  id                TEXT   PRIMARY KEY,
  owner_uid         TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  name              TEXT   NOT NULL CHECK (length(name) >= 3 AND length(name) <= 80),
  status            TEXT   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),
  created_at        BIGINT NOT NULL,
  updated_at        BIGINT NOT NULL,
  deleted_at        BIGINT,
  client_request_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_social_groups_owner
  ON social_groups (owner_uid, status);

CREATE UNIQUE INDEX IF NOT EXISTS idx_social_groups_client_request
  ON social_groups (owner_uid, client_request_id)
  WHERE client_request_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS social_group_memberships (
  id         TEXT   PRIMARY KEY,
  group_id   TEXT   NOT NULL REFERENCES social_groups(id) ON DELETE CASCADE,
  member_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  role       TEXT   NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
  joined_at  BIGINT NOT NULL,
  UNIQUE (group_id, member_uid)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_social_group_single_owner
  ON social_group_memberships (group_id)
  WHERE role = 'OWNER';

CREATE INDEX IF NOT EXISTS idx_social_group_memberships_member
  ON social_group_memberships (member_uid);

CREATE TABLE IF NOT EXISTS social_group_invitations (
  id                TEXT   PRIMARY KEY,
  group_id          TEXT   NOT NULL REFERENCES social_groups(id) ON DELETE CASCADE,
  sender_uid        TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  recipient_uid     TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  status            TEXT   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED')),
  created_at        BIGINT NOT NULL,
  expires_at        BIGINT NOT NULL,
  responded_at      BIGINT,
  client_request_id TEXT,
  CHECK (sender_uid <> recipient_uid)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_social_group_invitations_pending
  ON social_group_invitations (group_id, recipient_uid)
  WHERE status = 'PENDING';

CREATE UNIQUE INDEX IF NOT EXISTS idx_social_group_invitations_client_request
  ON social_group_invitations (group_id, client_request_id)
  WHERE client_request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_social_group_invitations_recipient
  ON social_group_invitations (recipient_uid, status, created_at);

CREATE INDEX IF NOT EXISTS idx_social_group_invitations_sender
  ON social_group_invitations (sender_uid, status);

CREATE INDEX IF NOT EXISTS idx_social_group_invitations_expiry
  ON social_group_invitations (status, expires_at);

CREATE TABLE IF NOT EXISTS social_group_checkin_shares (
  id         TEXT   PRIMARY KEY,
  group_id   TEXT   NOT NULL REFERENCES social_groups(id) ON DELETE CASCADE,
  checkin_id TEXT   NOT NULL REFERENCES social_workout_checkins(id) ON DELETE CASCADE,
  author_uid TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  created_at BIGINT NOT NULL,
  UNIQUE (group_id, checkin_id)
);

CREATE INDEX IF NOT EXISTS idx_social_group_shares_feed
  ON social_group_checkin_shares (group_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_social_group_shares_checkin
  ON social_group_checkin_shares (checkin_id);

CREATE INDEX IF NOT EXISTS idx_social_group_shares_author
  ON social_group_checkin_shares (author_uid, group_id);

-- ----------------------------------------------------------------------------
-- 14. Audiências Contextuais: Reações e Comentários (T17.12)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_checkin_reactions (
  checkin_id    TEXT   NOT NULL REFERENCES social_workout_checkins(id) ON DELETE CASCADE,
  reactor_uid   TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  type          TEXT   NOT NULL CHECK (type IN ('FIRE', 'MUSCLE', 'CLAP')),
  audience_type TEXT   NOT NULL DEFAULT 'FRIEND' CHECK (audience_type IN ('FRIEND', 'GROUP')),
  group_id      TEXT   REFERENCES social_groups(id) ON DELETE CASCADE,
  created_at    BIGINT NOT NULL,
  updated_at    BIGINT NOT NULL,
  CHECK (
    (audience_type = 'FRIEND' AND group_id IS NULL)
    OR (audience_type = 'GROUP' AND group_id IS NOT NULL)
  )
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_checkin_reactions_friend_unique
  ON social_checkin_reactions (checkin_id, reactor_uid)
  WHERE audience_type = 'FRIEND';

CREATE UNIQUE INDEX IF NOT EXISTS idx_checkin_reactions_group_unique
  ON social_checkin_reactions (checkin_id, reactor_uid, group_id)
  WHERE audience_type = 'GROUP';

CREATE INDEX IF NOT EXISTS idx_checkin_reactions_reactor
  ON social_checkin_reactions (reactor_uid);

CREATE INDEX IF NOT EXISTS idx_checkin_reactions_group
  ON social_checkin_reactions (group_id, reactor_uid);

CREATE INDEX IF NOT EXISTS idx_checkin_reactions_lookup
  ON social_checkin_reactions (checkin_id, audience_type, group_id);

CREATE TABLE IF NOT EXISTS social_checkin_comments (
  id            TEXT   PRIMARY KEY,
  checkin_id    TEXT   NOT NULL REFERENCES social_workout_checkins(id) ON DELETE CASCADE,
  author_uid    TEXT   NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  body          TEXT   NOT NULL CHECK (length(body) > 0 AND length(body) <= 300),
  audience_type TEXT   NOT NULL DEFAULT 'FRIEND' CHECK (audience_type IN ('FRIEND', 'GROUP')),
  group_id      TEXT   REFERENCES social_groups(id) ON DELETE CASCADE,
  created_at    BIGINT NOT NULL,
  deleted_at    BIGINT,
  CHECK (
    (audience_type = 'FRIEND' AND group_id IS NULL)
    OR (audience_type = 'GROUP' AND group_id IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS idx_checkin_comments_thread
  ON social_checkin_comments (checkin_id, audience_type, group_id, created_at, id);

CREATE INDEX IF NOT EXISTS idx_checkin_comments_author
  ON social_checkin_comments (author_uid);

CREATE INDEX IF NOT EXISTS idx_checkin_comments_group
  ON social_checkin_comments (group_id, author_uid);
