-- Explicit cutover with all old Payment writers/relays stopped. Original rows are retained.
-- Does not establish safe offsets for historical OrderPlaced: audit those separately before a shared-stack rollout.
CREATE TABLE IF NOT EXISTS payment_legacy_outbox_archive LIKE outbox_event;
START TRANSACTION;
INSERT IGNORE INTO payment_legacy_outbox_archive
SELECT * FROM outbox_event WHERE status IN ('PENDING','DEAD')
    AND event_type NOT IN ('OrderPaymentCompleted','OrderPaymentFailed');
UPDATE outbox_event SET status='DEAD',last_error='PAYMENT_LEGACY_QUARANTINE: original preserved in payment_legacy_outbox_archive'
WHERE status='PENDING' AND event_type NOT IN ('OrderPaymentCompleted','OrderPaymentFailed');
COMMIT;
SELECT COUNT(*) AS unexpected_legacy_pending FROM outbox_event
WHERE status='PENDING' AND event_type NOT IN ('OrderPaymentCompleted','OrderPaymentFailed');
