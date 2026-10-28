ALTER TABLE order_refund_attempt
    ADD COLUMN preparation_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN first_refund_prepared_at DATETIME(6) NULL,
    ADD COLUMN confirmation_delay_at DATETIME(6) NULL,
    ADD COLUMN preparation_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_order_attempt_preparation (preparation_event_id),
    ADD CONSTRAINT chk_order_attempt_preparation CHECK (
        (preparation_event_id IS NULL AND first_refund_prepared_at IS NULL AND confirmation_delay_at IS NULL AND preparation_fingerprint IS NULL)
        OR (preparation_event_id IS NOT NULL AND first_refund_prepared_at IS NOT NULL AND confirmation_delay_at IS NOT NULL
            AND preparation_fingerprint IS NOT NULL AND CHAR_LENGTH(preparation_fingerprint)=64
            AND first_refund_prepared_at>=started_at AND confirmation_delay_at=DATE_ADD(first_refund_prepared_at,INTERVAL 3 HOUR)));
