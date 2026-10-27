ALTER TABLE refund_execution
    ADD COLUMN cancellation_balance_changed_at DATETIME(6) NULL,
    ADD COLUMN cancellation_request_body VARCHAR(4096) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT chk_refund_cancellation_balance_block CHECK (
        cancellation_balance_changed_at IS NULL OR (first_cancel_prepared_at IS NOT NULL
            AND cancellation_balance_changed_at >= first_cancel_prepared_at)),
    ADD CONSTRAINT chk_refund_cancellation_body CHECK (
        cancellation_request_body IS NULL OR (first_cancel_prepared_at IS NOT NULL AND CHAR_LENGTH(TRIM(cancellation_request_body)) > 0));
