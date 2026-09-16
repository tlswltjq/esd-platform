ALTER TABLE submission
    ADD COLUMN rating_code VARCHAR(10) NULL AFTER status,
    ADD COLUMN rating_certification_number VARCHAR(100) NULL AFTER rating_code,
    ADD COLUMN rating_issuer VARCHAR(100) NULL AFTER rating_certification_number,
    ADD COLUMN rating_issued_at DATETIME(6) NULL AFTER rating_issuer,
    ADD COLUMN rating_country VARCHAR(2) NULL AFTER rating_issued_at;

CREATE TABLE game_release
(
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    game_id              BIGINT      NOT NULL,
    workspace_id         BIGINT      NOT NULL,
    submission_id        BIGINT      NOT NULL,
    build_id             BIGINT      NOT NULL,
    metadata_revision_id BIGINT      NOT NULL,
    pricing_revision_id  BIGINT      NOT NULL,
    rating_revision_id   BIGINT      NOT NULL,
    previous_release_id  BIGINT      NULL,
    status               VARCHAR(30) NOT NULL,
    publish_at           DATETIME(6) NULL,
    published_at         DATETIME(6) NULL,
    entity_version       BIGINT      NOT NULL DEFAULT 0,
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_release_workspace (workspace_id, id),
    KEY idx_release_due (status, publish_at, id),
    KEY idx_release_game_published (game_id, status, published_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
