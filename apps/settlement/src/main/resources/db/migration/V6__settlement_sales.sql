CREATE TABLE settlement_sale (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  order_no VARCHAR(36) NOT NULL,
  request_event_id VARCHAR(64) NOT NULL,
  payment_result_event_id VARCHAR(64) NOT NULL,
  payment_id VARCHAR(64) NOT NULL,
  buyer_subject VARCHAR(100) NOT NULL,
  product_id BIGINT NOT NULL,
  product_code VARCHAR(50) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content_version BIGINT NOT NULL,
  amount BIGINT NOT NULL,
  currency VARCHAR(3) NOT NULL,
  provider VARCHAR(30) NOT NULL,
  provider_transaction_id VARCHAR(100) NOT NULL,
  provider_occurred_at DATETIME(6) NOT NULL,
  pg_fee_policy_id VARCHAR(100) NOT NULL,
  pg_fee_rate_bps INT NOT NULL,
  pg_fee_amount BIGINT NOT NULL,
  seller_subject VARCHAR(100) NOT NULL,
  platform_policy_id VARCHAR(100) NOT NULL,
  platform_fee_rate_bps INT NOT NULL,
  platform_fee_amount BIGINT NOT NULL,
  seller_payout_amount BIGINT NOT NULL,
  platform_net_amount BIGINT NOT NULL,
  sale_month VARCHAR(7) NOT NULL,
  settlement_month VARCHAR(7) NOT NULL,
  received_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_sale_order_no (order_no),
  UNIQUE KEY uk_sale_event_id (event_id),
  UNIQUE KEY uk_sale_request_event_id (request_event_id),
  UNIQUE KEY uk_sale_payment_result_event_id (payment_result_event_id),
  UNIQUE KEY uk_sale_payment_id (payment_id),
  UNIQUE KEY uk_sale_pg_transaction (provider,provider_transaction_id),
  KEY idx_sale_seller_month (seller_subject,settlement_month,occurred_at,id),
  CONSTRAINT ck_sale_money CHECK (amount>0 AND currency='KRW' AND platform_fee_rate_bps BETWEEN 0 AND 10000
    AND pg_fee_rate_bps BETWEEN 0 AND 10000 AND pg_fee_amount BETWEEN 0 AND amount
    AND platform_fee_amount>=0 AND seller_payout_amount>=0
    AND amount=platform_fee_amount+seller_payout_amount AND platform_fee_amount=pg_fee_amount+platform_net_amount)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE seller_monthly_settlement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  seller_subject VARCHAR(100) NOT NULL,
  settlement_month VARCHAR(7) NOT NULL,
  status VARCHAR(10) NOT NULL,
  paid_amount BIGINT NOT NULL,
  platform_fee_amount BIGINT NOT NULL,
  seller_payout_amount BIGINT NOT NULL,
  pg_fee_amount BIGINT NOT NULL,
  platform_net_amount BIGINT NOT NULL,
  record_count BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  closed_at DATETIME(6),
  UNIQUE KEY uk_seller_month (seller_subject,settlement_month),
  KEY idx_seller_month (settlement_month,id),
  CONSTRAINT ck_month_money CHECK (paid_amount>=0 AND platform_fee_amount>=0 AND seller_payout_amount>=0 AND pg_fee_amount>=0
    AND record_count>=0 AND paid_amount=platform_fee_amount+seller_payout_amount AND platform_fee_amount=pg_fee_amount+platform_net_amount),
  CONSTRAINT ck_month_closed CHECK ((status='OPEN' AND closed_at IS NULL) OR (status='CLOSED' AND closed_at IS NOT NULL))
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

ALTER TABLE processed_event
  MODIFY event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  MODIFY consumer_group VARCHAR(100) COLLATE utf8mb4_bin NOT NULL;
