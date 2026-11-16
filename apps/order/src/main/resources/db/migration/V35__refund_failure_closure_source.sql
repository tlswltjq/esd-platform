-- Decision 148: Payment publishes an operator failure closure (decision 128) as its own RefundFailureClosed event. Order
-- answers it like a PG-confirmed failure and records which fact failed the attempt: failure_reason_code is
-- 'OPERATOR_FAILURE_CLOSED' (a PG-confirmed failure keeps 'PG_CANCELLATION_FAILED') and failure_closure_id holds the Payment
-- closure request ID.
--
-- Existing rows are not rewritten: every failure recorded before this migration came from RefundExecutionFailed (the
-- operator closure was never released), so it keeps 'PG_CANCELLATION_FAILED' and a NULL closure ID, which the check accepts.
-- An older Order version still running beside this one writes only PG-confirmed failures with no closure ID, which the
-- check also accepts, so a rolling deployment does not have to stop every instance.
ALTER TABLE order_refund_attempt
    ADD COLUMN failure_closure_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT chk_refund_failure_closure_source CHECK (
        (failure_closure_id IS NULL AND (failure_reason_code IS NULL OR failure_reason_code <> 'OPERATOR_FAILURE_CLOSED'))
        OR (failure_closure_id IS NOT NULL AND failure_reason_code = 'OPERATOR_FAILURE_CLOSED' AND failure_event_id IS NOT NULL));
