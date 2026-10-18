CREATE TABLE pg_charge (
  payment_id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
  order_no VARCHAR(36) COLLATE utf8mb4_bin NOT NULL UNIQUE,
  amount BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  provider_transaction_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL UNIQUE,
  provider_occurred_at DATETIME(6) NOT NULL,
  decision VARCHAR(20) NOT NULL,
  reason_code VARCHAR(100),
  reason VARCHAR(500),
  pg_fee_policy_id VARCHAR(100),
  pg_fee_rate_bps INT,
  pg_fee_amount BIGINT,
  CONSTRAINT ck_pg_amount CHECK (amount > 0 AND currency = 'KRW'),
  CONSTRAINT ck_pg_decision CHECK ((decision='APPROVED' AND reason_code IS NULL AND reason IS NULL)
    OR (decision='DECLINED' AND reason_code IS NOT NULL AND reason IS NOT NULL)),
  CONSTRAINT ck_pg_fee CHECK (
    (pg_fee_policy_id IS NULL AND pg_fee_rate_bps IS NULL AND pg_fee_amount IS NULL) OR
    (pg_fee_policy_id IS NOT NULL AND pg_fee_rate_bps IS NOT NULL AND pg_fee_amount IS NOT NULL
      AND pg_fee_rate_bps BETWEEN 0 AND 10000 AND pg_fee_amount BETWEEN 0 AND amount
      AND (decision <> 'DECLINED' OR pg_fee_amount=0)))
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE pg_settlement (
  provider_settlement_id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
  request_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL UNIQUE,
  currency CHAR(3) NOT NULL,
  cutoff_at DATETIME(6) NOT NULL,
  paid_at DATETIME(6) NOT NULL,
  status VARCHAR(30) NOT NULL,
  payment_amount_total BIGINT NOT NULL,
  pg_fee_amount_total BIGINT NOT NULL,
  payout_amount BIGINT NOT NULL,
  record_count BIGINT NOT NULL,
  CONSTRAINT ck_pg_payout CHECK (record_count > 0 AND pg_fee_amount_total >= 0 AND payout_amount >= 0
    AND payment_amount_total = pg_fee_amount_total + payout_amount AND status = 'SIMULATED_PAID' AND currency = 'KRW')
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE pg_settlement_item (
  payment_id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
  provider_transaction_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL UNIQUE,
  provider_settlement_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  FOREIGN KEY (payment_id) REFERENCES pg_charge(payment_id),
  FOREIGN KEY (provider_settlement_id) REFERENCES pg_settlement(provider_settlement_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
