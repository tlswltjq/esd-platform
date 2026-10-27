ALTER TABLE refund_execution
    ADD COLUMN refund_confirmed_at DATETIME(6) NULL,
    ADD COLUMN provider_refunded_at DATETIME(6) NULL,
    ADD COLUMN refund_confirmation_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uk_refund_execution_confirmation (refund_confirmation_event_id),
    ADD CONSTRAINT chk_refund_payment_confirmation CHECK (
        (refund_confirmed_at IS NULL AND refund_confirmation_event_id IS NULL AND provider_refunded_at IS NULL)
        OR (refund_confirmed_at IS NOT NULL AND refund_confirmed_at >= received_at AND refund_confirmation_event_id IS NOT NULL
            AND provider_refunded_at IS NOT NULL AND provider_refunded_at <= refund_confirmed_at));

CREATE TABLE refund_cancellation_evidence (
    execution_id BIGINT NOT NULL,
    cancellation_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    amount BIGINT NOT NULL,
    cancelled_at DATETIME(6) NULL,
    PRIMARY KEY (execution_id, cancellation_id),
    CONSTRAINT fk_refund_cancellation_evidence FOREIGN KEY (execution_id) REFERENCES refund_execution(id) ON DELETE CASCADE,
    CONSTRAINT chk_refund_cancellation_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
