# 설계: Payment

## 아키텍처 위치

- 포트 8083, 저장소 MySQL. 외부 요청은 Gateway(8080)의 `/api/v1/payments/**` 라우트를 거친다.
- `core/{domain, port, service}` + `api/{controller, listener, scheduler}` + `adapter/` + `config` 경계를 따른다.
- 외부 PG 호출은 `core.port`(PgGateway·승인/조회/환불 포트), HTTP 구현은 `adapter`, 환경 바인딩·조립은 `config`가 담당한다. 업무 서비스·도메인은 `EventEnvelope`·외부 이벤트 전체를 입력받지 않고 api.listener의 Mapper가 내부 입력으로 변환한다.
- Payment는 가격을 Catalog·Store에서 다시 조회하지 않고 Order DB·주문 상태를 직접 바꾸지 않는다. Payment의 최종 결과 저장과 Order의 반영 사이에는 이벤트 전달 지연이 있다.

## 핵심 설계 결정

- **세 트랜잭션 경계로 분리한다.** ① 접수(요청 비교→결제 생성→Inbox→커밋, PG 미호출), ② 공급자 호출(저장된 고정 입력으로 트랜잭션·잠금 밖에서 호출), ③ 결과(행 잠금→결과 연결·금액 검증→상태 전이→결과 메타데이터·Outbox 저장→커밋).
- **고정 paymentId를 멱등 키로 쓴다.** 접수 시 발급한 paymentId는 재시작·응답 유실·DB 롤백·동시 호출에도 바뀌지 않는다. 공급자 거래 ID와 구분한다.
- **확인된 결과만 확정한다.** `PAID`·`FAILED`만 최종이고, 미확정·응답 유실·결과 저장 실패는 `PENDING`을 유지한다. 시간 경과·재시도 횟수만으로 실패로 바꾸지 않는다.
- **provider별로 대상을 선별한다.** SIMULATED와 PORTONE_TEST 대기 건이 서로를 가로막거나 다른 공급자를 호출하지 않는다. PortOne 승인 어댑터는 `PAID`만 확정하고 그 밖은 미확정으로 둔다.
- **웹훅은 조회 힌트다.** 서명·스토어·재전달을 검증해 내구 접수하되, 최종 결제/환불 증명이 아니라 서버 PG 조회를 깨우는 힌트로만 쓴다.
- **환불 실행과 원구매를 분리한다.** 환불은 고정 attemptId·고정 본문·3시간 창으로 PG를 취소/조회하고, 확정돼도 원 `purchase_payment`의 `PAID`·원금액을 바꾸지 않는다.
- **비용은 미확정(null), 정산은 Settlement가 소유한다.** PG 비용을 0원으로 만들지 않고, Payment에 정산 중계 API를 두지 않는다. Settlement가 PG 명세를 직접 조회한다.

## 상태 모델

```
[*] --> PENDING        (주문 요청·Inbox 저장. 미호출·결과 미확인 포함)
PENDING --> PENDING     (일시 오류·응답 유실·미확정 결과·3시간 후 조회만)
PENDING --> PAID        (일치하는 승인 확인)
PENDING --> FAILED      (일치하는 최종 거절 확인)
PENDING --> EXPIRED     (Order가 만료를 전달. 금융 결과를 만들지 않음. PG 관측 이력이 있는 접수 전 승인은 PG 재조회 뒤 READY·FAILED일 때만. 결정 126)
PENDING --> FAILED      (운영자 승인 보류 종결, reasonCode REVIEW_CLOSED. 결정 115)
PENDING --> EXPIRED     (접수 전 보류를 만료 통지 뒤 종결. 이벤트 없음. 결정 115)
[*] --> EXPIRED        (만료 뒤 늦은 OrderPlaced. 승인·조회 예약 없음)
```

- `PENDING`에는 아직 호출하지 않은 결제와 호출했으나 결과를 확인하지 못한 결제가 함께 있다. READY·PROCESSING·UNKNOWN을 별도 상태로 나누지 않는다.
- PortOne 개별 시도 FAILED는 상태 전이가 아니라 별도 이력이다. 같은 orderNo·paymentId로 재결제한다.
- `EXPIRED`는 Order가 소유한 만료 판단의 전달 결과이며 금융 성공/실패 제약을 만들지 않는다.

## 상태×이벤트 표 (코드 기준, 결정 143)

main `9fe2e18e`의 `apps/payment/src/main`에서 옮겼다. 새 정책이 아니다. 코드와 문서가 어긋나는 곳은 고치지 않고 절 끝 "찾은 불일치"에 적었다. 스케줄러는 모두 유스케이스에 위임만 한다: `PendingPaymentScheduler` → `ResolvePayment.execute()`, `PaymentApprovalScheduler` → `ResolvePaymentApproval.execute()`, `RefundLookupScheduler` → `ResolveRefundLookup.execute()`, `RefundRetryScheduler` → `ResolveRefundRetry.execute()`. 잠금 순서는 어디서나 `purchase_payment` → `payment_approval`/`refund_execution`이다.

### Payment (`Payment`, `purchase_payment`)

| 상태 | 뜻 |
|---|---|
| `PENDING` | 접수됨. 미호출과 결과 미확인을 함께 담는다. `nextAttemptAt` 필수(V30 `chk_purchase_payment_state`) |
| `PAID` | 공급자 승인 확정. 거래·결과 이벤트 ID·시각 필수, 실패 사유 없음 |
| `FAILED` | 공급자 거절 확정 또는 운영자 승인 보류 종결(`REVIEW_CLOSED`). 실패 사유 필수 |
| `EXPIRED` | Order 만료 전달. 거래·결과 이벤트·조회 예약 모두 NULL. 금융 결과 아님 |

| 현재 상태 | 트리거 | 가드(조건) | 다음 상태 | 부수 효과 | 코드 위치 | 근거 |
|---|---|---|---|---|---|---|
| (없음) | `OrderPlaced` 수신 | 생명주기 행 원구매 일치, 기존 결제·Inbox 일치(첫 전달인데 결제 있음/재전달인데 결제 없음은 CONFLICT), 같은 이벤트 ID의 다른 주문 거절, 만료 근거 없음 | `PENDING` | `paymentId` 발급, `nextAttemptAt=now`, PORTONE_TEST면 `nextFailureLookupAt=now`, 추적 부모 저장 | `ReceiveOrderPayment.execute` → `Payment.receive` | R1.2~R1.9, R11.2 |
| (없음) | 만료 뒤 늦은 `OrderPlaced` | `payment_order_lifecycle.expired()` | `EXPIRED` | 승인·조회 예약 없음 | `ReceiveOrderPayment.execute` → `Payment.expireUnpaid` | R11.3 |
| `PENDING` | `PendingPaymentScheduler` | `gateway.charge` 결과가 같은 키·주문·금액·통화·provider이고 APPROVED. PORTONE_TEST는 `AdmittedPortOnePgGateway`가 `ADMITTED`·`REVIEW_REQUIRED`·`SUCCEEDED` 승인 중 접수된(`admittedAt`) 것을 찾아 PG `inspect`가 PAID일 때만 결과를 준다 | `PAID` | `OrderPaymentCompleted` Outbox(`PaymentResultOutbox`), 거래·비용·`pgPaidAmount`/`pgDiscountAmount`·결과 이벤트 ID/시각 저장, `nextAttemptAt`·`lastErrorCode` 비움 | `ResolvePayment.processFinancialResult` → `Payment.confirm` | R3.1, R3.10, R4.7, R10.1~R10.2 |
| `PENDING` | `PendingPaymentScheduler` | 같은 연결의 DECLINED(현재 SIMULATED만 만든다. PortOne 어댑터는 PAID 외에 `PgUnavailable`) | `FAILED` | `OrderPaymentFailed` Outbox(reasonCode·reason), `nextFailureLookupAt` 비움 | `ResolvePayment.processFinancialResult` → `Payment.confirm` | R3.2, R4.7 |
| `PENDING` | `ResolvePaymentApproval`의 PAID 관측 | 위 PAID 행과 같은 경로를 동기 호출 | `PAID` | 위와 같음 | `ResolvePaymentApproval.completeObservation` → `ResolvePayment.execute` | R4.3, R4.11 |
| `PENDING` | `PendingPaymentScheduler` | 결과 없음·불일치(`PG_INVALID_RESULT`)·호출 장애(`PG_UNAVAILABLE`)·결과 트랜잭션 실패(`RESULT_UNCONFIRMED`) | `PENDING` | `nextAttemptAt=now+PAYMENT_RETRY_MS`, `lastErrorCode` 기록 | `ResolvePayment.defer` → `Payment.defer` | R2.4, R2.7, R3.3~R3.6 |
| `PENDING` | 웹훅 `POST /api/v1/payments/webhooks/portone` | 첫 접수(같은 ID·본문 재전달은 무변화), PORTONE_TEST | `PENDING` | `nextAttemptAt`을 더 이를 때만 수신 시각으로 앞당김 | `ReceivePortOneWebhook.execute` → `Payment.requestRecheck` | R6.3~R6.4 |
| `PENDING` | `OrderExpired` 수신 | 첫 만료 통지, `resultEventId` NULL, 활성 승인(`REQUESTED`·`ADMITTED`·`REVIEW_REQUIRED`·`FAILURE_CONFIRMED`) 없음 | `EXPIRED` | 조회 예약·`lastErrorCode` 비움, 이벤트 없음 | `ApplyOrderExpiry.execute` → `Payment.expireUnpaid` | R11.1, R11.4 |
| `PAID`·`FAILED` | `OrderExpired` 수신 | `resultEventId` 있음 | (유지) | `payment_order_lifecycle.financialConflict=true` | `ApplyOrderExpiry.execute` | R11.7 |
| `PENDING` | `PaymentApprovalScheduler`(승인 작업자) | 승인이 접수 전 만료로 닫힘(`expireRequested`·`expireAfterVerifiedReady`), Order 접수 응답이 만료(빈 영수증), 접수 전 실패 + 만료 근거, 또는 Order 실패 영수증 `EXPIRED`·만료 근거 | `EXPIRED` | 이벤트 없음 | `ResolvePaymentApproval.cycle`·`expireRequested`·`completeFailure` → `Payment.expireUnpaid` | R4.10, R11.5, R11.6, R11.8 |
| `PENDING` | `POST /api/v1/admin/approval-reviews/{approvalId}/closure` | PORTONE_TEST, `resultEventId` NULL, 승인이 접수됐거나 주문 만료 통지 없음, 종결 근거 CANCELLED·FAILED·READY | `FAILED` | `failureReasonCode=REVIEW_CLOSED`, `providerTransactionId`=보류 시도 txId, 결정 시각=종결 재조회 시각, `OrderPaymentFailed` Outbox | `CloseApprovalReview.execute` → `Payment.failAfterReview` | R13.5, 결정 115 |
| `PENDING` | 같은 종결 API | 접수 전 승인 + `payment_order_lifecycle` 만료 통지 있음 | `EXPIRED` | 이벤트 없음 | `CloseApprovalReview.execute` → `Payment.expireUnpaid` | R13.5, R13.9 |
| `PAID`·`FAILED` | 같은 결과의 재확정 | `supplierResult()`가 같음 / 다름 | (유지) | 같으면 무변화(Outbox 없음), 다르면 409 CONFLICT | `Payment.confirm` | R3.7~R3.9, R10.4 |
| `EXPIRED` | 늦은 확정 결과 | — | (유지) | CONFLICT로 거절, `ResolvePayment`가 `PG_INVALID_RESULT`로 defer 시도(`defer`는 `PENDING`만 바꿈) | `Payment.confirm` | R11.7 |

직교 필드:

| 필드 | 바뀌는 때 | 동작에 미치는 영향 | 코드 위치 |
|---|---|---|---|
| `nextFailureLookupAt`·`failureHistoryCheckedAt` | PORTONE_TEST 접수 시 now, 결과 처리·장애 때 재예약, 완전 수집 시 NULL, `FAILED`·`EXPIRED`는 NULL | `PAID` 뒤에도 실패 시도 이력 GET만 재개(`due`가 `nextFailureLookupAt<=now`도 고름). 금융 상태는 바꾸지 않음 | `Payment.recordFailureLookup`, `ResolvePayment.collectFailureHistory` |
| `payment_order_lifecycle.expiryEventId`·`expiredAt` | `OrderExpired` 첫 수신(Payment 없어도) | 늦은 `OrderPlaced`를 `EXPIRED`로, 승인 작업자·종결의 만료 판정 근거 | `PaymentOrderLifecycle.recordExpiry`·`expired` |
| `payment_order_lifecycle.financialConflict` | 확정 결과가 있는 결제에 만료 도착 | 표시만. 상태를 바꾸지 않음 | `PaymentOrderLifecycle.retainFinancialConflict` |

### PaymentApproval (`PaymentApproval`, `payment_approval`)

