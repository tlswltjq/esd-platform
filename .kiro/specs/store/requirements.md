# 요구사항: Store (공개 상품 조회 사본·검색, 자동 동기화·대조·복구)

> 상태: 작성 중([결정 150](../../../docs/decisions.md#spec-foundation)·[결정 178](../../../docs/decisions.md#evaluation-findings-deferred)). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다. 코드가 이 문서와 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"에 있다.

## 개요

Store는 Catalog 공개 상품의 **조회용 사본**(`store_product`)을 자기 스키마 `stove_store`에 두고, 방문자에게 제목·장르 검색, 목록, 상세를 익명으로 준다. 사본의 정본은 Catalog다([P1.4-1](../platform/requirements.md), design "정본 소유"). Store는 상품 ID·가격·제목·판매 상태·노출 여부를 만들거나 다시 계산하지 않는다(원칙 ③).

사본은 두 경로로 바뀐다.

- 정상 경로: Catalog 공개 사본 이벤트 4개(`CatalogProductFirstPublished`·`CatalogProductReleaseReplaced`·`CatalogProductSaleStatusChanged`·`CatalogProductListingChanged`)를 받아 `api.listener`에서 한 내부 입력으로 합치고 `ApplyCatalogProduct` 하나로 반영한다([P3.5-6](../platform/requirements.md)·[P3.7-1](../platform/requirements.md), [결정 171](../../../docs/decisions.md#catalog-copy-events-by-fact)).
- 복구 경로: 시작 때와 주기마다 Catalog 내부 조회로 정본을 끝까지 읽어 누락·구버전·같은 버전 내용 차이를 자동으로 고친다([P3.7-2](../platform/requirements.md), [결정 66](../../../docs/decisions.md#store-automatic-reconciliation-first)·[결정 67](../../../docs/decisions.md#store-canonical-same-version-repair)).

Store는 업무 이벤트를 내지 않는다. 받은 이벤트는 Inbox 대신 `catalogVersion` 비교로 멱등하게 처리한다([P2.3-5](../platform/requirements.md)).

## 용어

- **공개 사본(`store_product`)**: Catalog 공개 상품 하나의 조회용 사본. 기본 키는 Catalog `productId`, `productCode`는 유일하다.
- **`catalogVersion`**: Catalog가 상품마다 매기는 단조 증가 정수. 사본의 신구는 이 값으로만 가린다. 시각으로 가리지 않는다([P1.3-4](../platform/requirements.md)·[P3.5-7](../platform/requirements.md)).
- **식별 값**: `productId`·`productCode`·`publishedAt`(최초 공개 시각). 한 상품에서 바뀌지 않는다.
- **공개 사본 이벤트**: 위 4개 이벤트. 본문은 모두 그 시점의 공개 사본 전체(`CatalogProductSnapshot`)와 `catalogVersion`이다. 이행 중에는 옛 `CatalogProductPublished`도 같은 뜻으로 받는다([P2.7-4](../platform/requirements.md)).
- **사본 입력**: `api.listener`의 Mapper나 정본 조회 adapter가 검증을 마친 뒤 core에 넘기는 Store 내부 값. 이벤트 봉투·공통 계약 record를 core에 넘기지 않는다(AGENTS.md 3절, [결정 78](../../../docs/decisions.md)).
- **정본 대조 회차**: Catalog 내부 목록을 첫 페이지부터 빈 페이지까지 읽고 상품마다 정본 상세와 사본을 맞추는 한 번의 실행. 결과는 `SUCCEEDED`·`PRODUCT_FAILURES`·`ABORTED` 중 하나다(design "상태 모델").
- **준비 상태**: 프로세스 메모리의 `NOT_READY`·`READY`. `READY` 전에는 업무 조회가 503이다.
- **상품 단위 실패**: 회차 안에서 상품 하나만 반영하지 못한 실패(식별 충돌, 정본 응답 계약 오류, Store가 모르는 장르 등). Catalog 접근 실패와 다르다.
- **AND 검색**: 장르를 여럿 고르면 고른 장르를 **모두** 가진 상품만 돌려준다([결정 75](../../../docs/decisions.md#store-multiple-genres-match-all)).
- **실패 보관 표**: 소비 재시도를 소진했거나 영구 실패한 레코드를 Store DB에 기한 없이 저장하고 같은 트랜잭션에서 (소비 그룹, 토픽, 키)를 정지하는 표([P2.6-4·5](../platform/requirements.md), [결정 160](../../../docs/decisions.md#dead-letter-store)). 표 구조와 회수 API는 공통 메시징 라이브러리가 정한다.

## 요구사항

### R1. 공개 상품 검색·목록 조회
**사용자 스토리:** 방문자로서, 공개된 게임을 제목과 장르로 찾고 목록을 보고 싶다. 그래야 원하는 상품을 발견할 수 있다.

#### 수용 기준
1. WHEN `GET /api/v1/storefront/products` 요청이 오면 THEN Store SHALL 로그인 없이 노출 상품(`listed=true`) 목록을 조회한다.
2. THE 기본값 SHALL q 없음, `sort=NEWEST`, `page=0`, `size=20`이고, IF `page < 0` 또는 `size`가 1~100 밖이면 THEN Store SHALL 400 `INVALID_REQUEST`로 거절한다.
3. THE q SHALL 앞뒤 공백을 지우고 100자 이하만 받는다. 빈 값은 전체 목록이다. IF 100자를 넘으면 THEN Store SHALL 400으로 거절한다.
4. THE 제목 검색 SHALL 대소문자를 구분하지 않는 부분 일치이고, 파라미터 바인딩과 LIKE 특수 문자(`%`·`_`·`!`) 이스케이프로 문자 그대로 찾는다.
5. WHEN `genres`가 둘 이상 오면 THEN Store SHALL 고른 장르를 모두 가진 상품만 돌려준다(AND, 결정 75).
6. IF `genres`가 없으면 THEN Store SHALL 장르 조건 없이 모든 노출 상품을 검색한다.
7. IF `genres`에 모르는 코드·중복·빈 값이 있거나 `genres` 파라미터가 비어 있으면 THEN Store SHALL API 경계에서 400 `INVALID_REQUEST`로 거절한다.
8. THE 장르 조건 SHALL 제목 조건·노출 조건(`listed=true`)과 함께 DB 페이지·총건수 계산 전에 적용한다([결정 51](../../../docs/decisions.md#independent-listing-with-sale-default)·결정 75).

### R2. 정렬과 페이지
**사용자 스토리:** 방문자로서, 최신순·가격순으로 정렬하고 페이지를 넘겨 보고 싶다. 그래야 많은 상품을 훑어볼 수 있다.

#### 수용 기준
1. THE Store SHALL `NEWEST`·`PRICE_ASC`·`PRICE_DESC` 정렬만 받고, IF 다른 값이 오면 THEN 400으로 거절한다.
2. WHERE `NEWEST` 정렬에서 THE Store SHALL 최초 공개 시각(`publishedAt`) 내림차순으로 정렬한다. 이 값은 Catalog가 찍은 시각을 표시 순서에만 쓰는 것이고 판정에 쓰지 않는다(원칙 ⑥).
3. WHERE `PRICE_ASC`·`PRICE_DESC` 정렬에서 THE Store SHALL 가격 오름차순·내림차순으로 정렬한다.
4. THE 마지막 정렬 키 SHALL 모두 `productId` 내림차순이다. 공개본 교체(재출시)는 최초 공개 시각을 바꾸지 않으므로 신작으로 올라오지 않는다.
5. THE 목록 응답 SHALL `content`·`page`·`size`·`totalElements`·`totalPages`를 담는다.

### R3. 상품 상세 조회
**사용자 스토리:** 방문자로서, 상품 코드로 지금 공개된 상세를 보고 싶다. 그래야 구매 전에 내용을 확인할 수 있다.

#### 수용 기준
1. WHEN `GET /api/v1/storefront/products/{productCode}` 요청이 오면 THEN Store SHALL 지금 반영된 공개 사본의 상세를 익명으로 준다.
2. THE 상세·목록의 공개 칸과 뜻 SHALL Catalog 공개 사본 계약(`CatalogProductSnapshot`)을 따르며, 내부 저장 경로·소유자 정보를 담지 않는다.
3. THE 가격 SHALL Catalog가 정한 KRW 정가 사본이다. Store는 주문 금액이나 구매 가능 여부를 판정하지 않는다. 주문은 Catalog 정본으로 판정한다(design 흐름 1 S8 "늦은 도착" 행).
4. THE 응답 SHALL `saleStatus`·`catalogVersion`·`listed`를 함께 준다.
5. WHERE `READY` 상태에서 없는 상품 코드면 THE Store SHALL 404 `NOT_FOUND`를 준다.
6. IF 사본의 `listed=false`이면 THEN Store SHALL 상세를 404로 거절한다([결정 52](../../../docs/decisions.md#public-detail-b-with-c-extension)). 노출 여부는 Catalog가 정한 값을 그대로 쓰고 Store가 판매 상태로 다시 계산하지 않는다(원칙 ③).

### R4. 장르 기준 목록 조회
**사용자 스토리:** 방문자로서, 고를 수 있는 장르 목록을 보고 싶다. 그래야 장르로 검색 조건을 고를 수 있다.

#### 수용 기준
1. WHEN `GET /api/v1/storefront/genres` 요청이 오면 THEN Store SHALL 익명으로 장르 코드·표시명·선택 가능 여부를 준다. 이 응답은 준비 상태와 무관하다.
2. THE 장르 기준 목록 SHALL 공통 코드의 `GenreCatalog` 값 객체를 따르며, 실행 중 다른 서비스에서 받아 오지 않는다([결정 71](../../../docs/decisions.md#genres-managed-through-code-deployment)·[결정 72](../../../docs/decisions.md#initial-ten-product-genres)).
3. WHERE 선택이 중지된 알려진 코드에서 THE Store SHALL 기존 상품의 표시·검색에 그 코드를 계속 쓴다([결정 73](../../../docs/decisions.md#retired-genres-preserve-existing-evidence)).

### R5. 공개 사본 이벤트 수신과 사본 반영
**사용자 스토리:** Store로서, Catalog의 공개 사본 변경을 받아 사본을 최신으로 두고 싶다. 그래야 방문자가 최신 공개본을 본다.

이벤트의 사실·키·순서·소비 그룹은 플랫폼 정본이다: design "목표 행" 공개 사본 4개(분리안 SP-4), 이벤트 목록 #15(이행 중 옛 타입), [P3.7-1](../platform/requirements.md).

#### 수용 기준
1. WHEN `stove.catalog.v1`에서 공개 사본 이벤트 4개 중 하나를 받으면 THEN Store SHALL 소비 그룹 `store-catalog-publication`, 최초 소비 위치 earliest로 처리하고, `api.listener`에서 네 타입을 같은 사본 입력으로 바꿔 `ApplyCatalogProduct` 하나로 반영한다(결정 171, P2.1-6).
2. WHEN 더 높은 `catalogVersion`이 오면 THEN Store SHALL 공개 칸 전체를 교체한다.
3. WHEN 더 낮은 `catalogVersion`(중복·역순·재투입 포함)이 오면 THEN Store SHALL 지금 사본을 그대로 두고 성공 처리한다.
4. WHEN 같은 `catalogVersion`·같은 공개 내용이 오면 THEN Store SHALL 바꾸지 않고 성공 처리한다.
5. IF 같은 `catalogVersion`인데 공개 내용이 다르면 THEN Store SHALL 덮어쓰지 않고 영구 실패로 처리한다. 이 차이는 정본 대조(R7.4)만 고친다(결정 67).
6. IF 식별 값(`productId`·`productCode`·`publishedAt`)이 기존 사본과 다르거나, 같은 `productCode`가 다른 `productId`의 사본에 이미 있으면 THEN Store SHALL 영구 실패로 처리한다.
7. WHERE 콘텐츠 버전이 같고 판매 상태·노출만 바뀐 더 높은 `catalogVersion`에서 THE Store SHALL 그 변경을 반영한다.
8. IF 구독 토픽에 처리하지 않는 `eventType`이 오면 THEN Store SHALL 부수효과 없이 건너뛰고 (소비 그룹, 타입) 지표를 올린다([P2.3-7](../platform/requirements.md)).
9. THE Store SHALL Inbox(`processed_event`) 없이 버전 규칙으로 멱등하게 처리하고, 처리 트랜잭션이 커밋된 뒤 오프셋을 커밋한다([P2.3-5·6](../platform/requirements.md)). 실패한 레코드는 R5.16·R10으로 보낸다.
10. WHILE 결정 171의 이행이 끝나지 않은 동안 THE Store SHALL 옛 `CatalogProductPublished`도 같은 사본 입력으로 받는다. 옛 타입 처리는 그 타입이 모든 `outbox_event`와 실패 보관 표에서 사라진 뒤에만 지운다([P2.7-4](../platform/requirements.md)).
11. THE Mapper SHALL (`eventType`, `schemaVersion`)으로 변환 규칙을 고르고, 헤더가 없는 옛 메시지는 `schemaVersion=1`로 읽는다([P2.1-1](../platform/requirements.md)·[P2.7-2](../platform/requirements.md), [결정 170](../../../docs/decisions.md#schema-version-header)).
12. WHEN Mapper가 변환하면 THEN Mapper SHALL 봉투·본문 `eventId` 일치, 봉투·본문 `eventType` 일치, `occurredAt` 있음, Kafka 키 = 본문 `productCode`, 양수 `productId`·`contentVersion`, `catalogVersion >= contentVersion`, 알려진 `saleStatus`, 명시적 `listed`, 필수 공개 칸·길이, `KRW`, 장르 1~3개(기준 목록 안, 중복 없음)를 검증한 뒤에만 사본 입력을 만든다. 없는 칸을 기본값으로 채우지 않는다(원칙 ①, 플랫폼 T24).
13. WHEN 없는 상품의 이벤트가 오면 THEN Store SHALL 사본을 만든다.
14. IF 더 높은 `catalogVersion`인데 `contentVersion`이 지금 사본보다 낮거나, `contentVersion`이 같은데 콘텐츠 칸(제목·소개·본문·썸네일·영상·가격·통화·빌드 버전·장르)이 다르면 THEN Store SHALL 영구 실패로 처리한다.
15. WHEN 같은 상품의 최초 삽입이 정본 대조와 겹쳐 유일 키 충돌·교착이 나면 THEN Store SHALL 새 트랜잭션으로 최대 3회 다시 시도한다. 교착은 JPA가 감싼 예외의 원인 사슬로 판단한다(D-047).
16. IF 실패가 영구 실패(R5.5·R5.6·R5.12·R5.14)이면 THEN Store SHALL 재시도 없이 R10의 실패 보관으로 보낸다. 재시도 가능 실패는 공통 블로킹 재시도 뒤 R10으로 보낸다([P2.6-1·3](../platform/requirements.md)).

### R6. 시작 시 초기 동기화와 준비 상태
**사용자 스토리:** 운영자로서, Store가 시작하면 공개 상품 사본이 사람 개입 없이 채워지길 원한다. 그래야 배포·재시작이 단순하다.

#### 수용 기준
1. WHEN Store가 시작하면 THEN Store SHALL 준비 상태 `NOT_READY`로 시작하고 곧바로 정본 대조 회차(R7)를 돌려 초기 사본을 만든다.
2. THE 정본 조회 SHALL Catalog `GET /internal/v1/store-products`(페이지)·`GET /internal/v1/store-products/{code}`를 Store 전용 서비스 키 헤더로 부른다. 키는 이 두 GET에만 싣고, 키가 없으면 공개 API로 우회하지 않는다(design "동기 의존" Store → Catalog 행, [P1.2-3](../platform/requirements.md)).
3. WHILE 정본 HTTP 호출 중 THE Store SHALL Store DB 트랜잭션을 열지 않는다([P1.2-4](../platform/requirements.md)).
4. WHEN 이벤트와 초기 동기화가 같은 상품에서 겹치면 THEN Store SHALL 상품 행 잠금 뒤 `catalogVersion` 비교로 최신 사본을 남긴다.
5. WHEN 회차가 `SUCCEEDED`로 끝나면 THEN Store SHALL 준비 상태를 `READY`로 연다. 빈 Catalog를 끝까지 읽은 것도 `SUCCEEDED`다. 회차가 `PRODUCT_FAILURES`로 끝났을 때 준비 상태를 열지는 `[결정 필요 M-SO-2]`이고, 정해지기 전에는 지금 규칙(열지 않음)을 따른다.
6. WHILE `NOT_READY`인 동안 THE Store SHALL 상품 검색·상세 조회에 503 `STORE_NOT_READY`를 준다. 장르 조회·이벤트 반영은 준비 상태와 무관하다.
7. IF 초기 회차가 `SUCCEEDED`가 아니면 THEN Store SHALL `NOT_READY`를 유지하고 재시도 간격 뒤 자동으로 다시 돈다. 원인이 사라지면 재시작 없이 열린다.
8. WHEN Store가 재시작하면 THEN Store SHALL `NOT_READY`에서 전체 회차를 처음부터 다시 돌고, 저장한 사본은 지우지 않는다(design 흐름 1 S8 "상대 모듈 중단" 행).
9. WHILE `READY`인 동안 THE Store SHALL 프로세스가 끝날 때까지 `NOT_READY`로 돌아가지 않는다.

### R7. 주기적 정본 대조와 자동 교정
**사용자 스토리:** 운영자로서, 정상 운영 중 생긴 누락·구버전·같은 버전 내용 차이가 자동으로 고쳐지길 원한다. 그래야 불가피한 예외에만 개입한다.

#### 수용 기준
1. WHILE Store가 실행 중인 동안 THE Store SHALL 정본 대조 회차를 반복한다. 첫 회차가 초기 동기화(R6)이고, `READY` 뒤에도 같은 회차를 주기로 돈다.
2. THE 다음 회차 기한 SHALL Store 자기 시계로 정한다: `SUCCEEDED` 뒤 정상 간격(기본 1분), `ABORTED` 뒤 재시도 간격(기본 5초). `PRODUCT_FAILURES` 뒤 간격은 `[결정 필요 M-SO-2]`이고 정해지기 전에는 재시도 간격이다. 이 값은 설정으로 바꾸는 운영 기본값이며 갱신 지연 SLA가 아니다(결정 67).
3. WHEN 상품을 맞추면 THEN Store SHALL 결과를 다섯 가지로 나눈다: 사본 없음 → `INSERTED`, 정본이 더 높은 버전 → `UPDATED`, 같은 버전·내용 차이 → `REPAIRED`, 같은 버전·같은 내용 → `UNCHANGED`, 사본이 더 높은 버전 → `STALE`. 바꾼 세 결과는 R8.1로 기록한다.
4. WHEN 같은 `catalogVersion`의 내용 차이가 확인되면 THEN Store SHALL 직접 조회해 검증한 정본으로 사본을 고친다. 이벤트를 교정 입력으로 쓰지 않는다(결정 67 A).
5. IF HTTP 응답을 기다리는 사이 더 높은 버전이 이벤트로 반영됐으면 THEN Store SHALL 행 잠금 뒤 확인한 최신 사본을 지키고 `STALE`로 센다.
6. THE Store SHALL 식별 값 충돌이나 정본 응답 계약 오류를 자동으로 덮어쓰지 않는다. 사본 삭제·버전 낮추기를 하지 않는다(결정 67). 실패 보관 레코드의 재투입은 ADMIN만 한다(R10.3).
7. WHILE 회차가 실패하는 동안 THE Store SHALL 이미 저장한 사본으로 조회를 계속 준다. 조회마다 Catalog를 부르거나 실패 때 Catalog로 우회하지 않는다.
8. IF Catalog에 접근할 수 없으면(연결·읽기 시간 초과, 5xx, 429, 서비스 키 거절 401·403) 또는 목록 응답이 계약 밖이면 THEN Store SHALL 그 회차를 바로 멈추고 `ABORTED`로 끝낸다(design 흐름 1 S9 "상대 모듈 중단" 행).
9. IF 상품 하나의 상세가 계약 밖이거나 식별 충돌이면 THEN Store SHALL 그 상품을 상품 단위 실패로 세고 나머지 상품을 계속 맞춘 뒤, 실패가 하나라도 있으면 회차를 `PRODUCT_FAILURES`로 끝낸다.
10. WHEN 정본 목록에 없는 상품의 사본이 Store에 있으면 THEN Store SHALL 그 사본을 지우지 않고 "Store에만 있는 사본"으로 세어 지표로 낸다(RI-5 역방향).
11. WHILE 다른 회차가 실행 중이면 THE Store SHALL 새 회차를 시작하지 않고 실행 중인 회차 하나로 합친다. 프로세스 사이 조율(전역 단일 스캔·임대)은 두지 않으며, 인스턴스가 여럿이면 상품 행 잠금과 버전 규칙이 사본을 지킨다.
12. THE 회차 SHALL 숨긴 상품을 포함한 정본 목록을 첫 페이지부터 빈 페이지까지 읽고, 상품마다 정본 상세를 읽어 사본과 맞춘다.

### R8. 자동 복구 감사와 관측
**사용자 스토리:** 운영자로서, 자동 처리의 진행·실패 근거를 보고 싶다. 그래야 사람이 개입할 예외를 판단한다.

#### 수용 기준
1. WHEN 회차가 사본을 바꾸면(`INSERTED`·`UPDATED`·`REPAIRED`) THEN Store SHALL `store_reconciliation`에 변경 전후 JSON·정본을 읽은 Store 시각·기록 시각·결과를 사본 변경과 같은 트랜잭션으로 남긴다.
2. WHEN 결과가 `UNCHANGED`·`STALE`이면 THEN Store SHALL 감사 행을 만들지 않는다.
3. THE Store SHALL 회차 지표를 낸다: 준비 상태, 실행 중 여부, 결과별 회차 수(`SUCCEEDED`·`PRODUCT_FAILURES`·`ABORTED`), 마지막 `SUCCEEDED` 시각, 마지막으로 목록을 끝까지 읽은 시각, 마지막 회차의 읽은 수·바꾼 수·상품 단위 실패 수·`STALE` 수.
4. THE 지표 adapter SHALL core가 주는 상태 값만 받고, 서비스·프레임워크 객체 조립은 config가 한다.
5. THE Store SHALL 상품 단위 지표를 회차 결과와 분리해 낸다: 마지막 완주 회차에서 정본보다 뒤처져 있던 사본 수(`INSERTED`+`UPDATED`+`REPAIRED`), 상품 단위 실패 수(실패 유형별), 상품 단위 실패가 처음 관측된 뒤 지금까지 이어진 가장 긴 시간(Store 시계 기준), Store에만 있는 사본 수([P3.7-4](../platform/requirements.md), [결정 158](../../../docs/decisions.md#forward-gap-counting), MC-SO1).
6. THE 상품 단위 지연·실패의 경보 기준 시간 SHALL 실험으로 정상 지연을 잰 뒤 정하고, 그 전에는 게이지만 낸다([결정 159](../../../docs/decisions.md#forward-gap-threshold)). 회차가 도는지 보는 경보(초기화 10분·성공 끊김 5분)는 [결정 85](../../../docs/decisions.md) 값을 그대로 쓰되, "Catalog 접근 불가로 회차가 멈춤"과 "상품 하나의 영구 실패"를 다른 경보 조건으로 나눈다(MC-SO1).
7. THE 지연 측정 SHALL Catalog가 찍은 시각(`updatedAt`·`occurredAt`)과 Store 시계를 비교하지 않는다. 뒤처짐은 `catalogVersion` 비교로, 지속 시간은 Store 시계의 관측 시각끼리로만 잰다(원칙 ⑥, [P2.1-3](../platform/requirements.md)).
8. THE Store SHALL 회차·상품 실패 경고 로그에 상품 코드(형식이 맞을 때만)와 실패 유형만 남기고, 응답 본문·URL·서비스 키를 남기지 않는다.

### R9. 최소 보안과 데이터 소유
**사용자 스토리:** 운영자로서, Store가 조회 경로만 열고 업무 쓰기 API를 두지 않길 원한다. 그래야 공개 조회 경계가 단순하다.

#### 수용 기준
1. THE Store 상품 목록·상세·장르 조회 SHALL 익명 GET으로 허용하고, Store SHALL 업무 쓰기 API를 두지 않는다. 운영 API는 공통 실패 보관 재투입(R10)뿐이고 평소 꺼 두며, 켜도 ADMIN만 쓰고 Gateway에 노출하지 않는다([결정 87](../../../docs/decisions.md)). 관리자 수동 재동기화 API는 두지 않는다(결정 66).
2. THE Store 보안 설정 SHALL 위 조회 경로, health·API 문서 경로, 지표 수집 경로, (켰을 때) 운영 API만 허용하고 나머지는 닫는다.
3. THE Store SHALL 스키마 `stove_store`만 읽고 쓰며([P1.1-2](../platform/requirements.md)), 업무 표는 `store_product`·`store_product_genre`·`store_reconciliation`, 메시징 표는 공통 라이브러리의 실패 보관 표다.
4. THE Store SHALL 업무 이벤트를 내지 않으며 Outbox·Inbox(`processed_event`)·재발행 표를 두지 않는다. Elasticsearch·Redis 캐시·featured를 쓰지 않는다.

### R10. 소비 실패 보관과 재투입
**사용자 스토리:** 운영자로서, Store가 처리하지 못한 공개 사본 이벤트가 사라지지 않고 남아 원인을 보고 다시 처리할 수 있길 원한다.

공통 계약은 [P2.6-4·5](../platform/requirements.md)(결정 155·160)다. 아래는 Store가 그 계약을 지키는 방식이다.

#### 수용 기준
1. WHEN 공개 사본 이벤트가 재시도를 소진하거나 영구 실패하면 THEN Store SHALL 그 레코드(토픽·파티션·offset·키·원본 헤더·본문·실패 사유)를 Store DB의 실패 보관 표에 기한 없이 저장하고, 저장 1건부터 즉시 경보한다. 사본은 바꾸지 않는다.
2. THE 같은 트랜잭션의 (소비 그룹 `store-catalog-publication`, 토픽 `stove.catalog.v1`, 키 `productCode`) 정지 SHALL 결정 160대로 한다. 상태 사본 소비에서 키 정지를 어떻게 적용하고 언제 풀지는 `[결정 필요 M-SO-1]`이고, 정해지기 전에는 결정 160 그대로(ADMIN 재투입으로만 해제)다.
3. WHEN ADMIN이 재투입하면 THEN Store SHALL 보관 레코드를 키 단위로 원래 순서대로 같은 리스너 경로(`*Mapper.from` → `ApplyCatalogProduct`)로 다시 처리하고 결과를 표에 남긴다. 회수 정렬 기준은 공통 메시징 설계를 따른다(1회차 지적 B1, 판정 전).
4. THE 재투입 SHALL 버전 규칙(R5.2~R5.7·R5.13·R5.14)을 그대로 거친다. 이미 정본 대조로 더 높은 버전이 된 사본에 낮은 버전 레코드를 재투입하면 무변화로 끝난다.
5. WHILE 키가 정지된 동안에도 THE Store SHALL 정본 대조(R7)로 그 상품의 사본을 계속 정본에 맞춘다. 대조는 소비 경로가 아니므로 키 정지의 대상이 아니다.

## 범위 밖(후속)

- Elasticsearch·Redis 캐시, 추천·인기 순위·개인화·형태소 검색, 대표 장르·장르 계층.
- 가격 범위·판매 상태 등 추가 검색 필터, featured·할인·쿠폰·구매 가능 여부 판정.
- 전역 단일 스캔·내구 커서/임대·다중 인스턴스 동기화 조율, 무중단 재색인, 대규모 성능 보장.
- 관리자 수동 재동기화 API를 정상 운영 경로로 두는 것(결정 66).
- 상품 단위 지연 경보의 기준 시간 — 실험 뒤(결정 159). 경보 전송 채널·대시보드·공유 환경 배포는 별도 운영 범위.
