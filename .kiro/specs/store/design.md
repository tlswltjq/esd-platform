# 설계: Store

## 아키텍처 위치

- 포트 8087, 저장소 MySQL(`stove_store` 스키마).
- `core/{domain, port, service}` + `api/{controller, listener}` + `adapter/` + `config` 경계를 따른다.
- Store는 Catalog 공개 API와 새 변경 이벤트만 사용하며 다른 서비스의 DB를 직접 조회하지 않는다.
- Gateway(8080)가 공개 GET `/api/v1/storefront/**`를 Store로 라우팅한다.
- `common:jpa`·`common:kafka`를 사용하고 `common:messaging`에는 의존하지 않는다(Store는 Outbox·Inbox를 두지 않음). 공통 운영 HTTP API는 `stove.messaging.ops.enabled=false`로 끄되 Kafka 재시도·DLT는 유지한다.

## 핵심 설계 결정

- **사본은 조회 전용이고 정본은 Catalog다.** productId를 PK로, productCode에 유일 제약을 두며 상품 ID·가격·제목을 독자적으로 발급·편집하지 않는다.
- **버전은 catalogVersion으로 판단한다.** 콘텐츠 버전과 독립적인 catalogVersion으로 신구를 정하고 시각으로 판단하지 않는다.
- **정상 변경은 이벤트, 복구는 자동.**(결정 66) Kafka 반영을 정상 경로로 유지하고 초기화 실패 재개·주기 대조·동일 버전 정본 교정을 자동으로 수행한다. 수동 갱신 API를 정상 운영 경로로 두지 않는다.
- **동일 버전 내용 차이는 검증한 정본으로 교정한다.**(결정 67 A) Kafka의 동일 버전 충돌 검사는 유지하고, 직접 조회·검증한 정본으로만 같은 catalogVersion의 사본을 교정한다.
- **준비 상태와 대조 작업 상태를 분리한다.** 초기 전체 동기화 1회 성공으로 준비 상태를 열고, 준비된 사본은 이후 대조가 실패해도 조회를 계속 제공한다.
- **장르는 순위 없는 집합이다.**(결정 70·75) 정렬한 불변 목록으로 전달해 순서 차이로 버전이 오르거나 동일 스냅샷이 충돌하지 않게 하고, 복수 장르 검색은 AND다. 기준 목록(`GenreCatalog`)은 ACTION·ADVENTURE·RPG·STRATEGY·SIMULATION·PUZZLE·SPORTS·RACING·RHYTHM·VISUAL_NOVEL 10개다(결정 72). 코드를 추가할 때는 수신 서비스(Review·Catalog·Store)를 먼저 배포한 뒤 Studio 선택지를 연다.

## 데이터 모델

| 테이블 | 내용 |
|---|---|
| `store_product` | productId(PK)·productCode(유일)·contentVersion·catalogVersion, 제목·소개·본문·썸네일·영상·가격·통화·빌드 버전·listed/saleStatus, 최초 공개 시각·갱신 시각 |
| `store_product_genre` | 상품별 중복 없는 장르 집합(부모 참조·삭제 연계) |
| `store_reconciliation` | 자동 교정의 변경 전후 JSON·정본 조회 시각·기록 시각(INSERTED/UPDATED/REPAIRED) |

Flyway는 V1에 `store_product`, V4에 `store_reconciliation`, V5에 `store_product_genre`를 추가했고 기존 SQL 이력은 수정하지 않는다.

## 사본 반영 규칙

| 입력 | 처리 |
|---|---|
| 없는 상품 | 사본 생성 |
| 더 높은 catalogVersion | 공개 필드 전체 교체 |
| 낮은 catalogVersion / 중복 / 역순 | 현재 사본 유지 |
| 같은 버전·같은 공개 데이터 | 변경 없이 성공 |
| 같은 버전·다른 공개 데이터 (Kafka) | 계약 위반으로 실패, 기존 사본 보존 |
| 같은 버전·다른 공개 데이터 (검증한 정본) | REPAIRED로 교정 |
| 상품 ID·상품 코드 연결 충돌 | 계약 위반으로 실패 |
| 콘텐츠 버전 동일·판매 상태 변경 | 반영 |