| 상태 | 뜻 |
|---|---|
| `REQUESTED` | 인증 결과 내구 접수. Order 접수 영수증 전. confirm 송신 없음 |
| `ADMITTED` | Order 접수 영수증 저장. 3시간 창 안에서 confirm 송신 가능 |
| `REVIEW_REQUIRED` | 송신 중단(`postStopped=true`)·운영 확인 필요. 사유는 `lastErrorCode` |
| `FAILURE_CONFIRMED` | 접수된 시도의 PG FAILED 확인, Order 실패 통지 영수증 대기. 새 승인 접수 막음 |
| `SUCCEEDED` | 결제 `PAID` 커밋 확인(터미널) |
| `FAILED` | 시도 실패 종료 또는 운영자 종결(터미널). 같은 주문 재결제 근거 |
| `EXPIRED` | 주문 만료로 닫힘(터미널) |

터미널(`terminal()`)은 `SUCCEEDED`·`FAILED`·`EXPIRED`이고, 닫을 때 `resolvedAt` 기록·`protectedPayload`·`nextAt` 제거(V29 `chk_payment_approval_resolution`). 비터미널 4개는 `active_guard`로 결제당 하나만 허용(`uk_payment_approval_active`). 아래 "작업자"는 `PaymentApprovalScheduler` → `ResolvePaymentApproval.execute`가 lease(`claim`)를 잡은 한 회다. 한 회의 끝에는 늘 `finish`가 다음 조회 시각을 정한다.

| 현재 상태 | 트리거 | 가드(조건) | 다음 상태 | 부수 효과 | 코드 위치 | 근거 |
|---|---|---|---|---|---|---|
| (없음) | `POST /api/v1/payments/{orderNo}/approvals` | 본인 결제, PORTONE_TEST, 결제 `PENDING`, 활성 승인 없음, txId(`supplierAttemptId`) 미사용. 같은 requestId는 같은 내용이면 기존 반환, 다르면 CONFLICT | `REQUESTED` | 본문 AES-GCM 봉인 저장, 토큰 지문, `nextAt=receivedAt`, `expiresAt=orderOccurredAt+30분`. PG·Order 미호출 | `RequestPaymentApproval.execute` → `PaymentApproval.receive` | R4.1~R4.2, R4.4 |
| `REQUESTED` | 작업자 | PG `inspect`=READY, 송신 중단 아님, Order `admit`이 영수증 반환 | `ADMITTED` | `admittedAt`, `firstPreparedAt`(최초 1회), 같은 회에 confirm 송신 가능 | `ResolvePaymentApproval.cycle` → `admit`·`prepare` | R4.3, R4.5 |
| `REQUESTED` | 작업자 | Order `admit`이 판매 제한(`OrderSaleRestricted`) | `REQUESTED` | `lastErrorCode=ORDER_SALE_RESTRICTED` | `PaymentApproval.recordSaleRestriction` | — |
| `REQUESTED` | 작업자 | Order `admit`이 빈 영수증(만료) | `EXPIRED` | 결제 `EXPIRED` | `ResolvePaymentApproval.cycle` → `expire` | R4.10 |
| `REQUESTED` | 작업자 | 미접수 + 주문 만료 통지 + `latestPgState` NULL | `EXPIRED` | PG 미호출, 결제가 `PENDING`이면 `EXPIRED` | `ResolvePaymentApproval.expireRequested` | R11.5, 결정 126 |
| `REQUESTED` | 작업자 | 미접수 + 만료 통지 + `latestPgState` 있음 → PG `inspect` 한 번 | PAID → `REVIEW_REQUIRED`(`PAID_WITHOUT_ADMISSION`) / 전액 CANCELLED → `REVIEW_REQUIRED`(`PG_CANCELLED`) / PENDING → `REVIEW_REQUIRED`(`PG_PENDING_AT_ORDER_EXPIRY`) / READY·FAILED → `EXPIRED` | 관측은 별도 잠금 트랜잭션 저장. `EXPIRED`는 잠금 안에서 `REQUESTED` 재확인, 결제 `EXPIRED`. 조회 장애(UNAVAILABLE)는 무변화 | `ResolvePaymentApproval.expireAfterLookup` | R11.5, 결정 126, D-044 |
| `REQUESTED`(보류 포함 미접수) | 작업자 | PG `inspect`=FAILED | `FAILED` | `failedAt` 기록, Order 통지 없음, 만료 통지가 있으면 결제 `EXPIRED`(없으면 `PENDING` 유지) | `PaymentApproval.failBeforeAdmission` | R5.1, R11.5 |
| `REQUESTED` | 작업자 | 미접수에서 PG PAID | `REVIEW_REQUIRED` | 사유 `PAID_WITHOUT_ADMISSION`, `reviewStartedAt` | `ResolvePaymentApproval.stop` → `stopPosts` | R12.4 |
| `REQUESTED`·`ADMITTED` | 작업자 | PG 전액 CANCELLED(같은 시도·취소 합계=총액) | `REVIEW_REQUIRED` | 사유 `PG_CANCELLED`. 저절로 닫지 않음 | `ResolvePaymentApproval.holdForCancellation` | R13.8, 결정 115 |
| `REQUESTED`·`ADMITTED` | 작업자 | confirm POST 거절(`ApprovalPostRejected`) / 근거 오류(`IllegalArgumentException`) / Order 접수 충돌(`BusinessException`) | `REVIEW_REQUIRED` | 사유 `PG_REQUEST_REJECTED`·`PG_INVALID_EVIDENCE`·`ORDER_ADMISSION_CONFLICT` | `ResolvePaymentApproval.execute`(catch) → `stop` | R4.12, R12.4 |
| `ADMITTED` | 작업자 | 접수됨 + 주문 만료 통지 | `REVIEW_REQUIRED` | 사유 `ORDER_EXPIRY_CONFLICT`. 같은 회에 조회는 계속 | `ResolvePaymentApproval.cycle` → `stop` | R11.7 |
| `ADMITTED` | 작업자 | 조회 결과가 PAID·FAILED·CANCELLED 아님, `postStopped=false`, `now ≥ firstPreparedAt+3시간`(`CONFIRM_WINDOW`), PG PENDING·READY | `REVIEW_REQUIRED` | 사유 `CONFIRM_WINDOW_EXPIRED`. 실패·새 키·결제 전이 없음, 본문 유지 | `ResolvePaymentApproval.holdAfterConfirmWindow` → `PaymentApproval.holdAfterConfirmWindow` | R4.6, 결정 124 |
| `ADMITTED` | 작업자 | PG READY, `canPost`(접수·준비됨, 중단 아님, `firstPreparedAt ≤ now < firstPreparedAt+3시간`, lease 유효) | `ADMITTED` | 복호화한 원래 본문으로 `gateway.submit`(confirm) 뒤 다시 `inspect`해 결과 반영 | `ResolvePaymentApproval.cycle` | R4.5, R4.9 |
| `ADMITTED`·`REVIEW_REQUIRED`(접수됨) | 작업자 | PG PAID → `ResolvePayment`가 결제 `PAID` 커밋 / 회 시작 시 결제가 이미 `PAID` | `SUCCEEDED` | 결제와 금액·통화 일치 확인 | `PaymentApproval.succeed` | R3.1, R4.11 |
| `ADMITTED`·`REVIEW_REQUIRED`(접수됨) | 작업자 | PG FAILED(검증된 시도 실패) | `FAILURE_CONFIRMED` → 같은 회에 `FAILED` | `failedAt`, `postStopped`; Order `fail` 통지 영수증 확인 뒤 `FAILED`. 영수증 `EXPIRED`이거나 만료 통지가 있으면 결제 `EXPIRED`, 아니면 결제 `PENDING`(같은 주문 재결제) | `recordFailure` → `completeFailure` → `fail` | R4.13, R11.6 |
| `FAILURE_CONFIRMED` | 작업자 | `admittedAt`·`failedAt` 있음(앞 회에서 통지 유실) | `FAILED` | 위와 같은 Order 통지 재시도 | `ResolvePaymentApproval.completeFailure` | R4.13, R4.14 |
| `REVIEW_REQUIRED` | 작업자 | 사유 `PG_INVALID_EVIDENCE`, 미접수·미준비·`failedAt` 없음, PG READY, 만료 통지, 결제 `PENDING` | `EXPIRED` | 결제 `EXPIRED` | `PaymentApproval.expireAfterVerifiedReady` | R11.8 |
| `REVIEW_REQUIRED` | 작업자 | PG CANCELLED | `REVIEW_REQUIRED` | 관측만 기록, 사유 유지 | `ResolvePaymentApproval.holdForCancellation` | R13.8 |
| `REVIEW_REQUIRED` | `POST /api/v1/admin/approval-reviews/{approvalId}/closure` | PORTONE_TEST, 같은 lease를 `claimForClosure`(`nextAt` 무시, 산 lease면 409 `APPROVAL_CLOSURE_BUSY`), 트랜잭션 밖 `inspect`, 잠금 안에서 lease 소유·`postStopped` 재확인, 근거 `closesReview()`(CANCELLED·FAILED·READY), 결제 미확정 | `FAILED` | 사유(`lastErrorCode`) 유지, lease 제거, `approval_closure` 저장, 결제 `FAILED`(`REVIEW_CLOSED`)+`OrderPaymentFailed` 또는 `EXPIRED` | `CloseApprovalReview.execute` → `closeAfterReview` | R13.1~R13.6, 결정 115 |
| `REVIEW_REQUIRED` | 같은 종결 API | PG PAID(`CLOSURE_PG_PAID`)·PENDING(`CLOSURE_PG_PENDING`)·결제 확정(`CLOSURE_PAYMENT_RESOLVED`)·조회 실패(`CLOSURE_PG_INVALID_EVIDENCE`·`CLOSURE_PG_UNAVAILABLE`) | `REVIEW_REQUIRED` | 관측 또는 `lastInspectionError` 기록, lease 반환, `nextAt`을 잡기 전 값으로 복원(지났으면 now) | `CloseApprovalReview.refusal`·`refuse`·`release` | R13.4, 결정 122 |
| 비터미널 | 작업자 회 종료 | lease 소유 | (유지) | `nextAt` = `REVIEW_REQUIRED`면 `now + ApprovalReviewRequeryInterval.after(reviewStartedAt, now)`, 그 밖은 `now + PAYMENT_RETRY_MS`; lease 제거 | `PaymentApproval.finish`, `ResolvePaymentApproval.nextCheck` | R13.10, 결정 122 |

직교 필드:

| 필드 | 바뀌는 때 | 동작에 미치는 영향 | 코드 위치 |
|---|---|---|---|
| `postStopped`·`lastErrorCode`(보류 사유) | `stopPosts`(사유가 `PAID_WITHOUT_ADMISSION`이면 덮지 않음), `recordFailure`는 `postStopped`만 | confirm 송신 금지, 보류 목록·게이지(`ApprovalReviewReason.KNOWN` 8개 + OTHER) 분류, 고객 안내 묶음, R11.8 자동 만료 자격 | `PaymentApproval.stopPosts` |
| `reviewStartedAt` | 처음 보류될 때 한 번 | 재조회 간격 계산, `reviewNotice.since`, 조사 기록 기준 시각. NULL(옛 행)이면 간격은 상한 | `stopPosts`, `ApprovalReviewRequeryInterval.after` |
| `latestPgState`·`latestPgObservedAt` | 유효한 `inspect` 성공 때(별도 잠금 트랜잭션, lease 소유자만) | 결정 126 만료 전 조회 여부, 결정 132 고객 안내 코드 | `recordObservation` |
| `lastInspectedAt`·`lastInspectionError` | `inspect` 실패(INVALID_EVIDENCE·UNAVAILABLE), 성공 시 오류 NULL | 오류가 있으면 안내는 `CHARGE_UNCONFIRMED` | `recordInspectionFailure` |
| `firstPreparedAt`(confirm 기한 = +3시간) | 접수 직후 최초 1회, 재시작·lease로 초기화 안 함 | `canPost`의 끝 경계, 결정 124 보류 경계 | `prepare`, `canPost`, `holdAfterConfirmWindow` |
| `expiresAt` | 접수 때 `orderOccurredAt+30분` 고정 | Order 접수 요청에 실어 보냄(만료 판단은 Order 몫) | `receive`, `admissionRequest` |
| `leaseToken`·`leaseUntil`·`nextAt` | `claim`·`claimForClosure`(잡는 동안 `nextAt=leaseUntil`), `finish` | 작업자와 운영 종결이 동시에 돌지 않게 함 | `claim`, `claimForClosure`, `finish` |
| `approval_closure`(V37) | 종결 성공 때 한 번(approvalId·requestId 유일) | 승인 `FAILED`를 운영 종결로 구분, `CLOSED_*` 안내 | `ApprovalClosure.record` |
| `approval_investigation`(V34·V38) | `REVIEW_REQUIRED`일 때 ADMIN POST | 기록뿐. 상태·일정 불변 | `RecordApprovalInvestigation.execute` |
| `reviewNotice`(파생, 저장 안 함) | 조회 시 계산 | `REVIEW_REQUIRED`: `PG_INVALID_EVIDENCE`면 `CHARGE_UNCONFIRMED`, 그 밖은 마지막 관측(PAID→`CHARGED`, CANCELLED→`CHARGE_CANCELLED`, READY·FAILED→`NOT_CHARGED`, PENDING·조회 오류·관측 없음→`CHARGE_UNCONFIRMED`). `FAILED`+종결 기록: CANCELLED→`CLOSED_CHARGE_CANCELLED`, 그 밖→`CLOSED_NOT_CHARGED` | `ApprovalReviewNotice.of`, `ReadApprovalReviewNotice`(결제 상세는 마지막 승인) |

