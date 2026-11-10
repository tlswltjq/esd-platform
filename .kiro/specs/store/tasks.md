# 작업: Store

> 경로 줄임 `store:` = `apps/store/src/main/java/com/stove/store/`, `store/db/Vn` = `apps/store/src/main/resources/db/migration/Vn__*.sql`. 줄 번호는 2026-11-10 `esd-worktrees/spec-foundation` 기준이다. 모듈을 넘는 차이는 [플랫폼 tasks](../platform/tasks.md)의 T 번호에 있고 여기서는 링크한다.

## 완료 (검증 결과는 [근거 색인](../../../docs/evidence.md))

- [x] StoreProduct 사본·버전 규칙·제목 검색/정렬/페이지·상세·`CatalogProductPublished` 수신·시작 시 초기 동기화 — 2026-10-15
- [x] 자동 초기화 재시도·주기 정본 대조·동일 버전 정본 교정(A)·`store_reconciliation` 원자 감사·`stove.store.sync.*` 지표 — 2026-11-03
- [x] 장르 분류(플랫폼 10개)·`store_product_genre`·복수 장르 AND 검색·`/storefront/genres` — 2026-11-03
- [x] 자동 복구 경보 연결: `/actuator/prometheus` 익명 GET 허용, `StoreSyncInitStalled`(초기화 10분)·`StoreSyncReconcileFailing`(대조 성공 5분 끊김) 경보, 격리 스택 발화·해제 검증 — 2026-11-05, [결정 85](../../../docs/decisions.md), PR #104
- [x] 서비스 11개 공통 Prometheus 수집에 Store 포함(실제 격리 Prometheus 대상 up) — 2026-11-06, PR #113
- [x] 최초 삽입 flush의 MySQL 교착(1213)이 `jakarta.persistence.OptimisticLockException`으로 감싸여 재시도를 지나치던 결함 재현·수정(원인 사슬로 판단) — 2026-11-08, [D-047](../../../docs/defects.md#d-047)

## 코드와 맞출 것

형식: 무엇이 · 어디서(`파일:줄`) · 목표 · 영향 · 재현 테스트. Store는 조회 사본이고 주문·결제·보유권 판정은 Catalog 정본으로 하므로(design 흐름 1 S8 "늦은 도착" 행) **돈·보유권 영향이 있는 항목은 없다**. 아래는 방문자 노출·가용성·운영 감지 영향이 큰 순서다.

- [ ] **MC-SO1. 대조 회차가 상품 하나의 영구 실패로 전체 실패가 되고, 상품 단위 지연 지표가 없다** (소급 RC-12 — 결정 158·159로 닫힘)
  - 어디서: `store:core/service/SynchronizeCatalogProducts.java:64-87`(`firstFailure`가 있으면 `:87`에서 회차 전체를 실패로 던짐 → `:93-95` 실패 기록, `lastSuccessAt` 멈춤), `store:adapter/StoreSynchronizationMetrics.java:17-30`(회차 지표만), `infra/prometheus/alerts.yml`의 `StoreSyncReconcileFailing`(대조 성공 5분 끊김 하나).
  - 목표: R7.8·R7.9·R8.3·R8.5~R8.7, design "상태 모델" 표 4·"adapter 메모" 지표 표, [P3.7-4](../platform/requirements.md). 회차 결과를 `SUCCEEDED`·`PRODUCT_FAILURES`·`ABORTED`로 나누고, 상품 단위 게이지(`product.behind`·`product.failed{type}`·`product.failed.oldest.seconds`)를 회차 결과와 분리한다. `StoreSyncReconcileFailing`은 "목록을 끝까지 읽은 회차가 끊김"(Catalog 접근 불가)으로, 상품 하나의 영구 실패는 별도 게이지로 낸다. RC-12 닫힘 내용: 감지 주체는 Store 자신(결정 158 — "Catalog → Store 사본은 기존 Store 대조가 정본과 맞추고 Store가 상품 단위 지연을 센다"), 상품 단위 경보 문턱은 G6 실험 측정 뒤 정하고 그 전에는 게이지만(결정 159), 회차 경보 값 10분·5분은 결정 85 그대로. 준비 상태·다음 간격은 `[결정 필요 M-SO-2]`.
  - 영향: 돈·보유권 없음. 상품 하나의 충돌이 5분 뒤부터 경보를 계속 울려 다른 상품의 실제 지연·Catalog 장애를 가린다. 초기화 중이면 M-SO-2의 가용성 문제로 이어진다.
  - 재현: `SynchronizeCatalogProductsTest`에 식별 충돌 상품 1개 + 정상 상품 갱신을 넣고, 회차 결과가 `PRODUCT_FAILURES`, 정상 상품은 `UPDATED`, `last.complete.timestamp`는 움직이고 `product.failed{type=CONFLICT}=1`인지 단언. Catalog 503 주입 시 `ABORTED`이고 `last.complete.timestamp`가 멈추는지.

- [ ] **SO-T1. 공개 사본 이벤트 4개를 받지 않는다** ([결정 171](../../../docs/decisions.md#catalog-copy-events-by-fact), 플랫폼 T22)
  - 어디서: `store:api/listener/CatalogEventListener.java:22`(`isType(EventType.CATALOG_PRODUCT_PUBLISHED)`만), `store:api/listener/CatalogProductMapper.java:14`(`CatalogProductPublishedEvent` 하나만 받음). `common/kafka/.../contract/EventType.java:44`에 새 상수 4개 없음.
  - 목표: R5.1·R5.10, design "이벤트" 소비 표. 네 타입과 옛 타입을 같은 Mapper 경로로 받아 같은 사본 입력 → `ApplyCatalogProduct.execute`. 배포 순서 P2.7-4 ①(Store가 다섯 타입을 모두 받는 판)이 Catalog 전환보다 먼저다.
  - 영향: Catalog가 먼저 새 타입을 내면 Store는 지금 코드에서 조용히 건너뛰어(SO-T2) 사본이 다음 대조(기본 1분)까지 뒤처진다. 대조가 메우므로 영구 누락은 없다.
  - 재현: `StoreIntegrationTest`에 네 타입을 각각 실제 Kafka로 넣어 사본이 바뀌는지, 같은 `catalogVersion`의 옛 타입·새 타입 중복이 무변화인지.

- [ ] **SO-T2. 모르는 `eventType`을 지표 없이 건너뛴다** ([P2.3-7](../platform/requirements.md), 플랫폼 T31)
  - 어디서: `store:api/listener/CatalogEventListener.java:22-24`(else 없음).
  - 목표: R5.8. 건너뛸 때 (소비 그룹, `eventType`) 카운터를 올린다.
  - 영향: SO-T1 이행 중 배포 순서가 뒤집혀도 드러나지 않는다.
  - 재현: 리스너 단위 테스트에 모르는 타입 레코드 → 서비스 호출 없음 + 카운터 1.

- [ ] **SO-T3. `schemaVersion` 헤더를 읽지 않는다** ([결정 170](../../../docs/decisions.md#schema-version-header), 플랫폼 T19·T25)
  - 어디서: `store:api/listener/CatalogEventListener.java:21-24`, `CatalogProductMapper.java:14`. 공통 `EventEnvelope`에 아직 `schemaVersion`이 없다(`common/kafka`).
  - 목표: R5.11. (`eventType`, `schemaVersion`)으로 Mapper 변환을 고르고, 헤더가 없으면 1. 모르는 버전은 영구 실패.
  - 영향: 발행자가 본문 모양을 바꾸면 Store가 옛 모양으로 읽다 계약 오류가 된다.
  - 재현: 헤더 없음 → v1 경로, `schemaVersion=2` 견본 → v2 경로, `schemaVersion=99` → 영구 실패.

- [ ] **SO-T4. core 입력이 공통 계약 record `CatalogProductSnapshot`이고, 그 record가 빠진 칸을 기본값으로 만든다** (AGENTS.md 3절, [결정 78](../../../docs/decisions.md), 플랫폼 T24)
  - 어디서: `common/kafka/src/main/java/com/stove/common/kafka/contract/payload/CatalogProductSnapshot.java:13-19`(`listed` 없으면 판매 상태로 추정, `saleStatus`·`catalogVersion` 없으면 `ON_SALE`·`contentVersion`), `store:api/listener/CatalogProductMapper.java:26`(record를 그대로 반환), `store:core/service/ApplyCatalogProduct.java:30`, `store:core/domain/StoreProduct.java:55`·`:64`·`:81`·`:113`, `store:core/port/CatalogProducts.java:8`, `store:adapter/CatalogHttpClient.java:82`, 컨트롤러 DTO(`StoreProductDetail.java:11`·`StoreProductSummary.java:10`·`StoreProductPage.java:6`).
  - 목표: R5.12·용어 "사본 입력", design "이벤트" Mapper 검증 4. Store core에 사본 입력 값(예: `CatalogProductCopy`)을 두고, Mapper·adapter가 검증 뒤 만든다. 옛 형식 기본값은 공통 record에서 빼고, 남겨야 하면 Store Mapper의 `schemaVersion=1` 변환으로 옮긴다(노출 추정은 Catalog 규칙의 재계산이라 원칙 ③). 남기지 않아도 되는지는 토픽 보존 기간 안에 옛 형식 메시지가 있는지로 판단한다(T24).
  - 영향: 방문자 노출 — 버전 칸 없는 옛 JSON이 빈 Store에 들어오면 `listed=true`·`ON_SALE` 사본이 생긴다(T24 재현). 더 높은 버전의 숨김은 덮지 못한다(`StoreListingTest#olderPayloadWithoutListingUsesAutomaticSalesRuleAndCannotRestoreANewerHiddenCopy`).
  - 재현: 버전·노출 칸 없는 과거 JSON을 빈 Store에 넣음 → 목표에서는 영구 실패(또는 v1 변환 규칙의 결과)이고 공통 record가 값을 만들지 않음.

- [ ] **SO-T5. 영구 실패도 4회 재시도한다** ([P2.6-3](../platform/requirements.md), 플랫폼 T20)
  - 어디서: 영구 실패를 `BusinessException(INVALID_REQUEST/CONFLICT)`로 던지는 곳 — `store:api/listener/CatalogProductMapper.java:24`, `store:core/domain/StoreProduct.java:67`·`:71`·`:75`·`:77`, `store:core/domain/StoreProductValues.java:26`·`:28`, `store:core/service/ApplyCatalogProduct.java:42`. 재시도 정책은 `common/kafka/.../KafkaConsumerAutoConfiguration.java:37-43`.
  - 목표: R5.16. 이 자리들이 공통 영구 실패 예외(T20)로 표시되어 재시도 없이 실패 보관으로 간다. 삽입 경합(`ConcurrencyFailureException`)·DB 장애는 재시도 가능으로 남긴다.
  - 영향: 같은 버전 충돌 한 건이 파티션을 7초+ 멈춰 같은 파티션의 다른 상품 반영이 늦어진다(`docs/architecture.md` R11).
  - 재현: `StoreIntegrationTest#invalidKafkaInputGoesToDltWithoutWritingProduct`에 처리 시도 횟수 단언(1회).

- [ ] **SO-T6. 실패 보관 표·키 정지·재투입 경로가 없다** ([결정 155](../../../docs/decisions.md#key-stop-on-dead)·[결정 160](../../../docs/decisions.md#dead-letter-store), 플랫폼 T2·T7)
  - 어디서: `apps/store/src/main/resources/application.yml:58-60`(`stove.messaging.ops.enabled=false`, 공통 운영 API 없음), `store/db/V1~V5`(실패 보관 표 없음), `store:config/SecurityConfig.java:11-14`(ADMIN 인증 없음, 나머지 `denyAll`). 테스트 `StoreIntegrationTest#invalidKafkaInputGoesToDltWithoutWritingProduct`(DLT 토픽 단언), `#schemaContainsOnlyOneBusinessTableAndNoInboxOrOutbox`.
  - 목표: R10 전체, R9.1·R9.3, design "데이터 모델" 실패 보관 표·"상태 모델" 표 5. 공통 메시징이 자동 구성하는 실패 보관 표를 Store에 이행하고, 운영 API는 평소 꺼 두며 켤 때 ADMIN을 검증한다(결정 87). 키 정지의 Store 적용 방식은 `[결정 필요 M-SO-1]`. 회수 정렬은 공통 설계(1회차 지적 B1, 판정 전).
  - 영향: 지금은 실패 레코드가 `stove.catalog.v1.DLT`에 7일만 남고 Store에서 재투입할 방법이 없다(운영 API 꺼짐). 사본은 정본 대조로 수렴하므로 방문자 영향은 작지만, Catalog 결함의 증거(같은 버전 다른 내용)가 사라진다.
  - 재현: 같은 버전 다른 내용 이벤트 → 실패 보관 표 1행·키 정지·경보 지표 1, 사본 불변. ADMIN 재투입(대조로 이미 맞춘 뒤) → 무변화로 끝나고 정지 해제(M-SO-1 결과에 맞춰 단언 조정).

- [ ] **SO-T7. 준비·작업 단계·회차 결과가 boolean·nullable 조합의 숨은 상태다** (원칙 ④)
  - 어디서: `store:core/domain/CatalogSyncStatus.java:5-6`(`ready`·`running` boolean, `failureType` nullable 문자열, `lastSuccessAt` nullable), `store:core/service/SynchronizeCatalogProducts.java:28-29`(초기값 `Instant.EPOCH`), `:61-62`·`:82-84`·`:89-90`·`:93-94`.
  - 목표: design "상태 모델" 표 2~4. `StoreReadiness {NOT_READY, READY}`, `SyncPhase {WAITING, RUNNING}`, `ReconciliationRunOutcome {RUNNING, SUCCEEDED, PRODUCT_FAILURES, ABORTED}`를 core enum으로 두고, 상태 조회·지표는 이 값에서 만든다. 실패 유형은 문자열 클래스 이름이 아니라 분류 enum(접근 실패·목록 계약 오류·상품 계약 오류·식별 충돌·장르 오류).
  - 영향: 운영 감지 — 지금은 "초기화 실패 대기"와 "초기화 중"을 `running`·`failureType` 조합으로만 구별한다.
  - 재현: `SynchronizeCatalogProductsTest`를 enum 값 단언으로 바꿈.

- [ ] **SO-T8. Catalog 서비스 키 거절(401·403)을 접근 실패가 아니라 계약 오류로 분류한다** (플랫폼 design 흐름 1 S9 "상대 모듈 중단" 행)
  - 어디서: `store:adapter/CatalogHttpClient.java:91-95`(`5xx`·`429`만 `CatalogUnavailableException`, 나머지 `IllegalStateException`), `store:core/service/SynchronizeCatalogProducts.java:80`(접근 실패만 회차 중단).
  - 목표: R7.8, design "adapter 메모" 오류 분류. 401·403·키 없음은 접근 실패로 회차를 즉시 `ABORTED`.
  - 영향: 운영 감지 — 키를 잘못 배포하면 목록 GET에서 바로 실패하므로 지금도 회차는 멈춘다. 그러나 목록 뒤 상세에서 키가 거절되는 경우(키 교체 중)에는 모든 상품을 하나씩 실패로 세며 Catalog를 상품 수만큼 부르고, 경보가 "상품 실패"로 잘못 보인다.
  - 재현: `CatalogHttpValidationTest`에 상세 403 → `CatalogUnavailableException`(또는 접근 실패 분류) 단언, `SynchronizeCatalogProductsTest`에서 상세 403 → 상세 호출 1회 뒤 `ABORTED`.

- [ ] **SO-T9. Store에만 있는 사본을 세지 않는다** (RI-5 역방향, [결정 158](../../../docs/decisions.md#forward-gap-counting))
  - 어디서: `store:core/service/SynchronizeCatalogProducts.java:65-86`(Catalog 코드만 순회, Store 쪽 집합과 비교 없음).
  - 목표: R7.10, 지표 `stove.store.sync.product.orphaned`. 완주 회차에서 읽은 정본 `productId` 집합에 없는 사본 수를 센다. 지우지 않는다(R7.6). 경보 문턱은 결정 159.
  - 영향: 방문자 노출 — Catalog 개발 DB를 다시 만든 뒤 남은 사본이 공개 목록에 계속 보여도 감지되지 않는다(Catalog는 공개 삭제를 하지 않으므로 운영에서는 드묾).
  - 재현: Store에만 사본 1행을 넣고 빈 Catalog로 완주 → 게이지 1, 사본 유지.

- [ ] **SO-T10. 판매 상태·감사 결과가 core에서 문자열이다** (원칙 ④)
  - 어디서: `store:core/domain/StoreProduct.java:42`(`String saleStatus`), `store:core/domain/StoreProductValues.java:24`·`store:api/listener/CatalogProductMapper.java:21-22`(문자열 리터럴 비교 두 곳 중복), `store:core/domain/StoreReconciliation.java:18`(`String action`, `:32` `result.name()`).
  - 목표: design "데이터 모델". `CatalogSaleStatus {ON_SALE, STOPPED, BLOCKED}`(Catalog 값의 사본 enum)와 `ReconciliationAction {INSERTED, UPDATED, REPAIRED}`를 `@Enumerated(STRING)`으로 쓰고 DB CHECK(`ck_store_sale_status`·`ck_store_reconciliation_action`)와 값이 같음을 테스트로 지킨다.
  - 영향: 없음(지금 CHECK가 막음). 새 판매 상태가 생길 때 고칠 곳이 셋으로 흩어져 있다.
  - 재현: enum 값 집합 = CHECK 값 집합을 이행 통합 테스트로 단언.

- [ ] **SO-T11. 사본 값 제약 일부가 DB에 없다** (원칙 ⑤)
  - 어디서: `store/db/V1`(`price`·`currency`·`content_version`·`product_id`에 CHECK 없음), `store/db/V4`(`before_snapshot`과 `action`의 관계 CHECK 없음).
  - 목표: design "데이터 모델" 목표 제약 — `CHECK (product_id > 0)`, `CHECK (price >= 0)`, `CHECK (currency = 'KRW')`, `CHECK (content_version >= 1)`, `CHECK ((action = 'INSERTED') = (before_snapshot IS NULL))`. 새 이행 파일로 더하고 기존 행 검사(위반 0건)를 같은 PR 이행 테스트로(결정 104).
  - 영향: 없음(코드 검증이 지금 막음). 코드 검증을 우회한 쓰기(운영 SQL 등)를 DB가 못 막는다.
  - 재현: 각 위반 INSERT가 `DataIntegrityViolationException`인지.

- [ ] **SO-T12. 운영 계약에 테스트 전용 스위치와 수동 실행 경로가 있다** (원칙 ⑦, [결정 46](../../../docs/decisions.md))
  - 어디서: `store:config/CatalogSynchronizationConfiguration.java:47`(`stove.store.sync.enabled`, false면 스케줄러 없음 → 운영에서 준비가 영원히 열리지 않음), `apps/store/src/main/resources/application.yml:54`, `store:core/service/SynchronizeCatalogProducts.java:52-53`(주석 "Also used for explicit full-pass contract checks"의 공개 `execute()`).
  - 목표: design "설정" 표. 운영 설정에서 `enabled`를 지우고, 통합 테스트는 테스트 구성(스케줄러 빈 제외·`Clock` 주입)으로 회차를 몬다. `execute()`는 스케줄러와 테스트가 같은 경로를 쓰도록 남기되 운영 API로 노출하지 않는다(지금도 노출 없음).
  - 영향: 운영 — 잘못 배포된 `STORE_SYNC_ENABLED=false`가 경고 없이 전 상점 503을 만든다(10분 뒤 `StoreSyncInitStalled`만 울림).
  - 재현: 설정 키 없이 컨텍스트가 뜨고 스케줄러 빈이 있는지, 테스트 구성에서만 빠지는지.

- [ ] **SO-T13. 결과별 회차 수를 게이지로 낸다**
  - 어디서: `store:adapter/StoreSynchronizationMetrics.java:20-21`(`Gauge.builder("stove.store.sync.runs", …)`).
  - 목표: design "adapter 메모" 지표 표 — 카운터 `stove.store.sync.runs{result}`(세 결과). 경보식의 `increase()` 사용을 그에 맞춘다.
  - 영향: 없음(값은 같음). Prometheus 쪽 형식이 카운터 관례와 다르다.
  - 재현: `/actuator/prometheus` 출력의 `# TYPE`이 counter인지.

- [ ] **SO-T14. 모듈 안 불변식 일부에 확인 테스트가 없다** (원칙 ⑤)
  - 어디서: design "모듈 안 불변식" SO-I4·SO-I5(콘텐츠 버전 하향·같은 콘텐츠 버전 콘텐츠 차이)·SO-I9·SO-I11.
  - 목표: SO-I4 — `ck_store_catalog_version` 위반 INSERT 거절. SO-I5 — `StoreProductTest`에 `catalogVersion` 증가 + `contentVersion` 감소, 같은 `contentVersion` + 제목 변경이 각각 `CONFLICT`. SO-I9 — 트랜잭션 안에서 `ReconcileCatalogProduct.execute` 호출 시 `IllegalStateException`. SO-I11 — ArchUnit: `core.service`가 `StoreProductRepository`의 `delete*`를 부르지 않음.
  - 영향: 없음(테스트 공백).
  - 재현: 위 테스트 자체.

## 진행 중 / 남음

- [ ] 결정 대기: `[결정 필요 M-SO-1]`(상태 사본 소비의 키 정지·해제), `[결정 필요 M-SO-2]`(상품 단위 실패만 남은 회차의 준비 상태·다음 간격) — notes D. 정해지면 R6.5·R7.2·R10.2와 design 표 2·3·5의 해당 칸을 결정 번호로 바꾼다.
- [ ] 결정 171 이행 3단계(P2.7-4): ① SO-T1(Store가 다섯 타입 수신) → ② Catalog가 새 타입 발행(Catalog 모듈 작업) → ③ 옛 `CatalogProductPublished`가 모든 `outbox_event`·실패 보관 표·토픽 보존 기간에서 사라진 근거를 적고 Store의 옛 타입 처리 제거.
- [ ] 상품 단위 지연·실패 게이지의 경보 문턱을 G6 실험으로 정하기(결정 159) — 플랫폼 tasks "정방향 응답 쌍의 경보 기준 시간" 항목과 함께.
- [ ] 2회차 독립 평가(결정 178): 플랫폼 스펙과 이 모듈 스펙을 함께 보고 1회차 지적 B1을 판정.
- 계정 복구·상태 등 G5 공통 운영은 [Auth](../auth/tasks.md)·[Gateway](../gateway/tasks.md) 작업과 [로드맵](../../../docs/roadmap.md)을 따른다.

## 범위 밖(후속)

- Elasticsearch·개인화·대규모 무중단 재색인.
- 가격 범위·판매 상태 등 추가 필터, 대표 장르·장르 계층.
- 전역 단일 스캔·내구 커서/임대·다중 인스턴스 조율.
- 관리자 수동 재동기화 API를 정상 운영 경로로 두는 것(결정 66).
- 정본 조회 시간 제한(연결 3초·읽기 10초)을 설정 키로 빼는 것.
