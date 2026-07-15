#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCENARIO="${1:-smoke}"

if [[ $# -gt 0 ]]; then
  shift
fi

if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.env"
  set +a
fi

SCRIPT_PATH="$ROOT_DIR/scripts/$SCENARIO.js"
if [[ ! -f "$SCRIPT_PATH" ]]; then
  echo "Unknown scenario: $SCENARIO" >&2
  echo "Available: smoke, read-load, login-load, order-concurrency" >&2
  exit 1
fi

mkdir -p "$ROOT_DIR/results"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

k6 run \
  --summary-export "$ROOT_DIR/results/$SCENARIO-$TIMESTAMP-summary.json" \
  "$@" \
  "$SCRIPT_PATH"
