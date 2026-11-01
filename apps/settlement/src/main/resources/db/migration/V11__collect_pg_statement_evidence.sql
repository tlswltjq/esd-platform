CREATE TABLE settlement_pg_statement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_id VARCHAR(100) NOT NULL, environment VARCHAR(10) NOT NULL,
  portone_store_id VARCHAR(100) NOT NULL, portone_channel_key VARCHAR(100) NOT NULL,
  identity_mapping_verified BOOLEAN NOT NULL,
  date_type VARCHAR(10) NOT NULL, base_date DATE NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  total_amount BIGINT NOT NULL, total_fee BIGINT NOT NULL, total_vat BIGINT NOT NULL, total_fee_vat_sum BIGINT NOT NULL,
  record_count BIGINT NOT NULL, page_count INT NOT NULL,
  received_at DATETIME(6) NOT NULL, collected_by VARCHAR(100) NOT NULL,
  checked_at DATETIME(6) NULL, status VARCHAR(30) NOT NULL,
  UNIQUE KEY uk_pg_statement_fingerprint(fingerprint),
  KEY idx_pg_statement_received(received_at,id),
  CONSTRAINT ck_pg_statement_scope CHECK(environment IN ('TEST','LIVE') AND date_type IN ('DUE','SETTLE')),
  CONSTRAINT ck_pg_statement_totals CHECK(total_fee_vat_sum=total_fee+total_vat AND record_count>=0 AND page_count>0)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE settlement_pg_statement_page (
  statement_id BIGINT NOT NULL, page_position INT NOT NULL,
  request_cursor VARCHAR(2000) NULL, next_cursor VARCHAR(2000) NOT NULL, response_body LONGTEXT NOT NULL,
  PRIMARY KEY(statement_id,page_position),
  CONSTRAINT fk_pg_statement_page FOREIGN KEY(statement_id) REFERENCES settlement_pg_statement(id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE settlement_pg_statement_entry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, merchant_id VARCHAR(100) NOT NULL, environment VARCHAR(10) NOT NULL,
  transaction_type VARCHAR(10) NOT NULL, transaction_id VARCHAR(100) NOT NULL,
  pay_token VARCHAR(100) NOT NULL, order_no VARCHAR(100) NOT NULL, product_desc VARCHAR(2000) NULL,
  amount BIGINT NOT NULL, fee INT NOT NULL, vat INT NOT NULL, fee_vat_sum INT NOT NULL,
  pay_method VARCHAR(20) NOT NULL, due_date DATE NOT NULL, received_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_pg_statement_entry(merchant_id,environment,transaction_id),
  KEY idx_pg_statement_token(merchant_id,environment,pay_token),
  CONSTRAINT ck_pg_entry_scope CHECK(environment IN ('TEST','LIVE')),
  CONSTRAINT ck_pg_entry_amount CHECK((transaction_type='PAY' AND amount>0 AND fee<=0 AND vat<=0)
    OR (transaction_type='REFUND' AND amount<0 AND fee>=0 AND vat>=0)),
  CONSTRAINT ck_pg_entry_fee CHECK(CAST(fee AS SIGNED)+CAST(vat AS SIGNED)=fee_vat_sum)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE settlement_pg_statement_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, statement_id BIGINT NOT NULL, item_position INT NOT NULL, entry_id BIGINT NOT NULL,
  check_code VARCHAR(40) NOT NULL, payment_id VARCHAR(64) NULL, checked_at DATETIME(6) NULL,
  UNIQUE KEY uk_pg_statement_item_position(statement_id,item_position),
  UNIQUE KEY uk_pg_statement_item_entry(statement_id,entry_id),
  CONSTRAINT fk_pg_statement_item_header FOREIGN KEY(statement_id) REFERENCES settlement_pg_statement(id),
  CONSTRAINT fk_pg_statement_item_entry FOREIGN KEY(entry_id) REFERENCES settlement_pg_statement_entry(id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
