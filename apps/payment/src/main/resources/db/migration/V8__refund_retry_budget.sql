-- 중단된 취소(CANCELING) 재개에 시간 예산과 백오프를 준다.
--
-- 이전 동작은 "1분마다 조건 없이 계속 재개" 였다. PG 가 오래 죽어 있으면 같은 건을 영원히
-- 같은 간격으로 다시 부르고, **복구 중인 PG 를 계속 두드린다.** 멈추는 것은 알람뿐인데
-- 알람은 사람을 부를 뿐 재시도를 멈추지 않는다.
--
-- D-003 이 Outbox 에서 같은 문제를 다뤘고 거기서는 재시도 예산을 **횟수가 아니라 시간으로**
-- 잡았다(next_attempt_at). 같은 형태를 쓴다 — 횟수는 시간을 말해 주지 않기 때문이다.
--
-- **다만 DEAD 에 해당하는 포기 상태는 만들지 않는다.** Outbox 이벤트는 포기해도 재발행으로
-- 되살릴 수 있지만, CANCELING 은 "돈이 나갔는지 불확실" 이라는 뜻이라 포기할 대상이 아니다.
-- 그 행을 종단 상태로 옮기는 순간 **불확실이 해소된 것처럼 보이고 아무도 다시 보지 않는다.**
-- 그래서 여기서 시간 예산은 "포기하는 시점" 이 아니라 **"사람을 불러야 하는 시점"** 이다.
ALTER TABLE payment
    -- 몇 번 시도했는가. 지금까지 이 값은 로그에만 있었고, 로그를 세지 않으면 알 수 없었다.
    -- stove.payment.refund-resumed 는 **성공만** 세므로 실패 횟수를 말해 주지 못한다 --
    -- 그리고 그 값이 곧 PG 연동이 정상인지 보는 창이다.
    ADD COLUMN cancel_attempts INT NOT NULL DEFAULT 0,
    -- 다음 재개를 시도해도 되는 시각. NULL 이면 아직 예약된 적이 없다는 뜻이라 즉시 대상이다.
    ADD COLUMN next_cancel_attempt_at TIMESTAMP(3) NULL,
    -- CANCELING 에 들어간 시각. 예산 초과 판정의 기준이며 updated_at 으로 대신할 수 없다 --
    -- updated_at 은 재시도마다 갱신되므로 "얼마나 오래 불확실했는가" 를 잃는다.
    ADD COLUMN canceling_since TIMESTAMP(3) NULL;

-- 스윕이 타는 경로. (status, next_cancel_attempt_at) 로 "지금 집어야 할 것" 이 바로 나온다.
-- 예산 초과 게이지는 (status, canceling_since) 를 타므로 두 번째 인덱스가 따로 필요하다.
CREATE INDEX idx_payment_cancel_retry ON payment (status, next_cancel_attempt_at);
CREATE INDEX idx_payment_canceling_since ON payment (status, canceling_since);

-- 이미 CANCELING 인 행이 있으면 기준 시각이 없다. updated_at 이 가장 가까운 대리값이다
-- (마지막으로 그 행이 움직인 시각). 없는 것보다 낫고, 새 행부터는 정확해진다.
UPDATE payment
SET canceling_since = updated_at
WHERE status = 'CANCELING'
  AND canceling_since IS NULL;