### RefundExecution (`RefundExecution`, `refund_execution`)

| 상태 | 뜻 |
|---|---|
| `PENDING` | 접수됨. 금융 미확정이거나, 조회로 전액 취소를 확인했지만 우리 시도의 응답으로 귀속하지 못함(`PG_QUERY_UNATTRIBUTED`) |
| `SUCCEEDED` | 우리 취소 ID의 PG 성공 응답 확인(`PG_RESPONSE` 근거 또는 귀속 미확정 뒤 같은 취소 ID 성공) |
| `FAILED` | 우리 취소 ID FAILED + 원결제 PAID·전액 미취소를 함께 확인. 후속 실행(`previousAttemptId`)의 근거 |

고객 환불의 금융 완료는 상태가 아니라 `refundConfirmedAt`이 정한다. `PENDING`이면서 `refundConfirmedAt`이 있는 행이 귀속 미확정이다(V19 `chk_refund_payment_confirmation`).

| 현재 상태 | 트리거 | 가드(조건) | 다음 상태 | 부수 효과 | 코드 위치 | 근거 |
|---|---|---|---|---|---|---|
| (없음) | `RefundExecutionRequested` 수신(`stove.order.v1`, 그룹 `payment-refund-execution`) | 결제 `PAID`·연결값 일치, 같은 attemptId는 같은 요청+Inbox 재전달만, 요청 이벤트 ID 미사용, 결제의 첫 실행이면 `previousAttemptId` 없음, 후속이면 같은 refundId·검증된 `FAILED` 선행·선행당 하나, `licenseSuspensionEventId` 미사용 | `PENDING` | 접수만. PG 미호출, 추적 부모 저장 | `ReceiveRefundExecution.execute` → `RefundExecution.receive` | R7.1, R7.14 |
| `PENDING` | 같은 수신의 첫 송신(접수 커밋 뒤, `PAYMENT_REFUND_EXECUTION_ENABLED`) | 미확정·미실패. 이미 준비됐으면 송신 없이 조회만 | `PENDING` | `firstCancellationPreparedAt`(최초 1회), 조회 일정 `+5초`, `RefundExecutionPrepared` Outbox(한 번), 본문 고정, 송신 lease, 직전 GET 대조 뒤 취소 POST | `RequestFirstRefundCancellation` → `RequestRefundCancellation.executeFirst` → `prepareCancellation`·`freezeCancellationBody`·`dispatch` | R7.1~R7.2, R7.9~R7.10 |
| `PENDING` | `RefundRetryScheduler` | `findRetryCandidates`: 준비·본문 있음, 접수 응답·잔액 변화·자동 중단·활성 보류 없음, 송신 일정 due | `PENDING` | 같은 attemptId·본문으로 재POST, 일정 10초→30초→60초 | `ResolveRefundRetry.execute` → `claimRetry`·`executeRetry` | R7.7, R7.9 |
| `PENDING` | 송신·재송신의 준비 단계 | `now ≥ firstCancellationPreparedAt+3시간` | `PENDING` | `automaticCancellationStoppedAt` 기록, 송신 일정 중지(조회는 유지) | `RefundExecution.prepareCancellation` | R7.7, 결정 29 |
| `PENDING` | POST 응답: 정상 접수(REQUESTED·FAILED) | 같은 attemptId·원구매, 이전 접수와 같은 취소 근거 | `PENDING` | `receiptCancellationId`·`receiptState` 등 저장, 송신 일정 중지 | `RefundExecution.recordCancellationReceipt` | R7.11 |
| `PENDING` | POST 응답: 409 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN` | 준비 뒤 관측 | `PENDING` | `cancellationBalanceChangedAt`, 송신 일정 중지 | `RefundExecution.blockAutomaticCancellation` | R7.3 |
| `PENDING` | POST 응답: 403 / 정확한 400 `INVALID_REQUEST` | 같은 송신 claim 소유, 미확정 | `PENDING` | `refund_cancellation_hold`(사유 `PG_REQUEST_FORBIDDEN`·`PG_REQUEST_INVALID`) 저장, `activeCancellationHoldId`, 송신 일정 중지 | `RequestRefundCancellation.recordRejection` → `holdCancellation` | R7.8, R7.15 |
| `PENDING` | `POST /api/v1/admin/refund-cancellation-holds/{holdId}/resume` | 현재 보류 ID, 미확정, 3시간 창 안, 자동 중단·잔액 변화·접수 응답 없음, 새 requestId | `PENDING` | 송신 일정 `nextAt=now`(단계 유지), 보류 해제, 재개 기록 | `RequestRefundCancellationResume.execute` → `resumeCancellation` | R7.16 |
| `PENDING`(미확정) | POST 응답: 취소 성공 | 같은 attemptId·원구매, 접수 응답이 있으면 같은 취소 ID, 미실패 | `SUCCEEDED` | 첫 금융 확인(`refundConfirmationSource=PG_RESPONSE`), `RefundPaymentConfirmed`(PG_RESPONSE) Outbox, 두 일정 중지 | `RecordRefundCancellationSuccess.execute` → `confirmCancellationSuccess` | R7.4~R7.5 |
| `PENDING`(귀속 미확정) | POST 응답: 취소 성공(진행 중이던 응답이 늦게 도착) | 확인된 취소가 1건이고 같은 취소 ID·금액=원액·같은 포인트 | `SUCCEEDED` | 최초 금융 근거 유지, `RefundExecutionSucceeded`(최초 확인 이벤트 참조) Outbox 한 번 | 같은 메서드 | R7.13 |
| `PENDING`(미확정) | `RefundLookupScheduler`(due 또는 웹훅 wake) / 송신 뒤 조회 | 우리 접수 응답 없으면 `find`, 있으면 `inspect`; 검증된 전액 취소(`PgRefundConfirmation`) | `PENDING`(귀속 미확정) | `refundConfirmedAt`·`PG_QUERY_UNATTRIBUTED`·`providerRefundedAt`·취소 내역 저장, `RefundPaymentConfirmed` Outbox(`RefundConfirmationOutbox`), 두 일정 중지 | `ResolveRefundLookup` → `ReconcileRefundPayment.execute` → `confirmRefund` | R7.4~R7.5, R7.12 |
| `PENDING`(미확정) | 같은 조회 | 연결된 취소 ID FAILED + 원결제 PAID·전액 미취소(`PgRefundFailureConfirmation`), 금융 완료 없음 | `FAILED` | `failureEventId`·`failureConfirmedAt`·`failureLookupHash`, `RefundExecutionFailed` Outbox, 두 일정 중지 | `ReconcileRefundPayment.execute` → `confirmFailure` | R7.6 |
| `PENDING`(미확정, 3시간 지남) | `POST /api/v1/admin/refund-delays/{attemptId}/closure` | PORTONE_TEST, `closableAt`(`confirmationDelayedSince≠null`), 종결 기록 없음, 조회 일정 lease를 `claimForClosure`(산 lease면 `REFUND_CLOSURE_BUSY`), 트랜잭션 밖 `find`/`inspect`가 전액 취소 | `PENDING`(귀속 미확정) | 조회 완료와 같은 `confirmRefund`+`RefundPaymentConfirmed`, `refund_closure`(`COMPLETED`) | `CloseRefundDelay.execute` | R14.2~R14.5(환불), 결정 127 |
| `PENDING`(귀속 미확정) | 같은 종결 API | `unattributed()`, 종결 기록 없음, lease 없음(일정 멈춤), 재조회 근거가 저장된 취소 내역과 같음(다르면 409 `CONFLICT`) | `PENDING` | 금융·이벤트 불변, `refund_closure`(`RECORDED`) | `CloseRefundDelay.execute` | R14.5(환불), 결정 127 |
| `PENDING` | 같은 종결 API | 전액 취소 확인 없음(`REFUND_CLOSURE_PG_NOT_CANCELLED`)·근거 오류·조회 장애 / 대상 아님(`REFUND_NOT_CLOSABLE`) | (유지) | lease만 반환, 작업자 `nextAt`·`wakeAt` 그대로. 실패를 확정하지 않음 | `CloseRefundDelay.refuse`·`release` | R14.2, R14.4(환불) |
| `SUCCEEDED`·`FAILED` | 같은 근거 재관측 | 같은 근거 / 다른 근거 | (유지) | 무변화 / 409 CONFLICT. `FAILED` 뒤 성공 근거, 금융 완료 뒤 실패 근거는 상태를 바꾸지 않음 | `confirmCancellationSuccess`·`confirmFailure`·`confirmRefund` | R7.6, R7.13 |

직교 필드:

| 필드 | 바뀌는 때 | 동작에 미치는 영향 | 코드 위치 |
|---|---|---|---|
| `refundConfirmedAt`·`refundConfirmationSource`(`PG_QUERY_UNATTRIBUTED`·`PG_RESPONSE`)·`providerRefundedAt`·`refundConfirmationEventId`·`refund_cancellation_evidence` | 첫 금융 확인 때 한 번 | 금융 완료. 이후 송신·조회·실패 확정 금지. `PG_QUERY_UNATTRIBUTED`+`PENDING`이 귀속 미확정(`unattributed()`) | `confirmRefund`, `confirmCancellationSuccess` |
| 3시간 창: `firstCancellationPreparedAt`·`cancellationResumeDeadline()`·`automaticCancellationStoppedAt` | 최초 준비 / 창 경과 뒤 준비 단계 | 창 안에서만 자동 POST·운영 재개. 창 뒤 미확정은 장기 미확정(`confirmationDelayedSince`) | `prepareCancellation`, `RefundCancellationWindow` |
| `cancellationBalanceChangedAt` | 409 잔액 변화 | 자동 POST 영구 중단, 재개 불가 | `blockAutomaticCancellation` |
| `receiptCancellationId`·`receiptState` | 정상 접수 응답 | 자동 POST 중단, 조회를 `inspect`(연결 조회)로 바꿈, 실패 확정의 전제 | `recordCancellationReceipt` |
| 환불 취소 보류 `activeCancellationHoldId`(`RefundCancellationHold`) | 403·정확한 400 | Kafka 재전달·재송신 스캔 어디서도 POST 안 함. ADMIN 재개만 해제 | `holdCancellation`, `resumeCancellation` |
| 조회 일정 `RefundLookupSchedule`(`nextAt`·`stage`·`claimId`·`leaseUntil`·`wakeAt`) | 준비 시 `+5초`, 회마다 5·15·35초 뒤 1분(밀린 단계 건너뜀), 웹훅 `wake`, 금융 확정·실패 시 `stop` | GET만 함. 3시간 뒤에도 유지. 멈춘 일정은 lease를 잡을 수 없음(`ck_refund_lookup_lease`) | `prepare`·`claim`·`finish`·`wake`·`stop`·`claimForClosure` |
| 송신 일정 `RefundCancellationSchedule` | claim·finish(10→30→60초), 접수·보류·잔액 변화·확정 시 `stop`, 재개 시 `resume` | 재POST 시각과 송신 lease | `RefundCancellationSchedule` |
| 결정 127 종결 `refund_closure`(V39) | 종결 성공 때 한 번(attemptId·requestId 유일) | 귀속 미확정 목록·게이지 `stove.payment.refund.unattributed.open`에서 빠짐, 상세 `reconciliationRequired=false`. 재요청 응답은 종결 기록에 고정한 첫 상태(`PENDING`·`PG_QUERY_UNATTRIBUTED`)를 반환 | `RefundClosure.record`, `CloseRefundDelay.receipt` |
| 조사 기록 `refund_investigation`(V27) | ADMIN POST, `confirmationDelayedSince≠null`일 때만 | 기록뿐. 금융 확정 뒤 새 기록은 409 | `RefundInvestigation.record` |

### 웹훅 수신이 깨우는 전이 (`ReceivePortOneWebhook`)

서명·스토어·본문 검증을 통과한 첫 접수만 아래를 한다. 같은 웹훅 ID·같은 본문은 무변화, 다른 본문은 409, 결제가 없으면 404, 다른 공급자면 409다. 웹훅 자체는 어떤 상태도 바꾸지 않는다.

| 웹훅 | 깨우는 것 | 이어지는 전이 | 깨우지 않는 것 |
|---|---|---|---|
| 모든 유효 종류 | `Payment.requestRecheck`: 결제가 `PENDING`이면 `nextAttemptAt`을 수신 시각으로 앞당김(더 이른 값만) | `PendingPaymentScheduler`의 Payment `PENDING → PAID`(접수된 승인이 있고 PG PAID일 때만) | `PaymentApproval.nextAt`(승인 작업자 일정). 그래서 접수된 보류 승인은 결제가 먼저 `PAID`가 돼도 `SUCCEEDED`는 다음 보류 재조회(최대 10분) 때 된다 |
| `Transaction.CancelPending`·`PartialCancelled`·`Cancelled` | 그 결제의 마지막 `PENDING`·`refundConfirmedAt` NULL 실행의 `RefundLookupSchedule.wake`(일정이 살아 있을 때만, 정기 `nextAt`·단계는 그대로) | `RefundLookupScheduler`의 RefundExecution 조회 전이(귀속 미확정 완료 또는 `FAILED`) | 금융 확정·실패한 실행, 준비 전 실행(`nextAt` NULL), 취소 POST |

### 찾은 불일치 (결정 143 1부)

줄 번호는 main `9fe2e18e` 기준이다. `design.md` 줄은 이 절을 넣기 전 번호다.

1. **R14가 두 번 있다.** `requirements.md:211`(금융 보류 고객 안내, 결정 123)과 `requirements.md:222`(장기·귀속 미확정 환불 종결, 결정 127). 참조가 어느 쪽인지 문맥으로만 갈린다. 고객 안내 쪽: `design.md:63`·`design.md:67`, `requirements.md:189`·`209`·`239`. 환불 종결 쪽: `requirements.md:8`·`141`·`144`, `design.md:212`("R14.1"), `docs/runbooks/refund-delay-closure.md:3`, 코드 주석 `RefundClosure.java:45`·`CloseRefundDelay.java:158`, 테스트 `RefundDelayClosureIntegrationTest.java:205`. 영향: 없음(번호만).
2. **이미 보류된 승인의 사유가 다음 보류 사유로 덮인다.** `PaymentApproval.stopPosts`(`PaymentApproval.java:176-184`)는 사유가 `PAID_WITHOUT_ADMISSION`일 때만 `lastErrorCode`를 지키고, `ResolvePaymentApproval`은 이미 `REVIEW_REQUIRED`인 승인에도 `stop`을 부른다: 조회 근거 오류 → `PG_INVALID_EVIDENCE`(`ResolvePaymentApproval.java:83`), Order 충돌 → `ORDER_ADMISSION_CONFLICT`(`:84`), 접수됨+만료 통지 → 매 회 `ORDER_EXPIRY_CONFLICT`(`:109`). 사유를 지키는 곳은 CANCELLED 관측뿐이다(`:258-264`). 문서는 CANCELLED(`requirements.md:207` R13.8)와 confirm 기한 보류(`design.md:128` "다른 사유로 이미 보류된 승인은 사유를 바꾸지 않는다")만 말하고, 일반 규칙은 적혀 있지 않다. 그래서 예를 들어 `PG_CANCELLED`·`CONFIRM_WINDOW_EXPIRED` 보류 건이 조회 근거 오류를 한 번 만나면 `PG_INVALID_EVIDENCE`가 된다. 그러면 결정 132의 관측 기준 안내가 `CHARGE_UNCONFIRMED`로 고정되고(`ApprovalReviewNotice.java:50`), R11.8의 READY 자동 만료 대상이 된다(`PaymentApproval.java:188`). 반대로 `PG_INVALID_EVIDENCE` 건은 `ORDER_EXPIRY_CONFLICT`로 바뀌면 관측 기준 안내로 넘어간다. 영향: 돈 이동 없음(종결·만료는 여전히 READY·FAILED·전액 CANCELLED 근거만 쓴다). 고객 안내 코드, 보류 게이지의 사유 분류, 자동 만료 여부는 바뀐다. 코드 읽기로 확인했고 테스트로 재현하지는 않았다. 의도인지는 확인 못 함. — 해소: 사용자가 "처음 보류 사유 유지"를 골랐다([결정 144](../../../docs/decisions.md#held-approval-keeps-first-reason), PR #185). R12.10.
3. **웹훅이 승인 작업자를 깨우지 않아 고객 안내가 결제 상태보다 늦을 수 있다(추정).** 웹훅은 `Payment.requestRecheck`만 부른다(`ReceivePortOneWebhook.java:39`). `AdmittedPortOnePgGateway.java:21-22`는 `REVIEW_REQUIRED` 승인으로도 결제를 `PAID`로 확정한다. 하지만 `reviewNotice`는 승인의 `latestPgState`로 정해지므로(`ApprovalReviewNotice.java:62`, `ReadApprovalReviewNotice.java:27`), 다음 보류 재조회(최대 10분, 결정 122) 전까지는 결제가 `PAID`인데도 안내가 옛 관측을 따른다. 예: READY를 본 `CONFIRM_WINDOW_EXPIRED` 건이면 `NOT_CHARGED`("새 주문으로 다시 구매하실 수 있습니다")가 나간다. 결정 132가 적은 한계("관측은 재조회 간격만큼 늦을 수 있다")에 들어가는지는 확인 못 함. 영향: 불명(중복 구매 안내 가능성. 돈이 직접 움직이지는 않음). — 해소: [D-051](../../../docs/defects.md#d-051)(PR #184)에서 `ResolvePayment.confirm`이 결제 `PAID` 커밋과 같은 트랜잭션에서 접수된 보류 승인을 `SUCCEEDED`로 닫는다.
4. **환불 취소 보류 사유 이름.** `design.md:144`는 POST 403의 중단을 `FORBIDDEN`이라고 쓰지만, 코드와 DB는 `PG_REQUEST_FORBIDDEN`이다(`RefundCancellationHold.java:36`, `V24__refund_forbidden_hold.sql:18`, `V25__refund_invalid_request_hold.sql:4`). 400 쪽 `PG_REQUEST_INVALID`는 같다. 영향: 없음.
5. **보류 사유 개수.** `design.md:173`은 8개, `design.md:177`은 "7개 KNOWN"이라고 쓴다. 코드 `ApprovalReviewReason.KNOWN`은 8개다(`ApprovalReviewReason.java:7`, `CONFIRM_WINDOW_EXPIRED` 포함). 영향: 없음.
6. **환불 종결 R14.6과 R14.5의 문장 충돌.** `requirements.md:231`(R14.6)은 종결이 "최초 금융 확인을 바꾸지 않는다"고 쓰지만, `requirements.md:230`(R14.5)과 코드는 미확정 실행에서 첫 금융 확인을 새로 만든다(`CloseRefundDelay.java:111` → `RefundExecution.java:291-296`, `RefundPaymentConfirmed` 발행). "이미 있는 확인을 덮지 않는다"는 뜻으로 보인다(추정). 영향: 없음(코드는 R14.5·결정 127대로 동작한다).
7. **R12.9 링크 앵커가 깨졌다.** `requirements.md:194`가 `design.md#승인-보류-운영-조회-결정-112-part-1`을 가리키지만, 실제 제목은 `### 승인 보류 운영 조회 (결정 114, 1부)`다(`design.md:167`). 영향: 없음.
8. **결정 124·126·127·132와 코드 가드는 일치한다.** 결정 124: `ADMITTED`·송신 중단 전·`firstPreparedAt+3시간` 경과·조회 결과 PENDING/READY에서만 보류한다(`PaymentApproval.java:160-167`). 늦은 PAID·FAILED는 원래 경로로 끝난다. 결정 126: `REQUESTED`·미접수·만료 통지·`latestPgState≠null`일 때만 한 번 조회하고, 결과별 분기도 결정 원문과 같다(`ResolvePaymentApproval.java:101-107`, `:177-184`). 결정 127: 대상(`RefundExecution.java:45-47`), lease 처리, 전액 취소 근거만 인정, PG 취소 없음, `COMPLETED`/`RECORDED` 모두 원문대로다. 결정 132: 묶음과 관측 매핑이 원문과 같다(`ApprovalReviewNotice.java:23-31`, `:61-68`). 다만 2번 때문에 결정 124의 "사유 유지"와 결정 132의 관측 기준 안내가 보류 이후에도 지켜진다고는 말할 수 없다. 영향: 해당 없음(일치 확인).

