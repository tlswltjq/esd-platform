# 요구사항: Catalog (출시 처리·공개본 정본·공개 조회·판매/노출/다운로드 제한)

## 개요

Catalog는 Studio가 보낸 출시 요청을 처리해 승인된 스냅샷을 불변 공개본으로 확정하고,
현재 공개본의 정본(상세·가격·빌드 참조·소유자)을 관리한다.
방문자에게 공개 상품 목록·상세를 익명 GET으로 제공하고, 판매 상태·검색/목록 노출·다운로드 제한의
정본을 보유해 Store 사본과 Order의 구매 허용 판단, Download의 신규 URL 발급 판단의 근거가 된다.
MVP 재작성은 2026-10-15 완료했고(관련 481건 통과), 이후 판매 관리·검색/목록 노출·공개 상세 B·다운로드 제한·철회 조정을 확장했다.

## 용어

- **공개본(CatalogRelease)**: 출시 요청으로 확정한 불변 스냅샷. COMPLETED 기록의 스냅샷이 공개본이며 CatalogProduct가 현재 한 건을 가리킨다.
- **contentVersion**: Project 자료·빌드의 콘텐츠 버전. 공개본 신구 판정의 기준.
- **catalogVersion**: 콘텐츠와 독립적인 노출·판매·상태 변경의 순서 버전. contentVersion보다 작아지지 않는다.
- **saleStatus**: 공개 판매 상태. `ON_SALE` / `STOPPED`(판매자 자율 중지) / `BLOCKED`(관리자 판매 제한).
- **listingPreference**: 판매자 노출 선호. `AUTO` / `LISTED` / `UNLISTED`.
- **platformListingHidden**: 관리자의 독립 강제 숨김 여부.
- **listed**: 최종 노출 값. `!platformListingHidden && listingPreference.isListed(saleStatus)`.
- **downloadRestricted**: 관리자의 독립 다운로드 제한 여부. 저장소 적용 상태는 `PENDING/APPLIED/FAILED`.
- **ownerSubject**: 상품 소유자. Auth JWT의 sub로 식별하며 변경할 수 없다.

## 요구사항

### R1. 출시 요청 처리와 공개본 확정
**사용자 스토리:** 플랫폼으로서, Studio의 출시 요청을 받아 승인된 스냅샷을 공개본으로 확정하고 싶다. 그래야 방문자가 공개 상품을 볼 수 있다.

#### 수용 기준
1. WHEN `ProjectReleaseRequested`(stove.studio.v1)를 수신하면 THEN 시스템 SHALL 봉투·본문의 이벤트 ID·유형, Kafka 키와 productCode 일치, 요청·심사·Project·빌드 ID·소유자·양수 contentVersion·상세/가격/KRW/빌드 크기/체크섬/저장 경로를 Studio 입력 계약으로 검증한다.
2. WHEN 공개본이 없는 정상 요청이면 THEN 시스템 SHALL 최초 CatalogProduct·공개본을 만들고 결과를 `COMPLETED`로 저장한다.
3. WHEN 현재 공개본보다 높은 contentVersion이면 THEN 시스템 SHALL 새 공개본을 만들고 현재 참조를 교체하며 `COMPLETED`로 저장한다.
4. IF 낮은 contentVersion의 새 요청이 뒤늦게 도착하면 THEN 시스템 SHALL `STALE_CONTENT_VERSION` 사유로 `REJECTED` 처리하고 현재 공개본을 유지한다.
5. THE 공개본 스냅샷 SHALL 불변이며, 출시 요청 후 Studio 작업본을 수정해도 이미 제출한 스냅샷은 바뀌지 않는다.
6. WHEN 다른 요청 ID가 현재 공개본과 같은 버전·같은 스냅샷이면 THEN 시스템 SHALL 기존 공개본·현재 참조·공개 시각을 유지하고 그 요청을 `COMPLETED`로 회신한다(같은 승인본 재출시, 결정 58).
7. IF 과거에 공개한 낮은 버전이 새 요청 ID로 오면 THEN 시스템 SHALL `STALE_CONTENT_VERSION`으로 거절한다. 롤백으로 해석하지 않는다.
8. THE 최초 공개 SHALL `ON_SALE`·`AUTO` 노출·다운로드 제한 없음으로 시작하며, 이후 새 공개본은 판매자 중지·관리자 판매 제한·노출 선택·다운로드 제한을 해제하거나 초기화하지 않는다.

