import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import {
  arrivalRateThresholds,
  baseUrl,
  isCommonSuccess,
  jsonParams,
  numberEnv,
  responseData,
  summaryTrendStats,
  testTags,
} from '../lib/common.js';

const BASE_URL = baseUrl();
const users = new SharedArray('login users', () => JSON.parse(open('../data/users.json')));

export const options = {
  summaryTrendStats,
  tags: testTags('login-load'),
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
  thresholds: arrivalRateThresholds,
};

export default function () {
  const user = users[(__VU - 1) % users.length];
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: user.email, password: user.password }),
    jsonParams(null, { endpoint: 'login' }),
  );
  const data = responseData(response);

  check(response, {
    'login status is 200': (res) => res.status === 200,
    'login response is successful': (res) => isCommonSuccess(res),
    'access token is returned': () => Boolean(data?.accessToken),
    'refresh token is returned': () => Boolean(data?.refreshToken),
  });
}
