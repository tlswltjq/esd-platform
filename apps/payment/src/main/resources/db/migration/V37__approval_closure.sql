-- Decision 115: append-only operator closure of a held approval. One closure per approval, one per command.
-- The row is written in the same transaction as the approval and purchase transition; existing rows are not touched.
CREATE TABLE approval_closure (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    note VARCHAR(500) NOT NULL,
    evidence_state VARCHAR(16) NOT NULL,
    evidence_observed_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_approval_closure_request UNIQUE (request_id),
    CONSTRAINT uq_approval_closure_approval UNIQUE (approval_id),
    -- No ON DELETE CASCADE: deleting the approval (or its purchase) must not silently erase this audit record.
    CONSTRAINT fk_approval_closure_approval FOREIGN KEY (approval_id) REFERENCES payment_approval(approval_id),
    CONSTRAINT chk_approval_closure_evidence CHECK (evidence_state IN ('CANCELLED','FAILED','READY')),
    CONSTRAINT chk_approval_closure_time CHECK (closed_at >= evidence_observed_at),
    CONSTRAINT chk_approval_closure_note CHECK (CHAR_LENGTH(TRIM(note)) > 0),
    CONSTRAINT chk_approval_closure_operator CHECK (CHAR_LENGTH(TRIM(operator_subject)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
