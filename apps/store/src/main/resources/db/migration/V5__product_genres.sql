CREATE TABLE store_product_genre (
    product_id BIGINT NOT NULL,
    genre_code VARCHAR(32) NOT NULL,
    PRIMARY KEY (product_id, genre_code),
    INDEX idx_store_genre_code (genre_code, product_id),
    CONSTRAINT fk_store_genre_product FOREIGN KEY (product_id) REFERENCES store_product(product_id) ON DELETE CASCADE
);
