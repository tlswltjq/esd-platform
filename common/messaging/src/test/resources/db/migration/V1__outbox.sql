-- Outbox 스키마. 앱 마이그레이션(V1 + outbox_retry_backoff + outbox_trace_context)의 최종 형태와 같다.
--
-- 이 파일이 여기 있는 이유는 lockPendingBatch 의 의미를 이 모듈에서 검증하기 위해서다.
-- 실제 앱은 각자의 db/migration 으로 같은 표를 만든다 — 스키마가 7벌 복제돼 있다는 뜻이고,
-- 그 복제 자체는 별건으로 다룰 문제다.
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
    KEY idx_outbox_status (status, id),
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
