CREATE TABLE license
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(40)  NOT NULL,
    member_id     BIGINT       NOT NULL,
    product_id    BIGINT       NOT NULL,
    license_key   VARCHAR(40)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    issued_at     DATETIME(6)  NOT NULL,
    revoked_at    DATETIME(6)  NULL,
    revoke_reason VARCHAR(200) NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 멱등성의 핵심: 한 주문의 한 상품은 단 한 번만 지급된다
    UNIQUE KEY uk_license_order_product (order_no, product_id),
    UNIQUE KEY uk_license_key (license_key),
    KEY idx_license_member (member_id, status)
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
