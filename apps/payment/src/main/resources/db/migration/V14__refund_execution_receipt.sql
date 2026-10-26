CREATE TABLE refund_execution (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    refund_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    request_occurred_at DATETIME(6) NOT NULL,
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    buyer_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    license_suspension_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    received_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    pending_payment_guard VARCHAR(64) COLLATE utf8mb4_bin
        GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN payment_id ELSE NULL END) STORED,
    pending_refund_guard VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN refund_id ELSE NULL END) STORED,
    UNIQUE KEY uk_refund_execution_attempt (attempt_id),
    UNIQUE KEY uk_refund_execution_request (request_event_id),
    UNIQUE KEY uk_refund_execution_pending_payment (pending_payment_guard),
    UNIQUE KEY uk_refund_execution_pending_refund (pending_refund_guard),
    KEY idx_refund_execution_payment (payment_id,id),
    KEY idx_refund_execution_refund (refund_id,id),
    CONSTRAINT fk_refund_execution_payment FOREIGN KEY (payment_id) REFERENCES purchase_payment (payment_id),
    CONSTRAINT chk_refund_execution_price CHECK (amount>0 AND currency='KRW'),
    CONSTRAINT chk_refund_execution_status CHECK (status='PENDING')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refund_execution_trace (
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    trace_parent VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    received_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_refund_execution_trace FOREIGN KEY (attempt_id)
        REFERENCES refund_execution (attempt_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
