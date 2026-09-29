ALTER TABLE order_item
    ADD COLUMN list_unit_price BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN discount_per_unit BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN promotion_id BIGINT NULL,
    ADD COLUMN discount_bearer VARCHAR(20) NULL;

UPDATE order_item SET list_unit_price = unit_price;
