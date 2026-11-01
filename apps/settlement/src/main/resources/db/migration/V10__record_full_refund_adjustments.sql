ALTER TABLE settlement_sale ADD UNIQUE KEY uk_sale_refund_link(order_no,payment_id,provider,provider_transaction_id);

CREATE TABLE settlement_refund (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL, confirmed_at DATETIME(6) NOT NULL,
  refund_id VARCHAR(36) NOT NULL, attempt_id VARCHAR(36) NOT NULL, request_event_id VARCHAR(64) NOT NULL,
  order_no VARCHAR(36) NOT NULL, payment_id VARCHAR(64) NOT NULL, buyer_subject VARCHAR(100) NOT NULL,
  seller_subject VARCHAR(100) NOT NULL, product_id BIGINT NOT NULL, product_code VARCHAR(50) NOT NULL,
  title VARCHAR(200) NOT NULL, content_version BIGINT NOT NULL,
  amount BIGINT NOT NULL, currency VARCHAR(3) NOT NULL, source VARCHAR(30) NOT NULL,
  provider VARCHAR(30) NOT NULL, provider_transaction_id VARCHAR(100) NOT NULL, supplier_cancelled_at DATETIME(6) NOT NULL,
  allocation_policy_id VARCHAR(100) NOT NULL, original_platform_policy_id VARCHAR(100) NOT NULL,
  platform_fee_amount BIGINT NOT NULL, seller_payout_amount BIGINT NOT NULL,
  month_policy_id VARCHAR(100) NOT NULL, refund_month VARCHAR(7) NOT NULL, settlement_month VARCHAR(7) NOT NULL,
  month_reason VARCHAR(100) NOT NULL, received_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_refund_id(refund_id), UNIQUE KEY uk_refund_order(order_no), UNIQUE KEY uk_refund_event(event_id),
  UNIQUE KEY uk_refund_provider_link(id,provider),
  KEY idx_refund_seller_month(seller_subject,settlement_month,confirmed_at,id),
  CONSTRAINT fk_refund_sale FOREIGN KEY(order_no,payment_id,provider,provider_transaction_id)
    REFERENCES settlement_sale(order_no,payment_id,provider,provider_transaction_id),
  CONSTRAINT ck_refund_money CHECK(amount>0 AND currency='KRW' AND platform_fee_amount>=0
    AND seller_payout_amount>=0 AND amount=platform_fee_amount+seller_payout_amount),
  CONSTRAINT ck_refund_source CHECK(source IN ('PG_RESPONSE','PG_QUERY_UNATTRIBUTED'))
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE settlement_refund_cancellation (
  refund_record_id BIGINT NOT NULL, provider VARCHAR(30) NOT NULL, cancellation_id VARCHAR(100) NOT NULL,
  cancelled_amount BIGINT NOT NULL, cancelled_at DATETIME(6) NULL,
  PRIMARY KEY(provider,cancellation_id),
  CONSTRAINT fk_refund_cancellation_parent FOREIGN KEY(refund_record_id,provider) REFERENCES settlement_refund(id,provider),
  CONSTRAINT ck_refund_cancellation_amount CHECK(cancelled_amount>0)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

ALTER TABLE seller_monthly_settlement
  ADD refund_amount BIGINT NOT NULL DEFAULT 0,
  ADD refunded_platform_fee_amount BIGINT NOT NULL DEFAULT 0,
  ADD refunded_seller_payout_amount BIGINT NOT NULL DEFAULT 0,
  ADD refund_count BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_month_refund_allocation CHECK(refund_amount>=0 AND refunded_platform_fee_amount>=0
    AND refunded_seller_payout_amount>=0 AND refund_count>=0 AND refund_amount=refunded_platform_fee_amount+refunded_seller_payout_amount);
