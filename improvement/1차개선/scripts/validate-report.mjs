import { readFileSync, writeFileSync, existsSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const workspace = resolve(root, '../..');
const checks = [];
const assert = (condition, name) => {
  if (!condition) throw new Error(name);
  checks.push(name);
};
const hash = (body) => createHash('sha256').update(body).digest('hex');
const load = (path) => JSON.parse(readFileSync(resolve(root, path), 'utf8'));
const data = load('data/metrics.json');
const manifest = load('data/figure-manifest.json');
const median = (values) => [...values].sort((a, b) => a - b)[1];
const sum = (rows, key) => rows.reduce((value, row) => value + row[key], 0);
for (const phase of ['before', 'after']) {
  const rows = data.mixed[phase];
  assert(rows.length === 3, `${phase}: three completed repeats`);
  for (const metric of Object.keys(data.medians)) {
    assert(Math.abs(median(rows.map((row) => row[metric])) - data.medians[metric][phase]) < 1e-9,
      `${phase}: ${metric} median recalculation`);
  }
  for (const key of ['http_requests', 'check_passes', 'order_samples']) {
    assert(sum(rows, key) === data.totals[phase][key], `${phase}: ${key} sum`);
  }
  assert(rows.every((row) => row.http_failure_rate === 0 && row.check_failures === 0 && row.dropped_iterations === 0),
    `${phase}: preserved failure and dropped counts`);
}
for (const [key, values] of Object.entries(data.medians)) {
  assert(Math.abs((values.before - values.after) / values.before * 100 - values.reduction_percent) < 1e-9,
    `${key}: reduction recalculation`);
}
assert(sum(data.path_comparison.records, 'order_samples') === 1443, 'path: 1443 measured order requests');
for (const route of ['public', 'origin']) {
  for (const key of ['p95_ms', 'p99_ms']) {
    assert(Math.abs(median(data.path_comparison.records.filter((row) => row.route === route).map((row) => row[key]))
      - data.path_comparison.medians[route][key]) < 1e-9, `path: ${route} ${key} median`);
  }
}
assert(manifest.source_sha256 === hash(readFileSync(resolve(root, 'data/metrics.json'))), 'manifest: data fingerprint');
assert(manifest.images.length === 4, 'manifest: four figures');
for (const figure of manifest.images) {
  const body = readFileSync(resolve(root, figure.file));
  assert(hash(body) === figure.sha256 && body.length === figure.bytes, `${figure.file}: fingerprint`);
  assert(body.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
    && body.readUInt32BE(16) === figure.width && body.readUInt32BE(20) === figure.height,
    `${figure.file}: PNG dimensions`);
  assert(figure.non_white_samples > 200 && figure.renderer_bounds_check === 'PASS', `${figure.file}: renderer checks`);
}
for (const path of ['README.md', 'EVIDENCE.md', 'A3_RECHECK_20261008.md']) {
  const content = new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(resolve(root, path)));
  assert(!/[\u3040-\u30ff]/u.test(content), `${path}: Korean report without stray Japanese text`);
  for (const [, target] of content.matchAll(/\[[^\]]*\]\(([^)]+)\)/g)) {
    if (/^https?:\/\//.test(target)) continue;
    // The QA record is generated at the end of this validation.
    if (target === 'data/ARTIFACT_QA.json') continue;
    assert(existsSync(resolve(root, target)), `${path}: link ${target}`);
  }
}
const walk = (directory) => readdirSync(directory, { withFileTypes: true }).flatMap((item) =>
  item.isDirectory() ? walk(resolve(directory, item.name)) : [resolve(directory, item.name)]);
const files = walk(root);
assert(files.every((path) => !/(^|[/\\])(\.env[^/\\]*|users\.json|.*\.dump|.*\.pem)$/i.test(path)),
  'public folder: no environment, account, backup or private-key files');
const availableSources = data.sources.filter((source) => existsSync(resolve(workspace, source.repository_relative_path)));
for (const source of availableSources) {
  assert(hash(readFileSync(resolve(workspace, source.repository_relative_path))) === source.sha256,
    `original source: ${source.id} fingerprint`);
}
const followup = load('data/a3-recheck.json');
assert(followup.original_a3_retained && followup.completed_measured_runs === 0 && followup.result === 'BLOCKED_AT_WARMUP_THRESHOLDS',
  'A3 followup: original retained; failed warmup not counted as a measured repeat');
const followupSource = resolve(workspace, followup.source.repository_relative_path);
if (existsSync(followupSource)) assert(hash(readFileSync(followupSource)) === followup.source.sha256, 'A3 followup: source fingerprint');
const reviewed = process.argv.includes('--visual-reviewed');
const prior = existsSync(resolve(root, 'data/ARTIFACT_QA.json')) ? load('data/ARTIFACT_QA.json') : null;
const report = {
  generated_at_utc: new Date().toISOString(), result: 'PASS', checks,
  source_fingerprints_verified: availableSources.length,
  unavailable_original_sources: data.sources.length - availableSources.length,
  figures: manifest.images.map(({ file, sha256 }) => ({ file, sha256 })),
  visual_review: reviewed ? {
    status: 'PASS', method: 'Model inspected all four PNGs with view_image; corrected the SQL label/axis spacing and re-inspected it.',
    note: 'Caller confirms an actual visual review. This flag does not perform image inspection.',
  } : prior?.visual_review && JSON.stringify(prior.figures) === JSON.stringify(manifest.images.map(({ file, sha256 }) => ({ file, sha256 })))
    ? prior.visual_review : { status: 'NOT_RUN', method: 'Open all four PNGs before confirming a visual review.' },
  validation_makes_no_network_or_database_calls: true,
  file_count: files.length,
  documents: ['README.md', 'EVIDENCE.md', 'A3_RECHECK_20261008.md'].map((file) => ({ file: relative(root, resolve(root, file)),
    sha256: hash(readFileSync(resolve(root, file))) })),
};
writeFileSync(resolve(root, 'data/ARTIFACT_QA.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ result: report.result, checks: checks.length, original_sources: availableSources.length,
  visual_review: report.visual_review.status }, null, 2));
