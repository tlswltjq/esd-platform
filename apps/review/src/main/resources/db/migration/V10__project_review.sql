CREATE TABLE project_review
(
    review_request_id BIGINT NOT NULL,
    request_event_id VARCHAR(64) NOT NULL,
    project_id BIGINT NOT NULL,
    product_code VARCHAR(50) NOT NULL,
    owner_subject VARCHAR(100) NOT NULL,
    content_version BIGINT NOT NULL,
    snapshot LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    feedback VARCHAR(2000) NULL,
    actor VARCHAR(100) NULL,
    decided_at DATETIME(6) NULL,
    requested_at DATETIME(6) NOT NULL,
    entity_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (review_request_id),
    KEY idx_project_review_status (status, requested_at, review_request_id),
    KEY idx_project_review_project (project_id, requested_at, review_request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
