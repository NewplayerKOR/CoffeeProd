-- Read-only diagnostics. Any rows require investigation; never infer prior resource restoration.
SELECT o.order_id, o.status AS order_status, p.status AS payment_status,
       CASE WHEN p.payment_id IS NULL THEN 'MISSING_PAYMENT'
            WHEN o.status = 'CANCELED' AND p.status = 'SUCCESS' THEN 'CANCELED_WITH_SUCCESS'
            WHEN o.status IN ('PAID','SHIPPED','DELIVERED') AND p.status <> 'SUCCESS' THEN 'PAYMENT_STATE_MISMATCH'
       END AS issue
FROM orders o LEFT JOIN payment p ON p.order_id = o.order_id
WHERE (o.status IN ('PAID','SHIPPED','DELIVERED') AND (p.payment_id IS NULL OR p.status <> 'SUCCESS'))
   OR (o.status = 'CANCELED' AND p.status = 'SUCCESS');
SELECT member_id, mileage FROM member WHERE mileage < 0;
SELECT p.payment_id, p.order_id FROM payment p LEFT JOIN orders o ON o.order_id = p.order_id
WHERE o.order_id IS NULL OR p.payment_key IS NULL OR btrim(p.payment_key) = '';
-- Payment has no historical amount snapshot. Compare orders.total_price/key with PG lookup;
-- this query cannot establish external payment amount equality.
