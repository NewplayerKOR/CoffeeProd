\set ON_ERROR_STOP on

BEGIN;

-- Flyway V16 적용 여부를 확인함
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'coffee_profile'
          AND column_name = 'catalog_key'
    ) THEN
        RAISE EXCEPTION 'Flyway V16 적용 후 카탈로그를 적재해야 합니다.';
    END IF;
END
$$;

-- CSV 원본을 운영 테이블과 분리해 적재함
CREATE TEMP TABLE stg_category
(
    category_code VARCHAR(50),
    name          VARCHAR(50)
) ON COMMIT DROP;

CREATE TEMP TABLE stg_processing_method
(
    code        VARCHAR(50),
    name        VARCHAR(100),
    description TEXT
) ON COMMIT DROP;

CREATE TEMP TABLE stg_flavor_note
(
    code        VARCHAR(50),
    name        VARCHAR(100),
    description TEXT
) ON COMMIT DROP;

CREATE TEMP TABLE stg_brew_method
(
    code        VARCHAR(50),
    name        VARCHAR(100),
    description TEXT
) ON COMMIT DROP;

CREATE TEMP TABLE stg_coffee_variety
(
    code        VARCHAR(50),
    name        VARCHAR(100),
    description TEXT
) ON COMMIT DROP;

CREATE TEMP TABLE stg_coffee_profile
(
    profile_key            VARCHAR(32),
    profile_name           VARCHAR(150),
    bean_type              VARCHAR(30),
    processing_method_code VARCHAR(50),
    origin_country_code    VARCHAR(2),
    origin_region          VARCHAR(100),
    farm_or_cooperative    VARCHAR(150),
    producer               VARCHAR(150),
    altitude_min           INTEGER,
    altitude_max           INTEGER,
    roast_level            VARCHAR(30),
    decaf                  BOOLEAN,
    decaf_method           VARCHAR(50),
    acidity                SMALLINT,
    body                   SMALLINT,
    sweetness              SMALLINT,
    aroma                  SMALLINT,
    summary                TEXT
) ON COMMIT DROP;

CREATE TEMP TABLE stg_profile_component
(
    profile_key            VARCHAR(32),
    display_order          SMALLINT,
    origin_country_code    VARCHAR(2),
    origin_region          VARCHAR(100),
    processing_method_code VARCHAR(50),
    component_ratio        NUMERIC(5, 2)
) ON COMMIT DROP;

CREATE TEMP TABLE stg_profile_flavor_note
(
    profile_key       VARCHAR(32),
    flavor_note_code  VARCHAR(50),
    display_order     SMALLINT,
    intensity         SMALLINT
) ON COMMIT DROP;

CREATE TEMP TABLE stg_profile_brew_method
(
    profile_key        VARCHAR(32),
    brew_method_code   VARCHAR(50),
    display_order      SMALLINT,
    recommendation_note VARCHAR(500)
) ON COMMIT DROP;

CREATE TEMP TABLE stg_profile_variety
(
    profile_key  VARCHAR(32),
    variety_code VARCHAR(50),
    display_order SMALLINT
) ON COMMIT DROP;

CREATE TEMP TABLE stg_product
(
    sku            VARCHAR(64),
    profile_key    VARCHAR(32),
    category_code  VARCHAR(50),
    weight_grams   INTEGER,
    name           VARCHAR(100),
    price          INTEGER,
    stock_quantity INTEGER,
    roast_level    VARCHAR(30),
    description    TEXT,
    image_url      VARCHAR(500),
    status         VARCHAR(30),
    display_order  SMALLINT
) ON COMMIT DROP;

-- 컨테이너에 읽기 전용으로 연결된 CSV를 적재함
\copy stg_category FROM '/seed/catalog/categories.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');
\copy stg_processing_method FROM '/seed/catalog/processing_methods.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');
\copy stg_flavor_note FROM '/seed/catalog/flavor_notes.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');
\copy stg_brew_method FROM '/seed/catalog/brew_methods.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');
\copy stg_coffee_variety FROM '/seed/catalog/coffee_varieties.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');
\copy stg_coffee_profile FROM '/seed/catalog/coffee_profiles.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');
\copy stg_profile_component FROM '/seed/catalog/coffee_profile_components.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');
\copy stg_profile_flavor_note FROM '/seed/catalog/profile_flavor_notes.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');
\copy stg_profile_brew_method FROM '/seed/catalog/profile_brew_methods.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');
\copy stg_profile_variety FROM '/seed/catalog/profile_varieties.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');
\copy stg_product FROM '/seed/catalog/products.csv' WITH (FORMAT CSV, HEADER TRUE, ENCODING 'UTF8');

