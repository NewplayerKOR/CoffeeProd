import { check, fail } from 'k6';

export function env(name, fallback) {
  const value = __ENV[name];
  if (value !== undefined && value !== '') {
    return value;
  }
  if (fallback !== undefined) {
    return fallback;
  }
  throw new Error(`Required environment variable is missing: ${name}`);
}

export function numberEnv(name, fallback) {
  const value = Number(env(name, fallback));
  if (!Number.isFinite(value)) {
    throw new Error(`Environment variable must be a number: ${name}`);
  }
  return value;
}

export function integerEnv(name, fallback, minimum = 0) {
  const value = numberEnv(name, fallback);
  if (!Number.isInteger(value) || value < minimum) {
    throw new Error(`Environment variable must be an integer >= ${minimum}: ${name}`);
  }
  return value;
}

export function baseUrl() {
  return env('BASE_URL').replace(/\/+$/, '');
}

export function jsonParams(token, tags = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return {
    headers,
    tags,
    timeout: env('REQUEST_TIMEOUT', '30s'),
  };
}

export function responseData(response) {
  try {
    return response.json('data');
  } catch (_) {
    return null;
  }
}

export function requireCheck(response, checks, message) {
  if (!check(response, checks)) {
    fail(`${message}: status=${response.status}, body=${response.body}`);
  }
}

export function isCommonSuccess(response, expectedHttpStatus = 200) {
  if (response.status !== expectedHttpStatus) {
    return false;
  }

  try {
    return response.json('status') === 200;
  } catch (_) {
    return false;
  }
}

export function testTags(testType) {
  return {
    test_type: testType,
    test_env: env('TEST_ENV', 'unknown'),
    run_id: env('K6_RUN_ID', 'manual'),
    git_commit: env('GIT_COMMIT', 'unknown'),
    db_snapshot: env('DB_SNAPSHOT_SHA256', 'unknown'),
  };
}

export const summaryTrendStats = [
  'avg',
  'min',
  'med',
  'max',
  'p(90)',
  'p(95)',
  'p(99)',
  'count',
];

export const defaultThresholds = {
  http_req_failed: [`rate<${numberEnv('HTTP_FAILURE_RATE', 0.01)}`],
  http_req_duration: [
    `p(95)<${numberEnv('HTTP_P95_MS', 500)}`,
    `p(99)<${numberEnv('HTTP_P99_MS', 1000)}`,
  ],
  checks: [`rate>${numberEnv('CHECK_SUCCESS_RATE', 0.99)}`],
};

export const arrivalRateThresholds = {
  ...defaultThresholds,
  dropped_iterations: ['count==0'],
};
