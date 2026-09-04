-- Supports the receipt poll in
-- SpringNotificationDeliveryRepository.findAwaitingReceipt, which runs on a
-- schedule for the life of the service.
--
-- Partial on SENT for the same reason V4 is partial on PENDING: it is a small
-- slice of a table that only grows, and indexing the settled rows would be
-- pure overhead. Keyed by last_attempt_at, which is both the range the query
-- bounds at each end and its ORDER BY, so the LIMIT can stop early.
CREATE INDEX idx_notification_delivery_awaiting_receipt
    ON notification_delivery (last_attempt_at, id)
    WHERE status = 'SENT' AND provider_message_id IS NOT NULL;
