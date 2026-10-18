ALTER TABLE purchase_payment
  ADD pg_fee_policy_id VARCHAR(100) COLLATE utf8mb4_bin NULL,
  ADD pg_fee_rate_bps INT NULL,
  ADD pg_fee_amount BIGINT NULL,
  ADD CONSTRAINT ck_payment_pg_fee CHECK (
    (pg_fee_policy_id IS NULL AND pg_fee_rate_bps IS NULL AND pg_fee_amount IS NULL) OR
    (pg_fee_policy_id IS NOT NULL AND pg_fee_rate_bps IS NOT NULL AND pg_fee_amount IS NOT NULL
      AND status IN ('PAID','FAILED') AND pg_fee_rate_bps BETWEEN 0 AND 10000 AND pg_fee_amount BETWEEN 0 AND amount
      AND (status <> 'FAILED' OR pg_fee_amount=0)));
