CREATE TABLE payment_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(40) NOT NULL,
    actor VARCHAR(80) NOT NULL,
    action VARCHAR(40) NOT NULL,
    detail VARCHAR(200) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    INDEX idx_payment_audit_order (order_no, occurred_at)
);
