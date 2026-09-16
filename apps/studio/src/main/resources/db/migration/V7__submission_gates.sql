CREATE TABLE submission_gate
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    submission_id  BIGINT        NOT NULL,
    review_type    VARCHAR(30)   NOT NULL,
    status         VARCHAR(30)   NOT NULL,
    reason_code    VARCHAR(30)   NULL,
    feedback       VARCHAR(1000) NULL,
    entity_version BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_submission_gate_type (submission_id, review_type),
    KEY idx_submission_gate_status (submission_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
