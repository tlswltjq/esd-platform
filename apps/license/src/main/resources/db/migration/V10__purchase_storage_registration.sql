-- 결정 89·93·95·96: 구매 근거(주문 1건)와 보유권(등록 1번)을 나눈다.
-- product_license는 구매 근거(보관함 항목)로 남고, 고객·상품당 1행 제한을 푼다.
-- 쓸 수 있는(ACTIVE) 보유권만 고객·상품당 1개로 DB가 막는다.

CREATE TABLE license_registration (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    registration_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    license_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    product_id BIGINT NOT NULL,
    product_code VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    registered_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    active_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END) STORED,
    open_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('ACTIVE', 'SUSPENDED') THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_license_registration_id (registration_id),
    UNIQUE KEY uk_license_registration_active (buyer_subject, product_id, active_guard),
    UNIQUE KEY uk_license_registration_open (license_id, open_guard),
    KEY idx_license_registration_library (buyer_subject, status, registered_at, id),
    KEY idx_license_registration_code (buyer_subject, product_code, status, id),
    CONSTRAINT fk_license_registration_purchase FOREIGN KEY (license_id) REFERENCES product_license (license_id),
    CONSTRAINT chk_license_registration_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ENDED')),
    CONSTRAINT chk_license_registration_end CHECK ((status = 'ENDED') = (ended_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 기존 보유권은 이미 지급(등록)된 것으로 옮긴다. 지우거나 고르지 않는다.
INSERT INTO license_registration (registration_id, license_id, order_no, buyer_subject, product_id, product_code, title,
                                  status, registered_at)
SELECT UUID(), license_id, order_no, buyer_subject, product_id, product_code, title, status, acquired_at
FROM product_license
WHERE status IN ('ACTIVE', 'SUSPENDED');

ALTER TABLE product_license
    ADD COLUMN suspended_from VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN refunded_suspension_request_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN refunded_at DATETIME(6) NULL;

UPDATE product_license SET suspended_from = 'REGISTERED' WHERE status = 'SUSPENDED';
UPDATE product_license SET status = 'REGISTERED' WHERE status = 'ACTIVE';

ALTER TABLE product_license
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'STORED',
    DROP INDEX uk_product_license_buyer_id,
    DROP INDEX uk_product_license_buyer_code,
    ADD KEY idx_product_license_product (buyer_subject, product_id, id),
    ADD KEY idx_product_license_storage (buyer_subject, status, acquired_at, id),
    ADD CONSTRAINT chk_product_license_status CHECK (status IN ('STORED', 'REGISTERED', 'SUSPENDED', 'REFUNDED'));

ALTER TABLE license_restoration ADD COLUMN returned_to_storage BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE license_approval_correction ADD COLUMN returned_to_storage BOOLEAN NOT NULL DEFAULT FALSE;
