CREATE TABLE ci_trust_policy
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    game_id               BIGINT       NOT NULL,
    workspace_id          BIGINT       NOT NULL,
    provider              VARCHAR(30)  NOT NULL,
    repository            VARCHAR(300) NOT NULL,
    ref_pattern           VARCHAR(300) NOT NULL,
    platform              VARCHAR(30)  NOT NULL,
    protected_ref_pattern VARCHAR(300) NULL,
    required_environment  VARCHAR(100) NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ci_trust_scope (game_id, provider, repository, ref_pattern, platform),
    KEY idx_ci_trust_lookup (game_id, provider, repository)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
