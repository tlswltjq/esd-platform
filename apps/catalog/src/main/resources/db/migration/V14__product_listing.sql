ALTER TABLE catalog_product
    ADD COLUMN listing_preference VARCHAR(20) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN platform_listing_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_listing_preference CHECK (listing_preference IN ('AUTO','LISTED','UNLISTED')),
    ADD CONSTRAINT ck_platform_listing_hidden CHECK (platform_listing_hidden IN (FALSE,TRUE));

CREATE TABLE catalog_listing_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    actor_subject VARCHAR(100) NOT NULL,
    authority VARCHAR(20) NOT NULL,
    previous_preference VARCHAR(20) NOT NULL,
    listing_preference VARCHAR(20) NOT NULL,
    previous_platform_hidden BOOLEAN NOT NULL,
    platform_hidden BOOLEAN NOT NULL,
    previous_version BIGINT NOT NULL,
    catalog_version BIGINT NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_listing_change(product_id,catalog_version),
    CONSTRAINT fk_listing_change_product FOREIGN KEY(product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_listing_change_version CHECK(catalog_version=previous_version+1),
    CONSTRAINT ck_listing_change_authority CHECK(authority IN ('CREATOR','ADMIN')),
    CONSTRAINT ck_listing_change_preferences CHECK(previous_preference IN ('AUTO','LISTED','UNLISTED') AND listing_preference IN ('AUTO','LISTED','UNLISTED'))
);
