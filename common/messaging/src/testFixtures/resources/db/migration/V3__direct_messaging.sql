-- 브로커 없음 기준선(결정 203). stove.messaging.broker=direct 일 때 다른 모듈의 소비자가 이 표를 (topic, SENT, sent_at, id) 순으로 읽는다.
-- 다른 adapter 에서는 쓰지 않지만, 기준선이 느린 조회로 불리해지지 않게 색인을 같이 둔다.
CREATE INDEX idx_outbox_direct_read ON outbox_event (topic, status, sent_at, id);

-- 이 모듈의 소비 그룹이 토픽마다 어디까지 읽었는지(Kafka 의 소비 오프셋에 해당).
CREATE TABLE direct_cursor
(
    consumer_group VARCHAR(100) NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    sent_at        DATETIME(6)  NOT NULL,
    row_id         BIGINT       NOT NULL,
    -- 같은 그룹의 여러 인스턴스 가운데 한 곳만 읽는다(PR #210 R1). 점유 기한은 DB 시각으로 잰다.
    owner          VARCHAR(64)  NULL,
    lease_until    DATETIME(6)  NULL,
    PRIMARY KEY (consumer_group, topic)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
