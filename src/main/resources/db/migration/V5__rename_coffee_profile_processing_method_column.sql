-- CoffeeProfile 엔티티 FK 컬럼명과 DB 컬럼명을 일치시킴
ALTER TABLE coffee_profile
    RENAME COLUMN process_method_id TO processing_method_id;