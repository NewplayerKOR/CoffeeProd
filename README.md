# CoffeeProd

커피 원두 커머스 서비스를 위한 Spring Boot 기반 REST API 서버입니다. 회원 인증, 상품/카테고리 관리, 장바구니, 주문, 결제 승인, 배송지 관리, 관리자 운영 기능을 제공합니다.

## 프로젝트 개요

CoffeeProd는 온라인 커피 원두 판매 서비스를 가정한 백엔드 프로젝트입니다. 일반 사용자는 회원가입과 로그인 후 상품을 조회하고 장바구니에 담아 주문을 생성할 수 있으며, Toss Payments 결제 승인 흐름을 통해 주문 결제를 처리합니다. 관리자는 상품, 카테고리, 회원, 주문 상태를 운영할 수 있습니다.

## 주요 기능

- 회원: 회원가입, 로그인, 토큰 재발급, 로그아웃, 내 정보 조회/수정, 비밀번호 변경, 회원 탈퇴
- 인증/인가: JWT 기반 인증, Refresh Token Redis 저장, 관리자 권한 분리
- 상품: 상품 목록 조회, 상품 상세 조회, 카테고리/로스팅/키워드 필터링, 페이지네이션
- 카테고리: 카테고리 목록 조회, 관리자 카테고리 등록/수정
- 장바구니: 장바구니 조회, 상품 추가, 수량/분쇄 옵션 변경, 상품 삭제, 전체 비우기
- 배송지: 배송지 목록 조회, 등록, 수정, 삭제, 기본 배송지 설정
- 주문: 장바구니 기반 주문 생성, 주문 목록/상세 조회, 주문 취소
- 결제: Toss Payments 결제 승인 및 결제 결과 저장
- 관리자: 상품 등록/수정/상태 변경/재고 추가, 회원 등급/상태 변경, 전체 주문 조회, 주문 상태 변경
- 문서화: Swagger UI 및 OpenAPI 문서 제공

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 4.1.0-SNAPSHOT |
| Build | Gradle Kotlin DSL |
| Web | Spring WebMVC |
| ORM | Spring Data JPA, Hibernate |
| Database | PostgreSQL 16, H2(Test) |
| Cache/Token Store | Redis |
| Security | Spring Security, JWT, BCrypt |
| API Docs | Springdoc OpenAPI, Swagger UI |
| Validation | Jakarta Validation |
| Payment | Toss Payments API 연동 구조 |
| Test | JUnit Platform, Spring Boot Test |
| Infra | Docker Compose(PostgreSQL, Redis) |

## 도메인 구조

```text
src/main/java/com/back/coffeeprod
├── domain
│   ├── address   # 배송지
│   ├── cart      # 장바구니
│   ├── member    # 회원/인증
│   ├── order     # 주문
│   ├── payment   # 결제
│   └── product   # 상품/카테고리
└── global
    ├── common    # 공통 응답
    ├── config    # Security, Redis, Swagger 설정
    ├── exception # 전역 예외 처리
    └── security  # JWT, UserDetails, 인증 필터
```

## 실행 환경

### 필수 요구사항

- Java 21
- Docker, Docker Compose
- Gradle Wrapper 사용 가능 환경

### 환경 변수

`application.yml`과 `docker-compose.yml`은 아래 환경 변수를 사용합니다.

| 변수명 | 설명 |
| --- | --- |
| `DB_URL` | PostgreSQL JDBC URL |
| `DB_USERNAME` | DB 사용자명 |
| `DB_PASSWORD` | DB 비밀번호 |
| `REDIS_HOST` | Redis 호스트 |
| `REDIS_PORT` | Redis 포트 |
| `REDIS_PASSWORD` | Redis 비밀번호 |
| `JWT_SECRET_KEY` | JWT 서명 키 |
| `JWT_ACCESS_EXPIRATION` | Access Token 만료 시간 |
| `JWT_REFRESH_EXPIRATION` | Refresh Token 만료 시간 |
| `TOSS_CLIENT_KEY` | Toss Payments 클라이언트 키 |
| `TOSS_SECRET_KEY` | Toss Payments 시크릿 키 |

예시:

```env
DB_URL=jdbc:postgresql://localhost:5432/coffee_db
DB_USERNAME=coffee_user
DB_PASSWORD=coffee_password
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=redis_password
JWT_SECRET_KEY=replace-with-long-secret-key
JWT_ACCESS_EXPIRATION=3600000
JWT_REFRESH_EXPIRATION=1209600000
TOSS_CLIENT_KEY=replace-with-toss-client-key
TOSS_SECRET_KEY=replace-with-toss-secret-key
```

### 인프라 실행

```bash
docker compose up -d
```

