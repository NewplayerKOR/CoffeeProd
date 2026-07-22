\set ON_ERROR_STOP on

-- 적재된 카탈로그의 고정 규모와 관계 정합성을 검증함
DO $$
BEGIN
    IF (
        SELECT COUNT(*)
        FROM coffee_profile
        WHERE catalog_key IS NOT NULL
    ) <> 96 THEN
        RAISE EXCEPTION '카탈로그 프로필은 96개여야 합니다.';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM product product
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = product.coffee_profile_id
        WHERE profile.catalog_key IS NOT NULL
    ) <> 192 THEN
        RAISE EXCEPTION '카탈로그 상품은 192개여야 합니다.';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM coffee_profile_component component
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = component.coffee_profile_id
        WHERE profile.catalog_key IS NOT NULL
    ) <> 68 THEN
        RAISE EXCEPTION '블렌드 구성요소는 68개여야 합니다.';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM coffee_profile_flavor_note relation
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = relation.coffee_profile_id
        WHERE profile.catalog_key IS NOT NULL
    ) <> 288 THEN
        RAISE EXCEPTION '향미 연결은 288개여야 합니다.';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM coffee_profile_brew_method relation
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = relation.coffee_profile_id
        WHERE profile.catalog_key IS NOT NULL
    ) <> 192 THEN
        RAISE EXCEPTION '추출법 연결은 192개여야 합니다.';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM coffee_profile_variety relation
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = relation.coffee_profile_id
        WHERE profile.catalog_key IS NOT NULL
    ) <> 192 THEN
        RAISE EXCEPTION '품종 연결은 192개여야 합니다.';
    END IF;

    IF EXISTS (
        SELECT profile.coffee_profile_id
        FROM coffee_profile profile
        LEFT JOIN coffee_profile_component component
          ON component.coffee_profile_id = profile.coffee_profile_id
        WHERE profile.catalog_key IS NOT NULL
          AND profile.bean_type = 'BLEND'
        GROUP BY profile.coffee_profile_id
        HAVING COUNT(component.coffee_profile_component_id) NOT BETWEEN 2 AND 5
           OR SUM(component.component_ratio) <> 100
    ) THEN
        RAISE EXCEPTION '블렌드 구성 개수 또는 비율 합계가 올바르지 않습니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM coffee_profile profile
        JOIN coffee_profile_component component
          ON component.coffee_profile_id = profile.coffee_profile_id
        WHERE profile.catalog_key IS NOT NULL
          AND profile.bean_type = 'SINGLE_ORIGIN'
    ) THEN
        RAISE EXCEPTION '단일 원산지 프로필에 블렌드 구성요소가 존재합니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM product product
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = product.coffee_profile_id
        WHERE profile.catalog_key IS NOT NULL
          AND product.roast_level <> profile.roast_level
    ) THEN
        RAISE EXCEPTION '상품과 프로필의 로스트 단계가 일치하지 않습니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM product product
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = product.coffee_profile_id
        WHERE profile.catalog_key IS NOT NULL
          AND product.status = 'ON_SALE'
          AND product.stock_quantity <= 0
    ) THEN
        RAISE EXCEPTION '판매 중인 상품의 재고가 올바르지 않습니다.';
    END IF;
END
$$;

-- 검증 결과를 실행 로그에 출력함
SELECT 'coffee_profile' AS metric, COUNT(*) AS row_count
FROM coffee_profile
WHERE catalog_key IS NOT NULL
UNION ALL
SELECT 'product', COUNT(*)
FROM product product
JOIN coffee_profile profile
  ON profile.coffee_profile_id = product.coffee_profile_id
WHERE profile.catalog_key IS NOT NULL
UNION ALL
SELECT 'component', COUNT(*)
FROM coffee_profile_component component
JOIN coffee_profile profile
  ON profile.coffee_profile_id = component.coffee_profile_id
WHERE profile.catalog_key IS NOT NULL
UNION ALL
SELECT 'flavor_note_relation', COUNT(*)
FROM coffee_profile_flavor_note relation
JOIN coffee_profile profile
  ON profile.coffee_profile_id = relation.coffee_profile_id
WHERE profile.catalog_key IS NOT NULL
UNION ALL
SELECT 'brew_method_relation', COUNT(*)
FROM coffee_profile_brew_method relation
JOIN coffee_profile profile
  ON profile.coffee_profile_id = relation.coffee_profile_id
WHERE profile.catalog_key IS NOT NULL
UNION ALL
SELECT 'variety_relation', COUNT(*)
FROM coffee_profile_variety relation
JOIN coffee_profile profile
  ON profile.coffee_profile_id = relation.coffee_profile_id
WHERE profile.catalog_key IS NOT NULL
ORDER BY metric;
