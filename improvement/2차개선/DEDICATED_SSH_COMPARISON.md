# 공유 SSH와 단독 SSH 비교 계획

- 상태: 지표/단독 터널의 preflight 중단을 보존하고 세 연결을 유지한 뒤 고정 비교를 완료했습니다. [실행 결과](RESULT_DEDICATED_SSH_20261009.md).
- 선행 근거: [TCP 헤더 대조](RESULT_PACKETS_20261008.md). 원본 HTTP 구간은 약 2~4ms였지만 이후 SSH data 귀속은 공유 모니터링 채널로 구분하지 못했습니다.
- 목적: 동일 endpoint를 공유/단독 SSH로 비교해 측정 경로의 공유 효과를 확인합니다. ACK/Nagle/VM 원인이나 Backend 성능 개선을 직접 증명하는 실험은 아닙니다.

## 고정 조건

같은 PC·Ubuntu·Windows OpenSSH 실행 파일을 사용합니다. 서로 다른 SSH process/socket인지 확인하고, 앱 지표 18091은 공유 API 18080과 같은 process에 남아 있어야 합니다. 별도 18081에는 카테고리 API 요청만 보냅니다. 이미 열린 앱·DB·모니터링·터널은 중단하지 않습니다.

HTTP1.1·연결 재사용·동일 헤더·카테고리 digest를 유지합니다. 공유/단독 15건씩 네 블록, 순서는 공유→단독/단독→공유를 교차해 각 60건·총 120건, 약 2분으로 고정합니다. 예쁜 결과가 나올 때까지 반복하지 않습니다.

health·앱/cloudflared native freshness 45초·기존 baseline 입력 20개 hash·SSH process/socket 고정·QA 스크립트 hash를 확인합니다. HTTP/check 실패·상태/입력 실패는 중단하고 부분 결과를 보존합니다. 로그인·주문·결제·재고 쓰기는 없습니다.

## 연결 명령

단독 API 터널(기존 18080·18091 유지, 별도 Windows PowerShell):

```powershell
$sshTarget = 'SSH_USER@SSH_HOST' # 승인된 실제 접속 대상으로 교체
& "C:\Windows\System32\OpenSSH\ssh.exe" -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:18081:127.0.0.1:8080 $sshTarget
```

18092가 닫혀 지표 수집이 connection refused인 경우에만 별도 창에서 복구합니다. 같은 모니터링을 단독 API 터널 18081에 합치지 않습니다.

```powershell
$sshTarget = 'SSH_USER@SSH_HOST' # 승인된 실제 접속 대상으로 교체
& "C:\Windows\System32\OpenSSH\ssh.exe" -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:18092:127.0.0.1:20241 $sshTarget
```

명령어 메모는 바탕화면 `CoffeeProd_단독_SSH_비교_명령어.txt`에 제공합니다. Ubuntu 상태 출력·재배포는 요청하지 않습니다.

## 수집과 해석

실행/검증은 `.md/improvement/monitoring/run-ssh-tool-probe.ps1 -DedicatedCompare`와 `analyze-dedicated-ssh.mjs`를 사용합니다. raw/native 7개 timing·업무 계약·조건별/블록별 통계·서버 카테고리 histogram과 자원을 저장합니다. source target 허용은 localhost 18080/18081 두 주소뿐이며 11개 회귀가 통과했습니다.

단독에서만 지연이 줄면 공유 SSH 경로가 영향을 주는 근거입니다. 양쪽이 비슷하면 공유 채널만으로는 설명되지 않으며 SSH 공통 처리·클라이언트/VM 구간을 다음 후보로 남깁니다. 새 connection/session의 나이가 다르고, 서버 per-request/복호화 trace가 없으므로 정확한 기여율이나 특정 TCP 설정 결함으로 확정하지 않습니다.

이 비교는 측정 경로 분리입니다. 운영 Backend의 개선 전후 결과·Cloudflare 성능 개선·최대 처리량으로 표현하지 않습니다. 이전 A3·실패·기존 캡처를 유지합니다.

[현재 패킷 결과](RESULT_PACKETS_20261008.md) | [진단 개요](README.md)