### R2. 중복·동시성·식별 충돌 처리
**사용자 스토리:** 플랫폼으로서, 재전달·동시 요청·계약 위반에도 결과가 안정적이길 원한다. 그래야 중복 공개나 유실이 없다.

#### 수용 기준
1. WHEN 같은 이벤트가 재전달되면 THEN 시스템 SHALL Inbox로 중복을 제거하고 결과 이벤트를 추가 생성하지 않는다.
2. WHEN 같은 요청 ID·요청 이벤트 ID·내용이 재전달되면 THEN 시스템 SHALL 기존 최종 결과를 유지하고 재공개·중복 결과 발행을 하지 않는다. 중복 확인은 버전 비교보다 먼저 하며, 같은 버전 스냅샷은 JSON 문자열이 아니라 파싱한 값으로 비교한다.
3. IF 같은 요청 ID로 스냅샷·reviewRequestId·원본 이벤트 ID가 달라지면 THEN 시스템 SHALL 계약 위반으로 보고 기존 요청·공개본·결과를 덮어쓰지 않는다.
4. IF 같은 Project·버전에 다른 스냅샷이거나 Project·상품 코드·소유자 연결이 충돌하면 THEN 시스템 SHALL 계약 위반으로 DLT 대상 처리하고 추측한 결과를 보내지 않는다.
5. WHILE 동시 요청·최초 생성 경합을 처리하는 동안 THE 시스템 SHALL `READ_COMMITTED`와 상품 행 잠금·유일 제약·전체 트랜잭션 재시도로 직렬화하고, 최종 현재 공개본은 가장 높은 버전이어야 한다.
6. WHEN DB·브로커 일시 장애가 발생하면 THEN 시스템 SHALL 업무 거절로 변환하지 않고 롤백·재시도한다.

### R3. 공개 조회 (목록·상세)
**사용자 스토리:** 방문자로서, 로그인 없이 공개 상품 목록과 상세를 보고 싶다. 그래야 구매 전에 상품을 탐색한다.

#### 수용 기준
1. WHERE 공개 조회 맥락에서 THE 시스템 SHALL 로그인 없이 `GET /api/v1/products`와 `GET /api/v1/products/by-code/{productCode}` 접근을 허용한다.
2. THE 목록 SHALL page=0·size=20·최대 size=100으로 제한하고 최초 공개 시각 내림차순·productId 내림차순으로 안정 정렬한다.
3. WHEN 최종 listed=false인 상품의 공개 상세를 요청하면 THEN 시스템 SHALL 없는 상품과 같은 404를 반환한다.
4. THE 공개 목록 SHALL listed=true인 상품만 포함하며, 잘못된 페이지 입력은 400, 없는 코드는 404로 응답한다.
5. THE 공개 응답 SHALL 승인받은 genres(1~3개)·saleStatus·catalogVersion을 포함하고 storagePath·ownerSubject·심사/출시 요청 ID·서명 URL은 포함하지 않으며 상세는 no-store다.

### R4. 판매 상태 (판매자 자율 중지·관리자 독립 제한)
**사용자 스토리:** 창작자·운영자로서, 상품의 판매를 각자 독립적으로 중지·재개하고 싶다. 그래야 서로의 판단을 대신 바꾸지 않는다.

#### 수용 기준
1. WHERE `GET/PUT /api/v1/products/by-code/{code}/sales` 맥락에서 THE 시스템 SHALL CREATOR 본인의 saleStatus=ON_SALE/STOPPED를 ownerSubject·JWT sub 대조로 관리한다.
2. WHERE `GET/PUT /api/v1/admin/products/{code}/sales/restriction` 맥락에서 THE 시스템 SHALL ADMIN의 restricted=true/false를 판매자 선호와 독립 저장한다.
3. THE 공개 판매 상태 SHALL 판매자 ON_SALE과 플랫폼 제한을 독립 저장해 `ON_SALE`/`STOPPED`/`BLOCKED`로 표현하며, 관리자가 해제해도 판매자가 STOPPED이면 STOPPED를 유지한다.
4. WHEN PUT 요청이 오면 THEN 시스템 SHALL 상품 행 잠금 뒤 expectedCatalogVersion을 대조하고, 현재 버전의 같은 상태 요청은 변경·감사·Outbox를 만들지 않으며 옛 버전 재전송은 409로 거절한다.
5. IF restricted 값이 누락되면 THEN 시스템 SHALL false로 해석하지 않고 400으로 거절하며, 관리 응답은 no-store다.

