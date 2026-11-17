-- 소비 실패 보관과 키 정지(결정 155·160·206). 앱 마이그레이션 V*__consumer_quarantine.sql 과 같은 모양이다.
-- 키 정지 = 같은 (consumer_group, topic, partition_key) 에 status 가 HELD·REPLAYING 인 행이 있음. 보관과 정지가 한 행이다.
-- partition_key 는 조회·정지용(100자 넘는 키는 sha256: 해시), message_key 는 원래 키(재투입용). claimed_at 은 재투입 점유 시각.
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
