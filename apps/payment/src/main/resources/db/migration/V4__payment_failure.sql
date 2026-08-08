-- PG 승인 거절로 종료된 결제의 흔적.
--
-- 지금까지 승인 실패는 아무 상태도 남기지 않았다 — Payment.fail() 은 호출자가 없었고
-- PaymentFailed 이벤트 타입도 없어서, 거절된 주문이 CREATED 에 영구히 머물렀다.
--
-- 사유를 코드와 문구로 나눠 둔다. 합쳐 두면 "한도초과가 몇 건인지" 같은 집계가
-- 문자열 파싱이 되고, 그게 PG 연동 품질을 보는 유일한 창이다.
ALTER TABLE payment
    ADD COLUMN failed_at        DATETIME(6)  NULL AFTER cancel_reason,
    ADD COLUMN fail_reason_code VARCHAR(50)  NULL AFTER failed_at,
    ADD COLUMN fail_reason      VARCHAR(200) NULL AFTER fail_reason_code;

-- 거절 사유별 집계용. 실패 건만 보므로 카디널리티가 낮다.
ALTER TABLE payment ADD INDEX idx_payment_fail_reason (fail_reason_code, id);
