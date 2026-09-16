CREATE TABLE submission_snapshot
(
    submission_id         BIGINT        NOT NULL,
    game_id               BIGINT        NOT NULL,
    product_code          VARCHAR(50)   NOT NULL,
    seller_id             BIGINT        NOT NULL,
    metadata_revision     BIGINT        NOT NULL,
    pricing_revision      BIGINT        NOT NULL,
    rating_revision       BIGINT        NOT NULL,
    build_id              BIGINT        NOT NULL,
    title                 VARCHAR(200)  NOT NULL,
    short_description     VARCHAR(500)  NOT NULL,
    price                 BIGINT        NOT NULL,
    currency              VARCHAR(3)    NOT NULL,
    rating_path           VARCHAR(30)   NOT NULL,
    rating_policy_version VARCHAR(30)   NOT NULL,
    product_version       VARCHAR(30)   NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,
    PRIMARY KEY (submission_id),
    KEY idx_submission_snapshot_product (product_code, submission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE review_case
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    submission_id        BIGINT        NOT NULL,
    review_type          VARCHAR(30)   NOT NULL,
    status               VARCHAR(30)   NOT NULL,
    decided_by           VARCHAR(100)  NULL,
    reason_code          VARCHAR(30)   NULL,
    external_feedback    VARCHAR(1000) NULL,
    rating_code          VARCHAR(10)   NULL,
    certification_number VARCHAR(100)  NULL,
    issuer               VARCHAR(100)  NULL,
    issued_at            DATETIME(6)   NULL,
    country              VARCHAR(2)    NULL,
    decided_at           DATETIME(6)   NULL,
    entity_version       BIGINT        NOT NULL DEFAULT 0,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_case_submission_type (submission_id, review_type),
    KEY idx_review_case_status (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