`docker-compose.yml`은 다음 컨테이너를 실행합니다.

- PostgreSQL 16: `localhost:5432`, DB명 `coffee_db`
- Redis 7: `localhost:6379`, password 인증 사용

### 애플리케이션 실행

```bash
./gradlew bootRun
```

Windows 환경:

```bash
./gradlew.bat bootRun
```

기본 활성 프로필은 `dev`입니다.

## 프로필별 설정

| Profile | DB | JPA DDL | 용도 |
| --- | --- | --- | --- |
| `dev` | PostgreSQL | `update` | 로컬 개발 |
| `test` | H2 in-memory(PostgreSQL mode) | `create-drop` | 테스트 |
| `prod` | PostgreSQL | `validate` | 운영 배포 |

## API 문서

서버 실행 후 Swagger UI에서 API를 확인할 수 있습니다.

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI Docs: `http://localhost:8080/v3/api-docs`

공통 응답 포맷:

```json
{
  "status": 200,
  "message": "성공",
  "data": {},
  "errors": null
}
```

인증이 필요한 API는 `Authorization: Bearer {accessToken}` 헤더를 사용합니다.

## API 명세 요약

### 인증

| Method | Endpoint | 인증 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/signup` | 불필요 | 회원가입 |
| `POST` | `/api/v1/auth/login` | 불필요 | 로그인 및 Access/Refresh Token 발급 |
| `POST` | `/api/v1/auth/reissue` | 불필요 | Refresh Token으로 토큰 재발급 |
| `POST` | `/api/v1/auth/logout` | 필요 | 로그아웃 및 Refresh Token 삭제 |
| `GET` | `/api/v1/auth/check-email?email={email}` | 불필요 | 이메일 중복 확인 |

주요 요청 필드:

- 회원가입: `email`, `password`, `name`, `nickname`
- 로그인: `email`, `password`
- 토큰 재발급: `refreshToken`

### 회원

| Method | Endpoint | 인증 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/members/me` | 필요 | 내 정보 조회 |
| `PATCH` | `/api/v1/members/me` | 필요 | 닉네임 수정 |
| `PATCH` | `/api/v1/members/me/password` | 필요 | 비밀번호 변경 |
| `DELETE` | `/api/v1/members/me` | 필요 | 회원 탈퇴(Soft Delete) |

주요 요청 필드:

- 내 정보 수정: `nickname`
- 비밀번호 변경: `currentPassword`, `newPassword`
- 회원 탈퇴: `currentPassword`

### 상품/카테고리

| Method | Endpoint | 인증 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/products` | 불필요 | 상품 목록 조회 |
| `GET` | `/api/v1/products/{productId}` | 불필요 | 상품 상세 조회 |
| `GET` | `/api/v1/categories` | 불필요 | 카테고리 목록 조회 |

상품 목록 쿼리 파라미터:

- `categoryId`: 카테고리 ID
- `roastLevel`: `LIGHT`, `MEDIUM`, `DARK`
- `keyword`: 상품명 검색어
- `page`, `size`, `sort`: Spring Pageable 파라미터

### 장바구니

| Method | Endpoint | 인증 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/carts` | 필요 | 장바구니 조회 |
| `POST` | `/api/v1/carts/items` | 필요 | 장바구니 상품 추가 |
| `PATCH` | `/api/v1/carts/items/{cartItemId}` | 필요 | 장바구니 상품 수량/옵션 변경 |
| `DELETE` | `/api/v1/carts/items/{cartItemId}` | 필요 | 장바구니 상품 삭제 |
| `DELETE` | `/api/v1/carts` | 필요 | 장바구니 전체 비우기 |

주요 요청 필드:

- 상품 추가: `productId`, `quantity`, `grindType`
- 상품 변경: `quantity`, `grindType`
- `grindType`: `WHOLE_BEAN`, `ESPRESSO`, `DRIP`, `FRENCH_PRESS`

### 배송지

| Method | Endpoint | 인증 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/members/me/addresses` | 필요 | 배송지 목록 조회 |
| `POST` | `/api/v1/members/me/addresses` | 필요 | 배송지 등록 |
| `PUT` | `/api/v1/members/me/addresses/{addressId}` | 필요 | 배송지 수정 |
| `DELETE` | `/api/v1/members/me/addresses/{addressId}` | 필요 | 배송지 삭제 |
| `PATCH` | `/api/v1/members/me/addresses/{addressId}/default` | 필요 | 기본 배송지 설정 |

주요 요청 필드:

- `recipient`, `phone`, `zipcode`, `addressLine1`, `addressLine2`

### 주문

| Method | Endpoint | 인증 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/orders` | 필요 | 장바구니 기반 주문 생성 |
| `GET` | `/api/v1/orders` | 필요 | 내 주문 목록 조회 |
| `GET` | `/api/v1/orders/{orderId}` | 필요 | 주문 상세 조회 |
| `POST` | `/api/v1/orders/{orderId}/cancel` | 필요 | 주문 취소 |

