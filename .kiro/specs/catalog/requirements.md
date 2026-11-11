# 요구사항: Catalog (승인 대조 뒤 공개본 확정, 공개 상품·판매/노출/다운로드 제한의 정본)

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다. 이벤트의 뜻·키·순서·소비 그룹·흐름 단계·모듈 간 불변식은 플랫폼 스펙의 행을 따르고, 여기에는 Catalog가 그 계약을 어떻게 지키는지만 적는다. 코드와 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"에 있다.

## 개요

Catalog는 Studio가 보낸 출시 요청을 내구 접수하고, Review에게서 직접 받은 승인 사실과 대조한 뒤에만 승인된 스냅샷을 불변 공개본으로 확정한다([결정 167](../../../docs/decisions.md#catalog-verifies-approval)). 현재 공개본(상세·가격·빌드 참조·소유자)·판매 상태·검색/목록 노출·다운로드 제한의 정본을 갖고([플랫폼 P1.4-6·P1.4-7](../platform/requirements.md)), 방문자에게 공개 목록·상세를 익명 GET으로 준다. Store는 Catalog의 공개 사본 이벤트와 내부 전체 조회로 사본을 만들고, Order는 새 주문·승인 접수의 판매 근거를, Download는 현재 공개 빌드와 다운로드 제한을, Settlement는 불변 소유자를 Catalog 내부 조회로 읽는다.

출시 처리는 "접수 → (승인 대기) → 공개 대기 → 공개 확정 / 거절 / 철회 / 정지 → 운영자 재개·종결"의 명시적 상태 모델을 따른다([결정 62](../../../docs/decisions.md#catalog-durable-publication-work)·결정 167·[결정 168](../../../docs/decisions.md#publication-blocked-closure)).

## 용어

- **출시 요청**: Studio `ProjectReleaseRequested`의 `releaseRequestId`와 불변 `ProjectSnapshot`. Catalog 안에서 공개 작업(`catalog_publication_job`)과 출시 결과(`catalog_release`)로 추적한다.
- **공개 작업(`catalog_publication_job`)**: 출시 요청의 처리 진행 상태. `AWAITING_APPROVAL`(승인 대기) · `PENDING`(공개 대기·일시 오류 재시도 대기) · `BLOCKED`(재시도로 풀리지 않는 실패로 정지) · `COMPLETED`(처리 끝 — 결과는 출시 결과가 정본). 작업 `COMPLETED`는 공개 성공을 뜻하지 않는다.
- **출시 결과(`catalog_release`)**: 출시 요청당 하나인 최종 결과. `COMPLETED`(공개 확정, 같은 승인본 재출시 포함) · `REJECTED`(거절, 사유 enum) · `WITHDRAWN`(공개 확정 전 철회) · `CLOSED`(운영자 종결). 행이 없으면 아직 결과가 없다.
- **공개본**: 결과가 `COMPLETED`인 출시 결과의 불변 스냅샷. `catalog_product.current_release_request_id`가 현재 공개본 한 건을 가리킨다.
- **승인 보관(`catalog_review_approval`)**: Review가 낸 `ProjectReviewApproved`의 `(reviewRequestId, projectId, contentVersion, productCode, 스냅샷 값)` 불변 사본. 출시 요청과 대조하는 근거다(결정 167).
- **contentVersion**: Studio가 발급한 Project 자료·빌드의 콘텐츠 버전. 공개본 신구 판정의 유일한 기준이다([플랫폼 P3.5-2](../platform/requirements.md)).
- **catalogVersion**: 공개 사본(공개본·판매·노출)이 바뀔 때마다 1 이상 늘어나는 상품별 단조 버전. 현재 공개본 `contentVersion`보다 작지 않다([플랫폼 P3.5-7](../platform/requirements.md)).
- **판매자 판매 의사(`sale_status`)**: `ON_SALE` / `STOPPED`. **관리자 판매 제한(`platform_restricted`)**: 독립 Boolean. **공개 판매 상태(saleStatus)**: 둘에서 생성하는 값 — 제한이면 `BLOCKED`, 아니면 판매자 의사([결정 48](../../../docs/decisions.md#separate-seller-and-platform-sale-controls)).
- **노출 선호(`listing_preference`)**: `AUTO` / `LISTED` / `UNLISTED`. **관리자 숨김(`platform_listing_hidden`)**: 독립 Boolean. **listed**: `!platform_listing_hidden && listing_preference.isListed(saleStatus)`([결정 51](../../../docs/decisions.md#independent-listing-with-sale-default)).
- **다운로드 제한(`download_restricted`)**: 관리자의 독립 원하는 상태. **저장소 적용 상태(`download_storage_status`)**: 그 원하는 상태를 객체 저장소에 적용했는지(`NOT_REQUIRED` · `PENDING` · `APPLIED` · `FAILED`)([결정 50](../../../docs/decisions.md#independent-download-restriction), 플랫폼 P1.4-7).
- **ownerSubject**: 상품 소유자. 최초 공개 스냅샷의 Auth JWT `sub`이며 바뀌지 않는다(플랫폼 P1.4-6).

## 요구사항

### R1. 출시 요청 접수와 공개본 확정
**사용자 스토리:** 플랫폼으로서, Studio의 출시 요청을 Review 승인과 대조한 뒤 불변 공개본으로 확정하고 싶다. 그래야 승인된 자료만 판매되고 방문자가 공개 상품을 본다.

#### 수용 기준
1. WHEN `ProjectReleaseRequested`(`stove.studio.v1`, 그룹 `catalog-project-release`)를 받으면 THEN Catalog SHALL `api.listener`의 `ReleaseSubmissionMapper.from`에서 봉투·본문 `eventId`·`eventType` 일치와 Kafka 키 = `snapshot.productCode`를 검증하고, 내부 입력 `ReleaseSubmission` 생성 때 요청·심사·Project·빌드 ID 양수, 소유자, 양수 `contentVersion`, 상세 길이, 가격 0 이상, 통화 `KRW`, 빌드 크기 양수, SHA-256 체크섬, 저장 경로, 장르 1~3개를 검증한다. IF 검증이 실패하면 THEN Catalog SHALL 영구 실패로 실패 보관 표에 보내고 Inbox를 표시하지 않는다([플랫폼 P2.6-3·P3.1-6](../platform/requirements.md)).
2. WHEN 검증된 출시 요청을 처음 받으면 THEN Catalog SHALL 공개하지 않고 원요청·불변 스냅샷·Inbox를 공개 작업으로 먼저 저장한다(결정 62, 플랫폼 P3.4-4). 작업의 처음 상태는 R11에 따라 `PENDING`(맞는 승인 보관 있음) 또는 `AWAITING_APPROVAL`(승인 보관 없음)이다.
3. WHEN 공개 작업자가 `PENDING` 작업을 처리하고 그 요청에 공개본이 없는 상품이면 THEN Catalog SHALL 최초 `catalog_product`와 공개본을 만들고 출시 결과를 `COMPLETED`로 저장한다.
4. WHEN 현재 공개본보다 높은 `contentVersion`이면 THEN Catalog SHALL 새 공개본으로 현재 참조를 교체하고 `COMPLETED`로 저장한다.
5. IF 현재 공개본보다 낮은 `contentVersion`이면 THEN Catalog SHALL 출시 결과를 `REJECTED`(`STALE_CONTENT_VERSION`)로 저장하고 현재 공개본을 유지한다. 과거에 공개한 낮은 버전이 새 요청 ID로 와도 같다(롤백으로 해석하지 않는다).
6. WHEN 다른 요청 ID가 현재 공개본과 같은 `contentVersion`·같은 스냅샷 값이면 THEN Catalog SHALL 기존 공개본·현재 참조·최초 공개 시각을 유지하고 그 요청을 `COMPLETED`로 회신하며 공개 사본 이벤트를 내지 않는다(같은 승인본 재출시, [결정 58](../../../docs/decisions.md#release-withdrawal-reuse-review-approval)).
7. THE 공개본 스냅샷 SHALL 불변이다. 출시 요청 뒤 Studio 작업본이 바뀌어도 접수한 스냅샷은 바뀌지 않고, Catalog는 Studio를 조회하지 않는다.
8. THE 최초 공개 SHALL 판매자 `ON_SALE`·관리자 제한 없음·`AUTO` 노출·관리자 숨김 없음·다운로드 제한 없음(`NOT_REQUIRED`)으로 시작하고, 이후 새 공개본은 판매자 중지·관리자 판매 제한·노출 선택·관리자 숨김·다운로드 제한을 해제하거나 초기화하지 않는다.
9. THE 공개 확정 트랜잭션 SHALL 요청 잠금(`catalog_request_gate`) → 상품 행 잠금 순서로, 출시 결과·공개본·현재 참조·`catalogVersion`·출시 결과 Outbox·공개 사본 Outbox·작업 `COMPLETED`를 한 번에 커밋한다(플랫폼 P3.5-1).

### R2. 중복·동시성·식별 충돌 처리
**사용자 스토리:** 플랫폼으로서, 재전달·동시 요청·계약 위반에도 결과가 하나로 안정되길 원한다. 그래야 중복 공개·유실·덮어쓰기가 없다.

#### 수용 기준
1. WHEN 같은 이벤트가 재전달되면 THEN Catalog SHALL Inbox(`processed_event`, 그룹별)로 거르고 결과 이벤트를 다시 만들지 않는다.
2. WHEN 같은 요청 ID·같은 요청 이벤트 ID·같은 내용이 다시 오면 THEN Catalog SHALL 기존 작업·결과를 유지하고 재공개·결과 재발행을 하지 않는다. 중복 확인은 버전 비교보다 먼저 하며, 스냅샷은 JSON 문자열이 아니라 역직렬화한 값으로 비교한다.
3. IF 같은 요청 ID로 스냅샷·`reviewRequestId`·원본 이벤트 ID가 다르거나, 같은 원본 이벤트 ID가 다른 요청 ID로 오면 THEN Catalog SHALL 기존 작업·결과·공개본을 덮어쓰지 않고 영구 실패로 실패 보관 표·경보에 보낸다(플랫폼 P2.3-4).
4. IF 접수 때 같은 Project·같은 `contentVersion`에 다른 스냅샷이 이미 있거나 Project·상품 코드·소유자 연결이 기존 상품과 충돌하면 THEN Catalog SHALL 접수하지 않고 영구 실패로 보낸다. 접수 뒤 공개 확정 때 같은 충돌이 드러나면 R12에 따라 작업을 `BLOCKED`로 둔다(플랫폼 P3.5-3).
5. WHILE 같은 Project의 최초 공개가 동시에 진행되는 동안 THE Catalog SHALL `READ_COMMITTED`·상품 행 잠금·`uk_catalog_project`·`uk_catalog_product_code`로 직렬화하고, 같은 Project의 빈 잠금 조회 뒤 커밋된 경합은 재시도 가능 실패로 전체 트랜잭션을 다시 시도한다. 다른 Project의 같은 상품 코드만 계약 위반이다(D-042). 최종 현재 공개본은 가장 높은 `contentVersion`이다.
6. WHEN DB·브로커 일시 장애가 나면 THEN Catalog SHALL 업무 거절로 바꾸지 않고 롤백한 뒤 재시도한다.

### R3. 공개 조회 (목록·상세)
**사용자 스토리:** 방문자로서, 로그인 없이 공개 상품 목록과 상세를 보고 싶다. 그래야 구매 전에 상품을 탐색한다.

#### 수용 기준
1. WHERE 공개 조회 맥락에서 THE Catalog SHALL 로그인 없이 `GET /api/v1/products`와 `GET /api/v1/products/by-code/{productCode}`를 허용한다.
2. THE 목록 SHALL `page` 기본 0·`size` 기본 20·최대 100으로 제한하고 최초 공개 시각 내림차순·`productId` 내림차순으로 안정 정렬한다. 잘못된 페이지 입력은 400 `INVALID_REQUEST`다.
3. THE 공개 목록 SHALL listed=true인 상품만 포함한다.
4. WHEN 최종 listed=false이거나 없는 상품의 공개 상세를 요청하면 THEN Catalog SHALL 같은 404 `NOT_FOUND`로 응답한다([결정 52](../../../docs/decisions.md#public-detail-b-with-c-extension)).
5. THE 공개 응답 SHALL 승인받은 genres(1~3개)·공개 판매 상태·`catalogVersion`을 포함하고 `storagePath`·`ownerSubject`·심사/출시 요청 ID·서명 URL·운영 선호·관리자 조건은 포함하지 않는다. 목록·상세 응답은 `Cache-Control: no-store`다.

### R4. 판매 상태 (판매자 자율 중지·관리자 독립 제한)
**사용자 스토리:** 창작자·운영자로서, 상품 판매를 각자 독립적으로 중지·재개하고 싶다. 그래야 서로의 판단을 대신 바꾸지 않는다.

#### 수용 기준
1. WHERE `GET/PUT /api/v1/products/by-code/{code}/sales` 맥락에서 THE Catalog SHALL CREATOR 본인(`ownerSubject` = JWT `sub`)의 판매자 판매 의사 `ON_SALE`/`STOPPED`를 관리한다. 남의 상품·없는 상품은 같은 404다.
2. WHERE `GET/PUT /api/v1/admin/products/{code}/sales/restriction` 맥락에서 THE Catalog SHALL ADMIN의 `restricted`(필수 Boolean)를 판매자 의사와 독립 저장한다.
3. THE 공개 판매 상태 SHALL 관리자 제한이면 `BLOCKED`, 아니면 판매자 의사이며, 한쪽 해제가 다른 쪽을 바꾸지 않는다. 재출시는 해제 명령이 아니다(결정 48).
4. WHEN PUT 요청이 오면 THEN Catalog SHALL 상품 행 잠금 뒤 `expectedCatalogVersion`을 대조한다. 현재 버전의 같은 값 요청은 변경·감사·Outbox를 만들지 않고 현재 상태를 200으로 돌려주며, 다른 버전은 409 `CONFLICT`다.
5. WHEN 판매 조건이 바뀌면 THEN Catalog SHALL 같은 트랜잭션에서 `catalogVersion` 1 증가·`catalog_sale_change` 불변 감사·`CatalogProductSaleStatusChanged` Outbox(R8)를 저장한다.
6. IF 필수 값(`saleStatus`·`restricted`·`expectedCatalogVersion`)이 없거나 허용 밖이면 THEN Catalog SHALL 기본값으로 해석하지 않고 400으로 거절한다. 판매 관리 응답은 no-store다.

### R5. 검색·목록 노출 설정 (독립 설정·판매 기반 기본값)
**사용자 스토리:** 창작자·운영자로서, 검색·목록 노출을 판매 상태와 독립적으로 정하고 싶다. 그래야 노출과 판매를 따로 통제한다.

#### 수용 기준
1. WHERE `GET/PUT /api/v1/products/by-code/{code}/listing` 맥락에서 THE Catalog SHALL CREATOR 본인의 노출 선호(`AUTO`/`LISTED`/`UNLISTED`)를 관리한다.
2. WHERE `GET/PUT /api/v1/admin/products/{code}/listing/restriction` 맥락에서 THE Catalog SHALL ADMIN의 `hidden`(필수 Boolean)을 관리한다.
3. THE 최종 노출 SHALL `!platform_listing_hidden && listing_preference.isListed(saleStatus)`이며, `AUTO`는 공개 판매 상태가 `ON_SALE`일 때만 포함하고 관리자 숨김이 우선한다. 이 규칙은 모듈 안 한 곳(`ListingPreference`·`CatalogProduct.isListed`)에서만 정의하고 목록 쿼리는 그 규칙에서 만든다.
4. THE `LISTED` SHALL 판매 상태와 무관하게 포함하고 `UNLISTED`는 제외하며, 명시적 선택은 판매 중지·재개·새 공개본에서도 유지한다.
5. WHEN 노출 조건이 바뀌면 THEN Catalog SHALL R4.4·R4.6과 같은 버전 대조·무변경 처리로, 같은 트랜잭션에서 `catalogVersion` 1 증가·`catalog_listing_change` 불변 감사·`CatalogProductListingChanged` Outbox(R8)를 저장한다.
6. THE 공개 사본 SHALL 최종 listed만 싣고 소유자·노출 선호·관리자 조건은 싣지 않는다.

### R6. 다운로드 제한과 저장소 적용
**사용자 스토리:** 운영자로서, 기존 구매자의 다운로드를 판매·노출과 독립적으로 제한하고 싶다. 그래야 긴급 상황에 파일 접근을 막는다.

#### 수용 기준
1. WHERE `GET/PUT /api/v1/admin/products/{code}/downloads/restriction` 맥락에서 THE Catalog SHALL ADMIN의 `restricted`·`expectedRestrictionVersion`·필수 사유(공백 불가·최대 500자)를 받는다.
2. WHEN PUT이 상태를 바꾸면 THEN Catalog SHALL 상품 행 잠금 안에서 독립 `download_restriction_version` 1 증가·`catalog_download_change` 불변 이력·저장소 적용 예약(`PENDING`, 다음 시각 = 지금)을 한 트랜잭션에 저장하고 202로 접수한다. 202는 저장소 적용 완료가 아니다. 같은 값이면 이력·예약 없이 현재 상태를, 다른 버전이면 409를 준다. 저장소 적용이 구성되지 않은 인스턴스는 503 `UPSTREAM_UNAVAILABLE`로 거절한다.
3. WHEN 제한을 요청하면 THEN Catalog SHALL 즉시 내부 빌드 조회를 403 `PRODUCT_DOWNLOAD_RESTRICTED`로 거절하고, 해제 요청 뒤에도 저장소 `APPLIED` 확인 전까지 거절한다.
4. WHEN 저장소 적용 작업이 도는 때가 되면 THEN Catalog SHALL 그 Project의 모든 `COMPLETED` 공개본 객체에 차단 태그를 원하는 상태대로 설정·제거하고, 태그 재조회와 읽기 계정 1바이트 GET(차단이면 거절, 해제면 성공)으로 확인한 뒤에만 `APPLIED`로 저장한다. 저장소 호출은 DB 트랜잭션 밖에서 하고, 결과는 호출 전 읽은 `download_restriction_version`이 그대로일 때만 저장한다(플랫폼 P1.2-4).
5. IF 적용이 실패하면 THEN Catalog SHALL `FAILED`와 다음 시각을 저장해 재시도하고, `APPLIED` 뒤에도 주기 재확인으로 저장소 상태 어긋남을 고친다. 실패·장기 미적용의 경보 기준은 [결정 필요 M-CA-2]다.
6. THE 다운로드 판단 SHALL 판매·노출 상태를 쓰지 않는다. License `ACTIVE`·상품 ID/코드 대조는 Download가 지킨다([플랫폼 P3.8](../platform/requirements.md)).
7. WHEN 제한을 한 번이라도 쓴 상품의 공개본이 바뀌면 THEN Catalog SHALL 새 공개본 객체에도 원하는 상태를 적용하도록 저장소 적용을 다시 예약한다. 제한을 쓴 적 없는 상품(`NOT_REQUIRED`)은 예약하지 않는다.

### R7. 최종 노출·판매 상태의 주문 근거
**사용자 스토리:** 플랫폼으로서, 숨긴 상품·판매 제한 상품이 공개 상세와 새 주문에서 일관되게 거절되길 원한다. 그래야 노출·판매 정책이 구매까지 이어진다.

#### 수용 기준
1. THE 공개 상세 허용 판정과 Order용 `publicDetailAllowed` SHALL 모듈 안 `ProductPublicAccess` 한 곳에서 같은 규칙(최종 listed)으로 생산한다(결정 52).
2. WHERE 내부 주문 근거 조회(`GET /internal/v1/order-products/{code}`, `X-Order-Service-Key`) 맥락에서 THE Catalog SHALL 상품 ID·코드·제목·`contentVersion`·금액·통화·공개 판매 상태·`publicDetailAllowed`만 주고, 공개 본문·저장 경로·소유자·운영 설정은 주지 않는다. 판매·노출 판정(새 주문 404·`BLOCKED` 승인 접수 차단)은 Order가 이 값으로 한다([결정 49](../../../docs/decisions.md#platform-restriction-pending-order-admission), 플랫폼 P3.10-1).
3. THE 판매 제한·숨김의 관측 지점 SHALL Order가 읽는 Catalog HTTP 정본이다. Catalog는 겹친 조회까지 원자 즉시 차단하는 분산 잠금을 두지 않는다(결정 49 비용).
4. THE 감사·Outbox·Store 초기 동기화·Order 기존 승인 조회 SHALL 공개 상세의 404에 의존하지 않는다.

### R8. 공개 사본 이벤트 (사실별 4종)
**사용자 스토리:** 플랫폼으로서, 공개본·판매·노출 변경을 사실별 이벤트로 Store에 전달하고 싶다. 그래야 새 소비자가 판매 중지를 새 공개로 읽지 않는다.

#### 수용 기준
1. WHEN 공개 사본이 바뀌면 THEN Catalog SHALL 바꾼 트랜잭션의 Outbox에 원인에 맞는 이벤트 하나를 기록한다: 최초 공개 `CatalogProductFirstPublished`, 공개본 교체 `CatalogProductReleaseReplaced`, 판매 조건 변경 `CatalogProductSaleStatusChanged`, 노출 조건 변경 `CatalogProductListingChanged`([결정 171](../../../docs/decisions.md#catalog-copy-events-by-fact), 플랫폼 P3.5-6). 본문은 모두 그 시점 공개 사본 전체(`CatalogProductSnapshot`)와 `catalogVersion`이다.
2. THE 공개 사본 SHALL 공개 판매 상태·`catalogVersion`·listed를 담고, 판매·노출 변경은 `contentVersion`을 올리지 않으며 `catalogVersion`은 현재 공개본 `contentVersion`보다 작아지지 않는다.
3. THE 같은 승인본 재출시(R1.6)·거절·철회·종결·다운로드 제한 변경 SHALL 공개 사본 이벤트를 내지 않는다.
4. WHERE 내부 전체 목록·상세(`/internal/v1/store-products`, `/{code}`) 맥락에서 THE Catalog SHALL `X-Store-Service-Key`와 명시적 `CATALOG_STORE_SERVICE_KEY`로만 숨긴 상품까지 읽기를 허용하고, 상세에 listed를 필수로 싣는다.
5. THE 내부 키 경로 SHALL Gateway 라우트·공개 OpenAPI에 노출하지 않으며, 키 미설정·잘못된 키·일반 JWT를 거절하고 다른 내부·관리 경로의 권한을 주지 않는다(플랫폼 P1.2-3).
6. THE Store 사본 전달 SHALL Outbox·Kafka의 비동기 반영이며 전역 즉시 차단을 보장하지 않는다. 옛 `CatalogProductPublished`에서 새 4종으로의 이행은 플랫폼 P2.7-4 순서를 따른다.

### R9. 출시 철회 조정
**사용자 스토리:** 창작자로서, 공개 확정 전까지 출시 요청을 철회하고 싶다. 그래야 잘못된 출시를 멈춘다.

#### 수용 기준
1. WHEN `ProjectReleaseWithdrawalRequested`를 받으면 THEN Catalog SHALL 공개 확정과 같은 요청 잠금(`catalog_request_gate`) 안에서 조정한다([결정 57](../../../docs/decisions.md#release-withdrawal-before-publication), 플랫폼 P3.4-5).
2. IF 그 요청의 출시 결과가 아직 없으면(작업 `AWAITING_APPROVAL`·`PENDING`·`BLOCKED`) THEN Catalog SHALL 출시 결과를 `WITHDRAWN`으로, 작업을 `COMPLETED`로 같은 트랜잭션에 저장하고 `ProjectReleaseWithdrawn`을 낸다.
3. IF 출시 결과가 이미 `COMPLETED`·`REJECTED`·`CLOSED`이면 THEN Catalog SHALL 결과를 바꾸지 않고 `ProjectReleaseWithdrawalDenied`(`targetStatus`)를 낸다([결정 176](../../../docs/decisions.md#withdrawal-result-events)). `targetStatus`의 허용 값에 `CLOSED`를 넣는 것은 [결정 필요 M-CA-5]다.
4. IF 출시 결과가 이미 `WITHDRAWN`이면 THEN Catalog SHALL 같은 `withdrawalId`는 무변화로, 다른 `withdrawalId`는 결과를 바꾸지 않고 `ProjectReleaseWithdrawn`을 그 철회에 다시 회신한다.
5. WHEN 철회가 원요청보다 먼저 오면 THEN Catalog SHALL 철회를 미해결로 보관하고 원요청 접수 트랜잭션에서 `WITHDRAWN`으로 확정한다. 철회로 끝난 요청은 이후 재전달로도 공개되지 않는다(플랫폼 P3.4-6).
6. THE 철회 SHALL 고정 유예시간을 두지 않으며 공개 전 철회 시간을 보장하지 않는다(결정 62). 공개 작업자의 기본 주기(200ms)는 최소 철회 시간이 아니다.
7. THE 철회 결과 SHALL 철회자(`ownerSubject`)·대상·Catalog 확정 시각·결과를 기록하며 철회 사유는 선택 입력이다(결정 61). 철회는 이전 공개본·승인 보관·판매/노출/다운로드 설정을 바꾸지 않는다(플랫폼 P3.4-7).
8. IF 철회의 소유자·Project·`contentVersion`·상품 코드가 대상 출시 요청과 다르면 THEN Catalog SHALL 그 철회를 적용하지 않는다. 원요청 뒤에 온 철회는 영구 실패로 보내고, 원요청보다 먼저 보관된 철회의 처리는 [결정 필요 M-CA-3]이다.

### R10. 내구 공개 작업과 재시도
**사용자 스토리:** 운영자로서, 출시 요청이 장애·재시작에도 같은 요청으로 끝까지 처리되길 원한다. 그래야 유실 없이 복구한다.

#### 수용 기준
1. THE 접수와 공개 SHALL 다른 트랜잭션이다. 접수는 원요청·불변 스냅샷·Inbox를 먼저 커밋하고(R1.2), 공개는 공개 작업자(`PublicationScheduler` → `PublishQueuedReleases`)가 `PENDING`이고 다음 시각이 지난 작업을 골라 처리한다.
2. THE 공개본·출시 결과·상품 변경·Outbox·작업 `COMPLETED` SHALL 한 트랜잭션이다(R1.9).
3. IF 공개가 일시 오류(DB·잠금 경합·브로커 장애 등 재시도 가능 실패)로 실패하면 THEN Catalog SHALL 작업을 `PENDING`으로 두고 같은 요청으로 5·15·30초, 이후 60초 간격으로 다시 시도한다. 새 요청 ID로 바꿔 복구하지 않는다(플랫폼 P3.5-4).
4. WHERE `GET /api/v1/admin/catalog-release-jobs`(상태 필터 `AWAITING_APPROVAL`·`PENDING`·`BLOCKED`, 기본 `PENDING`, `page`≥0·`size` 1~100, 잘못된 값 400)·`/{requestId}` 맥락에서 THE Catalog SHALL ADMIN에게 상태·`attemptCount`·`lastError`(분류 코드와 요약)·`nextAttemptAt`·`sourceEventId`·정지 시각을 no-store로 주고 내부 스냅샷·저장 경로는 주지 않는다.
5. THE 작업 `COMPLETED` SHALL 처리 끝만 뜻한다. 결과(공개·거절·철회·종결)는 출시 결과와 Studio 이력으로 확인한다.

### R11. Review 승인 사실 대조 (결정 167)
**사용자 스토리:** 플랫폼으로서, 공개를 확정하는 Catalog가 정본인 Review의 승인 사실을 직접 확인하길 원한다. 그래야 승인되지 않은 자료가 판매되고 보유권이 지급되는 일이 없다.

#### 수용 기준
1. WHEN `ProjectReviewApproved`(`stove.review.v1`)를 새 그룹 `catalog-review-approval`에서 받으면 THEN Catalog SHALL `ReviewApprovalMapper.from`에서 봉투·키를 검증하고, `reviewRequestId`를 유일 키로 `(reviewRequestId, projectId, contentVersion, productCode, 스냅샷 값)`을 Inbox와 같은 트랜잭션에 불변 보관한다. 승인 시각(`occurredAt`)은 값으로만 저장한다([플랫폼 P2.1-3](../platform/requirements.md)). 스냅샷 값은 승인 본문의 `snapshot`(Review가 보관한 `ProjectSnapshot` 전체)이다([결정 181](../../../docs/decisions.md#approval-snapshot-payload)). IF 승인 사실이 `snapshot` 없는 `schemaVersion` 1이면 THEN Catalog SHALL 대조 근거로 보관하지 않고 Inbox만 쓴다(무변화, 실패 보관 아님). 그 승인을 근거로 한 출시는 `AWAITING_APPROVAL`에 남는다([결정 182](../../../docs/decisions.md#no-approval-migration)).
2. IF 같은 `reviewRequestId`의 승인이 다른 값으로 다시 오면 THEN Catalog SHALL 기존 보관을 덮어쓰지 않고 영구 실패로 보낸다. 같은 값이면 무변화다.
3. WHEN 출시 요청을 접수하면 THEN Catalog SHALL 승인 잠금(`reviewRequestId`) → 요청 잠금 순서로 잡고 보관 승인과 `(reviewRequestId, projectId, contentVersion, productCode, 스냅샷 값)`을 대조한다. 스냅샷 값은 출시 요청의 `ProjectReleaseRequested.snapshot`과 보관 `snapshot`의 `ProjectSnapshot` 레코드 동등(장르 목록 순서 포함)으로 비교하고, 따로 정규화·해시하지 않는다([결정 181](../../../docs/decisions.md#approval-snapshot-payload)). 맞으면 작업을 `PENDING`으로, 보관 승인이 없으면 `AWAITING_APPROVAL`로 저장한다.
4. IF 보관 승인이 있는데 값이 하나라도 다르면 THEN Catalog SHALL 공개하지 않고 같은 트랜잭션에서 출시 결과 `REJECTED`(`APPROVAL_MISMATCH`)·작업 `COMPLETED`·`ProjectReleaseRejected`를 저장한다(결정 167).
5. WHEN 승인을 보관하면 THEN Catalog SHALL 같은 승인 잠금 안에서 그 `reviewRequestId`를 가진 `AWAITING_APPROVAL` 작업마다 요청 잠금을 잡고, 그 작업이 아직 `AWAITING_APPROVAL`이고 출시 결과가 없을 때만 R11.3·R11.4대로 `PENDING`으로 열거나 거절한다. 철회(`WITHDRAWN`)·거절·종결로 이미 끝난 요청에는 승인만 보관하고 작업을 열지 않는다(1회차 지적 B3, 판정 전).
6. THE 공개 확정 트랜잭션 SHALL 보관 승인과의 대조를 다시 확인한다. IF 맞는 보관 승인이 없거나 값이 다르면 THEN Catalog SHALL 공개하지 않고 R12에 따라 작업을 `BLOCKED`로 둔다(돌이킬 수 없는 공개 지점의 가드, 원칙 ⑤).
7. THE 출시 요청과 승인의 도착 순서 SHALL 판정에 쓰지 않는다. 두 토픽 사이 순서는 보장되지 않으므로(플랫폼 P2.5-2) 결과는 보관 승인의 존재와 값으로만 정한다.
8. WHILE 작업이 `AWAITING_APPROVAL`인 동안 THE Catalog SHALL 그 건수와 가장 오래 기다린 시간을 게이지로 낸다(결정 158). 경보 기준은 측정 뒤 정한다([결정 159](../../../docs/decisions.md#forward-gap-threshold)). 승인이 끝내 오지 않는 작업의 운영자 처리는 [결정 필요 M-CA-1]이다.
9. THE 다른 `stove.review.v1` 이벤트(`ProjectReviewRejected`·심사 철회 결과) SHALL 그룹 `catalog-review-approval`에서 부수효과 없이 건너뛰고 건너뛴 수를 지표로 낸다(플랫폼 P2.3-7).

### R12. 공개 작업 정지와 운영자 재개·종결 (결정 168)
**사용자 스토리:** 운영자로서, 재시도로 풀리지 않는 공개 실패를 바로 알고 고쳐 재개하거나 공개 없이 끝내고 싶다. 그래야 한 Project의 출시가 영원히 막히지 않는다.

#### 수용 기준
1. IF 공개 작업이 재시도로 성공할 수 없는 실패(공통 영구 실패 분류, 플랫폼 T20: 같은 버전 다른 스냅샷, 다른 Project의 상품 코드 충돌, 소유자·Project 연결 불일치, 저장 스냅샷 역직렬화 실패, R11.6 승인 대조 실패)를 만나면 THEN Catalog SHALL 공개 트랜잭션을 롤백하고 별도 트랜잭션에서 작업을 `BLOCKED`로 바꾸며 분류 코드·요약·정지 시각을 저장하고 1건부터 경보한다(플랫폼 P3.5-5).
2. THE `BLOCKED` 작업 SHALL 공개 작업자가 고르지 않는다.
3. WHEN ADMIN이 `POST /api/v1/admin/catalog-release-jobs/{requestId}/resume`(필수 사유)을 부르면 THEN Catalog SHALL 작업 행 잠금 뒤 `BLOCKED`이면 `PENDING`(다음 시각 = 지금)으로 되돌리고 운영 이력(실행자·시각·사유)을 남긴다. 이미 `PENDING`이면 무변화 200, `AWAITING_APPROVAL`·`COMPLETED`이면 409다.
4. WHEN ADMIN이 `POST /api/v1/admin/catalog-release-jobs/{requestId}/close`(필수 사유)를 부르면 THEN Catalog SHALL 요청 잠금 → 작업 행 잠금 뒤 작업이 `BLOCKED`이고 출시 결과가 없을 때만, 같은 트랜잭션에서 출시 결과 `CLOSED`·종결 기록(실행자·사유·시각·정지 분류)·작업 `COMPLETED`·`ProjectReleaseClosed` Outbox를 저장한다. 공개하지 않는다. `ProjectReleaseRejected`의 `reason`에 싣지 않는다(결정 168).
5. IF 종결 대상이 이미 `CLOSED`이면 THEN Catalog SHALL 기존 종결 기록을 200으로 돌려주고 이벤트를 다시 내지 않는다. 다른 결과로 이미 끝났거나 `BLOCKED`가 아니면 409다.
6. WHEN 철회와 종결이 같은 요청에 겹치면 THEN Catalog SHALL 요청 잠금으로 직렬화하고 먼저 커밋된 쪽을 결과로 둔다(요청당 결과 하나).
7. THE 운영 API SHALL ADMIN만 부르고, Gateway의 관리자 경로로만 노출하며, 응답은 no-store다.

### R13. 출시 처리의 관측
**사용자 스토리:** 운영자로서, 응답을 받지 못한 출시 처리와 저장소 미적용을 건수로 보고 싶다. 그래야 정방향 누락을 사람이 찾지 않아도 드러난다.

#### 수용 기준
1. THE Catalog SHALL 다음을 게이지(건수·가장 오래 기다린 시간)로 낸다: `AWAITING_APPROVAL` 작업, `PENDING` 작업(시도 횟수 분포 포함), `BLOCKED` 작업, 원요청 없이 보관된 미해결 철회, 저장소 적용 `PENDING`·`FAILED` 상품(결정 158, 플랫폼 P3.2-7·P3.9-2).
2. THE `BLOCKED` 작업 SHALL 1건부터 경보한다(결정 168). 나머지 게이지의 경보 기준은 결정 159에 따라 측정 뒤 정하고, 저장소 적용 실패의 경보 기준은 [결정 필요 M-CA-2]다.
3. THE 게이지 SHALL Catalog 자기 스키마만 읽는다(플랫폼 P1.1-2).

## 범위 밖(후속)

- 예약 출시·롤백·채널·관리자 강제 공개·할인([Q13](../../../docs/domain/open-questions.md))·프로모션·상품군·번들·등급·주문 견적.
- 검색 색인·재색인·캐시, 독립 공개 접근 C의 독립 플래그·관리 API(결정 52).
- 이미 시작한 전송 종료·다운로드된 파일 회수·URL 영구 폐기(키 회전/프록시)(결정 50 비용).
- 공개 시점 빌드 객체의 존재 재확인(Catalog는 파일을 다시 검사하지 않는다, 플랫폼 흐름 1 S4 "상대 상태가 이미 바뀜").
- 공유 환경의 정책/계정 준비·실제 배포, G4 전체 완료.
