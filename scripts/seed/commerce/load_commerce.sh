#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
K6_USERS_FILE="$PROJECT_ROOT/k6/data/users.json"
TEMP_USERS_FILE="$K6_USERS_FILE.tmp"
MODE="${1:-load}"

if [[ $# -gt 1 ]]; then
  echo "사용법: bash scripts/seed/commerce/load_commerce.sh [--verify-only]" >&2
  exit 1
fi

MEMBER_COUNT="${MEMBER_COUNT:-10000}"
ORDER_COUNT="${ORDER_COUNT:-150000}"
REVIEW_COUNT="${REVIEW_COUNT:-30000}"
QNA_COUNT="${QNA_COUNT:-20000}"
HISTORY_DAYS="${HISTORY_DAYS:-730}"
K6_USER_COUNT="${K6_USER_COUNT:-200}"
SEED_VALUE="${SEED_VALUE:-20260722}"
SEED_END_DATE="${SEED_END_DATE:-2026-06-30}"

if [[ "$MODE" != "load" && "$MODE" != "--verify-only" ]]; then
  echo "사용법: bash scripts/seed/commerce/load_commerce.sh [--verify-only]" >&2
  exit 1
fi

INTEGER_VALUES=(
  "$MEMBER_COUNT"
  "$ORDER_COUNT"
  "$REVIEW_COUNT"
  "$QNA_COUNT"
  "$HISTORY_DAYS"
  "$K6_USER_COUNT"
  "$SEED_VALUE"
)

# 숫자 환경변수 형식을 확인함
for value in "${INTEGER_VALUES[@]}"; do
  if [[ ! "$value" =~ ^[0-9]+$ ]]; then
    echo "시드 설정은 0 이상의 정수여야 합니다: $value" >&2
    exit 1
  fi
done

# 기준일 형식을 확인함
if [[ ! "$SEED_END_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "SEED_END_DATE는 YYYY-MM-DD 형식이어야 합니다: $SEED_END_DATE" >&2
  exit 1
fi

trap 'rm -f "$TEMP_USERS_FILE"' EXIT

cd "$PROJECT_ROOT"

PSQL_VARIABLES=(
  -v "member_count=$MEMBER_COUNT"
  -v "order_count=$ORDER_COUNT"
  -v "review_count=$REVIEW_COUNT"
  -v "qna_count=$QNA_COUNT"
  -v "history_days=$HISTORY_DAYS"
  -v "k6_user_count=$K6_USER_COUNT"
  -v "seed_value=$SEED_VALUE"
  -v "seed_end_date=$SEED_END_DATE"
)

if [[ "$MODE" == "load" ]]; then
  # 거래 데이터를 트랜잭션으로 재생성함
  docker compose exec -T db sh -lc \
    'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"' \
    sh "${PSQL_VARIABLES[@]}" \
    < "$SCRIPT_DIR/sql/seed_commerce.sql"
fi

# DB 반영 결과의 관계와 금액을 검증함
docker compose exec -T db sh -lc \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"' \
  sh "${PSQL_VARIABLES[@]}" \
  < "$SCRIPT_DIR/sql/verify_commerce.sql"

# 실제 배송지 PK를 포함한 k6 계정을 내보냄
docker compose exec -T db sh -lc \
  'psql -X -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"' \
  sh -v "k6_user_count=$K6_USER_COUNT" \
  < "$SCRIPT_DIR/sql/export_k6_users.sql" \
  > "$TEMP_USERS_FILE"

node "$SCRIPT_DIR/validate_k6_users.mjs" "$TEMP_USERS_FILE" "$K6_USER_COUNT"
mv "$TEMP_USERS_FILE" "$K6_USERS_FILE"

if [[ "$MODE" == "--verify-only" ]]; then
  echo "기존 거래 시드 검증과 k6 계정 생성이 완료되었습니다."
else
  echo "거래 시드 적재와 검증이 완료되었습니다."
fi
echo "k6 계정 파일: $K6_USERS_FILE"
