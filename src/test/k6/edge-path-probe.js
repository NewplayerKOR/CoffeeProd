import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { sha256 } from 'k6/crypto';
import { summaryTrendStats } from '../../../k6/lib/common.js';

if (__ENV.EDGE_PROBE_APPROVED !== 'true' || !__ENV.EDGE_PROBE_SUMMARY) {
  throw new Error('Explicit read-only probe approval and summary output are required.');
}
export const options = {
  scenarios: { probe: { executor: 'shared-iterations', vus: 1, iterations: 60, maxDuration: '5m' } },
  summaryTrendStats,
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate==0'] },
};
const targets = [
  { endpoint: 'public-categories', url: 'https://api-coffeeprod.ttagyulab.com/api/v1/categories' },
  { endpoint: 'origin-categories', url: 'http://127.0.0.1:18080/api/v1/categories' },
  { endpoint: 'edge-trace', url: 'https://api-coffeeprod.ttagyulab.com/cdn-cgi/trace' },
];
function canonical(value) {
  return JSON.stringify(value, (_, item) => item !== null && typeof item === 'object' && !Array.isArray(item)
    ? Object.fromEntries(Object.keys(item).sort().map((key) => [key, item[key]])) : item);
}
export default function probe() {
  const round = exec.scenario.iterationInTest;
  let digest = null;
  for (let offset = 0; offset < 3; offset++) {
    const target = targets[(round + offset) % 3];
    const response = http.get(target.url, { timeout: '10s', redirects: 0, tags: { endpoint: target.endpoint } });
    const ray = response.headers['Cf-Ray'] ?? '';
    const trace = target.endpoint === 'edge-trace';
    let valid = false, currentDigest = null, traceColo = null;
    if (trace) {
      // The trace body contains the client's IP: retain only the location code, never the body.
      traceColo = /^colo=([A-Z0-9]{3})$/m.exec(response.body ?? '')?.[1] ?? null;
      valid = response.status === 200 && Boolean(traceColo);
    } else {
      try {
        const body = response.json();
        valid = response.status === 200 && body.status === 200 && Array.isArray(body.data) && body.data.length > 0;
        if (valid) currentDigest = sha256(canonical(body.data), 'hex');
      } catch (_) { valid = false; }
      if (digest !== null && digest !== currentDigest) valid = false;
      digest = currentDigest;
    }
    check(response, { 'read-only probe contract succeeds': () => valid });
    console.log(JSON.stringify({ type: 'safe-probe', round, endpoint: target.endpoint,
      ended_at_utc: new Date().toISOString(), status: response.status, protocol: response.proto,
      colo: traceColo ?? /-([A-Z0-9]{3})$/.exec(ray)?.[1] ?? null,
      cache_status: response.headers['Cf-Cache-Status'] ?? null,
      category_digest: currentDigest, valid, timings: response.timings }));
    if (!valid) exec.test.abort('Probe contract failed; response bodies are not logged.');
  }
  sleep(1);
}
export function handleSummary(data) {
  const metrics = Object.fromEntries(Object.entries(data.metrics).map(([key, metric]) => [key, metric.values ?? metric]));
  return { [__ENV.EDGE_PROBE_SUMMARY]: JSON.stringify({ metrics, root_group: data.root_group,
    plan: { rounds: 60, requests_per_round: 3, vus: 1, writes: false, trace_body_exported: false } }, null, 2) };
}
