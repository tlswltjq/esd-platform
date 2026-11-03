ALTER TABLE project_review
    ADD COLUMN withdrawn_by VARCHAR(100) NULL,
    ADD COLUMN withdrawal_reason VARCHAR(2000) NULL,
    ADD COLUMN withdrawn_at DATETIME(6) NULL,
    ADD COLUMN active_project_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'REQUESTED' THEN project_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_review_active_project(active_project_id);

CREATE TABLE review_request_gate (
    request_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE review_withdrawal (
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
    KEY idx_review_withdrawal_pending(request_id, resolved, withdrawal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
