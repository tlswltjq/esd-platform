# 서비스별 기능 명세

각 서비스가 **무엇을 책임지고, 어떤 요청과 이벤트를 받고, 무엇을 내보내는지** 정리한다.
시스템 전체의 이벤트 흐름은 [업무 흐름](domain/workflows.md), 설계 근거는 [아키텍처](architecture.md)를 본다.
업무 용어의 정의·사례·합의 상태는 [도메인 용어집](domain-glossary.md)에서 시작한다.
도메인별 모델·속성·생성·사용 시점은 [도메인·모델 안내](domain/model-guide.md)에서 찾는다.

**모듈 재작성 현황 (2026-10-18): `auth`, `gateway`, `studio`, `review`, `catalog`, `store`, `order`, `payment`, `license`, `download` 완료.**
Auth·Gateway의 완료 범위와 검증 기록은 [auth·Gateway/BFF](auth-bff.md),
Studio의 완료 범위는 [Studio 완료 기록](studio-simplification-plan.md#재작성-완료-기록), Review는 [Review 완료 기록](review-simplification-plan.md#재작성-완료-기록)을 본다.
Catalog·Store·Order·Payment·License·Download의 범위와 실행 결과는 각 MVP 완료 기록을 따른다. 신규 구현과 검증은 완료된 열 모듈의 계약을 기준으로 한다.
나머지 서비스 명세는 기존 구현의 참고 기록이며 현재 설계·검증 기준에서 제외한다.

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
[모델](domain/models/studio.md) · [실행·요청 예제](studio-guide.md)

완료된 기준은 Auth·Gateway·Studio·Review다. 소유자는 Auth JWT sub이며 Workspace·sellerId 매핑은 없다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST / GET | /api/v1/studio/projects | 생성(201)·내 목록 |
| GET | /api/v1/studio/projects/{id} | 상세정보·현재 버전의 요청·releaseEligible |
| PUT | /api/v1/studio/projects/{id}/store-page | contentVersion으로 상세정보 수정 |
| POST / GET | /api/v1/studio/projects/{id}/builds | 전체 ZIP 등록(201)·이력 |
| POST | /api/v1/studio/projects/{id}/thumbnail | PNG/JPEG 등록(201) |
| GET | /api/v1/studio/assets/{id}/{assetId} | 공개 썸네일 |
| POST / GET | /api/v1/studio/projects/{id}/review-requests | 심사 요청·재요청(202)·이력 |
| POST / GET | /api/v1/studio/projects/{id}/release-requests | 출시 요청·재요청(202)·이력 |

외부 결과는 해당 요청 ID·버전에 기록한다. 현재 버전의 승인 결과만 출시 요청 조건으로 인정한다.
변경된 상세정보를 과거 승인이 승인하지 않으며, 요청에 첨부한 과거 스냅샷은 보존한다.
동일한 처리 중 요청은 중복 발행하지 않는다. 거절 후 재요청은 새 ID를 사용한다.
Project의 APPROVED·PUBLISHED 상태와 공개본 참조, /publish API는 없다.

Studio는 ProjectReviewRequested·ProjectReleaseRequested를 발행한다.
ProjectReviewApproved/Rejected·ProjectReleaseCompleted/Rejected는 외부 요청 결과로 수신한다.
예약·채널·공개본 관리·CI·테스터·웹훅·분할 업로드 세션은 제공하지 않는다.

---

## review

Review는 Studio의 요청 당시 상세정보·전체 ZIP 스냅샷을 접수하고 수동 승인·반려의 원본을 관리한다.
[모델](domain/models/review.md) · [실행·검증](review-guide.md) · [개선 계획·완료 기록](review-simplification-plan.md)

REVIEWER·ADMIN만 접근하며 판정자는 Auth JWT sub로 기록한다.
요청 ID는 Studio의 reviewRequestId와 같고 REQUESTED에서 APPROVED 또는 REJECTED로 한 번 전이한다.
재심사는 새 요청이며 과거 판정·스냅샷을 유지한다.

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
Review는 실제 출시·상품 공개를 수행하지 않는다.

구 심사 흐름·여섯 유형·등급 연동·배정·체크리스트·SLA·이의 제기는 제거했다.
V1~V9 SQL·역사 자료를 보존하고 V10의 project_review만 새 업무에 사용한다.

---

## catalog

Studio의 출시 요청을 처리하고 공개 상품·공개본의 정본을 관리한다.
상세 범위·버전 정책·검증 기준은 [Catalog MVP 계획](catalog-mvp-plan.md)을 따른다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/products` | 공개 상품 페이지 목록. page=0, size=20, 최대 100 |
| GET | `/api/v1/products/by-code/{productCode}` | 현재 공개본 상세 |

두 API는 익명 조회를 허용한다. 목록은 최초 공개 시각·상품 ID 내림차순이다.
상세에는 공개 자료·가격·자료 버전·빌드 버전을 제공하고 저장 경로·소유자 subject·내부 요청 ID는 노출하지 않는다.

수신은 `ProjectReleaseRequested`(stove.studio.v1), 발행은
`ProjectReleaseCompleted/Rejected`(stove.release.v1)다. 결과의 requestEventId로 Studio 원본 요청을 연결한다.
실제 공개본 변경 시 `CatalogProductPublished`(stove.catalog.v1)를 같은 트랜잭션의 Outbox에 추가한다.
최초 공개와 더 높은 버전 공개를 처리하며, 낮은 버전의 새 요청은 거절한다.
공개 데이터·요청 결과·Inbox·Outbox는 같은 트랜잭션으로 확정한다.

기존 견적·프로모션·판매 시작/중지·재색인·ID별 상세 API는 제거했다.
이 문서의 미완료 모듈에서 언급하는 구 Catalog 연동은 재작성 전 기록이며 현재 연결 계약이 아니다.

---

## store

Catalog 공개 상품의 검색·조회 사본을 MySQL에 저장한다. [MVP 범위](store-mvp-plan.md) · [실행·수동 확인](store-guide.md).

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/storefront/products?q=&sort=NEWEST&page=0&size=20` | 제목 부분 검색·정렬·페이지 |
| GET | `/api/v1/storefront/products/{productCode}` | 현재 반영된 공개 상품 상세 |

목록·상세는 익명 GET이다. NEWEST·PRICE_ASC·PRICE_DESC를 지원하며 size 상한은 100이다.
초기 동기화 전 503, 잘못된 입력 400, 없는 상품 코드 404를 반환한다.
수신은 `CatalogProductPublished`(stove.catalog.v1), 업무 이벤트 발행은 없다.
시작 시 Catalog 기존 목록·상세 API로 초기 사본을 만들고 재시작하면 처음부터 다시 읽는다.
더 높은 contentVersion만 교체하고 중복·역순은 현재 사본을 유지한다. 동일 버전의 다른 데이터·식별 충돌은 실패한다.
업무 테이블은 store_product 하나이며 Inbox·Outbox·Elasticsearch·Redis 캐시·featured는 사용하지 않는다.
가격은 공개 KRW 정가이며 구매·할인·판매 가능 여부는 다루지 않는다.

---

## order

공개 상품 하나의 주문 스냅샷·청구액을 고정하고 내 주문 조회·최종 결제 결과 반영을 담당한다.
[MVP 범위](order-mvp-plan.md) · [실행·결과 확인](order-guide.md)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | /api/v1/orders | requestId·productCode·expectedContentVersion·expectedAmount로 생성 또는 동일 요청 재조회 |
| GET | /api/v1/orders | 내 주문 페이지. page=0·size=20, 최대 100 |
| GET | /api/v1/orders/{orderNo} | 본인 상세, 타인·없는 주문 404 |

MEMBER와 검증된 Auth JWT sub를 구매자로 사용한다. member_id·본문·임의 헤더로 구매자를 선택하지 않는다.
Catalog의 기존 코드별 공개 상세를 DB 트랜잭션 밖에서 읽고 버전·정가를 비교한다. 차이는 409이며 무료 상품도 409다.
정가 listAmount·할인액 discountAmount=0·청구액 amount를 고정하고 구매자별 requestId 유일 제약으로 중복 생성을 막는다.
같은 입력의 재요청은 Catalog 없이 기존 주문의 현재 상태를 반환한다.
새 requestId의 같은 상품 구매는 기존 PENDING이면 409 PURCHASE_IN_PROGRESS, PAID이면 409 PRODUCT_ALREADY_PURCHASED다.
최종 FAILED만 새 주문을 허용한다. V11의 구매자·상품 ID/코드 유일 제약으로 동시 생성도 제한한다.

발행은 OrderPlaced(stove.order.v1), 수신은 OrderPaymentCompleted/OrderPaymentFailed(stove.payment.v1)다.
결과의 requestEventId·주문번호·구매자·금액·통화가 일치해야 PENDING → PAID/FAILED로 전이한다.
원래 eventId·내용의 재전달은 무효과이며 확정 결과를 새 결과로 덮어쓰지 않는다.
주문·Outbox와 결과·상태·Inbox는 각각 같은 트랜잭션으로 저장한다.

업무 테이블은 purchase_order 하나다. 기존 V1~V9와 구 자료는 보존하고 V10을 추가한다.
구 견적·다중 항목·Redis·만료·취소·정산 계산을 사용하지 않는다.
Order 독립 검증은 결과 픽스처를, [Payment 연계 검증](payment-guide.md)은 실제 Payment 앱을 사용한다.

---

## payment

확정된 주문의 결제 접수, 모의 PG 자동 처리, 미확정 건 재개와 내 결제 조회를 담당한다.
[모델](domain/models/order-payment.md#payment) · [실행](payment-guide.md) · [MVP 범위·검증](payment-mvp-plan.md)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /api/v1/payments?page=0&size=20 | JWT sub 기준 본인 목록 |
| GET | /api/v1/payments/{orderNo} | 본인 상세, 타인·없는 결제 404 |

MEMBER 역할이 필요하다. 내부 요청·결과 이벤트 ID와 공급자 거래 ID는 API에 노출하지 않는다.
공개 provider는 SIMULATED다. prepare·콜백·승인 주입·환불·운영 HTTP API는 제공하지 않는다.

수신은 OrderPlaced, 발행은 OrderPaymentCompleted/OrderPaymentFailed다.
입력은 api.listener의 Mapper에서 내부 값으로 변환한다. ReceiveOrderPayment가 원래 요청 전체를 비교하고 PENDING·Inbox를 함께 저장한다.
주기 실행이 처리 시각에 도달한 PENDING을 ResolvePayment로 재개한다. 기본 주기 1초·20건·오류 재시도 간격 5초다.
PG는 Payment 트랜잭션·행 잠금 밖에서 호출하고 같은 paymentId를 멱등 키로 사용한다.
확인된 승인·거절만 결과와 Outbox를 함께 확정한다. 응답 유실·결과 저장 실패는 PENDING을 유지한다.
최종 상태·이벤트 ID·시각은 고정하며 Order 상태는 Order가 결과 검증 뒤 변경한다.

업무 테이블은 purchase_payment, 공급자 기술 원장은 simulated_payment_charge다.
구 V1~V10·자료를 보존하고 V11로 새 테이블만 추가한다. 공유 환경 전환은 [별도 절차](payment-guide.md#기존-db와-kafka-전환)를 따른다.
실제 PG·결제창·수수료·환불·이용권·정산은 후속 범위다.

---

## license

구매자·상품별 영구 이용 권한과 주문별 불변 구매 근거를 보존한다.
[MVP 범위·검증 기록](license-mvp-plan.md) · [조회·실행 안내](license-guide.md)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/library?page=0&size=20` | JWT sub 기준 내 보유 상품 페이지 |
| GET | `/api/v1/library/{productCode}` | 본인이 보유한 상품 한 개. 타인·미보유 404 |

**이벤트** — `stove.order.v1`의 `OrderPaid`만 수신. 소비 그룹 `license-paid-order`. 발행 이벤트 없음.

- Order가 최초 PAID에 함께 저장한 상품·결제 스냅샷을 사용한다. 추가 결제 승인이나 구매 확정 절차는 없다.
- 권한은 `(buyerSubject, productId)`별 한 개이며 출처 전체를 `product_license`에 함께 저장한다. 다른 주문의 같은 상품은 충돌이다.
- 같은 주문의 입력 전체와 기존 권한·Inbox를 비교하고, 출처를 포함한 License·Inbox를 함께 커밋한다.
- 최초 licenseId·제목·획득 시각은 유지한다. Catalog·Order·Payment HTTP 조회로 지급하거나 가격을 바꾸지 않는다.
- MEMBER JWT가 필요하다. 본문·쿼리·헤더의 회원 ID로 소유자를 바꾸지 않는다.
- 구 License Outbox 릴레이와 운영 HTTP는 비활성화한다. 지급 실패는 공통 재시도·DLT로 남기며 자동 환불을 요청하지 않는다.
- 기존 마이그레이션과 구 자료를 보존하고 V5에서 출처를 권한에 통합한다. license_purchase는 과거 자료로만 남긴다. 기존 PAID 주문의 자동 소급 지급은 없다.
- 환불·회수는 후속 범위다. Download는 기존 내 라이브러리 상세 API로 보유권을 확인한다.

---

## download

본인의 상품 보유권과 현재 공개 빌드를 조회해 전체 ZIP의 만료 URL을 발급한다.
업무 DB·MongoDB·Kafka·보유권/공개본 사본은 사용하지 않는다. [범위·검증](download-mvp-plan.md) · [실행](download-guide.md)

| 메서드 | 경로 | 응답 |
|---|---|---|
| GET | `/api/v1/downloads/{productCode}/ticket` | productCode·version·fileSize·checksum·downloadUrl·expiresAt |

- MEMBER·유효 JWT의 sub로 본인을 식별하고 같은 사용자 토큰으로 License 상세를 조회한다.
- Catalog의 `/internal/v1/products/{productCode}/build`는 전용 서비스 키로만 조회한다. Gateway·공개 OpenAPI에는 노출하지 않는다.
- License·Catalog의 상품 ID·코드를 대조하고, 현재 COMPLETED 공개본의 전체 빌드만 사용한다.
- 저장소 HEAD로 객체·크기를 확인한 뒤 해당 파일의 GET URL을 발급한다. 기본 TTL 5분, 허용 범위 1초~15분이다.
- 응답은 no-store다. 쿼리·과거 버전·플랫폼·델타 선택·manifests API는 제공하지 않는다.
- 미보유/지급 전 403, 현재 공개 상품 없음 404, 파일 누락/크기 불일치 409, 연계 장애/응답 불일치 503이다.
- URL은 만료 전 반복 사용·공유가 가능하다. License 보유권과 URL 유효기간을 구분한다.
- 외부 이벤트 수신·발행은 없으며 발급·실패·재시작이 주문·보유권·공개본을 변경하지 않는다.

---

## settlement

매출 배분. 오픈마켓 구조상 가장 복잡한 영역이라 **집계 / 수수료 / 마감** 세 조각으로 나눴다.

**HTTP API**

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/settlements/orders/{orderNo}` | 주문 단위 원장(매출 + 환불 역산) |
| GET | `/api/v1/settlements/sellers/{sellerId}` | 판매자 월별 원장 |
| GET | `/api/v1/settlements/me/ledger?month=yyyy-MM` | CREATOR 자신의 원장 |
| GET | `/api/v1/settlements/me/closings?month=yyyy-MM` | CREATOR 자신의 월 마감 |
| GET | `/api/v1/settlements/closings` | 판매자 월 마감 결과 |
| POST | `/api/v1/settlements/close` | 수동 마감(배치 재실행용) |
| GET | `/api/v1/settlements/reconciliation?month=yyyy-MM` | ADMIN 월별 판매자 대사·차이 경고 |
| GET | `/api/v1/settlements/export.csv?month=yyyy-MM` | ADMIN 원장 CSV |

**재작성 전 할인 행사 API 기록(현재 Catalog에서 제거)** — 기존 catalog가 가격과 행사 기간을 소유했다. CREATOR는 본인 워크스페이스 상품만
`POST /api/v1/promotions/seller/products/{productId}`로 판매자 부담 행사를 만들고,
ADMIN은 `/platform/products/{productId}`로 플랫폼 부담 행사를 만든다. 본문은
`{"discountPerUnit":2000,"startsAt":"2026-10-01T00:00:00Z","endsAt":"2026-10-08T00:00:00Z"}`다.
`GET`으로 같은 경로의 이력을 조회하고, `POST /api/v1/promotions/{seller|platform}/{id}/stop`으로 중지한다.
한 상품의 기간은 겹칠 수 없고 시작은 포함, 끝은 제외한다. 할인가 1원 이상만 허용한다.
상점·상품의 표시 가격과 catalog 견적은 행사 기간을 현재 시각에 대조해 계산한다.
Store 색인은 행사 일정을 보관해 캐시된 진열도 경계 시각에 새 가격을 계산한다.

**금액 사본** — 주문은 통화를, 각 항목은 정가, 할인액, 최종 결제 단가, 행사 ID·부담 주체,
정산 기준액, 수수료율·수수료, 판매자 지급액을 보존한다. 정산 원장은 주문의 이 값을 사용한다.
결제 공급자는 주문 최종 금액을 쓰고, 정산은 결제 완료 이벤트의 같은 사본을 사용한다.
정가 10,000원과 할인 2,000원인 입점 상품에서 고객은 두 행사 모두 8,000원을 낸다.
판매자 부담이면 정산 기준 8,000원·수수료 2,400원·지급액 5,600원이다.
플랫폼 부담이면 정산 기준 10,000원·수수료 3,000원·지급액 7,000원이며
플랫폼 판촉비 2,000원을 별도로 기록한다(기본 입점 수수료율 30%).
환불은 원 매출의 결제·정가·할인·기준액·플랫폼 비용을 부호 반전한다. 현재 수수료·지급액은
음수 기준액과 요율로 다시 계산하므로 반복 항목의 반올림 차이는 [Q12](domain/open-questions.md#q12)의 검토 대상이다.
첫 월 마감 금액은 `base*` 필드에 남기고,
이후 닫힌 원장은 `adjustment*`에 별도로 합산한다. 닫힌 매출의 지각 환불 원장에는
`adjustmentForMonth`가 찍힌다. 대사는 고객 결제액, 판매자 할인, 플랫폼 판촉비,
정산 기준액, 수수료, 지급액과 마감액 차이를 반환한다.
`taxInvoiceStatus=SIMULATED`는 MockTaxInvoiceIssuer 결과이며 실제 발행이 아니다.

**이벤트** — 수신 `PaymentCompleted`·`PaymentCancelled` / 발행 없음

**규칙**

- **수수료 정책** — 자체 판매(`SELF`)는 0%, 입점 판매(`PARTNER`)는 설정된 요율(기본 30%).
  판매자 ID 로 구분하며, 정책이 늘어나면 `FeePolicy` 만 확장한다.
- **환불 역산** — 환불 이벤트에는 항목 정보가 없다. 자기 원장의 `SALE` 레코드를 근거로
  환불 원장을 만들므로 **다른 서비스에 되묻지 않는다.** 현재 수수료는 재계산하므로 동일 상품의 반복 항목에서 반올림 잔액이 남을 수 있다. [구현 차이 Q12](domain/open-questions.md#q12)를 참고한다.
- **금전 원장이므로 방어가 두 겹이다** — Inbox 가드 `(event_id, consumer_group)` 와
  도메인 유니크 `(order_no, product_id, record_type)`.
- **월 마감**은 재실행 안전하다. 이미 확정본이 있는 판매자는 건너뛰지 않고 **거기에 더한다**
  (`SellerSettlement#accumulate`). 건너뛰면 마감 후 도착한 지각 원장이 close 도장만 찍힌 채
  어느 확정본에도 안 들어간다 — [D-001](defects.md#d-001) 이 그 결함이다.
  순액이 0 이하인 판매자(환불이 매출 초과)는 세금계산서를 발행하지 않는다.
  다음 달 이월 원장과 실제 송금 정책은 [Q6](domain/open-questions.md#q6)의 미결 항목이다.
- **마감은 판매자마다 독립 트랜잭션 3단계**다(`SettlementCloseFacade`) —
  확정본 커밋 → 세금계산서 발행(**트랜잭션 밖**) → 발행번호 커밋.
  발행은 되돌릴 수 없는 외부 호출이라 트랜잭션 안에 두면 롤백돼도 계산서는 이미 나간다
  ([D-022](defects.md#d-022), 결제의 [D-006](defects.md#d-006) 과 같은 모양).
  한 판매자의 실패가 나머지를 롤백시키지 않는다.
- 그래서 마감 대상은 **두 부류의 합집합**이다(`sellersToClose`) —
  미마감 원장이 있는 판매자, 그리고 **마감은 끝났는데 계산서가 없는 판매자**.
  뒤엣것을 빼면 발행 실패가 영구 방치된다.
- 배치는 매월 1일 03시(Asia/Seoul). 다중 인스턴스 단일 실행은
  `@SchedulerLock("settlement-close-month")`(MySQL 락)이 보장한다.
  **락은 동시 실행 창만 닫는다** — 단일 인스턴스 부분 실패는 위의 3단계 분리가 막는다.

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

Outbox·DLT 운영 기능이 활성화된 서비스에서 직접 호출한다. Gateway에는 `/api/v1/ops/**` 라우트가 없으며, Settlement 등 해당 운영 API를 허용한 서비스에서도 `ADMIN` 토큰이 필요하다.

| HTTP API | 동작 |
|---|---|
| `GET /api/v1/ops/outbox/dead` | 발행을 포기한 Outbox 행과 사유 조회 |
| `POST /api/v1/ops/outbox/dead/{eventId}/requeue` | 지정한 DEAD 이벤트 회수 |
| `POST /api/v1/ops/outbox/dead/requeue-all` | DEAD 이벤트 일괄 회수 |
| `GET /api/v1/ops/dlt?topic={원본토픽}.DLT&max=20` | 재투입 그룹의 미처리 레코드 조회. 오프셋을 커밋하지 않음 |
| `POST /api/v1/ops/dlt/replay?topic={원본토픽}.DLT&max=100` | 원본 토픽에 재발행하고 재투입 그룹의 오프셋 커밋 |

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
| `PgGateway` (payment) | `SimulatedPgGateway` | 없음 | `stove.payment.pg-provider=simulated` |
| `TaxInvoiceIssuer` (settlement) | `MockTaxInvoiceIssuer` | 없음 | — |

### 실제로 동작하는 것

**`S3BuildStorage` / `S3ReviewBuildStorage`** — Studio가 전체 ZIP·썸네일을 임시 디스크에서 검사한 뒤 S3/MinIO에 저장한다.
Review는 제출 스냅샷의 객체 존재·크기를 확인하고 심사자에게 GET 서명 URL을 발급한다.
실제 MinIO 다운로드·SHA-256·만료와 읽기 전용 계정의 업로드·삭제 거절을 검증했다.
Download의 `S3BuildStorage`도 읽기 전용 계정으로 HEAD·presigned GET을 사용한다.
CDN 전략·자체 HMAC 토큰·파일 중계는 제공하지 않는다.

### 흉내만 내는 것

| 스텁 | 하는 일 | 빠진 것 |
|---|---|---|
| `SimulatedPgGateway` | 키별 승인·거절을 JDBC 원장에 독립 커밋, 같은 입력의 최초 결과 재조회 | 실제 PG·자금 이동, 별도 외부 DB·네트워크 장애 |
| `MockTaxInvoiceIssuer` | 발행번호 `TI-202607-001001` | 실제 세금계산서 전송·역발행 상태 |

Payment의 공급자 응답 유실과 결과 저장 실패는 테스트 전용 픽스처로 검증한다.
실제 PG 교체 시 같은 키의 재확인·멱등 결제와 확인된 결과 반환 계약이 필요하다. [Payment 범위](payment-mvp-plan.md)를 따른다.

### 스텁 공통의 한계

아래는 재작성 전 License·Settlement의 기존 스텁에 관한 기록이다. 현재 License는 지급 실패 보상 이벤트를 발행하지 않는다.
그래서 license 의 Saga 보상 경로(`LicenseIssueFailed` → 자동 환불)는
지급 실패를 인위적으로 주입해야만 테스트할 수 있다.

### 교체 안전장치

미완료 모듈의 기존 스텁은 `@Profile("!prod")` 조건을 사용한다. Payment의 SimulatedPgGateway는 `stove.payment.pg-provider=simulated`로 선택한다.
`prod` 로 띄우면 스텁이 빠지고, 실제 어댑터가 없으면 **"no qualifying bean" 으로 기동이 실패한다.**
운영에서 스텁이 조용히 도는 것만은 일어나지 않게 하려는 의도다.

Studio의 저장소 선택은 기존 설정을 사용한다. Review·Download는 읽기 전용 S3/MinIO 어댑터 한 개를 조립한다.
Download의 구 CDN/S3 이중 전략은 제거했다. 향후 같은 포트에 다른 구현을 추가하면 활성 구현을 명시적으로 선택해야 한다.

이 "반드시" 는 사람에게 거는 기대가 아니다 — ArchUnit 이 검사한다.
`Mock*`/`Stub*`/`Fake*` 로 시작하는 어댑터에 `@Profile` 이나 `@ConditionalOnProperty` 가
없으면 테스트가 깨진다(`스텁_어댑터는_격리한다`). 조건을 거는 것이 관례가 아니라 통과 조건이다.
