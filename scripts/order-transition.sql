-- Stop every old Order writer/relay before running against stove_order.
-- Explicit cutover only: preserve originals and keep old events out of the new relay.
CREATE TABLE IF NOT EXISTS order_legacy_outbox_archive LIKE outbox_event;
START TRANSACTION;
INSERT IGNORE INTO order_legacy_outbox_archive
SELECT * FROM outbox_event
WHERE status IN ('PENDING', 'DEAD') AND event_type <> 'OrderPlaced';
UPDATE outbox_event
SET status = 'DEAD', last_error = 'ORDER_LEGACY_QUARANTINE: original preserved in order_legacy_outbox_archive'
WHERE status = 'PENDING' AND event_type <> 'OrderPlaced';
COMMIT;
SELECT COUNT(*) AS unexpected_legacy_pending FROM outbox_event
WHERE status = 'PENDING' AND event_type <> 'OrderPlaced';
