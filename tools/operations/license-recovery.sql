-- MySQL 8. 사전에 SET @order_no = '대상 UUID'; 를 지정하고 실행한다.
-- 같은 인스턴스의 stove_order / stove_license를 읽을 수 있는 조회 전용 계정을 사용한다.
-- 이 결과는 운영 대조 자료다. 지급·정지 상태를 변경하거나 복구 여부를 자동 결정하지 않는다.
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

SELECT @order_no AS requested_order_no,
       o.status AS order_status, o.payment_id,
       l.license_id, l.status AS license_status, l.event_id AS paid_event_id,
       g.event_id AS confirmed_grant_event_id, g.license_id AS confirmed_license_id,
       (BINARY l.buyer_subject = BINARY o.buyer_subject) AS same_buyer,
       (l.product_id = o.product_id AND BINARY l.product_code = BINARY o.product_code) AS same_product,
       (BINARY l.payment_id = BINARY o.payment_id
        AND BINARY l.request_event_id = BINARY o.request_event_id
        AND BINARY l.payment_result_event_id = BINARY o.payment_result_event_id) AS same_payment_source,
       (l.amount = o.amount AND BINARY l.currency = BINARY o.currency
        AND l.content_version = o.content_version AND BINARY l.title = BINARY o.title) AS same_purchase_snapshot,
       (BINARY g.license_id = BINARY l.license_id AND BINARY g.payment_id = BINARY l.payment_id
        AND BINARY g.buyer_subject = BINARY l.buyer_subject AND g.product_id = l.product_id
        AND BINARY g.product_code = BINARY l.product_code) AS same_confirmation,
       r.refund_id, r.status AS refund_status
  FROM stove_order.purchase_order o
  LEFT JOIN stove_license.product_license l ON BINARY l.order_no = BINARY o.order_no
  LEFT JOIN stove_order.order_license_grant g ON BINARY g.order_no = BINARY o.order_no
  LEFT JOIN stove_order.order_refund r ON BINARY r.order_no = BINARY o.order_no
 WHERE o.order_no = @order_no;

SELECT 'order' AS source_module, e.event_id, e.event_type, e.aggregate_id,
       e.topic, e.partition_key, e.status, e.retry_count, e.last_error, e.created_at, e.sent_at,
       e.trace_parent, p.processed_at AS receiver_processed_at
  FROM stove_order.outbox_event e
  LEFT JOIN stove_license.processed_event p
    ON p.event_id = e.event_id AND p.consumer_group = 'license-paid-order'
 WHERE e.aggregate_id = @order_no AND e.event_type = 'OrderPaid'
UNION ALL
SELECT 'license', e.event_id, e.event_type, e.aggregate_id,
       e.topic, e.partition_key, e.status, e.retry_count, e.last_error, e.created_at, e.sent_at,
       e.trace_parent, p.processed_at
  FROM stove_license.outbox_event e
  LEFT JOIN stove_order.processed_event p
    ON p.event_id = e.event_id AND p.consumer_group = 'order-license-grant'
 WHERE e.aggregate_id IN (SELECT license_id FROM stove_license.product_license WHERE order_no = @order_no)
   AND e.event_type = 'LicenseGranted';

COMMIT;