## 데이터 모델

| 테이블 | 내용 |
|---|---|
| `purchase_payment` | 결제 본체. paymentId·orderNo·requestEventId·orderOccurredAt·buyerSubject·상품 스냅샷·listAmount/discountAmount/amount/currency·provider·status·providerTransactionId/providerOccurredAt·resultEventId/resultOccurredAt·failureReasonCode/failureReason·nextAttemptAt/lastErrorCode·createdAt/updatedAt |
| 모의 PG 원장(simulators/mock-pg 전용 DB) | paymentId 유일 키·주문/금액/통화·공급자 거래 ID·APPROVED/DECLINED·사유·결정 시각. Payment 엔티티를 참조하지 않고 독립 커밋 |
| 웹훅 접수(`payment_pg_webhook`) | 웹훅 ID·본문 해시·재조회 예약. 같은 ID·본문은 최초 기록 유지 |
| 승인 접수·환불 실행·취소 예약/중단·실패 시도 이력·비용 스냅샷 | 암호화 고정 본문·토큰 지문·최초 준비 시각·3시간 창·lease·attemptId·조사 기록 등. 후속 마이그레이션으로 추가 |
| `payment_request_trace`(V13) | 최초 접수의 W3C v00 추적 부모. 누락·이상·중복 헤더는 부모 없음, 재전달로 덮어쓰지 않음(결정 28) |
| `failed_payment_attempt`(V28) | 공급자 transactionId별 불변 실패 근거·공개 attemptId·`next_failure_lookup_at`. 공급자 원문 사유 미저장 |
| 승인 접수(V29) | 암호화 고정 본문·토큰 지문·원구매·승인 상태·최초 준비·다음 일정·lease, 명령/공급자 시도/진행 중 작업 유일 키 |
| `payment_order_lifecycle`(V31) | 주문별 잠금과 최초 만료 근거. Payment 없이도 존재, cascade 삭제 없음 |
| `pg_paid_amount`·`pg_discount_amount`(V32) | PG 실결제/할인 쌍. NULL 일치·`PAID`·음수 금지·합계=amount CHECK |
| `approval_investigation`(V34·V38) | 승인 보류 조사의 append-only 기록. V38부터 승인 FK에 삭제 cascade 없음(RESTRICT) |
| `approval_closure`(V37) | 승인 보류 운영 종결의 append-only 기록. approvalId·requestId(각각 유일)·종결자·note·근거 상태(CANCELLED·FAILED·READY)·관측/종결 시각. 승인 FK에 삭제 cascade 없음 |
| `refund_closure`(V39) | 장기·귀속 미확정 환불 운영 종결의 append-only 기록(결정 127). attemptId·requestId(각각 유일)·종결자·귀속 판정(OUR_REQUEST·EXTERNAL_CANCELLATION·UNDETERMINED)·note·결과(COMPLETED·RECORDED)·PG 취소 시각·관측/종결 시각, 첫 응답의 실행 상태·최초 금융 확인 시각·출처(재요청 응답용, 상태 PENDING·출처 PG_QUERY_UNATTRIBUTED CHECK). 실행 FK에 삭제 cascade 없음 |
| `refund_failure_closure`·`refund_failure_closure_cancellation`(V40) | 자동 송신이 멈춘 환불의 ADMIN 실패 종결 append-only 기록(결정 128). attemptId·requestId·실패 이벤트 ID(각각 유일)·종결자·note·근거 종류(NO_LINKED_CANCELLATION·LINKED_CANCELLATION_FAILED)·중단 원인(CANCELLATION_HOLD·BALANCE_CHANGED·WINDOW_ENDED)·PG 조회 스냅샷 해시·이벤트 취소 ID·관측/종결 시각, 첫 응답의 실행 상태(FAILED CHECK)·실패 확정 시각, 관측한 FAILED 취소 행(ID·요청 시각·면세·부가세·reason·포인트). 실행 FK에 삭제 cascade 없음. 같은 V40이 `refund_execution`에 늦은 전액 취소 발견·PG 취소 시각과 송신 세대(`cancel_dispatch_generation`, 기본 0, 송신 claim·운영 재개마다 증가)를 더하고, 실패 CHECK를 "PG FAILED 접수·조회 일정 중지 필수"에서 "실패 이벤트·시각·해시·금융 미확정"으로 넓힌다(`chk_refund_execution_failure_v40`) |

- paymentId·orderNo·requestEventId·nullable resultEventId와 nullable `(provider, providerTransactionId)`에 각각 유일 제약을 둔다. 문자열 식별자는 대소문자를 구분하고 `(status, nextAttemptAt, id)`·`(buyerSubject, id)` 조회를 지원한다.
- V1~V10 체크섬을 보존하고 `V11`이 `purchase_payment`를 추가한다. 이후 V12 비용 스냅샷(외부 모의 PG 전환), PortOne 웹훅·checkout·승인 접수·환불 취소/조회·실패 시도 이력·암호화 본문·EXPIRED 허용 등을 후속 마이그레이션으로 쌓았다.
- 모델 속성은 `docs/domain/models/order-payment.md#payment` 참조.

## API 계약

