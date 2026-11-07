ALTER TABLE payment_approval
    ADD COLUMN review_started_at DATETIME(6) NULL,
    ADD COLUMN latest_pg_state VARCHAR(16) NULL,
    ADD COLUMN latest_pg_observed_at DATETIME(6) NULL;

-- Investigation notes are append-only evidence and never change approval or scheduling.
CREATE TABLE approval_investigation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    note VARCHAR(500) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    review_started_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_approval_investigation_request UNIQUE (request_id),
    CONSTRAINT fk_approval_investigation_attempt FOREIGN KEY (approval_id) REFERENCES payment_approval(approval_id) ON DELETE CASCADE,
    CONSTRAINT chk_approval_investigation_time CHECK (recorded_at >= review_started_at),
    CONSTRAINT chk_approval_investigation_note CHECK (CHAR_LENGTH(TRIM(note)) > 0),
    CONSTRAINT chk_approval_investigation_operator CHECK (CHAR_LENGTH(TRIM(operator_subject)) > 0),
    INDEX ix_approval_investigation_history (approval_id, recorded_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

