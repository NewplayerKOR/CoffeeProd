\set ON_ERROR_STOP on

BEGIN;

SET LOCAL TIME ZONE 'UTC';

-- 동일 시드의 동시 실행을 차단함
SELECT pg_advisory_xact_lock(hashtextextended('coffeeprod-commerce-seed', 0));

CREATE TEMP TABLE seed_config
(
    member_count   INTEGER NOT NULL,
    order_count    INTEGER NOT NULL,
    review_count   INTEGER NOT NULL,
    qna_count      INTEGER NOT NULL,
    history_days   INTEGER NOT NULL,
    k6_user_count  INTEGER NOT NULL,
    seed_value     INTEGER NOT NULL,
    seed_end_date  DATE    NOT NULL
) ON COMMIT DROP;

INSERT INTO seed_config
VALUES (
    :member_count::INTEGER,
    :order_count::INTEGER,
    :review_count::INTEGER,
    :qna_count::INTEGER,
    :history_days::INTEGER,
    :k6_user_count::INTEGER,
    :seed_value::INTEGER,
    :'seed_end_date'::DATE
);

-- 입력 규모와 선행 카탈로그를 검증함
DO $$
DECLARE
    config            RECORD;
    on_sale_products  INTEGER;
BEGIN
    SELECT * INTO config FROM seed_config;
    SELECT COUNT(*) INTO on_sale_products FROM product WHERE status = 'ON_SALE';

    IF config.member_count < 1 OR config.member_count > 999999 THEN
        RAISE EXCEPTION 'member_count는 1~999999 범위여야 합니다.';
    END IF;

    IF config.k6_user_count < 1 OR config.k6_user_count > config.member_count THEN
        RAISE EXCEPTION 'k6_user_count는 1~member_count 범위여야 합니다.';
    END IF;

    IF config.order_count < 1 OR config.review_count < 0 OR config.qna_count < 0 THEN
        RAISE EXCEPTION 'order_count는 1 이상이며 review_count와 qna_count는 0 이상이어야 합니다.';
    END IF;

    IF config.review_count > config.order_count / 2 THEN
        RAISE EXCEPTION 'review_count는 구매 이력 확보를 위해 order_count의 절반 이하여야 합니다.';
    END IF;

    IF config.history_days < 30 OR config.history_days > 3650 THEN
        RAISE EXCEPTION 'history_days는 30~3650 범위여야 합니다.';
    END IF;

    IF on_sale_products < 50 THEN
        RAISE EXCEPTION '판매 중 상품이 50개 이상 필요합니다. 카탈로그를 먼저 적재하십시오.';
    END IF;
END
$$;

