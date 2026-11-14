# 설계: Payment

> 상태: 작성 중(결정 150·178). 목표 계약이다. 지금 코드와 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"(`MC-P*`·`PA-T*`)에 있다. 모듈을 넘는 계약(이벤트 뜻·키·순서·흐름·돈 불변식)은 [플랫폼 design](../platform/design.md)이 정본이다.
> 경로 줄임: `payment:` = `apps/payment/src/main/java/com/stove/payment/`, `Vn` = `apps/payment/src/main/resources/db/migration/Vn__*.sql`. 줄 번호는 2026-11-09 `docs/spec-foundation` 기준.

## 아키텍처 위치

- 경계·의존 방향은 [플랫폼 P1](../platform/requirements.md)과 design "모듈 경계와 의존 방향"을 따른다. Payment는 자기 스키마 `stove_payment`만 읽고 쓴다(P1.1-2).
- 패키지: `core/{domain, port, service}` · `api/{controller, listener, scheduler}` · `adapter/{pg, order, crypto, trace}` · `config`.
  - `api.listener`: `OrderEventListener`(`OrderPlaced`), `OrderExpiryListener`(`OrderExpired`), `RefundExecutionEventListener`(`RefundExecutionRequested`), 목표 `OrderPaymentResultRejectionListener`(가칭, `OrderPaymentResultRejected`), 목표 `SettlementRefundListener`(가칭, `SettlementRefundRecorded`). 각 리스너는 `*Mapper.from(envelope, payload)`로 봉투·본문·키를 검증해 내부 입력으로 바꾼다. 서비스·도메인은 이벤트 전체를 받지 않는다(AGENTS.md 3절).
  - `api.scheduler`: 위임만 한다. `PendingPaymentScheduler` → `ResolvePayment`, `PaymentApprovalScheduler` → `ResolvePaymentApproval`, `RefundLookupScheduler` → `ResolveRefundLookup`, `RefundRetryScheduler` → `ResolveRefundRetry`, 목표 `ChargeWatchScheduler`(가칭) → `ResolveChargeWatch`(가칭, R16).
  - `core.port`: `PgPaymentApprovalGateway`·`PaymentAttemptLookup`·`RefundCancellationGateway`·`RefundPaymentLookup`(PG), `OrderPaymentAdmissions`(Order 내부 명령 HTTP), 목표 `ProviderNotificationVerifier`·`ProviderNotificationReceipts`(가칭, 지금 `PortOneWebhookVerifier`·`PortOneWebhookReceipts`). 공급자 이름은 port 이름·값에 없다(R22).
  - `adapter.pg`: 공급자 HTTP·서명·응답 분류·보장 기한 계산. `config`가 주소·키·기한 설정을 바인딩해 adapter에 값만 주입한다.
- 동기 의존: Payment → Order 명령 2개(승인 진행 접수·시도 실패 통지, P1.2-6), Payment → PG(승인·조회·취소), PG → Payment(통지 수신). 플랫폼 design "동기 의존" 표 행 그대로다.
- 트랜잭션 경계(모든 PG·Order 호출 공통): ① 잠금 트랜잭션에서 대상·lease 확인 → ② 트랜잭션 밖 HTTP → ③ 잠금 트랜잭션에서 lease 소유·상태 재확인 뒤 상태 전이 + Outbox. 잠금 순서는 어디서나 `purchase_payment` → (`payment_approval` | `refund_execution` | `unlinked_charge` | `payment_charge_watch`)다. `payment_order_lifecycle`은 그보다 먼저 잠근다(주문 단위 직렬화, R11.2).

## 데이터 모델

표마다 상태 칸·유일 제약·FK·금액 CHECK를 적는다. "지금"은 코드의 이행 파일, "목표"는 바꿀 것(tasks 번호).

### `purchase_payment` (결제, V11·V12·V30·V32)

