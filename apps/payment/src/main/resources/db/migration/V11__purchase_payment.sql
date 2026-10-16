CREATE TABLE purchase_payment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    order_occurred_at DATETIME(6) NOT NULL,
    buyer_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    product_id BIGINT NOT NULL,
    product_code VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    content_version BIGINT NOT NULL,
    list_amount BIGINT NOT NULL,
    discount_amount BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(30) COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(20) NOT NULL,
    provider_transaction_id VARCHAR(100) COLLATE utf8mb4_bin NULL,
    provider_occurred_at DATETIME(6) NULL,
    result_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    result_occurred_at DATETIME(6) NULL,
    failure_reason_code VARCHAR(100) NULL,
    failure_reason VARCHAR(500) NULL,
    next_attempt_at DATETIME(6) NULL,
    last_error_code VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_purchase_payment_id (payment_id),
    UNIQUE KEY uk_purchase_payment_order (order_no),
    UNIQUE KEY uk_purchase_payment_request (request_event_id),
    UNIQUE KEY uk_purchase_payment_result (result_event_id),
    UNIQUE KEY uk_purchase_payment_provider (provider,provider_transaction_id),
    KEY idx_purchase_payment_due (status,next_attempt_at,id),
    KEY idx_purchase_payment_buyer (buyer_subject,created_at,id),
    CONSTRAINT chk_purchase_payment_price CHECK (list_amount>0 AND discount_amount>=0 AND discount_amount<=list_amount
        AND amount>0 AND amount=list_amount-discount_amount AND currency='KRW'),
    CONSTRAINT chk_purchase_payment_state CHECK (
        (status='PENDING' AND next_attempt_at IS NOT NULL AND provider_transaction_id IS NULL AND provider_occurred_at IS NULL
            AND result_event_id IS NULL AND result_occurred_at IS NULL AND failure_reason_code IS NULL AND failure_reason IS NULL)
        OR (status IN ('PAID','FAILED') AND next_attempt_at IS NULL AND last_error_code IS NULL
            AND provider_transaction_id IS NOT NULL AND provider_occurred_at IS NOT NULL
            AND result_event_id IS NOT NULL AND result_occurred_at IS NOT NULL
            AND ((status='PAID' AND failure_reason_code IS NULL AND failure_reason IS NULL)
                OR (status='FAILED' AND failure_reason_code IS NOT NULL AND failure_reason IS NOT NULL))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE simulated_payment_charge (
    payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    reason_code VARCHAR(100) NULL,
    reason VARCHAR(500) NULL,
    UNIQUE KEY uk_simulated_payment_transaction (transaction_id),
    CONSTRAINT chk_simulated_payment_price CHECK (amount>0 AND currency='KRW'),
    CONSTRAINT chk_simulated_payment_decision CHECK (
        (decision='APPROVED' AND reason_code IS NULL AND reason IS NULL)
        OR (decision='DECLINED' AND reason_code IS NOT NULL AND reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
