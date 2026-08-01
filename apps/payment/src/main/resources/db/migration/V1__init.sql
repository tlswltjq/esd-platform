CREATE TABLE payment
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_no        VARCHAR(40)  NOT NULL,
    member_id       BIGINT       NOT NULL,
    amount          BIGINT       NOT NULL,
    currency        VARCHAR(3)   NOT NULL DEFAULT 'KRW',
    status          VARCHAR(20)  NOT NULL,
    method          VARCHAR(30)  NULL,
    pg_tx_id        VARCHAR(100) NULL,
    idempotency_key VARCHAR(100) NULL,
    lines_json      JSON         NULL,
    paid_at         DATETIME(6)  NULL,
    canceled_at     DATETIME(6)  NULL,
    cancel_reason   VARCHAR(200) NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 주문당 결제 1건
    UNIQUE KEY uk_payment_order_no (order_no),
    -- 중복 콜백 승인 차단(마지막 방어선)
    UNIQUE KEY uk_payment_idempotency (idempotency_key),
    KEY idx_payment_member (member_id, id),
    KEY idx_payment_status (status, id)
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
