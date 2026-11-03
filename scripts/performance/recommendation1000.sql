\set ON_ERROR_STOP on
BEGIN;
DO $$ BEGIN
  IF current_database() <> 'coffeeprod_perf_phase2' OR current_user <> 'perf_phase2' THEN
    RAISE EXCEPTION 'Dedicated phase2 database required';
  END IF;
END $$;
WITH source AS (
  SELECT p.*, row_number() OVER (ORDER BY product_id) n
  FROM product p WHERE status='ON_SALE' AND stock_quantity>0 AND coffee_profile_id IS NOT NULL
), needed AS (
  SELECT greatest(0,1000-count(*))::integer n,count(*)::integer available FROM source
)
INSERT INTO product(category_id,coffee_profile_id,sku,weight_grams,name,price,stock_quantity,roast_level,description,image_url,status,created_at,updated_at)
SELECT s.category_id,s.coffee_profile_id,'PERF2-REC-'||lpad(g.i::text,6,'0'),s.weight_grams,
       'PERF2 '||s.name,s.price,1000,s.roast_level,'Synthetic recommendation fixture',s.image_url,'ON_SALE',now(),now()
FROM needed CROSS JOIN LATERAL generate_series(1,needed.n) g(i)
JOIN source s ON s.n=1+mod(g.i-1,needed.available);
DO $$ BEGIN
  IF (SELECT count(*) FROM product WHERE status='ON_SALE' AND stock_quantity>0 AND coffee_profile_id IS NOT NULL)<>1000 THEN
    RAISE EXCEPTION 'Expected exactly 1000 eligible recommendation candidates';
  END IF;
END $$;
COMMIT;
