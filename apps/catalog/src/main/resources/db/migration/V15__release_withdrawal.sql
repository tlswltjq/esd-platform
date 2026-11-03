ALTER TABLE catalog_release
    ADD COLUMN withdrawn_by VARCHAR(100) NULL,
    ADD COLUMN withdrawal_reason VARCHAR(2000) NULL;

CREATE TABLE catalog_request_gate (
    request_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_publication_job (
    request_id BIGINT NOT NULL PRIMARY KEY,
    source_event_id VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    review_request_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    content_version BIGINT NOT NULL,
    product_code VARCHAR(50) NOT NULL,
    snapshot LONGTEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    next_attempt_at DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    trace_parent VARCHAR(55) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_catalog_publication_event(source_event_id),
    KEY idx_catalog_publication_due(state, next_attempt_at, request_id),
    KEY idx_catalog_publication_version(project_id, content_version, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE catalog_release_withdrawal (
    withdrawal_id BIGINT NOT NULL PRIMARY KEY,
    request_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    content_version BIGINT NOT NULL,
    product_code VARCHAR(50) NOT NULL,
    owner_subject VARCHAR(100) NOT NULL,
    reason VARCHAR(2000) NULL,
    requested_at DATETIME(6) NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_catalog_withdrawal_pending(request_id, resolved, withdrawal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
