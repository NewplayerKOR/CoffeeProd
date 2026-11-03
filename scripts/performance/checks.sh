#!/bin/sh
set -eu
umask 022
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "${PERF_CANDIDATE:-}" in [1-8]) ;; *) echo 'Invalid candidate' >&2; exit 2;; esac
for identifier in "${PERF_RUN_ID:-}" "${PERF_SAMPLE:-}" "${PERF_VARIANT:-}"; do
  case "$identifier" in ''|*[!a-zA-Z0-9_-]*) echo 'Invalid result identifier' >&2; exit 2;; esac
done
case "${PERF_PHASE:-}" in before|after) ;; *) echo 'Invalid phase' >&2; exit 2;; esac
target="${PERF_RESULTS:-/results}/$PERF_RUN_ID/$PERF_PHASE/$PERF_SAMPLE/diagnostics/case$PERF_CANDIDATE-$PERF_VARIANT"
test ! -e "$target/console.log"
mkdir -p "$target"
code=0
./gradlew --offline --no-daemon perfTest --rerun-tasks --console=plain -PperfServer -PperfCandidate="$PERF_CANDIDATE" > "$target/console.log" 2>&1 || code=$?
report_code=0
sh "$script_dir/export-reports.sh" build/test-results/perfTest build/reports/tests/perfTest "$target" > "$target/artifact-export.log" 2>&1 || report_code=$?
if [ "$report_code" -ne 0 ]; then cat "$target/artifact-export.log" >&2; fi
if [ "$code" -eq 0 ]; then code=$report_code; fi
exit "$code"
