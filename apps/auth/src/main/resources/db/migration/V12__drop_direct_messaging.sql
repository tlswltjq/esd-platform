-- 결정 232: 브로커 없음 기준선(결정 203) 전용 스키마를 지운다. messaging-direct 는 결정 227로 지웠고 읽는 코드가 없다.
-- 색인은 Outbox INSERT 마다 갱신 비용을 더해 Kafka 기준선의 쓰기 경로에 섞이므로 함께 지운다. 옛 V*__direct_messaging.sql 은 이력이라 고치지 않는다.
ALTER TABLE outbox_event DROP INDEX idx_outbox_direct_read;
