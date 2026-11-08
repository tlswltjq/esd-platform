-- Decision 125 scope extension (decisions 125 and 129, no new policy): store when a refund entered
-- PENDING_LICENSE_SUSPENSION, PENDING_LICENSE_RESTORATION and PENDING_APPROVAL_STOP, so Order can see refunds stuck
-- waiting for LicenseSuspended, LicenseRestored or the approval correction. Like V32, each column stays after the refund
-- leaves that state (operators read it) and is replaced when the refund enters the state again.
ALTER TABLE order_refund
    ADD COLUMN license_suspension_wait_started_at  DATETIME(6) NULL,
    ADD COLUMN license_restoration_wait_started_at DATETIME(6) NULL,
    ADD COLUMN approval_stop_wait_started_at       DATETIME(6) NULL;

-- Existing waiting rows: use only recorded facts, the same moments the code now records.
-- Suspension: a retry enters at the current attempt's retry receipt. A first suspension enters when Order recorded the
-- LicenseSuspensionRequested event (its Outbox row, same transaction). If that row is gone, fall back to the grant wait
-- start and then the receipt time. Both are never later than the real entry, so an old stuck refund is counted as early
-- as possible instead of being hidden (conservative for detection, as in V32).
UPDATE order_refund r
    LEFT JOIN order_refund_attempt a ON a.attempt_id = r.current_attempt_id
    LEFT JOIN outbox_event e ON e.event_id = r.suspension_request_event_id
SET r.license_suspension_wait_started_at = COALESCE(
        CASE WHEN r.current_attempt_id IS NOT NULL THEN a.retry_requested_at END,
        e.created_at, r.license_grant_wait_started_at, r.received_at)
WHERE r.status = 'PENDING_LICENSE_SUSPENSION';

-- Restoration: the confirmed failure records the restoration request time on the current attempt in the same transaction.
UPDATE order_refund r
    LEFT JOIN order_refund_attempt a ON a.attempt_id = r.current_attempt_id
SET r.license_restoration_wait_started_at = COALESCE(a.restoration_requested_at, r.received_at)
WHERE r.status = 'PENDING_LICENSE_RESTORATION';

-- Approval stop: the stop receipt time on the current review round.
UPDATE order_refund r
    LEFT JOIN order_refund_review_round w ON w.review_id = r.current_review_id
SET r.approval_stop_wait_started_at = COALESCE(w.stop_received_at, r.received_at)
WHERE r.status = 'PENDING_APPROVAL_STOP';

-- Rows in any other state keep NULL: their past waits were not recorded and are not invented.
ALTER TABLE order_refund
    ADD CONSTRAINT chk_order_refund_license_suspension_wait CHECK (
        status <> 'PENDING_LICENSE_SUSPENSION' OR license_suspension_wait_started_at IS NOT NULL),
    ADD CONSTRAINT chk_order_refund_license_restoration_wait CHECK (
        status <> 'PENDING_LICENSE_RESTORATION' OR license_restoration_wait_started_at IS NOT NULL),
    ADD CONSTRAINT chk_order_refund_approval_stop_wait CHECK (
        status <> 'PENDING_APPROVAL_STOP' OR approval_stop_wait_started_at IS NOT NULL),
    ADD INDEX ix_order_refund_license_suspension_wait (status, license_suspension_wait_started_at),
    ADD INDEX ix_order_refund_license_restoration_wait (status, license_restoration_wait_started_at),
    ADD INDEX ix_order_refund_approval_stop_wait (status, approval_stop_wait_started_at);
