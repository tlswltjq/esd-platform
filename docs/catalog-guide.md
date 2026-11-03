# Catalog 실행·출시 검증

[문서 목록](README.md) · [MVP 범위와 검증](catalog-mvp-plan.md) · [모델](domain/models/catalog-store.md)

Catalog는 Studio의 승인된 출시 요청을 받아 공개 상품을 확정한다. 상품 생성·수정용 HTTP API는 없다.
공개 상세정보·가격·빌드 버전은 출시 요청의 불변 스냅샷에서 가져온다.

## 실행과 API

기본 포트는 8081이다. Gateway의 `/api/v1/products/**` GET 라우트로 익명 조회한다.

```http
GET /api/v1/products?page=0&size=20
GET /api/v1/products/by-code/{productCode}
```

목록 data는 content·page·size·totalElements·totalPages를 포함한다.
page는 0 이상, size는 1~100이다. 상세에는 저장 경로·소유자 subject·내부 요청 ID가 노출되지 않는다.
기존 ID별 조회·견적·프로모션·재색인·판매 시작/중지 API는 제공하지 않는다.

처음 공개하려면 Auth의 CREATOR로 Studio 프로젝트를 생성하고 자료·빌드를 등록한다.
Review에서 현재 버전의 승인을 받은 뒤 Studio의 `POST /api/v1/studio/projects/{id}/release-requests`를 호출한다.
본문에는 논리적 명령 requestId와 기대 contentVersion을 전달한다. Studio의 프로젝트별 진행 중 출시는 1건이다.
202 응답 이후 Catalog 접수·공개 조회·Studio의 COMPLETED 반영 사이에는 비동기 지연이 있다.
현재 공개본을 바꾸려면 Studio 작업본 변경 → 새 심사 승인 → 새 출시 요청을 수행한다.

Catalog는 원요청·불변 스냅샷과 Inbox를 먼저 내구 저장하고 별도 자동 작업에서 공개한다.
공개본·출시 결과·상품 변경 Outbox와 작업 완료는 같은 트랜잭션으로 저장한다.
실패는 원래 요청으로 재시도하며 기본 실패 대기는 5·15·30·60초(이후 60초)다.
기본 poll 간격은 200ms, batch-size는 20(1~100)이다.
설정은 stove.catalog.publication.poll-interval-ms·batch-size·worker-enabled다.
고정 철회 유예시간이나 추가 공개 버튼은 없으며 poll 간격은 최소 철회 시간을 보장하지 않는다.

