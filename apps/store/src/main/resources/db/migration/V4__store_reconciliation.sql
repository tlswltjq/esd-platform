CREATE TABLE store_reconciliation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    product_code VARCHAR(50) NOT NULL,
    catalog_version BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    before_snapshot LONGTEXT,
    after_snapshot LONGTEXT NOT NULL,
    source_read_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_store_reconciliation_action CHECK (action IN ('INSERTED', 'UPDATED', 'REPAIRED')),
    KEY idx_store_reconciliation_product (product_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