이벤트와 HTTP 상세 응답은 동일한 반영 규칙을 사용한다. 버전 비교·저장은 같은 트랜잭션에서 처리하고, 기존 행 잠금과 최초 삽입 유일 제약으로 경합을 제어하며 삽입 경합은 실패한 트랜잭션 전체를 재시도한다.

## API 계약

| 메서드 | 경로 | 역할 | 동작 |
|---|---|---|---|
| GET | `/api/v1/storefront/genres` | 공개 | 장르 코드·표시명·선택 가능 여부 |
| GET | `/api/v1/storefront/products` | 공개 | q·genres(AND)·sort·page·size로 검색·목록 |
| GET | `/api/v1/storefront/products/{productCode}` | 공개 | 현재 반영된 공개 상세 |

- 기본값 q 없음·`sort=NEWEST`·`page=0`·`size=20`, page >= 0, 1 <= size <= 100.
- 잘못된 입력 400, 준비 전 503, 준비 후 없는/숨긴 상품 코드 404.
- `ApiResponse`와 전용 DTO를 사용하고 목록은 `content`·`page`·`size`·`totalElements`·`totalPages`를 갖는다.

## 이벤트 계약

| 방향 | 이벤트 | 비고 |
|---|---|---|
| 수신 | `CatalogProductPublished`(`stove.catalog.v1`) | 그룹 `store-catalog-publication`, earliest. 봉투·본문 ID/유형·Kafka 키·양수 ID/버전·필수 공개 필드·장르 1~3개를 검증. 잘못된 입력은 공통 Kafka 재시도·DLT |
| 발행 | 없음 | Store는 업무 이벤트를 발행하지 않는다 |

## 외부 연동

| 포트 | 어댑터 | 용도 |
|---|---|---|
| Catalog 정본 조회 | HTTP 어댑터 | `/internal/v1/store-products`·`/internal/v1/store-products/{code}`를 `CATALOG_STORE_SERVICE_KEY`로 조회(숨긴 상품 포함). 내부 키는 이 두 GET에만 전달 |

- 정본 HTTP 경계는 필수 필드·정수 금액/버전·명시적 노출 값·코드 일치·페이지 메타데이터를 검증한다.
- 구 이벤트용 공통 스냅샷 기본값을 정본 HTTP 응답의 누락 필드에 적용하지 않는다.
- 네트워크/5xx/429는 일시 접근 실패로 분류하고 제공사 메시지·URL·키를 경고·오류 분류에 복사하지 않는다.
- HTTP 호출 중에는 Store DB 트랜잭션을 열지 않는다. 연결 3초·읽기 10초 제한, 리다이렉트는 따르지 않는다. 키가 없으면 공개 경로로 우회하지 않는다.
- Kafka의 과거 공개 사본 JSON 해석은 공통 스냅샷 규칙을 따른다: `listed`가 없으면 saleStatus 기준(AUTO 복원), saleStatus·catalogVersion이 모두 없으면 콘텐츠 버전·ON_SALE. 이런 과거 사본은 더 높은 catalogVersion의 중지·제한·숨김을 덮지 못한다.

## 유스케이스 구성

- `ApplyCatalogProduct` — 이벤트·HTTP 초기화의 공통 사본 반영 경로. `execute(CatalogProductSnapshot)`로 동일 버전 충돌 검사·상품 잠금을 유지.
- `SearchStoreProducts` — 제목·장르(AND)·정렬·페이지 검색. 장르·제목·최종 노출 조건을 DB 페이지·총건수 계산 전에 적용.
- `ReadStoreProduct` — 상품 코드별 상세.
- `ReconcileCatalogProduct` — 직접 조회한 Catalog 정본의 코드·필수 값·상품 ID/코드/최초 공개일을 검증해 정본 교정.
- `CatalogSynchronizationScheduler` — config에서 조립해 시작 직후부터 실행. 초기화 실패 시 자동 재시도하고 한 차례 전체 대조 성공 뒤 준비 상태를 연다.

런타임 이벤트는 `api.listener`의 `CatalogProductMapper.from(...)`으로 봉투·본문·키를 검증한 뒤 스냅샷만 전달한다. 컨트롤러·리스너·스케줄러는 해당 유스케이스를 호출한다.

