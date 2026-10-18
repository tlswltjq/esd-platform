-- Fail before changing business tables if the old 1:N data cannot be copied
-- losslessly. Do not choose one purchase or silently discard a broken Inbox.
CREATE TEMPORARY TABLE license_merge_validation
(
    invalid_sources BIGINT NOT NULL,
    mismatched_sources BIGINT NOT NULL,
    invalid_inbox BIGINT NOT NULL,
    CONSTRAINT chk_license_merge_one_source CHECK (invalid_sources = 0),
    CONSTRAINT chk_license_merge_same_product CHECK (mismatched_sources = 0),
    CONSTRAINT chk_license_merge_matching_inbox CHECK (invalid_inbox = 0)
);

INSERT INTO license_merge_validation
SELECT
    (SELECT COUNT(*) FROM (
        SELECT l.id FROM product_license l LEFT JOIN license_purchase p ON p.license_id = l.id
        GROUP BY l.id HAVING COUNT(p.id) <> 1
    ) invalid),
    (SELECT COUNT(*) FROM product_license l JOIN license_purchase p ON p.license_id = l.id
        WHERE BINARY l.buyer_subject <> BINARY p.buyer_subject OR l.product_id <> p.product_id
           OR BINARY l.product_code <> BINARY p.product_code OR BINARY l.title <> BINARY p.title),
    (SELECT COUNT(*) FROM license_purchase p LEFT JOIN processed_event e
        ON BINARY e.event_id = BINARY p.event_id AND BINARY e.consumer_group = BINARY 'license-paid-order'
        WHERE e.id IS NULL OR BINARY e.event_type <> BINARY 'OrderPaid')
    + (SELECT COUNT(*) FROM processed_event e
        WHERE BINARY e.consumer_group = BINARY 'license-paid-order'
          AND NOT EXISTS (SELECT 1 FROM license_purchase p WHERE BINARY p.event_id = BINARY e.event_id));

DROP TEMPORARY TABLE license_merge_validation;

ALTER TABLE product_license
    ADD COLUMN event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN occurred_at DATETIME(6) NULL,
    ADD COLUMN order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN request_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN payment_result_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN payment_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN content_version BIGINT NULL,
    ADD COLUMN amount BIGINT NULL,
    ADD COLUMN currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN received_at DATETIME(6) NULL;

UPDATE product_license l JOIN license_purchase p ON p.license_id = l.id
SET l.event_id = p.event_id, l.occurred_at = p.occurred_at, l.order_no = p.order_no,
    l.request_event_id = p.request_event_id, l.payment_result_event_id = p.payment_result_event_id,
    l.payment_id = p.payment_id, l.content_version = p.content_version,
    l.amount = p.amount, l.currency = p.currency, l.received_at = p.received_at;

ALTER TABLE product_license
    MODIFY COLUMN event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN occurred_at DATETIME(6) NOT NULL,
    MODIFY COLUMN order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN request_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN payment_result_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN content_version BIGINT NOT NULL,
    MODIFY COLUMN amount BIGINT NOT NULL,
    MODIFY COLUMN currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN received_at DATETIME(6) NOT NULL,
    ADD UNIQUE KEY uk_product_license_order (order_no),
    ADD UNIQUE KEY uk_product_license_event (event_id),
    ADD UNIQUE KEY uk_product_license_request (request_event_id),
    ADD UNIQUE KEY uk_product_license_result (payment_result_event_id),
    ADD UNIQUE KEY uk_product_license_payment (payment_id),
    ADD CONSTRAINT chk_product_license_source CHECK (content_version > 0 AND amount > 0 AND currency = 'KRW');

-- license_purchase is retained as historical data. Runtime writes only to
-- product_license; no source rows are deleted during this migration.
