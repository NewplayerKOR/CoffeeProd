#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCENARIO="${1:-smoke}"

if [[ $# -gt 0 ]]; then
  shift
fi

# 호출 환경변수를 우선하고 .env는 없는 값만 채움
if [[ -f "$ROOT_DIR/.env" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ ! "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
      echo "Invalid k6/.env line: $line" >&2
      exit 1
    fi

    key="${line%%=*}"
    value="${line#*=}"
    if [[ ! -v "$key" ]]; then
      export "$key=$value"
    fi
  done < "$ROOT_DIR/.env"
fi

ASSET_BASE_URL="${ASSET_BASE_URL:-https://assets-coffeeprod.ttagyulab.com}"
export ASSET_BASE_URL

SCRIPT_PATH="$ROOT_DIR/scripts/$SCENARIO.js"
if [[ ! -f "$SCRIPT_PATH" ]]; then
  echo "Unknown scenario: $SCENARIO" >&2
  echo "Available: smoke, image-assets, baseline, read-load, login-load, stress, spike, soak, order-concurrency" >&2
  exit 1
fi

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 command was not found. Install k6 and restart Git Bash." >&2
  exit 1
fi

if [[ -z "${BASE_URL:-}" || "$BASE_URL" == "https://api.example.com" ]]; then
  echo "Set BASE_URL in k6/.env to the target API URL." >&2
  exit 1
fi

RESULT_DIR="${K6_RESULT_DIR:-$ROOT_DIR/results}"
mkdir -p "$RESULT_DIR"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
K6_RUN_ID="${K6_RUN_ID:-$SCENARIO-$TIMESTAMP}"
GIT_COMMIT="${GIT_COMMIT:-$(git -C "$ROOT_DIR/.." rev-parse HEAD 2>/dev/null || echo unknown)}"
TEST_ENV="${TEST_ENV:-unknown}"
DB_SNAPSHOT_SHA256="${DB_SNAPSHOT_SHA256:-unknown}"
REQUIRE_RUN_METADATA="${REQUIRE_RUN_METADATA:-false}"
ALLOW_DIRTY_GIT="${ALLOW_DIRTY_GIT:-false}"

if [[ -n "$(git -C "$ROOT_DIR/.." status --porcelain 2>/dev/null)" ]]; then
  GIT_DIRTY=true
else
  GIT_DIRTY=false
fi

if [[ ! "$K6_RUN_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "K6_RUN_ID may contain only letters, numbers, dot, underscore, and hyphen." >&2
  exit 1
fi

if [[ "$REQUIRE_RUN_METADATA" == "true" && "$SCENARIO" != "smoke" ]]; then
  if [[ "$TEST_ENV" == "unknown" ]]; then
    echo "TEST_ENV is required for a portfolio-grade run." >&2
    exit 1
  fi
  if [[ ! "$DB_SNAPSHOT_SHA256" =~ ^[A-Fa-f0-9]{64}$ ]]; then
    echo "DB_SNAPSHOT_SHA256 must be a 64-character SHA-256 value." >&2
    exit 1
  fi
  if [[ "$GIT_COMMIT" == "unknown" ]]; then
    echo "GIT_COMMIT is required for a portfolio-grade run." >&2
    exit 1
  fi
  if [[ "$GIT_DIRTY" == "true" && "$ALLOW_DIRTY_GIT" != "true" ]]; then
    echo "Commit or stash working tree changes before a portfolio-grade run." >&2
    exit 1
  fi
fi

export K6_RUN_ID GIT_COMMIT TEST_ENV DB_SNAPSHOT_SHA256

RESULT_PREFIX="$RESULT_DIR/$K6_RUN_ID"
K6_VERSION="$(k6 version | head -n 1)"
RUNNER_OS="$(uname -srm)"
RUNNER_HOST="${COMPUTERNAME:-${HOSTNAME:-unknown}}"
printf -v EXTRA_ARGS '%q ' "$@"

# 실행 조건을 결과 파일과 함께 보관함
{
  printf 'run_id=%s\n' "$K6_RUN_ID"
  printf 'scenario=%s\n' "$SCENARIO"
  printf 'started_at_utc=%s\n' "$TIMESTAMP"
  printf 'base_url=%s\n' "$BASE_URL"
  printf 'asset_base_url=%s\n' "${ASSET_BASE_URL:-not-applicable}"
  printf 'product_id=%s\n' "${PRODUCT_ID:-unknown}"
  printf 'test_env=%s\n' "$TEST_ENV"
  printf 'git_commit=%s\n' "$GIT_COMMIT"
  printf 'git_dirty=%s\n' "$GIT_DIRTY"
  printf 'db_snapshot_sha256=%s\n' "$DB_SNAPSHOT_SHA256"
  printf 'k6_version=%s\n' "$K6_VERSION"
  printf 'runner_host=%s\n' "$RUNNER_HOST"
  printf 'runner_os=%s\n' "$RUNNER_OS"
  printf 'extra_args=%s\n' "$EXTRA_ARGS"
  for setting in \
    IMAGE_RPS IMAGE_DURATION IMAGE_PRE_ALLOCATED_VUS IMAGE_MAX_VUS \
    IMAGE_API_PAGE_SIZE IMAGE_INVENTORY_BATCH_SIZE \
    IMAGE_FAILURE_RATE IMAGE_P95_MS IMAGE_P99_MS IMAGE_CACHE_HIT_RATE \
    EXPECTED_PUBLIC_PRODUCT_COUNT EXPECTED_UNIQUE_IMAGE_COUNT \
    BASELINE_DURATION BASELINE_PUBLIC_RPS BASELINE_MEMBER_RPS BASELINE_LOGIN_RPS \
    READ_RPS READ_DURATION LOGIN_RPS LOGIN_DURATION \
    STRESS_START_RPS STRESS_RPS_1 STRESS_RPS_2 STRESS_RPS_3 \
    SPIKE_BASE_RPS SPIKE_PEAK_RPS SOAK_DURATION \
    SOAK_PUBLIC_RPS SOAK_MEMBER_RPS SOAK_LOGIN_RPS; do
    if [[ -v "$setting" ]]; then
      printf 'setting_%s=%s\n' "$setting" "${!setting}"
    fi
  done
} > "$RESULT_PREFIX-metadata.txt"

set +e
k6 run \
  --summary-export "$RESULT_PREFIX-summary.json" \
  --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99),count" \
  --tag "run_id=$K6_RUN_ID" \
  --tag "test_env=$TEST_ENV" \
  --tag "git_commit=$GIT_COMMIT" \
  --tag "db_snapshot=$DB_SNAPSHOT_SHA256" \
  "$@" \
  "$SCRIPT_PATH"
K6_EXIT=$?
set -e

{
  printf 'finished_at_utc=%s\n' "$(date -u +%Y%m%dT%H%M%SZ)"
  printf 'k6_exit_code=%s\n' "$K6_EXIT"
} >> "$RESULT_PREFIX-metadata.txt"

if command -v node >/dev/null 2>&1; then
  node "$ROOT_DIR/tools/write-run-report.mjs" \
    "$RESULT_PREFIX-summary.json" \
    "$RESULT_PREFIX-metadata.txt" \
    "$RESULT_PREFIX-report.md" \
    "$K6_EXIT"
else
  echo "node command was not found; Markdown report generation was skipped." >&2
fi

echo "k6 summary: $RESULT_PREFIX-summary.json"
echo "run metadata: $RESULT_PREFIX-metadata.txt"
if [[ -f "$RESULT_PREFIX-report.md" ]]; then
  echo "run report: $RESULT_PREFIX-report.md"
fi

exit "$K6_EXIT"
