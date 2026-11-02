CREATE TABLE settlement_pg_cost_posting (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_entry_id BIGINT NOT NULL, sale_id BIGINT NOT NULL, refund_record_id BIGINT NULL,
  payment_id VARCHAR(64) NOT NULL, seller_subject VARCHAR(100) NOT NULL, business_month VARCHAR(7) NOT NULL,
  check_code VARCHAR(40) NOT NULL,
  policy_id VARCHAR(100) NOT NULL, verification_statement_id BIGINT NOT NULL, verified_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_pg_cost_source(source_entry_id),
  KEY idx_pg_cost_business(seller_subject,business_month),
  KEY idx_pg_cost_refund(refund_record_id),
  CONSTRAINT fk_pg_cost_source FOREIGN KEY(source_entry_id) REFERENCES settlement_pg_statement_entry(id),
  CONSTRAINT fk_pg_cost_sale FOREIGN KEY(sale_id) REFERENCES settlement_sale(id),
  CONSTRAINT fk_pg_cost_refund FOREIGN KEY(refund_record_id) REFERENCES settlement_refund(id),
  CONSTRAINT fk_pg_cost_verification FOREIGN KEY(verification_statement_id) REFERENCES settlement_pg_statement(id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE INDEX idx_pg_entry_payout ON settlement_pg_statement_entry(merchant_id,environment,due_date,id);
CREATE INDEX idx_pg_statement_pending ON settlement_pg_statement(status,id);
