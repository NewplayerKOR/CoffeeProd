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