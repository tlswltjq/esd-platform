-- 결정 129: Kafka 에서 원본이 사라진 SENT Outbox 이벤트를 같은 ID 로 재발행한 운영 기록.
-- 새 표만 더하고 outbox_event 의 옛 행은 바꾸지 않는다(결정 104 제외 대상).
-- 재발행마다 한 행이다. 같은 이벤트를 여러 번 재발행했다면 행이 여러 개다.
CREATE TABLE outbox_republish
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    event_id         VARCHAR(64)  NOT NULL,
    event_type       VARCHAR(60)  NOT NULL,
    previous_sent_at DATETIME(6)  NULL,
    republished_by   VARCHAR(100) NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    republished_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_republish_event (event_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
