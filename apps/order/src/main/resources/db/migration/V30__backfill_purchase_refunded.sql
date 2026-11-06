-- 결정 99(PR #116 리뷰 R2): PurchaseRefunded가 생기기 전에 끝난 환불도 License가 구매를 끝내도록,
-- 환불마다 같은 내용의 PurchaseRefunded를 Outbox에 한 번 채운다. 릴레이가 보내고 License(소비 그룹
-- license-purchase-refund, earliest)가 받는다. License의 환불 완료 처리는 재전달에 멱등하다.
-- 본문은 ApplyRefundPaymentConfirmation이 남기는 PurchaseRefundedEvent와 같은 필드다.
-- 이벤트 ID는 환불 ID로 정해 다시 실행해도 같은 행이며, 이미 PurchaseRefunded가 있는 주문은 건너뛴다.
INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, event_type, topic, partition_key, payload, status, created_at)
SELECT CONCAT('purchase-refunded-', r.refund_id), 'Order', o.order_no, 'PurchaseRefunded', 'stove.order.v1', o.order_no,
       JSON_OBJECT(
           'eventId', CONCAT('purchase-refunded-', r.refund_id),
           'occurredAt', DATE_FORMAT(r.completed_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
           'refundId', r.refund_id,
           'licenseId', a.license_id,
           'orderNo', o.order_no,
           'buyerSubject', o.buyer_subject,
           'productId', o.product_id,
           'productCode', o.product_code,
           'paymentId', o.payment_id,
           'suspensionRequestEventId', a.suspension_request_event_id),
       'PENDING', NOW(6)
FROM order_refund r
JOIN purchase_order o ON o.order_no = r.order_no
JOIN order_refund_attempt a ON a.attempt_id = r.current_attempt_id
WHERE r.status = 'COMPLETED'
  AND r.completed_at IS NOT NULL
  AND a.license_id IS NOT NULL
  AND a.suspension_request_event_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM outbox_event e WHERE e.event_type = 'PurchaseRefunded' AND e.aggregate_id = o.order_no);
