ALTER TABLE refund_execution
    ADD COLUMN first_cancel_prepared_at DATETIME(6) NULL,
    ADD COLUMN auto_cancel_stopped_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_refund_execution_preparation_time CHECK (
        first_cancel_prepared_at IS NULL OR first_cancel_prepared_at >= received_at),
    ADD CONSTRAINT chk_refund_execution_cancellation_stop CHECK (
        auto_cancel_stopped_at IS NULL OR (
            first_cancel_prepared_at IS NOT NULL
            AND auto_cancel_stopped_at >= TIMESTAMPADD(HOUR, 3, first_cancel_prepared_at)));
