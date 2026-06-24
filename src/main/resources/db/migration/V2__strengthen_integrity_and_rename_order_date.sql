-- 핵심 관계와 값 범위의 무결성을 강화함
-- 주문 날짜 컬럼 오타를 수정함

ALTER TABLE orders
    RENAME COLUMN order_data TO order_date;

ALTER TABLE orders
    ALTER COLUMN member_id SET NOT NULL,
ALTER COLUMN order_date SET NOT NULL;

ALTER TABLE product
    ALTER COLUMN category_id SET NOT NULL;

ALTER TABLE order_item
    ALTER COLUMN order_id SET NOT NULL,
ALTER COLUMN product_id SET NOT NULL;

ALTER TABLE payment
    ALTER COLUMN order_id SET NOT NULL;

ALTER TABLE payment
    ADD CONSTRAINT uk_payment_order_id
        UNIQUE (order_id);

ALTER TABLE payment
    ADD CONSTRAINT uk_payment_payment_key
        UNIQUE (payment_key);

ALTER TABLE product
    ADD CONSTRAINT ck_product_price_positive
        CHECK (price > 0);

ALTER TABLE product
    ADD CONSTRAINT ck_product_stock_non_negative
        CHECK (stock_quantity >= 0);

ALTER TABLE reviews
    ADD CONSTRAINT ck_reviews_rating_range
        CHECK (rating BETWEEN 1 AND 5);

ALTER TABLE product_qna
    ADD CONSTRAINT ck_product_qna_answer_state
        CHECK (
            (
                status = 'WAITING'
                    AND answer IS NULL
                    AND answerer_id IS NULL
                    AND answered_at IS NULL
                )
                OR
            (
                status = 'ANSWERED'
                    AND answer IS NOT NULL
                    AND BTRIM(answer) <> ''
                    AND answerer_id IS NOT NULL
                    AND answered_at IS NOT NULL
                )
            );