CREATE TABLE order_refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    received_at DATETIME(6) NOT NULL,
    automatic_refund_eligible BOOLEAN NOT NULL,
    status VARCHAR(40) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_refund_id (refund_id),
    UNIQUE KEY uk_order_refund_order (order_no),
    CONSTRAINT fk_order_refund_order FOREIGN KEY (order_no) REFERENCES purchase_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
