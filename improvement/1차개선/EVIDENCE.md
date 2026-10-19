# 측정 근거와 한계

[본 보고서](README.md)의 수치·방법·실패 기록을 설명하는 부록입니다. 문서와 PNG는 그대로 열어 볼 수 있으며, 서버 연결이나 부하 재실행이 필요하지 않습니다.

## 지표 정의

- k6 `http_req_duration`은 sending + waiting + receiving이며 최초 DNS·연결·TLS 시간을 모두 포함한 사용자 화면 완료 시간이 아닙니다. [k6 공식 지표 정의](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/)
- p95·p99는 각각 측정 요청의 약 95%·99%가 해당 시간 이하였음을 나타냅니다. 최대값이나 성공률이 아닙니다.
- 본문의 대표값은 **각 실행의 분위수 3개를 정렬한 중앙값**입니다. 3회 요청 전체를 합친 pooled p95·p99가 아닙니다.
- 감소율은 `(적용 전 중앙값 - 적용 후 중앙값) / 적용 전 중앙값 × 100`입니다.
- iteration/s는 시나리오 실행 빈도입니다. 인증·setup·여러 HTTP 호출 때문에 HTTP RPS와 같다고 가정하지 않습니다.
- 요청 성공률·check 통과율·dropped iteration을 함께 봤습니다. 중단 실행을 제외한 선정 완료 실행의 0건 실패만 보고합니다.
- 서버 rolling p95·p99는 Prometheus histogram에서 추정한 1분 창 지표입니다. 첫 1분을 제외했으며 클라이언트의 실행 전체 분위수와 별개입니다. DB 단독 시간이나 요청별 추적 자료도 아닙니다.

## 혼합 부하 실행별 값

단위 ms. 주문 값은 `my-orders` 엔드포인트 표본, 전체 값은 해당 k6 실행의 전체 HTTP 표본 기준입니다.

| 실행 | 주문 표본 | 주문 p95 | 주문 p99 | 전체 p95 | 전체 p99 | 전체 HTTP |
|---|---:|---:|---:|---:|---:|---:|
| B1 | 510 | 192.17 | 205.79 | 211.96 | 259.01 | 6,018 |
| B2 | 498 | 194.44 | 210.55 | 215.58 | 266.26 | 6,018 |
| B3 | 467 | 198.60 | 206.06 | 228.97 | 269.56 | 6,019 |
| A1 | 449 | 141.95 | 146.71 | 214.30 | 269.25 | 6,020 |
| A2 | 519 | 136.32 | 144.19 | 243.35 | 336.41 | 6,019 |
| A3* | 493 | 167.84 | 303.79 | 231.50 | 309.07 | 6,018 |

B1~B3: `baseline-before-20261007T054621Z`. A1~A2: `order-index-after-20261007T123425Z`. A3*: `order-index-after-supplement-20261007T152042Z`. 실행 ID의 시각 접미사는 UTC입니다.

성공 check 합계는 적용 전 38,000개, 적용 후 38,006개입니다. HTTP 실패·check 실패·dropped iteration은 6회 모두 0입니다. 원시 HTTP duration에서 재계산한 전체·회원 계열의 건수와 분위수는 native summary와 허용차 0.000001ms 내에서 일치한 기록이 있습니다.

6회는 같은 공개 경로·부하 설정에서 입력 해시 20개 항목이 일치했습니다. A3는 별도 세션의 재예열 후 보충이며, 적용 후 3회를 연속 실행했다고 표현하지 않습니다.

## 환경과 통제 수준

| 항목 | 기록 | 증거 수준 |
|---|---|---|
| PostgreSQL | 16.15 | SQL 진단 출력 |
| 데이터량 | orders 150,000 / order_item 375,000 | 전후 SQL 출력과 사용자 확인 |
| 데이터 유지 | baseline 이후 배포 비교까지 추가·삭제 등 변경 없음 | USER_VERIFIED; 행 전체의 byte 동일성 검증은 아님 |
| 적용 전 revision | `99bfed04c746ec9ab8c32ac96de7fbba0446a84b` | 사용자 제공 이미지 출력 |
| 적용 후 revision | `bb40a8b2643f38856e0a0d2f8d8c4d951f4ceb8d` | 사용자 제공 이미지 출력 |
| 인덱스 | V17 success, 두 인덱스 valid·ready | 사용자 제공 PostgreSQL 검증 출력 |
| 환경 변화 | OS 업데이트·재부팅 후 기준선 수립; 이후 전후 사이 업그레이드 서비스 실행 이력 있음 | 패키지 목록 전체의 동일성은 미확인 |

