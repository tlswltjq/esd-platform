-- 결정 89: 같은 상품의 기존 주문이 구매를 막지 않는다. 막는 것은 판매 금지뿐이며
-- 중복 청구는 (buyer_subject, request_id) 멱등이, 보유 중복은 License 등록이 막는다.
ALTER TABLE purchase_order
    DROP INDEX uk_purchase_order_buyer_product,
    DROP INDEX uk_purchase_order_buyer_code,
    DROP COLUMN purchase_guard;
