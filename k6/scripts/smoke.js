import http from 'k6/http';
import { check, group } from 'k6';
import { baseUrl, defaultThresholds, jsonParams } from '../lib/common.js';

const BASE_URL = baseUrl();
const PRODUCT_ID = __ENV.PRODUCT_ID;

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: defaultThresholds,
};

function verify(response, expectedStatus = 200) {
  check(response, {
    [`HTTP ${expectedStatus}`]: (res) => res.status === expectedStatus,
    'CommonResponse status is 200': (res) => res.json('status') === 200,
  });
}

export default function () {
  group('public API smoke', () => {
    verify(http.get(`${BASE_URL}/api/v1/categories`, jsonParams(null, { endpoint: 'categories' })));
    verify(http.get(
      `${BASE_URL}/api/v1/products?page=0&size=10`,
      jsonParams(null, { endpoint: 'products' }),
    ));

    if (PRODUCT_ID) {
      verify(http.get(
        `${BASE_URL}/api/v1/products/${PRODUCT_ID}`,
        jsonParams(null, { endpoint: 'product-detail' }),
      ));
      verify(http.get(
        `${BASE_URL}/api/v1/products/${PRODUCT_ID}/reviews?page=0&size=10`,
        jsonParams(null, { endpoint: 'reviews' }),
      ));
      verify(http.get(
        `${BASE_URL}/api/v1/products/${PRODUCT_ID}/qnas?page=0&size=10`,
        jsonParams(null, { endpoint: 'qnas' }),
      ));
    }
  });
}
