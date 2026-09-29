ALTER TABLE submission_snapshot
    ADD COLUMN build_variants_json TEXT NULL;

UPDATE submission_snapshot SET build_variants_json = '[]' WHERE build_variants_json IS NULL;

ALTER TABLE submission_snapshot
    MODIFY COLUMN build_variants_json TEXT NOT NULL;
