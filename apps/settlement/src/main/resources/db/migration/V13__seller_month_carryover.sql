-- Decisions 121·130: immutable seller carry ledger, one row per (seller, month), written by the closing transaction.
-- New table only: existing CLOSED months get no backfilled rows and are read as carriedIn = 0.
CREATE TABLE settlement_seller_carryover (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  seller_subject VARCHAR(100) NOT NULL,
  settlement_month VARCHAR(7) NOT NULL,
  net_seller_payout_amount BIGINT NOT NULL,
  carried_in BIGINT NOT NULL,
  payable BIGINT NOT NULL,
  carried_out BIGINT NOT NULL,
  source_month VARCHAR(7) NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_seller_carryover_month (seller_subject,settlement_month),
  CONSTRAINT ck_seller_carryover_amount CHECK (carried_in<=0 AND payable>=0 AND carried_out<=0 AND (payable=0 OR carried_out=0)
    AND net_seller_payout_amount+carried_in=payable+carried_out),
  CONSTRAINT ck_seller_carryover_source CHECK ((source_month IS NULL AND carried_in=0) OR (source_month IS NOT NULL AND source_month<settlement_month))
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
