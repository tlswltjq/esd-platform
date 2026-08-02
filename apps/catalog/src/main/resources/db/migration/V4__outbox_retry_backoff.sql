-- D-003: Outbox 재시도에 지수 백오프를 넣는다.
--
-- 지금까지는 폴링 주기(기본 1초)로 고정 재시도했다. 간격이 늘지 않으니
-- 장애 감내 시간이 max-retry x poll-interval 로 못박혔고(기본 설정에서 약 10초),
-- 브로커 롤링 재시작이나 리더 선출보다 짧았다. 그 순간 대기 중이던 이벤트가
-- 한꺼번에 DEAD 가 되고, DEAD 를 되돌리는 경로는 없었다 -- 곧 영구 유실이다.
--
-- next_attempt_at 이 지나야 다시 집어가므로 재시도 간격이 1초 -> 2초 -> 4초 ... 로 늘어난다.
-- 기본 설정(max-retry 10)에서 감내 시간이 약 8분으로 늘어난다.
ALTER TABLE outbox_event ADD COLUMN next_attempt_at DATETIME(6) NULL AFTER retry_count;

-- 릴레이 조회 조건이 (status, next_attempt_at, id) 이므로 인덱스도 맞춘다.
ALTER TABLE outbox_event DROP INDEX idx_outbox_status;
ALTER TABLE outbox_event ADD INDEX idx_outbox_pending (status, next_attempt_at, id);
