CREATE TABLE license_approval_correction (
    request_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    request_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_occurred_at DATETIME(6) NOT NULL,
    stop_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    suspension_request_event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    license_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    completed_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    restored_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_license_correction_stop (stop_request_id),
    UNIQUE KEY uk_license_correction_suspension (suspension_request_event_id),
    UNIQUE KEY uk_license_correction_completed (completed_event_id),
    CONSTRAINT fk_license_correction_license FOREIGN KEY (license_id) REFERENCES product_license(license_id),
    CONSTRAINT fk_license_correction_suspension FOREIGN KEY (suspension_request_event_id) REFERENCES license_suspension(request_event_id),
    CONSTRAINT chk_license_correction_time CHECK (restored_at>=request_occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
