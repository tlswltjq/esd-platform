# Store MVP 재작성 계획·완료 기록

[문서 목록](README.md) · [Catalog 완료 범위](catalog-mvp-plan.md) · [유스케이스 작성 규칙](use-case-conventions.md)

2026-10-15 계획 수정·재작성 완료. 도메인과 코드 이해를 위한 아래 최소 범위를 구현하고 OCI에서 검증했다. 실제 결과는 [완료 기록](#재작성-완료-기록)을 따른다.

## 목표와 기준

최소한의 MVP를 직접 구현하고 실행하며 **도메인의 책임, 데이터 흐름과 코드를 설명할 수 있게 만드는 것**이 목표다.
방문자가 공개된 게임을 제목으로 찾고 목록·상세를 확인하는 흐름을 먼저 완성한다.
실제 사용과 학습에서 드러난 요구를 기준으로 검색 기능과 운영 구성을 발전시킨다.

| 모듈 | 이번 흐름에서 맡는 책임 |
|---|---|
| Studio | 작업본·빌드, 심사·출시 요청과 결과 기록 |
| Review | 제출 스냅샷의 수동 승인·반려 |
| Catalog | 공개 상품·현재 공개본의 정본과 공개 변경 이벤트 |
| Store | 공개 상품의 조회용 사본, 제목 검색·목록·상세 |
| Auth·Gateway | 기존 업무 인증·권한과 공개 GET 라우팅 |

완료된 Auth·Gateway·Studio·Review·Catalog의 계약을 기준으로 한다.
Store는 Catalog의 공개 API와 새 변경 이벤트만 사용하며 다른 서비스의 DB를 직접 조회하지 않는다.
미완료 Store의 기존 API·Elasticsearch·Redis·ProductChanged나 구매·다운로드 모듈의 요구는 설계 제약으로 삼지 않는다.

## 이번 MVP와 후속 개발

| 이번 MVP에 포함 | 최소 구현 |
|---|---|
| 목록·검색 | MySQL의 제목 부분 검색과 페이지 조회 |
| 정렬 | NEWEST, PRICE_ASC, PRICE_DESC |
| 상세 | 상품 코드로 현재 반영된 공개 정보 조회 |
| 변경 반영 | Catalog의 최초 공개·공개본 교체 이벤트 수신 |
| 초기 데이터 | Store 시작 시 기존 Catalog 목록·상세 API로 사본 채우기 |
| 단순 복구 | Store 재시작 시 처음부터 다시 읽어 누락된 사본 보충 |

이번 MVP는 소량의 개발·학습용 상품과 Store 단일 인스턴스를 전제로 한다.
다음 항목은 완료 조건에서 제외하고 후속 개발로 남긴다.

- Elasticsearch·Redis 캐시, 추천·인기 순위·개인화·장르/태그 필터·형태소 검색.
- featured API, 할인·쿠폰·구매 가능 여부·주문 견적·다운로드·운영자 상품 편집·비공개 전환.
- 별도 export API, 주기적 전체 대조, 동기화 진행 상태 저장과 중단 위치 재개, 다중 인스턴스 동기화 조율.
- 무중단 재구축, 전용 운영 화면·지표·장애 주입 실험, 대량 상품의 검색·복구 성능 보장.
- 서비스별 DB 계정·최소 권한 분리, 별도 서비스 인증, Kafka 생산자 권한 분리.

## 최소 사용자 흐름

1. 기존 Studio·Review·Catalog 흐름으로 게임을 공개한다.
2. Catalog가 공개본과 함께 기록한 상품 변경 이벤트를 발행한다.
3. Store가 공개 상품 사본을 저장한다.
4. 방문자가 Gateway를 통해 제목 검색·목록·상세를 조회한다.
5. 새 버전을 심사·출시하면 Catalog와 Store가 갱신된다. Studio 작업본만 수정하면 공개 사본은 바뀌지 않는다.

Store 시작 전에 공개된 상품은 기존 Catalog API로 읽어 같은 사본 반영 규칙을 적용한다.
초기 데이터는 HTTP로 가져오고 이후 변경은 이벤트로 받는 두 경로를 코드와 실행 예제로 확인한다.

## 저장 모델과 사본 반영

Store의 업무 엔티티는 **StoreProduct 하나**다.
Catalog의 productId를 PK로 사용하고 productCode에 유일 제약을 둔다.
contentVersion, 제목·소개·본문·썸네일·영상·가격·통화·빌드 버전, 최초 공개 시각·갱신 시각을 저장한다.
상품 ID를 자체 발급하거나 가격·제목을 독자적으로 편집하지 않는다.

| 입력 | 처리 |
|---|---|
| 없는 상품 | 사본 생성 |
| 더 높은 contentVersion | 공개 필드 전체 교체 |
| 낮은 contentVersion | 현재 사본 유지 |
| 같은 버전·같은 공개 데이터 | 변경 없이 성공 |
| 같은 버전·다른 공개 데이터 | 계약 위반으로 실패, 기존 사본 보존 |
| 상품 ID·상품 코드 연결 충돌 | 계약 위반으로 실패 |

이벤트와 HTTP 상세 응답은 동일한 반영 규칙을 사용한다.
상품 코드의 식별은 Catalog처럼 원문을 기준으로 하고 제목 검색의 대소문자 처리와 구분한다.
버전 비교·저장은 같은 트랜잭션에서 처리한다. 기존 행 잠금과 최초 삽입 유일 제약으로 경합을 제어하며,
삽입 경합이 발생하면 실패한 트랜잭션 전체를 재시도한다.
Store가 만드는 부수효과는 사본 한 건의 저장뿐이므로 같은 상품·버전의 반복 반영 자체를 멱등하게 만든다.
별도 Inbox는 두지 않는다. 중복 이벤트도 위 버전 규칙으로 성공 처리하고 Kafka 오프셋은 처리 성공 후 커밋한다.
실패하면 상품 변경을 롤백하고 재전달받는다. HTTP로 읽은 상품에는 가짜 이벤트를 만들지 않는다.
향후 사본 저장 외의 부수효과가 추가되면 별도 중복 처리 기록의 필요성을 다시 판단한다.

## Catalog에 추가할 변경 이벤트

Catalog의 기존 출시 처리·Studio 결과 계약과 공개 HTTP API는 유지하고,
`CatalogProductPublishedEvent` 하나를 `stove.catalog.v1`에 추가한다.
공통 계약에 eventType=`CatalogProductPublished`와 공개 값 `CatalogProductSnapshot`을 정의한다.

- 이벤트는 eventId·occurredAt·product를 갖고 공통 DomainEvent 계약을 따른다.
- product는 productId·productCode·contentVersion과 기존 Catalog 공개 상세 응답의 모든 공개 필드를 담는다.
- ownerSubject·storagePath·체크섬·심사/출시 요청 ID 등 내부 정보는 담지 않는다.
- 최초 공개 또는 더 높은 버전으로 현재 공개본을 교체한 경우에만 발행한다.
- 중복 요청·동일 버전의 동일 스냅샷·출시 거절에는 새 상품 변경 이벤트를 기록하지 않는다.
- 공개본 변경, 기존 Studio 결과 Outbox, 새 Store 변경 Outbox를 같은 Catalog 트랜잭션에서 확정한다.
- Kafka 키·Outbox aggregateId는 productCode다. 이벤트 시각 대신 contentVersion으로 신구를 판단한다.
- 이벤트와 HTTP 상세의 필드 의미·시각 정밀도를 맞춰 동일 버전이 직렬화 차이로 충돌하지 않게 한다.

Store는 새 그룹 `store-catalog-publication`, 최초 소비 위치 earliest로 수신한다.
구 ProductChanged 등 다른 이벤트 유형은 무시하고 새 유형만 처리한다.
봉투·본문 이벤트 ID/유형, Kafka 키·상품 코드, 양수 ID/버전과 필수 공개 필드를 검증한다.
잘못된 입력은 공통 Kafka 재시도·DLT 처리에 맡기며 별도 복구 프레임워크를 추가하지 않는다.

## 초기 동기화와 재시작

별도 export 계약을 만들지 않고 다음 완료된 Catalog API를 사용한다.

| 기존 API | 사용 목적 |
|---|---|
| GET /api/v1/products?page=0&size=100 | 상품 코드를 페이지로 읽기 |
| GET /api/v1/products/by-code/{productCode} | contentVersion을 포함한 공개 상세 읽기 |

목록에는 상세 필드와 버전이 없으므로 각 상품의 상세를 추가로 조회한다.
소량의 상품에서는 이 호출 수를 허용하고, HTTP DTO는 Store 어댑터에서 해석한다.
Catalog의 Java 구현 클래스나 DB 모델에 의존하지 않는다.

1. 새 변경 이벤트를 발행하는 Catalog를 먼저 준비하고 Store의 Kafka 수신을 시작한다.
2. Store 시작 시 한 번, 목록을 page=0부터 빈 페이지가 나올 때까지 순회한다.
3. 각 코드의 상세를 읽어 상품 단위 트랜잭션으로 반영한다. HTTP 호출 중에는 DB 트랜잭션을 열지 않는다.
4. 이벤트와 초기 동기화가 겹쳐도 버전 비교로 최신 사본을 유지한다.
5. 전체 순회가 성공하면 메모리의 준비 상태를 연다. 빈 Catalog의 순회 완료도 정상이다.

초기 동기화 전에는 업무 조회에 503을 반환한다. HTTP 연결·읽기에는 유한한 타임아웃을 설정한다.
조회나 반영이 실패하면 실패를 로그로 남기고 준비 상태를 열지 않는다.
Catalog나 입력 문제를 해결한 뒤 Store를 재시작하면 처음부터 다시 읽는다.
이미 저장한 사본은 보존하며 별도 StoreSyncState 테이블·커서 체크포인트·자동 재시도 스케줄러는 두지 않는다.

준비 완료 후 Catalog가 중단되어도 Store는 저장한 사본으로 조회를 계속 제공한다.
매 조회마다 Catalog를 호출하거나 실패 시 Catalog로 폴백하지 않는다.
목록 순회는 한 시점의 전체 스냅샷이 아니며, 순회 중 변경은 이벤트로 반영한다.
누락 자동 감지·주기적 복구나 무지연 일치는 보장하지 않는다. 누락을 확인하면 재시작으로 다시 읽는다.
전체 재구축이 필요한 개발 환경에서는 Store를 중단하고 사본 테이블을 초기화한 뒤 시작한다.
현재 Catalog는 공개 삭제를 제공하지 않으므로 조회에서 보이지 않았다는 이유로 사본을 삭제하지 않는다.

## 검색·조회 API

기존 Gateway 공개 GET 경로로 두 API를 제공한다.

| 메서드 | 경로 | 역할 |
|---|---|---|
| GET | /api/v1/storefront/products | q·sort·page·size로 검색·목록 조회 |
| GET | /api/v1/storefront/products/{productCode} | 상품 코드별 상세 조회 |

- 기본값은 q 없음, sort=NEWEST, page=0, size=20이다. page >= 0, 1 <= size <= 100으로 제한한다.
- q는 앞뒤 공백을 제거하고 최대 100자, 빈 값은 전체 목록으로 처리한다.
- 제목은 대소문자를 구분하지 않는 부분 일치로 검색한다. 파라미터 바인딩과 LIKE 특수 문자 이스케이프를 사용한다.
- NEWEST는 최초 공개 시각 내림차순, PRICE_ASC·PRICE_DESC는 가격 오름차순·내림차순이다.
  마지막 정렬 키는 모두 productId 내림차순이다. 재출시를 신작으로 취급하지 않는다.
- ApiResponse와 전용 DTO를 사용한다. 목록은 content·page·size·totalElements·totalPages를 갖는다.
- 상품 목록·상세의 공개 필드와 의미는 기존 Catalog 응답을 따른다. 내부 저장 경로와 소유자 정보는 노출하지 않는다.
- 잘못된 입력은 400, 준비 완료 후 없는 상품 코드는 404다.
- 가격은 Catalog의 KRW 정가이며 주문 금액이나 구매 가능 여부를 판정하지 않는다.

## 최소 보안과 실행 구성

기존 Auth의 인증, Studio 소유권, Review 판정 권한은 업무 규칙으로 유지한다.
Store 목록·상세는 익명 GET으로 허용하고 새 업무 쓰기·운영 API는 만들지 않는다.
Store 보안 설정은 이 조회 경로와 필요한 health·API 문서 경로만 허용하고 나머지는 닫는다.
초기 동기화는 이미 공개 허용된 Catalog 목록·상세 경로를 사용하므로 Catalog의 보안 경로 확장은 필요 없다.

MySQL 서버와 개발용 DB 계정은 기존 구성을 공유하고, 데이터 소유권을 구분하는 `stove_store` 스키마만 추가한다.
신규 환경 초기화 SQL과 기존 DB 환경의 스키마 생성·권한 부여 절차를 준비하고,
Store datasource와 Catalog 접속 주소를 로컬·Compose·독립 검증 환경에 연결한다.

Store는 common:jpa와 common:kafka를 사용하고 초기 Flyway에는 store_product만 만든다.
Store는 업무 이벤트를 발행하지 않고 별도 Inbox도 두지 않으므로 common:messaging에 의존하지 않는다.
따라서 Store의 Outbox 빈·빈 Outbox 테이블·릴레이 설정도 필요 없다. Catalog의 기존 Outbox는 유지한다.
Store의 공통 운영 HTTP API는 `stove.messaging.ops.enabled=false`로 끄되 Kafka 재시도·DLT 처리는 유지한다.
이번 작업을 위해 공통 메시징 구성을 재설계하지 않는다.

## 실행·테스트 환경: OCI

로컬은 코드·문서 편집과 작업본 동기화에 사용한다.
빌드, 단위·아키텍처 테스트, 통합 테스트와 서비스 실행·E2E는 모두 기존 OCI 인스턴스에서 수행한다.
SSH 대상과 원격 작업 경로는 기존 scripts/remote.sh 및 로컬 Git 설정을 사용하며 머신별 접속 값은 저장소에 기록하지 않는다.
remote.sh는 커밋 전 작업본도 rsync로 보내므로 테스트를 위해 커밋하거나 푸시할 필요가 없다.

구현 후 모듈 검증은 다음과 같이 원격 Gradle 경로로 실행한다.

```sh
./scripts/remote.sh gradle :apps:catalog:test :apps:store:test :apps:catalog:integrationTest :apps:store:integrationTest -Pstove.infraTest.maxParallel=1 --rerun-tasks
```

변경한 공통 계약과 완료 모듈의 영향 범위 회귀 검사도 같은 원격 경로로 실행한다.
통합 테스트는 Testcontainers의 임시 인프라·DB를 사용한다.
서비스 간 여정은 scripts/verify-catalog.py를 참고해 Store와 stove_store를 추가한 독립 검증 스크립트를 작성하고 OCI에서 실행한다.
Auth·Gateway·Studio·Review·Catalog·Store와 MySQL·Redis·Kafka·MinIO만 사용하며 기존 전체 앱 E2E를 실행하지 않는다.

기존 전체 앱·인프라 스택은 중지해 둬도 되며 데이터 볼륨은 보존한다.
검증에는 별도 임시 데이터·포트를 사용하고 종료 시 해당 실행이 만든 프로세스·컨테이너·볼륨만 정리한다.
테스트마다 현재 동기화한 소스를 빌드해 사용하며 기존 실행 스택이나 남아 있는 jar를 검증 근거로 삼지 않는다.
OCI의 로그·JUnit XML·실제 실행 결과를 기준으로 완료를 기록한다.

## 구현 구성과 순서

업무 클래스는 다음 네 유스케이스로 시작하고 [작성 규칙](use-case-conventions.md)을 따른다.
ApplyCatalogProduct는 사본 반영, SearchStoreProducts는 검색·목록, ReadStoreProduct는 상세,
SynchronizeCatalogProducts는 시작 시 Catalog 순회를 맡는다.
HTTP 연동은 core.port와 infrastructure에 목록·상세 조회 포트와 어댑터를 둔다.
컨트롤러·이벤트 리스너·시작 실행 클래스는 해당 유스케이스를 호출한다.

1. **계약·Catalog 변경:** 공개 스냅샷·이벤트를 정의하고 실제 공개 변경에 Outbox 기록을 추가한다.
2. **Store 사본:** 단일 테이블·Flyway와 버전 비교·중복·경합 처리를 구현한다.
3. **검색·상세:** 목록·검색·정렬·페이지·상세 API와 최소 공개 GET 설정을 구현한다.
4. **초기 데이터:** 기존 Catalog 목록·상세 클라이언트, 시작 시 전체 조회와 메모리 준비 상태를 구현한다.
5. **정리·검증:** 구 코드·테스트를 새 범위로 교체하고 OCI의 모듈 테스트·독립 검증 스크립트로 실제 사용자 흐름과 재시작을 확인한다.

구 ProductDocument·검색 저장소·포괄적 StoreService·캐시·색인 초기화·featured를 제거·교체한다.
Store의 Elasticsearch·Redis 의존성을 제거하되 다른 모듈의 공유 인프라는 유지한다.
구 색인·캐시는 이관하지 않고 Catalog에서 초기 사본을 읽는다. 구 데이터의 물리 삭제는 별도 작업이다.
구현한 API의 OpenAPI, 서비스·모델 설명과 실행 예제를 함께 갱신한다.

## 검증과 완료 기준

| 경계 | 이번 MVP에서 확인할 내용 |
|---|---|
| 도메인·단위 | 버전 비교·동일 버전 충돌·상품 식별 불변식, 검색 입력·정렬 규칙 |
| Catalog 통합 | 최초 공개·교체 시 이벤트, 중복·거절 시 추가 이벤트 없음, 공개본·Studio 결과·상품 변경 Outbox의 원자성 |
| Store 통합 | 실제 MySQL·Kafka에서 버전 비교·사본 저장의 원자성, 중복·역순·초기 삽입 경합과 실패 입력의 DLT |
| 초기 동기화 | 시작 전 공개된 상품·빈 Catalog·여러 페이지, 이벤트와 HTTP 경합, 실패 후 재시작 재조회 |
| 공개 API | 익명 조회, 준비 전 503·준비 후 빈 목록/404, 검색·한글·특수 문자·정렬·페이지·400 |
| 실제 흐름 | 기존 게임 공개→Store 검색·상세, 작업본 수정 시 사본 유지, 재심사·재출시 후 사본 갱신 |
| 실행·회귀 | 새 스키마 기동·기존 환경 추가 절차, Catalog 중단 중 준비된 Store 조회, 완료 모듈·공통 계약의 변경 영향 회귀 |

모듈 통합 테스트는 해당 서비스와 실제 인프라·계약 픽스처로 검증한다.
서비스 간 여정은 Auth·Gateway·Studio·Review·Catalog·Store와 필요한 인프라만 실행하는 별도 E2E로 확인한다.
미완료 구매·다운로드 모듈이나 기존 전체 E2E 성공은 완료 조건으로 삼지 않는다.
실제 실행 결과와 짧은 수동 예제를 남겨 정본·사본의 차이, 두 입력 경로와 버전 규칙을 설명할 수 있어야 한다.
구현·검증을 마친 뒤에만 Store를 재작성 완료 목록에 추가한다.

## 재작성 완료 기록

2026-10-15, StoreProduct 단일 테이블과 네 유스케이스로 재작성을 완료했다.
제목 검색·세 정렬·페이지·코드별 상세, Catalog 변경 이벤트와 기존 공개 API를 이용한 시작 시 동기화를 구현했다.
구 검색 색인·Redis 캐시·featured·프로모션 코드를 제거했다. Store는 Inbox·Outbox를 사용하지 않는다.

Catalog는 실제 공개본이 바뀔 때만 CatalogProductPublished를 추가하고 기존 Studio 결과 계약을 유지한다.
공개 시각을 DB의 마이크로초 정밀도에 맞춰 이벤트와 HTTP의 같은 버전 비교가 일치하도록 했다.
Store의 첫 삽입 경합은 새 트랜잭션 재시도로 처리하고 버전 비교·저장으로 반복 수신을 처리한다.
공통 아키텍처 이름 검사에는 새 유스케이스 동사 Search·Synchronize를 추가했다.

빌드·단위·아키텍처·통합·실제 서비스 실행은 모두 OCI에서 수행했다.
최종 회귀 검사는 --rerun-tasks로 실제 실행했고 JUnit XML을 집계했다.

| 모듈 | 단위·아키텍처 | 실제 인프라 통합 |
|---|---:|---:|
| Auth | 3 | 8 |
| Gateway | 87 | 1 |
| Studio | 46 | 6 |
| Review | 50 | 12 |
| Catalog | 37 | 14 |
| Store | 40 | 10 |
| common:kafka | 151 | 이번 변경에서 실행하지 않음 |
| common:archunit | 19 | 해당 없음 |
| common:messaging | 72 | 이번 변경에서 실행하지 않음 |

총 **556건 통과**, 실패·오류·건너뜀 0건이다. 최종 회귀 실행은 7분 18초였다.
Store 통합 검사는 실제 MySQL·Kafka로 초기 삽입 경합·중복·역순·계약 충돌·DLT,
한글·LIKE 특수 문자·대소문자 검색·동률 정렬·페이지·400/404/503·내부 필드 미노출을 확인했다.
OCI에서 Store OpenAPI 스냅샷을 생성해 가져온 뒤 갱신 플래그 없이 계약 일치를 재검증했다.
Catalog의 기존 V1~V11 마이그레이션은 변경하지 않았고 Store에만 V1을 추가했다.

[독립 검증 스크립트](../scripts/verify-store.py)는 OCI의 임시 MySQL·Redis·Kafka·MinIO와 여섯 앱으로 다음 **5단계를 모두 통과**했다.

1. 실제 로그인·업로드·Review 승인·Catalog 출시→Store 검색·상세, 작업본 격리와 재출시 반영.
2. Store 사본을 비우고 Kafka 리스너를 끈 채 재시작해 기존 HTTP API만으로 공개 사본 복원.
3. Catalog를 중단한 상태에서 준비된 Store의 목록·상세 조회 유지.
4. Catalog 중단 중 Store를 재시작했을 때 STORE_NOT_READY·503 유지.
5. Catalog 복구 후 Store를 다시 시작해 HTTP 초기화 성공과 공개본 일치 확인.

실행 로그·상품 JSON 증거는 스크립트가 출력하는 OCI 임시 디렉터리에 남고 생성한 검증 프로세스·컨테이너는 종료 시 정리했다.
기존 데이터 볼륨은 보존했다. 기존 개발 DB의 스키마 준비 SQL 실행과 운영 배포는 수행하지 않았다.
주기적 자동 대조·대규모 검색·다중 인스턴스·계정 권한 분리는 후속 개발로 남긴다.
실행·수동 확인과 재시작 절차는 [Store 실행 안내](store-guide.md)를 따른다.
