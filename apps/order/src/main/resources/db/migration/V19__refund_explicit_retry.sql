ALTER TABLE order_refund_attempt
    ADD COLUMN previous_attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN retry_requested_at DATETIME(6) NULL,
    ADD COLUMN retry_suspension_request_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uk_order_refund_attempt_previous (previous_attempt_id),
    ADD UNIQUE KEY uk_order_refund_retry_suspension (retry_suspension_request_event_id),
    ADD CONSTRAINT fk_order_refund_attempt_previous FOREIGN KEY (previous_attempt_id) REFERENCES order_refund_attempt (attempt_id),
    ADD CONSTRAINT chk_order_refund_attempt_previous CHECK (previous_attempt_id IS NULL OR (attempt_no > 1 AND previous_attempt_id <> attempt_id)),
    ADD CONSTRAINT chk_order_refund_retry_request CHECK (
        (retry_requested_at IS NULL AND retry_suspension_request_event_id IS NULL)
        OR (retry_requested_at IS NOT NULL AND retry_suspension_request_event_id IS NOT NULL AND status='FAILED'
            AND failure_event_id IS NOT NULL AND restored_event_id IS NOT NULL AND restored_at IS NOT NULL AND retry_requested_at >= restored_at));
