-- Email as a delivery channel (Guest Application confirmation/admin alert
-- emails, .docs/README_GUEST_APPLICATION_lang.md §9). Kept as its own table
-- rather than folded into notification_delivery: there is no device, no push
-- provider and no receipt-poll step, and making those nullable there would
-- make every push code path account for rows that can never apply to it.

CREATE TABLE email_delivery (
    id                  UUID         PRIMARY KEY,
    reference_type      VARCHAR(40),
    reference_id        VARCHAR(200),
    recipient_email     VARCHAR(320) NOT NULL,
    subject             VARCHAR(200) NOT NULL,
    body                TEXT         NOT NULL,
    provider            VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    provider_message_id VARCHAR(200),
    attempt_count       INTEGER      NOT NULL DEFAULT 0,
    last_attempt_at     TIMESTAMPTZ,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);

-- The retry claim's access path: FOR UPDATE SKIP LOCKED over PENDING rows,
-- same shape as idx_notification_delivery_status.
CREATE INDEX idx_email_delivery_status
    ON email_delivery (status);
