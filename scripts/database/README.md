# CoffeeProd Database Operations

## 전제 조건

- Docker와 Docker Compose가 실행되어야 한다.
- 프로젝트 루트에 `.env`가 있어야 한다.
- `DB_URL`은 `coffee_db`를 가리켜야 한다.
- 현재 DB 데이터 폐기가 승인되어야 한다.

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

V17은 `orders(member_id, order_date DESC)`와 `order_item(order_id)` 인덱스를 일반 `CREATE INDEX`로 생성한다. Flyway 트랜잭션으로 두 인덱스를 원자적으로 적용하며, 잠금 대기는 10초, 전체 실행은 5분으로 제한한다.

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

### 3.4 실패와 복구

- 잠금 대기 또는 제한 시간 초과 시 Flyway 트랜잭션이 두 인덱스를 함께 롤백한다. 원인을 확인한 뒤 저부하 시간에 다시 배포한다.
- 성공한 V17을 되돌릴 때 V17 파일이나 Flyway 이력을 수정하지 않는다. 두 인덱스를 삭제하는 새 후속 migration을 작성해 배포한다.
- 긴급 수동 삭제는 DBA 승인 후에만 수행하고, 동일 내용을 후속 migration으로 즉시 정합화한다.
- 실패 이력이 남은 경우 원인과 실제 스키마 상태를 확인한 뒤에만 Flyway `repair` 여부를 판단한다.
