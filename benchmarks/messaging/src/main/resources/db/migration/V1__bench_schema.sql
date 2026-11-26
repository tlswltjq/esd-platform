-- 층 2 시험 앱 스키마(E13·결정 234). Outbox·Inbox·격리 표는 운영 앱 마이그레이션의 최종 형태와 같다
-- (common/messaging/src/testFixtures/resources/db/migration/V1__outbox.sql·V2__consumer_quarantine.sql 와 같은 모양).
-- Outbox 표는 쓰지 않지만 common:messaging 엔티티 검증(ddl-auto: validate)이 요구한다.
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
    published_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_status (status, id),
    KEY idx_outbox_pending (status, next_attempt_at, id),
    KEY idx_outbox_key_order (partition_key, id)
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

CREATE TABLE consumer_quarantine
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    consumer_group VARCHAR(100)  NOT NULL,
    topic          VARCHAR(100)  NOT NULL,
    partition_key  VARCHAR(100)  NOT NULL,
    message_key    TEXT          NULL,
    event_id       VARCHAR(64)   NULL,
    event_type     VARCHAR(60)   NULL,
    payload        MEDIUMTEXT    NULL,
    headers        MEDIUMTEXT    NOT NULL,
    origin         VARCHAR(200)  NOT NULL,
    failure        VARCHAR(1000) NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    quarantined_at DATETIME(6)   NOT NULL,
    claimed_at     DATETIME(6)   NULL,
    replayed_at    DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_consumer_quarantine_key (consumer_group, topic, partition_key, status, id),
    KEY idx_consumer_quarantine_status (status, consumer_group)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 시험 표: 메시지 하나의 반영 = 한 행. event_id 에 유일 제약을 두지 않는다 — 중복 반영을 막는 것은 Inbox 이고
-- 이 표는 그것을 세는 자리다(E13 정합성 "중복 반영").
CREATE TABLE bench_message
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        VARCHAR(64)  NOT NULL,
    message_key     VARCHAR(100) NOT NULL,
    sent_at_us      BIGINT       NOT NULL,
    attempt         INT          NOT NULL,
    payload_bytes   INT          NOT NULL,
    completed_at_us BIGINT       NOT NULL,
    PRIMARY KEY (id),
    KEY idx_bench_message_event (event_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
