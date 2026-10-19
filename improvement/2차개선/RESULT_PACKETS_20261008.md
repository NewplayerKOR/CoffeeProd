# TCP 헤더 캡처 대조 결과

- 상태: 전달된 Ubuntu/로컬 Windows 파일의 헤더 검증·대조 완료. ACK/Nagle/VM의 정확한 원인은 미확정입니다.
- 실행: `ssh-packet-window-20261008T142306Z`, 2026-10-08 23:23:07~23:23:37 KST(14:23 UTC). 사용자가 관리자 캡처 스크립트를 실행했습니다. 이번 분석에서는 추가 부하·운영 변경을 하지 않았습니다.
- 카테고리 HTTP 30/30 성공, 업무 check 30/30 성공, 재사용 15개·새 연결 설정 15개 모두 exit 0입니다. native 7 timing count/분위수를 재검증했습니다.

## 파일 검증

| 관측 | Windows | Ubuntu |
|---|---:|---:|
| 캡처 레코드 | 331 | 4,492 |
| 실제 저장 TCP payload | 0byte | 0byte |
| 형식 | pcapng/Ethernet | pcap/LINUX_SLL2 |

Ubuntu 관측 시각은 14:21:43.448~14:26:13.487 UTC로 조회 구간을 포함합니다. loopback 하나의 인터페이스에서 원본 HTTP request 30개·응답 그룹 30개를 확인했습니다. 재사용 flow 1개에 15개 request, 나머지 15개 flow에 각 1개 request로 계획과 맞습니다. Docker bridge/veth 복제 레코드를 별도 응답으로 합산하지 않았습니다.

tcpdump 로그는 captured 4,492/received by filter 4,852/dropped by kernel 0입니다. 수신 필터 카운터와 기록 카운터는 같은 의미가 아니며 차이 360개를 네트워크 손실로 해석하지 않습니다. kernel drop 0도 종단 간 손실 0을 증명하지 않습니다. [tcpdump 공식 매뉴얼](https://github.com/the-tcpdump-group/tcpdump/blob/master/tcpdump.1.in)

## 같은 Ubuntu 시계의 원본 HTTP 간격

| 30개 원본 TCP 요청 그룹 | 중앙값 | p95 | p99 | 최대 |
|---|---:|---:|---:|---:|
| 요청 data → 첫 응답 data | 2.07ms | 2.73ms | 3.60ms | 3.94ms |
| 요청 data → 마지막 응답 data | 2.22ms | 3.58ms | 5.36ms | 5.87ms |

같은 loopback TCP flow의 요청 data 뒤에 기록된 두 응답 data segment로 구분했습니다. HTTP 본문이나 URL을 복호화하지 않았으므로 서버 애플리케이션 span이라고 부르지 않습니다. 두 요청 길이 그룹은 각각 153byte 15개·172byte 15개였고, 계획한 연결 조건 및 flow 수와 일치했습니다.

Windows k6 duration 중앙값은 재사용 47.03ms·새 연결 설정 107.64ms였습니다. 서버 원본 HTTP 관측 구간은 짧은 반면 클라이언트 응답까지는 더 길다는 이전 진단을 뒷받침합니다. 서로 다른 분위수를 차감해 지연 비용을 계산하지 않습니다.

## SSH 전달 후보와 시각 제한

Ubuntu 원본 마지막 HTTP 응답 뒤 **다음 SSH server data**까지의 간격은 30개 후보의 중앙값 29.26ms/p95 42.69ms였습니다. 한 기계의 시계 안에서 관측한 간격입니다. SSH는 암호화되고 Prometheus/health 채널도 같은 연결을 쓰므로 다음 data가 같은 HTTP 응답이라는 것은 검증되지 않았습니다. 정확한 원인·지연 기여율로 표현하지 않습니다.

Windows 수신 레코드 중 동일 signature가 약 1~2μs 간격으로 중복 기록된 사례가 있습니다. 10μs 이내 동일 signature를 탐색적으로 합치면 331→316개지만 이는 실제 wire 재전송을 제거한 확정 결과가 아닙니다.

이 탐색 조건에서 단일 signature 대응은 client→server 301개/server→client 10개였습니다. 기록 시각의 Ubuntu−Windows 차이는 약 +902ms였으며 시계·캡처 기준 차이를 포함합니다. 단순 constant offset/비음수 전달 가정의 하한 902.546ms가 상한 902.372ms보다 약 0.174ms 커서 정확한 보정 모델도 성립하지 않았습니다. 따라서 **약 0.9초 네트워크 지연이나 보정된 편도 지연으로 보고하지 않습니다.**

ACK 구간 표본·관측 위치가 부족하므로 delayed ACK, Nagle, NIC buffering 또는 VM scheduling 중 하나를 확정하지 못했습니다. ACK 지연은 가능한 TCP 동작이지만 특정 호스트 결함으로 단정할 근거는 아닙니다. [TCP 표준](https://www.rfc-editor.org/rfc/rfc9293.html)

## 판단과 다음 범위

**카테고리 저빈도 범위에서는 Backend 내부 처리보다 원본 응답 이후 SSH 전달·클라이언트 처리 구간이 우선 후보입니다.** 계획했던 패킷 대조는 수행했고 원본 HTTP 구간이 짧다는 근거를 보강했습니다. 모든 API의 병목 배제나 SSH 특정 결함의 확정은 아닙니다.

필요한 다음 실험은 모니터링과 분리된 단독 SSH 터널 비교입니다. 공유 채널과 계측 위치의 영향을 먼저 분리하고, ACK 원인 확인이 실제로 필요할 때 수신 캡처 품질과 시각 기준을 보완합니다. 이번에 새 터널·캡처·서버 설정 변경을 추가 실행하지 않았습니다.

현재 DEV DB·BCrypt·메모리 수정 업무를 할당할 근거는 부족합니다. 환경/측정 경로 개선은 운영 Backend 성능 개선과 구분하며 원본 A3나 이전 실패를 교체하지 않습니다. QA TASK 4는 원인 확정·개선 판정 전 상태를 유지합니다.

## 자료

[정제된 수치·해석 제한](data/packet-correlation.json) · [준비 절차](PACKET_CAPTURE_SETUP.md) · [진단 개요](README.md)

원본 pcap/pcapng·stderr·개별 seq/ACK 자료는 Git 제외 `k6/results/packet-capture-20261008T142306Z`에 보존했습니다. 공개 JSON에는 본문·토큰·원본 IP/port/seq를 넣지 않았습니다. Ubuntu 원본 SHA256은 `bd5719048e2bfe3c346229f1d658636e90b1b8a200cddf3d2462d7e557e3b7a0`입니다.
