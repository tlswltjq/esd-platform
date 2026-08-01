-- 정산(settlement) 합류: 판매자별 매출 배분을 위해 주문 시점 판매자를 스냅샷으로 보관
ALTER TABLE order_item
    ADD COLUMN seller_id BIGINT NOT NULL DEFAULT 0 AFTER product_name,
    ADD KEY idx_order_item_seller (seller_id);
