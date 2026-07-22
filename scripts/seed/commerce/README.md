# CoffeeProd 부하테스트용 거래 시드

이 디렉터리는 카탈로그 상품을 기준으로 회원, 배송지, 주문, 주문상품, 결제, 리뷰, QnA와 일별 매출 통계를 재현 가능하게 생성한다.

## 기본 데이터 규모

| 데이터 | 기본 규모 |
|---|---:|
| 일반 회원 | 10,000명 |
| 관리자 | 1명 |
| 주문 | 150,000건 |
| 주문상품 | 주문당 1~4건 |
| 리뷰 | 30,000건 |
| QnA | 20,000건 |
| 이력 기간 | 730일 |
| k6 활성 계정 | 200명 |

모든 회원명, 연락처, 주소, 주문과 콘텐츠는 부하 검증을 위한 합성 데이터다. 실제 개인정보나 실제 결제 키를 포함하지 않는다.

## 설계 원칙

- Flyway는 스키마 이력만 관리하고 대량 테스트 데이터는 별도 시드로 관리함
- PostgreSQL 집합 연산으로 대량 데이터를 생성해 거대한 CSV 커밋을 피함
- `loadtest-*@coffeeprod.local`, `LOADTEST-*` 식별자로 시드 범위를 제한함
- 같은 설정으로 다시 실행하면 이전 시드를 제거하고 동일한 분포로 재생성함
- 이벤트 시각은 `TIMESTAMPTZ`로 저장하고 영업일은 `Asia/Seoul` 기준으로 집계함
- 현재 상품 재고는 카탈로그의 현재 시점 스냅샷으로 유지하고 과거 주문만 생성함

## 선행 조건

1. Flyway V1~V16 적용이 완료되어야 한다.
2. `bash scripts/seed/catalog/load_catalog.sh`로 판매 중 상품 50개 이상을 적재해야 한다.
3. Docker PostgreSQL이 실행 중이어야 한다.
4. 로컬에 Node.js가 있어야 한다.

## 실행

Git Bash에서 프로젝트 루트 기준으로 실행한다.

```bash
bash scripts/seed/commerce/load_commerce.sh
```

실행기는 다음 순서로 처리한다.

1. 기존 부하테스트 시드 데이터 제거
2. 회원과 실제 DB PK를 가진 배송지 생성
3. 주문, 주문상품, 결제, 리뷰와 QnA 생성
4. 영향받은 영업일의 `sales_statistics` 재집계
5. 금액, 상태, 구매 이력과 집계 결과 검증
6. 실제 배송지 ID를 포함한 `k6/data/users.json` 생성

`k6/data/users.json`은 로컬 실행 파일이며 Git에 포함되지 않는다. 기존 파일이 있으면 성공적인 적재와 검증 후 덮어쓴다.

### 검증만 재실행

적재가 이미 커밋되었거나 검증 도중 중단한 경우 거래 데이터를 다시 생성하지 않고 검증과 k6 계정 내보내기만 실행한다.

```bash
bash scripts/seed/commerce/load_commerce.sh --verify-only
```

검증 SQL은 세션 범위에서만 `work_mem=128MB`, `temp_buffers=32MB`를 사용한다. 주문, 주문상품, 배송 완료 구매 조합과 일별 매출을 임시 테이블로 한 번씩 요약하므로 운영 테이블에 검증용 인덱스를 추가하지 않는다. 각 집계 단계와 소요 시간은 터미널에 출력된다.

기존 검증은 PostgreSQL 기본 `work_mem=4MB`에서 주문 15만 건을 직접 그룹화해 `HashAggregate`가 임시 파일로 분할되었다. Windows Docker 바인드 마운트에서 `BufFileRead`가 길어지는 원인이므로, 영구 인덱스를 먼저 추가하지 않고 검증 세션과 쿼리 구조만 개선했다. 실제 조회 인덱스는 k6 기준선과 `EXPLAIN ANALYZE` 측정 후 별도 성능 이슈에서 결정한다.

## 규모 변경

환경변수로 규모와 기준일을 조절한다.

```bash
MEMBER_COUNT=20000 \
ORDER_COUNT=500000 \
REVIEW_COUNT=80000 \
QNA_COUNT=50000 \
HISTORY_DAYS=1095 \
K6_USER_COUNT=500 \
SEED_VALUE=20260722 \
SEED_END_DATE=2026-06-30 \
bash scripts/seed/commerce/load_commerce.sh
```

`REVIEW_COUNT`는 배송 완료 구매 조합을 사용하므로 `ORDER_COUNT`의 절반 이하여야 한다. 판매 중 상품은 최소 50개가 필요하다.

## 테스트 계정

- 일반 계정: `loadtest-user-000001@coffeeprod.local`부터 순번 생성
- 관리자: `loadtest-admin@coffeeprod.local`
- 공통 비밀번호: `password`

공통 비밀번호는 로컬·스테이징 전용 합성 계정에만 사용한다. 운영 환경에는 이 시드를 실행하지 않는다.

## 독립 검증

적재 후 검증 SQL만 다시 실행할 수 있다.

```bash
docker compose exec -T db sh -lc \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 \
  -v member_count=10000 -v order_count=150000 \
  -v review_count=30000 -v qna_count=20000 \
  -v history_days=730 -v k6_user_count=200 \
  -v seed_end_date=2026-06-30' \
  < scripts/seed/commerce/sql/verify_commerce.sql
```

검증 항목은 다음과 같다.

- 회원 수와 회원별 기본 배송지 1개
- 주문당 주문상품 1~4개와 상품 금액 합계
- 주문 총액, 배송비, 사용 마일리지 계산
- 주문 상태와 결제 상태·결제 시각 조합
- 배송 완료 구매 이력이 있는 리뷰
- QnA 답변 상태와 관리자 답변자
- 성공 결제와 일별 매출 통계의 일치

## 주의사항

- 재실행 시 예약된 시드 계정의 장바구니와 거래 이력이 제거된다.
- 실행 중 오류가 발생하면 해당 DB 트랜잭션이 롤백된다.
- Redis에 남은 과거 테스트 RefreshToken은 이 SQL이 제거하지 않는다.
- 기준선 측정 전에는 다음 단계의 DB snapshot 절차로 동일 상태를 복원한다.
