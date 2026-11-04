# DEV8 홈 서버 기준선 실행 안내

> **기존 결과만 보거나 포트폴리오 그림을 재생성하려면 [공유 데이터·그래프 안내](evidence/README.md)를 사용한다.** 해당 경로는 서버·DB·부하 테스트 없이 동작한다. 아래 내용은 과거 측정 실행 절차이며 결과 재사용을 위해 실행할 필요가 없다.

이 문서는 홈 서버에서 전용 Docker 환경을 구동하고 로컬 PC에서 부하를 보내는 절차다. 운영 `coffee-postgres`, `coffee-redis`, `coffeeprod-app` 및 기존 데이터 디렉터리를 재사용하지 않는다. DEV8은 수정 전 기준선 준비이며 구조 개선은 DEV9에서 수행한다.

현재 실행기는 **절차 v2**다. 이번 수정 범위는 코드와 자체 검증이며 아래 홈 서버 명령을 자동 실행하지 않는다. 기존 `dev8-20261102-01/before`의94개 PASS 원본은 보존한다. 새 v2 측정에는 새 소스 bundle·RunId를 사용하며, 기존 결과를 덮거나 v2 결과로 변경하지 않는다. DB migration은 기존 V1~V18을 유지한다.

## 1. 준비와 소스 전달

- 로컬: PowerShell7, Java21(자체 회귀), Node.js, k6, SSH/SCP, tar.
- 홈 서버: Linux, Docker Engine/Compose, Bash, coreutils, SSH. Java/Gradle/Node.js의 서버 설치는 필요 없다. DB 작업은 소유 UUID/PID가 있는 원자적 디렉터리 잠금으로 직렬화한다.
- 기본 자원 제한: app/checks CPU2·2GiB, DB CPU2·2GiB, Redis/mock PG 각 CPU0.5·256MiB. checks 실행 중 app은 중지한다. 서버 여유 자원이 부족하면 최초 prepare 전에 전용 `.env.perf`의 app/DB 한도를 조정한다. prepare 이후 전후 비교 중 한도·호스트·기반 이미지를 바꾸면 실행기가 거부한다.
- 접속 대상은 사용자 제공 `parktaegyu@192.168.0.8`이다. 자동 실행기는 `ssh -o BatchMode=yes`가 성공하는 기존 키/agent 인증이 필요하다. 비밀번호·개인키를 스크립트나 명령행에 저장하지 않는다. 자동 인증이 안 되면 평소 Git Bash로 로그인해 아래 서버 명령을 실행할 수 있지만, 원격 전체 기준선은 미실행으로 남는다.

로컬 프로젝트 루트에서 **새 이름**의 소스 archive를 생성한다. 운영 `.env`, 기존 k6 계정, DB 디렉터리와 build 결과는 archive에 넣지 않는다. 미커밋 소스는 기본 revision과 파일 SHA256으로 식별하므로 commit/push가 필요하지 않다.

```powershell
node scripts/performance/manifest.mjs bundle build/perf-phase2/dev8-source-v2-20261102.tar.gz
scp build/perf-phase2/dev8-source-v2-20261102.tar.gz parktaegyu@192.168.0.8:/tmp/coffeeprod-dev8-v2.tar.gz
ssh parktaegyu@192.168.0.8
```

홈 서버 셸에서 기존 프로젝트와 다른 디렉터리에 푼다. 이후 로컬 실행기의 `RemoteRoot`에는 여기서 출력된 절대 경로를 사용한다.

```bash
mkdir -p "$HOME/coffeeprod-perf-phase2"
tar -xzf /tmp/coffeeprod-dev8-v2.tar.gz -C "$HOME/coffeeprod-perf-phase2"
cd "$HOME/coffeeprod-perf-phase2"
pwd
export PERF_BIND_IP=192.168.0.8
bash scripts/performance/phase2.sh init
```

init은 전용 소유 UUID·합성 자격 증명을 `scripts/performance/.env.perf`에 권한600으로 생성하고 JDK/PostgreSQL/Redis의 기반 이미지를 digest로 고정한다. 기존 파일은 덮어쓰지 않는다. 해당 파일을 출력하거나 소스 archive/버전 관리에 포함하지 않는다.

## 2. Docker 구동과 DB 준비

