ALTER TABLE product
    ADD COLUMN product_kind VARCHAR(20) NOT NULL DEFAULT 'BASIC',
    ADD COLUMN parent_product_code VARCHAR(50) NULL,
    ADD COLUMN edition_name VARCHAR(100) NULL,
    ADD KEY idx_product_family (parent_product_code);

CREATE TABLE product_bundle_component (
    product_id BIGINT NOT NULL,
    component_product_code VARCHAR(50) NOT NULL,
    PRIMARY KEY (product_id, component_product_code),
    CONSTRAINT fk_product_bundle_component_product
        FOREIGN KEY (product_id) REFERENCES product (id)
);
