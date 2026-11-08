-- Decision 125: store when a refund entered PENDING_LICENSE_GRANT so Order can see refunds stuck waiting for LicenseGranted.
-- The column stays after the refund leaves the wait (operators read it) and is replaced when the refund enters the wait again.
ALTER TABLE order_refund
    ADD COLUMN license_grant_wait_started_at DATETIME(6) NULL;

-- Existing waiting rows: use only recorded facts, the same moments the code now records.
-- An automatic refund enters the wait at receipt. A reviewed refund enters it at the approval of its current round.
-- If a legacy row has no recorded decision time, fall back to the receipt time. That is never later than the real entry,
-- so an old stuck refund is counted as early as possible instead of being hidden (conservative for detection).
-- Rows in any other state keep NULL: their past waits were not recorded and are not invented.
UPDATE order_refund r
    LEFT JOIN order_refund_review_round w ON w.review_id = r.current_review_id
SET r.license_grant_wait_started_at = CASE
        WHEN r.automatic_refund_eligible THEN r.received_at
        ELSE COALESCE(w.decided_at, r.reviewed_at, r.received_at)
    END
WHERE r.status = 'PENDING_LICENSE_GRANT';

ALTER TABLE order_refund
    ADD CONSTRAINT chk_order_refund_license_grant_wait CHECK (
        status <> 'PENDING_LICENSE_GRANT' OR license_grant_wait_started_at IS NOT NULL),
    ADD INDEX ix_order_refund_license_grant_wait (status, license_grant_wait_started_at);
