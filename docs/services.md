# 서비스별 기능 명세

각 서비스가 **무엇을 책임지고, 어떤 요청과 이벤트를 받고, 무엇을 내보내는지** 정리한다.
시스템 전체의 이벤트 흐름은 [업무 흐름](domain/workflows.md), 설계 근거는 [아키텍처](architecture.md)를 본다.
업무 용어의 정의·사례·합의 상태는 [도메인 용어집](domain-glossary.md)에서 시작한다.
도메인별 모델·속성·생성·사용 시점은 [도메인·모델 안내](domain/model-guide.md)에서 찾는다.

**모듈 재작성 현황 (2026-10-18): `auth`, `gateway`, `studio`, `review`, `catalog`, `store`, `order`, `payment`, `license`, `download`, `settlement` 완료.**
Auth·Gateway의 완료 범위와 검증 기록은 [auth·Gateway/BFF](auth-bff.md),
Studio의 완료 범위는 [Studio 완료 기록](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/studio-simplification-plan.md#재작성-완료-기록), Review는 [Review 완료 기록](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/review-simplification-plan.md#재작성-완료-기록)을 본다.
Catalog·Store·Order·Payment·License·Download·Settlement의 범위와 실행 결과는 각 MVP 완료 기록을 따른다. 신규 구현과 검증은 완료된 열한 모듈의 계약을 기준으로 한다.
외부 모의 PG는 업무 모듈과 분리된 검증용 공급자이며 완료 모듈 수에 포함하지 않는다.

읽는 순서는 비즈니스 흐름과 같다 — 크리에이터가 게임을 올리고(A), 이용자가 사고(B), 이용·정산된다(C).

| | 서비스 | 포트 | 한 줄 책임 | 저장소 |
|---|---|---|---|---|
| — | [auth](#auth) | 8091 | 계정·역할·OAuth2/OIDC 토큰 | MySQL |
| A | [studio](#studio) | 8085 | 출시 상세정보·전체 빌드·심사 및 출시 요청 | MySQL |
| A | [review](#review) | 8086 | 불변 스냅샷·빌드 열람, 수동 승인·반려 | MySQL |
| A | [catalog](#catalog) | 8081 | 출시 처리, 공개 상품·공개본과 조회 | MySQL |
| B | [store](#store) | 8087 | 공개 상품 검색·조회 사본 | MySQL |
| B | [order](#order) | 8082 | 주문 스냅샷·청구액·내 주문·결제 결과 | MySQL |
| B | [payment](#payment) | 8083 | 확정 주문 결제·미확정 재개·결과 발행 | MySQL |
| C | [license](#license) | 8084 | 상품 이용 권한·구매 근거·내 라이브러리 | MySQL |
| C | [download](#download) | 8088 | 보유권·현재 공개 빌드 확인, 전체 ZIP 서명 URL | 없음 |
| C | [settlement](#settlement) | 8089 | 매출 배분·수수료·마감 | MySQL |
| — | [gateway](#gateway) | 8080 | 라우팅·BFF 로그인·세션·토큰 관리, 내부 API 차단 | Redis |

표의 포트는 서비스 내부 포트다. 로컬 E2E 구성의 호스트 포트는 10000을 더한 값이며, 외부 요청은 보통 Gateway `18080`을 사용한다. [개발 환경과 실행](getting-started.md)을 참고한다.

---

## auth

회원과 역할의 원본을 관리하고 OAuth2/OIDC 토큰을 발행한다. 모델의 속성은 [계정·역할 모델](domain/models/identity.md), 토큰 검증과 권한 조건은 [인증·권한 계약](security.md)에 있다.

| HTTP API | 역할 |
|---|---|
| `POST /api/v1/auth/signup` | 창작자 가입, `MEMBER`·`CREATOR` 역할 부여 |
| `POST /api/v1/auth/signup/member` | 구매자 가입, `MEMBER` 역할 부여 |
| `/login`, `/logout`, `/oauth2/**`, `/.well-known/**` | 로그인·승인·토큰·공개 키와 OIDC 메타데이터 |

**이벤트** — 회원·창작자 가입 시 `UserRegistered` 발행. Studio는 Auth JWT sub로 소유자를 직접 식별하며 별도 판매자 연결을 생성하지 않는다. 근거는 [AccountController](../apps/auth/src/main/java/com/stove/auth/api/controller/AccountController.java), [RegisterAccount](../apps/auth/src/main/java/com/stove/auth/core/service/RegisterAccount.java)다.

---

## studio

Studio의 책임은 출시용 상세정보·빌드 관리, 심사 요청·재요청, 출시 요청·재요청이다.
심사 판정과 실제 출시·공개 상품 관리는 외부 업무이며 결과는 요청 기록에만 표시한다.
[모델](domain/models/studio.md) · [실행·요청 예제](../.kiro/specs/studio/design.md#사용-안내)

소유자는 Auth JWT sub이며 Workspace·sellerId 매핑은 없다. 실제 연계는 Auth·Gateway·Studio·Review·Catalog 여정으로 확인한다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /api/v1/studio/genres | CREATOR 장르 기준 목록·선택 가능 여부 |
| POST / GET | /api/v1/studio/projects | 생성(201)·내 페이지 목록 |
| GET | /api/v1/studio/projects/{id} | 상세정보·현재 버전의 요청·releaseEligible |
| PUT | /api/v1/studio/projects/{id}/store-page | contentVersion으로 상세정보 수정 |
| POST / GET | /api/v1/studio/projects/{id}/builds | 전체 ZIP 등록(201)·페이지 이력 |
| POST | /api/v1/studio/projects/{id}/thumbnail | PNG/JPEG 등록(201) |
| GET | /api/v1/studio/assets/{id}/{assetId} | 공개 썸네일 |
| POST / GET | /api/v1/studio/projects/{id}/review-requests | 심사 요청·재요청(202)·페이지 이력 |
| POST / GET | /api/v1/studio/projects/{id}/release-requests | 출시 요청·재요청(202)·페이지 이력 |
| POST / GET | /api/v1/studio/projects/{id}/review-requests/{targetId}/withdrawals | 심사 철회 내구 접수(202)·페이지 영수증 이력 |
| GET | /api/v1/studio/projects/{id}/review-requests/{targetId}/withdrawals/{withdrawalId} | 심사 철회 최종 결과 조회 |
| POST / GET | /api/v1/studio/projects/{id}/release-requests/{targetId}/withdrawals | 출시 철회 내구 접수(202)·페이지 영수증 이력 |
| GET | /api/v1/studio/projects/{id}/release-requests/{targetId}/withdrawals/{withdrawalId} | 출시 철회 최종 결과 조회 |

프로젝트·빌드·심사/출시 이력의 GET 목록은 `page=0`·`size=20`이 기본이며 최대 100개, ID 내림차순이다. `data`는 기존 배열에서 `items/page/size/totalElements/totalPages`로 전환했다. 항목 정보와 소유권은 유지하며 잘못된 범위·정수 조회 구간 초과는 400이다. [계약·검증 기록](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/studio-list-pagination-plan.md)을 따른다. 철회 영수증은 기존 제한 조회·응답 계약을 유지한다.

상점 정보의 genres는 작업본에서 0~3개, 새 심사 제출에서는 선택 가능한 코드 1~3개다. 코드 기준 목록은 코드 수정·배포로 관리하고 향후 선택 중지한 알려진 코드의 기존 근거는 보존한다. 장르만 수정해도 새 버전 심사·출시를 거친다. [장르 계약](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/store-genre-filter-plan.md)을 따른다.

외부 결과는 해당 요청 ID·버전에 기록한다. 현재 버전의 승인 결과만 출시 요청 조건으로 인정한다.
변경된 상세정보를 과거 승인이 승인하지 않으며, 요청에 첨부한 과거 스냅샷은 보존한다.
동일한 처리 중 요청은 중복 발행하지 않는다. 거절 후 재요청은 새 ID를 사용한다.
심사·출시 제출 본문은 requestId·contentVersion이 필수다. 같은 명령은 과거 요청으로 수렴하고 새 의사는 새 requestId로 전송한다.
프로젝트 전체의 진행 중 심사와 출시는 각각 1건이며 철회 접수 중에도 유지한다.
activeReviewRequest·activeReleaseRequest는 작업본 버전과 독립적으로 조회한다. 편집·다음 버전 심사는 이전 출시 처리 중에도 가능하다.
철회는 선택 reason과 명시적 대상을 접수하며 PENDING에서 WITHDRAWN 또는 DENIED로 확정된다. 접수는 최종 성공을 보장하지 않는다.
Project의 APPROVED·PUBLISHED 상태와 공개본 참조, /publish API는 없다.

Studio는 ProjectReviewRequested·ProjectReleaseRequested를 발행한다.
ProjectReviewApproved/Rejected·ProjectReleaseCompleted/Rejected는 외부 요청 결과로 수신한다.
ProjectReviewWithdrawalRequested·ProjectReleaseWithdrawalRequested를 발행하고 각각의 WithdrawalResolved 결과를 수신한다.
예약·채널·공개본 관리·CI·테스터·웹훅·분할 업로드 세션은 제공하지 않는다.

---

## review

Review는 Studio의 요청 당시 상세정보·전체 ZIP 스냅샷을 접수하고 수동 승인·반려의 원본을 관리한다.
[모델](domain/models/review.md) · [실행·검증](../.kiro/specs/review/design.md#사용-안내) · [개선 계획·완료 기록](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/review-simplification-plan.md)

REVIEWER·ADMIN만 접근하며 판정자는 Auth JWT sub로 기록한다.
요청 ID는 Studio의 reviewRequestId와 같고 REQUESTED에서 APPROVED·REJECTED·WITHDRAWN 중 하나로 최종 전이한다.
재심사는 새 요청이며 과거 판정·스냅샷을 유지한다. 제출 스냅샷과 심사자 응답의 genres에는 알려진 코드 1~3개가 포함된다. 이미 제출한 장르가 나중에 선택 중지돼도 기존 심사는 이어갈 수 있다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /api/v1/reviews | 상태·Project 필터, 페이지 조회 |
| GET | /api/v1/reviews/{reviewRequestId} | 제출 스냅샷·판정 조회 |
| GET | /api/v1/reviews/{reviewRequestId}/build-download | 읽기 전용 S3/MinIO 다운로드 URL |
| POST | /api/v1/reviews/{reviewRequestId}/approve | 승인 |
| POST | /api/v1/reviews/{reviewRequestId}/reject | 필수 feedback으로 반려 |

동일 판정·사유의 재시도는 최초 결과를 반환하며 새 이벤트를 만들지 않는다.
확정 판정·반려 사유 변경은 409다. 동시 판정은 행 잠금으로 직렬화하고 판정·Outbox를 함께 저장한다.
목록·상세·판정 응답에는 내부 저장 경로가 없다. URL은 기본 5분이며 캐시하지 않는다.

ProjectReviewRequested를 새 그룹 review-project로 수신하고 ProjectReviewApproved/Rejected를 발행한다.
Studio가 원래 요청 ID·버전에 결과를 기록하고 현재 버전의 출시 요청 조건을 계산한다.
ProjectReviewWithdrawalRequested는 같은 그룹에서 접수하고 판정과 같은 잠금 경계에서 처리한다.
심사 검토 착수 후에도 최종 판정 전 철회를 허용하며 먼저 커밋된 결과를 ProjectReviewWithdrawalResolved로 전달한다.
Review는 실제 출시·상품 공개를 수행하지 않는다.

구 심사 흐름·여섯 유형·등급 연동·배정·체크리스트·SLA·이의 제기는 제거했다.
V1~V9 SQL·역사 자료를 보존하고 V10의 project_review만 새 업무에 사용한다.

---

## catalog

상품 검색·목록은 [노출 계약](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/catalog-listing-policy-plan.md)의 독립 설정을 따른다. CREATOR GET/PUT `/api/v1/products/by-code/{code}/listing`은 자기 상품의 AUTO/LISTED/UNLISTED, ADMIN GET/PUT `/api/v1/admin/products/{code}/listing/restriction`은 독립 강제 숨김을 관리한다. AUTO는 최종 ON_SALE만 노출하고 관리자 숨김이 우선한다. Catalog 공개 목록과 Store 검색·페이지·총개수는 listed=true만 포함한다. [공개 상세 B](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/public-product-detail-plan.md)에 따라 최종 listed=false이면 Catalog/Store 공개 상세와 새 주문도 404로 거절한다.

상품 다운로드는 ADMIN GET/PUT `/api/v1/admin/products/{code}/downloads/restriction`의 독립 제한으로 관리한다. 202는 제한 상태·감사·작업 예약 접수이며 저장소 적용은 PENDING/APPLIED/FAILED로 별도 조회한다. [저장소 적용·보장 한계](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/download-restriction-plan.md)를 따른다.

판매자 자율 중지와 관리자 판매 제한의 API·역할·기존 주문 영향·사본 버전은 [판매 상태 계약](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/catalog-sales-policy-plan.md)을 따른다.

Studio의 출시 요청을 처리하고 공개 상품·공개본의 정본을 관리한다.
상세 범위·버전 정책·검증 기준은 [Catalog MVP 계획](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/catalog-mvp-plan.md)을 따른다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/products` | 공개 상품 페이지 목록. page=0, size=20, 최대 100 |
| GET | `/api/v1/products/by-code/{productCode}` | 현재 공개본 상세·saleStatus·catalogVersion. no-store |
| GET·PUT | `/api/v1/products/by-code/{code}/sales` | CREATOR 본인의 판매 의사 조회·변경. expectedCatalogVersion 필요 |
| GET·PUT | `/api/v1/admin/products/{code}/sales/restriction` | ADMIN의 독립 판매 제한 조회·설정·해제 |
| GET | `/api/v1/admin/catalog-release-jobs` 및 `/{requestId}` | ADMIN의 내구 공개 작업·실패/재시도 조회. no-store |

공개 목록·상세 두 API는 익명 조회를 허용한다. 판매 관리 API는 JWT 역할·소유권을 검증한다. 목록은 최초 공개 시각·상품 ID 내림차순이다.
목록·상세·공개 이벤트·Store 내부 정본에는 승인받은 genres 1~3개를 포함한다.
상세에는 공개 자료·가격·자료 버전·빌드 버전을 제공하고 저장 경로·소유자 subject·내부 요청 ID는 노출하지 않는다.

수신은 `ProjectReleaseRequested`(stove.studio.v1), 발행은
`ProjectReleaseCompleted/Rejected`(stove.release.v1)다. 결과의 requestEventId로 Studio 원본 요청을 연결한다.
실제 공개본 또는 판매 조건 변경 시 `CatalogProductPublished`(stove.catalog.v1)를 같은 트랜잭션의 Outbox에 추가한다. 콘텐츠 버전과 독립적인 catalogVersion으로 Store 사본을 갱신한다.
최초 공개와 더 높은 버전 공개를 처리하며, 낮은 버전의 새 요청은 거절한다.
원요청·불변 스냅샷·Inbox를 먼저 내구 접수한다. 자동 공개 작업은 공개본·요청 결과·Outbox·작업 완료를 같은 트랜잭션으로 확정한다.
ProjectReleaseWithdrawalRequested를 수신하고 공개 확정과 같은 종료 경계에서 조정해 ProjectReleaseWithdrawalResolved를 발행한다.
고정 철회 유예시간은 없다. 철회 선확정 시 기존 공개본을 유지하고 공개 선확정 시 해당 결과를 유지한다.

재작성 전 견적·프로모션·판매 시작/중지·재색인·ID별 상세 API는 제거했다. 현재 판매 관리는 위 별도 GET·PUT 계약을 사용한다.
이 문서의 미완료 모듈에서 언급하는 구 Catalog 연동은 재작성 전 기록이며 현재 연결 계약이 아니다.

---

## store

Catalog 공개 상품의 검색·조회 사본을 MySQL에 저장한다. [MVP 범위](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/store-mvp-plan.md) · [실행·수동 확인](../.kiro/specs/store/design.md#사용-안내).

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/storefront/genres` | 익명 장르 기준 목록·선택 가능 여부 |
| GET | `/api/v1/storefront/products?q=&genres=ACTION&genres=RPG&sort=NEWEST&page=0&size=20` | 제목·모든 선택 장르(AND) 검색·정렬·페이지 |
| GET | `/api/v1/storefront/products/{productCode}` | 현재 반영된 공개 상품 상세 |

목록·상세는 익명 GET이다. NEWEST·PRICE_ASC·PRICE_DESC를 지원하며 size 상한은 100이다.
초기 동기화 전 503, 잘못된 입력 400, 없는 상품 코드 404를 반환한다.
수신은 `CatalogProductPublished`(stove.catalog.v1), 업무 이벤트 발행은 없다.
시작 시 Catalog 내부 전체 목록·상세로 초기 사본을 만들며 실패하면 자동 재시도한다. 준비 후에도 주기적으로 정본을 대조해 누락·구버전 사본을 복구한다. 기본 재시도 간격은 5초, 정상 대조 간격은 1분이며 설정으로 조정한다. 준비된 사본은 백그라운드 대조가 실패해도 계속 조회할 수 있다. 재시작 시 전체 대조를 새로 시작한다.
Store 동기화는 CATALOG_STORE_SERVICE_KEY로 보호한 Catalog `/internal/v1/store-products`와 `/internal/v1/store-products/{code}`에서 숨긴 상품까지 읽는다. 상세의 필수 listed 등을 검증하며 내부 키는 이 두 GET에만 전달한다. 공개 API로 우회하지 않는다. 숨긴 사본은 보존하되 공개 상세는 404로 거절한다.

Kafka 반영은 catalogVersion으로 공개 사본을 교체하고 중복·역순은 현재 사본을 유지한다. 콘텐츠 버전이 같아도 판매 상태 변경을 반영한다. Kafka의 동일 Catalog 버전 내용 충돌은 실패한다. 직접 조회·검증한 Catalog 정본은 동일 버전의 내용 차이도 교정하며, 처리 중 반영된 더 최신 사본과 상품 ID·코드·최초 공개 시점의 식별 충돌은 덮어쓰지 않는다.
업무 테이블은 store_product·장르 관계 store_product_genre·자동 교정의 변경 전후를 원자적으로 기록하는 store_reconciliation이다. 장르 조건도 페이지·총건수 계산 전에 적용하고 정본 대조/교정과 감사에 포함한다. Inbox·Outbox·Elasticsearch·Redis 캐시·featured는 사용하지 않는다. [자동 동기화·복구의 범위와 검증](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/store-auto-reconciliation-plan.md)을 따른다.
가격은 공개 KRW 정가이며 saleStatus·catalogVersion을 조회 사본으로 제공한다. 구매 허용의 최종 판단은 Order가 Catalog 정본으로 확인한다. [판매 상태 계약](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/catalog-sales-policy-plan.md)을 따른다.

---

## order

공개 상품 하나의 주문 스냅샷·청구액을 고정하고 내 주문 조회·최종 결제 결과 반영을 담당한다.
[MVP 범위](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/order-mvp-plan.md) · [실행·결과 확인](../.kiro/specs/order/design.md#사용-안내)

상품 근거는 CATALOG_ORDER_SERVICE_KEY·X-Order-Service-Key로 보호한 Catalog `/internal/v1/order-products/{code}`에서 조회한다. 상품 식별·가격·판매 상태·publicDetailAllowed만 받고 공개 본문·저장 경로는 받지 않는다. 새 주문은 공개 상세 허용 근거가 false이면 404이며 같은 requestId의 기존 주문 재전송·기존 주문 승인 접수는 원래 계약을 유지한다. 기존 관리자 판매 제한의 승인 접수 차단은 유지한다. [정책·연계 오류·검증 경계](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/public-product-detail-plan.md)를 따른다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | /api/v1/orders | requestId·productCode·expectedContentVersion·expectedAmount로 생성 또는 동일 요청 재조회 |
| GET | /api/v1/orders | 내 주문 페이지. page=0·size=20, 최대 100 |
| GET | /api/v1/orders/{orderNo} | 본인 상세, 타인·없는 주문 404 |
| POST | /api/v1/orders/{orderNo}/refund | MEMBER의 서버 원금 전액 접수. 신규/진행 중 202, 기존 최종 결과 200 |
| GET | /api/v1/orders/{orderNo}/refund | MEMBER의 본인 환불 상태·첫 접수·완료 시각·반려 사유 |
| GET | /api/v1/admin/refunds | ADMIN의 상태별 목록. 기본 PENDING_REVIEW, page=0/size=20, 최대 100 |
| GET | /api/v1/admin/refunds/{refundId} | ADMIN의 원구매·처리 상태·최초 판정 상세 |
| POST | /api/v1/admin/refunds/{refundId}/decision | ADMIN의 requestId·APPROVED/REJECTED·사유. 판정자는 JWT sub |

MEMBER와 검증된 Auth JWT sub를 구매자로 사용한다. member_id·본문·임의 헤더로 구매자를 선택하지 않는다.
Catalog의 코드별 내부 주문 근거를 DB 트랜잭션 밖에서 읽고 공개 접근·판매 가능 여부와 버전·정가를 확인한다. 기대 값 차이는 409이며 무료 상품도 409다.
정가 listAmount·할인액 discountAmount=0·청구액 amount를 고정하고 구매자별 requestId 유일 제약으로 중복 생성을 막는다.
같은 입력의 재요청은 Catalog 없이 기존 주문의 현재 상태를 반환한다. 신규 주문은 ON_SALE만 허용한다. 새 승인 접수는 관리자 BLOCKED를 확인하되 판매자 STOPPED·현재 가격으로 기존 주문을 바꾸지 않는다. 이미 확정한 승인 영수증은 유지한다. [판매 상태와 승인 경계](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/catalog-sales-policy-plan.md)를 따른다.
새 requestId의 같은 상품 구매는 기존 주문 상태와 무관하게 새 주문이다. 막는 것은 판매 금지뿐이며, 이미 가진 상품은 License 등록이 409 `PRODUCT_ALREADY_OWNED`로 막고 구매분은 보관함에 남는다([결정 89](decisions.md#purchase-blocked-only-by-sale-ban)).
최종 FAILED만 새 주문을 허용한다. V11의 구매자·상품 ID/코드 유일 제약으로 동시 생성도 제한한다.

발행은 OrderPlaced(stove.order.v1), 수신은 OrderPaymentCompleted/OrderPaymentFailed(stove.payment.v1)다.
결과의 requestEventId·주문번호·구매자·금액·통화가 일치해야 PENDING → PAID/FAILED로 전이한다.
원래 eventId·내용의 재전달은 무효과이며 확정 결과를 새 결과로 덮어쓰지 않는다.
주문·Outbox와 결과·상태·Inbox는 각각 같은 트랜잭션으로 저장한다.

원구매는 purchase_order에 보존한다. 환불 접수/판정은 order_refund, 실제 지급 근거는 order_license_grant, 실행 이력은 order_refund_attempt에 저장한다. 원래 PAID와 환불 상태를 분리하고 V20까지 기존 마이그레이션·자료를 보존한다.
환불 고객 상태는 PENDING_REVIEW/PROCESSING/COMPLETED/FAILED/REJECTED다. FAILED는 검증된 PG 실패와 실제 복구 완료 뒤에만 표시한다. REJECTED에는 고객에게 보이는 rejectionReason이 있고 승인 감사 메모·판정자는 공개하지 않는다.
최초 내구 접수가 결제 확정 후 정확히 4시간 이내면 자동 허용하고, 이후에는 관리자 심사한다. 먼저 커밋된 판정이 확정되며 같은 명령·판정자·내용만 최초 판정 영수증으로 재전송 처리한다. 판정 변경·다른 명령은 409다.
승인은 실제 License 지급 확인과 함께 정지 요청을 발행하고, 실제 정지 확인 뒤에만 RefundExecutionRequested를 발행한다. 조회/판정은 no-store이며 관리자 200은 환불 완료를 의미하지 않는다. 현재 고객 재시도 HTTP는 없고 반려 후 재심사는 미결이다. [계약·검증](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/refund-session-handoff.md).
구 견적·다중 항목·Redis·만료·취소·정산 계산을 사용하지 않는다.
Order 독립 검증은 결과 픽스처를, [Payment 연계 검증](../.kiro/specs/payment/design.md#사용-안내)은 실제 Payment 앱을 사용한다.

---

## payment

Order의 PRODUCT_SALE_RESTRICTED는 새 승인 접수 전 관리자 제한이다. Payment는 REQUESTED와 ORDER_SALE_RESTRICTED를 저장하고 PG 승인 POST 없이 원래 기한 내 재확인한다. 제한 해제·만료·기존 영수증의 처리는 [판매 상태 계약](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/catalog-sales-policy-plan.md)을 따른다.

확정된 주문의 결제 접수, 외부 PG 승인/조회, 미확정 건 재개·내 결제 조회와 허용된 환불의 PG 실행/결과 확인을 담당한다.
[모델](domain/models/order-payment.md#payment) · [실행](../.kiro/specs/payment/design.md#사용-안내) · [MVP 범위·검증](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/payment-mvp-plan.md)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /api/v1/payments?page=0&size=20 | JWT sub 기준 본인 목록 |
| GET | /api/v1/payments/{orderNo} | 본인 상세, 타인·없는 결제 404 |
| GET | /api/v1/payments/{orderNo}/checkout | 본인 PENDING/PORTONE_TEST 결제의 공개 SDK 설정. 생성·승인 API가 아님 |
| GET | /api/v1/payments/{orderNo}/attempt-failures?page=0&size=20 | 본인 실패 시도 이력·수집 상태. no-store, DB 조회만 수행 |
| POST | /api/v1/payments/webhooks/portone | 설정된 PortOne 서명·스토어 검증 뒤 내구 힌트 접수. 공개 edge가 Payment로 직접 전달 |

MEMBER 역할이 필요하다. 내부 요청·결과 이벤트 ID와 공급자 거래 ID는 API에 노출하지 않는다.
provider는 SIMULATED 또는 PORTONE_TEST다. 고객 환불 접수는 Order API를 사용한다. Payment에 고객 취소·승인 결과 주입 API는 없다. ADMIN 전용 `/api/v1/admin/refund-cancellation-holds`는 중단 목록·상세·재개를, `/api/v1/admin/refund-delays`는 장기·귀속 미확정 목록·상세·조사 이력/기록과 PG 재조회 근거 종결(`/{attemptId}/closure`, [결정 127](decisions.md#refund-delay-closure))과 자동 송신이 멈춘 환불의 실패 종결(`/{attemptId}/failure-closure`, [결정 128](decisions.md#refund-failure-closure))을 제공한다. 최신 계약과 실행/미적용 범위는 [환불 인계](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/refund-session-handoff.md)를 따른다. 웹훅은 최종 결제/환불 증명이 아니라 서버 PG 조회를 깨우는 힌트다. 공개 webhook 경로의 서명 검증과 회원 checkout의 JWT 인증을 구분한다.

기본 결제 수신은 OrderPlaced, 결과 발행은 OrderPaymentCompleted/OrderPaymentFailed다.
입력은 api.listener의 Mapper에서 내부 값으로 변환한다. ReceiveOrderPayment가 원래 요청 전체를 비교하고 PENDING·Inbox를 함께 저장한다.
주기 실행이 처리 시각에 도달한 PENDING을 ResolvePayment로 재개한다. PORTONE_TEST는 구매자가 SDK 결제창을 승인하고 서버가 PG 거래의 주문·금액을 대조한다. 기본 주기 1초·20건·오류 재시도 간격 5초다.
PG는 Payment 트랜잭션·행 잠금 밖에서 호출하고 같은 paymentId를 멱등 키로 사용한다.
확인된 승인·거절만 결과와 Outbox를 함께 확정한다. 응답 유실·결과 저장 실패는 PENDING을 유지한다.
최종 상태·이벤트 ID·시각은 고정하며 Order 상태는 Order가 결과 검증 뒤 변경한다.
PortOne의 개별 FAILED는 별도 이력이며 최종 OrderPaymentFailed로 바꾸지 않는다. 같은 orderNo·paymentId·금액의 PENDING checkout으로 재결제한다. 서버가 원구매와 대조한 실패 근거를 V28에 보존하며, 승인·Outbox 커밋 뒤 별도로 수집한다. 이력 조회 실패는 내구 재예약하고 PAID 뒤에도 이력 GET만 재개한다. 종료/만료 정책과 실제 TEST 재결제 전체 검증은 후속이다.

기본 결제는 purchase_payment에 저장한다. 모의 PG의 결정·비용은 독립된 simulators/mock-pg의 전용 MySQL에 저장한다. PortOne 웹훅/환불 실행·취소 예약/중단 이력은 후속 마이그레이션으로 추가했다.
V12는 Payment에 비용 스냅샷만 추가한다. 구 simulated_payment_charge는 이관 근거로 보존하고 신규 결정을 쓰지 않는다. 공유 환경 전환은 [별도 절차](../.kiro/specs/payment/design.md#사용-안내)를 따른다.
환불은 RefundExecutionRequested를 내구 접수한 뒤 고정 attemptId/본문과 최초 준비 3시간 창으로 취소·조회한다. 같은 실행의 재송신/조회 예약과 운영 중단 이력을 별도로 보존한다. 확인된 전액 취소/실행 성공/확정 실패는 결과 이벤트를 발행하며 원 purchase_payment의 PAID는 바꾸지 않는다. 오류별 자동 복구·운영 중단과 실제 TEST 범위는 [환불 계약](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/refund-session-handoff.md)을 따른다.
Settlement는 PG 명세를 직접 조회하며 Payment에 정산 중계 API를 두지 않는다. 실제 운영 자금 이동·정산 환불 조정은 후속이다.

---

## license

구매자·상품별 영구 이용 권한과 주문별 불변 구매 근거를 보존한다.
[MVP 범위·검증 기록](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/license-mvp-plan.md) · [조회·실행 안내](../.kiro/specs/license/design.md#사용-안내)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/library?page=0&size=20` | JWT sub 기준 내 보유 상품 페이지 |
| GET | `/api/v1/library/{productCode}` | 본인이 보유한 상품 한 개. 타인·미보유 404 |

**이벤트** — `stove.order.v1`의 OrderPaid·LicenseSuspensionRequested·LicenseRestorationRequested를 수신한다. 지급·정지·복구 완료와 LicenseGranted·LicenseSuspended·LicenseRestored Outbox를 같은 트랜잭션으로 저장한다.

- Order가 최초 PAID에 함께 저장한 상품·결제 스냅샷을 사용한다. 추가 결제 승인이나 구매 확정 절차는 없다.
- 권한은 `(buyerSubject, productId)`별 한 개이며 출처 전체를 `product_license`에 함께 저장한다. 다른 주문의 같은 상품은 충돌이다.
- 같은 주문의 입력 전체와 기존 권한·Inbox를 비교하고, 출처를 포함한 License·Inbox·지급 완료 Outbox를 함께 커밋한다.
- 최초 licenseId·제목·획득 시각은 유지한다. Catalog·Order·Payment HTTP 조회로 지급하거나 가격을 바꾸지 않는다.
- MEMBER JWT가 필요하다. 본문·쿼리·헤더의 회원 ID로 소유자를 바꾸지 않는다.
- 운영 HTTP는 제공하지 않는다. 현재 지급·정지·복구 완료 이벤트는 Outbox 릴레이를 사용한다. 지급 실패는 공통 재시도·DLT로 남기며 자동 환불을 요청하지 않는다.
- 기존 마이그레이션과 구 자료를 보존하고 V5에서 출처를 권한에 통합한다. license_purchase는 과거 자료로만 남긴다. 기존 PAID 주문의 자동 소급 지급은 없다.
- 결제가 끝나면 구매 근거(보관함 항목)를 만들고, 고객이 `/api/v1/storage/{licenseId}/registration`으로 등록할 때 보유권이 생긴다. 같은 상품의 `ACTIVE` 보유권은 1개뿐이다. 정지·복구·환불 완료는 구매 근거 단위이며 끝난 항목은 라이브러리·보관함에서 빠진다. Download는 상세의 ACTIVE를 확인한다. 이미 받은 파일 회수는 후속이다([결정 89~98](decisions.md#purchase-blocked-only-by-sale-ban)).

---

## download

본인의 상품 보유권과 현재 공개 빌드를 조회해 전체 ZIP의 만료 URL을 발급한다.
업무 DB·MongoDB·Kafka·보유권/공개본 사본은 사용하지 않는다. [범위·검증](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/download-mvp-plan.md) · [실행](../.kiro/specs/download/design.md#사용-안내)

| 메서드 | 경로 | 응답 |
|---|---|---|
| GET | `/api/v1/downloads/{productCode}/ticket` | productCode·version·fileSize·checksum·downloadUrl·expiresAt |

- MEMBER·유효 JWT의 sub로 본인을 식별하고 같은 사용자 토큰으로 License 상세를 조회한다.
- Catalog의 `/internal/v1/products/{productCode}/build`는 전용 서비스 키로만 조회한다. Gateway·공개 OpenAPI에는 노출하지 않는다.
- License·Catalog의 상품 ID·코드를 대조하고, 현재 COMPLETED 공개본의 전체 빌드만 사용한다.
- 저장소 HEAD로 객체·크기를 확인한 뒤 해당 파일의 GET URL을 발급한다. 기본 TTL 5분, 허용 범위 1초~15분이다.
- 응답은 no-store다. 쿼리·과거 버전·플랫폼·델타 선택·manifests API는 제공하지 않는다.
- 미보유/지급 전/이용권 SUSPENDED와 상품 PRODUCT_DOWNLOAD_RESTRICTED는 403, 현재 공개 상품 없음 404, 파일 누락/크기 불일치 409, 연계 장애/응답 불일치 503이다.
- URL은 만료 전 반복 사용·공유가 가능하다. License 보유권과 URL 유효기간을 구분한다.
- 외부 이벤트 수신·발행은 없으며 발급·실패·재시작이 주문·보유권·공개본을 변경하지 않는다.

---

## settlement

[실행](../.kiro/specs/settlement/design.md#사용-안내) · [모델](domain/models/settlement.md) · [범위·검증](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/settlement-mvp-plan.md)

기존 OrderPaid를 소비해 판매자 매출·배분·월 마감과 PG 지급 명세 수령 대사를 담당한다.
PG 비용은 플랫폼 전액 부담이다. 고객 결제액과 기존 주문·결제·이용권 계약은 유지한다.

| 메서드 | 경로 | 권한·동작 |
|---|---|---|
| GET | /api/v1/settlements/me/sales | CREATOR 본인 매출 |
| GET | /api/v1/settlements/me/months | CREATOR 본인 월 합계 |
| GET | /api/v1/settlements/sales | ADMIN 전체/판매자별 매출 |
| GET | /api/v1/settlements/months | ADMIN 전체/판매자별 월 합계 |
| POST | /api/v1/settlements/sellers/{sellerSubject}/months/{month}/close | ADMIN 지난 월 마감 |
| POST | /api/v1/settlements/pg-receipts | ADMIN 기존 PG 명세 수집·접수·최초 대사 |
| GET | /api/v1/settlements/pg-receipts | ADMIN 수령 명세 목록 |
| GET | /api/v1/settlements/pg-receipts/{providerSettlementId} | ADMIN 명세·대사 사유 상세 |
| POST | /api/v1/settlements/pg-receipts/{providerSettlementId}/reconcile | ADMIN 저장 명세 재대사 |

본인 sellerSubject는 JWT sub다. PG 거래/비용·지급 명세는 Settlement 소유 포트와 HTTP 어댑터로 외부 PG에서 직접 조회한다.
상품 소유자는 Catalog 내부 GET으로 확인한다. 업무 모듈 DB를 직접 읽지 않는다.
매출·월 합계·Inbox를 함께 커밋하고 원본 13개 값 비교·업무 유일 제약으로 중복과 출처 재사용을 검증한다.
월 마감은 OPEN→CLOSED이며 마감 후 지연 거래는 현재 월에 반영한다. PG 수령은 별도의 명세 접수·대사 상태다.
공통 운영 API·Outbox 릴레이·자동 마감·환불·계산서·판매자 송금은 제공하지 않는다.

---

## gateway

Spring Cloud Gateway. 라우팅과 함께 **내부 전용 API 를 외부에 노출하지 않는 역할**을 한다.
재작업 후 BFF를 겸하며 OAuth 기밀 클라이언트로 로그인·토큰 교환·갱신을 수행한다.
브라우저는 세션 쿠키를 사용하고 세션과 토큰은 Redis에 공유한다. [상세 구성과 검증](auth-bff.md)을 참고한다.

| 경로 | 대상 | 비고 |
|---|---|---|
| `/api/v1/auth/**`, `/oauth2/**`, `/login`, `/logout`, `/.well-known/**` | auth | 가입·로그인·토큰, 브라우저 경로의 Host 유지 |
| `/api/v1/storefront/**` | store | |
| `/api/v1/products/**` | catalog | **GET 만 허용** |
| `/api/v1/promotions/**` | catalog | 판매자 `CREATOR`, 플랫폼 `ADMIN` |
| `/api/v1/orders/**` | order | |
| `/api/v1/payments/**` | payment | |
| `/api/v1/library/**` | license | |
| `/api/v1/downloads/**` | download | |
| `/api/v1/studio/**` | studio | |
| `/api/v1/reviews/**` | review | 운영 |
| `/api/v1/settlements/**` | settlement | `/me/**`는 `CREATOR`, 나머지는 `ADMIN` |

catalog 라우트에 `Method=GET` 조건이 걸려 있어서, 주문 금액을 재계산하는
`POST /api/v1/products/quote` 는 게이트웨이를 통해 호출할 수 없다.
**서비스 간 내부 호출로만 도달 가능하다.**

---

## 운영 API

Outbox·DLT 운영 기능은 모든 서비스에서 기본 비활성(`stove.messaging.ops.enabled: false`)이다. 설정은 시작 시점에 정해지므로, 재처리가 필요한 서비스의 지정 인스턴스만 `STOVE_MESSAGING_OPS_ENABLED=true`로 다시 시작해 사용하고 작업 뒤 원래 설정으로 되돌린다. 비활성 상태에서는 ADMIN에게도 404다. Gateway에는 `/api/v1/ops/**` 라우트가 없으며, 켠 인스턴스에도 서비스 직접 접근과 ADMIN JWT가 필요하다. MEMBER·익명은 사용할 수 없다.

| HTTP API | 동작 | ADMIN 허용 |
|---|---|---|
| `GET /api/v1/ops/outbox/dead` | 발행을 포기한 Outbox 행과 사유 조회 | 예 |
| `POST /api/v1/ops/outbox/dead/{eventId}/requeue` | 지정한 DEAD 이벤트 회수 | 예 |
| `POST /api/v1/ops/outbox/dead/requeue-all` | DEAD 이벤트 일괄 회수 | 아니오(403) |
| `GET /api/v1/ops/dlt?topic={원본토픽}.DLT&max=20` | 재투입 그룹의 미처리 레코드 조회. 오프셋을 커밋하지 않음 | 예 |
| `POST /api/v1/ops/dlt/replay?topic={원본토픽}.DLT&max=100` | 원본 토픽에 재발행하고 재투입 그룹의 오프셋 커밋 | 예 |

| 서비스 | 대상 | 비고 |
|---|---|---|
| Auth·Studio·Review·Catalog·Order·Payment·License·Settlement | Outbox DEAD·DLT | 위 규칙을 따른다 |
| Store | DLT만 존재 | 운영 API를 열지 않는다. Catalog 정본과의 주기 대조가 사본 차이를 자동 보완한다([자동 복구](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/store-auto-reconciliation-plan.md)) |
| Download·Gateway | 없음 | Outbox·Kafka 소비가 없다 |

실패 원인과 현재 주문·환불 상태를 확인한 뒤 재처리한다. 재투입이 원래 업무 순서를 복원하지 않는 이유는 [이벤트 순서](event-ordering.md)를 참고한다. 요청 계약은 [OutboxOpsController](../common/messaging/src/main/java/com/stove/common/messaging/ops/OutboxOpsController.java), [DltOpsController](../common/kafka/src/main/java/com/stove/common/kafka/ops/DltOpsController.java)에 있다.

---

## 외부 연동 대역

업무용 외부 기능의 계약은 `core/port`에 있고, 다른 서비스의 HTTP 호출·S3·PG 구현체는 `adapter/`에 있다. Auth의 프레임워크 SPI 구현도 `adapter/`에 두며 객체 조립은 `config`가 담당한다.
**어느 것이 실제 동작이고 어느 것이 흉내인지** 여기서 구분한다.

| 포트 | 스텁 | 실제 어댑터 | 선택 방법 |
|---|---|---|---|
| `BuildStorage` (studio) | — | **`S3BuildStorage`** | `stove.storage.provider` = `s3` |
| `ReviewBuildStorage` (review) | — | **`S3ReviewBuildStorage`** | 읽기 전용 S3/MinIO 계정 |
| `BuildStorage` (download) | — | **`S3BuildStorage`** | 읽기 전용 S3/MinIO HEAD·presigned GET |
| `PgGateway` (payment) | 외부 모의 PG | `SimulatedPgHttpGateway` | PG 주소·Payment 전용 키 |
| `PgPayments`·`PgSettlements` (settlement) | 외부 모의 PG | 각 HTTP 어댑터 | PG 주소·Settlement 읽기 키 |
| `CatalogOwners` (settlement) | — | `CatalogOwnerHttpClient` | Catalog 주소·전용 내부 키 |

### 실제로 동작하는 것

**`S3BuildStorage` / `S3ReviewBuildStorage`** — Studio가 전체 ZIP·썸네일을 임시 디스크에서 검사한 뒤 S3/MinIO에 저장한다.
Review는 제출 스냅샷의 객체 존재·크기를 확인하고 심사자에게 GET 서명 URL을 발급한다.
실제 MinIO 다운로드·SHA-256·만료와 읽기 전용 계정의 업로드·삭제 거절을 검증했다.
Download의 `S3BuildStorage`도 읽기 전용 계정으로 HEAD·presigned GET을 사용한다.
CDN 전략·자체 HMAC 토큰·파일 중계는 제공하지 않는다.

### 외부 모의 PG의 범위

simulators/mock-pg는 실제 HTTP·독립 프로세스·전용 MySQL로 결제 결정·비용·지급 명세를 보존한다.
승인·거절과 지급 결과는 모의 결과이며 실제 금융 거래·은행 입금·판매자 송금은 수행하지 않는다.
Payment·Settlement는 각자의 포트와 어댑터를 사용하고 공급자 구현 JAR·DB를 공유하지 않는다.
실제 PG 도입 시 공급자 계약에 따라 결제창·인증·서버 검증·웹훅·비용 확정·정산 자료 수집을 확장한다.
현재 인터페이스나 URL 교체만으로 연동이 완성된다고 가정하지 않는다.

PG의 Payment·Settlement·제어 키는 서로 다르며 제어 키는 검증 운영자만 사용한다.
잘못된 키는 401, 유효한 다른 역할 키의 접근은 403이다. 키 미설정·중복이면 PG는 기동하지 않는다.
Payment에 내장 승인 폴백을 두지 않는다. 기존 결제 결정은 [이관 절차](../.kiro/specs/settlement/design.md#사용-안내)를 따른다.
