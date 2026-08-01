CREATE TABLE product
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    product_code VARCHAR(50)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    seller_id    BIGINT       NOT NULL,
    price        BIGINT       NOT NULL,
    currency     VARCHAR(3)   NOT NULL DEFAULT 'KRW',
    status       VARCHAR(20)  NOT NULL,
    rating_code  VARCHAR(10)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_code (product_code),
    KEY idx_product_status (status, id),
    KEY idx_product_seller (seller_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Transactional Outbox
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

-- Consumer Inbox (멱등)
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
