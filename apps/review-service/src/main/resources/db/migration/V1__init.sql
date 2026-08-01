CREATE TABLE review_request
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    game_id            BIGINT       NOT NULL,
    product_code       VARCHAR(50)  NOT NULL,
    title              VARCHAR(200) NOT NULL,
    seller_id          BIGINT       NOT NULL,
    price              BIGINT       NOT NULL,
    currency           VARCHAR(3)   NOT NULL DEFAULT 'KRW',
    self_rated         TINYINT(1)   NOT NULL DEFAULT 0,
    status             VARCHAR(20)  NOT NULL,
    board_ticket_id    VARCHAR(50)  NULL,
    rating_code        VARCHAR(10)  NULL,
    reject_reason_code VARCHAR(30)  NULL,
    reject_reason      VARCHAR(200) NULL,
    closed_at          DATETIME(6)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 상품코드당 심의 이력 1건(재신청은 같은 레코드를 REQUESTED 로 되돌린다)
    UNIQUE KEY uk_review_product_code (product_code),
    KEY idx_review_status (status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE outbox_event
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    event_id       VARCHAR(64)  NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    partition_key  VARCHAR(100) NOT NULL,
    payload        JSON         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    retry_count    INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(500) NULL,
    created_at     DATETIME(6)  NOT NULL,
    sent_at        DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_status (status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE processed_event
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    event_id       VARCHAR(64)  NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    processed_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_processed_event (event_id, consumer_group)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
