CREATE TABLE payment_request_trace (
    payment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    trace_parent VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    received_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_payment_request_trace_payment FOREIGN KEY (payment_id)
        REFERENCES purchase_payment (payment_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
