ALTER TABLE catalog_product
    ADD COLUMN sale_status VARCHAR(20) NOT NULL DEFAULT 'ON_SALE',
    ADD COLUMN catalog_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN platform_restricted BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE catalog_product p JOIN catalog_release r ON r.release_request_id=p.current_release_request_id
SET p.catalog_version=r.content_version;

ALTER TABLE catalog_product
    ADD CONSTRAINT ck_catalog_sale_status CHECK (sale_status IN ('ON_SALE', 'STOPPED')),
    ADD CONSTRAINT ck_catalog_version CHECK (catalog_version > 0);

CREATE TABLE catalog_sale_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    actor_subject VARCHAR(100) NOT NULL,
    authority VARCHAR(20) NOT NULL,
    previous_seller_status VARCHAR(20) NOT NULL,
    seller_status VARCHAR(20) NOT NULL,
    previous_restriction BOOLEAN NOT NULL,
    platform_restricted BOOLEAN NOT NULL,
    previous_version BIGINT NOT NULL,
    catalog_version BIGINT NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_catalog_sale_change (product_id, catalog_version),
    CONSTRAINT fk_catalog_sale_change_product FOREIGN KEY(product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_catalog_sale_change_version CHECK(catalog_version=previous_version+1),
    CONSTRAINT ck_catalog_sale_change_authority CHECK(authority IN ('CREATOR','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
