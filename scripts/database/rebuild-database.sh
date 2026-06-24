#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(
    cd "$(dirname "${BASH_SOURCE[0]}")/../.." &&
    pwd
)"

cd "$PROJECT_ROOT"

TARGET_DATABASE="coffee_db"

# 명시적 승인값을 검증함
if [[ "${CONFIRM_REBUILD:-}" != "$TARGET_DATABASE" ]]; then
    echo "DB 재구축이 취소되었습니다."
    echo "실행 방법:"
    echo "CONFIRM_REBUILD=coffee_db ./scripts/database/rebuild-database.sh"
    exit 1
fi

# PostgreSQL 컨테이너를 실행함
docker compose up -d db

DB_USER="$(
    docker compose exec -T db printenv POSTGRES_USER |
    tr -d '\r'
)"

if [[ -z "$DB_USER" ]]; then
    echo "PostgreSQL 사용자 정보를 확인할 수 없습니다."
    exit 1
fi

# PostgreSQL 준비 상태를 확인함
for attempt in {1..30}; do
    if docker compose exec -T db \
        pg_isready -U "$DB_USER" -d postgres >/dev/null 2>&1; then
        break
    fi

    if [[ "$attempt" -eq 30 ]]; then
        echo "PostgreSQL 준비 시간을 초과했습니다."
        exit 1
    fi

    sleep 1
done

# 대상 DB 연결을 종료하고 빈 DB를 생성함
docker compose exec -T db \
    psql \
    -v ON_ERROR_STOP=1 \
    -U "$DB_USER" \
    -d postgres <<'SQL'
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'coffee_db'
  AND pid <> pg_backend_pid();

DROP DATABASE IF EXISTS coffee_db;
CREATE DATABASE coffee_db;
SQL

echo "빈 coffee_db 생성이 완료되었습니다."
echo "애플리케이션을 실행해 Flyway V1, V2를 적용하십시오."