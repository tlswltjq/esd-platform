-- Read-only checks for the V10 Order / V4 License -> V11 / V5 transition.
-- Run against the intended database with business writes paused. Every finding
-- needs investigation; this script never deletes or repairs business records.
-- Also inspect Kafka backlog/DLT/replay sources before resuming consumers.
USE stove_order;

SELECT 'duplicate_active_product_id' AS finding, buyer_subject, product_id,
       COUNT(*) AS purchases, GROUP_CONCAT(order_no ORDER BY id) AS order_numbers
FROM purchase_order WHERE status IN ('PENDING', 'PAID')
GROUP BY buyer_subject, product_id HAVING COUNT(*) > 1;

SELECT 'duplicate_active_product_code' AS finding, buyer_subject, product_code,
       COUNT(*) AS purchases, GROUP_CONCAT(order_no ORDER BY id) AS order_numbers
FROM purchase_order WHERE status IN ('PENDING', 'PAID')
GROUP BY buyer_subject, product_code HAVING COUNT(*) > 1;

SELECT 'pending_paid_outbox' AS finding, event_id, aggregate_id AS order_no, status
FROM outbox_event WHERE event_type = 'OrderPaid' AND status <> 'SENT';

USE stove_license;

SELECT 'license_source_count' AS finding, l.id AS license_id, l.license_id AS public_id,
       COUNT(p.id) AS sources
FROM product_license l LEFT JOIN license_purchase p ON p.license_id = l.id
GROUP BY l.id, l.license_id HAVING COUNT(p.id) <> 1;

SELECT 'license_source_mismatch' AS finding, l.id AS license_id, p.order_no
FROM product_license l JOIN license_purchase p ON p.license_id = l.id
WHERE BINARY l.buyer_subject <> BINARY p.buyer_subject OR l.product_id <> p.product_id
   OR BINARY l.product_code <> BINARY p.product_code OR BINARY l.title <> BINARY p.title;

SELECT 'source_without_matching_inbox' AS finding, p.order_no, p.event_id
FROM license_purchase p LEFT JOIN processed_event e
    ON BINARY e.event_id = BINARY p.event_id AND BINARY e.consumer_group = BINARY 'license-paid-order'
WHERE e.id IS NULL OR BINARY e.event_type <> BINARY 'OrderPaid';

SELECT 'inbox_without_source' AS finding, e.event_id
FROM processed_event e
WHERE BINARY e.consumer_group = BINARY 'license-paid-order'
  AND NOT EXISTS (SELECT 1 FROM license_purchase p WHERE BINARY p.event_id = BINARY e.event_id);
