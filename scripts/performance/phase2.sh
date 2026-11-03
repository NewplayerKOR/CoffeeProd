#!/usr/bin/env bash
set -Eeuo pipefail
export LC_ALL=C.UTF-8
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
env_file="$root/scripts/performance/.env.perf"
manifest="$root/scripts/performance/.manifest.json"
compose_file="$root/scripts/performance/compose.yml"
project=coffeeprod-perf-phase2
fail() { echo "$*" >&2; exit 2; }
valid_id() { [[ "$1" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$ ]] || fail 'Invalid result identifier'; }
field() { sed -n 's/^  "'"$1"'": "\([a-zA-Z0-9_-]*\)",*$/\1/p' "$manifest"; }
verify_source() {
  [[ -f "$manifest" && -f scripts/performance/.source-sha256.txt ]] || fail 'Create and transfer the manifest bundle from the local PC first'
  sha256sum --check --status scripts/performance/.source-sha256.txt || fail 'Source hash mismatch; regenerate the local bundle'
  unexpected="$(comm -23 <(find src/main src/test/performance scripts/performance k6/phase2 -type f ! -name '.*' | LC_ALL=C sort) <(awk '{print $2}' scripts/performance/.source-sha256.txt | LC_ALL=C sort))"
  [[ -z "$unexpected" ]] || fail 'Unmanifested measurement/source file found; regenerate the bundle'
}
random_hex() { od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; }
command="${1:-help}"; shift || true
run_id= phase=before variant=catalog candidate=1 sample=manual
while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-id) run_id="${2:?}"; shift 2;;
    --phase) phase="${2:?}"; shift 2;;
    --variant) variant="${2:?}"; shift 2;;
    --candidate) candidate="${2:?}"; shift 2;;
    --sample) sample="${2:?}"; shift 2;;
    *) fail 'Unknown phase2 argument';;
  esac
