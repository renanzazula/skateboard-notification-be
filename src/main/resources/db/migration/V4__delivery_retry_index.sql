-- Numbered V4 rather than V3: retention landed on main first and took that
-- version. Two migrations sharing a version is not a merge conflict git can
-- see — it is a startup failure, since Flyway refuses to resolve them.
--
-- Supports the retry poll in SpringNotificationDeliveryRepository.lockRetryable,
-- which runs every couple of minutes for the life of the service.
--
-- V1's index is on status alone, so it indexes every row in the table — and
-- almost all of them end up SENT, which is precisely the set the poll never
-- wants. Restricting it to PENDING keeps the index small however much delivery
-- history accumulates.
--
-- Ordered by (created_at, id) rather than by the columns in the WHERE clause,
-- because that is the query's ORDER BY: leading with last_attempt_at would
-- filter well but still force a sort of every candidate before the LIMIT could
-- take the first few. This way a backlog is walked oldest-first and the scan
-- stops as soon as the batch is full, with attempt_count and last_attempt_at
-- applied as it goes.
CREATE INDEX idx_notification_delivery_pending
    ON notification_delivery (created_at, id)
    WHERE status = 'PENDING';
