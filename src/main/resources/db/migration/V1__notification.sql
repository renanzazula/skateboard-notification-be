-- Notification platform schema (see the implementation plan, §2).
--
-- Five concerns are kept apart deliberately, because they have different
-- lifetimes and different owners:
--   device       — where a push can be delivered   (churns with app installs)
--   preference   — whether the user wants it       (set by the user)
--   notification — what happened, once per event   (shared by all recipients)
--   user_notification — who it was for, read state (the future inbox)
--   delivery     — one attempt to one device       (infrastructure, retryable)
-- Collapsing any pair of these makes the others wrong: a "sent" push is not a
-- read notification, and one notification must not be duplicated per user.
--
-- Every table carries tenant_id even though exactly one tenant exists today.
-- Recipient resolution filters on it from day one so a second tenant can never
-- be introduced by a migration that silently starts leaking notifications.

CREATE TABLE notification_device (
    id                UUID         PRIMARY KEY,
    user_id           UUID         NOT NULL,
    tenant_id         UUID         NOT NULL,
    device_identifier VARCHAR(200) NOT NULL,
    platform          VARCHAR(20)  NOT NULL,
    push_provider     VARCHAR(20)  NOT NULL,
    push_token        VARCHAR(500) NOT NULL,
    app_version       VARCHAR(50),
    device_name       VARCHAR(200),
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    last_seen_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_notification_device_user_identifier UNIQUE (user_id, device_identifier)
);

-- The fan-out query's access path: all enabled devices in a tenant.
CREATE INDEX idx_notification_device_tenant_enabled
    ON notification_device (tenant_id) WHERE enabled = TRUE;

-- Used on registration to find and disable the same handset's registration
-- under a previously signed-in user (spec §29).
CREATE INDEX idx_notification_device_push_token
    ON notification_device (push_token);

-- The master push switch, separate from the per-type table so that turning
-- push off entirely stays one row regardless of how many types exist.
CREATE TABLE notification_channel_setting (
    user_id      UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    push_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

-- Per-type opt-out. A missing row means enabled, matching the DEFAULT TRUE
-- this preference had while skateboard-user-be owned it — so a user who never
-- opened the settings screen needs no row and no data migration.
CREATE TABLE notification_preference (
    id                UUID        PRIMARY KEY,
    user_id           UUID        NOT NULL,
    tenant_id         UUID        NOT NULL,
    notification_type VARCHAR(40) NOT NULL,
    push_enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_notification_preference_user_type UNIQUE (user_id, notification_type)
);

CREATE TABLE notification (
    id             UUID         PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    type           VARCHAR(40)  NOT NULL,
    title          VARCHAR(200) NOT NULL,
    body           VARCHAR(500) NOT NULL,
    image_url      TEXT,
    reference_type VARCHAR(40),
    reference_id   VARCHAR(200),
    -- Navigation metadata for the app, held opaquely as a JSON string. TEXT
    -- rather than jsonb for the same reason skateboard-app-config-be's
    -- about_us_page.blocks is (V8): nothing queries into it server-side.
    data           TEXT         NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_notification_tenant_created_at
    ON notification (tenant_id, created_at DESC);

-- One row per recipient, so a notification's content is stored once however
-- many users receive it. read_at is the only thing that means "the user has
-- seen this" — a SENT delivery does not.
CREATE TABLE user_notification (
    id              UUID        PRIMARY KEY,
    notification_id UUID        NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_user_notification_notification_user UNIQUE (notification_id, user_id)
);

-- The inbox query the history API will need; the partial index keeps the
-- unread badge cheap.
CREATE INDEX idx_user_notification_user_created_at
    ON user_notification (user_id, created_at DESC);
CREATE INDEX idx_user_notification_unread
    ON user_notification (user_id) WHERE read_at IS NULL;

CREATE TABLE notification_delivery (
    id                  UUID        PRIMARY KEY,
    notification_id     UUID        NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    user_id             UUID        NOT NULL,
    device_id           UUID        NOT NULL REFERENCES notification_device (id) ON DELETE CASCADE,
    channel             VARCHAR(20) NOT NULL,
    provider            VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    provider_message_id VARCHAR(200),
    attempt_count       INTEGER     NOT NULL DEFAULT 0,
    last_attempt_at     TIMESTAMPTZ,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_notification_delivery_notification_device UNIQUE (notification_id, device_id)
);

CREATE INDEX idx_notification_delivery_status
    ON notification_delivery (status);

-- The idempotency ledger (spec §24). Inserting the event id in the same
-- transaction that creates the notification is what makes a redelivered
-- message a no-op rather than a second push: the primary key collides and the
-- transaction rolls back before anything is written.
CREATE TABLE processed_event (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(60) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
