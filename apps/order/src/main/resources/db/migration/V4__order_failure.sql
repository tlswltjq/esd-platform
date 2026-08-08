-- 결제 실패로 종료된 주문의 흔적.
--
-- OrderStatus.FAILED 는 선언만 있고 도달 경로가 없었다(payment 가 PaymentFailed 를
-- 발행하지 않았으므로). 실패 사유는 취소 사유와 성격이 달라 컬럼을 따로 둔다 —
-- 취소는 승인된 돈을 되돌린 것이고, 실패는 돈이 움직인 적이 없다.
ALTER TABLE orders
    ADD COLUMN failed_at   DATETIME(6)  NULL AFTER cancel_reason,
    ADD COLUMN fail_reason VARCHAR(200) NULL AFTER failed_at;
