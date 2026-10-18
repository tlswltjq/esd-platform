-- PENDING and PAID share one purchase slot. Definitive FAILED orders keep their
-- history without blocking a new request. Existing duplicate active purchases
-- deliberately fail this migration rather than deleting or rewriting orders.
ALTER TABLE purchase_order
    ADD COLUMN purchase_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'PAID') THEN 1 ELSE NULL END
    ) STORED,
    ADD UNIQUE KEY uk_purchase_order_buyer_product (buyer_subject, product_id, purchase_guard),
    ADD UNIQUE KEY uk_purchase_order_buyer_code (buyer_subject, product_code, purchase_guard);
