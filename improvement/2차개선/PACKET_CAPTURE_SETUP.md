# TCP 헤더 대조 준비

- 상태: 준비 후 사용자가 실제 캡처·30건 진단을 실행했고 [결과 대조](RESULT_PACKETS_20261008.md)를 완료했습니다. 아래는 재현 절차입니다.
- 목적: [SSH 도구 비교](RESULT_SSH_TOOLS_20261008.md)에서 남은 전달 구간의 TCP 시각·ACK를 확인합니다. 운영 코드·DB·SSH/VM/서버 설정은 변경하지 않습니다.
- 대상 연결: Windows `CLIENT_IPV4:CLIENT_SSH_PORT` → Ubuntu `SERVER_IPV4:22`, API listener 18080을 소유한 SSH 프로세스입니다. 실제 내부 주소는 공개 자료에 포함하지 않습니다. 매 실행에서 현재 연결의 IP·source port를 확인해 예시 변수를 교체합니다.
- 로컬 도구: pktmon 설치됨, tshark/dumpcap 미발견. Codex 실행 세션의 pktmon 권한은 부족하여 상태 조회가 거부됐습니다. 관리자 권한을 우회하거나 기존 캡처를 종료하지 않았습니다.

## 1. Ubuntu 캡처 시작

Windows 일반 PowerShell에서 스크립트를 전송합니다.

```powershell
$sshTarget = 'SSH_USER@SSH_HOST' # 승인된 실제 접속 대상으로 교체
scp ".\src\test\k6\capture-ubuntu-ssh-headers.sh" "${sshTarget}:~/capture-ubuntu-ssh-headers.sh"
```

Ubuntu에서 실행하고 `listening on any` 출력 뒤 창을 유지합니다. tcpdump가 없다면 이 스크립트는 중단하며 자동 설치하지 않습니다. 필요한 경우 캡처 전에 `sudo apt-get install tcpdump`를 사용자 판단으로 실행합니다. 설치 중에는 부하를 실행하지 않습니다.

```bash
CLIENT_IPV4='REPLACE_WITH_CLIENT_IPV4'
CLIENT_SSH_PORT='REPLACE_WITH_CLIENT_PORT'
bash "$HOME/capture-ubuntu-ssh-headers.sh" "$CLIENT_IPV4" "$CLIENT_SSH_PORT"
```

선택한 SSH 4-tuple과 TCP 8080의 IPv4 헤더만 캡처합니다. Docker NAT/bridge 때문에 원본 HTTP를 loopback으로만 제한하지 않았습니다. SLL2 20 + 최소 IPv4 20 + 최소 TCP 20 = 60바이트로 제한해 TCP 본문을 기록하지 않습니다. 최대 5분 또는 20,000 packet, 권한 077의 사용자 HOME 임시 결과 폴더를 사용합니다. Ubuntu 전체 트래픽·QUIC·DNS는 수집하지 않습니다.

## 2. Windows 캡처와 자동 조회

현재 사용자 계정의 **관리자 PowerShell**에서 실행합니다. 다른 pktmon 작업이 있다면 실행하지 않습니다.

```powershell
$sshHostAddress = 'REPLACE_WITH_SERVER_IPV4'
& ".\src\test\k6\capture-windows-ssh-headers.ps1" -SshHost $sshHostAddress
```

PowerShell 명령은 저장소 루트에서 실행합니다. `-SshHost`는 필수 IPv4 값이며, 최초 선택과 캡처 직전 재확인 모두 같은 호스트를 사용합니다. 로컬 전용 실행기 `.md/improvement/monitoring/run-ssh-tool-probe.ps1`와 승인된 도구 런타임이 별도로 필요합니다. `.md/`는 공개하지 않으므로 새 checkout만으로 실제 캡처 실행 전체가 재현된다고 간주하지 않습니다.

1. 표시된 pktmon이 실행 중이 아니고 기존 필터가 없을 때만 `NONE`을 입력합니다. 기존 작업이나 필터가 있다면 중단합니다.
2. 표시된 SSH source IP/port가 Ubuntu 캡처 값과 같고 Ubuntu에 listening 출력이 있으면 `READY`를 입력합니다.
3. Windows 캡처를 시작하고 자동으로 카테고리 GET 30건을 실행합니다. k6 재사용 15건·새 연결 설정 15건, 직렬 약 1/s, 로그인·업무 쓰기 없음입니다. 상관관계용 단일 창이며 인수/최대 처리량/개선율 검증이 아닙니다.
4. Windows 캡처는 테스트 종료 시 정리되고 파일 변환·본문 미포함 검사를 실행합니다. Windows 완료 뒤 Ubuntu의 **현재 tcpdump 창만** Ctrl+C로 종료합니다. 종료하지 않아도 최대 5분에 자동 종료합니다. 앱·DB·SSH·Prometheus는 유지합니다.

Windows는 IPv4 SSH 4-tuple·NIC·54바이트로 제한합니다. 임시 pktmon 필터를 사용합니다. 원래 필터가 없다고 확인했고 생성 후 필터 목록이 바뀌지 않았을 때만 정리합니다. 이 Windows 버전의 `filter remove`가 전체 필터를 제거하기 때문에, 다른 필터가 생기면 제거하지 않고 경고합니다. 캡처 중 다른 pktmon 작업을 시작하지 않습니다. [Microsoft pktmon 옵션](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/pktmon-start)

## 3. 전달할 결과

- Ubuntu: 출력된 결과 폴더의 `ubuntu-headers.pcap`, `tcpdump-stderr.txt`, `conditions.json`, `completion.txt`를 Windows에 내려받거나 첨부합니다. `.env`·DB backup·키·토큰은 보내지 않습니다.
- Windows: 결과 폴더는 `k6/results/packet-capture-*`, 테스트 폴더는 `k6/results/ssh-packet-window-*`입니다. 로컬 파일은 QA가 읽을 수 있습니다. 본문 미포함 검사가 실패하면 원본을 공유하지 말고 오류만 알려줍니다.
- 초기 캡처·조회 실패도 보존하며 자동 재시도하지 않습니다. 두 파일의 실행 창이 겹치지 않거나 packet이 없으면 유효한 대조로 판정하지 않습니다.

## 해석 제한

표준 pcap/pcapng 헤더 파서는 TCP payload byte 0을 검사하고 주소·port·seq·ACK·flags·원래 길이·시각만 추출합니다. synthetic 회귀 12/12·PowerShell parser·Bash syntax 검사에 더해 실제 pktmon331개·Ubuntu4,492개 헤더 검증을 완료했습니다. 시계 offset과 ACK 원인 확인까지 완료한 것은 아닙니다.

SSH는 암호화되고 모니터링 채널도 같은 연결을 쓰므로 외부 packet을 HTTP 요청과 무조건 1:1 연결하지 않습니다. NIC offload·Docker/인터페이스 중복도 패킷 손실이나 재전송으로 단정하지 않습니다. 두 PC 시계 offset이 검증되지 않으면 기계 간 timestamp를 단순 차감하지 않고 각 기계의 flow 내 interval/ACK부터 비교합니다.

이번 캡처로도 정확한 원인이 구분되지 않으면 추가 확인의 필요성과 범위를 다시 판단합니다. 캡처만으로 Backend 개선 완료나 이전 A3 무효를 주장하지 않습니다.

[진단 개요](README.md) | [현재 유력 후보](RESULT_SSH_TOOLS_20261008.md)
