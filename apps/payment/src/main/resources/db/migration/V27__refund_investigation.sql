-- Investigation notes are append-only evidence and never change refund execution or scheduling.
CREATE TABLE refund_investigation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    note VARCHAR(500) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    delayed_since DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_refund_investigation_request UNIQUE (request_id),
    CONSTRAINT fk_refund_investigation_attempt FOREIGN KEY (attempt_id) REFERENCES refund_execution(attempt_id) ON DELETE CASCADE,
    CONSTRAINT chk_refund_investigation_time CHECK (recorded_at >= delayed_since),
    CONSTRAINT chk_refund_investigation_note CHECK (CHAR_LENGTH(TRIM(note)) > 0),
    CONSTRAINT chk_refund_investigation_operator CHECK (CHAR_LENGTH(TRIM(operator_subject)) > 0),
    INDEX ix_refund_investigation_history (attempt_id, recorded_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX ix_refund_execution_delay ON refund_execution (status, refund_confirmed_at, first_cancel_prepared_at, id);