-- 손상되거나 다른 버전의 CSV 적재를 차단함
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM stg_coffee_profile) <> 96 THEN
        RAISE EXCEPTION '커피 프로필 CSV는 96행이어야 합니다.';
    END IF;

    IF (SELECT COUNT(*) FROM stg_product) <> 192 THEN
        RAISE EXCEPTION '상품 CSV는 192행이어야 합니다.';
    END IF;

    IF EXISTS (
        SELECT profile_key
        FROM stg_coffee_profile
        GROUP BY profile_key
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION '프로필 키가 중복되었습니다.';
    END IF;

    IF EXISTS (
        SELECT sku
        FROM stg_product
        GROUP BY sku
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION '상품 SKU가 중복되었습니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM stg_coffee_profile profile
        WHERE profile.processing_method_code IS NOT NULL
          AND NOT EXISTS (
            SELECT 1
            FROM stg_processing_method method
            WHERE method.code = profile.processing_method_code
        )
    ) THEN
        RAISE EXCEPTION '프로필이 존재하지 않는 가공 방식을 참조합니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM stg_coffee_profile profile
        WHERE profile.bean_type = 'BLEND'
          AND (
            (
                SELECT COUNT(*)
                FROM stg_profile_component component
                WHERE component.profile_key = profile.profile_key
            ) NOT BETWEEN 2 AND 5
            OR (
                SELECT COALESCE(SUM(component.component_ratio), 0)
                FROM stg_profile_component component
                WHERE component.profile_key = profile.profile_key
            ) <> 100
        )
    ) THEN
        RAISE EXCEPTION '블렌드 구성 개수 또는 비율 합계가 올바르지 않습니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM stg_profile_component component
        WHERE NOT EXISTS (
            SELECT 1
            FROM stg_coffee_profile profile
            WHERE profile.profile_key = component.profile_key
              AND profile.bean_type = 'BLEND'
        )
           OR NOT EXISTS (
            SELECT 1
            FROM stg_processing_method method
            WHERE method.code = component.processing_method_code
        )
    ) THEN
        RAISE EXCEPTION '블렌드 구성요소 참조가 올바르지 않습니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM stg_product product
        WHERE NOT EXISTS (
            SELECT 1
            FROM stg_coffee_profile profile
            WHERE profile.profile_key = product.profile_key
        )
           OR NOT EXISTS (
            SELECT 1
            FROM stg_category category
            WHERE category.category_code = product.category_code
        )
    ) THEN
        RAISE EXCEPTION '상품의 프로필 또는 카테고리 참조가 올바르지 않습니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM stg_profile_flavor_note relation
        WHERE NOT EXISTS (
            SELECT 1 FROM stg_coffee_profile profile
            WHERE profile.profile_key = relation.profile_key
        )
           OR NOT EXISTS (
            SELECT 1 FROM stg_flavor_note note
            WHERE note.code = relation.flavor_note_code
        )
    ) THEN
        RAISE EXCEPTION '향미 연결 참조가 올바르지 않습니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM stg_profile_brew_method relation
        WHERE NOT EXISTS (
            SELECT 1 FROM stg_coffee_profile profile
            WHERE profile.profile_key = relation.profile_key
        )
           OR NOT EXISTS (
            SELECT 1 FROM stg_brew_method method
            WHERE method.code = relation.brew_method_code
        )
    ) THEN
        RAISE EXCEPTION '추출법 연결 참조가 올바르지 않습니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM stg_profile_variety relation
        WHERE NOT EXISTS (
            SELECT 1 FROM stg_coffee_profile profile
            WHERE profile.profile_key = relation.profile_key
        )
           OR NOT EXISTS (
            SELECT 1 FROM stg_coffee_variety variety
            WHERE variety.code = relation.variety_code
        )
    ) THEN
        RAISE EXCEPTION '품종 연결 참조가 올바르지 않습니다.';
    END IF;
END
$$;

-- 변경 가능한 기준정보를 코드 기준으로 동기화함
INSERT INTO category (name)
SELECT name
FROM stg_category
ON CONFLICT (name) DO NOTHING;

INSERT INTO processing_method (code, name, description)
SELECT code, name, description
FROM stg_processing_method
ON CONFLICT (code) DO UPDATE
    SET name = EXCLUDED.name,
        description = EXCLUDED.description;

INSERT INTO flavor_note (code, name, description)
SELECT code, name, description
FROM stg_flavor_note
ON CONFLICT (code) DO UPDATE
    SET name = EXCLUDED.name,
        description = EXCLUDED.description;

