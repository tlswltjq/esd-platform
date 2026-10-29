ALTER TABLE order_payment_admission
    ADD COLUMN failed_supplier_attempt_id VARCHAR(100) COLLATE utf8mb4_bin NULL,
    ADD COLUMN failed_at DATETIME(6) NULL,
    ADD COLUMN closed_at DATETIME(6) NULL,
    ADD COLUMN closed_order_status VARCHAR(20) NULL,
    ADD CONSTRAINT chk_admission_failure_close CHECK (
        (failed_supplier_attempt_id IS NULL AND failed_at IS NULL AND closed_at IS NULL AND closed_order_status IS NULL)
        OR (failed_supplier_attempt_id IS NOT NULL AND failed_at IS NOT NULL AND closed_at IS NOT NULL
            AND closed_order_status IS NOT NULL AND closed_order_status IN ('PENDING','EXPIRED'))
    );
