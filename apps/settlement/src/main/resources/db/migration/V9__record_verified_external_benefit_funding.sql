ALTER TABLE settlement_pg_transaction
  ADD funding_total_amount BIGINT NULL,
  ADD pg_paid_amount BIGINT NULL,
  ADD pg_discount_amount BIGINT NULL,
  ADD provider_store_id VARCHAR(100) NULL,
  ADD provider_channel_key VARCHAR(100) NULL,
  ADD provider_channel_type VARCHAR(30) NULL,
  ADD provider_pg_provider VARCHAR(30) NULL,
  ADD benefit_policy_id VARCHAR(100) NULL,
  ADD CONSTRAINT ck_pg_transaction_funding CHECK (
    (funding_total_amount IS NULL AND pg_paid_amount IS NULL AND pg_discount_amount IS NULL
      AND provider_store_id IS NULL AND provider_channel_key IS NULL AND provider_channel_type IS NULL
      AND provider_pg_provider IS NULL AND benefit_policy_id IS NULL)
    OR
    (funding_total_amount IS NOT NULL AND pg_paid_amount IS NOT NULL AND pg_discount_amount IS NOT NULL
      AND provider_store_id IS NOT NULL AND provider_channel_key IS NOT NULL AND provider_channel_type IS NOT NULL
      AND provider_pg_provider IS NOT NULL AND benefit_policy_id IS NOT NULL
      AND provider='PORTONE_TEST' AND provider_channel_type='TEST' AND provider_pg_provider='TOSSPAY_V2'
      AND funding_total_amount=amount AND pg_paid_amount BETWEEN 0 AND amount
      AND pg_discount_amount=amount-pg_paid_amount)
  );
