ALTER TABLE catalog_product
    ADD COLUMN download_restricted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN download_restriction_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN download_storage_status VARCHAR(20) NOT NULL DEFAULT 'APPLIED',
    ADD COLUMN download_storage_checked_at DATETIME(6) NULL,
    ADD COLUMN download_storage_next_attempt_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_download_restricted CHECK (download_restricted IN (FALSE, TRUE)),
    ADD CONSTRAINT chk_download_version CHECK (download_restriction_version >= 1),
    ADD CONSTRAINT chk_download_storage_status CHECK (download_storage_status IN ('PENDING','APPLIED','FAILED')),
    ADD CONSTRAINT chk_download_work CHECK (download_restriction_version = 1 OR download_storage_next_attempt_at IS NOT NULL);
CREATE INDEX idx_download_storage_due ON catalog_product(download_storage_next_attempt_at);

CREATE TABLE catalog_download_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    actor_subject VARCHAR(100) NOT NULL,
    previous_restricted BOOLEAN NOT NULL,
    restricted BOOLEAN NOT NULL,
    restriction_version BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_download_change_version(product_id, restriction_version),
    CONSTRAINT fk_download_change_product FOREIGN KEY(product_id) REFERENCES catalog_product(id),
    CONSTRAINT chk_download_change_transition CHECK (previous_restricted IN (FALSE,TRUE) AND restricted IN (FALSE,TRUE) AND previous_restricted <> restricted),
    CONSTRAINT chk_download_change_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
    CONSTRAINT chk_download_change_version CHECK (restriction_version > 1)
);