INSERT INTO brew_method (code, name, description)
SELECT code, name, description
FROM stg_brew_method
ON CONFLICT (code) DO UPDATE
    SET name = EXCLUDED.name,
        description = EXCLUDED.description;

INSERT INTO coffee_variety (code, name, description)
SELECT code, name, description
FROM stg_coffee_variety
ON CONFLICT (code) DO UPDATE
    SET name = EXCLUDED.name,
        description = EXCLUDED.description;

-- 변경 가능한 프로필을 카탈로그 키 기준으로 동기화함
INSERT INTO coffee_profile (
    catalog_key,
    processing_method_id,
    profile_name,
    bean_type,
    origin_country_code,
    origin_region,
    farm_or_cooperative,
    producer,
    altitude_min,
    altitude_max,
    roast_level,
    decaf,
    decaf_method,
    acidity,
    body,
    sweetness,
    aroma,
    summary,
    created_at,
    updated_at
)
SELECT profile.profile_key,
       method.processing_method_id,
       profile.profile_name,
       profile.bean_type,
       profile.origin_country_code,
       profile.origin_region,
       profile.farm_or_cooperative,
       profile.producer,
       profile.altitude_min,
       profile.altitude_max,
       profile.roast_level,
       profile.decaf,
       profile.decaf_method,
       profile.acidity,
       profile.body,
       profile.sweetness,
       profile.aroma,
       profile.summary,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM stg_coffee_profile profile
LEFT JOIN processing_method method
       ON method.code = profile.processing_method_code
ON CONFLICT (catalog_key) DO UPDATE
    SET processing_method_id = EXCLUDED.processing_method_id,
        profile_name = EXCLUDED.profile_name,
        bean_type = EXCLUDED.bean_type,
        origin_country_code = EXCLUDED.origin_country_code,
        origin_region = EXCLUDED.origin_region,
        farm_or_cooperative = EXCLUDED.farm_or_cooperative,
        producer = EXCLUDED.producer,
        altitude_min = EXCLUDED.altitude_min,
        altitude_max = EXCLUDED.altitude_max,
        roast_level = EXCLUDED.roast_level,
        decaf = EXCLUDED.decaf,
        decaf_method = EXCLUDED.decaf_method,
        acidity = EXCLUDED.acidity,
        body = EXCLUDED.body,
        sweetness = EXCLUDED.sweetness,
        aroma = EXCLUDED.aroma,
        summary = EXCLUDED.summary,
        updated_at = CURRENT_TIMESTAMP;

-- 카탈로그 프로필의 다중 연결정보를 전체 교체함
DELETE FROM coffee_profile_component component
USING coffee_profile profile, stg_coffee_profile staging
WHERE component.coffee_profile_id = profile.coffee_profile_id
  AND profile.catalog_key = staging.profile_key;

DELETE FROM coffee_profile_flavor_note relation
USING coffee_profile profile, stg_coffee_profile staging
WHERE relation.coffee_profile_id = profile.coffee_profile_id
  AND profile.catalog_key = staging.profile_key;

DELETE FROM coffee_profile_brew_method relation
USING coffee_profile profile, stg_coffee_profile staging
WHERE relation.coffee_profile_id = profile.coffee_profile_id
  AND profile.catalog_key = staging.profile_key;

DELETE FROM coffee_profile_variety relation
USING coffee_profile profile, stg_coffee_profile staging
WHERE relation.coffee_profile_id = profile.coffee_profile_id
  AND profile.catalog_key = staging.profile_key;

INSERT INTO coffee_profile_component (
    coffee_profile_id,
    origin_country_code,
    origin_region,
    processing_method_id,
    component_ratio,
    display_order
)
SELECT profile.coffee_profile_id,
       component.origin_country_code,
       component.origin_region,
       method.processing_method_id,
       component.component_ratio,
       component.display_order
FROM stg_profile_component component
JOIN coffee_profile profile
  ON profile.catalog_key = component.profile_key
LEFT JOIN processing_method method
  ON method.code = component.processing_method_code;

INSERT INTO coffee_profile_flavor_note (
    coffee_profile_id,
    flavor_note_id,
    display_order,
    intensity
)
SELECT profile.coffee_profile_id,
       note.flavor_note_id,
       relation.display_order,
       relation.intensity
FROM stg_profile_flavor_note relation
JOIN coffee_profile profile
  ON profile.catalog_key = relation.profile_key
JOIN flavor_note note
  ON note.code = relation.flavor_note_code;

