ALTER TABLE settlement_record
    ADD COLUMN paid_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN list_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN discount_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN platform_expense BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN promotion_id BIGINT NULL,
    ADD COLUMN discount_bearer VARCHAR(20) NULL,
    ADD COLUMN adjustment_for_month VARCHAR(7) NULL;

UPDATE settlement_record SET paid_amount = gross_amount, list_amount = gross_amount;

ALTER TABLE seller_settlement
    ADD COLUMN base_gross_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN base_fee_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN base_net_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN adjustment_gross_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN adjustment_fee_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN adjustment_net_amount BIGINT NOT NULL DEFAULT 0;

UPDATE seller_settlement
SET base_gross_amount = gross_amount, base_fee_amount = fee_amount, base_net_amount = net_amount;
