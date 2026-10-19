import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const workspace = resolve(root, '../..');
const sources = [];
function readSource(id, path) {
  const body = readFileSync(resolve(workspace, path));
  sources.push({ id, repository_relative_path: path, sha256: createHash('sha256').update(body).digest('hex') });
  return JSON.parse(body.toString('utf8'));
}
const http = readSource('http_index_comparison', 'k6/results/order-index-after-20261007T123425Z/http-comparison.json');
const sql = readSource('sql_plan_comparison', 'k6/results/order-index-after-20261007T123425Z/sql-plan-comparison.json');
const paths = readSource('order_path_comparison', 'k6/results/order-path-20261008T034841Z/path-comparison.json');
const local = readSource('local_order_regression', '.md/improvement/monitoring/evidence-20261008/order-service-rerun.json');
const live = readSource('deployed_order_regression', 'k6/results/order-index-after-supplement-20261007T152042Z/order-read-functional-regression.json');
const validation = readSource('path_validation', 'k6/results/order-path-20261008T034841Z/analysis-validation.json');
const post = readSource('post_suite_check', 'k6/results/order-path-20261008T034841Z/post-suite-check.json');
const repair = readSource('test_serialization_repair', 'k6/results/order-path-20261008T034841Z/serialization-preflight-repair.json');
const median = (values) => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
const sum = (rows, key) => rows.reduce((total, row) => total + row[key], 0);
const assert = (condition, message) => { if (!condition) throw new Error(message); };
assert(http.repetition_complete && http.after_cross_session && http.records.before.length === 3 && http.records.after.length === 3,
  'Require complete 3/3 index comparison with supplement provenance.');
const mixed = Object.fromEntries(['before', 'after'].map((phase) => [phase, http.records[phase].map((row, index) => {
  assert(row.raw_summary_validation === 'PASS' && row.http_failure_rate === 0 && row.check_failures === 0 && row.dropped_iterations === 0,
    'HTTP source validation failed.');
  return { run_id: row.run_id, display_id: `${phase === 'before' ? 'B' : 'A'}${index + 1}`,
    supplemental: phase === 'after' && index === 2, order_samples: row.orders.count,
    order_p95_ms: row.orders.p95_ms, order_p99_ms: row.orders.p99_ms,
    overall_p95_ms: row.overall.p95_ms, overall_p99_ms: row.overall.p99_ms,
    http_requests: row.http_requests, check_passes: row.check_passes,
    http_failure_rate: row.http_failure_rate, check_failures: row.check_failures, dropped_iterations: row.dropped_iterations,
    server_order_rolling_p95_min_ms: row.server.order_server_rolling_p95_ms.sample_min,
    server_order_rolling_p95_max_ms: row.server.order_server_rolling_p95_ms.sample_max,
    cpu_sample_max: row.server.cpu_sample_max, pending_sample_max: row.server.pending_sample_max,
    raw_sha256: row.metric_file_sha256 };
})]));
const medians = {};
for (const metric of ['order_p95_ms', 'order_p99_ms', 'overall_p95_ms', 'overall_p99_ms']) {
  const before = median(mixed.before.map((row) => row[metric]));
  const after = median(mixed.after.map((row) => row[metric]));
  medians[metric] = { before, after, reduction_percent: (before - after) / before * 100 };
}
assert(Math.abs(medians.order_p95_ms.after - http.comparison.orders.p95_ms.after_run_median) < 1e-7,
  'Derived median differs from reviewed analysis.');
function scans(node) {
  const current = node['Relation Name'] && ['orders', 'order_item'].includes(node['Relation Name']) ? [{
    relation: node['Relation Name'], type: node['Node Type'], parallel_aware: node['Parallel Aware'],
    index: node['Index Name'] ?? null, rows_per_loop: node['Actual Rows'], loops: node['Actual Loops'],
  }] : [];
  return [...current, ...(node.Plans ?? []).flatMap(scans)];
}
const queryNames = ['주문 ID 페이지', '회원 주문 count', '주문상품 join(축약)'];
const sqlQueries = queryNames.map((name, index) => {
  const before = sql.before.plans[index];
  const after = sql.after.map((run) => run.summaries[index]);
  assert(after.length === 3 && after.every((row) => row.rows === before.Plan['Actual Rows']), 'SQL result rows differ.');
  return { name, before_execution_ms: before['Execution Time'], before_runs: 1,
    after_execution_ms: after.map((row) => row.execution_ms),
    after_execution_median_ms: median(after.map((row) => row.execution_ms)),
    returned_rows: before.Plan['Actual Rows'], before_scans: scans(before.Plan),
    after_scans: after[0].scans.filter((scan) => ['orders', 'order_item'].includes(scan.relation))
      .map(({ type, relation, index, rows_per_loop, loops }) => ({ type, relation, index, rows_per_loop, loops })) };
});
assert(paths.records.length === 6 && paths.records.every((row) => row.validation.startsWith('PASS') && row.server.histogram_p99_reproduction === 'PASS'),
  'Require six reviewed path measurements.');
