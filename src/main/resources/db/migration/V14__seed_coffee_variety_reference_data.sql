-- 초기 커피 품종 기준정보를 등록함
INSERT INTO coffee_variety (code, name, description)
VALUES ('ETHIOPIAN_HEIRLOOM', '에티오피아 토착종', '에티오피아 지역 토착 품종군을 표현함'),
       ('CATURRA', '카투라', '버번 계열의 왜성 품종으로 균형 잡힌 단맛과 산미를 표현함'),
       ('CASTILLO', '카스티요', '콜롬비아에서 널리 재배되는 품종을 표현함'),
       ('MUNDO_NOVO', '문도 노보', '티피카와 버번 계열의 교배 품종을 표현함'),
       ('CATUAI', '카투아이', '문도 노보와 카투라 계열의 교배 품종을 표현함');

-- 기존 초기 프로필에 품종을 연결함
INSERT INTO coffee_profile_variety (
    coffee_profile_id,
    coffee_variety_id,
    display_order
)
SELECT coffee_profile.coffee_profile_id,
       coffee_variety.coffee_variety_id,
       seed.display_order
FROM (VALUES ('Ethiopia Yirgacheffe Washed',
              'ETHIOPIAN_HEIRLOOM',
              1),
             ('Colombia Huila Honey',
              'CATURRA',
              1),
             ('Colombia Huila Honey',
              'CASTILLO',
              2),
             ('Brazil Cerrado Natural',
              'MUNDO_NOVO',
              1),
             ('Brazil Cerrado Natural',
              'CATUAI',
              2)) AS seed(profile_name, variety_code, display_order)
         JOIN coffee_profile
              ON coffee_profile.profile_name = seed.profile_name
         JOIN coffee_variety
              ON coffee_variety.code = seed.variety_code;