| 메서드 | 경로 | 역할 | 동작 |
|---|---|---|---|
| GET | `/api/v1/payments?page=0&size=20` | MEMBER | 본인 결제 목록(`createdAt DESC, id DESC`) |
| GET | `/api/v1/payments/{orderNo}` | MEMBER | 본인 주문의 결제 상세. 금융 보류·보류 종결 건은 `reviewNotice`(R14, 결정 123) |
| GET | `/api/v1/payments/{orderNo}/checkout` | MEMBER | 본인 `PENDING`/`PORTONE_TEST`의 SDK 설정. `no-store`. 생성·승인 아님 |
| GET | `/api/v1/payments/{orderNo}/attempt-failures?page=0&size=20` | MEMBER | 본인 실패 시도 이력·수집 상태. `no-store`, DB 조회만 |
| POST | `/api/v1/payments/{orderNo}/approvals` | MEMBER | PortOne 승인 내구 접수(202). `PORTONE_TEST` 모드만 |
| GET | `/api/v1/payments/{orderNo}/approvals/{requestId}` | MEMBER | 승인 진행 조회. `no-store`, 토큰·암호문 없음. 보류·보류 종결 건은 `reviewNotice`(R14) |
| POST | `/api/v1/payments/webhooks/portone` | 공개(서명) | 서명·스토어 검증 뒤 조회 힌트 접수. 공개 edge가 직접 전달 |
| GET/POST | `/api/v1/admin/refund-cancellation-holds` | ADMIN | 운영 중단 목록·상세·재개 |
| GET/POST | `/api/v1/admin/approval-reviews` | ADMIN | REVIEW_REQUIRED 목록(reason 필터)·상세·조사 이력 GET(종결 기록 포함), append-only 조사 기록 POST |
| POST | `/api/v1/admin/approval-reviews/{approvalId}/closure` | ADMIN | PG 재조회 근거로 보류 종결(`PORTONE_TEST`만). `no-store`, requestId 멱등. PG 취소·승인 없음 |
| GET/POST | `/api/v1/admin/refund-delays` | ADMIN | 장기 미확정(`kind=DELAYED`, 기본)·귀속 미확정(`kind=UNATTRIBUTED`)·실패 뒤 늦은 전액 취소(`kind=LATE_CANCELLATION`) 목록·상세(종결·실패 종결 기록 포함)·조사 이력 GET, 조사 기록 POST |
| POST | `/api/v1/admin/refund-delays/{attemptId}/closure` | ADMIN | PG 재조회로 같은 원결제 전액 취소를 확인할 때만 종결·귀속 판정 기록(`PORTONE_TEST`만, 결정 127). `no-store`, requestId 멱등. PG 취소 없음 |
| POST | `/api/v1/admin/refund-delays/{attemptId}/failure-closure` | ADMIN | 자동 송신이 멈춘 실행을 PG 재조회로 PAID·취소액 0·모든 취소 FAILED를 확인할 때만 실패로 닫고 `RefundExecutionFailed`를 낸다(`PORTONE_TEST`만, 결정 128). `no-store`, requestId 멱등(첫 응답 값 저장). PG 취소 없음. Gateway 허용 목록에 있음 |

- 익명 401, 역할 없음 403, 타인·없는 결제 404, 잘못된 페이지 400. checkout의 로컬 `PAID`/`FAILED`·다른 공급자는 409.
- MEMBER 조회의 구매자는 `CurrentSubject`의 JWT sub다. 본문·쿼리·헤더·`X-Member-Id`로 소유자를 바꾸지 않는다.
- 구 prepare·callback·cancel·simulator·승인 주입 업무 엔드포인트와 Gateway의 익명/CSRF·simulator 예외는 제거했다.
- 승인 접수 상태: `REQUESTED`·`ADMITTED`·`REVIEW_REQUIRED`·`FAILURE_CONFIRMED`·`SUCCEEDED`·`FAILED`·`EXPIRED`. 개별 승인 `FAILED`는 같은 주문 재결제 근거이고 전역 실패가 아니다. 운영자 종결로 끝난 승인도 `FAILED`지만 이때 결제는 `FAILED`/`EXPIRED`라 새 주문으로 안내하며, 종결 기록으로 구분한다(결정 115).
- 승인 접수 요청 `{requestId(한 인증 결과에 고정한 UUID), txId, paymentToken}` → 202 `requestId·orderNo·paymentId·receivedAt`, Location `/approvals/{requestId}`.

| 관측 | Order | 승인 작업 | Payment | 고객의 다음 행동 |
|---|---|---|---|---|
| 인증 결과 202 접수 | PENDING일 수 있음 | REQUESTED | PENDING | Location 조회. 성공으로 표시하지 않음 |
| 기한 내 Order 접수 영수증 저장 | PAYMENT_PROCESSING | ADMITTED | PENDING | 기존 진행 결과 확인. 새 결제창 열지 않음 |
| 기한 내 개별 실패·Order 종료 확인 | PENDING | FAILED | PENDING | 같은 주문·금액으로 새 인증 |
| Order 만료 응답 또는 기한 후 실패 종료 | EXPIRED | EXPIRED/FAILED | EXPIRED | 현재 상품 조건으로 새 주문 |
| PG 성공 재조회·Payment/Outbox 커밋 | 이벤트 반영 뒤 PAID | SUCCEEDED | PAID | 주문·라이브러리 후속 반영 확인 |
| HTTP 결과 유실·3시간 경과·운영 확인 필요 | 진행 유지 | ADMITTED/REVIEW_REQUIRED(3시간 뒤 PG `PENDING`·`READY`면 `CONFIRM_WINDOW_EXPIRED`) | PENDING | 기존 결과 확인. 시간만으로 새 결제 없음 |
| 운영자 보류 종결(재조회 근거 전액 CANCELLED·FAILED·READY) | 이벤트 반영 뒤 FAILED(이미 EXPIRED면 유지) | FAILED(보류 사유 유지) | FAILED(`REVIEW_CLOSED`), 접수 전·만료 통지 뒤면 EXPIRED | 현재 상품 조건으로 새 주문 |

