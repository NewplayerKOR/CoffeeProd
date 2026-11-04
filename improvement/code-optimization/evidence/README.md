# 기존 측정 결과 보관

이 폴더는 이미 수행한 DEV8·DEV9·QA9 결과를 선별한 공유용 아카이브다. 다른 PC에서 저장소를 내려받으면 홈서버·DB·k6·Gradle 없이 결과를 읽고 그래프를 재생성할 수 있다. 추가 부하 테스트는 실행하지 않는다.

- `comparison.json` / `comparison.csv`: 지표·단위·전후값·표본 수·출처를 포함한 비교 데이터. CSV는 Excel 등에서도 사용할 수 있다.
- `qa9/`: 완료된 before 진단42개/HTTP39세션, after 진단9개/HTTP7세션. 중단된 after 장바구니r2는 제외했다.
- `dev8-v2/`: 승인된 기존 HTTP before39세션. `dev8-v1-diagnostics/`는 재사용한 원래 진단42개다. 계보가 다른 자료를 임의로 하나의 전후 비교로 합치지 않는다.
- `dev9/`: 항목별 기존 로컬/격리 PostgreSQL·Redis 검증과 SQL/엔티티/JWT/인덱스 수치.
- `regression/`: 기존 로컬 H2 651/651·harness16/16의 JUnit 결과. 로그·환경 속성을 제외한 testcase 결과를 보존했다.
- `ci/41743023340/`: 병합 커밋 `f772d11`의 기존 CI 결과. **651개 중1개 실패**이며 로컬 통과 결과와 구분한다. CI나 테스트를 새로 실행한 자료가 아니다.
- `status.json`: 완료/미실행 범위. QA9 전체 after·최종 혼합 부하 인수를 완료한 자료가 아니다.
- `manifest.json`: 원본 위치의 저장소 상대 경로, 원본 SHA256, 공개용 파일 SHA256, 변환·개인정보 제외 내역.

각 HTTP 세션의 `measurement-points.jsonl.gz`는 기존 k6 JSONL에서 모든 수치 Point를 보존하고 태그를 선별한 압축 자료다. URL은 호스트를 제외한 경로/쿼리만 보관했다. `gzip.open(..., 'rt', encoding='utf-8')`로 읽을 수 있으며 각 줄은 JSON이다. 요약·측정 조건·예열·같은 앱 식별자도 함께 보관했다.

비밀번호·토큰·개인키·계정 파일·DB dump·전체 콘솔 로그·개인 PC 이름·사설 호스트 주소는 포함하지 않았다. 원래 전체 수집본은 측정 홈서버와 로컬 `build/` 보관본이며, 이 폴더는 출처 해시를 가진 공유용 선별본이다. `manifest.json`에 없는 비교표·그림은 포함된 계측자료에서 재생성한 산출물이다.

## 다른 PC에서 사용

저장소 루트에서, 데이터 비교표만 다시 만들려면 Python 표준 라이브러리만 있으면 된다.

```sh
python improvement/code-optimization/scripts/render_results.py --data-only
```

PNG·SVG도 재생성하려면 Python3.12 가상환경에 그래프 의존성을 설치한 뒤 실행한다.

```sh
python -m pip install -r improvement/code-optimization/requirements-render.txt
python improvement/code-optimization/scripts/render_results.py
```

이미 생성된 그림은 [figures](../figures/)에 있으므로 설치 없이 사용할 수도 있다. 영어 라벨과 Matplotlib 번들 폰트를 사용해 OS별 한글 폰트 의존성을 피했다. 모든 계산은 이 폴더의 기존 파일만 읽고 서버·네트워크·테스트 프로세스에 접근하지 않는다.

## 포트폴리오 인용 범위

추천의 HTTP p95는3회 중앙값, 장바구니 HTTP는 완료된 단회 쌍이다. JDBC 호출 수·SQL 수·엔티티 수·JWT 검증 횟수는 서로 다른 지표이며 CPU/전체 HTTP 지연 개선율로 바꾸지 않는다. 최초 장바구니 동시 생성의 SQL 증가도 그대로 보존했다. 스케줄러의 고정45초 지연 조건 after가 없어 그 개선율은 만들지 않는다.

기존 약27% 주문 인덱스 성과는 이미 [1차개선](../../1차개선/README.md)에 데이터·그림·생성 코드가 보관돼 있다. 이번 수치와 환경·revision이 다른 독립 사례이며 합산하지 않는다.
