import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const workspace = resolve(root, '../..');
const source = 'k6/results/order-index-a3-recheck-20261008T051209Z/a3-comparison.json';
const body = readFileSync(resolve(workspace, source));
const record = JSON.parse(body.toString('utf8'));
if (record.result !== 'BLOCKED_AT_WARMUP_THRESHOLDS' || record.execution_count !== 0 || !record.original_a3_retained) {
  throw new Error('Unexpected recheck outcome; review the report before changing this snapshot.');
}
const pick = (row) => ({ run_id: row.run_id, raw_sha256: row.raw_sha256,
  raw_native_validation: row.raw_native_validation, started_at_utc: row.started_at_utc, finished_at_utc: row.finished_at_utc,
  orders: row.orders, overall: row.overall, http_requests: row.http_requests, check_passes: row.check_passes,
  http_failure_rate: row.http_failure_rate, check_failures: row.check_failures, dropped_iterations: row.dropped_iterations,
  k6_exit_code: row.k6_exit_code, thresholds_passed: row.thresholds_passed,
  failed_thresholds: row.failed_thresholds, server: row.server });
const output = { schema_version: 1, extracted_at_utc: new Date().toISOString(),
  source: { repository_relative_path: source, sha256: createHash('sha256').update(body).digest('hex') },
  result: record.result, planned_measured_runs: record.planned_repeat_count, completed_measured_runs: record.execution_count,
  original_a3_retained: record.original_a3_retained, conditions_comparison: record.conditions_comparison,
  original_phase: '5-minute measured run', followup_phase: '2-minute warmup only; 5-minute run not started',
  original: pick(record.original), followup_warmup: pick(record.recheck),
  invalidation_evidence: record.invalidation_evidence, interpretation: record.interpretation,
  limits: record.limits,
};
writeFileSync(resolve(root, 'data/a3-recheck.json'), JSON.stringify(output, null, 2) + '\n');
console.log(JSON.stringify({ result: 'PASS', measurement_result: output.result,
  original_a3_retained: output.original_a3_retained, measured_runs: output.completed_measured_runs }, null, 2));