done
[[ "$phase" == before || "$phase" == after ]] || fail 'Phase must be before or after'
valid_id "$variant"
valid_id "$sample"
[[ "$candidate" =~ ^[1-8]$ ]] || fail 'Candidate must be 1..8'
verify_source
if [[ "$command" == init ]]; then
  [[ ! -e "$env_file" ]] || fail 'Existing identity retained. Use sync-source after transferring a reviewed source bundle.'
  bind="${PERF_BIND_IP:-127.0.0.1}"
  [[ "$bind" =~ ^(127\.0\.0\.1|10\.[0-9]+\.[0-9]+\.[0-9]+|192\.168\.[0-9]+\.[0-9]+|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]+\.[0-9]+)$ ]] || fail 'Bind to localhost or a private LAN IPv4 address'
  IFS=. read -r -a octets <<< "$bind"; for octet in "${octets[@]}"; do ((10#$octet <= 255)) || fail 'Invalid bind address'; done
  owner="$(cat /proc/sys/kernel/random/uuid)"
  umask 077
  {
    printf 'PERF_OWNER=%s\nPERF_BIND_IP=%s\n' "$owner" "$bind"
    printf 'PERF_SOURCE_HASH=%s\nPERF_MEASUREMENT_HASH=%s\n' "$(field sourceHash)" "$(field measurementHash)"
    printf 'PERF_DB_PASSWORD=%s\nPERF_REDIS_PASSWORD=%s\nPERF_JWT_SECRET=%s\n' "$(random_hex)" "$(random_hex)" "$(random_hex)"
    printf 'PERF_APP_CPUS=2\nPERF_APP_MEMORY=2g\nPERF_DB_CPUS=2\nPERF_DB_MEMORY=2g\n'
  } > "$env_file"
  # Freeze registry inputs before building. Never use the operational .env.
  for spec in 'JDK eclipse-temurin:21-jdk-alpine' 'POSTGRES postgres:16-alpine' 'REDIS redis:7-alpine'; do
    read -r key image <<< "$spec"
    docker pull "$image" >/dev/null
    digest="$(docker image inspect --format '{{index .RepoDigests 0}}' "$image")"
    printf 'PERF_%s_IMAGE=%s\n' "$key" "$digest" >> "$env_file"
  done
  echo 'Dedicated identity created; database is not seeded until prepare.'; exit
fi
[[ -f "$env_file" ]] || fail 'Run init first'
# This file is locally generated, mode 600, and accepts only simple literal values.
[[ -z "$(grep -Ev '^[A-Z0-9_]+=[a-zA-Z0-9._:/@-]+$' "$env_file")" ]] || fail 'Malformed dedicated environment file'
set -a; . "$env_file"; set +a
[[ "$PERF_OWNER" =~ ^[a-f0-9-]{36}$ ]] || fail 'Missing owner identity'
if [[ "$command" == sync-source ]]; then
  sed -i "s/^PERF_SOURCE_HASH=.*/PERF_SOURCE_HASH=$(field sourceHash)/;s/^PERF_MEASUREMENT_HASH=.*/PERF_MEASUREMENT_HASH=$(field measurementHash)/" "$env_file"
  echo 'Source identity updated; pinned images and synthetic credentials retained.'; exit
fi
[[ "$PERF_SOURCE_HASH" == "$(field sourceHash)" && "$PERF_MEASUREMENT_HASH" == "$(field measurementHash)" ]] || fail 'Environment and bundle differ; use sync-source'
compose=(docker compose --env-file "$env_file" -p "$project" -f "$compose_file")
"${compose[@]}" config --quiet
guard() {
  local id identity
  for service in db redis mock-pg app; do
    id="$("${compose[@]}" ps -q "$service")"; if [[ "$service" == app && -z "$id" ]]; then continue; fi
    [[ -n "$id" ]] || fail "Missing dedicated service: $service"
    identity="$(docker inspect --format '{{index .Config.Labels "coffeeprod.perf.owner"}}|{{index .Config.Labels "com.docker.compose.project"}}' "$id")"
    [[ "$identity" == "$PERF_OWNER|$project" ]] || fail 'Container ownership mismatch'
    if [[ "$service" == mock-pg || "$service" == app ]]; then
      [[ "$(docker inspect --format '{{index .Config.Labels "coffeeprod.perf.source"}}' "$id")" == "$PERF_SOURCE_HASH" ]] || fail 'Built image and source identity differ; rebuild the dedicated stack'
    fi
  done
  [[ "$(docker inspect --format '{{json (index .NetworkSettings.Ports "5432/tcp")}}' "$("${compose[@]}" ps -q db)")" == null ]] || fail 'Database must not publish a host port'
  [[ "$(psql -Atc 'select current_database() || chr(124) || current_user')" == 'coffeeprod_perf_phase2|perf_phase2' ]] || fail 'Database identity mismatch'
}
psql() { "${compose[@]}" exec -T db psql -X -U perf_phase2 -d coffeeprod_perf_phase2 -v ON_ERROR_STOP=1 "$@"; }
runtime_lock() {
  uname -srm
  docker info --format '{{.Architecture}}|{{.NCPU}}|{{.MemTotal}}|{{.KernelVersion}}'
  printf '%s\n' "$PERF_JDK_IMAGE" "$PERF_POSTGRES_IMAGE" "$PERF_REDIS_IMAGE" "$PERF_APP_CPUS|$PERF_APP_MEMORY|$PERF_DB_CPUS|$PERF_DB_MEMORY"
  printf '%s' "$PERF_JWT_SECRET" | sha256sum
  for service in db redis mock-pg app; do
    id="$("${compose[@]}" ps --all -q "$service")"
    printf '%s|' "$service"; docker inspect --format '{{.HostConfig.NanoCpus}}|{{.HostConfig.Memory}}' "$id"
  done
  docker run --rm --network none --memory 256m --cpus 1 --label "coffeeprod.perf.owner=$PERF_OWNER" \
    -e JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -e PERF_MOCK_BIND_IP=127.0.0.1 \
    "coffeeprod-phase2:$PERF_SOURCE_HASH" dependencies /app/perf.jar
}
if [[ "$command" == status ]]; then "${compose[@]}" ps; exit; fi
guard
if [[ "$command" == stop ]]; then "${compose[@]}" stop; exit; fi
if [[ "$command" == manifest ]]; then
  cat "$manifest"; exit
fi
if [[ "$command" == owner ]]; then printf '%s\n' "$PERF_OWNER"; exit; fi
[[ -n "$run_id" ]] || fail '--run-id is required'; valid_id "$run_id"
result="$root/build/perf-phase2/$run_id"; execution="$result/$phase/$sample"; mkdir -p "$execution"
lock="$root/build/perf-phase2/.operation.lock"
[[ ! -f "$lock" ]] || fail 'Legacy lock file found; inspect it before removing'
if [[ "$command" == unlock ]]; then
  [[ -f "$lock/owner" ]] || fail 'No owned lock to release'
  token="$(cat "$lock/owner")"; [[ "$token" == "$PERF_OWNER:"* ]] || fail 'Lock owner mismatch'
  pid="${token##*:}"; [[ "$pid" =~ ^[0-9]+$ ]] || fail 'Invalid lock PID'
  ! kill -0 "$pid" 2>/dev/null || fail 'Lock process still exists; preserved'
  rm -- "$lock/owner"; rmdir -- "$lock"; echo 'Stale owned lock released'; exit
fi
mkdir "$lock" 2>/dev/null || fail 'Another phase2 operation owns the lock; inspect before using unlock'
printf '%s:%s\n' "$PERF_OWNER" "$$" > "$lock/owner"
cleanup_lock() { if [[ -f "$lock/owner" && "$(cat "$lock/owner")" == "$PERF_OWNER:$$" ]]; then rm -- "$lock/owner"; rmdir -- "$lock"; fi; }
trap cleanup_lock EXIT
snapshot="$result/baseline.dump"
if [[ "$command" == monitor-start ]]; then
  [[ ! -e "$execution/monitor.pid" ]] || fail 'Monitor evidence already exists'
  nohup bash "$root/scripts/performance/monitor.sh" "$execution" "$PERF_OWNER" < /dev/null > "$execution/monitor.log" 2>&1 9>&- &
  printf '%s\n' "$!" > "$execution/monitor.pid"
  exit
fi
if [[ "$command" == monitor-stop ]]; then
  [[ -f "$execution/monitor.pid" ]] || fail 'Missing monitor identity'
  pid="$(cat "$execution/monitor.pid")"; [[ "$pid" =~ ^[0-9]+$ ]] || fail 'Invalid monitor PID'
  if [[ -r "/proc/$pid/cmdline" ]]; then
    args="$(tr '\0' ' ' < "/proc/$pid/cmdline")"
    [[ "$args" == *scripts/performance/monitor.sh* && "$args" == *"$PERF_OWNER"* && "$args" == *"$execution"* ]] || fail 'Monitor PID identity mismatch; preserved'
    kill -TERM "$pid"
  fi
  exit
fi
vars=(-v member_count=10000 -v order_count=150000 -v review_count=30000 -v qna_count=20000 -v history_days=730 -v k6_user_count=200 -v seed_value=20260722 -v seed_end_date=2026-06-30)
if [[ "$command" == prepare ]]; then
  [[ ! -e "$snapshot" ]] || fail 'Baseline already exists; choose a new run ID'
  [[ "$(psql -Atc 'select count(*) from flyway_schema_history where success')" == 18 ]] || fail 'Actual V1-V18 migration required'
  "${compose[@]}" stop app >/dev/null
  psql < scripts/seed/catalog/sql/load_catalog.sql > "$result/catalog-seed.log"
  psql < scripts/seed/catalog/sql/verify_catalog.sql > "$result/catalog-verify.log"
  psql "${vars[@]}" < scripts/seed/commerce/sql/seed_commerce.sql > "$result/commerce-seed.log"
  psql "${vars[@]}" < scripts/seed/commerce/sql/verify_commerce.sql > "$result/commerce-verify.log"
  [[ "$(psql -Atc 'select (select count(*) from orders)::text || chr(124) || (select count(*) from order_item)::text || chr(124) || (select count(*) from product)::text')" == '150000|375000|192' ]] || fail 'Unexpected fixture counts'
  psql -c 'create extension if not exists pg_stat_statements' >/dev/null
  psql -c 'analyze' >/dev/null
  "${compose[@]}" exec -T db pg_dump -U perf_phase2 -d coffeeprod_perf_phase2 -Fc > "$snapshot"
  sha256sum "$snapshot" > "$result/baseline.sha256"
  cp "$manifest" "$result/baseline-manifest.json"
  runtime_lock > "$result/runtime-lock.txt"
  psql -qAt -v k6_user_count=200 < scripts/seed/commerce/sql/export_k6_users.sql > "$result/users.json"
  psql -Atc "select product_id from product where status='ON_SALE' order by product_id limit 20" > "$result/products.txt"
  psql -c 'select version,description,success from flyway_schema_history order by installed_rank' > "$result/migrations.txt"
  "${compose[@]}" up -d --wait app >/dev/null
  echo "Baseline prepared: $run_id"; exit
fi
if [[ "$command" == restore ]]; then
  [[ -f "$snapshot" ]] || fail 'Missing baseline snapshot'
  [[ ! -e "$execution/restore.log" ]] || fail 'Sample already exists; use a new sample ID'
  runtime_lock > "$execution/runtime-lock.txt"
  cmp --silent "$result/runtime-lock.txt" "$execution/runtime-lock.txt" || fail 'Host, image inputs or resource limits changed; collect a new baseline'
  sha256sum --check --status "$result/baseline.sha256" || fail 'Snapshot hash mismatch'
  "${compose[@]}" stop app >/dev/null
  psql -c "select pg_terminate_backend(pid) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()" >/dev/null
  "${compose[@]}" exec -T db pg_restore -U perf_phase2 -d coffeeprod_perf_phase2 --clean --if-exists --exit-on-error < "$snapshot" > "$execution/restore.log" 2>&1
  "${compose[@]}" exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli FLUSHDB' >/dev/null
  "${compose[@]}" restart --no-deps mock-pg >/dev/null
  if [[ "$variant" == recommendation1000 ]]; then psql < scripts/performance/recommendation1000.sql > "$execution/variant.log"; fi
  if [[ "$variant" =~ ^order(1|5|20)$ ]]; then
    psql -c "update product set stock_quantity=10000000 where status='ON_SALE'" >/dev/null
  fi
  psql -c 'analyze; select pg_stat_statements_reset()' >/dev/null
  "${compose[@]}" up -d --no-build --pull never --wait app >/dev/null
  cp "$manifest" "$execution/source-manifest.json"
  echo "Restored $run_id/$phase/$variant"; exit
fi
if [[ "$command" == write-state ]]; then
  [[ "$candidate" == 6 && "$variant" =~ ^order(1|5|20)$ ]] || fail 'Write state is only for the order workload'
  size="${variant#order}"
  psql -qAtc "select json_build_object('orders',(select count(*) from orders),'orderItems',(select count(*) from order_item),'mileageTotal',(select sum(mileage)::bigint from member),'products',(select json_agg(x order by x.\"productId\") from (select product_id as \"productId\",stock_quantity as stock from product where status='ON_SALE' order by product_id limit $size) x))"
  exit
fi
if [[ "$command" == checks ]]; then
  checks_image="coffeeprod-phase2-checks:$PERF_SOURCE_HASH"
  [[ "$(docker image inspect --format '{{index .Config.Labels "coffeeprod.perf.source"}}' "$checks_image" 2>/dev/null)" == "$PERF_SOURCE_HASH" ]] || fail 'Prebuild the checks image with compose --profile checks build checks before measuring'
  "${compose[@]}" stop app >/dev/null
  workers=false; [[ "$candidate" != 4 ]] || workers=true
  code=0
  PERF_WORKERS="$workers" "${compose[@]}" --profile checks run --rm --no-deps --pull never \
    -e "PERF_RUN_ID=$run_id" -e "PERF_PHASE=$phase" -e "PERF_SAMPLE=$sample" -e "PERF_CANDIDATE=$candidate" -e "PERF_VARIANT=$variant" -e "PERF_WORKERS=$workers" checks || code=$?
  restart_code=0
  "${compose[@]}" up -d --no-build --pull never --wait app >/dev/null || restart_code=$?
  if [[ "$restart_code" -ne 0 ]]; then echo "App restart failed (exit $restart_code); checks exit $code retained" >&2; fi
  if [[ "$code" -eq 0 ]]; then code=$restart_code; fi
  exit "$code"
fi
if [[ "$command" == collect ]]; then
  dest="$execution/collection-$candidate-$variant"; [[ ! -e "$dest" ]] || fail 'Collection exists; preserve it and choose a new sample ID'
  mkdir -p "$dest"
  uname -srm > "$dest/host.txt"; getconf _NPROCESSORS_ONLN >> "$dest/host.txt"
  if command -v free >/dev/null 2>&1; then free -m >> "$dest/host.txt"; else docker info --format 'Docker MemTotal={{.MemTotal}}' >> "$dest/host.txt"; fi
  docker version --format '{{.Server.Version}}' > "$dest/docker.txt"; docker compose version >> "$dest/docker.txt"
  mapfile -t ids < <("${compose[@]}" ps -q)
  docker stats --no-stream --format '{{json .}}' "${ids[@]}" > "$dest/container-stats.jsonl"
  for id in "${ids[@]}"; do docker inspect --format '{{.Name}}|{{.Image}}|{{.HostConfig.NanoCpus}}|{{.HostConfig.Memory}}' "$id"; done > "$dest/runtime.txt"
  psql -c "select calls,total_exec_time,rows,shared_blks_hit,shared_blks_read,query from pg_stat_statements order by total_exec_time desc limit 50" > "$dest/postgres-statements.txt"
  psql -c "select state,wait_event_type,wait_event,count(*) from pg_stat_activity where datname=current_database() group by 1,2,3" > "$dest/postgres-waits.txt"
  "${compose[@]}" logs --no-color --tail=200 app > "$dest/app.log" 2>&1
  echo "Collected $run_id/$phase/$candidate/$variant"; exit
fi
fail 'Expected init, sync-source, prepare, restore, checks, collect, manifest, status or stop'
