ALTER TABLE refund_execution
    ADD COLUMN previous_attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_refund_execution_previous (previous_attempt_id),
    ADD UNIQUE KEY uk_refund_execution_suspension (license_suspension_event_id),
    ADD CONSTRAINT fk_refund_execution_previous FOREIGN KEY (previous_attempt_id) REFERENCES refund_execution (attempt_id),
    ADD CONSTRAINT chk_refund_execution_previous CHECK (previous_attempt_id IS NULL OR previous_attempt_id <> attempt_id);
