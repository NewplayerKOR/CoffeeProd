-- 기존 timestamp 값을 한국 시각으로 해석해 timestamptz로 변경함
-- 이벤트 발생 시각은 UTC 기준으로 저장함

ALTER TABLE member
ALTER COLUMN created_at TYPE TIMESTAMPTZ
        USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ
        USING updated_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE product
ALTER COLUMN created_at TYPE TIMESTAMPTZ
        USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ
        USING updated_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE orders
ALTER COLUMN order_date TYPE TIMESTAMPTZ
        USING order_date AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN created_at TYPE TIMESTAMPTZ
        USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ
        USING updated_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE payment
ALTER COLUMN paid_at TYPE TIMESTAMPTZ
        USING paid_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE reviews
ALTER COLUMN created_at TYPE TIMESTAMPTZ
        USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ
        USING updated_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE product_qna
ALTER COLUMN answered_at TYPE TIMESTAMPTZ
        USING answered_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN created_at TYPE TIMESTAMPTZ
        USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ
        USING updated_at AT TIME ZONE 'Asia/Seoul';