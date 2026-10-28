ALTER TABLE refund_execution
    ADD COLUMN preparation_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_refund_preparation_event (preparation_event_id),
    ADD CONSTRAINT chk_refund_preparation_event CHECK (preparation_event_id IS NULL OR first_cancel_prepared_at IS NOT NULL);

-- Publish only existing preparation facts. Do not move first preparation, financial state or schedules.
UPDATE refund_execution SET preparation_event_id=UUID()
WHERE first_cancel_prepared_at IS NOT NULL AND preparation_event_id IS NULL;

INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,trace_parent,status,retry_count,created_at)
SELECT e.preparation_event_id,'Payment',e.order_no,'RefundExecutionPrepared','stove.payment.v1',e.order_no,
    JSON_OBJECT('eventId',e.preparation_event_id,'occurredAt',DATE_FORMAT(e.first_cancel_prepared_at,'%Y-%m-%dT%H:%i:%s.%fZ'),
        'refundId',e.refund_id,'attemptId',e.attempt_id,'requestEventId',e.request_event_id,'orderNo',e.order_no,
        'paymentId',e.payment_id,'buyerSubject',e.buyer_subject,'amount',e.amount,'currency',e.currency,
        'licenseSuspensionEventId',e.license_suspension_event_id,'previousAttemptId',e.previous_attempt_id,
        'firstPreparedAt',DATE_FORMAT(e.first_cancel_prepared_at,'%Y-%m-%dT%H:%i:%s.%fZ'),
        'confirmationDelayAt',DATE_FORMAT(DATE_ADD(e.first_cancel_prepared_at,INTERVAL 3 HOUR),'%Y-%m-%dT%H:%i:%s.%fZ')),
    t.trace_parent,'PENDING',0,CURRENT_TIMESTAMP(6)
FROM refund_execution e LEFT JOIN refund_execution_trace t ON t.attempt_id=e.attempt_id
WHERE e.preparation_event_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM outbox_event o WHERE o.event_id=e.preparation_event_id);
