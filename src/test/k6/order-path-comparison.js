import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { sha256 } from 'k6/crypto';
import {
  arrivalRateThresholds, env, integerEnv, isCommonSuccess,
  jsonParams, responseData, summaryTrendStats, testTags,
} from '../../../k6/lib/common.js';

const BASE_URL = env('BASE_URL').replace(/\/+$/, '');
const ROUTE = env('COMPARISON_ROUTE');
const SUMMARY_PATH = env('COMPARISON_SUMMARY_PATH');
const EXPECTED_DIGEST = env('EXPECTED_PAGE_DIGEST', '');
const PHASE = env('COMPARISON_PHASE', 'measured');
const users = new SharedArray('order path comparison users', () =>
  JSON.parse(open('../../../k6/data/users.json')));
if (env('PATH_COMPARISON_APPROVED', 'false') !== 'true' ||
    !['public', 'origin'].includes(ROUTE) ||
    BASE_URL !== (ROUTE === 'public' ? 'https://api-coffeeprod.ttagyulab.com' : 'http://127.0.0.1:18080')) {
  throw new Error('Only the explicitly approved public/origin order comparison is allowed.');
}
const rate = integerEnv('COMPARISON_RPS', 2, 1);
if (rate > 2 || !users[0]?.email || !users[0]?.password) {
  throw new Error('Use at most 2 iterations/s and an existing performance account.');
}

export const options = {
  summaryTrendStats,
  tags: { ...testTags('order-path-comparison'), route: ROUTE, comparison_phase: PHASE },
  scenarios: {
    orderReads: { executor: 'constant-arrival-rate', rate, timeUnit: '1s',
      duration: env('COMPARISON_DURATION', '2m'), preAllocatedVUs: 5, maxVUs: 10,
      gracefulStop: '30s', tags: { phase: 'load', endpoint: 'my-orders' } },
  },
  thresholds: {
    ...arrivalRateThresholds,
    'checks{phase:load}': ['rate==1'],
    'checks{phase:setup}': ['rate==1'],
    'checks{phase:teardown}': ['rate==1'],
    'http_req_failed{phase:load}': ['rate==0'],
    'http_req_duration{phase:load}': ['p(95)<500', 'p(99)<1000'],
  },
};

function pageSnapshot(response) {
  const page = responseData(response);
  if (!isCommonSuccess(response) || !Array.isArray(page?.content) ||
      page.content.length < 1 || page.content.length > 10 || page.number !== 0 ||
      page.size !== 10 || !Number.isInteger(page.totalElements) || page.totalElements < page.content.length) {
    return null;
  }
  const dates = page.content.map((order) => Date.parse(order.orderDate));
  if (dates.some((date) => !Number.isFinite(date)) || dates.some((date, index) => index > 0 && dates[index - 1] < date)) {
    return null;
  }
  // Response.json() exposes Go maps; sort object keys without changing array order or field values.
  const canonical = JSON.stringify({ totalElements: page.totalElements, content: page.content }, (_, value) =>
    value !== null && typeof value === 'object' && !Array.isArray(value)
      ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, value[key]])) : value);
  return { digest: sha256(canonical, 'hex'),
    totalElements: page.totalElements, contentCount: page.content.length };
}

export function setup() {
  const user = users[0];
  const login = http.post(`${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: user.email, password: user.password }),
    jsonParams(null, { phase: 'setup', endpoint: 'comparison-login' }));
  const accessToken = responseData(login)?.accessToken;
  if (!check(login, { 'comparison login succeeds': () => isCommonSuccess(login) && Boolean(accessToken) }, { phase: 'setup' })) {
    throw new Error('Comparison login failed; response body and credentials are not logged.');
  }
  const response = http.get(`${BASE_URL}/api/v1/orders?page=0&size=10`,
    jsonParams(accessToken, { phase: 'setup', endpoint: 'comparison-order-preflight' }));
  const snapshot = pageSnapshot(response);
  if (!snapshot || (EXPECTED_DIGEST && snapshot.digest !== EXPECTED_DIGEST)) {
    http.post(`${BASE_URL}/api/v1/auth/logout`, null,
      jsonParams(accessToken, { phase: 'setup', endpoint: 'comparison-cleanup' }));
    throw new Error('Order page contract changed; no order IDs or response bodies are logged.');
  }
  return { accessToken, pageDigest: snapshot.digest,
    totalElements: snapshot.totalElements, contentCount: snapshot.contentCount };
}

export default function readOrderPage(data) {
  const response = http.get(`${BASE_URL}/api/v1/orders?page=0&size=10`,
    jsonParams(data.accessToken, { phase: 'load', endpoint: 'my-orders' }));
  const snapshot = pageSnapshot(response);
  check(response, {
    'order list returns HTTP 200': (res) => res.status === 200,
    'order list returns CommonResponse success': (res) => isCommonSuccess(res),
    'order page and snapshot match preflight': () => Boolean(snapshot) && snapshot.digest === data.pageDigest,
  });
}

export function teardown(data) {
  if (!data?.accessToken) return;
  const response = http.post(`${BASE_URL}/api/v1/auth/logout`, null,
    jsonParams(data.accessToken, { phase: 'teardown', endpoint: 'comparison-logout' }));
  check(response, { 'comparison logout succeeds': (res) => isCommonSuccess(res) }, { phase: 'teardown' });
}

export function handleSummary(data) {
  // setup_data contains the access token; export only explicitly selected metrics and safe metadata.
  const metrics = Object.fromEntries(Object.entries(data.metrics).map(([name, metric]) =>
    [name, metric.values ?? metric]));
  const safe = { metrics, root_group: data.root_group,
    thresholds: Object.fromEntries(Object.entries(data.metrics)
      .filter(([, metric]) => metric.thresholds).map(([name, metric]) => [name, metric.thresholds])),
    comparison: { route: ROUTE, phase: PHASE, account_index: 0, rate,
      page_digest: data.setup_data?.pageDigest, total_elements: data.setup_data?.totalElements,
      content_count: data.setup_data?.contentCount,
      credentials_tokens_and_response_bodies_exported: false } };
  return { [SUMMARY_PATH]: JSON.stringify(safe, null, 2),
    stdout: `Order path stage ended: route=${ROUTE}, phase=${PHASE}. Sanitized summary saved.\n` };
}
