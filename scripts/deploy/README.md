# CoffeeProd 운영 배포

이 디렉터리는 GHCR에 게시된 Backend 이미지를 서버에서 한 명령으로 갱신하기 위한 절차를 관리한다.

## 1. 배포 구조

```text
GitHub develop/main push
-> GitHub Actions Docker 이미지 빌드
-> GHCR 이미지 게시
-> 서버 deploy.sh 실행
-> app/db/redis 기동 및 헬스체크
-> Cloudflare Tunnel이 127.0.0.1:8080으로 연결
```

`develop` push는 `develop`과 `sha-*` 태그를 생성한다. `main` push는 `main`, `sha-*`, `latest` 태그를 생성한다. 현재 저장소의 기본 브랜치가 `develop`이고 `main`이 배포 브랜치로 운영되지 않으므로 운영 Compose의 초기 기본 태그는 `develop`이다. 추후 `main` 승격 절차를 도입하면 서버 `.env`의 `APP_IMAGE_TAG`를 `latest`로 변경한다.

## 2. 서버 사전 조건

- Docker Engine과 Docker Compose 플러그인이 설치되어 있어야 한다.
- 서버 작업 디렉터리에 `docker-compose.prod.yml`, `.env`, `scripts`가 있어야 한다.
- `postgres-data`, `redis-data`는 서버에만 보관하고 Git에 추가하지 않는다.
- Cloudflare Tunnel Origin은 `http://127.0.0.1:8080`을 유지한다.
- 비공개 GHCR 이미지라면 서버에서 읽기 전용 패키지 토큰으로 한 번 로그인한다.

```bash
echo "$GHCR_READ_TOKEN" | docker login ghcr.io -u NewplayerKOR --password-stdin
unset GHCR_READ_TOKEN
```

서버 토큰에는 `read:packages`만 부여한다. Issue 생성용 토큰이나 GitHub Actions의 `GITHUB_TOKEN`을 서버에 저장하지 않는다.

## 3. 운영 환경변수

`.env.example`을 기준으로 서버 전용 `.env`를 작성한다. 최소한 다음 값이 필요하다.

```env
APP_IMAGE=ghcr.io/newplayerkor/coffeeprod
APP_IMAGE_TAG=develop
APP_PORT=8080
POSTGRES_DB=coffee_db

DB_USERNAME=...
DB_PASSWORD=...
REDIS_PASSWORD=...
JWT_SECRET_KEY=...
TOSS_WIDGET_CLIENT_KEY=...
TOSS_WIDGET_SECRET_KEY=...
CORS_ALLOWED_ORIGINS=https://coffeeprod.ttagyulab.com
```

`.env`는 Git에 추가하거나 이미지에 포함하지 않는다.

## 4. 최초 전환

기존 JAR 또는 systemd 애플리케이션이 8080 포트를 사용 중이면 먼저 중지한다. 기존 DB·Redis Compose를 내릴 때 볼륨 삭제 옵션을 사용하지 않는다.

```bash
sudo systemctl stop coffeeprod 2>/dev/null || true
docker compose down
bash scripts/deploy/deploy.sh
```

`docker compose down -v`와 `postgres-data`, `redis-data` 삭제는 금지한다. 기존 데이터 디렉터리는 운영 Compose가 그대로 다시 사용한다.

기존 PostgreSQL 스키마에는 정상적인 `flyway_schema_history`가 있어야 한다. 신규 DB는 빈 `postgres-data`에서 Flyway V1부터 자동 적용한다. 기존 비어 있지 않은 스키마에 `baseline-on-migrate`를 임의로 활성화하지 않는다.

## 5. 일반 배포

GitHub Actions의 이미지 게시가 성공한 후 서버에서 실행한다.

```bash
bash scripts/deploy/deploy.sh
```

스크립트는 다음을 수행한다.

1. `.env`와 Compose 설정 검증
2. GHCR 및 PostgreSQL·Redis 이미지 pull
3. 변경된 컨테이너 재생성
4. DB, Redis, Backend 헬스체크 대기
5. 최종 컨테이너 상태 출력

DB와 Redis의 호스트 포트는 공개하지 않는다. Backend만 `127.0.0.1:8080`에 바인딩하므로 외부 접근은 Cloudflare Tunnel을 통한다.

## 6. 카탈로그 적재

카탈로그는 일반 배포마다 자동 적재하지 않는다. 최초 구축 또는 승인된 카탈로그 갱신 시에만 실행한다.

```bash
COMPOSE_FILE=docker-compose.prod.yml bash scripts/seed/catalog/load_catalog.sh
```

해당 적재는 상품 가격·재고·상태를 CSV 기준으로 upsert하므로 성능 테스트 기준 DB를 보존해야 할 때는 먼저 백업한다.

## 7. 상태와 로그

```bash
docker compose --env-file .env -f docker-compose.prod.yml ps
docker compose --env-file .env -f docker-compose.prod.yml logs -f --tail=100 app
curl -fsS http://127.0.0.1:8080/api/v1/categories
```

## 8. 버전 고정과 롤백

GitHub Actions가 생성한 `sha-*` 태그를 지정하면 특정 버전을 배포할 수 있다.

```bash
bash scripts/deploy/deploy.sh sha-1a2b3c4
```

문제가 발생하면 직전에 정상 동작한 태그를 같은 방식으로 지정한다. DB migration이 이전 애플리케이션과 호환되지 않는 경우에는 이미지 롤백만 수행하지 말고 사전에 작성된 DB 복구 절차를 함께 적용한다.

## 9. 자동 배포 범위

현재 GitHub Actions는 이미지를 자동으로 빌드해 GHCR에 게시한다. 서버 반영은 `deploy.sh`를 직접 실행하는 한 단계로 유지한다.

서버까지 완전 자동화하려면 별도 작업에서 다음 중 하나를 결정해야 한다.

- 서버 self-hosted runner
- 제한된 배포 계정으로 SSH 실행
- 승인형 배포 도구 또는 webhook

서버 실행 권한과 운영 승인 정책이 확정되기 전에는 GitHub-hosted runner에 서버의 광범위한 SSH 권한을 제공하지 않는다.
