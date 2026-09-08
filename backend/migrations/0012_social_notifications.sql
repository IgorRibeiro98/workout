-- T17.5 — Notificações sociais com Firebase Cloud Messaging (FCM).
--
-- Armazena preferências de notificação por usuário, registro de dispositivos por token FCM,
-- eventos duráveis de notificação (outbox transacional) e registros de entrega por dispositivo.
--
-- Princípios:
-- 1. Notificações sociais são estritamente opt-in: push_enabled nasce 0 (desligado por padrão).
-- 2. Notificação é sinal best-effort, nunca source of truth.
-- 3. FCM failure nunca faz rollback de ações sociais.
-- 4. Dados locais e de treino nunca entram em notificações sociais.

CREATE TABLE social_notification_preferences (
  owner_uid                     TEXT PRIMARY KEY REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  push_enabled                  INTEGER NOT NULL DEFAULT 0,
  friend_request_received       INTEGER NOT NULL DEFAULT 1,
  friend_request_accepted       INTEGER NOT NULL DEFAULT 1,
  challenge_invitation_received INTEGER NOT NULL DEFAULT 1,
  challenge_starting_soon       INTEGER NOT NULL DEFAULT 1,
  challenge_ended               INTEGER NOT NULL DEFAULT 1,
  updated_at                    INTEGER NOT NULL
);

CREATE TABLE social_push_devices (
  id                  TEXT PRIMARY KEY,
  owner_uid           TEXT NOT NULL REFERENCES social_profiles(owner_uid) ON DELETE CASCADE,
  device_id           TEXT NOT NULL,
  fcm_token           TEXT NOT NULL UNIQUE,
  platform            TEXT NOT NULL DEFAULT 'ANDROID' CHECK (platform = 'ANDROID'),
  enabled             INTEGER NOT NULL DEFAULT 1,
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL,
  last_registered_at  INTEGER NOT NULL,
  UNIQUE (owner_uid, device_id)
);

CREATE TABLE social_notification_events (
  id              TEXT PRIMARY KEY,
  recipient_uid   TEXT NOT NULL,
  type            TEXT NOT NULL CHECK (type IN (
                    'FRIEND_REQUEST_RECEIVED',
                    'FRIEND_REQUEST_ACCEPTED',
                    'CHALLENGE_INVITATION_RECEIVED',
                    'CHALLENGE_STARTING_SOON',
                    'CHALLENGE_ENDED'
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

CREATE INDEX idx_events_pending ON social_notification_events(status, deliver_after)
  WHERE status = 'PENDING';

CREATE TABLE social_notification_deliveries (
  event_id                TEXT NOT NULL REFERENCES social_notification_events(id) ON DELETE CASCADE,
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
