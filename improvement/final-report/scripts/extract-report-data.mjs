import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const workspace = resolve(directory, '../..');
const hash = (path) => createHash('sha256').update(readFileSync(resolve(workspace, path))).digest('hex');
const load = (path) => JSON.parse(readFileSync(resolve(workspace, path), 'utf8'));
const firstPath = 'improvement/1차개선/data/metrics.json';
const mixedPath = 'improvement/2차개선/data/mixed-path-with-cloudflared.json';
const sshPath = 'improvement/2차개선/data/ssh-client-comparison.json';
const first = load(firstPath), mixed = load(mixedPath), ssh = load(sshPath);
for (const source of first.sources) assert.equal(hash(source.repository_relative_path), source.sha256);
assert.equal(hash(mixed.source.repository_relative_path), mixed.source.sha256);
for (const record of ssh.records) assert.equal(hash(`k6/results/${ssh.suite_id}/${record.id}-native.jsonl`), record.raw_sha256);
const median = (values) => [...values].sort((a, b) => a - b)[1];
for (const [metric, expected] of Object.entries(first.medians)) {
  for (const phase of ['before', 'after']) assert.ok(Math.abs(median(first.mixed[phase].map((row) => row[metric])) - expected[phase]) < 1e-9);
}
const cases = first.sql.queries.map((query) => ({
  query: query.name, beforeMs: query.before_execution_ms, afterMs: query.after_execution_median_ms,
  returnedRows: query.returned_rows,
  beforeAccess: [...new Set(query.before_scans.map((scan) => scan.type))].join(' / '),
  afterAccess: [...new Set(query.after_scans.map((scan) => scan.type))].join(' / '),
}));
const measured = mixed.records.filter((row) => row.phase === 'measured');
assert.equal(measured.length, 2);
const routeRows = ['전체 혼합', '주문 목록', '로그인'].map((endpoint) => {
  const find = (route) => {
    const record = measured.find((row) => row.route === route);
    if (endpoint === '전체 혼합') return record.overall.p99_ms;
    return record.endpoints[endpoint === '주문 목록' ? 'my-orders' : 'login'].p99_ms;
  };
  return { endpoint, publicMs: find('public'), sshMs: find('origin') };
});
const metadata = (label, files, definitions, caveats, componentIds) => ({
  label, files: files.map((file) => ({ label: file })),
  metricDefinitions: definitions.map(([name, definition]) => ({ label: name, definition, componentIds })),
  caveats, evidenceFlow: [{ kind: 'source', title: '기록 대조',
    detail: '보존된 측정 JSON에서 필요한 행을 추출했습니다. 연결된 원본의 SHA-256을 대조했으며 새 부하·운영 조회를 실행하지 않았습니다.' }],
});
const firstCaveats = ['적용 후 A3는 별도 재예열한 보충 실행이며 원본을 유지했습니다.',
  '시간대·네트워크·캐시가 완전히 통제되지 않아 약 27%는 관측된 감소입니다.',
  '주문·결제 생성, 쓰기 비용·최대 처리량·전체 최신 회귀는 이 비교의 범위 밖입니다.'];
const firstSource = (components, definition = 'p95/p99는 전후 각각 3회 실행별 분위수의 중앙값입니다. 요청 전체를 합친 분위수가 아닙니다.') =>
  metadata('주문 인덱스 전후 비교', ['1차개선 metrics.json', '1차개선 README.md'], [['응답 시간(ms)', definition]], firstCaveats, components);
