-- Decision 128: operator failure closure of a refund execution whose automatic POST stopped, and the low-frequency lookup
-- that continues after a failure. Existing rows are not rewritten: a row failed before this migration keeps its stopped
-- lookup schedule (NULL next_refund_lookup_at) and NULL late-cancellation columns, and it has no failure closure.
--
-- 1) A FAILED execution may now keep its read-only lookup schedule, and an operator failure closure fails an execution
--    that has no PG FAILED receipt (403·400, balance change, lost response). The closure record below proves it instead.
--    One ALTER statement, so MySQL applies the dropped and the new check together or not at all; the new check has its own
--    name so that both can appear in the same statement.
ALTER TABLE refund_execution
    DROP CHECK chk_refund_execution_failure,
    ADD COLUMN late_cancel_observed_at DATETIME(6) NULL,
    ADD COLUMN late_cancelled_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_refund_execution_failure_v40 CHECK (
        (status<>'FAILED' AND failure_event_id IS NULL AND failure_confirmed_at IS NULL AND failure_lookup_hash IS NULL)
        OR (status='FAILED' AND failure_event_id IS NOT NULL AND failure_confirmed_at IS NOT NULL AND failure_confirmed_at >= received_at
            AND failure_lookup_hash IS NOT NULL AND refund_confirmed_at IS NULL)),
    ADD CONSTRAINT chk_refund_execution_late_cancel CHECK (
        (late_cancel_observed_at IS NULL AND late_cancelled_at IS NULL)
        OR (status='FAILED' AND late_cancel_observed_at IS NOT NULL AND late_cancelled_at IS NOT NULL
            AND late_cancel_observed_at >= failure_confirmed_at AND late_cancelled_at <= late_cancel_observed_at));

-- 2) Append-only failure closure audit. One per attempt, one per command, one per failure event.
CREATE TABLE refund_failure_closure (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    note VARCHAR(500) NOT NULL,
    evidence VARCHAR(32) NOT NULL,
    stop_reason VARCHAR(24) NOT NULL,
    snapshot_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_observed_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NOT NULL,
    failure_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    failed_cancellation_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    -- The execution facts the first response reported; a replay returns these values.
    execution_status VARCHAR(20) NOT NULL,
    failure_confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_refund_failure_closure_request UNIQUE (request_id),
    CONSTRAINT uq_refund_failure_closure_attempt UNIQUE (attempt_id),
    CONSTRAINT uq_refund_failure_closure_event UNIQUE (failure_event_id),
    -- No ON DELETE CASCADE: deleting the execution must not silently erase this audit record.
    CONSTRAINT fk_refund_failure_closure_execution FOREIGN KEY (attempt_id) REFERENCES refund_execution(attempt_id),
    CONSTRAINT chk_refund_failure_closure_evidence CHECK (evidence IN ('NO_LINKED_CANCELLATION','LINKED_CANCELLATION_FAILED')),
    CONSTRAINT chk_refund_failure_closure_stop CHECK (stop_reason IN ('CANCELLATION_HOLD','BALANCE_CHANGED','WINDOW_ENDED')),
    CONSTRAINT chk_refund_failure_closure_time CHECK (closed_at >= evidence_observed_at AND failure_confirmed_at = closed_at),
    CONSTRAINT chk_refund_failure_closure_receipt CHECK (execution_status = 'FAILED'),
    CONSTRAINT chk_refund_failure_closure_note CHECK (CHAR_LENGTH(TRIM(note)) > 0),
    CONSTRAINT chk_refund_failure_closure_operator CHECK (CHAR_LENGTH(TRIM(operator_subject)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) The FAILED cancellations the PG listed at closure; the next attempt's preflight expects exactly these rows.
CREATE TABLE refund_failure_closure_cancellation (
    closure_id BIGINT NOT NULL,
    cancellation_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    tax_free_amount BIGINT NOT NULL,
    vat_amount BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    easy_pay_discount_amount BIGINT NULL,
    PRIMARY KEY (closure_id, cancellation_id),
    CONSTRAINT fk_refund_failure_closure_cancellation FOREIGN KEY (closure_id) REFERENCES refund_failure_closure(id),
    CONSTRAINT chk_refund_failure_closure_cancellation_amounts CHECK (tax_free_amount >= 0 AND vat_amount >= 0
        AND (easy_pay_discount_amount IS NULL OR easy_pay_discount_amount >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
