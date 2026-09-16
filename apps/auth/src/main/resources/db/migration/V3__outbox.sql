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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE processed_event
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    event_id       VARCHAR(64)  NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    processed_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_processed_event (event_id, consumer_group)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
