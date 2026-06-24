-- 적용된 Flyway migration을 확인함

SELECT installed_rank,
       version,
       description,
       type,
       script,
       checksum,
       installed_by,
       installed_on,
       execution_time,
       success
FROM flyway_schema_history
ORDER BY installed_rank;