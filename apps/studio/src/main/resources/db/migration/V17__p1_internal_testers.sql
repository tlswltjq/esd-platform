CREATE TABLE internal_tester_grant
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    game_id        BIGINT       NOT NULL,
    workspace_id   BIGINT       NOT NULL,
    tester_subject VARCHAR(100) NOT NULL,
    channel        VARCHAR(20)  NOT NULL,
    active         TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tester_game_subject_channel (game_id, tester_subject, channel),
    KEY idx_tester_subject_active (tester_subject, active, game_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
