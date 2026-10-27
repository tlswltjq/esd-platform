ALTER TABLE order_refund_attempt
    ADD COLUMN failure_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN failure_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN restoration_request_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN restoration_requested_at DATETIME(6) NULL,
    ADD COLUMN restored_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN restored_at DATETIME(6) NULL,
    ADD COLUMN restoration_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_refund_failure_event (failure_event_id),
    ADD UNIQUE KEY uk_refund_restoration_request (restoration_request_event_id),
    ADD UNIQUE KEY uk_refund_restored_event (restored_event_id),
    ADD CONSTRAINT chk_refund_failure_restoration CHECK (
        (failure_event_id IS NULL AND failure_fingerprint IS NULL AND restoration_request_event_id IS NULL AND restoration_requested_at IS NULL)
        OR (status='FAILED' AND failure_event_id IS NOT NULL AND failure_fingerprint IS NOT NULL
            AND restoration_request_event_id IS NOT NULL AND restoration_requested_at IS NOT NULL
            AND resolved_at IS NOT NULL AND restoration_requested_at >= resolved_at AND reconciliation_required=FALSE)),
    ADD CONSTRAINT chk_refund_restored CHECK (
        (restored_event_id IS NULL AND restored_at IS NULL AND restoration_fingerprint IS NULL)
        OR (restored_event_id IS NOT NULL AND restored_at IS NOT NULL AND restoration_fingerprint IS NOT NULL
            AND restoration_request_event_id IS NOT NULL AND restored_at >= restoration_requested_at));
