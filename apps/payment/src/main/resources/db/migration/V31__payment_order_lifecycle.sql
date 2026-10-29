-- The per-order row serializes creation with expiry, including a notice delivered before OrderPlaced.
-- It intentionally survives without a Payment row, so late/replayed creation cannot reopen expiry.
CREATE TABLE payment_order_lifecycle (
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    request_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    buyer_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    expiry_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    expired_at DATETIME(6) NULL,
    financial_conflict BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uk_payment_order_expiry_event(expiry_event_id),
    CONSTRAINT ck_payment_order_lifecycle_charge CHECK(amount>0 AND currency='KRW'),
    CONSTRAINT ck_payment_order_lifecycle_expiry CHECK(
        (expiry_event_id IS NULL AND expired_at IS NULL AND financial_conflict=false)
        OR (expiry_event_id IS NOT NULL AND expired_at IS NOT NULL AND expired_at>=expires_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
