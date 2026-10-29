-- New development baseline: no automatic-approval compatibility branch is introduced.
-- A processing order continues to occupy the same buyer/product purchase slot.
ALTER TABLE purchase_order
    MODIFY COLUMN purchase_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'PAYMENT_PROCESSING', 'PAID') THEN 1 ELSE NULL END
    ) STORED;

CREATE TABLE order_payment_admission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    admission_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    buyer_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    admitted_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_payment_admission (admission_id),
    KEY idx_order_payment_admission_order (order_no, id),
    CONSTRAINT fk_order_payment_admission_order FOREIGN KEY (order_no) REFERENCES purchase_order(order_no),
    CONSTRAINT chk_order_payment_admission CHECK (amount > 0 AND currency = 'KRW' AND admitted_at < expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