### R5. 검색·목록 노출 설정 (독립 설정·판매 기반 기본값)
**사용자 스토리:** 창작자·운영자로서, 검색·목록 노출을 판매 상태와 독립적으로 정하고 싶다. 그래야 노출과 판매를 따로 통제한다.

#### 수용 기준
1. WHERE `GET/PUT /api/v1/products/by-code/{code}/listing` 맥락에서 THE 시스템 SHALL CREATOR 본인의 listingPreference(AUTO/LISTED/UNLISTED)를 관리한다.
2. WHERE `GET/PUT /api/v1/admin/products/{code}/listing/restriction` 맥락에서 THE 시스템 SHALL ADMIN의 platformListingHidden(필수 Boolean)을 관리한다.
3. THE 최종 노출 SHALL `!platformListingHidden && listingPreference.isListed(saleStatus)`로 계산하며, AUTO는 최종 saleStatus=ON_SALE일 때만 포함하고 관리자 숨김이 우선한다.
4. THE LISTED SHALL 판매 상태와 독립적으로 포함하고 UNLISTED는 제외하며, 명시적 선택은 판매 중지/재개·새 공개본에서도 유지한다.
5. THE 공개 사본 Outbox SHALL 최종 listed만 포함하고 소유자·운영 선호·관리자 조건은 공개하지 않는다.

### R6. 다운로드 제한과 저장소 적용
**사용자 스토리:** 운영자로서, 기존 구매자의 다운로드를 판매 중지와 독립적으로 제한하고 싶다. 그래야 긴급 상황에 파일 접근을 막는다.

#### 수용 기준
1. WHERE `GET/PUT /api/v1/admin/products/{code}/downloads/restriction` 맥락에서 THE 시스템 SHALL ADMIN의 restricted·expectedRestrictionVersion·필수 사유(공백 불가·최대 500자)를 받는다.
2. WHEN PUT이 성공하면 THEN 시스템 SHALL 원하는 상태·감사·작업 예약을 202로 접수하고 상품 행 잠금·독립 downloadRestrictionVersion으로 충돌을 제어하며 `catalog_download_change` 불변 이력을 원자 저장한다.
3. WHEN 제한을 요청하면 THEN 시스템 SHALL 즉시 신규 URL 발급을 거절하고, 해제 요청 뒤에도 저장소 APPLIED 확인 전까지 거절한다.
4. WHEN 스케줄러가 적용하면 THEN 시스템 SHALL 해당 프로젝트의 모든 COMPLETED 공개본의 객체에 `stove-download-blocked` 태그를 설정/제거하고, 태그 재조회·읽기 계정 1바이트 GET으로 확인한 뒤에만 `APPLIED`로 저장한다.
5. IF 적용이 실패하면 THEN 시스템 SHALL `FAILED`와 다음 시각을 저장해 재시도하고, 판매 제한 상태는 다운로드 판단에 사용하지 않으며 License ACTIVE·상품 ID/코드 대조는 유지한다.

### R7. 최종 노출·주문 거절 연계
**사용자 스토리:** 플랫폼으로서, 숨긴 상품이 공개 상세와 새 주문에서 일관되게 거절되길 원한다. 그래야 노출 정책이 구매까지 이어진다.

#### 수용 기준
1. WHEN 최종 listed=false이면 THEN 시스템 SHALL Catalog 공개 상세를 404로 거절하고, Order의 새 주문도 404 근거(publicDetailAllowed=false)를 제공한다.
2. WHERE 내부 주문 근거 조회(`/internal/v1/order-products/{code}`) 맥락에서 THE 시스템 SHALL 상품 ID·코드·제목·버전·금액·통화·saleStatus·publicDetailAllowed만 전달하고 공개 본문·저장 경로·소유자·운영 설정은 전달하지 않는다.
3. WHEN 같은 requestId의 기존 주문 재전송·기존 주문 승인 접수이면 THEN 시스템 SHALL 새 주문 공개 상세 검사를 재실행하지 않고 원래 계약을 유지한다.
4. THE 관리자 판매 제한(BLOCKED) SHALL 기존 별도 계약대로 새 승인 접수를 차단한다.
5. THE 공개 상세 판정 SHALL 모듈 내부 ProductPublicAccess로 모으며, 감사/Outbox·Store 초기 동기화·Order 기존 승인 조회는 공개 상세의 404에 의존하지 않는다.

### R8. Store 사본 갱신 (catalogVersion 기반)
**사용자 스토리:** 플랫폼으로서, 공개본·판매·노출 변경을 Store 사본에 전달하고 싶다. 그래야 검색·조회가 최신 상태를 반영한다.

