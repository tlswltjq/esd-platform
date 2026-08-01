CREATE TABLE orders
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(40) NOT NULL,
    member_id     BIGINT      NOT NULL,
    status        VARCHAR(20) NOT NULL,
    total_amount  BIGINT      NOT NULL,
    currency      VARCHAR(3)  NOT NULL DEFAULT 'KRW',
    paid_at       DATETIME(6) NULL,
    canceled_at   DATETIME(6) NULL,
    cancel_reason VARCHAR(200) NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 주문번호는 결제·라이선스·정산의 멱등 키. 유니크 제약이 마지막 방어선이다.
    UNIQUE KEY uk_orders_order_no (order_no),
    KEY idx_orders_member (member_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE order_item
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     BIGINT       NOT NULL,
    product_id   BIGINT       NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    unit_price   BIGINT       NOT NULL,
    quantity     INT          NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_item_order (order_id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders (id)
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