창작자의 출시 철회는 [Studio 철회 API](studio-guide.md#심사출시-요청-철회)로 접수한다.
Catalog에서 철회가 먼저 확정되면 해당 요청은 WITHDRAWN이며 새 공개를 하지 않는다.
공개가 먼저 확정되면 철회 결과에 기존 COMPLETED/REJECTED를 반환한다. 원요청보다 먼저 온 철회도 보존한다.
철회는 이전 공개본·승인 근거·기존 판매/노출/다운로드 설정을 보존한다.

ADMIN은 Gateway를 통해 다음 읽기 전용 no-store API로 미완료 작업과 개별 이력을 확인한다.
목록은 PENDING 작업이고 page·size(최대 100)를 지원한다. 내부 스냅샷·저장 경로는 응답하지 않는다.

~~~http
GET /api/v1/admin/catalog-release-jobs
GET /api/v1/admin/catalog-release-jobs/{requestId}
~~~

state는 작업 처리 상태이므로 작업 COMPLETED만으로 공개 성공을 단정하지 않는다.
철회로 종료된 요청도 작업은 처리 완료될 수 있다. 출시의 정본 결과와 Studio 요청 이력을 함께 확인한다.
실패 시 attemptCount·lastError·nextAttemptAt과 원요청 sourceEventId를 확인하고 원본 Outbox·DLT 전달 상태를 조사한다.
지연만으로 성공·실패를 확정하거나 새 요청 ID로 바꿔 복구하지 않는다.

## 독립 검증

JDK 21과 Docker가 필요하다. 기본 검증 포트가 사용 중이면 CATALOG_TEST_PORT_OFFSET을 지정한다.

```sh
./gradlew :apps:catalog:test :apps:catalog:integrationTest
python3 tools/verification/verify-catalog.py
CATALOG_WITHDRAWAL_CHECK=true python3 tools/verification/verify-catalog.py
```

Testcontainers가 Docker 소켓을 찾지 못하면 현재 Docker context의 endpoint를 DOCKER_HOST로 지정한다.
검증 스크립트는 Auth·Gateway·Studio·Review·Catalog와 MySQL·Redis·Kafka·MinIO만 별도로 실행한다.
종료 시 자신이 생성한 프로세스와 컨테이너·볼륨을 정리한다. 기존 로컬 스택을 변경하지 않는다.
실행 로그와 재시작 증거는 출력된 임시 디렉터리에 남는다.

검증은 실제 로그인·업로드·심사·출시·공개 조회, 작업본 격리, 재출시,
Catalog 재시작 전 미발행 결과 저장과 재시작 후 결과 전달을 포함한다.
브로커를 중단한 상태에서 Catalog의 발행 시도가 실패해도 Outbox가 PENDING으로 보존되고,
브로커 복구 후 Studio에 같은 요청 결과가 반영되는지 확인한다.
CATALOG_WITHDRAWAL_CHECK=true는 실제 심사/출시 철회·동일 자료 재신청·같은 승인본 재출시,
Catalog 프로세스 재시작 후 저장된 공개 작업 재개와 기본 공개 여정을 실행한다.
검증은 현재 사용자 지정 환경에서 격리 실행하고 기존 자원은 보존한다.

## 단일 요청 제약과 새 이벤트 전환

Studio V26과 Review V11은 프로젝트별 활성 심사 유일성을, Studio V26은 활성 출시 유일성을 추가한다.
기존 REQUESTED가 프로젝트별로 여러 건이면 이행이 실패한다. 적용 전에 대상을 대조하고 임의 삭제·자동 최신 선택을 하지 않는다.
Catalog V15는 철회 이력·요청 잠금·내구 공개 작업을 추가한다.
이번 신규 이벤트 소비자가 준비되기 전에 Studio가 철회 이벤트를 발행하지 않도록 교체 순서를 맞춘다.
Review/Catalog 철회 요청 소비와 Studio 철회 결과 소비를 모든 대상 인스턴스에서 준비한 뒤 새 명령 경로를 사용한다.
Studio 제출 POST의 새 필수 본문과 호출부도 함께 교체한다. 실제 운영 DB 전환·공유 배포는 독립 검증과 별도다.

## 기존 DB 전환

1. 기존 Catalog의 모든 쓰기 프로세스·리스너·릴레이를 정지하고 DB를 백업한다.
2. Catalog DB에 [전환 SQL](../tools/migrations/catalog/catalog-transition.sql)을 실행한다.
   구 미발행 Outbox 원본을 catalog_legacy_outbox_archive에 보존하고 기존 행을 DEAD로 격리한다.
   스크립트는 반복 실행 가능하며 마지막 unexpected_legacy_pending 값이 0이어야 한다.
3. 새 Catalog를 기동하면 Flyway V11이 두 테이블을 추가한다. V1~V10과 구 업무 테이블은 유지한다.
4. Studio의 studio_release_request에서 id·request_event_id·project_id·content_version·status를 추출하고,
   Catalog의 catalog_release에서 release_request_id·request_event_id·project_id·content_version·status를 별도로 추출해 대조한다.
   애플리케이션에서 서비스 간 DB 조인을 추가하지 않는다.
5. Catalog에 없는 요청은 Studio Outbox 또는 Kafka에 보존된 원본 ProjectReleaseRequested를 재전달한다.
   원본 eventId·occurredAt·releaseRequestId·reviewRequestId·snapshot, Kafka 키(productCode), eventId·eventType 헤더를 유지한다.
   새 이벤트 ID로 재작성하면 Studio의 결과 상관관계가 깨진다.
6. 이미 Catalog가 처리한 요청은 입력을 반복해도 결과 Outbox가 추가되지 않는다.
   Studio에 결과가 없다면 Catalog의 기존 결과 Outbox 전달 상태·DLT를 조사해 기존 결과를 복구한다.
7. 복구 원본이 없는 요청은 누락으로 기록한다. 현재 Studio 작업본으로 과거 스냅샷을 추측해 만들지 않는다.

구 자료의 자동 공개 이관이나 물리 삭제는 수행하지 않는다.
격리한 구 Outbox를 공통 DEAD 일괄 재큐잉으로 되살리지 않는다.
실제 운영 DB 전환 실행은 이 코드 작업의 독립 검증과 별개다.

## Store 연동 확장

2026-10-15 Store MVP에서 최초 공개·상위 버전 공개본 교체 시 CatalogProductPublished를 추가했다.
공개 데이터와 Studio 결과·Store 변경 Outbox는 같은 트랜잭션으로 저장한다.
중복 요청·동일 버전·거절에는 새 Store 변경 이벤트를 만들지 않는다. 기존 HTTP API·Studio 결과 필드는 유지한다.
Store는 기존 공개 목록·상세 API로 초기 사본을 읽으므로 export API는 추가하지 않았다.
[Store 실행·검증](store-guide.md)과 [완료 기록](store-mvp-plan.md#재작성-완료-기록)을 따른다.