측정 당시 CPU 표본 최대값은 적용 전 11.95%, 적용 후 10.33%였고 DB 커넥션 대기 표본은 0이었습니다. 표본 사이의 짧은 포화나 최대 처리 용량 부재를 증명하지는 않습니다. 네트워크·캐시·시간대·OS 차이 때문에 27%를 인덱스만의 순수 인과 효과나 통계적 유의성으로 주장하지 않습니다.

## SQL 비교의 범위

| SQL | 적용 전 1회 | 적용 후 1 / 2 / 3회 | 반환 행 |
|---|---:|---|---:|
| 주문 ID 페이지 | 14.632 | 0.116 / 0.123 / 0.132 | 10 |
| 회원 주문 count | 14.481 | 0.150 / 0.152 / 0.181 | 1 |
| 주문상품 join(축약) | 77.541 | 0.689 / 0.642 / 0.577 | 26 |

시간 단위는 ms입니다. 적용 전은 shared read가 더 많았고 적용 후는 shared hit 중심이었습니다. shared read를 실제 디스크 I/O와 동일시하지 않습니다. 축약 join은 ORM이 실제 발행한 SQL 그대로가 아니며 페이지 하위 쿼리를 포함합니다. 세 시간을 합쳐 API 시간을 추정하지 않습니다.

적용 후 회원 count의 Index Only Scan은 해당 실행에서 heap fetches=0이었습니다. 이후 갱신·visibility map 상태에서도 항상 0임을 보장하지 않습니다. 주문상품 scan의 `rows=3, loops=10`은 반올림된 행 평균이며 반환 행은 30이 아닌 26입니다. 중첩 노드의 시간·버퍼를 중복 합산하지 않았습니다.

## 경로 비교의 범위

완료 실행은 `order-path-20261008T034841Z`입니다. UTC 03:48:41~04:05:20(KST 12:48:41~13:05:20), 예열 각 30초·본 측정 각 2분·휴지 30초·주문 단독 2 iteration/s로 진행했습니다. 같은 계정의 page=0,size=10과 total=501을 확인했습니다.

| 쌍 | 실행 순서 | 공개 표본 | 공개 p95 / p99 | 원본 표본 | 원본 p95 / p99 |
|---|---|---:|---|---:|---|
| 1 | 공개 → 원본 | 241 | 136.73 / 191.43 | 241 | 12.02 / 12.98 |
| 2 | 원본 → 공개 | 241 | 128.51 / 185.74 | 240 | 11.52 / 21.51 |
| 3 | 공개 → 원본 | 240 | 121.00 / 161.05 | 240 | 10.03 / 10.71 |

단위 ms. 본 주문 표본은 공개 722 + 원본 721 = 1,443건, 성공 check 4,329개입니다. 예열·인증을 포함한 8단계 전체는 HTTP 1,588건 / check 4,708개이며 실패·누락은 0입니다. 240/241건 차이는 실행 경계의 실제 수집 건수로 보존했습니다.

공개는 HTTP/2·TLS·Cloudflare 경로, 원본은 HTTP/1.1·SSH 터널입니다. 순서를 일부 교차했지만 무작위 실험이나 순수 Cloudflare 비용 측정은 아닙니다. 서버 rolling p99는 각 실행의 첫 1분 제외 후 4개 유한 표본이며 histogram 재계산 기록도 일치했습니다. 공개 응답의 사후 `cf-cache-status`는 DYNAMIC이고, 양쪽 API 응답은 no-store/no-cache/max-age=0이었습니다. 이미지 CDN 캐시 성능과 별개입니다.

## 회귀와 실패 기록

| 검증 | 결과 | 확인한 범위 |
|---|---|---|
| 로컬 주문 서비스 | 7/7, 실패·오류·skip 0 | H2, `--rerun-tasks --offline`; 실제 PostgreSQL 검증 아님 |
| 배포 주문 읽기 | 13/13 | 두 회원 목록·페이지·정렬·상세·주문상품 값, 익명 401, 타인 상세 403; 로그인·로그아웃 포함 |
| 스크립트 안전성 | 9/9 | JSON 의미 비교, 민감정보·쓰기 가드 등 선택한 오프라인 검사 |

