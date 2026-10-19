import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { createContext, SourceTextModule, SyntheticModule } from 'node:vm';

const token = 'synthetic-access-token-must-not-be-exported';
const fakeUser = { email: 'qa@example.invalid', password: 'synthetic-password-must-not-be-exported' };
const page = { number: 0, size: 10, totalElements: 2, content: [
  { orderId: 987654321, orderDate: '2026-10-08T00:00:00Z', firstProductName: 'Test beans' },
  { orderId: 987654320, orderDate: '2026-10-07T00:00:00Z', firstProductName: 'Test blend' },
] };
async function load(overrides = {}, pages = [page]) {
  const requests = [];
  const checkResults = [];
  let readIndex = 0;
  const environment = { BASE_URL: 'https://api-coffeeprod.ttagyulab.com', COMPARISON_ROUTE: 'public',
    COMPARISON_SUMMARY_PATH: 'synthetic-summary.json', PATH_COMPARISON_APPROVED: 'true', ...overrides };
  const context = createContext({ __ENV: environment, open: () => JSON.stringify([fakeUser]) });
  const check = (response, predicates) => {
    const results = Object.values(predicates).map((predicate) => predicate(response));
    checkResults.push(...results);
    return results.every(Boolean);
  };
  const response = (data) => ({ status: 200, json: (field) => field === 'status' ? 200 : data });
  const http = { get: (url) => { requests.push(['GET', url]); return response(pages[Math.min(readIndex++, pages.length - 1)]); },
    post: (url) => { requests.push(['POST', url]); return response(url.endsWith('/login') ? { accessToken: token } : null); } };
  const modules = {
    k6: new SyntheticModule(['check', 'fail'], function () {
      this.setExport('check', check); this.setExport('fail', (message) => { throw new Error(message); });
    }, { context }),
    'k6/http': new SyntheticModule(['default'], function () { this.setExport('default', http); }, { context }),
    'k6/data': new SyntheticModule(['SharedArray'], function () {
      this.setExport('SharedArray', class { constructor(name, factory) { return factory(); } });
    }, { context }),
    'k6/crypto': new SyntheticModule(['sha256'], function () {
      this.setExport('sha256', (value) => createHash('sha256').update(value).digest('hex'));
    }, { context }),
  };
  const common = new SourceTextModule(readFileSync(resolve('k6/lib/common.js'), 'utf8'), { context });
  await common.link((name) => modules[name]);
  const module = new SourceTextModule(readFileSync(resolve('src/test/k6/order-path-comparison.js'), 'utf8'), { context });
  await module.link((name) => name.endsWith('/common.js') ? common : modules[name]);
  await module.evaluate();
  return { namespace: module.namespace, requests, checkResults };
}
let passed = 0;
const { namespace, requests } = await load();
const setup = namespace.setup();
assert.equal(setup.accessToken, token);
namespace.default(setup);
namespace.teardown(setup);
assert.deepEqual(requests.map(([method, url]) => [method, new URL(url).pathname]), [
  ['POST', '/api/v1/auth/login'], ['GET', '/api/v1/orders'], ['GET', '/api/v1/orders'], ['POST', '/api/v1/auth/logout'],
]);
passed++;
const summary = namespace.handleSummary({ setup_data: setup,
  metrics: { http_reqs: { values: { count: 4 } } }, root_group: { checks: [] } });
const exported = summary['synthetic-summary.json'];
for (const forbidden of [token, fakeUser.email, fakeUser.password, 'accessToken', 'setup_data', '987654321', '987654320']) {
  assert.equal(exported.includes(forbidden), false, `Sensitive value/key exported: ${forbidden}`);
}
const safe = JSON.parse(exported);
assert.equal(safe.comparison.page_digest, setup.pageDigest);
assert.equal(safe.metrics.http_reqs.count, 4);
passed++;
await assert.rejects(load({ PATH_COMPARISON_APPROVED: 'false' }), /explicitly approved/);
passed++;
await assert.rejects(load({ BASE_URL: 'https://example.invalid' }), /explicitly approved/);
passed++;
await assert.rejects(load({ COMPARISON_RPS: '3' }), /at most 2/);
passed++;
const { namespace: changed } = await load({ EXPECTED_PAGE_DIGEST: '0'.repeat(64) });
assert.throws(() => changed.setup(), /contract changed/);
passed++;
const reordered = { ...page, content: page.content.map((row) => Object.fromEntries(Object.entries(row).reverse())) };
const reorderedTest = await load({}, [page, reordered]);
reorderedTest.namespace.default(reorderedTest.namespace.setup());
assert.equal(reorderedTest.checkResults.every(Boolean), true, 'Object key order must not change snapshot semantics');
passed++;
const changedValue = { ...page, content: page.content.map((row, index) =>
  index === 0 ? { ...row, firstProductName: 'Changed test beans' } : row) };
const valueTest = await load({}, [page, changedValue]);
valueTest.namespace.default(valueTest.namespace.setup());
assert.equal(valueTest.checkResults.at(-1), false, 'Actual field changes must still fail');
passed++;
const orderTest = await load({}, [page, { ...page, content: [...page.content].reverse() }]);
orderTest.namespace.default(orderTest.namespace.setup());
assert.equal(orderTest.checkResults.at(-1), false, 'Array/order-date changes must still fail');
passed++;
console.log(`PASS: ${passed} offline script safety/contract checks. No real network or credentials used.`);
