# 설계: Store

> 목표 계약이다. 코드가 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"(`MC-SO*`·`SO-T*`)에 있다. 이벤트의 사실·키·순서·소비 그룹, 흐름, 모듈 간 정합 조건은 [플랫폼 설계](../platform/design.md)가 정본이고 여기서는 링크한다. 경로 줄임 `store:` = `apps/store/src/main/java/com/stove/store/`, `store/db/Vn` = `apps/store/src/main/resources/db/migration/Vn__*.sql`.

## 아키텍처 위치

- 포트 8087, MySQL 스키마 `stove_store`([P1.1-2](../platform/requirements.md)). Gateway가 공개 GET `/api/v1/storefront/**`를 Store로 라우팅한다(`apps/gateway/src/main/resources/application.yml:102`).
- 패키지: `core/{domain, port, service}` + `api/{controller, listener, scheduler}` + `adapter/` + `config/`. ArchUnit `StoreArchitectureTest`와 공통 `ModulePackageRules`가 지킨다([P1.1-1](../platform/requirements.md)·[P1.5](../platform/requirements.md)).
- 동기 의존: Store → Catalog 하나(design "동기 의존" Store 행). Store를 부르는 업무 모듈은 없다(Gateway 라우팅만).
- 비동기 의존: Catalog 공개 사본 이벤트 → Store, 단방향(design "비동기 의존" 단방향 행). 응답 사실은 없고 Store가 정본 대조로 차이를 센다(design "정방향 응답 쌍" 마지막 행, [결정 158](../../../docs/decisions.md#forward-gap-counting)).
- 의존 라이브러리: `common:jpa`, `common:kafka`(계약·봉투·소비 재시도), 공통 실패 보관 표·재투입 API(결정 160, 공통 메시징 설계). Outbox·Inbox·재발행은 쓰지 않는다.

| 층 | Store의 부품 | 하는 일 |
|---|---|---|
| api.controller | `StoreController`, `GenreController`, DTO `StoreProductDetail`·`StoreProductSummary`·`StoreProductPage` | 공개 GET 3개, 입력 경계 검증(빈 `genres` 400) |
| api.listener | `CatalogEventListener`, `CatalogProductMapper` | 공개 사본 이벤트 4개(+이행 중 옛 타입)를 (`eventType`, `schemaVersion`)으로 골라 검증 → 사본 입력 |
| api.scheduler | `CatalogSynchronizationScheduler` | 1초 주기로 `SynchronizeCatalogProducts.poll()` |
| core.service | `ApplyCatalogProduct`, `ReconcileCatalogProduct`, `SynchronizeCatalogProducts`, `SearchStoreProducts`, `ReadStoreProduct` | 사본 반영(이벤트), 상품 하나 정본 교정, 회차·준비 상태, 검색, 상세 |
| core.domain | `StoreProduct`, `StoreReconciliation`, `StoreProductValues`, `ProductPublicAccess`, 상태 enum(아래) | 버전 규칙·식별 불변·노출 규칙 |
| core.port | `CatalogProducts` | 정본 목록 페이지·상세 조회 계약 |
| adapter | `CatalogHttpClient`, `StoreSynchronizationMetrics` | HTTP 구현·응답 검증·오류 분류, Micrometer 지표 |
| config | `CatalogClientConfiguration`, `CatalogSynchronizationConfiguration`, `SecurityConfig` | 주소·키·시간 제한·간격 바인딩과 조립, 보안 |

## 데이터 모델

### `store_product` (공개 사본, `store/db/V1`·`V2`·`V3`)

| 칸 | 형식 | 뜻 | 제약(목표) |
|---|---|---|---|
| `product_id` | BIGINT PK | Catalog 상품 ID | PK. 목표 `CHECK (product_id > 0)` (SO-T11) |
| `product_code` | VARCHAR(50) | Catalog 상품 코드 | `uk_store_product_code` |
| `title`·`short_description`·`thumbnail_url`·`content`·`video_url` | 문자열 | 공개 콘텐츠 | NOT NULL(`video_url`만 NULL 가능) |
| `price` | BIGINT | KRW 정가 사본(정수 최소 단위) | 목표 `CHECK (price >= 0)` (SO-T11) |
| `currency` | VARCHAR(3) | 통화 | 목표 `CHECK (currency = 'KRW')` (SO-T11) |
| `published_at` | DATETIME(6) | 최초 공개 시각(Catalog 값, 식별 값) | NOT NULL, 표시 정렬에만 씀 |
| `content_version` | BIGINT | 공개본 콘텐츠 버전 | 목표 `CHECK (content_version >= 1)` (SO-T11) |
| `build_version` | VARCHAR(100) | 공개 빌드 표시 버전 | NOT NULL |
| `updated_at` | DATETIME(6) | Catalog가 찍은 공개 사본 변경 시각(값으로만 저장) | NOT NULL. 판정에 쓰지 않음 |
| `sale_status` | VARCHAR(20) | Catalog 판매 상태 사본 `CatalogSaleStatus` | `ck_store_sale_status IN ('ON_SALE','STOPPED','BLOCKED')` |
| `catalog_version` | BIGINT | 사본 버전 | `ck_store_catalog_version CHECK (catalog_version >= content_version)` |
| `listed` | BOOLEAN | Catalog가 정한 최종 노출 여부 사본 | `ck_store_listed` |

색인: `idx_store_published(published_at, product_id)`, `idx_store_price(price, product_id)`, `idx_store_listing_newest(listed, published_at, product_id)`.

`sale_status`와 `listed`는 **Store의 상태가 아니라 Catalog 값의 사본**이다. Store는 이 값을 스스로 전이시키지 않고, `catalog_version`이 오를 때 공개 칸 전체와 함께 바꾼다. 노출 규칙(AUTO는 ON_SALE만 노출, 결정 51)은 Catalog가 계산해 `listed`로 주며 Store는 다시 계산하지 않는다(원칙 ③).

### `store_product_genre` (`store/db/V5`)

`(product_id, genre_code)` PK, `fk_store_genre_product` → `store_product(product_id)` `ON DELETE CASCADE`, `idx_store_genre_code(genre_code, product_id)`. 상품당 1~3개는 DB로 표현할 수 없어 `StoreProductValues.requireSnapshot`이 지킨다. 장르는 순서 없는 집합이며 정렬한 목록으로 비교한다(결정 70·75).

### `store_reconciliation` (정본 교정 감사, `store/db/V4`)

| 칸 | 뜻 | 제약 |
|---|---|---|
| `id` | 자동 증가 | PK |
| `product_id`·`product_code`·`catalog_version` | 교정한 상품과 정본 버전 | NOT NULL, `idx_store_reconciliation_product(product_id, id)` |
| `action` | `ReconciliationAction` | `ck_store_reconciliation_action IN ('INSERTED','UPDATED','REPAIRED')` |
| `before_snapshot` | 교정 전 사본 JSON | `INSERTED`이면 NULL. 목표 `CHECK ((action = 'INSERTED') = (before_snapshot IS NULL))` (SO-T11) |
| `after_snapshot` | 교정 뒤 사본 JSON | NOT NULL |
| `source_read_at` | Store가 정본 상세를 읽은 Store 시각 | NOT NULL |
| `recorded_at` | 기록한 Store 시각 | NOT NULL |

추가만 하는 표다. 수정·삭제 경로를 두지 않는다.

### 실패 보관 표 (목표, 결정 160)

공통 메시징 라이브러리가 표·에러 핸들러·회수 API를 자동 구성한다. Store는 그 Flyway 이행을 `stove_store`에 둔다. 칸(토픽·파티션·offset·키·원본 헤더·본문·실패 사유·정지 여부·재투입 결과)은 공통 설계가 정한다([P2.6-4·5](../platform/requirements.md)). Store는 Inbox가 없으므로 이 표만 둔다(SO-T6, notes F).

## 상태 모델

Store가 스스로 전이시키는 상태는 다섯 가지다. 정본(Catalog) 값의 사본(`sale_status`·`listed`)은 상태가 아니다(위 데이터 모델).

| 대상 | 자리 | enum 값 | 저장 |
|---|---|---|---|
| 공개 사본 하나 | `store_product` 행 | 존재 여부 + `catalog_version`(단조 증가 정수) | DB |
| 준비 상태 | 프로세스 하나 | `StoreReadiness {NOT_READY, READY}` | 메모리 |
| 동기화 작업 단계 | 프로세스 하나 | `SyncPhase {WAITING, RUNNING}` | 메모리 |
| 대조 회차 | 회차 하나 | `ReconciliationRunOutcome {RUNNING, SUCCEEDED, PRODUCT_FAILURES, ABORTED}` | 메모리(마지막 회차) |
| 실패 보관 키 | (그룹, 토픽, `productCode`) | 공통 설계의 정상·정지 | DB(공통 표) |

표 읽는 법: 모든 칸은 `허용 → 다음 상태` / `무변화` / `거절(오류 코드 또는 실패 분류)` 중 하나다. `—`는 빈칸이 아니라 "그 행의 조건이 그 열의 트리거에서는 생기지 않는 쌍"이다(예: 상세 조회에는 이벤트 버전 조건이 없다, 실행 중인 회차가 없으면 회차 종료가 생기지 않는다).

상품 하나의 대조 결과 `ReconciliationResult {INSERTED, UPDATED, REPAIRED, UNCHANGED, STALE}`는 상태가 아니라 한 번의 판정 결과다. `store_reconciliation.action`은 그중 바꾼 세 값만 저장한다.

지금 코드는 준비·단계·회차 결과를 `CatalogSyncStatus(boolean ready, boolean running, …, String failureType)`의 boolean·nullable 조합으로 표현한다(원칙 ④ 위반, SO-T7).

### 1. 공개 사본 `store_product` (상품 하나)

트리거 기호

- **E** = 공개 사본 이벤트 4개(이행 중 옛 `CatalogProductPublished` 포함). 반영은 `ApplyCatalogProduct.execute`. ADMIN 재투입(R10.3)도 같은 경로라 같은 행을 따른다.
- **R** = 정본 대조의 상품 하나 교정(`ReconcileCatalogProduct.execute`, 검증한 정본 `CanonicalProduct`).
- **Q** = 공개 상세 조회(`ReadStoreProduct`), **L** = 목록 검색(`SearchStoreProducts`). 준비 상태 `NOT_READY`면 둘 다 503(표 2)이 먼저다.
- 입력 비교의 v = 입력 `catalogVersion`, s = 사본 `catalog_version`, c = 사본 `content_version`.
- "영구 실패" = Kafka 경로는 재시도 없이 실패 보관(R10), 대조 경로는 상품 단위 실패(표 4). 어느 경우든 사본과 감사 행은 바뀌지 않는다(트랜잭션 롤백).

#### 사본 없음

| 트리거 · 조건 | E (이벤트·재투입) | R (정본 대조) | Q · L |
|---|---|---|---|
| Mapper/정본 검증 실패(필수 칸·형식·장르·`catalogVersion < contentVersion`·키 ≠ 코드·요청 코드 ≠ 정본 코드) | 거절 → 영구 실패 | 거절 → 상품 단위 실패 | — (행 없음) |
| 같은 `productCode`가 다른 `productId`의 사본에 있음 | 거절 → 영구 실패(`CONFLICT`) | 거절 → 상품 단위 실패 | — |
| 같은 `productId`가 다른 트랜잭션에서 방금 삽입됨(유일 키 충돌·교착) | 새 트랜잭션 재시도(최대 3회) → "사본 있음" 표로 판정. 3회 실패면 재시도 가능 실패 | 같음. 3회 실패면 상품 단위 실패 | — |
| 정상 입력 | 허용 → 사본 있음(s = v) | 허용 → 사본 있음(s = v), 결과 `INSERTED`, 감사 행 | — |
| Q 상세 조회 | — | — | 거절 → 404 `NOT_FOUND` |
| L 목록 검색 | — | — | 무변화(결과에 없음) |

#### 사본 있음 (s, c)

| 트리거 · 조건 | E (이벤트·재투입) | R (정본 대조) |
|---|---|---|
| 검증 실패(위와 같음) | 거절 → 영구 실패 | 거절 → 상품 단위 실패 |
| 식별 값 불일치(`productId`·`productCode`·`publishedAt` 중 하나라도 다름) | 거절 → 영구 실패(`CONFLICT`). 버전과 무관하게 먼저 판정 | 거절 → 상품 단위 실패(`CONFLICT`) |
| v < s | 무변화(멱등) | 무변화, 결과 `STALE` |
| v = s, 공개 내용 같음 | 무변화(멱등) | 무변화, 결과 `UNCHANGED` |
| v = s, 공개 내용 다름 | 거절 → 영구 실패(`CONFLICT`). 덮어쓰지 않음 | 허용 → 정본으로 교체(s 그대로), 결과 `REPAIRED`, 감사 행(결정 67) |
| v > s, 입력 `contentVersion` < c | 거절 → 영구 실패(`CONFLICT`) | 거절 → 상품 단위 실패(`CONFLICT`) |
| v > s, 입력 `contentVersion` = c, 콘텐츠 칸 다름 | 거절 → 영구 실패(`CONFLICT`) | 거절 → 상품 단위 실패(`CONFLICT`) |
| v > s, 그 밖(콘텐츠 교체 또는 판매·노출만 변경) | 허용 → 공개 칸 전체 교체(s = v) | 허용 → 교체(s = v), 결과 `UPDATED`, 감사 행 |

| 트리거 · 조건 | Q (상세) | L (목록) |
|---|---|---|
| `listed = true` | 허용 → 200 사본 상세(무변화) | 허용 → 조건이 맞으면 결과에 포함(무변화) |
| `listed = false` | 거절 → 404 `NOT_FOUND`(결정 52) | 무변화(결과에서 빠짐, 페이지·총건수 전에 거름) |

삭제·버전 낮추기 트리거는 없다. 운영자 명령도 사본을 직접 바꾸지 않는다(R7.6·R9.1). 콘텐츠 칸 = 제목·소개·썸네일·가격·통화·본문·영상·빌드 버전·장르(`StoreProduct.sameContent`). 공개 내용 = 콘텐츠 칸 + `contentVersion`·`updatedAt`·`saleStatus`·`catalogVersion`·`listed`·식별 값(`CatalogProductSnapshot` 전체).

### 2. 준비 상태 `StoreReadiness` (프로세스 하나, 메모리)

| 상태 \ 트리거 | 회차 `SUCCEEDED` | 회차 `PRODUCT_FAILURES` | 회차 `ABORTED` | 상품 검색·상세(Q·L) | 장르 조회 | 공개 사본 이벤트(E) | 프로세스 시작 |
|---|---|---|---|---|---|---|---|
| (시작 전) | — | — | — | — | — | — | 허용 → `NOT_READY` |
| `NOT_READY` | 허용 → `READY` | 무변화(`NOT_READY` 유지) — 지금 규칙, `[결정 필요 M-SO-2]` | 무변화 | 거절 → 503 `STORE_NOT_READY` | 허용(준비와 무관) | 허용(표 1대로 반영, 준비와 무관) | — (이미 시작) |
| `READY` | 무변화 | 무변화 | 무변화(저장한 사본으로 계속 조회) | 허용(표 1의 Q·L) | 허용 | 허용 | — |

`READY` → `NOT_READY` 전이는 없다(R6.9). 재시작하면 새 프로세스가 `NOT_READY`로 시작하고 DB 사본은 그대로다(R6.8).

### 3. 동기화 작업 단계 `SyncPhase` (프로세스 하나, 메모리)

`nextAttemptAt`은 Store 시계 값이다(한 시계 안의 비교, 원칙 ⑥ 대상 아님).

| 상태 \ 트리거 | 1초 주기 확인(기한 전) | 1초 주기 확인(기한 도래) | 회차 실행 요청이 겹침 | 회차 종료 `SUCCEEDED` | 회차 종료 `PRODUCT_FAILURES` | 회차 종료 `ABORTED` | 프로세스 시작 |
|---|---|---|---|---|---|---|---|
| (시작 전) | — | — | — | — | — | — | 허용 → `WAITING`(기한 = 지금) |
| `WAITING` | 무변화 | 허용 → `RUNNING`, 새 회차 시작(표 4) | — (실행 중 아님: 기한 도래와 같음) | — (회차 없음) | — | — | — |
| `RUNNING` | 무변화 | 무변화(실행 중 회차 하나로 합침) | 무변화(합침, 새 회차 없음) | 허용 → `WAITING`(기한 = 종료 + 정상 간격) | 허용 → `WAITING`(기한 = 종료 + 재시도 간격 — 지금 규칙, `[결정 필요 M-SO-2]`) | 허용 → `WAITING`(기한 = 종료 + 재시도 간격) | — |

`WAITING`의 "회차 종료" 열은 실행 중인 회차가 없어 생기지 않는 쌍이고, 코드상 `active` 플래그가 회차 종료를 `RUNNING`에서만 일으킨다(`SynchronizeCatalogProducts.execute`의 `finally`).

### 4. 대조 회차 `ReconciliationRunOutcome` (회차 하나, 메모리)

회차 안 누적 값: 읽은 수, 바꾼 수, `STALE` 수, 상품 단위 실패 목록(코드·유형), Store에만 있는 사본 수(SO-T9).

| 상태 \ 트리거 | 목록 페이지 응답(코드 1개 이상) | 빈 페이지 | 목록 응답 계약 오류(형식·페이지 정보·중복 코드) | 상품 결과 `INSERTED`·`UPDATED`·`REPAIRED`·`UNCHANGED`·`STALE` | 상품 단위 실패(상세 계약 오류·식별 충돌·코드 불일치·삽입 재시도 소진) | Catalog 접근 실패(연결·시간 초과·5xx·429·401·403·서비스 키 없음) | 프로세스 종료 |
|---|---|---|---|---|---|---|---|
| `RUNNING` | 허용 → 무변화(코드마다 상품 대조) | 허용 → 상품 단위 실패가 없으면 `SUCCEEDED`, 있으면 `PRODUCT_FAILURES` | 허용 → `ABORTED`(즉시 멈춤) | 허용 → 무변화(누적) | 허용 → 무변화(실패 누적, 다음 상품 계속) | 허용 → `ABORTED`(즉시 멈춤, 남은 상품 건너뜀) | 허용 → 회차 버림(메모리), 반영된 상품 트랜잭션은 이미 커밋됨 |
| `SUCCEEDED` | 거절(끝난 회차는 불변, 새 회차는 표 3) | 거절 | 거절 | 거절 | 거절 | 거절 | 무변화 |
| `PRODUCT_FAILURES` | 거절 | 거절 | 거절 | 거절 | 거절 | 거절 | 무변화 |
| `ABORTED` | 거절 | 거절 | 거절 | 거절 | 거절 | 거절 | 무변화 |

`ABORTED`와 `PRODUCT_FAILURES`를 나누는 것이 MC-SO1이다. 지금 코드는 둘 다 "실패"이고 `firstFailure`가 있으면 회차 전체를 실패로 끝낸다(`SynchronizeCatalogProducts.java:64-87`).

### 5. 실패 보관 키 (그룹 `store-catalog-publication`, 토픽 `stove.catalog.v1`, 키 `productCode`)

공통 상태 전이는 [P2.6-4·5](../platform/requirements.md)(결정 155·160)가 정본이다. Store 쪽 쌍만 적는다.

| 상태 \ 트리거 | 영구 실패·재시도 소진 | 정지 키의 뒤 레코드 도착 | ADMIN 재투입: 그 키의 레코드 모두 성공(무변화 포함) | ADMIN 재투입: 하나라도 다시 실패 | 정본 대조가 그 상품을 정본에 맞춤(`UPDATED`·`REPAIRED`·`UNCHANGED`·`STALE`) |
|---|---|---|---|---|---|
| 정상 | 허용 → 정지, 레코드 저장, 즉시 경보 | — (정상 키의 레코드는 표 1대로 처리) | — (보관 레코드 없음) | — | 무변화 |
| 정지 | 허용 → 정지 유지, 레코드 추가 | 허용 → 처리하지 않고 저장(정지 유지) | 허용 → 정상(정지 해제) | 무변화(정지 유지) | 무변화(정지 유지) — 결정 160 그대로, `[결정 필요 M-SO-1]` |

### 6. 감사 행 `store_reconciliation`

| 상태 \ 트리거 | 대조 결과 `INSERTED`·`UPDATED`·`REPAIRED` | 대조 결과 `UNCHANGED`·`STALE` | 감사 행 저장 실패 | 이벤트 반영(E) | 수정·삭제 요청 |
|---|---|---|---|---|---|
| (행 없음) | 허용 → 행 생성(사본 변경과 같은 트랜잭션) | 무변화(행 만들지 않음) | 거절 → 사본 변경과 함께 롤백, 상품 단위 실패 | 무변화(이벤트 경로는 감사 행 없음) | — |
| 행 있음 | 허용 → 새 행 추가(기존 행 불변) | 무변화 | 거절 → 롤백 | 무변화 | 거절(경로 없음) |

## API 계약

| 메서드 | 경로 | 역할 | 요청 | 응답 | 오류 |
|---|---|---|---|---|---|
| GET | `/api/v1/storefront/genres` | 익명 | 없음 | `ApiResponse<List<GenreDefinition>>`(코드·표시명·선택 가능 여부) | 없음(준비 상태 무관) |
| GET | `/api/v1/storefront/products` | 익명 | `q`(≤100자, 공백 제거), `genres`(반복, AND), `sort`(`NEWEST`·`PRICE_ASC`·`PRICE_DESC`, 기본 `NEWEST`), `page`(≥0, 기본 0), `size`(1~100, 기본 20) | `ApiResponse<StoreProductPage>`: `content`(`StoreProductSummary`), `page`, `size`, `totalElements`, `totalPages` | 400 `INVALID_REQUEST`, 503 `STORE_NOT_READY` |
| GET | `/api/v1/storefront/products/{productCode}` | 익명 | 경로 상품 코드 | `ApiResponse<StoreProductDetail>` | 404 `NOT_FOUND`(없음·`listed=false`), 503 `STORE_NOT_READY` |
| (공통) | 실패 보관 재투입·조회 운영 API | ADMIN, 평소 꺼짐 | 공통 메시징 설계 | 공통 | 공통 |

- 검증 순서: 입력 400 → 준비 503 → 대상 404. 읽기 전용이라 멱등 키가 없다.
- 응답은 `Cache-Control: no-store`. 응답 DTO는 `StoreProductDetail`·`StoreProductSummary`이고 core 사본 입력에서 만든다(SO-T4).
- `StoreProductSummary` 칸: `productId`·`productCode`·`title`·`shortDescription`·`thumbnailUrl`·`price`·`currency`·`publishedAt`·`saleStatus`·`catalogVersion`·`listed`·`genres`. 상세는 여기에 `content`·`videoUrl`·`contentVersion`·`buildVersion`·`updatedAt`을 더한다. OpenAPI 스냅샷(`apps/store/src/integrationTest/resources/openapi/store.json`)이 지킨다.
- 상품 코드 대조는 대소문자를 구분한다(DB 대조 규칙과 별개로 `ReadStoreProduct`가 같은 문자열만 받음).

## 이벤트

### 발행

없다. Store는 업무 사실을 만들지 않는다. 상품 단위 지연은 이벤트가 아니라 지표로 낸다([결정 158](../../../docs/decisions.md#forward-gap-counting)).

### 소비

| 플랫폼 행 | 소비 그룹 · 리스너 | Mapper 검증 | 서비스 | 업무 멱등 | 실패 처리 |
|---|---|---|---|---|---|
| design "목표 행" `CatalogProductFirstPublished`·`CatalogProductReleaseReplaced`·`CatalogProductSaleStatusChanged`·`CatalogProductListingChanged`(SP-4, [결정 171](../../../docs/decisions.md#catalog-copy-events-by-fact), T22) | `store-catalog-publication`(`ApplyCatalogProduct.CONSUMER_GROUP`) · `CatalogEventListener` · `stove.catalog.v1` · earliest · `ack-mode: record` | 아래 "Mapper 검증" | `ApplyCatalogProduct.execute(사본 입력)` | Inbox 없음(상태 사본 예외, [P2.3-5](../platform/requirements.md)). `productId` 행 잠금 + `catalogVersion` 비교(표 1), 최초 삽입은 PK·`uk_store_product_code` | 영구 실패 → 재시도 없이 실패 보관(R10). 재시도 가능(삽입 경합 3회 소진·DB 장애) → 공통 1·2·4초 재시도 뒤 실패 보관 |
| design 이벤트 목록 #15 `CatalogProductPublished`(이행 중 옛 타입, P2.7-4 ①~③) | 같음 | 같음 | 같음 | 같음 | 같음 |
| 그 밖의 `eventType` | 같은 리스너 | — | 호출 없음 | — | 부수효과 없이 건너뜀 + (그룹, 타입) 건너뜀 지표([P2.3-7](../platform/requirements.md), T31) |

리스너 경로

```
CatalogEventListener.receive(record)
  EventEnvelope.from(record)                      -- eventId·eventType 헤더 없으면 영구 실패(P2.1-5)
  switch (eventType, schemaVersion)               -- 결정 170, 헤더 없으면 1
    공개 사본 4개 · 옛 CatalogProductPublished(v1) -> CatalogProductMapper.from(envelope, payload) -> 사본 입력
    그 밖                                          -> 건너뜀 지표, return
  ApplyCatalogProduct.execute(사본 입력)
    TransactionTemplate(READ_COMMITTED), 최대 3회:
      lockById(productId) (SELECT … FOR UPDATE)
        없음 -> findByProductCode: 다른 productId면 CONFLICT, 같은 productId면 재시도, 없으면 persist + flush
        있음 -> StoreProduct.apply(입력)        -- 표 1 "사본 있음" E 열
  커밋 뒤 오프셋 커밋
```

Mapper 검증(`CatalogProductMapper.from`, 영구 실패면 공통 영구 실패 예외 — T20)

1. 봉투 `eventId` = 본문 `eventId`(1~64자), 봉투 `eventType` = 받은 타입 상수, 본문 `occurredAt` 있음, 본문 사본 있음.
2. Kafka 키 = 본문 `product.productCode`([P2.4-1](../platform/requirements.md)).
3. 사본 값: `productId > 0`, `contentVersion > 0`, `catalogVersion >= contentVersion`, `saleStatus ∈ CatalogSaleStatus`, `listed` 명시, 문자열 칸 길이(코드 50·제목 200·소개 500·URL 1000·본문 100,000·빌드 100), 코드 형식 `[A-Za-z0-9][A-Za-z0-9._-]{0,49}`, `price >= 0`, `currency = KRW`, `publishedAt`·`updatedAt` 있음, 장르 1~3개가 `GenreCatalog` 안에 있고 중복 없음.
4. 칸이 없을 때 기본값을 만들지 않는다. 옛 형식(버전·노출 칸 없음) 메시지를 받아야 하면 그 규칙은 공통 record가 아니라 이 Mapper의 `schemaVersion=1` 변환에 두고, 결과가 더 높은 버전의 중지·숨김을 덮지 못함을 테스트로 지킨다(플랫폼 T24, design 흐름 1 S8 "순서 역전" 행).
5. 본문의 `occurredAt`·`updatedAt`·`publishedAt`은 값으로만 옮긴다. Store 시계와 비교하지 않는다([P2.1-3](../platform/requirements.md)).

여러 토픽을 받지 않으므로 (토픽, `eventType`) 짝 대조([P2.3-8](../platform/requirements.md))는 해당 없다. 흐름 안 실패 종류별 처리는 design 흐름 1 "단계별 실패 표" S8·S9 행이 정본이다.

## 모듈 안 불변식

모듈을 넘는 정합은 [RI-5](../platform/design.md)(모든 Catalog 상품의 사본이 있고 버전 ≤ 정본, 같으면 내용 같음, Store에만 있는 상품 없음)·[RI-9](../platform/design.md)(Store `catalog_version`은 내려가지 않음)다. 아래는 Store 안에서 그것을 지키는 조각과 그 밖의 모듈 안 불변식이다.

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| SO-I1 | 사본의 `catalog_version`은 내려가지 않는다(RI-9) | `StoreProduct.apply`(`StoreProduct.java:68`), `StoreProduct.reconcile`(`:86`), 행 잠금 `StoreProductRepository.lockById` | `StoreProductTest#repeatedAndOlderSnapshotsCannotRevertCurrentPublication`, `StoreReconciliationIntegrationTest#delayedHttpResponseCannotOverwriteANewerKafkaProjection`, `StoreIntegrationTest#concurrentHttpAndEventInsertsConvergeToHighestVersion` |
| SO-I2 | 식별 값(`productId`·`productCode`·`publishedAt`)은 한 사본에서 바뀌지 않고, 한 `productCode`는 한 `productId`에만 연결된다 | `StoreProduct.apply:65-67`, `reconcile:83-85`, `ApplyCatalogProduct.java:38-43`, `ReconcileCatalogProduct.java:57-62`, `CanonicalProduct` 생성자, PK·`uk_store_product_code` | `StoreProductTest#identityCannotChangeEvenInANewerVersion`, `StoreReconciliationIntegrationTest#changedPublicationIdentityIsNeverAutomaticallyRepaired`, `#canonicalCodeMismatchCannotCreateAnotherProduct`, `StoreIntegrationTest#invalidEnvelopeAndIdentityConflictDoNotChangeProjection` |
| SO-I3 | 같은 `catalog_version`이면 공개 내용이 같다. Kafka 입력은 같은 버전 사본을 바꾸지 않고, 바꾸는 것은 검증한 정본의 `REPAIRED`뿐이다(결정 67) | `StoreProduct.apply:69-72`, `reconcile:87-91` | `StoreProductTest#sameVersionConflictsPreserveOriginalSnapshot`, `StoreIntegrationTest#newerVersionWinsAndSameVersionConflictsAreRolledBack`, `StoreSaleStatusTest#conflictingSalesAtTheSameCatalogVersionDoNotOverwriteTheProjection`, `StoreReconciliationIntegrationTest#verifiedCanonicalSnapshotRepairsSameVersionAndRecordsBeforeAndAfterAtomically` |
| SO-I4 | `catalog_version >= content_version` | `ck_store_catalog_version`(V2), `StoreProductValues.requireSnapshot`(`:25`), Mapper | 없음 → tasks SO-T14(CHECK 위반 INSERT 거절 테스트) |
| SO-I5 | 더 높은 `catalog_version`에서 `content_version`은 내려가지 않고, 같은 `content_version`이면 콘텐츠 칸이 같다(판매·노출만 바뀜) | `StoreProduct.apply:74-77` | 같은 콘텐츠 버전의 판매 변경: `StoreSaleStatusTest#salesChangesAtTheSameContentVersionSurviveOldPublicationAndHttpSnapshots`, `StoreListingTest#sameVersionListingConflictIsRejectedAndHigherVersionChangesOnlyVisibility`. 콘텐츠 버전 하향·같은 콘텐츠 버전의 콘텐츠 차이 거절: 없음 → SO-T14 |
| SO-I6 | 대조가 사본을 바꾸면 감사 행이 정확히 하나, 같은 트랜잭션에 있다. `UNCHANGED`·`STALE`에는 없다 | `ReconcileCatalogProduct.apply:68-71`, `ck_store_reconciliation_action` | `StoreReconciliationIntegrationTest#reconciliationAndAuditRollBackTogetherIfTheAuditCannotBeWritten`, `#verifiedCanonicalSnapshotRepairsSameVersionAndRecordsBeforeAndAfterAtomically`, `StoreReconciliationMigrationIntegrationTest#addingTheAuditPreservesTheExistingProjectionAndDoesNotFabricateRepairs` |
| SO-I7 | `listed=false` 사본은 공개 목록·상세에 나오지 않고, 노출 조건은 페이지·총건수 계산 전에 적용된다 | `SearchStoreProducts.java:49`, `ProductPublicAccess.requireDetail`, `ReadStoreProduct.java:23` | `StoreIntegrationTest#listingFilterRunsBeforeSearchPaginationAndHiddenDetailIsRejected`, `PublicProductDetailTest#hiddenProductsAreNotPublicDetails` |
| SO-I8 | `NOT_READY`이면 상품 조회는 503이고, 한 프로세스에서 `READY`는 되돌아가지 않는다 | `SynchronizeCatalogProducts.requireReady`, 회차 종료 상태 기록(`:89`·`:93`) | `SynchronizeCatalogProductsTest#failedInitializationRetriesAutomaticallyAtTheDueTimeAndSuccessUsesTheNormalInterval`, `StoreReconciliationIntegrationTest#failedBackgroundScanPreservesAnAlreadyReadyProjection` |
| SO-I9 | 정본 HTTP 호출 중 Store DB 트랜잭션이 열려 있지 않다 | `ReconcileCatalogProduct.execute:38-39`(활성 트랜잭션이면 `IllegalStateException`) | 없음 → SO-T14 |
| SO-I10 | 사본마다 장르는 기준 목록 안의 중복 없는 1~3개 | `StoreProductValues.requireSnapshot:27-28`, `store_product_genre` PK·FK | `StoreGenreMigrationIntegrationTest#relationMigrationPreservesRowsAndEnforcesUniqueCodesAndParentIdentity`, `StoreGenreIntegrationTest#unknownGenreFiltersAreRejected` |
| SO-I11 | Store는 사본을 지우지 않는다(정본에 없어도) | 삭제 경로 없음(리포지토리 `delete*` 호출 없음) | 없음 → SO-T14(ArchUnit: core.service에서 `StoreProductRepository.delete*` 호출 금지) |
| SO-I12 | 회차 하나만 동시에 돈다(프로세스 안) | `SynchronizeCatalogProducts.active`(`AtomicBoolean`, `:54`) | `SynchronizeCatalogProductsTest#overlappingPollsDoNotRunTheSameLocalScanTwice` |
| SO-I13 | 상품 단위 실패는 같은 회차의 다른 상품 반영을 막지 않고, 접근 실패는 회차를 즉시 멈춘다 | `SynchronizeCatalogProducts.java:71-81` | `StoreReconciliationIntegrationTest#aFailedProductDoesNotStarveOtherProductsInTheSameScan`, `SynchronizeCatalogProductsTest#sourceOutageStopsThePassInsteadOfTimingOutForEveryRemainingProduct` |

## 외부 연동과 adapter 메모

### core.port `CatalogProducts`

| 메서드 | 계약 | 실패 |
|---|---|---|
| `codes(int page)` | 숨긴 상품을 포함한 정본 상품 코드 한 페이지(크기 100). 빈 목록이면 끝 | 접근 실패 → `CatalogUnavailableException`, 계약 밖 → 목록 계약 오류(회차 `ABORTED`) |
| `detail(String code)` | 그 코드의 정본 공개 사본 하나. 요청 코드와 응답 코드가 같아야 함 | 접근 실패 → `CatalogUnavailableException`, 계약 밖 → 상품 단위 실패 |

목표: 포트가 공통 계약 record(`CatalogProductSnapshot`)가 아니라 Store 사본 입력을 돌려준다(SO-T4).

### adapter `CatalogHttpClient` (Catalog 내부 API 규칙 — core 계약 아님)

- 경로: `GET {CATALOG_BASE_URL}/internal/v1/store-products?page={n}&size=100`, `GET {CATALOG_BASE_URL}/internal/v1/store-products/{code}`. 헤더 `X-Store-Service-Key: {CATALOG_STORE_SERVICE_KEY}`(Catalog `config/SecurityConfig.java:29`이 이 두 GET에만 허용). 키가 비어 있으면 호출하지 않고 실패(공개 경로로 우회 금지).
- 연결 3초·읽기 10초, 리다이렉트 따르지 않음(`CatalogClientConfiguration`).
- 응답 봉투 `ApiResponse`: `success=true`이고 `data`가 객체여야 한다.
- 목록 검증: `content` 배열, `page`=요청 값, `size`=100, `totalElements`·`totalPages` 0 이상 정수이고 서로 맞음, `content` 길이 = 남은 수(최대 100), 코드 형식·중복 없음.
- 상세 검증: `listed` boolean 명시, `productId`·`price`·`contentVersion`·`catalogVersion` 정수(소수 거절), 필수 문자열 칸, `videoUrl` 문자열 또는 없음, `genres` 문자열 배열, 사본 값 검증(Mapper 3과 같음), 응답 코드 = 요청 코드. 공통 record의 옛 형식 기본값은 HTTP 경로에 적용되지 않는다(검증이 먼저).
- 오류 분류: 연결 실패·시간 초과(`ResourceAccessException`), 5xx, 429, **401·403(키 거절)** → 접근 실패. 그 밖의 4xx·형식 오류 → 계약 오류. 지금 401·403은 계약 오류로 분류된다(SO-T8).
- 로그·예외 메시지에 응답 본문·URL·키를 복사하지 않는다.

### adapter `StoreSynchronizationMetrics`

core가 주는 상태 값(`Supplier`)만 받는다. 지표 이름(목표, R8.3·R8.5):

| 지표 | 형식 | 뜻 |
|---|---|---|
| `stove.store.sync.ready` | 게이지 0/1 | `READY`면 1 |
| `stove.store.sync.running` | 게이지 0/1 | `RUNNING`이면 1 |
| `stove.store.sync.runs{result=success\|product_failures\|aborted}` | 카운터(지금 게이지, SO-T13) | 결과별 회차 수 |
| `stove.store.sync.last.success.timestamp` | 게이지(epoch 초) | 마지막 `SUCCEEDED` 종료 시각(Store 시계) |
| `stove.store.sync.last.complete.timestamp` | 게이지 | 마지막으로 목록을 끝까지 읽은 회차(`SUCCEEDED`·`PRODUCT_FAILURES`)의 종료 시각 — 신설 |
| `stove.store.sync.last.scanned`·`last.changed`·`last.failed`·`last.skipped.newer` | 게이지 | 마지막 회차 누적 값 |
| `stove.store.sync.product.behind` | 게이지 | 마지막 완주 회차에서 정본보다 뒤처졌던 사본 수(`INSERTED`+`UPDATED`+`REPAIRED`) — 신설, 결정 158 |
| `stove.store.sync.product.failed{type}` | 게이지 | 마지막 완주 회차의 상품 단위 실패 수(유형별) — 신설 |
| `stove.store.sync.product.failed.oldest.seconds` | 게이지 | 지금 실패 중인 상품 중 처음 실패를 관측한 Store 시각부터 가장 오래된 것의 경과 초. 메모리 기록이라 재시작 때 0부터 — 신설 |
| `stove.store.sync.product.orphaned` | 게이지 | Store에만 있는 사본 수 — 신설, SO-T9 |

경보(`infra/prometheus/alerts.yml`): `StoreSyncInitStalled`(`ready == 0`이 10분), `StoreSyncReconcileFailing`(한 번 성공한 뒤 성공이 5분 끊김)은 결정 85 값을 쓴다. 목표에서 `StoreSyncReconcileFailing`은 "목록을 끝까지 읽은 회차가 5분 끊김"(접근 불가)으로 바꾸고, 상품 단위 실패는 별도 게이지로 둔다. 상품 단위 게이지의 경보 문턱은 결정 159에 따라 실험 뒤 정한다(MC-SO1).

`product.behind`는 이벤트가 대조 직전에 발행돼 아직 소비되지 않은 정상 지연도 센다. 그래서 문턱은 측정 뒤 정한다(결정 159).

## 설정

| 키 | 환경 변수 · 기본값 | 뜻 | 운영·테스트 차이 |
|---|---|---|---|
| `stove.store.catalog-base-url` | `CATALOG_BASE_URL` · `http://localhost:8081` | Catalog 주소 | 없음 |
| `stove.store.catalog-service-key` | `CATALOG_STORE_SERVICE_KEY` · 빈 값 | Catalog와 같은 값의 서비스 키. 빈 값이면 회차가 `ABORTED`로 반복되고 준비가 열리지 않음 | 없음. 배포 비밀값은 외부 주입(결정 86) |
| `stove.store.sync.poll-interval-ms` | `STORE_SYNC_POLL_INTERVAL_MS` · 1000 | 기한 확인 주기(양수) | 없음 |
| `stove.store.sync.retry-delay` | `STORE_SYNC_RETRY_DELAY` · `PT5S` | `ABORTED`(그리고 지금은 `PRODUCT_FAILURES`) 뒤 다음 회차까지 | 없음 |
| `stove.store.sync.reconcile-interval` | `STORE_SYNC_RECONCILE_INTERVAL` · `PT1M` | `SUCCEEDED` 뒤 다음 회차까지 | 없음 |
| `stove.store.sync.enabled` | `STORE_SYNC_ENABLED` · true | 스케줄러 등록 여부 | **테스트 전용 스위치** — false면 운영에서 준비가 영원히 열리지 않는다. 목표: 운영 계약에서 지우고 테스트는 테스트 구성으로 끈다(원칙 ⑦, SO-T12) |
| `spring.kafka.consumer.auto-offset-reset` | earliest | 새 그룹이 토픽 처음부터 읽음 | 없음 |
| `spring.kafka.listener.ack-mode` | record, `enable-auto-commit: false` | 레코드마다 커밋 뒤 오프셋 | 없음 |
| `stove.messaging.ops.enabled` | false | 공통 운영 API(목표: 실패 보관 재투입) | 평소 꺼 둠(결정 87). 켤 때 ADMIN 인증이 필요 — Store는 지금 리소스 서버 설정이 없다(notes F) |

정본 조회 시간 제한(연결 3초·읽기 10초)은 `CatalogClientConfiguration`의 코드 값이다. 설정 키로 뺄지는 정하지 않았고 운영·테스트 차이가 없다.

## 사용 안내

- 실행: `DB_HOST`·`DB_PORT`·`DB_USER`·`DB_PASSWORD`, `CATALOG_BASE_URL`, Catalog와 같은 값의 `CATALOG_STORE_SERVICE_KEY`. 기존 개발 MySQL에는 관리자 계정으로 [스키마 준비 SQL](../../../tools/migrations/store/store-schema.sql)을 적용하고, 새 환경은 `infra/mysql/init`에 들어 있다.
- 검증: `./gradlew :apps:store:test :apps:store:integrationTest -Pstove.infraTest.maxParallel=1`, `python3 tools/verification/verify-store.py`(포트 충돌 시 `STORE_TEST_PORT_OFFSET`). 9개 서비스 여정은 `python3 tools/verification/verify-g4-operations.py`(`G4_TEST_PORT_OFFSET` 기본 20000).
- 복구 확인: 경고 로그, `stove.store.sync.*`, `store_reconciliation` 전후 기록, 실패 보관 표. 식별 충돌·정본 계약 위반은 Catalog 원인을 고치면 다음 회차에서 회복 여부가 보인다. 개발 환경 전체 재구축은 Store를 멈추고 사본 표를 비운 뒤 시작한다.
