# Store 실행·검색·사본 확인

[문서 목록](README.md) · [MVP 계획·검증](store-mvp-plan.md) · [상품 모델](domain/models/catalog-store.md)

Store는 Catalog 공개 상품의 검색·조회 사본을 MySQL에 저장한다.
정본과 실제 공개 결정은 Catalog에 있으며 Store에서 상품을 직접 편집하지 않는다.

## 실행 환경

코드·문서는 로컬에서 수정하고 빌드·모든 테스트·서비스 실행은 OCI에서 한다.
기존 scripts/remote.sh의 SSH·작업 디렉터리 설정을 사용한다. 실제 접속 값은 로컬 Git 설정에만 둔다.

```sh
./scripts/remote.sh gradle :apps:store:test :apps:catalog:test :common:kafka:test
./scripts/remote.sh gradle :apps:store:integrationTest :apps:catalog:integrationTest -Pstove.infraTest.maxParallel=1
./scripts/remote.sh sync
```

동기화한 원격 저장소 루트에서 `python3 scripts/verify-store.py`를 실행한다.
이 스크립트는 OCI에서 현재 소스로 여섯 앱을 빌드하고 임시 MySQL·Redis·Kafka·MinIO를 띄운다.
기존 전체 스택을 올리지 않으며, 자신이 생성한 프로세스와 임시 데이터만 종료 시 정리한다.
실행 로그·JSON 증거 경로는 출력되는 임시 디렉터리다. 포트가 겹치면 STORE_TEST_PORT_OFFSET을 지정한다.
Gradle 의존성과 테스트 이미지를 처음 준비하는 경우 원격에서 네트워크 접근이 필요하다.

기존 개발 MySQL에서는 [스키마 준비 SQL](../scripts/store-schema.sql)을 관리자 계정으로 적용한다.
새 환경은 infra/mysql/init의 초기화 SQL에 포함돼 있다. 기존 볼륨은 삭제하지 않는다.
Store는 DB_HOST·DB_PORT·DB_USER·DB_PASSWORD와 CATALOG_BASE_URL을 사용한다.
기본 포트는 8087이며 Flyway V1은 store_product 한 테이블을 만든다.

## 공개 API

Gateway를 통해 익명 GET으로 조회한다.

```http
GET /api/v1/storefront/products?q=게임&sort=NEWEST&page=0&size=20
GET /api/v1/storefront/products/{productCode}
```

정렬은 NEWEST·PRICE_ASC·PRICE_DESC, size는 1~100이다. NEWEST는 최초 공개 시각 기준이다.
검색어는 제목 부분 일치이며 앞뒤 공백을 제외해 100자까지다. `%`, `_`, `!`도 문자 그대로 검색한다.
목록은 ApiResponse.data 안에 content·page·size·totalElements·totalPages를 반환한다.
상세의 공개 필드는 Catalog 상세와 같으며 내부 저장 경로나 소유자 정보는 포함하지 않는다.
잘못된 입력은 400, 준비 후 없는 상품은 404, 초기 동기화 미완료는 STORE_NOT_READY·503이다.

## 두 가지 사본 입력 경로

시작 시 기존 Catalog 목록 API를 페이지로 읽고, 각 상품의 상세 API로 버전·본문을 가져온다.
빈 목록까지 읽으면 메모리 준비 상태가 열린다. HTTP 호출 중에는 DB 트랜잭션을 유지하지 않는다.
연결·읽기 실패나 계약 충돌 시 503을 유지하고 로그에 원인을 남긴다. 원인 해결 후 Store를 재시작한다.

이후 최초 공개·재출시는 stove.catalog.v1의 CatalogProductPublished 이벤트로 반영한다.
새 그룹 store-catalog-publication이 earliest부터 읽고 구 ProductChanged는 무시한다.
두 경로는 ApplyCatalogProduct의 같은 버전 규칙을 사용한다. 더 높은 버전만 교체하고 낮은 버전·동일 내용은 유지한다.
같은 버전에 다른 내용이나 상품 식별 충돌이 오면 기존 사본을 보존하고 실패 처리한다.
이벤트 실패는 공통 Kafka 재시도·DLT로 처리한다.

Store는 사본 저장만 수행하므로 버전 비교·저장을 같은 트랜잭션에서 실행하는 것으로 반복 입력을 처리한다.
별도 Inbox·Outbox·업무 이벤트 발행은 없다. 정상 처리 후 오프셋을 커밋하기 전 종료돼도 재전달이 사본을 바꾸지 않는다.

## 수동 확인과 복구

1. Studio에서 프로젝트·자료·빌드를 준비하고 Review에서 승인한 뒤 출시를 요청한다.
2. Catalog 공개 상세와 Store 상세가 일치하고 제목 검색에 나타나는지 확인한다.
3. Studio 작업본만 수정했을 때 Store가 기존 공개본을 유지하는지 확인한다.
4. 다시 심사·출시하면 같은 상품 ID·최초 공개 시각을 유지하면서 제목·가격·버전이 갱신되는지 확인한다.
5. Store를 재시작하면 시작 전 공개 상품도 기존 HTTP API로 읽는다. Kafka 수신을 끈 독립 검증 단계로 이 경로를 구분한다.

준비된 Store는 Catalog가 일시 중단돼도 저장한 사본을 조회한다. Catalog가 중단된 상태에서 새로 시작한 Store는 503을 유지한다.
주기적 자동 대조는 없으며 누락 확인 시 재시작해 다시 읽는다.
동일 버전 충돌이나 잘못된 고버전 사본은 원인을 먼저 해결하고, 필요하면 개발용 Store를 중단해 사본만 초기화한 뒤 다시 시작한다.
초기화는 해당 Store 데이터에만 적용한다. 자동 기동 시 사본을 삭제하지 않는다.
