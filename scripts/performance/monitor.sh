#!/usr/bin/env bash
set -Eeuo pipefail
# Called only by the guarded phase2 CLI; bounded to 15 minutes even if the client disappears.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$root"
dest="${1:?}"; expected="${2:?}"
[[ "$dest" == "$root"/build/perf-phase2/* && "$dest" != *..* ]] || exit 2
set -a; . scripts/performance/.env.perf; set +a
[[ "$PERF_OWNER" == "$expected" ]] || exit 2
compose=(docker compose --env-file scripts/performance/.env.perf -p coffeeprod-perf-phase2 -f scripts/performance/compose.yml)
trap 'exit 0' INT TERM
deadline=$((SECONDS+900))
while ((SECONDS<deadline)); do
  mapfile -t ids < <("${compose[@]}" ps -q)
  for id in "${ids[@]}"; do [[ "$(docker inspect --format '{{index .Config.Labels "coffeeprod.perf.owner"}}' "$id")" == "$expected" ]] || exit 2; done
  date -u +%Y-%m-%dT%H:%M:%SZ >> "$dest/monitor-times.txt"
  docker stats --no-stream --format '{{json .}}' "${ids[@]}" >> "$dest/monitor-stats.jsonl"
  "${compose[@]}" exec -T db psql -X -U perf_phase2 -d coffeeprod_perf_phase2 -Atc "select json_build_object('at',now(),'active',count(*) filter(where state='active'),'lockWait',count(*) filter(where wait_event_type='Lock')) from pg_stat_activity where datname=current_database()" >> "$dest/monitor-db.jsonl"
  sleep 1
done
