ALTER TABLE project_credential
    ADD COLUMN allowed_repository VARCHAR(300) NULL AFTER last_used_at,
    ADD COLUMN allowed_ref VARCHAR(300) NULL AFTER allowed_repository,
    ADD COLUMN allowed_platform VARCHAR(30) NULL AFTER allowed_ref,
    ADD COLUMN release_allowed TINYINT(1) NOT NULL DEFAULT 0 AFTER allowed_platform,
    ADD COLUMN source_provider VARCHAR(30) NULL AFTER release_allowed,
    ADD COLUMN source_environment VARCHAR(100) NULL AFTER source_provider,
    ADD COLUMN oidc_token_id VARCHAR(200) NULL AFTER source_environment,
    ADD UNIQUE KEY uk_project_credential_oidc_token (oidc_token_id);

ALTER TABLE game_build
    ADD COLUMN source_ref VARCHAR(300) NULL AFTER repository;
