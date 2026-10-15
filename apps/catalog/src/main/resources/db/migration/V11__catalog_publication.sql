CREATE TABLE catalog_release (
    release_request_id BIGINT NOT NULL PRIMARY KEY,
    request_event_id VARCHAR(64) NOT NULL,
    review_request_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    product_code VARCHAR(50) NOT NULL,
    content_version BIGINT NOT NULL,
    snapshot LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(2000),
    requested_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_catalog_request_event (request_event_id),
    KEY idx_catalog_release_version (project_id, content_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_product (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    product_code VARCHAR(50) NOT NULL,
    owner_subject VARCHAR(100) NOT NULL,
    current_release_request_id BIGINT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_catalog_project (project_id),
    UNIQUE KEY uk_catalog_product_code (product_code),
    KEY idx_catalog_published (published_at, id),
    CONSTRAINT fk_catalog_current_release FOREIGN KEY (current_release_request_id)
        REFERENCES catalog_release(release_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
