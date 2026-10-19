# 공유·단독 SSH 비교 결과

- 상태: 고정 비교·검증 완료. 공유 모니터링 채널만으로 지연을 설명하기 어렵고, 정확한 SSH/OS/VM 원인은 미확정입니다.
- 실행: `dedicated-ssh-probe-20261008T145903Z`, **2026-10-08 23:59:03~2026-10-09 00:01:15 KST**. UTC suite 날짜는 10-08입니다.
- 범위: 공유/단독 SSH의 동일 카테고리 GET, HTTP1.1·재사용·동일 헤더·동일 data digest. 업무 쓰기·배포·Backend/DB/Ubuntu 설정 변경 없음.

## 조건과 검증

18080/18091은 같은 Windows OpenSSH process, 18081은 동일 실행 파일의 다른 process/socket입니다. 앱 모니터링은 기존 공유 경로에 유지했습니다. 18092 지표 터널도 별도 유지했습니다. process·socket·script/target module·기존 입력 20개 hash·health/native freshness를 실행 중 점검했습니다.

공유/단독 15개씩 네 블록이며 순서는 공유→단독/단독→공유를 교차했습니다. 각 60개, 전체 HTTP120/check120 성공, 8구간 exit0입니다. 정확한 GET URL·HTTP1.1·data digest·native 7 timing의 count/p95/p99를 검증했습니다. 연결 age와 실행 시각은 완전히 같지 않습니다.

## 결과

| k6 duration, 각 60개 | 평균 | 중앙값 | p95 | p99 |
|---|---:|---:|---:|---:|
| 공유 SSH | 49.07ms | 51.70ms | 97.19ms | 113.10ms |
| 단독 SSH | 46.51ms | 43.00ms | 77.17ms | 92.23ms |

| 블록, 각 경로 15개 | 공유 중앙값 | 단독 중앙값 |
|---|---:|---:|
| 0 | 43.99ms | 44.59ms |
| 1 | 55.02ms | 54.05ms |
| 2 | 55.69ms | 38.43ms |
| 3 | 54.71ms | 44.63ms |

단독 경로의 pooled 수치는 낮았지만 블록0은 오히려 높고 블록1은 비슷했습니다. 네 구간을 합친 60개 분위수이며 안정적 반복-run p99·통계적 유의성·인과적 기여율로 주장하지 않습니다. 낮은 결과만 골라 교체하거나 재시도하지 않았습니다.

같은 실행 창의 15초 서버 표본 9개에서 카테고리 rolling1분 p99는 약2.09~9.27ms, 앱 CPU 최대0.670%, HikariCP pending0입니다. 서버 전체 순간 peak나 per-request span을 측정한 것은 아닙니다.

## 보조 LAN 참고

비교 종료 뒤 00:05:05~00:05:12 KST에 같은 서버로 ICMP8회를 실행했고 8/8 응답 성공, 정수-ms 표시값은 모두0ms였습니다. 정확히 왕복 시간이0이라는 의미는 아닙니다. ICMP는 TCP/SSH 처리 경로와 달라 SSH 지연량으로 차감하지 않습니다.

이 작은 표본에서는 물리 LAN 왕복 자체가 수십 ms라는 근거가 관측되지 않았습니다. TCP·SSH forwarding·클라이언트/VM scheduling의 문제까지 배제하지 않습니다.

## 판단

**모니터링 공유는 일부 영향을 줄 수 있지만, 현재 지연의 충분한 설명은 아닙니다.** 분리된 경로에서도 중앙값43ms가 남았습니다. 앞선 Ubuntu 원본 HTTP 약2~4ms와 함께 보면 SSH/전달·클라이언트 처리의 공통 부분이 계속 우선 후보입니다.

현재 증거로 DB/BCrypt/메모리 튜닝을 DEV에 할당하지 않습니다. 단독 터널은 측정 경로 분리이며 공개 서비스의 Backend 개선 성과가 아닙니다. ACK/Nagle·특정 SSH 구현·VM scheduler 중 하나를 확정하지 않습니다.

추가로 원인을 좁힌다면 **다른 SSH client 구현의 단독 forwarding**을 같은 조건으로 비교하는 것이 다음 최소 실험 후보입니다. 그 비교·새 캡처·TCP/서버 설정 변경은 아직 미실행입니다. 원인 확정을 위해 반드시 무한 반복해야 하는 것은 아니며, 현재 자료로도 Backend 처리와 측정 경로의 차이를 기록할 수 있습니다.

## 중단·도구 보완

최초 preflight는 cloudflared18092 listener 없음/connection refused로 부하 전 중단했고, 복구 후에는18081 listener 없음으로 다시 부하 전 중단했습니다. 세 SSH 창을 유지한 뒤 위 고정 비교1회를 실행했습니다. 앱/DB 성능 실패로 분류하지 않습니다.

후처리 검증기의 Windows executable 경로 비교는 `WINDOWS`/`Windows` 대소문자 차이를 같은 경로로 정규화하도록 보완했습니다. 사전 점검은 같은 executable임을 확인했고 raw·임계값·측정 결과는 변경하지 않았으며 측정을 다시 실행하지 않았습니다.

종료 후 공유·단독·지표 연결, 앱 health/native freshness 및 남은 k6=0을 확인했습니다. 세 SSH 창과 모니터링은 유지했습니다.

[정제된 경로·블록별 통계](data/dedicated-ssh-comparison.json) · [사전 계획](DEDICATED_SSH_COMPARISON.md) · [패킷 근거](RESULT_PACKETS_20261008.md) · [진단 개요](README.md)