#### 수용 기준
1. WHEN 공개본 또는 판매/노출 조건이 바뀌면 THEN 시스템 SHALL `CatalogProductPublished`(stove.catalog.v1)를 같은 트랜잭션의 Outbox에 추가한다.
2. THE 공개 사본 SHALL saleStatus·catalogVersion·listed를 포함하고, 상태 변경에는 contentVersion을 올리지 않으며 catalogVersion은 contentVersion보다 작아지지 않는다.
3. WHERE 내부 전체 목록/상세(`/internal/v1/store-products`, `/{code}`) 맥락에서 THE 시스템 SHALL `X-Store-Service-Key`와 명시적 `CATALOG_STORE_SERVICE_KEY`로만 숨긴 상품까지 읽기를 허용한다.
4. THE 내부 키 경로 SHALL Gateway 라우트·공개 OpenAPI에 노출하지 않으며, 미설정·잘못된 키·일반 JWT는 거절하고 다른 내부/관리 경로의 권한을 주지 않는다.
5. THE Store 사본 전달 SHALL Outbox/Kafka의 비동기 반영이며 전역 즉시 차단·동시 철회를 보장하지 않는다.

### R9. 출시 철회 조정
**사용자 스토리:** 창작자로서, 공개 확정 전까지 출시 요청을 철회하고 싶다. 그래야 잘못된 출시를 멈출 수 있다.

#### 수용 기준
1. WHEN `ProjectReleaseWithdrawalRequested`를 수신하면 THEN 시스템 SHALL 공개 확정과 같은 종료 경계에서 조정하고 `ProjectReleaseWithdrawalResolved`를 발행한다.
2. IF 철회가 먼저 확정되면 THEN 시스템 SHALL 기존 공개본을 유지한다.
3. IF 공개가 먼저 확정되면 THEN 시스템 SHALL 해당 결과를 유지한다.
4. THE 철회 SHALL 고정 유예시간을 두지 않으며 공개 전 철회 시간을 보장하지 않는다.
5. THE 결과 SHALL 행위자·대상·시각·결과를 기록하고 철회 사유는 선택 입력이다.
6. WHEN 철회가 원요청보다 먼저 오면 THEN 시스템 SHALL 이를 보존하고, 철회로 종료된 요청의 원본 재전달로 공개하지 않는다. 철회는 이전 공개본·승인 근거·판매/노출/다운로드 설정을 보존한다.

### R10. 내구 공개 작업 (결정 62)
**사용자 스토리:** 운영자로서, 출시 요청이 장애·재시작에도 같은 요청으로 끝까지 처리되길 원한다. 그래야 유실 없이 복구한다.

#### 수용 기준
1. WHEN 출시 요청을 수신하면 THEN 시스템 SHALL 원요청·불변 스냅샷·Inbox를 먼저 내구 저장하고(`catalog_publication_job`, V15) 별도 자동 작업에서 공개한다.
2. THE 공개본·출시 결과·상품 변경 Outbox·작업 완료 SHALL 같은 트랜잭션으로 저장한다.
3. IF 공개가 실패하면 THEN 시스템 SHALL 원래 요청으로 재시도하며 대기는 5·15·30초, 이후 60초다. 새 요청 ID로 바꿔 복구하지 않는다.
4. WHERE `GET /api/v1/admin/catalog-release-jobs`(PENDING 목록, page·size≤100)·`/{requestId}` 맥락에서 THE 시스템 SHALL ADMIN에게 attemptCount·lastError·nextAttemptAt·sourceEventId를 no-store로 제공하고 내부 스냅샷·저장 경로는 응답하지 않는다.
5. THE 작업 상태 COMPLETED SHALL 공개 성공을 뜻하지 않는다(철회로 종료된 요청도 작업은 완료된다). 정본 결과는 출시 기록과 Studio 이력으로 확인한다.

## 범위 밖(후속)

- 예약 출시·롤백·채널·관리자 강제 공개·할인([Q13](../../../docs/domain/open-questions.md))·프로모션·상품군·번들·등급 업무·주문 견적.
- 검색 색인·재색인·캐시, 독립 공개 접근 C의 독립 플래그·관리 API.
- 이미 시작한 전송 종료·다운로드된 파일 회수·URL 영구 폐기(키 회전/프록시).
- 공유 환경의 정책/계정 준비·실제 배포, G4 전체 완료.
