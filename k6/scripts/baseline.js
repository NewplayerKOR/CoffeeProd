import {
  arrivalRateThresholds,
  env,
  integerEnv,
  numberEnv,
  summaryTrendStats,
  testTags,
} from '../lib/common.js';
import {
  authenticatedReadTraffic,
  loginTraffic,
  preflight,
  publicReadTraffic,
} from '../lib/workloads.js';

const DURATION = env('BASELINE_DURATION', '5m');

export const options = {
  summaryTrendStats,
  tags: testTags('baseline'),
  scenarios: {
    publicReads: {
      executor: 'constant-arrival-rate',
      exec: 'publicReads',
      rate: integerEnv('BASELINE_PUBLIC_RPS', 15, 1),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: integerEnv('BASELINE_PUBLIC_PRE_ALLOCATED_VUS', 30, 1),
      maxVUs: integerEnv('BASELINE_PUBLIC_MAX_VUS', 100, 1),
      gracefulStop: '30s',
      tags: { traffic_type: 'public' },
    },
    memberReads: {
      executor: 'constant-arrival-rate',
      exec: 'memberReads',
      rate: integerEnv('BASELINE_MEMBER_RPS', 4, 1),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: integerEnv('BASELINE_MEMBER_PRE_ALLOCATED_VUS', 15, 1),
      maxVUs: integerEnv('BASELINE_MEMBER_MAX_VUS', 60, 1),
      gracefulStop: '30s',
      tags: { traffic_type: 'member' },
    },
    logins: {
      executor: 'constant-arrival-rate',
      exec: 'logins',
      rate: integerEnv('BASELINE_LOGIN_RPS', 1, 1),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: integerEnv('BASELINE_LOGIN_PRE_ALLOCATED_VUS', 5, 1),
      maxVUs: integerEnv('BASELINE_LOGIN_MAX_VUS', 30, 1),
      gracefulStop: '30s',
      tags: { traffic_type: 'login' },
    },
  },
  thresholds: {
    ...arrivalRateThresholds,
    business_errors: [`rate<${numberEnv('BUSINESS_ERROR_RATE', 0.01)}`],
    authentication_errors: [`rate<${numberEnv('AUTH_ERROR_RATE', 0.01)}`],
    'http_req_duration{endpoint:products}': [
      `p(95)<${numberEnv('PRODUCT_LIST_P95_MS', 500)}`,
    ],
    'http_req_duration{endpoint:login}': [
      `p(95)<${numberEnv('LOGIN_P95_MS', 1000)}`,
    ],
    'http_req_duration{traffic_type:member}': [
      `p(95)<${numberEnv('MEMBER_READ_P95_MS', 750)}`,
    ],
  },
};

export function setup() {
  preflight({ authenticate: true });
}

export function publicReads() {
  publicReadTraffic();
}

export function memberReads() {
  authenticatedReadTraffic();
}

export function logins() {
  loginTraffic();
}
