#!/usr/bin/env bash
set -euo pipefail

copy_only=false
case "${1-}" in
  --copy-only) copy_only=true; shift ;;
esac
if (( $# != 0 )); then
  echo "사용법: bash scripts/seed/catalog/load_catalog.sh [--copy-only]" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CSV_DIR="$SCRIPT_DIR/csv"

CSV_FILES=(
  categories.csv
  processing_methods.csv
  flavor_notes.csv
  brew_methods.csv
  coffee_varieties.csv
  coffee_profiles.csv
  coffee_profile_components.csv
  profile_flavor_notes.csv
  profile_brew_methods.csv
  profile_varieties.csv
  products.csv
)

# 필수 CSV 존재 여부를 확인함
for file_name in "${CSV_FILES[@]}"; do
  if [[ ! -f "$CSV_DIR/$file_name" ]]; then
    echo "필수 CSV가 없습니다: $CSV_DIR/$file_name" >&2
    exit 1
  fi
done

cd "$PROJECT_ROOT"

# DB 적재 전에 파일 참조와 비율을 검증함
node scripts/seed/catalog/generate_catalog.mjs --check

# 컨테이너 내부 계정으로 트랜잭션 적재를 실행함
docker compose exec -T -e "CATALOG_COPY_ONLY=$copy_only" db sh -lc \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -v copy_only="$CATALOG_COPY_ONLY"' \
  < "$SCRIPT_DIR/sql/load_catalog.sql"

if [[ "$copy_only" == false ]]; then
  # 전체 적재에서 생성한 관계의 정합성을 다시 검증한다.
  docker compose exec -T db sh -lc \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' \
    < "$SCRIPT_DIR/sql/verify_catalog.sql"
fi

echo "카탈로그 적재와 검증이 완료되었습니다."
