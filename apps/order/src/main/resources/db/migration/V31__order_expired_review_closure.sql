-- Decision 115 (PR #135 review): a Payment operator closure (REVIEW_CLOSED) that reaches an order that already expired on its
-- own keeps the order EXPIRED. The result identity is kept here, not on purchase_order, so every reader of an EXPIRED order is
-- unchanged, while R4.6 can still reject a reused event ID and a changed redelivery. New table only; no existing row changes.
CREATE TABLE order_expired_review_closure (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_order_expired_review_closure_order UNIQUE (order_no),
    CONSTRAINT uq_order_expired_review_closure_event UNIQUE (event_id),
    CONSTRAINT fk_order_expired_review_closure_order FOREIGN KEY (order_no) REFERENCES purchase_order(order_no),
    CONSTRAINT chk_order_expired_review_closure_reason CHECK (reason_code = 'REVIEW_CLOSED' AND CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
