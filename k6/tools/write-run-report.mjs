import fs from 'node:fs';
import path from 'node:path';

const [summaryPath, metadataPath, reportPath, exitCodeValue] = process.argv.slice(2);

if (!summaryPath || !metadataPath || !reportPath || exitCodeValue === undefined) {
  console.error('Usage: node write-run-report.mjs <summary> <metadata> <report> <exit-code>');
  process.exit(2);
}

function readMetadata(filePath) {
  if (!fs.existsSync(filePath)) {
    return {};
  }
  return Object.fromEntries(
    fs.readFileSync(filePath, 'utf8')
      .split(/\r?\n/)
      .filter(Boolean)
      .map((line) => {
        const separator = line.indexOf('=');
        return separator < 0
          ? [line, '']
          : [line.slice(0, separator), line.slice(separator + 1)];
      }),
  );
}

function readSummary(filePath) {
  if (!fs.existsSync(filePath)) {
    return { metrics: {} };
  }
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function metricValue(metrics, name, field) {
  const value = metrics[name]?.[field];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function formatNumber(value, digits = 2) {
  return value === null
    ? '-'
    : value.toLocaleString('ko-KR', {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    });
}

function formatInteger(value) {
  return value === null ? '-' : Math.round(value).toLocaleString('ko-KR');
}

function formatPercent(value) {
  return value === null ? '-' : `${formatNumber(value * 100, 4)}%`;
}

function markdownCell(value) {
  return String(value ?? '-').replaceAll('|', '\\|');
}

const metadata = readMetadata(metadataPath);
const summary = readSummary(summaryPath);
const metrics = summary.metrics ?? {};
const exitCode = Number(exitCodeValue);
const verdict = exitCode === 0 ? 'PASS' : 'FAIL';
const durationMetric = metadata.scenario === 'image-assets'
  ? (metrics['http_req_duration{phase:load}'] ?? metrics.http_req_duration)
  : metrics.http_req_duration;
const failureMetric = metadata.scenario === 'image-assets'
  ? (metrics['http_req_failed{phase:load}'] ?? metrics.http_req_failed)
  : metrics.http_req_failed;
const imageCacheMetric = metrics['image_cache_hits{phase:load}'] ?? metrics.image_cache_hits;

function directMetricValue(metric, field) {
  const value = metric?.[field];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

const rows = [
  ['HTTP 요청', formatInteger(metricValue(metrics, 'http_reqs', 'count'))],
  ['평균 RPS', formatNumber(metricValue(metrics, 'http_reqs', 'rate'))],
  ['평균 응답시간', `${formatNumber(directMetricValue(durationMetric, 'avg'))}ms`],
  ['p95 응답시간', `${formatNumber(directMetricValue(durationMetric, 'p(95)'))}ms`],
  ['p99 응답시간', `${formatNumber(directMetricValue(durationMetric, 'p(99)'))}ms`],
  ['최대 응답시간', `${formatNumber(directMetricValue(durationMetric, 'max'))}ms`],
  ['HTTP 실패율', formatPercent(directMetricValue(failureMetric, 'value'))],
  ['Check 성공률', formatPercent(metricValue(metrics, 'checks', 'value'))],
  ['Dropped iteration', formatInteger(metricValue(metrics, 'dropped_iterations', 'count'))],
  ['수신 데이터', `${formatNumber(metricValue(metrics, 'data_received', 'count') === null
    ? null
    : metricValue(metrics, 'data_received', 'count') / 1_000_000)}MB`],
];

const imageRows = [
  ['이미지 계약 오류율', formatPercent(metricValue(metrics, 'image_contract_errors', 'value'))],
  ['이미지 캐시 HIT 비율', formatPercent(directMetricValue(imageCacheMetric, 'value'))],
  ['평균 이미지 크기', `${formatNumber(metricValue(metrics, 'image_response_bytes', 'avg') === null
    ? null
    : metricValue(metrics, 'image_response_bytes', 'avg') / 1024)}KiB`],
  ['이미지 크기 p95', `${formatNumber(metricValue(metrics, 'image_response_bytes', 'p(95)') === null
    ? null
    : metricValue(metrics, 'image_response_bytes', 'p(95)') / 1024)}KiB`],
];

const metadataKeys = [
  ['실행 ID', 'run_id'],
  ['시나리오', 'scenario'],
  ['시작 UTC', 'started_at_utc'],
  ['종료 UTC', 'finished_at_utc'],
  ['대상 API', 'base_url'],
  ['이미지 호스트', 'asset_base_url'],
  ['테스트 환경', 'test_env'],
  ['Git commit', 'git_commit'],
  ['Git dirty', 'git_dirty'],
  ['DB snapshot SHA-256', 'db_snapshot_sha256'],
  ['k6 버전', 'k6_version'],
  ['실행기', 'runner_host'],
  ['실행기 OS', 'runner_os'],
];
const loadSettings = Object.entries(metadata)
  .filter(([key]) => key.startsWith('setting_'))
  .map(([key, value]) => [key.slice('setting_'.length), value]);

const report = [
  `# CoffeeProd k6 실행 결과: ${metadata.scenario ?? 'unknown'}`,
  '',
  `- 최종 판정: **${verdict}**`,
  `- k6 종료 코드: \`${exitCode}\``,
  `- 원본 Summary: \`${path.basename(summaryPath)}\``,
  `- 실행 Metadata: \`${path.basename(metadataPath)}\``,
  '',
  '## 실행 조건',
  '',
  '| 항목 | 값 |',
  '|---|---|',
  ...metadataKeys.map(([label, key]) => `| ${label} | ${markdownCell(metadata[key])} |`),
];

if (loadSettings.length > 0) {
  report.push(
    '',
    '## 부하 설정',
    '',
    '| 환경변수 | 값 |',
    '|---|---|',
    ...loadSettings.map(([key, value]) => `| \`${key}\` | ${markdownCell(value)} |`),
  );
}

report.push(
  '',
  '## 핵심 지표',
  '',
  '| 지표 | 결과 |',
  '|---|---:|',
  ...rows.map(([label, value]) => `| ${label} | ${value} |`),
);

if (metadata.scenario === 'image-assets') {
  report.push(
    '',
    '## 이미지 자산 지표',
    '',
    '| 지표 | 결과 |',
    '|---|---:|',
    ...imageRows.map(([label, value]) => `| ${label} | ${value} |`),
    '',
    '이미지 테스트는 상품 API의 URL 매핑, 96개 고유 WebP의 전체 점검, R2/CDN 반복 다운로드를 포함한다.',
  );
}

report.push(
  '',
  '## 해석 주의사항',
  '',
  '- 이 문서는 단일 실행의 정적 요약이며 서버 CPU, Heap, GC, DB와 Redis 시계열을 포함하지 않는다.',
  '- 성능 개선 주장은 동일 환경과 동일 DB snapshot의 이전 결과를 최소 3회 반복 비교한 뒤 확정한다.',
  '- 이미지 자산 테스트는 R2/CDN 전달 성능을 측정하며 브라우저 LCP와 Next Image 최적화 성능은 별도 측정한다.',
  '',
);

fs.mkdirSync(path.dirname(reportPath), { recursive: true });
fs.writeFileSync(reportPath, report.join('\n'), 'utf8');
console.log(`Markdown report: ${reportPath}`);
