-- JPA String 매핑과 PostgreSQL 국가 코드 타입을 일치시킴
ALTER TABLE coffee_profile
ALTER COLUMN origin_country_code TYPE VARCHAR(2)
    USING origin_country_code::VARCHAR(2);