-- D-008: 멱등키의 전역 유니크 제약을 걷어낸다.
--
-- 멱등키는 PG 가 만들어 주는 값이라 우리 쪽에서 유일성을 보장할 수 없다.
-- 전역 유니크를 걸고 그 키로 결제를 먼저 조회하면, PG 가 키를 재사용했을 때
-- 다른 주문의 결제가 매칭되어 엉뚱한 건이 '중복 콜백'으로 조용히 무시된다.
--
-- 주문번호가 이미 유니크라 결제는 주문당 1건이다. 따라서 멱등키의 역할은
-- '이 결제에 이미 적용된 콜백인가' 하나로 충분하며, 그 판정은 엔티티가 한다.
-- 동시 중복 콜백은 SELECT ... FOR UPDATE 로 막는다.
ALTER TABLE payment DROP INDEX uk_payment_idempotency;

-- 운영 조회(멱등키로 결제 역추적)를 위해 인덱스는 남긴다.
ALTER TABLE payment ADD INDEX idx_payment_idempotency (idempotency_key);
