-- V17 적용 직전 데이터 규모와 기존 인덱스를 확인함

SELECT installed_rank,
       version,
       description,
       success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 5;

SELECT (SELECT COUNT(*) FROM orders)     AS orders_count,
       (SELECT COUNT(*) FROM order_item) AS order_items_count;

SELECT table_class.relname              AS table_name,
       index_class.relname              AS index_name,
       index_state.indisvalid           AS is_valid,
       index_state.indisready           AS is_ready,
       pg_get_indexdef(index_class.oid) AS index_definition
FROM pg_index index_state
JOIN pg_class table_class
  ON table_class.oid = index_state.indrelid
JOIN pg_namespace namespace
  ON namespace.oid = table_class.relnamespace
JOIN pg_class index_class
  ON index_class.oid = index_state.indexrelid
WHERE namespace.nspname = current_schema()
  AND table_class.relname IN ('orders', 'order_item')
ORDER BY table_class.relname, index_class.relname;

-- 두 값이 모두 0이어야 V17을 적용할 수 있음
SELECT 'orders(member_id, order_date DESC)' AS candidate,
       COUNT(*)                             AS existing_count
FROM pg_index index_state
JOIN pg_class table_class
  ON table_class.oid = index_state.indrelid
JOIN pg_namespace namespace
  ON namespace.oid = table_class.relnamespace
JOIN pg_class index_class
  ON index_class.oid = index_state.indexrelid
JOIN pg_am access_method
  ON access_method.oid = index_class.relam
WHERE namespace.nspname = current_schema()
  AND table_class.relname = 'orders'
  AND access_method.amname = 'btree'
  AND index_state.indisunique = false
  AND index_state.indisprimary = false
  AND index_state.indpred IS NULL
  AND index_state.indexprs IS NULL
  AND index_state.indnkeyatts = 2
  AND index_state.indnatts = 2
  AND pg_get_indexdef(index_state.indexrelid, 1, true) = 'member_id'
  AND pg_get_indexdef(index_state.indexrelid, 2, true)
      IN ('order_date DESC', 'order_date DESC NULLS FIRST')

UNION ALL

SELECT 'order_item(order_id)',
       COUNT(*)
FROM pg_index index_state
JOIN pg_class table_class
  ON table_class.oid = index_state.indrelid
JOIN pg_namespace namespace
  ON namespace.oid = table_class.relnamespace
JOIN pg_class index_class
  ON index_class.oid = index_state.indexrelid
JOIN pg_am access_method
  ON access_method.oid = index_class.relam
WHERE namespace.nspname = current_schema()
  AND table_class.relname = 'order_item'
  AND access_method.amname = 'btree'
  AND index_state.indisunique = false
  AND index_state.indisprimary = false
  AND index_state.indpred IS NULL
  AND index_state.indexprs IS NULL
  AND index_state.indnkeyatts = 1
  AND index_state.indnatts = 1
  AND pg_get_indexdef(index_state.indexrelid, 1, true) = 'order_id';
