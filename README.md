# ☕ CoffeeProd 백엔드 서버

![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0--SNAPSHOT-green.svg)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)
![Redis](https://img.shields.io/badge/Redis-7-red.svg)

커피 원두 커머스 서비스를 위한 Spring Boot 기반 REST API 서버입니다. 
회원가입/인증부터 상품 관리, 장바구니, 주문, 결제 승인, 리뷰, QnA, 통계까지 온라인 커머스의 핵심 흐름을 제공합니다.

---

## 📖 프로젝트 개요

CoffeeProd는 원두 커피 판매를 가정한 B2C 커머스 플랫폼 백엔드입니다. 
Toss Payments 결제 연동, 쿠폰/마일리지, 동시성 재고 차감, JWT 기반의 인증 시스템 등 실제 서비스 운영에 필요한 핵심 로직을 구현하는 데 중점을 두었습니다.

---

## ✨ 핵심 기능 (Features)

### 🧑‍💻 사용자 (User)
* **인증 & 회원**: JWT 기반 회원가입/로그인, 이메일 중복 확인, RefreshToken Rotation, 마이페이지, 회원 탈퇴 (Soft Delete)
* **상품 & 카테고리**: 카테고리별 상품 조회, 로스팅 정도/키워드 필터링, 상품 상세 조회
* **장바구니**: 수량 및 분쇄 옵션 변경, 조건부 상품 담기
* **주문 & 결제**: 
  * 장바구니 기반 주문서 생성 및 스냅샷 저장
  * **Toss Payments** 연동 결제 승인
  * 마일리지 선차감 및 동시성 제어가 적용된 조건부 재고 차감 (초과 판매 방지)
  * 주문 취소 시 재고 및 마일리지 자동 복구
* **리뷰 & QnA**: 구매 이력 기반 상품 리뷰 작성 (1인 1리뷰 제한), 상품 QnA 등록/수정/삭제

### 🛠️ 관리자 (Admin)
* **회원 관리**: 전체 회원 조회, 등급(`BRONZE`, `SILVER`, `GOLD`) 및 상태(`ACTIVE`, `SUSPENDED`) 변경
* **상품/카테고리 관리**: 신규 상품 등록, 재고 입고, 상품 숨김(`HIDDEN`) 처리
* **주문 관리**: 전체 주문 현황 조회, 주문 상태 전이 (`PENDING` -> `PAID` -> `SHIPPED` -> `DELIVERED`), 운송장 등록
* **매출 통계**: 매일 자정 스케줄러 기반 전일 매출 자동 집계, 일/월/년 단위 통계 조회, 수동 재집계 기능
* **고객 지원 (QnA)**: 대기 중인 문의 확인 및 관리자 답변 등록

---

## ⚙️ 기술 스택 (Tech Stack)

| 분류 | 기술 |
| --- | --- |
| **Language** | Java 21 |
| **Framework** | Spring Boot `4.1.0-SNAPSHOT` |
| **Web** | Spring WebMVC |
| **Database** | PostgreSQL 16, H2 (Test) |
| **ORM** | Spring Data JPA, Hibernate |
| **Cache & Token** | Redis 7 |
| **Security** | Spring Security, JWT, BCrypt |
| **API Docs** | Springdoc OpenAPI, Swagger UI |
| **Payment** | Toss Payments 연동 (`FakePaymentGateway` 지원) |
| **Infra** | Docker Compose |

---

## 🚀 시작하기 (Getting Started)

### 1. 필수 요구사항
* Java 21
* Docker & Docker Compose
* Gradle (Wrapper 포함)

### 2. 인프라 실행 (DB & Redis)
프로젝트 루트에서 Docker Compose를 사용하여 PostgreSQL과 Redis를 백그라운드에서 실행합니다.
```bash
docker compose up -d
```

### 3. 환경 변수 설정
`application.yml` 또는 `.env`에 다음 환경 변수 설정이 필요합니다. 
(개발 환경인 `dev` 프로필에서는 로컬 기본값이 적용되어 있어 별도 설정 없이 실행 가능합니다.)

```env
DB_URL=jdbc:postgresql://localhost:5432/coffee_db
DB_USERNAME=coffee_user
DB_PASSWORD=coffee_password
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=redis_password
JWT_SECRET_KEY=your-256-bit-secret-key
TOSS_CLIENT_KEY=your-toss-client-key
TOSS_SECRET_KEY=your-toss-secret-key
```

### 4. 애플리케이션 실행
기본 프로필은 `dev` (H2 또는 Local DB + Fake 결제 모듈) 로 설정되어 있습니다.

```bash
# Mac/Linux
./gradlew bootRun

# Windows
./gradlew.bat bootRun
```

> **프로필 안내 (`spring.profiles.active`)**
> * `dev`: 로컬 개발용 (Fake Payment)
> * `test`: 테스트용 (H2, Fake Payment)
> * `local-toss`: 실제 Toss 승인 테스트용
> * `prod`: 운영 배포용

---

## 📚 API 문서 (Swagger)

서버가 실행된 후 아래 URL에서 전체 API 명세를 확인하고 테스트할 수 있습니다.
- **Swagger UI**: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
- **OpenAPI JSON**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

### API 공통 응답 포맷
```json
{
  "status": 200,
  "message": "성공",
  "data": { ... },
  "errors": null
}
```

---

## 🔒 보안 및 인증 규칙

1. **접근 권한**
   * **공개 API**: 로그인/회원가입, 상품/카테고리 조회(GET)
   * **사용자 API**: `Authorization: Bearer {accessToken}` 필수
   * **관리자 API**: `/api/v1/admin/**` 경로는 `ROLE_ADMIN` 권한 필수
2. **토큰 관리**
   * Access Token은 클라이언트 메모리/스토리지에 보관합니다.
   * Refresh Token은 서버의 Redis에 보관되며 재발급 시 1회성(Rotation)으로 갱신됩니다.

---

## 🗄️ 도메인 및 DB 설계 핵심

* **동시성 제어**: `주문 생성 시` 상품의 재고는 DB의 `조건부 UPDATE` 쿼리(`stockQuantity >= requestQuantity`)를 사용하여 동시 주문에 의한 초과 판매를 방지합니다.
* **트랜잭션 정합성**: 주문 실패 또는 결제 위변조 발생 시 즉시 주문을 `CANCELED` 처리하고 차감된 마일리지와 재고를 복구합니다.
* **스냅샷 패턴**: 주문 발생 시점의 상품 가격, 배송지 주소는 주문 엔티티(`OrderItem`, `Orders`)에 스냅샷 형태로 복사되어, 이후 상품 정보가 변경되더라도 과거 주문 내역이 영향받지 않습니다.
* **통계 집계 로직**: 스케줄러가 매일 `00:10` 전일 결제 데이터를 1개의 `SalesStatistics` 레코드로 요약(Upsert)하여 조회 성능을 높였습니다.

---

## 🧪 테스트 (Testing)

현업 수준의 서비스 통합 테스트가 작성되어 있습니다.
```bash
./gradlew test
```
* **주요 테스트 커버리지**:
  * 결제 금액 위변조 및 보상 트랜잭션 (Refund/Cancel)
  * 다중 스레드 환경에서의 `재고 차감 동시성` 테스트
  * 관리자 상태 전이 및 배송 정책 테스트

---

## 📌 향후 로드맵 (Roadmap)

- [ ] Spring Security & Controller 통합 테스트 강화
- [ ] 주문 결제 실패 시 예약된 주문 데이터 정리 (Scheduler)
- [ ] 통계 스케줄러 분산 락 적용 방안 (다중 인스턴스 대비)
- [ ] 리뷰 / QnA 신고 시스템
- [ ] AWS 기반 CI/CD 파이프라인 구성
