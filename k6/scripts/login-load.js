import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { baseUrl, defaultThresholds, jsonParams, numberEnv } from '../lib/common.js';

const BASE_URL = baseUrl();
const users = new SharedArray('login users', () => JSON.parse(open('../data/users.json')));

export const options = {
  scenarios: {
    logins: {
      executor: 'constant-arrival-rate',
      rate: numberEnv('LOGIN_RPS', 2),
      timeUnit: '1s',
      duration: __ENV.LOGIN_DURATION || '2m',
      preAllocatedVUs: numberEnv('LOGIN_PRE_ALLOCATED_VUS', 5),
      maxVUs: numberEnv('LOGIN_MAX_VUS', 30),
    },
  },
  thresholds: defaultThresholds,
};

export default function () {
  const user = users[(__VU - 1) % users.length];
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: user.email, password: user.password }),
    jsonParams(null, { endpoint: 'login' }),
  );

  check(response, {
    'login status is 200': (res) => res.status === 200,
    'access token is returned': (res) => Boolean(res.json('data.accessToken')),
    'refresh token is returned': (res) => Boolean(res.json('data.refreshToken')),
  });
}
