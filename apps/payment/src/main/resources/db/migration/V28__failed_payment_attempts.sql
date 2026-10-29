CREATE TABLE failed_payment_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    supplier_attempt_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    failed_at DATETIME(6) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_failed_payment_attempt_public UNIQUE (attempt_id),
    CONSTRAINT uq_failed_payment_attempt_supplier UNIQUE (supplier_attempt_id),
    CONSTRAINT fk_failed_payment_attempt_payment FOREIGN KEY (payment_id) REFERENCES purchase_payment(payment_id) ON DELETE CASCADE,
    CONSTRAINT chk_failed_payment_attempt_values CHECK (amount > 0 AND currency='KRW' AND failed_at>=requested_at AND observed_at>=failed_at),
    INDEX ix_failed_payment_attempt_history (payment_id, requested_at, failed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE purchase_payment
    ADD COLUMN next_failure_lookup_at DATETIME(6) NULL,
    ADD COLUMN failure_history_checked_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_payment_failure_lookup CHECK (next_failure_lookup_at IS NULL OR (provider='PORTONE_TEST' AND status IN ('PENDING','PAID'))),
    ADD INDEX ix_payment_failure_lookup (provider,next_failure_lookup_at,id);

-- Schedule a read-only history lookup. Historical failures and observation times are never invented.
UPDATE purchase_payment SET next_failure_lookup_at=COALESCE(next_attempt_at,UTC_TIMESTAMP(6))
WHERE provider='PORTONE_TEST' AND status IN ('PENDING','PAID');
