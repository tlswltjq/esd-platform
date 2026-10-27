CREATE TABLE payment_pg_webhook (
    message_id VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    store_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    transaction_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    KEY idx_payment_pg_webhook_payment (payment_id, received_at),
    CONSTRAINT fk_payment_pg_webhook_payment FOREIGN KEY (payment_id)
        REFERENCES purchase_payment (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
