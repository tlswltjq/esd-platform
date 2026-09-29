ALTER TABLE game_build
    DROP INDEX uk_build_game_version,
    ADD COLUMN build_number VARCHAR(100) NULL AFTER version,
    ADD COLUMN platform VARCHAR(30) NULL AFTER build_number,
    ADD COLUMN architecture VARCHAR(30) NULL AFTER platform,
    ADD COLUMN actual_checksum VARCHAR(64) NULL AFTER checksum,
    ADD COLUMN actual_file_size BIGINT NULL AFTER actual_checksum,
    ADD COLUMN commit_sha VARCHAR(64) NULL AFTER storage_path,
    ADD COLUMN repository VARCHAR(300) NULL AFTER commit_sha,
    ADD COLUMN ci_provider VARCHAR(30) NULL AFTER repository,
    ADD COLUMN ci_run_id VARCHAR(100) NULL AFTER ci_provider,
    ADD COLUMN idempotency_key VARCHAR(100) NULL AFTER ci_run_id,
    ADD COLUMN status VARCHAR(20) NULL AFTER idempotency_key,
    ADD COLUMN failure_code VARCHAR(50) NULL AFTER status,
    ADD COLUMN validated_at DATETIME(6) NULL AFTER failure_code,
    ADD COLUMN entity_version BIGINT NOT NULL DEFAULT 0 AFTER validated_at;

UPDATE game_build
SET build_number = version,
    platform = 'WINDOWS',
    architecture = 'X86_64',
    idempotency_key = CONCAT('legacy-', id),
    status = 'RETIRED'
WHERE build_number IS NULL;

ALTER TABLE game_build
    MODIFY build_number VARCHAR(100) NOT NULL,
    MODIFY platform VARCHAR(30) NOT NULL,
    MODIFY architecture VARCHAR(30) NOT NULL,
    MODIFY idempotency_key VARCHAR(100) NOT NULL,
    MODIFY status VARCHAR(20) NOT NULL,
    ADD UNIQUE KEY uk_build_game_idempotency (game_id, idempotency_key),
    ADD KEY idx_build_game_status (game_id, status, id);

CREATE TABLE upload_session
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    build_id          BIGINT       NOT NULL,
    storage_upload_id VARCHAR(200) NOT NULL,
    part_size         BIGINT       NOT NULL,
    part_count        INT          NOT NULL,
    idempotency_key   VARCHAR(100) NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    expires_at        DATETIME(6)  NOT NULL,
    completed_at      DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_session_build (build_id),
    UNIQUE KEY uk_upload_session_storage_id (storage_upload_id),
    CONSTRAINT fk_upload_session_build FOREIGN KEY (build_id) REFERENCES game_build (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
