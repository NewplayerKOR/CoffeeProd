-- 카테고리 중복을 사전 검증함
DO $$
BEGIN
    IF EXISTS (
        SELECT name
        FROM category
        GROUP BY name
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION '중복 카테고리명이 존재해 UNIQUE 제약을 추가할 수 없습니다.';
    END IF;
END
$$;

-- 서비스 검증을 DB 제약으로 보강함
ALTER TABLE category
    ADD CONSTRAINT uk_category_name UNIQUE (name);

-- CSV 프로필을 반복 식별할 키를 추가함
ALTER TABLE coffee_profile
    ADD COLUMN catalog_key VARCHAR(32);

ALTER TABLE coffee_profile
    ADD CONSTRAINT uk_coffee_profile_catalog_key
        UNIQUE (catalog_key);

ALTER TABLE coffee_profile
    ADD CONSTRAINT ck_coffee_profile_catalog_key
        CHECK (
            catalog_key IS NULL
            OR catalog_key ~ '^[A-Z0-9_]+$'
        );
