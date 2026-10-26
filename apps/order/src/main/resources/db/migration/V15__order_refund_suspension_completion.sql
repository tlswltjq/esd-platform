ALTER TABLE order_refund
    ADD COLUMN suspension_request_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN current_attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_order_refund_suspension_request (suspension_request_event_id),
    ADD UNIQUE KEY uk_order_refund_current_attempt (current_attempt_id);

ALTER TABLE order_refund_attempt
    ADD COLUMN suspension_request_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN suspension_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN suspension_occurred_at DATETIME(6) NULL,
    ADD COLUMN license_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN execution_request_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uk_order_refund_attempt_suspension_request (suspension_request_event_id),
    ADD UNIQUE KEY uk_order_refund_attempt_suspension_result (suspension_event_id),
    ADD UNIQUE KEY uk_order_refund_attempt_execution_request (execution_request_event_id);

-- 이전 접수의 원래 요청 ID를 검증된 Outbox 한 건에서만 복원한다.
-- 근거가 없거나 여러 건이면 NULL을 유지한다. 새 ID를 추정 발급하거나 완료로 간주하지 않는다.
UPDATE order_refund r
JOIN (
    SELECT previous_refund.id, MIN(e.event_id) AS request_event_id
    FROM order_refund previous_refund
    JOIN purchase_order p ON p.order_no = previous_refund.order_no AND p.status = 'PAID'
    JOIN order_license_grant g ON g.order_no = p.order_no
        AND g.buyer_subject = p.buyer_subject AND g.product_id = p.product_id
        AND g.product_code = p.product_code AND g.payment_id = p.payment_id
    JOIN outbox_event e ON e.event_type = 'LicenseSuspensionRequested'
        AND e.aggregate_type = 'Order' AND e.aggregate_id = p.order_no
        AND e.topic = 'stove.order.v1' AND e.partition_key = p.order_no
        AND JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.eventId')) = e.event_id
        AND JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.refundId')) = previous_refund.refund_id
        AND JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.orderNo')) = p.order_no
        AND JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.buyerSubject')) = p.buyer_subject
        AND JSON_TYPE(JSON_EXTRACT(e.payload, '$.productId')) = 'INTEGER'
        AND JSON_EXTRACT(e.payload, '$.productId') = p.product_id
        AND JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.productCode')) = p.product_code
        AND JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.paymentId')) = p.payment_id
    WHERE previous_refund.status = 'PENDING_LICENSE_SUSPENSION'
    GROUP BY previous_refund.id
    HAVING COUNT(*) = 1
) known ON known.id = r.id
SET r.suspension_request_event_id = known.request_event_id;
