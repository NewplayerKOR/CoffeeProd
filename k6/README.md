# CoffeeProd k6 Performance Tests

Windows PC의 Git Bash에서 k6를 실행하고 배포 유사 Ubuntu 서버의 Spring Boot, PostgreSQL, Redis를 대상으로 측정한다. 부하 발생기와 테스트 대상 서버는 같은 장비에서 실행하지 않는다.

## 1. 테스트 원칙

- 검증된 동일 DB snapshot으로 최적화 전·후를 비교함
- Git commit, DB snapshot SHA-256, 대상 환경과 k6 버전을 실행마다 기록함
- 정상 부하 기준선과 시스템 한계 탐색 결과를 분리함
- 읽기·로그인 테스트 후 Redis 상태를 확인함
- 주문 테스트 전후 상품 재고와 성공 주문 수를 대조함
- 실제 운영 결제와 공개 시연 환경에서는 쓰기 테스트를 실행하지 않음

## 2. 준비

Git Bash에서 프로젝트 루트를 기준으로 실행한다.

```bash
winget.exe install --id GrafanaLabs.k6 --exact --source winget
k6 version

cp k6/.env.example k6/.env
bash scripts/seed/commerce/load_commerce.sh --verify-only
```

`k6/.env`에서 다음 값을 실제 테스트 환경에 맞게 변경한다.

```dotenv
BASE_URL=https://staging-api.example.com
PRODUCT_ID=1
TEST_ENV=home-server
DB_SNAPSHOT_SHA256=<pg_dump-file-sha256>
REQUIRE_RUN_METADATA=true
```

`k6/data/users.json`은 거래 시드 검증이 성공한 후 생성된다. 이 파일에는 합성 계정과 실제 배송지 PK가 들어가며 Git에는 포함하지 않는다.

이미지 테스트는 공개 상품 API에서 `ON_SALE` 상품 175개의 R2 URL을 수집한 뒤 96개 고유 WebP를 실제로 내려받는다. 전체 카탈로그 192개는 `ON_SALE 175`, `SOLD_OUT 11`, `HIDDEN 6`으로 구성되며 공개 API가 판매 중 상품만 반환하는 것은 정상 계약이다. 다른 환경에서는 `k6/.env`의 기대 건수를 변경한다.

`REQUIRE_RUN_METADATA=true`이면 Smoke 이외 테스트에서 환경명, 64자리 DB snapshot SHA-256, Git commit이 없거나 working tree가 수정된 상태일 때 실행을 차단한다. 최종 비교를 실행할 때는 변경사항을 먼저 commit한다.

## 3. 간단 실행

서버를 켠 뒤 프로젝트 루트의 Git Bash에서 실행한다.

```bash
# 약 4분: 공개 API, 전체 이미지 자산, 상품 조회 부하
bash k6/run-suite.sh quick

# 약 2분: 상품 API 이미지 매핑과 R2/CDN만 검사
bash k6/run-suite.sh assets
```

Quick 모드는 로그인 계정이나 쓰기 권한이 필요하지 않다. 서버 연결, 공개 상품 이미지 URL 175개, 고유 WebP 96개, CDN 캐시와 10 RPS 상품 조회를 한 번에 확인한다. 중간 시나리오가 실패하면 이후 부하를 중단하며 이미 생성된 결과는 보존한다.

전체 읽기·인증 성능 근거를 다시 만들 때는 다음 명령을 사용한다.

```bash
# 기본 Soak 30분, 전체 약 1시간
bash k6/run-suite.sh full

# 최종 2시간 Soak 포함, 전체 약 2시간 30분
SOAK_DURATION=2h bash k6/run-suite.sh full
```

Full 모드는 `k6/data/users.json`의 테스트 계정과 정상 Redis 로그인이 필요하다. 주문 동시성은 데이터를 변경하므로 자동 스위트에 포함하지 않는다.

## 4. 개별 실행 순서

```bash
# 1. 배포 연결과 API 계약 확인
bash k6/run.sh smoke

# 2. 상품 이미지 URL과 R2/CDN 검증
bash k6/run.sh image-assets

# 3. 최적화 전 정상 부하 기준선
bash k6/run.sh baseline

# 4. 단계별 부하 증가로 포화 구간 탐색
bash k6/run.sh stress

# 5. 순간 트래픽 증가와 회복 확인
bash k6/run.sh spike

# 6. 메모리·연결 누수와 장시간 안정성 확인
SOAK_DURATION=2h bash k6/run.sh soak

# 7. DB snapshot 복원 후 주문·재고 동시성 확인
ALLOW_WRITES=true ORDER_VUS=50 CANCEL_AFTER=false \
bash k6/run.sh order-concurrency
```

