-- Flyway V17과 주문 조회 인덱스의 유효 상태를 검증함

DO $$
DECLARE
    orders_index_count INTEGER;
    order_item_index_count INTEGER;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM flyway_schema_history
        WHERE version = '17'
          AND success = true
    ) THEN
        RAISE EXCEPTION '성공한 Flyway V17 이력이 없습니다.';
    END IF;

    IF NOT EXISTS (
        SELECT 1
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
          AND index_class.relname = 'idx_orders_member_order_date_desc'
          AND access_method.amname = 'btree'
          AND index_state.indisvalid = true
          AND index_state.indisready = true
          AND index_state.indisunique = false
          AND index_state.indisprimary = false
          AND index_state.indpred IS NULL
          AND index_state.indexprs IS NULL
          AND index_state.indnkeyatts = 2
          AND index_state.indnatts = 2
          AND pg_get_indexdef(index_state.indexrelid, 1, true) = 'member_id'
          AND pg_get_indexdef(index_state.indexrelid, 2, true)
              IN ('order_date DESC', 'order_date DESC NULLS FIRST')
    ) THEN
        RAISE EXCEPTION 'idx_orders_member_order_date_desc 정의 또는 상태가 올바르지 않습니다.';
    END IF;

    IF NOT EXISTS (
        SELECT 1
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
          AND index_class.relname = 'idx_order_item_order_id'
          AND access_method.amname = 'btree'
          AND index_state.indisvalid = true
          AND index_state.indisready = true
          AND index_state.indisunique = false
          AND index_state.indisprimary = false
          AND index_state.indpred IS NULL
          AND index_state.indexprs IS NULL
          AND index_state.indnkeyatts = 1
          AND index_state.indnatts = 1
          AND pg_get_indexdef(index_state.indexrelid, 1, true) = 'order_id'
    ) THEN
        RAISE EXCEPTION 'idx_order_item_order_id 정의 또는 상태가 올바르지 않습니다.';
    END IF;

    SELECT COUNT(*)
    INTO orders_index_count
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
          IN ('order_date DESC', 'order_date DESC NULLS FIRST');

    SELECT COUNT(*)
    INTO order_item_index_count
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

    IF orders_index_count <> 1 OR order_item_index_count <> 1 THEN
        RAISE EXCEPTION
            '주문 조회 인덱스가 누락되었거나 중복되었습니다. orders=%, order_item=%',
            orders_index_count,
            order_item_index_count;
    END IF;
END
$$;

SELECT history.installed_rank,
       history.version,
       history.description,
       history.installed_on,
       history.execution_time,
       history.success
FROM flyway_schema_history history
WHERE history.version = '17';

SELECT table_class.relname              AS table_name,
       index_class.relname              AS index_name,
       index_state.indisvalid           AS is_valid,
       index_state.indisready           AS is_ready,
       pg_get_indexdef(index_class.oid) AS index_definition,
       pg_size_pretty(pg_relation_size(index_class.oid)) AS index_size
FROM pg_index index_state
JOIN pg_class table_class
  ON table_class.oid = index_state.indrelid
JOIN pg_namespace namespace
  ON namespace.oid = table_class.relnamespace
JOIN pg_class index_class
  ON index_class.oid = index_state.indexrelid
WHERE namespace.nspname = current_schema()
  AND index_class.relname IN (
      'idx_orders_member_order_date_desc',
      'idx_order_item_order_id'
  )
ORDER BY index_class.relname;
