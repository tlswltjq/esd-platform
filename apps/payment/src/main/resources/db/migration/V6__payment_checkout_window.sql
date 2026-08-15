-- 결제창을 연 시각.
--
-- 지금까지 결제 대기 행에는 "언제 만들어졌나"(created_at)만 있었다. 그래서 만료를 걸 수 있는
-- 시계가 하나뿐이었고, 그 하나로 두 가지 다른 것을 재야 했다 —
-- **주문이 얼마나 오래됐나**(가격이 굳은 시점)와 **결제창을 연 지 얼마나 됐나**다.
--
-- 하나로 재면 이런 일이 생긴다: 주문 창이 30분인데 사용자가 29분째에 결제창을 열면
-- 카드번호를 넣을 시간이 1분뿐이다. 창을 넓히면 이번에는 옛 가격이 오래 유효해진다.
-- **두 시계는 서로 다른 것을 지키므로 나눠야 한다.**
ALTER TABLE payment
    ADD COLUMN prepared_at DATETIME(6) NULL AFTER method;

-- 기존 행 보정. PENDING 인데 prepared_at 이 비어 있으면 만료 판정이 성립하지 않는다
-- (판단 근거가 없는데 '만료됨'이라고 답하면 정상 결제가 자동 환불된다).
-- 마이그레이션 시점에 열려 있던 결제창은 updated_at 이 곧 사전등록 시각이다 —
-- 그 사이에 그 행을 건드리는 다른 경로가 없다.
UPDATE payment SET prepared_at = updated_at WHERE status = 'PENDING' AND prepared_at IS NULL;
