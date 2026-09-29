ALTER TABLE order_item
    ADD COLUMN settlement_basis BIGINT NULL,
    ADD COLUMN fee_rate DECIMAL(5, 4) NULL,
    ADD COLUMN fee_amount BIGINT NULL,
    ADD COLUMN seller_payout BIGINT NULL;
