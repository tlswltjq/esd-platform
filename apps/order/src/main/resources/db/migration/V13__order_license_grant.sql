CREATE TABLE order_license_grant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    license_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    product_id BIGINT NOT NULL,
    product_code VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_license_grant_event (event_id),
    UNIQUE KEY uk_order_license_grant_license (license_id),
    UNIQUE KEY uk_order_license_grant_order (order_no),
    CONSTRAINT fk_order_license_grant_order FOREIGN KEY (order_no) REFERENCES purchase_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
