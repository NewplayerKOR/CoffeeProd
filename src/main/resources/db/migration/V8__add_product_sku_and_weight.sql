-- 기존 상품 테이블에 판매 SKU와 중량 정보를 추가함
ALTER TABLE product
    ADD COLUMN sku VARCHAR(64),
    ADD COLUMN weight_grams INTEGER;

-- 기존 개발 데이터를 마이그레이션 가능한 값으로 보정함
UPDATE product
SET sku = CONCAT('LEGACY-', product_id)
WHERE sku IS NULL;

UPDATE product
SET weight_grams = 200
WHERE weight_grams IS NULL;

-- 모든 상품은 SKU와 중량을 반드시 보유하도록 강제함
ALTER TABLE product
    ALTER COLUMN sku SET NOT NULL;

ALTER TABLE product
    ALTER COLUMN weight_grams SET NOT NULL;

-- SKU 중복과 비정상 중량을 DB에서도 차단함
ALTER TABLE product
    ADD CONSTRAINT uk_product_sku UNIQUE (sku);

ALTER TABLE product
    ADD CONSTRAINT ck_product_sku_not_blank
        CHECK (BTRIM(sku) <> '');

ALTER TABLE product
    ADD CONSTRAINT ck_product_weight_grams_positive
        CHECK (weight_grams > 0);