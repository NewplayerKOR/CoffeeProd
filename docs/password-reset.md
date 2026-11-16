# 이메일 인증번호로 비밀번호 복구

DEV-PASSWORD-RESET-001. 로그인 화면의 “비밀번호를 잊으셨나요?”에서 `/password-reset`으로 이동한다. 이메일 → 인증번호 → 새 비밀번호·확인 → 완료·재로그인 순서다. 로그인 상태의 비밀번호 변경 API는 유지한다.

## API 계약

인증 없이 JSON POST로 호출하며 기존 CommonResponse를 사용한다. 응답은 `Cache-Control: no-store`다. 쿠키·Bearer 토큰은 사용하지 않는다. 코드·거래·권한·비밀번호를 URL, 로그, 분석 이벤트, 영속 브라우저 저장소에 남기지 않는다.

| 경로 | 요청 | 성공 data |
| --- | --- | --- |
| `/api/v1/auth/password-reset/send` | `email`, 재발송에만 `transaction` | `transaction`, 공통 안내 `message` |
| `/api/v1/auth/password-reset/verify` | `email`, `transaction`, 6자리 `code` | 일회성 `grant` |
| `/api/v1/auth/password-reset/reset` | `email`, `transaction`, `grant`, `newPassword`, `confirmPassword` | `reset: true` |

- 첫 발송 요청은 가입 여부·상태·로컬 비밀번호 유무와 무관하게 같은 HTTP 200·안내를 반환한다. `transaction`은 이메일과 목적에 귀속된 추측 불가능한 256비트 값이며 10분 후 만료한다.
- 2개 작업자·최대 80개 대기 작업으로 제한한 메모리 큐가 계정 조회와 발송을 수행한다. HTTP 응답은 발송 성공 확인이 아니다. 발송·제한·제공사 실패도 계정 정보를 노출하지 않도록 초기 응답에서 구분하지 않는다. 큐 포화 또는 요청 단계 Redis 장애는 서비스 오류이며 인증을 허용하지 않는다. 재시작하면 대기 작업은 소실될 수 있으므로 인증번호를 받지 못한 사용자는 다시 요청한다.
- ACTIVE이고 기존 로컬 비밀번호를 가진 회원의 가입 이메일에만 발송한다. 미가입·정지·탈퇴·소셜 전용 계정에는 발송하지 않는다. 초기 화면은 모든 사용자에게 소셜 로그인을 이용할 수 있다는 공통 안내를 보여준다. 이메일은 저장된 가입 이메일 표기와 일치해야 한다.
- 코드 6자리·5분, 재발송 60초, 코드별 실패 5회, 이메일별 실패 15분당 10회다. 이메일 시간당 3회·하루 5회, IP 시간당 10회·하루 20회, 전체 하루 80회·월 2,400회를 공통 OAuth 가입 인증과 공유한다. 발송 대상이 아닌 요청도 동일하게 예약해 가입 여부를 추측하거나 한도를 우회하지 못하게 한다.
- 공통 EmailVerificationService·MailSender/Resend를 사용한다. 코드와 권한은 `PASSWORD_RESET`·이메일·복구 거래에 귀속한다. OAuth 가입 코드·권한과 혼용할 수 없다. Redis에 HMAC만 저장하고 발송 성공이 확인된 경우에만 기존 코드를 교체한다. 실패·결과 불명은 기존 코드를 보존하고 예약량을 유지한다. 자동 재시도는 하지 않는다.
- 인증 성공 시 코드를 소비하고 5분짜리 권한을 발급한다. 인증된 거래에는 재발송할 수 없다. 새 비밀번호는 기존 8~100자 조건과 BCrypt의 UTF-8 72바이트 제한을 적용하고 확인 값과 일치해야 한다.
- 최종 변경은 회원 행을 잠그고 상태·이메일·로컬 비밀번호·거래 당시 토큰 버전을 다시 확인한다. 일회성 권한과 거래를 원자 소비하고 RefreshToken을 삭제한다. Redis 장애 시 비밀번호 변경도 실패한다. 실패로 소비된 권한은 다시 발급하지 않으므로 처음부터 재시도한다.
- Flyway V21이 `member.token_version`을 기본값 0으로 추가한다. 복구 성공 시 비밀번호와 버전을 같은 DB 트랜잭션에서 갱신한다. API 인증 필터와 토큰 재발급이 JWT의 `ver`를 DB와 비교한다. 기존 `ver` 없는 토큰은 버전 0일 때만 허용한다. 로그인·OAuth·재발급은 현재 버전으로 새 토큰을 발급한다. 이미 처리 중인 요청까지 소급 취소하지는 않는다.
- 성공 후 기존 AccessToken·RefreshToken과 다른 미완료 복구 권한은 사용할 수 없다. 자동 로그인하지 않으며 새 비밀번호로 로그인해야 한다.

## 발송 설정과 운영 인계

[OAuth·Resend 준비 안내](oauth-setup.md)의 `AUTH_VERIFICATION_SECRET`, `MAIL_PROVIDER`, `RESEND_API_KEY`, `MAIL_FROM`을 그대로 사용한다. `.env.example`과 운영 Compose가 이미 전달하는 공통 설정이며 새 비밀값은 필요하지 않다. 서버에서만 주입한다. Resend DNS Verified·발송 전용 키·발신자와 실제 수신 가능한 테스트 계정을 준비해야 한다.

IP 제한은 신뢰할 수 없는 X-Forwarded-For를 직접 사용하지 않고 요청의 remoteAddr를 사용한다. 실제 프록시 환경의 IP 전달은 QA에서 별도 확인한다. 브라우저 화면 검사는 모의 API, 백엔드 통합 검사는 모의 메일과 실제 격리 PostgreSQL 16·Redis 7로 수행한다. 실제 Resend 발송·수신, 독립 QA, hosted Actions, 운영 배포는 별도 확인 대상이다.

## 자체 검증

- Windows/JDK 27.0.1.0.3: `gradlew.bat --no-daemon test perfTest bootJar perfBootJar --warning-mode all --console=plain` — test 703건, perfTest 16건 통과, 실패·오류·건너뜀 0. 성능 서버 부하 측정 결과를 의미하지 않는다.
- 복구 통합 테스트 21건은 위 703건에 포함한다. 실제 HTTP로 기존/새 비밀번호 로그인, JWT 폐기·재발급, 대상 계정, 메일 실패·불명, 코드·권한 만료·목적·이메일 귀속, 중복/동시 요청, 재발송·시도 제한, legacy JWT 호환, 실제 TTL, 토큰 폐기 중 Redis 장애 시 DB 롤백을 확인한다. OAuth 통합 회귀와 V19부터의 데이터 보존 마이그레이션도 포함한다.
- 프론트 `npm run lint`, `npm run build` 통과. 브라우저 검증과 Linux 실행 결과·리비전은 TASKS 완료 기록에 남긴다.

- Linux/JDK 27.0.1.0.3 Docker에서 CI와 동일한 세 클래스(`OAuthIntegrationTest`, `OAuthMigrationTest`, `PasswordResetIntegrationTest`) 52건 통과, 실패·오류·건너뜀 0. 위 전체 테스트의 부분집합이다.
- 실제 Next 프로덕션 서버 `http://127.0.0.1:3108`, 모의 API, Chromium 데스크톱 1365×900·모바일 390×844에서 브라우저 흐름 4건 통과. 진입·복구·비밀번호 불일치·중복 제출·인증 만료·재시작·토큰 삭제·가로 넘침·pageerror 0 확인. Browser 플러그인이 없어 기존 Playwright를 사용했으며 프로젝트 의존성을 추가하지 않았다.
