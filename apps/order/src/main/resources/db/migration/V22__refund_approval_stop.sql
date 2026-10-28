ALTER TABLE order_refund_review_round
    DROP CHECK chk_refund_review_application,
    ADD COLUMN correction_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN stop_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN stop_operator_subject VARCHAR(100) COLLATE utf8mb4_bin NULL,
    ADD COLUMN stop_reason VARCHAR(500) NULL,
    ADD COLUMN stop_received_at DATETIME(6) NULL,
    ADD COLUMN stop_suspension_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN stop_suspension_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN stop_suspension_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN stop_license_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN stop_restoration_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN stop_restoration_requested_at DATETIME(6) NULL,
    ADD COLUMN stop_restored_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN stop_restored_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN stop_restored_at DATETIME(6) NULL,
    ADD COLUMN stop_reopened_at DATETIME(6) NULL,
    ADD COLUMN stop_next_review_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_review_correction_request (correction_request_id),
    ADD UNIQUE KEY uk_review_stop_request (stop_request_id),
    ADD UNIQUE KEY uk_review_stop_suspension_request (stop_suspension_request_id),
    ADD UNIQUE KEY uk_review_stop_suspension_event (stop_suspension_event_id),
    ADD UNIQUE KEY uk_review_stop_restoration_request (stop_restoration_request_id),
    ADD UNIQUE KEY uk_review_stop_restored_event (stop_restored_event_id),
    ADD UNIQUE KEY uk_review_stop_next (stop_next_review_id),
    ADD CONSTRAINT chk_refund_review_application CHECK (
        CHAR_LENGTH(TRIM(buyer_subject))>0 AND ((review_no=1 AND review_id=refund_id
            AND application_request_id IS NULL AND previous_review_id IS NULL AND customer_reason IS NULL AND correction_request_id IS NULL)
        OR (review_no>1 AND previous_review_id IS NOT NULL AND previous_review_id<>review_id AND (
            (application_request_id IS NOT NULL AND correction_request_id IS NULL AND customer_reason IS NOT NULL AND CHAR_LENGTH(TRIM(customer_reason))>0)
            OR (application_request_id IS NULL AND correction_request_id IS NOT NULL AND customer_reason IS NULL))))),
    ADD CONSTRAINT chk_review_stop_identity CHECK (
        (stop_request_id IS NULL AND stop_operator_subject IS NULL AND stop_reason IS NULL AND stop_received_at IS NULL
            AND stop_suspension_request_id IS NULL AND stop_suspension_event_id IS NULL AND stop_suspension_fingerprint IS NULL
            AND stop_license_id IS NULL AND stop_restoration_request_id IS NULL AND stop_restoration_requested_at IS NULL
            AND stop_restored_event_id IS NULL AND stop_restored_fingerprint IS NULL AND stop_restored_at IS NULL
            AND stop_reopened_at IS NULL AND stop_next_review_id IS NULL)
        OR (stop_request_id IS NOT NULL AND stop_operator_subject IS NOT NULL AND CHAR_LENGTH(TRIM(stop_operator_subject))>0
            AND stop_reason IS NOT NULL AND CHAR_LENGTH(TRIM(stop_reason))>0 AND stop_received_at IS NOT NULL
            AND decision='APPROVED' AND decided_at IS NOT NULL AND stop_received_at>=decided_at)),
    ADD CONSTRAINT chk_review_stop_suspension CHECK (
        (stop_suspension_event_id IS NULL AND stop_suspension_fingerprint IS NULL AND stop_license_id IS NULL
            AND stop_restoration_request_id IS NULL AND stop_restoration_requested_at IS NULL)
        OR (stop_suspension_request_id IS NOT NULL AND stop_suspension_event_id IS NOT NULL AND stop_suspension_fingerprint IS NOT NULL
            AND stop_license_id IS NOT NULL AND stop_restoration_request_id IS NOT NULL AND stop_restoration_requested_at IS NOT NULL
            AND stop_restoration_requested_at>=stop_received_at)),
    ADD CONSTRAINT chk_review_stop_restored CHECK (
        (stop_restored_event_id IS NULL AND stop_restored_fingerprint IS NULL AND stop_restored_at IS NULL)
        OR (stop_restored_event_id IS NOT NULL AND stop_restored_fingerprint IS NOT NULL AND stop_restored_at IS NOT NULL
            AND stop_restoration_request_id IS NOT NULL AND stop_restored_at>=stop_restoration_requested_at)),
    ADD CONSTRAINT chk_review_stop_reopened CHECK (
        (stop_reopened_at IS NULL AND stop_next_review_id IS NULL)
        OR (stop_reopened_at IS NOT NULL AND stop_next_review_id IS NOT NULL AND stop_reopened_at>=stop_received_at
            AND (stop_suspension_request_id IS NULL OR (stop_restored_at IS NOT NULL AND stop_reopened_at>=stop_restored_at))));

ALTER TABLE order_refund
    DROP CHECK chk_order_refund_review,
    ADD CONSTRAINT chk_order_refund_review CHECK (
        (review_request_id IS NULL AND review_decision IS NULL AND review_operator_subject IS NULL
            AND review_reason IS NULL AND reviewed_at IS NULL AND status<>'REJECTED')
        OR (review_request_id IS NOT NULL AND review_decision IS NOT NULL AND review_operator_subject IS NOT NULL
            AND review_reason IS NOT NULL AND reviewed_at IS NOT NULL AND automatic_refund_eligible=FALSE
            AND CHAR_LENGTH(review_request_id)=36 AND CHAR_LENGTH(TRIM(review_operator_subject))>0
            AND CHAR_LENGTH(TRIM(review_reason))>0 AND reviewed_at>=received_at
            AND ((current_review_id=refund_id AND ((review_decision='APPROVED' AND status IN ('PENDING_LICENSE_GRANT',
                    'PENDING_LICENSE_SUSPENSION','PENDING_PAYMENT_REFUND','PENDING_LICENSE_RESTORATION','FAILED','COMPLETED','PENDING_APPROVAL_STOP'))
                OR (review_decision='REJECTED' AND status='REJECTED' AND suspension_request_event_id IS NULL
                    AND current_attempt_id IS NULL AND payment_confirmation_event_id IS NULL)))
                OR (current_review_id<>refund_id AND review_decision IN ('REJECTED','APPROVED'))))),
    ADD CONSTRAINT chk_refund_approval_stop_stage CHECK (status<>'PENDING_APPROVAL_STOP'
        OR (automatic_refund_eligible=FALSE AND current_attempt_id IS NULL AND payment_confirmation_event_id IS NULL));