const snapshot = {
  title: 'CoffeeProd 성능 개선 최종 보고서', surface: 'report', status: 'observed', buildStatus: 'complete',
  generatedAt: new Date().toISOString(), filters: [],
  report: { title: 'CoffeeProd 성능 개선 최종 보고서', subtitle: '주문 조회 개선 성과와 전달 경로 진단의 한계',
    asOf: '2026-10-12' },
  queries: {
    outcomes: { rows: [
      { cycle: '1차 · 주문 조회', result: '주문 조회 범위 성공', evidence: 'p95 약 27% 감소·SQL 접근 경로 개선', portfolio: '대표 개선 사례' },
      { cycle: '2차 · 혼합 지연', result: '진단 종료 · 개선 미확인', evidence: '전달 경로 영향 관측·근본 원인 미확정', portfolio: '보조 진단 사례' },
    ], source: metadata('1·2차 판정', ['1차개선 README.md', 'RESULT_SSH_CLIENTS_20261012.md'],
      [['판정 범위', '진단 완료·요청 성공과 성능 개선 성공을 구분한 범위별 판정입니다.']],
      ['전체 프로젝트 최종 인수나 2차 성능 개선 성공을 뜻하지 않습니다.'], ['cycle-outcomes', 'executive-summary']) },
    order_summary: { rows: [{ metric: '주문 목록 p95', beforeMs: first.medians.order_p95_ms.before, afterMs: first.medians.order_p95_ms.after }],
      source: firstSource(['order-summary', 'order-story', 'executive-summary']) },
    order_runs: { rows: ['before', 'after'].flatMap((phase) => first.mixed[phase].map((row) => ({
      run: row.display_id + (row.supplemental ? '*' : ''), phase: phase === 'before' ? '적용 전' : '적용 후',
      p95Ms: row.order_p95_ms, p99Ms: row.order_p99_ms, samples: row.order_samples,
    }))), source: firstSource(['order-runs', 'order-run-values'], '각 B1~B3/A1~A3 실행의 주문 목록 p95/p99입니다. A3*는 별도 재예열한 보충입니다.') },
    sql_plans: { rows: cases, source: metadata('PostgreSQL 실행계획', ['1차개선 metrics.json', 'sql-plan-comparison.json'],
      [['진단 SQL 시간(ms)', '적용 전 1회와 적용 후 3회 중앙값입니다. join은 축약 진단 SQL이며 API 시간과 다릅니다.']],
      ['반환 행 수는 동일하지만 캐시 조건이 달라 SQL 시간을 API 개선 배율로 환산하지 않습니다.'], ['sql-plans', 'sql-story', 'sql-image']) },
    overall_summary: { rows: ['overall_p95_ms', 'overall_p99_ms'].map((metric) => ({
      metric: metric === 'overall_p95_ms' ? '전체 혼합 p95' : '전체 혼합 p99',
      beforeMs: first.medians[metric].before, afterMs: first.medians[metric].after,
    })), source: firstSource(['overall-summary', 'scope-limits']) },
    route_comparison: { rows: routeRows, source: metadata('순서 교차 혼합 경로 비교', ['mixed-path-with-cloudflared.json', 'RESULT_CLOUDFLARED_20261008.md'],
      [['p99(ms)', '2026-10-08 공개→SSH 원본 각 5분 본 측정의 엔드포인트별 p99입니다.']],
      ['진단 경로 비교이지 개선 전후 비교가 아닙니다.', 'HTTP/2·TLS와 HTTP/1.1·SSH 및 실행 시각이 달라 순수 Cloudflare 비용이 아닙니다.'],
      ['route-comparison', 'diagnostic-story']) },
    ssh_components: { rows: ['windows', 'git'].map((route) => ({
      client: route === 'windows' ? 'Windows SSH' : 'Git SSH',
      waitingMs: ssh.routes[route].timings.http_req_waiting.average_ms,
      receivingMs: ssh.routes[route].timings.http_req_receiving.average_ms,
      totalMs: ssh.routes[route].timings.http_req_duration.average_ms,
      medianMs: ssh.routes[route].timings.http_req_duration.median_ms,
      p95Ms: ssh.routes[route].timings.http_req_duration.p95_ms,
      p99Ms: ssh.routes[route].timings.http_req_duration.p99_ms,
      samples: ssh.routes[route].count,
    })), source: metadata('Windows·Git OpenSSH 고정 비교', ['ssh-client-comparison.json', 'RESULT_SSH_CLIENTS_20261012.md'],
      [['시간 분해(ms)', '각 클라이언트 60건의 평균 waiting·receiving을 합쳐 평균 duration을 분해합니다. 이번 sending 계측값은 0입니다.'],
       ['중앙값·p95·p99(ms)', '각 경로 60건의 native duration 분위수입니다.']],
      ['평균 성분은 합산 가능하지만 중앙값·p95/p99 성분은 합산하지 않습니다.', '버전·런타임·SSH session 조건을 개별 통제하지 못했습니다.',
       '저빈도 카테고리 결과로 혼합 부하 전체 원인을 확정하지 않습니다.'], ['ssh-components', 'ssh-values', 'ssh-story']) },
    regressions: { rows: [
      { scope: '로컬 H2 주문 서비스', passed: 7, total: 7, conclusion: '선택 범위 기능 회귀 통과' },
      { scope: '배포 주문 읽기·권한', passed: 13, total: 13, conclusion: '페이지·정렬·상세·401/403 확인' },
      { scope: '부하 스크립트 안전성', passed: 9, total: 9, conclusion: '선정 안전성 회귀 통과' },
    ], source: metadata('선택 기능·안전성 검증', ['1차개선 README.md', 'metrics.json'],
      [['통과 수', '기존 결과의 선택 테스트 개수이며 전체 최신 테스트 스위트 개수가 아닙니다.']],
      ['운영 주문 생성·결제 성공·프론트엔드 인수를 의미하지 않습니다.'], ['regressions']) },
    portfolio_points: { rows: [
      { point: '문제 진단', evidence: 'k6 결과와 실행계획으로 넓은 탐색 확인', message: '추측이 아닌 근거로 개선 대상을 선택' },
      { point: 'DB 설계', evidence: '회원·최신순 및 주문상품 FK 인덱스 2개', message: '실제 조회 조건에 맞는 접근 경로 설계' },
      { point: '검증', evidence: '전후 3회·지표·기능/권한 회귀 대조', message: '변경 후 효과와 정합성을 함께 확인' },
      { point: '운영 관측', evidence: 'Ubuntu·Docker·Actuator·Prometheus·k6', message: '응답 시간과 서버 자원을 함께 해석' },
      { point: '판단의 신뢰성', evidence: 'A3·실패 보존, 전체 지연 미개선 공개', message: '측정 한계와 실패를 숨기지 않는 판단' },
    ], source: metadata('포트폴리오에 제시할 기술 근거', ['1차개선 README.md', '2차개선 README.md'],
      [['어필 포인트', '측정·구현 기록에서 설명 가능한 기술 역량입니다. 채용 평가 효과를 보장하지 않습니다.']],
      ['DEV 구현·사용자 배포·QA 측정의 실제 역할과 AI 활용을 구분해서 설명합니다.'], ['portfolio-points', 'portfolio-story']) },
  },
};
const manifest = { generated_at_utc: snapshot.generatedAt, measurement_cutoff_utc: '2026-10-12T12:28:19Z',
  sources: [firstPath, mixedPath, sshPath].map((path) => ({ path, sha256: hash(path) })),
  first_cycle_original_hashes_verified: first.sources.length, second_cycle_original_hashes_verified: 9,
  reads_only_existing_files: true, new_load_test: false,
  values: { order_p95_reduction_percent: first.medians.order_p95_ms.reduction_percent,
    before_http: first.totals.before.http_requests, after_http: first.totals.after.http_requests,
    before_order_samples: first.totals.before.order_samples, after_order_samples: first.totals.after.order_samples,
    after_a3_order_p99_ms: first.mixed.after[2].order_p99_ms,
  } };
mkdirSync(resolve(directory, 'data'), { recursive: true });
writeFileSync(resolve(directory, 'data/reviewed-data.json'), JSON.stringify(snapshot, null, 2) + '\n');
writeFileSync(resolve(directory, 'data/evidence-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log(JSON.stringify({ queries: Object.keys(snapshot.queries).length, rows: Object.values(snapshot.queries).reduce((sum, query) => sum + query.rows.length, 0), source_hashes_verified: 17 }));