INSERT INTO coffee_profile_brew_method (
    coffee_profile_id,
    brew_method_id,
    display_order,
    recommendation_note
)
SELECT profile.coffee_profile_id,
       method.brew_method_id,
       relation.display_order,
       relation.recommendation_note
FROM stg_profile_brew_method relation
JOIN coffee_profile profile
  ON profile.catalog_key = relation.profile_key
JOIN brew_method method
  ON method.code = relation.brew_method_code;

INSERT INTO coffee_profile_variety (
    coffee_profile_id,
    coffee_variety_id,
    display_order
)
SELECT profile.coffee_profile_id,
       variety.coffee_variety_id,
       relation.display_order
FROM stg_profile_variety relation
JOIN coffee_profile profile
  ON profile.catalog_key = relation.profile_key
JOIN coffee_variety variety
  ON variety.code = relation.variety_code;

-- 판매 SKU를 SKU 기준으로 동기화함
INSERT INTO product (
    category_id,
    coffee_profile_id,
    sku,
    weight_grams,
    name,
    price,
    stock_quantity,
    roast_level,
    description,
    image_url,
    status,
    created_at,
    updated_at
)
SELECT category.category_id,
       profile.coffee_profile_id,
       product.sku,
       product.weight_grams,
       product.name,
       product.price,
       product.stock_quantity,
       product.roast_level,
       product.description,
       product.image_url,
       product.status,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM stg_product product
JOIN stg_category staging_category
  ON staging_category.category_code = product.category_code
JOIN category
  ON category.name = staging_category.name
JOIN coffee_profile profile
  ON profile.catalog_key = product.profile_key
ON CONFLICT (sku) DO UPDATE
    SET category_id = EXCLUDED.category_id,
        coffee_profile_id = EXCLUDED.coffee_profile_id,
        weight_grams = EXCLUDED.weight_grams,
        name = EXCLUDED.name,
        price = EXCLUDED.price,
        stock_quantity = EXCLUDED.stock_quantity,
        roast_level = EXCLUDED.roast_level,
        description = EXCLUDED.description,
        image_url = EXCLUDED.image_url,
        status = EXCLUDED.status,
        updated_at = CURRENT_TIMESTAMP;

-- 운영 테이블 반영 건수를 커밋 전에 검증함
DO $$
BEGIN
    IF (
        SELECT COUNT(*)
        FROM coffee_profile profile
        JOIN stg_coffee_profile staging
          ON staging.profile_key = profile.catalog_key
    ) <> (SELECT COUNT(*) FROM stg_coffee_profile) THEN
        RAISE EXCEPTION '커피 프로필 반영 건수가 일치하지 않습니다.';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM product product
        JOIN stg_product staging
          ON staging.sku = product.sku
    ) <> (SELECT COUNT(*) FROM stg_product) THEN
        RAISE EXCEPTION '상품 반영 건수가 일치하지 않습니다.';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM coffee_profile_component component
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = component.coffee_profile_id
        JOIN stg_coffee_profile staging
          ON staging.profile_key = profile.catalog_key
    ) <> (SELECT COUNT(*) FROM stg_profile_component) THEN
        RAISE EXCEPTION '블렌드 구성요소 반영 건수가 일치하지 않습니다.';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM coffee_profile_flavor_note relation
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = relation.coffee_profile_id
        JOIN stg_coffee_profile staging
          ON staging.profile_key = profile.catalog_key
    ) <> (SELECT COUNT(*) FROM stg_profile_flavor_note) THEN
        RAISE EXCEPTION '향미 연결 반영 건수가 일치하지 않습니다.';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM coffee_profile_brew_method relation
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = relation.coffee_profile_id
        JOIN stg_coffee_profile staging
          ON staging.profile_key = profile.catalog_key
    ) <> (SELECT COUNT(*) FROM stg_profile_brew_method) THEN
        RAISE EXCEPTION '추출법 연결 반영 건수가 일치하지 않습니다.';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM coffee_profile_variety relation
        JOIN coffee_profile profile
          ON profile.coffee_profile_id = relation.coffee_profile_id
        JOIN stg_coffee_profile staging
          ON staging.profile_key = profile.catalog_key
    ) <> (SELECT COUNT(*) FROM stg_profile_variety) THEN
        RAISE EXCEPTION '품종 연결 반영 건수가 일치하지 않습니다.';
    END IF;
END
$$;

COMMIT;

SELECT COUNT(*) AS imported_profiles
FROM coffee_profile
WHERE catalog_key IS NOT NULL;

SELECT COUNT(*) AS imported_products
FROM product
WHERE sku LIKE 'CP-%';
