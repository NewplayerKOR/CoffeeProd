import React from 'react';
import { DataComponent, DataTable, EvidenceChart, ReportSection, RichNarrative, useDataApp } from '../../data-app-public.jsx';
import sqlImage from '../assets/sql-access-paths.png';

const paired = {
  type: 'bar', x: 'metric', y: 'beforeMs', fields: ['beforeMs', 'afterMs'],
  stackable: false, startAtZero: true, showValues: true, valueDecimals: 2, yLabel: 'ms',
  colors: { beforeMs: '#52616d', afterMs: '#007f75' }, legend: { labels: { beforeMs: '적용 전', afterMs: '적용 후' } },
};
const repeated = {
  type: 'bar', x: 'run', y: 'p95Ms', fields: ['p95Ms', 'p99Ms'],
  stackable: false, startAtZero: true, valueDecimals: 2, yLabel: 'ms',
  colors: { p95Ms: '#007f75', p99Ms: '#ad507c' }, legend: { labels: { p95Ms: 'p95', p99Ms: 'p99' } },
};
const routes = {
  type: 'bar', x: 'endpoint', y: 'publicMs', fields: ['publicMs', 'sshMs'],
  stackable: false, startAtZero: true, showValues: true, valueDecimals: 2, yLabel: 'ms',
  colors: { publicMs: '#52616d', sshMs: '#007f75' }, legend: { labels: { publicMs: '공개 HTTPS', sshMs: 'SSH 원본' } },
};
const components = {
  type: 'stackedBar', x: 'client', y: 'waitingMs', fields: ['waitingMs', 'receivingMs'],
  startAtZero: true, valueDecimals: 2, yLabel: 'ms',
  colors: { waitingMs: '#007f75', receivingMs: '#ad507c' },
  legend: { labels: { waitingMs: '첫 응답 대기 · 평균', receivingMs: '본문 수신 · 평균' } },
};
const decimal = (precision) => (value) => value.toFixed(precision);

function ReportTable({ id, queryId, title, columns }) {
  const { snapshot, visible } = useDataApp();
  const rows = snapshot.queries[queryId].rows;
  return visible(id) && <DataComponent id={id} queryId={queryId} title={title} kind="table" displayRows={rows} sourceRows={rows}>
    <DataTable rows={rows} columns={columns} caption={title} searchable={false} compactNumbers={false} />
  </DataComponent>;
}

