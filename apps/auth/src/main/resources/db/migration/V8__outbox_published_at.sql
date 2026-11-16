-- 결정 204 측정(브로커 구간 지연): 릴레이가 브로커로 보내기 직전 시각. sent_at(ack 뒤)과 달리 소비보다 늦을 수 없다.
-- 기존 행은 NULL로 둔다(측정 전 행). 업무 판단에 쓰지 않는다.
ALTER TABLE outbox_event ADD COLUMN published_at DATETIME(6) NULL AFTER sent_at;
