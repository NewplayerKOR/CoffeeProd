import fs from 'node:fs';
import path from 'node:path';

const [beforePath, afterPath, outputPath] = process.argv.slice(2);

if (!beforePath || !afterPath || !outputPath) {
  console.error('Usage: node compare-runs.mjs <before-summary> <after-summary> <output.md>');
  process.exit(2);
}

function readSummary(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function value(metrics, name, field) {
  const result = metrics[name]?.[field];
  return typeof result === 'number' && Number.isFinite(result) ? result : null;
}

function perRequest(metrics, metricName) {
  const total = value(metrics, metricName, 'count');
  const requests = value(metrics, 'http_reqs', 'count');
  return total === null || !requests ? null : total / requests;
}

function change(before, after) {
  if (before === null || after === null || before === 0) {
    return null;
  }
  return ((after / before) - 1) * 100;
}

function format(valueToFormat, digits = 2) {
  return valueToFormat === null
    ? '-'
    : valueToFormat.toLocaleString('ko-KR', {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    });
}

function formatChange(valueToFormat) {
  if (valueToFormat === null) {
    return '-';
  }
  const sign = valueToFormat > 0 ? '+' : '';
  return `${sign}${format(valueToFormat, 2)}%`;
}

const before = readSummary(beforePath).metrics ?? {};
const after = readSummary(afterPath).metrics ?? {};

const metricRows = [
  {
    label: '평균 RPS',
    before: value(before, 'http_reqs', 'rate'),
    after: value(after, 'http_reqs', 'rate'),
    unit: '',
  },
  {
    label: '평균 응답시간',
    before: value(before, 'http_req_duration', 'avg'),
    after: value(after, 'http_req_duration', 'avg'),
    unit: 'ms',
  },
  {
    label: 'p95 응답시간',
    before: value(before, 'http_req_duration', 'p(95)'),
    after: value(after, 'http_req_duration', 'p(95)'),
    unit: 'ms',
  },
  {
    label: 'p99 응답시간',
    before: value(before, 'http_req_duration', 'p(99)'),
    after: value(after, 'http_req_duration', 'p(99)'),
    unit: 'ms',
  },
  {
    label: '최대 응답시간',
    before: value(before, 'http_req_duration', 'max'),
    after: value(after, 'http_req_duration', 'max'),
    unit: 'ms',
  },
  {
    label: 'HTTP 실패율',
    before: value(before, 'http_req_failed', 'value') === null
      ? null
      : value(before, 'http_req_failed', 'value') * 100,
    after: value(after, 'http_req_failed', 'value') === null
      ? null
      : value(after, 'http_req_failed', 'value') * 100,
    unit: '%',
  },
  {
    label: 'Check 성공률',
    before: value(before, 'checks', 'value') === null
      ? null
      : value(before, 'checks', 'value') * 100,
    after: value(after, 'checks', 'value') === null
      ? null
      : value(after, 'checks', 'value') * 100,
    unit: '%',
  },
  {
    label: 'Dropped iteration',
    before: value(before, 'dropped_iterations', 'count'),
    after: value(after, 'dropped_iterations', 'count'),
    unit: '건',
  },
  {
    label: '요청당 수신량',
    before: perRequest(before, 'data_received'),
    after: perRequest(after, 'data_received'),
    unit: 'bytes',
  },
];

const report = [
  '# CoffeeProd k6 실행 비교',
  '',
  `- 이전 결과: \`${path.basename(beforePath)}\``,
  `- 이후 결과: \`${path.basename(afterPath)}\``,
  '- 판정 원칙: 동일 서버·DB snapshot·시나리오를 최소 3회 반복한 중앙값 비교 전에는 개선을 확정하지 않는다.',
  '',
  '## 지표 비교',
  '',
  '| 지표 | 이전 | 이후 | 변화율 |',
  '|---|---:|---:|---:|',
  ...metricRows.map((row) => (
    `| ${row.label} | ${format(row.before)}${row.unit} | ${format(row.after)}${row.unit} | `
    + `${formatChange(change(row.before, row.after))} |`
  )),
  '',
  '## 해석 기준',
  '',
  '- 응답시간은 낮을수록 좋지만 인터넷 RTT, JIT와 캐시 예열 영향을 함께 고려한다.',
  '- 이미지 URL 적용 후 요청당 수신량 증가는 정상적인 데이터 현실성 증가일 수 있다.',
  '- HTTP 실패율 증가, Check 성공률 감소 또는 Dropped iteration 발생은 회귀 후보로 우선 조사한다.',
  '- 이미지 전달과 사용자 체감 개선은 `image-assets` 결과와 Lighthouse 결과를 함께 제시한다.',
  '',
];

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, report.join('\n'), 'utf8');
console.log(`Comparison report: ${outputPath}`);
