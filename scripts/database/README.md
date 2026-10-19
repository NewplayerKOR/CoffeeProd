# CoffeeProd Database Operations

## 전제 조건

- Docker와 Docker Compose가 실행되어야 한다.
- 프로젝트 루트에 `.env`가 있어야 한다.
- `DB_URL`은 `coffee_db`를 가리켜야 한다.
- 빈 DB 재구축은 현재 DB 데이터 폐기가 승인된 경우에만 수행한다. 인덱스 점검은 기존 데이터를 유지한다.

## 1. 기존 DB 백업

기존 데이터가 필요하면 재구축 전에 `pg_dump`를 수행한다.

## 2. 빈 DB 재구축

Git Bash에서 실행한다.

```bash
CONFIRM_REBUILD=coffee_db \
./scripts/database/rebuild-database.sh
```

애플리케이션을 실행하면 Flyway migration이 순서대로 적용된다.

## 3. 주문 조회 인덱스 적용

V17은 `orders(member_id, order_date DESC)`와 `order_item(order_id)` 인덱스를 일반 `CREATE INDEX`로 생성한다. Flyway 트랜잭션으로 두 인덱스를 원자적으로 적용하며, 잠금 대기는 10초, 각 SQL 실행은 5분으로 제한한다.

현재 단일 애플리케이션 재배포 구조와 데이터 규모를 기준으로, 저부하 배포 시간대에 적용한다. 적용 중 해당 테이블의 쓰기는 잠시 대기할 수 있다.

### 3.1 적용 전 확인

백업 또는 복원 가능한 DB snapshot과 현재 배포 image tag/digest를 먼저 기록한다. 다음 결과에서 V17이 미적용이고 후보 인덱스의 `existing_count`가 모두 0인지 확인한다.

```bash
docker compose --env-file .env -f docker-compose.prod.yml exec -T db \
  sh -lc 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < scripts/database/precheck/order_query_index_precheck.sql
```

이름이 같거나 정의가 같은 인덱스가 있으면 배포하지 않고 정의와 `indisvalid`, `indisready`를 먼저 확인한다.

### 3.2 Flyway 적용

새 Backend image를 배포하면 애플리케이션 시작 시 V17이 적용된다. 기본 180초보다 긴 대기가 필요하면 배포 대기 시간을 명시한다.

```bash
DEPLOY_WAIT_SECONDS=300 bash scripts/deploy/deploy.sh <image-tag>
```

### 3.3 적용 후 확인

```bash
docker compose --env-file .env -f docker-compose.prod.yml exec -T db \
  sh -lc 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < scripts/database/verify-order-query-indexes.sql
```

V17 성공 이력, 두 인덱스의 정확한 정의, `is_valid=true`, `is_ready=true`를 확인한다. 이후 애플리케이션 health와 주문 목록·상세를 확인하고 QA TASK 3에 적용 완료를 인계한다.

검증기는 `pg_get_indexdef(index_oid, column_no, true)`로 컬럼명을 확인하고 `pg_index_column_has_property`로 ASC/DESC 및 NULL 정렬을 별도로 확인한다. 전체 인덱스 정의와 판정 속성을 오류 발생 전에 출력하므로 실패 원인을 확인할 수 있다. 동일 정의의 인덱스는 이름이 달라도 중복으로 집계하며 invalid 인덱스도 포함한다.

V17 적용 전 precheck는 후보 인덱스 개수가 각각 0이어야 한다. V17 적용 후에는 각각 1이며, 검증 SQL에서 예상 이름/정의/valid/ready까지 확인한다. precheck는 V1~V16 스키마와 Flyway 이력이 있는 DB에서 사용한다. 테이블 자체가 없는 새 DB는 먼저 Flyway로 스키마를 생성하며 precheck의 테이블 부재 오류를 인덱스 누락으로 해석하지 않는다.

### 3.4 이미 적용된 DB의 검증기 교체

정상 DESC 인덱스를 거부하던 문자열 비교 오류는 standalone SQL 파일에서 수정했다. 서버의 다음 두 파일을 최신 파일로 교체하고 3.3의 검증 명령을 실행한다.

- `scripts/database/verify-order-query-indexes.sql`
- `scripts/database/precheck/order_query_index_precheck.sql`

V17 성공 이력과 인덱스의 정상 정의/유효 상태가 확인된 DB에서는 검증 파일 교체만 필요하다. 앱 재배포나 인덱스 재생성은 필요하지 않다.

이미 적용된 `V17__add_order_query_indexes.sql`은 checksum 보존을 위해 수정하지 않는다. V17 내부의 기존 DESC 문자열 중복 검사는 다른 이름의 동일 인덱스를 놓칠 수 있다. 신규/수동 관리 DB에서는 수정된 precheck로 이름 충돌과 동일 정의를 먼저 확인하고, 후보가 존재하면 적용을 중단해 DBA와 처리 방법을 결정한다. 추가 migration이나 Flyway repair는 이 검증기 수정에 포함하지 않는다.

### 3.5 실패와 복구

- 잠금 대기 또는 제한 시간 초과 시 Flyway 트랜잭션이 두 인덱스를 함께 롤백한다. 원인을 확인한 뒤 저부하 시간에 다시 배포한다.
- 성공한 V17을 되돌릴 때 V17 파일이나 Flyway 이력을 수정하지 않는다. 두 인덱스를 삭제하는 새 후속 migration을 작성해 배포한다.
- 긴급 수동 삭제는 DBA 승인 후에만 수행하고, 동일 내용을 후속 migration으로 즉시 정합화한다.
- 실패 이력이 남은 경우 원인과 실제 스키마 상태를 확인한 뒤에만 Flyway `repair` 여부를 판단한다.
