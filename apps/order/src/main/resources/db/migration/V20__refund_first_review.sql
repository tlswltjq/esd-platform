ALTER TABLE order_refund
    ADD COLUMN review_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN review_decision VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN review_operator_subject VARCHAR(100) COLLATE utf8mb4_bin NULL,
    ADD COLUMN review_reason VARCHAR(500) NULL,
    ADD COLUMN reviewed_at DATETIME(6) NULL,
    ADD UNIQUE KEY uk_order_refund_review_request (review_request_id),
    ADD INDEX ix_order_refund_review_queue (status, received_at, id),
    ADD CONSTRAINT chk_order_refund_review CHECK (
        (review_request_id IS NULL AND review_decision IS NULL AND review_operator_subject IS NULL
            AND review_reason IS NULL AND reviewed_at IS NULL AND status <> 'REJECTED')
        OR (review_request_id IS NOT NULL AND review_decision IS NOT NULL AND review_operator_subject IS NOT NULL
            AND review_reason IS NOT NULL AND reviewed_at IS NOT NULL AND automatic_refund_eligible=FALSE
            AND CHAR_LENGTH(review_request_id)=36 AND CHAR_LENGTH(TRIM(review_operator_subject))>0
            AND CHAR_LENGTH(TRIM(review_reason))>0 AND reviewed_at>=received_at
            AND ((review_decision='APPROVED' AND status IN ('PENDING_LICENSE_GRANT','PENDING_LICENSE_SUSPENSION',
                    'PENDING_PAYMENT_REFUND','PENDING_LICENSE_RESTORATION','FAILED','COMPLETED'))
                OR (review_decision='REJECTED' AND status='REJECTED' AND suspension_request_event_id IS NULL
                    AND current_attempt_id IS NULL AND payment_confirmation_event_id IS NULL))));

-- Preserve prior rows without inventing historical operator decisions.
