ALTER TABLE submission_snapshot
    ADD COLUMN rating_country VARCHAR(2) NULL AFTER rating_policy_version,
    ADD COLUMN target_rating_code VARCHAR(10) NULL AFTER rating_country,
    ADD COLUMN rating_questionnaire TEXT NULL AFTER target_rating_code;

UPDATE submission_snapshot
SET rating_country = 'KR',
    target_rating_code = CASE WHEN rating_path = 'GRAC' THEN '18' ELSE 'ALL' END,
    rating_questionnaire = '{}'
WHERE rating_country IS NULL;

ALTER TABLE submission_snapshot
    MODIFY COLUMN rating_country VARCHAR(2) NOT NULL,
    MODIFY COLUMN target_rating_code VARCHAR(10) NOT NULL,
    MODIFY COLUMN rating_questionnaire TEXT NOT NULL;

ALTER TABLE review_case
    ADD COLUMN rating_path VARCHAR(30) NULL AFTER external_feedback,
    ADD COLUMN target_rating_code VARCHAR(10) NULL AFTER rating_path,
    ADD COLUMN external_submission_id VARCHAR(50) NULL AFTER target_rating_code;

UPDATE review_case rc
    JOIN submission_snapshot ss ON ss.submission_id = rc.submission_id
SET rc.rating_path = ss.rating_path,
    rc.target_rating_code = ss.target_rating_code
WHERE rc.review_type = 'RATING';
