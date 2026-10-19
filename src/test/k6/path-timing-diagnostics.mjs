import { createReadStream, readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { createHash } from 'node:crypto';

export const timingNames = ['http_req_duration', 'http_req_waiting', 'http_req_receiving',
  'http_req_sending', 'http_req_blocked', 'http_req_connecting', 'http_req_tls_handshaking'];

export function statistics(values) {
  if (values.some((value) => !Number.isFinite(value) || value < 0)) throw new Error('Invalid timing.');
  if (!values.length) return { count: 0 };
  const sorted = [...values].sort((a, b) => a - b);
  const percentile = (fraction) => {
    const rank = fraction * (sorted.length - 1), lower = Math.floor(rank);
    return sorted[lower] + (sorted[Math.ceil(rank)] - sorted[lower]) * (rank - lower);
  };
  return { count: values.length, average_ms: values.reduce((a, b) => a + b, 0) / values.length,
    median_ms: percentile(0.5), p95_ms: percentile(0.95), p99_ms: percentile(0.99), max_ms: sorted.at(-1) };
}

export function validateTimings(timings) {
  for (const key of timingNames) {
    if (!Number.isFinite(timings[key]) || timings[key] < 0) throw new Error('Missing/invalid timing.');
  }
  if (Math.abs(timings.http_req_duration - timings.http_req_sending - timings.http_req_waiting -
      timings.http_req_receiving) > 1e-6) throw new Error('Duration decomposition mismatch.');
}

export function summarizeRequests(rows) {
  rows.forEach((row) => validateTimings(row.timings));
  const slow = rows.filter((row) => row.timings.http_req_duration >= 250);
  const total = (group, key) => group.reduce((sum, row) => sum + row.timings[key], 0);
  const duration = total(slow, 'http_req_duration');
  return { count: rows.length,
    timings: Object.fromEntries(timingNames.map((key) => [key, statistics(rows.map((row) => row.timings[key]))])),
    new_tcp_requests: rows.filter((row) => row.timings.http_req_connecting > 0).length,
    new_tls_requests: rows.filter((row) => row.timings.http_req_tls_handshaking > 0).length,
    slow_count: slow.length, slow_new_tcp: slow.filter((row) => row.timings.http_req_connecting > 0).length,
    slow_new_tls: slow.filter((row) => row.timings.http_req_tls_handshaking > 0).length,
    slow_duration_sum_ms: duration,
    slow_component_share: Object.fromEntries(['sending', 'waiting', 'receiving'].map((key) =>
      [key, duration ? total(slow, `http_req_${key}`) / duration : null])),
    slow_by_endpoint: Object.fromEntries([...new Set(slow.map((row) => row.endpoint))].map((endpoint) =>
      [endpoint, slow.filter((row) => row.endpoint === endpoint).length])) };
}

export async function readRequests(path, summaryPath) {
  const requests = new Map(), hash = createHash('sha256');
  const stream = createReadStream(path);
  stream.on('data', (chunk) => hash.update(chunk));
  for await (const line of createInterface({ input: stream, crlfDelay: Infinity })) {
    const point = JSON.parse(line);
    if (point.type !== 'Point' || !timingNames.includes(point.metric)) continue;
    const { time, tags, value } = point.data;
    if (!Number.isFinite(Date.parse(time))) throw new Error('Invalid request timestamp.');
    const key = JSON.stringify([time, Object.keys(tags).sort().map((name) => [name, tags[name]])]);
    if (!requests.has(key)) requests.set(key, { endpoint: tags.endpoint ?? 'unlabeled', timings: {} });
    const row = requests.get(key);
    if (Object.hasOwn(row.timings, point.metric)) throw new Error('Ambiguous request join.');
    row.timings[point.metric] = value;
  }
  const rows = [...requests.values()], summary = JSON.parse(readFileSync(summaryPath, 'utf8'));
  const result = summarizeRequests(rows);
  if (summary.metrics.http_reqs.count !== rows.length) throw new Error('Native request count mismatch.');
  for (const name of timingNames) {
    const native = summary.metrics[name], actual = result.timings[name];
    if (Math.abs(native['p(95)'] - actual.p95_ms) > 1e-6 ||
        Math.abs(native['p(99)'] - actual.p99_ms) > 1e-6) throw new Error('Native quantile mismatch.');
  }
  return { rows, raw_sha256: hash.digest('hex'), overall: result,
    endpoints: Object.fromEntries([...new Set(rows.map((row) => row.endpoint))].map((endpoint) =>
      [endpoint, summarizeRequests(rows.filter((row) => row.endpoint === endpoint))])) };
}
