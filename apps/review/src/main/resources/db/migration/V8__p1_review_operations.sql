ALTER TABLE review_case
    ADD COLUMN internal_memo VARCHAR(2000) NULL AFTER external_feedback,
    ADD COLUMN evidence_url VARCHAR(500) NULL AFTER internal_memo,
    ADD COLUMN assigned_to VARCHAR(100) NULL AFTER evidence_url,
    ADD COLUMN assigned_at DATETIME(6) NULL AFTER assigned_to,
    ADD COLUMN due_at DATETIME(6) NULL AFTER assigned_at,
    ADD COLUMN checklist_json TEXT NULL AFTER due_at,
    ADD COLUMN review_round INT NOT NULL DEFAULT 1 AFTER checklist_json,
    ADD COLUMN appeal_reason VARCHAR(1000) NULL AFTER review_round;

UPDATE review_case
SET due_at = DATE_ADD(created_at, INTERVAL 5 DAY), checklist_json = '{}'
WHERE due_at IS NULL OR checklist_json IS NULL;

ALTER TABLE review_case
    MODIFY due_at DATETIME(6) NOT NULL,
    MODIFY checklist_json TEXT NOT NULL;

CREATE INDEX idx_review_case_operations
    ON review_case (status, review_type, assigned_to, due_at, id);

CREATE TABLE review_decision_history
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    review_case_id BIGINT        NOT NULL,
    action         VARCHAR(40)   NOT NULL,
    actor          VARCHAR(100)  NOT NULL,
    from_status    VARCHAR(30)   NULL,
    to_status      VARCHAR(30)   NOT NULL,
    details        VARCHAR(2000) NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_review_history_case (review_case_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