주요 요청 필드:

- 주문 생성: `addressId`, `usedMileage`
- 주문 상태: `PENDING`, `PAID`, `SHIPPED`, `DELIVERED`, `CANCELED`

### 결제

| Method | Endpoint | 인증 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/payments/confirm` | 필요 | Toss Payments 결제 승인 및 검증 |

주요 요청 필드:

- `paymentKey`, `orderId`, `amount`

결제 흐름:

1. `POST /api/v1/orders`로 주문을 생성합니다.
2. 클라이언트에서 Toss Payments SDK 결제 UI를 실행합니다.
3. Toss Payments에서 받은 `paymentKey`와 주문 금액을 서버로 전달합니다.
4. `POST /api/v1/payments/confirm`에서 주문 상태, 결제 금액, PG 승인 결과를 검증합니다.

### 관리자 API

관리자 API는 JWT 인증과 `ADMIN` 권한이 필요합니다.

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/admin/members` | 전체 회원 목록 조회 |
| `PATCH` | `/api/v1/admin/members/{memberId}/grade` | 회원 등급 변경 |
| `PATCH` | `/api/v1/admin/members/{memberId}/status` | 회원 상태 변경 |
| `POST` | `/api/v1/admin/categories` | 카테고리 등록 |
| `PUT` | `/api/v1/admin/categories/{categoryId}` | 카테고리 수정 |
| `POST` | `/api/v1/admin/products` | 상품 등록 |
| `PUT` | `/api/v1/admin/products/{productId}` | 상품 전체 수정 |
| `PATCH` | `/api/v1/admin/products/{productId}/status` | 상품 상태 변경 |
| `PATCH` | `/api/v1/admin/products/{productId}/stock` | 상품 재고 추가 |
| `GET` | `/api/v1/admin/orders` | 전체 주문 목록 조회 |
| `PATCH` | `/api/v1/admin/orders/{orderId}/status` | 주문 상태 변경 및 운송장 등록 |

관리자 요청 값:

- 회원 등급: `BRONZE`, `SILVER`, `GOLD`
- 회원 상태: `ACTIVE`, `SUSPENDED`
- 상품 상태: `ON_SALE`, `SOLD_OUT`, `HIDDEN`
- 상품 로스팅: `LIGHT`, `MEDIUM`, `DARK`
- 주문 상태 변경: `status`, `trackingNo`

## 보안 정책

- JWT 기반 Stateless 인증을 사용합니다.
- 비밀번호는 BCrypt로 암호화합니다.
- Refresh Token은 Redis에 저장하고, 재발급 시 Rotation 구조를 사용합니다.
- `/api/v1/admin/**` 경로는 `ADMIN` 권한이 필요합니다.
- 공개 접근 허용 경로:
  - `/api/v1/auth/signup`
  - `/api/v1/auth/login`
  - `/api/v1/auth/reissue`
  - `/api/v1/auth/check-email`
  - `/api/v1/products/**`
  - `/api/v1/categories/**`
  - `/swagger-ui/**`
  - `/v3/api-docs/**`

## 테스트

테스트 프로필은 H2 in-memory DB를 PostgreSQL 모드로 사용합니다.

```bash
./gradlew test
```

현재 테스트 패키지에는 회원, 상품, 재고 동시성, 주문, 결제 서비스 통합 테스트가 포함되어 있습니다.

## 배포 상황

현재 저장소 기준으로 확인되는 배포 구성은 다음과 같습니다.

- 로컬 개발 인프라: Docker Compose로 PostgreSQL, Redis 실행 가능
- 운영 프로필: `prod` 프로필 존재, JPA `ddl-auto=validate`
- 애플리케이션 배포 스크립트/CI/CD 파이프라인: 저장소 내 미확인
- 운영 서버/클라우드 배포 설정: 저장소 내 미확인

## 추후 개선 및 미구현 항목

저장소 기준으로 추후 보완이 필요한 항목입니다.

- CI/CD 파이프라인 구성
- 운영 배포 문서 및 서버 환경 변수 관리 가이드
- `.env.example` 또는 환경 변수 샘플 파일 제공
- API 요청/응답 예시 보강
- Toss Payments 실운영/테스트 키 분리 가이드
- 관리자 계정 생성/권한 부여 절차 문서화
- 프론트엔드 연동 가이드
- 운영 모니터링, 로그, 장애 대응 문서
- DB 마이그레이션 도구 도입 검토
- 깨진 한글 주석/Swagger 설명 인코딩 정리

