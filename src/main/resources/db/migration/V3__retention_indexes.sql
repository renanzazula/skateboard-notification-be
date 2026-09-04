-- Retention (spec §40). PurgeExpiredDataService deletes aged rows in batches on
-- a nightly schedule; these are the access paths it needs.
--
-- notification is purged by created_at alone. idx_notification_tenant_created_at
-- already exists but leads with tenant_id, so it cannot serve a tenant-agnostic
-- range scan — hence a dedicated btree on created_at.
--
-- processed_event has no index at all today. It is the table that most needs
-- pruning (pure idempotency bookkeeping, worthless once a redelivery can no
-- longer arrive) and the purge filters it on processed_at.
--
-- user_notification and notification_delivery need nothing here: they are
-- removed by ON DELETE CASCADE from notification, and the leading column of
-- their (notification_id, ...) unique indexes already covers that.

CREATE INDEX idx_notification_created_at ON notification (created_at);
CREATE INDEX idx_processed_event_processed_at ON processed_event (processed_at);
