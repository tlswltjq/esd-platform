ALTER TABLE review_case
    MODIFY COLUMN external_submission_id VARCHAR(100) NULL,
    ADD COLUMN external_submitted_at DATETIME(6) NULL AFTER external_submission_id,
    ADD COLUMN external_evidence_url VARCHAR(500) NULL AFTER external_submitted_at;
