ALTER TABLE product ADD COLUMN storefront_snapshot_json LONGTEXT NULL;
ALTER TABLE product ADD COLUMN projection_version BIGINT NOT NULL DEFAULT 0;
