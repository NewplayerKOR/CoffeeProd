# 요청 타이밍 분해와 Edge 경로 진단

- 상태: 추가 진단 완료. 운영 코드·DB 개선은 미실행이며 정확한 원인은 미확정입니다.
- 질문: 공개 혼합 부하의 tail이 신규 TCP/TLS 때문인지, 응답 대기인지 구분합니다.
- 범위: 기존 본 측정 4개 원시 자료 재분석 + 신규 읽기 전용 1VU 진단 1회입니다. 로그인·업무 데이터 변경·배포·Ubuntu 서비스 변경은 없습니다.

## 1. 기존 혼합 측정 분해

두 비교의 본 HTTP 24,076건을 요청 시각·전체 tag로 결합했습니다. 7개 timing의 native p95/p99와 요청 수, 요청별 duration 합계를 재현했습니다. 분해 함수의 누락·비정상 수치·경계·합계 회귀 12/12가 통과했습니다.

| 공개 본 측정 | 첫 비교 | 순서 교차 비교 |
|---|---:|---:|
| HTTP 표본 | 6,019 | 6,019 |
| duration p99 | 310.44ms | 306.10ms |
| waiting p99 | 309.63ms | 304.71ms |
| 250ms 이상 요청 | 257 | 247 |
| 느린 요청 시간 합계 중 waiting | 99.13% | 99.21% |
| 느린 요청 중 신규 TCP/TLS | 19 / 19 | 21 / 21 |
| 주문 조회의 신규 TCP/TLS | 0 / 0 | 0 / 0 |

느린 요청의 대부분은 재사용 연결에서도 발생했습니다. 관측된 tail을 반복 TLS handshake만으로 설명할 수 없습니다. `duration = sending + waiting + receiving`이며 초기 연결·TLS는 이 duration에 포함되지 않습니다. waiting에는 원격 처리와 네트워크 전달이 함께 포함되므로 서버 시간 또는 Cloudflare 내부 시간으로 치환하지 않습니다. 분위수끼리 더하거나 빼지 않습니다. [k6 공식 정의](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/)

두 공개 측정에서 250ms 이상 요청 중 로그인 계열은 240/257건(93.39%), 233/247건(94.33%)입니다. 로그인 처리 비용이 혼합 tail에 기여하지만 공개·원본 경로 차이를 BCrypt 결함으로 확정하지 않습니다. 보안 해시 비용을 낮추는 변경은 하지 않았습니다.

## 2. 읽기 전용 경로 진단

`edge-probe-20261008T123718Z`, 2026-10-08 21:37:18~21:38:30 KST(12:37 UTC). 60회 순환, 1VU, 순환마다 세 endpoint를 순서 교차해 GET하고 1초 휴지했습니다. 전체 180 HTTP/180 check 성공, exit 0, 자동 재시도 0입니다. `k6/results`에 조건·native summary·원시 timing·guard 관측을 보존했습니다.

- 공개: `https://api-coffeeprod.ttagyulab.com/api/v1/categories`
- SSH 원본: `http://127.0.0.1:18080/api/v1/categories`
- Edge 자체 응답: 동일 공개 호스트의 `/cdn-cgi/trace`

