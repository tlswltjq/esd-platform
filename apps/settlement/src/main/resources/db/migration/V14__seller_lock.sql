-- Decisions 42·130 (D-045): one lock row per seller. Sale/refund posting and seller month closing lock it first,
-- so "a later month is CLOSED" and "an earlier month got a row" cannot be decided by two transactions at once.
-- New table only: rows are created lazily on first use; existing tables are not touched.
CREATE TABLE settlement_seller_lock (
  seller_subject VARCHAR(100) NOT NULL PRIMARY KEY,
  created_at DATETIME(6) NOT NULL
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
