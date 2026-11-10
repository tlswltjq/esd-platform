# 설계: Platform (모듈을 넘는 계약)

> 작성 중 — [결정 150](../../../docs/decisions.md#spec-foundation)·[결정 151](../../../docs/decisions.md#platform-spec). 판단 기준은 [판단 원칙](../../../docs/architecture.md)(결정 147)이다.

읽는 법: 요구사항은 [requirements.md](requirements.md)(`P1.1-2` = P1.1의 2번 수용 기준), 코드와 다른 점은 [tasks.md](tasks.md)(`T`번호), 한 모듈 안에서 끝나는 차이는 모듈 스펙 작업(`MC`번호)이다. `[결정 필요 PD-n]`은 업무 정책이 정해지지 않은 자리, `RC-n`은 원칙에 어긋나 다시 물을 옛 결정(다시 묻기 전까지 유효)이다. 경로 줄임은 requirements와 같다(`<모듈>:` = `apps/<모듈>/src/main/java/com/stove/<모듈>/`). 공급자 이름은 "adapter 메모"에만 있다. 줄 번호는 2026-11-09 `docs/spec-foundation` 기준이다.

## 모듈 경계와 의존 방향

### 모듈과 외부 공급자
| 구분 | 이름 | 소유 저장소 | 외부와의 경계 |
|---|---|---|---|
| 업무 모듈 | Auth | `stove_auth` | 메일 발송(지금 `auth:adapter/mail/LoggingPasswordResetMailAdapter.java` — 로그만) |
| 업무 모듈 | Gateway | 없음(세션) | 외부 요청 진입점, Auth 토큰 엔드포인트(`gateway:config/bff/BffTokens.java`) |
| 업무 모듈 | Studio | `stove_studio`, 객체 저장소 빌드 업로드 | 객체 저장소 쓰기(`studio:adapter/storage/S3BuildStorage.java`) |
| 업무 모듈 | Review | `stove_review` | 객체 저장소 서명 URL 읽기(`review:adapter/storage/S3ReviewBuildStorage.java`) |
| 업무 모듈 | Catalog | `stove_catalog` | 객체 저장소 태그 쓰기(`catalog:adapter/storage/S3BuildDistribution.java`) |
| 업무 모듈 | Store | `stove_store` | 없음 |
| 업무 모듈 | Order | `stove_order` | 없음 |
| 업무 모듈 | Payment | `stove_payment` | PG 승인·조회·취소(`payment:adapter/pg/*`), PG 통지 수신(`payment:api/controller/PortOneWebhookController.java:28`) |
| 업무 모듈 | License | `stove_license` | 없음 |
| 업무 모듈 | Download | 없음(요청마다 조회) | 객체 저장소 서명 URL(`download:adapter/storage/S3BuildStorage.java`) |
| 업무 모듈 | Settlement | `stove_settlement` | PG 거래·취소·지급 명세 조회(`settlement:adapter/*PgPayment*`·`*Refund*`·`*Statement*`) |
| 외부 공급자 | PG | — | Payment·Settlement가 각자 `core.port`로 부른다 |
| 외부 공급자 | 기준 시뮬레이터 | — | 결정 141: PortOne 흉내 서버(`tools/verification/support/portone_fake.py`)가 기준. `simulators/mock-pg`는 독립 공급자(AGENTS.md 4절), Payment `SimulatedPgHttpGateway`·Settlement `SimulatedPg*HttpClient`가 부른다 |
| 외부 공급자 | 객체 저장소(S3/MinIO) | 버킷 하나(`STORAGE_BUCKET`) | Studio 쓰기, Review·Download 읽기, Catalog 태그. 버킷 정책이 태그로 읽기를 막는다(`infra/minio/policies/download-reader.json:19`) |
| 외부 공급자 | 메일 | — | Auth만 |

> adapter 메모(공급자 이름은 여기만): Payment PG 어댑터는 `PortOnePaymentApprovalGateway`·`PortOnePaymentAttemptLookup`·`PortOneRefundCancellationGateway`·`PortOneRefundPaymentLookup`·`SimulatedPgHttpGateway`, Settlement는 `PortOnePaymentHttpClient`·`PortOneRefundHttpClient`·`TossPayStatementHttpClient`·`SimulatedPgPaymentHttpClient`·`SimulatedPgSettlementHttpClient`, 미연결 대체 `UnavailablePgRefunds`·`UnavailablePgSettlements`·`UnavailablePgStatementSource`다. PG 통지 경로 이름 `/api/v1/payments/webhooks/portone`은 공급자 이름을 경로에 담는다(T28와 함께 결정 140 중립화 대상).

### 동기 의존 (HTTP)

| 호출 모듈 → 대상 | 경로 | 종류 | 호출 코드 | 받는 쪽 보호 | 목적 |
|---|---|---|---|---|---|
| Gateway → 각 모듈 | `/api/v1/**` 공개·관리자 경로(라우트 22개) | 라우팅 | `apps/gateway/src/main/resources/application.yml` 라우트 | 각 모듈 JWT·역할 | 외부 요청 전달. `/internal/**` 없음 |
| Gateway → Auth | `/oauth2/token`·`/oauth2/jwks`·`/userinfo` | 조회 | `gateway:config/bff/BffTokens.java` | OAuth2 | BFF 로그인·토큰 릴레이 |
| Order → Catalog | `GET /internal/v1/order-products/{code}` | 조회 | `order:adapter/CatalogHttpClient.java:23` | `X-Order-Service-Key` | 주문 생성 때 상품·가격 고정, 승인 접수 전 판매 제한 확인(결정 49) |
| Payment → Order | `POST /internal/v1/orders/{orderNo}/payment-admissions` | **명령** | `payment:adapter/order/HttpOrderPaymentAdmissions.java:28` | `X-Payment-Service-Key` | PG 확정 전 Order가 기한·상태를 판정하고 승인 진행 영수증을 연다(결정 32·49) |
| Payment → Order | `POST /internal/v1/orders/{orderNo}/payment-admissions/{admissionId}/failure` | **명령** | `payment:adapter/order/HttpOrderPaymentAdmissions.java:36` | `X-Payment-Service-Key` | 접수된 시도의 PG 실패를 Order 영수증에 닫고 주문을 `PENDING`/`EXPIRED`로 되돌린다 |
| Store → Catalog | `GET /internal/v1/store-products`, `/{code}` | 조회 | `store:adapter/CatalogHttpClient.java:35`·`:67` | `X-Store-Service-Key` | 시작 시 사본 구성·대조 |
| Settlement → Catalog | `GET /internal/v1/products/{code}/owner` | 조회 | `settlement:adapter/CatalogOwnerHttpClient.java:14` | `X-Settlement-Service-Key` | 매출 기록 때 판매자 확인 |
| Download → Catalog | `GET /internal/v1/products/{code}/build` | 조회 | `download:adapter/CatalogBuildHttpClient.java:26` | `X-Download-Service-Key` | 현재 공개 빌드·제한 확인 |
| Download → License | `GET /api/v1/library/{code}` (고객 토큰 전달) | 조회 | `download:adapter/LicenseHttpClient.java:19` | 고객 JWT | 보유권 `ACTIVE` 확인 |
| Payment → PG | 승인·조회·취소 | 명령·조회 | `payment:adapter/pg/*` | 공급자 키 | 결제·환불 실행 |
| Settlement → PG | 거래·취소·지급 명세 조회 | 조회 | `settlement:adapter/*` | 공급자 키 | 매출·환불 근거와 대사(Payment 경유 없음, Settlement R4.1 `.kiro/specs/settlement/requirements.md:66`) |
| PG → Payment | 통지 수신 | 들어옴 | `payment:api/controller/PortOneWebhookController.java:28` | 서명 검증 | 결제 상태 변경 통지 |

동기 그래프: Gateway → {Auth, Store, Catalog, Order, Payment, License, Download, Studio, Review, Settlement}; Payment → Order → Catalog; Store → Catalog; Settlement → Catalog; Download → {Catalog, License}. **순환 없음**(P1.2-1 충족). Catalog·License·Auth는 다른 업무 모듈을 동기 호출하지 않는다.

### 비동기 의존 ("요청 → 사실 응답" 쌍, P1.3-2)

이벤트별 소비 그룹·키·멱등 키는 아래 "이벤트 목록"이 정본이다. 이 표는 모듈 사이 방향만 보인다.

| 쌍 | 요청 사실 (발행 모듈 → 소비 모듈) | 응답 사실 (발행 모듈 → 소비 모듈) |
|---|---|---|
| S1 | Studio `ProjectReviewRequested`·`ProjectReviewWithdrawalRequested` → Review | Review `ProjectReviewApproved`·`ProjectReviewRejected`·철회 결과(목표 `ProjectReviewWithdrawn`·`ProjectReviewWithdrawalDenied`) → Studio |
| S2 | Studio `ProjectReleaseRequested`·`ProjectReleaseWithdrawalRequested` → Catalog | Catalog `ProjectReleaseCompleted`·`ProjectReleaseRejected`·철회 결과(목표 `ProjectReleaseWithdrawn`·`ProjectReleaseWithdrawalDenied`) → Studio |
| S3 | Order `OrderPlaced`·`OrderExpired`·(목표)`OrderPaymentResultRejected` → Payment | Payment `OrderPaymentCompleted`·`OrderPaymentFailed`·(목표)`OrderPaymentReviewClosed` → Order |
| S4 | Order `OrderPaid` → License | License `LicenseGranted` → Order |
| S5 | Order `LicenseSuspensionRequested` → License | License `LicenseSuspended` → Order |
| S6 | Order `RefundExecutionRequested` → Payment | Payment `RefundExecutionPrepared`·`RefundPaymentConfirmed`·`RefundExecutionSucceeded`·`RefundExecutionFailed`·(목표)`RefundFailureClosed` → Order, (목표)`RefundLateCancellationConfirmed` → Order·Settlement |
| S7 | Order `LicenseRestorationRequested` → License | License `LicenseRestored` → Order |
| S8 | Order `LicenseApprovalCorrectionRequested` → License | License `LicenseApprovalCorrected` → Order |
| 단방향 | Catalog 공개 사본 이벤트 → Store · Order `OrderPaid` → Settlement · Payment `RefundPaymentConfirmed` → Settlement · Order `PurchaseRefunded` → License · Auth `UserRegistered` → 소비자 없음 | — |

발행 근거: `order:core/service/{CreateOrder.java:59, ApplyPaymentResult.java:56, RecordOrderExpiry.java:15, ApplyRefundSuspension.java:79, ApplyRefundPaymentConfirmation.java:54, ApplyRefundRestoration.java:41, ApplyApprovalCorrection.java:50, DecideRefund.java:54, DecideRefundReconsideration.java:51, RequestRefundRetry.java:37}`, `payment:core/service/{PaymentResultOutbox.java:15·20, RecordRefundPreparation.java:16, RefundConfirmationOutbox.java:14, RecordRefundCancellationSuccess.java:40·46, ReconcileRefundPayment.java:64}`, `license:core/service/{GrantLicense.java:48, ApplyLicenseSuspension.java:61, ApplyLicenseRestoration.java:61, ApplyApprovalCorrection.java:57}`, `catalog:core/service/{PublishProjectRelease.java:117-161, ReceiveReleaseWithdrawal.java:79-82, RecordCatalogSaleChange.java:20, ChangeCatalogListing.java:64}`, `review:core/service/{ReceiveProjectReview.java:53, DecideProjectReview.java:41, ReceiveReviewWithdrawal.java:45}`, `studio:core/service/{review/RequestProjectReview.java:74, release/RequestProjectRelease.java:67, project/RequestProjectWithdrawal.java:67-70}`, `auth:core/service/RegisterAccount.java:48`.

비동기 순환은 Studio⇄Review(S1), Studio⇄Catalog(S2), Order⇄Payment(S3·S6), Order⇄License(S4·S5·S7·S8)이고 모두 위 쌍이다. 순환이 Order에 모이는 것은 Order가 주문·환불 진행 상태의 정본이기 때문이다(AGENTS.md 4절). 동기+비동기가 섞인 순환은 Order ⇄ Payment 하나다(Payment → Order 동기 명령 = 결정 32의 판정 요청, Order → Payment 이벤트 = 주문 사실). Order에 들어오는 "실패"는 두 길이지만 사실이 다르다: 동기 `/failure`는 **접수된 시도 하나의 PG 실패**(주문은 `PENDING`·`EXPIRED`로 돌아가 같은 주문 재결제 가능, P3.12-2), 이벤트 `OrderPaymentFailed`는 **그 결제의 최종 실패**(주문 `FAILED`, P3.14-3)다.

### 정본 소유
| 사실·값 | 정본 모듈(테이블.컬럼) | 사본·사용처 | 사본이 받는 근거 | 판정 |
|---|---|---|---|---|
| 계정·역할(`sub`) | Auth | 모든 모듈(JWT) | 토큰 | 하나 |
| 출시 상세·빌드·심사/출시 요청 | Studio | Review 스냅샷, Catalog 출시 스냅샷 | `Project*Requested` 스냅샷 | 하나(스냅샷은 불변 사본) |
| 심사 판정 | Review | Studio 관문 | `ProjectReview*` | 하나 |
| 공개본·가격·판매/노출 상태·소유자 | Catalog(`catalog_product`) | Store 사본, Order 주문 스냅샷, Settlement 매출의 판매자, Download 빌드 조회 | `CatalogProductPublished`·`catalogVersion`, 내부 조회 | 하나 |
| 다운로드 제한 | Catalog(`catalog_product.download_restricted`) | 객체 저장소 태그 `stove-download-blocked` | Catalog 적용 작업(`download_storage_status`) | 하나(태그는 생성 사본) |
| 주문 청구액·상품 스냅샷 | Order(`purchase_order.amount`·`list_amount`·`discount_amount`·`content_version`) | Payment `purchase_payment`, License `product_license`, Settlement `settlement_sale` | `OrderPlaced`·`OrderPaid` | 하나 |
| 30분 승인 진행 기한 | Order(`Order.getExpiresAt()` `order:core/domain/Order.java:77`, 영수증 `order_payment_admission`) | Payment `payment_approval.expires_at` 등 | 접수 응답 `expiresAt` | **중복 계산**: Payment가 `OrderPlaced.occurredAt + 30분`을 다시 계산(T12) |
| 최종 결제 결과(`PAID`/`FAILED`)·PG 결제 구성 | Payment(`purchase_payment.status`·`pg_paid_amount`·`pg_discount_amount`) | Order `purchase_order.status`·`pg_paid_amount`·`pg_discount_amount`(V27 "Read copies only") | `OrderPaymentCompleted`/`Failed` | 하나(PG 결과의 정본은 Payment, 주문 상태의 정본은 Order) |
| PG 비용 | **둘**: Payment `purchase_payment.pg_fee_*`(`payment/db/V12__pg_fee_snapshot.sql`), Settlement `settlement_pg_fee` | — | 각자 PG 응답 | **정본 중복** [결정 필요 PD-13] |
| PG 거래 식별(`provider`·`provider_transaction_id`) | PG(외부). Payment `purchase_payment`, Settlement `settlement_pg_transaction`이 각자 관측 | — | 각자 PG 조회 | 외부 정본의 두 관측. 금액 판정은 각자 자기 업무(Payment: 결제 확정, Settlement: 매출 근거)에만 쓰므로 허용. 두 관측이 다르면 대사 대상(P3) |
| 환불 접수·판정·진행 | Order(`order_refund`·`order_refund_attempt`) | Payment `refund_execution` 요청 | `RefundExecutionRequested` | 하나 |
| 환불 금융 확인(취소 합계·확인 시각) | Payment(`refund_execution.refund_confirmed_at`) | Order `order_refund`, Settlement `settlement_refund.confirmed_at` | `RefundPaymentConfirmed` | 하나 |
| 환불 취소 확인 지연 시각(PG 3시간 보장) | Payment(`RefundCancellationWindow` `payment:core/domain/RefundCancellationWindow.java:8`) | Order `RefundPreparationConfirmation` | `RefundExecutionPrepared.confirmationDelayAt` | **중복 계산**: Order가 `firstPreparedAt + 3시간`과 대조(T14). 공급자 보장 기한이 core에 있음(결정 140 대상) |
| 구매 근거·보유권 상태 | License(`product_license.status`·`license_registration.status`) | Order 지급·정지 확인, Download 조회 | `LicenseGranted`·`LicenseSuspended`·`LicenseRestored`, `/api/v1/library` | 하나 |
| 매출 배분·판매자 월 합계·이월 | Settlement | 없음 | — | 하나 |
| 판매자 지급 명세 대사 | Settlement | 없음 | PG 직접 | 하나 |

결제 기한 30분·공급자 보장 기한 3시간을 다시 계산하는 위치 목록은 tasks T12·T14와 MC-P2에 있다.

## 이벤트 목록

이벤트마다 업무 사실 한 문장 · 소유(발행) 모듈 · 소비 모듈과 소비 그룹 · 파티션 키 · 순서 요구 · 멱등 키 · 실패 시 처리 · 상태(P2.8-1)를 둔다. 정본 대조는 [tasks.md](tasks.md) T27의 `EventType` 대조 테스트가 지킨다.

### 토픽과 소유 모듈
| 토픽(`Topics`) | 발행 모듈(Outbox 주인) | 파티션 키 | 현행 이벤트 수 | 비고 |
|---|---|---|---|---|
| `stove.auth.v1` (`AUTH`) | Auth | `subject` | 1 | 소비자 없음 |
| `stove.studio.v1` (`STUDIO`) | Studio | `productCode` | 4 (모두 문자열 리터럴, design 이벤트 목록(리터럴)) | `EventType`의 studio 상수 8개는 발행 없음 |
| `stove.review.v1` (`REVIEW`) | Review | `productCode` | 3 (design 이벤트 목록(리터럴)) | `EventType`의 review 상수 5개는 발행 없음 |
| `stove.release.v1` (`RELEASE`) | **Catalog** | `productCode` | 3 (design 이벤트 목록(리터럴)) | `Topics.java:120` 주석은 "외부 출시 업무"라 소유자가 드러나지 않음(T32) |
| `stove.catalog.v1` (`CATALOG`) | Catalog | `productCode` | 1 | |
| `stove.order.v1` (`ORDER`) | Order | `orderNo` | 7 | 정지·복구·승인 정정 **요청**도 Order 사실이라 여기 있다 |
| `stove.payment.v1` (`PAYMENT`) | Payment | `orderNo` | 6 | |
| `stove.license.v1` (`LICENSE`) | License | `orderNo` | 4 | |
| `<토픽>.DLT` | 소비 모듈의 에러 핸들러 | 원본 파티션 유지 | — | `DeadLetterTopics.SUFFIX = ".DLT"` |

`EventType.java`의 `// order`·`// payment`·`// license` 묶음 주석은 소유 모듈과 다르다(`REFUND_EXECUTION_PREPARED`·`ORDER_PAYMENT_COMPLETED`·`ORDER_PAYMENT_FAILED`는 Payment 발행인데 `// order` 아래, `REFUND_EXECUTION_FAILED`는 Payment 발행이고 `LICENSE_RESTORATION_REQUESTED`·`LICENSE_APPROVAL_CORRECTION_REQUESTED`는 Order 발행인데 `// license` 아래). 아래 표의 "소유" 열이 정본이다(T32).

### 이벤트 하나의 수명 (공통 흐름)

```
발행 모듈 트랜잭션
  업무 행 변경 + outbox_event(PENDING) INSERT            -- 같은 커밋 (OutboxRecorder, MANDATORY)
OutboxRelay (서비스당 1대, stove.outbox.poll-interval-ms=200)
  lockPendingBatch (FOR UPDATE SKIP LOCKED, id 순, batch 200)
  -> 키별 체인으로 묶어 웨이브 발행
     ack 성공 : SENT
     실패     : retryCount++, next_attempt_at = 1s,2s,...,5m, 같은 키 뒤 이벤트 holdUntil
     10회 소진: DEAD (stove.outbox 지표, ADMIN requeue)    -- 목표(결정 155): DEAD 키의 뒤 행은 회수 전까지 보류, ADMIN 키 단위 requeue
Kafka   topic=Topics.*, key=partitionKey, headers eventId·eventType·occurredAt·schemaVersion[PD-18]·traceparent
소비 리스너 (그룹당 파티션당 스레드 1, ack-mode record)
  EventEnvelope.from (헤더 없으면 거절)
  -> isType 분기 -> *Mapper.from(envelope, payload) (계약 검증, api.listener)
  -> core 서비스 트랜잭션: ProcessedEventGuard.firstDelivery + 업무 유일 키 + 상태 전이 (+ 다음 이벤트 Outbox)
  -> 커밋 뒤 오프셋 커밋
  실패: 재시도 가능 -> 1·2·4초 재시도 -> <topic>.DLT 같은 파티션 (DeadLetterMetrics)
        영구 실패   -> 재시도 없이 DLT (목표, P2.6-3)
DLT -> ADMIN DltOpsService.replay (원본 헤더 보존) -> 원본 토픽 -> Inbox가 중복 거름   -- 목표(결정 155): DLT로 보낸 (그룹, 토픽, 키)를 정지 목록에 올리고 뒤 레코드도 같은 DLT로. 재투입은 키 단위 offset 순, 다 처리되면 정지 해제
```

### `EventType` 상수 (42개) — 현행·목표 분리안

열 약어

- **실패 처리**: `기본` = 공통 `DefaultErrorHandler`(4회 시도, 1·2·4초) → `<토픽>.DLT` 같은 파티션(`common/kafka/.../KafkaConsumerAutoConfiguration.java:32-44`). `환불 팩토리` = Order `orderRefundListenerContainerFactory`(`RefundCompletionRejected`는 재시도 없이 DLT, 나머지는 `기본`과 같음, `order:config/RefundKafkaConfiguration.java:8-22`). `결제 환불 팩토리` = Payment `paymentRefundExecutionListenerContainerFactory`(`max.poll.records=1`, 에러 처리는 `기본`, `payment:config/RefundKafkaConfiguration.java:6-17`). 발행 쪽은 모두 Outbox(최대 10회 → `DEAD`). `재발행` = 결정 129 운영 재발행 허용(`common/messaging/.../ops/OutboxRepublishTargets.java:27-37`).
- **순서 요구**: 같은 키 안에서 이 이벤트가 어느 이벤트와 순서가 맞아야 하는지. "인과"는 앞 이벤트를 소비한 결과로만 생겨 발행 쪽 순서 보장이 필요 없다는 뜻.
- **멱등 키**: 모든 소비자의 1층은 Inbox `uk_processed_event(event_id, consumer_group)`(Store 제외). 표에는 2층 = 소비 모듈의 업무 유일 제약(각 모듈 `src/main/resources/db/migration`)을 적는다.
- 소비 확인 방법: `apps/*/src/main/java/**/api/listener/*.java`의 `@KafkaListener`·`isType(...)` grep, 소비 그룹은 각 서비스의 `CONSUMER_GROUP` 상수. 발행 확인: `apps/*/src/main/java`에서 payload 생성(`new XxxEvent(`·`XxxEvent.of`) grep.

| # | 이벤트 | 업무 사실 한 문장 | 소유(발행) 모듈 · 발행 지점 | 소비 모듈 · 소비 그룹 | 토픽 | 파티션 키 | 순서 요구 | 멱등 키(2층) | 실패 처리 | 상태 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `UserRegistered` | 계정이 가입을 마쳤다 | Auth · `RegisterAccount:48` | 없음 | `stove.auth.v1` | `subject` | 없음 | (소비자 없음) | 발행 Outbox만 | 현행(소비자 없음, 발행 중단은 정하지 않음) |
| 15 | `CatalogProductPublished` | Catalog의 공개 상품 사본이 바뀌었다(지금은 최초 공개·공개본 교체·판매 상태 변경·노출 설정 변경 네 사실을 겸함) | Catalog · `PublishProjectRelease:122-130`, `RecordCatalogSaleChange:20`, `ChangeCatalogListing:64`(모두 `catalog:core/service/`) | Store · `store-catalog-publication`(`CatalogEventListener` → `ApplyCatalogProduct`) | `stove.catalog.v1` | `productCode` | 같은 상품의 사본은 `catalogVersion` 순. 발행 순서가 아니라 버전으로 판정(P2.3-5) | Inbox 없음(상태 사본 예외). `StoreProduct.apply`의 `catalogVersion`·`contentVersion` 비교, `uk_store_product_code` | `기본` | 현행 → 목표 분리안(분리안 SP-4, RC-6) |
| 17 | `OrderPaid` | Order가 최초 결제 완료 결과를 자기 주문과 대조해 `PAID`로 반영했다 | Order · `ApplyPaymentResult`(첫 `PAID` 반영 때만) | License · `license-paid-order`(`GrantLicense`); Settlement · `settlement-paid-order`(`RecordPaidSale`) | `stove.order.v1` | `orderNo` | 같은 주문의 `LicenseSuspensionRequested`·`PurchaseRefunded`보다 앞. 하류 반영은 인과 | License `uk_product_license_order`·`uk_product_license_payment`·`uk_product_license_result`; Settlement `uk_sale_order_no`·`uk_sale_payment_result_event_id` | `기본`, `재발행` | 현행 |
| 18 | `LicenseSuspensionRequested` | Order가 환불(또는 재시도)을 위해 그 구매분의 보유권 정지 요청을 등록했다(정지 완료가 아님) | Order · `RequestRefund:53`, `DecideRefund:54`, `DecideRefundReconsideration:51`, `RequestRefundRetry:37`, `ApplyLicenseGrant:58`(지급 확인을 기다리던 요청) | License · `license-suspension`(`ApplyLicenseSuspension`) | `stove.order.v1` | `orderNo` | 같은 주문의 `OrderPaid` 뒤. 같은 환불의 `LicenseRestorationRequested`보다 앞 | `eventId` = `suspensionRequestEventId`; License `uk_license_suspension_request(request_event_id)` | `기본`, `재발행` | 현행 |
| 19 | `RefundExecutionRequested` | Order가 보유권 정지를 확인하고 전액 환불 실행 한 건(`attemptId`)을 등록했다 | Order · `ApplyRefundSuspension` | Payment · `payment-refund-execution`(`RefundExecutionEventListener` → `ReceiveRefundExecution`) | `stove.order.v1` | `orderNo` | 같은 환불의 `LicenseSuspensionRequested` 뒤(인과: `LicenseSuspended`를 받아야 생김). 재시도는 `previousAttemptId`로 이음 | Payment `uk_refund_execution_request(request_event_id)`·`uk_refund_execution_attempt`·`uk_refund_execution_suspension`·`uk_refund_execution_previous` | `결제 환불 팩토리` | 현행 |
| 20 | `RefundExecutionPrepared` | Payment가 환불 실행을 내구성 있게 준비했다(PG 호출을 보냈다는 뜻이 아님) | **Payment** · `RecordRefundPreparation` | Order · `order-refund-preparation`(`ApplyRefundPreparation`) | `stove.payment.v1` | `orderNo` | 같은 시도의 `RefundPaymentConfirmed`·`RefundExecutionFailed`보다 앞 | Order `uk_order_attempt_preparation(preparation_event_id)`; Payment `uk_refund_preparation_event` | `환불 팩토리` | 현행 |
| 21 | `PurchaseRefunded` | Order가 주문 한 건의 전액 환불을 완료로 확정했다 | Order · `ApplyRefundPaymentConfirmation`(`Refund.confirmPayment`가 바뀐 때만) | License · `license-purchase-refund`(`ApplyPurchaseRefund`) | `stove.order.v1` | `orderNo` | 같은 주문의 `LicenseSuspensionRequested` 뒤 | `suspensionRequestEventId` 대조, `License.refund`(`SUSPENDED`만). 전용 유일 제약 (미확인) | `기본` | 현행 |
| 22 | `OrderExpired` | Order가 결제 기한이 지난 주문의 승인 접수를 안전하게 닫았다(PG 거절이 아님) | Order · `RecordOrderExpiry` | Payment · `payment-order-expiry`(`OrderExpiryListener` → `ApplyOrderExpiry`) | `stove.order.v1` | `orderNo` | 같은 주문의 `OrderPlaced` 뒤 | Payment `uk_payment_order_expiry_event(expiry_event_id)` | `기본` | 현행(칸 `expiresAt` 유지) |
| 23 | `OrderPlaced` | 단일 상품 주문의 정가·할인액·청구액이 고정됐다 | Order · `CreateOrder` | Payment · `payment-order-request`(`OrderEventListener` → `ReceiveOrderPayment`) | `stove.order.v1` | `orderNo` | 같은 주문의 첫 이벤트 | Payment `uk_purchase_payment_order(order_no)`·`uk_purchase_payment_request(request_event_id)` | `기본` | 현행 → 목표 칸 `expiresAt` 추가(T12) |
| 24 | `OrderPaymentCompleted` | Payment가 한 `OrderPlaced` 요청(`requestEventId`)의 결제를 최종 승인했다 | **Payment** · `PaymentResultOutbox` | Order · `order-payment-result`(`PaymentEventListener` → `ApplyPaymentResult`) | `stove.payment.v1` | `orderNo` | 주문 요청당 결과 하나. `OrderPaymentFailed`와 함께 존재하지 않음 | Order `uk_purchase_order_result`; Payment `uk_purchase_payment_result(result_event_id)` | `기본` | 현행 → 목표 칸 `admissionId` 추가(U1, T1) |
| 25 | `OrderPaymentFailed` | Payment가 한 `OrderPlaced` 요청의 결제를 최종 실패로 정했다(지금은 운영자 보류 종결 `reasonCode=REVIEW_CLOSED`도 겸함) | **Payment** · `PaymentResultOutbox`, `ApprovalClosure.REASON_CODE`(`payment:core/domain/ApprovalClosure.java:27`) | Order · `order-payment-result` | `stove.payment.v1` | `orderNo` | #24와 같음 | #24와 같음 | `기본` | 현행 → 목표 분리안(분리안 SP-1, RC-1) |
| 28 | `RefundPaymentConfirmed` | Payment가 원결제 전액 취소(돈의 결과)를 처음 확인했다(지금은 `source`로 "이 시도에 귀속됨/귀속 미확정"도 구별) | Payment · `RecordRefundCancellationSuccess:40`(`source=PG_RESPONSE`), `RefundConfirmationOutbox`(`PG_QUERY_UNATTRIBUTED`) | Order · `order-refund-payment-confirmation`(`ApplyRefundPaymentConfirmation`); Settlement · `settlement-confirmed-refund`(`RecordConfirmedRefund`) | `stove.payment.v1` | `orderNo` | 같은 시도의 `RefundExecutionPrepared` 뒤, `RefundExecutionSucceeded`보다 앞 | Order `uk_order_refund_payment_confirmation`; Settlement `uk_refund_event`·`uk_refund_id`·`uk_refund_order`; Payment `uk_refund_execution_confirmation` | Order `환불 팩토리`, Settlement `기본`(SIMULATED면 DLT, U4) | 현행 → 목표 분리안(분리안 SP-5, RC-8) |
| 29 | `RefundExecutionSucceeded` | Payment가 이미 확인된 전액 취소를 이 환불 시도의 PG 취소 응답에 귀속했다(늦은 직접 응답 보강) | Payment · `RecordRefundCancellationSuccess:46` | Order · `order-refund-payment-confirmation`(같은 리스너). Settlement는 소비하지 않음(U5) | `stove.payment.v1` | `orderNo` | 같은 시도의 `RefundPaymentConfirmed` 뒤(`confirmationEventId`로 이음) | Order `uk_order_refund_attempt_success(success_event_id)`; Payment `uk_refund_execution_success` | `환불 팩토리` | 현행 |
| 33 | `LicenseGranted` | License가 `OrderPaid` 한 건으로 구매 근거(`product_license`, 보관함 `STORED`)를 만들었다(라이브러리 등록이 아님, 결정 93) | License · `GrantLicense` | Order · `order-license-grant`(`LicenseEventListener` → `ApplyLicenseGrant`) | `stove.license.v1` | `orderNo` | 같은 주문의 `LicenseSuspended`보다 앞(인과) | Order `uk_order_license_grant_event`·`uk_order_license_grant_order`·`uk_order_license_grant_license` | `기본`, `재발행` | 현행(이름이 사실과 다름, RC-3) |
| 34 | `LicenseSuspended` | License가 정지 요청 한 건(`requestEventId`)으로 그 구매분을 정지했다 | License · `ApplyLicenseSuspension` | Order · `order-refund-suspension`(`RefundSuspensionEventListener` → `ApplyRefundSuspension`, 승인 정지 중이면 `ApplyApprovalCorrection.handleStoppedSuspension`) | `stove.license.v1` | `orderNo` | 같은 요청의 `LicenseSuspensionRequested` 뒤(인과) | Order `uk_order_refund_attempt_suspension_result(suspension_event_id)`; License `uk_license_suspension_result(completed_event_id)` | `환불 팩토리`, `재발행` | 현행 |
| 38 | `RefundExecutionFailed` | PG가 이 시도의 취소를 FAILED로 확정했고 이어진 조회에서 원결제가 `PAID`·전액 미취소로 확인됐다(목표: 이 뜻 하나만) | **Payment** · `ReconcileRefundPayment` | **Order만** · `order-refund-restoration`(`RefundRestorationEventListener` → `ApplyRefundRestoration`). Settlement·License는 소비하지 않음 | `stove.payment.v1` | `orderNo` | 같은 시도의 `RefundExecutionPrepared` 뒤. 같은 환불의 `LicenseRestorationRequested`보다 앞(인과) | Payment `uk_refund_execution_failure(failure_event_id)`; Order 시도별 실패 기록(유일 제약 이름 미확인) | `환불 팩토리` | 현행(`RefundFailureClosed`와 짝) |
| 39 | `LicenseRestorationRequested` | Order가 실패한 환불 시도(`failureEventId`)를 근거로 정지한 구매분의 복구 요청을 등록했다 | **Order** · `ApplyRefundRestoration` | License · `license-restoration`(`ApplyLicenseRestoration`) | `stove.order.v1` | `orderNo` | 같은 환불의 `LicenseSuspensionRequested` 뒤 | License `uk_license_restoration_request(request_event_id)`·`uk_license_restoration_suspension(suspension_request_event_id)` | `기본`, `재발행` | 현행 |
| 40 | `LicenseApprovalCorrectionRequested` | Order가 승인을 멈춘 환불의 정지 구매분을 되돌리는 정정 요청을 등록했다 | **Order** · `ApplyApprovalCorrection:50` | License · `license-approval-correction`(`ApplyApprovalCorrection`) | `stove.order.v1` | `orderNo` | 같은 환불의 `LicenseSuspended` 뒤(인과) | License `uk_license_correction_stop(stop_request_id)`·`uk_license_correction_suspension` | `기본`, `재발행` | 현행 |
| 41 | `LicenseApprovalCorrected` | License가 승인 정정 요청 한 건으로 정지 구매분을 되돌렸다(`returnedToStorage`면 보관함으로) | License · `ApplyApprovalCorrection` | Order · `order-approval-correction`(`ApprovalCorrectionEventListener` → `ApplyApprovalCorrection`) | `stove.license.v1` | `orderNo` | 같은 요청의 `LicenseApprovalCorrectionRequested` 뒤(인과) | License `uk_license_correction_completed(completed_event_id)`; Order (유일 제약 이름 미확인) | `환불 팩토리`, `재발행` | 현행 |
| 42 | `LicenseRestored` | License가 복구 요청 한 건으로 정지 구매분을 되돌렸다(`returnedToStorage`면 보관함으로) | License · `ApplyLicenseRestoration` | Order · `order-refund-restoration`(`RefundRestorationEventListener` → `ApplyRefundRestoration`) | `stove.license.v1` | `orderNo` | 같은 요청의 `LicenseRestorationRequested` 뒤(인과). 같은 그룹이 받는 `RefundExecutionFailed`(다른 토픽)와의 순서는 보장 없음(P2.5-2) | License `uk_license_restoration_result(completed_event_id)` | `환불 팩토리`, `재발행` | 현행 |

| 상태 | 수 | 번호 |
|---|---|---|
| 현행 | 20 | #1, #15, #17~#25, #28, #29, #33, #34, #38~#42 |
| 구 계약 | 13 | #2~#14 |
| 제거 대상 | 9 | #16, #26, #27, #30, #31, #32, #35, #36, #37 |
| 합계 | 42 | |

현행 20개 가운데 목표 분리안이 붙은 것: #15 `CatalogProductPublished`, #25 `OrderPaymentFailed`, #28 `RefundPaymentConfirmed`. 이름 정정 소급 후보: #33 `LicenseGranted`(RC-3). 짝 이벤트 신설: #38 `RefundExecutionFailed` ↔ design "목표 행" `RefundFailureClosed`.

구 계약·제거 대상 22개(발행·소비 없음, P2.8). 열: 번호 · 이벤트 · (구) 사실 · 토픽 · 상태

| # | 이벤트 | (구) 사실 | 토픽 | 상태 |
|---|---|---|---|---|
| 2 | `GameRegistered` | (구) 프로젝트 단위로 게임 심사를 요청했다 | `stove.studio.v1` | 구 계약(Q2, PD-20) |
| 3 | `BuildUploaded` | (구) 빌드 파일 업로드가 끝났다 | `stove.studio.v1` | 구 계약(Q2, PD-20) |
| 4 | `BuildValidated` | (구) 업로드 빌드가 검증을 통과했다 | `stove.studio.v1` | 구 계약(Q10, PD-20) |
| 5 | `BuildValidationFailed` | (구) 업로드 빌드가 검증에 실패했다 | `stove.studio.v1` | 구 계약(Q10, PD-20) |
| 6 | `SubmissionCreated` | (구) 자료·빌드 묶음의 심사를 요청했다 | `stove.studio.v1` | 구 계약(Q2, PD-20). 현행 대체: `ProjectReviewRequested`(design 이벤트 목록(리터럴)) |
| 7 | `ReleaseScheduled` | (구) 릴리스 공개 시각을 예약했다 | `stove.studio.v1` | 구 계약(Q2, PD-20) |
| 8 | `ReleasePublished` | (구) 재작성 전 LIVE 공개 | `stove.studio.v1` | 구 계약(Q2, PD-20). 현행 대체: `ProjectReleaseRequested` → `ProjectReleaseCompleted` |
| 9 | `ReleaseRolledBack` | (구) 이전 릴리스로 되돌렸다 | `stove.studio.v1` | 구 계약(Q10: 실패 뒤에도 성공처럼 적재되던 뜻 미정, PD-20) |
| 10 | `ReviewApproved` | (구) 프로젝트 단위 심사를 승인했다 | `stove.review.v1` | 구 계약(Q2, PD-20) |
| 11 | `ReviewRejected` | (구) 프로젝트 단위 심사를 반려했다 | `stove.review.v1` | 구 계약(Q2, PD-20) |
| 12 | `SubmissionReviewApproved` | (구) 제출물의 한 심사 유형을 승인했다 | `stove.review.v1` | 구 계약(Q2, PD-20) |
| 13 | `ReviewChangesRequested` | (구) 심사자가 자료 보완을 요청했다 | `stove.review.v1` | 구 계약(Q2, PD-20) |
| 14 | `ReviewAppealed` | (구) 같은 심사 사건의 재검토를 시작했다 | `stove.review.v1` | 구 계약(Q2, PD-20) |
| 16 | `ProductChanged` | (구) 재작성 전 상품 마스터가 바뀌었다 | `stove.catalog.v1` | 제거 대상 |
| 26 | `OrderCreated` | (구) 재작성 전 주문 항목·금액 고정 | `stove.order.v1` | 제거 대상 |
| 27 | `OrderCanceled` | (구) 재작성 전 주문 취소 | `stove.order.v1` | 제거 대상 |
| 30 | `PaymentCompleted` | (구) 재작성 전 정상 승인 기록 | `stove.payment.v1` | 제거 대상 |
| 31 | `PaymentCancelled` | (구) 재작성 전 PG 환불 완료 기록 | `stove.payment.v1` | 제거 대상 |
| 32 | `PaymentFailed` | (구) 재작성 전 PG 승인 거절 기록 | `stove.payment.v1` | 제거 대상 |
| 35 | `LicenseIssued` | (구) Download 사본용 이용권 지급 | `stove.license.v1` | 제거 대상 |
| 36 | `LicenseRevoked` | (구) Download 사본용 이용권 회수 | `stove.license.v1` | 제거 대상 |
| 37 | `LicenseIssueFailed` | (구) 이용권 지급 최종 실패(옛 Saga 보상 입력) | `stove.license.v1` | 제거 대상 |

### `EventType`에 없는 현행 이벤트 (문자열 리터럴 10개, T26)

지금 실제로 흐르는 출시·심사 이벤트는 `EventType` 상수가 아니라 payload의 `eventType()`과 리스너의 `isType("...")` 문자열로만 있다(T26). 목표: 모두 `EventType` 상수로 올리고 design 이벤트 목록과 같은 방식으로 관리한다.

| 이벤트 | 업무 사실 한 문장 | 소유(발행) 모듈 · 발행 지점 | 소비 모듈 · 소비 그룹 | 토픽 | 파티션 키 | 순서 요구 | 멱등 키(2층) | 실패 처리 | 상태 |
|---|---|---|---|---|---|---|---|---|---|
| `ProjectReviewRequested` | Studio가 프로젝트 스냅샷(`ProjectSnapshot`)의 심사를 요청했다 | Studio · `RequestProjectReview` | Review · `review-project`(`StudioEventListener` → `ReceiveProjectReview`) | `stove.studio.v1` | `productCode` | 같은 상품의 `ProjectReviewWithdrawalRequested`보다 앞 | Review `uk_review_active_project`·`uk_review_case_submission_type` | `기본` | 현행(상수 없음) |
| `ProjectReviewWithdrawalRequested` | 창작자가 진행 중인 심사 요청의 철회를 요청했다 | Studio · `RequestProjectWithdrawal` | Review · `review-project`(`ReceiveReviewWithdrawal`) | `stove.studio.v1` | `productCode` | 같은 상품의 `ProjectReviewRequested` 뒤 | Review 상태 대조 (전용 유일 제약 미확인) | `기본` | 현행(상수 없음) |
| `ProjectReleaseRequested` | Studio가 심사 승인된 스냅샷의 출시를 요청했다 | Studio · `RequestProjectRelease` | Catalog · `catalog-project-release`(`StudioReleaseEventListener` → `ReceiveProjectRelease`·`PublishProjectRelease`) | `stove.studio.v1` | `productCode` | 같은 상품의 `ProjectReleaseWithdrawalRequested`보다 앞 | Catalog `uk_catalog_request_event(request_event_id)`·`uk_catalog_publication_event(source_event_id)` | `기본` | 현행(상수 없음) |
| `ProjectReleaseWithdrawalRequested` | 창작자가 진행 중인 출시 요청의 철회를 요청했다 | Studio · `RequestProjectWithdrawal` | Catalog · `catalog-project-release`(`ReceiveReleaseWithdrawal`) | `stove.studio.v1` | `productCode` | 같은 상품의 `ProjectReleaseRequested` 뒤 | Catalog 상태 대조 (전용 유일 제약 미확인) | `기본` | 현행(상수 없음) |
| `ProjectReviewApproved` | Review가 프로젝트 스냅샷 심사를 승인했다 | Review · `DecideProjectReview:28` | Studio · `studio-project`(`ProjectResultListener` → `RecordProjectReviewResult`) | `stove.review.v1` | `productCode` | 같은 요청의 `ProjectReviewRequested` 뒤(인과) | Studio `uk_studio_active_review` | `기본` | 현행(상수 없음) |
| `ProjectReviewRejected` | Review가 프로젝트 스냅샷 심사를 반려했다 | Review · `DecideProjectReview:34` | Studio · `studio-project` | `stove.review.v1` | `productCode` | 같음 | 같음 | `기본` | 현행(상수 없음) |
| `ProjectReviewWithdrawalResolved` | Review가 심사 철회 요청을 처리했다 | Review · `ReceiveProjectReview`, `ReceiveReviewWithdrawal` | Studio · `studio-project`(`RecordProjectWithdrawalResult`) | `stove.review.v1` | `productCode` | 같은 요청의 철회 요청 뒤(인과) | Studio 상태 대조 | `기본` | 현행(상수 없음) → 목표 분리안 SP-7(`ProjectWithdrawalResult.status`가 두 사실을 겸함을 코드로 확인) |
| `ProjectReleaseCompleted` | Catalog가 출시 요청의 스냅샷을 공개했다(최초 공개 또는 공개본 교체) | **Catalog** · `PublishProjectRelease` | Studio · `studio-project`(`RecordProjectReleaseResult`) | `stove.release.v1` | `productCode` | 같은 요청의 `ProjectReleaseRequested` 뒤(인과) | Studio `uk_studio_active_release` | `기본` | 현행(상수 없음) |
| `ProjectReleaseRejected` | Catalog가 출시 요청을 거절했다 | **Catalog** · `PublishProjectRelease` | Studio · `studio-project` | `stove.release.v1` | `productCode` | 같음 | 같음 | `기본` | 현행(상수 없음) |
| `ProjectReleaseWithdrawalResolved` | Catalog가 출시 철회 요청을 처리했다 | **Catalog** · `PublishProjectRelease:117`, `ReceiveReleaseWithdrawal` | Studio · `studio-project`(`RecordProjectWithdrawalResult`) | `stove.release.v1` | `productCode` | 같은 요청의 철회 요청 뒤(인과) | Studio 상태 대조 | `기본` | 현행(상수 없음) → 목표 분리안 SP-8 |

`studio-project` 그룹 하나가 `stove.review.v1`·`stove.release.v1` 두 토픽을 받으므로 두 토픽 사이 순서는 보장되지 않는다(P2.5-2). 승인 → 출시 결과는 인과로 순서가 생기지만, 철회 결과와 승인·출시 결과는 두 토픽에서 엇갈릴 수 있다 — 흐름 1 실패 표 S3·S5·S7 "순서 역전" 행이 다룬다.

### 한 이벤트에 두 뜻 — 원칙 ① 분리 목표안 (SP-1 ~ SP-8)

| # | 지금 | 겸한 뜻 | 받는 쪽 반응 차이 | 목표 이벤트(가칭) | 받는 쪽 목표 | 근거 |
|---|---|---|---|---|---|---|
| SP-1 | `OrderPaymentFailed(reasonCode=REVIEW_CLOSED)` | ① PG 거절·시도 소진으로 결제 실패가 확정됨 ② ADMIN이 PG 재조회 근거로 금융 보류 승인을 종결함 | Order는 둘 다 `FAILED`로 받지만, ②는 주문이 이미 `EXPIRED`면 `ExpiredReviewClosure`로 따로 식별해야 하고(`order:core/domain/ExpiredReviewClosure.java:17`, `PaymentResult.java:34`가 문자열 `REVIEW_CLOSED`로 판정), 고객 안내(새 주문)와 운영 기록(ADMIN·근거)이 다르다 | ① `OrderPaymentFailed`(PG·시도 실패만, `reasonCode`는 실패의 하위 분류만) ② `OrderPaymentReviewClosed`(design "목표 행") | Order `PaymentEventListener`가 두 타입을 받아 `PaymentResultMapper`에서 같은 `FAILED` 입력으로 합치되, 결과 종류 enum으로 구별해 `ExpiredReviewClosure`가 문자열 `reasonCode` 대신 그 enum을 본다 | 결정 114·115, RC-1 |
| SP-2 | `RefundExecutionFailed`(결정 128 B안은 ADMIN 실패 종결도 이 이벤트로 보내도록 정함) | ① PG가 이 시도의 취소를 FAILED로 확정 ② ADMIN이 PG 재조회 근거(취소액 0·진행 중 취소 없음)로 자동 송신이 멈춘 시도를 실패로 종결 | Order 반응은 같음(보유권 복구 요청 + 고객 재시도). 그러나 ②는 PG 취소 ID가 없을 수 있어 `cancellationId` 칸에 다른 뜻을 넣게 되고(결정 147 문제의 예), 근거가 ADMIN 종결이다 | ① `RefundExecutionFailed` 유지(PG 확정 실패만) ② `RefundFailureClosed`(design "목표 행") | Order `RefundRestorationEventListener`가 두 타입을 받아 같은 실패 확정 입력으로 합치되 실패 근거 enum(예: `PG_CONFIRMED`·`ADMIN_CLOSURE`)을 남긴다. Settlement·License는 둘 다 소비하지 않는다 | 결정 128·147·148, RC-7 |
| SP-3 | `LicenseGranted` | 이름은 "보유권 지급"이지만 사실은 "구매 근거(`product_license`)가 보관함 `STORED`로 생김". 라이브러리 등록(`license_registration` `ACTIVE`)은 다른 사실이며 이벤트가 없다 | Order는 "지급 확인"으로 읽어 환불 대기(`PENDING_LICENSE_GRANT`)를 푼다. 필요한 사실은 "구매 근거가 있다"이므로 반응은 맞고 이름만 틀림 | 한 뜻이므로 나누지 않고 이름 정정(가칭 `PurchaseRecordStored`, RC-3). 결정 92가 이름 유지를 정했으므로 RC-3로만 올리고 본문 표는 현 이름 유지 | 바꾸면 P2.7-4 순서(소비자 두 타입 → 발행 전환 → 옛 타입 제거) | 결정 92·93, RC-3 |
| SP-4 | `CatalogProductPublished` | ① 최초 공개(`CatalogProduct.publish`) ② 공개본 교체(`product.replace`, `contentVersion` 증가) ③ 판매 상태 변경(`RecordCatalogSaleChange`, 결정 48) ④ 노출 설정 변경(`ChangeCatalogListing`, 결정 51) | Store는 넷 다 사본 덮어쓰기로 반응. 다른 소비자가 생기면(알림·검색 색인·Download 등) "최초 공개"만 골라낼 방법이 버전 추론뿐이다 | ① `CatalogProductFirstPublished`(최초 공개. 옛 `CatalogProductPublished`는 P2.7-4로 이행 뒤 제거 — 옛 타입의 뜻을 좁혀 다시 쓰지 않는다, P2.7-2) ② `CatalogProductReleaseReplaced` ③ `CatalogProductSaleStatusChanged` ④ `CatalogProductListingChanged`. 넷 다 같은 `CatalogProductSnapshot`(전체 공개 사본 + `catalogVersion`)을 싣는다 | Store `CatalogEventListener`가 네 타입을 받아 같은 `ApplyCatalogProduct.execute(snapshot)`로 합친다(P2.3-5 버전 판정 유지) | 결정 48·51, RC-6 |
| SP-5 | `RefundPaymentConfirmed.source`(`PG_RESPONSE`·`PG_QUERY_UNATTRIBUTED`) | ① 원결제 전액 취소가 확인됨(돈) ② 그 취소가 이 시도의 PG 취소 응답에 귀속됨(`PG_RESPONSE`일 때만) | Settlement는 ①만 필요. Order는 `source`가 `PG_RESPONSE`면 시도 성공까지 기록한다(`order:core/domain/Refund.java:239`). 귀속이 늦게 확인되면 `RefundExecutionSucceeded`로 따로 온다 — 같은 "귀속" 사실이 두 이벤트에 나뉘어 있다(원칙 ③) | ① `RefundPaymentConfirmed`(돈의 결과만, `source` 칸 제거) ② 귀속은 항상 `RefundExecutionSucceeded`로(즉시 응답이면 ①과 같은 트랜잭션에서 둘 다 적재, 늦은 응답이면 나중에 ②만) | Order는 ①로 환불 완료, ②로 시도 귀속을 기록. Settlement 변화 없음. 귀속 미확정 종결(결정 127)은 ①만 나가고 ②가 없다 | 결정 127, RC-8 |
| SP-6 | `PaymentApproval.lastErrorCode`(이벤트 칸이 아니라 Payment 상태 칸) | 보류 사유·판매 제한 대기·마지막 오류 코드 겸함, CHECK 없음 | 이벤트로 나가지 않아 P2 범위 밖. 다만 design "목표 행" `OrderPaymentReviewClosed`가 보류 사유를 싣는다면 이 칸이 정본이 되므로 먼저 나뉘어야 한다 | Payment 모듈 스펙(보류 사유 enum + 오류 코드 칸 분리) | — | 결정 144, RC-2, MC-P4 |
| SP-7 | `ProjectReviewWithdrawalResolved`(`ProjectWithdrawalResult.status` `String`) | ① 철회가 이겨 심사가 `WITHDRAWN`됨 ② 판정이 먼저 커밋돼 철회가 거절됨(`status`=`APPROVED`/`REJECTED`). `actor`는 철회자 또는 판정자, `feedback`은 철회 사유 또는 반려 사유(`review:core/domain/ReviewWithdrawal.java:63-68`) | Studio는 ①이면 요청을 `WITHDRAWN`·자리 해제, ②면 명령만 `DENIED`. 판정자·반려 사유는 판정 이벤트가 정본이다 | ① `ProjectReviewWithdrawn`(withdrawalId·reviewRequestId·projectId·contentVersion·productCode·withdrawnBy·withdrawalReason·withdrawnAt·requestEventId) ② `ProjectReviewWithdrawalDenied`(withdrawalId·reviewRequestId·…·`targetStatus` enum {`APPROVED`,`REJECTED`}·requestEventId) | Studio `ProjectResultListener`가 둘을 명령·요청 반영 입력으로 변환 | 결정 53·54, RC-9, T21 |
| SP-8 | `ProjectReleaseWithdrawalResolved` | ① 공개 확정 전 철회되어 `WITHDRAWN` ② 이미 `COMPLETED`/`REJECTED`라 철회 거절. 판정 아닐 때 `actor`=문자열 `"catalog"`, `feedback`=철회 사유 또는 거절 사유(`catalog:core/domain/ReleaseWithdrawal.java:63-67`) | SP-7과 같음 | ① `ProjectReleaseWithdrawn` ② `ProjectReleaseWithdrawalDenied`(`targetStatus` enum {`COMPLETED`,`REJECTED`}) | SP-7과 같음 | 결정 57, RC-9, T21 |

이번 점검에서 나누지 않기로 본 것: `LicenseSuspensionRequested`는 발행 지점이 5곳이지만(design 이벤트 목록 #18) 모두 "Order가 정지 요청을 등록했다"는 같은 사실이고 `refundId`·`eventId`(= `suspensionRequestEventId`)로 구별된다. `LicenseSuspended`도 환불 정지와 승인 정지(재심사 중) 모두 "정지 요청 한 건이 처리됐다"는 한 사실이고, Order가 `requestEventId`로 어느 요청인지 판정한다.

`ProjectReleaseCompleted`는 "새 공개본으로 교체"와 "같은 승인본 재출시라 기존 공개본 유지"(결정 58)를 함께 뜻하지만 Studio에는 둘 다 "요청 완료"라는 한 사실이라 나누지 않는다. `ProjectReleaseRejected.reason`은 자유 문자열이 아니라 enum(`STALE_CONTENT_VERSION` 등)으로 고정한다.

### 목표 행 (아직 코드에 없음)

| 이벤트 | 업무 사실 한 문장 | 소유(발행) · 발행 지점 | 소비 모듈 · 소비 그룹 | 토픽 | 키 | 순서 요구 | 멱등 키 | 실패 처리 | 근거 |
|---|---|---|---|---|---|---|---|---|---|
| `RefundFailureClosed` | ADMIN이 PG 재조회 근거(원결제 `PAID`·취소액 0·진행 중이거나 성공한 취소 없음)로 자동 송신이 멈춘 환불 시도를 실패로 종결했다 | Payment · `CloseRefundFailure`(종결과 같은 잠금 트랜잭션, P3.23-2) | **Order만** · `order-refund-restoration`(`RefundRestorationEventListener`가 `RefundExecutionFailed`와 함께 받아 같은 내부 입력으로 합침). Settlement·License는 소비하지 않음 | `stove.payment.v1` | `orderNo` | 같은 시도의 `RefundExecutionPrepared` 뒤. 한 시도에 `RefundExecutionFailed`와 둘 다 나가지 않음(I16) | 시도당 실패 사실 1건: Payment `uk_refund_execution_failure`·`refund_failure_closure.failure_event_id` 유일, Order 시도별 실패 기록 | 환불 팩토리 | 결정 128·147·148, T4 |
| `RefundLateCancellationConfirmed` | ADMIN이 PG 재조회 근거(원결제 전액 `CANCELLED`)로, 실패로 확정된 환불 시도 뒤 실제로 일어난 전액 취소를 확인했다 | Payment · 늦은 취소 반영 서비스(가칭 `ConfirmLateCancellation`, 확인 기록과 같은 잠금 트랜잭션, P3.23-7) | Order(늦은 취소 완료 → `PurchaseRefunded`) · 그룹은 Order 모듈 스펙; Settlement(환불 조정) · `settlement-confirmed-refund`(`RefundPaymentConfirmed`와 같은 내부 입력으로 합침). License는 소비하지 않음(`PurchaseRefunded`로 회수) | `stove.payment.v1` | `orderNo` | 같은 시도의 실패 사실(`RefundExecutionFailed`·`RefundFailureClosed`) 뒤 | 시도당 하나: Payment 늦은 취소 확인 기록 유일(시도 ID), Order·Settlement Inbox | 기본 | 결정 128·157, T4 |
| `OrderPaymentReviewClosed` | ADMIN이 PG 재조회 근거(전액 CANCELLED·FAILED·READY)로 금융 보류 승인을 종결해 이 주문 요청의 결제가 끝났다 | Payment · 승인 종결 서비스(`CloseApprovalReview`) | Order · `order-payment-result`(Completed·Failed와 **같은 그룹**: 같은 주문의 결과가 한 스레드에서 커밋 순서대로 처리된다, P2.5-1) | `stove.payment.v1` | `orderNo` | 주문 요청당 결과 하나 — `OrderPaymentCompleted`·`OrderPaymentFailed`와 배타 | Order `uk_purchase_order_result`; Payment `uk_purchase_payment_result`·`approval_closure.request_id` | 기본 | 결정 114·115, RC-1, T18 |
| `OrderPaymentResultRejected` | Order가 이 주문 요청의 결제 성공 결과를, 열린 최신 승인 진행 기록의 결과가 아니어서 반영하지 않았다 | Order · `ApplyPaymentResult`(거절 기록과 같은 주문 행 잠금 트랜잭션, P3.14-2) | Payment · (신설) `payment-order-result-rejection` | `stove.order.v1` | `orderNo` | 거절한 결과 뒤(인과). 결과 하나에 거절 하나 | 거절한 결과의 `eventId`: Order 거절 기록 유일 키, Payment Inbox | 기본 | 결정 153, T1 |
| `CatalogProductFirstPublished` · `CatalogProductReleaseReplaced` · `CatalogProductSaleStatusChanged` · `CatalogProductListingChanged` | SP-4 ①~④ | Catalog · `PublishProjectRelease`(①②), `RecordCatalogSaleChange`(③), `ChangeCatalogListing`(④) | Store · `store-catalog-publication`(네 타입을 `ApplyCatalogProduct` 하나로 합침) | `stove.catalog.v1` | `productCode` | `catalogVersion` 순(버전 판정) | 상태 사본 예외(P2.3-5) | 기본 | 결정 48·51, RC-6, [결정 필요 PD-19], T22 |
| `ProjectReviewWithdrawn` · `ProjectReviewWithdrawalDenied` | SP-7 ①·② | Review · `ReceiveReviewWithdrawal`·`ReceiveProjectReview` | Studio · `studio-project` | `stove.review.v1` | `productCode` | 같은 요청의 철회 요청 뒤(인과) | `withdrawalId` | 기본 | RC-9, T21 |
| `ProjectReleaseWithdrawn` · `ProjectReleaseWithdrawalDenied` | SP-8 ①·② | Catalog · `ReceiveReleaseWithdrawal`·`PublishProjectRelease` | Studio · `studio-project` | `stove.release.v1` | `productCode` | 같은 요청의 철회 요청 뒤(인과) | `withdrawalId` | 기본 | RC-9, T21 |
| (운영자 출시 종결) | [결정 필요 PD-16]을 B로 고르면 여기 먼저 정의 | Catalog | Studio | `stove.release.v1` | `productCode` | — | — | — | PD-16 |

`OrderPaymentResultRejected` 본문 목표: 주문 연결값 5개(`orderNo`·`requestEventId`·`buyerSubject`·`amount`·`currency`), `rejectedResultEventId`, `paymentId`, `admissionId`(거절한 결과에 있던 값, 없으면 null), `rejectionReason`(enum: `NO_OPEN_ADMISSION` 열린 진행 기록 없음 · `ADMISSION_MISMATCH` 다른 진행 기록 · `ORDER_CLOSED` 주문이 이미 `EXPIRED`·`FAILED`·다른 결과로 `PAID`). 금액·PG 상태는 싣지 않는다 — 청구 근거의 정본은 Payment다(원칙 ③, [결정 153](../../../docs/decisions.md#late-success-rejection)).

`RefundFailureClosed` 본문 목표(참고 구현 `esd-worktrees/refund-failure-closure`의 `RefundFailureClosedEvent`와 다른 점은 "비고"):

| 칸 | 뜻 | 비고 |
|---|---|---|
| `eventId` | 이 실패 사실의 ID. 실행의 `failureEventId`와 같다 | PG 확정 실패와 같은 칸이므로 Payment 실행에 실패 근거가 있어야 한다(`refund_failure_closure` 행 유무, MC-P8) |
| `occurredAt` | 종결 시각(Payment 시계) | 소비 쪽 판정에 쓰지 않음(P2.1-3) |
| `refundId`·`attemptId`·`requestEventId`·`orderNo`·`paymentId`·`buyerSubject`·`amount`·`currency`·`providerTransactionId` | 어느 환불 시도인지 | `RefundExecutionFailed`와 같은 이름·같은 뜻 |
| `closureId` | 종결 요청 ID | Order가 시도의 출처(`OPERATOR_FAILURE_CLOSED`)와 함께 기록 |
| `failedCancellationId` | 연결된 PG 취소가 있고 재조회에서 FAILED였을 때만 그 ID, 없으면 null | `cancellationId`와 이름을 달리해 뜻을 구별(원칙 ①) |
| (싣지 않음) `observedPaymentStatus`·`closureBasis`·`evidenceObservedAt`·`lookupHash`·ADMIN 식별자 | 근거 상세 | Payment `refund_failure_closure`가 정본(원칙 ③). `observedPaymentStatus`는 이 사실에서 늘 같은 값이고 공급자 상태 문자열이며(원칙 ②), `evidenceObservedAt`은 Order가 Payment 시각끼리 다시 검사하는 원인이었다. 참고 구현은 이 넷을 싣는다 — T4에서 뺀다 |

칸 변경 목표(이벤트 종류는 그대로):

| 이벤트 | 변경 | 호환(P2.7) | 이유 | 작업 |
|---|---|---|---|---|
| `OrderPlaced` | `expiresAt` 추가(Order가 정한 결제 기한) | 선택 칸 추가 — 호환 | 정본 하나(P1.4-3). `OrderExpired.expiresAt`과 같은 이름·같은 뜻 | T12 |
| `OrderPaymentCompleted` | `admissionId` 추가(결과를 낸 승인 진행 영수증 = 시도 `approvalId`) | 선택 칸 추가 — 옛 행은 null이고 Order는 null 결과를 `PAID`로 받지 않는다(이행 방침은 T1) | I12, 원칙 ⑥ | T1 |
| `OrderPaymentFailed` | `admissionId` 추가(접수된 시도를 끝낸 실패만 값), `reasonCode`에서 `REVIEW_CLOSED` 제거 | 칸 추가는 호환, `REVIEW_CLOSED` 제거는 SP-1 이행(P2.7-4) 뒤 | SP-1 | T1·T18 |
| `RefundPaymentConfirmed` | `source` 삭제 | **비호환**(칸 삭제) — [결정 필요 PD-18]의 버전 방식으로 | SP-5 | T19 |
| `RefundExecutionFailed` | `cancellationId`는 PG가 준 우리 취소 ID만 | 뜻 고정(지금도 같음) | 결정 147 문제 예시 | T4 |
| Settlement `settlement_refund.source`(테이블 열) | 새 행에 의미 없는 값으로 동결, 옛 행 호환 | — | SP-5의 사본 | MC-S2 |

### 소비 그룹 (21개)
소비 그룹 21개(`CONSUMER_GROUP` 상수, 2026-11-09 확인)와 그 소비 이벤트는 위 `EventType` 표·리터럴 표의 "소비 모듈 · 소비 그룹" 열이 정본이다. 리스너는 모두 `apps/<모듈>/src/main/java/com/stove/<모듈>/api/listener/`, 서비스는 `.../core/service/`에 있다. 컨테이너 팩토리: Order `order-refund-*`·`order-approval-correction` 그룹은 환불 팩토리, Payment `payment-refund-execution`은 결제 환불 팩토리, 나머지는 기본.

여러 토픽을 받는 리스너 중 (토픽, `eventType`) 짝까지 대조하는 것은 Order `RefundRestorationEventListener:28-29`뿐이다(`RefundExecutionFailed`는 payment 토픽, `LicenseRestored`는 license 토픽에서 온 것만 받음). 목표: 여러 토픽을 받는 리스너는 모두 이 짝을 대조한다(P2.2-2, MC-ST1).

## 모듈 간 흐름

흐름마다 단계 목록과 **단계별 실패 표**를 둔다(P3.1-1, 결정 152). 실패 종류는 6가지 — 중복 전달 · 순서 역전 · 늦은 도착 · 상대 상태가 이미 바뀜 · 동시 진행 · 상대 모듈 중단 — 이고 모든 단계에 6행이 있다(흐름 1 60행, 흐름 2 72행, 흐름 3 84행, 빈칸 0). "지금 코드" 열은 2026-11-09에 직접 읽은 것만 적었고 못 읽은 곳은 "(미확인)", 흐름 3의 **[읽음]**은 소스를 열어 확인함, **[표]**는 모듈 `design.md`의 결정 143 코드 기준 상태×이벤트 표에서 옮김이다. 실패 표의 행은 P5 실험의 가설 목록이다.

### 흐름 1. 출시 → 공개 (Studio → Review → Studio → Catalog → Store · Download)

#### 단계 목록
토픽: `stove.studio.v1`(Studio 발행), `stove.review.v1`(Review 발행), `stove.release.v1`(Catalog 출시 결과), `stove.catalog.v1`(Catalog 공개 사본). 모든 키는 `productCode`. 소비 그룹: Review `review-project`, Catalog `catalog-project-release`, Studio `studio-project`(`stove.review.v1`·`stove.release.v1` 함께), Store `store-catalog-publication`.

| 단계 | 시작 모듈(트리거) | 이벤트(목표 이름 / 지금 이름) | 받는 모듈(그룹) | 상태 전이 |
|---|---|---|---|---|
| S1 심사 요청 | Studio `RequestProjectReview` (CREATOR POST, `requestId`·`contentVersion`) | `ProjectReviewRequested` (`stove.studio.v1`) | Review `ReceiveProjectReview` (`review-project`) | Studio `studio_review_request`: (없음)→REQUESTED, 명령 영수증 생성, 활성 심사 자리 점유 / Review `project_review`: (없음)→REQUESTED |
| S2 심사 철회 | Studio `RequestProjectWithdrawal` (CREATOR POST) | 요청: `ProjectReviewWithdrawalRequested` (`stove.studio.v1`) → 결과: 목표 `ProjectReviewWithdrawn` / `ProjectReviewWithdrawalDenied`, 지금 `ProjectReviewWithdrawalResolved` (`stove.review.v1`) | Review `ReceiveReviewWithdrawal` → Studio `RecordProjectWithdrawalResult` | Studio 명령: PENDING→WITHDRAWN 또는 DENIED / Review: REQUESTED→WITHDRAWN(철회 승) 또는 변화 없음(판정 선확정) / Studio 심사 요청: REQUESTED→WITHDRAWN, 자리 해제 |
| S3 심사 판정 | Review `DecideProjectReview` (REVIEWER·ADMIN POST approve/reject) | `ProjectReviewApproved` / `ProjectReviewRejected` (`stove.review.v1`) | Studio `RecordProjectReviewResult` (`studio-project`) | Review: REQUESTED→APPROVED/REJECTED / Studio 심사 요청: REQUESTED→APPROVED/REJECTED, 자리 해제, APPROVED이고 현재 버전이면 `releaseEligible=true` |
| S4 출시 요청 | Studio `RequestProjectRelease` (CREATOR POST, `releaseEligible` 필요) | `ProjectReleaseRequested` (`stove.studio.v1`) | Catalog `ReceiveProjectRelease` (`catalog-project-release`) | Studio `studio_release_request`: (없음)→REQUESTED, 활성 출시 자리 점유 / Catalog `catalog_publication_job`: (없음)→PENDING |
| S5 출시 철회 | Studio `RequestProjectWithdrawal` | 요청: `ProjectReleaseWithdrawalRequested` (`stove.studio.v1`) → 결과: 목표 `ProjectReleaseWithdrawn` / `ProjectReleaseWithdrawalDenied`, 지금 `ProjectReleaseWithdrawalResolved` (`stove.release.v1`) | Catalog `ReceiveReleaseWithdrawal` → Studio `RecordProjectWithdrawalResult` | Catalog `catalog_release_withdrawal`: 접수(미해결)→해결 / Catalog `catalog_release`: (없음)→WITHDRAWN(철회 승) 또는 변화 없음 / Studio 명령 PENDING→WITHDRAWN/DENIED, 출시 요청 REQUESTED→WITHDRAWN |
| S6 공개 확정 | Catalog `PublicationScheduler` → `PublishQueuedReleases` → `PublishProjectRelease` (200ms 주기) | 출시 결과 `ProjectReleaseCompleted` / `ProjectReleaseRejected` (`stove.release.v1`) + 공개 사본 목표 `CatalogProductFirstPublished` / `CatalogProductReleaseReplaced`, 지금 `CatalogProductPublished` (`stove.catalog.v1`) | Studio (S7), Store (S8) | Catalog job: PENDING→COMPLETED / `catalog_release`: (없음)→COMPLETED 또는 REJECTED(`STALE_CONTENT_VERSION`) 또는 WITHDRAWN(대기 철회 있음) / `catalog_product`: (없음)→생성(ON_SALE·AUTO·제한 없음, `catalogVersion=contentVersion`) 또는 `currentReleaseRequestId` 교체·`catalogVersion` 증가 |
| S7 출시 결과 기록 | Kafka 전달 | `ProjectReleaseCompleted` / `ProjectReleaseRejected` | Studio `RecordProjectReleaseResult` (`studio-project`) | Studio 출시 요청: REQUESTED→COMPLETED/REJECTED, 활성 출시 자리 해제 |
| S8 Store 사본 반영 | Kafka 전달 | 공개 사본 이벤트(S6, 그리고 판매·노출 변경의 공개 사본 이벤트) | Store `ApplyCatalogProduct` (`store-catalog-publication`) | `store_product`: (없음)→생성, 또는 `catalogVersion` 증가 시 공개 필드 교체 |
| S9 Store 동기화·대조·복구 | Store `CatalogSynchronizationScheduler` → `SynchronizeCatalogProducts` (1초 확인, 성공 뒤 1분·실패 뒤 5초) | 이벤트 없음, HTTP `GET /internal/v1/store-products`·`/{code}` | Store `ReconcileCatalogProduct` | 메모리 준비 상태 false→true(최초 전체 성공) / `store_product` INSERTED·UPDATED·REPAIRED / `store_reconciliation` 행 추가 |
| S10 다운로드 티켓 | Download `ReadBuildDownload` (MEMBER GET) | 이벤트 없음, HTTP License `GET /api/v1/library/{code}` → Catalog `GET /internal/v1/products/{code}/build` → 저장소 HEAD·presign | (응답만) | 상태 없음(무상태). 결과: 200 티켓 / 403 / 404 / 409 / 503 |


Review·Catalog·Studio의 상태×입력 표(원요청·철회·판정 입력의 모든 쌍)와 상태 열 목표 enum·CHECK는 각 모듈 스펙으로 옮긴다(MC-ST3·MC-R1·MC-C1).

#### 끝 상태와 정합 조건 (RI-1 ~ RI-9)

흐름이 끝났다 = 아래 하나로 수렴하고, 진행 중 자리(Studio 활성 심사·출시, Review REQUESTED, Catalog PENDING 작업·미해결 철회)가 모두 비었다.

| 끝 상태 | Studio | Review | Catalog | Store | Download |
|---|---|---|---|---|---|
| E1 공개됨(최초·교체) | 심사 APPROVED, 출시 COMPLETED | APPROVED | `catalog_release` COMPLETED, `currentReleaseRequestId` = 이 요청, job COMPLETED | `store_product.catalogVersion` = Catalog 값, 공개 필드 같음 | 이 공개본 빌드 발급 |
| E2 같은 승인본 재출시 | 출시 COMPLETED | APPROVED(재사용, 결정 58) | 새 요청 COMPLETED, 현재 참조·공개 시각 그대로, 공개 사본 이벤트 없음 | 변화 없음 | 변화 없음 |
| E3 늦은 버전 거절 | 출시 REJECTED(`STALE_CONTENT_VERSION`) | APPROVED | 요청 REJECTED, 현재 공개본 유지 | 변화 없음 | 기존 공개본 |
| E4 출시 철회 | 출시 WITHDRAWN, 명령 WITHDRAWN | APPROVED(유지) | 요청 WITHDRAWN, 이전 공개본·설정 유지 | 변화 없음 | 이전 공개본(있으면) |
| E5 반려 | 심사 REJECTED | REJECTED(`feedback`) | 없음 | 없음 | 없음 |
| E6 심사 철회 | 심사 WITHDRAWN, 명령 WITHDRAWN | WITHDRAWN | 없음 | 없음 | 없음 |
| E7 철회 거절 | 명령 DENIED, 대상은 E1·E3·E5 중 하나 | 판정 유지 | 결과 유지 | E1이면 E1과 같음 | E1이면 E1과 같음 |

정합 조건(검사기 = 모든 스키마를 읽는 읽기 전용 계정의 주기 대조, P3.9. 스키마 이름은 이번에 확인하지 않아 테이블 이름만 적는다):

| 번호 | 조건 | 방향 | 지키는 곳(지금) | 확인 테스트(지금) | 목표와 차이 |
|---|---|---|---|---|---|
| RI-1 | Studio `studio_review_request`(id)의 최종 상태 = Review `project_review`(`review_request_id`=id)의 상태. Studio가 APPROVED/REJECTED이면 Review가 같은 값이다 | 역방향 항상, 정방향은 지연 기준 `[결정 필요 PD-6]` 안 | Review 판정·결과 Outbox 한 트랜잭션(`DecideProjectReview.java:24-41`), Studio 요청 ID·버전 대조(`RecordProjectReviewResult.java:27-33`) | Review E2E(`verify-review.py`), `ProjectReviewIntegrationTest` | 다른 결과가 오면 Studio가 무시(MC-ST2), 모듈 간 검사기 없음(T6) |
| RI-2 | Catalog `catalog_release` COMPLETED의 `review_request_id`로 찾은 Review 심사가 APPROVED이고, `project_id`·`content_version`이 같으며, 두 스냅샷이 값으로 같다(공개본은 승인된 자료다) | 역방향 항상 | Studio만: 출시 요청 때 현재 버전 승인 확인과 그 심사 스냅샷 재사용(`RequestProjectRelease.java:45-47·58·64`). Catalog는 확인하지 않음(Catalog 설계 "Studio 명령을 신뢰") | (미확인) — 모듈 간 테스트 없음 | 공개를 확정하는 모듈에 가드가 없다. Catalog가 확인할지 `[결정 필요 PD-15]`, 어느 쪽이든 검사기 필요(T6) |
| RI-3 | `catalog_product.current_release_request_id`가 가리키는 `catalog_release`는 COMPLETED이고, 그 Project의 COMPLETED 중 `content_version`이 가장 크다 | 모듈 안(공개 정본) | 상품 행 잠금 + `contentVersion` 비교(`PublishProjectRelease.java:79-126`), 읽을 때 재확인(`ReadPublishedBuild.java:45-52`) | `CatalogPublicationIntegrationTest#concurrentFirstPublicationAndDuplicateDeliveriesConvergeToHighestVersion` | DB 제약 없음(애플리케이션 가드만) — 허용 |
| RI-4 | Studio `studio_release_request`(id)의 최종 상태 = Catalog `catalog_release`(`release_request_id`=id)의 `status`. Studio COMPLETED이면 Catalog COMPLETED | 역방향 항상, 정방향 지연 `[결정 필요 PD-6]` | Catalog 결과 Outbox 한 트랜잭션(`PublishProjectRelease.java:149-169`), Studio 대조(`RecordProjectReleaseResult.java:32-36`) | `verify-catalog.py`, G4 여정 | 다른 결과·다른 원요청 ID를 Studio가 무시(MC-ST2), 검사기 없음(T6) |
| RI-5 | 모든 `catalog_product` p에 대해 Store `store_product`(`product_id`=p.id)가 있고 `catalog_version` ≤ p.`catalog_version`, 같으면 공개 필드가 같다. 지연 기준 안에 같아진다. Store에만 있는 상품은 없다 | 정방향 지연 `[결정 필요 PD-6]`, 역방향 항상 | 이벤트 반영 버전 규칙(`StoreProduct.java:64-72`), 정본 대조(`ReconcileCatalogProduct.java`, 결정 66·67), 경보 `StoreSyncInitStalled`·`StoreSyncReconcileFailing` | `StoreReconciliationIntegrationTest`, `StoreAutomaticRecoveryIntegrationTest`, `SynchronizeCatalogProductsTest` | 대조 자체는 있음(이 흐름에서 유일한 모듈 간 검사기). 상품 단위 지연 지표 없음(MC-SO1) |
| RI-6 | Download 티켓의 `version`·`fileSize`·`checksum`은 발급 시점 Catalog `current_release_request_id` 공개본 스냅샷의 값이고, 그 `productId`·`productCode`는 License 보유 상품과 같다 | 요청마다(사본 없음) | `ReadPublishedBuild.java:26-43`, `ReadBuildDownload.java:23-28`, 저장소 HEAD 크기 대조 | `ReadBuildDownloadTest`, `DownloadIntegrationTest`, G4 `verifiesPublishedUpdateAndPermanentRight` | 없음. Store 사본이 늦으면 Store 상세 버전 ≠ Download 버전인 창이 있다 — 불변식 아님(Store는 조회 사본) |
| RI-7 | 프로젝트별 진행 중 심사 ≤ 1(Studio·Review 각각), 진행 중 출시 ≤ 1(Studio). Studio에 활성 심사가 있으면 Review에 같은 id REQUESTED 또는 미해결 철회가 있다 | 모듈 안 + 정방향 지연 | Studio `uk_studio_active_review`·`uk_studio_active_release`(V26 생성 열), Review V11 활성 유일 키 | `StudioWithdrawalMigrationIntegrationTest`, `ReviewWithdrawalMigrationIntegrationTest` | 모듈 간 활성 자리 대조 없음(T6) |
| RI-8 | Catalog WITHDRAWN 요청은 현재 공개본이 된 적이 없고 그 요청으로 공개 사본 이벤트가 나가지 않았다. Studio 명령 WITHDRAWN이면 대상이 Review·Catalog에서 WITHDRAWN이다 | 역방향 항상 | `PublishProjectRelease.java:108-121`(대기 철회가 있으면 상품 변경 전에 끝냄), 같은 요청 잠금 | `ReleaseWithdrawalIntegrationTest#aWithdrawnUpdateKeepsTheEarlierPublicBuildAndIndependentControls`, `ReviewWithdrawalIntegrationTest` | 없음 |
| RI-9 | `catalog_product.catalog_version`은 단조 증가하고 현재 공개본 `content_version` 이상이다. Store `catalog_version`은 내려가지 않는다 | 모듈 안 | `CatalogProduct.java:133·148`(`max(increment, contentVersion)`), `StoreProduct.java:68` | `CatalogProductTest`, `StoreProductTest` | DB CHECK로 `catalog_version >= 1` 정도만 가능 — 단조성은 코드 가드 |

#### 단계별 실패 표

공통: 수신 실패는 공통 `DefaultErrorHandler`(1·2·4초) 뒤 DLT, 경보 `MessagesDeadLettered`. Outbox가 Kafka로 못 나가면 `OutboxBacklogGrowing`·`OutboxEventsAbandoned`, 소비 정지는 `ConsumerLagGrowing`·`ConsumerStalled`, 앱 중단은 `ServiceDown`(모두 `infra/prometheus/alerts.yml`). DLT·DEAD 재투입은 각 앱 공통 운영 API(Studio·Review·Catalog의 `RecoveryOps*` 테스트가 지킴, Store는 `stove.messaging.ops.enabled=false`).

| 단계 | 실패 종류 | 구체 사례 | 감지하는 곳 | 처리(재시도·멱등 키·보상) | 끝내 안 되면(운영자 개입·경보) | 지금 코드 | 목표와 차이 |
|---|---|---|---|---|---|---|---|
| S1 심사 요청 | 중복 전달 | 릴레이 재발행·Kafka 재배달로 같은 eventId가 두 번, 또는 다른 eventId로 같은 `reviewRequestId`·같은 스냅샷 | Review `ReviewRequestGate` 잠금 뒤 `findById(reviewRequestId)`, 그다음 Inbox | 같은 스냅샷이면 변화 없음, 다른 스냅샷이면 CONFLICT(덮어쓰지 않음) | 다른 스냅샷 CONFLICT는 DLT·`MessagesDeadLettered`, 운영자가 Studio 원요청과 대조 | `ReceiveProjectReview.java:34-47`. `ProjectReviewIntegrationTest#duplicateReceptionPreservesTheSnapshotAndIndependentRerequests` | 없음 |
| S1 심사 요청 | 순서 역전 | 같은 요청의 철회(S2)가 원요청보다 먼저 Review에 도착(DLT 재투입, 원요청 처리 실패 뒤 철회만 성공) | Review `ReceiveReviewWithdrawal`에서 심사 행 없음 | 철회 의사를 `ReviewWithdrawal`로 보관, 원요청 수신 때 같은 잠금 안에서 WITHDRAWN 확정 | 원요청이 끝내 안 오면 철회가 미해결로 남고 Studio 명령 PENDING·자리 점유가 계속됨 → 검사기 "원요청 없는 철회" `[결정 필요 PD-6]` | `ReceiveProjectReview.java:51-55`, `ReceiveReviewWithdrawal.java:43-44`. `ReviewWithdrawalIntegrationTest#withdrawalBeforeOriginalIsDurableAndOriginalReplayCannotReopenIt`, `#realKafkaCanDeliverWithdrawalBeforeOriginalAndRepeatedOriginalRemainsWithdrawn` | 미해결 철회를 세는 지표·검사기 없음(T6) |
| S1 심사 요청 | 늦은 도착 | Review 정지 동안 요청이 쌓였다가 늦게 도착. 그 사이 Studio 작업본은 v3로 바뀜 | 감지할 필요 없음(스냅샷 기준 심사) | 원요청 스냅샷(v1)으로 그대로 접수·심사, 결과는 v1 요청에만 기록(Studio R7.5) | 창작자는 철회(S2) 후 새 버전 심사 | Review는 Studio 최신 작업본을 읽지 않음(Review R1.3) | 없음 |
| S1 심사 요청 | 상대 상태가 이미 바뀜 | Studio는 이전 심사의 최종 결과를 받아 자리를 풀었는데 Review에는 같은 Project의 다른 REQUESTED가 남음(정상 경로로는 불가, 이행·수동 수정 때 가능) | Review V11 프로젝트별 활성 심사 유일 키 | 트랜잭션 실패 → 공통 재시도 → DLT | DLT 경보, 운영자가 두 모듈의 활성 요청을 대조(Review 설계 V11 전환 절) | V11 키 존재. 위반 때 예외 분류 (미확인) | 활성 자리 불일치(RI-7)를 찾는 검사기 없음(T6) |
| S1 심사 요청 | 동시 진행 | 같은 이벤트를 리밸런싱 중 두 인스턴스가 동시 처리 / Studio에서 같은 Project 심사 요청 2건 동시 제출 | Review 요청 게이트 `ensure`+`lock`·PK / Studio Project 행 잠금·`uk_studio_active_review` | 늦은 쪽 트랜잭션 실패 후 재시도에서 기존 행으로 수렴 / Studio는 409 | 해당 없음 — 재시도로 수렴 | `ProjectReviewIntegrationTest#simultaneousDuplicateIntakeCanRetryAfterTheFailedTransaction`, Studio V26 `uk_studio_active_review` | 없음 |
| S1 심사 요청 | 상대 모듈 중단 | Review 중단 / Kafka 중단 | Review 중단: 소비 지연 지표 / Kafka 중단: Studio Outbox 적체 | 요청은 Studio Outbox·Kafka에 남고 재가동 뒤 이어서 처리. 202는 접수일 뿐(Studio R3) | `ConsumerLagGrowing`·`ServiceDown`·`OutboxBacklogGrowing` | 공통 Outbox 릴레이·경보 규칙 존재 | 창작자에게 "심사 대기 지연"을 알리는 계약 없음(Studio tasks `[결정 필요]` 통지 계약, 모듈 스펙(Studio·Review·Catalog·Download)) |
| S2 심사 철회 | 중복 전달 | 같은 `withdrawalId` 철회가 다른 eventId로 재발행 / 철회 결과가 Studio에 두 번 | Review `withdrawals.findById(withdrawalId)`+`requireSame` / Studio Inbox(`studio-project`) | 이미 해결이면 무시, 내용이 다르면 CONFLICT / Studio는 같은 결과 재기록 없음 | CONFLICT는 DLT | `ReceiveReviewWithdrawal.java:33-41`, `RecordProjectWithdrawalResult.java:40-43`. `ReviewWithdrawalTest`(메서드 미확인) | 없음 |
| S2 심사 철회 | 순서 역전 | "판정이 먼저"인 철회 결과가 판정 이벤트(`ProjectReviewApproved`)보다 먼저 Studio에 도착(같은 토픽·키라 보통은 Outbox 순서대로지만 DLT 재투입 때 역전) | Studio `RecordProjectWithdrawalResult` | 지금: 철회 결과의 `status=APPROVED`로 심사 요청을 APPROVED로 채움, 뒤에 온 판정은 같은 결과라 무시. 목표: `ProjectReviewWithdrawalDenied(targetStatus=APPROVED)`를 리스너가 같은 반영으로 변환, 반려 사유·판정자는 판정 이벤트가 채움 | 해당 없음 — 어느 순서든 같은 최종 상태 | `ProjectWithdrawalIntegrationTest#withdrawalDenialCanRepairTheMissingApprovalResultWithoutPublishingAutomatically` | 결과 이벤트의 칸 겸용(T21) |
| S2 심사 철회 | 늦은 도착 | 철회 이벤트가 소비 지연으로 판정 커밋 뒤에 도착 | Review 요청 잠금 안 상태 확인 | 판정 유지(결정 53) → 거절 결과 발행. Studio 명령 DENIED | 해당 없음 — 정책상 판정 우선 | `ReviewWithdrawalIntegrationTest#approvalBeforeWithdrawalReturnsTheFirstFinalFact` | 이벤트 분리(T21)만 |
| S2 심사 철회 | 상대 상태가 이미 바뀜 | Studio가 판정 결과를 이미 받은 뒤 창작자가 철회 제출 | Studio `RequestProjectWithdrawal`(대상 최종 결과 확인) | 409로 거절, 이벤트 발행 없음(Studio R6.8) | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `RequestProjectWithdrawal.java`(106줄, 최종 결과 검사 줄 미확인) | 없음 |
| S2 심사 철회 | 동시 진행 | 심사자 승인과 철회 수신이 같은 순간 | Review `ReviewRequestGate`·`project_review` 행 잠금 | 먼저 커밋한 쪽이 최종, 뒤쪽은 그 결과를 따름 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `ReviewWithdrawalIntegrationTest#simultaneousApprovalAndWithdrawalAgreeOnOneFinalState` | 없음 |
| S2 심사 철회 | 상대 모듈 중단 | Review 중단 동안 철회 접수 → Studio 명령 PENDING, 활성 자리 유지, 새 심사 409 | 소비 지연 지표 | 재가동 뒤 처리. 타임아웃으로 성공·실패를 정하지 않음(결정 54) | `ConsumerLagGrowing`. PENDING 명령이 오래 남는 것은 검사기 `[결정 필요 PD-6]` | PENDING 명령 수 지표 (미확인) | PENDING 철회 지표·검사기 없음(T6) |
| S3 심사 판정 | 중복 전달 | 심사자 재클릭(같은 판정·사유) / 결과 이벤트 재배달 | Review `ProjectReview.decide` / Studio Inbox | 같은 판정이면 200·새 이벤트 없음, 다른 판정 409 / Studio 무시 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `ProjectReview.java:99-101`, `DecideProjectReview.java:25`, `RecordProjectReviewResult.java:25` | 없음 |
| S3 심사 판정 | 순서 역전 | v1 심사 판정이 v2 심사 요청 접수 뒤 Studio에 도착 | Studio 요청 ID·`contentVersion` 대조 | v1 요청에만 기록, v2와 `releaseEligible`에 영향 없음 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `RecordProjectReviewResult.java:27-33`, `ReadProjects.java:86-91`(현재 버전만 근거) | 없음 |
| S3 심사 판정 | 늦은 도착 | 판정 도착 전에 창작자가 작업본을 v2로 수정 | Studio 현재 버전 필터 | 승인은 v1 요청에만 기록, v2는 새 심사 필요 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `ReadProjects.java:86-91` | 없음 |
| S3 심사 판정 | 상대 상태가 이미 바뀜 | Studio 요청이 이미 WITHDRAWN·다른 판정인데 반대 판정 도착(Review 결함·수동 수정 때만) | Studio `ProjectReviewRequest.recordResult` | 목표: 같은 결과면 무시, 다른 결과면 CONFLICT → DLT | DLT 경보, 운영자가 Review 정본과 대조 | `ProjectReviewRequest.java:90` 최종이면 **조용히 무시** | 모순 결과가 드러나지 않음(MC-ST2) |
| S3 심사 판정 | 동시 진행 | 두 심사자가 같은 요청을 승인·반려 동시 | Review 요청 행 잠금 | 한 판정만 확정, 다른 쪽 409 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `DecideProjectReview.java:24`(`reviews.lock`). 테스트 메서드 (미확인) | 없음 |
| S3 심사 판정 | 상대 모듈 중단 | Studio 중단 → 결과가 Kafka에 대기, 창작자 화면은 REQUESTED | 소비 지연 지표 | 재가동 뒤 반영. Review 200은 Studio 반영 완료가 아님(Review 설계) | `ConsumerLagGrowing`, 처리 실패는 DLT → 운영 API 재투입 | 공통 | 판정 확정 후 Studio 미반영(RI-1 정방향)을 세는 검사기 없음(T6) |
| S4 출시 요청 | 중복 전달 | 창작자 재전송(같은 `requestId`) / Kafka 재배달 / 다른 eventId가 같은 요청에 | Studio 명령 영수증 / Catalog 게이트 잠금 뒤 `catalog_release`·`catalog_publication_job` 존재 대조, `uk_catalog_publication_event` | 기존 요청 반환 / 같은 내용이면 무시, 내용·이벤트 ID가 다르면 CONFLICT | CONFLICT는 DLT | `RequestProjectRelease.java:38-43`, `ReceiveProjectRelease.java:42-57`. `CatalogPublicationIntegrationTest#kafkaConsumesWithNewGroupAndDeduplicates` | 없음 |
| S4 출시 요청 | 순서 역전 | 같은 Project의 v2 요청이 v1보다 먼저 Catalog에 도착(진행 중 1건이라 정상 경로로는 불가, 이행 재전달·DLT 재투입 때 가능) | Catalog 공개 확정 때 `contentVersion` 비교 | 둘 다 접수, v1은 공개 때 `STALE_CONTENT_VERSION`으로 REJECTED | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `PublishProjectRelease.java:98-104`. `CatalogPublicationIntegrationTest#publishesReplacesRejectsStaleAndKeepsOriginalResults` | 없음 |
| S4 출시 요청 | 늦은 도착 | Catalog 정지로 요청이 늦게 도착, 그 사이 창작자는 다음 버전 편집·심사(허용, Studio R5.5) | 감지할 필요 없음 | 요청 스냅샷 그대로 공개. 작업본 변경은 공개본에 영향 없음 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | Catalog는 Studio를 조회하지 않음(Catalog 설계) | 없음 |
| S4 출시 요청 | 상대 상태가 이미 바뀜 | 요청 발행 뒤 빌드 객체가 저장소에서 사라짐(운영 실수·수명 규칙) | Studio는 요청 때 HEAD로만 확인, Catalog는 파일을 다시 검사하지 않음, Download가 HEAD에서 감지 | 공개는 진행, 다운로드는 409(다른 빌드로 대체하지 않음, Download R6.4) | 운영자가 파일 복구 또는 새 버전 출시 | `RequestProjectRelease.java:59-60`, Download `S3BuildStorage` HEAD | 공개 시점 객체 존재를 아무도 확인하지 않음 — 저장소 불변 보존을 운영 조건으로 둔 결정(Download R6.12)대로라 차이 아님 |
| S4 출시 요청 | 동시 진행 | 같은 Project에 다른 `requestId` 출시 2건 동시 / 리스너 접수와 작업자 공개가 같은 요청에 동시 | Studio Project 잠금·`uk_studio_active_release` / Catalog `catalog_request_gate` 잠금 | 409 / 같은 잠금으로 직렬화 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | V26 `uk_studio_active_release`, `ReceiveProjectRelease.java:39-40`, `PublishProjectRelease.java:51-52` | 없음 |
| S4 출시 요청 | 상대 모듈 중단 | Catalog 중단 / Kafka 중단 | 소비 지연 / Outbox 적체 | Studio REQUESTED·자리 유지, 재가동 뒤 접수 | `ConsumerLagGrowing`·`OutboxBacklogGrowing`·`ServiceDown` | 공통 | 출시 지연 통지 계약 없음(모듈 스펙(Studio·Review·Catalog·Download)) |
| S5 출시 철회 | 중복 전달 | 같은 `withdrawalId` 재발행 / 결과 재배달 | Catalog `withdrawals.findById`+`requireSame` / Studio Inbox | 해결됐으면 무시, 다르면 CONFLICT | DLT | `ReceiveReleaseWithdrawal.java:46-55` | 없음 |
| S5 출시 철회 | 순서 역전 | 철회가 원요청보다 먼저 Catalog에 도착 | Catalog 철회 처리 때 작업·출시 기록 없음 | 철회 보관, 원요청 접수 직후 `resume`이 WITHDRAWN 확정, 원요청 재전달로도 공개 안 됨 | 원요청이 끝내 안 오면 미해결 철회·Studio PENDING 지속 → 검사기 `[결정 필요 PD-6]` | `ReceiveReleaseWithdrawal.java:59-85`, `ReceiveProjectRelease.java:62`. `ReleaseWithdrawalIntegrationTest#withdrawalBeforeOriginalWaitsForTheOriginalAndCannotBeReversedByReplay` | 미해결 철회 검사기 없음(T6) |
| S5 출시 철회 | 늦은 도착 | 작업자가 200ms 안에 공개해 철회가 공개 확정 뒤 도착(보통 경우) | Catalog 게이트 잠금 안 `catalog_release` 존재 | 공개 유지(결정 57) → 거절 결과(`targetStatus=COMPLETED`) | 해당 없음 — 철회 시간 보장 없음은 합의된 정책(결정 62) | `ReceiveReleaseWithdrawal.java:80-84`. `ReleaseWithdrawalIntegrationTest#publicationBeforeWithdrawalIsPreserved` | 이벤트 분리(T21)만 |
| S5 출시 철회 | 상대 상태가 이미 바뀜 | Studio가 COMPLETED를 이미 받은 뒤 창작자가 철회 제출 | Studio `RequestProjectWithdrawal` | 409, 이벤트 없음 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | Studio R6.8 (검사 줄 미확인) | 없음 |
| S5 출시 철회 | 동시 진행 | 작업자 공개와 철회 수신이 같은 요청에 동시 | `catalog_request_gate` 잠금 | 한 결과만 확정(공개 또는 WITHDRAWN) | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `PublishProjectRelease.java:106-121`, `ReleaseWithdrawalIntegrationTest#wrongOwnerCannotWithdrawAnAcceptedJobAndConcurrentPublicationHasOneFinalResult` | 없음 |
| S5 출시 철회 | 상대 모듈 중단 | Catalog 중단 동안 철회 접수 / Studio 중단 동안 결과 발행 | 소비 지연 | 재가동 뒤 같은 키 순서로 처리. Studio 명령은 PENDING, 새 버전 출시 409(Studio R5.7) | `ConsumerLagGrowing`, 장기 PENDING은 `[결정 필요 PD-6]` | 공통 | T6 |
| S6 공개 확정 | 중복 전달 | 작업이 롤백 뒤 다시 실행 / 결과·공개 사본 Outbox 재발행 | `PublishProjectRelease`가 `catalog_release` 존재부터 대조 / 소비자 Inbox·버전 규칙 | 같은 내용이면 그대로 완료, 결과 이벤트 재생성 없음 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `PublishProjectRelease.java:54-61` | 없음 |
| S6 공개 확정 | 순서 역전 | 재시도 대기 중인 v1 작업이 다른 인스턴스의 v2 공개 뒤 실행 | 상품 행 잠금 뒤 현재 공개본 `contentVersion` 비교 | v1 REJECTED(`STALE_CONTENT_VERSION`), 현재 공개본 유지 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `PublishProjectRelease.java:98-104`. D-042 | 없음 |
| S6 공개 확정 | 늦은 도착 | 일시 오류로 작업 재시도가 길어짐(5·15·30·60초) → Studio REQUESTED가 오래 남음 | ADMIN `GET /api/v1/admin/catalog-release-jobs`(`attemptCount`·`lastError`) | 같은 요청으로 재시도(Catalog R10.3) | 경보 없음 → 목표: 대기 시간·시도 횟수 지표와 경보 `[결정 필요 PD-6]` | `CatalogPublicationJob.java:68-79`, `alerts.yml`에 Catalog 공개 작업 규칙 없음 | 지연 경보 없음(T23) |
| S6 공개 확정 | 상대 상태가 이미 바뀜 | 공개 직전 관리자가 판매 제한·숨김·다운로드 제한을 설정 | 같은 `catalog_product` 행 잠금, `catalogVersion` | 새 공개본은 제한·선택을 해제하지 않음(Catalog R1.8), `catalogVersion` 단조 증가 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `CatalogProduct.java:145-148`. G4 여정 `enforcesIndependentSalesAndListingControls`(`verify-g4-operations.py`) | 없음 |
| S6 공개 확정 | 동시 진행 | 두 Catalog 인스턴스가 같은 Project의 서로 다른 요청(v1·v2)을 최초 공개 | `lockByProjectId` 빈 결과 뒤 `findByProductCode`의 `projectId` 비교, `catalog_product` 유일 키 | 같은 Project면 `ConcurrencyFailureException`으로 롤백 → 작업 재시도, 다른 Project면 CONFLICT | 해당 없음 — 재시도로 가장 높은 버전에 수렴 | `PublishProjectRelease.java:79-89`. `CatalogPublicationIntegrationTest#sameProjectFirstPublicationCommittedBetweenProjectAndCodeReadsConverges`, `#concurrentFirstPublicationAndDuplicateDeliveriesConvergeToHighestVersion`, `#differentProjectWithSameProductCodeStaysConflict`(D-042) | 없음 |
| S6 공개 확정 | 상대 모듈 중단 | (a) Catalog DB 장애 (b) 재시도로 풀리지 않는 실패(같은 버전 다른 스냅샷, 다른 Project의 코드 충돌, 스냅샷 역직렬화 실패) | (a) 트랜잭션 실패 (b) 같은 오류가 `lastError`에 반복 | (a) 롤백 후 다음 주기 재시도 (b) 지금은 60초마다 **무한 재시도**, Studio REQUESTED·활성 자리 점유가 끝나지 않아 그 Project의 다음 출시가 모두 409 | 목표: (b)를 재시도 대기와 다른 상태로 분리하고 경보, 결과 처리는 `[결정 필요 PD-16]` | `PublishQueuedReleases.java:44-48`(모든 `RuntimeException`을 같은 재시도로), `CatalogPublicationJob.java:68-79`(상한·종료 상태 없음) | 영구 실패의 분류·종료·경보 없음(T23) |
| S7 출시 결과 기록 | 중복 전달 | `ProjectReleaseCompleted` 재배달 | Studio Inbox(`studio-project`) | 무시 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `RecordProjectReleaseResult.java:26-27` | 없음 |
| S7 출시 결과 기록 | 순서 역전 | 출시 철회 거절 결과(COMPLETED)가 `ProjectReleaseCompleted`보다 먼저 Studio 도착(DLT 재투입) | Studio `RecordProjectWithdrawalResult` | 철회 결과로 요청을 COMPLETED로 채움, 뒤의 Completed는 같은 결과로 무시 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `ProjectReleaseRequest.java:71-86` | 이벤트 분리(T21)만 |
| S7 출시 결과 기록 | 늦은 도착 | Studio 소비 지연으로 Completed가 늦음 — Store·Download는 이미 새 공개본, 창작자 화면은 REQUESTED, 새 출시 409 | 소비 지연 지표 | 재가동 뒤 반영 | 정방향 지연(RI-4)은 검사기 `[결정 필요 PD-6]` | 공통 | T6 |
| S7 출시 결과 기록 | 상대 상태가 이미 바뀜 | (a) Studio 요청이 이미 최종인데 다른 결과 도착 (b) 결과의 `requestEventId`가 Studio 원요청 이벤트 ID와 다름 | Studio `ProjectReleaseRequest.recordResult` / `RecordProjectReleaseResult` | 목표: 같은 결과 무시, 다른 결과·다른 원요청 ID는 CONFLICT → DLT·경보 | 운영자가 Catalog 정본과 대조 | (a) `ProjectReleaseRequest.java:65` **조용히 무시** (b) `RecordProjectReleaseResult.java:35` **조용히 무시** — 두 모듈이 다른 최종 상태로 남아도 아무도 모름 | MC-ST2 |
| S7 출시 결과 기록 | 동시 진행 | 결과 기록과 창작자의 새 출시 요청이 동시 | Studio Project 행 잠금 | 직렬화: 결과 기록 뒤면 새 요청 허용, 앞이면 409 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `RecordProjectReleaseResult.java:28`, `RequestProjectRelease.java:36` | 없음 |
| S7 출시 결과 기록 | 상대 모듈 중단 | Studio 중단 / 처리 예외 반복 | 소비 지연 / DLT | 재가동 뒤 처리 / DLT는 운영 API 재투입 | `MessagesDeadLettered` | 공통 | 없음 |
| S8 Store 사본 반영 | 중복 전달 | 같은 공개 사본 이벤트 재배달 | `store_product` 행 잠금 뒤 `catalogVersion` 같음+내용 같음 | 변화 없음(Inbox 없이 버전 규칙) | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `StoreProduct.java:64-72` | 없음 |
| S8 Store 사본 반영 | 순서 역전 | 판매 중지(catalogVersion 5) 뒤 공개본 교체(4)가 DLT 재투입으로 도착 / 과거 형식 JSON(버전 칸 없음) 도착 | `catalogVersion` 비교 | 낮은 버전 무시, 과거 형식은 더 높은 버전의 중지·숨김을 덮지 못함 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `StoreProduct.java:68`, `CatalogProductSnapshot` 생성자 기본값 | 과거 형식 기본값이 계약 record에 있음(T24) |
| S8 Store 사본 반영 | 늦은 도착 | Store 소비 지연 동안 방문자가 구버전·판매 중지 전 사본을 봄 | 다음 대조(S9, 기본 1분) | 이벤트 도착 또는 대조 UPDATED로 수렴. 주문은 Catalog 정본(`/internal/v1/order-products`)으로 판정하므로 돈·보유권 영향 없음 | 상품 단위 지연 경보 `[결정 필요 PD-6]` | 대조 존재, 상품 단위 지연 지표 없음(Store 설계 "관측") | T6 |
| S8 Store 사본 반영 | 상대 상태가 이미 바뀜 | 같은 `catalogVersion`인데 이벤트 내용이 사본과 다름(Catalog 결함) | `StoreProduct.apply` 같은 버전 비교 | Kafka 입력은 CONFLICT(덮어쓰지 않음) → DLT, 정본 대조가 REPAIRED로 교정(결정 67) | 반복되면 Catalog 결함 — `store_reconciliation` REPAIRED 기록으로 운영자 조사 | `StoreProduct.java:69-72`, `ReconcileCatalogProduct.java:67` | 없음 |
| S8 Store 사본 반영 | 동시 진행 | 이벤트 반영과 HTTP 대조가 같은 상품을 최초 삽입 | `lockById` 빈 결과 뒤 `findByProductCode`의 `productId` 비교, 유일 키 | 같은 상품이면 `ConcurrencyFailureException` → 새 트랜잭션 최대 3회, MySQL 1213도 원인 사슬로 재시도 | 3회 실패면 Kafka 재시도·대조 다음 회차 | `ApplyCatalogProduct.java:33-53`. `StoreIntegrationTest#sameProductCommittedBetweenIdAndCodeReadsConverges`, `#kafkaDeliveryConvergesWhenSameProductIsCommittedBetweenReads`, `#concurrentHttpAndEventInsertsConvergeToHighestVersion`(D-040·D-047) | 없음 |
| S8 Store 사본 반영 | 상대 모듈 중단 | Store 중단 / Catalog 중단 | 소비 지연 / Store는 이벤트만으로 반영하므로 영향 없음 | Store 재시작 때 전체 대조부터 다시, 사본은 보존 | `ServiceDown`·`ConsumerLagGrowing` | Store R6.8 | 없음 |
| S9 동기화·대조 | 중복 전달 | 대조가 겹쳐 실행 / 같은 상태를 반복 대조 / Store 인스턴스 둘이 각자 대조 | `AtomicBoolean active` / 결과 UNCHANGED / 행 잠금·버전 | 겹친 실행은 하나로 합침, 이력 없음, 인스턴스 간에는 행 잠금으로 안전 | 해당 없음 — 전역 조율은 범위 밖(Store 범위 밖) | `SynchronizeCatalogProducts.java:54`, `ReconcileCatalogProduct.java:68` | 없음 |
| S9 동기화·대조 | 순서 역전 | HTTP 상세(v3)를 읽는 사이 이벤트 v4가 반영됨 | 행 잠금 뒤 `catalogVersion` 비교 | STALE로 판정, 최신 사본 보존 | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `StoreProduct.java:86`. `StoreIntegrationTest#reconciliationConvergesWhenEventCommitsBetweenIdAndCodeReads` | 없음 |
| S9 동기화·대조 | 늦은 도착 | 페이지 순회 중 Catalog에 새 상품이 공개되어 이번 회차에서 빠짐 | 다음 회차 | INSERTED. 전체 원자 스냅샷은 보장하지 않음(Store 설계) | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `SynchronizeCatalogProducts.java:65-86` | 없음 |
| S9 동기화·대조 | 상대 상태가 이미 바뀜 | Catalog 정본에서 같은 `productCode`가 다른 `productId`로 바뀜(식별 충돌) / 정본 응답 계약 오류 | `ReconcileCatalogProduct` 코드 대조 | 덮어쓰지 않고 상품별 실패 기록, 나머지 상품 계속, 회차는 실패 → 5초 뒤 재시도 | `StoreSyncReconcileFailing`(성공 5분 끊김), 운영자가 Catalog 원인 수정 | `ReconcileCatalogProduct.java:57-62`, `SynchronizeCatalogProducts.java:75-87` | 한 상품의 영구 충돌이 전체 대조 성공 표시를 계속 막아 다른 상품 지연을 가림 — 상품별 실패 지표 분리 필요(MC-SO1) |
| S9 동기화·대조 | 동시 진행 | 대조와 이벤트 반영이 같은 상품 최초 삽입 | S8 동시 진행과 같음 | 같음 | 같음 | D-040 `ReconcileCatalogProduct.apply` 수정 | 없음 |
| S9 동기화·대조 | 상대 모듈 중단 | Catalog 중단·키 거절·5xx·429 | `CatalogUnavailableException` | 회차 즉시 중단, 5초 뒤 재시도. 준비 전이면 503 유지, 준비 뒤면 사본으로 조회 계속 | `StoreSyncInitStalled`(10분)·`StoreSyncReconcileFailing`(5분) | `SynchronizeCatalogProducts.java:80`, 결정 85 | 없음 |
| S10 다운로드 티켓 | 중복 전달 | 같은 구매자가 반복 요청 | 해당 없음 — 상태가 없다 | 요청마다 새 URL 발급(자연 멱등, Download R8.3) | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `ReadBuildDownload.java:22-29` | 없음 |
| S10 다운로드 티켓 | 순서 역전 | 해당 없음 — 이벤트를 받지 않고 요청마다 License → Catalog → 저장소를 같은 순서로 읽는다 | 해당 없음 — 뒤집힐 입력 순서가 없다 | 해당 없음 — 저장하는 상태가 없어 보상할 것이 없다 | 해당 없음 — 같은 이유 | Download R9.2, `ReadBuildDownload.java:23-28` | 없음 |
| S10 다운로드 티켓 | 늦은 도착 | 결제 직후 License 지급 전 요청 / 현재 참조를 읽은 뒤 v2 공개 확정 | License 응답 / Catalog 불변 공개본 | 403(지급 대기) / 이번 응답은 v1 값으로 완성(Download R4.6) | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | Download R3.8·R4.6 | 지급 대기와 미보유를 구분해 알리는 계약은 Download tasks `[결정 필요]`(모듈 스펙(Studio·Review·Catalog·Download)) |
| S10 다운로드 티켓 | 상대 상태가 이미 바뀜 | 발급 뒤 관리자 다운로드 제한 APPLIED / 이용권 SUSPENDED / 공개 객체 삭제 | 저장소 IAM 태그 거절 / 다음 발급의 License 확인 / HEAD | 제한은 기존 URL도 거절(APPLIED 뒤), SUSPENDED는 만료 전 URL 사용 가능(정책), 객체 없음 409 | 해당 없음 — 정책상 수용(Download R5.6·R8.6) | `ReadPublishedBuild.java:31-32`, Download R5·R6 | 없음 |
| S10 다운로드 티켓 | 동시 진행 | 발급 중 공개본 교체 / HEAD와 GET 사이 객체 교체 | 불변 공개본·고유 객체 키 | 한 응답은 한 공개본 값. HEAD~GET 사이 변조는 막지 않고 클라이언트가 SHA-256 대조(Download R6.12) | 해당 없음 — 처리 칸에서 끝나 남는 불일치가 없다 | `ReadPublishedBuild.java:33-39` | 없음 |
| S10 다운로드 티켓 | 상대 모듈 중단 | License·Catalog·저장소 중단 | HTTP 2초/5초, SDK 5초 제한 | 503, 미보유·없는 상품으로 바꾸지 않음, 재시도 없음 | `ServiceDown` | Download R9.3·R9.4 | 없음 |

### 흐름 2. 구매 (Order → Payment ↔ Order → License · Settlement)

#### 단계 목록
| 단계 | 주체 → 상대 | 수단 | 입력 | 결과(목표) | 멱등·동일성 키 | 요구사항 |
|---|---|---|---|---|---|---|
| S1 주문 생성 | MEMBER → Order (→ Catalog 내부 HTTP) | `POST /api/v1/orders`, Catalog `/internal/v1/order-products/{code}`(트랜잭션 밖) | `requestId`·`productCode`·기대 버전·기대 금액 | `purchase_order` `PENDING`, `expiresAt`, `OrderPlaced` Outbox | `(buyer_subject, request_id)` 유일 | P3.10-1·P1.4-3 |
| S2 결제 접수 | Order → Payment | `OrderPlaced` / `stove.order.v1`, 키 `orderNo` | 스냅샷·청구액·통화·`expiresAt` | `purchase_payment` `PENDING`(고정 `paymentId`) 또는 만료 사실이 먼저면 `EXPIRED` | Inbox `(event_id, consumer_group)`, `uk_purchase_payment_order` | P3.11-1 |
| S3 시도 접수 | 고객 → Payment | 결제창 정보 조회, `POST /api/v1/payments/{orderNo}/approvals` | 결제 공급자 인증 결과(`supplierAttemptId`·토큰) | `payment_approval` `REQUESTED`(본문 봉인) | `approvalId`(요청 ID), `supplierAttemptId` 유일, 결제당 비터미널 하나 | Payment 모듈 규칙 |
| S4 승인 진행 접수 | Payment → Order | 내부 HTTP `POST /internal/v1/orders/{orderNo}/payment-admissions`(`X-Payment-Service-Key`) | `admissionId`·연결값·`paymentId` | Order `PAYMENT_PROCESSING`+영수증 / `ORDER_EXPIRED` / 판매 제한 / 409 | `admissionId` 유일(같은 입력이면 최초 영수증) | P3.10-3·P3.11-3·P3.11-4 |
| S5 PG 확정·조회 | Payment ↔ PG (웹훅 PG → Payment) | PG 확정 송신·조회(adapter), 웹훅 | 봉인 본문, 고정 멱등 키 | PG 관측(`latestPgState`) | PG 멱등 키(공급자 보장 기한 안) | P3.11-5·P3.12-3 |
| S6 결과 확정·시도 실패 통지 | Payment (→ Order) | 결제 행 잠금 트랜잭션, 실패 통지 내부 HTTP `…/{admissionId}/failure` | 검증된 PG PAID/FAILED | 결제 `PAID`+시도 `SUCCEEDED`+`OrderPaymentCompleted` / 시도 실패 → Order `PENDING`·`EXPIRED` | 결제당 결과 하나(`resultEventId`), `supplierAttemptId`+`failedAt` | P3.12-1·P3.12-2·P3.12-4 |
| S7 승인 보류·종결 | Payment(작업자·ADMIN) → Order | `stopPosts`, ADMIN 종결 API, `OrderPaymentReviewClosed`(목표) | design "승인 보류 사유" 표 보류 조건, PG 재조회 근거 | 보류 / 시도 `FAILED`+결제 `FAILED`·`EXPIRED` | lease 토큰, 종결 `requestId`(`approval_closure` 유일) | P3.12-5·P3.13-1·P4.6-1·P3.13-2·P3.13-3·P3.13-4·P3.13-5·P3.13-6·P4.6-2 |
| S8 만료 통지 | Order → Payment | `OrderExpired` / `stove.order.v1` | 원 요청 연결·`expiresAt` | `payment_order_lifecycle` 만료 기록, 활성 시도가 없으면 결제 `EXPIRED`, 있으면 P3.13-6 | 만료 eventId(주문당 하나) | P3.10-5·P3.13-6·P3.13-1 |
| S9 결과 반영 | Payment → Order | `OrderPaymentCompleted`·`OrderPaymentFailed`·`OrderPaymentReviewClosed` / `stove.payment.v1` | 결과 + `admissionId`(목표) | 주문 `PAID`(+`OrderPaid`)·`FAILED`, 또는 거절(+`OrderPaymentResultRejected`, 결정 153) | Inbox `order-payment-result`, `uk(payment_result_event_id)` | P3.14-1·P3.14-2·P3.14-3·P3.14-4·P3.14-5 |
| S9-R 거절 사실 | Order → Payment | `OrderPaymentResultRejected` / `stove.order.v1` | 거절한 결과의 `eventId`·`paymentId`·`admissionId`·거절 사유 | 주문에 연결되지 않은 청구 기록(`UnlinkedCharge`, 출처 `ORDER_REJECTED`)으로 보류 목록에 올림(결정 154) | 거절한 결과 `eventId`(Order 거절 기록 유일, Payment Inbox) | P3.14-2·P3.13-7 |
| S10 구매 근거 | Order → License → Order | `OrderPaid` / `stove.order.v1`, 구매 근거 생성 사실(지금 `LicenseGranted`) / `stove.license.v1` | `OrderPaid` 13개 값 | `product_license` `STORED`, Order `order_license_grant` | Inbox `license-paid-order`·`order-license-grant`, `uk_product_license_order`·`_result`·`_payment` | P3.15-1·P3.15-2 |
| S11 매출 배분 | Order → Settlement | `OrderPaid` | 13개 값 + PG 승인·Catalog 소유자 조회 | `settlement_sale`·월 합계·PG 거래 | Inbox `settlement-paid-order`, `uk_sale_order_no`·`uk_sale_payment_result_event_id` | P3.15-3·P3.15-4 |

승인 보류 사유 8개(`PAID_WITHOUT_ADMISSION`·`PG_REQUEST_REJECTED`·`PG_INVALID_EVIDENCE`·`ORDER_ADMISSION_CONFLICT`·`ORDER_EXPIRY_CONFLICT`·`PG_CANCELLED`·`CONFIRM_WINDOW_EXPIRED`·`PG_PENDING_AT_ORDER_EXPIRY`)의 들어가는 조건·고객 안내·자동 해제·종결 결과 표는 Payment 한 모듈의 규칙이라 Payment 모듈 스펙으로 옮긴다(MC-P12). 모듈을 넘는 결과(종결의 주문 결과, 접수 전 시도의 만료)는 P3.13-4·6에 있다.

#### 주문 상태×결과 (목표, U1 반영)

| 주문 상태 | 결과 이벤트 | 가드 | 다음 | 지금 코드(`Order.java:152-172`)와 차이 |
|---|---|---|---|---|
| `PAYMENT_PROCESSING`(열린 영수증 A) | `OrderPaymentCompleted`(admissionId=A) | 연결값·`paymentId` 일치, Inbox 최초 | `PAID` + `OrderPaid` | 지금은 `admissionId`가 없어 `paymentId`만 대조(결제당 고정이라 시도를 구분 못 함) |
| `PAYMENT_PROCESSING`(A) | `OrderPaymentCompleted`(admissionId≠A) | 연결값 일치 | 그대로 + 거절 기록·`OrderPaymentResultRejected`(결정 153) | 지금은 같은 `paymentId`면 `PAID` |
| `PAYMENT_PROCESSING`(A) | `OrderPaymentFailed`(A) | 위와 같음 | `FAILED` | 같음 |
| `PAYMENT_PROCESSING`(A) | `OrderPaymentReviewClosed`(A) | 위와 같음 | `FAILED`(사유 운영 종결) | 지금은 `OrderPaymentFailed(REVIEW_CLOSED)` |
| `PENDING`(영수증 없음 또는 실패 종료) | `OrderPaymentCompleted` | 연결값 일치 | 그대로 + 거절 기록·`OrderPaymentResultRejected`(결정 153) | **지금은 `PAID`**(U1) |
| `PENDING` | `OrderPaymentFailed` | 연결값 일치 | `FAILED` | 같음(모의 PG 최종 거절의 경로, T13) |
| `PENDING` | `OrderPaymentReviewClosed`(접수 전 시도) | 연결값 일치, 종결 기록 최초 | `FAILED` | 지금은 `OrderPaymentFailed(REVIEW_CLOSED)`로 같은 결과 |
| `EXPIRED` | `OrderPaymentReviewClosed` | 연결값 일치 | 그대로 + 종결 기록 | 지금은 `ExpiredReviewClosure`(V31)·`Order.keepsExpiryFor` |
| `EXPIRED`·`FAILED` | `OrderPaymentCompleted` | 연결값 일치 | 그대로 + 거절 기록·`OrderPaymentResultRejected`(결정 153) | 지금은 409 → DLT(Payment는 `PAID`, 아무 데도 보이지 않음) |
| `PAID` | 다른 `OrderPaymentCompleted`(다른 `eventId`·`admissionId`) | 연결값 일치 | 그대로 + 거절 기록·`OrderPaymentResultRejected`(결정 153) | 지금은 409 → DLT |
| `PAID`·`FAILED` | 같은 결과 재전달 | 저장 결과와 같음 | 무변화(다른 성공은 위 행, 그 밖의 다른 결과는 409) | 같음 |

`purchase_order.status`에 허용 값 CHECK를 두고(`PENDING`·`PAYMENT_PROCESSING`·`PAID`·`FAILED`·`EXPIRED`), `status='PAID'`이면 결과를 낸 영수증 ID(`payment_admission_id`)가 NOT NULL이고 `order_payment_admission`을 가리키는 CHECK·FK를 둔다(P3.14-1의 DB 쪽 지킴, T1·MC-O1).

#### adapter 메모 (결제 공급자 규칙, 결정 140 — core 계약 아님)

core 계약에는 공급자 이름을 쓰지 않았다. 지금 쓰는 공급자(PortOne V2·토스페이 채널)의 규칙은 adapter와 설정에 둔다.

- PG 관측 상태 `READY`·`PENDING`·`PAID`·`FAILED`·`CANCELLED`(core `ApprovalObservation.State`)로의 변환: 공급자 `PAY_PENDING` → `PENDING` 등은 `PortOnePaymentApprovalGateway`가 한다.
- 확정 송신 거절 분류: 공급자의 형식 갖춘 400·403·일반 409 → `ApprovalPostRejected`(core 보류 사유 `PG_REQUEST_REJECTED`). 분류표는 adapter 소유.
- 멱등 보장 기한 3시간: 공급자 `Idempotency-Key` 보장 기간. adapter 설정 키로 두고 core에 기한 값만 넘긴다(P3.11-5, RC-14).
- 웹훅 서명(`HmacPortOneWebhookVerifier`)·이벤트 종류(`Transaction.*`)는 adapter. core에는 "조회 힌트" 하나로만 들어온다.
- 지금 core에 남은 공급자 상수: `"PORTONE_TEST"` 비교(`Payment.java:148`, `PaymentApproval.java:72`), 주석의 "PortOne's idempotency window"(`PaymentApproval.java:24`). MC-P3.

#### 단계별 실패 표

경로 줄임: `O/…` = `order:…`, `P/…` = `payment:…`, `L/…` = `license:…`, `S/…` = `settlement:…`. "공통 재시도·DLT"는 common messaging의 Kafka 소비 재시도와 DLT다.

| 단계 | 실패 종류 | 구체 사례 | 감지하는 곳 | 처리(재시도·멱등 키·보상) | 끝내 안 되면(운영자 개입·경보) | 지금 코드 | 목표와 차이 |
|---|---|---|---|---|---|---|---|
| S1 주문 생성 | 중복 전달 | 응답 유실 뒤 고객이 같은 `requestId`·같은 입력으로 다시 보냄 | `CreateOrder.existing` | `(buyer_subject, request_id)` 유일로 기존 주문 반환, 입력이 다르면 409 | 해당 없음 — 고객 요청 한 건으로 끝나고 남는 상태가 없음 | `O/core/service/CreateOrder.java:41`, `O/core/domain/Order.java:119-123` | 없음 |
| S1 주문 생성 | 순서 역전 | 해당 없음 — 동기 요청 하나이고 앞선 이벤트가 없다. Catalog 공개본 교체와의 선후는 `expectedContentVersion`·`expectedAmount` 대조로 판정한다 | `Order.create` | 기대 값 불일치 409, 고객이 새 `requestId`로 재주문 | 해당 없음 | `Order.java:60-63` | 없음 |
| S1 주문 생성 | 늦은 도착 | Catalog 내부 조회가 읽기 10초를 넘김 | Catalog HTTP 어댑터 타임아웃 | 503, 주문 미생성. 고객이 같은 `requestId`로 재요청 | 해당 없음 — 남는 상태 없음 | Order 설계 "외부 연동"(연결 3초·읽기 10초) | 없음 |
| S1 주문 생성 | 상대 상태가 이미 바뀜 | Catalog 조회 뒤 트랜잭션 전에 판매 중지(`STOPPED`)·공개본 교체 | 주문 시점에는 감지 안 함. 승인 접수(S4)에서 Catalog 판매 상태를 다시 봄 | 주문은 조회 시점 스냅샷으로 고정. 판매 중지는 S4에서 `PRODUCT_SALE_RESTRICTED` | 해당 없음 — S4가 막음 | `CreateOrder`(트랜잭션 밖 조회), Order 설계 상태×이벤트 표 승인 접수 행 | 없음 |
| S1 주문 생성 | 동시 진행 | 같은 `requestId` 두 요청이 동시에 저장 | DB 유일 제약 충돌 | 충돌을 잡아 이미 저장된 주문을 다시 읽어 반환 | 해당 없음 | `CreateOrder.java:51-74`(`DataIntegrityViolationException` 뒤 `existing`) | 없음 |
| S1 주문 생성 | 상대 모듈 중단 | Catalog 중단 / Kafka 중단 | Catalog: 연결 실패 503. Kafka: Outbox 릴레이 실패 | Catalog: 주문 미생성. Kafka: Outbox `PENDING` 보존 뒤 재발행 | Outbox `DEAD` → 경보 `OutboxEventsAbandoned`, 운영자 재큐잉 | `tools/verification/verify-order.py` 6단계(Kafka 중단 중 Outbox 보존) | 없음 |
| S2 결제 접수 | 중복 전달 | `OrderPlaced` 재전달 | `ReceiveOrderPayment` Inbox(`payment-order-request`) | 같은 결제·같은 내용이면 무변화, 다르면 `CONFLICT` | DLT → 원인 확인 뒤 원본 재투입 | `P/core/service/ReceiveOrderPayment.java:20`, Payment 설계 상태×이벤트 표 첫 행 | 없음 |
| S2 결제 접수 | 순서 역전 | DLT 재투입·Outbox 재큐잉으로 `OrderExpired`가 `OrderPlaced`보다 먼저 옴 | `payment_order_lifecycle.expired()` | 만료 사실을 먼저 기록하고, 늦은 `OrderPlaced`는 결제를 `EXPIRED`로 만든다(시도 없음) | 해당 없음 — 주문·결제 모두 만료로 끝남 | `ReceiveOrderPayment` → `Payment.expireUnpaid`(Payment R11.3) | 없음. 단 lifecycle이 `occurredAt + 30분`을 다시 계산함(T12) |
| S2 결제 접수 | 늦은 도착 | 릴레이 지연으로 `OrderPlaced`가 결제 기한 직전·뒤에 도착 | 지금: 결제창 조회가 Payment 시계로 `orderOccurredAt + 30분` 비교. 목표: 감지하지 않음 | 목표: 결제를 `PENDING`으로 만들고 기한 판정은 S4의 Order에 맡김. 기한 뒤면 `OrderExpired`가 곧 온다 | 해당 없음 — 주문 만료로 끝남 | `P/core/service/ReadPaymentCheckout.java:50` | 원칙 ⑥ 위반(T12). 결제창 안내는 [결정 필요 PD-17] |
| S2 결제 접수 | 상대 상태가 이미 바뀜 | `OrderPlaced` 처리 때 주문은 이미 `EXPIRED`(만료 사실은 아직 미도착) | 감지하지 않음 | 결제 `PENDING` 생성 → `OrderExpired` 도착 때 활성 시도가 없으면 `EXPIRED` | 해당 없음 | `ApplyOrderExpiry.execute`(Payment R11.1·R11.4) | 없음 |
| S2 결제 접수 | 동시 진행 | 파티션 재조정 중 두 인스턴스가 같은 `OrderPlaced`를 처리 | Inbox `uk_processed_event`, `uk_purchase_payment_order` | 늦은 쪽 롤백 → 재시도 때 재전달로 판정해 무변화 | 해당 없음 | 네 모듈 V1 `uk_processed_event`, Payment `uk_purchase_payment_order`(I8) | 없음 |
| S2 결제 접수 | 상대 모듈 중단 | Payment 중단·DB 연결 대기 | Kafka 소비 지연(소비 지연 게이지) | Kafka가 보존, 기동 뒤 소비. 부팅 때 첫 DB 연결을 60초 기다림(결정 136) | 소비 지연 경보. 그 사이 주문은 기한이 지나면 `EXPIRED`(고객 미결제로 끝남) | `spring.datasource.hikari.initialization-fail-timeout`(결정 136), 경보 규칙 (미확인) | 없음 |
| S3 시도 접수 | 중복 전달 | 고객이 같은 승인 요청(`requestId`)을 다시 보냄 | `PaymentApproval.requireSameInput` | 같은 내용이면 기존 시도 반환, 다르면 `CONFLICT`. `supplierAttemptId` 재사용도 `CONFLICT` | 해당 없음 | `P/core/domain/PaymentApproval.java:89-94` | 없음 |
| S3 시도 접수 | 순서 역전 | 고객 승인 요청이 Payment의 `OrderPlaced` 소비보다 먼저 도착 | `RequestPaymentApproval`의 결제 조회 | 404, 고객이 결제창에서 다시 시도 | 해당 없음 — PG는 READY(확정 송신 전이라 돈이 나가지 않음) | `P/core/service/RequestPaymentApproval.java:39`(`NOT_FOUND`) | 없음 |
| S3 시도 접수 | 늦은 도착 | 고객이 기한 직전 인증하고 승인 요청이 기한 뒤 도착 | S4의 Order 기한 판정 | 시도 `REQUESTED` 저장 → S4에서 `ORDER_EXPIRED` → 시도·결제 `EXPIRED` | 해당 없음 — 돈이 나가지 않음 | `RequestPaymentApproval`에는 기한 검사 없음(`clock`은 접수 시각만), 결제창 단계만 Payment 시계로 막음 | 목표와 같음(결제창 단계 제외, PD-17) |
| S3 시도 접수 | 상대 상태가 이미 바뀜 | 시도 접수 직후 주문이 만료되고 `OrderExpired` 도착 | 승인 작업자의 `orderExpired` | P3.13-6(관측 이력 없으면 PG 호출 없이 닫음, 있으면 한 번 조회) | 보류(`PG_PENDING_AT_ORDER_EXPIRY` 등) → ADMIN 종결 | `P/core/service/ResolvePaymentApproval.java:101-108,177-184` | 없음 |
| S3 시도 접수 | 동시 진행 | 고객이 두 탭에서 동시에 승인 요청 | `uk_payment_approval_active`(V29 생성 열 `active_guard`) | 하나만 저장, 다른 쪽 409 `APPROVAL_IN_PROGRESS` | 해당 없음 | `payment/db/V29__payment_approval.sql:25` | 없음 |
| S3 시도 접수 | 상대 모듈 중단 | 결제 공급자 결제창 중단 / Payment 중단으로 인증 뒤 승인 요청이 안 들어옴 | 감지하지 않음(시도가 없음) | 고객 재시도. PG는 READY로 남고 돈은 나가지 않음(서버 확정 방식) | 해당 없음 — 주문은 기한에 만료 | (미확인: 공급자 쪽 READY 만료 시간) | 없음 |
| S4 승인 진행 접수 | 중복 전달 | 접수 응답 유실 뒤 Payment가 같은 `admissionId`로 재요청 | `OrderPaymentAdmission.requireSameInput` | 최초 영수증 반환 | 해당 없음 | `O/core/service/RequestOrderPaymentProcessing.java:33-37` | 없음 |
| S4 승인 진행 접수 | 순서 역전 | 접수 요청과 만료 스캔이 엇갈림: Payment에 `OrderExpired`가 접수 영수증보다 먼저 옴 | Order: 주문 행 잠금. Payment: `orderExpired` 검사 | Order가 잠금 아래 먼저 커밋한 쪽이 이긴다. 접수가 이겼으면 만료는 없다. 만료가 이겼으면 빈 영수증 → 시도·결제 `EXPIRED` | 해당 없음 | `RequestOrderPaymentProcessing.java:31,38-42`, `ResolvePaymentApproval.java:133-140` | 없음 |
| S4 승인 진행 접수 | 늦은 도착 | Payment 작업자 지연(대기열 밀림·Order 중단)으로 기한 뒤 접수 요청 | Order `beginPaymentProcessing`의 기한 판정 | Order `EXPIRED`+`OrderExpired` 커밋 뒤 409 `ORDER_EXPIRED` → 시도·결제 `EXPIRED`. PG가 PAID면 `PAID_WITHOUT_ADMISSION` 보류 | PAID 보류 → 운영자 콘솔 취소 → 종결(결정 114·115) | `O/core/domain/Order.java:87-90`, `HttpOrderPaymentAdmissions.java:50-51` | 없음 |
| S4 승인 진행 접수 | 상대 상태가 이미 바뀜 | 접수 때 관리자 판매 금지(`BLOCKED`) | Order 접수 전 Catalog 조회 | 409 `PRODUCT_SALE_RESTRICTED` → 시도는 접수 전으로 남아 다음 회차 재요청 | 판매 재개가 없으면 주문 기한 만료 → P3.13-6 | `HttpOrderPaymentAdmissions.java:52-54`, `PaymentApproval.recordSaleRestriction`(`PaymentApproval.java:140-144`) | 대기가 `REQUESTED` + `lastErrorCode`로 숨은 하위 상태(원칙 ④, RC-2) |
| S4 승인 진행 접수 | 동시 진행 | 같은 주문에 승인 접수와 결과 반영·만료 스캔이 동시에 | 주문 행 잠금(`lockByOrderNo`) | 직렬화. 접수 뒤 만료 스캔은 `PAYMENT_PROCESSING`이라 무변화 | 해당 없음 | `RequestOrderPaymentProcessing.java:31`, `Order.expire`(`Order.java:106-112`) | 없음 |
| S4 승인 진행 접수 | 상대 모듈 중단 | Order 중단·타임아웃·5xx | `HttpOrderPaymentAdmissions` | 시도 무변화, 5초(`PAYMENT_RETRY_MS`) 뒤 같은 `admissionId`로 재요청. 기한이 지나면 Order 복구 뒤 만료 스캔 → `OrderExpired` | 해당 없음 — 만료로 끝남. 다만 접수 전 시도 체류 게이지는 없음 | `HttpOrderPaymentAdmissions.java:57,62`(Order 장애를 `PgUnavailable`로 던짐) | 오류 분류가 PG 장애와 섞임(원칙 ①, MC-P6) |
| S5 PG 확정·조회 | 중복 전달 | 확정 송신 응답 유실 → 재송신 / 같은 웹훅 재전달 | PG 멱등 키 / 웹훅 수신 기록 | 같은 멱등 키·처음 저장한 본문으로 재송신(기한 안). 웹훅은 같은 ID·본문이면 무변화 | 기한 넘으면 `CONFIRM_WINDOW_EXPIRED` 보류 | `PaymentApproval.canPost`(`PaymentApproval.java:150-154`), `JdbcPortOneWebhookReceipts` | 기한 값이 core 상수(MC-P2) |
| S5 PG 확정·조회 | 순서 역전 | 웹훅이 확정 송신 응답보다 먼저 도착 | 웹훅은 조회 힌트 | 다음 서버 조회를 앞당길 뿐 상태를 바꾸지 않음 | 해당 없음 | `ReceivePortOneWebhook.java:37-39` → `Payment.requestRecheck` | 없음 |
| S5 PG 확정·조회 | 늦은 도착 | 확정 응답 유실 뒤 PG가 3시간 넘게 PENDING | 작업자 조회 직후 `holdAfterConfirmWindow` | `CONFIRM_WINDOW_EXPIRED` 보류. 늦은 PAID는 원래 주문 `PAID`, 늦은 FAILED는 시도 실패 통지 | ADMIN 목록·게이지·경보, 근거 종결 | `PaymentApproval.java:161-168`, `ResolvePaymentApproval.java:189-192` | 없음 |
| S5 PG 확정·조회 | 상대 상태가 이미 바뀜 | 운영자가 공급자 콘솔에서 승인하거나 전액 취소함 | 작업자 조회 | 접수 전 PAID → `PAID_WITHOUT_ADMISSION`, 전액 CANCELLED → `PG_CANCELLED` 보류(저절로 닫지 않음) | ADMIN 종결 | `ResolvePaymentApproval.java:128-131,258-264` | 없음 |
| S5 PG 확정·조회 | 동시 진행 | 승인 작업자 회차와 결제 재개 작업(`PendingPaymentScheduler`, 웹훅이 앞당김)이 같은 결제를 동시에 처리 | 작업자는 lease, 결제 재개는 결제 행 잠금 | 작업자끼리·종결과는 lease로 배제. 결제 재개는 lease 없이 PG를 조회하고 확정 트랜잭션에서 결제만 잠금 | 해당 없음 — 목표에서는 확정 트랜잭션이 시도를 다시 확인 | `ResolvePayment.java:103,112-119`, `AdmittedPortOnePgGateway.java:21-28`(잠금 밖에서 시도 조회) | 확정 때 시도 재확인 없음(T1, 추정 경합) |
| S5 PG 확정·조회 | 상대 모듈 중단 | 결제 공급자 중단·조회 실패 | 조회 예외(`PgUnavailable`) | 상태 유지, 조회 실패 기록(`lastInspectionError`), 다음 회차 재조회. 보류 건은 최대 10분 간격 | 3시간 뒤 접수 시도는 보류 → ADMIN | `ResolvePaymentApproval.java:193-212` | 없음 |
| S6 결과 확정·시도 실패 통지 | 중복 전달 | 같은 PG 결과를 두 번 확정 / 실패 통지 재전송 | `Payment.confirm`의 `supplierResult` 대조 / `OrderPaymentAdmission.requireFailure` | 같으면 무변화(Outbox 없음), 다르면 409. 실패 통지는 최초 영수증 반환 | 다른 결과 409 → 결제 미확정 유지·로그 | `P/core/domain/Payment.java:122-125`, Order 설계 영수증 표 | 없음 |
| S6 결과 확정·시도 실패 통지 | 순서 역전 | 같은 결제에서 이전 시도의 실패 통지와 다음 시도의 접수가 엇갈림 | 결제당 비터미널 시도 하나(`FAILURE_CONFIRMED`가 막음), Order "최신 영수증" 검사 | 실패 통지가 끝나 시도가 `FAILED`가 되기 전에는 새 시도를 받지 않음 | 해당 없음 | `uk_payment_approval_active`, `RecordOrderPaymentAttemptFailure`(최신 아님 409) | 없음 |
| S6 결과 확정·시도 실패 통지 | 늦은 도착 | 시도 실패 통지가 결제 기한 뒤 Order에 도착 | Order `releaseFailedPaymentProcessing`의 기한 판정 | 주문 `EXPIRED`+`OrderExpired`, 영수증 `closedOrderStatus=EXPIRED` → 결제 `EXPIRED` | 해당 없음 | `O/core/domain/Order.java:97-104`, `ResolvePaymentApproval.java:235-245` | 없음 |
| S6 결과 확정·시도 실패 통지 | 상대 상태가 이미 바뀜 | 실패 통지 때 주문에 이미 결과가 있거나 영수증이 최신이 아님 | Order 409 | Payment가 `ORDER_ADMISSION_CONFLICT` 보류(`BusinessException` catch) | ADMIN 조사·종결 | `ResolvePaymentApproval.java:84`, `Order.java:99-100` | 없음 |
| S6 결과 확정·시도 실패 통지 | 동시 진행 | 결제 `PAID` 확정과 보류 종결이 동시에 | 종결은 잠금 안에서 결제 미확정 재확인(`CLOSURE_PAYMENT_RESOLVED`). `PAID` 커밋은 같은 트랜잭션에서 접수된 보류를 `SUCCEEDED` | 하나만 이김. `PAID`가 이기면 종결 409 | 해당 없음 | `ResolvePayment.java:160-184`(D-051), Payment 설계 종결 행 | 없음 |
| S6 결과 확정·시도 실패 통지 | 상대 모듈 중단 | 실패 통지 중 Order 중단 / 결과 Outbox 발행 중 Kafka 중단 | 통지 HTTP 실패 / Outbox 릴레이 | 시도 `FAILURE_CONFIRMED` 유지, 다음 회차에 같은 통지 재전송(R4.14). Outbox는 보존 | Outbox `DEAD` 경보·재큐잉 | `ResolvePaymentApproval.java:97-99` | 없음 |
| S7 승인 보류·종결 | 중복 전달 | ADMIN이 같은 종결 요청(`requestId`)을 다시 보냄 / `OrderPaymentReviewClosed` 재전달 | `approval_closure`(approvalId·requestId 유일) / Order Inbox | 최초 영수증 반환 / Order 무변화 | 해당 없음 | `ApprovalClosure.record`(V37). Order 쪽은 지금 `OrderPaymentFailed` 재전달 대조 | 이벤트 분리(RC-1) |
| S7 승인 보류·종결 | 순서 역전 | 종결 결과가 Order에 닿기 전에 주문이 스스로 `EXPIRED` | Order 결과 반영 | 만료 유지 + 종결 사실 기록(P3.13-4) | 해당 없음 — 끝은 같은 "새 주문 안내" | `O/core/service/ApplyPaymentResult.java:47,65-72`, `Order.keepsExpiryFor`(`Order.java:147-150`) | 실패 이벤트에 종결을 실어 예외 분기를 만듦(원칙 ①, RC-1) |
| S7 승인 보류·종결 | 늦은 도착 | 근거 READY로 종결한 뒤 콘솔 승인 등으로 PG가 PAID가 됨 | 지금: 감지하지 않음(웹훅이 와도 `PENDING`이 아니면 무시) | 목표: 끝난 결제를 공급자 보장 기한까지 주기 조회하고 웹훅이 오면 조회를 앞당김. `PAID`면 결제를 다시 열지 않고 `UnlinkedCharge`(출처 `LATE_CHARGE`) 기록·보류 목록·경보·안내 청구됨([결정 154](../../../docs/decisions.md#unlinked-charge-discovery), P3.13-8·9) | 운영자가 공급자 콘솔에서 취소한 뒤 PG 근거로 기록 종결. 공급자 기한 뒤 청구는 G7 정산 대사가 마지막 안전망. 지금은 운영자가 공급자 콘솔에서만 알 수 있음 | `P/core/domain/Payment.java:174`(`requestRecheck`가 `PENDING` 아니면 반환), `ResolvePayment.java:89` | 청구된 돈이 주문·보류 어디에도 연결되지 않음(원칙 ⑤) |
| S7 승인 보류·종결 | 상대 상태가 이미 바뀜 | 종결 요청 사이 PG가 PAID로 바뀜 | 종결의 트랜잭션 밖 재조회 | 409 `CLOSURE_PG_PAID`, 보류 유지·다음 조회 시각 복원 | 운영자가 콘솔에서 먼저 취소 | `CloseApprovalReview.refusal`·`release`(Payment 설계 종결 거절 행) | 없음 |
| S7 승인 보류·종결 | 동시 진행 | 작업자 회차와 ADMIN 종결이 같은 시도에 | lease(`claimForClosure`) | 살아 있는 lease면 409 `APPROVAL_CLOSURE_BUSY`, 잠금 안에서 lease 소유 재확인 | 해당 없음 — ADMIN 재시도(10분 간격이라 드묾, 결정 122) | `PaymentApproval.java:106-120` | 없음 |
| S7 승인 보류·종결 | 상대 모듈 중단 | 종결 중 PG 조회 실패 / 종결 결과 발행 중 Order 중단 | 재조회 예외 / Outbox | 409 `CLOSURE_PG_UNAVAILABLE`, 보류 유지 / Outbox 보존 | 보류 게이지·경보 유지 | Payment 설계 종결 거절 행 | 없음 |
| S8 만료 통지 | 중복 전달 | `OrderExpired` 재전달 | `payment_order_lifecycle`(같은 만료 eventId·시각) + Inbox(`payment-order-expiry`) | 무변화, 다르면 `CONFLICT` | DLT | `P/core/domain/PaymentOrderLifecycle.java:39-44` | 없음 |
| S8 만료 통지 | 순서 역전 | `OrderExpired`가 `OrderPlaced`보다 먼저(S2) 또는 접수 영수증 처리보다 먼저(S4) | lifecycle 기록 | S2·S4 행과 같음 | 해당 없음 | 위 행들 | 없음 |
| S8 만료 통지 | 늦은 도착 | `OrderExpired`가 늦게 와서 그동안 접수 전 시도가 계속 조회됨 | 감지하지 않음 | 조회만 하고 송신은 접수가 막음(접수가 Order에서 거절됨) | 해당 없음 | `ResolvePaymentApproval.java:101-140` | 없음 |
| S8 만료 통지 | 상대 상태가 이미 바뀜 | 이미 `PAID`·`FAILED`로 확정된 결제에 만료 도착 | `ApplyOrderExpiry` | 결제 유지, `financialConflict=true` 표시만 | 지금: 경보 없음. 목표: 접수 필수(P3.11-2)면 생기지 않아야 하므로 생기면 경보 | `P/core/service/ApplyOrderExpiry.java:45` | 표시만 있고 지표·경보 없음(adapter 검색 0건). T13이 고쳐지면 불변식 위반 신호로 경보 |
| S8 만료 통지 | 동시 진행 | 만료 수신과 승인 작업자 회차가 같은 결제에 | 결제 행 잠금 | 활성 시도가 있으면 결제를 만료시키지 않고 작업자가 P3.13-6로 처리 | 해당 없음 | Payment 설계 `OrderExpired` 수신 행 | 없음 |
| S8 만료 통지 | 상대 모듈 중단 | Payment 중단 / 기한 규칙이 바뀌어 Payment가 `OrderExpired`를 거절 | 소비 지연 / `requireTerms` 409 | 재시도 / 지금은 재시도 뒤 DLT | DLT 경보 `MessagesDeadLettered` | `PaymentOrderLifecycle.java:36,48`(`occurredAt + 30분`과 `expiresAt` 대조) | 기한 복제가 만료 사실을 DLT로 보냄(T12) |
| S9 결과 반영 | 중복 전달 | `OrderPaymentCompleted` 재전달 | `ApplyPaymentResult`(결과 eventId 교차 조회·Inbox `order-payment-result`) | 저장 결과와 같으면 무변화, 다르면 409 | DLT → 거래 기록 대조 | `O/core/service/ApplyPaymentResult.java:38-50`, `Order.java:154-158` | 없음 |
| S9 결과 반영 | 순서 역전 | 같은 주문의 결과와 만료가 엇갈림(DLT 재투입으로 결과가 늦게 다시 옴) | 주문 행 잠금 | 접수된 주문은 만료되지 않으므로 결과가 이김. 접수 없는 결과는 아래 두 행 | 해당 없음 | `Order.expire`는 `PENDING`만(`Order.java:108`) | 없음 |
| S9 결과 반영 | 늦은 도착 | **U1(a)**: 기한이 지났지만 스캔(1초 간격)·Order 중단 때문에 아직 `PENDING`인 주문에 성공 결과가 옴 | 지금: 감지하지 않음 | 목표: 열린 영수증이 없으므로 `PAID`로 반영하지 않음(P3.14-2). Order가 거절 기록과 `OrderPaymentResultRejected`를 내고 Payment가 보류로 올림([결정 153](../../../docs/decisions.md#late-success-rejection)) | 목표: Payment 보류 목록·경보·고객 안내(P3.13-7) | `O/core/domain/Order.java:152-172`(`PENDING`이면 기한·영수증 대조 없이 163행을 지나 165-166행에서 `PAID`). 모의 PG는 접수 없이 결과를 냄(T13) | `PAID`·`OrderPaid`·구매 근거·매출이 접수 근거 없이 생김(T1) |
| S9 결과 반영 | 상대 상태가 이미 바뀜 | **U1(b)**: 시도 실패 통지로 `PENDING`에 돌아온 주문(`paymentId=null`, 영수증 실패 종료)에 이전 시도의 늦은 성공이 옴. 같은 결제라 `paymentId`가 같다. **U1(c)**: `EXPIRED`·`FAILED` 주문에 성공이 옴 | 지금: (b) 감지하지 않음, (c) 409 | 목표: (b)·(c) 모두 `PAID`로 반영하지 않음(P3.14-2). Order 거절 기록 + `OrderPaymentResultRejected` → Payment 보류([결정 153](../../../docs/decisions.md#late-success-rejection)). Payment는 실패 통지한 시도의 성공을 애초에 내지 않음(P3.12-5) | 목표: Payment 보류 목록·경보·고객 안내(P3.13-7). 지금 (c)는 DLT, Payment는 `PAID`인데 보류·경보 없음 | (b) `Order.java:159-166`(`PENDING` 분기에 대조 없음), (c) `Order.java:161-162`. Payment가 (b)를 낼 수 있는 경로: 잠금 밖 시도 확인(T1, 추정)·모의 PG(T13) | (b) 근거 없는 `PAID`, (c) 청구된 돈이 DLT에만 남음 |
| S9 결과 반영 | 동시 진행 | 결과 반영과 승인 접수·실패 통지·만료 스캔이 같은 주문에 | 주문 행 잠금 | 직렬화 | 해당 없음 | `ApplyPaymentResult.java:36`(`lockByOrderNo`) | 없음 |
| S9 결과 반영 | 상대 모듈 중단 | Order 중단 / 처리 예외 반복 | Kafka 소비 지연 / 공통 재시도 | Kafka 보존, 기동 뒤 반영. 주문은 `PAYMENT_PROCESSING`이라 만료되지 않음 | DLT → 원본 재투입(Order 설계 "이벤트 복구"). 모듈 간 누락 게이지 없음(U3, PD-6) | Order 설계 "사용 안내" 이벤트 복구 | 누락 검사기 없음(T6) |
| S9-R 거절 사실 | 중복 전달 | 같은 `OrderPaymentResultRejected` 재전달 / 같은 결과의 재전달로 Order가 거절을 다시 판정 | Payment Inbox / Order 거절 기록 유일 키(거절한 결과 `eventId`) | Payment 무변화. Order는 거절 기록이 있으면 새 거절 사실을 쓰지 않음 | 해당 없음 — 사실이 하나로 남는다 | 없음(목표 이벤트) | T1 |
| S9-R 거절 사실 | 순서 역전 | 해당 없음 — 거절은 Payment가 이미 커밋한 결과를 Order가 받은 뒤에만 생긴다(인과). 같은 결제의 `OrderExpired`와 엇갈려도 Payment는 만료 기록과 무관하게 보류로 올린다 | — | — | — | 없음(목표 이벤트) | T1 |
| S9-R 거절 사실 | 늦은 도착 | 운영자가 PG 콘솔 취소 뒤 이미 그 결제를 종결한 다음 거절 사실이 옴 | Payment 결제 행 잠금 뒤 보류·종결 기록 확인 | 거절 사실만 기록하고 종결 결과를 바꾸지 않음(결정 115·144) | 보류 목록에 사실 이력으로 보임 | 없음(목표 이벤트) | T1·MC-P13 |
| S9-R 거절 사실 | 상대 상태가 이미 바뀜 | 그 결제가 이미 다른 사유로 보류 중 | Payment 결제·시도 잠금 | 처음 보류 사유를 유지하고 거절 사실을 추가로 기록(결정 144). 보류가 없으면 새로 올림 | 보류 목록·경보 | 없음(목표 이벤트) | T1·MC-P13 |
| S9-R 거절 사실 | 동시 진행 | 거절 처리와 같은 결제의 웹훅·재조회·ADMIN 종결이 동시에 | Payment 결제 → 시도 잠금 순서(P3.12-1) | 직렬화 | 해당 없음 — 잠금으로 끝난다 | 없음(목표 이벤트) | T1 |
| S9-R 거절 사실 | 상대 모듈 중단 | Payment 중단 / 처리 예외 반복 | Kafka 소비 지연 / 공통 재시도 | Kafka 보존, 기동 뒤 반영. 그 사이 고객은 Order 안내만 봄 | DLT·경보, 키 정지·키 단위 재투입(결정 155), 보존·경보 기준은 PD-8 | 없음(목표 이벤트) | T1 |
| S10 구매 근거 | 중복 전달 | `OrderPaid` 재전달 / 구매 근거 생성 사실 재전달 | `GrantLicense.verifyRedelivery`(13개 값) / Order Inbox(`order-license-grant`) | 같으면 무변화, 다르면 `CONFLICT` | DLT | `L/core/service/GrantLicense.java:32-35,52-56` | 없음 |
| S10 구매 근거 | 순서 역전 | 해당 없음(이 흐름 안) — `OrderPaid`는 주문당 하나이고 앞선 License 입력이 없다. 지급 전 정지 요청의 역전은 흐름 3(License 설계 찾은 불일치 7) | — | — | — | — | F 경계 3 |
| S10 구매 근거 | 늦은 도착 | `OrderPaid` 전달 지연으로 고객 보관함이 비어 있음, 그 사이 환불 접수 | Order `PENDING_LICENSE_GRANT` 진입 시각 | 늦게 와도 그대로 생성. 대기 환불은 지급 확인 뒤 정지 요청 | 10분 경계 게이지·경보 `RefundLicenseGrantsDelayed`(결정 125). 환불이 없으면 경보 없음(U3) | Order 설계 환불 처리 요약 | 환불 없는 지급 지연은 보이지 않음(PD-6) |
| S10 구매 근거 | 상대 상태가 이미 바뀜 | 같은 고객이 같은 상품을 이미 보유(다른 주문) | `License.register`의 고객·상품 잠금 | 구매 근거는 `STORED`로 생성, 등록 때 409 `PRODUCT_ALREADY_OWNED` | 해당 없음 — 고객이 환불 신청 가능 | License 설계 구매 근거 표(결정 89·93) | 없음 |
| S10 구매 근거 | 동시 진행 | 같은 상품 두 주문의 `OrderPaid`를 동시에 처리 | 각자 다른 `orderNo` 행 | 둘 다 `STORED`. 등록은 고객·상품 잠금으로 하나만 `ACTIVE`(`uk_license_registration_active`) | 해당 없음 | License 설계, I3 | 없음 |
| S10 구매 근거 | 상대 모듈 중단 | License 중단 / `LicenseGranted` 원본이 Kafka에서 사라짐 | 소비 지연 / 결정 125 게이지(환불 대기만) | Kafka 보존 / `SENT` 원본은 같은 ID 재발행(`outbox_republish`, 결정 129) | DLT 재투입·DEAD 재큐잉(결정 37 런북) | License 설계 "오류·복구" | U3 검사기 없음(PD-6) |
| S11 매출 배분 | 중복 전달 | `OrderPaid` 재전달 | `RecordPaidSale.existing`(13개 값·Inbox) | 무변화, 다르면 409 | DLT | `S/core/service/RecordPaidSale.java:53-54,65-73` | 없음 |
| S11 매출 배분 | 순서 역전 | 환불 확정(`RefundPaymentConfirmed`)이 매출보다 먼저 Settlement에 옴 | 환불 정산의 원매출 조회(`ConfirmedRefund.requireSale`) | 목표: 환불 확인은 "매출 대기 환불"로 저장되고, 늦게 온 매출을 기록하는 트랜잭션에서 함께 반영(결정 156). 지금은 환불 쪽이 거절·재시도 | 대기 게이지. 지금은 DLT → 원매출 복구 뒤 재전달 | Settlement 설계 "DLT로 간 매출·환불은 원인 해소(환불은 원매출 복구) 뒤" | F 경계 3 |
| S11 매출 배분 | 늦은 도착 | 귀속 월이 마감된 뒤 `OrderPaid` 도착 | 판매자 잠금 뒤 마감 월 확인 | 현재 KST `OPEN` 월에 반영, `saleMonth` 보존(결정 42·130) | 현재 월도 `CLOSED`면 409 → 재시도·DLT | `RecordPaidSale.java:78-91` | 귀속 시각이 Order의 `OrderPaid.occurredAt`(F 경계 4) |
| S11 매출 배분 | 상대 상태가 이미 바뀜 | PG 승인 조회 결과나 Catalog 소유자가 주문과 다름 | `PgPayment.requireOrder`·`ProductOwner.requireOrder` | 원장 없이 `CONFLICT` → 공통 재시도 | DLT → 운영 조사 | `RecordPaidSale.java:55-56` | 없음 |
| S11 매출 배분 | 동시 진행 | 같은 판매자의 매출 반영과 월 마감이 동시에 / 유일 키 충돌 | `SellerLockRepository.lock` / DB 유일 제약 | 직렬화 / 5회 재시도 | 409 → DLT | `RecordPaidSale.java:57-62,105-107` | 없음 |
| S11 매출 배분 | 상대 모듈 중단 | 결제 공급자·Catalog 조회 실패 / Settlement 중단 | 조회 예외 / 소비 지연 | 원장 없이 예외 → 공통 재시도 / Kafka 보존 | DLT → 원본 재투입. 모듈 간 누락 게이지 없음 | `RecordPaidSale.java:55-56`, Settlement 설계 상태 표 89행 | U3(PD-6) |

### 흐름 3. 환불 (Order → License → Payment ↔ PG → Order → License · Settlement)

#### 단계 목록
```
S1  고객 → Order            접수·자격(자동 4시간 / 심사)
S2  License → Order         지급 확인(LicenseGranted)          [PENDING_LICENSE_GRANT]
S3  Order → License         정지 요청(LicenseSuspensionRequested)
S4  License → Order         정지 완료(LicenseSuspended) → 시도·실행 요청 저장
S5  Order → Payment         실행 접수(RefundExecutionRequested)
S6  Payment ↔ PG → Order    첫 송신 준비·송신(RefundExecutionPrepared)
S7  Payment ↔ PG            재송신·조회·보류·재개·3시간 창
S8  Payment → Order         금융 완료·귀속(RefundPaymentConfirmed / RefundExecutionSucceeded) → COMPLETED + PurchaseRefunded
S9  Payment → Order         PG 확정 실패(RefundExecutionFailed) → PENDING_LICENSE_RESTORATION
S10 ADMIN → Payment → Order 운영자 실패 종결(RefundFailureClosed) → PENDING_LICENSE_RESTORATION
S11 Order → License → Order 복구(LicenseRestorationRequested / LicenseRestored) → FAILED → 고객 재신청(S3로)
S12 Order → License         회수(PurchaseRefunded) → REFUNDED
S13 Payment → Settlement    환불 조정(RefundPaymentConfirmed) → 월 반영 → 마감 이월
S14 ADMIN → Payment → Order · Settlement  늦은 취소 반영(RefundLateCancellationConfirmed) → 늦은 취소 완료 + PurchaseRefunded → License 회수, Settlement 조정 (결정 157)
```

분기: S1 심사 반려·재심사, S3~S4 사이 승인 중단(P3.27), S7 → S8/S9/S10 중 하나, S8 뒤 결정 127 ADMIN 종결(귀속 판정만), S9·S10 뒤 늦은 전액 취소가 실제로 나타나면 ADMIN 근거 반영 S14(결정 157).

#### Order 환불 상태와 흐름 단계

시도(`RefundAttempt`)·실행(`RefundExecution`)·구매 근거의 목표 상태 모델은 모듈 스펙 몫이다(MC-O2·MC-P8·MC-P11, License `suspendedFrom`은 RC-17). 시도가 귀속(`UNATTRIBUTED`/`SUCCEEDED`)을 가질지는 [결정 필요 PD-12].

**Order 환불 `Refund.status`(`order_refund`)** — 지금 enum 그대로, CHECK 추가.

| 상태 | 들어오는 트리거 | 나가는 트리거 | 고객 상태 |
|---|---|---|---|
| `PENDING_REVIEW` | 기한 밖 접수, 재심사, 승인 중단 뒤 정정 회차 | 판정 | `PENDING_REVIEW` |
| `PENDING_LICENSE_GRANT` | 허용 + 지급 근거 없음 | `LicenseGranted`, 승인 중단 | `PROCESSING` |
| `PENDING_LICENSE_SUSPENSION` | 허용 + 지급 근거 있음 / `LicenseGranted` / 고객 재신청 | `LicenseSuspended`, 승인 중단 | `PROCESSING` |
| `PENDING_APPROVAL_STOP` | 정지 요청 뒤 승인 중단 | `LicenseApprovalCorrected` | `PROCESSING` |
| `PENDING_PAYMENT_REFUND` | `LicenseSuspended`(실행 요청 저장) | `RefundPaymentConfirmed`, `RefundExecutionFailed`, `RefundFailureClosed` | `PROCESSING` |
| `PENDING_LICENSE_RESTORATION` | 실패 확정(두 실패 사실 중 하나) | `LicenseRestored` | `PROCESSING` |
| `FAILED` | `LicenseRestored` | 고객 재신청, `RefundLateCancellationConfirmed` | `FAILED` + `failureNotice` |
| (목표) 늦은 취소 완료 — 이름은 Order 모듈 스펙 | `RefundLateCancellationConfirmed`(실패 확정 뒤, 결정 157) | 없음(끝) | `COMPLETED`(늦은 취소 안내) |
| `REJECTED` | 반려 | 고객 재심사(7일) | `REJECTED` |
| `COMPLETED` | `RefundPaymentConfirmed` | 없음(끝) | `COMPLETED` |

#### adapter 메모 (공급자 규칙, core 계약 아님)

- PortOne 환불 취소 응답 분류(결정 29 하위 HTTP 오류별 처리, 결정 133): 401 키 수정 후 자동 재개, 403·400 `INVALID_REQUEST`·형식 갖춘 400/409/그 밖 4xx는 "PG 형식 거절"(`HELD`), 409 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN`은 "잔액 변화"(`BALANCE_BLOCKED`), 404·409 `IDEMPOTENCY_OUTSTANDING_REQUEST`·429·5xx·깨진 본문·타임아웃은 "미확정". 502 `PG_PROVIDER`는 G7에서 확인.
- PortOne 3시간 공개 보장 창: adapter·config가 "같은 키 재송신 보장 기한"으로 core에 준다(결정 140, Payment tasks.md:60).
- 실패 종결 근거 판정(`PgRefundUncancelled`: 결제 `PAID`·취소액 0·취소 목록 모두 `FAILED`)은 공급자 응답을 core 값으로 옮기는 일이라 adapter가 맡고, core는 "미취소 확인됨/아님"만 받는다.

#### 단계별 실패 표

공통 사실(행마다 반복하지 않음): 소비 실패는 `ConsumerRetryPolicy`(최초 + 3회, 1·2·4초) 뒤 DLT로 가고 `MessagesDeadLettered` 경보가 울린다 [읽음 `common/kafka/.../ConsumerRetryPolicy.java:20-34`]. Outbox 발행 실패는 Outbox 재시도 뒤 `DEAD`·`OutboxEventsAbandoned` [표]. `SENT` 재발행은 Order(`OrderPaid`·`LicenseSuspensionRequested`·`LicenseRestorationRequested`·`LicenseApprovalCorrectionRequested`)와 License(`LicenseGranted`·`LicenseSuspended`·`LicenseRestored`·`LicenseApprovalCorrected`)만 된다 [읽음 `common/messaging/.../ops/OutboxRepublishTargets.java:29-37`]. Payment·Settlement 설정에는 재발행이 없다 [읽음 `apps/payment/src/main/resources/application.yml`]. Order 쓰기는 모두 `lockByOrderNo` 뒤에 한다 [표].

| 단계 | 실패 종류 | 구체 사례 | 감지하는 곳 | 처리(재시도·멱등 키·보상) | 끝내 안 되면(운영자 개입·경보) | 지금 코드 | 목표와 차이 |
|---|---|---|---|---|---|---|---|
| S1 접수 | 중복 전달 | 고객 앱이 같은 환불 POST를 두 번 보냄 | Order `RequestRefund` | 주문 행 잠금 + `uk_order_refund_order`, 기존 refundId 반환 | 해당 없음(수렴) | [표] 기존 refundId·첫 접수 시각 반환 | 없음 |
| S1 접수 | 순서 역전 | 해당 없음 — 동기 HTTP 한 번이고 앞선 이벤트에 의존하지 않는다(`PAID`는 이미 커밋된 주문 상태) | 해당 없음 | 해당 없음 | 해당 없음 | 해당 없음 | 없음 |
| S1 접수 | 늦은 도착 | 결제 후 3시간 59분 59초에 보낸 요청이 잠금 대기로 4시간을 넘겨 처리됨 | Order `RequestRefund` | 판정 시각은 잠금 전 수신 시각(`clock.instant()`)으로 고정 | 해당 없음 | [읽음 `RequestRefund.java:35`] 잠금 전 시각 사용 | 비교 상대가 Payment 시각(`resultOccurredAt`)이라 RC-16 |
| S1 접수 | 상대 상태가 이미 바뀜 | 주문이 `PAID`가 아님(만료·실패) | Order `Refund.request` | 409 거절 | 해당 없음 | [표] 모든 환불 쓰기가 원주문 `PAID` 요구 | 없음 |
| S1 접수 | 동시 진행 | 고객 접수와 다른 기기 접수가 동시에 | Order | 주문 행 비관적 잠금 → 한쪽만 생성, 다른 쪽은 기존 반환 | 해당 없음 | [표] 결정 29 하위 "동시 중복 접수" | 없음 |
| S1 접수 | 상대 모듈 중단 | 해당 없음 — 접수는 Order 혼자 끝내고 다른 모듈을 동기 호출하지 않는다(결정 29 하위 "첫 응답") | 해당 없음 | 해당 없음 | 해당 없음 | 해당 없음 | 없음 |
| S2 지급 확인 | 중복 전달 | `LicenseGranted` 재전달 | Order `ApplyLicenseGrant` | Inbox(`order-license-grant`) + `order_license_grant` 주문당 1행, 같은 본문이면 무효과 | 해당 없음 | [표] | 없음 |
| S2 지급 확인 | 순서 역전 | `LicenseGranted`가 환불 접수보다 먼저 옴 | Order | 지급 근거만 저장, 뒤의 허용이 곧바로 정지 요청 | 해당 없음 | [표] "환불 없음·다른 상태 → 지급 근거만 저장" | 없음 |
| S2 지급 확인 | 늦은 도착 | Order 소비가 DLT로 가 `PENDING_LICENSE_GRANT`가 10분 넘게 남음 | Order 게이지 `status=PENDING_LICENSE_GRANT`, 경보 `RefundLicenseGrantsDelayed` | 결정 37 DLT 재투입, Kafka 원본이 없으면 License `SENT` 재발행(결정 129) | Outbox 행도 없으면 조사 대상(종결 경로 없음, 결정 125 "환불 조사 이후 조치·종결에서 정한다") | [표] 결정 125·129 구현(PR #151·#164·#167) | Outbox 행까지 없는 건의 종결은 미결 — PD-9 |
| S2 지급 확인 | 상대 상태가 이미 바뀜 | `LicenseGranted`의 구매 연결(paymentId 등)이 주문과 다름 | Order `ApplyLicenseGrant` | 거절 → DLT | 운영자 조사(모순 건, 결정 129) | [표] | 없음 |
| S2 지급 확인 | 동시 진행 | `LicenseGranted` 반영과 ADMIN 승인 중단이 동시에 | Order | 주문 잠금으로 직렬화. 중단이 먼저면 지급 근거만 저장, 지급이 먼저면 정지 요청 뒤 중단은 `PENDING_APPROVAL_STOP` | 해당 없음 | [표] | 없음 |
| S2 지급 확인 | 상대 모듈 중단 | License가 내려가 `OrderPaid`를 아직 소비 못 함 | Order 게이지(10분) | License 복구 뒤 Kafka 소비 재개 | 경보로 운영자가 License 상태 확인 | [표] | 없음 |
| S3 정지 요청 | 중복 전달 | `LicenseSuspensionRequested` 재전달 | License `ApplyLicenseSuspension` | Inbox(`license-suspension`) + `license_suspension` 요청당 1행, 결과 이벤트 재발행 안 함 | 해당 없음 | [표] | 없음 |
| S3 정지 요청 | 순서 역전 | 정지 요청이 `OrderPaid` 반영보다 먼저 License에 옴 | License(`NOT_FOUND`) | Order가 `LicenseGranted` 뒤에만 요청을 내므로 생기지 않아야 한다. 생기면 재시도 뒤 DLT | 운영자 DLT 재투입 | [표] 결정 29 B | 없음 |
| S3 정지 요청 | 늦은 도착 | 재시도 정지 요청이 이전 정지의 복구 결과보다 늦게 License에 옴 | License `License.suspend` | 구매 근거 상태·`suspensionRequestEventId`로 판정(현재 정지 없으면 정지, 있으면 거절) | DLT 뒤 조사 | [표] | 없음 |
| S3 정지 요청 | 상대 상태가 이미 바뀜 | 고객이 정지 전에 주문2로 같은 상품을 다시 사 다른 보유권이 `ACTIVE` | License | 이 구매 근거만 정지(구매 근거 단위, 결정 94) | 해당 없음 | [표] | `suspendedFrom` 숨은 상태 — License 모듈 스펙 |
| S3 정지 요청 | 동시 진행 | 정지와 고객의 보관함 등록이 동시에 | License | 정지는 대상 행 잠금, 등록은 `ProductPurchaseLock`. `uk_license_registration_active`가 마지막 방어 | 해당 없음 | [표] | 정지가 상품 전체 잠금을 안 잡는 점은 License 초안 확인 — License 모듈 스펙 |
| S3 정지 요청 | 상대 모듈 중단 | License 중단으로 `PENDING_LICENSE_SUSPENSION` 정체 | Order 게이지 `status=PENDING_LICENSE_SUSPENSION`(10분), 고객 `LICENSE_SUSPENSION_CONFIRMATION_DELAYED` | 소비 재개, 원본 없으면 Order `SENT` 재발행 | 경보 → 운영자 | [표·읽음 재발행 목록] | 없음 |
| S4 정지 완료 | 중복 전달 | `LicenseSuspended` 재전달 | Order `ApplyRefundSuspension` | Inbox(`order-refund-suspension`) + 기록된 정지 결과와 같으면 새 시도 없음 | 해당 없음 | [표] `RefundAttempt.verifySuspension` | 없음 |
| S4 정지 완료 | 순서 역전 | 이전 정지 요청의 `LicenseSuspended`가 재시도 요청 뒤 옴 | Order | `suspensionRequestEventId`·`retrySuspensionRequestEventId` 일치로만 시도 생성 | 해당 없음 | [표] | 시각 비교 ORD-5 제거 |
| S4 정지 완료 | 늦은 도착 | 승인 중단 뒤 정지 완료가 옴 | Order | 중단된 정지 요청이면 정정 복구 요청(`LicenseApprovalCorrectionRequested`) | 게이지 `status=PENDING_APPROVAL_STOP` | [표] | 시각 비교 ORD-7 제거 |
| S4 정지 완료 | 상대 상태가 이미 바뀜 | License의 licenseId·구매 연결이 지급 근거와 다름 | Order | 거절 → DLT | 운영자 조사 | [표] | 없음 |
| S4 정지 완료 | 동시 진행 | `LicenseSuspended` 반영과 승인 중단 API가 동시에 | Order | 주문 잠금. 실행 요청 저장이 먼저면 중단 409, 중단이 먼저면 정정 복구 | 해당 없음 | [표] | 없음 |
| S4 정지 완료 | 상대 모듈 중단 | Order 중단 — 정지는 됐는데 실행 요청이 안 나감(고객은 게임도 돈도 없음) | Order 게이지(`PENDING_LICENSE_SUSPENSION`, 10분) | Order 재시작 뒤 Kafka 소비 재개 | 원본 없으면 License `LicenseSuspended` 재발행 | [표] | 없음 |
| S5 실행 접수 | 중복 전달 | `RefundExecutionRequested` 재전달 | Payment `ReceiveRefundExecution` | 같은 attemptId는 같은 요청 + Inbox(`payment-refund-execution`)만 무효과 | 해당 없음 | [표] | 없음 |
| S5 실행 접수 | 순서 역전 | 재시도 요청이 이전 시도 `FAILED` 확정보다 먼저 Payment에 옴(이론상) | Payment | 이전 시도 `FAILED`·`failureEventId` 없으면 409 → 재시도 → DLT | 운영자 DLT 재투입 | [읽음 `ReceiveRefundExecution.java:61-67`] | Order는 실패 반영 뒤에만 재시도를 내므로 정상 경로에서는 생기지 않음. `occurredAt` 비교(PAY-1)는 제거 |
| S5 실행 접수 | 늦은 도착 | 요청이 Payment 중단으로 몇 시간 늦게 도착 | Payment | 접수 시점부터 3시간 창이 시작(창 기준은 Payment 최초 준비) | 해당 없음 | [표] | Order가 이 지연을 못 본다 — PD-7 |
| S5 실행 접수 | 상대 상태가 이미 바뀜 | 원결제가 이미 콘솔에서 부분 취소돼 금액이 다름 | Payment `Payment.requireRefundRequest`·PG 조회 | 접수는 하되 송신 전 GET 대조에서 잔액 다르면 자동 취소 안 함(결정 29 하위 "외부 부분 취소") | 운영자: 콘솔 처리 뒤 결정 127/128 종결 | [표] | 없음 |
| S5 실행 접수 | 동시 진행 | 같은 정지 완료 ID로 두 실행 요청(버그) | Payment | `licenseSuspensionEventId` 사용 여부 검사 + DB `uk_refund_execution_suspension`(V22)·`uk_refund_execution_request`(V14) | 해당 없음 | [읽음 `ReceiveRefundExecution.java:71-72`, `V22__refund_explicit_retry.sql:4`] | 없음 |
| S5 실행 접수 | 상대 모듈 중단 | Payment 중단 — Order는 `PENDING_PAYMENT_REFUND`, 고객은 `PROCESSING` | Kafka 소비 지연 게이지(D-026)만 | Payment 재시작 뒤 소비 | 경보 없음(Order 게이지 대상 아님, Payment 3시간 경보는 준비 뒤부터) | [표] Order 게이지 4개 상태 | PD-7 |
| S6 첫 송신·준비 | 중복 전달 | Kafka 재전달로 첫 송신이 두 번 시작 | Payment `RequestFirstRefundCancellation` | 이미 준비됐으면 송신 없이 조회만, 송신 lease, 같은 `Idempotency-Key`·본문 | 해당 없음 | [표] | 없음 |
| S6 첫 송신·준비 | 순서 역전 | `RefundExecutionPrepared`가 금융 결과보다 늦게 Order에 옴 | Order `ApplyRefundPreparation` | 같은 Payment 토픽·같은 키라 정상 경로에서는 생기지 않음. 생겨도 준비 기록은 환불 상태를 검사하지 않음 | 해당 없음 | [표] `RefundAttempt.recordPreparation` | 시각 비교 ORD-1 제거 |
| S6 첫 송신·준비 | 늦은 도착 | 취소 POST 응답 유실 | Payment | 미확정으로 두고 같은 키 재송신·조회 | 3시간 뒤 경보 | [표] | 없음 |
| S6 첫 송신·준비 | 상대 상태가 이미 바뀜 | PG에서 결제가 이미 전액 취소됨(콘솔) | Payment 송신 전 GET 대조 | 송신 안 하고 조회 근거로 금융 완료(귀속 미확정) | 결정 127 종결로 귀속 판정 | [표] | 귀속 칸 분리 — T19 |
| S6 첫 송신·준비 | 동시 진행 | 첫 송신과 재송신 스캔이 겹침 | Payment | 송신 일정 claim(lease)·`purchase_payment`→`refund_execution` 잠금 순서 | 해당 없음 | [표] | 없음 |
| S6 첫 송신·준비 | 상대 모듈 중단 | PG 장애(5xx·타임아웃) | Payment | 미확정 유지, 10·30초 뒤 1분 재송신 | 3시간 뒤 `RefundConfirmationsDelayed` | [표] | 없음 |
| S7 재송신·조회·보류·재개 | 중복 전달 | 웹훅 `Cancelled` 중복 | Payment `ReceivePortOneWebhook` | 웹훅 ID·본문 멱등, 조회만 깨움 | 해당 없음 | [표] | 웹훅 core 이름 중립화(Payment tasks.md:59) |
| S7 재송신·조회·보류·재개 | 순서 역전 | 웹훅 `Cancelled`가 우리 POST 응답보다 먼저 옴 | Payment | 웹훅은 힌트, 조회 결과로만 확정 | 해당 없음 | [표] | 없음 |
| S7 재송신·조회·보류·재개 | 늦은 도착 | PG가 형식 갖춘 409를 계속 돌려줌 | Payment 송신 응답 분류 | 목표: 바로 `HELD`(결정 133) | ADMIN 재개(3시간 안) 또는 실패 종결 | (미확인) 결정 133은 Payment tasks.md:41에 미구현으로 남음 | 결정 133 구현 — MC-P9 |
| S7 재송신·조회·보류·재개 | 상대 상태가 이미 바뀜 | 보류 중 PG 잔액 변화(409 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN`) | Payment | `BALANCE_BLOCKED`, 재개 불가, 조회 유지 | 실패 종결 또는 콘솔 취소 + 결정 127 | [표] | 상태 표현 MC-P8 |
| S7 재송신·조회·보류·재개 | 동시 진행 | ADMIN 재개와 실패 종결의 PG 재조회가 겹침(#166 R1) | Payment `RefundExecution.resumeCancellation` | 종결 표시 lease 동안 재개 409 `REFUND_CLOSURE_BUSY`, 송신 claim null | 해당 없음(재시도) | [읽음 #166 `RefundExecution.java:107-110`, `RefundCancellationSchedule.java:31-32`] | 없음(B안 반영) |
| S7 재송신·조회·보류·재개 | 상대 모듈 중단 | PG 조회가 3시간 넘게 실패 | Payment | 창 밖 간격 `min(10분, max(60초, 경과))`로 계속 | `RefundConfirmationsDelayed` → 운영자 콘솔 확인 → 결정 127/128 | [읽음 #166 커밋 `b71bfa2f` 제목] 간격 구현은 #166 브랜치(미병합) | #166 병합 필요 |
| S8 금융 완료·귀속 | 중복 전달 | `RefundPaymentConfirmed` 재전달 | Order `Refund.confirmPayment` | Inbox(`order-refund-payment-confirmation`) + 지문 대조 | 해당 없음 | [표] | 없음 |
| S8 금융 완료·귀속 | 순서 역전 | `RefundExecutionSucceeded`가 `RefundPaymentConfirmed`보다 먼저 Order에 옴 | Order | 순서와 무관하게 첫 금융 확인 ID·시각 보존 | 해당 없음 | [표] "금융 확인 vs 늦은/먼저 온 실행 성공" | 목표에서 `PG_RESPONSE` 경로는 두 이벤트를 같은 트랜잭션·같은 키로 내므로 항상 확인 → 성공 순 |
| S8 금융 완료·귀속 | 늦은 도착 | 귀속 미확정 뒤 몇 시간 지나 `RefundExecutionSucceeded` | Order | 시도 `UNATTRIBUTED` → `SUCCEEDED` | 오지 않으면 결정 127 ADMIN 종결(Payment만 닫힘) | [표] `reconciliationRequired` 해제 | Order 쪽 해제 미결 — PD-12 |
| S8 금융 완료·귀속 | 상대 상태가 이미 바뀜 | 금융 완료가 왔는데 Order 시도가 이미 `FAILED`(Payment 버그) | Order | 거절 → DLT. Payment가 실패 뒤 성공을 내지 않는 것이 1차 방어(R7.6) | 운영자 조사 | [표] Order 환불 표 "시도 `PENDING`/`SUCCEEDED`" 가드 | 없음 |
| S8 금융 완료·귀속 | 동시 진행 | 같은 주문의 금융 완료와 고객 조회가 동시에 | Order | 조회는 잠금 없는 읽기, 저장된 근거만 | 해당 없음 | [표] | 없음 |
| S8 금융 완료·귀속 | 상대 모듈 중단 | Order 중단 — 돈은 돌려줬는데 고객은 `PROCESSING`, 보유권 정지 유지 | Kafka 소비 지연(D-026) | 재시작 뒤 소비 | 원본 없으면 재발행 경로 없음(Payment 미포함) | [읽음 재발행 목록] | PD-9 |
| S9 PG 확정 실패 | 중복 전달 | `RefundExecutionFailed` 재전달 | Order `ApplyRefundRestoration` | Inbox(`order-refund-restoration`) + `failureFingerprint` | 해당 없음 | [표] | 없음 |
| S9 PG 확정 실패 | 순서 역전 | 실패와 늦은 성공 응답이 PG에서 엇갈림 | Payment | 먼저 확정한 쪽 유지, 실패 뒤 성공 근거는 상태 불변(R7.6) | 실패 뒤 늦은 취소 감시 → ADMIN 근거 반영 S14(결정 157) | [표] | 없음 |
| S9 PG 확정 실패 | 늦은 도착 | Order 시계가 Payment보다 늦어 실패 `occurredAt`이 시도 시작보다 앞섬 | Order `Refund.recordFailure`·`RefundAttempt.recordRefundFailure` | 지금은 거절 → DLT(환불이 `PENDING_PAYMENT_REFUND`에 멈춤) | DLT 재투입해도 같은 결과 | [읽음 `Refund.java:173`, `RefundAttempt.java:98`] | ORD-2·ORD-3 제거 — T3 |
| S9 PG 확정 실패 | 상대 상태가 이미 바뀜 | 실패가 왔는데 Order 환불이 이미 `COMPLETED` | Order | 거절 → DLT(I18가 Payment에서 막음) | 운영자 조사 | [표] 가드 "금융 확인 없음" | 없음 |
| S9 PG 확정 실패 | 동시 진행 | 실패 확정과 ADMIN 실패 종결이 같은 실행에 | Payment | 조회 lease 공유(`claimForClosure`), 종결 쪽 두 번째 트랜잭션이 `failureClosableAt`·종결 행 없음 재확인, `failure_event_id` UNIQUE | 해당 없음 | [읽음 #166 `CloseRefundFailure.java:82,108-117`] | 없음 |
| S9 PG 확정 실패 | 상대 모듈 중단 | Order 중단 — 실패 확정됐는데 보유권 정지 유지 | Order 게이지는 `PENDING_LICENSE_RESTORATION` 진입 뒤만 | 재시작 뒤 소비 | Payment 이벤트 재발행 없음 | [읽음 재발행 목록] | PD-9·PD-7 |
| S10 운영자 실패 종결 | 중복 전달 | ADMIN이 같은 `requestId`로 두 번 POST / `RefundFailureClosed` 재전달 | Payment `CloseRefundFailure`, Order `ApplyRefundRestoration` | 종결 `requestId` 유일·최초 응답 재생, Order Inbox(받은 종류로 기록) + 출처를 넣은 지문 | 해당 없음 | [읽음 #166 `CloseRefundFailure.java:69-70`, `ApplyRefundRestoration.java` diff, `RefundFailureConfirmation.fingerprint`] | 없음 |
| S10 운영자 실패 종결 | 순서 역전 | 종결의 PG GET 뒤에 재개된 POST가 나감 | Payment | R1 B안: 종결 표시 동안 송신·재개 금지, 두 번째 트랜잭션에서 `ownsClosure`·접수 응답 동일 재확인 | 해당 없음 | [읽음 #166 `CloseRefundFailure.java:82-85,108-117`] | 없음 |
| S10 운영자 실패 종결 | 늦은 도착 | 종결 뒤 PG에서 전액 취소가 늦게 나타남 | Payment 실패 뒤 조회 → `recordLateCancellation` | 조사 목록 `LATE_CANCELLATION`·경보, 상태·보유권 자동 불변 | ADMIN이 PG 근거로 늦은 취소 반영(S14, 결정 157) | [읽음 #166 `RefundExecution.java` `recordLateCancellation`] | 반영 경로가 main에 없음(T4, 결정 157) |
| S10 운영자 실패 종결 | 상대 상태가 이미 바뀜 | GET과 잠금 사이에 PG 접수 응답이 새로 기록됨 | Payment 두 번째 트랜잭션 | `cancellationReceipt` 비교 → 409 `REFUND_NOT_FAILURE_CLOSABLE` | ADMIN 재시도 | [읽음 #166 `CloseRefundFailure.java:114-117`] | 없음 |
| S10 운영자 실패 종결 | 동시 진행 | 두 ADMIN이 다른 requestId로 동시에 종결 | Payment | 조회 lease·송신 종결 표시 중 하나만 획득(다른 쪽 409 BUSY), `refund_failure_closure` attemptId 유일 | 해당 없음 | [읽음 #166 `CloseRefundFailure.java:78-85,127-131`] | 없음 |
| S10 운영자 실패 종결 | 상대 모듈 중단 | 종결 도중 Payment 프로세스가 죽음 | Payment | 종결 표시·lease는 기한이 지나면 풀림(`closureUntil`) | ADMIN 다시 요청 | [읽음 #166 `RefundCancellationSchedule.java:19-21,40-52`] | 없음 |
| S11 복구·재신청 | 중복 전달 | `LicenseRestorationRequested`·`LicenseRestored` 재전달 | License `ApplyLicenseRestoration`, Order | 요청 지문·Inbox, Order `restorationFingerprint` | 해당 없음 | [표] | 없음 |
| S11 복구·재신청 | 순서 역전 | 이전 정지의 복구 요청이 새 정지 뒤에 License에 옴 | License `License.restore` | `suspensionRequestEventId` 불일치 → 거절, `uk_license_restoration_suspension` | DLT 조사 | [표] | 없음 |
| S11 복구·재신청 | 늦은 도착 | License 시계가 늦어 `LicenseRestored.occurredAt`이 Order 복구 요청 시각보다 앞섬 | Order `RefundAttempt.recordRestoration` | 지금은 거절 → DLT, 고객은 게임도 돈도 없음 | DLT 재투입해도 같은 결과 | [읽음 `RefundAttempt.java:110`] | ORD-4 제거 — T3 |
| S11 복구·재신청 | 상대 상태가 이미 바뀜 | 복구 때 같은 상품에 다른 `ACTIVE` 보유권(주문2) | License | 옛 보유권 `ENDED`, 구매 근거 보관함 반환(`returnedToStorage=true`) | 해당 없음 | [표] 결정 96·98 | 없음 |
| S11 복구·재신청 | 동시 진행 | 복구 직후 고객 재신청 API와 `LicenseRestored` 반영이 겹침 | Order | 주문 잠금. 재신청은 `restoredEventId` 있을 때만 | 해당 없음 | [읽음 `RefundAttempt.java:148-151`] 추가로 `at ≥ restoredAt`(License 시각) 검사 | ORD-6 제거 — T3 |
| S11 복구·재신청 | 상대 모듈 중단 | License 중단으로 복구 정체 | Order 게이지 `PENDING_LICENSE_RESTORATION`(10분), 고객 `LICENSE_RESTORATION_CONFIRMATION_DELAYED` | 소비 재개, Order `SENT` 재발행 | 경보 → 운영자 | [표·읽음 재발행 목록] | 없음 |
| S12 회수 | 중복 전달 | `PurchaseRefunded` 재전달 | License `ApplyPurchaseRefund` | Inbox(`license-purchase-refund`) + `refundedSuspensionRequestEventId` | 해당 없음 | [표] | 없음 |
| S12 회수 | 순서 역전 | 해당 없음 — Order는 `LicenseSuspended` 반영 뒤에만 실행을 요청하고 완료는 그 뒤라, License에는 정지가 항상 먼저 커밋돼 있다(서로 다른 토픽이어도 인과 순서) | 해당 없음 | 해당 없음 | 해당 없음 | [표] | 없음 |
| S12 회수 | 늦은 도착 | 회수가 늦어도 정지 중이라 보유권은 쓸 수 없음 | License | 늦게 와도 같은 정지면 `REFUNDED` | 해당 없음 | [표] | 없음 |
| S12 회수 | 상대 상태가 이미 바뀜 | 구매 근거가 정지 상태가 아님(운영 수동 변경 등) | License `License.refund` | 거절 → DLT | 운영자 조사 | [표] | 없음 |
| S12 회수 | 동시 진행 | 회수와 고객 보관함 등록 | License | 정지 상태는 등록 불가(`PURCHASE_NOT_REGISTRABLE`) | 해당 없음 | [표] | 없음 |
| S12 회수 | 상대 모듈 중단 | License 중단 — 환불 완료인데 라이브러리에 정지 항목이 남음 | (미확인) 회수 지연 게이지 없음 | 소비 재개 | 원본 없으면 `PurchaseRefunded` 재발행 불가(허용 목록 밖) | [읽음 재발행 목록] | PD-9. 보유권은 정지 상태라 사용 위험은 없음 |
| S13 정산 조정·이월 | 중복 전달 | `RefundPaymentConfirmed` 재전달 | Settlement `RecordConfirmedRefund.existing` | Inbox(`settlement-confirmed-refund`) + `uk_refund_id`·`uk_refund_event`·`uk_refund_order` | 해당 없음 | [표] | 없음 |
| S13 정산 조정·이월 | 순서 역전 | 4시간 안 자동 환불의 `RefundPaymentConfirmed`가 Settlement의 `OrderPaid` 반영보다 먼저 옴(Settlement 소비 지연) | Settlement(원매출 없음 404) | 목표: "매출 대기 환불"로 저장, `OrderPaid` 매출 기록 트랜잭션에서 함께 반영(결정 156). 지금: 재시도 7초 뒤 DLT | 대기 게이지(건수·최장 대기). 매출이 끝내 안 오면 PD-6 기준으로 경보 | [표] | 지금은 DLT → 운영자 재투입, 재투입 전 마감이면 과지급(T8) |
| S13 정산 조정·이월 | 늦은 도착 | 환불 확인 월이 이미 마감됨 | Settlement | 현재 OPEN 월에 반영(`CLOSED_MONTH_ROLLOVER`), 원래 월 보존 | 해당 없음 | [표] 결정 42·130 | 없음 |
| S13 정산 조정·이월 | 상대 상태가 이미 바뀜 | 판매자 월 마감과 환불 반영이 겹쳐 월이 CLOSED로 바뀜 | Settlement | 판매자 잠금으로 직렬화, 월이 바뀌면 3번 재선택 뒤 409 | DLT 재투입 | [표] | 없음 |
| S13 정산 조정·이월 | 동시 진행 | 같은 판매자의 매출 반영·환불 반영·마감 | Settlement `SellerLockRepository.lock` | 판매자 단위 직렬화, 유일 제약 충돌은 5회 재시도 | 해당 없음 | [표] | 없음 |
| S13 정산 조정·이월 | 상대 모듈 중단 | 결제 공급자가 `SIMULATED`라 Settlement가 PG 환불 근거를 못 얻음(503 → DLT) | Settlement `UnavailablePgRefunds.require` | 재시도 뒤 DLT | 의도된 미연동, 판매자 순액이 크게 남음 | [표] | U4 — PD-10 |
| S14 늦은 취소 반영 | 중복 전달 | ADMIN 요청 재전송 / `RefundLateCancellationConfirmed` 재전달 | Payment 요청 ID·시도당 확인 기록 유일 / Order·Settlement Inbox | 같은 요청이면 같은 결과, 이미 반영된 시도면 409. 소비는 무변화 | 해당 없음 — 사실이 하나로 남는다 | 없음(목표, T4) | T4 |
| S14 늦은 취소 반영 | 순서 역전 | 늦은 취소 확인이 같은 시도의 실패 사실보다 먼저 Order에 옴 | Order 시도 상태(실패 확정 전) | 해당 없음 — Payment는 실패 확정 기록이 있는 시도에만 반영을 허용하고 같은 키라 결정 155로 순서가 지켜진다 | — | 없음(목표) | T4 |
| S14 늦은 취소 반영 | 늦은 도착 | 늦은 취소가 반영 월 마감 뒤에 확인됨 | Settlement 판매자 잠금 뒤 마감 월 확인 | 현재 `OPEN` 월에 반영(결정 42·130) | 해당 없음 | 없음(목표) | T4 |
| S14 늦은 취소 반영 | 상대 상태가 이미 바뀜 | 고객이 이미 재신청해 같은 결제의 새 환불 시도가 진행 중 / 보유권이 복구돼 `ACTIVE` | Payment 같은 결제의 열린 실행 / License 보유권 상태 | 새 시도 처리는 Order·Payment 모듈 스펙(MC-O3·MC-P15). License는 `ACTIVE`에서도 `REFUNDED`로 회수(P3.23-8) | 경보·조사 목록 | 없음(목표) | T4·MC-O3·MC-P15·MC-L1 |
| S14 늦은 취소 반영 | 동시 진행 | 같은 실행의 늦은 취소 반영과 실패 뒤 조회·고객 재시도 사전 확인이 동시에 | Payment 실행 잠금 | 직렬화, 반영 뒤 사전 확인은 이미 환불된 결제로 본다 | 해당 없음 — 잠금으로 끝난다 | 없음(목표) | T4·MC-P15 |
| S14 늦은 취소 반영 | 상대 모듈 중단 | PG 재조회 장애 / Order·Settlement 중단 | 재조회 예외 / 소비 지연 | 재조회 실패면 반영 거절(409), 운영자가 다시 시도. 소비는 Kafka 보존 뒤 반영 | DLT·키 정지(결정 155) | 없음(목표) | T4 |

## 돈 불변식과 지키는 곳

I1~I11은 `docs/domain/workflows.md`(I1~I11)에서 옮겼고 번호를 유지한다. I12부터는 이 스펙에서 새로 둔 것이다. "지키는 곳"은 코드 위치 또는 DB 제약, "확인 테스트"는 그것을 확인하는 테스트(이름으로 찾았고 단언까지 읽은 것은 ✓), "확인 SQL"은 모든 스키마를 읽는 읽기 전용 계정에서 0행이 통과다. 모듈을 넘는 확인 SQL을 실행하는 테스트·검사기는 아직 없다(T6).

### 불변식 표 (I1 ~ I20)

"지키는 곳"은 2026-11-09에 직접 읽은 코드·Flyway다. "테스트"는 테스트 소스에서 해당 제약·메서드 이름으로 찾은 클래스이며, 단언 내용까지 읽은 것은 ✓로 표시했다. 모듈을 넘는 **확인 SQL을 실행하는 테스트·검사기는 어디에도 없다**(`tools/verification/`에 불변식 검사기 없음, T6).

| 번호 | 정의(테이블·컬럼) | 걸친 모듈 | 지키는 곳 | 확인 테스트 | 확인 SQL(0행 통과) |
|---|---|---|---|---|---|
| I1 | `purchase_order.amount = list_amount − discount_amount > 0`; 같은 `order_no`의 `purchase_payment.amount`·`currency` 동일; `pg_paid_amount + pg_discount_amount = amount`(PAID일 때만); Order는 연결(주문번호·요청 이벤트·구매자·금액·통화)이 같은 결과만 반영 | Order·Payment | DB `chk_purchase_order_price`(`order/db/V10__purchase_order.sql:29`), `chk_purchase_payment_price`(`payment/db/V11__purchase_payment.sql:35`, `currency='KRW'` 포함), `ck_payment_provider_amounts`(`payment/db/V32__provider_payment_amounts.sql:5`), `ck_order_provider_amounts`(`order/db/V27__refund_amount_details.sql:5`). 코드 `Order.requireConnection`(`order:core/domain/Order.java:174`), `ProviderPaymentAmounts.requireTotal`(`order:core/domain/ProviderPaymentAmounts.java:9`, `PaymentResult.java:23`), `Payment.confirm`(`payment:core/domain/Payment.java:117`), 시뮬레이터 응답 대조 `SimulatedPgHttpGateway.java:36` | `OrderTest.rejectsFeeDeductedAmountAndWrongPaymentConnection` ✓(이름), `OrderTest.priceCompositionCannotHideFeesOrNegativeDiscounts`, `PgPaymentAmountsTest`, `OrderMigrationIntegrationTest` | `SELECT o.order_no FROM stove_order.purchase_order o JOIN stove_payment.purchase_payment p ON p.order_no=o.order_no WHERE o.amount<>p.amount OR o.currency<>p.currency OR (o.status='PAID' AND (p.status<>'PAID' OR o.payment_id<>p.payment_id OR NOT (o.pg_paid_amount <=> p.pg_paid_amount) OR NOT (o.pg_discount_amount <=> p.pg_discount_amount)))` |
| I2 | `refund_execution.amount = purchase_payment.amount`(PAID); 금융 확인은 취소 합계 = 원결제액; `settlement_refund.(amount, platform_fee_amount, seller_payout_amount)` = 원 `settlement_sale` | Order·Payment·Settlement | 코드 `Payment.requireRefundRequest`(`payment:core/domain/Payment.java:73`, 호출 `RefundExecution.java:167`), `RefundExecution.confirmCancellationSuccess`(`:221`)·`confirmRefund`(`:269`), `Refund.confirmPayment`→`verifyPaymentIdentity`(`order:core/domain/Refund.java:225`·`:248`), `ConfirmedRefund.requireSale`(`settlement:core/domain/ConfirmedRefund.java:22`), `ReverseOriginalFullRefundPolicy.allocate`(`settlement:core/domain/ReverseOriginalFullRefundPolicy.java:6`). DB `chk_refund_execution_price`(`payment/db/V14__refund_execution_receipt.sql:26`, 금액>0·KRW만 — 원결제액 일치는 코드만), `ck_refund_money`·`fk_refund_sale`(`settlement/db/V10__record_full_refund_adjustments.sql:19-21`) | `FullRefundAllocationPolicyTest`, `SettlementRefundPostingTest`, `SaleRefundLinkTest`, `RefundPaymentConfirmationTest`(Order) | `SELECT r.refund_id FROM stove_settlement.settlement_refund r JOIN stove_settlement.settlement_sale s ON s.order_no=r.order_no WHERE r.amount<>s.amount OR r.platform_fee_amount<>s.platform_fee_amount OR r.seller_payout_amount<>s.seller_payout_amount`; `SELECT e.attempt_id FROM stove_payment.refund_execution e JOIN stove_payment.purchase_payment p ON p.payment_id=e.payment_id WHERE e.amount<>p.amount OR p.status<>'PAID'` |
| I3 | `license_registration.status='ACTIVE'` ⇒ 그 `product_license.status='REGISTERED'` ∧ Order 같은 `order_no`가 `PAID`·같은 `payment_id`; 구매 근거는 `OrderPaid`로만·주문당 1행; 고객·상품당 `ACTIVE` 1개; 구매 근거당 열린 보유권 1개 | Order·License | 코드 `ApplyPaymentResult.execute`(첫 PAID 때만 `OrderPaid` Outbox, `order:core/service/ApplyPaymentResult.java:50-57`), `GrantLicense.execute`(`license:core/service/GrantLicense.java:31-48`), `License.grant`(`license:core/domain/License.java:152`)·`register`(`:72`)·`suspend`(`:80`)·`restore`(`:98`)·`refund`(`:121`). DB `uk_license_registration_active`·`uk_license_registration_open`·`fk_license_registration_purchase`(`license/db/V10__purchase_storage_registration.sql:20-24`), `chk_product_license_status`(`:50`), `uk_product_license_order`·`_result`·`_payment`(`license/db/V5__merge_license_purchase.sql:60-64`) | `LicenseMigrationIntegrationTest`, `GrantLicenseTest`, `PurchaseStorageIntegrationTest` | `SELECT r.registration_id FROM stove_license.license_registration r JOIN stove_license.product_license l ON l.license_id=r.license_id LEFT JOIN stove_order.purchase_order o ON o.order_no=l.order_no WHERE r.status='ACTIVE' AND (l.status<>'REGISTERED' OR o.status IS NULL OR o.status<>'PAID' OR o.payment_id<>l.payment_id)` |
| I4 | `order_refund.status='COMPLETED'` ⇒ 같은 `refund_id`의 `refund_execution.refund_confirmed_at` 있음; `product_license.status='REFUNDED'` ⇒ Order 환불 `COMPLETED`(또는 늦은 취소 완료); `settlement_refund` 있음 ⇒ `refund_confirmed_at` 있음(또는 Payment 늦은 취소 확인 기록, 결정 157) | Order·Payment·License·Settlement | 코드 `RefundExecution.confirmRefund`·`confirmCancellationSuccess`, `RefundConfirmationOutbox.recordUnattributed`(`payment:core/service/RefundConfirmationOutbox.java:13`), `RecordRefundCancellationSuccess`(`:40`), `ApplyRefundPaymentConfirmation.execute`(바뀐 때만 `PurchaseRefunded`, `order:core/service/ApplyRefundPaymentConfirmation.java:45-54`), `License.refund`(`SUSPENDED`이고 `suspensionRequestEventId` 일치, `license:core/domain/License.java:121-127`), `SettlementRefund.record`(`requireSale`·`requireConfirmation`, `settlement:core/domain/SettlementRefund.java:55-59`), 실행 요청은 `LicenseSuspended` 뒤만(`order:core/service/ApplyRefundSuspension.java:76-79`). DB `chk_order_refund_payment_confirmation`(`order/db/V17__refund_execution_success.sql:13-14`), `chk_refund_payment_confirmation`(`payment/db/V19__refund_cancellation_success.sql:17-19`) | `ApplyRefundPaymentConfirmationTest`, `RefundPaymentConfirmationKafkaIntegrationTest`, `PurchaseStorageIntegrationTest` | `SELECT o.refund_id FROM stove_order.order_refund o LEFT JOIN stove_payment.refund_execution e ON e.refund_id=o.refund_id AND e.refund_confirmed_at IS NOT NULL WHERE o.status='COMPLETED' AND e.refund_id IS NULL`; `SELECT l.order_no FROM stove_license.product_license l LEFT JOIN stove_order.order_refund o ON o.order_no=l.order_no AND o.status='COMPLETED' WHERE l.status='REFUNDED' AND o.order_no IS NULL`; `SELECT s.refund_id FROM stove_settlement.settlement_refund s LEFT JOIN stove_payment.refund_execution e ON e.refund_id=s.refund_id AND e.refund_confirmed_at IS NOT NULL WHERE e.refund_id IS NULL` |
| I5 | CLOSED 월 합계 = 원장 합계; 이월 등식 | Settlement(입력 Order·Payment) | 코드 `SellerMonthlySettlement.close`(`settlement:core/domain/SellerMonthlySettlement.java:60-63`)·`getNetSellerPayoutAmount`(`:47`), `CloseSellerMonth.close`·`carry`(`settlement:core/service/CloseSellerMonth.java:50-68`), `SellerMonthBalance.settle`·`of`(`settlement:core/domain/SellerMonthBalance.java:17`·`:23`). DB `ck_month_allocation`(`settlement/db/V8__...sql:58`), `ck_month_refund_allocation`(`settlement/db/V10__...sql:39`), `ck_seller_carryover_amount`·`ck_seller_carryover_source`·`uk_seller_carryover_month`(`settlement/db/V13__seller_month_carryover.sql:13-16`), `uk_seller_carryover_source`(`settlement/db/V15__...sql:3`) | `SellerCarryoverTest`, `SettlementIntegrationTest`, `SettlementMigrationIntegrationTest` | `SELECT m.seller_subject, m.settlement_month FROM stove_settlement.seller_monthly_settlement m LEFT JOIN (SELECT seller_subject, settlement_month, SUM(seller_payout_amount) s, COUNT(*) c FROM stove_settlement.settlement_sale GROUP BY 1,2) a USING (seller_subject, settlement_month) LEFT JOIN (SELECT seller_subject, settlement_month, SUM(seller_payout_amount) s, COUNT(*) c FROM stove_settlement.settlement_refund GROUP BY 1,2) r USING (seller_subject, settlement_month) WHERE m.status='CLOSED' AND (m.seller_payout_amount<>COALESCE(a.s,0) OR m.record_count<>COALESCE(a.c,0) OR m.refunded_seller_payout_amount<>COALESCE(r.s,0) OR m.refund_count<>COALESCE(r.c,0))`; 이월: workflows.md I5의 `settlement_seller_carryover` 자기 조인 SQL(V13 이전 마감 월 제외) |
| I6 | CLOSED 월 불변, CLOSED→OPEN 없음, 늦은 기록은 현재 OPEN 월로, 원래 월 보존 | Settlement | 코드 `SellerMonthlySettlement.add`(`:54`)·`addRefund`(`:48`)(CLOSED면 409), `RecordPaidSale.record`(`settlement:core/service/RecordPaidSale.java:79-96`, 판매자 잠금 후 현재 월로), `SellerLockRepository.lock`(`settlement:core/domain/SellerLockRepository.java:13`). DB `ck_month_closed`(`settlement/db/V6__settlement_sales.sql:62`) — CLOSED 행의 합계 불변은 DB가 막지 않음(코드만) | `SellerCarryoverTest`, `SettlementRefundPostingTest` | `SELECT id FROM stove_settlement.settlement_sale WHERE settlement_month < sale_month`; `SELECT id FROM stove_settlement.settlement_refund WHERE settlement_month < refund_month`. 합계 변경은 I5가 잡는다. `closed_at` 불변은 검사기가 이전 회차 값과 비교 |
| I7 | 미확정 PG 비용은 NULL; 마감은 비용을 읽지 않음 | Payment·Settlement | DB `ck_payment_pg_fee`(`payment/db/V12__pg_fee_snapshot.sql:5`), `ck_pg_fee_money`(`settlement/db/V8__...sql:33`). 코드 `SettlementSaleView`·`SellerMonthView`의 `pgCostStatus`, `HttpValues` 비용 해석, `CloseSellerMonth`(비용 참조 없음) | `ReadPgFinancialsContractTest`, `SettlementIntegrationTest` | `SELECT s.id FROM stove_settlement.settlement_sale s LEFT JOIN stove_settlement.settlement_pg_fee f ON f.payment_id=s.payment_id WHERE f.payment_id IS NULL`로 미확정을 고른 뒤 ADMIN 응답(`AdminSaleResponse`·`AdminMonthResponse`)의 비용·순액이 NULL인지 본다(SQL만으로는 판정 불가) |
| I8 | 같은 금융 사건 1회 반영 | Order·Payment·License·Settlement | DB `uk_processed_event`(각 모듈 V1: `order/db/V1__init.sql:64`, `payment/db/V1__init.sql:57`, `license/db/V1__init.sql:51`, `settlement/db/V1__init.sql:51`), 업무 유일 키 `uk_purchase_order_result`(`order/db/V10:27`), `uk_product_license_order`·`_result`·`_payment`, `uk_sale_order_no`·`uk_sale_payment_result_event_id`(`settlement/db/V6:31-34`), `uk_refund_order`·`uk_refund_event`·`uk_refund_id`(`settlement/db/V10:16`), `uk_order_refund_order`(`order/db/V12__order_refund.sql:10`)·`uk_order_refund_payment_confirmation`(`order/db/V16:6`), `uk_purchase_payment_order`(`payment/db/V11:29`)·`uk_refund_execution_request`(`payment/db/V14:20`)·`uk_refund_execution_confirmation`(`payment/db/V17:5`), `uk_seller_carryover_month`·`_source`. 코드 `ProcessedEventGuard`(`common/messaging/.../inbox/ProcessedEventGuard.java`), `GrantLicense.verifyRedelivery`(`:52`), `RecordPaidSale.existing`(`:65`), `Order.apply`(저장 결과와 다르면 409, `Order.java:154-157`) | `ApplyPaymentResultTest`, `GrantLicenseTest`, `ApplyRefundPaymentConfirmationTest`, `ApplyLicenseGrantTest`, `LicenseIntegrationTest` | `information_schema.TABLE_CONSTRAINTS`에서 위 제약 이름이 네 스키마에 모두 있는지 확인. 금액 중복은 I5 |
| I9 | License는 다른 모듈 시각과의 선후로 복구·정정을 거부하지 않음 | Order·License | 코드 `ApplyLicenseRestoration`, `ApplyApprovalCorrection`, `LicenseRestoration.record`(`license:core/domain/LicenseRestoration.java:30`), `LicenseApprovalCorrection.record`(`:24`). 이행 `license/db/V12__restoration_cross_module_time.sql:4-5`가 `chk_license_restoration_time`·`chk_license_correction_time` 삭제. `apps/license/src/main`에서 `occurredAt` 선후 비교 0건(2026-11-09 grep) | `LicenseRestorationIntegrationTest`, `LicenseApprovalCorrectionIntegrationTest`(시계 차이 4건, workflows.md 기준) | `SELECT CONSTRAINT_NAME FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='stove_license' AND CONSTRAINT_NAME IN ('chk_license_restoration_time','chk_license_correction_time')` |
| I10 | 보류 승인의 첫 사유 유지 | Payment | 코드 `PaymentApproval.stopPosts`(`payment:core/domain/PaymentApproval.java:177`), 로그 `ResolvePaymentApproval.java:271` | `PaymentApprovalTest` | DB에 사유 이력 없음 → SQL로 확인 불가. 보조: `SELECT approval_id FROM stove_payment.payment_approval WHERE status='REVIEW_REQUIRED' AND (last_error_code IS NULL OR review_started_at IS NULL)`(결정 122 옛 행 별도) |
| I11 | 결제 PAID ⇒ 접수된 열린 승인 없음 | Payment | 코드 `ResolvePayment.confirm`→`succeedAdmittedApproval`(`payment:core/service/ResolvePayment.java:164`·`:174`) | `PaymentApprovalIntegrationTest` | `SELECT a.approval_id FROM stove_payment.payment_approval a JOIN stove_payment.purchase_payment p ON p.payment_id=a.payment_id WHERE p.status='PAID' AND a.admitted_at IS NOT NULL AND a.status IN ('ADMITTED','REVIEW_REQUIRED')` |
| **I12** (U1) | `purchase_order.status='PAID'` ⇒ 그 주문이 반영한 결과의 `admissionId`가 그 주문의 승인 진행 영수증(`order_payment_admission.admission_id`)이고, 그 영수증은 실패로 닫히지 않았다 | Order·Payment | 목표: `Order.apply`가 열린 최신 영수증과 `admissionId`·`paymentId`를 대조(P3.14-1), DB `purchase_order.payment_admission_id` FK + `CHECK (status<>'PAID' OR payment_admission_id IS NOT NULL)`. **지금 없음**(`order:core/domain/Order.java:152-172`, 가드 `:163-164`는 `PAYMENT_PROCESSING`일 때만 `paymentId` 대조) → T1. 연결되지 않은 성공은 거절 사실로 드러냄(결정 153, P3.14-2) | **없음** → T1: 영수증 실패 종료 뒤 같은 `paymentId` 결과 주입 → 거절, 기한 지난 `PENDING`에 결과 주입 → 거절, 영수증 B 열림 + 결과 `admissionId`=A → 거절 | 목표: `SELECT order_no FROM stove_order.purchase_order o WHERE o.status='PAID' AND NOT EXISTS (SELECT 1 FROM stove_order.order_payment_admission a WHERE a.admission_id=o.payment_admission_id AND a.order_no=o.order_no AND a.failed_at IS NULL)`. 지금(목표 열이 없음): `... WHERE o.status='PAID' AND NOT EXISTS (SELECT 1 FROM stove_order.order_payment_admission a WHERE a.order_no=o.order_no AND a.payment_id=o.payment_id AND a.closed_at IS NULL)` — "열린 영수증이 하나라도 있는가"까지만 본다(`order/db/V24__order_payment_admission.sql:8-24`, `V25__payment_admission_failure.sql:1-9`) |
| **I13** (U2 목표) | Order·Payment·License·Settlement는 다른 모듈 시각과의 선후로 거절하지 않음 | 네 모듈 | License만 지킴(I9). Order·Payment·Settlement 위반 9곳(T3) | 없음(시계 차이 재현 테스트는 License만) | SQL로 확인 불가. 정적 검사 제안: `core.domain`·`core.service`에서 입력 레코드의 `occurredAt()`/`confirmedAt()`을 인자로 받는 `isBefore`/`isAfter` 호출 금지(ArchUnit 또는 소스 검사, T3) |
| **I14** (U3 목표) | 정방향: PAID → 구매 근거·매출, COMPLETED → REFUNDED, `refund_confirmed_at` → `settlement_refund`, 각각 기준 시간 T 안 | 네 모듈 | 없음(Order의 환불 대기 게이지 일부만, 결정 125·129). 목표 감지 주체·기준 시간 T는 [결정 필요 PD-6], 재반영 경로는 PD-9 | 없음 | `SELECT o.order_no FROM stove_order.purchase_order o LEFT JOIN stove_license.product_license l ON l.order_no=o.order_no WHERE o.status='PAID' AND l.order_no IS NULL AND o.updated_at < NOW(6) - INTERVAL :T SECOND`; `settlement_sale`·`order_refund`/`product_license REFUNDED`·`refund_execution`/`settlement_refund`도 같은 모양. `updated_at`은 같은 스키마의 행 시각을 검사기 시계와 비교하는 것으로, 판정이 아니라 경보 기준이다(원칙 ⑥ 밖: 업무를 거절하지 않음) |
| **I15** (U1, Payment 쪽) | Payment는 승인 진행 영수증이 없는 시도의 PG 결제로 결과 이벤트(`OrderPaymentCompleted`)를 내지 않는다: 결과를 낸 시도는 결과 트랜잭션 안에서 그 결제의 접수된 현재 시도다 | Payment(영수증은 Order) | 목표: `ResolvePayment` 확정 트랜잭션이 결제 → 시도 순서로 잠근 뒤 시도 재확인(P3.12-1), 모의 PG도 접수 경로(P3.11-2). 지금: `payment:adapter/pg/AdmittedPortOnePgGateway.java:21-28`가 잠금 밖에서만 확인, 모의 PG는 접수 없이 확정(`payment:core/service/ResolvePayment.java:99-126`) → T1·T13 | 없음 → 확정 직전 시도를 실패 종료로 바꾼 경합 테스트(T1), 모의 PG 접수 없이 결과가 나오면 Red(T13) | `SELECT p.payment_id FROM stove_payment.purchase_payment p WHERE p.status='PAID' AND NOT EXISTS (SELECT 1 FROM stove_payment.payment_approval a WHERE a.payment_id=p.payment_id AND a.status='SUCCEEDED' AND a.admitted_at IS NOT NULL)` |
| **I16** | 실패 확정된 환불 시도에는 실패 사실이 정확히 하나: PG 확정 실패면 Order 시도 `failure_event_id` = `RefundExecutionFailed` eventId·`failure_closure_id` NULL, 운영자 종결이면 `refund_failure_closure.failure_event_id` = Order `failure_event_id`·`failure_closure_id` = Payment 종결 요청 ID | Order·Payment | 목표: Payment `refund_failure_closure.failure_event_id` UNIQUE, Order 출처 enum 열 + CHECK(출처 `OPERATOR_FAILURE_CLOSED` ⇔ `failure_closure_id` NOT NULL). 참고 구현(#166 V40·V35)에 일부, main에 없음 → T4 | #166 `RefundFailureClosureIntegrationTest`(main 미병합) | `SELECT a.attempt_id FROM stove_order.order_refund_attempt a LEFT JOIN stove_payment.refund_failure_closure c ON c.failure_event_id=a.failure_event_id WHERE a.status='FAILED' AND (a.failure_closure_id IS NULL) <> (c.attempt_id IS NULL)` |
| **I17** | 보유권 복구는 실패 확정(두 사실 중 하나) 뒤에만 생기고 정지 1건당 1건 | Order·License·Payment | Order는 실패 반영 트랜잭션에서만 `LicenseRestorationRequested`를 쓴다(`order:core/service/ApplyRefundRestoration.java:41`), License `uk_license_restoration_suspension` [표] | `LicenseRestorationIntegrationTest` | `SELECT r.request_event_id FROM stove_license.license_restoration r LEFT JOIN stove_payment.refund_execution e ON e.failure_event_id=r.failure_event_id AND e.status='FAILED' WHERE e.attempt_id IS NULL` |
| **I18** | 같은 환불 시도에 금융 완료와 실패가 함께 있지 않다 | Payment(→ Order) | `RefundExecution.confirmFailure`(금융 확인 있으면 무시), 종결 대상 판정 `failureClosableAt`(`refundConfirmedAt == null`) [읽음 #166]. 목표: DB CHECK로 승격(MC-P8) | (미확인) | `SELECT attempt_id FROM stove_payment.refund_execution WHERE refund_confirmed_at IS NOT NULL AND status='FAILED'` |
| **I19** | 운영자 실패 종결 중에는 그 실행의 PG 취소 요청이 나가지 않는다 | Payment | `RefundCancellationSchedule.markClosure`·`closureMarkedAt`(송신 claim이 null), `RefundExecution.resumeCancellation`(409 `REFUND_CLOSURE_BUSY`) [읽음 #166]. main에 없음 → T4 | #166 R1 Red→Green 테스트(직접 실행하지 않음) | SQL로 확인 불가(시간 창 안의 사건). 감사: 종결 시각과 같은 실행의 송신 기록 시각 대조 |
| **I20** | PG 청구(`PAID` 관측)가 있는 결제는 `PAID` 주문의 결과, 승인 보류(`payment_approval.status`=`REVIEW_REQUIRED`), 주문에 연결되지 않은 청구 기록(`UnlinkedCharge`) 중 하나에 연결된다 | Payment(→ Order) | 목표: 끝난 결제의 공급자 보장 기한까지 주기 조회 + 웹훅 시 조회 앞당김(P3.13-8), `PAID` 관측 시 기록 생성(P3.13-9), Order 거절 사실(P3.14-2) → 기록(P3.13-7). **지금 없음**(`payment:core/domain/Payment.java:172-178` 끝난 결제의 웹훅 무시, `ResolvePayment.java:89`) → T5 | **없음** → T5: 흉내 서버 여정 `READY` 종결 뒤 `console-approve` → 기록·경보, 만료 뒤 `PAID` → 기록, Order 거절 → 기록 | SQL로는 PG 상태를 알 수 없음. 확인: 주기 조회 결과의 `PAID` 관측 건이 모두 기록·보류·`PAID` 주문 중 하나에 있음, G7 PG 명세 대사([결정 154](../../../docs/decisions.md#unlinked-charge-discovery)) |


### U1 ~ U6 — 지키는 곳 없던 구멍과 목표

| U | 구멍(`docs/domain/workflows.md`) | 목표 계약 | 목표 지키는 곳 | 남은 결정·작업 |
|---|---|---|---|---|
| U1 | `PENDING` 주문의 결과를 기한·`paymentId`와 대조하지 않아, 실패로 돌아온 주문에 이전 시도의 늦은 성공이 오면 `PAID`·보유권·매출 | I12·I15, P3.12-1·P3.14-1·P4.2-5 | Order `Order.apply` 영수증·`admissionId` 대조 + `purchase_order.payment_admission_id` FK·CHECK; Payment 확정 트랜잭션의 시도 재확인 | 결정 153(거절 사실 `OrderPaymentResultRejected` → Payment 보류)·결정 154(`UnlinkedCharge`), T1·T5·T13 |
| U2 | 다른 모듈 시각과의 선후 비교로 판정 | I13, P4.5-1 | 식별자·잠금 판정(아래 U2 표 "목표 판정 방식"), 정적 검사(입력의 `occurredAt()`/`confirmedAt()`을 인자로 받는 `isBefore`/`isAfter` 금지) | T3, PD-11(외부 PG 시각), RC-16(결정 29 4시간 기한) |
| U3 | 앞 모듈 기록 → 뒤 모듈 반영의 누락을 감지하는 곳 없음 | I14, P4.3-1·P3.9-2·P3.15-5 | [결정 필요 PD-6]이 정하는 감지 주체(검사기 또는 사실 이벤트 + 앞 모듈 게이지), 재반영은 재발행·DLT 재투입 | PD-6·PD-8·PD-9, T6·T7 |
| U4 | `SIMULATED` 공급자에서 Settlement가 환불 확인을 거절(DLT) | P4.3-2, P3.26-5 | 모든 공급자가 같은 `PgRefunds` 계약(검증 생략 경로 없음, 원칙 ⑦) | [결정 필요 PD-10], T9 |
| U5 | 늦은 `RefundExecutionSucceeded`가 정산에 반영된다는 문서와 코드 불일치 | P4.3-3, P3.26-4: Settlement는 소비하지 않는다(정산 금액은 첫 금융 확인 하나, 취소 귀속은 Settlement 자신의 PG 조회) | `settlement:api/listener/RefundEventListener.java:21`(지금 코드가 목표와 같음) | Settlement R6.2 문서 정정 MC-S2 |
| U6 | PG 비용 확정 경로 | I7은 지켜짐. 비용 확정·대사는 G7(결정 142)·결정 140 범위 | DB `ck_payment_pg_fee`·`ck_pg_fee_money`, `CloseSellerMonth`(비용 미참조) | PD-13(비용 정본), T17 |

### 지키는 층 요약

| 층 | 불변식 |
|---|---|
| DB 제약만으로 성립 | I8(유일성), I6 일부(`ck_month_closed`) |
| DB + 코드 | I1, I2, I3, I4, I5, I7, 목표 I12·I16 |
| 코드만 | I6(CLOSED 합계 불변, MC-S1), I9, I10, I11, I17, I18(목표 CHECK), I19 |
| 지키는 곳 없음(지금) | I12(U1), I13(U2, License 밖), I14(U3), I15, I16·I19(main 미병합) |
| 모듈 간 SQL 검사 | 모든 행 — 실행 주체 없음(T6) |

### U2 — 다른 모듈 시각 비교 위치와 목표 판정 방식 (I13)

모두 이번에 소스를 열어 확인했다 [읽음]. "시계" 열은 비교하는 두 값이 어느 모듈 시계에서 왔는지다. 목표는 "그 비교가 지키려던 것"을 식별자·상태·잠금으로 이미 지키는지 보고, 지키면 비교를 지운다. 받은 시각은 값 그대로 저장한다.

| ID | 위치 | 비교 | 시계 | 이 비교가 지키려던 것 | 목표 판정 방식 | 확인 테스트(아이디어) |
|---|---|---|---|---|---|---|
| ORD-1 | `order:core/domain/RefundAttempt.java:39-40` `recordPreparation` | `firstPreparedAt < startedAt`이면 거절, `firstPreparedAt > resolvedAt`이면 거절 | Payment vs Order(`startedAt`) / Payment vs Payment(`resolvedAt`은 Payment `occurredAt`) | 다른 시도의 준비 사실이 섞이는 것 | `attemptId`·`executionRequestEventId`·`licenseSuspensionEventId`·`previousAttemptId` 일치로 충분. 첫 비교는 지움. 둘째는 같은 Payment 시계끼리라 Payment의 불변식이며 Order가 다시 검사하지 않는다(③). `RefundPreparationConfirmation.java:17`의 `confirmationDelayAt == firstPreparedAt + 3h` 재계산도 지움 | 고정 `Clock`으로 Order를 Payment보다 5초 앞에 두고 `RefundExecutionPrepared` 반영(지금 Red: `RefundCompletionRejected` → DLT) |
| ORD-2 | `order:core/domain/Refund.java:173` `recordFailure`, `:255` `verifyPaymentIdentity` | 결과 `occurredAt < attempt.startedAt`이면 거절 | Payment vs Order | 이전 시도의 늦은 결과가 새 시도에 붙는 것 | `attemptId == currentAttemptId`·`requestEventId == executionRequestEventId`가 이미 막는다(시도마다 새 UUID). 비교 지움 | Order 시계 +5초에서 `RefundExecutionFailed`·`RefundPaymentConfirmed` 반영(Red 예상) |
| ORD-3 | `RefundAttempt.java:98` `recordRefundFailure` | Order 처리 시각 `at < occurredAt`이면 거절 | Order vs Payment | "미래에서 온 실패" 차단(뜻 없음) | 상태 `PENDING`·식별자만. 비교 지움. #166 diff도 이 줄을 그대로 둔다(#166:`RefundAttempt.java` 101행 근처) | Order 시계 −5초 |
| ORD-4 | `RefundAttempt.java:110` `recordRestoration` | License `occurredAt < restorationRequestedAt`이면 거절 | License vs Order | 이전 복구 요청의 늦은 결과 | `restorationRequestEventId`·`failureEventId`·`suspensionEventId`·`licenseId` 일치. 비교 지움(License I9와 같은 모양) | License 시계 −5초에서 `LicenseRestored` |
| ORD-5 | `RefundAttempt.java:166-167` `afterRetrySuspension` | License `occurredAt < retryRequestedAt` 거절, Order `at < occurredAt` 거절 | License vs Order 두 번 | 재시도 이전의 정지 결과 | `retrySuspensionRequestEventId`(재시도마다 새 UUID)·licenseId. 비교 둘 다 지움 | License 시계 ±5초 |
| ORD-6 | `RefundAttempt.java:150` `requireRetryableAt` (U2 목록에 없던 곳) | 재시도 API 시각 `at < restoredAt`이면 409 | Order vs License(`restoredAt = LicenseRestored.occurredAt`, `RefundAttempt.java:116`) | 복구 완료 전 재시도 | `restoredEventId != null`이 복구 완료를 말한다. 비교 지움 | License 시계 +5초, 복구 직후 재시도 API(지금 409 예상) |
| ORD-7 | `order:core/domain/RefundApprovalStop.java:48` `observeSuspension` | Order `at < input.occurredAt` 거절 | Order vs License | 없음(같은 주문 잠금 아래 Order 시각끼리 `at ≥ receivedAt`만 의미 있음) | 정지 요청 ID 일치·`reopenedAt == null`. License 시각 비교 지움. `at < receivedAt`(Order끼리)은 남겨도 된다 | License 시계 +5초 |
| ORD-8 | `RefundApprovalStop.java:56` `observeRestored` | License `occurredAt < restorationRequestedAt` 거절 | License vs Order | 이전 정정 요청의 결과 | `restorationRequestId`·`stopRequestId`·정지 ID 일치. 비교 지움 | License 시계 −5초 |
| ORD-9 | `RefundApprovalStop.java:67` `reopen` (U2 목록에 없던 곳) | Order `at < restoredAt` 거절 | Order vs License(`restoredAt = input.occurredAt`, `:62`) | 복구 전 재개 | `canReopen()`(`restoredEventId != null`)이 이미 지킨다. 비교 지움 | 위 ORD-8과 같은 시험에서 재개까지 |
| ORD-10 | `RefundAttempt.java:123` `recordCancellationSuccess` (환불 초안 목록에 없던 곳, 통합 때 코드에서 찾음) | Payment 실행 `occurredAt < startedAt`이면 거절 | Payment vs Order | 이전 시도의 성공이 새 시도에 붙는 것 | `attemptId`·`refundId`·`executionRequestEventId` 일치가 이미 막는다. 비교 지움 | Order 시계 +5초에서 `RefundExecutionSucceeded` 반영 |
| PAY-1 | `payment:core/service/ReceiveRefundExecution.java:66` | Order 요청 `occurredAt < previous.failureConfirmedAt` 거절 | Order vs Payment | 이전 시도 실패 확정 전에 만든 재시도 | `previous.status == FAILED`·`failureEventId != null`·`findByPreviousAttemptId` 없음·`refund_execution` 잠금이 이미 지킨다(Order는 실패 반영 뒤에만 재시도를 만든다). 비교 지움 | Payment 시계 +5초에서 재시도 `RefundExecutionRequested` |
| SET-1 | `settlement:core/domain/RefundMonthInput.java:11` | Settlement 수신 `receivedAt < confirmedAt`이면 거절 | Settlement vs Payment | 반영 월이 원래 월보다 앞서는 것(I6) | 거절하지 않는다. 반영 월 = `max(원래 월, 현재 OPEN 월)` 규칙으로 I6을 지킨다(현재 월이 원래 월보다 이르면 원래 월에 반영). 월 선택은 Settlement 잠금 아래서 한다 | KST 월 경계에서 Settlement 시계 −5초(지금 Red 예상) |
| ORD-0 | `order:core/domain/RefundPolicy.java:9-11` + `RequestRefund.java:35` | 자동 환불: Order 수신 시각 ≤ Payment 결과 시각 + 4시간 | Order vs Payment | 결정 29 하위 "기한 시작" | 결정이 이 비교를 정했다. 바꾸지 않고 RC-16로 올린다 | — |
| PG-* | `payment:core/domain/RefundExecution.java:163-165`·`:224-225`·`:276`, #166 `recordLateCancellation` | PG가 준 `requestedAt`·`cancelledAt`이 Payment 관측 시각보다 뒤면 근거 무효 | PG(외부) vs Payment | 근거 위조·오염 방지 | `[결정 필요 PD-11]`(원칙 ⑥을 외부 PG 시계까지 넓힐지) | PG 흉내 서버 시계 +5초 |

같은 모듈 시각끼리의 비교(예: #166 `RefundFailureConfirmation`의 `evidenceObservedAt ≤ occurredAt`, 둘 다 Payment 시계)는 원칙 ⑥ 위반이 아니다. 다만 받는 쪽이 보낸 쪽 불변식을 다시 검사하는 것이라 원칙 ③ 쪽에서 Order 검사는 빼는 것이 목표다(T14).

위 13곳(ORD-1~ORD-10, PAY-1, SET-1 — ORD-1·ORD-5는 한 메서드 안 두 비교)이 T3의 대상이다. ORD-0은 결정 29가 정한 비교라 RC-16으로, PG-*는 [결정 필요 PD-11]로 따로 다룬다.

## 품질 속성 시나리오

실험 뒤(결정 152).
