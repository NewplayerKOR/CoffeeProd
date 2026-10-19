-- Flyway V17과 주문 조회 인덱스의 유효 상태를 검증함

-- 오류 발생 전에도 실제 정의와 판정 속성을 출력함
SELECT table_class.relname              AS table_name,
       index_class.relname              AS index_name,
       access_method.amname             AS access_method,
       index_state.indnkeyatts           AS key_count,
       index_state.indnatts              AS total_column_count,
       index_state.indisunique           AS is_unique,
       index_state.indpred IS NOT NULL   AS is_partial,
       index_state.indexprs IS NOT NULL  AS is_expression,
       index_state.indisvalid            AS is_valid,
       index_state.indisready            AS is_ready,
       pg_get_indexdef(index_class.oid, 1, true) AS first_key,
       pg_get_indexdef(index_class.oid, 2, true) AS second_key,
       pg_index_column_has_property(index_class.oid, 1, 'asc') AS first_is_asc,
       pg_index_column_has_property(index_class.oid, 1, 'nulls_last') AS first_nulls_last,
       pg_index_column_has_property(index_class.oid, 2, 'desc') AS second_is_desc,
       pg_index_column_has_property(index_class.oid, 2, 'nulls_first') AS second_nulls_first,
       pg_get_indexdef(index_class.oid)   AS index_definition,
       pg_size_pretty(pg_relation_size(index_class.oid)) AS index_size
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
  AND table_class.relname IN ('orders', 'order_item')
ORDER BY table_class.relname, index_class.relname;

-- 컬럼명과 정렬 방향 및 NULL 정렬을 각각 검사함
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
          AND pg_get_indexdef(index_state.indexrelid, 2, true) = 'order_date'
          AND pg_index_column_has_property(index_state.indexrelid, 1, 'asc') IS TRUE
          AND pg_index_column_has_property(index_state.indexrelid, 1, 'nulls_last') IS TRUE
          AND pg_index_column_has_property(index_state.indexrelid, 2, 'desc') IS TRUE
          AND pg_index_column_has_property(index_state.indexrelid, 2, 'nulls_first') IS TRUE
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
          AND pg_index_column_has_property(index_state.indexrelid, 1, 'asc') IS TRUE
          AND pg_index_column_has_property(index_state.indexrelid, 1, 'nulls_last') IS TRUE
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
      AND pg_get_indexdef(index_state.indexrelid, 2, true) = 'order_date'
      AND pg_index_column_has_property(index_state.indexrelid, 1, 'asc') IS TRUE
      AND pg_index_column_has_property(index_state.indexrelid, 1, 'nulls_last') IS TRUE
      AND pg_index_column_has_property(index_state.indexrelid, 2, 'desc') IS TRUE
      AND pg_index_column_has_property(index_state.indexrelid, 2, 'nulls_first') IS TRUE;

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
      AND pg_get_indexdef(index_state.indexrelid, 1, true) = 'order_id'
      AND pg_index_column_has_property(index_state.indexrelid, 1, 'asc') IS TRUE
      AND pg_index_column_has_property(index_state.indexrelid, 1, 'nulls_last') IS TRUE;

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
