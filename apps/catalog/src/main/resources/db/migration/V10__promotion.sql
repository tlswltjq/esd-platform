CREATE TABLE promotion (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    discount_per_unit BIGINT NOT NULL,
    bearer VARCHAR(20) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    stopped_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_promotion_product_time (product_id, starts_at, ends_at),
    CONSTRAINT fk_promotion_product FOREIGN KEY (product_id) REFERENCES product(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
