import http from 'k6/http';
import { check } from 'k6';
import { baseUrl, defaultThresholds, jsonParams, numberEnv } from '../lib/common.js';

const BASE_URL = baseUrl();
const PRODUCT_ID = __ENV.PRODUCT_ID;

export const options = {
  scenarios: {
    publicReads: {
      executor: 'constant-arrival-rate',
      rate: numberEnv('READ_RPS', 10),
      timeUnit: '1s',
      duration: __ENV.READ_DURATION || '2m',
      preAllocatedVUs: numberEnv('READ_PRE_ALLOCATED_VUS', 20),
      maxVUs: numberEnv('READ_MAX_VUS', 100),
    },
  },
  thresholds: defaultThresholds,
};

export default function () {
  const random = Math.random();
  let response;

  if (random < 0.60 || !PRODUCT_ID) {
    const page = Math.floor(Math.random() * 3);
    response = http.get(
      `${BASE_URL}/api/v1/products?page=${page}&size=20`,
      jsonParams(null, { endpoint: 'products' }),
    );
  } else if (random < 0.72) {
    response = http.get(
      `${BASE_URL}/api/v1/categories`,
      jsonParams(null, { endpoint: 'categories' }),
    );
  } else if (random < 0.84) {
    response = http.get(
      `${BASE_URL}/api/v1/products/${PRODUCT_ID}`,
      jsonParams(null, { endpoint: 'product-detail' }),
    );
  } else if (random < 0.92) {
    response = http.get(
      `${BASE_URL}/api/v1/products/${PRODUCT_ID}/reviews?page=0&size=10`,
      jsonParams(null, { endpoint: 'reviews' }),
    );
  } else {
    response = http.get(
      `${BASE_URL}/api/v1/products/${PRODUCT_ID}/qnas?page=0&size=10`,
      jsonParams(null, { endpoint: 'qnas' }),
    );
  }

  check(response, {
    'read status is 200': (res) => res.status === 200,
    'read response is successful': (res) => res.json('status') === 200,
  });
}
