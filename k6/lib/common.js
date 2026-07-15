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

export function baseUrl() {
  return env('BASE_URL').replace(/\/+$/, '');
}

export function jsonParams(token, tags = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return { headers, tags };
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

export const defaultThresholds = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<500', 'p(99)<1000'],
  checks: ['rate>0.99'],
};
