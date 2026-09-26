import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend } from 'k6/metrics';
import {
  baseUrl,
  env,
  integerEnv,
  isCommonSuccess,
  jsonParams,
  numberEnv,
  responseData,
  summaryTrendStats,
  testTags,
} from '../lib/common.js';

const BASE_URL = baseUrl();
const ASSET_BASE_URL = env(
  'ASSET_BASE_URL',
  'https://assets-coffeeprod.ttagyulab.com',
).replace(/\/+$/, '');
const EXPECTED_PUBLIC_PRODUCT_COUNT = integerEnv('EXPECTED_PUBLIC_PRODUCT_COUNT', 175, 1);
const EXPECTED_UNIQUE_IMAGE_COUNT = integerEnv('EXPECTED_UNIQUE_IMAGE_COUNT', 96, 1);
const API_PAGE_SIZE = integerEnv('IMAGE_API_PAGE_SIZE', 200, 1);
const INVENTORY_BATCH_SIZE = integerEnv('IMAGE_INVENTORY_BATCH_SIZE', 8, 1);

export const imageContractErrors = new Rate('image_contract_errors');
export const imageCacheHits = new Rate('image_cache_hits');
export const imageResponseBytes = new Trend('image_response_bytes');

export const options = {
  summaryTrendStats,
  tags: testTags('image-assets'),
  scenarios: {
    imageReads: {
      executor: 'constant-arrival-rate',
      rate: integerEnv('IMAGE_RPS', 10, 1),
      timeUnit: '1s',
      duration: env('IMAGE_DURATION', '2m'),
      preAllocatedVUs: integerEnv('IMAGE_PRE_ALLOCATED_VUS', 20, 1),
      maxVUs: integerEnv('IMAGE_MAX_VUS', 100, 1),
      gracefulStop: '30s',
      tags: { traffic_type: 'asset', phase: 'load' },
    },
  },
  thresholds: {
    'http_req_failed{phase:catalog-api}': ['rate==0'],
    'http_req_failed{phase:inventory}': ['rate==0'],
    'http_req_failed{phase:load}': [
      `rate<${numberEnv('IMAGE_FAILURE_RATE', 0.01)}`,
    ],
    'http_req_duration{phase:load}': [
      `p(95)<${numberEnv('IMAGE_P95_MS', 1000)}`,
      `p(99)<${numberEnv('IMAGE_P99_MS', 2000)}`,
    ],
    'checks{phase:catalog-api}': ['rate==1'],
    'checks{phase:inventory}': ['rate==1'],
    'checks{phase:load}': [
      `rate>${numberEnv('CHECK_SUCCESS_RATE', 0.99)}`,
    ],
    image_contract_errors: ['rate==0'],
    'image_cache_hits{phase:load}': [
      `rate>${numberEnv('IMAGE_CACHE_HIT_RATE', 0.90)}`,
    ],
    dropped_iterations: ['count==0'],
  },
};

function header(response, name) {
  const target = name.toLowerCase();
  const key = Object.keys(response.headers).find(
    (candidate) => candidate.toLowerCase() === target,
  );
  return key ? response.headers[key] : '';
}

function isApprovedImageUrl(value) {
  return typeof value === 'string'
    && value.startsWith(`${ASSET_BASE_URL}/products/catalog/`)
    && value.endsWith('.webp');
}

function validateImageResponse(response, phase) {
  const contentType = header(response, 'content-type').toLowerCase();
  const contentLength = Number(header(response, 'content-length'));
  const cacheControl = header(response, 'cache-control').toLowerCase();
  const cacheStatus = header(response, 'cf-cache-status').toUpperCase();

  const success = check(response, {
    'image returns HTTP 200': (res) => res.status === 200,
    'image content type is WebP': () => contentType.startsWith('image/webp'),
    'image content length is positive': () => Number.isFinite(contentLength) && contentLength > 0,
    'image uses immutable long-term cache': () => (
      cacheControl.includes('max-age=31536000') && cacheControl.includes('immutable')
    ),
    'image exposes Cloudflare cache status': () => Boolean(cacheStatus),
  }, { phase });

  imageContractErrors.add(!success, { phase });
  imageCacheHits.add(cacheStatus === 'HIT', { phase });
  if (Number.isFinite(contentLength) && contentLength > 0) {
    imageResponseBytes.add(contentLength, { phase });
  }
  return success;
}

export function setup() {
  const response = http.get(
    `${BASE_URL}/api/v1/products?page=0&size=${API_PAGE_SIZE}&sort=id,asc`,
    jsonParams(null, { endpoint: 'products', phase: 'catalog-api' }),
  );
  const data = responseData(response);
  const products = Array.isArray(data?.content) ? data.content : [];
  const imageUrls = products.map((product) => product.imageUrl);
  const approvedImageUrls = imageUrls.filter(isApprovedImageUrl);
  const uniqueImageUrls = [...new Set(approvedImageUrls)];

  const catalogValid = check(response, {
    'catalog API returns HTTP 200': (res) => res.status === 200,
    'catalog API returns CommonResponse success': (res) => isCommonSuccess(res),
    'catalog contains expected public product count': () => (
      products.length === EXPECTED_PUBLIC_PRODUCT_COUNT
    ),
    'all products contain approved image URLs': () => approvedImageUrls.length === products.length,
    'catalog contains expected unique image count': () => (
      uniqueImageUrls.length === EXPECTED_UNIQUE_IMAGE_COUNT
    ),
  }, { phase: 'catalog-api' });
  imageContractErrors.add(!catalogValid, { phase: 'catalog-api' });

  if (!catalogValid) {
    fail(
      `Catalog image contract failed: products=${products.length}, `
      + `approved=${approvedImageUrls.length}, unique=${uniqueImageUrls.length}`,
    );
  }

  let inventoryValid = true;
  for (let start = 0; start < uniqueImageUrls.length; start += INVENTORY_BATCH_SIZE) {
    const batch = uniqueImageUrls.slice(start, start + INVENTORY_BATCH_SIZE).map((url) => ({
      method: 'GET',
      url,
      params: {
        tags: { endpoint: 'catalog-image', phase: 'inventory' },
        timeout: env('REQUEST_TIMEOUT', '30s'),
      },
    }));
    const responses = http.batch(batch);
    for (const imageResponse of responses) {
      inventoryValid = validateImageResponse(imageResponse, 'inventory') && inventoryValid;
    }
  }

  if (!inventoryValid) {
    fail('One or more catalog images failed the inventory contract.');
  }

  return { imageUrls: uniqueImageUrls };
}

export default function (data) {
  const index = exec.scenario.iterationInTest % data.imageUrls.length;
  const response = http.get(data.imageUrls[index], {
    tags: { endpoint: 'catalog-image', phase: 'load' },
    timeout: env('REQUEST_TIMEOUT', '30s'),
  });
  validateImageResponse(response, 'load');
}
