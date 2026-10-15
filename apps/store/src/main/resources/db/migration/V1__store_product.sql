CREATE TABLE store_product (
    product_id BIGINT NOT NULL PRIMARY KEY,
    product_code VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    short_description VARCHAR(500) NOT NULL,
    thumbnail_url VARCHAR(1000) NOT NULL,
    price BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    published_at DATETIME(6) NOT NULL,
    content LONGTEXT NOT NULL,
    video_url VARCHAR(1000),
    content_version BIGINT NOT NULL,
    build_version VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_store_product_code (product_code),
    KEY idx_store_published (published_at, product_id),
    KEY idx_store_price (price, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
