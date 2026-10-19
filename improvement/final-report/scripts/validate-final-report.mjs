import assert from 'node:assert/strict';
import { readFileSync, existsSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const workspace = resolve(root, '../..');
const read = (path) => JSON.parse(readFileSync(resolve(workspace, path), 'utf8'));
const hash = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');
const data = read('improvement/final-report/visual/src/data.json');
const manifest = read('improvement/final-report/data/evidence-manifest.json');
const first = read('improvement/1차개선/data/metrics.json');
const mixed = read('improvement/2차개선/data/mixed-path-with-cloudflared.json');
const ssh = read('improvement/2차개선/data/ssh-client-comparison.json');
const markdownPath = resolve(workspace, 'improvement/FINAL_REPORT.md');
const markdown = new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(markdownPath));
const checks = [], verify = (condition, name) => { assert.ok(condition, name); checks.push(name); };
for (const source of manifest.sources) verify(hash(resolve(workspace, source.path)) === source.sha256, `${source.path}: SHA-256`);
const rows = (id) => data.queries[id].rows;
verify(Object.keys(data.queries).length === 9, 'Nine bounded source queries');
verify(data.report.asOf === '2026-10-12', 'Evidence cutoff retained');
const median = (values) => [...values].sort((a, b) => a - b)[1];
const order = rows('order_summary')[0];
for (const [phase, field] of [['before', 'beforeMs'], ['after', 'afterMs']]) {
  verify(Math.abs(median(first.mixed[phase].map((row) => row.order_p95_ms)) - order[field]) < 1e-9, `Order ${phase}: median`);
}
verify(Math.abs((order.beforeMs - order.afterMs) / order.beforeMs * 100 - 26.996362369899213) < 1e-9, 'Observed reduction independently recalculated');
verify(rows('order_runs').length === 6 && rows('order_runs')[5].run === 'A3*', 'Six repeats, A3 supplementation visible');
verify(rows('order_runs')[5].p99Ms === first.mixed.after[2].order_p99_ms, 'Original A3 p99 retained');
for (const row of rows('sql_plans')) {
  const original = first.sql.queries.find((query) => query.name === row.query);
  verify(row.beforeMs === original.before_execution_ms && row.afterMs === original.after_execution_median_ms && row.returnedRows === original.returned_rows, `${row.query}: timings/rows`);
}
for (const [index, key] of ['overall_p95_ms', 'overall_p99_ms'].entries()) {
  for (const [phase, field] of [['before', 'beforeMs'], ['after', 'afterMs']]) verify(rows('overall_summary')[index][field] === median(first.mixed[phase].map((row) => row[key])), `${key}: ${phase}`);
}
for (const row of rows('route_comparison')) {
  for (const [route, field] of [['public', 'publicMs'], ['origin', 'sshMs']]) {
    const original = mixed.records.find((record) => record.phase === 'measured' && record.route === route);
    const value = row.endpoint === '전체 혼합' ? original.overall.p99_ms : original.endpoints[row.endpoint === '로그인' ? 'login' : 'my-orders'].p99_ms;
    verify(row[field] === value, `${row.endpoint}: ${route}`);
  }
}
for (const [index, route] of ['windows', 'git'].entries()) {
  const row = rows('ssh_components')[index], original = ssh.routes[route].timings;
  verify(Math.abs(row.waitingMs + row.receivingMs - row.totalMs) < 1e-8, `${route}: mean components reconcile`);
  verify(row.medianMs === original.http_req_duration.median_ms && row.p99Ms === original.http_req_duration.p99_ms, `${route}: native percentiles`);
}
verify(markdown.includes('미확정') && markdown.includes('303.79ms') && markdown.includes('별도 재예열'), 'Material caveats present');
verify(markdown.includes('전체 API 27% 개선') && markdown.includes('사용하지 않습니다'), 'Unsupported portfolio claims explicitly excluded');
for (const link of markdown.matchAll(/\]\(([^)]+)\)/g)) {
  if (!/^https?:/.test(link[1])) verify(existsSync(resolve(dirname(markdownPath), link[1])), `Markdown link: ${link[1]}`);
}
const captures = read('improvement/final-report/data/figure-capture.json');
verify(captures.figures.length === 5, 'Five native chart exports');
const figures = captures.figures.map((figure) => {
  const path = resolve(root, 'figures', figure.file), body = readFileSync(path);
  verify(body.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) && body.readUInt32BE(16) === figure.width && body.readUInt32BE(20) === figure.height, `${figure.file}: PNG dimensions`);
  return { ...figure, sha256: hash(path), bytes: body.length };
});
verify(!/"(?:email|password|accessToken|refreshToken|authorization)"\s*:/i.test(JSON.stringify(data)), 'No account/token fields in reviewed snapshot');
verify(!/C:\\\\|192\.168\./.test(JSON.stringify(data)), 'No absolute local paths or private IPs in report snapshot');
const report = { generated_at_utc: new Date().toISOString(), result: 'PASS', checks,
  figures, markdown_sha256: hash(markdownPath), snapshot_sha256: hash(resolve(root, 'visual/src/data.json')),
  authoring_validation_only: true, new_load_test: false,
  visual_review: 'Recorded separately in visual-review.json; this validator does not perform browser inspection.' };
writeFileSync(resolve(root, 'data/ARTIFACT_QA.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ result: 'PASS', checks: checks.length, figures: figures.length, new_load_test: false }));
