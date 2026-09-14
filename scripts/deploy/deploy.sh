#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_ROOT/docker-compose.prod.yml}"
DEPLOY_WAIT_SECONDS="${DEPLOY_WAIT_SECONDS:-180}"

if [[ $# -gt 1 ]]; then
  echo "사용법: $0 [image-tag]" >&2
  exit 2
fi

if [[ $# -eq 1 ]]; then
  export APP_IMAGE_TAG="$1"
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "운영 환경변수 파일이 없습니다: $ENV_FILE" >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "운영 Compose 파일이 없습니다: $COMPOSE_FILE" >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker 명령을 찾을 수 없습니다." >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose 플러그인을 사용할 수 없습니다." >&2
  exit 1
fi

cd "$PROJECT_ROOT"

compose=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")

show_failure() {
  local exit_code=$?
  echo "배포에 실패했습니다. 현재 상태와 애플리케이션 로그를 출력합니다." >&2
  "${compose[@]}" ps >&2 || true
  "${compose[@]}" logs --tail=100 app >&2 || true
  exit "$exit_code"
}
trap show_failure ERR

# 필수 환경변수와 Compose 구성을 배포 전에 검증함
"${compose[@]}" config --quiet

# 레지스트리 이미지를 갱신하고 정상 상태까지 대기함
"${compose[@]}" pull
"${compose[@]}" up -d --remove-orphans --wait --wait-timeout "$DEPLOY_WAIT_SECONDS"

trap - ERR

"${compose[@]}" ps
echo "CoffeeProd 배포가 완료되었습니다."
