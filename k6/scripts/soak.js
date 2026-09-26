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

const DURATION = env('SOAK_DURATION', '30m');

export const options = {
  summaryTrendStats,
  tags: testTags('soak'),
  scenarios: {
    publicReads: {
      executor: 'constant-arrival-rate',
      exec: 'publicReads',
      rate: integerEnv('SOAK_PUBLIC_RPS', 15, 1),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: integerEnv('SOAK_PUBLIC_PRE_ALLOCATED_VUS', 30, 1),
      maxVUs: integerEnv('SOAK_PUBLIC_MAX_VUS', 100, 1),
      gracefulStop: '1m',
      tags: { traffic_type: 'public' },
    },
    memberReads: {
      executor: 'constant-arrival-rate',
      exec: 'memberReads',
      rate: integerEnv('SOAK_MEMBER_RPS', 4, 1),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: integerEnv('SOAK_MEMBER_PRE_ALLOCATED_VUS', 15, 1),
      maxVUs: integerEnv('SOAK_MEMBER_MAX_VUS', 60, 1),
      gracefulStop: '1m',
      tags: { traffic_type: 'member' },
    },
    logins: {
      executor: 'constant-arrival-rate',
      exec: 'logins',
      rate: integerEnv('SOAK_LOGIN_RPS', 1, 1),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: integerEnv('SOAK_LOGIN_PRE_ALLOCATED_VUS', 5, 1),
      maxVUs: integerEnv('SOAK_LOGIN_MAX_VUS', 30, 1),
      gracefulStop: '1m',
      tags: { traffic_type: 'login' },
    },
  },
  thresholds: {
    ...arrivalRateThresholds,
    business_errors: [`rate<${numberEnv('BUSINESS_ERROR_RATE', 0.01)}`],
    authentication_errors: [`rate<${numberEnv('AUTH_ERROR_RATE', 0.01)}`],
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
