#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-quick}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
SUITE_ID="${K6_SUITE_ID:-portfolio-$MODE-$TIMESTAMP}"
RESULT_DIR="$ROOT_DIR/results/$SUITE_ID"

case "$MODE" in
  quick)
    SCENARIOS=(smoke image-assets read-load)
    ;;
  assets)
    SCENARIOS=(image-assets)
    ;;
  full)
    SCENARIOS=(smoke image-assets baseline read-load login-load stress spike soak)
    ;;
  *)
    echo "Unknown suite mode: $MODE" >&2
    echo "Available: quick, assets, full" >&2
    exit 1
    ;;
esac

mkdir -p "$RESULT_DIR"
INDEX_PATH="$RESULT_DIR/README.md"

{
  printf '# CoffeeProd k6 테스트 스위트\n\n'
  printf -- '- 실행 ID: `%s`\n' "$SUITE_ID"
  printf -- '- 실행 모드: `%s`\n' "$MODE"
  printf -- '- 시작 UTC: `%s`\n\n' "$TIMESTAMP"
  printf '| 시나리오 | 판정 | 결과 보고서 |\n'
  printf '|---|---|---|\n'
} > "$INDEX_PATH"

SUITE_EXIT=0
for SCENARIO in "${SCENARIOS[@]}"; do
  RUN_ID="$SUITE_ID-$SCENARIO"
  printf '\n[%s] %s 실행\n' "$SUITE_ID" "$SCENARIO"

  if K6_RUN_ID="$RUN_ID" K6_RESULT_DIR="$RESULT_DIR" \
    bash "$ROOT_DIR/run.sh" "$SCENARIO"; then
    printf '| `%s` | PASS | [%s-report.md](./%s-report.md) |\n' \
      "$SCENARIO" "$RUN_ID" "$RUN_ID" >> "$INDEX_PATH"
  else
    RUN_EXIT=$?
    printf '| `%s` | FAIL | [%s-report.md](./%s-report.md) |\n' \
      "$SCENARIO" "$RUN_ID" "$RUN_ID" >> "$INDEX_PATH"
    printf '\n`%s` 실패로 이후 부하 테스트를 중단했다. 종료 코드: `%s`\n' \
      "$SCENARIO" "$RUN_EXIT" >> "$INDEX_PATH"
    SUITE_EXIT=$RUN_EXIT
    break
  fi
done

printf '\n결과 디렉터리: %s\n' "$RESULT_DIR"
printf '스위트 인덱스: %s\n' "$INDEX_PATH"
exit "$SUITE_EXIT"
