# Ubuntu 내부 원본 조회 확인

- 상태: 사용자가 실행한 CSV의 검증·분석 완료. QA TASK 4의 원인 진단은 진행 중입니다.
- 범위: `http://127.0.0.1:8080/api/v1/categories`, 1초 휴지, 60회 GET. 신규 부하·운영 코드·DB 변경은 없습니다.
- 출처: `coffeeprod-origin-local-PJ7xsubQ.csv`. 완료 시각 2026-10-08 21:52:45~21:53:45 KST(12:52:45~12:53:45 UTC), 시각 정밀도 1초입니다.

## 검증 결과

CSV schema, 순서가 연속인 0~59의 60개 round, HTTP 200/HTTP1.1, UTC 완료 시각 증가, 유한·비음수 timing과 `DNS <= TCP <= TTFB <= total`을 확인했습니다. 원본 SHA256을 확인하고 Git 제외 `k6/results/origin-local-20261008T125245Z`에 원본을 보존했습니다. 이 폴더 시각은 첫 응답 완료 시각이며 실행 시작 시각이 아닙니다.

| Ubuntu 내부, 60개 | 평균 | 중앙값 | p95 | p99 | 최대 |
|---|---:|---:|---:|---:|---:|
| TCP 연결 완료까지 | 0.18ms | 0.18ms | 0.21ms | 0.30ms | 0.40ms |
| 첫 바이트까지(TTFB) | 2.61ms | 2.53ms | 3.12ms | 3.30ms | 3.38ms |
| 전체 응답(total) | 2.77ms | 2.71ms | 3.30ms | 3.46ms | 3.51ms |

HTTP 성공 60/60, 실패 0입니다. 분위수는 정렬된 60개 표본의 `p*(n-1)` 위치에서 선형 보간했습니다. 초 단위 원본을 1,000배 하여 ms로 변환했습니다. curl의 TTFB/total은 요청 시작부터의 누적 시간으로 연결 시간을 포함합니다. [curl 공식 정의](https://curl.se/docs/manpage.html)

## 이전 경로와 대조

| 카테고리 조회, 각 60개 | 중앙값 | p95 | p99 |
|---|---:|---:|---:|
| Ubuntu localhost curl total | 2.71ms | 3.30ms | 3.46ms |
| Windows SSH 원본 k6 duration | 45.23ms | 90.22ms | 100.33ms |
| Windows 공개 API k6 duration | 87.46ms | 99.41ms | 199.14ms |

앞선 Windows 진단은 21:37~21:38 KST였습니다. 동일 시각·동일 도구 비교가 아니며 curl은 매번 새 localhost 연결, k6는 재사용 연결이고 duration에 초기 연결 시간이 포함되지 않습니다. 공개 첫 요청의 330.17ms도 포함한 기존 60개 결과를 그대로 사용했습니다. 분위수 차이를 순수 SSH/Cloudflare 비용으로 계산하지 않습니다.

## 판단

**이번 낮은 빈도의 카테고리 조회에서는 서버 내부가 느리다는 근거가 없습니다.** localhost 전체 응답도 약 2~4ms였으며 앞선 서버 histogram의 낮은 처리 시간과 방향이 일치합니다. Windows SSH와 공개 경로의 높은 클라이언트 시간은 애플리케이션 내부 밖의 전달 경로 또는 측정 클라이언트 영향을 우선 조사할 근거입니다.

이 결과만으로 모든 API·주문·로그인·혼합 고부하·VM 순간 상태가 정상임을 증명하지 않습니다. CSV는 본문·digest를 저장하지 않았으므로 HTTP 200은 업무 계약 검증이 아닙니다. 카테고리 데이터 일치 근거는 앞선 공개/SSH 120개 digest 검증이며 이번 localhost 본문의 일치를 확인한 것은 아닙니다.

클라이언트 NRT/connector ICN 위치 차이는 이전 진단 때 관측된 후보입니다. 정확한 내부 경로, SSH buffering·패킷 지연, 클라이언트 스케줄링 등은 아직 구분하지 못했습니다. Ubuntu 메모리 부족 또는 Cloudflare 단독 장애로 확정하지 않습니다.

## 다음 순서

1. 같은 Windows PC·같은 SSH endpoint에서 curl과 k6를 가까운 시각에 비교하여 도구·연결 재사용 영향부터 분리합니다. 기존 localhost 전용 포트 공개 범위와 보안 설정은 바꾸지 않습니다.
2. 공개 경로는 동일 endpoint의 위치 코드·timing을 다른 클라이언트 네트워크에서 비교할 필요가 있습니다. 실제 요청 추적이 없으면 Edge 내부 시간을 정확히 나눌 수 없습니다.
3. 코드 병목이 확인되면 DEV 업무를 할당합니다. 현재 DB·BCrypt·메모리 설정 수정이나 1차 A3 결과 교체는 하지 않습니다.

후속: 1번 Windows SSH 도구·연결 비교는 [240건 고정 비교](RESULT_SSH_TOOLS_20261008.md)로 완료했습니다. 다른 클라이언트 네트워크·패킷 시각 대조는 미실행입니다. 이번은 병목 후보를 좁힌 진단이지 새로운 Backend 성능 개선 성공 사례가 아닙니다.

## 재현 자료

- [정제된 CSV·통계](data/origin-local-probe.json): 60개 원본 수치, 검증 조건, 시간 정의와 해석 한계.
- CSV SHA256: `8b8328a8b9c90317c574bddb125e1c94c7f8bb0abd87a45e4b65bd89ee22189b`
- 재현: `.md/improvement/monitoring/analyze-origin-local.ps1 -CsvPath <CSV 경로>`.

[이전 타이밍·Edge 진단](RESULT_PATH_TIMINGS_20261008.md) | [진단 개요](README.md)
