-- 결제를 시작조차 하지 않은 주문에 만료를 준다.
--
-- OrderStatus 에는 CREATED · PAID · CANCELED · FAILED 만 있었고, CREATED 이후 상태는 전부
-- **결제 결과 이벤트**로만 바뀐다. 그래서 결제를 아예 시작하지 않은 주문은 아무 이벤트도 낳지 않아
-- 영원히 CREATED 로 남았다 -- 실측으로 전체 주문의 96%(98,750 / 102,950)가 그 상태다.
--
-- 정합성 문제는 아니다. 금전 경로는 이미 닫혀 있다(D-029 가 사전등록에 창을 걸었다).
-- 문제는 **쌓이는 것 자체**이고, 그중에서도 "미결제 주문 수" 가 지표로 못 쓰게 되는 것이다 --
-- 만료된 것과 진짜 결제를 기다리는 것이 한 상태에 섞여 있기 때문이다.
--
-- **EXPIRED 를 CANCELED 로 합치지 않는다.** CANCELED 는 누군가 되돌린 것이고 되돌릴 돈이 있었다.
-- EXPIRED 는 아무 일도 일어나지 않은 채 시간이 지난 것이다. 합치면 이번에는
-- "취소된 주문 수" 가 장바구니 방치까지 세게 되어, 못 쓰는 지표를 하나에서 다른 하나로 옮길 뿐이다.
ALTER TABLE orders
    ADD COLUMN expired_at TIMESTAMP(3) NULL;

-- 스윕이 타는 경로. (status, created_at) 이면 "CREATED 중 오래된 것" 이 인덱스만으로 나온다.
--
-- 기존 인덱스로는 안 된다 -- idx_orders_member 는 (member_id, id) 라 상태를 못 거르고,
-- 상태만 있는 인덱스는 CREATED 가 96% 라 선택도가 없어 풀스캔과 다르지 않다.
CREATE INDEX idx_orders_status_created ON orders (status, created_at);