## 동기화·복구 동작 요약

- 겹친 대조는 프로세스 내에서 하나로 합친다. 진행 위치·타이머·준비 상태는 메모리에 두고 재시작 시 전체 대조를 다시 수행한다. 교정 이력은 DB에 보존한다.
- 상품별 DB 잠금·삽입 경합 재시도·버전 검사로 변경을 보호하며 전역 단일 스캔·커서/임대는 이번 범위가 아니다.
- 준비 완료 후 Catalog가 중단돼도 저장한 사본으로 조회를 계속 제공하고, 매 조회마다 Catalog를 호출하거나 실패 시 폴백하지 않는다.
- 현재 Catalog는 공개 삭제를 제공하지 않으므로 공개 검색·상세에서 보이지 않았다는 이유로 사본을 삭제하지 않는다.

### 실행 설정

| 설정 | 기본값 | 의미 |
|---|---|---|
| `STORE_SYNC_ENABLED` | true | 자동 초기화·대조 실행 |
| `STORE_SYNC_POLL_INTERVAL_MS` | 1000 | 실행 기한 확인 간격 |
| `STORE_SYNC_RETRY_DELAY` | PT5S | 실패한 대조 종료 후 다음 재시도 간격 |
| `STORE_SYNC_RECONCILE_INTERVAL` | PT1M | 성공한 전체 대조 종료 후 다음 정기 대조 간격 |

기본 간격은 변경 가능한 구현 설정이며 갱신 지연 SLA가 아니다. 여러 상품의 대조는 각 시점에서 수행되므로 전체 Catalog의 원자적 스냅샷 전환을 보장하지 않는다.

## 관측

- Micrometer에 `stove.store.sync.ready`·`running`·`runs{result=success|failure}`·`last.success.timestamp`·`last.scanned`·`last.changed`·`last.failed`·`last.skipped.newer`를 등록한다.
- 지표 어댑터는 도메인 상태 공급자만 받고 서비스·프레임워크 객체 조립은 config가 맡는다.
- 현재 코드는 지표를 등록하고 공통 빌드에 Prometheus registry·metrics/prometheus 노출을 포함하지만, Store 보안 허용 목록에는 아직 `/actuator/prometheus`가 없다. 실제 경보 전송·대시보드/스크레이프 배포는 별도 운영 범위다.

## 사용 안내

- 실행: `DB_HOST`·`DB_PORT`·`DB_USER`·`DB_PASSWORD`, `CATALOG_BASE_URL`, Catalog와 같은 값의 `CATALOG_STORE_SERVICE_KEY`를 준다. 기존 개발 MySQL에는 관리자 계정으로 [스키마 준비 SQL](../../../tools/migrations/store/store-schema.sql)을 적용하고, 새 환경은 `infra/mysql/init`에 포함돼 있다.
- 검증: `./gradlew :apps:store:test :apps:store:integrationTest -Pstove.infraTest.maxParallel=1`, `python3 tools/verification/verify-store.py`(Auth·Gateway·Studio·Review·Catalog·Store와 인프라만, 포트 충돌 시 `STORE_TEST_PORT_OFFSET`). 출시·철회·판매/노출/다운로드 제한·사본 복구를 잇는 9개 서비스 여정은 `python3 tools/verification/verify-g4-operations.py`(기본 `G4_TEST_PORT_OFFSET`=20000, Linux 임시 포트 범위와 겹치면 시작 전 거절, 결제는 계약 픽스처)다.
- 조회 예: `GET /api/v1/storefront/products?q=게임&genres=ACTION&genres=RPG&sort=NEWEST&page=0&size=20`. 검색어의 `%`·`_`·`!`는 문자 그대로 검색한다.
- 복구 확인: 경고 로그, `stove.store.sync.*`의 마지막 성공 시각·실패 수, `store_reconciliation` 전후 기록을 본다. 식별 충돌·정본 계약 위반은 원인을 고치면 다음 자동 재시도에서 회복 여부를 확인한다. 개발 환경 전체 재구축은 Store를 멈추고 사본 테이블을 비운 뒤 시작한다.