환경변수는 `k6/.env`보다 명령행 앞에 지정한 값이 우선한다.

```bash
BASELINE_DURATION=10m BASELINE_PUBLIC_RPS=30 \
bash k6/run.sh baseline
```

## 5. 테스트 프로파일

| 스크립트 | 목적 | 기본 부하 | 데이터 변경 |
|---|---|---|---:|
| `smoke.js` | 배포 연결과 공개 API 계약 확인 | VU 1, 1회 | 없음 |
| `image-assets.js` | API 이미지 매핑, 전체 R2 WebP, CDN 반복 다운로드 확인 | 전체 96개 + 10 RPS, 2분 | 없음 |
| `baseline.js` | 최적화 전·후 정상 부하 비교 | 총 20 iteration/s, 5분 | RefreshToken 변경 |
| `read-load.js` | 공개 조회만 격리 측정 | 10 RPS, 2분 | 없음 |
| `login-load.js` | BCrypt·JWT·Redis만 격리 측정 | 2 RPS, 2분 | RefreshToken 변경 |
| `stress.js` | 포화 구간과 처리 한계 탐색 | 25 → 50 → 100 iteration/s | RefreshToken 변경 |
| `spike.js` | 순간 급증과 정상 복귀 확인 | 10 → 100 → 10 iteration/s | RefreshToken 변경 |
| `soak.js` | 장시간 누수·성능 저하 확인 | 총 20 iteration/s, 기본 30분 | RefreshToken 변경 |
| `order-concurrency.js` | 원자적 재고 차감과 초과 판매 확인 | 기본 VU 2, 각 1회 | 주문·재고 변경 |

최종 Soak는 `SOAK_DURATION=2h` 이상으로 실행한다. 기본 30분은 환경과 모니터링 구성을 확인하기 위한 짧은 실행값이다.

이미지 자산 테스트의 기본 합격 조건:

- 공개 `ON_SALE` 상품 175개 모두 승인된 R2 HTTPS WebP URL 사용
- 고유 이미지 URL 96개
- 전체 이미지 HTTP 200, `Content-Type: image/webp`, 양수 `Content-Length`
- `Cache-Control: public, max-age=31536000, immutable`
- Cloudflare cache status 헤더 존재
- 부하 구간 HTTP 실패율 1% 미만, p95 1초 미만, p99 2초 미만
- 예열 후 CDN cache HIT 비율 90% 초과
- Dropped iteration 0건

`SOLD_OUT` 11개와 `HIDDEN` 6개를 포함한 전체 192개 URL 정책은 `scripts/seed/catalog/sql/verify_catalog.sql`의 DB 검증 대상으로 관리한다. 공개 API 부하 테스트에서 비공개 상품을 강제로 노출하지 않는다.

## 6. 배포 유사 트래픽 구성

정상 부하와 Soak는 기본적으로 다음 비율을 사용한다.

| 트래픽 | 비율 | 주요 API |
|---|---:|---|
| 공개 조회·추천 | 75% | 상품 목록·필터·상세, 카테고리, 리뷰, QnA, 익명 추천 |
| 인증 사용자 조회 | 20% | 내 정보, 내 주문, 내 배송지 |
| 로그인 | 5% | BCrypt 검증, JWT 발급, Redis RefreshToken 저장 |

Stress와 Spike도 같은 75:20:5 비율을 무작위로 적용한다. 인증 조회 VU는 AccessToken을 재사용하며 장시간 테스트 중 401이 발생하면 재로그인하고 `session_relogins`에 기록한다.

## 7. 잠정 판정 기준

| 지표 | 기본 기준 |
|---|---:|
| HTTP 실패율 | 1% 미만 |
| 전체 요청 p95 | 500ms 미만 |
| 전체 요청 p99 | 1,000ms 미만 |
| Check 성공률 | 99% 초과 |
| Business/Auth 오류율 | 1% 미만 |
| Dropped iteration | 0건 |
| 이미지 자산 오류율 | 0% |
| 이미지 부하 p95 | 1,000ms 미만 |
| 이미지 부하 p99 | 2,000ms 미만 |
| 이미지 cache HIT | 90% 초과 |
| 주문 생성 p95 | 1,500ms 미만 |
| 예상 밖 주문 결과 | 0건 |

