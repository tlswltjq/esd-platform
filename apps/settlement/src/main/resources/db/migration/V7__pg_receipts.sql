CREATE TABLE pg_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider VARCHAR(30) NOT NULL,
  provider_settlement_id VARCHAR(64) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  cutoff_at DATETIME(6) NOT NULL,
  paid_at DATETIME(6) NOT NULL,
  provider_status VARCHAR(30) NOT NULL,
  payment_amount_total BIGINT NOT NULL,
  pg_fee_amount_total BIGINT NOT NULL,
  payout_amount BIGINT NOT NULL,
  record_count BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL,
  reported_by VARCHAR(100) NOT NULL,
  reported_at DATETIME(6) NOT NULL,
  last_checked_at DATETIME(6),
  simulated_received_at DATETIME(6),
  UNIQUE KEY uk_pg_receipt (provider,provider_settlement_id),
  KEY idx_pg_receipt_reported (reported_at,id),
  CONSTRAINT ck_receipt_money CHECK (record_count>0 AND pg_fee_amount_total>=0 AND payout_amount>=0
    AND payment_amount_total=pg_fee_amount_total+payout_amount AND currency='KRW' AND provider_status='SIMULATED_PAID'),
  CONSTRAINT ck_receipt_state CHECK ((status IN ('REPORTED_PAID','MISMATCH') AND simulated_received_at IS NULL)
    OR (status='SIMULATED_RECEIVED' AND simulated_received_at IS NOT NULL))
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE pg_receipt_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  receipt_id BIGINT NOT NULL,
  item_position INT NOT NULL,
  payment_id VARCHAR(64) NOT NULL,
  order_no VARCHAR(36) NOT NULL,
  amount BIGINT NOT NULL,
  currency VARCHAR(3) NOT NULL,
  provider VARCHAR(30) NOT NULL,
  provider_transaction_id VARCHAR(100) NOT NULL,
  provider_occurred_at DATETIME(6) NOT NULL,
  pg_fee_policy_id VARCHAR(100) NOT NULL,
  pg_fee_rate_bps INT NOT NULL,
  pg_fee_amount BIGINT NOT NULL,
  mismatch_code VARCHAR(100),
  mismatch_reason VARCHAR(500),
  FOREIGN KEY (receipt_id) REFERENCES pg_receipt(id),
  UNIQUE KEY uk_pg_receipt_payment (provider,payment_id),
  UNIQUE KEY uk_pg_receipt_transaction (provider,provider_transaction_id),
  UNIQUE KEY uk_pg_receipt_position (receipt_id,item_position),
  CONSTRAINT ck_receipt_item_money CHECK (amount>0 AND currency='KRW' AND pg_fee_amount BETWEEN 0 AND amount AND pg_fee_rate_bps BETWEEN 0 AND 10000)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
