# Cloudflared 지표 수집 연결

- 상태: 지표 SSH 연결과 Prometheus 수집 확인 완료입니다. 추가 혼합 비교를 준비합니다.
- 목적: 로그에 나타나지 않는 터널 RTT·손실·프록시 오류를 혼합 부하의 느린 요청 시간대와 대조합니다. Backend 코드 개선이 아닌 진단 계측입니다.
- 기존 앱 SSH 포트 18080/18091, 데이터·배포·cloudflared 서비스는 변경하지 않습니다.

2026-10-08 19:44:40 KST 자동 점검에서 Windows 18092~18096은 모두 미연결이었고 기존 `coffeeprod` target은 UP이었습니다. 추가 job 설정 예시는 실행 중인 Prometheus의 promtool 구문 검사를 통과했습니다. live scrape 설정·서비스는 아직 변경하지 않았고 새 부하도 실행하지 않았습니다.

후속 사용자 연결 후 Windows 18092에서 exporter를 발견했습니다. 실제 config 검사 PASS 후 로컬 Prometheus만 재시작했고 `coffeeprod`·`cloudflared` 두 target UP/최근 scrape 약 3초를 확인했습니다. RTT 3~4ms, HA 연결 4개, 프록시 오류 카운터 0을 읽었습니다. `quic_client_lost_packets`는 실제 시계열이 없어 미제공으로 기록하며 손실 0으로 해석하지 않습니다. RTT의 실제 HELP도 millisec로 확인했습니다. Ubuntu 앱·DB·cloudflared는 종료·재시작·재배포하지 않았습니다.

기존 live config 백업은 Git 제외 경로 `.md/improvement/monitoring/prometheus-before-cloudflared-20261008T112435Z.yml`에 보존했습니다. 최초 checksum은 `6484774573f2f534526aa2f00f55115ba29fd60a2ff219f94d2cb8bd83acddb1`입니다.

## 연결 방식

일반 호스트에서 cloudflared는 기본적으로 loopback의 20241~20245 중 사용 가능한 포트에 `/metrics`를 엽니다. 전체 범위가 사용 중이면 다른 임의 포트가 될 수 있습니다. 실제 포트를 추정으로 확정하지 않습니다. [Cloudflare 공식 문서](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/monitor-tunnels/metrics/)

바탕화면 연결 메모의 **cloudflared 지표 터널 추가** 명령을 별도 PowerShell 창에서 실행하고 창을 유지합니다. 기존 앱·모니터링 터널은 닫지 않습니다. 이 명령은 Windows 18092~18096을 Ubuntu loopback 20241~20245에 대응시킵니다. 새 서버 방화벽 개방·공개 metrics URL·Ubuntu 설정 변경은 없습니다.

QA 점검 도구가 5개 로컬 주소 중 실제 cloudflared 지표를 반환하는 주소만 선택합니다. 여러 주소가 응답하면 어느 터널인지 확인하기 전 자동 선택하지 않습니다. 기본 범위에서 발견되지 않으면 서비스 재시작이나 ExecStart 변경 없이 시작 로그의 metrics 주소만 확인하는 별도 절차가 필요합니다. 전체 ExecStart·환경 변수는 토큰 노출 위험 때문에 요청하지 않습니다.

## 자동 점검과 적용 순서

1. cloudflared exporter의 HTTP 200·metric 이름·단일 endpoint를 확인합니다.
2. 확인한 로컬 주소로만 Prometheus `cloudflared` job을 추가합니다. 기존 `coffeeprod` job과 저장 볼륨·비밀번호는 유지합니다.
3. promtool로 설정을 검사합니다. 부하가 실행 중이면 Prometheus 재시작/재적용은 보류합니다.
4. 필요한 경우 기존 Prometheus 컨테이너만 재시작하고 앱·cloudflared 두 target UP과 최근 scrape를 확인합니다.
5. 실제 지표 이름·값·단위를 확인한 뒤 고정된 순서 교차 혼합 비교를 진행합니다. 이 문서 작성 단계에서 새 부하는 실행하지 않았습니다.

Windows 점검 명령은 연결 메모에도 포함했습니다.

```powershell
& '.\.md\improvement\monitoring\check-cloudflared.ps1'
```

## 수집할 항목

| 관측 | PromQL 예시 | 해석 |
|---|---|---|
| 수집 상태 | `up{job="cloudflared"}` | 1이어야 최근 exporter 조회 성공 |
| 터널 RTT | `quic_client_smoothed_rtt{job="cloudflared"}` | 문서 기준 ms, 실제 실행 버전의 설명도 확인 |
| 패킷 손실 증가 | `sum(increase(quic_client_lost_packets{job="cloudflared"}[1m]))` | 누적 카운터의 창 증가량, 빈 결과는 0이 아님 |
| 원본 프록시 오류 증가 | `sum(increase(cloudflared_tunnel_request_errors{job="cloudflared"}[1m]))` | 오류 카운터의 창 증가량 |
| 연결 수 | `cloudflared_tunnel_ha_connections{job="cloudflared"}` | 연결 유지 여부, 서버 처리 성능 지표는 아님 |
| 연결 위치 | `cloudflared_tunnel_server_locations{job="cloudflared"}` | 현재/이전 edge 위치 전환 확인 |

지표 지원 여부는 실제 실행에서 확인합니다. QUIC를 사용하지 않거나 버전 차이가 있으면 일부 지표가 없을 수 있습니다. `increase`는 추정 증가량이며 정수 실측 요청 수와 같다고 가정하지 않습니다. 터널 RTT는 Windows→Cloudflare 구간까지 포함한 전체 사용자 응답시간이 아닙니다.

## 완료 조건과 원복

기본 지표 endpoint 발견, Prometheus 두 target UP, 의미 있는 최신 지표 수집이 확인되어야 연결 완료입니다. RTT·손실·오류와 클라이언트 지연이 시간상 겹쳐도 그것만으로 단독 원인을 확정하지 않습니다. 기존 A3와 예열 실패를 교체하지 않습니다.

이번 연결은 별도 SSH 창을 닫고 추가한 cloudflared scrape job만 제거하면 원복할 수 있습니다. 기존 `coffeeprod` job·Prometheus/Grafana 볼륨은 삭제하지 않습니다. 실제 설정 적용 전 현재 설정 백업을 보존합니다.

[2차 진단](README.md) | [앞선 혼합 비교](RESULT_20261008.md)
