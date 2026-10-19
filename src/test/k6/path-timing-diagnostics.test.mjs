import assert from 'node:assert/strict';
import { statistics, validateTimings, summarizeRequests } from './path-timing-diagnostics.mjs';

let passed = 0;
function test(name, action) { action(); passed++; console.log(`PASS: ${name}`); }
const timings = { http_req_duration: 300, http_req_waiting: 297, http_req_receiving: 2,
  http_req_sending: 1, http_req_blocked: 25, http_req_connecting: 10, http_req_tls_handshaking: 15 };
test('empty sample stays unavailable', () => assert.deepEqual(statistics([]), { count: 0 }));
test('singleton percentiles', () => assert.equal(statistics([7]).p99_ms, 7));
test('linear interpolation', () => assert.equal(statistics([0, 100]).p95_ms, 95));
test('input order is preserved', () => { const values = [2, 1]; statistics(values); assert.deepEqual(values, [2, 1]); });
test('negative timing rejected', () => assert.throws(() => statistics([-1])));
test('nonfinite timing rejected', () => assert.throws(() => statistics([NaN])));
test('missing component rejected', () => assert.throws(() => validateTimings({})));
test('component sum validated', () => validateTimings(timings));
test('TLS is not added to request duration', () => assert.throws(() => validateTimings({ ...timings, http_req_duration: 315 })));
test('250ms boundary is inclusive', () => assert.equal(summarizeRequests([
  { endpoint: 'orders', timings: { ...timings, http_req_duration: 250, http_req_waiting: 247 } }]).slow_count, 1));
test('slow shares reconcile', () => {
  const result = summarizeRequests([{ endpoint: 'login', timings }]);
  assert.equal(result.slow_component_share.waiting, 0.99);
  assert.equal(Object.values(result.slow_component_share).reduce((a, b) => a + b), 1);
  assert.equal(result.slow_new_tls, 1);
});
test('no slow samples do not imply zero waiting share', () => {
  const result = summarizeRequests([{ endpoint: 'orders', timings: { ...timings, http_req_duration: 3, http_req_waiting: 0 } }]);
  assert.equal(result.slow_component_share.waiting, null);
});
console.log(`Passed ${passed}/${passed} offline checks.`);
