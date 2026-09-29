CREATE TABLE build_webhook_subscription
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    game_id          BIGINT        NOT NULL,
    workspace_id     BIGINT        NOT NULL,
    endpoint_url     VARCHAR(500)  NOT NULL,
    encrypted_secret VARCHAR(1000) NOT NULL,
    active           TINYINT(1)    NOT NULL DEFAULT 1,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_build_webhook_game (game_id, active, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE build_webhook_delivery
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    subscription_id BIGINT       NOT NULL,
    build_id        BIGINT       NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NOT NULL,
    delivered_at    DATETIME(6)  NULL,
    last_error      VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_build_webhook_due (status, next_attempt_at, id),
    KEY idx_build_webhook_build (build_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
