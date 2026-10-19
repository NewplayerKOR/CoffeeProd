# CoffeeProd 개선 기록

[성능 개선 최종 보고서](FINAL_REPORT.md)에서 대표 성과·그래프·판정·포트폴리오 활용을 확인합니다. 전체 프로젝트 최종 인수와 구분하며 원본 A3·실패·중단 기록을 유지합니다.

| 기록 | 현재 판정 |
|---|---|
| [1차 개선](1차개선/README.md) | 주문 조회 접근 경로와 p95 개선 확인, 전체 지연·안정적 p99 개선 미확인 |
| [2차 진단](2차개선/README.md) | 합의한 진단 범위 종료, 정확한 근본 원인·추가 성능 개선 미확인 |
| [최종 시각 보고서](http://127.0.0.1:4175/) | 로컬 미리보기 서버 실행 중 열리는 그래프·표 보고서 |

이 폴더의 Markdown·PNG·정제 증빙은 포트폴리오용 Git 추적 대상입니다. 계정·토큰·DB 백업·개인정보를 포함하지 않습니다. 자세한 운영 원본은 기존 Git 제외 `k6/results`에 보존합니다.

## 공개 범위와 재현

시각 보고서의 소스·정제 데이터·보관용 HTML과 PNG를 포함합니다. `.openai/` 호스팅 메타데이터, `.data-app-offline/`, `dist/`, 의존성 캐시, 환경변수·키·DB 백업·패킷 캡처 원본은 제외합니다. 공개 문서의 SSH 대상은 예시 변수이며 실제 접속 정보는 로컬에서만 지정합니다.

저장소 루트의 PowerShell에서 아래 검사는 운영 API·DB·SSH·pktmon 없이 실행합니다. curl 출력 검사는 임시 localhost 모의 서버만 사용하므로 로컬 연결이 허용되어야 합니다.

```powershell
$qaTests = @(Get-ChildItem -LiteralPath '.\src\test\k6' -File -Filter '*.mjs' |
    Where-Object { $_.Name -match '(?:\.test|-test)\.mjs$' } |
    Select-Object -ExpandProperty FullName)
node --experimental-vm-modules --test @qaTests
& .\src\test\k6\monitoring-freshness.test.ps1

# 새 checkout: 식별정보 없는 예시를 Git 제외 로컬 설정으로 복사한다.
# 기존 설정이 있다면 유지하며 실제 호스팅 프로젝트 정보를 덮어쓰지 않는다.
if (-not (Test-Path -LiteralPath '.\improvement\final-report\visual\.openai\hosting.json')) {
    New-Item -ItemType Directory -Force -Path '.\improvement\final-report\visual\.openai' | Out-Null
    Copy-Item -LiteralPath '.\improvement\final-report\hosting.example.json' `
        -Destination '.\improvement\final-report\visual\.openai\hosting.json'
}
node improvement/final-report/visual/scripts/verify-protected-runtime.mjs
node improvement/1차개선/scripts/validate-report.mjs
node improvement/final-report/scripts/validate-final-report.mjs
```

보고서 validator는 로컬 `data/ARTIFACT_QA.json`을 갱신합니다. 수치·해시 검사는 새 부하나 브라우저 검증이 아니며, 과거 측정·시각 확인의 시점과 한계를 유지합니다. `.md/`의 실행기·운영 원본은 공개 대상이 아니므로 실제 인프라 진단에는 별도로 준비된 로컬 도구와 승인된 환경이 필요합니다. 시각 보고서의 보호 런타임과 검증 manifest는 그대로 보존합니다.

`hosting.example.json`에는 프로젝트 ID·자격 증명 없이 런타임이 요구하는 기본 바인딩 이름만 들어 있습니다. 이 예시를 로컬에 복사해도 호스팅 게시를 수행하지 않습니다. `.gitattributes`는 보고서 증빙과 보호 런타임의 검증된 바이트가 checkout 시 줄바꿈 변환으로 달라지지 않도록 보존합니다. 보관용 생성 HTML의 컴파일러 공백도 그대로 유지합니다.
