ALTER TABLE store_product
    ADD COLUMN sale_status VARCHAR(20) NOT NULL DEFAULT 'ON_SALE',
    ADD COLUMN catalog_version BIGINT NOT NULL DEFAULT 1;

UPDATE store_product SET catalog_version=content_version;

ALTER TABLE store_product
    ADD CONSTRAINT ck_store_sale_status CHECK (sale_status IN ('ON_SALE', 'STOPPED', 'BLOCKED')),
    ADD CONSTRAINT ck_store_catalog_version CHECK (catalog_version >= content_version);
