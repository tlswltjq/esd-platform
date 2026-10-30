-- Preserve only provider-reported values. Missing historical point amounts remain unknown.
ALTER TABLE refund_execution
    ADD COLUMN receipt_easy_pay_discount_amount BIGINT NULL,
    ADD COLUMN response_easy_pay_discount_amount BIGINT NULL,
    ADD CONSTRAINT ck_refund_receipt_point_amount CHECK (
        receipt_easy_pay_discount_amount IS NULL
        OR (receipt_cancellation_id IS NOT NULL AND receipt_easy_pay_discount_amount >= 0
            AND receipt_easy_pay_discount_amount <= amount)),
    ADD CONSTRAINT ck_refund_response_point_amount CHECK (
        response_easy_pay_discount_amount IS NULL
        OR (status = 'SUCCEEDED' AND response_easy_pay_discount_amount >= 0
            AND response_easy_pay_discount_amount <= amount));

ALTER TABLE refund_cancellation_evidence
    ADD COLUMN easy_pay_discount_amount BIGINT NULL,
    ADD CONSTRAINT ck_refund_evidence_point_amount CHECK (
        easy_pay_discount_amount IS NULL OR (easy_pay_discount_amount >= 0 AND easy_pay_discount_amount <= amount));