**측정 전용 파일·환경·프로젝트 이름**을 명시한다. app과 checks 이미지는 아래처럼 측정 전에 순차 빌드한다. 측정 중에는 빌드·pull을 하지 않는다. 저장소 기본 compose에는 app이 없고, prod compose는 GHCR 이미지를 사용하므로 이번에는 아래 파일을 사용한다.

```bash
docker compose --env-file scripts/performance/.env.perf \
  -p coffeeprod-perf-phase2 -f scripts/performance/compose.yml \
  build app && \
docker compose --env-file scripts/performance/.env.perf \
  -p coffeeprod-perf-phase2 -f scripts/performance/compose.yml \
  --profile checks build checks && \
docker compose --env-file scripts/performance/.env.perf \
  -p coffeeprod-perf-phase2 -f scripts/performance/compose.yml \
  up -d --no-build --pull never --wait

docker compose --env-file scripts/performance/.env.perf \
  -p coffeeprod-perf-phase2 -f scripts/performance/compose.yml ps
```

app은 `http://192.168.0.8:18080`, 관리 포트는 서버 내부 `127.0.0.1:19091`이다. 로컬 PC가 LAN 포트18080에 접근할 수 있어야 한다. Cloudflare/R2 경로를 부하에 섞지 않는다. DB/Redis/mock PG는 호스트 포트를 게시하지 않는다.

**DB 변경:** 운영 migration은 추가/수정하지 않는다. 새 전용 볼륨에 Flyway V1~V18이 실제 적용된다. `prepare`는 이 전용 DB에 합성 데이터를 적재하고 진단용 `pg_stat_statements` 확장을 만든다. 기존 운영 DB에는 적용하지 않는다.

```bash
# 실제 migration 성공 확인
docker compose --env-file scripts/performance/.env.perf \
  -p coffeeprod-perf-phase2 -f scripts/performance/compose.yml \
  exec -T db sh -lc 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"'

# 일반회원10000·주문150000·주문상품375000·상품192 적재/검증 및 snapshot
bash scripts/performance/phase2.sh prepare --run-id dev8-v2-before-01
```

prepare는 기존 시드 SQL을 재사용하지만 기존 `load_commerce.sh`를 호출하지 않는다. 전용 실행별 `users.json`과 실제 PK를 출력하며 기존 `k6/data/users.json`을 덮어쓰지 않는다. 같은 RunId의 snapshot은 재생성하지 않는다. 다른 기준선을 만들 때는 새 RunId를 사용한다. 추천 후보1000개는 baseline snapshot 복원 후 별도 variant로 생성한다.

## 3. 로컬에서 전체 before 실행

우선 자동 인증을 확인한다. 평소 대화형 SSH 로그인과 자동화의 무인 인증은 별도 조건이다.

```powershell
ssh -o BatchMode=yes parktaegyu@192.168.0.8 'true'

pwsh -File scripts/performance/run-phase2.ps1 `
  -Phase before -RunId dev8-v2-before-01 `
  -BaseUrl http://192.168.0.8:18080 `
  -SshTarget parktaegyu@192.168.0.8 `
  -RemoteRoot /home/parktaegyu/coffeeprod-perf-phase2
```

`RemoteRoot`는 서버에서 확인한 실제 `pwd` 값으로 바꾼다. Git Bash에서 pwsh를 호출할 때는 명령 앞에 `MSYS_NO_PATHCONV=1`을 붙인다. Git Bash의 SSH 프로그램을 사용해야 한다면 `-SshExecutable 'C:/Program Files/Git/usr/bin/ssh.exe' -ScpExecutable 'C:/Program Files/Git/usr/bin/scp.exe'`를 추가한다.

실행기는 서버/로컬 소스·측정기·fixture 해시와 전용 owner를 확인한다. HTTP는 **반복마다 복원 → 앱 준비 → 예열30초 → 같은 앱에서 본 측정 → 수집**을3회 수행한다. 예열 뒤에는 앱 재시작·DB/Redis/mock 초기화·빌드를 하지 않는다. 일반 조회는10업무회/s·본 측정2분, 주문 쓰기는1업무회/s·2분이다. 예열300업무회·쓰기30건을 검증하며 쓰기 예열의 주문/항목/재고/마일리지 변화도 확인한다. 기간 집계는 단일 VU로30초 동안 순차 예열하고 진행 중 요청을 마친 뒤1회 측정한다. 후보4는 기존 mock 조회45초·작업3건의 진단만 수행한다. 전체14개 variant의 `All`은 진단42개+HTTP39세션=81개다. 각 HTTP 세션 안에 예열·본 측정 파일과 동일 앱 식별자·시작 시각을 저장한다.

