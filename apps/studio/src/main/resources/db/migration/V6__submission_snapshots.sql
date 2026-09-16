CREATE TABLE store_page_revision
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    game_id              BIGINT        NOT NULL,
    revision_no          INT           NOT NULL,
    title                VARCHAR(200)  NOT NULL,
    short_description    VARCHAR(500)  NOT NULL,
    platform             VARCHAR(30)   NOT NULL,
    minimum_requirements VARCHAR(1000) NOT NULL,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_revision (game_id, revision_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE pricing_revision
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    game_id     BIGINT      NOT NULL,
    revision_no INT         NOT NULL,
    country     VARCHAR(2)  NOT NULL,
    currency    VARCHAR(3)  NOT NULL,
    price       BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pricing_revision (game_id, revision_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE rating_revision
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    game_id        BIGINT      NOT NULL,
    revision_no    INT         NOT NULL,
    policy_version VARCHAR(30) NOT NULL,
    questionnaire  TEXT        NOT NULL,
    resolved_path  VARCHAR(30) NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rating_revision (game_id, revision_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE submission
(
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    game_id              BIGINT      NOT NULL,
    workspace_id         BIGINT      NOT NULL,
    sequence_no          INT         NOT NULL,
    metadata_revision_id BIGINT      NOT NULL,
    pricing_revision_id  BIGINT      NOT NULL,
    rating_revision_id   BIGINT      NOT NULL,
    build_id             BIGINT      NOT NULL,
    status               VARCHAR(30) NOT NULL,
    entity_version       BIGINT      NOT NULL DEFAULT 0,
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_submission_sequence (game_id, sequence_no),
    KEY idx_submission_workspace (workspace_id, id),
    KEY idx_submission_status (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
