ALTER TABLE store_product ADD COLUMN listed BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE store_product SET listed = (sale_status = 'ON_SALE');
ALTER TABLE store_product ADD CONSTRAINT ck_store_listed CHECK (listed IN (FALSE, TRUE));
CREATE INDEX idx_store_listing_newest ON store_product(listed, published_at, product_id);
