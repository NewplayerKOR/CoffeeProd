import {
  arrivalRateThresholds,
  env,
  integerEnv,
  numberEnv,
  summaryTrendStats,
  testTags,
} from '../lib/common.js';
import { mixedTraffic, preflight } from '../lib/workloads.js';

const BASE_RPS = integerEnv('SPIKE_BASE_RPS', 10, 1);

export const options = {
  summaryTrendStats,
  tags: testTags('spike'),
  scenarios: {
    mixedSpike: {
      executor: 'ramping-arrival-rate',
      startRate: BASE_RPS,
      timeUnit: '1s',
      preAllocatedVUs: integerEnv('SPIKE_PRE_ALLOCATED_VUS', 50, 1),
      maxVUs: integerEnv('SPIKE_MAX_VUS', 300, 1),
      stages: [
        { duration: env('SPIKE_BASE_DURATION', '1m'), target: BASE_RPS },
        {
          duration: env('SPIKE_RAMP_DURATION', '10s'),
          target: integerEnv('SPIKE_PEAK_RPS', 100, 1),
        },
        {
          duration: env('SPIKE_PEAK_DURATION', '1m'),
          target: integerEnv('SPIKE_PEAK_RPS', 100, 1),
        },
        { duration: env('SPIKE_RECOVERY_RAMP_DURATION', '10s'), target: BASE_RPS },
        { duration: env('SPIKE_RECOVERY_DURATION', '2m'), target: BASE_RPS },
        { duration: '10s', target: 0 },
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
