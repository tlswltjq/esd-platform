CREATE TABLE simulated_pg_transaction (
    pg_tx_id VARCHAR(100) NOT NULL PRIMARY KEY,
    order_no VARCHAR(40) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason_code VARCHAR(50) NULL,
    reason VARCHAR(200) NULL,
    created_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    KEY idx_simulated_pg_order_no (order_no)
);
