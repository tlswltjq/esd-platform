CREATE TABLE settlement_record
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    order_no          VARCHAR(40)  NOT NULL,
    product_id        BIGINT       NOT NULL,
    seller_id         BIGINT       NOT NULL,
    sale_type         VARCHAR(10)  NOT NULL,
    record_type       VARCHAR(10)  NOT NULL,
    gross_amount      BIGINT       NOT NULL,
    fee_rate          DECIMAL(5, 4) NOT NULL,
    fee_amount        BIGINT       NOT NULL,
    net_amount        BIGINT       NOT NULL,
    settlement_month  VARCHAR(7)   NOT NULL,
    closed            TINYINT(1)   NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 결제/환불 이벤트 재전송 시 매출 중복 집계 차단
    UNIQUE KEY uk_settlement_record (order_no, product_id, record_type),
    KEY idx_settlement_seller_month (seller_id, settlement_month),
    KEY idx_settlement_month_closed (settlement_month, closed)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE seller_settlement
(
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    seller_id        BIGINT      NOT NULL,
    settlement_month VARCHAR(7)  NOT NULL,
    gross_amount     BIGINT      NOT NULL,
    fee_amount       BIGINT      NOT NULL,
    net_amount       BIGINT      NOT NULL,
    record_count     INT         NOT NULL,
    tax_invoice_no   VARCHAR(30) NULL,
    closed_at        DATETIME(6) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seller_settlement (seller_id, settlement_month)
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

-- settlement 은 이벤트를 발행하지 않지만, 공통 Outbox 자동 구성이 참조하므로 테이블은 유지한다
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
