-- 대표 가공 방식을 기준정보로 등록함
INSERT INTO processing_method (code, name, description)
SELECT 'WASHED', 'Washed', '물로 점액질을 제거해 깔끔한 산미와 명료한 향미를 강조함'
    WHERE NOT EXISTS (
    SELECT 1 FROM processing_method WHERE code = 'WASHED'
);

INSERT INTO processing_method (code, name, description)
SELECT 'NATURAL', 'Natural', '커피 체리를 과육째 건조해 과일 향과 단맛을 강조함'
    WHERE NOT EXISTS (
    SELECT 1 FROM processing_method WHERE code = 'NATURAL'
);

INSERT INTO processing_method (code, name, description)
SELECT 'HONEY', 'Honey', '점액질 일부를 남겨 단맛과 바디감을 균형 있게 표현함'
    WHERE NOT EXISTS (
    SELECT 1 FROM processing_method WHERE code = 'HONEY'
);

INSERT INTO processing_method (code, name, description)
SELECT 'SWISS_WATER', 'Swiss Water', '화학 용매 없이 물 기반 방식으로 카페인을 제거함'
    WHERE NOT EXISTS (
    SELECT 1 FROM processing_method WHERE code = 'SWISS_WATER'
);

-- 대표 싱글오리진 커피 프로필을 등록함
INSERT INTO coffee_profile (
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
    summary
)
SELECT
    pm.processing_method_id,
    'Ethiopia Yirgacheffe Washed',
    'SINGLE_ORIGIN',
    'ET',
    'Yirgacheffe',
    NULL,
    NULL,
    1800,
    2200,
    'LIGHT',
    FALSE,
    NULL,
    5,
    2,
    4,
    5,
    '플로럴 향, 밝은 산미, 차 같은 깔끔한 후미가 특징인 에티오피아 워시드 프로필'
FROM processing_method pm
WHERE pm.code = 'WASHED'
  AND NOT EXISTS (
    SELECT 1 FROM coffee_profile
    WHERE profile_name = 'Ethiopia Yirgacheffe Washed'
);

INSERT INTO coffee_profile (
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
    summary
)
SELECT
    pm.processing_method_id,
    'Colombia Huila Honey',
    'SINGLE_ORIGIN',
    'CO',
    'Huila',
    NULL,
    NULL,
    1500,
    1900,
    'MEDIUM',
    FALSE,
    NULL,
    4,
    3,
    4,
    4,
    '균형 잡힌 산미와 단맛, 중간 바디감이 특징인 콜롬비아 허니 프로필'
FROM processing_method pm
WHERE pm.code = 'HONEY'
  AND NOT EXISTS (
    SELECT 1 FROM coffee_profile
    WHERE profile_name = 'Colombia Huila Honey'
);

INSERT INTO coffee_profile (
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
    summary
)
SELECT
    pm.processing_method_id,
    'Brazil Cerrado Natural',
    'SINGLE_ORIGIN',
    'BR',
    'Cerrado',
    NULL,
    NULL,
    900,
    1200,
    'MEDIUM',
    FALSE,
    NULL,
    2,
    4,
    5,
    3,
    '견과류, 초콜릿, 낮은 산미와 묵직한 단맛이 특징인 브라질 내추럴 프로필'
FROM processing_method pm
WHERE pm.code = 'NATURAL'
  AND NOT EXISTS (
    SELECT 1 FROM coffee_profile
    WHERE profile_name = 'Brazil Cerrado Natural'
);