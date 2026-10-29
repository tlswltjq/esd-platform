ALTER TABLE purchase_payment DROP CHECK chk_purchase_payment_state;
ALTER TABLE purchase_payment ADD CONSTRAINT chk_purchase_payment_state CHECK (
    (status='PENDING' AND next_attempt_at IS NOT NULL AND provider_transaction_id IS NULL AND provider_occurred_at IS NULL
        AND result_event_id IS NULL AND result_occurred_at IS NULL AND failure_reason_code IS NULL AND failure_reason IS NULL)
    OR (status='EXPIRED' AND next_attempt_at IS NULL AND next_failure_lookup_at IS NULL AND last_error_code IS NULL
        AND provider_transaction_id IS NULL AND provider_occurred_at IS NULL AND result_event_id IS NULL AND result_occurred_at IS NULL
        AND failure_reason_code IS NULL AND failure_reason IS NULL)
    OR (status IN ('PAID','FAILED') AND next_attempt_at IS NULL AND last_error_code IS NULL
        AND provider_transaction_id IS NOT NULL AND provider_occurred_at IS NOT NULL
        AND result_event_id IS NOT NULL AND result_occurred_at IS NOT NULL
        AND ((status='PAID' AND failure_reason_code IS NULL AND failure_reason IS NULL)
            OR (status='FAILED' AND failure_reason_code IS NOT NULL AND failure_reason IS NOT NULL)))
);
