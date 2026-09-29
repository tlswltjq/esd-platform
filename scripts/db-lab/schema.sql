-- db-lab 실험용 스키마. 운영 스키마를 새로 설계한 것이 아니라 Flyway 마이그레이션을 합친 최종 형태다.
--
--   orders          apps/order/.../db/migration   V1 + V4 + V6
--   outbox_event    apps/order/.../db/migration   V1 + V3 + V5  (7개 서비스가 같은 모양)
--   processed_event apps/order/.../db/migration   V1            (Inbox, 같은 모양)
--
-- 실험이 보려는 것은 인덱스와 제약이 만드는 락이므로, 그 둘은 마이그레이션과 한 글자도 다르지 않아야 한다.

DROP TABLE IF EXISTS outbox_event;
DROP TABLE IF EXISTS processed_event;
DROP TABLE IF EXISTS orders;

CREATE TABLE orders
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(40)  NOT NULL,
    member_id     BIGINT       NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    total_amount  BIGINT       NOT NULL,
    currency      VARCHAR(3)   NOT NULL DEFAULT 'KRW',
    paid_at       DATETIME(6)  NULL,
    canceled_at   DATETIME(6)  NULL,
    cancel_reason VARCHAR(200) NULL,
    failed_at     DATETIME(6)  NULL,
    fail_reason   VARCHAR(200) NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    expired_at    TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no),
    KEY idx_orders_member (member_id, id),
    KEY idx_orders_status_created (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE outbox_event
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        VARCHAR(64)  NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(60)  NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    partition_key   VARCHAR(100) NOT NULL,
    payload         JSON         NOT NULL,
    trace_parent    VARCHAR(64)  NULL,
    status          VARCHAR(20)  NOT NULL,
    retry_count     INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NULL,
    last_error      VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL,
    sent_at         DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_pending (status, next_attempt_at, id)
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
