\set ON_ERROR_STOP on
\timing on

BEGIN;

SET LOCAL TIME ZONE 'UTC';
SET LOCAL work_mem = '128MB';
SET LOCAL temp_buffers = '32MB';

CREATE TEMP TABLE verify_config
(
    member_count  INTEGER NOT NULL,
    order_count   INTEGER NOT NULL,
    review_count  INTEGER NOT NULL,
    qna_count     INTEGER NOT NULL,
    history_days  INTEGER NOT NULL,
    k6_user_count INTEGER NOT NULL,
    seed_end_date DATE    NOT NULL
) ON COMMIT DROP;

INSERT INTO verify_config
VALUES (
    :member_count::INTEGER,
    :order_count::INTEGER,
    :review_count::INTEGER,
    :qna_count::INTEGER,
    :history_days::INTEGER,
    :k6_user_count::INTEGER,
    :'seed_end_date'::DATE
);

\echo '[verify] 시드 회원과 주문 범위를 구성합니다.'

-- 시드 회원 범위를 한 번만 조회함
CREATE TEMP TABLE verify_seed_member
ON COMMIT DROP
AS
SELECT member_id,
       email,
       status,
       CASE
           WHEN email ~ '^loadtest-user-[0-9]{6}@coffeeprod[.]local$'
               THEN SUBSTRING(email FROM 'user-([0-9]{6})@')::INTEGER
           ELSE NULL
       END AS member_no
FROM member
WHERE email ~ '^loadtest-(user-[0-9]{6}|admin)@coffeeprod[.]local$';

ALTER TABLE verify_seed_member
    ADD CONSTRAINT pk_verify_seed_member PRIMARY KEY (member_id);

CREATE UNIQUE INDEX uk_verify_seed_member_no
    ON verify_seed_member (member_no)
    WHERE member_no IS NOT NULL;

ANALYZE verify_seed_member;

-- 시드 주문 범위를 후속 검증에서 재사용함
CREATE TEMP TABLE verify_seed_order
ON COMMIT DROP
AS
SELECT order_id,
       member_id,
       order_date,
       status,
       tracking_no,
       product_total_price,
       delivery_fee,
       used_mileage,
       earned_mileage,
       total_price
FROM orders
WHERE toss_order_id LIKE 'LOADTEST-%';

ALTER TABLE verify_seed_order
    ADD CONSTRAINT pk_verify_seed_order PRIMARY KEY (order_id);

ANALYZE verify_seed_order;

\echo '[verify] 주문상품 금액과 배송 완료 구매 조합을 집계합니다.'

-- 주문상품을 한 번 집계해 반복 정렬과 디스크 유출을 방지함
CREATE TEMP TABLE verify_order_item_summary
ON COMMIT DROP
AS
SELECT item.order_id,
       COUNT(*)::INTEGER AS item_count,
       SUM(item.order_price * item.quantity)::BIGINT AS product_total_price,
       BOOL_OR(item.order_price <= 0 OR item.quantity <= 0) AS has_invalid_item
FROM order_item item
JOIN verify_seed_order seed_order USING (order_id)
GROUP BY item.order_id;

ALTER TABLE verify_order_item_summary
    ADD CONSTRAINT pk_verify_order_item_summary PRIMARY KEY (order_id);

ANALYZE verify_order_item_summary;

-- 배송 완료 구매 조합을 리뷰 검증에 재사용함
CREATE TEMP TABLE verify_delivered_purchase
ON COMMIT DROP
AS
SELECT DISTINCT seed_order.member_id,
       item.product_id
FROM verify_seed_order seed_order
JOIN order_item item USING (order_id)
WHERE seed_order.status = 'DELIVERED';

ALTER TABLE verify_delivered_purchase
    ADD CONSTRAINT pk_verify_delivered_purchase
        PRIMARY KEY (member_id, product_id);

ANALYZE verify_delivered_purchase;

\echo '[verify] 일별 성공 결제 금액을 집계합니다.'