- `SUCCEEDED`는 Payment 금융 결과/Outbox 저장이며 OrderPaid·License 지급까지 동기 완료를 뜻하지 않는다.
- 접수된(`admittedAt` 있음) `ADMITTED`·`REVIEW_REQUIRED` 승인은 결제가 `PAID`로 확정되는 그 트랜잭션에서 `SUCCEEDED`가 된다(`ResolvePayment.confirm` → `succeedAdmittedApproval`, `purchase_payment` → `payment_approval` 잠금 순서). 그래서 웹훅·결제 재조회가 보류 건을 `PAID`로 확정해도 다음 보류 재조회(결정 122)를 기다리지 않고 보류 안내·ADMIN 보류 목록·게이지에서 바로 빠진다([D-051](../../../docs/defects.md#d-051)). 승인 작업자의 같은 규칙(`ResolvePaymentApproval.cycle`의 PAID 분기)은 남아 있다.

## 이벤트 계약

| 방향 | 이벤트 · 토픽 | 연결과 금액 |
|---|---|---|
| 수신 | `OrderPlaced` · `stove.order.v1` (그룹 `payment-order-request`) | eventId·occurredAt·orderNo·buyerSubject·상품 스냅샷·listAmount·discountAmount·amount·currency |
| 발행 | `OrderPaymentCompleted` · `stove.payment.v1` | eventId·occurredAt·orderNo·requestEventId·paymentId·buyerSubject·amount·currency |
| 발행 | `OrderPaymentFailed` · `stove.payment.v1` | 완료와 같은 연결·금액 + 필수 reasonCode·reason |
| 수신 | `OrderExpired` · `stove.order.v1` (orderNo 키) | orderNo·원 OrderPlaced ID·구매자·청구액·통화·고정 기한·만료 확정 시각. 금융 실패 아님 |
| 수신 | `RefundExecutionRequested` · (환불 접수) | 고정 attemptId·원 PAID 연결로 PG 취소/조회 |
| 발행 | `RefundPaymentConfirmed` · `stove.payment.v1` | refund/attempt·원 실행 요청·주문·결제·구매자·금액·통화·`PG_QUERY_UNATTRIBUTED` 또는 `PG_RESPONSE`·원 PG 거래·취소 ID들. '우리 실행 성공' 이벤트가 아님 |
| 발행 | `RefundExecutionSucceeded` · `stove.payment.v1` | 조회 완료가 먼저 확정된 뒤 같은 취소 근거의 직접 성공을 보완. 최초 금융 이벤트 참조 |
| 발행 | `RefundExecutionFailed` · `stove.payment.v1` | 연결된 취소 ID FAILED와 원결제 전액 미취소를 함께 확인한 실패 근거, 또는 ADMIN 실패 종결(결정 128)의 PG 재조회 근거. Order 이용권 복구의 근거. 실패 종결은 계약을 바꾸지 않고 같은 이벤트를 쓴다: `cancellationId`는 우리 접수 취소 ID, 없으면 `ADMIN_FAILURE_CLOSURE:<requestId>`(PG 취소 ID가 아님을 드러내는 값), `lookupHash`는 종결 때 PG 응답 원문의 SHA-256. Order는 둘을 실패 지문에만 쓴다 |
| 발행 | `RefundExecutionPrepared` · `stove.payment.v1` | 최초 준비 시각·3시간 경계를 한 번만 발행. PG 송신·성공을 뜻하지 않음 |

- `RefundPaymentConfirmed`·`RefundExecutionSucceeded`는 선택적 `RefundAmountsSnapshot`(원승인 실결제/할인 쌍, 성공 취소별 ID·총액·선택적 포인트, 제공사 취소 시각)을 담는다. 저장된 성공 근거만 쓰고 반환 현금을 계산하지 않으며, 접수·실패 응답의 금액을 성공 반환으로 전달하지 않는다([결정 36](../../../docs/decisions.md#refund-amount-details)).
- `OrderPaymentFailed`의 reasonCode `REVIEW_CLOSED`는 운영자가 승인 보류를 PG 재조회 근거로 종결했다는 뜻이다(결정 115). 공급자 거절이 아니며, providerTransactionId는 보류된 PortOne 시도(txId), 결정 시각은 종결 재조회 시각이다. reason은 근거 상태를 담은 기술 사유이고 고객 문구가 아니다. 주문이 이미 스스로 만료됐으면 Order는 만료를 유지한다.
- Kafka 키와 결과 Outbox aggregateId는 orderNo다. requestEventId는 원래 `OrderPlaced.eventId`이며 구매자 requestId와 다르다.
- eventId·requestEventId·paymentId 최대 64자, buyerSubject 최대 100자. 금액은 정수 long·양수·KRW이고 `listAmount - discountAmount == amount`. 소수 절삭 입력은 저장 전에 거부한다.
- 결과 이벤트 ID·시각은 결과 Outbox와 같은 트랜잭션에서 최초 한 번만 확정한다. DEAD가 된 결과는 보존하며 재발행은 저장된 ID·본문을 그대로 쓴다.
- 입력: orderNo UUID, 상품 코드 50자 패턴, 제목 200자 이하, 상품 ID·contentVersion 양수, 정가 양수·할인 0~정가. 할인 검사는 가격 구조 일관성이며 할인 자격 재판정이 아니다. 실패 사유 reasonCode 100자·reason 500자 이하 필수. 주문·결과 시각은 UTC 마이크로초.
- 결과 트랜잭션이 롤백돼 발행된 적 없는 후보 결과 ID는 최종 결과가 아니다. 업무 결과를 다시 확정하는 API는 두지 않는다.

## 외부 연동

- `PgGateway`(SIMULATED → 외부 모의 PG `SimulatedPgHttpGateway`, PORTONE_TEST → `AdmittedPortOnePgGateway`/`PortOnePaymentApprovalGateway`)로 승인·조회한다. 선택은 `stove.payment.pg-provider`·`PORTONE_TEST` 설정으로 조립한다.
- PortOne 조회는 상점·V2·TEST 채널·채널 키·`TOSSPAY_V2`·KRW·주문 총액·미취소·`customData.orderNo`·원래 승인 시도를 대조하고, `실결제액 + PG 할인액 = 주문 총액`을 요구한다([결정 34](../../../docs/decisions.md#provider-discount-payment-amounts)). PG 비용이 없는 응답은 미확정 null로 보존한다.
- Payment는 Order 내부 승인 접수/실패 통지 API를 `core.port` 계약의 HTTP adapter로 호출한다. 두 앱이 같은 서비스 키를 공유하고 MEMBER Bearer는 거절되며 Gateway는 이 경로를 제공하지 않는다.
- 승인 본문 암호화 키는 32바이트(Base64)이며 재시작해도 같아야 한다. 미완료 암호문이 있는 동안 교체하지 않는다.
- 웹훅 공개 경로는 edge(`https://<IP>/api/v1/payments/webhooks/portone`)가 Payment로 직접 전달한다. 서명 검증과 회원 checkout의 JWT 인증을 구분한다.
- Payment 승인/조회 키, Settlement 읽기 키, PG 제어 키는 분리하며 제어 키를 업무 앱에 주지 않는다. 비밀값은 서버 설정으로만 주입하고 저장소·로그·응답에 넣지 않는다.

## 처리·복구 동작 요약

- 주기 실행은 단일 인스턴스 fixed-delay(기본 1초·20건)로 due인 `PENDING`을 `nextAttemptAt, id` 순으로 고른다. 오류·미확정은 기본 5초 뒤 재예약하고 결제별로 처리한다. `stove.payment.processing-enabled`는 기본 true, 검증에서 false→true 재시작으로 접수/재개를 나눈다.
- 승인 복구: 최초 준비부터 3시간 미만은 같은 승인 ID·멱등 키·본문으로 자동 재송신+조회, 3시간부터는 GET만. 암호화 본문은 복호화한 원래 바이트만 재사용한다.
- confirm 기한 경과 보류(R4.6, 결정 124): `ResolvePaymentApproval`은 조회 결과가 PAID·FAILED·전액 CANCELLED가 아니면 같은 lease의 잠금 트랜잭션에서 `PaymentApproval.holdAfterConfirmWindow`를 부른다. `ADMITTED`·송신 중단 전이고 `firstPreparedAt + 3시간`(`CONFIRM_WINDOW`, `canPost`와 같은 경계)에 이르렀으며 PG가 `PENDING`·`READY`이면 `stopPosts("CONFIRM_WINDOW_EXPIRED")`로 보류한다. 실패·새 키·결제 전이는 만들지 않고 보호 본문도 지우지 않는다. 이후는 보류 흐름(재조회 간격 결정 122, 종결 결정 115)을 그대로 타며, 늦은 PAID는 원래 주문 성공, 늦은 FAILED는 기존 Order 실패 통지로 끝난다. 다른 사유로 이미 보류된 승인은 사유를 바꾸지 않는다. 기존 행은 다음 조회 때 올라오므로 이행은 없다.
- 환불: 고정 attemptId를 Idempotency-Key로, 원액을 amount·currentCancellableAmount에 명시. 부분 취소·명시적 잔액 불일치(409 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN`)는 자동 POST 중단·조회 유지. 검증된 전액 취소 조회 또는 검증된 취소 완료 응답으로만 완료를 확정한다.
- 중복 공급자 호출은 같은 키의 멱등 처리와 최종 행 잠금으로 중복 승인·중복 결과를 막는다. 다중 인스턴스 임대는 후속 범위다.
- 원자성은 같은 DB 안의 상태·Outbox에만 적용한다. 공급자 결정과 Payment DB를 한 트랜잭션으로 묶거나 exactly-once 전달을 가정하지 않는다. 스케줄러는 유스케이스에만 위임한다.
- 순서 역전: `OrderPlaced`·`OrderExpired`가 같은 `payment_order_lifecycle` 행을 먼저 잠근다. 만료가 먼저면 근거만 보존하고, 늦은 `OrderPlaced`는 원구매 대조 뒤 예약 없는 `EXPIRED`로 만든다. 확정 금융 결과가 있으면 덮어쓰지 않고 충돌을 표시한다.
- 접수 전 만료(R11.5, 결정 126): 주문 만료 통지를 받은 `REQUESTED`·미접수 승인은 `latestPgState`가 NULL이면 PG 호출 없이 승인·Payment를 `EXPIRED`로 닫는다. 값이 있으면(PG를 한 번이라도 유효하게 관측함) `ResolvePaymentApproval.expireAfterLookup`이 같은 lease로 트랜잭션 밖에서 `inspect`하고 관측을 별도 잠금 트랜잭션에 저장한 뒤, PAID → `PAID_WITHOUT_ADMISSION`, 전액 CANCELLED → `holdForCancellation`(`PG_CANCELLED`), PENDING → `PG_PENDING_AT_ORDER_EXPIRY`로 `stopPosts`하고, READY·FAILED는 잠금 안에서 `REQUESTED`를 다시 확인해 `EXPIRED`로 닫는다. 보류된 건은 이후 기존 보류 흐름(재조회 간격 결정 122, 종결 결정 115)을 그대로 탄다. 늦은 PAID는 사유를 `PAID_WITHOUT_ADMISSION`으로 바꾸고, FAILED는 기존 접수 전 실패 경로로 닫힌다. 조회 장애는 상태를 바꾸지 않아 다음 회차에 다시 조회하고, 조회 실패 기록(`lastInspectionError`)만 있는 승인은 관측이 없으므로 PG 호출 없이 닫는다. 새 열·이행은 없다([D-044](../../../docs/defects.md#d-044)).
- 실패 시도 이력은 `PAID`·결과 Outbox 커밋 뒤 별도 트랜잭션으로 수집하고 장애는 `next_failure_lookup_at`으로 재예약한다. 오래된 `PENDING` 응답이 승인 뒤 예약을 지우지 않는다.
- 추적: 재시도마다 원래 부모의 새 자식 span을 만들고 다른 주문·스케줄러 scope와 분리한다. 샘플링·수집기 상태는 상태 전이 조건이 아니다.

환불 실행 일정·분류:

| 항목 | 규칙 |
|---|---|
| 첫 송신 | `RefundExecutionRequested` 소비자가 접수 커밋 뒤 수행(한 번에 1레코드). 이미 준비된 실행의 재전달은 GET만 |
| 결과 GET | 최초 준비 후 5·15·35초, 이후 1분. 3시간이 지나면, 그리고 실패(PG 확정·실패 종결) 뒤에는 `min(상한, max(시작값, now − 최초 준비))`(결정 122 공식, 기본 60초·10분). 밀린 단계는 건너뛰고 몰아서 실행하지 않는다. 3시간 뒤·실패 뒤에도 GET 유지(실패 뒤에는 전액 취소만 본다). 동시 최대 4·대기 큐 0·임대(연결+응답 제한+30초) |
| POST 재송신 | 한 회 종료 시각 기준 10초→30초→이후 1분. 임대(HTTP 3회 제한 합+30초)·스캔 1초·작업자 1개. 늦은 소유자의 종료는 새 임대를 덮어쓰지 못한다 |
| HTTP 분류 | 401·GET 403/404·POST 404 → 미확정, 같은 요청 자동 재개 / POST 403 → 중단 `FORBIDDEN` / 정확한 POST 400 `INVALID_REQUEST` → 중단 `PG_REQUEST_INVALID`. 오류 본문은 파싱·저장하지 않는다 |
| 활성화 | `PAYMENT_REFUND_LOOKUP_ENABLED`·`PAYMENT_REFUND_EXECUTION_ENABLED`·`PAYMENT_REFUND_RETRY_ENABLED` 기본 false. 송신은 조회, 재송신은 송신·조회 조립이 없으면 기동 실패 |
- 할인 거래 환불 사전 조회는 원승인의 실결제/할인과 대조하고, 제공사가 보고한 포인트 반환 근거를 보존한다([결정 35](../../../docs/decisions.md#provider-discount-full-refund)).

## 사용 안내

| 환경변수 | 기본값·범위 |
|---|---|
| `PAYMENT_PG_PROVIDER` | `SIMULATED` (`PORTONE_TEST` 선택 가능) |
| `PAYMENT_PROCESSING_ENABLED` | true. false면 접수·조회만(승인 작업자 포함 중지) |
| `PAYMENT_POLL_MS` / `PAYMENT_BATCH_SIZE` / `PAYMENT_RETRY_MS` | 1000(100~60000) / 20(1~200) / 5000(100~300000) |
| `PAYMENT_REFUND_LOOKUP_LATE_INITIAL_MS` / `PAYMENT_REFUND_LOOKUP_LATE_MAX_MS` | 60000(1000~600000) / 600000(1000~600000, 시작값 이상. 줄일 수는 있지만 10분보다 늘릴 수 없다). 3시간 창이 지난 미확정 환불과 실패한 환불의 PG 조회 시작값·상한만 정한다(결정 122·128). 창 안 간격은 고정 |
| `PAYMENT_REVIEW_RETRY_INITIAL_MS` / `PAYMENT_REVIEW_RETRY_MAX_MS` | 5000(100~300000) / 600000(1000~600000, 시작값 이상. 설정으로 줄일 수는 있지만 결정 122의 10분보다 늘릴 수 없다). 보류(`REVIEW_REQUIRED`) 승인의 PG 재조회 시작값·상한만 정한다([결정 122](../../../docs/decisions.md#approval-review-requery-interval)) |
| `MOCK_PG_URI` / `MOCK_PG_PAYMENT_KEY` | `http://localhost:8092` / 필수, Payment 전용 승인·조회 키 |
| `PAYMENT_ORDER_SERVICE_KEY` | Order와 같은 값. Order 내부 승인/실패 API 전용 |
| `PAYMENT_APPROVAL_PAYLOAD_KEY` / `ORDER_URI` | 32바이트 Base64, 교체 금지 / Payment가 접근할 Order 주소 |

- 목록은 `page≥0`, `size 1~100`, `createdAt DESC, id DESC`. 주문 생성 직후 Kafka 접수 전에는 결제 상세가 잠시 404다.
- 검증: `./gradlew :apps:payment:test :apps:payment:integrationTest`, `python3 tools/verification/verify-payment.py`(8개 업무 앱+외부 모의 PG 격리, 기본 포트 오프셋 30000은 `PAYMENT_TEST_PORT_OFFSET`, 11단계). 장애 주입 클래스는 bootJar에 포함하지 않는다.
- PortOne 실사용 전 채널의 **수동 승인**을 켜야 한다(자동 승인 채널을 서버 코드만으로 막을 수 없음). SDK의 paymentToken·txId를 승인 접수 API로 보내고 Location을 조회한다. `tools/verification/support/portone_checkout.html`은 Order와 무관한 직접 PG 탐침이다.
- 공유 환경 전환: 구 V1~V10·자료는 보존하고 자동 변환·자동 결제하지 않는다. 구 쓰기·릴레이를 멈추고 `tools/migrations/payment/payment-transition.sql`(반복 실행 가능)로 구 PENDING·DEAD Outbox를 보관·격리한다. 새 그룹 `payment-order-request`의 시작 오프셋은 과거 OrderPlaced·미발행 Outbox·기존 결과를 대조해 정하며 earliest로 바로 켜거나 구 Inbox를 지우지 않는다.
- 내장 모의 PG(V11 `simulated_payment_charge`) 이관: 처리기를 멈추고 원래 결정을 외부 PG로 이관·대조한 뒤 재개한다. 빈 PG로 기존 `PENDING`을 재개하면 결정을 잃으므로 자동 전환하지 않는다. V12 비용은 NULL로 두고 0원으로 채우지 않는다.
- DEAD 결과 Outbox는 원인을 해소한 뒤 원래 eventId·본문으로 공통 requeue한다. 새 결제·결과 이벤트로 대신 보내지 않는다.

### 승인 보류 운영 조회 (결정 114, 1부)

`GET /api/v1/admin/approval-reviews?page=0&size=20&reason=PG_INVALID_EVIDENCE`는 현재 보류 목록을, `/{approvalId}`는 현재·과거 보류 상세를 DB에서 조회하며 `Cache-Control: no-store`를 반환한다. 접수·입장·최초 준비·보류 시작 시각, 보류 경과 초, 마지막 PG 상태/관측 시각, 조사 건수를 제공한다. 토큰·암호문·보호 본문은 응답에 포함하지 않는다. 기존 행의 보류 시작 시각은 NULL이며 경과 시간도 NULL이다.

V34는 기존 행을 갱신하지 않고 관측 열과 `approval_investigation`을 추가한다. resolver의 PG inspect 결과는 inspect 후 별도 payment→approval 잠금 트랜잭션에서 저장한다. `/{approvalId}/investigations` GET/POST는 500자 이내 확인 내용을 추가하고 JWT sub와 서버 시각을 기록한다. 같은 requestId의 다른 내용은 충돌이다. 금융 상태·재시도 간격은 변경하지 않는다. V34의 승인 FK는 삭제 cascade였으나, V38이 RESTRICT로 다시 만들어 승인이나 그 결제를 지우면 조사 기록이 조용히 사라지지 않고 삭제가 거절된다(옛 행은 고치지 않음, 결정 104 이행 테스트 대상 아님).

보류 사유는 처음 보류될 때 정해진다. 이미 `REVIEW_REQUIRED`인 승인을 다시 조회하다 새 중단 조건(`PG_INVALID_EVIDENCE`·`ORDER_ADMISSION_CONFLICT`·`ORDER_EXPIRY_CONFLICT`·`PG_REQUEST_REJECTED`)이 나와도 `PaymentApproval.stopPosts`는 `lastErrorCode`·`reviewStartedAt`을 바꾸지 않고, `ResolvePaymentApproval.stop`이 버린 사유를 로그로 남긴다. `PAID_WITHOUT_ADMISSION`만 사유를 올리고 그 뒤로는 고정된다([결정 144](../../../docs/decisions.md#held-approval-keeps-first-reason)). 그래서 R11.8 자동 만료는 처음부터 `PG_INVALID_EVIDENCE`로 보류된 미접수 승인에만 적용된다.

`stove.payment.approval.review.required{reason}`는 현재 보류 건수이며 8개 알려진 사유(2부의 `PG_CANCELLED`, 결정 126의 `PG_PENDING_AT_ORDER_EXPIRY`, 결정 124의 `CONFIRM_WINDOW_EXPIRED` 포함) 외 값은 OTHER로 합산한다. `PaymentApprovalsRequireReview` 경보는 ADMIN API로 안내한다. 종결은 아래 2부, 재조회 간격은 아래 "보류 재조회 간격", 고객 안내는 아래 "금융 보류 고객 안내"다.

승인 보류 상세·조사 이력 GET은 `reviewStartedAt != null`인 과거 보류도 현재 `status`와 함께 제공한다. 한 번도 보류되지 않은 승인은 404이고 조사 POST는 현재 REVIEW_REQUIRED에만 허용된다. `latestPgState`는 마지막 유효 관측(the last valid observation)이며 최근 조회 성공을 뜻하지 않는다. `lastInspectedAt`·`lastInspectionError`는 inspect 후 별도 잠금 트랜잭션에서 현재 lease 소유자만 기록한다. 오류는 INVALID_EVIDENCE 또는 UNAVAILABLE이고 성공 시 NULL로 지운다. 기존 예외 분류·원래 예외는 유지한다. CANCELLED 인식과 종결은 아래 2부에서 더했다.

V35는 `(status,last_error_code,received_at,id)` 색인, 조회 시각·오류 열 및 상태 집합/관측 쌍/보류 시각 CHECK를 추가한다. V34의 과거 NULL 행은 모두 제약을 충족하며 이후 값은 도메인이 같은 쌍·시간 순서로만 저장했으므로 기존 행을 수정하거나 과거 자료를 정정할 필요가 없다. 게이지는 스크레이프당 GROUP BY 한 번으로 7개 KNOWN(2부의 `PG_CANCELLED`, 결정 126의 `PG_PENDING_AT_ORDER_EXPIRY` 포함) 및 OTHER를 집계한다. PAID_WITHOUT_ADMISSION 경보는 critical, 나머지는 warning이며 알려진 과거 보류는 Alertmanager silence를 사용한다.

### 승인 보류 종결 (결정 115, 2부)

`POST /api/v1/admin/approval-reviews/{approvalId}/closure`(`PORTONE_TEST`만, ADMIN)는 `{requestId, note}`를 받아 JWT sub를 종결자로 쓴다. 순서는 작업자와 같다.

1. requestId로 최초 기록을 찾으면 같은 내용만 그대로 돌려준다(PG 미호출). 다른 내용은 409 `APPROVAL_CLOSURE_CHANGED`.
2. payment→approval 잠금에서 `REVIEW_REQUIRED`를 확인하고 작업자와 같은 lease(`leaseToken`·`leaseUntil`, 길이도 같음)를 잡는다. 다음 조회 시각(`nextAt`)은 기다리지 않지만 살아 있는 lease는 빼앗지 않는다(409 `APPROVAL_CLOSURE_BUSY`). lease를 잡는 동안 `nextAt=leaseUntil`이라 작업자 스캔에 걸리지 않는다.
3. 트랜잭션 없이 PortOne `inspect`.
4. payment→approval 잠금에서 lease 소유를 다시 확인하고 관측을 기록한다. PAID·PENDING·이미 최종 결과인 결제는 거절하고 lease를 돌려주며 원래 `nextAt`을 복원한다. 조회 실패는 `lastInspectionError`(INVALID_EVIDENCE/UNAVAILABLE)만 남기고 409다.
5. 근거가 맞으면 같은 트랜잭션에서 승인 `FAILED`(보류 사유 유지)·`approval_closure` 기록·결제 전이를 커밋한다. 접수 전 승인이고 `payment_order_lifecycle`이 만료 통지를 이미 받았으면 `EXPIRED`(이벤트 없음), 그 밖은 `FAILED`(`REVIEW_CLOSED`)와 `OrderPaymentFailed` Outbox다. 결과 이벤트 작성은 `ResolvePayment`와 같은 코드(`PaymentResultOutbox`)를 쓴다.

승인 작업 상태는 새 값을 만들지 않고 `FAILED`로 끝낸다. 터미널·활성 판정(`terminal()`, `active_guard`, `chk_payment_approval_resolution`)과 고객 승인 조회 상태 집합을 그대로 쓰고, 고객의 다음 행동은 결제·주문 상태(FAILED/EXPIRED)가 정한다. 운영 종결 여부는 상세·조사 이력의 `closure` 기록으로 구분한다.

작업자가 전액 CANCELLED를 보면 보류 전 승인은 `PG_CANCELLED`로 송신을 멈추고, 보류된 승인은 관측만 남긴다. 저절로 닫지 않는다. 전액 판정은 같은 시도·`cancelled == total`·`paid + discount == total`·성공 취소 합계 = 총액(FAILED 내역 제외, REQUESTED 등은 잘못된 근거)이다.

V36은 `chk_approval_pg_state`를 지우고 CANCELLED를 더해 다시 만든다(허용 집합만 넓어져 기존 행은 그대로 충족). V37은 새 표만 더한다. `approval_closure`의 승인 FK는 삭제 cascade가 없어(RESTRICT), 종결된 승인이나 그 결제를 지우려 하면 감사 기록이 조용히 사라지지 않고 삭제가 거절된다. 둘 다 옛 행을 고치지 않아 결정 104의 이행 테스트 대상이 아니다. 주문이 스스로 만료된 뒤 도착한 `REVIEW_CLOSED`는 Order가 만료를 유지하고 결과 식별만 남겨 DLT로 보내지 않는다. 같은 결과의 재전달만 무효과다([Order R4.8](../order/requirements.md)).

### 보류 재조회 간격 (결정 122)

`ResolvePaymentApproval`은 한 회를 끝낼 때(`PaymentApproval.finish`) 잠금 안의 현재 상태로 다음 조회 시각을 정한다. `REVIEW_REQUIRED`이면 `now + ApprovalReviewRequeryInterval.after(reviewStartedAt, now)` = `now + min(상한, max(시작값, now − reviewStartedAt))`이고, 그 밖은 `now + PAYMENT_RETRY_MS`다. 시작값·상한은 `stove.payment.review-retry-initial-ms`·`review-retry-max-ms`(`PAYMENT_REVIEW_RETRY_INITIAL_MS`·`PAYMENT_REVIEW_RETRY_MAX_MS`, 기본 5초·10분)이고 `PaymentProcessingProperties`가 범위를 검증한다. 같은 회에 막 보류된 승인은 경과가 0이라 시작값으로 시작한다. `reviewStartedAt`이 NULL인 옛 보류 행(V34 이전)은 상한을 쓰며, 새 열·이행은 없다. 종결은 `claimForClosure`로 `nextAt`을 기다리지 않고, 거절·실패하면 잡기 전의 `nextAt`(늘어난 간격 그대로)으로 돌려준다. 보류가 저절로 풀리는 분기(PAID·FAILED·READY 만료)는 바꾸지 않았고, 다음 조회가 늦어지는 만큼만 늦게 일어난다(예: 30분 보류 뒤 만료 통지를 받은 READY 건은 최대 10분 뒤 닫힌다).

### 금융 보류 고객 안내 (결정 123)

`ApprovalReviewNotice.of(approval, closure)`(도메인)가 고객 안내를 만든다. 승인이 `REVIEW_REQUIRED`면 보류 사유(`lastErrorCode`)를 `BUNDLES`로 묶음에 매핑한다. `PG_INVALID_EVIDENCE`만 `CHARGE_UNCONFIRMED`로 고정이다(결정 123). 나머지 알려진 사유와 알 수 없는 값은 `BY_OBSERVATION`이며(결정 132), `lastInspectionError`가 있거나 `latestPgState`가 NULL·`PENDING`이면 `CHARGE_UNCONFIRMED`, `PAID`면 `CHARGED`, 전액 `CANCELLED`면 `CHARGE_CANCELLED`, `READY`·`FAILED`면 `NOT_CHARGED`다. `ApprovalReviewNoticeTest`가 `BUNDLES`의 키가 `ApprovalReviewReason.KNOWN`과 같은지 확인하므로, 게이지에 새 사유를 더하면 묶음을 정해야 빌드가 통과한다. 승인이 `FAILED`이고 그 승인의 `approval_closure`가 있으면 근거 CANCELLED → `CLOSED_CHARGE_CANCELLED`, READY·FAILED → `CLOSED_NOT_CHARGED`다. 그 밖의 승인은 안내가 없다. `since`는 `reviewStartedAt`, 금액은 승인의 `amount`(주문 청구액)이다.

`ReadApprovalReviewNotice`(읽기 전용 서비스, `PaymentConfiguration`에서 조립)는 결제 상세에서 그 결제의 마지막 승인(`findFirstByPaymentIdOrderByIdDesc`)을, 승인 조회에서는 그 승인을 쓴다. 미종결 승인은 결제당 하나이고 종결은 결제를 끝내므로 마지막 승인이 고객이 보는 건이다. 종결 기록은 승인이 `FAILED`일 때만 찾는다. 응답 필드는 `PaymentResponse.reviewNotice`·`Progress.reviewNotice`(`ApprovalReviewNoticeResponse`)이고, 목록(`PaymentPage`)은 `null`이다. 결제창 409 `APPROVAL_IN_PROGRESS`는 `ReadPaymentCheckout`이 이미 찾은 진행 중 승인에 같은 함수를 적용해 보류면 그 문구를, 아니면 한국어 기본 문구를 메시지로 쓴다. 보류 사유는 ADMIN 응답(R12)에만 남고, Order 응답·이벤트(`OrderPaymentFailed`의 reason 포함)는 바꾸지 않는다. 새 열·이행은 없다.

### 장기·귀속 미확정 환불 종결 (결정 127)

`POST /api/v1/admin/refund-delays/{attemptId}/closure`(`PORTONE_TEST`만, ADMIN, `CloseRefundDelay`)는 `{requestId, attribution, note}`를 받아 JWT sub를 종결자로 쓴다. 구조는 승인 종결(결정 115)과 같다. 운영 순서는 [환불 종결 절차서](../../../docs/runbooks/refund-delay-closure.md)다.

1. 같은 requestId의 종결 기록이 있으면 PG를 부르지 않고 내용(대상·종결자·판정·note)을 대조해 최초 영수증을 반환한다. 다르면 `REFUND_CLOSURE_CHANGED`.
2. payment→execution 잠금 안에서 대상(`RefundExecution.closableAt`: 3시간 지난 `PENDING` 미확정 또는 `PENDING`·`PG_QUERY_UNATTRIBUTED`)과 종결 기록 없음을 확인한다. 조회 일정이 살아 있는 실행(미확정)은 `RefundLookupSchedule.claimForClosure`로 작업자와 같은 lease를 잡는다(`nextAt`을 기다리지 않음, 다른 lease가 살아 있으면 `REFUND_CLOSURE_BUSY`). lease 길이는 작업자와 같은 `connectTimeout + readTimeout + 30초`(`RefundLookupConfiguration.lookupLease`)다. 귀속 미확정 실행은 `confirmRefund`가 일정을 멈춰 작업자가 없고, DB가 조회 시각 없는 lease를 허용하지 않으므로(`ck_refund_lookup_lease`) lease를 잡지 않는다. 대신 4번의 잠금·시도 UNIQUE·requestId가 한 번만 닫게 한다.
3. 트랜잭션 없이 작업자와 같은 읽기를 한다. 우리 취소 접수가 있으면 `RefundPaymentLookup.inspect`, 없으면 `find`다. 전액 취소 확인(`PgRefundConfirmation`)이 없으면 `REFUND_CLOSURE_PG_NOT_CANCELLED`(잔액 있는 PAID·부분 취소·진행/실패 취소. 실패 근거도 기록하지 않음), `IllegalArgumentException`은 `CLOSURE_PG_INVALID_EVIDENCE`, 그 밖의 예외는 `CLOSURE_PG_UNAVAILABLE`이다. 거절되면 lease만 돌려주고(`releaseClosure`) 작업자의 `nextAt`·`wakeAt`은 그대로 둔다.
4. 다시 payment→execution 잠금 안에서 lease 소유(잡았을 때만)·같은 실행 요청·대상·종결 기록 없음을 확인하고 기존 `RefundExecution.confirmRefund`를 부른다. 미확정이면 첫 금융 확인(source `PG_QUERY_UNATTRIBUTED`)과 `RefundPaymentConfirmed` Outbox(`RefundConfirmationOutbox`, 조회 작업자와 같은 코드)를 저장하고 일정을 멈춘다(`COMPLETED`). 이미 귀속 미확정이면 같은 근거는 무변화(`RECORDED`), 다른 취소 내역은 409 `CONFLICT`다. 같은 트랜잭션에 `refund_closure`를 저장한다. 시도 UNIQUE 충돌은 같은 requestId면 최초 영수증, 아니면 `REFUND_NOT_CLOSABLE`이다. 영수증의 `executionStatus`·`refundConfirmedAt`·`refundConfirmationSource`는 현재 실행이 아니라 종결 기록에 함께 저장한 첫 응답 값으로 만든다. 종결 뒤 같은 취소 ID의 늦은 성공 응답(R7.13)이 실행을 `SUCCEEDED`로 바꿔도 같은 명령의 재요청은 최초 결과를 그대로 받는다(R14.1, PR #157 R2).

`ReadRefundDelays`는 `kind=UNATTRIBUTED`(`RefundDelayKind`)로 `PENDING`·`PG_QUERY_UNATTRIBUTED`·종결 기록 없음인 PortOne 실행을 `refundConfirmedAt, id` 순으로 준다. 같은 조건의 건수가 게이지 `stove.payment.refund.unattributed.open`(`RefundConfirmationMetrics`)이고, 경보 `RefundsUnattributed`는 `> 0`·`for` 없음·warning이다(`infra/prometheus/tests/refund-unattributed.yml`). 기존 `stove.payment.refund.confirmation.delayed`(`refundConfirmedAt is null`)와 `RefundConfirmationsDelayed`(critical)는 그대로라 대상이 겹치지 않는다. 상세는 귀속 미확정 실행을 3시간 창 안에서도 보이고(창 안에서 조회로 완료될 수 있음) `closure`를 싣는다. 상세의 `reconciliationRequired`는 `RefundExecution.unattributed() && 종결 없음`으로 계산한다. 그래서 늦은 성공 응답(`SUCCEEDED`)이나 종결 뒤에는 `false`다(전에는 출처만 보고 계속 `true`였다).

Order 시도의 `reconciliationRequired`는 이 종결로 바뀌지 않는다. 기존 계약에서 이를 지우는 것은 `RefundExecutionSucceeded`(우리 취소 ID의 PG 성공 응답. Order는 시도를 `SUCCEEDED`·source `PG_RESPONSE`로 기록)뿐이고, 운영 판정(특히 외부·판단 불가)을 그 이벤트로 보내면 계약의 뜻이 바뀐다. 새 이벤트나 뜻 변경은 새 정책이라 구현하지 않았다(결정 127, `tasks.md`). 지금 Order의 그 필드를 읽는 API·지표는 없다.

V39는 새 표만 더하고 옛 행을 고치지 않는다. 이행 전에 있던 귀속 미확정 실행은 종결 기록이 없으므로 목록·게이지·경보 대상이 된다(결정 104 이행 테스트 `RefundDelayClosureIntegrationTest.v39PreservesExistingExecutionsAddsNoClosuresAndEnforcesAuditConstraints`). 적용 절차: V39는 `CREATE TABLE` 한 문장이고 MySQL 8의 원자적 DDL이라 일부만 남지 않는다. 옛 버전은 이 표를 쓰지 않아 함께 돌아도 문제가 없다. 새 버전 기동 직후 기존 귀속 미확정 건만큼 `RefundsUnattributed`가 울린다. 현재 구성에는 Alertmanager가 없어 경보는 Prometheus 화면에만 보이고 silence로 끌 수 없으므로, 알려진 과거 건은 결정 127 종결로 닫아야 풀린다. 부분 실패 복구: `flyway_schema_history`에 V39 실패 행이 있으면 `SHOW TABLES LIKE 'refund_closure';`로 표가 생겼는지 보고, 있으면 `DROP TABLE refund_closure;`를 실행한 뒤 V39 실패 행을 지우고(Flyway `repair`와 같은 동작) 다시 기동한다. 종결 기록이 이미 쌓인 뒤에는 표를 지우지 않는다(감사 기록, 실행 FK RESTRICT).

### 3시간 뒤·실패 뒤 환불 조회 간격 (결정 122·128)

`ResolveRefundLookup`은 한 회를 끝낼 때(`RefundExecution.finishLookup`) 잠금 안의 현재 상태로 다음 조회 시각을 정한다. 최초 준비 후 3시간 안이고 실패가 아니면 지금처럼 5·10·20·60초 단계다. 3시간이 지났거나 실행이 `FAILED`면 `now + RefundLookupInterval.after(firstPreparedAt, now)` = `now + min(상한, max(시작값, now − firstPreparedAt))`이다. 시작값·상한은 `stove.payment.refund-lookup.late-initial-ms`·`late-max-ms`(기본 60초·10분)이고 `config`의 `RefundLookupProperties`가 범위를 검증하며, `RefundLookupInterval`도 상한 10분 초과를 거절한다. 3시간 뒤의 경과는 늘 10분보다 크므로 기본 설정에서 창 뒤 간격은 10분이다. 창 안에서 확정된 실패는 경과에 비례해 60초부터 늘어난다. 웹훅(`ReceivePortOneWebhook`)은 `wakeAt`을 세워 다음 스캔에서 바로 잡히고, 결정 127·128 종결은 `claimForClosure`로 `nextAt`을 기다리지 않는다(`RefundFailureClosureIntegrationTest`).

### 자동 송신이 멈춘 환불 실패 종결 (결정 128)

`POST /api/v1/admin/refund-delays/{attemptId}/failure-closure`(`PORTONE_TEST`만, ADMIN, `CloseRefundFailure`)는 `{requestId, note}`를 받아 JWT sub를 종결자로 쓴다. 구조는 결정 115·127과 같다. 운영 순서는 [환불 종결 절차서](../../../docs/runbooks/refund-delay-closure.md)다.

1. 같은 requestId의 실패 종결 기록이 있으면 PG를 부르지 않고 내용(대상·종결자·note)을 대조해 최초 영수증을 반환한다. 다르면 `REFUND_CLOSURE_CHANGED`.
2. payment→execution 잠금 안에서 대상(`RefundExecution.failureClosableAt`: `PENDING`·미확정·준비됨, 취소 POST lease 없음, 활성 중단·잔액 변화 차단·3시간 경과 중 하나)과 결정 127·128 종결 기록 없음을 확인하고, 조회 일정이 살아 있으면 작업자 lease를 잡는다(`REFUND_CLOSURE_BUSY`).
3. 트랜잭션 없이 `RefundPaymentLookup.inspectUncancelled`로 PG를 읽는다. PortOne 구현은 작업자와 같은 GET·원결제 대조 뒤, `PAID`·취소액 0이고 목록이 모두 원액 `FAILED`일 때만 `PgRefundUncancelled`(FAILED 행·응답 원문 SHA-256)를 준다. 상태가 `PAID`가 아니거나 취소액이 있거나 `REQUESTED`·`SUCCEEDED` 등 다른 행이 있으면 빈 값(`REFUND_FAILURE_CLOSURE_PG_NOT_UNCANCELLED`)이다. 원액이 아닌 FAILED 행·원결제 불일치는 `IllegalArgumentException`(`CLOSURE_PG_INVALID_EVIDENCE`)이다. 다음 시도의 송신 전 대조가 그 행을 기대할 수 없기 때문이다. 우리 취소 접수가 있으면 그 ID가 같은 내용의 FAILED 행으로 있어야 한다.
4. 다시 잠금 안에서 lease 소유·같은 실행 요청·대상과, 2번에서 기억한 중단 상태가 그대로인지 확인한다. 기억하는 것은 활성 중단 ID, 송신 세대(`RefundCancellationSchedule.generation`, 송신 claim·운영 재개마다 1 증가), 우리 취소 접수다. 조회 lease는 운영 재개나 송신을 막지 않으므로, 그 사이 재개·송신이 있었으면 다시 닫을 수 있는 상태(새 중단 B, 응답 유실 뒤 3시간 만료)여도 옛 GET은 근거가 아니다. 이때 `REFUND_NOT_FAILURE_CLOSABLE`이고 아무것도 저장하지 않는다(PR #166 R1). 재개·송신을 종결 동안 막는 대신 세대를 비교한 이유: 재개 API·첫 송신·재송신 세 경로에 종결 lease 확인을 넣어야 하고, 작업자 조회 lease와 종결 lease를 구분하는 표시도 새로 필요하다. 세대 비교는 종결 한 곳만 바꾸고, 이 세 경로의 동작은 바꾸지 않는다. 중단·잔액 차단·접수는 모두 송신 claim 뒤에만 생기므로 세대가 같으면 그 사이 POST가 시작되지 않았다. `RefundExecution.closeAsFailed`가 상태 `FAILED`·실패 이벤트 ID·시각·스냅샷 해시를 쓰고 취소 일정을 멈추며, 조회 일정은 두고 lease만 돌려준다. 같은 트랜잭션에 `refund_failure_closure`(관측 FAILED 행 포함)와 `RefundExecutionFailed` Outbox(`RefundConfirmationOutbox.recordFailure`, PG 확정 실패와 같은 코드)를 저장한다. 영수증은 종결 기록에 함께 저장한 첫 응답 값(`FAILED`·실패 확정 시각)과 바뀌지 않는 식별자로만 만든다.

고객 재시도: `ReceiveRefundExecution`은 지금처럼 선행 실행의 `FAILED`·실패 이벤트·시각만 보므로 실패 종결된 선행도 받는다. 받으면 선행의 조회 일정을 멈춘다(`handOverLookupToRetry`). 후행이 같은 결제를 직접 조회하므로, 후행이 일으킨 전액 취소를 선행이 늦은 취소로 올리지 않게 하려는 것이다. `RequestRefundCancellation.previousFailures`는 선행 사슬을 거슬러 오르며, PG 확정 실패는 FAILED 접수를, 실패 종결은 그 종결이 관측한 FAILED 행(선행 실행 ID로 귀속)을 기대 이력에 넣고 거기서 멈춘다(관측은 결제 전체의 목록이라 더 앞 행을 이미 담음). `RefundCancellationPermission`은 이력과 별도로 `predecessorVerified`를 가져, 관측 행이 없는 실패 종결 뒤에도 후행이 보낼 수 있다. 송신 전 대조(`PortOneRefundCancellationGateway.matchesPreviousFailures`)는 그대로 "PG 목록 = 기대 이력"이다.

실패 뒤 조회: PG 확정 실패(`confirmFailure`)와 실패 종결 모두 조회 일정을 멈추지 않는다. 작업자 스캔(`findDueLookups`)은 `PENDING`·`FAILED`를 보고, `ReconcileRefundPayment.executeScheduled`는 `FAILED`면 전액 취소만 조회한다. 찾으면 `recordLateCancellation`이 발견·PG 취소 시각만 쓰고 일정을 멈춘다. 상태·금융 근거·보유권은 바꾸지 않고 이벤트도 없다(결정 128 "자동으로 바꾸지 않는다"). 송신 경로(Kafka·재송신)에서 부르는 `execute`는 `FAILED`면 지금처럼 바로 돌아가 추가 GET을 하지 않는다. 웹훅은 그 결제에서 조회 일정이 살아 있는 최신 실행(`findFirstByPaymentIdAndLookupScheduleNextAtIsNotNullOrderByIdDesc`)을 깨운다. 미확정 실행과 감시 중인 실패 실행이 대상이고, 일정이 멈춘 귀속 미확정 실행은 전처럼 깨우지 않는다. 목록 `kind=LATE_CANCELLATION`·게이지 `stove.payment.refund.failed.late_cancellation`·경보 `RefundCancelledAfterFailure`(`> 0`·`for` 없음·warning, `infra/prometheus/tests/refund-late-cancellation.yml`)가 이 건을 보인다. 경보를 끄는 시스템 동작은 없다(처리 방식은 결정 128대로 실제로 생기면 다시 정함, `tasks.md`).

V40 적용·호환: `refund_execution` 변경(옛 실패 CHECK 제거·늦은 취소 두 열·송신 세대 열(기본 0)·새 CHECK 셋)은 한 `ALTER TABLE` 문장이라 MySQL 8의 원자적 DDL로 함께 적용되거나 함께 실패한다. 새 실패 CHECK는 이름을 바꿔(`_v40`) 같은 문장의 옛 이름 제거와 겹치지 않는다. 옛 행은 고치지 않는다. 이행 전 실패한 실행은 조회 일정이 멈춘 채(`next_refund_lookup_at` NULL)라 감시 대상이 아니고, 늦은 취소 열은 NULL이며 실패 종결 기록이 없다(결정 104 이행 테스트 `RefundFailureClosureIntegrationTest.v40PreservesExistingExecutionsAndEnforcesTheFailureClosureConstraints`). 옛 버전과 함께 돌면 옛 버전은 새 열·표를 쓰지 않고, 옛 실패 확정은 일정을 멈춰 넓어진 CHECK를 만족하며, 옛 스캔은 `PENDING`만 봐 감시 중인 실패 실행을 건드리지 않는다. 옛 버전에서 실패 종결된 선행의 재시도를 보내려 하면 이전 실패 근거가 없다는 409로 멈췄다가 새 버전에서 이어진다. 부분 실패 복구: `flyway_schema_history`에 V40 실패 행이 있으면 `SHOW CREATE TABLE refund_execution`으로 `late_cancel_observed_at`·`chk_refund_execution_failure_v40`이 있는지 본다. 첫 `ALTER`는 원자적이라 둘 다 있거나 둘 다 없다. 없으면 V40 실패 행만 지우고(Flyway `repair`와 같은 동작) 다시 기동한다. 있으면 `SHOW TABLES LIKE 'refund_failure_closure%'`로 표 생성 여부를 보고, 만들어진 표가 비어 있으면 자식 표(`refund_failure_closure_cancellation`)부터 제거한 뒤 `refund_execution`을 V39 모양(늦은 취소 두 열·송신 세대 열과 새 CHECK 셋을 빼고 V21의 `chk_refund_execution_failure` 원문을 되살림)으로 한 `ALTER` 문장으로 되돌리고, V40 실패 행을 지운 뒤 다시 기동한다. 실패 종결이 쌓였거나 감시 중인 실패 실행(조회 일정 있음)이 있으면 V21 CHECK로 되돌릴 수 없으므로 되돌리지 않고 V40을 앞으로 고친다.
