import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate } from 'k6/metrics';
import {
  baseUrl,
  isCommonSuccess,
  jsonParams,
  numberEnv,
  responseData,
} from './common.js';

const BASE_URL = baseUrl();
const PRODUCT_ID = numberEnv('PRODUCT_ID', 0);

export const users = new SharedArray(
  'coffeeprod performance users',
  () => JSON.parse(open('../data/users.json')),
);

export const businessErrors = new Rate('business_errors');
export const authenticationErrors = new Rate('authentication_errors');
export const sessionRelogins = new Counter('session_relogins');

let sessionToken;
let sessionUser;

function userForVu() {
  const index = (exec.vu.idInTest - 1) % users.length;
  return users[index];
}

function userForIteration() {
  const index = exec.scenario.iterationInTest % users.length;
  return users[index];
}

function recordResult(metric, success, tags) {
  metric.add(!success, tags);
  return success;
}

function login(user, endpoint) {
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: user.email, password: user.password }),
    jsonParams(null, { endpoint }),
  );

  const token = responseData(response)?.accessToken;
  const refreshToken = responseData(response)?.refreshToken;
  const success = check(response, {
    'login returns HTTP 200': (res) => res.status === 200,
    'login returns CommonResponse success': (res) => isCommonSuccess(res),
    'login returns access token': () => Boolean(token),
    'login returns refresh token': () => Boolean(refreshToken),
  });

  recordResult(authenticationErrors, success, { endpoint });
  return success ? token : null;
}

function authenticatedGet(path, endpoint) {
  if (!sessionUser) {
    sessionUser = userForVu();
  }
  if (!sessionToken) {
    sessionToken = login(sessionUser, 'session-login');
  }
  if (!sessionToken) {
    recordResult(businessErrors, false, { flow: 'authenticated', endpoint });
    return;
  }

  let response = http.get(
    `${BASE_URL}${path}`,
    jsonParams(sessionToken, { endpoint }),
  );

  // 장시간 테스트에서 만료된 AccessToken을 재로그인으로 교체함
  if (response.status === 401) {
    sessionRelogins.add(1, { endpoint });
    sessionToken = login(sessionUser, 'session-relogin');
    if (sessionToken) {
      response = http.get(
        `${BASE_URL}${path}`,
        jsonParams(sessionToken, { endpoint }),
      );
    }
  }

  const success = check(response, {
    'authenticated read returns HTTP 200': (res) => res.status === 200,
    'authenticated read returns CommonResponse success': (res) => isCommonSuccess(res),
  });
  recordResult(businessErrors, success, { flow: 'authenticated', endpoint });
}

export function validateWorkloadConfiguration({
  requireProduct = true,
  requireUsers = true,
} = {}) {
  if (requireProduct && (!Number.isInteger(PRODUCT_ID) || PRODUCT_ID < 1)) {
    throw new Error('PRODUCT_ID must be a positive integer.');
  }
  if (requireUsers && users.length < 1) {
    throw new Error('k6/data/users.json must contain at least one user.');
  }
  if (requireUsers) {
    for (const user of users) {
      if (!user.email || !user.password) {
        throw new Error('Every performance user must have email and password.');
      }
    }
  }
}

export function preflight({ authenticate = true } = {}) {
  validateWorkloadConfiguration({ requireProduct: true, requireUsers: authenticate });

  const productResponse = http.get(
    `${BASE_URL}/api/v1/products/${PRODUCT_ID}`,
    jsonParams(null, { endpoint: 'preflight-product' }),
  );
  if (!isCommonSuccess(productResponse)) {
    throw new Error(
      `Preflight product request failed: status=${productResponse.status}, body=${productResponse.body}`,
    );
  }

  if (authenticate && !login(users[0], 'preflight-login')) {
    throw new Error('Preflight login failed. Check k6/data/users.json and Redis.');
  }
}

export function publicReadTraffic() {
  const random = Math.random();
  let response;
  let endpoint;

  if (random < 0.45) {
    const page = Math.floor(Math.random() * 5);
    endpoint = 'products';
    response = http.get(
      `${BASE_URL}/api/v1/products?page=${page}&size=20`,
      jsonParams(null, { endpoint }),
    );
  } else if (random < 0.60) {
    const decaf = Math.random() < 0.2;
    const roastLevel = ['LIGHT', 'MEDIUM', 'DARK'][Math.floor(Math.random() * 3)];
    endpoint = 'product-filter';
    response = http.get(
      `${BASE_URL}/api/v1/products?page=0&size=20&decaf=${decaf}&roastLevel=${roastLevel}`,
      jsonParams(null, { endpoint }),
    );
  } else if (random < 0.75) {
    endpoint = 'product-detail';
    response = http.get(
      `${BASE_URL}/api/v1/products/${PRODUCT_ID}`,
      jsonParams(null, { endpoint }),
    );
  } else if (random < 0.83) {
    endpoint = 'categories';
    response = http.get(
      `${BASE_URL}/api/v1/categories`,
      jsonParams(null, { endpoint }),
    );
  } else if (random < 0.90) {
    endpoint = 'reviews';
    response = http.get(
      `${BASE_URL}/api/v1/products/${PRODUCT_ID}/reviews?page=0&size=10`,
      jsonParams(null, { endpoint }),
    );
  } else if (random < 0.95) {
    endpoint = 'qnas';
    response = http.get(
      `${BASE_URL}/api/v1/products/${PRODUCT_ID}/qnas?page=0&size=10`,
      jsonParams(null, { endpoint }),
    );
  } else {
    endpoint = 'recommendation';
    response = http.post(
      `${BASE_URL}/api/v1/coffee-recommendations`,
      JSON.stringify({
        decaf: false,
        preferredAcidity: 3,
        preferredBody: 4,
        preferredSweetness: 4,
        preferredAroma: 4,
        limit: 5,
      }),
      jsonParams(null, { endpoint }),
    );
  }

  const success = check(response, {
    'public request returns HTTP 200': (res) => res.status === 200,
    'public request returns CommonResponse success': (res) => isCommonSuccess(res),
  });
  recordResult(businessErrors, success, { flow: 'public', endpoint });
}

export function authenticatedReadTraffic() {
  const random = Math.random();

  if (random < 0.40) {
    authenticatedGet('/api/v1/members/me', 'member-me');
  } else if (random < 0.80) {
    authenticatedGet('/api/v1/orders?page=0&size=10', 'my-orders');
  } else {
    authenticatedGet('/api/v1/members/me/addresses', 'my-addresses');
  }
}

export function loginTraffic() {
  login(userForIteration(), 'login');
}

export function mixedTraffic() {
  const random = Math.random();

  if (random < 0.75) {
    publicReadTraffic();
  } else if (random < 0.95) {
    authenticatedReadTraffic();
  } else {
    loginTraffic();
  }
}
