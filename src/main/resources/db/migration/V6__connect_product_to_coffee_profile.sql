-- 판매 상품이 커피 프로필을 선택적으로 참조하도록 연결함
ALTER TABLE product
    ADD COLUMN IF NOT EXISTS coffee_profile_id BIGINT;

-- product.coffee_profile_id 외래키를 추가함
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_product_coffee_profile'
    ) THEN
        ALTER TABLE product
            ADD CONSTRAINT fk_product_coffee_profile
                FOREIGN KEY (coffee_profile_id)
                    REFERENCES coffee_profile (coffee_profile_id);
    END IF;
END $$;

-- 커피 프로필 기준 상품 조회 성능을 보조함
CREATE INDEX IF NOT EXISTS idx_product_coffee_profile_id
    ON product (coffee_profile_id);