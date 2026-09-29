ALTER TABLE submission
    ADD COLUMN rating_path VARCHAR(30) NULL AFTER rating_country,
    ADD COLUMN rating_policy_version VARCHAR(30) NULL AFTER rating_path,
    ADD COLUMN rating_external_application_number VARCHAR(100) NULL AFTER rating_policy_version,
    ADD COLUMN rating_external_evidence_url VARCHAR(500) NULL AFTER rating_external_application_number;
