ALTER TABLE product_license
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN suspension_request_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL;

CREATE TABLE license_suspension (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    request_occurred_at DATETIME(6) NOT NULL,
    refund_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    license_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    product_id BIGINT NOT NULL,
    product_code VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    suspended_at DATETIME(6) NOT NULL,
    completed_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_license_suspension_request (request_event_id),
    UNIQUE KEY uk_license_suspension_result (completed_event_id),
    KEY idx_license_suspension_license (license_id, id),
    CONSTRAINT fk_license_suspension_license FOREIGN KEY (license_id) REFERENCES product_license (license_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
