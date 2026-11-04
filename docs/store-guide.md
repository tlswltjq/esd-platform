# Store 실행·검색·사본 확인

[검색·목록 노출 계약](catalog-listing-policy-plan.md): 기본 AUTO는 판매 가능 상품만 검색·목록에 포함한다. 판매자의 명시적 노출/숨김과 관리자의 강제 숨김을 별도로 반영한다. [공개 상세 B](public-product-detail-plan.md)에 따라 최종 listed=false인 사본의 공개 상세도 404다. 초기 동기화를 위해 Catalog·Store에 같은 전용 CATALOG_STORE_SERVICE_KEY를 준비해야 한다.

[문서 목록](README.md) · [MVP 계획·검증](store-mvp-plan.md) · [상품 모델](domain/models/catalog-store.md)

Store는 Catalog 공개 상품의 검색·조회 사본을 MySQL에 저장한다.
정본과 실제 공개 결정은 Catalog에 있으며 Store에서 상품을 직접 편집하지 않는다.

2026-11-03 [자동 동기화·복구](store-auto-reconciliation-plan.md)를 구현했다. 정상 변경은 Kafka로 반영하고 초기화 실패·누락·구버전은 자동 재시도/대조하며, 검증한 Catalog 정본으로 같은 버전의 내용 차이도 교정한다. 교정과 감사는 원자 저장한다.

## 실행 환경

2026-11-03 현재 사용자 지시에 따라 코드·문서 편집은 로컬에서, 빌드·테스트·서비스 실행과 인프라 검증은 `ssh sng` OCI의 격리 자원에서 수행한다. 2026-11-02 OrbStack 지정은 환경 변경 전 기록이다. 기존 컨테이너·볼륨과 공유 자료를 보존한다. 아래 명령도 지정된 OCI 격리 작업본에서 실행한다.

```sh
./gradlew :apps:store:test :apps:catalog:test :common:kafka:test
./gradlew :apps:store:integrationTest :apps:catalog:integrationTest -Pstove.infraTest.maxParallel=1
```

저장소 루트에서 `python3 tools/verification/verify-store.py`를 실행한다.
이 스크립트는 현재 소스로 여섯 앱을 빌드하고 임시 MySQL·Redis·Kafka·MinIO를 띄운다.
기존 전체 스택을 올리지 않으며, 자신이 생성한 프로세스와 임시 데이터만 종료 시 정리한다.
실행 로그·JSON 증거 경로는 출력되는 임시 디렉터리다. 포트가 겹치면 STORE_TEST_PORT_OFFSET을 지정한다.
Gradle 의존성과 테스트 이미지를 처음 준비하는 경우 네트워크 접근이 필요하다.

새 심사·출시 철회와 판매·노출·다운로드 제한까지 연결하는 검증은 `python3 tools/verification/verify-g4-operations.py`를 사용한다. 현재 12단계는 장르만 수정한 뒤 심사/출시·AND 검색과 사본 누락/변조 자동 복구·감사와 같은 PID의 초기화 회복까지 포함한다. [초기 G4 운영 여정](g4-operations-verification.md)과 [자동 복구 결과](store-auto-reconciliation-plan.md)·[장르 구현/검증 기록](store-genre-filter-plan.md)을 구분한다. 결제 결과는 계약 픽스처이며 외부 PG를 실행하지 않는다.

기존 개발 MySQL에서는 [스키마 준비 SQL](../tools/migrations/store/store-schema.sql)을 관리자 계정으로 적용한다.
새 환경은 infra/mysql/init의 초기화 SQL에 포함돼 있다. 기존 볼륨은 삭제하지 않는다.
Store는 DB_HOST·DB_PORT·DB_USER·DB_PASSWORD와 CATALOG_BASE_URL을 사용한다.
기본 포트는 8087이며 Flyway V1은 store_product 한 테이블을 만든다.
V4는 기존 사본을 보존하며 store_reconciliation 감사 테이블을 추가한다. V5는 store_product_genre 관계와 장르 검색 인덱스를 추가한다.

## 공개 API

Gateway를 통해 익명 GET으로 조회한다.

```http
GET /api/v1/storefront/genres
GET /api/v1/storefront/products?q=게임&genres=ACTION&genres=RPG&sort=NEWEST&page=0&size=20
GET /api/v1/storefront/products/{productCode}
```

장르 목록은 코드·표시명·선택 가능 여부를 제공한다. 상품 목록/상세에는 genres 배열이 포함된다. 복수 genres는 모두 포함하는 AND이며 제목·노출 조건과 함께 DB 페이지와 총건수 계산 전에 적용한다. 생략하면 모든 노출 상품을 검색한다. 알려진 선택 중지 장르의 기존 상품도 검색할 수 있으며 알 수 없는 코드·중복·빈 조건은 400이다.

