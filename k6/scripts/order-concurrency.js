import http from 'k6/http';
import { check, fail } from 'k6';
import { SharedArray } from 'k6/data';
import {
  baseUrl,
  defaultThresholds,
  env,
  jsonParams,
  numberEnv,
  requireCheck,
  responseData,
} from '../lib/common.js';

const BASE_URL = baseUrl();
const PRODUCT_ID = numberEnv('PRODUCT_ID');
const QUANTITY = numberEnv('ORDER_QUANTITY', 1);
const USED_MILEAGE = numberEnv('USED_MILEAGE', 0);
const users = new SharedArray('order users', () => JSON.parse(open('../data/users.json')));
const ORDER_VUS = numberEnv('ORDER_VUS', users.length);

export const options = {
  scenarios: {
    concurrentOrders: {
      executor: 'per-vu-iterations',
      vus: ORDER_VUS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    ...defaultThresholds,
    'http_req_duration{endpoint:create-order}': ['p(95)<1500'],
  },
};

export function setup() {
  if (__ENV.ALLOW_WRITES !== 'true') {
    throw new Error('Order test is blocked. Set ALLOW_WRITES=true explicitly.');
  }
  if (ORDER_VUS > users.length) {
    throw new Error(`ORDER_VUS(${ORDER_VUS}) exceeds users.json entries(${users.length}).`);
  }
  for (const user of users.slice(0, ORDER_VUS)) {
    if (!user.addressId) {
      throw new Error(`addressId is missing for user: ${user.email}`);
    }
  }
}

export default function () {
  const user = users[__VU - 1];
  if (!user) {
    fail(`No test user mapped to VU ${__VU}`);
  }

  const loginResponse = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: user.email, password: user.password }),
    jsonParams(null, { endpoint: 'login' }),
  );
  requireCheck(loginResponse, {
    'order user login succeeds': (res) => res.status === 200 && Boolean(res.json('data.accessToken')),
  }, 'Login failed');

  const token = loginResponse.json('data.accessToken');
  const authParams = jsonParams(token);

  http.del(`${BASE_URL}/api/v1/carts`, null, {
    ...authParams,
    tags: { endpoint: 'clear-cart' },
  });

  const cartResponse = http.post(
    `${BASE_URL}/api/v1/carts/items`,
    JSON.stringify({
      productId: PRODUCT_ID,
      quantity: QUANTITY,
      grindType: 'WHOLE_BEAN',
    }),
    jsonParams(token, { endpoint: 'add-cart-item' }),
  );
  requireCheck(cartResponse, {
    'cart item is added': (res) => res.status === 200,
  }, 'Adding cart item failed');

  const orderResponse = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify({ addressId: user.addressId, usedMileage: USED_MILEAGE }),
    jsonParams(token, { endpoint: 'create-order' }),
  );

  check(orderResponse, {
    'order is created or rejected by stock': (res) => res.status === 201 || res.status === 400,
    'successful order has order id': (res) => res.status !== 201 || Boolean(responseData(res)?.orderId),
  });

  if (__ENV.CANCEL_AFTER === 'true' && orderResponse.status === 201) {
    const orderId = orderResponse.json('data.orderId');
    const cancelResponse = http.post(
      `${BASE_URL}/api/v1/orders/${orderId}/cancel`,
      null,
      jsonParams(token, { endpoint: 'cancel-order' }),
    );
    check(cancelResponse, {
      'created order is canceled': (res) => res.status === 200,
    });
  }
}