-- 성공 결제를 날짜별로 한 번만 집계함
CREATE TEMP TABLE verify_expected_sales
ON COMMIT DROP
AS
WITH paid_sales AS (
    SELECT (payment.paid_at AT TIME ZONE 'Asia/Seoul')::DATE AS stat_date,
           COUNT(*)::BIGINT AS order_count,
           SUM(orders.product_total_price)::BIGINT AS product_sales_amount,
           SUM(orders.delivery_fee)::BIGINT AS delivery_fee_amount,
           SUM(orders.used_mileage)::BIGINT AS used_mileage_amount,
           SUM(orders.total_price)::BIGINT AS payment_amount
    FROM payment
    JOIN orders USING (order_id)
    CROSS JOIN verify_config config
    WHERE payment.status = 'SUCCESS'
      AND payment.paid_at >= (
          (config.seed_end_date - (config.history_days - 1))::TIMESTAMP
          AT TIME ZONE 'Asia/Seoul'
      )
      AND payment.paid_at < (
          (config.seed_end_date + 1)::TIMESTAMP
          AT TIME ZONE 'Asia/Seoul'
      )
    GROUP BY (payment.paid_at AT TIME ZONE 'Asia/Seoul')::DATE
)
SELECT day::DATE AS stat_date,
       COALESCE(sales.order_count, 0)::BIGINT AS order_count,
       COALESCE(sales.product_sales_amount, 0)::BIGINT AS product_sales_amount,
       COALESCE(sales.delivery_fee_amount, 0)::BIGINT AS delivery_fee_amount,
       COALESCE(sales.used_mileage_amount, 0)::BIGINT AS used_mileage_amount,
       COALESCE(sales.payment_amount, 0)::BIGINT AS payment_amount
FROM verify_config config
CROSS JOIN LATERAL generate_series(
    (config.seed_end_date - (config.history_days - 1))::TIMESTAMP,
    config.seed_end_date::TIMESTAMP,
    INTERVAL '1 day'
) day
LEFT JOIN paid_sales sales
  ON sales.stat_date = day::DATE;

ALTER TABLE verify_expected_sales
    ADD CONSTRAINT pk_verify_expected_sales PRIMARY KEY (stat_date);

ANALYZE verify_expected_sales;

\echo '[verify] 건수와 도메인 정합성을 검증합니다.'

-- 시드 건수와 핵심 관계를 검증함
DO $$
DECLARE
    config        RECORD;
    actual_count  BIGINT;
BEGIN
    SELECT * INTO config FROM verify_config;

    SELECT COUNT(*) INTO actual_count
    FROM verify_seed_member;
    IF actual_count <> config.member_count + 1 THEN
        RAISE EXCEPTION '회원 수 불일치: expected=%, actual=%', config.member_count + 1, actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM verify_seed_order;
    IF actual_count <> config.order_count THEN
        RAISE EXCEPTION '주문 수 불일치: expected=%, actual=%', config.order_count, actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM reviews review
    JOIN verify_seed_member seed_member
      ON seed_member.member_id = review.member_id
    WHERE seed_member.member_no IS NOT NULL;
    IF actual_count <> config.review_count THEN
        RAISE EXCEPTION '리뷰 수 불일치: expected=%, actual=%', config.review_count, actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM product_qna qna
    JOIN verify_seed_member seed_member
      ON seed_member.member_id = qna.member_id
    WHERE seed_member.member_no IS NOT NULL;
    IF actual_count <> config.qna_count THEN
        RAISE EXCEPTION 'QnA 수 불일치: expected=%, actual=%', config.qna_count, actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM verify_seed_member seed_member
    JOIN address address ON address.member_id = seed_member.member_id
    WHERE seed_member.member_no IS NOT NULL
      AND address.is_default = TRUE;
    IF actual_count <> config.member_count THEN
        RAISE EXCEPTION '기본 배송지 수 불일치: expected=%, actual=%', config.member_count, actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM verify_seed_member
    WHERE member_no <= config.k6_user_count
      AND status = 'ACTIVE';
    IF actual_count <> config.k6_user_count THEN
        RAISE EXCEPTION 'k6 활성 회원 수 불일치: expected=%, actual=%', config.k6_user_count, actual_count;
    END IF;
END
$$;

-- 회원별 기본 배송지 중복을 차단함
DO $$
BEGIN
    IF EXISTS (
        SELECT seed_member.member_id
        FROM verify_seed_member seed_member
        JOIN address ON address.member_id = seed_member.member_id
        WHERE seed_member.member_no IS NOT NULL
          AND address.is_default = TRUE
        GROUP BY seed_member.member_id
        HAVING COUNT(*) <> 1
    ) THEN
        RAISE EXCEPTION '기본 배송지가 정확히 1개가 아닌 시드 회원이 존재합니다.';
    END IF;
END
$$;

-- 주문 금액과 주문상품 수를 검증함
DO $$
BEGIN
    IF EXISTS (
        SELECT seed_order.order_id
        FROM verify_seed_order seed_order
        LEFT JOIN verify_order_item_summary item_summary USING (order_id)
        WHERE COALESCE(item_summary.item_count, 0) NOT BETWEEN 1 AND 4
           OR item_summary.product_total_price <> seed_order.product_total_price
           OR seed_order.total_price
               <> seed_order.product_total_price
                  - seed_order.used_mileage
                  + seed_order.delivery_fee
           OR seed_order.used_mileage NOT BETWEEN 0 AND seed_order.product_total_price
           OR seed_order.delivery_fee NOT IN (0, 3000)
           OR COALESCE(item_summary.has_invalid_item, FALSE)
    ) THEN
        RAISE EXCEPTION '주문상품 수 또는 주문 금액 정합성이 맞지 않습니다.';
    END IF;
