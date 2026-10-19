# SSH 원본의 도구·연결 조건 비교

- 상태: 고정 비교 완료. QA TASK 4 원인 진단 진행 중이며 Backend 코드·DB 개선은 미실행입니다.
- 목적: Windows SSH 원본의 지연이 k6에서만 발생하는지, HTTP 연결 재사용 조건에 따라 달라지는지 확인합니다.
- 범위: 같은 PC·같은 SSH 터널·`http://127.0.0.1:18080/api/v1/categories`만 GET. 로그인·배포·서버 설정·DB 변경 없음.

## 계획과 실행

`ssh-tool-probe-20261008T134459Z`, 2026-10-08 22:44:59~22:48:57 KST(13:44 UTC). curl/k6 각각 새 HTTP 연결·재사용 연결, 네 조건에 15건씩 네 블록을 실행했습니다. 블록별 조건 순서를 순환하여 각 조건이 실행 위치 0~3에 한 번씩 나타납니다. 약 1초에 한 번 시작하는 직렬 요청, 조건별 총 60개입니다.

HTTP1.1·Accept/Accept-Encoding identity/User-Agent를 맞추고 child process에서 proxy 우회와 curl 설정 파일 비사용을 적용했습니다. curl은 요청마다 `Connection: close` 또는 재사용, k6는 `noConnectionReuse` true/false입니다. 바깥 SSH 연결은 유지했습니다. `--no-keepalive`는 HTTP 재사용 제어가 아니므로 사용하지 않았습니다. [curl 공식 옵션](https://curl.se/docs/manpage.html) · [k6 재사용 옵션](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/#no-connection-reuse)

전체 16구간 exit 0, HTTP 240/240 성공입니다. k6 120개는 CommonResponse·카테고리 digest 일치까지 검증했습니다. curl 120개는 HTTP/수신 크기를 확인했으며 본문을 버렸으므로 업무 계약까지 검증한 것은 아닙니다. 기존 baseline 입력 20개 hash·health·두 target native freshness를 자동 점검했습니다. 성능 SLO 인수나 높은 부하는 아닙니다.

## 결과

| 조건, 각 60개 | 응답 교환 중앙값 | p95 | p99 |
|---|---:|---:|---:|
| curl 새 연결 | 125.57ms | 127.30ms | 147.88ms |
| curl 재사용 | 48.25ms | 89.98ms | 116.63ms |
| k6 새 연결 설정 | 111.74ms | 124.24ms | 125.19ms |
| k6 재사용 | 53.36ms | 82.11ms | 116.82ms |

curl 응답 교환은 요청별 `total-pretransfer`, k6는 native `http_req_duration`입니다. 시작 경계가 완전히 같지는 않으며 분위수끼리 빼지 않습니다. 각 조건 네 구간의 60개를 합친 분위수로, 장기 안정적 p99나 최대 처리량이 아닙니다. 각 블록 통계도 자료에 보존했습니다.

별도 전체 시간은 curl native total, k6는 HTTP 호출 앞뒤 Date.now의 1ms 해상도 값입니다. 이 중앙값은 각각 125.91/48.34ms, 112.50/53.50ms입니다. k6 Date.now 값은 native duration과 별도 지표로 구분했습니다.

curl native `num_connects` 합계는 새 연결 60개·재사용 4개였습니다. k6의 positive connecting 표본은 7/4개였지만 connecting=0을 연결 없음으로 해석하지 않습니다. k6 새 연결은 설정으로 제어했고 실제 socket 개수는 패킷으로 검증하지 않았습니다.

## 서버와 판단

같은 실행 창의 15초 평가 시각 16개에서 서버 카테고리 rolling 1분 p99는 2.09~3.83ms, 앱 CPU 최대 0.268%, DB pending 0입니다. [앞선 Ubuntu localhost](RESULT_ORIGIN_LOCAL_20261008.md)의 total p99 3.46ms와 방향이 일치하지만 동시 per-request span 비교는 아닙니다.

**SSH 원본 지연은 k6만의 현상이 아닙니다.** 두 도구 모두 재사용 연결에서도 약 48~53ms의 중앙값을 보였습니다. Cloudflare를 통하지 않는 경로에서도 지연이 있으므로 전체 지연을 Cloudflare 하나로 설명할 수 없습니다. SSH 전달·클라이언트/VM 네트워크·스케줄링을 우선 조사할 근거입니다.

새 HTTP 연결이 두 도구 모두 더 느렸습니다. 다만 기존 k6 혼합 테스트도 이미 연결을 재사용하므로 이 차이를 새로운 Backend 최적화 성과로 주장하지 않습니다. SSH channel 생성·ACK 지연·buffering 등의 정확한 원인은 패킷 시각 없이 확정하지 않습니다. 이번 카테고리 저빈도 결과는 주문·로그인·혼합 고부하의 병목을 모두 배제하지 않습니다.

## QA 도구 중단 보존

최초 `ssh-tool-probe-20261008T134303Z`는 첫 curl 구간 뒤 22:43:17 KST에 파싱 오류로 중단했습니다. 다중 URL에 `--out-null`이 첫 출력에만 적용되어 공개 카테고리 본문과 JSON timing이 섞였습니다. 부분 stdout은 Git 제외 폴더에 보존하며 공식 비교에 사용하지 않습니다. Backend/API 성능 실패로 분류하지 않습니다.

각 URL에 `--output NUL`을 지정하도록 보완했습니다. 실제 서버와 무관한 로컬 모의 서버에서 본문 미출력·JSON 순서·재사용 1개/새 연결 3개의 회귀를 확인한 뒤 위 고정 비교 1회를 실행했습니다. 성능이 좋을 때까지 반복하거나 임계값을 낮추지 않았습니다.

## 다음 작업

남은 후보를 구분하려면 Windows SSH 구간과 Ubuntu 원본 구간의 **TCP 패킷 시각·길이·ACK 메타데이터를 같은 요청 창에 대조**해야 합니다. HTTP 본문·로그인 토큰을 수집하지 않는 조건으로 별도 준비하며, 아직 패킷 캡처나 SSH/VM/서버 설정 변경은 하지 않았습니다. 공개 NRT/connector ICN 영향도 별도 후보로 유지합니다.

코드 병목 근거가 없는 상태에서 DEV DB·BCrypt·메모리 수정 업무를 할당하지 않습니다. 기존 A3·중단 실행·완료 실행을 모두 유지합니다.

[정제된 조건별·블록별 통계](data/ssh-tool-probe.json) · [이전 Ubuntu 내부 결과](RESULT_ORIGIN_LOCAL_20261008.md) · [진단 개요](README.md)
