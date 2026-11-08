-- Decision 127: append-only operator closure of a delayed or unattributed refund execution. One closure per attempt,
-- one per command. It is written after a PG re-inspection confirmed the full cancellation of the same original payment.
-- This migration only adds a table; existing refund_execution rows are not touched. Unattributed rows that existed before
-- it have no closure and are therefore open targets of the list, gauge and alert.
CREATE TABLE refund_closure (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    attribution VARCHAR(24) NOT NULL,
    note VARCHAR(500) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    evidence_refunded_at DATETIME(6) NOT NULL,
    evidence_observed_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NOT NULL,
    -- The execution facts the first response reported. A late PG success may later move the execution to SUCCEEDED;
    -- a replay of the same command still returns these values.
    execution_status VARCHAR(20) NOT NULL,
    refund_confirmed_at DATETIME(6) NOT NULL,
    refund_confirmation_source VARCHAR(30) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_refund_closure_request UNIQUE (request_id),
    CONSTRAINT uq_refund_closure_attempt UNIQUE (attempt_id),
    -- No ON DELETE CASCADE: deleting the execution must not silently erase this audit record.
    CONSTRAINT fk_refund_closure_execution FOREIGN KEY (attempt_id) REFERENCES refund_execution(attempt_id),
    CONSTRAINT chk_refund_closure_attribution CHECK (attribution IN ('OUR_REQUEST','EXTERNAL_CANCELLATION','UNDETERMINED')),
    CONSTRAINT chk_refund_closure_outcome CHECK (outcome IN ('COMPLETED','RECORDED')),
    CONSTRAINT chk_refund_closure_time CHECK (closed_at >= evidence_observed_at),
    CONSTRAINT chk_refund_closure_receipt CHECK (execution_status = 'PENDING' AND refund_confirmation_source = 'PG_QUERY_UNATTRIBUTED'),
    CONSTRAINT chk_refund_closure_note CHECK (CHAR_LENGTH(TRIM(note)) > 0),
    CONSTRAINT chk_refund_closure_operator CHECK (CHAR_LENGTH(TRIM(operator_subject)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
