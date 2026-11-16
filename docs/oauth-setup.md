# OAuth·인증 메일 준비와 개발 계약

DEV-OAUTH-001의 Google·카카오·Resend 설정 안내. 실제 비밀값은 문서·Git·프론트 번들에 넣지 않는다. 비밀번호 복구 API·화면은 [비밀번호 복구 안내](password-reset.md)를 참고한다.

## Google

1. Google Cloud의 Google Auth Platform에서 브랜드 정보·지원 연락처·대상을 설정한다. 테스트 상태라면 사용할 계정을 테스트 사용자로 등록한다.
2. 웹 애플리케이션 OAuth 클라이언트를 만들고 아래 전체 Redirect URI를 등록한다. Gemini API 키는 OAuth Client ID/Secret과 별개다.
3. Client ID/Secret을 백엔드의 OAUTH_GOOGLE_CLIENT_ID / OAUTH_GOOGLE_CLIENT_SECRET에 주입한다. 요청 범위는 openid email profile이다.

| 환경 | Redirect URI |
|---|---|
| 로컬 | http://localhost:8080/login/oauth2/code/google |
| 운영 | https://api-coffeeprod.ttagyulab.com/login/oauth2/code/google |

[Google 공식 설정](https://developers.google.com/identity/protocols/oauth2/web-server)

## 카카오

1. 카카오 Developers 앱에서 카카오 로그인을 활성화한다. 앱 정보·서비스 주소와 사용 가능 대상을 확인한다.
2. REST API 키와 해당 키의 Client Secret을 각각 OAUTH_KAKAO_CLIENT_ID / OAUTH_KAKAO_CLIENT_SECRET에 넣는다. JavaScript 키를 사용하지 않는다.
3. 닉네임 동의 항목을 활성화한다. 이메일 동의 권한이 있을 때만 OAUTH_KAKAO_EMAIL_SCOPE=true로 설정한다. 권한이 없어도 메일 인증으로 가입을 보완할 수 있다.
4. 아래 Redirect URI를 등록한다.

| 환경 | Redirect URI |
|---|---|
| 로컬 | http://localhost:8080/login/oauth2/code/kakao |
| 운영 | https://api-coffeeprod.ttagyulab.com/login/oauth2/code/kakao |

[카카오 설정](https://developers.kakao.com/docs/ko/kakaologin/prerequisite), [REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api)

## Resend와 Cloudflare DNS

1. Resend → Domains → Add Domain에 mailsender.ttagyulab.com을 등록한다. 발송만 사용하고 Receiving·열람/클릭 추적은 활성화하지 않는다.
2. Cloudflare → 기존 ttagyulab.com Zone → DNS → Records → Add record로 이동한다. 서브도메인용 Zone·웹사이트·A 레코드를 새로 만들 필요가 없다.
3. Resend 화면에 표시되는 DKIM·SPF·발송용 MX를 추가한다. Type·Content·MX Priority는 실제 계정 화면에서 복사하며 예시 서버·키를 사용하지 않는다.

| Resend가 표시한 전체 호스트 예시 | Cloudflare Name |
|---|---|
| resend._domainkey.mailsender.ttagyulab.com | resend._domainkey.mailsender |
| send.mailsender.ttagyulab.com | send.mailsender |

TTL은 Auto로 두고 프록시 선택이 있다면 DNS only로 설정한다. 동일한 이름에 SPF TXT를 중복 추가하지 말고 기존 레코드와 충돌 여부를 확인한다. 루트 도메인과 기존 수신 메일의 MX·웹 레코드는 보존한다. 발송용 MX는 반송 처리용이며 별도 수신함을 만드는 설정이 아니다.

4. Resend에서 Verify DNS Records를 실행하고 Verified를 확인한다. DNS 반영에는 시간이 걸릴 수 있으므로 Pending을 성공으로 기록하지 않는다.
5. 해당 도메인의 발송 전용 API 키를 생성해 RESEND_API_KEY에 주입한다. MAIL_FROM은 CoffeeProd <no-reply@mailsender.ttagyulab.com>으로 둔다. 발신 주소용 수신함은 필요하지 않다.
6. AUTH_VERIFICATION_SECRET에 암호학적 난수 32자 이상을 별도로 넣는다. JWT 키와 공유하지 않는다. 변경하면 진행 중인 메일 인증·권한은 사용할 수 없어 다시 시작해야 한다.
7. 실제 수신 가능한 계정으로 메일 도착·스팸함·발신자 표시와 코드 만료를 확인한다. 코드나 비밀키를 증거 파일에 남기지 않는다.

[Cloudflare 공식 절차](https://resend.com/docs/guides/dns/cloudflare), [Resend 도메인](https://resend.com/docs/dashboard/domains/introduction)

## 실행·배포 설정

- 로컬 APP_BACKEND_URL=http://localhost:8080, APP_FRONTEND_URL=http://localhost:3000. 프론트 NEXT_PUBLIC_API_BASE_URL도 localhost:8080으로 맞춘다. localhost와 127.0.0.1을 섞으면 거래 쿠키를 공유하지 못한다.
- 운영 APP_BACKEND_URL=https://api-coffeeprod.ttagyulab.com, APP_FRONTEND_URL=https://coffeeprod.ttagyulab.com. Origin에는 끝 슬래시·경로를 넣지 않는다.
- OAuth 쿠키는 HttpOnly·SameSite=Lax이며 HTTPS에서는 Secure다. 서버는 쿠키와 정확한 Origin, X-OAuth-Request: 1 헤더를 함께 검사한다. 기존 Bearer API의 credential 정책은 유지한다.
- OAuth는 기존 Redis를 사용한다. Redis 장애·거래 만료 시 인증을 중단한다. 제공사 자격 증명이 없으면 해당 버튼을 표시하지 않으며 기존 이메일 로그인은 사용할 수 있다.
- MAIL_PROVIDER는 resend를 사용한다. 다른 값이나 누락된 Resend 키는 발송 불가로 처리한다. 대체 제공사는 MailSender 구현과 계약 검증 후 추가한다.
- 프록시 배포에서 IP별 제한은 서버가 직접 확인한 원격 주소를 기준으로 한다. 임의 X-Forwarded-For를 신뢰하지 않는다. 프록시 뒤에서는 여러 사용자가 동일 IP 한도를 공유할 수 있으므로 운영 QA가 실제 토폴로지를 확인한다.
- 운영 배포 전 V20 마이그레이션과 백엔드·프론트를 함께 검증한다. 기존 password 값은 보존하고 nullable로만 확장한다. 소셜 전용 회원에 임의 로컬 비밀번호를 추가하지 않는다.
- 전체 테스트의 OAuth 통합 검증은 Docker Engine과 PostgreSQL 16·Redis 7 이미지가 필요하다. Testcontainers가 임시 컨테이너를 생성·제거한다. 기존 DB/Redis에 연결하지 않는다.
- CI는 Windows JVM 회귀(test -PwindowsJvmTests)와 Linux OAuth 통합(test --tests '*OAuthIntegrationTest' --tests '*OAuthMigrationTest')을 모두 필수로 실행한다. 이미지 게시에는 두 작업의 성공이 필요하다. 로컬 기본 test는 두 범위를 함께 실행한다.

## API와 후속 인계

모든 OAuth API는 /api/v1/auth/oauth 아래에 있으며 CommonResponse를 사용한다. POST는 credentials: include, Origin, X-OAuth-Request: 1이 필요하다. /providers GET은 enabled·connected 제공사 목록을 반환한다.

- POST /{provider}/start: purpose=LOGIN/LINK/WITHDRAW. LINK는 현재 JWT와 currentPassword 또는 confirmation을 요구한다. 소셜 전용 회원은 연결된 제공사로 LINK·reauthenticate=true를 먼저 실행해 확인 권한을 받는다.
- 인가 URL은 /oauth2/authorization/{provider}, 제공사 콜백은 /login/oauth2/code/{provider}, 프론트 완료는 /auth/callback이다. 서비스/제공사 토큰을 URL로 전달하지 않는다.
- POST /exchange: LOGIN(tokens), SIGNUP(email/emailVerified/provider), LINK_PROOF(confirmation), LINK_CONFIRM 또는 WITHDRAW_CONFIRM을 반환한다. JWT 교환과 확인 권한 발급은 일회성이다.
- POST /signup: email/name/nickname/grant. 검증된 제공사 이메일을 그대로 쓰는 경우에만 grant를 생략한다.
- POST /email/send: email. POST /email/verify: email/code, 응답 grant는 5분·일회성이다.
- POST /link 및 /withdraw: confirmed=true와 현재 회원 JWT가 필요하다. 권한은 회원·목적과 5분 만료에 귀속한다.
- 메일 공통 인터페이스는 EmailVerificationService.send/verify/consume과 MailSender다. PASSWORD_RESET과 OAUTH_SIGNUP_EMAIL은 코드·권한을 공유하지 않는다. 복구 API는 계정 자격 검증 후 이 인터페이스를 사용한다. [복구 API 계약](password-reset.md)을 참고한다.
- 발송 결과가 불명확하면 예약량과 기존 코드를 보존하며 새 코드는 활성화하지 않는다. 자동 재발송하지 않는다. Resend 요청은 발송별 고정 idempotency key를 사용한다.

실제 Google·카카오 로그인, Resend 수신, 독립 QA와 운영 확인은 모의 테스트 통과로 대체하지 않는다. 결과는 PR과 TASKS 검증 기록을 따른다.
