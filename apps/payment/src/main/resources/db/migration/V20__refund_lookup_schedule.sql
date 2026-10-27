ALTER TABLE refund_execution
    ADD COLUMN next_refund_lookup_at DATETIME(6) NULL,
    ADD COLUMN refund_lookup_stage INT NOT NULL DEFAULT 0,
    ADD COLUMN refund_lookup_claim_id VARCHAR(36) NULL,
    ADD COLUMN refund_lookup_lease_until DATETIME(6) NULL,
    ADD COLUMN refund_lookup_wake_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_refund_lookup_stage CHECK (refund_lookup_stage BETWEEN 0 AND 3),
    ADD CONSTRAINT ck_refund_lookup_lease CHECK (
        (refund_lookup_claim_id IS NULL AND refund_lookup_lease_until IS NULL)
        OR (refund_lookup_claim_id IS NOT NULL AND refund_lookup_lease_until IS NOT NULL AND next_refund_lookup_at IS NOT NULL)),
    ADD CONSTRAINT ck_refund_lookup_deadline CHECK (next_refund_lookup_at IS NULL OR (
        first_cancel_prepared_at IS NOT NULL AND refund_confirmed_at IS NULL
        AND next_refund_lookup_at >= first_cancel_prepared_at)),
    ADD CONSTRAINT ck_refund_lookup_wake CHECK (refund_lookup_wake_at IS NULL OR next_refund_lookup_at IS NOT NULL),
    ADD INDEX ix_refund_lookup_due (next_refund_lookup_at, refund_lookup_lease_until);

-- Preserve the original preparation anchor across deployment; never start a new cancellation.
UPDATE refund_execution r JOIN purchase_payment p ON p.payment_id = r.payment_id
SET r.next_refund_lookup_at = TIMESTAMPADD(SECOND, 5, r.first_cancel_prepared_at)
WHERE p.provider = 'PORTONE_TEST' AND r.first_cancel_prepared_at IS NOT NULL AND r.refund_confirmed_at IS NULL;
