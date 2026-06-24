-- V2 적용 전 위반 데이터를 탐지함
-- violation_count가 모두 0이어야 함

SELECT 'orders.member_id IS NULL' AS check_name,
       COUNT(*)                   AS violation_count
FROM orders
WHERE member_id IS NULL

UNION ALL

SELECT 'orders.order_data IS NULL',
       COUNT(*)
FROM orders
WHERE order_data IS NULL

UNION ALL

SELECT 'product.category_id IS NULL',
       COUNT(*)
FROM product
WHERE category_id IS NULL

UNION ALL

SELECT 'order_item.order_id IS NULL',
       COUNT(*)
FROM order_item
WHERE order_id IS NULL

UNION ALL

SELECT 'order_item.product_id IS NULL',
       COUNT(*)
FROM order_item
WHERE product_id IS NULL

UNION ALL

SELECT 'payment.order_id IS NULL',
       COUNT(*)
FROM payment
WHERE order_id IS NULL

UNION ALL

SELECT 'duplicate payment.order_id',
       COUNT(*)
FROM (SELECT order_id
      FROM payment
      WHERE order_id IS NOT NULL
      GROUP BY order_id
      HAVING COUNT(*) > 1) duplicated_payment_order

UNION ALL

SELECT 'duplicate payment.payment_key',
       COUNT(*)
FROM (SELECT payment_key
      FROM payment
      GROUP BY payment_key
      HAVING COUNT(*) > 1) duplicated_payment_key

UNION ALL

SELECT 'product.price <= 0',
       COUNT(*)
FROM product
WHERE price <= 0

UNION ALL

SELECT 'product.stock_quantity < 0',
       COUNT(*)
FROM product
WHERE stock_quantity < 0

UNION ALL

SELECT 'reviews.rating outside 1..5',
       COUNT(*)
FROM reviews
WHERE rating < 1
   OR rating > 5

UNION ALL

SELECT 'invalid product_qna answer state',
       COUNT(*)
FROM product_qna
WHERE (
    status = 'WAITING'
        AND (
        answer IS NOT NULL
            OR answerer_id IS NOT NULL
            OR answered_at IS NOT NULL
        )
    )
   OR (
    status = 'ANSWERED'
        AND (
        answer IS NULL
            OR BTRIM(answer) = ''
            OR answerer_id IS NULL
            OR answered_at IS NULL
        )
    );