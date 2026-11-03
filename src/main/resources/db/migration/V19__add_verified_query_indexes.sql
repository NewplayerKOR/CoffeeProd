-- Bound DDL waits; Flyway applies this migration atomically.
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

-- Refuse name conflicts and equivalent B-tree keys, including differently named
-- indexes with INCLUDE columns. Inspect sort properties separately from key names.
DO $$
DECLARE
    candidate RECORD;
    equivalent_name TEXT;
BEGIN
    FOR candidate IN
        SELECT * FROM (VALUES
            ('idx_reviews_product_created_at', 'reviews', ARRAY['product_id', 'created_at'], ARRAY[FALSE, TRUE]),
            ('idx_product_qna_product_created_at', 'product_qna', ARRAY['product_id', 'created_at'], ARRAY[FALSE, TRUE])
        ) AS definitions(index_name, table_name, key_columns, descending_flags)
    LOOP
        IF EXISTS (
            SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = current_schema() AND c.relname = candidate.index_name
        ) THEN
            RAISE EXCEPTION 'V19 index name conflict: %', candidate.index_name;
        END IF;

        SELECT x.relname INTO equivalent_name
        FROM pg_index i
        JOIN pg_class t ON t.oid = i.indrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        JOIN pg_class x ON x.oid = i.indexrelid
        JOIN pg_am am ON am.oid = x.relam
        WHERE n.nspname = current_schema() AND t.relname = candidate.table_name
          AND am.amname = 'btree' AND i.indpred IS NULL AND i.indexprs IS NULL
          AND i.indnkeyatts = cardinality(candidate.key_columns)
          AND NOT EXISTS (
              SELECT 1 FROM generate_subscripts(candidate.key_columns, 1) AS positions(key_position)
              WHERE pg_get_indexdef(i.indexrelid, key_position, TRUE) IS DISTINCT FROM candidate.key_columns[key_position]
                 OR pg_index_column_has_property(i.indexrelid, key_position, 'desc') IS DISTINCT FROM candidate.descending_flags[key_position]
                 OR pg_index_column_has_property(i.indexrelid, key_position, 'nulls_first') IS DISTINCT FROM candidate.descending_flags[key_position]
          )
        LIMIT 1;
        IF equivalent_name IS NOT NULL THEN
            RAISE EXCEPTION 'V19 equivalent index already exists: % (candidate %)', equivalent_name, candidate.index_name;
        END IF;
    END LOOP;
END
$$;

CREATE INDEX idx_reviews_product_created_at
    ON reviews USING btree (product_id, created_at DESC);

CREATE INDEX idx_product_qna_product_created_at
    ON product_qna USING btree (product_id, created_at DESC);
