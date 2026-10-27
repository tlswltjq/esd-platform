ALTER TABLE refund_execution
    DROP CHECK chk_refund_execution_status,
    DROP CHECK chk_refund_execution_success,
    ADD COLUMN receipt_cancellation_id VARCHAR(100) COLLATE utf8mb4_bin NULL,
    ADD COLUMN receipt_state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN receipt_requested_at DATETIME(6) NULL,
    ADD COLUMN receipt_observed_at DATETIME(6) NULL,
    ADD COLUMN receipt_tax_free_amount BIGINT NULL,
    ADD COLUMN receipt_vat_amount BIGINT NULL,
    ADD COLUMN receipt_reason VARCHAR(500) NULL,
    ADD COLUMN failure_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN failure_confirmed_at DATETIME(6) NULL,
    ADD COLUMN failure_lookup_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_refund_execution_failure (failure_event_id),
    ADD CONSTRAINT chk_refund_execution_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    ADD CONSTRAINT chk_refund_execution_receipt CHECK (
        (receipt_cancellation_id IS NULL AND receipt_state IS NULL AND receipt_requested_at IS NULL AND receipt_observed_at IS NULL
            AND receipt_tax_free_amount IS NULL AND receipt_vat_amount IS NULL AND receipt_reason IS NULL)
        OR (receipt_cancellation_id IS NOT NULL AND receipt_state IN ('REQUESTED','FAILED') AND receipt_state IS NOT NULL
            AND receipt_requested_at IS NOT NULL AND receipt_observed_at IS NOT NULL AND receipt_requested_at <= receipt_observed_at
            AND first_cancel_prepared_at IS NOT NULL AND cancellation_request_body IS NOT NULL
            AND receipt_tax_free_amount IS NOT NULL AND receipt_tax_free_amount BETWEEN 0 AND amount
            AND receipt_vat_amount IS NOT NULL AND receipt_vat_amount BETWEEN 0 AND amount-receipt_tax_free_amount AND receipt_reason IS NOT NULL)),
    ADD CONSTRAINT chk_refund_execution_failure CHECK (
        (status<>'FAILED' AND failure_event_id IS NULL AND failure_confirmed_at IS NULL AND failure_lookup_hash IS NULL)
        OR (status='FAILED' AND failure_event_id IS NOT NULL AND failure_confirmed_at IS NOT NULL AND failure_confirmed_at >= received_at
            AND failure_lookup_hash IS NOT NULL AND receipt_state='FAILED' AND receipt_cancellation_id IS NOT NULL
            AND refund_confirmed_at IS NULL AND next_refund_lookup_at IS NULL AND refund_lookup_wake_at IS NULL AND refund_lookup_claim_id IS NULL)),
    ADD CONSTRAINT chk_refund_execution_success CHECK (
        (status<>'SUCCEEDED' AND cancellation_success_event_id IS NULL AND cancellation_succeeded_at IS NULL
            AND successful_cancellation_id IS NULL AND cancellation_requested_at IS NULL AND response_cancelled_at IS NULL
            AND response_tax_free_amount IS NULL AND response_vat_amount IS NULL AND response_reason IS NULL)
        OR (status='SUCCEEDED' AND cancellation_success_event_id IS NOT NULL AND cancellation_succeeded_at IS NOT NULL
            AND cancellation_succeeded_at >= received_at AND successful_cancellation_id IS NOT NULL
            AND first_cancel_prepared_at IS NOT NULL AND cancellation_request_body IS NOT NULL
            AND cancellation_requested_at IS NOT NULL AND cancellation_requested_at <= cancellation_succeeded_at
            AND (response_cancelled_at IS NULL OR (response_cancelled_at >= cancellation_requested_at AND response_cancelled_at <= cancellation_succeeded_at))
            AND response_tax_free_amount IS NOT NULL AND response_tax_free_amount BETWEEN 0 AND amount
            AND response_vat_amount IS NOT NULL AND response_vat_amount BETWEEN 0 AND amount-response_tax_free_amount
            AND response_reason IS NOT NULL AND refund_confirmed_at IS NOT NULL));
