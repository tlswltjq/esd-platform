CREATE TABLE settlement_pg_transaction (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  payment_id VARCHAR(64) NOT NULL,
  order_no VARCHAR(36) NOT NULL,
  amount BIGINT NOT NULL,
  currency VARCHAR(3) NOT NULL,
  provider VARCHAR(30) NOT NULL,
  provider_transaction_id VARCHAR(100) NOT NULL,
  provider_occurred_at DATETIME(6) NOT NULL,
  received_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_pg_transaction_payment (payment_id),
  UNIQUE KEY uk_pg_transaction_order (order_no),
  UNIQUE KEY uk_pg_transaction_identity (provider,provider_transaction_id),
  UNIQUE KEY uk_pg_transaction_link (payment_id,provider,provider_transaction_id),
  CONSTRAINT ck_pg_transaction_money CHECK (amount>0 AND currency='KRW')
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE settlement_pg_fee (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  payment_id VARCHAR(64) NOT NULL,
  provider VARCHAR(30) NOT NULL,
  provider_transaction_id VARCHAR(100) NOT NULL,
  provider_occurred_at DATETIME(6) NOT NULL,
  pg_fee_policy_id VARCHAR(100) NOT NULL,
  pg_fee_rate_bps INT NOT NULL,
  pg_fee_amount BIGINT NOT NULL,
  received_at DATETIME(6) NOT NULL,
  recorded_by VARCHAR(100) NOT NULL,
  UNIQUE KEY uk_pg_fee_payment (payment_id),
  UNIQUE KEY uk_pg_fee_transaction (provider,provider_transaction_id),
  CONSTRAINT fk_pg_fee_transaction FOREIGN KEY (payment_id,provider,provider_transaction_id)
    REFERENCES settlement_pg_transaction (payment_id,provider,provider_transaction_id),
  CONSTRAINT ck_pg_fee_money CHECK (pg_fee_amount>=0 AND pg_fee_rate_bps BETWEEN 0 AND 10000)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

INSERT INTO settlement_pg_transaction(payment_id,order_no,amount,currency,provider,provider_transaction_id,provider_occurred_at,received_at)
SELECT payment_id,order_no,amount,currency,provider,provider_transaction_id,provider_occurred_at,received_at FROM settlement_sale;
INSERT INTO settlement_pg_fee(payment_id,provider,provider_transaction_id,provider_occurred_at,pg_fee_policy_id,pg_fee_rate_bps,pg_fee_amount,received_at,recorded_by)
SELECT payment_id,provider,provider_transaction_id,provider_occurred_at,pg_fee_policy_id,pg_fee_rate_bps,pg_fee_amount,received_at,'MIGRATED_V8' FROM settlement_sale;

-- 기존 비용/월 스냅샷은 이행 근거로 보존한다. 신규 미확정 비용을 0원으로 채우지 않는다.
ALTER TABLE settlement_sale
  DROP CHECK ck_sale_money,
  CHANGE provider_occurred_at legacy_provider_occurred_at DATETIME(6) NULL,
  CHANGE pg_fee_policy_id legacy_pg_fee_policy_id VARCHAR(100) NULL,
  CHANGE pg_fee_rate_bps legacy_pg_fee_rate_bps INT NULL,
  CHANGE pg_fee_amount legacy_pg_fee_amount BIGINT NULL,
  CHANGE platform_net_amount legacy_platform_net_amount BIGINT NULL,
  ADD CONSTRAINT ck_sale_allocation CHECK (amount>0 AND currency='KRW' AND platform_fee_rate_bps BETWEEN 0 AND 10000
    AND platform_fee_amount>=0 AND seller_payout_amount>=0 AND amount=platform_fee_amount+seller_payout_amount),
  ADD CONSTRAINT fk_sale_pg_transaction FOREIGN KEY (payment_id,provider,provider_transaction_id)
    REFERENCES settlement_pg_transaction (payment_id,provider,provider_transaction_id);

ALTER TABLE seller_monthly_settlement
  DROP CHECK ck_month_money,
  CHANGE pg_fee_amount legacy_pg_fee_amount BIGINT NULL,
  CHANGE platform_net_amount legacy_platform_net_amount BIGINT NULL,
  ADD CONSTRAINT ck_month_allocation CHECK (paid_amount>=0 AND platform_fee_amount>=0 AND seller_payout_amount>=0
    AND record_count>=0 AND paid_amount=platform_fee_amount+seller_payout_amount);
