#!/usr/bin/env bash
set -euo pipefail
umask 077
url='http://127.0.0.1:8080/api/v1/categories'
out=$(mktemp "$HOME/coffeeprod-origin-local-XXXXXXXX.csv")
printf 'ended_at_utc,round,status,http_version,dns_seconds,tcp_seconds,ttfb_seconds,total_seconds\n' > "$out"
for ((round=0; round<60; round++)); do
  result=$(curl --noproxy '*' --silent --show-error --fail --max-time 10 --output /dev/null \
    --write-out '%{http_code},%{http_version},%{time_namelookup},%{time_connect},%{time_starttransfer},%{time_total}' "$url")
  if [[ "$result" != 200,* ]]; then
    printf 'Non-200 response; partial result preserved: %s\n' "$out" >&2
    exit 1
  fi
  printf '%s,%s,%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$round" "$result" >> "$out"
  sleep 1
done
printf 'Completed 60 localhost GET requests. CSV: %s\n' "$out"
printf 'Each curl opens a new localhost connection; not a persistent-connection mixed-load test.\n'
