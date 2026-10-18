CREATE TABLE product_license
(
    id            BIGINT                                            NOT NULL AUTO_INCREMENT PRIMARY KEY,
    license_id    VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_subject VARCHAR(100) COLLATE utf8mb4_bin                  NOT NULL,
    product_id    BIGINT                                            NOT NULL,
    product_code  VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title         VARCHAR(200)                                      NOT NULL,
    acquired_at   DATETIME(6) NOT NULL,
    UNIQUE KEY uk_product_license_id (license_id),
    UNIQUE KEY uk_product_license_buyer_id (buyer_subject, product_id),
    UNIQUE KEY uk_product_license_buyer_code (buyer_subject, product_code),
    KEY           idx_product_license_buyer (buyer_subject, acquired_at, id),
    CONSTRAINT chk_product_license_product CHECK (product_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE license_purchase
(
    id                      BIGINT                                            NOT NULL AUTO_INCREMENT PRIMARY KEY,
    license_id              BIGINT                                            NOT NULL,
    event_id                VARCHAR(64) COLLATE utf8mb4_bin                   NOT NULL,
    occurred_at             DATETIME(6) NOT NULL,
    order_no                VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_event_id        VARCHAR(64) COLLATE utf8mb4_bin                   NOT NULL,
    payment_result_event_id VARCHAR(64) COLLATE utf8mb4_bin                   NOT NULL,
    payment_id              VARCHAR(64) COLLATE utf8mb4_bin                   NOT NULL,
    buyer_subject           VARCHAR(100) COLLATE utf8mb4_bin                  NOT NULL,
    product_id              BIGINT                                            NOT NULL,
    product_code            VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title                   VARCHAR(200)                                      NOT NULL,
    content_version         BIGINT                                            NOT NULL,
    amount                  BIGINT                                            NOT NULL,
    currency                VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin  NOT NULL,
    received_at             DATETIME(6) NOT NULL,
    UNIQUE KEY uk_license_purchase_order (order_no),
    UNIQUE KEY uk_license_purchase_event (event_id),
    UNIQUE KEY uk_license_purchase_request (request_event_id),
    UNIQUE KEY uk_license_purchase_result (payment_result_event_id),
    UNIQUE KEY uk_license_purchase_payment (payment_id),
    CONSTRAINT fk_license_purchase_license FOREIGN KEY (license_id) REFERENCES product_license (id),
    CONSTRAINT chk_license_purchase_values CHECK (product_id > 0 AND content_version > 0 AND amount > 0 AND currency = 'KRW')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
