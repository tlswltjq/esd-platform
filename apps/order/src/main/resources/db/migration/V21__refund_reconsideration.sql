ALTER TABLE order_refund
    DROP CHECK chk_order_refund_review,
    ADD COLUMN current_review_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_order_refund_current_review (current_review_id);

UPDATE order_refund SET current_review_id=refund_id WHERE automatic_refund_eligible=FALSE;

CREATE TABLE order_refund_review_round (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    review_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    refund_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_no BIGINT NOT NULL,
    application_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    previous_review_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    buyer_subject VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    customer_reason VARCHAR(500) NULL,
    received_at DATETIME(6) NOT NULL,
    decision_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    decision VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operator_subject VARCHAR(100) COLLATE utf8mb4_bin NULL,
    decision_reason VARCHAR(500) NULL,
    decided_at DATETIME(6) NULL,
    UNIQUE KEY uk_refund_review_id (review_id),
    UNIQUE KEY uk_refund_review_no (refund_id, review_no),
    UNIQUE KEY uk_refund_review_application (application_request_id),
    UNIQUE KEY uk_refund_review_previous (previous_review_id),
    UNIQUE KEY uk_refund_review_decision (decision_request_id),
    CONSTRAINT fk_refund_review_refund FOREIGN KEY (refund_id) REFERENCES order_refund(refund_id) ON DELETE CASCADE,
    CONSTRAINT chk_refund_review_application CHECK (
        CHAR_LENGTH(TRIM(buyer_subject))>0 AND ((review_no=1 AND review_id=refund_id
            AND application_request_id IS NULL AND previous_review_id IS NULL AND customer_reason IS NULL)
        OR (review_no>1 AND application_request_id IS NOT NULL AND previous_review_id IS NOT NULL
            AND previous_review_id<>review_id AND customer_reason IS NOT NULL AND CHAR_LENGTH(TRIM(customer_reason))>0))),
    CONSTRAINT chk_refund_review_decision CHECK (
        (decision_request_id IS NULL AND decision IS NULL AND operator_subject IS NULL AND decision_reason IS NULL AND decided_at IS NULL)
        OR (decision_request_id IS NOT NULL AND decision IS NOT NULL AND operator_subject IS NOT NULL
            AND decision_reason IS NOT NULL AND decided_at IS NOT NULL AND decision IN ('APPROVED','REJECTED')
            AND CHAR_LENGTH(TRIM(operator_subject))>0 AND CHAR_LENGTH(TRIM(decision_reason))>0 AND decided_at>=received_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Copy only known facts: a legacy missing decision remains missing, without fabricated actors or reasons.
INSERT INTO order_refund_review_round(review_id,refund_id,review_no,buyer_subject,received_at,
    decision_request_id,decision,operator_subject,decision_reason,decided_at)
SELECT r.refund_id,r.refund_id,1,o.buyer_subject,r.received_at,
    r.review_request_id,r.review_decision,r.review_operator_subject,r.review_reason,r.reviewed_at
FROM order_refund r JOIN purchase_order o ON o.order_no=r.order_no WHERE r.automatic_refund_eligible=FALSE;

-- The previous-review link is checked under the order lock. Avoid a cascading self-FK depth limit for unlimited rounds.
ALTER TABLE order_refund
    ADD CONSTRAINT chk_order_refund_current_review CHECK (
        (automatic_refund_eligible=TRUE AND current_review_id IS NULL)
        OR (automatic_refund_eligible=FALSE AND current_review_id IS NOT NULL)),
    ADD CONSTRAINT chk_order_refund_review CHECK (
        (review_request_id IS NULL AND review_decision IS NULL AND review_operator_subject IS NULL
            AND review_reason IS NULL AND reviewed_at IS NULL AND status<>'REJECTED')
        OR (review_request_id IS NOT NULL AND review_decision IS NOT NULL AND review_operator_subject IS NOT NULL
            AND review_reason IS NOT NULL AND reviewed_at IS NOT NULL AND automatic_refund_eligible=FALSE
            AND CHAR_LENGTH(review_request_id)=36 AND CHAR_LENGTH(TRIM(review_operator_subject))>0
            AND CHAR_LENGTH(TRIM(review_reason))>0 AND reviewed_at>=received_at
            AND ((current_review_id=refund_id AND ((review_decision='APPROVED' AND status IN ('PENDING_LICENSE_GRANT',
                    'PENDING_LICENSE_SUSPENSION','PENDING_PAYMENT_REFUND','PENDING_LICENSE_RESTORATION','FAILED','COMPLETED'))
                OR (review_decision='REJECTED' AND status='REJECTED' AND suspension_request_event_id IS NULL
                    AND current_attempt_id IS NULL AND payment_confirmation_event_id IS NULL)))
                OR (current_review_id<>refund_id AND review_decision='REJECTED'))));
