# 로컬 백엔드 실행

## 준비

- JDK 27과 Docker Engine을 실행한다.
- `.env.example`을 참고해 Git에서 제외한 `.env`에 로컬 설정을 준비한다. 실제 비밀값은 문서·커밋·로그에 넣지 않는다.
- 기본 `dev` 프로필은 작업 디렉터리의 `.env`를 Properties 형식으로 읽는다. `KEY=value` 형식을 사용하고 값을 따옴표로 감싸지 않는다. 외부에서 주입한 환경변수는 파일보다 우선한다. 운영 프로필은 이 개발용 import를 사용하지 않는다.
- IntelliJ의 작업 디렉터리는 백엔드 루트로 지정하고 `com.back.coffeeprod.CoffeeprodApplication`을 실행한다.

## DB·Redis 포트

기본 PostgreSQL 포트는 5432, Redis 포트는 6379다. 다른 프로젝트가 사용 중이면 `.env`에서 다음 값을 함께 변경한다. DB·Redis 계정과 비밀번호는 기존 로컬 비밀 설정을 사용한다.

```properties
DB_PORT=55432
DB_URL=jdbc:postgresql://localhost:55432/coffee_db
REDIS_HOST=localhost
REDIS_PORT=56379
```

```powershell
docker compose --env-file .env -p coffeeprod-local up -d
.\gradlew.bat --no-daemon bootRun --console=plain
```

이 Compose는 `coffee-postgres`·`coffee-redis` 컨테이너와 프로젝트의 `postgres-data`·`redis-data` 디렉터리를 사용한다. 기존 디렉터리는 보존하며 빈 DB는 Flyway가 초기화한다. 기본 포트 변경은 로컬 Compose에만 적용하고 운영 Compose는 별도로 관리한다.

Compose는 `.env` 안의 `$`를 보간할 수 있다. 비밀값에 `$`가 있다면 원문을 유지해 환경변수로 주입하고, Compose 보간 경고를 무시한 채 실제 전달값이 같다고 가정하지 않는다. Java Properties import는 Compose의 따옴표·보간 규칙과 다르다.

## 확인

- `http://localhost:8080/actuator/health`: 200과 `UP`을 확인한다.
- `http://localhost:8080/api/v1/products?page=0&size=1`: 빈 DB에서도 200으로 상품 목록을 반환한다.
- `http://localhost:8080/swagger-ui/index.html`: Swagger 화면을 제공한다.
- `/api/v1/members/me`와 `/actuator/health/readiness`는 현재 Security 정책상 비로그인 요청에 401을 반환한다.

같은 8080 포트에서 CLI와 IntelliJ를 동시에 실행하지 않는다. 먼저 실행한 애플리케이션을 중지한 뒤 다른 실행 방법을 사용한다. 이 점검은 실제 OAuth·메일·AI·결제 호출이나 운영 수락을 대신하지 않는다.

[Spring 외부 설정과 확장자 힌트](https://docs.spring.io/spring-boot/reference/features/external-config.html) · [IntelliJ 실행 설정](https://www.jetbrains.com/help/idea/run-debug-configuration-spring-boot.html)
