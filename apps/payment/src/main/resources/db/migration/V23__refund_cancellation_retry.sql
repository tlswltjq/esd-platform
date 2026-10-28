ALTER TABLE refund_execution
    ADD COLUMN next_cancel_retry_at DATETIME(6) NULL,
    ADD COLUMN cancel_retry_stage INT NOT NULL DEFAULT 0,
    ADD COLUMN cancel_retry_claim_id VARCHAR(36) NULL,
    ADD COLUMN cancel_retry_claimed_at DATETIME(6) NULL,
    ADD COLUMN cancel_retry_lease_until DATETIME(6) NULL,
    ADD COLUMN cancel_retry_finished_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_cancel_retry_stage CHECK (cancel_retry_stage BETWEEN 0 AND 2),
    ADD CONSTRAINT ck_cancel_retry_lease CHECK (
        (cancel_retry_claim_id IS NULL AND cancel_retry_claimed_at IS NULL AND cancel_retry_lease_until IS NULL)
        OR (cancel_retry_claim_id IS NOT NULL AND cancel_retry_claimed_at IS NOT NULL
            AND cancel_retry_lease_until > cancel_retry_claimed_at AND next_cancel_retry_at IS NOT NULL)),
    ADD CONSTRAINT ck_cancel_retry_time CHECK (next_cancel_retry_at IS NULL OR (
        first_cancel_prepared_at IS NOT NULL AND cancellation_request_body IS NOT NULL
        AND next_cancel_retry_at >= first_cancel_prepared_at AND refund_confirmed_at IS NULL AND status = 'PENDING'
        AND receipt_cancellation_id IS NULL AND cancellation_balance_changed_at IS NULL AND auto_cancel_stopped_at IS NULL)),
    ADD CONSTRAINT ck_cancel_retry_finish CHECK (cancel_retry_finished_at IS NULL OR (
        first_cancel_prepared_at IS NOT NULL AND cancel_retry_finished_at >= first_cancel_prepared_at)),
    ADD INDEX ix_cancel_retry_due (next_cancel_retry_at, cancel_retry_lease_until);

-- Existing preparations have no trustworthy completion time. The enabled scanner observes them
-- and persists a conservative ten-second recovery wait without inventing a historical completion.