- 초기 baseline 예열 HTTP 502 73건은 OS 서비스 재시작과 시간상 겹쳤습니다. 환경 정비 후 새 기준선을 사용했으며 이를 코드 개선으로 계산하지 않았습니다.
- `order-index-after-20261007T121638Z`는 2회차 도중 scrape freshness 45초 가드에서 실패했습니다. 부분 결과를 보존·제외했고 정확한 중단 원인은 확정하지 않았습니다.
- 선정한 after 부모 실행의 3회차도 중단되어 불완전한 결과는 제외했습니다. 별도 재예열 후 A3를 보충했습니다.
- 인덱스 검증 SQL이 개별 `pg_get_indexdef(..., 2, true)` 문자열에 DESC가 포함된다고 가정해 오탐했습니다. 실제 인덱스는 정상이었으며 DEV가 키 이름과 정렬 속성을 별도 검사하도록 수정했습니다.
- 첫 경로 실행 `order-path-20261008T034309Z`는 예열 snapshot check 61건 실패로 중단했습니다. HTTP·공통 성공 응답과 반복 조회의 의미 값은 같았지만 JSON 객체 키 나열 순서에 민감한 직렬화 비교가 오탐했습니다.
- 객체 키만 정규화하고 배열 순서·전체 값 비교는 유지했습니다. 키 순서 변경은 통과하고 실제 값·배열 순서 변경은 실패하는 회귀를 확인했습니다. check 100%·HTTP 오류 0 기준은 완화하지 않았습니다.

전체 최신 Java 스위트, 주문 생성·취소·결제·마일리지 쓰기 부하, 실제 PG 결제, 프론트엔드·최대 처리량은 이 완료 판정에 포함하지 않았습니다.

## 공개 자료와 원본 추적

이 폴더에는 정제된 [metrics.json](data/metrics.json)과 [후속 A3 자료](data/a3-recheck.json), PNG 4개, 문서와 생성 스크립트를 포함합니다. `.env`, 계정 파일, 비밀번호·토큰·응답 본문·DB dump는 복사하지 않았습니다. 원시 결과는 기존 Git 제외 경로에 남아 있어 공개 저장소만으로 원시 요청 전체를 독립 재분석할 수는 없습니다.

원본 경로는 저장소 기준입니다. 파일별 전체 SHA-256은 `metrics.json.sources`, 실행별 원시 지표 해시는 각 `raw_sha256`에 있습니다. 해시는 원본 식별 수단이지 측정 정확성을 자체 보증하지 않습니다.

| 자료 | 기존 원본 경로 |
|---|---|
| 인덱스 HTTP 비교 | `k6/results/order-index-after-20261007T123425Z/http-comparison.json` |
| SQL 계획 비교 | `k6/results/order-index-after-20261007T123425Z/sql-plan-comparison.json` |
| 경로 비교 | `k6/results/order-path-20261008T034841Z/path-comparison.json` |
| 로컬 회귀 | `.md/improvement/monitoring/evidence-20261008/order-service-rerun.json` |
| 배포 회귀 | `k6/results/order-index-after-supplement-20261007T152042Z/order-read-functional-regression.json` |
| 경로 분석 검증 | `k6/results/order-path-20261008T034841Z/analysis-validation.json` |
| 종료 후 상태 | `k6/results/order-path-20261008T034841Z/post-suite-check.json` |
| 직렬화 오탐 수정 | `k6/results/order-path-20261008T034841Z/serialization-preflight-repair.json` |

## 자료 재생성

측정 자료를 갱신하려면 원본 8개가 있는 기존 작업 폴더에서 다음을 실행합니다. 네트워크 요청·DB 수정·부하 재실행 없이 기존 JSON을 읽어 정제된 자료를 생성합니다.

```powershell
node 'improvement/1차개선/scripts/extract-evidence.mjs'
```

후속 A3 예열 실패 자료는 별도 원본을 대상으로 `node 'improvement/1차개선/scripts/extract-a3-recheck.mjs'`로 생성합니다. 기존 전후 3회 수치와 그래프에 합치거나 대체하지 않습니다. [A3 후속 기록](A3_RECHECK_20261008.md)에 조건과 중단 결과가 있습니다.

그래프만 다시 만들 때는 포함된 `metrics.json`, Node.js, `@napi-rs/canvas`, 맑은 고딕 폰트가 필요합니다. 다음은 저장소 루트에서 실행하는 예입니다. `--canvas-module`로 설치된 라이브러리 경로를 지정할 수도 있습니다.

```powershell
node 'improvement/1차개선/scripts/render-charts.mjs' --font-dir 'C:/Windows/Fonts'
```

[figure-manifest.json](data/figure-manifest.json)은 그래프 크기·해시·비어 있지 않은 픽셀·문자 경계 검사 결과를 기록합니다. 자동 경계 검사와 별도로 생성된 PNG 4개를 직접 열어 한글·수치·축·범례와 겹침을 확인했습니다. 검토 기록은 [ARTIFACT_QA.json](data/ARTIFACT_QA.json)에 있습니다. 생성 이후 자료가 바뀌면 검토를 다시 수행해야 합니다.

보고서 파일·수치·해시·링크의 오프라인 검사는 `node 'improvement/1차개선/scripts/validate-report.mjs'`로 수행할 수 있습니다. `--visual-reviewed`는 현재 PNG를 실제 열어 확인한 뒤에만 붙입니다. 이 옵션 자체가 화면 검사를 수행하지는 않습니다.
