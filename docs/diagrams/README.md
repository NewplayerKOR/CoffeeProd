# CoffeeProd 데이터 구조

[핵심 ERD SVG](../images/erd-core.svg) · [핵심 원본](erd-core.mmd) · [전체 ERD SVG](../images/erd.svg) · [전체 원본](erd.mmd)

README 본문은 구매·결제를 설명하는 핵심 9개 테이블과 그 사이의 실제 FK 9개를 보여준다. 전체 24개 테이블 ERD는 확대 링크로 제공한다.

핵심 범위는 member, coffee_profile, product, cart, cart_item, orders, order_item, payment, payment_operation이다. 핵심 그림 밖의 카테고리·가공 방식 FK와 기준정보, 리뷰·문의·통계는 전체 그림에 유지한다. 주문 시점의 가격은 order_item.order_price, 배송지는 orders.delivery_address, 결제 복구는 payment_operation을 통해 설명한다.

Flyway V1~V19를 기준으로 전체 24개 테이블과 실제 FK 27개를 한 장에 정리했다. PK·FK와 주요 컬럼을 표시하며, 일반 감사 시각·긴 본문·부가 메타데이터는 생략했다. 실제 데이터나 운영 DB 접속 정보는 포함하지 않는다.

이 그림은 V1~V19의 구조를 보존한 자료다. 이후 V20의 소셜 계정·인증 권한 테이블과 V21의 회원 토큰 버전 변경은 포함하지 않는다. 최신 구조는 [현재 migration](../../src/main/resources/db/migration)을 확인한다.

## 읽는 방법

- PK는 기본키, FK는 외래키, UK는 단일 컬럼 UNIQUE 제약이다. 복합 UNIQUE의 개별 컬럼에는 UK를 붙이지 않는다.
- 관계선의 원은 0, 막대는 1, 갈래는 여러 건을 뜻한다. 점선은 자식 PK에 부모 FK가 포함되지 않는 비식별 관계다.
- 필수 여부와 개수는 DB의 NOT NULL·UNIQUE 제약을 따른다. 예를 들어 회원당 장바구니는 최대 1개지만, DB는 cart.member_id의 NULL을 허용한다.
- FK가 없는 sales_statistics와 sales_rebuild_request도 포함한다. 날짜별 집계·재집계의 논리 관계를 FK처럼 연결하지 않는다.
- payment_operation.actor_id는 FK가 아니므로 회원과 연결하지 않는다. 주문당 작업 유형의 중복은 (order_id, operation_type) 복합 UNIQUE가 제한한다.

## 테이블 구성

| 영역 | 테이블 |
| --- | --- |
| 회원·장바구니 | member, address, cart, cart_item |
| 주문·결제 | orders, order_item, payment, payment_operation |
| 상품·커피 프로필 | category, product, coffee_profile, processing_method, coffee_profile_component |
| 향미·추출·품종 | flavor_note, brew_method, coffee_variety, coffee_profile_flavor_note, coffee_profile_brew_method, coffee_profile_variety |
| 회원 취향 | member_coffee_preference |
| 리뷰·문의 | reviews, product_qna |
| 통계 | sales_statistics, sales_rebuild_request |

## 기준과 갱신

- 작성일: 2026-11-16. 저장소의 [Flyway migration](../../src/main/resources/db/migration)과 JPA 엔티티를 대조한 물리 스키마 요약이다. 실행 중인 운영 DB를 조회한 결과와는 구분한다.
- V2의 필수 FK·결제 주문 UNIQUE, V5의 가공 방식 컬럼명, V6의 상품 프로필 FK, V8의 SKU·중량, V16의 카탈로그 키, V18의 결제 복구 구조를 반영했다. V17·V19는 인덱스 변경이며 테이블·FK 수를 바꾸지 않는다.
- 원본은 Mermaid ER 형식이며 Mermaid 12.3.0, ELK 레이아웃, classic 스타일로 SVG를 생성했다. 저장소의 애플리케이션 의존성에는 렌더러를 추가하지 않는다.
- 두 SVG는 HTML 라벨 대신 SVG 텍스트를 사용하고 흰 배경과 원본 크기를 명시했다. 핵심 ERD는 약 1414×839, 전체 ERD는 약 4792×2099이며 원본 확대 링크를 제공한다.
- 변경 시 migration과 엔티티에 맞춰 원본을 갱신하고 SVG를 다시 생성한다. 전체 테이블·PK·FK·관계선·선택성을 대조하고, 24개 테이블과 27개 FK 수는 스키마가 바뀌면 함께 갱신한다.
- README에는 핵심 SVG를 배치하고 전체 SVG는 확대 링크로 연결한다. 두 Mermaid 원본을 각각 관리하며 핵심 범위 밖의 FK를 연결하거나 장바구니와 주문 사이에 가상 관계를 추가하지 않는다. 상세 컬럼과 원문 정의는 전체 ERD 및 해당 migration을 확인한다.
