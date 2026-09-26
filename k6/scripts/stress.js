import {
  arrivalRateThresholds,
  env,
  integerEnv,
  numberEnv,
  summaryTrendStats,
  testTags,
} from '../lib/common.js';
import { mixedTraffic, preflight } from '../lib/workloads.js';

const RAMP_DURATION = env('STRESS_RAMP_DURATION', '2m');
const HOLD_DURATION = env('STRESS_HOLD_DURATION', '3m');

export const options = {
  summaryTrendStats,
  tags: testTags('stress'),
  scenarios: {
    mixedStress: {
      executor: 'ramping-arrival-rate',
      startRate: integerEnv('STRESS_START_RPS', 10, 1),
      timeUnit: '1s',
      preAllocatedVUs: integerEnv('STRESS_PRE_ALLOCATED_VUS', 50, 1),
      maxVUs: integerEnv('STRESS_MAX_VUS', 300, 1),
      stages: [
        { duration: RAMP_DURATION, target: integerEnv('STRESS_RPS_1', 25, 1) },
        { duration: HOLD_DURATION, target: integerEnv('STRESS_RPS_1', 25, 1) },
        { duration: RAMP_DURATION, target: integerEnv('STRESS_RPS_2', 50, 1) },
        { duration: HOLD_DURATION, target: integerEnv('STRESS_RPS_2', 50, 1) },
        { duration: RAMP_DURATION, target: integerEnv('STRESS_RPS_3', 100, 1) },
        { duration: HOLD_DURATION, target: integerEnv('STRESS_RPS_3', 100, 1) },
        { duration: RAMP_DURATION, target: 0 },
      ],
      gracefulStop: '30s',
      tags: { traffic_type: 'mixed' },
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

export default function () {
  mixedTraffic();
}
