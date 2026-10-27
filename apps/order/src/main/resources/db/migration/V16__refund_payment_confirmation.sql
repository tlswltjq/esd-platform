ALTER TABLE order_refund
    ADD COLUMN payment_confirmation_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN completed_at DATETIME(6) NULL,
    ADD COLUMN payment_confirmation_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN payment_confirmation_source VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_order_refund_payment_confirmation (payment_confirmation_event_id),
    ADD CONSTRAINT chk_order_refund_payment_confirmation CHECK (
        (status <> 'COMPLETED' AND payment_confirmation_event_id IS NULL AND completed_at IS NULL
            AND payment_confirmation_fingerprint IS NULL AND payment_confirmation_source IS NULL)
        OR (status = 'COMPLETED' AND payment_confirmation_event_id IS NOT NULL AND completed_at IS NOT NULL
            AND completed_at >= received_at AND payment_confirmation_fingerprint IS NOT NULL
            AND payment_confirmation_source IS NOT NULL AND payment_confirmation_source = 'PG_QUERY_UNATTRIBUTED'));

ALTER TABLE order_refund_attempt ADD COLUMN reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE;