정렬은 NEWEST·PRICE_ASC·PRICE_DESC, size는 1~100이다. NEWEST는 최초 공개 시각 기준이다.
검색어는 제목 부분 일치이며 앞뒤 공백을 제외해 100자까지다. `%`, `_`, `!`도 문자 그대로 검색한다.
목록은 ApiResponse.data 안에 content·page·size·totalElements·totalPages를 반환한다.
상세의 공개 필드는 Catalog 상세와 같으며 내부 저장 경로나 소유자 정보는 포함하지 않는다.
잘못된 입력은 400, 준비 후 없거나 숨긴 상품은 404, 초기 동기화 미완료는 STORE_NOT_READY·503이다.

## 두 가지 사본 입력 경로

백그라운드 작업이 시작 직후 전용 키로 보호한 Catalog `/internal/v1/store-products` 전체 목록을 페이지로 읽고 `/internal/v1/store-products/{code}`에서 숨긴 상품까지 버전·본문·listed를 가져온다. 공개 상세로 우회하지 않는다. 최초 전체 대조가 성공하기 전에는 STORE_NOT_READY·503이다. 키·연결·응답 문제로 실패하면 자동 재시도하며 원인이 해소된 뒤 재시작 없이 준비 상태가 열린다.
준비된 Store도 정기적으로 전체 대조한다. 배경 대조가 실패해도 이미 준비된 사본의 조회는 유지한다. HTTP 호출 중에는 DB 트랜잭션을 유지하지 않고, 반영할 때 상품별 잠금으로 최신 버전을 다시 확인한다.

이후 최초 공개·재출시는 stove.catalog.v1의 CatalogProductPublished 이벤트로 반영한다.
새 그룹 store-catalog-publication이 earliest부터 읽고 구 ProductChanged는 무시한다.
Kafka는 ApplyCatalogProduct의 기존 버전 규칙을 사용한다. 더 높은 정상 버전만 교체하고 낮은 버전·동일 내용은 유지한다. 같은 버전의 다른 내용과 식별 충돌은 거절한다.
직접 조회한 정본은 별도 ReconcileCatalogProduct 경로에서 검증한다. 같은 버전의 사본 내용 차이는 자동 교정하며 이미 더 최신인 사본은 보존한다. 상품 ID·코드·최초 공개일의 충돌과 정본 계약 위반을 강제로 덮어쓰지 않는다. 변경 전후 공공 상품 스냅샷·버전·조회/기록 시각은 사본 변경과 같은 트랜잭션에 저장한다.
이벤트 실패는 공통 Kafka 재시도·DLT로 처리한다.

Store는 사본 저장만 수행하므로 버전 비교·저장을 같은 트랜잭션에서 실행하는 것으로 반복 입력을 처리한다.
별도 Inbox·Outbox·업무 이벤트 발행은 없다. 정상 처리 후 오프셋을 커밋하기 전 종료돼도 재전달이 사본을 바꾸지 않는다. 자동 대조의 교정 이력은 별도 store_reconciliation에 저장한다.

자동 작업은 STORE_SYNC_ENABLED(기본 true), STORE_SYNC_POLL_INTERVAL_MS(1000), STORE_SYNC_RETRY_DELAY(PT5S), STORE_SYNC_RECONCILE_INTERVAL(PT1M)으로 설정한다. 간격은 실행 종료 후부터 적용하며 전체 대조 시간·장애를 포함하는 지연 SLA가 아니다. 프로세스 내 대조는 중복 실행하지 않고, 재시작하면 전체 대조를 다시 시작한다. 상세 설정·지표·범위는 [자동 동기화 계획](store-auto-reconciliation-plan.md)을 따른다.

## 수동 확인과 복구

1. Studio에서 프로젝트·자료·빌드를 준비하고 Review에서 승인한 뒤 출시를 요청한다.
2. Catalog 공개 상세와 Store 상세가 일치하고 제목 검색에 나타나는지 확인한다.
3. Studio 작업본만 수정했을 때 Store가 기존 공개본을 유지하는지 확인한다.
4. 다시 심사·출시하면 같은 상품 ID·최초 공개 시각을 유지하면서 제목·가격·버전이 갱신되는지 확인한다.
5. Store를 재시작하면 시작 전 상품을 숨김 여부와 관계없이 보호한 내부 HTTP API로 읽는다. Kafka 수신을 끈 독립 검증 단계로 이 경로를 구분한다. 숨긴 사본은 보존하고 공개 목록·상세에서는 제외한다.

준비된 Store는 Catalog가 일시 중단돼도 저장한 사본을 조회한다. Catalog가 중단된 상태에서 새로 시작한 Store는 503을 유지한다.
누락·구버전·같은 버전의 내용 차이는 자동 대조로 보완된다. 경고 로그·동기화 지표의 마지막 성공 시각/실패 유형과 store_reconciliation의 교정 전후를 확인한다. 식별 충돌·정본 계약 위반은 원인을 조사하고 해결하면 다음 자동 재시도에서 회복 여부를 확인한다. 사본의 강제 버전 하향·자동 삭제는 하지 않는다.
