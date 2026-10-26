CREATE TABLE order_refund_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    refund_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    resolved_at DATETIME(6) NULL,
    failure_reason_code VARCHAR(100) COLLATE utf8mb4_bin NULL,
    failure_reason VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_refund_attempt_id (attempt_id),
    UNIQUE KEY uk_order_refund_attempt_no (refund_id, attempt_no),
    CONSTRAINT fk_order_refund_attempt_refund FOREIGN KEY (refund_id) REFERENCES order_refund (refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
