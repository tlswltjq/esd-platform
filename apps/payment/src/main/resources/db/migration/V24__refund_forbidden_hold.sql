CREATE TABLE refund_cancellation_hold (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    hold_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claimed_at DATETIME(6) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    reason VARCHAR(40) NOT NULL,
    activated BOOLEAN NOT NULL,
    resume_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resumed_at DATETIME(6) NULL,
    operator_subject VARCHAR(100) COLLATE utf8mb4_bin NULL,
    resume_note VARCHAR(500) NULL,
    CONSTRAINT uq_cancel_hold_id UNIQUE (hold_id),
    CONSTRAINT uq_cancel_hold_owner UNIQUE (attempt_id, claim_id),
    CONSTRAINT uq_cancel_resume_request UNIQUE (resume_request_id),
    CONSTRAINT fk_cancel_hold_execution FOREIGN KEY (attempt_id) REFERENCES refund_execution(attempt_id) ON DELETE CASCADE,
    CONSTRAINT ck_cancel_hold_observation CHECK (observed_at >= claimed_at AND reason = 'PG_REQUEST_FORBIDDEN'),
    CONSTRAINT ck_cancel_hold_resume CHECK (
        (resume_request_id IS NULL AND resumed_at IS NULL AND operator_subject IS NULL AND resume_note IS NULL)
        OR (resume_request_id IS NOT NULL AND resumed_at IS NOT NULL AND operator_subject IS NOT NULL
            AND resume_note IS NOT NULL AND activated = TRUE AND resumed_at >= observed_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE refund_execution
    ADD COLUMN active_cancel_hold_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT uq_execution_active_cancel_hold UNIQUE (active_cancel_hold_id),
    ADD CONSTRAINT ck_execution_cancel_hold CHECK (active_cancel_hold_id IS NULL OR (
        first_cancel_prepared_at IS NOT NULL AND cancellation_request_body IS NOT NULL
        AND next_cancel_retry_at IS NULL AND cancel_retry_claim_id IS NULL)),
    DROP CHECK ck_cancel_retry_time,
    ADD CONSTRAINT ck_cancel_retry_time CHECK (next_cancel_retry_at IS NULL OR (
        first_cancel_prepared_at IS NOT NULL AND cancellation_request_body IS NOT NULL
        AND next_cancel_retry_at >= first_cancel_prepared_at AND refund_confirmed_at IS NULL AND status = 'PENDING'
        AND receipt_cancellation_id IS NULL AND cancellation_balance_changed_at IS NULL
        AND auto_cancel_stopped_at IS NULL AND active_cancel_hold_id IS NULL));

-- No rejected POST is inferred from historical generic errors. Hold ownership and resume
-- history are coordinated under the execution lock; avoid a circular deletion FK for its active pointer.
