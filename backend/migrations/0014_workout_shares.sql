-- T17.7 — Compartilhamento seguro de treinos entre amigos (Workout Shares)
--
-- Armazena ofertas de compartilhamento de WorkoutTemplates estruturados, snapshots imutáveis,
-- ciclo de vida (PENDING, ACCEPTED, IMPORTED, DECLINED, CANCELLED, EXPIRED) e integração com notificações.

-- 1. Tabela de Compartilhamento de Treinos
CREATE TABLE workout_shares (
    id                  TEXT    PRIMARY KEY,
    sender_uid          TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    recipient_uid       TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    snapshot_version    INTEGER NOT NULL,
    snapshot_json       TEXT    NOT NULL,
    snapshot_hash       TEXT    NOT NULL,
    status              TEXT    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'IMPORTED', 'DECLINED', 'CANCELLED', 'EXPIRED')),
    client_request_id   TEXT    NOT NULL,
    created_at          INTEGER NOT NULL,
    accepted_at         INTEGER,
    imported_at         INTEGER,
    declined_at         INTEGER,
    cancelled_at        INTEGER,
    expires_at          INTEGER NOT NULL,
    CHECK (sender_uid != recipient_uid)
);

CREATE UNIQUE INDEX idx_workout_shares_sender_client_request ON workout_shares (sender_uid, client_request_id);
CREATE INDEX idx_workout_shares_recipient ON workout_shares (recipient_uid, status, expires_at);
CREATE INDEX idx_workout_shares_sender ON workout_shares (sender_uid, status, created_at);

-- 2. Preferência de Notificação de Workout Share
ALTER TABLE social_notification_preferences ADD COLUMN workout_share_received INTEGER NOT NULL DEFAULT 1;

-- 3. Atualização do CHECK constraint de social_notification_events para suportar WORKOUT_SHARE_RECEIVED
CREATE TABLE social_notification_events_new (
  id              TEXT PRIMARY KEY,
  recipient_uid   TEXT NOT NULL,
  type            TEXT NOT NULL CHECK (type IN (
                    'FRIEND_REQUEST_RECEIVED',
                    'FRIEND_REQUEST_ACCEPTED',
                    'CHALLENGE_INVITATION_RECEIVED',
                    'CHALLENGE_STARTING_SOON',
                    'CHALLENGE_ENDED',
                    'WORKOUT_SHARE_RECEIVED'
                  )),
  entity_id       TEXT NOT NULL,
  dedupe_key      TEXT NOT NULL UNIQUE,
  deliver_after   INTEGER NOT NULL,
  expires_at      INTEGER NOT NULL,
  status          TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN (
                    'PENDING',
                    'COMPLETED',
                    'SUPPRESSED',
                    'EXPIRED',
                    'CANCELLED'
                  )),
  created_at      INTEGER NOT NULL,
  completed_at    INTEGER
);

CREATE TABLE social_notification_deliveries_new (
  event_id                TEXT NOT NULL REFERENCES social_notification_events_new(id) ON DELETE CASCADE,
  device_registration_id  TEXT NOT NULL REFERENCES social_push_devices(id) ON DELETE CASCADE,
  status                  TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN (
                            'PENDING',
                            'SENT',
                            'FAILED_PERMANENT',
                            'FAILED_TRANSIENT'
                          )),
  attempt_count           INTEGER NOT NULL DEFAULT 0,
  next_attempt_at         INTEGER,
  last_error_code         TEXT,
  sent_at                 INTEGER,
  PRIMARY KEY (event_id, device_registration_id)
);

INSERT INTO social_notification_events_new SELECT * FROM social_notification_events;
INSERT INTO social_notification_deliveries_new SELECT * FROM social_notification_deliveries;

DROP TABLE social_notification_deliveries;
DROP TABLE social_notification_events;

ALTER TABLE social_notification_events_new RENAME TO social_notification_events;
ALTER TABLE social_notification_deliveries_new RENAME TO social_notification_deliveries;

CREATE INDEX idx_social_notification_events_dispatch
  ON social_notification_events (status, deliver_after, expires_at);
CREATE INDEX idx_social_notification_events_recipient
  ON social_notification_events (recipient_uid, status);
CREATE INDEX idx_social_notification_deliveries_pending
  ON social_notification_deliveries (status, next_attempt_at);
