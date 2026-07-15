# CoffeeProd k6 Performance Tests

Windows PC의 Git Bash에서 k6를 실행하고, 배포된 Ubuntu 백엔드를 대상으로 테스트한다.
백엔드 서버와 같은 장비에서 k6를 실행하지 않는다.

## 1. k6 설치

PowerShell 또는 Git Bash에서 실행한다.

```bash
winget.exe install --id GrafanaLabs.k6 --exact --source winget
k6 version
```

설치 후 `k6`를 찾지 못하면 터미널을 다시 시작하고 `where.exe k6`로 경로를 확인한다.

## 2. 로컬 설정

Git Bash에서 프로젝트 루트 기준으로 실행한다.

```bash
cp k6/.env.example k6/.env
cp k6/data/users.example.json k6/data/users.json
```

`k6/.env`에서 `BASE_URL`, `PRODUCT_ID`와 부하 값을 수정한다.
로그인·주문 테스트를 수행하려면 `k6/data/users.json`을 실제 테스트 계정과 배송지 ID로 교체한다.
두 파일은 Git에 포함되지 않는다.

## 3. 실행

```bash
# 배포 연결과 공개 API 상태 확인
bash k6/run.sh smoke

# 상품 중심 공개 조회 부하
bash k6/run.sh read-load

# 테스트 계정 로그인 부하
bash k6/run.sh login-load

# 주문 생성 동시성 테스트: 스테이징 또는 테스트 데이터에서만 실행
ALLOW_WRITES=true bash k6/run.sh order-concurrency
```

추가 k6 옵션은 실행 스크립트 뒤에 전달할 수 있다.

```bash
bash k6/run.sh read-load --http-debug=full
```

결과 요약은 `k6/results/`에 생성된다.

## 4. 시나리오

| 스크립트 | 대상 | 데이터 변경 |
|---|---|---:|
| `smoke.js` | 카테고리, 상품, 리뷰, QnA 연결 확인 | 없음 |
| `read-load.js` | 공개 조회 트래픽 혼합 | 없음 |
| `login-load.js` | BCrypt/JWT/Redis 로그인 처리 | RefreshToken 변경 |
| `order-concurrency.js` | 장바구니 추가와 동시 주문/재고 차감 | 있음 |

기본 임계치는 실패율 1% 미만, `p(95) < 500ms`, `p(99) < 1000ms`, check 성공률 99% 초과다.
주문 생성은 별도로 `p(95) < 1500ms`를 적용한다. 실제 SLO가 정해지면 값을 조정한다.

## 5. 주문 테스트 주의사항

- 실제 Toss 운영 결제 승인은 포함하지 않는다.
- `ORDER_VUS`만큼 서로 다른 테스트 계정과 배송지가 필요하다.
- 모든 VU는 같은 `PRODUCT_ID`를 주문하므로 재고 동시성 검증에 사용한다.
- 테스트 전 상품 재고를 기록하고, 종료 후 성공 주문 수와 재고 감소량을 비교한다.
- `CANCEL_AFTER=true`이면 생성된 PENDING 주문을 즉시 취소해 재고를 복구한다.
- 재고 한계를 확인할 때는 `CANCEL_AFTER=false`를 사용하고 테스트 데이터를 별도로 정리한다.

## 6. 함께 확인할 서버 지표

- Spring Boot: CPU, Heap, GC, 요청 스레드
- HikariCP: active, idle, pending connection
- PostgreSQL: CPU, connection, lock, slow query
- Redis: latency, connection, command 처리량
- k6: `http_req_duration`, `http_req_failed`, `checks`, `dropped_iterations`
