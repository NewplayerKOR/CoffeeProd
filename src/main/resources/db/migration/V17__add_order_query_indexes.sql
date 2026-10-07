-- 기존 이름 충돌과 동일 정의 중복을 사전 차단함
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_class index_class
        JOIN pg_namespace namespace
          ON namespace.oid = index_class.relnamespace
        WHERE namespace.nspname = current_schema()
          AND index_class.relkind = 'i'
          AND index_class.relname IN (
              'idx_orders_member_order_date_desc',
              'idx_order_item_order_id'
          )
    ) THEN
        RAISE EXCEPTION 'V17 대상 인덱스 이름이 이미 존재합니다. 정의와 유효 상태를 확인하십시오.';
    END IF;

    IF EXISTS (
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
    ) THEN
        RAISE EXCEPTION 'orders(member_id, order_date DESC)와 동일한 인덱스가 이미 존재합니다.';
    END IF;

    IF EXISTS (
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
          AND access_method.amname = 'btree'
          AND index_state.indisunique = false
          AND index_state.indisprimary = false
          AND index_state.indpred IS NULL
          AND index_state.indexprs IS NULL
          AND index_state.indnkeyatts = 1
          AND index_state.indnatts = 1
          AND pg_get_indexdef(index_state.indexrelid, 1, true) = 'order_id'
    ) THEN
        RAISE EXCEPTION 'order_item(order_id)와 동일한 인덱스가 이미 존재합니다.';
    END IF;
END
$$;

-- 잠금 대기와 전체 수행 시간을 제한함
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

-- 회원별 최신 주문 ID와 count 조회를 지원함
CREATE INDEX idx_orders_member_order_date_desc
    ON orders USING btree (member_id, order_date DESC);

-- 선택된 주문의 주문상품 조회를 지원함
CREATE INDEX idx_order_item_order_id
    ON order_item USING btree (order_id);
