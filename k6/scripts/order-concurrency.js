import http from 'k6/http';
import { check, fail } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate } from 'k6/metrics';
import {
  baseUrl,
  defaultThresholds,
  integerEnv,
  isCommonSuccess,
  jsonParams,
  requireCheck,
  responseData,
  summaryTrendStats,
  testTags,
} from '../lib/common.js';

const BASE_URL = baseUrl();
const PRODUCT_ID = integerEnv('PRODUCT_ID', undefined, 1);
const QUANTITY = integerEnv('ORDER_QUANTITY', 1, 1);
const USED_MILEAGE = integerEnv('USED_MILEAGE', 0, 0);
const users = new SharedArray('order users', () => JSON.parse(open('../data/users.json')));
const ORDER_VUS = integerEnv('ORDER_VUS', 2, 1);
const expectedOrderStatuses = http.expectedStatuses(201, 400);
const ordersCreated = new Counter('orders_created');
const ordersRejectedByStock = new Counter('orders_rejected_by_stock');
const unexpectedOrderResults = new Rate('unexpected_order_results');

function isOutOfStockResponse(response) {
  try {
    return response.status === 400
      && response.json('status') === 400
      && response.json('message') === '재고가 부족합니다.';
  } catch (_) {
    return false;
  }
}

export const options = {
  summaryTrendStats,
  tags: testTags('order-concurrency'),
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
    unexpected_order_results: ['rate==0'],
  },
};

export function setup() {
  if (__ENV.ALLOW_WRITES !== 'true') {
    throw new Error('Order test is blocked. Set ALLOW_WRITES=true explicitly.');
  }
  if (['prod', 'production'].includes((__ENV.TEST_ENV || '').toLowerCase())) {
    throw new Error('Order test is blocked in the production environment.');
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

  const clearCartResponse = http.del(`${BASE_URL}/api/v1/carts`, null, {
    ...authParams,
    tags: { endpoint: 'clear-cart' },
  });
  requireCheck(clearCartResponse, {
    'cart is cleared': (res) => isCommonSuccess(res),
  }, 'Clearing cart failed');

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

  const orderParams = jsonParams(token, { endpoint: 'create-order' });
  orderParams.responseCallback = expectedOrderStatuses;
  const orderResponse = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify({ addressId: user.addressId, usedMileage: USED_MILEAGE }),
    orderParams,
  );

  const orderData = responseData(orderResponse);
  const isCreated = orderResponse.status === 201
    && isCommonSuccess(orderResponse, 201)
    && Boolean(orderData?.orderId);
  const isOutOfStock = isOutOfStockResponse(orderResponse);
  const isExpectedResult = isCreated || isOutOfStock;

  ordersCreated.add(isCreated ? 1 : 0);
  ordersRejectedByStock.add(isOutOfStock ? 1 : 0);
  unexpectedOrderResults.add(!isExpectedResult);

  check(orderResponse, {
    'order is created or rejected only by stock': () => isExpectedResult,
    'HTTP 201 order has order id': (res) => res.status !== 201 || Boolean(orderData?.orderId),
  });

  if (__ENV.CANCEL_AFTER === 'true' && isCreated) {
    const orderId = orderData.orderId;
    const cancelResponse = http.post(
      `${BASE_URL}/api/v1/orders/${orderId}/cancel`,
      null,
      jsonParams(token, { endpoint: 'cancel-order' }),
    );
    check(cancelResponse, {
      'created order is canceled': (res) => isCommonSuccess(res),
    });
  }
}
