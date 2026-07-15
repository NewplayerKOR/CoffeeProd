# CoffeeProd 현실형 카탈로그 CSV

이 디렉터리는 카탈로그 기준정보, 커피 프로필, 블렌드 구성, 판매 SKU를 재현 가능하게 생성한다.

## 데이터 규모

- 커피 프로필 96개: 단일 원산지 72개, 블렌드 24개
- 판매 SKU 192개: 프로필별 200g/500g 2종
- 단일 원산지 원형 36개, 블렌드 레시피 12개를 로스트별로 확장
- 향미, 추출법, 품종, 블렌드 구성요소 연결 데이터 포함

모든 내용은 포트폴리오와 부하 검증을 위한 합성 데이터다. 실존 농장 재고, 실제 판매가, 공식 커핑 점수를 의미하지 않는다.

## 생성과 정적 검증

Git Bash:

```bash
node scripts/seed/catalog/generate_catalog.mjs
node scripts/seed/catalog/generate_catalog.mjs --check
```

생성기는 `scripts/seed/catalog/csv`에 UTF-8 CSV를 덮어쓰고 즉시 정적 검증한다. `--check`는 기존 CSV를 변경하지 않고 다음 항목을 확인한다.

- 기준정보 코드, 프로필 키, SKU 중복
- 모든 연결 CSV의 참조 존재 여부
- 연결 데이터의 `display_order` 연속성
- 단일 원산지와 블렌드 필드 규칙
- 블렌드 구성요소 2~5개와 비율 합계 100
- 가격, 중량, 재고, 판매 상태, 로스트 단계 범위

## 파일과 적재 순서

1. `csv/categories.csv`
2. `csv/processing_methods.csv`
3. `csv/flavor_notes.csv`
4. `csv/brew_methods.csv`
5. `csv/coffee_varieties.csv`
6. `csv/coffee_profiles.csv`
7. `csv/coffee_profile_components.csv`
8. `csv/profile_flavor_notes.csv`
9. `csv/profile_brew_methods.csv`
10. `csv/profile_varieties.csv`
11. `csv/products.csv`

CSV의 `profile_key`, `category_code`, 각 기준정보 `code`는 적재 단계에서 DB의 PK로 변환할 소스 키다. 운영 테이블에는 `profile_key`와 `category_code` 컬럼이 없으므로 직접 `COPY`하지 않는다. 다음 작업에서 staging table과 트랜잭션 기반 upsert/import SQL을 작성한 뒤 적재한다.

`image_url`은 `/images/catalog/{profile-key}.webp` 형식의 계획 경로다. 이미지 에셋이 추가되기 전에는 프론트에서 대체 이미지를 표시해야 한다.