| 칸 | 뜻 | 제약 |
|---|---|---|
| `payment_id` | 고정 결제 식별자 | `uk_purchase_payment_id` |
| `order_no`·`request_event_id`·`buyer_subject`·상품 스냅샷·`order_occurred_at` | 주문 사본(Order 정본) | `uk_purchase_payment_order`, `uk_purchase_payment_request` |
| `list_amount`·`discount_amount`·`amount`·`currency` | 청구액 사본 | `chk_purchase_payment_price`(`list>0`, `0≤discount≤list`, `amount=list−discount>0`, `currency='KRW'`) |
| `expires_at` (목표 신설) | `OrderPlaced.expiresAt` 값(표시·기록만) | NULL 허용(옛 이벤트) — PA-T5 |
| `status` | `PENDING`·`PAID`·`FAILED`·`EXPIRED` | 지금 `chk_purchase_payment_state`의 세 갈래가 값을 묶음. 목표: 이름 있는 값 CHECK `chk_purchase_payment_status` 추가(MC-P4 이행과 같이) |
| `provider` | 공급자 키(설정 값). core는 비교하지 않음 | 옛 값 `PORTONE_TEST`·`SIMULATED` 읽기 유지(결정 104) — MC-P3·PA-T15 |
| `provider_transaction_id`·`provider_occurred_at` | 공급자 거래·결정 시각(값만) | `uk_purchase_payment_provider(provider, provider_transaction_id)` |
| `result_event_id`·`result_occurred_at` | 결과 이벤트 ID·시각(Payment 시계) | `uk_purchase_payment_result` |
| `failure_reason_code`·`failure_reason` | `FAILED`의 사유 | `chk_purchase_payment_state`: `FAILED`만 NOT NULL |
| `next_attempt_at`·`last_error_code` | 결제 재개 일정과 마지막 오류(상태 아님) | `PENDING`만 `next_attempt_at` NOT NULL |
| `next_failure_lookup_at`·`failure_history_checked_at` | 실패 시도 이력 수집 일정(상태 아님) | — |
| `pg_paid_amount`·`pg_discount_amount` | PG 결제 구성 | `ck_payment_provider_amounts`(NULL 일치·`PAID`만·합계=amount) |
| `order_response_kind`·`order_response_event_id` (목표 신설) | Order 반영 응답(`APPLIED` = `OrderPaid`, `REJECTED` = `OrderPaymentResultRejected`)과 그 `eventId` | CHECK 값 2개, 둘 다 NULL이거나 둘 다 값, `PAID`일 때만 `APPLIED`, `uk_purchase_payment_order_response(order_response_event_id)`. 응답 없는 `PAID` 결제를 게이지로 셈([결정 189](../../../docs/decisions.md#payment-order-response)) |
| `pg_fee_policy_id`·`pg_fee_rate_bps`·`pg_fee_amount` | 옛 PG 비용 사본 — **목표: 삭제** | `ck_payment_pg_fee`. 쓰는 곳은 `SimulatedPgHttpGateway`뿐이라, 그 경로를 없애는 PR(결정 162·T13 또는 PA-T16)에서 칸·CHECK·`Payment` 필드·`PgFee`·`PgResult.fee`를 함께 지운다([결정 192](../../../docs/decisions.md#drop-payment-pg-fee)) |
| (목표) `(payment_id, amount)` 유일 키 | 환불 실행 금액 FK 대상 | MC-P1 |

### `payment_order_lifecycle` (주문 생명주기 사본, V31)

| 칸 | 뜻 | 제약 |
|---|---|---|
| `order_no` (PK) | 주문 단위 잠금 | — |
| `request_event_id`·`buyer_subject`·`amount`·`currency` | 원구매 연결값 | `ck_payment_order_lifecycle_charge` |
| `expires_at` | Order가 준 기한 값. 목표: `OrderPlaced.expiresAt`/`OrderExpired.expiresAt`을 그대로 저장(지금 `occurredAt + 30분` 재계산, T12·PA-T5) | — |
| (목표) `status` | `OPEN`·`EXPIRED` | 목표 CHECK `ck_payment_order_lifecycle_status`, `EXPIRED` ⇔ `expiry_event_id` NOT NULL |
| `expiry_event_id`·`expired_at` | 만료 사실 ID·Order 시각(값만) | `uk_payment_order_expiry_event` |
| `financial_conflict` | 지금: 확정 결과가 있는 결제에 만료가 온 표시 | 목표: 칸 삭제 — "결제 `PAID` ∧ 생명주기 `EXPIRED`"를 정본 두 칸에서 세는 게이지로 바꾼다(원칙 ③④, MC-P7·PA-T6). 지금 CHECK `expired_at >= expires_at`는 Order 시각끼리의 Order 불변식을 다시 검사하므로 지운다(원칙 ③, PA-T6) |

### `payment_approval` (승인 시도, V29·V34·V35·V36)

| 칸 | 뜻 | 제약 |
|---|---|---|
| `approval_id` | 시도 ID = Order `admissionId` | `uk_payment_approval_id` |
| `payment_id` | 결제 | `fk_payment_approval_payment`(지금 `ON DELETE CASCADE` — 감사 기록 보존을 위해 목표 RESTRICT, PA-T7) |
| `supplier_attempt_id`·`token_fingerprint`·`protected_payload` | 공급자 시도 ID·토큰 지문·암호화 본문 | `uk_payment_approval_supplier_attempt` |
| `amount`·`currency` | 청구액 사본 | `chk_payment_approval_charge` |
| `status` | 7개 값(상태 모델) | `chk_payment_approval_resolution`(끝 3개 / 진행 4개). 목표: 값 CHECK 이름 분리 |
| `active_guard` | 진행 4개일 때 1 | `uk_payment_approval_active(payment_id, active_guard)` — 결제당 진행 시도 하나 |
| `received_at`·`admitted_at`·`first_prepared_at`·`failed_at`·`resolved_at` | 같은 Payment 시계의 생애 시각(`admitted_at`은 Order 영수증 값) | `chk_payment_approval_window`(`first_prepared_at` ⇒ `admitted_at`) |
| `expires_at` | 지금 `orderOccurredAt + 30분` 재계산 | 목표: `purchase_payment.expires_at` 값 복사 또는 삭제(T12·PA-T5) |
| (목표) `confirm_deadline` | adapter가 준 확정 송신 기한 | 목표 CHECK `confirm_deadline IS NULL OR first_prepared_at IS NOT NULL` (MC-P2) |
| `post_stopped` | 송신 중단 | 목표: 상태로 흡수(`REVIEW_REQUIRED`·`FAILURE_CONFIRMED`·끝 상태 ⇔ 송신 금지)하고 칸 삭제 — PA-T8 |
| `last_error_code` | 지금 보류 사유·판매 제한 대기·오류 코드를 겸함(MC-P4) | 목표: `review_reason`(보류 사유 enum 8개, CHECK, `REVIEW_REQUIRED`일 때만 NOT NULL·끝 상태에서 보존) + `last_error_code`(오류 코드만) + 판매 제한 대기 표현(소급 RC-2, notes E-1) |
| `review_started_at` | 처음 보류된 시각 | `chk_approval_review_time` |
| `latest_pg_state`·`latest_pg_observed_at`·`last_inspected_at`·`last_inspection_error` | 마지막 유효 관측·조회 오류 | `chk_approval_pg_state`·`chk_approval_pg_observation`·`chk_approval_inspection_error` |
| `next_at`·`lease_token`·`lease_until` | 작업 일정·lease(기술 잠금) | `chk_payment_approval_lease` |

### `unlinked_charge` (주문에 연결되지 않은 청구, 목표 신설 — 결정 153·154, MC-P13·MC-P14)

| 칸 | 뜻 | 제약 |
|---|---|---|
| `charge_id` | 기록 ID(UUID) | PK |
| `payment_id` | 결제 | FK `purchase_payment(payment_id)` RESTRICT |
| `source` | `LATE_CHARGE`(끝난 결제의 늦은 청구) · `ORDER_REJECTED`(Order가 거절한 결과) | CHECK, `uk_unlinked_charge_source(payment_id, source)` |
| `status` | `OPEN`·`CLOSED` | CHECK |
| `evidence_*` | 발견 근거(공급자 거래 ID·PG 관측 상태·관측 시각) / `ORDER_REJECTED`면 `rejected_result_event_id`·`rejection_reason`·`admission_id` | `ORDER_REJECTED` ⇔ `rejected_result_event_id` NOT NULL(CHECK), `uk_unlinked_charge_rejection(rejected_result_event_id)` |
| `created_at` | 기록 시각(고객 안내 `since`) | — |
| `closure_request_id`·`closed_by`·`closure_note`·`closure_evidence_state`·`closure_observed_at`·`closed_at` | 종결 기록 | `CLOSED` ⇔ 모두 NOT NULL, `closure_evidence_state='CANCELLED'`(CHECK), `uk_unlinked_charge_closure_request` |
| `lease_token`·`lease_until` | 종결 재조회 lease | 쌍 CHECK |

`unlinked_charge_investigation`(추가 전용, `approval_investigation`과 같은 모양)을 둔다.

### `payment_charge_watch` (끝난 결제의 청구 감시, 목표 신설 — 결정 154, MC-P14)

| 칸 | 뜻 | 제약 |
|---|---|---|
| `payment_id` | 결제(PK) | FK RESTRICT |
| `status` | `WATCHING`·`CHARGE_FOUND`·`ENDED` | CHECK |
| `watch_until` | adapter가 준 "더 이상 결제될 수 없다고 공급자가 보장하는 시각"(값은 `[외부 대기]`) | NOT NULL |
| `next_lookup_at`·`lease_token`·`lease_until`·`last_inspected_at`·`last_inspection_error` | 일정·lease·조회 오류 | `WATCHING` ⇔ `next_lookup_at` NOT NULL |
| `ended_at` | `CHARGE_FOUND`·`ENDED` 시각 | 끝 상태 ⇔ NOT NULL |

### `refund_execution` (환불 실행, V14~V26)

| 칸 | 뜻 | 제약 |
|---|---|---|
| `attempt_id`·`refund_id`·`request_event_id`·`license_suspension_event_id`·`previous_attempt_id` | Order 실행 요청 식별자 | `uk_refund_execution_attempt`·`_request`·`_suspension`·`_previous`, `fk_refund_execution_previous`, `chk_refund_execution_previous` |
| `payment_id`·`amount`·`currency` | 원결제·전액 | `fk_refund_execution_payment`, `chk_refund_execution_price`. 목표 복합 FK `(payment_id, amount)` → `purchase_payment`(I2, MC-P1) |
| `pending_payment_guard`·`pending_refund_guard` | 진행 중 실행 결제당·환불당 하나 | `uk_refund_execution_pending_payment`·`_pending_refund`. 목표: `PENDING`·`CONFIRMED_UNATTRIBUTED`에서 값(귀속 미확정도 진행 중 금융 기록) — PA-T9 |
| `status` | 목표 `PENDING`·`CONFIRMED_UNATTRIBUTED`·`SUCCEEDED`·`FAILED` | 지금 `chk_refund_execution_status`(3개). 목표 4개(MC-P11) |
| (목표) `dispatch_state` | `NOT_PREPARED`·`SENDING`·`RECEIPTED`·`HELD`·`BALANCE_BLOCKED`·`WINDOW_ENDED`·`STOPPED` | 목표 CHECK. 지금은 `receipt_cancellation_id`·`active_cancel_hold_id`·`cancellation_balance_changed_at`·`auto_cancel_stopped_at`의 NULL 조합(MC-P8) |
| `first_cancel_prepared_at`·(목표) `cancellation_resend_deadline`·`preparation_event_id`·`cancellation_request_body` | 첫 준비·adapter 기한·준비 사실·고정 본문 | `uk_refund_preparation_event`, `chk_refund_preparation_event`. 지금 기한은 core `RefundCancellationWindow`가 계산(MC-P2) |
| `refund_confirmed_at`·`refund_confirmation_event_id`·`provider_refunded_at`·`refund_cancellation_evidence` | 금융 확인(Payment 시계)·이벤트 ID·PG 취소 시각(값만)·성공 취소 내역 | `uk_refund_execution_confirmation`, 목표 CHECK: `status ∈ {CONFIRMED_UNATTRIBUTED, SUCCEEDED}` ⇔ `refund_confirmed_at` NOT NULL |
| `refund_confirmation_source` | 지금 `PG_RESPONSE`·`PG_QUERY_UNATTRIBUTED` | 목표: 상태(`SUCCEEDED`·`CONFIRMED_UNATTRIBUTED`)가 귀속을 말하므로 칸 삭제, 옛 행은 상태로 이행(결정 164, T19·PA-T3) |
| `cancellation_success_event_id`·`cancellation_succeeded_at`·`successful_cancellation_id`·`response_*` | 우리 취소 성공 응답 근거 | `uk_refund_execution_success`, `chk_refund_execution_success`. 목표: `cancellation_success_event_id` 삭제(이벤트 `RefundExecutionSucceeded` 폐지, PA-T3) |
| `receipt_*` | PG 접수 응답 | `chk_refund_execution_receipt` |
| `failure_event_id`·`failure_confirmed_at`·`failure_lookup_hash` | 실패 사실 | `uk_refund_execution_failure`, `chk_refund_execution_failure`(`FAILED` ⇒ `refund_confirmed_at IS NULL` — I18을 이미 DB가 지킴) |
| 조회 일정 `next_refund_lookup_at`·`refund_lookup_stage`·`refund_lookup_claim_id`·`refund_lookup_lease_until`·`refund_lookup_wake_at` | 조회 일정·lease(기술 잠금) | `ck_refund_lookup_*`. 목표: `FAILED` 뒤에도 실패 뒤 감시 일정 허용(R19.1) — `chk_refund_execution_failure`의 일정 NULL 조건 완화(PA-T12) |
| 송신 일정 `next_cancel_retry_at`·`cancel_retry_*`·(#166) `closure_id`·`closure_until` | 재송신 일정·lease·실패 종결 표시(기술 잠금) | `ck_cancel_retry_*` |
| (목표) `settlement_refund_event_id` | 정산 반영 응답(`SettlementRefundRecorded`) ID | 목표 유일 키(R20, PA-T14) |

PG 시각을 Payment 시각과 비교하는 지금의 CHECK(V17·V19 `chk_refund_payment_confirmation`의 `provider_refunded_at <= refund_confirmed_at`, V19·V21 `chk_refund_execution_success`의 `cancellation_requested_at <= cancellation_succeeded_at`·`response_cancelled_at` 범위, V21 `chk_refund_execution_receipt`의 `receipt_requested_at <= receipt_observed_at`)는 결정 163에 따라 지운다(R7.18, PA-T1). PG 시각끼리(`response_cancelled_at >= cancellation_requested_at`)는 남긴다.

### 운영 기록 표

| 표 | 뜻 | 유일 제약·CHECK | 상태 |
|---|---|---|---|
| `approval_closure`(V37) | 승인 보류 종결 | `uq_approval_closure_request`·`_approval`, 근거 `CANCELLED`·`FAILED`·`READY` | 추가 전용 기록(상태 없음) |
| `approval_investigation`(V34·V38) | 보류 조사 | FK RESTRICT | 추가 전용 |
| `refund_cancellation_hold`(V24·V25) | 송신 보류(`HELD`의 근거·재개) | `uq_cancel_hold_id`·`_owner`·`uq_cancel_resume_request`. 목표 사유에 `PG_REQUEST_REJECTED`(결정 133, MC-P9) 추가 | 보류 기록(활성 여부는 `dispatch_state`가 정본) |
| `refund_closure`(V39) | 장기·귀속 미확정 종결과 귀속 판정 | `uq_refund_closure_request`·`_attempt`, `chk_refund_closure_attribution`·`_outcome`·`_receipt` | 추가 전용 |
| `refund_failure_closure`(목표, #166 참고 구현) | 운영자 실패 종결 근거 | `attempt_id`·`request_id`·`failure_event_id` 각각 유일 | 추가 전용 |
| `refund_late_cancellation`(목표 신설, 결정 157) | 늦은 취소 확인 | `attempt_id`·`request_id`·`event_id` 각각 유일, FK RESTRICT, 대상 실행 `FAILED`·뒤 실행 없음(코드 가드, 결정 179). `superseded_request_event_id`(만들지 않은 후속 실행 요청, NULL 허용·유일) | 상태 하나(`CONFIRMED`) — 상태 모델 참조 |
| `refund_investigation`(V27) | 환불 조사 | — | 추가 전용 |
| `payment_pg_webhook`(V15) | 통지 접수 | 통지 ID 유일 | 추가 전용 |
| `failed_payment_attempt`(V28) | 실패 시도 이력 | 공급자 거래 ID 유일 | 추가 전용 |
| `payment_request_trace`(V13)·`refund_execution_trace` | 추적 부모 | — | — |
| `processed_event`·`outbox_event`(공통) | Inbox·Outbox | `uk_processed_event` | 공통 라이브러리 |

## 상태 모델

표 읽는 법: 행 = 트리거(HTTP 명령·소비 이벤트·예약 작업·운영자 명령), 열 = 현재 상태. 칸은 `→ 다음 상태`(허용) / `무변화`(멱등·대상 아님) / `거절(오류 코드 또는 영구 실패)` 중 하나다. "(없음)" 열은 행이 아직 없을 때다. 잠금(lease·종결 표시)은 상태가 아니고 가드로만 쓴다(R21.2). 근거 열의 R은 [requirements.md](requirements.md), P는 [플랫폼 요구사항](../platform/requirements.md)이다.

### 1. 결제 `purchase_payment.status`

| 값 | 뜻 |
|---|---|
| `PENDING` | 접수됨. 결과 미확정(아직 시도 없음·진행 중 시도·조회 장애 포함) |
| `PAID` | 접수된 현재 시도의 PG 결제를 확인해 결과를 냄(끝) |
| `FAILED` | 운영자 보류 종결 (a)·(c), 또는 공급자가 결제 전체를 최종 거절(끝) |
| `EXPIRED` | Order 만료 사실로 닫힘. 금융 결과 아님(끝) |

| 트리거 \ 상태 | (없음) | `PENDING` | `PAID` | `FAILED` | `EXPIRED` | 근거 |
|---|---|---|---|---|---|---|
| `OrderPlaced` 첫 수신 | 만료 사실 없음 → `PENDING` / 있음 → `EXPIRED`(예약 없음) | 같은 입력 무변화 / 다른 입력 거절(영구) | 같음 | 같음 | 같음 | R1.2~1.5, R11.3 |
| `OrderExpired` 수신 | 무변화(생명주기만 `EXPIRED`) | 진행 시도 없음 → `EXPIRED` / 있음 → 무변화(작업자가 시도 표 #17~#19) | 무변화 + 만료 사실 저장 + 충돌 게이지 | 무변화 + 만료 사실 저장(정상 경합: 종결 (c) 뒤 Order 스스로 만료) | 무변화 | R11.4·R11.7, P3.13-4 |
| 결제 재개 작업: 접수된 현재 시도의 PG `PAID`(잠금 안 재확인) | 무변화(대상 없음) | → `PAID` + 시도 `SUCCEEDED` + `OrderPaymentCompleted` | 같은 결과 무변화 / 다른 결과 거절(`CONFLICT`) | 무변화(결과 불변). 청구 감시가 찾음(#11) | 무변화(결과 불변). 청구 감시가 찾음(#11) | R3.1·R3.7, I11·I15 |
| adapter의 결제 전체 최종 거절 | 무변화 | → `FAILED` + `OrderPaymentFailed` | 거절(`CONFLICT`) | 같은 결과 무변화 | 거절(`CONFLICT`) | R3.2(notes F2) |
| 미확정·조회 장애·대조 실패 | 무변화 | 무변화(`next_attempt_at` 재예약, `last_error_code`) | 무변화 | 무변화 | 무변화 | R2.4, R3.3~3.5 |
| 시도 실패 영수증 처리(R4.13) | 무변화 | 영수증 `PENDING`이고 만료 사실 없음 → 무변화(재결제) / 영수증 `EXPIRED` 또는 만료 사실 → `EXPIRED` | 무변화(대상 아님) | 무변화 | 무변화 | P3.12-2, R11.6 |
| 접수 전 시도의 만료 닫힘·`ORDER_EXPIRED` 응답·R11.8 | 무변화 | → `EXPIRED`(이벤트 없음) | 무변화 | 무변화 | 무변화 | R4.10, R11.5·R11.8 |
| 공급자 통지(R6) | 무변화(새 업무 없음) | 무변화(다음 조회 앞당김) | 무변화 | 무변화(청구 감시가 살아 있으면 감시 조회 앞당김) | 같음 | R6.4·R6.8 |
| ADMIN 승인 보류 종결(근거 맞음) | 거절 404 | (a)·(c) → `FAILED` + `OrderPaymentReviewClosed` / (b) → `EXPIRED` | 거절 409 `CLOSURE_PAYMENT_RESOLVED` | 거절 409 `APPROVAL_NOT_HELD` | 거절 409 `APPROVAL_NOT_HELD` | R13.5, P3.13-4 |
| `OrderPaymentResultRejected` 수신 | 거절(영구) | 거절(영구, 이 결제의 결과가 아님) | 결과 ID 같음 → 무변화 + `unlinked_charge`(`ORDER_REJECTED`) / 다름 → 거절(영구) | 거절(영구) | 거절(영구) | R17.1·17.2 |
| 청구 감시의 PG `PAID` 관측 | 무변화 | 무변화(감시 없음) | 무변화(감시 없음) | 무변화 + `unlinked_charge`(`LATE_CHARGE`) | 무변화 + `unlinked_charge`(`LATE_CHARGE`) | R16.4 |
| `RefundExecutionRequested` 수신 | 거절(영구) | 거절(`CONFLICT`) | 무변화(실행 접수, 결제 불변) | 거절(`CONFLICT`) | 거절(`CONFLICT`) | R7.1 |
| `POST …/approvals`(새 시도) | 거절 404 | 진행 시도 없음 → 무변화(시도 생성) / 있음 → 거절 409 `APPROVAL_IN_PROGRESS` | 거절 409 | 거절 409 | 거절 409 | R4.1 |
| `GET …/checkout` | 거절 404 | 만료 사실 있음 → 409 `ORDER_EXPIRED` / 진행 시도 → 409 `APPROVAL_IN_PROGRESS` / 그 밖 → 무변화(설정 반환) | 거절 409 | 거절 409 | 거절 409 | R4.8, 결정 169 |

### 2. 승인 시도 `payment_approval.status`

| 값 | 뜻 |
|---|---|
| `REQUESTED` | 인증 결과 내구 접수, Order 영수증 전. 확정 송신 없음 |
| `ADMITTED` | Order 영수증 저장. `confirm_deadline` 전까지 확정 송신 가능 |
| `REVIEW_REQUIRED` | 송신 중단·운영자 조사(사유 `review_reason`). 접수 전/후로 갈리는 동작은 직교 칸 "접수됨"(`admitted_at` 유무)으로 아래 표에 나눠 적는다 — 소급 후보 RC-13(notes E-2) |
| `FAILURE_CONFIRMED` | 접수된 시도의 PG `FAILED` 확인, Order 실패 영수증 대기 |
| `SUCCEEDED` | 결제 `PAID` 커밋(끝) |
| `FAILED` | 시도 실패 종료 또는 운영자 종결(끝). 종결 여부는 `approval_closure` 행이 정본 |
| `EXPIRED` | 주문 만료로 닫힘(끝) |

열 줄임: `REQ`=`REQUESTED`, `ADM`=`ADMITTED`, `RV-전`=`REVIEW_REQUIRED`·접수 전, `RV-후`=`REVIEW_REQUIRED`·접수 후, `FC`=`FAILURE_CONFIRMED`, `끝`=`SUCCEEDED`·`FAILED`·`EXPIRED`. 작업자 트리거는 lease를 잡은 한 회이고 끝 상태는 작업 대상이 아니다(`무변화`).

| # | 트리거 \ 상태 | (없음) | `REQ` | `ADM` | `RV-전` | `RV-후` | `FC` | `끝` | 근거 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `POST …/approvals` 새 `requestId` | 결제 `PENDING`·진행 시도 없음·공급자 시도 ID 미사용 → `REQ` / 아니면 거절 409·404 | 거절 409 `APPROVAL_IN_PROGRESS` | 같음 | 같음 | 같음 | 같음 | 무변화(새 행은 결제 조건을 따름) | R4.1 |
| 2 | 같은 `requestId` 재요청 | — | 같은 내용 무변화(최초 영수증) / 다른 내용 거절 `CONFLICT` | 같음 | 같음 | 같음 | 같음 | 같음 | R4.2 |
| 3 | 작업자: PG `READY` + Order 영수증 | 무변화 | → `ADM`(`admitted_at`, 첫 준비 때 `first_prepared_at`·`confirm_deadline`) | 무변화(같은 `admissionId` 최초 영수증) | 무변화(보류 중 접수 요청 안 함) | 무변화 | 무변화 | 무변화 | R4.3, P3.11-3 |
| 4 | 작업자: 접수 응답 `ORDER_EXPIRED` | 무변화 | → `EXPIRED`(결제 `EXPIRED`) | 무변화(접수 요청 없음) | 무변화 | 무변화 | 무변화 | 무변화 | R4.10 |
| 5 | 작업자: 접수 응답 `PRODUCT_SALE_RESTRICTED` | 무변화 | 무변화(접수 전 대기, 표현은 RC-2) | 무변화 | 무변화 | 무변화 | 무변화 | 무변화 | R4.18 |
| 6 | 작업자: 접수·실패 통지 409(만료·판매 제한 밖) | 무변화 | → `RV-전`(`ORDER_ADMISSION_CONFLICT`) | 무변화(통지 없음) | 무변화(사유 유지) | 무변화(사유 유지) | → `RV-후`(`ORDER_ADMISSION_CONFLICT`) | 무변화 | R4.12 |
| 7 | 작업자: Order 연계 장애(연결 실패·5xx) | 무변화 | 무변화(같은 `admissionId` 재요청) | 무변화 | 무변화 | 무변화 | 무변화(통지 재요청) | 무변화 | R4.17 |
| 8 | 작업자: 확정 송신(`READY`, `confirm_deadline` 전, lease 유효) | 무변화 | 거절(송신 가드: 영수증 없음) | 무변화(송신 뒤 조회) | 거절(송신 금지) | 거절(송신 금지) | 거절(송신 금지) | 거절(송신 금지) | R4.3·R4.5 |
| 9 | 작업자: 확정 송신 형식 거절 | 무변화 | 무변화(송신 없음) | → `RV-후`(`PG_REQUEST_REJECTED`) | 무변화 | 무변화 | 무변화 | 무변화 | R4.12 |
| 10 | 작업자: PG `PAID` | 무변화 | → `RV-전`(`PAID_WITHOUT_ADMISSION`) | → `SUCCEEDED` + 결제 `PAID`(결제 표 #3) | 무변화 + 사유를 `PAID_WITHOUT_ADMISSION`으로 한 번 올림 | → `SUCCEEDED` + 결제 `PAID` | 거절(근거 충돌, 시도 무변화, 실패 통지 유지 — [결정 188](../../../docs/decisions.md#closed-attempt-charge)) | `SUCCEEDED` 무변화 / `FAILED`·`EXPIRED`: 무변화(근거 충돌 기록). 결제가 열려 있으면 진행 시도가 #14로 보류되고, 끝났으면 청구 감시(R16) — [결정 188](../../../docs/decisions.md#closed-attempt-charge) | R3.1·R3.11, 결정 144·153 |
| 11 | 작업자: 검증된 PG `FAILED` | 무변화 | → `FAILED`(접수 전 실패, Order 통지 없음, 만료 사실 있으면 결제 `EXPIRED`) | → `FC` | → `FAILED`(접수 전 실패) | → `FC` | 같은 근거 무변화 / 다른 근거 거절 | 무변화 | R4.13, R5.1 |
| 12 | 작업자: 같은 시도 PG 전액 `CANCELLED` | 무변화 | → `RV-전`(`PG_CANCELLED`) | → `RV-후`(`PG_CANCELLED`) | 무변화(관측만, 사유 유지) | 무변화(관측만) | 무변화(관측만) | 무변화 | R13.8 |
| 13 | 작업자: `confirm_deadline` 지남 + PG `PENDING`·`READY` | 무변화 | 무변화(기한 없음) | → `RV-후`(`CONFIRM_WINDOW_EXPIRED`) | 무변화 | 무변화 | 무변화 | 무변화 | R4.6, 결정 124 |
| 14 | 작업자: 근거 대조 실패 | 무변화 | → `RV-전`(`PG_INVALID_EVIDENCE`) | → `RV-후`(`PG_INVALID_EVIDENCE`) | 무변화(사유 유지, 오류 기록) | 같음 | 무변화(오류 기록) | 무변화 | R4.12, R12.10 |
| 15 | 작업자: PG 조회 장애 | 무변화 | 무변화(오류 기록) | 같음 | 같음 | 같음 | 같음 | 무변화 | R12.7 |
| 16 | 작업자: Order 실패 영수증 수신 | 무변화 | 무변화(통지 없음) | 무변화 | 무변화 | 무변화 | → `FAILED`(결제 표 #6) | 무변화 | R4.13 |
| 17 | 작업자: 만료 사실 + 접수 전 + PG 관측 이력 없음 | 무변화 | → `EXPIRED`(PG 호출 없음, 결제 `EXPIRED`) | 해당 없음 → 무변화(#19) | 무변화(보류 유지, ADMIN 종결 (b)) | 무변화 | 무변화 | 무변화 | R11.5, 결정 126 |
| 18 | 작업자: 만료 사실 + 접수 전 + 이력 있음 → PG 한 번 조회 | 무변화 | `PAID` → `RV-전`(`PAID_WITHOUT_ADMISSION`) / `CANCELLED` → `RV-전`(`PG_CANCELLED`) / `PENDING` → `RV-전`(`PG_PENDING_AT_ORDER_EXPIRY`) / `READY`·`FAILED` → `EXPIRED`(잠금 안 `REQ` 재확인) / 장애 → 무변화 | 무변화 | 무변화(#20만 예외) | 무변화 | 무변화 | 무변화 | R11.5, D-044 |
| 19 | 작업자: 만료 사실 + 접수됨 | 무변화 | 무변화 | → `RV-후`(`ORDER_EXPIRY_CONFLICT`), 조회 계속 | 무변화 | 무변화(사유 유지) | 무변화(통지 경로 계속) | 무변화 | R11.7 |
| 20 | 작업자: R11.8 자동 만료(사유 `PG_INVALID_EVIDENCE`·준비/실패 없음·PG `READY`·만료 사실) | 무변화 | 무변화 | 무변화 | 조건 충족 → `EXPIRED`(결제 `EXPIRED`) / 다른 사유 무변화 | 무변화(접수됨) | 무변화 | 무변화 | R11.8 |
| 21 | 결제 `PAID` 커밋(결제 재개 작업·통지 경로) | 무변화 | 무변화(접수 전 시도로는 `PAID`가 생기지 않음) | → `SUCCEEDED` | 무변화 | → `SUCCEEDED` | 거절(근거 충돌, #10과 같음) | 무변화 | R12.12, D-051 |
| 22 | ADMIN 종결(근거 `CANCELLED`·`FAILED`·`READY`) | 거절 404 | 거절 409 `APPROVAL_NOT_HELD` | 같음 | → `FAILED` + `approval_closure`, 결제 (b)/(c) | → `FAILED` + `approval_closure`, 결제 (a) | 거절 409 `APPROVAL_NOT_HELD` | 같은 `requestId` 무변화(최초 결과) / 그 밖 409 | R13.2~13.5 |
| 23 | ADMIN 종결 거절 근거(`PAID`·`PENDING`·대조 실패·장애·결제 확정) | 거절 404 | 409 `APPROVAL_NOT_HELD` | 같음 | 무변화(관측 기록, lease 반환, `next_at` 복원) + 409 | 같음 | 409 | 409 | R13.4 |
| 24 | ADMIN 종결 중 작업자 lease 살아 있음 | 404 | 409 | 409 | 무변화 + 409 `APPROVAL_CLOSURE_BUSY` | 같음 | 409 | 409 | R13.3 |
| 25 | ADMIN 조사 기록 | 404 | 거절 409(보류 아님) | 같음 | 무변화(기록 추가) | 같음 | 409 | 과거 보류였으면 조회만, POST 409 | R12.3·R12.6 |
| 26 | 작업자 회 종료(`finish`) | 무변화 | 무변화(`next_at` = 재시도 간격) | 같음 | 무변화(`next_at` = 결정 122 간격) | 같음 | 무변화 | 무변화 | R12.11 |

#### 승인 보류 사유 표 (플랫폼에서 옮긴 모듈 내부 표, MC-P12)

목표 칸은 `review_reason`(enum + CHECK, 소급 RC-2 — 지금 `last_error_code`). 공통 규칙: 첫 사유 유지(P4.6-1, 결정 144), 재조회 간격(결정 122), 시스템은 PG 취소를 하지 않음(P3.13-2), 결제 `PAID` 커밋 때 접수된 보류는 같은 트랜잭션에서 `SUCCEEDED`(P4.6-2).

| 사유 | 들어가는 조건 | 접수 | 고객 안내(결정 123·132) | 자동으로 벗어나는 길 | 운영 종결(결정 115) |
|---|---|---|---|---|---|
| `PAID_WITHOUT_ADMISSION` | 접수 전 시도에서 PG `PAID` | 전 | 관측 `PAID`면 `CHARGED` | 없음(사유 고정) | `PAID`인 동안 409. 콘솔 취소 뒤 `CANCELLED` 근거 → (b)·(c) |
| `PG_REQUEST_REJECTED` | 확정 송신이 형식 갖춘 거절을 받음 | 후 | 관측대로 | PG `PAID` → `SUCCEEDED`, `FAILED` → 시도 실패 통지 | `READY`·`FAILED`·`CANCELLED` → (a) |
| `PG_INVALID_EVIDENCE` | PG 응답이 시도·결제와 대조되지 않음 | 전·후 | 항상 `CHARGE_UNCONFIRMED` | 접수 전·준비 전·실패 없음 + `READY` + 만료 사실 → `EXPIRED`(R11.8) | → (a)·(b)·(c) |
| `ORDER_ADMISSION_CONFLICT` | Order 접수·실패 통지가 409(만료·판매 제한 밖) | 전·후 | 관측대로 | 접수 후면 PG `PAID`·`FAILED`로 위와 같음 | → (a)·(b)·(c) |
| `ORDER_EXPIRY_CONFLICT` | 접수된 시도에 만료 사실 도착(정상 경로에서는 생기지 않는 방어) | 후 | 관측대로 | PG `PAID`·`FAILED`로 위와 같음 | → (a) |
| `PG_CANCELLED` | 같은 시도 PG 전액 `CANCELLED`, 보류 전이었음 | 전·후 | `CHARGE_CANCELLED` | 없음 | `CANCELLED` 근거 → (a)·(b)·(c) |
| `CONFIRM_WINDOW_EXPIRED` | 접수 후 `confirm_deadline` 지남 + PG `PENDING`·`READY` | 후 | `PENDING` → `CHARGE_UNCONFIRMED`, `READY` → `NOT_CHARGED` | 늦은 `PAID` → 원래 주문 `PAID`, 늦은 `FAILED` → 실패 통지 | → (a) |
| `PG_PENDING_AT_ORDER_EXPIRY` | 접수 전 + 만료 사실 + 관측 이력 + 조회 `PENDING` | 전 | `CHARGE_UNCONFIRMED` | 늦은 `PAID` → 사유 `PAID_WITHOUT_ADMISSION`, `FAILED` → 접수 전 실패(결제 `EXPIRED`) | → (b) |

### 3. 주문 생명주기 `payment_order_lifecycle.status` (목표 신설 칸, PA-T6)

| 값 | 뜻 |
|---|---|
| `OPEN` | 만료 사실 없음 |
| `EXPIRED` | Order의 `OrderExpired`를 받음(끝) |

| 트리거 \ 상태 | (없음) | `OPEN` | `EXPIRED` | 근거 |
|---|---|---|---|---|
| `OrderPlaced` 수신 | → `OPEN`(원구매·`expiresAt` 값 저장) | 같은 조건 무변화 / 다른 조건 거절(영구) | 같은 조건 → 무변화(결제를 `EXPIRED`로 생성) / 다른 조건 거절(영구) | R11.2·R11.3 |
| `OrderExpired` 첫 수신 | → `EXPIRED`(행 생성, 원구매는 이벤트 값) | 연결값 일치 → `EXPIRED` / 불일치 거절(영구) | 같은 만료 `eventId` 무변화 / 다른 `eventId` 거절(영구, `uk_payment_order_expiry_event`) | R11.1·R11.9 |
| `OrderExpired` 재전달(Inbox 있음) | 무변화 | 무변화 | 무변화 | P2.3-1 |
| 결제 `PAID` 커밋 | 무변화 | 무변화 | 무변화(충돌 게이지가 셈, R11.7) | MC-P7 |
| 승인 작업자·종결의 만료 확인(읽기) | 무변화 | 무변화("만료 없음") | 무변화("만료 있음") | R11.5, R13.9 |

### 4. 주문에 연결되지 않은 청구 `unlinked_charge.status` (목표 신설, 결정 153·154)

| 값 | 뜻 |
|---|---|
| `OPEN` | 주문에 반영되지 않은 PG 청구가 있음. 보류 목록·경보·고객 안내 `CHARGED` |
| `CLOSED` | 운영자가 공급자 콘솔에서 취소한 뒤 PG 전액 `CANCELLED` 근거로 닫음(끝) |

| 트리거 \ 상태 | (없음) | `OPEN` | `CLOSED` | 근거 |
|---|---|---|---|---|
| 청구 감시 PG `PAID` 관측 | → `OPEN`(`LATE_CHARGE`) | 무변화(같은 출처) | 무변화(같은 출처는 하나, 경고 로그) | R16.4 |
| `OrderPaymentResultRejected` 수신(결과 ID 일치) | → `OPEN`(`ORDER_REJECTED`) | 무변화(같은 결과) | 무변화 | R17.1·17.3 |
| ADMIN 종결 근거 전액 `CANCELLED` | 거절 404 | → `CLOSED` | 같은 `requestId`·내용 무변화(최초 결과) / 다른 요청 거절 409 `UNLINKED_CHARGE_NOT_OPEN`(가칭) | R17.6 |
| ADMIN 종결 근거 `PAID`·그 밖·대조 실패·장애 | 거절 404 | 무변화 + 409 `CLOSURE_PG_PAID`·`CLOSURE_PG_NOT_CANCELLED`·`CLOSURE_PG_INVALID_EVIDENCE`·`CLOSURE_PG_UNAVAILABLE` | 거절 409 | R17.6 |
| ADMIN 종결 중 lease 살아 있음 | 404 | 무변화 + 409 `UNLINKED_CHARGE_CLOSURE_BUSY`(가칭) | 409 | R17.6 |
| ADMIN 같은 `requestId` 다른 내용 | 404 | 거절 409 `UNLINKED_CHARGE_CLOSURE_CHANGED` | 같음 | R17.6 |
| ADMIN 조사 기록 | 404 | 무변화(기록 추가) | 거절 409 | R17.8 |

결제·시도 상태는 이 표의 어떤 전이로도 바뀌지 않는다(결정 154 "다시 열지 않는다"). 종결 뒤 결제의 고객 표현: 저장 `PAID` + 닫힌 `ORDER_REJECTED` 기록이면 응답 `status`만 `CHARGE_RETURNED`(가칭)로 계산한다(R17.7, [결정 190](../../../docs/decisions.md#charge-returned-view)).

### 5. 청구 감시 `payment_charge_watch.status` (목표 신설, 결정 154)

| 값 | 뜻 |
|---|---|
| `WATCHING` | 끝난 결제를 `watch_until`까지 주기 조회 중 |
| `CHARGE_FOUND` | PG `PAID`를 관측해 `unlinked_charge`를 만듦(끝) |
| `ENDED` | `watch_until`에 이름(끝) |

| 트리거 \ 상태 | (없음) | `WATCHING` | `CHARGE_FOUND` | `ENDED` | 근거 |
|---|---|---|---|---|---|
| 결제가 종결·만료·실패로 끝나는 트랜잭션(시도 행 있음) | → `WATCHING` | 무변화 | 무변화 | 무변화 | R16.1 |
| 결제가 끝남(시도 행 없음) | 무변화(만들지 않음) | — | — | — | R16.1 |
| 감시 작업 조회: `READY`·`PENDING`·`FAILED`·`CANCELLED` | 무변화 | 무변화(다음 조회 예약) | 무변화(조회 안 함) | 무변화(조회 안 함) | R16.2 |
| 감시 작업 조회: `PAID` | 무변화 | → `CHARGE_FOUND` + `unlinked_charge`(`LATE_CHARGE`) | 무변화 | 무변화 | R16.4 |
| 조회 장애·근거 대조 실패 | 무변화 | 무변화(오류 기록) | 무변화 | 무변화 | R16.6 |
| `watch_until` 도달(같은 모듈 시계) | 무변화 | → `ENDED` | 무변화 | 무변화 | R16.5 |
| 공급자 통지 | 무변화 | 무변화(다음 조회 앞당김) | 무변화 | 무변화 | R16.3 |

### 6. 환불 실행 `refund_execution.status` × 직교 `dispatch_state`

| `status` 값 | 뜻 |
|---|---|
| `PENDING` | 금융 미확정 |
| `CONFIRMED_UNATTRIBUTED` | PG 조회로 원결제 전액 취소를 확인했지만 우리 요청으로 귀속 못 함(금융 완료) |
| `SUCCEEDED` | 우리 취소 ID의 성공을 확인(금융 완료, 우리 요청에 귀속)(끝) |
| `FAILED` | 실패 확정. 운영자 종결 여부는 `refund_failure_closure` 행이 정본(끝 — 실패 뒤 감시·늦은 취소 확인은 별도 기록) |

| `dispatch_state` 값 | 뜻 |
|---|---|
| `NOT_PREPARED` | 첫 송신 준비 전 |
| `SENDING` | 자동 송신 일정 있음(`cancellation_resend_deadline` 전) |
| `RECEIPTED` | PG 접수 응답 받음 — 자동 송신 멈춤, 연결 조회만 |
| `HELD` | PG 형식 거절 — ADMIN 재개 가능(기한 전) |
| `BALANCE_BLOCKED` | 잔액 변화 — 재개 불가 |
| `WINDOW_ENDED` | 재송신 기한 지남 |
| `STOPPED` | 금융 확정 또는 실패 확정 |

실패 종결 대상(지금 `failureClosableAt`)은 `status=PENDING ∧ dispatch_state ∈ {HELD, BALANCE_BLOCKED, WINDOW_ENDED}`다(MC-P8).

| # | 트리거 \ `status` | (없음) | `PENDING` | `CONFIRMED_UNATTRIBUTED` | `SUCCEEDED` | `FAILED` | 근거 |
|---|---|---|---|---|---|---|---|
| 1 | `RefundExecutionRequested` 첫 수신(결제 `PAID`·연결값·금액 일치) | → `PENDING`/`NOT_PREPARED` | 같은 `attemptId`·같은 요청 무변화 / 다른 요청 거절(영구) | 같음 | 같음 | 같음 | R7.1, P3.19-1 |
| 2 | 후속 실행 요청(`previousAttemptId` = 이 행) | 거절 `CONFLICT`(선행 없음) | 거절 `CONFLICT`(선행 미확정) | 거절 `CONFLICT` | 거절 `CONFLICT` | 늦은 취소 확인 기록 있음 → 무변화(후행 행 만들지 않음, PG 호출 없음, 요청 `eventId`를 확인 기록에 남김, 결정 179) / 후행 없음 → 무변화(후행 새 행 `PENDING`) / 있음 → 거절 `CONFLICT` | R7.14, R19.6, P3.19-2 |
| 3 | 첫 송신 준비 | — | `NOT_PREPARED` → `SENDING` + `RefundExecutionPrepared`(`confirmationDelayAt`=adapter 기한) / 이미 준비 → 무변화(조회만) | 무변화 | 무변화 | 무변화 | R7.17 |
| 4 | 송신 응답: PG 접수(받아 둠·실패 기록) | — | `SENDING` → `RECEIPTED` | 무변화 | 무변화 | 무변화 | R7.11 |
| 5 | 송신 응답: 형식 거절(adapter 분류) | — | `SENDING` → `HELD` + `refund_cancellation_hold` | 무변화 | 무변화 | 무변화 | R7.8, 결정 133 |
| 6 | 송신 직전 조회·응답: 잔액 변화 | — | `SENDING`·`HELD` → `BALANCE_BLOCKED` | 무변화 | 무변화 | 무변화 | R7.3 |
| 7 | 송신 준비 단계: 재송신 기한 지남(같은 모듈 시계) | — | `SENDING` → `WINDOW_ENDED` | 무변화 | 무변화 | 무변화 | R7.7·R7.19 |
| 8 | 송신 응답: 우리 취소 성공 | — | → `SUCCEEDED`/`STOPPED` + `RefundPaymentConfirmed` | 같은 취소 ID·금액 → `SUCCEEDED`(이벤트 없음) / 다른 ID 거절 | 같은 근거 무변화 / 다른 근거 거절 `CONFLICT` | 거절 `CONFLICT`(상태 불변, 실패 뒤 감시가 조사) | R7.5·R7.13, I18 |
| 9 | 조회: 같은 원결제 전액 취소 확인 | — | → `CONFIRMED_UNATTRIBUTED`/`STOPPED` + `RefundPaymentConfirmed` | 같은 근거 무변화 / 다른 근거 거절 `CONFLICT` | 무변화 | 무변화 + 조사 `LATE_CANCELLATION`·경보(R19.2) | R7.4·R7.12 |
| 10 | 조회: 우리 취소 `FAILED` + 원결제 `PAID`·전액 미취소 | — | `RECEIPTED`(실패 기록) → `FAILED`/`STOPPED` + `RefundExecutionFailed` | 거절(상태 불변, I18) | 거절(상태 불변) | 같은 근거 무변화 | R7.6, P3.22-1 |
| 11 | 조회 미확정·장애·대조 실패 | — | 무변화(다음 조회) | 무변화 | 무변화 | 무변화 | R7.7 |
| 12 | ADMIN 송신 보류 재개 | 404 | `HELD`·기한 전·종결 표시 없음 → `SENDING` / 종결 표시 있음 409 `REFUND_CLOSURE_BUSY` / 그 밖 409 | 409 | 409 | 409 | R7.16 |
| 13 | ADMIN 장기·귀속 미확정 종결(근거 맞음) | 404 | 금융 미확정·기한 지남 → `CONFIRMED_UNATTRIBUTED`/`STOPPED` + `RefundPaymentConfirmed` + `refund_closure`(`COMPLETED`) / 기한 전 409 `REFUND_NOT_CLOSABLE` | 종결 기록 없음 → 무변화 + `refund_closure`(`RECORDED`) / 있음 409 | 409 `REFUND_NOT_CLOSABLE` | 409 `REFUND_NOT_CLOSABLE` | R15 |
| 14 | ADMIN 실패 종결(근거 맞음) | 404 | `HELD`·`BALANCE_BLOCKED`·`WINDOW_ENDED` → `FAILED`/`STOPPED` + `refund_failure_closure` + `RefundFailureClosed` / `SENDING`·`RECEIPTED`·`NOT_PREPARED` 409 `REFUND_NOT_FAILURE_CLOSABLE` | 409 | 409 | 409 | R18 |
| 15 | ADMIN 실패 종결 근거 아님·장애·lease 경합 | 404 | 무변화 + 409(`REFUND_NOT_FAILURE_CLOSABLE`·`CLOSURE_PG_UNAVAILABLE`·`REFUND_CLOSURE_BUSY`) | 409 | 409 | 409 | R18.3 |
| 16 | ADMIN 늦은 취소 반영 | 404 | 409 `LATE_CANCELLATION_NOT_ELIGIBLE`(가칭) | 409 | 409 | 근거 전액 `CANCELLED`·확인 기록 없음 → 무변화 + `refund_late_cancellation` + `RefundLateCancellationConfirmed` / 뒤 실행 있음 → 409 `LATE_CANCELLATION_SUPERSEDED`(PG 조회 없음, 결정 179) / 근거 아님 409 | R19.3·R19.4 |
| 17 | 실패 뒤 감시 조회 | — | 무변화(대상 아님) | 무변화 | 무변화 | 무변화(전액 취소면 #9 마지막 칸) | R19.1 |
| 18 | 공급자 취소 계열 통지 | — | 무변화(조회 앞당김) | 무변화 | 무변화 | 무변화(실패 뒤 감시 앞당김) | R6.7·R6.9 |
| 19 | `SettlementRefundRecorded` 수신 | 거절(영구) | 거절(영구: 금융 확인 없음) | 무변화 + 정산 응답 ID 기록 | 같음 | 늦은 취소 확인 있음 → 그 기록에 응답 ID / 없음 → 거절(영구) | R20.1 |
| 20 | ADMIN 조사 기록 | 404 | 지연(기한 지남) 대상이면 무변화(기록 추가) / 아니면 409 | 무변화(기록 추가) | 409 | 늦은 취소 조사 대상이면 무변화 / 아니면 409 | R8.8 |

`dispatch_state` 전이 요약(열 = 현재 값):

| 트리거 \ `dispatch_state` | `NOT_PREPARED` | `SENDING` | `RECEIPTED` | `HELD` | `BALANCE_BLOCKED` | `WINDOW_ENDED` | `STOPPED` |
|---|---|---|---|---|---|---|---|
| 첫 송신 준비 | → `SENDING` | 무변화 | 무변화 | 무변화 | 무변화 | 무변화 | 무변화 |
| 송신 응답 PG 접수 | 거절(송신 없음) | → `RECEIPTED` | 같은 근거 무변화 | 거절(송신 금지) | 거절(송신 금지) | 거절(송신 금지) | 무변화 |
| 송신 응답 형식 거절 | 거절 | → `HELD` | 무변화 | 무변화 | 무변화 | 무변화 | 무변화 |
| 잔액 변화 | 무변화 | → `BALANCE_BLOCKED` | 무변화 | → `BALANCE_BLOCKED` | 무변화 | 무변화 | 무변화 |
| 재송신 기한 지남 | 무변화 | → `WINDOW_ENDED` | 무변화 | 무변화(재개만 막힘) | 무변화 | 무변화 | 무변화 |
| ADMIN 재개(기한 전) | 409 | 409 | 409 | → `SENDING` | 409 | 409 | 409 |
| 금융 확정·실패 확정(`status` 전이) | → `STOPPED` | → `STOPPED` | → `STOPPED` | → `STOPPED` | → `STOPPED` | → `STOPPED` | 무변화 |
| 재송신 작업 | 무변화 | 송신(lease·종결 표시 없음일 때) | 무변화 | 무변화 | 무변화 | 무변화 | 무변화 |

### 7. 늦은 취소 확인 `refund_late_cancellation` (목표 신설, 결정 157)

상태 값은 `CONFIRMED` 하나다(기록이 있으면 확인됨). 실패한 실행은 다시 열지 않는다.

| 트리거 \ 상태 | (없음) | `CONFIRMED` | 근거 |
|---|---|---|---|
| ADMIN 반영 요청, 실행 `FAILED`, 트랜잭션 밖 재조회 전액 `CANCELLED` | → `CONFIRMED` + `RefundLateCancellationConfirmed` | 같은 `requestId`·내용 무변화(최초 결과) / 다른 요청 거절 409 `LATE_CANCELLATION_ALREADY_CONFIRMED`(가칭) | R19.3·19.4 |
| ADMIN 반영 요청, 근거가 전액 `CANCELLED` 아님·대조 실패 | 거절 409(기록 없음) | 거절 409 | R19.3 |
| ADMIN 반영 요청, PG 조회 장애 | 거절 409 `CLOSURE_PG_UNAVAILABLE` | 거절 409 | 흐름 3 S14 |
| ADMIN 반영 요청, 실행이 `FAILED` 아님 | 거절 409 `LATE_CANCELLATION_NOT_ELIGIBLE` | — | R19.3 |
| ADMIN 반영 요청, 같은 환불의 뒤 실행 있음 | 거절 409 `LATE_CANCELLATION_SUPERSEDED`(가칭, 기록 없음, PG 조회 없음 — 뒤 실행이 자기 경로로 끝남, 결정 179) | 거절 409 | R19.3 |
| `SettlementRefundRecorded` 수신 | 거절(영구) | 무변화 + 응답 ID 기록 | R20.1 |
| 고객 재시도 사전 확인(같은 결제) | 무변화(이전 실패로 봄) | 이미 환불된 결제로 봄 → 후속 실행을 만들지 않고 PG를 부르지 않음, `superseded_request_event_id` 기록(영구 실패 아님, 결정 179) | R19.6 |

## API 계약

공통: 익명 401, 역할 없음 403, 타인·없는 대상 404, 잘못된 입력·페이지 400. 고객·ADMIN 응답은 토큰·보호 본문·암호문·공급자 원문·공급자 이름을 넣지 않는다. 운영 명령은 모두 `requestId`(UUID) 멱등: 같은 `requestId`·같은 내용이면 PG를 다시 부르지 않고 최초 결과, 다른 내용이면 409 `*_CHANGED`. 운영 명령은 PG 취소·승인을 실행하지 않는다.

| 메서드 · 경로 | 역할 | 요청 → 응답 | 오류 코드 | 요구사항 |
|---|---|---|---|---|
| `GET /api/v1/payments?page&size` | MEMBER | 본인 결제 목록(`createdAt DESC, id DESC`, `size 1~100`), `reviewNotice=null` | 400 | R8.1~8.4 |
| `GET /api/v1/payments/{orderNo}` | MEMBER | 결제 상세 + `reviewNotice`. 응답 `status`는 저장 값, 단 R17.7 조건이면 `CHARGE_RETURNED`(응답 전용, [결정 190](../../../docs/decisions.md#charge-returned-view)) | 404 | R8.4, R14, R17.7 |
| `GET /api/v1/payments/{orderNo}/checkout` | MEMBER | 결제창 공개 설정, `no-store` | 409 `ORDER_EXPIRED`(만료 사실 수신 뒤만)·`APPROVAL_IN_PROGRESS`·끝 상태 409, 404 | R4.8, R4.16 |
| `POST /api/v1/payments/{orderNo}/approvals` | MEMBER | `{requestId, supplierAttemptId, paymentToken}` → 202 `{requestId, orderNo, paymentId, receivedAt}` + Location | 409 `APPROVAL_IN_PROGRESS`·`CONFLICT`, 404 | R4.1·R4.2 |
| `GET /api/v1/payments/{orderNo}/approvals/{requestId}` | MEMBER | 상태·접수·종결 시각 + `reviewNotice`, `no-store` | 404 | R4.14, R14 |
| `GET /api/v1/payments/{orderNo}/attempt-failures` | MEMBER | 실패 시도 페이지 + `checkedAt`·`collectionPending`, `no-store` | 404 | R5.5·R5.9 |
| `POST /api/v1/payments/provider-notifications`(가칭, 지금 `/api/v1/payments/webhooks/portone`) | 공개(서명) | adapter 서명 검증 → 200 | 401·405·409(ID 재사용)·413 | R6, PA-T17 |
| `GET /api/v1/admin/approval-reviews?reason&page&size`, `/{approvalId}`, `/{approvalId}/investigations`(GET·POST) | ADMIN | 보류 목록·상세(종결 기록 포함)·조사 이력·조사 기록 | 404(보류 이력 없음)·409(보류 아님) | R12 |
| `POST /api/v1/admin/approval-reviews/{approvalId}/closure` | ADMIN | `{requestId, note}` → 시도·결제 상태·종결 기록 | 409 `APPROVAL_NOT_HELD`·`APPROVAL_CLOSURE_BUSY`·`APPROVAL_CLOSURE_CHANGED`·`CLOSURE_PG_PAID`·`CLOSURE_PG_PENDING`·`CLOSURE_PG_INVALID_EVIDENCE`·`CLOSURE_PG_UNAVAILABLE`·`CLOSURE_PAYMENT_RESOLVED` | R13 |
| `GET /api/v1/admin/unlinked-charges?source&status&page&size`, `/{chargeId}`, `/{chargeId}/investigations`(GET·POST) (목표) | ADMIN | 기록 목록·상세·조사 | 404·409 | R17.5·R17.8 |
| `POST /api/v1/admin/unlinked-charges/{chargeId}/closure` (목표) | ADMIN | `{requestId, note}` → 기록 상태·근거 | 409 `UNLINKED_CHARGE_NOT_OPEN`·`UNLINKED_CHARGE_CLOSURE_BUSY`·`UNLINKED_CHARGE_CLOSURE_CHANGED`·`CLOSURE_PG_PAID`·`CLOSURE_PG_NOT_CANCELLED`·`CLOSURE_PG_INVALID_EVIDENCE`·`CLOSURE_PG_UNAVAILABLE`(새 코드는 가칭) | R17.6 |
| `GET/POST /api/v1/admin/refund-cancellation-holds`, `…/{holdId}/resume` | ADMIN | 송신 보류 목록·상세·재개(`{requestId}`) | 409 `REFUND_CLOSURE_BUSY`·재개 불가 | R7.16 |
| `GET /api/v1/admin/refund-delays?kind=DELAYED|UNATTRIBUTED|LATE_CANCELLATION`, `/{attemptId}`, `/{attemptId}/investigations` | ADMIN | 목록·상세(종결·실패 종결·늦은 취소 기록 포함)·조사 이력·조사 기록. 항목에 `investigationCount`·`lastInvestigatedBy`·`lastInvestigatedAt` | 400(`kind`)·409 | R8.8·R8.9, R15.7, R19.2 |
| `POST /api/v1/admin/refund-delays/{attemptId}/closure` | ADMIN | `{requestId, attribution, note}` → 실행 상태·최초 금융 확인·종결 기록 | 409 `REFUND_NOT_CLOSABLE`·`REFUND_CLOSURE_BUSY`·`REFUND_CLOSURE_CHANGED`·`REFUND_CLOSURE_PG_NOT_CANCELLED`·`CLOSURE_PG_INVALID_EVIDENCE`·`CLOSURE_PG_UNAVAILABLE`·`CONFLICT` | R15 |
| `POST /api/v1/admin/refund-delays/{attemptId}/failure-closure` (목표, #166) | ADMIN | `{requestId, note}` → 실행 상태·실패 종결 기록 | 409 `REFUND_NOT_FAILURE_CLOSABLE`·`REFUND_CLOSURE_BUSY`·`REFUND_FAILURE_CLOSURE_CHANGED`(가칭)·`CLOSURE_PG_UNAVAILABLE` | R18 |
| `POST /api/v1/admin/refund-delays/{attemptId}/late-cancellation` (목표, 가칭) | ADMIN | `{requestId, note}` → 늦은 취소 확인 기록 | 409 `LATE_CANCELLATION_NOT_ELIGIBLE`·`LATE_CANCELLATION_ALREADY_CONFIRMED`·`LATE_CANCELLATION_PG_NOT_CANCELLED`·`CLOSURE_PG_UNAVAILABLE`·`REFUND_CLOSURE_BUSY`(가칭) | R19.3·19.4 |
| `POST /internal/v1/orders/{orderNo}/payment-admissions`, `…/{admissionId}/failure` (Order가 제공, Payment가 호출) | 서비스 키 | Order 모듈 스펙 | `ORDER_EXPIRED`·`PRODUCT_SALE_RESTRICTED`·409 | R4.3·R4.13·R4.17 |

결제 진행 관측과 고객 다음 행동(요약):

| 관측 | Order | 시도 | 결제 | 고객 다음 행동 |
|---|---|---|---|---|
| 인증 결과 202 접수 | `PENDING` | `REQUESTED` | `PENDING` | 진행 조회 |
| Order 영수증 저장 | `PAYMENT_PROCESSING` | `ADMITTED` | `PENDING` | 결과 확인, 새 결제창 없음 |
| 개별 실패 + 기한 안 | `PENDING` | `FAILED` | `PENDING` | 같은 주문 새 인증 |
| 만료 응답 또는 기한 뒤 실패 | `EXPIRED` | `EXPIRED`·`FAILED` | `EXPIRED` | 새 주문 |
| PG 성공 확정 | 이벤트 반영 뒤 `PAID` | `SUCCEEDED` | `PAID` | 라이브러리 확인 |
| 보류 | 진행 유지 | `REVIEW_REQUIRED` | `PENDING` | `reviewNotice` |
| 운영자 종결 | `FAILED`(만료면 유지) | `FAILED` + 종결 기록 | `FAILED`·`EXPIRED` | 새 주문 |
| 끝난 결제의 늦은 청구·Order 거절 | 그대로 | 그대로 | 그대로 | `reviewNotice=CHARGED`(R14.4) |

## 이벤트

소비 실패 처리는 공통(재시도 → 실패 보관 표 + 키 정지, [P2.6](../platform/requirements.md), 결정 155·160). 업무 멱등 키는 Inbox와 별도의 DB 유일 제약이다(P2.3-3).

### 발행 (모두 `stove.payment.v1`, 키 `orderNo`, Outbox는 상태 전이와 같은 트랜잭션)

| 이벤트(플랫폼 행) | 발행 지점 | 이 모듈이 지키는 것 | 업무 멱등(Payment 쪽) | 상태 |
|---|---|---|---|---|
| `OrderPaymentCompleted`(design 이벤트 목록 #24) | `ResolvePayment.confirm` → `PaymentResultOutbox` | 접수된 현재 시도만(R3.1), `admissionId` 필수·새 `schemaVersion`(R3.12) | `uk_purchase_payment_result` | 현행 → 칸 변경(T1) |
| `OrderPaymentFailed`(#25) | `ResolvePayment`(adapter 최종 거절만) | 운영자 종결을 싣지 않음 | 같음 | 현행 → 발행 경로 확인(notes F2) |
| `OrderPaymentReviewClosed`(목표 행) | `CloseApprovalReview` | (a)·(c)만, `admissionId`는 (a)만 | `uk_purchase_payment_result`, `approval_closure.request_id` | 목표(T18) |
| `RefundExecutionPrepared`(#20) | `RecordRefundPreparation` | 실행당 한 번, `confirmationDelayAt`=adapter 기한 | `uk_refund_preparation_event` | 현행 |
| `RefundPaymentConfirmed`(#28) | `RecordRefundCancellationSuccess`, `RefundConfirmationOutbox` | 실행당 첫 금융 확인 한 번, 귀속을 싣지 않음(`source` 삭제, 새 `schemaVersion`) | `uk_refund_execution_confirmation` | 현행 → 칸 삭제(T19) |
| `RefundExecutionSucceeded`(#29) | 지금 `RecordRefundCancellationSuccess:46` | 목표: 발행 중단 — 귀속은 `refund_execution.status`가 정본(결정 164) | — | 폐기 대상(PA-T3, P2.8) |
| `RefundExecutionFailed`(#38) | `ReconcileRefundPayment` | PG 확정 실패만, `cancellationId`는 우리 취소 ID | `uk_refund_execution_failure` | 현행 |
| `RefundFailureClosed`(목표 행) | `CloseRefundFailure` | 운영자 종결만, 본문은 식별자·`closureId`·`failedCancellationId` | `refund_failure_closure.failure_event_id` 유일 | 목표(T4) |
| `RefundLateCancellationConfirmed`(목표 행) | `ConfirmLateCancellation`(가칭) | `FAILED` 실행의 늦은 전액 취소 확인만 | `refund_late_cancellation.attempt_id`·`event_id` 유일 | 목표(T4) |

재발행: 위 현행·목표 이벤트를 같은 `eventId`·본문·키로 다시 내는 운영 경로를 둔다(`republish-owner: payment`, P2.2-7, T7·PA-T13).

### 소비

| 이벤트(플랫폼 행) | 리스너 → Mapper → 서비스 | 소비 그룹 | Mapper 검증 | 업무 멱등 키 | 실패 처리 |
|---|---|---|---|---|---|
| `OrderPlaced`(#23) | `OrderEventListener` → `OrderPaymentMapper.from` → `ReceiveOrderPayment` | `payment-order-request` | 봉투·키=`orderNo`·가격 일관성·식별자 길이·`expiresAt`(선택) | `uk_purchase_payment_order`·`uk_purchase_payment_request` | 계약 위반 영구 |
| `OrderExpired`(#22) | `OrderExpiryListener` → `OrderExpiryMapper.from` → `ApplyOrderExpiry` | `payment-order-expiry` | 봉투·연결값·`expiresAt` 값(재계산 대조 없음) | `uk_payment_order_expiry_event` | 연결값 불일치 영구 |
| `RefundExecutionRequested`(#19) | `RefundExecutionEventListener` → `RefundExecutionMapper.from` → `ReceiveRefundExecution` | `payment-refund-execution`(결제 환불 팩토리) | 봉투·식별자·금액·통화 | `uk_refund_execution_attempt`·`_request`·`_suspension`·`_previous` | 결제 없음·선행 모순 영구, 일시 오류 재시도 |
| `OrderPaymentResultRejected`(목표 행) | (가칭) `OrderPaymentResultRejectionListener` → `OrderPaymentResultRejectedMapper.from` → `RecordUnlinkedCharge` | (신설) `payment-order-result-rejection` | 연결값 5개·`rejectedResultEventId`·`paymentId`·`admissionId`(null 허용)·`rejectionReason` enum | `uk_unlinked_charge_rejection`, `uk_unlinked_charge_source` | 결과 ID 불일치 영구 |
| `SettlementRefundRecorded`(목표 행, 가칭) | (가칭) `SettlementRefundListener` → `SettlementRefundMapper.from` → `RecordSettlementRefundResponse` | (신설) `payment-settlement-refund` | 봉투·`refundId`·`attemptId`·키 | 실행·늦은 취소 기록당 응답 ID 하나 | 대상 없음·이미 다른 응답 영구 |
| `OrderPaid`(#17) | (목표) `OrderPaidListener` → `OrderPaidResponseMapper.from` → `RecordOrderPaymentResponse` | (신설) `payment-order-paid` | 봉투·키=`orderNo`·`paymentResultEventId`·`paymentId` | 결제당 응답 하나(`order_response_event_id` 유일) | 결제 없음·식별자 불일치 = 영구 실패. 같은 응답 재전달 무변화 ([결정 189](../../../docs/decisions.md#payment-order-response)) |

여러 토픽을 받는 리스너는 (토픽, `eventType`) 짝을 대조한다(P2.3-8). Payment 리스너는 지금 토픽 하나씩이다.

## 모듈 안 불변식

모듈을 넘는 것은 플랫폼 I 번호를 링크한다.

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| PI1 | 결제당 진행 중 시도 하나 | DB `uk_payment_approval_active`(V29) | `PaymentApprovalIntegrationTest#ownershipRolesAndChangedOrConcurrentCommandsCannotExposeOrDuplicateTheApproval` |
| PI2 | 결제당 최종 결과 하나, 결과 불변 | `Payment.confirm`, DB `uk_purchase_payment_result`·`chk_purchase_payment_state` | `PaymentTest#confirmedResultIsImmutableAndDuplicateCannotChangeEventOrTimestamp`, `PaymentIntegrationTest#concurrentResolversUseOneSupplierDecisionAndOneResultEvent` |
| PI3 | 확정 송신은 영수증 뒤·`confirm_deadline` 전에만 (I15 일부) | `PaymentApproval.canPost`, DB `chk_payment_approval_window` | `PaymentApprovalTest#postNeedsAdmissionAndTheWindowStartsAtTheFirstPreparationOnly` |
| PI4 | 결과 이벤트는 접수된 현재 시도의 것만 (I15) | 목표: 확정 트랜잭션의 결제 → 시도 잠금·재확인(지금 `AdmittedPortOnePgGateway`가 잠금 밖) | `PaymentApprovalIntegrationTest#aPaidSupplierSnapshotWithoutOrderAdmissionCannotPublishSuccess`(접수 전만). 실패 통지 뒤 경합: 없음 → T1 |
| PI5 | 처음 보류 사유 유지, `PAID_WITHOUT_ADMISSION`만 올림 (I10) | `PaymentApproval.stopPosts`. 목표 DB: 없음(사유 이력 없음, 결정 144 D 기각) | `PaymentApprovalTest#aHeldApprovalKeepsItsFirstReasonWhenALaterStopArrives`·`#anObservedChargeWithoutAdmissionRaisesAHeldReason` |
| PI6 | 결제 `PAID` ⇒ 접수된 열린 시도 없음 (I11) | `ResolvePayment.confirm` → `succeedAdmittedApproval` | `PaymentApprovalIntegrationTest#aHeldAdmittedApprovalClosesInThePaidPaymentCommitSoNoStaleHeldNoticeRemains` |
| PI7 | 시도가 끝나면 보호 본문·일정 없음 | DB `chk_payment_approval_resolution` | `PaymentApprovalTest#aTerminalApprovalKeepsItsOriginalResolutionAndCannotChangeItsOutcome` |
| PI8 | PG 결제 구성 합계 = 청구액 (I1) | DB `ck_payment_provider_amounts`, `PgPaymentAmounts` | `PgPaymentAmountsTest#customerPaymentAndSupplierDiscountMustAddUpToTheOrderTotal` |
| PI9 | 환불 실행 금액 = 원결제액 (I2) | 코드 `Payment.requireRefundRequest`. 목표 DB 복합 FK — 없음 → MC-P1 | `RefundExecutionTest#aFullRefundMustMatchTheLocalConfirmedPayment`(코드만) |
| PI10 | 결제당·환불당 진행 중 실행 하나 | DB `uk_refund_execution_pending_payment`·`_pending_refund` | `RefundReceptionIntegrationTest#databaseConstraintsProtectIdentitiesAndPendingPaymentOrRefundBindings` |
| PI11 | 선행 실패 하나당 후행 하나, 정지 완료 하나당 실행 하나 | DB `uk_refund_execution_previous`·`uk_refund_execution_suspension`·`chk_refund_execution_previous` | 같은 테스트 |
| PI12 | 실행당 금융 확인 하나, 실패와 공존 없음 (I18) | DB `uk_refund_execution_confirmation`, `chk_refund_execution_failure`(`FAILED` ⇒ `refund_confirmed_at IS NULL`), 코드 `confirmRefund`·`confirmFailure` | `RefundCancellationSuccessTest#theFirstSuccessIsImmutableAndAnUnrelatedCancellationCannotReplaceIt`. 금융 확인 뒤 실패 거절: 없음 → PA-T10 |
| PI13 | 실행당 실패 사실 정확히 하나 (I16) | DB `uk_refund_execution_failure`, 목표 `refund_failure_closure.failure_event_id` 유일 | `RefundFailureTest#aVerifiedFailedCancellationAndUnrefundedPurchaseStopTheExecutionOnce`. 운영자 종결: 없음 → T4 |
| PI14 | 실패 종결 중 송신 없음 (I19) | 목표 `RefundCancellationSchedule` 종결 표시, `resumeCancellation` 409 | 없음 → T4(#166 Red→Green) |
| PI15 | 결제당 출처별 연결되지 않은 청구 하나, `ORDER_REJECTED` ⇔ 거절 결과 ID | 목표 DB `uk_unlinked_charge_source`·`uk_unlinked_charge_rejection`·CHECK | 없음 → MC-P13·MC-P14 |
| PI16 | 끝난 결제의 PG 청구는 감시가 찾는다 (I20의 Payment 쪽) | 목표 `payment_charge_watch` + `ResolveChargeWatch` | 없음 → MC-P14(흉내 서버 `READY` 종결 뒤 콘솔 승인) |
| PI17 | 실행당 늦은 취소 확인 하나, `FAILED` 실행에만 | 목표 DB 유일 + 코드 가드 | 없음 → MC-P15 |
| PI18 | 통지 ID 하나에 본문 하나 | DB `payment_pg_webhook` 유일, `JdbcPortOneWebhookReceipts` | `PortOneWebhookIntegrationTest#changedWebhookIdReuseAndMissingPurchaseCannotBeInventedIntoWork` |
| PI19 | 상태 칸 허용 값 | DB CHECK(지금 결제·시도는 조합 CHECK, 실행은 `chk_refund_execution_status`; 목표는 모든 상태 칸 값 CHECK) | `PaymentMigrationIntegrationTest`(목표 이행 테스트) — 없음 → MC-P4·MC-P8·PA-T6 |
| PI20 | PG 시각을 자기 시각과 비교하지 않음 (I13·P4.5-3) | 목표: core 비교 0곳, DB CHECK 0곳 | 없음 → MC-P10·PA-T1·PA-T2(흉내 서버 시계 +5초) |

모듈을 넘는 불변식: I1·I2·I4·I8·I11·I15·I16·I18·I19·I20은 [플랫폼 design "불변식 표"](../platform/design.md#불변식-표-i1--i20)의 확인 SQL이 정본이다.

## 외부 연동과 adapter 메모

### core.port (공급자 중립)

| 포트 | 계약 | adapter(지금) |
|---|---|---|
| `PgPaymentApprovalGateway` | 확정 송신(고정 본문·멱등 키), 조회 → `ApprovalObservation`(core 상태 enum·금액·공급자 시도 ID), 결과 `confirmDeadline` | `PortOnePaymentApprovalGateway`, `AdmittedPortOnePgGateway` |
| `PaymentAttemptLookup` | 실패 시도 이력 → `PgFailedPaymentAttempt`(실패 시각은 값) | `PortOnePaymentAttemptLookup` |
| `RefundCancellationGateway` | 취소 송신 → 접수/성공/형식 거절/잔액 변화/인증·설정 오류/미확정 분류 결과, `cancellationResendDeadline` | `PortOneRefundCancellationGateway` |
| `RefundPaymentLookup` | 전액 취소 확인·실패 확인·미취소 확인(`PgRefundConfirmation`·`PgRefundFailureConfirmation`·미취소 판정) | `PortOneRefundPaymentLookup` |
| (목표) `ChargeWatchLookup`(가칭) | 끝난 결제의 청구 여부 + `watch_until` | 신설 |
| `OrderPaymentAdmissions` | Order 접수·실패 통지. Order 장애는 `OrderUnavailable`(가칭)로 PG 장애와 분리 | `HttpOrderPaymentAdmissions`(지금 `PgUnavailable`, MC-P6) |
| (목표) `ProviderNotificationVerifier`·`ProviderNotificationReceipts` | 서명 검증·통지 접수 | `HmacPortOneWebhookVerifier`·`JdbcPortOneWebhookReceipts` |

### adapter 메모 (공급자 규칙 — core 계약 아님)

공급자 이름과 공급자 값은 이 절에만 둔다(원칙 ②, R22).

- 지금 공급자: PortOne V2 + 토스페이 채널(`tosspay_v2`). 저장된 `provider` 값은 `PORTONE_TEST`이고, 옛 `SIMULATED` 행은 읽기만 한다(결정 104·162). 기준 시뮬레이터는 PortOne 흉내 서버(`tools/verification/support/portone_fake.py`, 결정 141)이며 운영 코드는 주소 설정만 바꿔 붙는다. `simulators/mock-pg`·`SimulatedPgHttpGateway`는 없앤다(결정 162, PA-T15).
- 관측 상태 변환: PortOne `READY` → `READY`, `PAY_PENDING` → `PENDING`, `PAID` → `PAID`, `FAILED` → `FAILED`, `CANCELLED`(전액) → `CANCELLED`, 부분 취소는 근거 오류. 변환은 `PortOnePaymentApprovalGateway`. `PAY_PENDING`이 "확정을 기다림"인지는 G7 확인(`[외부 대기]`).
- 확정 송신 기한: PortOne `Idempotency-Key` 보장 기간 3시간. adapter 설정 키(가칭 `stove.payment.pg.portone.idempotency-window`)로 두고, 첫 준비 시각 + 3시간을 `confirmDeadline`·`cancellationResendDeadline`으로 core에 준다(결정 124·140, MC-P2, (1회차 지적 B7, 판정 전)). core에 `Duration.ofHours(3)`을 두지 않는다.
- 끝난 결제 감시 기한(`watch_until`): PortOne이 READY 결제를 더 이상 승인할 수 없다고 보장하는 시점. 공급자 문서로 확인 전 `[외부 대기]`(결정 154). 간격 설정도 adapter.
- 확정 송신 거절 분류: 형식 갖춘 400·403·일반 409 → `ApprovalPostRejected`(core 사유 `PG_REQUEST_REJECTED`).
- 환불 취소 응답 분류(결정 29 하위·133): 401 → 인증·설정 오류(키 수정 뒤 자동 재개). 403·400 `INVALID_REQUEST`·정상 JSON에 `type`이 있는 400·409·그 밖 4xx → 형식 거절(`HELD`). 409 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN` → 잔액 변화(`BALANCE_BLOCKED`). GET 403/404·POST 404·409 `IDEMPOTENCY_OUTSTANDING_REQUEST`·429·5xx·깨진 본문·타임아웃 → 미확정. 502 `PG_PROVIDER`는 G7 확인. 오류 본문은 분류에만 쓰고 저장하지 않는다(형식 거절은 상태·`type`만 보류 기록에).
- 취소 요청: `Idempotency-Key`는 `attemptId`를 RFC 8941 문자열로 감싼 값, `amount`·`currentCancellableAmount`에 원결제액, `reason`은 `refundId` 기반 상관값.
- 실패 종결 미취소 판정(`PgRefundUncancelled`): 결제 `PAID`·`amount.cancelled == 0`·취소 목록이 모두 `FAILED`일 때만 "미취소 확인됨"을 준다.
- 통지 검증: Standard Webhooks `webhook-id`·`webhook-timestamp`(±5분)·`webhook-signature`(HMAC-SHA256, 회전 중 복수 서명), 원문 64 KiB 제한, 중복 헤더 거절. 종류 `Transaction.*`는 adapter가 "결제 조회 힌트"·"취소 조회 힌트"로 바꾼다. 통지 경로 이름의 `portone`은 중립 경로로 옮긴다(T28·PA-T17, edge·공급자 콘솔 주소 변경 필요).
- 실패 시도 이력: `/payments/{paymentId}/transactions`를 조회해 스토어·채널·주문·금액을 대조. 공급자 원문 사유는 저장하지 않는다.
- PG 시각(`requestedAt`·`cancelledAt`·`failedAt`·`paidAt`)은 값으로만 core에 넘긴다. adapter의 같은 비교(`PortOneRefundCancellationGateway.java:131`)도 없앤다(결정 163).
- PG 비용: 승인 응답에 비용이 있어도 core에 넘기지 않는다(결정 165).

## 설정

| 키(환경 변수) | 기본값 | 바인딩 | 운영/테스트 차이 |
|---|---|---|---|
| `stove.payment.processing-enabled`(`PAYMENT_PROCESSING_ENABLED`) | `true` | `PaymentProcessingProperties` | 없음. 검증 도구가 false→true 재시작에 쓰는 운영 스위치 |
| `stove.payment.poll-interval-ms`·`batch-size`·`retry-delay-ms` | 1000·20·5000 | 같음 | 없음 |
| `stove.payment.review-retry-initial-ms`·`review-retry-max-ms` | 5000·600000(상한 10분 초과 금지, 결정 122) | 같음 | 없음 |
| `stove.payment.refund.lookup/execution/retry.enabled` | `false` | `RefundLookupConfiguration` 등 | 모든 환경 기본 꺼짐(켜기는 배포 설정). 꺼져 있으면 Order 게이지 "실행 준비 미수신"이 드러낸다(T15) |
| (목표) 환불 실패 뒤·기한 뒤 조회 간격 시작값·상한 | 60초·10분 | 신설 | 없음(R19.1) |
| (목표) 청구 감시 간격 | `[외부 대기]` | adapter 설정 | 없음 |
| `stove.payment.pg.provider`(`PAYMENT_PG_PROVIDER`) | 지금 `SIMULATED`(`PaymentConfiguration.java:65,69` `matchIfMissing=true`) | `config` | 목표: 공급자 설정 하나, 개발·시험은 같은 adapter + 흉내 서버 주소(결정 162, PA-T15) |
| `MOCK_PG_URI`·`MOCK_PG_PAYMENT_KEY` | — | — | 목표: 삭제(PA-T15) |
| `PORTONE_API_BASE_URL`·`PORTONE_API_SECRET`·`PORTONE_STORE_ID`·`PORTONE_CHANNEL_KEY`·`PORTONE_WEBHOOK_SECRET`·`PORTONE_WEBHOOK_URL` | 주소만 기본값 | `PortOneProperties` | 흉내 서버는 주소·키 값만 다름 |
| (목표) `stove.payment.pg.portone.idempotency-window` | 3시간 | adapter 설정 | 없음(MC-P2) |
| `PAYMENT_ORDER_SERVICE_KEY`·`ORDER_URI` | 필수 | `PaymentApprovalProperties` | 없음 |
| `PAYMENT_APPROVAL_PAYLOAD_KEY` | 필수(32바이트 Base64, 미완료 암호문이 있는 동안 교체 금지) | 같음 | 없음 |
| (목표) `stove.outbox.republish-owner: payment` | — | 공통 메시징 | 없음(T7) |

운영 계약에 테스트 전용 경로·기본값을 두지 않는다(원칙 ⑦). 장애 주입 클래스는 bootJar에 넣지 않는다.
