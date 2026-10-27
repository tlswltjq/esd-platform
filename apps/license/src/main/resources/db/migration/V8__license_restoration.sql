CREATE TABLE license_restoration (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    request_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_occurred_at DATETIME(6) NOT NULL,
    refund_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    license_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    suspension_request_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    completed_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    restored_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_license_restoration_request (request_event_id),
    UNIQUE KEY uk_license_restoration_suspension (suspension_request_event_id),
    UNIQUE KEY uk_license_restoration_result (completed_event_id),
    CONSTRAINT fk_license_restoration_license FOREIGN KEY (license_id) REFERENCES product_license (license_id),
    CONSTRAINT fk_license_restoration_suspension FOREIGN KEY (suspension_request_event_id) REFERENCES license_suspension (request_event_id),
    CONSTRAINT chk_license_restoration_time CHECK (restored_at >= request_occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
