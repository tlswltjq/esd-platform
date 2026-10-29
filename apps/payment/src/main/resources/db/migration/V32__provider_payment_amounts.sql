-- Unknown historical supplier breakdowns remain NULL; they are not inferred from the order or PG fee.
ALTER TABLE purchase_payment
    ADD COLUMN pg_paid_amount BIGINT NULL,
    ADD COLUMN pg_discount_amount BIGINT NULL,
    ADD CONSTRAINT ck_payment_provider_amounts CHECK (
        (pg_paid_amount IS NULL AND pg_discount_amount IS NULL)
        OR (status = 'PAID' AND pg_paid_amount IS NOT NULL AND pg_discount_amount IS NOT NULL
            AND pg_paid_amount >= 0 AND pg_discount_amount >= 0 AND pg_paid_amount <= amount
            AND pg_discount_amount = amount - pg_paid_amount)
    );