END
$$;

-- 주문과 결제 상태의 조합을 검증함
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM verify_seed_order orders
        LEFT JOIN payment USING (order_id)
        WHERE NOT COALESCE((
              (orders.status = 'PENDING' AND payment.payment_id IS NULL)
              OR
              (orders.status IN ('PAID', 'SHIPPED', 'DELIVERED') AND payment.status = 'SUCCESS')
              OR
              (orders.status = 'CANCELED' AND payment.status IN ('FAILED', 'REFUNDED'))
          ), FALSE)
    ) THEN
        RAISE EXCEPTION '주문과 결제 상태 조합이 유효하지 않습니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM payment
        JOIN verify_seed_order USING (order_id)
        WHERE (
              (payment.status = 'FAILED' AND payment.paid_at IS NOT NULL)
              OR
              (payment.status IN ('SUCCESS', 'REFUNDED') AND payment.paid_at IS NULL)
          )
    ) THEN
        RAISE EXCEPTION '결제 상태와 결제 시각 조합이 유효하지 않습니다.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM verify_seed_order orders
        JOIN member USING (member_id)
        LEFT JOIN payment USING (order_id)
        WHERE (
              member.created_at > orders.order_date
              OR
              (
                  orders.status IN ('SHIPPED', 'DELIVERED')
                  AND COALESCE(BTRIM(orders.tracking_no), '') = ''
              )
              OR
              (
                  orders.status NOT IN ('SHIPPED', 'DELIVERED')
                  AND orders.tracking_no IS NOT NULL
              )
              OR
              (
                  payment.status IN ('SUCCESS', 'REFUNDED')
                  AND payment.paid_at < orders.order_date
              )
              OR
              (
                  payment.status IN ('SUCCESS', 'REFUNDED')
                  AND orders.earned_mileage
                      <> (orders.product_total_price - orders.used_mileage) / 100
              )
              OR
              (
                  (payment.status = 'FAILED' OR payment.payment_id IS NULL)
                  AND orders.earned_mileage <> 0
              )
          )
    ) THEN
        RAISE EXCEPTION '회원 가입일, 배송 또는 마일리지 스냅샷이 유효하지 않습니다.';
    END IF;
END
$$;

-- 리뷰가 배송 완료 구매 이력을 가지는지 검증함
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM reviews review
        JOIN verify_seed_member seed_member
          ON seed_member.member_id = review.member_id
         AND seed_member.member_no IS NOT NULL
        LEFT JOIN verify_delivered_purchase purchase
          ON purchase.member_id = review.member_id
         AND purchase.product_id = review.product_id
        WHERE purchase.member_id IS NULL
    ) THEN
        RAISE EXCEPTION '배송 완료 구매 이력이 없는 리뷰가 존재합니다.';
    END IF;
END
$$;

-- QnA 답변 상태와 관리자 권한을 검증함
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM product_qna qna
        JOIN verify_seed_member asker
          ON asker.member_id = qna.member_id
         AND asker.member_no IS NOT NULL
        LEFT JOIN member answerer ON answerer.member_id = qna.answerer_id
        WHERE NOT COALESCE((
              (
                  qna.status = 'WAITING'
                  AND qna.answer IS NULL
                  AND qna.answerer_id IS NULL
                  AND qna.answered_at IS NULL
              )
              OR
              (
                  qna.status = 'ANSWERED'
                  AND qna.answer IS NOT NULL
                  AND BTRIM(qna.answer) <> ''
                  AND qna.answered_at IS NOT NULL
                  AND qna.answered_at >= qna.created_at
                  AND answerer.role = 'ADMIN'
              )
          ), FALSE)
    ) THEN
        RAISE EXCEPTION 'QnA 답변 상태 또는 답변자 권한이 유효하지 않습니다.';
    END IF;
END
$$;

-- 일별 매출 통계가 성공 결제 합계와 일치하는지 검증함
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM verify_expected_sales expected
        LEFT JOIN sales_statistics actual USING (stat_date)
        WHERE actual.sales_statistics_id IS NULL
           OR actual.order_count <> expected.order_count
           OR actual.product_sales_amount <> expected.product_sales_amount
           OR actual.delivery_fee_amount <> expected.delivery_fee_amount
           OR actual.used_mileage_amount <> expected.used_mileage_amount
           OR actual.payment_amount <> expected.payment_amount
    ) THEN
        RAISE EXCEPTION '일별 매출 통계가 성공 결제 합계와 일치하지 않습니다.';
    END IF;
END
$$;

COMMIT;

SELECT 'commerce seed verification passed' AS result;

\timing off
