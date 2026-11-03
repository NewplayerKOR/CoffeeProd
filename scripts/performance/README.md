# DEV8 측정 도구

홈 서버의 **전용** Docker Backend에 로컬 PC가 부하를 보내는 DEV8/DEV9/QA9 공용 측정기다. 운영 코드의 구조 개선을 포함하지 않는다. 일반 `test`, `bootJar`, GHCR CI에는 performance source set을 넣지 않는다.

현재 절차는 **protocolVersion 2**다. 각 HTTP 반복은 `복원 → 앱 준비 → 예열30초 → 같은 앱에서 본 측정 → 수집` 순서이며 3회 반복한다. 두 단계 사이에 앱/DB/Redis/mock을 초기화하지 않는다. 전체 `All`은 진단42개 + HTTP39세션 =81개이고, HTTP 세션마다 `warmup/`·`measurement/`·`lifecycle.json`을 보존한다. 기존94개 실행 원본은 당시 절차의 PASS 자료로 보존하며 변경하지 않는다.

실행 순서·SSH/소스 전달·Docker·DB·복원·결과 판정은 [홈 서버 실행 안내](../../improvement/code-optimization/README.md)를 따른다. 현재 업무 상태는 Git 제외 문서 `.md/TASKS.md`의 DEV TASK8에서 관리한다.

로컬 도구 자체 확인:

```powershell
.\gradlew.bat --offline --no-daemon perfTest perfBootJar --rerun-tasks --console=plain
node --test scripts/performance/protocol.test.mjs
```

`perfTest` 기본 실행은 보호 장치·HTTP mock 등의 단위 검증이다. `-PperfServer -PperfCandidate=1`부터 `8`까지는 명시한 전용 PostgreSQL/Redis에서만 실행된다. 서버 연결 정보와 소유 UUID가 없으면 연결 전에 실패한다. H2나 개발 PC smoke 결과를 홈 서버 기준선으로 사용하지 않는다.

app/checks 이미지는 안내의 순서대로 미리 빌드한다. checks 실행 중에는 빌드/pull을 하지 않는다. 결과는 측정 JSON·콘솔·JUnit `TEST-*.xml`·HTML이며 Gradle 내부 binary는 내보내지 않는다. XML/HTML 파일은0644, 디렉터리는0755로 내보내 root 생성 결과도 일반 SSH 계정으로 내려받을 수 있다. 실패 시 최초 오류와 추가 수집/전송 오류를 구분해 보존한다.

실행기/내보내기 자체 회귀(운영 서비스 연결 없음):

```powershell
node --test scripts/performance/protocol.test.mjs scripts/performance/artifacts.test.mjs
pwsh -NoProfile -File scripts/performance/runner.test.ps1
node scripts/performance/manifest.mjs write
pwsh -NoProfile -File scripts/performance/runner-flow.test.ps1
```

`runner-flow.test.ps1`은 실제 실행기 전체를 모의 SSH/SCP/k6로 돌린다. 네트워크를 열지 않고 All/Diagnostics/Http의 수·순서와 예열 실패·재시작·HTTP 오류·drop·누락·수집/전송 실패를 확인한다. 합성 결과는 성능 기준선이 아니다.

Windows 로컬 Docker 자체 검증(홈 서버 접속 없음):

```powershell
# Docker Desktop 실행, Java21 빌드와 manifest 생성 후 새 결과 경로 사용
.\gradlew.bat --offline --no-daemon test perfTest perfBootJar bootJar --rerun-tasks --console=plain
node scripts/performance/manifest.mjs write
pwsh -NoProfile -File scripts/performance/selftest-local.ps1 `
  -OutputDirectory "$PWD/build/dev-task8/local-warmup-v2-01"
```

전용 로컬 프로젝트·`.env.perf`가 이미 있으면 보존하고 중단한다. 로컬에 캐시된 PostgreSQL16·Redis7·JDK21 기반 이미지와 Git Bash가 필요하다. 패키징된 측정 JAR로 로컬 runtime 이미지를 한 번 빌드하고 실제 V1~V18·시드·snapshot/복원 뒤 추천/주문5종/30일 통계를 각각1회 확인한다. 조회·주문은 예열30초+본 측정2분이며 통계는30초 순차 예열+단일 측정이다. 같은 소유 UUID의 컨테이너·전용 볼륨만 정리한다. 운영 JAR·운영 DB·홈 서버를 사용하지 않는다.

실행 모드는 `-Mode All`(기본), `Diagnostics`(42개), `Http`(39세션)다. 일부 모드는 전체 인수를 주장하지 않는다. 예열은 일반 조회300업무회·쓰기30업무회이며 기간 집계는 단일 VU로30초 동안 반복하고 진행 중 요청을 마친다. 본 측정의 일반 조회10업무회/s·2분, 쓰기1업무회/s·2분, 기간 집계1회는 유지한다. 쓰기 예열30건의 주문·항목·상품별 재고·마일리지 변화를 별도 검사한다.

측정기는 기준선 확정 후 동결한다. 절차/측정기/fixture가 다른 자료는 덮어쓰거나 같은 측정으로 합치지 않는다. 영향 없는 기존 진단 원본은 그 실행 해시와 함께 별도 참조할 수 있다. 개선 후 같은 버전의 `after`를 수집하고 `node scripts/performance/compare.mjs build/perf-phase2/<RunId>`로 비교한다. 비교기는 반복별 앱 식별자·시작 시각·예열 결과·HTTP 오류/drop·파일 누락·쓰기 변화까지 확인한다. 기간 집계는 단일 요청 시간3개와 중앙값으로 표시한다. SQL/로딩/스케줄러/JWT/인덱스 원본의 해석과 채택 판단은 DEV9/QA9가 수행한다.
