ALTER TABLE order_refund_attempt
    ADD COLUMN success_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN success_confirmation_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN successful_cancellation_id VARCHAR(100) COLLATE utf8mb4_bin NULL,
    ADD COLUMN success_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_order_refund_attempt_success (success_event_id),
    ADD CONSTRAINT chk_order_refund_attempt_success CHECK (
        (success_event_id IS NULL AND success_confirmation_event_id IS NULL AND successful_cancellation_id IS NULL AND success_fingerprint IS NULL)
        OR (success_event_id IS NOT NULL AND success_confirmation_event_id IS NOT NULL AND successful_cancellation_id IS NOT NULL
            AND success_fingerprint IS NOT NULL AND status='SUCCEEDED' AND resolved_at IS NOT NULL AND resolved_at >= started_at
            AND reconciliation_required=FALSE));

ALTER TABLE order_refund DROP CHECK chk_order_refund_payment_confirmation,
    ADD CONSTRAINT chk_order_refund_payment_confirmation CHECK (
        (status <> 'COMPLETED' AND payment_confirmation_event_id IS NULL AND completed_at IS NULL
            AND payment_confirmation_fingerprint IS NULL AND payment_confirmation_source IS NULL)
        OR (status = 'COMPLETED' AND payment_confirmation_event_id IS NOT NULL AND completed_at IS NOT NULL
            AND completed_at >= received_at AND payment_confirmation_fingerprint IS NOT NULL AND payment_confirmation_source IS NOT NULL
            AND payment_confirmation_source IN ('PG_QUERY_UNATTRIBUTED','PG_RESPONSE')));