const pathRecords = paths.records.map((row) => ({ run_id: row.run_id, pair: row.pair, route: row.route,
  order_samples: row.duration.count, p95_ms: row.duration.p95_ms, p99_ms: row.duration.p99_ms,
  slow_at_least_250ms: row.slow_at_least_250ms, protocols: row.protocols,
  server_rolling_p99_min_ms: row.server.rolling_p99_sample_min_ms,
  server_rolling_p99_max_ms: row.server.rolling_p99_sample_max_ms,
  raw_sha256: row.raw_sha256 }));
assert(sum(pathRecords, 'order_samples') === 1443 && local.tests === 7 && local.failures === 0 && local.errors === 0 &&
  live.checks.length === 13 && live.checks.every((check) => check.passed) && validation.offline_script_checks === 9,
  'Regression/count source mismatch.');
const output = {
  schema_version: 1, title: 'CoffeeProd 1차 개선: 주문 조회 인덱스',
  evidence_period_kst: ['2026-10-07', '2026-10-08'], extracted_at_utc: new Date().toISOString(),
  sources, metric_definition: 'p95/p99 values are medians of three per-run percentiles, not pooled percentiles.',
  environment: { postgres_version: sql.before.server_version, orders: sql.before.orders,
    order_items: sql.before.order_items, representative_member_orders: 501,
    before_revision: '99bfed04c746ec9ab8c32ac96de7fbba0446a84b',
    after_revision: 'bb40a8b2643f38856e0a0d2f8d8c4d951f4ceb8d',
    deployment_and_unchanged_data_evidence: 'USER_VERIFIED; not a fresh remote inventory for this report' },
  workload: { public_iterations_per_second: 15, member_iterations_per_second: 4, login_iterations_per_second: 1,
    measured_duration: '5m per run', warmup_duration: '2m', repeats_per_side: 3, cooldown_seconds: 90,
    after_cross_session: true, order_page: 0, order_page_size: 10, random_endpoint_selection: true },
  mixed, medians,
  totals: Object.fromEntries(['before', 'after'].map((phase) => [phase, {
    http_requests: sum(mixed[phase], 'http_requests'), check_passes: sum(mixed[phase], 'check_passes'),
    order_samples: sum(mixed[phase], 'order_samples'), http_failures: 0, check_failures: 0, dropped_iterations: 0,
  }])),
  sql: { queries: sqlQueries, before_cache_context: 'Single diagnostic run with more shared reads',
    after_cache_context: 'Three runs with mostly shared hits', interpretation: 'SQL execution time, not API p95; reduced diagnostic projection differs from ORM SQL' },
  path_comparison: { rps: 2, measured_duration: '2m per route and pair', pairs: 3,
    account_index: 0, total_order_requests: sum(pathRecords, 'order_samples'), records: pathRecords,
    medians: paths.medians, cache_status_after_suite: post.headers.find((row) => row.route === 'public').cf_cache_status,
    interpretation: 'Path diagnostic only; SSH, TLS, protocols and windows differ, not pure Cloudflare cost' },
  regressions: { local_h2_order_service: { passed: local.tests, failed: local.failures, errors: local.errors, skipped: local.skipped },
    deployed_order_reads: { passed: live.checks.length, failed: 0 }, offline_script_safety: { passed: validation.offline_script_checks, failed: 0 } },
  failures_preserved: { initial_snapshot_check_failures: repair.failed_snapshot_checks,
    cause: repair.fix, earlier_scrape_guard_and_interrupted_run_excluded: true },
  limits: ['A3 is a separate session after re-warmup; no uninterrupted 3-run claim.',
    'Network/cache/time windows and unverified between-run OS package changes prevent pure causal attribution.',
    'Overall mixed latency increased; A3 order p99 increased.',
    'No purchase writes, real payments, full current regression suite, frontend or capacity proof.'],
};
mkdirSync(resolve(root, 'data'), { recursive: true });
writeFileSync(resolve(root, 'data/metrics.json'), JSON.stringify(output, null, 2) + '\n');
console.log(JSON.stringify({ result: 'PASS', mixed_after_requests: output.totals.after.http_requests,
  path_order_requests: output.path_comparison.total_order_requests, order_p95_reduction_percent: medians.order_p95_ms.reduction_percent }, null, 2));