-- 이전 시드 식별자를 수집함
CREATE TEMP TABLE previous_seed_member
(
    member_id BIGINT PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO previous_seed_member (member_id)
SELECT member_id
FROM member
WHERE email ~ '^loadtest-(user-[0-9]{6}|admin)@coffeeprod[.]local$';

CREATE TEMP TABLE previous_seed_order
(
    order_id BIGINT PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO previous_seed_order (order_id)
SELECT order_id
FROM orders
WHERE member_id IN (SELECT member_id FROM previous_seed_member)
   OR toss_order_id LIKE 'LOADTEST-%';

-- 이전 및 신규 영업일을 매출 재집계 대상으로 기록함
CREATE TEMP TABLE seed_affected_stat_date
(
    stat_date DATE PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO seed_affected_stat_date (stat_date)
SELECT DISTINCT (orders.order_date AT TIME ZONE 'Asia/Seoul')::DATE
FROM orders
WHERE order_id IN (SELECT order_id FROM previous_seed_order)
ON CONFLICT DO NOTHING;

INSERT INTO seed_affected_stat_date (stat_date)
SELECT generated_day::DATE
FROM seed_config config
CROSS JOIN LATERAL generate_series(
    (config.seed_end_date - (config.history_days - 1))::TIMESTAMP,
    config.seed_end_date::TIMESTAMP,
    INTERVAL '1 day'
) generated_day
ON CONFLICT DO NOTHING;

-- 이전 시드의 자식 데이터부터 제거함
DELETE FROM product_qna qna
USING previous_seed_member seed_member
WHERE qna.member_id = seed_member.member_id
   OR qna.answerer_id = seed_member.member_id;

DELETE FROM reviews review
USING previous_seed_member seed_member
WHERE review.member_id = seed_member.member_id;

DELETE FROM payment payment
USING previous_seed_order seed_order
WHERE payment.order_id = seed_order.order_id;

DELETE FROM order_item item
USING previous_seed_order seed_order
WHERE item.order_id = seed_order.order_id;

DELETE FROM orders orders
USING previous_seed_order seed_order
WHERE orders.order_id = seed_order.order_id;

DELETE FROM cart_item item
USING cart cart, previous_seed_member seed_member
WHERE item.cart_id = cart.cart_id
  AND cart.member_id = seed_member.member_id;

DELETE FROM cart cart
USING previous_seed_member seed_member
WHERE cart.member_id = seed_member.member_id;

DELETE FROM member_coffee_preference preference
USING previous_seed_member seed_member
WHERE preference.member_id = seed_member.member_id;

DELETE FROM address address
USING previous_seed_member seed_member
WHERE address.member_id = seed_member.member_id;

DELETE FROM member member
USING previous_seed_member seed_member
WHERE member.member_id = seed_member.member_id;

-- k6 로그인에 사용하는 공통 BCrypt 해시를 저장함
WITH generated_member AS (
    SELECT member_no,
           config.*,
           (
               (
                   config.seed_end_date
                   - config.history_days
                   - MOD(member_no * 17 + config.seed_value, 365)
               )::TIMESTAMP
               + make_interval(hours => MOD(member_no * 7, 24))
               + make_interval(mins => MOD(member_no * 19, 60))
           ) AT TIME ZONE 'Asia/Seoul' AS joined_at
    FROM seed_config config
    CROSS JOIN LATERAL generate_series(1, config.member_count) member_no
)
INSERT INTO member (
    email,
    password,
    name,
    nickname,
    role,
    grade,
    mileage,
    status,
    created_at,
    updated_at
)
SELECT format('loadtest-user-%s@coffeeprod.local', LPAD(member_no::TEXT, 6, '0')),
       '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
       format('부하회원 %s', LPAD(member_no::TEXT, 6, '0')),
       format('loadtest_user_%s', LPAD(member_no::TEXT, 6, '0')),
       'USER',
       CASE
           WHEN MOD(member_no, 20) = 0 THEN 'GOLD'
           WHEN MOD(member_no, 5) = 0 THEN 'SILVER'
           ELSE 'BRONZE'
       END,
       20000 + MOD(member_no, 25) * 1000,
       CASE
           WHEN member_no <= k6_user_count THEN 'ACTIVE'
           WHEN MOD(member_no, 1000) = 0 THEN 'WITHDRAWN'
           WHEN MOD(member_no, 200) = 0 THEN 'SUSPENDED'
           ELSE 'ACTIVE'
       END,
       joined_at,
       joined_at
FROM generated_member;

INSERT INTO member (
    email,
    password,
    name,
    nickname,
    role,
    grade,
    mileage,
    status,
    created_at,
    updated_at
)
SELECT 'loadtest-admin@coffeeprod.local',
       '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
       '부하테스트 관리자',
       'loadtest_admin',
       'ADMIN',
       'GOLD',
       0,
       'ACTIVE',
       (seed_end_date - history_days)::TIMESTAMP AT TIME ZONE 'Asia/Seoul',
       (seed_end_date - history_days)::TIMESTAMP AT TIME ZONE 'Asia/Seoul'
FROM seed_config;

CREATE TEMP TABLE seed_member_map
(
    member_no INTEGER PRIMARY KEY,
    member_id BIGINT  NOT NULL UNIQUE
) ON COMMIT DROP;

INSERT INTO seed_member_map (member_no, member_id)
SELECT member_no,
       member.member_id
FROM seed_config config
CROSS JOIN LATERAL generate_series(1, config.member_count) member_no
JOIN member
  ON member.email = format(
      'loadtest-user-%s@coffeeprod.local',
      LPAD(member_no::TEXT, 6, '0')
  );

-- 회원마다 기본 배송지를 생성함
INSERT INTO address (
    member_id,
    recipient,
    phone,
    zipcode,
    address_line1,
    address_line2,
    is_default
)
SELECT map.member_id,
       format('수령인 %s', LPAD(map.member_no::TEXT, 6, '0')),
       format(
           '010-%s-%s',
           LPAD(MOD(map.member_no * 31, 10000)::TEXT, 4, '0'),
           LPAD(MOD(map.member_no * 73, 10000)::TEXT, 4, '0')
       ),
       LPAD((10000 + MOD(map.member_no * 37, 89999))::TEXT, 5, '0'),
       CASE MOD(map.member_no, 8)
           WHEN 0 THEN '서울특별시 강남구 테헤란로'
           WHEN 1 THEN '서울특별시 마포구 월드컵북로'
           WHEN 2 THEN '경기도 성남시 분당구 판교역로'
           WHEN 3 THEN '부산광역시 해운대구 센텀중앙로'
           WHEN 4 THEN '대전광역시 유성구 대학로'
           WHEN 5 THEN '대구광역시 수성구 동대구로'
           WHEN 6 THEN '광주광역시 북구 첨단과기로'
           ELSE '인천광역시 연수구 송도과학로'
       END || ' ' || (10 + MOD(map.member_no, 290))::TEXT,
       format('%s동 %s호', 101 + MOD(map.member_no, 20), 201 + MOD(map.member_no, 700)),
       TRUE
FROM seed_member_map map;

-- 일부 회원에게 보조 배송지를 생성함
INSERT INTO address (
    member_id,
    recipient,
    phone,
    zipcode,
    address_line1,
    address_line2,
    is_default
)
SELECT map.member_id,
       format('회사수령 %s', LPAD(map.member_no::TEXT, 6, '0')),
       format(
           '010-%s-%s',
           LPAD(MOD(map.member_no * 41, 10000)::TEXT, 4, '0'),
           LPAD(MOD(map.member_no * 89, 10000)::TEXT, 4, '0')
       ),
       LPAD((10000 + MOD(map.member_no * 53, 89999))::TEXT, 5, '0'),
       '서울특별시 영등포구 국제금융로 ' || (1 + MOD(map.member_no, 99))::TEXT,
       format('%s층', 2 + MOD(map.member_no, 38)),
       FALSE
FROM seed_member_map map
WHERE MOD(map.member_no, 10) IN (0, 1, 2);

-- 판매 상품을 결정적 순번으로 매핑함
CREATE TEMP TABLE seed_product_map
(
    product_no INTEGER PRIMARY KEY,
    product_id BIGINT       NOT NULL UNIQUE,
    sku        VARCHAR(64)  NOT NULL UNIQUE,
    price      INTEGER      NOT NULL
) ON COMMIT DROP;

INSERT INTO seed_product_map (product_no, product_id, sku, price)
SELECT ROW_NUMBER() OVER (ORDER BY sku)::INTEGER,
       product_id,
       sku,
       price
FROM product
WHERE status = 'ON_SALE';

-- 영업일과 시간대별 주문 상태를 생성함
CREATE TEMP TABLE seed_order_source
(
    order_no       INTEGER PRIMARY KEY,
    member_id      BIGINT       NOT NULL,
    order_date     TIMESTAMPTZ  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    payment_status VARCHAR(20)
) ON COMMIT DROP;

WITH generated_order AS (
    SELECT order_no,
           config.*,
           LEAST(
               config.member_count,
               1 + FLOOR(
                   POWER(
                       MOD(order_no::BIGINT * 48271 + config.seed_value, 2147483647)::DOUBLE PRECISION
                       / 2147483647.0,
                       1.65
                   ) * config.member_count
               )::INTEGER
           ) AS member_no,
           config.seed_end_date
               - MOD(order_no * 37 + config.seed_value, config.history_days) AS business_date,
           MOD(order_no * 29 + config.seed_value, 100) AS status_bucket,
           MOD(order_no * 13 + config.seed_value, 100) AS hour_bucket
    FROM seed_config config
    CROSS JOIN LATERAL generate_series(1, config.order_count) order_no
), classified_order AS (
    SELECT generated.*,
           CASE
               WHEN business_date >= seed_end_date - 1 AND status_bucket < 4 THEN 'PENDING'
               WHEN status_bucket < 5 THEN 'CANCELED'
               WHEN business_date >= seed_end_date - 2 THEN 'PAID'
               WHEN business_date >= seed_end_date - 6 THEN 'SHIPPED'
               WHEN status_bucket < 15 THEN 'PAID'
               WHEN status_bucket < 30 THEN 'SHIPPED'
               ELSE 'DELIVERED'
           END AS order_status,
           CASE
               WHEN hour_bucket < 8 THEN 7 + MOD(order_no, 3)
               WHEN hour_bucket < 35 THEN 10 + MOD(order_no, 3)
               WHEN hour_bucket < 62 THEN 13 + MOD(order_no, 4)
               WHEN hour_bucket < 90 THEN 17 + MOD(order_no, 4)
               ELSE 20 + MOD(order_no, 3)
           END AS order_hour
    FROM generated_order generated
)
INSERT INTO seed_order_source (
    order_no,
    member_id,
    order_date,
    status,
    payment_status
)
SELECT classified.order_no,
       member_map.member_id,
       (
           classified.business_date::TIMESTAMP
           + make_interval(hours => classified.order_hour)
           + make_interval(mins => MOD(classified.order_no * 17, 60))
           + make_interval(secs => MOD(classified.order_no * 43, 60))
       ) AT TIME ZONE 'Asia/Seoul',
       classified.order_status,
       CASE
           WHEN classified.order_status = 'PENDING' THEN NULL
           WHEN classified.order_status = 'CANCELED' AND MOD(classified.order_no, 2) = 0 THEN 'REFUNDED'
           WHEN classified.order_status = 'CANCELED' THEN 'FAILED'
           ELSE 'SUCCESS'
       END
FROM classified_order classified
JOIN seed_member_map member_map
  ON member_map.member_no = classified.member_no;

-- 주문별 1~4개 상품과 구매 시점 가격을 생성함
CREATE TEMP TABLE seed_order_item_source
(
    order_no    INTEGER      NOT NULL,
    product_id  BIGINT       NOT NULL,
    order_price INTEGER      NOT NULL,
    quantity    INTEGER      NOT NULL,
    grind_type  VARCHAR(30)  NOT NULL,
    PRIMARY KEY (order_no, product_id)
) ON COMMIT DROP;

WITH product_count AS (
    SELECT COUNT(*)::INTEGER AS value FROM seed_product_map
), generated_item AS (
    SELECT orders.order_no,
           item_no,
           count.value AS product_count,
           FLOOR(
               POWER(
                   MOD(
                       orders.order_no::BIGINT * 69621
                       + item_no::BIGINT * 101
                       + config.seed_value,
                       2147483647
                   )::DOUBLE PRECISION / 2147483647.0,
                   2.20
               ) * count.value
           )::INTEGER AS base_product_no
    FROM seed_order_source orders
    CROSS JOIN seed_config config
    CROSS JOIN product_count count
    CROSS JOIN LATERAL generate_series(1, 1 + MOD(orders.order_no, 4)) item_no
)
INSERT INTO seed_order_item_source (
    order_no,
    product_id,
    order_price,
    quantity,
    grind_type
)
SELECT generated.order_no,
       product.product_id,
       product.price,
       1 + MOD(generated.order_no + generated.item_no * 7, 3),
       CASE MOD(generated.order_no + generated.item_no, 4)
           WHEN 0 THEN 'WHOLE_BEAN'
           WHEN 1 THEN 'ESPRESSO'
           WHEN 2 THEN 'DRIP'
           ELSE 'FRENCH_PRESS'
       END
FROM generated_item generated
JOIN seed_product_map product
  ON product.product_no = 1 + MOD(
      generated.base_product_no + (generated.item_no - 1),
      generated.product_count
  );

CREATE TEMP TABLE seed_order_final
(
    order_no            INTEGER PRIMARY KEY,
    member_id           BIGINT        NOT NULL,
    order_date          TIMESTAMPTZ   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    payment_status      VARCHAR(20),
    used_mileage        INTEGER       NOT NULL,
    delivery_address    VARCHAR(255)  NOT NULL,
    tracking_no         VARCHAR(255),
    product_total_price INTEGER       NOT NULL,
    delivery_fee        INTEGER       NOT NULL,
    earned_mileage      INTEGER       NOT NULL,
    total_price         INTEGER       NOT NULL
) ON COMMIT DROP;

WITH order_amount AS (
    SELECT order_no,
           SUM(order_price * quantity)::INTEGER AS product_total_price
    FROM seed_order_item_source
    GROUP BY order_no
), order_policy AS (
    SELECT orders.*,
           amount.product_total_price,
           CASE WHEN amount.product_total_price >= 30000 THEN 0 ELSE 3000 END AS delivery_fee,
           CASE
               WHEN MOD(orders.order_no, 5) = 0
                   THEN LEAST(5000, amount.product_total_price / 10000 * 1000)
               ELSE 0
           END AS used_mileage
    FROM seed_order_source orders
    JOIN order_amount amount USING (order_no)
)
INSERT INTO seed_order_final (
    order_no,
    member_id,
    order_date,
    status,
    payment_status,
    used_mileage,
    delivery_address,
    tracking_no,
    product_total_price,
    delivery_fee,
    earned_mileage,
    total_price
)
SELECT policy.order_no,
       policy.member_id,
       policy.order_date,
       policy.status,
       policy.payment_status,
       policy.used_mileage,
       format(
           '[%s] %s %s (%s)',
           address.recipient,
           address.address_line1,
           COALESCE(address.address_line2, ''),
           address.phone
       ),
       CASE
           WHEN policy.status IN ('SHIPPED', 'DELIVERED')
               THEN 'LT' || LPAD(policy.order_no::TEXT, 12, '0')
           ELSE NULL
       END,
       policy.product_total_price,
       policy.delivery_fee,
       CASE
           WHEN policy.payment_status IN ('SUCCESS', 'REFUNDED')
               THEN (policy.product_total_price - policy.used_mileage) / 100
           ELSE 0
       END,
       policy.product_total_price - policy.used_mileage + policy.delivery_fee
FROM order_policy policy
JOIN address
  ON address.member_id = policy.member_id
 AND address.is_default = TRUE;

INSERT INTO orders (
    member_id,
    order_date,
    status,
    used_mileage,
    delivery_address,
    tracking_no,
    toss_order_id,
    product_total_price,
    delivery_fee,
    earned_mileage,
    total_price,
    created_at,
    updated_at
)
SELECT member_id,
       order_date,
       status,
       used_mileage,
       delivery_address,
       tracking_no,
       'LOADTEST-' || LPAD(order_no::TEXT, 12, '0'),
       product_total_price,
       delivery_fee,
       earned_mileage,
       total_price,
       order_date,
       order_date + INTERVAL '1 minute'
FROM seed_order_final;

CREATE TEMP TABLE seed_order_map
(
    order_no INTEGER PRIMARY KEY,
    order_id BIGINT  NOT NULL UNIQUE
) ON COMMIT DROP;

INSERT INTO seed_order_map (order_no, order_id)
SELECT source.order_no,
       orders.order_id
FROM seed_order_final source
JOIN orders
  ON orders.toss_order_id = 'LOADTEST-' || LPAD(source.order_no::TEXT, 12, '0');

INSERT INTO order_item (
    order_id,
    product_id,
    order_price,
    quantity,
    grind_type
)
SELECT order_map.order_id,
       item.product_id,
       item.order_price,
       item.quantity,
       item.grind_type
FROM seed_order_item_source item
JOIN seed_order_map order_map USING (order_no);

INSERT INTO payment (
    order_id,
    pg_provider,
    payment_key,
    pay_method,
    status,
    paid_at
)
SELECT order_map.order_id,
       'TOSSPAYMENTS',
       'LOADTEST-PAY-' || LPAD(source.order_no::TEXT, 12, '0'),
       CASE MOD(source.order_no, 5)
           WHEN 0 THEN 'CARD'
           WHEN 1 THEN 'EASY_PAY'
           WHEN 2 THEN 'TRANSFER'
           WHEN 3 THEN 'VIRTUAL_ACCOUNT'
           ELSE 'MOBILE_PHONE'
       END,
       source.payment_status,
       CASE
           WHEN source.payment_status = 'FAILED' THEN NULL
           ELSE source.order_date + make_interval(mins => 1 + MOD(source.order_no, 20))
       END
FROM seed_order_final source
JOIN seed_order_map order_map USING (order_no)
WHERE source.payment_status IS NOT NULL;

-- 현재 마일리지를 주문 스냅샷과 일치하는 수준으로 보정함
WITH mileage_ledger AS (
    SELECT member_id,
           SUM(
               CASE
                   WHEN status = 'PENDING' THEN -used_mileage
                   WHEN status = 'CANCELED' THEN 0
                   ELSE earned_mileage - used_mileage
               END
           )::INTEGER AS mileage_delta
    FROM seed_order_final
    GROUP BY member_id
)
UPDATE member
SET mileage = LEAST(100000, GREATEST(10000, 20000 + ledger.mileage_delta))
FROM mileage_ledger ledger
WHERE member.member_id = ledger.member_id;

-- 배송 완료 구매 이력이 있는 회원에게만 리뷰를 생성함
WITH eligible_review AS (
    SELECT orders.member_id,
           item.product_id,
           MAX(orders.order_date) AS purchased_at
    FROM orders
    JOIN order_item item USING (order_id)
    WHERE orders.order_id IN (SELECT order_id FROM seed_order_map)
      AND orders.status = 'DELIVERED'
    GROUP BY orders.member_id, item.product_id
), ranked_review AS (
    SELECT eligible.*,
           ROW_NUMBER() OVER (
               ORDER BY MD5(
                   member.email || ':' || product.sku || ':' || config.seed_value::TEXT
               )
           ) AS review_no,
           config.review_count,
           config.seed_end_date
    FROM eligible_review eligible
    JOIN member ON member.member_id = eligible.member_id
    JOIN product ON product.product_id = eligible.product_id
    CROSS JOIN seed_config config
)
INSERT INTO reviews (
    member_id,
    product_id,
    rating,
    content,
    created_at,
    updated_at
)
SELECT member_id,
       product_id,
       CASE
           WHEN MOD(review_no, 100) < 55 THEN 5
           WHEN MOD(review_no, 100) < 85 THEN 4
           WHEN MOD(review_no, 100) < 95 THEN 3
           WHEN MOD(review_no, 100) < 99 THEN 2
           ELSE 1
       END,
       CASE MOD(review_no, 8)
           WHEN 0 THEN '향이 선명하고 식은 뒤에도 단맛이 오래 남았습니다.'
           WHEN 1 THEN '설명에 적힌 풍미와 비슷했고 핸드드립으로 마시기 좋았습니다.'
           WHEN 2 THEN '산미와 고소함의 균형이 좋아 아침 커피로 만족했습니다.'
           WHEN 3 THEN '분쇄도에 맞춰 추출하니 향과 바디가 안정적으로 표현되었습니다.'
           WHEN 4 THEN '포장 상태가 좋고 로스팅 정보가 명확해 재구매를 고려하고 있습니다.'
           WHEN 5 THEN '우유와 함께 마셔도 커피의 단맛과 향이 충분히 느껴졌습니다.'
           WHEN 6 THEN '평소보다 조금 진하게 추출했을 때 가장 만족스러웠습니다.'
           ELSE '가격과 품질의 균형이 좋고 일상적으로 마시기 편한 원두였습니다.'
       END,
       LEAST(
           purchased_at + make_interval(days => (2 + MOD(review_no, 20))::INTEGER),
           (seed_end_date + 1)::TIMESTAMP AT TIME ZONE 'Asia/Seoul' - INTERVAL '1 second'
       ),
       LEAST(
           purchased_at + make_interval(days => (2 + MOD(review_no, 20))::INTEGER),
           (seed_end_date + 1)::TIMESTAMP AT TIME ZONE 'Asia/Seoul' - INTERVAL '1 second'
       )
FROM ranked_review
WHERE review_no <= review_count;

-- 문의와 관리자 답변 상태를 함께 생성함
WITH generated_qna AS (
    SELECT qna_no,
           config.*,
           1 + MOD(qna_no * 7919 + config.seed_value, config.member_count) AS member_no,
           1 + MOD(
               qna_no * 37 + config.seed_value,
               (SELECT COUNT(*)::INTEGER FROM seed_product_map)
           ) AS product_no,
           config.seed_end_date
               - MOD(qna_no * 23 + config.seed_value, config.history_days) AS business_date,
           MOD(qna_no, 5) <> 0 AS answered
    FROM seed_config config
    CROSS JOIN LATERAL generate_series(1, config.qna_count) qna_no
), qna_source AS (
    SELECT generated.*,
           member_map.member_id,
           product_map.product_id,
           admin.member_id AS answerer_id,
           (
               generated.business_date::TIMESTAMP
               + make_interval(hours => 8 + MOD(generated.qna_no * 7, 14))
               + make_interval(mins => MOD(generated.qna_no * 11, 60))
           ) AT TIME ZONE 'Asia/Seoul' AS created_at
    FROM generated_qna generated
    JOIN seed_member_map member_map USING (member_no)
    JOIN seed_product_map product_map USING (product_no)
    CROSS JOIN member admin
    WHERE admin.email = 'loadtest-admin@coffeeprod.local'
)
INSERT INTO product_qna (
    member_id,
    product_id,
    answerer_id,
    title,
    question,
    answer,
    status,
    answered_at,
    created_at,
    updated_at
)
SELECT member_id,
       product_id,
       CASE WHEN answered THEN answerer_id ELSE NULL END,
       CASE MOD(qna_no, 6)
           WHEN 0 THEN '추천 추출 방식 문의'
           WHEN 1 THEN '로스팅 날짜와 보관 방법 문의'
           WHEN 2 THEN '산미와 바디감 문의'
           WHEN 3 THEN '에스프레소 사용 적합 여부'
           WHEN 4 THEN '디카페인 공정 관련 문의'
           ELSE '분쇄도 선택 문의'
       END,
       CASE MOD(qna_no, 6)
           WHEN 0 THEN '이 원두의 향미를 잘 살릴 수 있는 물 온도와 추출 비율을 알려주세요.'
           WHEN 1 THEN '개봉한 뒤 향을 오래 유지하려면 어떤 용기와 장소에 보관해야 하나요?'
           WHEN 2 THEN '상품 설명의 산미와 바디감이 실제로 어느 정도인지 궁금합니다.'
           WHEN 3 THEN '가정용 반자동 머신에서 에스프레소로 사용해도 잘 어울리나요?'
           WHEN 4 THEN '카페인을 제거한 방식과 일반 원두 대비 향미 차이를 알고 싶습니다.'
           ELSE '핸드드립과 프렌치프레스 주문 시 각각 어떤 분쇄도를 선택해야 하나요?'
       END,
       CASE WHEN answered THEN
           CASE MOD(qna_no, 6)
               WHEN 0 THEN '물 92~94도, 원두와 물 1:15 비율에서 시작한 뒤 취향에 맞게 조정해 주세요.'
               WHEN 1 THEN '직사광선과 습기를 피해 밀폐 용기에 보관하고 개봉 후 가급적 한 달 안에 드시는 것을 권장합니다.'
               WHEN 2 THEN '상품 상세의 감각 점수는 상대 비교 기준이며 추출 방식에 따라 체감이 달라질 수 있습니다.'
               WHEN 3 THEN '에스프레소로 사용할 수 있으며 처음에는 1:2 추출 비율을 기준으로 맞추는 것을 권장합니다.'
               WHEN 4 THEN '상품 상세의 디카페인 공정 정보를 확인할 수 있으며 일반 원두보다 향미 강도가 부드러울 수 있습니다.'
               ELSE '핸드드립은 DRIP, 프렌치프레스는 FRENCH_PRESS 분쇄 옵션을 선택해 주세요.'
           END
       ELSE NULL END,
       CASE WHEN answered THEN 'ANSWERED' ELSE 'WAITING' END,
       CASE
           WHEN answered THEN LEAST(
               created_at + make_interval(hours => 1 + MOD(qna_no * 13, 72)),
               (seed_end_date + 1)::TIMESTAMP AT TIME ZONE 'Asia/Seoul' - INTERVAL '1 second'
           )
           ELSE NULL
       END,
       created_at,
       CASE
           WHEN answered THEN LEAST(
               created_at + make_interval(hours => 1 + MOD(qna_no * 13, 72)),
               (seed_end_date + 1)::TIMESTAMP AT TIME ZONE 'Asia/Seoul' - INTERVAL '1 second'
           )
           ELSE created_at
       END
FROM qna_source;

-- 요청한 리뷰 수가 확보되었는지 확인함
DO $$
DECLARE
    expected_count INTEGER;
    actual_count   INTEGER;
BEGIN
    SELECT review_count INTO expected_count FROM seed_config;

    SELECT COUNT(*) INTO actual_count
    FROM reviews review
    JOIN member ON member.member_id = review.member_id
    WHERE member.email ~ '^loadtest-user-[0-9]{6}@coffeeprod[.]local$';

    IF actual_count <> expected_count THEN
        RAISE EXCEPTION '리뷰 생성 건수 부족: expected=%, actual=%. 주문 수를 늘리십시오.',
            expected_count, actual_count;
    END IF;
END
$$;

-- 영향받은 영업일의 매출을 전체 결제 기준으로 재집계함
DELETE FROM sales_statistics statistics
USING seed_affected_stat_date affected
WHERE statistics.stat_date = affected.stat_date;

WITH affected_bounds AS (
    SELECT MIN(stat_date) AS start_date,
           MAX(stat_date) AS end_date
    FROM seed_affected_stat_date
), paid_sales AS (
    SELECT (payment.paid_at AT TIME ZONE 'Asia/Seoul')::DATE AS stat_date,
           COUNT(*)::BIGINT AS order_count,
           SUM(orders.product_total_price)::BIGINT AS product_sales_amount,
           SUM(orders.delivery_fee)::BIGINT AS delivery_fee_amount,
           SUM(orders.used_mileage)::BIGINT AS used_mileage_amount,
           SUM(orders.total_price)::BIGINT AS payment_amount
    FROM payment
    JOIN orders USING (order_id)
    CROSS JOIN affected_bounds bounds
    WHERE payment.status = 'SUCCESS'
      AND (payment.paid_at AT TIME ZONE 'Asia/Seoul')::DATE
          BETWEEN bounds.start_date AND bounds.end_date
    GROUP BY (payment.paid_at AT TIME ZONE 'Asia/Seoul')::DATE
)
INSERT INTO sales_statistics (
    stat_date,
    order_count,
    product_sales_amount,
    delivery_fee_amount,
    used_mileage_amount,
    payment_amount
)
SELECT affected.stat_date,
       COALESCE(sales.order_count, 0),
       COALESCE(sales.product_sales_amount, 0),
       COALESCE(sales.delivery_fee_amount, 0),
       COALESCE(sales.used_mileage_amount, 0),
       COALESCE(sales.payment_amount, 0)
FROM seed_affected_stat_date affected
LEFT JOIN paid_sales sales USING (stat_date)
ORDER BY affected.stat_date;

COMMIT;

-- 적재 결과 요약을 출력함
SELECT 'members' AS entity, COUNT(*) AS row_count
FROM member
WHERE email ~ '^loadtest-(user-[0-9]{6}|admin)@coffeeprod[.]local$'
UNION ALL
SELECT 'orders', COUNT(*) FROM orders WHERE toss_order_id LIKE 'LOADTEST-%'
UNION ALL
SELECT 'order_items', COUNT(*)
FROM order_item item
JOIN orders USING (order_id)
WHERE orders.toss_order_id LIKE 'LOADTEST-%'
UNION ALL
SELECT 'payments', COUNT(*)
FROM payment
WHERE payment_key LIKE 'LOADTEST-PAY-%'
UNION ALL
SELECT 'reviews', COUNT(*)
FROM reviews review
JOIN member ON member.member_id = review.member_id
WHERE member.email ~ '^loadtest-user-[0-9]{6}@coffeeprod[.]local$'
UNION ALL
SELECT 'qnas', COUNT(*)
FROM product_qna qna
JOIN member ON member.member_id = qna.member_id
WHERE member.email ~ '^loadtest-user-[0-9]{6}@coffeeprod[.]local$';
