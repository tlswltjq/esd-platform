# 서비스별 기능 명세

각 서비스가 **무엇을 책임지고, 어떤 요청과 이벤트를 받고, 무엇을 내보내는지** 정리한다.
시스템 전체의 이벤트 흐름과 설계 근거는 [README](../README.md) 를 본다.

읽는 순서는 비즈니스 흐름과 같다 — 크리에이터가 게임을 올리고(A), 이용자가 사고(B), 이용·정산된다(C).

| | 서비스 | 포트 | 한 줄 책임 | 저장소 |
|---|---|---|---|---|
| A | [studio](#studio) | 8085 | 게임 프로젝트·빌드 등록, 심의 신청 | MySQL |
| A | [review](#review) | 8086 | 등급분류 심의 상태머신 | MySQL |
| A | [catalog](#catalog) | 8081 | 상품 마스터, 노출 제어, 가격 확정 | MySQL + Redis |
| B | [store](#store) | 8087 | 진열·검색 (읽기 전용) | Elasticsearch + Redis |
| B | [order](#order) | 8082 | 주문 생성/취소, 금액 검증 | MySQL |
| B | [payment](#payment) | 8083 | PG 연동, 승인 대조, 환불 | MySQL |
| C | [license](#license) | 8084 | 소유권 발급·회수 | MySQL |
| C | [download](#download) | 8088 | 패치 매니페스트, 서명 URL | MongoDB |
| C | [settlement](#settlement) | 8089 | 매출 배분·수수료·마감 | MySQL |
| — | [gateway](#gateway) | 8080 | 라우팅, 내부 API 차단 | — |

---

## studio

크리에이터가 게임을 올리는 입구. 심의 신청과 빌드 업로드가 각각 다운스트림(review, download)의 시작점이 된다.

**상태머신**

```
DRAFT ──submit──▶ SUBMITTED ──ReviewApproved──▶ APPROVED
                            └─ReviewRejected──▶ REJECTED ──submit──▶ SUBMITTED
```

**HTTP API** — `sellerId` 는 실제로는 스튜디오 계정 토큰에서 주입된다(현재는 `X-Seller-Id` 헤더).

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/studio/games` | 프로젝트 생성. `productCode` 중복이면 409 |
| GET | `/api/v1/studio/games` | 내 프로젝트 목록 |
| POST | `/api/v1/studio/games/{gameId}/submit` | 심의 신청 → `GameRegistered` |
| POST | `/api/v1/studio/games/{gameId}/builds` | 빌드 메타데이터 등록 → `BuildUploaded` |
| GET | `/api/v1/studio/games/{gameId}/builds` | 빌드 이력 |

**이벤트** — 수신 `ReviewApproved`·`ReviewRejected` / 발행 `GameRegistered`·`BuildUploaded`

**규칙**

- 모든 변경은 `requireOwner(sellerId)` 를 통과해야 한다.
- 이미 신청했거나 승인된 프로젝트는 다시 신청할 수 없다. 반려된 건만 재신청 가능.
- 같은 게임의 같은 버전은 한 번만 등록된다.
- 바이너리는 직접 받지 않는다. `BuildStorage` 포트로 업로드 경로와 presigned URL 만 발급한다.

---

## review

등급분류 심의. **상품 등록 파이프라인이 이 상태머신에 물려 있어, 승인 이벤트 없이는 상품이 만들어지지 않는다.**

**상태머신** — `APPROVED` 는 종착 상태다.

```
REQUESTED ──▶ IN_REVIEW ──▶ APPROVED
                       └──▶ REJECTED ──(재신청)──▶ REQUESTED
```

**HTTP API** — 심의 담당자용 운영 API.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/reviews?status=` | 심의 목록(상태 필터) |
| POST | `/api/v1/reviews/{reviewId}/approve?ratingCode=ALL` | 승인 → `ReviewApproved` |
| POST | `/api/v1/reviews/{reviewId}/reject` | 반려 → `ReviewRejected` |

**이벤트** — 수신 `GameRegistered` / 발행 `ReviewApproved`·`ReviewRejected`

**규칙**

- **자체등급분류 분기.** `selfRated` 면 게임물관리위원회 접수를 건너뛰고 내부 심사로 간다.
  `stove.review.auto-approve-self-rated`(기본 `true`)면 접수 즉시 승인된다.
- 자체등급분류가 아니면 `RatingBoardClient` 포트로 접수번호를 받아 `IN_REVIEW` 로 전이한다.
- 반려 후 재신청은 같은 레코드를 `REQUESTED` 로 되돌린다(이력 유지).

---

## catalog

상품 마스터. 커머스 트랙 전체가 참조하는 최대 접점이라 **가격·상태 변경 규칙을 엔티티 안에 가둔다.**

**상태머신**

```
DRAFT/REVIEWING ──ReviewApproved──▶ APPROVED ──sale-open──▶ ON_SALE
                                                  ▲            │ suspend
                                                  └────────────┴──▶ SUSPENDED ──▶ CLOSED
```

구매 가능한 상태는 `ON_SALE` **하나뿐**이다.

**HTTP API**

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/products` | 판매 중 상품 목록 |
| GET | `/api/v1/products/{productId}` | 상품 단건 (Redis 캐시 5분) |
| POST | `/api/v1/products/{productId}/sale-open` | 판매 시작 |
| POST | `/api/v1/products/{productId}/suspend` | 판매 중지 |
| POST | `/api/v1/products/reindex` | store 색인 재구축 트리거 |
| POST | `/api/v1/products/quote` | **내부 전용** — 주문 금액 서버 재계산 |

**이벤트** — 수신 `ReviewApproved` / 발행 `ProductChanged`

**규칙**

- 상품은 `ReviewApproved` 수신으로만 생성된다. 재심의는 같은 `productCode` 에 멱등하게 반영된다.
- 판매 시작은 `APPROVED` 또는 `SUSPENDED` 에서만 가능하다 — 심의를 건너뛴 판매가 성립하지 않는다.
- `quote` 는 상품마다 `requirePurchasable()` 을 확인하고, **통화가 다른 상품을 한 주문에 섞는 것을 거부**한다.
- 캐시 무효화는 상태 변경 지점(`ProductCommandService`)에서만 일어난다.

---

## store

진열·검색 전용. catalog(쓰기)와 분리된 **읽기 모델**이며 자체 원본을 갖지 않는다.

**HTTP API**

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/storefront/products?q=&page=&size=` | 검색/목록 |
| GET | `/api/v1/storefront/featured` | 메인 진열 (Redis 캐시) |

**이벤트** — 수신 `ProductChanged` / 발행 없음

**규칙**

- `ON_SALE` 상품만 노출한다.
- 색인 문서 ID = `productId` 로 고정된 upsert 라 **같은 이벤트를 몇 번 받아도 결과가 같다**(자연 멱등).
  그래서 Inbox 테이블을 두지 않는다.
- 메인 진열은 전 사용자 공통 응답이라 캐시 적중률이 가장 높고, 색인이 갱신되면 통째로 무효화한다.

---

## order

주문 생성과 취소. **결제 결과 이벤트로만 `CREATED` 이후 상태가 바뀐다 — `EXPIRED` 만 예외다.**

**상태머신**

```
CREATED ──PaymentCompleted──▶ PAID
        ├─cancel / PaymentCancelled──▶ CANCELED
        ├──▶ FAILED
        └─시간(스윕)──▶ EXPIRED
```

**만료** — 결제를 시작조차 하지 않은 주문은 아무 이벤트도 낳지 않아 영원히 `CREATED` 로 남았다
(실측 전체의 96%). `OrderExpirySweeper` 가 1분마다 `stove.order.expire-after`(1시간)를 넘긴 건을
**배치 크기만큼만** 집어 닫는다. `CANCELED` 와 나눠 두는 이유는 **되돌릴 것이 있었는가**가 다르기
때문이고, **이벤트를 내지 않는** 이유는 아무도 반응하지 않는 메시지를 밀린 건수만큼 내지 않기 위해서다.
지표 `stove.order.pending`·`stove.order.expirable`(게이지), 알람 `OrderExpirySweepFalling`.

**HTTP API**

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/orders` | 주문 생성 (catalog 가격 재계산 경유) |
| GET | `/api/v1/orders/{orderNo}` | 주문 조회 |
| GET | `/api/v1/orders` | 내 주문 목록 |
| POST | `/api/v1/orders/{orderNo}/cancel` | 결제 전 취소 |

**이벤트** — 수신 `PaymentCompleted`·`PaymentCancelled` / 발행 `OrderCreated`·`OrderCanceled`

**규칙**

- **검증 게이트 1단계.** 클라이언트가 보낸 `expectedAmount` 는 화면-서버 불일치 감지용일 뿐이고,
  주문 금액은 catalog 가 확정한 값만 쓴다. 다르면 `PRICE_MISMATCH`.
- catalog 호출이 실패하면 주문을 만들지 않는다 — 가격 미확정 상태로 결제에 넘기지 않는다.
- 이 동기 호출은 트랜잭션 밖(`PlaceOrderFacade`)에서 일어나고, DB 변경과 이벤트 적재만 트랜잭션 안에 있다.
- 주문번호는 `ORD + yyyyMMdd + 난수 10자리` — 날짜는 운영 조회용, 난수는 추측 방지용.

---

## payment

PG 연동. **검증 게이트 4단계 중 3개가 여기 있다.**

**상태머신**

```
READY ──prepare──▶ PENDING ──callback──▶ PAID ──cancel──▶ CANCELED
                                     └──▶ FAILED
```

**HTTP API**

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/payments/{orderNo}` | 결제 조회 |
| POST | `/api/v1/payments/{orderNo}/prepare` | PG 사전등록 → 결제창 URL |
| POST | `/api/v1/payments/callback` | PG 승인 콜백 수신 |
| POST | `/api/v1/payments/{orderNo}/cancel` | 환불 |

**이벤트** — 수신 `OrderCreated`·`LicenseIssueFailed` / 발행 `PaymentCompleted`·`PaymentCancelled`

**규칙**

- **게이트 2** — 승인 전에 서버가 확정한 금액을 PG 에 먼저 등록한다.
  **주문이 `stove.payment.window`(30분)를 넘겼으면 여기서 막는다**(`PAYMENT_WINDOW_EXPIRED`).
  금액은 주문 시각에 한 번 굳으므로 이 창이 곧 "옛 가격이 유효한 기간" 이다(D-029).
- **게이트 3** — 콜백의 승인 금액이 사전등록 금액과 다르면 승인을 확정하지 않고 `PAYMENT_AMOUNT_MISMATCH`.
  위·변조 또는 연동 오류이므로 운영 알람 대상으로 남긴다.
- **결제창 만료** — 사전등록 뒤 `stove.payment.checkout-window`(15분)를 넘겨 도착한 승인은
  **거절하지 않고 받아 적은 뒤 자동 환불한다.** 그 시점엔 PG 에서 이미 돈이 움직였으므로
  거절하면 우리 장부에만 없는 상태가 되어 대사가 깨진다. 이때 `PaymentCompleted` 는
  **내보내지 않는다** — 일어나지 않을 판매를 하위 서비스에 알리지 않기 위해서다.
  지표 `stove.payment.auto-refunded`, 알람 `AutoRefundsRising`.
- **게이트 4** — 중복 콜백은 상태와 `idempotency_key` 유니크로 흡수하고 **이벤트를 재발행하지 않는다.**
- **중단된 취소 재개.** `CANCELING` 은 "PG 환불을 요청하기로 커밋했는데 확정까지 못 갔다",
  즉 **돈이 나갔는지 불확실한 상태**다. `RefundSweeper` 가 1분마다 깨어나 **다음 시도 시각이 된**
  건을 집어 재개한다 — 안전한 근거는 PG 취소의 `pgTxId` 멱등 계약 하나다.
- **재시도 예산.** 각 건의 다음 시도는 `RefundRetryPolicy` 가 미룬다(2→4→8→16→30분 상한).
  **주기와 간격은 다른 값이다** — 예전에는 그 구분이 없어 PG 가 죽어 있으면 같은 건에 1분마다
  요청이 나갔고, 그건 복구 중인 PG 를 계속 두드리는 것이었다.
  **포기 상태는 없다.** Outbox 는 예산이 소진되면 `DEAD` 로 보내지만(D-003), 여기서 그렇게 하면
  **불확실이 해소된 것처럼 보이고 아무도 다시 보지 않는다.** 예산(`refund-budget`, 1시간)은
  재시도를 멈추는 값이 아니라 **사람을 부르는 값**이다.
  지표 `stove.payment.canceling`·`stove.payment.canceling.stale`(게이지),
  `stove.payment.refund-resume-failed`(카운터),
  알람 `RefundsStuckInCanceling`·`RefundsStuckBeyondBudget`.
- **Saga 보상.** `LicenseIssueFailed` 를 받으면 자동 환불한다("돈은 빠졌는데 게임은 없는" 상태 해소).
  사용자 환불과 규칙은 같지만 진입점(`compensate`)이 분리돼 있다 — 이벤트 경로만 멱등 마킹이 필요하기 때문.
  **이 경로는 운영에서 한 번도 지나가지 않았다**(실측 0건) — D-027 이 조건을 좁혔기 때문이다.
  그래도 지우지 않는다: 0 은 "필요 없다" 가 아니라 **"그 실패가 아직 안 났다"** 는 뜻이다.
  대신 `stove.payment.compensated` 로 세고, `PaymentCompensationTest` 가 실 인프라에서 그 경로를 지난다.

---

## license

소유권(라이선스/CD키). **멱등성이 이 도메인의 핵심**이다.

**상태머신** — `ACTIVE ──revoke──▶ REVOKED`

**HTTP API**

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/library` | 내 보유 라이브러리 |

**이벤트** — 수신 `PaymentCompleted`·`PaymentCancelled` / 발행 `LicenseIssued`·`LicenseRevoked`·`LicenseIssueFailed`

**규칙**

- 지급은 결제 완료 이벤트로만 발생한다.
- `(order_no, product_id)` 유니크 제약으로 **한 주문의 한 상품은 한 번만 지급**된다.
  존재 확인과 DB 제약을 이중으로 건다.
- 재시도까지 소진된 지급 실패는 `LicenseIssueFailed` 를 발행해 결제 환불을 유도한다.
  이 보상 이벤트는 `REQUIRES_NEW` 로 별도 커밋된다 — 지급 트랜잭션이 롤백된 뒤에도 반드시 나가야 하기 때문.

---

## download

배포와 다운로드. **license 를 동기 호출하지 않는다.**

**HTTP API**

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/downloads/{productCode}/ticket` | 다운로드 인증 → CDN 서명 URL |
| GET | `/api/v1/downloads/{productCode}/manifests` | 버전 목록(패치 이력) |

**이벤트** — 수신 `BuildUploaded`·`ProductChanged`·`LicenseIssued`·`LicenseRevoked` / 발행 없음

**규칙**

- 소유 여부는 이벤트로 받아둔 **권한 사본**(`Entitlement`)으로 판정한다.
  다운로드는 트래픽이 가장 크고, license 장애가 다운로드 장애로 번지면 안 되기 때문.
- 미보유 상품 요청은 403.
- 서명 URL 은 `DownloadUrlSigner` 포트로 발급한다. 짧은 수명의 토큰을 만들어
  **인증을 CDN 엣지에서 끝내고** 원본 서버가 매 요청을 인증하지 않게 한다.
- 모든 쓰기 경로가 문서 ID 고정 upsert 라 자연 멱등이다.

---

## settlement

매출 배분. 오픈마켓 구조상 가장 복잡한 영역이라 **집계 / 수수료 / 마감** 세 조각으로 나눴다.

**HTTP API**

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/settlements/orders/{orderNo}` | 주문 단위 원장(매출 + 환불 역산) |
| GET | `/api/v1/settlements/sellers/{sellerId}` | 판매자 월별 원장 |
| GET | `/api/v1/settlements/closings` | 월 마감 확정본 |
| POST | `/api/v1/settlements/close` | 수동 마감(배치 재실행용) |

**이벤트** — 수신 `PaymentCompleted`·`PaymentCancelled` / 발행 없음

**규칙**

- **수수료 정책** — 자체 판매(`SELF`)는 0%, 입점 판매(`PARTNER`)는 설정된 요율(기본 30%).
  판매자 ID 로 구분하며, 정책이 늘어나면 `FeePolicy` 만 확장한다.
- **환불 역산** — 환불 이벤트에는 항목 정보가 없다. 자기 원장의 `SALE` 레코드를 근거로
  부호를 뒤집어 상계하므로 **다른 서비스에 되묻지 않는다.**
- **금전 원장이므로 방어가 두 겹이다** — Inbox 가드 `(event_id, consumer_group)` 와
  도메인 유니크 `(order_no, product_id, record_type)`.
- **월 마감**은 재실행 안전하다. 이미 확정본이 있는 판매자는 건너뛰지 않고 **거기에 더한다**
  (`SellerSettlement#accumulate`). 건너뛰면 마감 후 도착한 지각 원장이 close 도장만 찍힌 채
  어느 확정본에도 안 들어간다 — [D-001](defects.md#d-001) 이 그 결함이다.
  순액이 0 이하인 판매자(환불이 매출 초과)는 세금계산서를 발행하지 않고 이월한다.
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

| 경로 | 대상 | 비고 |
|---|---|---|
| `/api/v1/storefront/**` | store | |
| `/api/v1/products/**` | catalog | **GET 만 허용** |
| `/api/v1/orders/**` | order | |
| `/api/v1/payments/**` | payment | |
| `/api/v1/library/**` | license | |
| `/api/v1/downloads/**` | download | |
| `/api/v1/studio/**` | studio | |
| `/api/v1/reviews/**` | review | 운영 |
| `/api/v1/settlements/**` | settlement | 운영 |

catalog 라우트에 `Method=GET` 조건이 걸려 있어서, 주문 금액을 재계산하는
`POST /api/v1/products/quote` 는 게이트웨이를 통해 호출할 수 없다.
**서비스 간 내부 호출로만 도달 가능하다.**

---

## 외부 연동 대역

외부 시스템은 전부 `core/port` 뒤에 있고, 구현체는 `infrastructure/` 에 있다.
**어느 것이 실제 동작이고 어느 것이 흉내인지** 여기서 구분한다.

| 포트 | 스텁 | 실제 어댑터 | 선택 방법 |
|---|---|---|---|
| `BuildStorage` (studio) | `MockBuildStorage` | **`S3BuildStorage`** | `stove.storage.provider` = `mock`(기본) / `s3` |
| `DownloadUrlSigner` (download) | — | **`CdnUrlSigner`**, **`S3PresignedUrlSigner`** | `stove.download.url-strategy` = `cdn`(기본) / `s3` |
| `PgClient` (payment) | `MockPgClient` | 없음 | — |
| `RatingBoardClient` (review) | `MockRatingBoardClient` | 없음 | — |
| `TaxInvoiceIssuer` (settlement) | `MockTaxInvoiceIssuer` | 없음 | — |

### 실제로 동작하는 것

**`S3BuildStorage` / `S3PresignedUrlSigner`** — AWS SDK v2 로 presigned URL 을 발급한다.
로컬은 MinIO, 운영은 S3 를 가정하며 둘 다 S3 API 라서 `endpoint` 만 다르다.
서버는 바이너리를 직접 받지 않는다 — 수 GB 짜리 빌드가 애플리케이션을 통과하지 않게
클라이언트가 스토리지로 바로 올리고 바로 받는다.
`MinIOContainer` 테스트가 발급한 URL 로 실제 업로드·다운로드까지 확인하고,
서명 없는 접근이 403 인 것도 함께 검증한다.

**`CdnUrlSigner`** — HMAC-SHA256 서명을 실제로 계산한다. 경로·회원·만료시각을 묶어
서명하는 구조는 CloudFront 서명 URL 과 같다. 다만 **검증하는 CDN 엣지가 없어 반쪽**이다.
서명 키 기본값 `local-dev-signing-key` 는 운영에서 반드시 교체해야 한다.

`DownloadUrlSigner` 에 어댑터가 둘인 것은 실제 운영 형태를 반영한 것이다 —
**S3 에 저장하고 CDN 으로 배포**하므로 저장 위치와 배포 경로는 독립적으로 고른다.

### 흉내만 내는 것

| 스텁 | 하는 일 | 빠진 것 |
|---|---|---|
| `MockBuildStorage` | `s3://stove-builds/{code}/{ver}/game.pak` 경로 문자열 조립 | 서명 없음. 파일이 실존하지 않는다 |
| `MockPgClient` | 거래 ID = UUID 앞 12자, 결제창 URL 문자열 조립 | **`cancel()` 이 로그만 찍는다 — 환불이 절대 실패하지 않는다.** 금액·통화를 받지만 검증하지 않는다 |
| `MockRatingBoardClient` | 접수번호 `GRAC-2026-00001` | 인메모리 카운터라 재기동하면 1부터. 실제 심의는 며칠 걸리는 비동기 프로세스이고 반려도 나온다 |
| `MockTaxInvoiceIssuer` | 발행번호 `TI-202607-001001` | 결정적이라 재실행 검증엔 유리하다. 실제 세금계산서는 전송·역발행 상태를 갖는 객체다 |

`MockPgClient` 의 금액 미검증은 설계상 문제가 아니다.
**검증 게이트 3단계(콜백 금액 대조)는 PG 가 아니라 `Payment.approve()` 가 자기 필드와 비교해 수행**하므로,
그 방어선은 스텁과 무관하게 실제로 동작한다.

### 스텁 공통의 한계

**예외를 던지지 않는다.** 타임아웃도, 거부도, 부분 실패도 없다.
그래서 license 의 Saga 보상 경로(`LicenseIssueFailed` → 자동 환불)는
지급 실패를 인위적으로 주입해야만 테스트할 수 있다.

### 교체 안전장치

스텁은 모두 `@Profile("!prod")` 가 걸려 있다.
`prod` 로 띄우면 스텁이 빠지고, 실제 어댑터가 없으면 **"no qualifying bean" 으로 기동이 실패한다.**
운영에서 스텁이 조용히 도는 것만은 일어나지 않게 하려는 의도다.

교체 대상이 이미 둘인 `BuildStorage` 와 `DownloadUrlSigner` 는 `@ConditionalOnProperty` 로
한쪽만 활성화된다. 같은 포트에 빈이 둘이 되면 기동이 실패하므로, 새 어댑터를 붙일 때는
반드시 조건을 함께 건다.

이 "반드시" 는 사람에게 거는 기대가 아니다 — ArchUnit 이 검사한다.
`Mock*`/`Stub*`/`Fake*` 로 시작하는 어댑터에 `@Profile` 이나 `@ConditionalOnProperty` 가
없으면 테스트가 깨진다(`스텁_어댑터는_격리한다`). 조건을 거는 것이 관례가 아니라 통과 조건이다.
