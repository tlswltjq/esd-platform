-- Read copies only. Missing historical provider values are not inferred from order amounts.
ALTER TABLE purchase_order
    ADD COLUMN pg_paid_amount BIGINT NULL,
    ADD COLUMN pg_discount_amount BIGINT NULL,
    ADD CONSTRAINT ck_order_provider_amounts CHECK (
        (pg_paid_amount IS NULL AND pg_discount_amount IS NULL)
        OR (status = 'PAID' AND pg_paid_amount IS NOT NULL AND pg_discount_amount IS NOT NULL
            AND pg_paid_amount >= 0 AND pg_paid_amount <= amount AND pg_discount_amount = amount - pg_paid_amount));

ALTER TABLE order_refund
    ADD COLUMN refund_pg_paid_amount BIGINT NULL,
    ADD COLUMN refund_pg_discount_amount BIGINT NULL,
    ADD COLUMN confirmed_cancellation_amount BIGINT NULL,
    ADD COLUMN confirmed_point_refund_amount BIGINT NULL,
    ADD COLUMN refund_amounts_reported_at DATETIME(6) NULL,
    ADD COLUMN refund_provider_cancelled_at DATETIME(6) NULL,
    ADD COLUMN refund_amounts_source VARCHAR(30) NULL,
    ADD COLUMN refund_amounts_cancellation_ids_hash VARCHAR(64) NULL,
    ADD CONSTRAINT ck_refund_amount_details CHECK (
        (confirmed_cancellation_amount IS NULL AND refund_pg_paid_amount IS NULL AND refund_pg_discount_amount IS NULL
            AND confirmed_point_refund_amount IS NULL AND refund_amounts_reported_at IS NULL
            AND refund_provider_cancelled_at IS NULL AND refund_amounts_source IS NULL AND refund_amounts_cancellation_ids_hash IS NULL)
        OR (confirmed_cancellation_amount IS NOT NULL AND confirmed_cancellation_amount > 0
            AND status IN ('PENDING_PAYMENT_REFUND','COMPLETED')
            AND refund_amounts_reported_at IS NOT NULL AND refund_amounts_reported_at >= received_at
            AND refund_amounts_source IS NOT NULL AND refund_amounts_source IN ('PG_RESPONSE','PG_QUERY_UNATTRIBUTED')
            AND refund_amounts_cancellation_ids_hash IS NOT NULL
            AND (refund_provider_cancelled_at IS NULL OR refund_provider_cancelled_at <= refund_amounts_reported_at)
            AND ((refund_pg_paid_amount IS NULL AND refund_pg_discount_amount IS NULL)
                OR (refund_pg_paid_amount IS NOT NULL AND refund_pg_discount_amount IS NOT NULL
                    AND refund_pg_paid_amount >= 0 AND refund_pg_paid_amount <= confirmed_cancellation_amount
                    AND refund_pg_discount_amount = confirmed_cancellation_amount - refund_pg_paid_amount))
            AND (confirmed_point_refund_amount IS NULL
                OR (confirmed_point_refund_amount >= 0 AND confirmed_point_refund_amount <= confirmed_cancellation_amount
                    AND ((refund_pg_discount_amount IS NOT NULL AND confirmed_point_refund_amount <= refund_pg_discount_amount)
                        OR (refund_pg_discount_amount IS NULL AND confirmed_point_refund_amount = 0))))));
