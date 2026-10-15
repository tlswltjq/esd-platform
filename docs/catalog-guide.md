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
202 응답 이후 Catalog 공개 조회와 Studio의 COMPLETED 반영 사이에는 이벤트 지연이 있다.
현재 공개본을 바꾸려면 Studio 작업본 변경 → 새 심사 승인 → 새 출시 요청을 수행한다.

## 독립 검증

JDK 21과 Docker가 필요하다. 기본 검증 포트가 사용 중이면 CATALOG_TEST_PORT_OFFSET을 지정한다.

```sh
./gradlew :apps:catalog:test :apps:catalog:integrationTest
python3 scripts/verify-catalog.py
```

Testcontainers가 Docker 소켓을 찾지 못하면 현재 Docker context의 endpoint를 DOCKER_HOST로 지정한다.
검증 스크립트는 Auth·Gateway·Studio·Review·Catalog와 MySQL·Redis·Kafka·MinIO만 별도로 실행한다.
종료 시 자신이 생성한 프로세스와 컨테이너·볼륨을 정리한다. 기존 로컬 스택을 변경하지 않는다.
실행 로그와 재시작 증거는 출력된 임시 디렉터리에 남는다.

검증은 실제 로그인·업로드·심사·출시·공개 조회, 작업본 격리, 재출시,
Catalog 재시작 전 미발행 결과 저장과 재시작 후 결과 전달을 포함한다.
브로커를 중단한 상태에서 Catalog의 발행 시도가 실패해도 Outbox가 PENDING으로 보존되고,
브로커 복구 후 Studio에 같은 요청 결과가 반영되는지 확인한다.

## 기존 DB 전환

1. 기존 Catalog의 모든 쓰기 프로세스·리스너·릴레이를 정지하고 DB를 백업한다.
2. Catalog DB에 [전환 SQL](../scripts/catalog-transition.sql)을 실행한다.
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
