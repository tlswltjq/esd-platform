ALTER TABLE rating_revision
    ADD COLUMN country VARCHAR(2) NULL AFTER policy_version,
    ADD COLUMN target_rating_code VARCHAR(10) NULL AFTER country;

UPDATE rating_revision
SET country = 'KR',
    target_rating_code = CASE WHEN resolved_path = 'GRAC' THEN '18' ELSE 'ALL' END
WHERE country IS NULL;

ALTER TABLE rating_revision
    MODIFY COLUMN country VARCHAR(2) NOT NULL,
    MODIFY COLUMN target_rating_code VARCHAR(10) NOT NULL;
