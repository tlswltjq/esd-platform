-- 브로커 없음 기준선(결정 203). stove.messaging.broker=direct 일 때 다른 모듈의 소비자가 이 표를 (topic, SENT, sent_at, id) 순으로 읽는다.
-- 다른 adapter 에서는 쓰지 않지만, 기준선이 느린 조회로 불리해지지 않게 색인을 같이 둔다.
CREATE INDEX idx_outbox_direct_read ON outbox_event (topic, status, sent_at, id);
