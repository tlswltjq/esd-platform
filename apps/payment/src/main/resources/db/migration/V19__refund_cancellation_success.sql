ALTER TABLE refund_execution
    ADD COLUMN refund_confirmation_source VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN cancellation_success_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN cancellation_succeeded_at DATETIME(6) NULL,
    ADD COLUMN successful_cancellation_id VARCHAR(100) COLLATE utf8mb4_bin NULL,
    ADD COLUMN cancellation_requested_at DATETIME(6) NULL,
    ADD COLUMN response_cancelled_at DATETIME(6) NULL,
    ADD COLUMN response_tax_free_amount BIGINT NULL,
    ADD COLUMN response_vat_amount BIGINT NULL,
    ADD COLUMN response_reason VARCHAR(500) NULL,
    ADD UNIQUE KEY uk_refund_execution_success (cancellation_success_event_id);

UPDATE refund_execution SET refund_confirmation_source='PG_QUERY_UNATTRIBUTED' WHERE refund_confirmed_at IS NOT NULL;

ALTER TABLE refund_execution
    DROP CHECK chk_refund_execution_status,
    DROP CHECK chk_refund_payment_confirmation,
    ADD CONSTRAINT chk_refund_execution_status CHECK (status IN ('PENDING','SUCCEEDED')),
    ADD CONSTRAINT chk_refund_payment_confirmation CHECK (
        (refund_confirmed_at IS NULL AND refund_confirmation_event_id IS NULL AND provider_refunded_at IS NULL AND refund_confirmation_source IS NULL)
        OR (refund_confirmed_at IS NOT NULL AND refund_confirmed_at >= received_at AND refund_confirmation_event_id IS NOT NULL
            AND refund_confirmation_source IS NOT NULL AND (
                (refund_confirmation_source='PG_QUERY_UNATTRIBUTED' AND provider_refunded_at IS NOT NULL AND provider_refunded_at <= refund_confirmed_at)
                OR (refund_confirmation_source='PG_RESPONSE' AND status='SUCCEEDED' AND (provider_refunded_at IS NULL OR provider_refunded_at <= refund_confirmed_at))))),
    ADD CONSTRAINT chk_refund_execution_success CHECK (
        (status='PENDING' AND cancellation_success_event_id IS NULL AND cancellation_succeeded_at IS NULL
            AND successful_cancellation_id IS NULL AND cancellation_requested_at IS NULL AND response_cancelled_at IS NULL
            AND response_tax_free_amount IS NULL AND response_vat_amount IS NULL AND response_reason IS NULL)
        OR (status='SUCCEEDED' AND cancellation_success_event_id IS NOT NULL AND cancellation_succeeded_at IS NOT NULL
            AND cancellation_succeeded_at >= received_at AND successful_cancellation_id IS NOT NULL
            AND first_cancel_prepared_at IS NOT NULL AND cancellation_request_body IS NOT NULL
            AND cancellation_requested_at IS NOT NULL AND cancellation_requested_at <= cancellation_succeeded_at
            AND (response_cancelled_at IS NULL OR (response_cancelled_at >= cancellation_requested_at AND response_cancelled_at <= cancellation_succeeded_at))
            AND response_tax_free_amount IS NOT NULL AND response_tax_free_amount >= 0 AND response_tax_free_amount <= amount
            AND response_vat_amount IS NOT NULL AND response_vat_amount >= 0 AND response_vat_amount <= amount-response_tax_free_amount
            AND response_reason IS NOT NULL AND refund_confirmed_at IS NOT NULL));