`-Mode All`이 기본이다. 진단만은 `-Mode Diagnostics`, HTTP만은 `-Mode Http`를 사용한다. HTTP 전용 실행은 JUnit 진단42개를 다시 실행하지 않으며 전체8개 인수로 자동 표시하지 않는다. 일부 후보는 기존 `-Candidates`로 선택한다.

특정 후보만 확인하려면 PowerShell에서 배열로 호출한다. 이 결과는 전체8개 기준선 완료로 판정하지 않는다.

```powershell
& ./scripts/performance/run-phase2.ps1 -Phase before -RunId phase2-new-smoke `
  -BaseUrl http://192.168.0.8:18080 -SshTarget parktaegyu@192.168.0.8 `
  -RemoteRoot /home/parktaegyu/coffeeprod-perf-phase2 -Candidates @(1,5)
```

각 RunId는 서버 prepare가 선행돼야 한다. 실행 실패 시 기존 결과를 보존하고 원인을 확인한다. 같은 로컬 phase 디렉터리를 덮어쓰지 않는다. 새 RunId로 재실행하려면 서버의 prepare도 그 RunId로 수행한다.

### v2 소스 반영과 HTTP 전용 명령 — 향후 추가 측정을 결정한 경우

현재는 코드 수정·자체 검증까지만 수행한다. 다음 명령은 향후 HTTP 추가 측정을 결정했을 때 사용한다. 1절의 새 v2 archive 생성·전송 후 서버의 기존 전용 디렉터리에 압축을 푼다. 기존 `.env.perf`·owner·DB 볼륨·과거 결과는 유지하고 init은 반복하지 않는다. `dev8-v2-before-01`은 새 RunId 예시이며 이미 존재하면 양쪽에서 같은 새 이름을 사용한다. HTTP 전용이므로 checks 이미지 빌드는 생략한다.

```bash
cd /home/parktaegyu/coffeeprod-perf-phase2
tar -xzf /tmp/coffeeprod-dev8-v2.tar.gz -C /home/parktaegyu/coffeeprod-perf-phase2
bash scripts/performance/phase2.sh sync-source && \
docker compose --env-file scripts/performance/.env.perf \
  -p coffeeprod-perf-phase2 -f scripts/performance/compose.yml build app && \
docker compose --env-file scripts/performance/.env.perf \
  -p coffeeprod-perf-phase2 -f scripts/performance/compose.yml up -d --no-build --pull never --wait && \
bash scripts/performance/phase2.sh prepare --run-id dev8-v2-before-01
```

로컬 Git Bash에서 실행한다. SSH agent에 기존 키가 등록되어 있고 무인 SSH가 성공하는 창을 사용한다.

```bash
MSYS_NO_PATHCONV=1 pwsh -File scripts/performance/run-phase2.ps1 \
  -Phase before -Mode Http -RunId dev8-v2-before-01 \
  -BaseUrl http://192.168.0.8:18080 \
  -SshTarget parktaegyu@192.168.0.8 \
  -RemoteRoot /home/parktaegyu/coffeeprod-perf-phase2 \
  -SshExecutable "C:/Program Files/Git/usr/bin/ssh.exe" \
  -ScpExecutable "C:/Program Files/Git/usr/bin/scp.exe"
```

v2 전체 정상 완료는 `execution.json`의 `protocolVersion=2`, `mode=All`, `status=PASS`, `allEightCandidates=true`, `samples.length=81`로 확인한다. `Http`는39세션, `Diagnostics`는42개이며 `allEightCandidates=false`다. 기존 v1의94개 기준과 혼용하지 않는다. v2 전체 소요 시간은 아직 실측하지 않았으므로 기존3시간20분을 새 절차의 예상 시간으로 제시하지 않는다.

## 4. 서버에서 진단·복원 명령 직접 실행

자동 SSH가 준비되지 않은 경우 서버 셸에서 개별 진단까지 실행할 수 있다. 아래는8번의 예다. `--sample`은 새 이름이어야 한다.

```bash
bash scripts/performance/phase2.sh restore --run-id dev8-v2-before-01 \
  --phase before --candidate 8 --variant catalog --sample manual-c8-r1
bash scripts/performance/phase2.sh checks --run-id dev8-v2-before-01 \
  --phase before --candidate 8 --variant catalog --sample manual-c8-r1