이 값은 확정 SLO가 아니라 최초 기준선용 품질 게이트다. Stress와 Spike에서는 임계치 실패 자체보다 최초 실패 RPS, 오류 형태, 회복 여부와 서버 자원 포화 지점을 함께 기록한다.

## 8. 주문 테스트 판정

`order-concurrency.js`는 다음 결과만 정상으로 인정한다.

- HTTP 201과 유효한 `orderId`: `orders_created` 증가
- HTTP 400과 정확한 `재고가 부족합니다.` 응답: `orders_rejected_by_stock` 증가
- 그 외 4xx·5xx·본문 불일치: `unexpected_order_results` 증가 및 테스트 실패

`TEST_ENV=prod` 또는 `production`에서는 주문 테스트 실행을 차단한다.

`CANCEL_AFTER=true`이면 성공한 PENDING 주문을 즉시 취소해 재고를 복구한다. 재고 한계 검증은 `false`로 실행하고 종료 후 다음 값을 DB에서 대조한다.

1. 테스트 전후 상품 재고 차이
2. 성공 생성 주문 수
3. 재고 부족 거절 수
4. 상품 재고 음수 여부
5. 주문상품 금액과 주문 총액 정합성

## 9. 결과 파일

개별 실행마다 `k6/results/`에 세 파일을 생성한다.

```text
<scenario>-<UTC timestamp>-summary.json
<scenario>-<UTC timestamp>-metadata.txt
<scenario>-<UTC timestamp>-report.md
```

Metadata에는 실행 ID, 시나리오, UTC 시각, 대상 URL, 이미지 호스트, 부하 설정, 환경명, Git commit, working tree 상태, DB snapshot SHA-256, k6 버전과 실행기 OS를 저장한다. Markdown 보고서는 k6 종료 코드와 주요 성능 지표를 자동 정리한다. Node.js가 없으면 JSON과 metadata만 생성한다.

`run-suite.sh`는 실행 ID별 하위 디렉터리에 위 파일과 전체 실행 인덱스 `README.md`를 생성한다.

```text
k6/results/portfolio-quick-<UTC timestamp>/
├── README.md
├── portfolio-quick-<UTC timestamp>-smoke-report.md
├── portfolio-quick-<UTC timestamp>-image-assets-report.md
└── portfolio-quick-<UTC timestamp>-read-load-report.md
```

결과 디렉터리는 Git에서 제외한다. 포트폴리오에는 비밀값을 제거한 Markdown 보고서와 비교표를 사용하고 원본 JSON·metadata는 검증 근거로 별도 보관한다.

이전·이후 동일 시나리오를 비교할 때는 다음 명령으로 정적 Markdown 비교표를 생성한다.

```bash
node k6/tools/compare-runs.mjs \
  /path/to/before-summary.json \
  /path/to/after-summary.json \
  k6/results/read-load-comparison.md
```

비교표는 응답시간, 실패율, Check, Dropped iteration과 요청당 수신량을 함께 표시한다. 단일 실행 차이는 환경 잡음일 수 있으므로 같은 조건을 3회 반복한 중앙값으로 최종 개선 여부를 판단한다.

## 10. 결과 해석 범위

- `baseline`, `read-load`, `stress`, `spike`, `soak`는 백엔드 API JSON 성능을 측정한다.
- `image-assets`는 API의 이미지 URL 매핑과 R2/CDN 파일 전달을 측정한다.
- 브라우저 LCP, CLS와 Next Image 변환 성능은 Lighthouse 또는 브라우저 테스트로 별도 측정한다.
- 실제 개선 수치는 동일 서버, 동일 DB snapshot, 동일 k6 설정으로 최소 3회 측정한 중앙값을 비교한다.
- 이미지 URL 적용 후 `data_received` 증가는 실제 응답 데이터가 추가된 정상 변화일 수 있다.

## 11. 함께 수집할 서버 지표

- Spring Boot: CPU, Heap, GC pause, 요청 스레드
- HikariCP: active, idle, pending connection
- PostgreSQL: CPU, connection, lock, temporary file, slow query
- Redis: latency, connection, command 처리량, memory
- k6: 요청 수, RPS, p95, p99, 실패율, checks, dropped iterations

성능 개선은 개선 전·후 commit과 동일 DB snapshot·서버 사양·부하 조건을 사용해 비교한다.
