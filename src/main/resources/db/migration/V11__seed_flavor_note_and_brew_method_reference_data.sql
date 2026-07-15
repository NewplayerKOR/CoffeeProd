-- 초기 향미 노트 기준정보를 등록함
INSERT INTO flavor_note (code, name, description)
VALUES ('FLORAL', '플로럴', '꽃 향 계열의 향미를 표현함'),
       ('JASMINE', '자스민', '자스민처럼 선명한 흰 꽃 향을 표현함'),
       ('CITRUS', '시트러스', '레몬, 오렌지 같은 밝은 감귤 향미를 표현함'),
       ('BERRY', '베리', '딸기, 블루베리 같은 베리류 향미를 표현함'),
       ('STONE_FRUIT', '핵과류', '복숭아, 살구 같은 핵과류 향미를 표현함'),
       ('CHOCOLATE', '초콜릿', '코코아와 초콜릿 계열의 향미를 표현함'),
       ('NUTTY', '견과류', '아몬드, 헤이즐넛 같은 고소한 향미를 표현함'),
       ('CARAMEL', '카라멜', '카라멜과 토피 계열의 단 향미를 표현함'),
       ('BROWN_SUGAR', '흑설탕', '흑설탕과 당밀 계열의 단 향미를 표현함'),
       ('TEA_LIKE', '티 라이크', '홍차나 녹차처럼 맑은 후미를 표현함'),
       ('SPICE', '스파이스', '계피와 정향 같은 향신료 계열 향미를 표현함');

-- 초기 추천 추출법 기준정보를 등록함
INSERT INTO brew_method (code, name, description)
VALUES ('POUR_OVER', '푸어오버', '필터를 사용해 맑고 선명한 향미를 표현함'),
       ('ESPRESSO', '에스프레소', '고압 추출로 농도와 바디감을 표현함'),
       ('FRENCH_PRESS', '프렌치프레스', '금속 필터로 오일감과 바디감을 표현함'),
       ('AEROPRESS', '에어로프레스', '압력과 침지를 조합해 균형감을 표현함'),
       ('COLD_BREW', '콜드브루', '저온 장시간 추출로 부드러운 단맛을 표현함'),
       ('MOKA_POT', '모카포트', '증기압으로 진한 질감을 표현함'),
       ('DRIP_MACHINE', '드립 머신', '일관된 가정용 드립 추출을 표현함');

-- 기존 초기 프로필에 향미 노트를 연결함
INSERT INTO coffee_profile_flavor_note (coffee_profile_id,
                                        flavor_note_id,
                                        display_order,
                                        intensity)
SELECT coffee_profile.coffee_profile_id,
       flavor_note.flavor_note_id,
       seed.display_order,
       seed.intensity
FROM (VALUES ('Ethiopia Yirgacheffe Washed', 'JASMINE', 1, 5),
             ('Ethiopia Yirgacheffe Washed', 'CITRUS', 2, 4),
             ('Ethiopia Yirgacheffe Washed', 'TEA_LIKE', 3, 4),
             ('Colombia Huila Honey', 'CARAMEL', 1, 4),
             ('Colombia Huila Honey', 'BROWN_SUGAR', 2, 4),
             ('Colombia Huila Honey', 'CITRUS', 3, 3),
             ('Brazil Cerrado Natural', 'CHOCOLATE', 1, 5),
             ('Brazil Cerrado Natural', 'NUTTY', 2, 5),
             ('Brazil Cerrado Natural', 'CARAMEL', 3,
              4)) AS seed(profile_name, flavor_note_code, display_order, intensity)
         JOIN coffee_profile
              ON coffee_profile.profile_name = seed.profile_name
         JOIN flavor_note
              ON flavor_note.code = seed.flavor_note_code;

-- 기존 초기 프로필에 추천 추출법을 연결함
INSERT INTO coffee_profile_brew_method (coffee_profile_id,
                                        brew_method_id,
                                        display_order,
                                        recommendation_note)
SELECT coffee_profile.coffee_profile_id,
       brew_method.brew_method_id,
       seed.display_order,
       seed.recommendation_note
FROM (VALUES ('Ethiopia Yirgacheffe Washed',
              'POUR_OVER',
              1,
              '88~92C 물로 짧게 블루밍한 뒤 맑게 추출함'),
             ('Ethiopia Yirgacheffe Washed',
              'AEROPRESS',
              2,
              '중간 분쇄도로 짧게 침지해 플로럴 향을 살림'),
             ('Colombia Huila Honey',
              'POUR_OVER',
              1,
              '중간 온도로 천천히 추출해 단맛과 산미를 균형 있게 표현함'),
             ('Colombia Huila Honey',
              'ESPRESSO',
              2,
              '우유와 조합해도 카라멜 계열의 단맛이 유지됨'),
             ('Brazil Cerrado Natural',
              'ESPRESSO',
              1,
              '진한 바디감과 초콜릿 향미를 표현함'),
             ('Brazil Cerrado Natural',
              'FRENCH_PRESS',
              2,
              '오일감과 견과류 향미를 강조함')) AS seed(profile_name, brew_method_code, display_order, recommendation_note)
         JOIN coffee_profile
              ON coffee_profile.profile_name = seed.profile_name
         JOIN brew_method
              ON brew_method.code = seed.brew_method_code;