bash scripts/performance/phase2.sh collect --run-id dev8-v2-before-01 \
  --phase before --candidate 8 --variant catalog --sample manual-c8-r1
```

checks는 app을 중지하고 전용 검증 컨테이너에서 실행한 뒤 app을 다시 구동한다. 4번은 worker를 켜고 실제 예약 작업의 대기/lease/중복 POST를 확인한다. 나머지에서는 worker를 끈다. SQL·엔티티 로딩·JWT spy 진단과 HTTP 지연 측정은 분리하며 k6 대상 app에는 진단 계측을 켜지 않는다.

## 5. 결과·after·종료

- 서버/로컬 원본: `build/perf-phase2/<RunId>/before`, 이후 `after`와 `comparison`.
- 실행 조건·소스/fixture 해시·DB snapshot SHA256·기반 image digest·자원 제한·JUnit XML/HTML·실제 SQL/bind/EXPLAIN JSON·CPU/로딩·잠금·k6 summary/timeseries·실패/drop을 보존한다. JUnit은 `TEST-*.xml`만 내보낸다. Gradle 내부 `xml/binary`는 전송하지 않으며 생성된 XML·HTML은0644/디렉터리0755로 내보낸다. 원본 테스트 결과·단언은 변경하지 않는다.
- 합성 계정 파일은 build 아래에만 두고 토큰 원문을 결과에 기록하지 않는다. 서버 로그 수집은 app200줄·SQL은 진단용 합성 SELECT로 제한한다.
- 기준선 확정 후 측정기/fixture를 고정한다. DEV9의 운영 코드 변경을 새 archive로 전달하고 `phase2.sh sync-source`, 위의 app/checks 순차 빌드와 `up -d --no-build --pull never --wait`를 수행한다. 절차/측정기·fixture·자원/기반 image가 다른 자료는 같은 전후 측정으로 합치지 않으며, 기존 진단의 재사용 여부는 원래 해시·환경·영향 범위를 확인한다.
- 실패한 진단/HTTP 시험도 수집과 SCP를 각각 시도한다. 최초 시험 오류를 유지하고 추가 수집·전송·monitor 종료 오류는 `execution.json`의 `secondaryFailures`에 기록한다. SSH255는 transport/session, 그 외 원격 종료는 해당 단계의 원문 로그로 구분하며 SCP 오류도 경로·종료 코드·로그를 남긴다.

```powershell
# DEV9 구현 후에만 사용
pwsh -File scripts/performance/run-phase2.ps1 -Phase after -RunId dev8-v2-before-01 `
  -BaseUrl http://192.168.0.8:18080 -SshTarget parktaegyu@192.168.0.8 `
  -RemoteRoot /home/parktaegyu/coffeeprod-perf-phase2
node scripts/performance/compare.mjs build/perf-phase2/dev8-v2-before-01
```

비교기는 같은 절차 버전·측정기/fixture·부하 조건, 반복별 앱 식별자/시작 시각과 예열 증거, 오류/drop·결과 누락·쓰기 예열 변화까지 검증한다. 일반 HTTP는업무p95 3개와 중앙값, 기간 집계는단일 요청 시간3개와 중앙값을 표시한다. `All`에서는 추천 업무 결과도 검증하고 `Http`에서는 진단 미포함을 표시한다. 통계·잠금·스케줄러·SQL/로딩·JWT 및 인덱스의 상세 비교/채택 근거는 DEV9/QA9가 기록한다. 3회 중앙값을 통계적 유의성·운영 최대 처리량으로 표현하지 않으며 과거 약27% 개선과 합산하지 않는다.

로그 확인·데이터를 보존하는 종료:

```bash
docker compose --env-file scripts/performance/.env.perf \
  -p coffeeprod-perf-phase2 -f scripts/performance/compose.yml logs --tail=100 app
docker compose --env-file scripts/performance/.env.perf \
  -p coffeeprod-perf-phase2 -f scripts/performance/compose.yml stop
```

기존 서비스에 `down`을 실행하거나 기존 `postgres-data`/`redis-data`를 삭제할 필요가 없다. 이번 DB 초기화/복원은 owner 확인이 포함된 전용 `prepare`/`restore` 명령만 사용한다. 홈 서버의8개 실제 기준선이 확보되기 전 DEV8은 검증 대기이며 DEV9를 시작하지 않는다.
