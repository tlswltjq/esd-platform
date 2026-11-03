ALTER TABLE studio_review_request
    ADD COLUMN request_event_id VARCHAR(64) NULL,
    ADD COLUMN withdrawal_reason VARCHAR(2000) NULL,
    ADD COLUMN finalized_at DATETIME(6) NULL,
    ADD COLUMN active_project_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'REQUESTED' THEN project_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_studio_active_review(active_project_id);

ALTER TABLE studio_release_request
    ADD COLUMN withdrawn_by VARCHAR(100) NULL,
    ADD COLUMN withdrawal_reason VARCHAR(2000) NULL,
    ADD COLUMN finalized_at DATETIME(6) NULL,
    ADD COLUMN active_project_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'REQUESTED' THEN project_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_studio_active_release(active_project_id);

CREATE TABLE studio_request_command (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    request_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    kind VARCHAR(32) NOT NULL,
    subject VARCHAR(100) NOT NULL,
    content_version BIGINT NOT NULL,
    target_request_id BIGINT NOT NULL,
    reason VARCHAR(2000) NULL,
    state VARCHAR(20) NOT NULL,
    final_status VARCHAR(20) NULL,
    final_actor VARCHAR(100) NULL,
    final_feedback VARCHAR(2000) NULL,
    finalized_at DATETIME(6) NULL,
    source_request_event_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_studio_command(project_id, kind, request_key),
    KEY idx_studio_command_target(project_id, kind, target_request_id, state),
    CONSTRAINT fk_studio_command_project FOREIGN KEY(project_id) REFERENCES studio_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
