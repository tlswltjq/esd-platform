CREATE TABLE project_credential
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    game_id      BIGINT       NOT NULL,
    workspace_id BIGINT       NOT NULL,
    name         VARCHAR(100) NOT NULL,
    secret_hash  VARCHAR(64)  NOT NULL,
    token_prefix VARCHAR(16)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    expires_at   DATETIME(6)  NULL,
    last_used_at DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_credential_hash (secret_hash),
    KEY idx_project_credential_game (game_id, id),
    CONSTRAINT fk_project_credential_game FOREIGN KEY (game_id) REFERENCES game_project (id),
    CONSTRAINT fk_project_credential_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