전체 카테고리 응답 120개의 data digest가 같았습니다. trace에는 클라이언트 IP가 있으므로 본문을 저장하지 않고 `colo`만 선별했습니다. 같은 호스트라도 Edge 자체 응답은 애플리케이션 요청과 다른 처리 경로이며 서버 trace 대용이 아닙니다. [Cloudflare 공식 설명](https://developers.cloudflare.com/fundamentals/reference/cdn-cgi-endpoint/)

| 전체 60개씩, duration | 중앙값 | p95 | p99 | 최대 |
|---|---:|---:|---:|---:|
| 공개 카테고리 | 87.46ms | 99.41ms | 199.14ms | 330.17ms |
| SSH 원본 카테고리 | 45.23ms | 90.22ms | 100.33ms | 111.45ms |
| Edge 자체 응답 | 39.61ms | 44.03ms | 46.41ms | 48.10ms |

첫 순환은 공개의 신규 TCP/TLS 연결 1개를 포함합니다. 첫 공개 요청은 duration 330.17ms, 별도 blocked 137.94ms/TLS 74.57ms였습니다. 330.17ms 전체가 TLS라는 의미는 아닙니다. 첫 순환을 제외한 추가 분류 59개씩의 p95/p99는 공개 98.69/103.47ms, SSH 66.13/91.80ms, Edge 44.05/46.44ms입니다. 전체 60개 결과도 유지하며 안정적 p99·개선율로 제시하지 않습니다. Edge는 첫 공개 요청이 연 연결을 재사용했으므로 독립 cold-start 비교가 아닙니다.

## 3. 위치와 서버 지표

- 공개 카테고리와 trace는 60개씩 모두 `NRT`, 공개 API cache status는 `DYNAMIC`입니다. 공개/Edge HTTP2, SSH 원본 HTTP1.1입니다.
- 서버 터널 위치는 `icn05`, `icn06`, `icn07`입니다. 15초 평가 간격 5개 시각의 HA는 4개, proxy error counter는 0, connector RTT는 4~5ms였습니다.
- 앱 CPU 표본 최대 0.223%, DB pending 최대 0, 서버 카테고리 rolling 1분 p99는 약 1.74~3.60ms였습니다. 범위 안의 histogram 추정치이며 요청별 span은 아닙니다.
- 초기·진행 중·종료 health/freshness 자동 guard를 사용했습니다. Ubuntu 전체 RAM·swap·유지보수 상태는 별도 계측하지 않았습니다.

클라이언트가 관측한 Edge 위치와 connector 위치가 다릅니다. 네트워크/Edge 전달 경로를 우선 조사할 근거가 추가됐지만 정확한 요청 내부 라우팅·추가 지연량을 증명하지는 않습니다. 이 위치는 신규 진단 때의 관측이며 과거 혼합 측정의 Edge 위치를 소급 확정하지 않습니다.

SSH 원본 카테고리도 서버 histogram보다 지연이 높았습니다. 따라서 SSH를 지연 없는 기준으로 보거나 공개/SSH 분위수 차이를 순수 Cloudflare 비용으로 해석하면 안 됩니다. 같은 PC·순차 교차 요청이지만 프로토콜·SSH·응답 경로와 시각이 다릅니다.

## 4. 다음 작업과 변경 판단

후속 완료: 사용자가 21:52~21:53 KST에 Ubuntu 내부 60건을 실행했고 CSV를 검증했습니다. 전체 응답 중앙값 2.71ms/p99 3.46ms, HTTP 60/60 성공입니다. [Ubuntu 내부 결과와 최신 다음 순서](RESULT_ORIGIN_LOCAL_20261008.md). 아래 명령은 당시 계획과 재현 절차로 보존합니다.

현재 운영 코드·DB·해시 비용을 수정할 근거는 부족합니다. 다음은 **Ubuntu 내부 HTTP 원본과 Windows SSH 원본을 구분**하는 진단입니다. 앱의 요청별 처리 시간과 클라이언트 timing을 연결하려면 추가 계측이 필요할 수 있지만 아직 DEV 변경을 할당하지 않습니다.

Ubuntu에서 아래 스크립트를 1회 실행하면 localhost 카테고리 60건만 조회합니다. 시스템 상태 출력 요청이 아니라 SSH 경로를 제외한 추가 진단이며 서버 설정은 바꾸지 않습니다. CSV만 공유하고 `.env`·로그인·백업 파일은 보내지 않습니다.

Windows PowerShell에서 스크립트를 전송합니다. 비밀번호는 터미널에서 직접 입력합니다.

```powershell
$sshTarget = 'SSH_USER@SSH_HOST' # 승인된 실제 접속 대상으로 교체
scp ".\src\test\k6\origin-local-probe.sh" "${sshTarget}:~/origin-local-probe.sh"
```

```bash
bash "$HOME/origin-local-probe.sh"
```

이 스크립트는 요청마다 새 localhost 연결을 열며 curl의 TTFB/total은 연결 시간도 포함합니다. 기존 k6 재사용 연결의 waiting/duration과 같은 정의는 아닙니다. 본문 계약 검증 없이 HTTP 200/전송 시간을 기록하는 보조 진단이고 카테고리 기능 검증은 앞선 120개 digest 비교가 담당합니다.

실행 결과가 원본 서버에서도 느리면 앱 처리 범위/스케줄링을 조사합니다. 내부는 빠르고 SSH만 느리면 SSH·클라이언트 전달 경로를 조사합니다. 공개 NRT/connector ICN 경로 후보는 병행하되 위치 강제·보안 설정 완화·서비스 종료를 임의로 수행하지 않습니다.

## 재현 자료

- [기존 timing 분해](data/path-timing-decomposition.json): 4개 본 측정, native count/분위수·합계 검증과 raw SHA256.
- [신규 Edge 진단](data/edge-probe.json): 전체/첫 순환 이후 분류, 위치·프로토콜·서버 지표와 해석 한계.
- QA 코드: `src/test/k6/path-timing-diagnostics.mjs`, `path-timing-diagnostics.test.mjs`, `edge-path-probe.js`, `origin-local-probe.sh`.
- 로컬 분석/실행 도구는 Git 제외 `.md/improvement/monitoring`에 있으며 운영 코드·DB 및 이전 A3 결과는 변경하지 않았습니다.

[이전 계측 결과](RESULT_CLOUDFLARED_20261008.md) | [진단 개요](README.md)