export function ReportContent() {
  const { snapshot, visible, canEdit, mode, appTitle, setAppTitle } = useDataApp();
  const rows = (id) => snapshot.queries[id].rows;
  const narrative = (id, text) => <RichNarrative id={`${id}:body`} value={text} label="보고서 내용 편집" />;
  return <article className="report-content coffeeprod-report" aria-label="CoffeeProd 성능 개선 최종 보고서">
    <header className="report-hero">
      <h1 data-data-app-title contentEditable={canEdit && mode === 'edit'} suppressContentEditableWarning
        onBlur={canEdit && mode === 'edit' ? (event) => setAppTitle(event.currentTarget.textContent.trim() || appTitle) : undefined}>{appTitle}</h1>
      {narrative('report-introduction', '주문 조회 개선 성과와 전달 경로 진단의 한계\n\n측정 기간: 2026-10-07~12 KST · Backend QA · 최종 프로젝트 인수와 구분')}
    </header>

    <ReportSection id="executive-summary" title="핵심 결론" queryId="outcomes" queryIds={['outcomes', 'order_summary']}
      sourceRowsByQuery={{ outcomes: rows('outcomes'), order_summary: rows('order_summary') }} showHeading={false}>
      {narrative('executive-summary', '## 핵심 결론\n\n- **1차는 주문 조회 범위에서 성공했습니다.** 주문 15만 건·주문상품 37.5만 건에서 인덱스 2개를 적용한 뒤 주문 목록 p95의 실행별 중앙값이 **194.44 → 141.95ms, 약 27% 감소**했습니다.\n- **2차는 진단을 완료했지만 새로운 성능 개선은 확인하지 못했습니다.** 전달 경로를 분리해 원인 후보를 좁혔으며, 근본 원인은 미확정입니다.\n- 포트폴리오의 대표 사례는 **1차의 검증 → 문제 발견 → 개선 → 재검증**입니다. 2차는 운영 계측과 가설 검증을 보여주는 보조 사례로 사용합니다.')}
    </ReportSection>
    <ReportTable id="cycle-outcomes" queryId="outcomes" title="사이클별 성과 판정" columns={[
      { field: 'cycle', label: '범위' }, { field: 'result', label: '판정' }, { field: 'evidence', label: '근거' }, { field: 'portfolio', label: '포트폴리오 활용' },
    ]} />

    <section className="report-section" aria-label="1차 주문 조회 개선">
      <ReportSection id="order-story" title="1차 주문 조회 개선" queryId="order_summary" showHeading={false}>
        {narrative('order-story', '## 1차 · 필요한 행을 찾는 접근 경로를 개선했습니다\n\n회원별 주문 목록은 주문 테이블 전체 탐색·정렬을, 주문상품 조회는 37.5만 행 탐색을 수행했습니다. CPU나 DB 풀 포화가 뚜렷하지 않은 상태에서 서버 증설보다 **조회 조건에 맞는 인덱스**를 우선했습니다.\n\n`orders(member_id, order_date DESC)`와 `order_item(order_id)`를 V17으로 적용했습니다. 첫 번째는 회원 필터와 최신순 정렬을, 두 번째는 선택한 주문의 상품 조회를 지원합니다. 이는 N+1 해결이나 쿼리 수 감소 작업이 아닙니다.')}
      </ReportSection>
      <EvidenceChart id="order-summary" queryId="order_summary" title="주문 목록 p95 · 실행별 중앙값" spec={paired}
        rows={rows('order_summary')} sourceRows={rows('order_summary')} height={300}>
        {narrative('order-summary-caption', '전후 각 3회의 p95를 구한 뒤 중앙값을 비교했습니다. 모든 요청을 합친 p95가 아닙니다. 약 27%는 관측된 감소이며 완전히 통제된 인과 효과가 아닙니다.')}
      </EvidenceChart>
      <EvidenceChart id="order-runs" queryId="order_runs" title="주문 목록 반복 측정 · A3*는 보충 실행" spec={repeated}
        rows={rows('order_runs')} sourceRows={rows('order_runs')} height={340}>
        {narrative('order-runs-caption', 'A3*는 별도 재예열한 보충 실행입니다. p99 **303.79ms**를 그대로 보존했으며 안정적인 tail 개선을 주장하지 않습니다.')}
      </EvidenceChart>
      <ReportTable id="order-run-values" queryId="order_runs" title="주문 표본과 실행별 분위수" columns={[
        { field: 'run', label: '실행' }, { field: 'phase', label: '조건' }, { field: 'samples', label: '주문 표본 수' },
        { field: 'p95Ms', label: 'p95(ms)', renderCell: decimal(2) }, { field: 'p99Ms', label: 'p99(ms)', renderCell: decimal(2) },
      ]} />
    </section>

    <section className="report-section" aria-label="SQL과 기능 재검증">
      <ReportSection id="sql-story" title="SQL 접근 경로 검증" queryId="sql_plans" showHeading={false}>
        {narrative('sql-story', '## SQL·HTTP·기능을 함께 재검증했습니다\n\n`EXPLAIN (ANALYZE, BUFFERS)`에서 전체 탐색이 Index Scan / Index Only Scan으로 바뀌었고 반환 행 수는 동일했습니다. SQL 실행 시간은 적용 전 1회와 적용 후 3회 중앙값이며 캐시 차이·축약 join의 한계가 있습니다. **SQL 감소 배율을 API 개선 배율로 바꾸지 않습니다.**')}
      </ReportSection>
      <DataComponent id="sql-image" queryId="sql_plans" title="SQL 접근 경로와 실행 시간 · 로그 눈금" kind="custom"
        displayRows={rows('sql_plans')} sourceRows={rows('sql_plans')}>
        <img className="report-figure" src={sqlImage} alt="진단 SQL 세 개의 적용 전 1회와 적용 후 3회 중앙값 비교. 가로축은 명시적인 로그 눈금이며 API 시간이 아닙니다." />
      </DataComponent>
      <ReportTable id="sql-plans" queryId="sql_plans" title="진단 SQL의 실행 시간과 접근 경로" columns={[
        { field: 'query', label: '진단 SQL' }, { field: 'beforeMs', label: '전(ms)', renderCell: decimal(3) },
        { field: 'afterMs', label: '후(ms)', renderCell: decimal(3) }, { field: 'returnedRows', label: '반환 행' },
        { field: 'afterAccess', label: '적용 후 접근' },
      ]} />
      <ReportTable id="regressions" queryId="regressions" title="선택한 기능·권한·안전성 회귀" columns={[
        { field: 'scope', label: '검증 범위' }, { field: 'passed', label: '통과' }, { field: 'total', label: '전체' }, { field: 'conclusion', label: '확인 내용' },
      ]} />
      {narrative('regression-limits', '전후 혼합 본 측정 HTTP는 **18,055 / 18,057건**, 주문 목록 표본은 **1,475 / 1,461건**입니다. 선정 완료 실행의 HTTP·check 실패와 dropped iteration은 0입니다. 선택 회귀 통과는 전체 최신 테스트·실결제·프론트엔드 인수를 의미하지 않습니다.')}
    </section>

    <section className="report-section" aria-label="개선 범위의 한계">
      <ReportSection id="scope-limits" title="전체 혼합 지연의 한계" queryId="overall_summary" showHeading={false}>
        {narrative('scope-limits', '## 주문 개선을 전체 서비스 개선으로 확대하지 않습니다\n\n전체 혼합 p95 중앙값은 **215.58 → 231.50ms**, p99는 **266.26 → 309.07ms**였습니다. 시간대·네트워크·캐시·OS 이력이 완전히 같지 않아 증가를 인덱스의 악영향으로 단정하지 않습니다. 다만 전체 지연 개선을 확인했다고 표현할 근거도 없습니다.')}
      </ReportSection>
      <EvidenceChart id="overall-summary" queryId="overall_summary" title="전체 혼합 지연 · 실행별 분위수의 중앙값" spec={paired}
        rows={rows('overall_summary')} sourceRows={rows('overall_summary')} height={320} />
    </section>

    <section className="report-section" aria-label="2차 전달 경로 진단">
      <ReportSection id="diagnostic-story" title="2차 전달 경로 진단" queryId="route_comparison" showHeading={false}>
        {narrative('diagnostic-story', '## 2차 · 지연이 생기는 구간을 분리했습니다\n\n같은 혼합 시나리오에서 공개 HTTPS와 SSH 원본을 비교하고 순서를 교차했습니다. 서버 지표·요청 timing·Ubuntu 내부 조회·TCP 헤더·공유/단독 SSH·두 OpenSSH 빌드를 단계적으로 확인했습니다.\n\n공개 경로의 클라이언트 지연이 더 높았지만 공개 HTTP/2·TLS와 원본 HTTP/1.1·SSH의 차이가 함께 포함됩니다. 아래 수치를 **순수 Cloudflare 비용이나 개선율로 해석하지 않습니다.**')}
      </ReportSection>
      <EvidenceChart id="route-comparison" queryId="route_comparison" title="순서 교차 혼합 비교 · 경로별 p99" spec={routes}
        rows={rows('route_comparison')} sourceRows={rows('route_comparison')} height={340}>
        {narrative('route-caption', '2026-10-08 공개→원본 각 5분 본 측정, HTTP12,038/check25,340 성공·오류/drop0. 비교 경로의 차이이며 개선 전후 실험이 아닙니다.')}
      </EvidenceChart>
      <ReportSection id="ssh-story" title="SSH 클라이언트 진단" queryId="ssh_components" showHeading={false}>
        {narrative('ssh-story', '### 클라이언트 교체만으로 지연은 제거되지 않았습니다\n\n단독 Windows/Git OpenSSH 각 60건의 카테고리 GET을 네 블록으로 순서 교차했습니다. HTTP/check120건 모두 성공했지만 중앙값은 **44.19 / 54.24ms**로 수십 ms가 남았습니다. Windows는 첫 응답 대기, Git은 본문 수신에 시간 분포가 집중됐습니다.')}
      </ReportSection>
      <EvidenceChart id="ssh-components" queryId="ssh_components" title="SSH 응답 시간 분해 · 평균 성분" spec={components}
        rows={rows('ssh_components')} sourceRows={rows('ssh_components')} height={320}>
        {narrative('ssh-caption', '평균 waiting+receiving은 평균 duration과 일치합니다. 중앙값·p95/p99 성분을 합친 그림이 아닙니다. 저빈도 카테고리·빌드/런타임 동시 차이로 정확한 TCP/VM 원인을 확정하지 않습니다.')}
      </EvidenceChart>
      <ReportTable id="ssh-values" queryId="ssh_components" title="단독 SSH의 전체 응답 교환 시간" columns={[
        { field: 'client', label: '클라이언트' }, { field: 'samples', label: '표본' },
        { field: 'medianMs', label: '중앙값(ms)', renderCell: decimal(2) }, { field: 'p95Ms', label: 'p95(ms)', renderCell: decimal(2) },
        { field: 'p99Ms', label: 'p99(ms)', renderCell: decimal(2) },
      ]} />
      {narrative('diagnostic-conclusion', '**진단 범위는 종료했고 근본 원인은 미확정입니다.** SSH·클라이언트·TCP/VM 전달 구간을 우선 후보로 남겼으며, 특정 Backend 코드 병목을 입증하지 못해 임의의 DB/BCrypt/메모리 튜닝은 하지 않았습니다. 카테고리 관측을 전체 혼합 tail·과거 A3 원인으로 일반화하지 않습니다.')}
    </section>

    <section className="report-section" aria-label="포트폴리오 어필 포인트">
      <ReportSection id="portfolio-story" title="포트폴리오 활용" queryId="portfolio_points" showHeading={false}>
        {narrative('portfolio-story', '## 포트폴리오의 메인은 1차, 2차는 보조 사례입니다\n\n어필의 근거는 도구 개수가 아니라 **문제를 좁힌 이유·변경 선택·반복 검증·한계 설명**입니다. 기술 역량을 설명할 자료는 확보했지만 채용 평가 효과를 보장하지는 않습니다.')}
      </ReportSection>
      <ReportTable id="portfolio-points" queryId="portfolio_points" title="어필 포인트와 제시할 증빙" columns={[
        { field: 'point', label: '역량' }, { field: 'evidence', label: '증빙' }, { field: 'message', label: '설명할 내용' },
      ]} />
      {narrative('portfolio-copy', '### 포트폴리오 요약 문구\n\n> 주문 15만 건·주문상품 37.5만 건에서 실행계획으로 전체 탐색을 확인하고 조회 조건에 맞는 인덱스 2개를 적용했습니다. 전후 각 3회 측정에서 주문 목록 p95의 중앙값이 약 27% 감소했으며, 선택한 기능·권한 회귀 검증을 통과했습니다. 보충 실행과 환경 차이, 전체 혼합 지연의 미개선도 함께 기록했습니다.\n\n2차는 “서버 처리와 전달 경로를 분리해 지연 원인 후보를 좁혔으며, 근본 원인은 미확정으로 남겼다”로 표현합니다. **전체 API 27% 개선·p99 완전 해결·Cloudflare 원인 확정**은 사용하지 않습니다.')}
    </section>

    <section className="report-section" aria-label="측정 방법과 남은 범위">
      {narrative('measurement-methods', '## 측정 조건과 책임 범위\n\n서버는 사용자 제공 기준 Ubuntu **3 vCPU·2.9GiB**, Docker의 Spring Boot·PostgreSQL·Redis입니다. Windows에서 k6를 실행하고 Actuator→SSH→Prometheus로 서버 지표를 수집했습니다. 컨테이너 개별 CPU·메모리 제한은 없었습니다.\n\n1차는 같은 공개 HTTPS 경로, 공개15·회원4·로그인1 iteration/s의 혼합 시나리오입니다. 주문 목록은 page=0,size=10, 전후 각 2분 예열·5분 본 측정 3회·휴지90초이며 데이터 변경 없음은 사용자 확인 근거입니다. 목표20 iteration/s와 HTTP requests/s는 다른 지표입니다.\n\nDEV는 V17 구현, 사용자는 배포·DB 적용, QA는 측정·검증·판정을 수행했습니다. AI는 분석과 작성에 활용됐지만 개선 효과의 근거는 측정·실행계획·회귀 결과입니다. 본인의 실제 담당 범위를 구분해 설명합니다.')}
      {narrative('remaining-scope', '## 남은 과제는 별도 인수 범위입니다\n\n전체 혼합 tail의 정확한 원인, 인덱스 쓰기 비용·주문/결제 동시성, 최대 처리량·장기 안정성, 전체 최신 회귀·Frontend·프로젝트 최종 인수는 미완료입니다. 추가 진단은 요청별 tracing과 SSH 없는 사설망 원본 비교를 먼저 확보한 뒤, 느린 구간에 맞춰 JFR/SQL/TCP·VM 분석을 선택합니다.\n\n이 보고서는 보존된 2026-10-07~12 자료의 최종 정리입니다. 새 부하·배포·DB/운영 설정 변경은 실행하지 않았으며 실패·중단·A3 원본을 교체하지 않았습니다.')}
    </section>
  </article>;
}
