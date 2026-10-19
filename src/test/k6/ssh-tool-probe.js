import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { sha256 } from 'k6/crypto';
import { summaryTrendStats } from '../../../k6/lib/common.js';
import { approvedSshBaseUrl } from './ssh-probe-target.mjs';

const mode = __ENV.SSH_PROBE_MODE;
const baseUrl = approvedSshBaseUrl(__ENV.SSH_PROBE_BASE_URL);
if (__ENV.SSH_PROBE_APPROVED !== 'true' || !['fresh', 'reuse'].includes(mode) ||
    !__ENV.SSH_PROBE_SUMMARY || !/^[a-f0-9]{64}$/.test(__ENV.SSH_CATEGORY_DIGEST ?? '')) {
  throw new Error('Explicit fixed SSH probe configuration is required.');
}
export const options = {
  scenarios: { probe: { executor: 'shared-iterations', vus: 1, iterations: 15, maxDuration: '45s' } },
  minIterationDuration: '1s', noConnectionReuse: mode === 'fresh',
  summaryTrendStats,
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate==0'] },
};
export default function probe() {
  const started = Date.now();
  const response = http.get(`${baseUrl}/api/v1/categories`, {
    timeout: '10s', redirects: 0, headers: { Accept: 'application/json',
      'Accept-Encoding': 'identity', 'User-Agent': 'CoffeeProd-QA-SSH-Tool-Probe' },
    tags: { endpoint: 'ssh-categories', connection_mode: mode },
  });
  const wallElapsedMs = Date.now() - started;
  let valid = false, digest = null;
  try {
    const body = response.json();
    if (response.status === 200 && body.status === 200 && Array.isArray(body.data) && body.data.length > 0) {
      const canonical = JSON.stringify(body.data, (_, value) => value !== null && typeof value === 'object' && !Array.isArray(value)
        ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, value[key]])) : value);
      digest = sha256(canonical, 'hex'); valid = digest === __ENV.SSH_CATEGORY_DIGEST;
    }
  } catch (_) { valid = false; }
  check(response, { 'SSH category contract and digest match': () => valid && response.proto === 'HTTP/1.1' });
  console.log(JSON.stringify({ type: 'safe-ssh-tool-probe', round: exec.scenario.iterationInTest,
    ended_at_utc: new Date().toISOString(), mode, status: response.status, protocol: response.proto,
    wall_elapsed_ms: wallElapsedMs, category_digest: digest, valid, timings: response.timings }));
  if (!valid || response.proto !== 'HTTP/1.1') exec.test.abort('SSH category contract failed; body is not logged.');
}
export function handleSummary(data) {
  const metrics = Object.fromEntries(Object.entries(data.metrics).map(([name, metric]) => [name, metric.values ?? metric]));
  return { [__ENV.SSH_PROBE_SUMMARY]: JSON.stringify({ metrics, root_group: data.root_group,
    plan: { iterations: 15, mode, no_connection_reuse: mode === 'fresh', writes: false } }, null, 2) };
}
