ALTER TABLE payment_approval
    ADD COLUMN last_inspected_at DATETIME(6) NULL,
    ADD COLUMN last_inspection_error VARCHAR(32) NULL,
    ADD INDEX ix_payment_approval_review (status, last_error_code, received_at, id),
    ADD CONSTRAINT chk_approval_pg_state CHECK (latest_pg_state IS NULL OR latest_pg_state IN ('READY','PENDING','PAID','FAILED')),
    ADD CONSTRAINT chk_approval_pg_observation CHECK ((latest_pg_state IS NULL AND latest_pg_observed_at IS NULL) OR (latest_pg_state IS NOT NULL AND latest_pg_observed_at IS NOT NULL)),
    ADD CONSTRAINT chk_approval_review_time CHECK (review_started_at IS NULL OR review_started_at >= received_at),
    ADD CONSTRAINT chk_approval_inspection_error CHECK (last_inspection_error IS NULL OR last_inspection_error IN ('INVALID_EVIDENCE','UNAVAILABLE'));
