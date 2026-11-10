# 설계: Order

> 목표 계약이다. 지금 코드와 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"(MC-O·OR-T 번호)에 있다. 모듈을 넘는 계약(이벤트의 뜻·키·순서, 흐름, 돈 불변식)은 [플랫폼 design](../platform/design.md)이 정본이다. 경로 줄임 `order:` = `apps/order/src/main/java/com/stove/order/`, `order/db/Vn` = `apps/order/src/main/resources/db/migration/Vn__*.sql`.

## 아키텍처 위치

- 포트 8082, 저장소 MySQL 스키마 `stove_order` 하나([P1.1-2](../platform/requirements.md)).
- 패키지: `core/{domain, port, service}` + `api/{controller, listener, scheduler}` + `adapter` + `config`([P1.1](../platform/requirements.md), AGENTS.md 3절).
  - `core.domain`: `Order`·`OrderPaymentAdmission`·`Refund`·`RefundAttempt`·`RefundReviewRound`·`RefundApprovalStop`과 값 객체·리포지토리. 상태 전이와 가드는 여기만 둔다.
  - `core.port`: `CatalogProducts`(Catalog 내부 조회 계약) 하나.
  - `core.service`: 유스케이스 하나에 서비스 하나. 주문 행 잠금(`OrderRepository.lockByOrderNo`)·Inbox(`ProcessedEventGuard`)·상태 변경·Outbox(`OutboxRecorder`)의 트랜잭션을 조율한다.
  - `api.listener`: `*Mapper.from(envelope, payload)`가 봉투·본문·키·(`eventType`, `schemaVersion`)을 검증해 내부 입력으로 바꾼다. 같은 반응을 하는 다른 이벤트(`RefundExecutionFailed`·`RefundFailureClosed`, `OrderPaymentFailed(REVIEW_CLOSED)`·`OrderPaymentReviewClosed` 이행 기간)는 여기서 합친다(원칙 ①).
  - `api.scheduler`: `OrderExpiryScheduler`(만료 스캔). 게이지 수집은 `adapter`의 Micrometer 바인더(`RefundLicenseGrantMetrics`·`OrderExpiryMetrics`·목표 `OrderForwardResponseMetrics`).
  - `adapter`: `CatalogHttpClient`, 지표 바인더.
  - `config`: JWT·권한·서비스 키·Kafka 컨테이너 팩토리·Catalog 클라이언트·설정 바인딩(`RefundConfiguration`·`OrderPaymentLifecycleConfiguration`).
- 동기 의존: Order → Catalog `/internal/v1/order-products/{code}`(`X-Order-Service-Key`, 트랜잭션 밖), Payment → Order `/internal/v1/orders/{orderNo}/payment-admissions`·`…/{admissionId}/failure`(`X-Payment-Service-Key`). Order가 받는 명령형 동기 호출은 이 두 개뿐이다([P1.2-6](../platform/requirements.md), design "동기 의존").
- 비동기 의존: 발행 토픽 `stove.order.v1`, 소비 토픽 `stove.payment.v1`·`stove.license.v1`·(목표) `stove.settlement.v1`. 키는 모두 `orderNo`([P2.4-1](../platform/requirements.md)).
- Gateway는 `/api/v1/orders/**`·`/api/v1/admin/refunds/**`만 라우팅하고 `/internal/**`·`/api/v1/ops/**`는 노출하지 않는다([P1.2-2](../platform/requirements.md)).

## 데이터 모델

시각은 UTC·마이크로초 정밀도(`DATETIME(6)`), 금액은 정수 `BIGINT`, 식별자 열은 대소문자를 구분한다. "목표"는 지금 이행 파일에 없는 것이다(tasks 번호).

### `purchase_order` — 주문

| 열 | 뜻 | 제약 |
|---|---|---|
| `order_no` | 외부 주문번호(UUID) | `uk_purchase_order_no` |
| `buyer_subject`·`request_id` | 구매자·요청 ID | `uk_purchase_order_request(buyer_subject, request_id)` |
| `product_id`·`product_code`·`title`·`content_version` | 스냅샷 | NOT NULL, 생성 뒤 불변(목표 `updatable=false`, OR-T8) |
| `list_amount`·`discount_amount`·`amount`·`currency` | 금액 | `chk_purchase_order_price`(`amount = list_amount − discount_amount > 0`, V10:29), `currency='KRW'` |
| `status` | 주문 상태 | 목표 `chk_purchase_order_status CHECK (status IN ('PENDING','PAYMENT_PROCESSING','PAID','FAILED','EXPIRED'))` (MC-O1) |
| `expires_at` | 결제 기한(생성 시각 + `stove.order.payment-window`) | 목표 NOT NULL, `CHECK (expires_at > created_at)`, 색인 `ix_purchase_order_expiry(status, expires_at)` (T12, OR-T1) |
| `request_event_id` | 최초 `OrderPlaced.eventId` | `uk_purchase_order_event` |
| `payment_id` | Payment 결제 식별자(불투명). 접수 때 연결, 시도 실패로 되돌리면 null, 결과 반영 때 결과 값 | — |
| `payment_admission_id` | 결과를 반영한 영수증 ID | 목표 FK → `order_payment_admission(admission_id)`, `chk_purchase_order_paid_admission`([결정 필요 M-OR-4], T1) |
| `payment_result_event_id`·`result_occurred_at` | 반영한 결과 이벤트 ID·Payment 사실 시각(값으로만) | `uk_purchase_order_result` |
| `payment_result_kind` | 결과 종류 | 목표 `CHECK (payment_result_kind IN ('COMPLETED','DECLINED','REVIEW_CLOSED'))`, `CHECK ((status IN ('PAID','FAILED')) = (payment_result_kind IS NOT NULL))`, `status='PAID' ⇔ kind='COMPLETED'` (OR-T2) |
| `paid_at` | 처음 `PAID`로 바꾼 Order 시계 시각 | 목표 `chk_purchase_order_paid_at CHECK (status<>'PAID' OR paid_at IS NOT NULL)` (MC-O4) |
| `pg_paid_amount`·`pg_discount_amount` | 승인 구성(선택) | `ck_order_provider_amounts`(V27:5) |
| `failure_reason_code`·`failure_reason` | 실패 사유(표시용 값). 결과 종류 판정에 쓰지 않음 | — |
| `created_at`·`updated_at` | Order 시계 | NOT NULL |

### `order_payment_admission` — 승인 진행 영수증

| 열 | 뜻 | 제약 |
|---|---|---|
| `admission_id`·`order_no`·`request_event_id`·`payment_id`·`buyer_subject`·`amount`·`currency`·`admitted_at`·`expires_at` | 접수 값(불변) | `uk_order_payment_admission`, `fk_order_payment_admission_order`, `chk_order_payment_admission`(`admitted_at < expires_at`, V24:23) |
| `status` | 영수증 상태 | 목표 `CHECK (status IN ('OPEN','ATTEMPT_FAILED','RESULT_APPLIED'))`, 주문당 `OPEN` 하나(생성 열 `open_order_no = CASE WHEN status='OPEN' THEN order_no END` + 유일 제약) (MC-O1) |
| `failed_supplier_attempt_id`·`failed_at` | 실패 통지 근거(Payment·결제 공급자 값, 비교하지 않음) | 목표 `CHECK ((status='ATTEMPT_FAILED') = (failed_supplier_attempt_id IS NOT NULL AND failed_at IS NOT NULL))` |
| `closed_at`·`closed_order_status` | 닫은 Order 시각·그때 주문이 된 상태(값) | 목표 `ATTEMPT_FAILED`면 `closed_order_status IN ('PENDING','EXPIRED')`, `RESULT_APPLIED`면 `IN ('PAID','FAILED')`, `OPEN`이면 둘 다 NULL. 지금 `chk_admission_failure_close`(V25:6)는 null 조합으로 열림/닫힘을 만든다 |

### 주문 쪽 결과 기록

| 표 | 뜻 | 제약 |
|---|---|---|
| `order_payment_result_rejection`(목표, OR-T3) | 반영하지 않은 성공 결과 하나(결정 153) | `rejected_result_event_id` 유일, `order_no` FK, `payment_id`, `admission_id`(null 가능), `rejection_reason CHECK IN ('NO_OPEN_ADMISSION','ADMISSION_MISMATCH','ORDER_CLOSED')`, `rejected_at`(Order 시계), `rejection_event_id`(낸 `OrderPaymentResultRejected.eventId`) 유일. 주문당 여러 행 가능 |
| `order_expired_review_closure`(V31) | `EXPIRED` 주문에 온 운영자 종결 하나 | `uq_…_order`·`uq_…_event`, FK. 목표: `reason_code='REVIEW_CLOSED'` CHECK 대신 `result_kind='REVIEW_CLOSED'` 열을 보고 `reason_code`는 값으로만(결정 174, OR-T2) |
| `order_forward_response`(목표, OR-T11) | 응답 대기의 응답 사실(결정 158) | `(order_no, response_type)` 유일, `event_id` 유일, `response_type CHECK IN ('SETTLEMENT_SALE_RECORDED','LICENSE_REFUND_APPLIED')`, `received_at`(Order 시계) |
| `order_license_grant`(V13) | 구매 근거 생성 사실(지급 근거) | `uk_order_license_grant_event`·`_license`·`_order`, FK |

결과 `eventId`는 `purchase_order.payment_result_event_id`·`order_payment_result_rejection.rejected_result_event_id`·`order_expired_review_closure.event_id` 세 곳 가운데 한 곳·한 주문에만 쓰인다. 표마다 유일 제약이 있고, 표를 넘는 유일성은 `ApplyPaymentResult`가 주문 잠금 아래 세 표를 교차 조회해 지킨다(불변식 OI7).

### `order_refund` — 환불

| 열 | 뜻 | 제약 |
|---|---|---|
| `refund_id`·`order_no` | 식별자 | `uk_order_refund_id`, `uk_order_refund_order`(주문당 하나), FK |
| `received_at`·`automatic_refund_eligible` | 첫 접수 Order 시각·자동 자격(접수 때 한 번) | NOT NULL, `updatable=false` |
| `status` | 환불 상태 | 목표 `CHECK (status IN ('PENDING_REVIEW','PENDING_LICENSE_GRANT','PENDING_LICENSE_SUSPENSION','PENDING_APPROVAL_STOP','PENDING_PAYMENT_REFUND','PENDING_LICENSE_RESTORATION','FAILED','REJECTED','COMPLETED','LATE_CANCELLATION_COMPLETED'))` (MC-O1·MC-O3) |
| `current_review_id`·`current_attempt_id`·`suspension_request_event_id` | 현재 회차·시도·정지 요청 | 유일, `chk_order_refund_current_review`(V21) |
| `payment_confirmation_event_id`·`payment_confirmation_fingerprint`·`completed_at` | 최초 금융 확인(Payment 시각은 값으로만) | `uk_order_refund_payment_confirmation`, `chk_order_refund_payment_confirmation`(V17:14) |
| `late_cancellation_event_id`·`late_cancellation_confirmation_id` | 늦은 취소 확인 식별(목표) | 유일, `CHECK ((status='LATE_CANCELLATION_COMPLETED') = (late_cancellation_event_id IS NOT NULL))` (MC-O3) |
| `purchase_refunded_at` | `PurchaseRefunded`를 쓴 Order 시각(응답 대기 시작, 목표) | `CHECK (status NOT IN ('COMPLETED','LATE_CANCELLATION_COMPLETED') OR purchase_refunded_at IS NOT NULL)` (OR-T11) |
| `payment_confirmation_source` | 옛 `source` 값 | 목표 새 행에 쓰지 않음, 옛 행 읽기만(T19) |
| `purchase_returned_to_storage` | 마지막 복구의 보관함 반환 여부 | NOT NULL |
| `license_grant_wait_started_at`·`license_suspension_wait_started_at`·`license_restoration_wait_started_at`·`approval_stop_wait_started_at` | 상태별 대기 진입 Order 시각 | V32·V34 CHECK(그 상태면 NOT NULL), `(status, 시각)` 색인 |
| `review_request_id`·`review_decision`·`review_operator_subject`·`review_reason`·`reviewed_at` | 1회차 판정 사본(V20) | 목표: 새 행에 쓰지 않고 지움, 정본은 `order_refund_review_round`(R11.4, OR-T9). `chk_order_refund_review`(V22:58)도 회차 표 기준으로 옮김 |
| 금액 사본(`RefundAmountProjection`, V27) | 확인된 반환 구성 | nullable, 0으로 채우지 않음 |

### `order_refund_attempt` — 환불 시도

| 열 | 뜻 | 제약 |
|---|---|---|
| `attempt_id`·`refund_id`·`attempt_no`·`previous_attempt_id`·`started_at` | 식별·순번·이전 시도·Order 시각 | `uk_order_refund_attempt_id`, `uk_order_refund_attempt_no(refund_id, attempt_no)`, `previous_attempt_id` 유일, FK |
| `status` | 시도 상태 | 목표 `CHECK (status IN ('PENDING','COMPLETED','FAILED','LATE_CANCELLED'))` (MC-O2, 결정 164) |
| `suspension_request_event_id`·`suspension_event_id`·`suspension_occurred_at`·`license_id` | 이 시도의 정지 근거 | 유일 |
| `execution_request_event_id` | `RefundExecutionRequested.eventId` | 유일 |
| `preparation_event_id`·`first_refund_prepared_at`·`confirmation_delay_at`·`preparation_fingerprint` | 준비 사실(받은 값 그대로) | `uk_order_attempt_preparation` |
| `resolved_at` | 끝난 사실의 시각(Payment 값) | — |
| `failure_source` | 실패 출처 | 목표 `CHECK (failure_source IN ('PG_CANCELLATION_FAILED','OPERATOR_FAILURE_CLOSED'))`, `CHECK ((status IN ('FAILED','LATE_CANCELLED')) = (failure_source IS NOT NULL))`, `CHECK ((failure_source='OPERATOR_FAILURE_CLOSED') = (failure_closure_id IS NOT NULL))` (I16, T4) |
| `failure_event_id`·`failure_fingerprint`·`failure_closure_id` | 실패 사실 식별 | `failure_event_id` 유일 |
| `restoration_request_event_id`·`restoration_requested_at`·`restored_event_id`·`restored_at`·`restoration_fingerprint` | 복구 요청(Order 시각)·복구 완료(License 값) | 유일 |
| `retry_requested_at`·`retry_suspension_request_event_id` | 이 시도 뒤 재신청 근거 | 유일 |
| `failure_reason_code`·`failure_reason` | 옛 실패 사유 | 목표 새 행에 쓰지 않음. 고객 `failureNotice.code`는 `failure_source`에서 옮김(결정 134) |
| `reconciliation_required`·`success_event_id`·`success_confirmation_event_id`·`successful_cancellation_id`·`success_fingerprint` | 취소 귀속 기록 | 목표 새 행에 쓰지 않고 지움(결정 164, MC-O2) |

### `order_refund_review_round` — 심사 회차(승인 중단 포함)

| 열 | 뜻 | 제약 |
|---|---|---|
| `review_id`·`refund_id`·`review_no`·`previous_review_id`·`application_request_id`·`correction_request_id` | 회차 식별·출처 | `uk_refund_review_id`·`_no`·`_previous`·`_application`·`uk_review_correction_request` |
| `decision` | 회차 판정 상태 | 목표 NOT NULL `CHECK (decision IN ('PENDING','APPROVED','REJECTED'))`(지금 null = 미판정) (OR-T10) |
| `decision_request_id`·판정자·사유·`decided_at` | 판정 영수증 | `uk_refund_review_decision`, `chk_refund_review_decision` |
| `stop_stage` | 승인 중단 단계 | 목표 `CHECK (stop_stage IS NULL OR stop_stage IN ('RECEIVED','SUSPENSION_OBSERVED','REOPENED'))`, 단계마다 필요한 `stop_*` 열 NOT NULL CHECK. 지금은 `chk_review_stop_*`(V22:32-51)의 null 조합 (OR-T10) |
| `stop_*` 열(V22) | 중단 명령·정지 관찰·복구 요청·복구 완료·다음 회차 | V22 유일 제약 7개 |

### 공통

- Inbox `processed_event`(`uk_processed_event(event_id, consumer_group)`), Outbox `outbox_event`(aggregateType `Order`, aggregateId·키 `orderNo`), 재발행 기록 `outbox_republish`(V33), 목표 실패 보관 표(결정 160, 공통 메시징).

### 이행

- 지금까지 V1~V34. V10부터가 현행 자료이고 구 V1~V9 자료·구 Outbox는 자동 이관하지 않는다(전환 SQL `tools/migrations/order/order-transition.sql`).
- 목표 이행(번호는 그 PR에서 정한다. 참고 구현 `esd-worktrees/refund-failure-closure`가 V35를 쓰므로 겹치지 않게 맞춘다). 모두 결정 104 옛 행 호환 테스트를 `OrderMigrationIntegrationTest`에 같은 PR로 쓴다. 채움은 Order가 기록한 사실로만 하고 다른 모듈 시각으로 채우지 않는다(R11.3).

| 대상 | 채움 규칙 | 근거 |
|---|---|---|
| `purchase_order.status` CHECK | 채움 없음. 추가 전 허용 밖 값 0행 확인 | MC-O1 |
| `purchase_order.expires_at` | `created_at + INTERVAL 30 MINUTE`(V10 이후 기한 규칙 그대로) | T12 |
| `purchase_order.paid_at` | `status='PAID'` 행은 `updated_at`(`Order.apply`가 `PAID` 때 쓴 Order 시각. `PAID` 뒤 `purchase_order`를 바꾸는 코드 경로 없음 — 이행 PR에서 다시 확인). 그 밖은 NULL | 결정 173, MC-O4 |
| `purchase_order.payment_result_kind` | `PAID` → `COMPLETED`, `FAILED`이고 `failure_reason_code='REVIEW_CLOSED'` → `REVIEW_CLOSED`, 그 밖 `FAILED` → `DECLINED` | 결정 174 |
| `purchase_order.payment_admission_id` | `PAID`·`FAILED` 행: 같은 주문의 가장 최근 영수증이 `closed_at IS NULL`이고 `payment_id`가 같으면 그 `admission_id`. 영수증 없는 옛 결과 행은 [결정 필요 M-OR-4] | T1 |
| `order_payment_admission.status` | `closed_at IS NOT NULL` → `ATTEMPT_FAILED`; 주문 `PAYMENT_PROCESSING`인 최신 열린 행 → `OPEN`; 주문 `PAID`·`FAILED`인 최신 열린 행 → `RESULT_APPLIED`(`closed_at`=주문 `updated_at`, `closed_order_status`=주문 상태). 그 밖의 조합은 이행 전 확인 쿼리로 0행을 확인하고, 있으면 이행을 멈추고 조사 | MC-O1 |
| `order_refund.status` CHECK | 채움 없음(`LATE_CANCELLATION_COMPLETED`는 새 값) | MC-O1·O3 |
| `order_refund.purchase_refunded_at` | `COMPLETED` 행: 그 환불의 `PurchaseRefunded` Outbox 행 `created_at`(V30 채움 행 포함), 없으면 금융 확인 이벤트의 Inbox `processed_event` 처리 시각, 없으면 `received_at`(실제보다 늦지 않음 — V32와 같은 원칙) | OR-T11 |
| `order_refund_attempt.status` | `SUCCEEDED` → `COMPLETED`; `PENDING`이고 환불 `COMPLETED`의 현재 시도(귀속 미확정 표시 `reconciliation_required=true`) → `COMPLETED`; 그 밖 그대로 | 결정 164, MC-O2 |
| `order_refund_attempt.failure_source` | `FAILED`이고 `failure_reason_code='PG_CANCELLATION_FAILED'` → `PG_CANCELLATION_FAILED`. 참고 구현의 운영자 종결 행(`failure_closure_id` 있음) → `OPERATOR_FAILURE_CLOSED` | T4 |
| `order_refund_review_round.decision` | NULL → `PENDING` | OR-T10 |
| `order_refund_review_round.stop_stage` | 다음 회차 연결(`stop_next_review_id`) 있음 → `REOPENED`; 정지 관찰(`stop_suspension_event_id`) 있음 → `SUSPENSION_OBSERVED`; 중단 명령(`stop_request_id`) 있음 → `RECEIVED`; 없음 → NULL | OR-T10 |
| 칸 삭제(`reconciliation_required`·`success_*`·`order_refund.review_*`·`payment_confirmation_source`) | 새 코드가 쓰지 않는 판을 먼저 내고, 옛 판이 모두 내려간 뒤 다음 이행에서 지운다 | 결정 164, R11.4 |

- V32·V34 적용 절차(기존, 유지): V32·V34는 열 추가 → 대기 행 채움 → CHECK·색인 추가를 차례로 실행하고 MySQL DDL은 문장마다 커밋된다. 그래서 모든 Order 인스턴스를 멈춰 진행 중 트랜잭션이 없게 한 뒤 새 버전을 기동해 Flyway가 실행하게 한다. 옛 버전과 함께 돌리면 채움 뒤 옛 버전이 만든 대기 행의 시각이 NULL이라 CHECK 추가가 실패하거나, CHECK 뒤 옛 버전의 쓰기가 제약 위반으로 실패한다. 부분 실패 복구: `flyway_schema_history`에서 실패 행과 이미 적용된 변경을 확인하고, 적용된 것만 역순으로 되돌린 뒤(V32: `ALTER TABLE order_refund DROP INDEX ix_order_refund_license_grant_wait;`, `… DROP CHECK chk_order_refund_license_grant_wait;`, `… DROP COLUMN license_grant_wait_started_at;` / V34: `DROP INDEX ix_order_refund_approval_stop_wait, ix_order_refund_license_restoration_wait, ix_order_refund_license_suspension_wait`, `DROP CHECK chk_order_refund_approval_stop_wait, chk_order_refund_license_restoration_wait, chk_order_refund_license_suspension_wait`, `DROP COLUMN approval_stop_wait_started_at, license_restoration_wait_started_at, license_suspension_wait_started_at`, 없는 항목은 뺀다) 실패 행을 지우고(Flyway `repair`와 같은 동작) 다시 기동한다. V32 채움: 자동 허용은 접수 시각, 심사 환불은 현재 회차 승인 시각, 판정 시각이 없으면 접수 시각. V34 채움: 정지는 재시도면 현재 시도 `retry_requested_at`, 첫 정지면 정지 요청 Outbox 행 `created_at`, 없으면 `license_grant_wait_started_at`, 없으면 접수 시각; 복구는 현재 시도 `restoration_requested_at`; 승인 중단은 현재 회차 `stop_received_at`, 없으면 접수 시각. 목표 CHECK 추가 이행도 같은 절차를 따른다.

## 상태 모델

표 기호: `→ X` 허용·다음 상태 X / `=` 무변화(멱등, 같은 영수증·같은 지문 재전달 포함) / `409` HTTP 거절(상태 불변) / `404` 없음 / `✕` 영구 실패(트랜잭션 롤백, 재시도 없이 실패 보관·경보, [P2.6-3](../platform/requirements.md)) / `M-OR-n` [결정 필요]. 모든 쓰기는 주문 행 잠금(`OrderRepository.lockByOrderNo`) 아래에서 판정한다. 이벤트 트리거는 Inbox 최초 전달이고, 이미 처리한 `eventId`는 Inbox가 거른다(표의 `=`와 같음). 시각 비교는 Order 시계끼리만 한다(원칙 ⑥).

### 주문 `Order.status`(`purchase_order`)

| 값 | 뜻 |
|---|---|
| `PENDING` | 생성됨. 열린 영수증 없음(처음이거나 시도 실패로 되돌아옴) |
| `PAYMENT_PROCESSING` | 기한 안에 연 `OPEN` 영수증 하나가 있음, 결과 대기 |
| `PAID` | `COMPLETED` 결과 반영(끝) |
| `FAILED` | `DECLINED`·`REVIEW_CLOSED` 결과 반영(끝) |
| `EXPIRED` | 열린 영수증 없이 기한 도달(끝) |

트리거: O1 `POST /api/v1/orders`(다른 `requestId`) · O2 같은 `requestId` · O3 승인 접수(새 `admissionId`) · O4 승인 접수(같은 `admissionId`·같은 입력) · O5 시도 실패 통지 · O6 만료 스캔 · O7 `OrderPaymentCompleted`(`admissionId`·`paymentId` = 열린 최신 영수증) · O8 그 밖의 `OrderPaymentCompleted`(연결값 일치) · O9 `OrderPaymentFailed` · O10 `OrderPaymentReviewClosed` · O11 이미 기록한 결과(반영·거절·종결)와 같은 재전달 · O12 연결값 불일치 결과·다른 주문이 쓴 결과 `eventId` · O13 환불 명령(접수·재시도 등, 주문은 읽기만) · O14 구매 근거 생성 사실(`PurchaseRecordStored`/`LicenseGranted`).

| 상태 \ 트리거 | O1 | O2 | O3 | O4 | O5 | O6 | O7 | O8 | O9 | O10 | O11 | O12 | O13 | O14 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| (없음) | → `PENDING` + `OrderPlaced` ① | O1로 처리 | 404 | 404 | 404 | = | ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | 404 | ✕ |
| `PENDING` | = (별도 주문) | = (기존 반환, 다른 입력 409) | → `PAYMENT_PROCESSING` ② / → `EXPIRED` ③ | = (최초 영수증) ④ | = (닫힌 같은 통지) / 409 | → `EXPIRED` + `OrderExpired` ⑤ / = | 해당 없음(열린 영수증 없음 → O8) | = + 거절 `NO_OPEN_ADMISSION` ⑥ | → `FAILED`(`DECLINED`) ⑦ / ✕ | → `FAILED`(`REVIEW_CLOSED`) ⑦ / ✕ | = | ✕ | 409 | ✕ |
| `PAYMENT_PROCESSING` | = | = | 409 | = (최초 영수증) | → `PENDING` / → `EXPIRED` + `OrderExpired` ⑧ / 409 | = (R3.6) | → `PAID` + `OrderPaid` ⑨ | = + 거절 `ADMISSION_MISMATCH` ⑥ | → `FAILED`(`DECLINED`) ⑩ / ✕ | → `FAILED`(`REVIEW_CLOSED`) ⑩ / ✕ | = | ✕ | 409 | ✕ |
| `PAID` | = | = | 409 | = (최초 영수증) | = (닫힌 같은 통지) / 409 | = | 해당 없음(영수증 `RESULT_APPLIED` → O8 또는 O11) | = + 거절 `ORDER_CLOSED` ⑥ | ✕ | ✕ | = | ✕ | 허용(주문 불변) | = (지급 근거 저장) ⑪ |
| `FAILED` | = | = | 409 | = (최초 영수증) | = (닫힌 같은 통지) / 409 | = | 해당 없음(→ O8) | = + 거절 `ORDER_CLOSED` ⑥ | ✕ | ✕ | = | ✕ | 409 | ✕ |
| `EXPIRED` | = | = | 409 `ORDER_EXPIRED`(새 `OrderExpired` 없음) | = (최초 영수증) | = (닫힌 같은 통지) / 409 | = | 해당 없음(→ O8) | = + 거절 `ORDER_CLOSED` ⑥ | M-OR-2 | = + 종결 기록 ⑫ / ✕ | = | ✕ | 409 | ✕ |

① 공개·판매·가격·기대 값 검증(R1). ② Order 시계 < `expires_at`, 연결값 일치, 판매 제한 아님 → `OPEN` 영수증. 판매 제한이면 409 `PRODUCT_SALE_RESTRICTED`, 연결값 불일치 409(상태 불변). ③ Order 시계 ≥ `expires_at` → `EXPIRED` + `OrderExpired` 커밋 뒤 409 `ORDER_EXPIRED`. ④ 같은 `admissionId`·다른 내용은 409. ⑤ `expires_at <= now`면 전이, 아니면 `=`. ⑥ 결과 거절 기록 + `OrderPaymentResultRejected` Outbox + Inbox(R4.9, 결정 153). ⑦ `admissionId`가 null일 때만 허용, 값이 있으면 ✕(닫힌 영수증의 결과). ⑧ 열린 최신 영수증·결과 없음일 때 Order 시계 < `expires_at` → `PENDING`, ≥ → `EXPIRED`. 최신 아님·내용 다름 409. `failedAt`은 비교하지 않음. ⑨ 영수증 → `RESULT_APPLIED`, `payment_admission_id`·`paid_at`·결과 식별 저장. ⑩ `admissionId`·`paymentId` = 열린 최신 영수증일 때 허용(영수증 `RESULT_APPLIED`), 다르면 ✕. ⑪ 주문 연결·`paymentId`·상품 일치, 주문의 첫 근거, `eventId`·`licenseId`를 다른 주문이 쓰지 않음. 기다리던 환불이 있으면 환불 표 E1. ⑫ 첫 종결이면 종결 기록 + Inbox, 같은 종결 재전달 `=`, 같은 주문의 다른 종결 ✕(R4.8).

### 승인 진행 영수증 `OrderPaymentAdmission.status`(`order_payment_admission`)

| 값 | 뜻 |
|---|---|
| `OPEN` | 기한 안에 연 승인 진행. 주문 `PAYMENT_PROCESSING`과 1:1 |
| `ATTEMPT_FAILED` | 검증된 시도 실패 통지로 닫힘(그때 주문이 `PENDING`/`EXPIRED`가 됨) |
| `RESULT_APPLIED` | 이 영수증의 결과가 주문에 반영됨(주문 `PAID`/`FAILED`) |

트리거: A1 승인 접수 성공 · A2 같은 `admissionId` 재전송 · A3 이 영수증의 시도 실패 통지 · A4 이 영수증 `admissionId`의 결과(O7·O9·O10) · A5 다른 `admissionId`의 결과·거절 · A6 만료 스캔.

| 상태 \ 트리거 | A1 | A2 | A3 | A4 | A5 | A6 |
|---|---|---|---|---|---|---|
| (없음) | → `OPEN`(주문 표 ②) | A1로 처리 | 404 | 주문 표 O8(거절 `ADMISSION_MISMATCH`)·O9/O10 ✕ | = | = |
| `OPEN` | 해당 없음(주문 `PAYMENT_PROCESSING`이라 새 접수 409) | = (최초 영수증, 다른 내용 409) | → `ATTEMPT_FAILED`(주문 표 ⑧) | → `RESULT_APPLIED` | = (주문은 거절 기록) | = |
| `ATTEMPT_FAILED` | 해당 없음(다른 영수증의 일) | = (최초 영수증) | = (같은 통지) / 409(다른 근거) | 성공: = + 주문 거절 `NO_OPEN_ADMISSION` / 실패·종결: ✕ | = | = |
| `RESULT_APPLIED` | 해당 없음 | = (최초 영수증) | 409 | 같은 결과 = / 다른 결과: 주문 표 O8·O9·O10 | = | = |

### 환불 `Refund.status`(`order_refund`)

| 값 | 뜻 | 고객 상태 | HTTP(조회·접수) |
|---|---|---|---|
| `PENDING_REVIEW` | 기한 밖 접수·재심사·정정 회차의 관리자 판정 대기. 보유권 유지 | `PENDING_REVIEW` | 202 |
| `PENDING_LICENSE_GRANT` | 허용됨, 구매 근거 생성 사실 대기 | `PROCESSING` | 202 |
| `PENDING_LICENSE_SUSPENSION` | 정지 요청을 냄(첫 정지 또는 재신청), `LicenseSuspended` 대기 | `PROCESSING` | 202 |
| `PENDING_APPROVAL_STOP` | 정지 요청 뒤 승인 중단, 정지 결과·정정 복구 대기 | `PROCESSING` | 202 |
| `PENDING_PAYMENT_REFUND` | 현재 시도의 `RefundExecutionRequested` 저장, 금융 결과 대기 | `PROCESSING` | 202 |
| `PENDING_LICENSE_RESTORATION` | 현재 시도 실패 확정, `LicenseRestored` 대기 | `PROCESSING` | 202 |
| `FAILED` | 실패 확정 + 실제 복구 완료. 고객 재신청 가능 | `FAILED` + `failureNotice` | 200 |
| `REJECTED` | 관리자 반려. 7일 안 재심사 가능 | `REJECTED` + `rejectionReason` | 200 |
| `COMPLETED` | 금융 완료(끝) | `COMPLETED` | 200 |
| `LATE_CANCELLATION_COMPLETED` | 실패 확정 뒤 늦은 전액 취소를 반영(끝, 결정 157) | `COMPLETED` + `completionNotice`(code `LATE_CANCELLATION_REFUNDED`, 가칭) | 200 |

트리거(HTTP·운영자): H1 접수 `POST /api/v1/orders/{orderNo}/refund` · H2 1회차 판정 `POST /api/v1/admin/refunds/{refundId}/decision` · H3 재심사 신청 `POST …/refund/reconsiderations` · H4 회차 판정 `POST /api/v1/admin/refunds/{refundId}/reconsiderations/{reviewId}/decision` · H5 승인 중단 `POST /api/v1/admin/refunds/{refundId}/approval-stops` · H6 재신청 `POST …/refund/retries` · S1 지연 게이지 수집·고객 조회(모든 상태 `=`) · R1 Outbox 운영 재발행(모든 상태 `=`, 효과는 받는 쪽 Inbox).

| 상태 \ 트리거 | H1 | H2 | H3 | H4 | H5 | H6 |
|---|---|---|---|---|---|---|
| (없음) | 주문 `PAID`·본인 → 자동 자격·근거 없음 `→ PENDING_LICENSE_GRANT` / 자동 자격·근거 있음 `→ PENDING_LICENSE_SUSPENSION` + `LicenseSuspensionRequested` / 기한 밖 `→ PENDING_REVIEW` + 1회차 회차. 주문 `PAID` 아님 409, 타인 404 | 404 | 404 | 404 | 404 | 404 |
| `PENDING_REVIEW` | = (기존 반환) | 현재 회차가 1회차·미판정 → 승인 `→ PENDING_LICENSE_GRANT`/`→ PENDING_LICENSE_SUSPENSION`(근거 있음) + `LicenseSuspensionRequested`, 반려 `→ REJECTED`. 같은 명령 =, 그 밖 409 | = (같은 `requestId`) / 409 | 현재 회차(2회차 이상)·미판정 → H2와 같은 전이. 같은 명령 =, 그 밖 409 | = (같은 중단 명령) / 409 | 409 |
| `PENDING_LICENSE_GRANT` | = | = (같은 명령) / 409 | = / 409 | = / 409 | 자동 자격 아님·현재 회차 `APPROVED`·중단 없음 → `→ PENDING_REVIEW`(정정 회차, 중단 단계 `RECEIVED`→`REOPENED` 같은 트랜잭션) / 같은 명령 = / 그 밖 409 | 409 |
| `PENDING_LICENSE_SUSPENSION` | = | = / 409 | = / 409 | = / 409 | 시도 없음(첫 정지)·위 조건 → `→ PENDING_APPROVAL_STOP` / 시도 있음(재신청) 409 / 같은 명령 = | = (같은 `previousAttemptId`) / 409 |
| `PENDING_APPROVAL_STOP` | = | = / 409(복구 전 판정 없음) | = / 409 | = / 409 | = / 409 | 409 |
| `PENDING_PAYMENT_REFUND` | = | = / 409 | = / 409 | = / 409 | = / 409(실행 요청 저장 뒤) | = / 409 |
| `PENDING_LICENSE_RESTORATION` | = | = / 409 | = / 409 | = / 409 | = / 409 | = / 409(복구 전) |
| `FAILED` | = (200) | = / 409 | = / 409 | = / 409 | = / 409 | 현재 시도 실패·복구 완료·첫 재신청 → `→ PENDING_LICENSE_SUSPENSION` + `LicenseSuspensionRequested` / 같은 `previousAttemptId` = / 그 밖 409 |
| `REJECTED` | = (200) | = / 409 | 현재 반려 회차·반려 + 7일 이내·사유 유효·첫 신청 → `→ PENDING_REVIEW`(새 회차) / 같은 `requestId` = / 그 밖 409 | = / 409 | = / 409 | 409 |
| `COMPLETED` | = (200) | = / 409 | = / 409 | = / 409 | = / 409 | = / 409 |
| `LATE_CANCELLATION_COMPLETED` | = (200) | = / 409 | = / 409 | = / 409 | = / 409 | = / 409 |

트리거(이벤트): E1 구매 근거 생성 사실 · E2 `LicenseSuspended` · E3 `LicenseApprovalCorrected` · E4 `RefundExecutionPrepared` · E5 `RefundPaymentConfirmed` · E6 실패 확정(`RefundExecutionFailed`·`RefundFailureClosed`) · E7 `LicenseRestored` · E8 `RefundLateCancellationConfirmed` · E9 `LicenseRefundApplied`(목표) · E10 `RefundExecutionSucceeded`(옛 이벤트). 칸의 "재전달"은 이미 기록한 근거와 식별자·지문이 같은 것, "그 밖"은 식별자가 이 환불의 현재 요청·시도와 다르거나 지문이 다른 것이다.

| 상태 \ 트리거 | E1 | E2 | E3 | E4 | E5 | E6 | E7 | E8 | E9 | E10 |
|---|---|---|---|---|---|---|---|---|---|---|
| (없음) | = (지급 근거만 저장, 주문 표 ⑪) | ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | = (소비 안 함) |
| `PENDING_REVIEW` | = (근거 저장) | 재전달 = / ✕ | 재전달 = / ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | = |
| `PENDING_LICENSE_GRANT` | 첫 근거·연결 일치 → `PENDING_LICENSE_SUSPENSION` + `LicenseSuspensionRequested` / ✕ | 재전달 = / ✕ | 재전달 = / ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | = |
| `PENDING_LICENSE_SUSPENSION` | 재전달 = / ✕ | 현재 정지 요청과 일치: 첫 정지 → `PENDING_PAYMENT_REFUND` + 시도 1 + `RefundExecutionRequested`, 재신청 → `PENDING_PAYMENT_REFUND` + 시도 n+1(`previousAttemptId`) + `RefundExecutionRequested` / 재전달 = / ✕ | 재전달 = / ✕ | 이전 시도 재전달 = / ✕ | ✕ | 이전 시도 재전달 = / ✕ | 이전 시도 재전달 = / ✕ | 재신청 접수됨(지정 = 현재 실패 시도): M-OR-1 / 첫 정지: ✕ | ✕ | = |
| `PENDING_APPROVAL_STOP` | 재전달 = / ✕ | 중단된 정지 요청과 일치·첫 → = (중단 단계 `SUSPENSION_OBSERVED` + `LicenseApprovalCorrectionRequested`) / 재전달 = / ✕ | 중단·복구 요청·정지·`licenseId` 일치 → `PENDING_REVIEW`(정정 회차, 단계 `REOPENED`, `suspension_request_event_id` 비움) / ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | = |
| `PENDING_PAYMENT_REFUND` | 재전달 = / ✕ | 현재 시도 정지 재전달 = / ✕ | 재전달 = / ✕ | 현재 시도·식별자 일치·첫 → = (시도에 준비 기록) / 재전달 = / ✕ | 현재 시도 `PENDING`·식별자·금액 일치 → `COMPLETED` + 시도 `COMPLETED` + `PurchaseRefunded` / ✕ | 현재 시도 `PENDING` 일치 → `PENDING_LICENSE_RESTORATION` + 시도 `FAILED` + `LicenseRestorationRequested` / 이전 시도 재전달 = / ✕ | 이전 시도 재전달 = / ✕ | 지정 = 이전 실패 시도(새 시도 진행 중): M-OR-1 / ✕ | ✕ | = |
| `PENDING_LICENSE_RESTORATION` | 재전달 = / ✕ | 재전달 = / ✕ | 재전달 = / ✕ | 현재 시도 일치 → = (늦게 온 준비 기록) / 재전달 = / ✕ | ✕ (I18) | 같은 실패 재전달 = / ✕ | 현재 시도·복구 요청·실패·정지·`licenseId` 일치 → `FAILED`(+ `purchase_returned_to_storage`) / ✕ | 지정 = 현재 시도 → `LATE_CANCELLATION_COMPLETED` + 시도 `LATE_CANCELLED` + `PurchaseRefunded` / 지정 ≠ 현재(재신청 시도가 다시 실패): M-OR-1 / ✕ | ✕ | = |
| `FAILED` | 재전달 = / ✕ | 재전달 = / ✕ | 재전달 = / ✕ | 현재 시도 일치 → = / 재전달 = / ✕ | ✕ (I18) | 재전달 = / ✕ | 재전달 = / ✕ | 지정 = 현재 시도 → `LATE_CANCELLATION_COMPLETED` + 시도 `LATE_CANCELLED` + `PurchaseRefunded` / 지정 ≠ 현재: M-OR-1 / ✕ | ✕ | = |
| `REJECTED` | = (근거 저장) | 재전달 = / ✕ | 재전달 = / ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | = |
| `COMPLETED` | 재전달 = / ✕ | 재전달 = / ✕ | 재전달 = / ✕ | 현재 시도 일치 → = / 재전달 = / ✕ | 같은 지문 = / ✕ | 이전 시도 재전달 = / ✕ | 이전 시도 재전달 = / ✕ | ✕ | 환불·구매 연결 일치 → = (응답 기록, R10.3) / ✕ | = |
| `LATE_CANCELLATION_COMPLETED` | 재전달 = / ✕ | 재전달 = / ✕ | 재전달 = / ✕ | 재전달 = / ✕ | ✕ | 재전달 = / ✕ | 현재 시도 복구 요청과 일치(복구 대기 중 늦은 취소가 먼저 반영된 경우) → = (복구 근거·보관함 반환 기록) / 재전달 = / ✕ | 같은 확인 재전달 = / ✕ | 연결 일치 → = (응답 기록) / ✕ | = |

| 직교 칸 | 동작 |
|---|---|
| `automatic_refund_eligible` | 접수 때 `received_at ≤ paid_at + 4h`(Order 시계끼리)로 한 번 정하고 바꾸지 않는다. true면 H2·H4·H5를 409로 거절 |
| `current_review_id`·`current_attempt_id`·`suspension_request_event_id` | 이벤트 대조의 기준. 과거 회차·시도·정지와 맞는 사실은 새 것을 바꾸지 않는다 |
| 대기 진입 시각 4개 | 그 상태에 들어갈 때 Order 시각으로 쓰고, 다시 들어가면 바꾸며, 벗어나도 남긴다. 게이지·안내는 상태를 바꾸지 않는다(결정 125) |
| `purchase_returned_to_storage` | `LicenseRestored`·`LicenseApprovalCorrected`의 `returnedToStorage`. 나중 값이 덮는다(결정 98) |
| 금액 사본 | E5 때 병합. 상태·완료 시각을 바꾸지 않는다(R9) |

### 환불 시도 `RefundAttempt.status`(`order_refund_attempt`)

| 값 | 뜻 |
|---|---|
| `PENDING` | 실행 요청 저장, 금융 결과 미확정 |
| `COMPLETED` | 원결제 전액 취소가 확인돼 이 시도로 환불이 끝남(귀속은 Payment 정본, 결정 164) |
| `FAILED` | 실패 확정(출처 `failure_source`). 복구 완료·재신청 접수는 근거 ID로 남고 상태는 환불 표가 정본 |
| `LATE_CANCELLED` | 실패 확정 뒤 늦은 전액 취소가 확인됨(끝). 실패 사실은 지우지 않음 |

트리거: E2 `LicenseSuspended`(이 시도를 만드는 정지 / 이 시도의 정지 재전달) · E4 · E5 · E6 · E7 · H6 재신청(이 시도를 `previousAttemptId`로 지정) · E8 · E10.

| 상태 \ 트리거 | E2 | E4 | E5 | E6 | E7 | H6 | E8 | E10 |
|---|---|---|---|---|---|---|---|---|
| (없음) | → `PENDING`(순번 1 또는 이전+1, 정지 근거·`licenseId`·`executionRequestEventId`) | ✕ | ✕ | ✕ | ✕ | 409 | ✕ | = |
| `PENDING` | 재전달 = / ✕ | 첫 → = (준비 기록, 받은 값 그대로) / 재전달 = / ✕ | → `COMPLETED` | → `FAILED`(`failure_source`·`failure_event_id`·`closure_id`·복구 요청 ID·`restoration_requested_at`) | ✕ | 409 | ✕ | = |
| `COMPLETED` | 재전달 = / ✕ | 첫 → = / 재전달 = / ✕ | 같은 지문 = / ✕ | ✕ (I18) | ✕ | 409 | ✕ | = |
| `FAILED` | 재신청 정지 → 이 시도 = (새 시도 생성, 환불 표) / 재전달 = / ✕ | 첫 → = / 재전달 = / ✕ | ✕ (I18) | 같은 실패 = / ✕ | 복구 요청·실패·정지·`licenseId` 일치·첫 → = (`restored_event_id`·`restored_at`(License 값)) / 재전달 = / ✕ | 복구 완료(`restored_event_id` 있음)·현재 시도·첫 → = (`retry_requested_at`·`retry_suspension_request_event_id`) / 같은 지정 = / 복구 전 409 | 환불 표 E8 허용 조건 → `LATE_CANCELLED` / M-OR-1 / ✕ | = |
| `LATE_CANCELLED` | 재전달 = / ✕ | 재전달 = / ✕ | ✕ | 재전달 = / ✕ | 늦은 복구 완료 일치 → = (근거 기록) / ✕ | 409 | 재전달 = / ✕ | = |

### 심사 회차 `RefundReviewRound.decision`과 승인 중단 `stop_stage`(`order_refund_review_round`)

회차 출처: 1회차 `INITIAL`, `correction_request_id` 있으면 `APPROVAL_CORRECTION`, 그 밖 `CUSTOMER_RECONSIDERATION`. 7일 재심사 기한은 회차의 `decided_at`(Order 시계) + 7×24시간이며 신청 때 계산한다(상태가 아님).

| `decision` \ 트리거 | H2·H4(이 회차 판정) | H3(이 회차가 현재 반려 회차) | H5(이 회차가 현재 승인 회차) | E2·E3 |
|---|---|---|---|---|
| `PENDING` | 현재 회차·자동 자격 아님 → `APPROVED`/`REJECTED`(판정자 = JWT `sub`) / 다른 회차 409 | 409 | 409 | ✕(중단 없음) |
| `APPROVED` | 같은 명령·판정자·내용 = / 409 | 409 | 중단 표로 | 중단 표로 |
| `REJECTED` | 같은 명령 = / 409 | 7일 이내·첫 신청 → = (이 회차 불변, 다음 회차 `PENDING` 생성) / 같은 `requestId` = / 409 | 409 | 재전달 = / ✕ |

| `stop_stage` \ 트리거 | H5 | 같은 중단 명령 재전송 | E2(중단된 정지 요청) | E3(정정 완료) |
|---|---|---|---|---|
| (없음) | 정지 요청 없음 → `REOPENED`(같은 트랜잭션에서 정정 회차 생성) / 정지 요청 있음 → `RECEIVED` | H5로 처리 | ✕ | ✕ |
| `RECEIVED` | 409(다른 명령) | = (최초 영수증) | 정지 요청 ID 일치 → `SUSPENSION_OBSERVED`(+ `LicenseApprovalCorrectionRequested`) / ✕ | ✕ |
| `SUSPENSION_OBSERVED` | 409 | = | 재전달 = / ✕ | 중단·복구 요청·정지·`licenseId` 일치 → `REOPENED`(정정 회차 생성) / ✕ |
| `REOPENED` | 409 | = | 재전달 = / ✕ | 재전달 = / ✕ |

정지 관찰·정정 완료·재개 판정은 식별자와 단계로만 한다. License 시각을 Order 시각과 비교하지 않는다(U2 ORD-7·8·9). Order 시각끼리의 `at ≥ received_at`은 남겨도 된다.

### 상태 없는 기록

`order_license_grant`·`order_expired_review_closure`·`order_payment_result_rejection`·`order_forward_response`는 불변 기록이다. 첫 기록과 재전달 대조(같으면 `=`, 다르면 ✕)만 한다.

## API 계약

| 메서드 | 경로 | 역할 | 동작 | 멱등 키 | 요구사항 |
|---|---|---|---|---|---|
| POST | `/api/v1/orders` | MEMBER | 단일 상품 주문 생성·같은 요청 재조회(201/200) | `(sub, requestId)` | R1·R2 |
| GET | `/api/v1/orders` | MEMBER | 본인 주문 페이지 | — | R5.2 |
| GET | `/api/v1/orders/{orderNo}` | MEMBER | 본인 주문 상세(`expiresAt` 포함) | — | R5.3·R5.4 |
| POST | `/api/v1/orders/{orderNo}/refund` | MEMBER | 전액 환불 접수(202/200) | `orderNo`(주문당 하나) | R6.1·R6.4·R6.5 |
| GET | `/api/v1/orders/{orderNo}/refund` | MEMBER | 고객 환불 상태·안내 | — | R6.2·R6.3·R8 |
| POST | `/api/v1/orders/{orderNo}/refund/retries` | MEMBER | `previousAttemptId` 재신청(202) | `previousAttemptId` | R8.1·R8.2 |
| POST | `/api/v1/orders/{orderNo}/refund/reconsiderations` | MEMBER | `requestId`·`previousReviewId`·`reason` 재심사(202) | `requestId` | R8.3 |
| GET | `/api/v1/orders/{orderNo}/refund/reviews` | MEMBER | 본인 회차 이력 페이지(관리자 명령 ID·판정자·승인 사유 숨김) | — | R8.4 |
| GET | `/api/v1/admin/refunds` | ADMIN | 상태별 목록(기본 `PENDING_REVIEW`), 대기 진입 시각 | — | R6.6·R8.8·R8.11 |
| GET | `/api/v1/admin/refunds/{refundId}` | ADMIN | 원구매·처리 상태·최초 판정·대기 진입 시각 | — | R6.6 |
| POST | `/api/v1/admin/refunds/{refundId}/decision` | ADMIN | 1회차 판정 | `requestId` | R6.7 |
| GET | `/api/v1/admin/refunds/{refundId}/reviews` | ADMIN | 전체 회차·감사 이력 | — | R8.4 |
| POST | `/api/v1/admin/refunds/{refundId}/reconsiderations/{reviewId}/decision` | ADMIN | 지정 회차 판정 | `requestId` | R8.4 |
| POST | `/api/v1/admin/refunds/{refundId}/approval-stops` | ADMIN | 첫 시도 전 승인 중단(202) | `requestId` | R8.5·R8.6 |
| POST | `/api/v1/ops/outbox/{eventId}/republish` | ADMIN(ops 스위치) | Order 소유 현행 이벤트의 `SENT` 행 재발행 | `eventId`·사유 | R8.10·R8.13·R8.15 |
| POST | `/internal/v1/orders/{orderNo}/payment-admissions` | `X-Payment-Service-Key` | 승인 진행 접수 | `admissionId` | R3.3~R3.9 |
| POST | `/internal/v1/orders/{orderNo}/payment-admissions/{admissionId}/failure` | `X-Payment-Service-Key` | 검증된 시도 실패 통지 | `admissionId` + `supplierAttemptId`·`failedAt` | R3.10 |

- 생성 입력: `requestId`(UUID)·`productCode`(`[A-Za-z0-9][A-Za-z0-9._-]{0,49}`)·`expectedContentVersion`(양수)·`expectedAmount`(0 이상 정수).
- 오류: 익명 401, 역할 없음 403, 잘못된 입력 400, 없는·숨긴 상품·없는·타인 주문 404, 기대 값·요청 ID 충돌·무료 상품·판매 금지(`PRODUCT_NOT_ON_SALE`) 409, 승인 접수 만료 409 `ORDER_EXPIRED`, 판매 제한 409 `PRODUCT_SALE_RESTRICTED`, Catalog 중단·타임아웃·키 미설정/거절 503, 잘못된 Catalog 응답 502.
- 고객 환불 응답: `status`(고객 상태)·`refundId`·`receivedAt`·`completedAt`·`rejectionReason`·`failureNotice`(code·message)·`processingNotice`(code·message·since)·`completionNotice`(code·message, 늦은 취소 완료만)·`retryableAttemptId`·`purchaseReturnedToStorage`·`amount`·`amountDetails`. 안내 코드: `LICENSE_GRANT_CONFIRMATION_DELAYED`·`LICENSE_SUSPENSION_CONFIRMATION_DELAYED`·`LICENSE_RESTORATION_CONFIRMATION_DELAYED`·`APPROVAL_STOP_CONFIRMATION_DELAYED`·`REFUND_EXECUTION_RECEIPT_DELAYED`(가칭, R8.14)·준비 지연 코드(R8.7, 기존 값)·`PAYMENT_NOT_CANCELLED`·`LATE_CANCELLATION_REFUNDED`(가칭).
- 민감 응답(주문·환불·판정)은 `Cache-Control: no-store`. 조회는 저장한 근거만 쓰고 Payment·PG HTTP를 부르지 않는다.

## 이벤트

### 발행 (`stove.order.v1`, 키 `orderNo`, 모두 같은 트랜잭션 Outbox)

| 이벤트(플랫폼 행) | 발행 서비스 · 시점 | 이 모듈이 지키는 것 |
|---|---|---|
| `OrderPlaced`([이벤트 목록 #23](../platform/design.md)) | `CreateOrder` · 주문 저장 | `eventId` = `request_event_id`. 목표 칸 `expiresAt`(T12) |
| `OrderPaid`(#17) | `ApplyPaymentResult` · 처음 `PAID` | 한 번만(I3). 목표 칸 `paymentConfirmedAt` = `result_occurred_at`(결정 166, MC-S4). 이 사실의 응답 쌍은 R10 (1회차 지적 A1, 판정 전) |
| `OrderExpired`(#22) | `RecordOrderExpiry` · 처음 `EXPIRED`(스캔·기한 후 접수·기한 후 실패 통지) | 주문당 하나, `expiresAt`은 저장 값 |
| `OrderPaymentResultRejected`([목표 행](../platform/design.md)) | `ApplyPaymentResult` · 결과 거절 기록과 같은 트랜잭션 | 거절한 결과당 하나(`rejected_result_event_id` 유일). 본문은 플랫폼 "목표 행" 아래 본문 목표 그대로, 금액·PG 상태 없음(결정 153) |
| `LicenseSuspensionRequested`(#18) | `RequestRefund`·`DecideRefund`·`DecideRefundReconsideration`·`RequestRefundRetry`·`ApplyLicenseGrant` | `eventId` = 새 `suspension_request_event_id`(재신청은 `retry_suspension_request_event_id`) |
| `RefundExecutionRequested`(#19) | `ApplyRefundSuspension` · 정지 완료 확인 | `eventId` = 시도 `execution_request_event_id`, 재신청은 `previousAttemptId` |
| `LicenseRestorationRequested`(#39) | `ApplyRefundRestoration` · 실패 확정(두 사실 모두) | 시도의 `restoration_request_event_id`. 실패 반영 트랜잭션에서만(I17) |
| `LicenseApprovalCorrectionRequested`(#40) | `ApplyApprovalCorrection.handleStoppedSuspension` · 중단된 정지 관찰 | 중단의 복구 요청 ID |
| `PurchaseRefunded`(#21) | `ApplyRefundPaymentConfirmation` · 처음 `COMPLETED` / 늦은 취소 서비스(목표 `ApplyLateCancellation`) · 처음 `LATE_CANCELLATION_COMPLETED` | 환불당 한 번. 정지 요청 ID는 끝난 시도의 것. 늦은 취소 때 License가 복구된 보유권을 회수할 수 있어야 한다(플랫폼 F 참고, 1회차 지적 A2·B2, 판정 전) |

헤더 `occurredAt`은 본문 사실 시각과 같다(T25). Outbox 행은 `schema_version`을 갖는다(결정 170).

### 소비

| 이벤트(플랫폼 행) | 그룹 · 리스너 → Mapper → 서비스 | 업무 멱등 키 | 실패 처리 |
|---|---|---|---|
| `OrderPaymentCompleted`(#24) | `order-payment-result` · `PaymentEventListener` → `PaymentResultMapper.from` → `ApplyPaymentResult` | `uk_purchase_order_result`, 거절 `rejected_result_event_id` 유일 | 연결값 불일치·다른 주문 `eventId` ✕. 반영 불가 성공은 거절 사실(DLT 아님). `admissionId` 없는 본문은 M-OR-3 |
| `OrderPaymentFailed`(#25) | 같은 그룹·리스너 | `uk_purchase_order_result` | 이행 기간 `reasonCode=REVIEW_CLOSED`는 Mapper에서 `REVIEW_CLOSED` 결과 종류로 바꿈(결정 174). `EXPIRED`는 M-OR-2 |
| `OrderPaymentReviewClosed`(목표 행) | 같은 그룹(같은 주문 결과의 순서 보장, P2.5-1) | `uk_purchase_order_result`, `order_expired_review_closure` 유일 | 주문 표 O10 |
| `LicenseGranted` → `PurchaseRecordStored`(#33, 결정 175) | `order-license-grant` · `LicenseEventListener` → `LicenseGrantMapper.from` → `ApplyLicenseGrant` | `uk_order_license_grant_event`·`_order`·`_license` | 이행 기간 두 타입을 같은 입력으로(P2.7-4). 불일치 ✕ |
| `LicenseSuspended`(#34) | `order-refund-suspension` · `RefundSuspensionEventListener` → `ApplyRefundSuspension`(중단 중이면 `ApplyApprovalCorrection.handleStoppedSuspension`, 같은 그룹 Inbox) | 시도 `suspension_event_id`, 회차 `stop_suspension_event_id` 유일 | 환불 표 E2 |
| `LicenseApprovalCorrected`(#41) | `order-approval-correction` · `ApprovalCorrectionEventListener` → `ApplyApprovalCorrection` | `stop_restored_event_id` 유일 | 환불 표 E3 |
| `RefundExecutionPrepared`(#20) | `order-refund-preparation` · `RefundPreparationEventListener` → `RefundPreparationMapper` → `ApplyRefundPreparation` | `uk_order_attempt_preparation` | 받은 `confirmationDelayAt`을 다시 계산하지 않음(T14) |
| `RefundPaymentConfirmed`(#28) | `order-refund-payment-confirmation` · `RefundPaymentConfirmationEventListener` → `RefundPaymentConfirmationMapper` → `ApplyRefundPaymentConfirmation` | `uk_order_refund_payment_confirmation` | `source`로 분기하지 않음. 새 `schemaVersion`(칸 삭제)도 같은 입력(T19) |
| `RefundExecutionSucceeded`(#29) | 같은 리스너 — 목표: 건너뜀(건너뛴 수 지표) | — | 결정 164 (1회차 지적 A4·B5, 판정 전) |
| `RefundExecutionFailed`(#38, Payment 토픽만) | `order-refund-restoration` · `RefundRestorationEventListener`(토픽·타입 짝 대조) → `RefundFailureConfirmationMapper` → `ApplyRefundRestoration` | 시도 `failure_event_id` 유일 | 환불 표 E6 |
| `RefundFailureClosed`(목표 행, Payment 토픽만) | 같은 그룹·리스너, Mapper가 같은 입력 + 출처 `OPERATOR_FAILURE_CLOSED`·`closureId` | `failure_event_id` 유일 | 근거 상세 칸이 없어도 거절하지 않음(T4) |
| `LicenseRestored`(#42, License 토픽만) | 같은 그룹·리스너 → `LicenseRestorationConfirmationMapper` → `ApplyRefundRestoration` | 시도 `restored_event_id` 유일 | 환불 표 E7 |
| `RefundLateCancellationConfirmed`(목표 행, Payment 토픽만) | `order-refund-restoration`(실패 사실과 같은 토픽·키·그룹이라 실패 확정 뒤에 처리됨) · `RefundRestorationEventListener` → (목표) `LateCancellationMapper` → `ApplyLateCancellation` | `order_refund.late_cancellation_event_id` 유일 | 환불 표 E8, 재신청 진행 중은 M-OR-1 (1회차 지적 A5, 판정 전) |
| `SettlementSaleRecorded`(가칭, 목표 행) | (신설) `order-settlement-sale` · `SettlementEventListener` → `RecordForwardResponse` | `order_forward_response(order_no, response_type)` | 주문 `PAID` 아님·연결 불일치 ✕ (1회차 지적 B4, 판정 전) |
| `LicenseRefundApplied`(가칭, 목표 행) | (신설) `order-license-refund` · `LicenseEventListener`(License 토픽) → `RecordForwardResponse` | 같음 | 환불 `COMPLETED`·`LATE_CANCELLATION_COMPLETED` 아님 ✕ |

- 모든 리스너는 처리하지 않는 `eventType`을 건너뛰고 (그룹, 타입) 지표로 센다(P2.3-7, T31).
- 영구 실패는 공통 영구 실패 예외로 던진다(지금 `RefundCompletionRejected`·`BusinessException`, 환불 팩토리만 재시도 제외 — T20).
- 키 정지·실패 보관·재투입은 공통 메시징(결정 155·160). 같은 주문 키의 뒤 레코드는 앞 레코드가 회수될 때까지 처리되지 않는다.

## 모듈 안 불변식

"지금"이 비어 있으면 목표이며 tasks 번호를 단다. 테스트는 클래스#메서드, 메서드를 확인하지 못한 것은 클래스만 적었다.

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| OI1 | 청구액 = 정가 − 할인 > 0, 스냅샷·`expires_at` 불변 | DB `chk_purchase_order_price`(V10:29), `Order.create`·`OrderPrice.atListPrice`. 열 불변은 목표 `updatable=false`(OR-T8) | `OrderTest#storesOriginalProductAndSeparateCustomerAmounts`, `OrderTest#priceCompositionCannotHideFeesOrNegativeDiscounts`; 열 불변 없음 → OR-T8 |
| OI2 | `(buyer, requestId)`당 주문 하나, 같은 ID 다른 입력 거절 | `uk_purchase_order_request`, `Order.requireSameRequest` | `OrderTest#sameRequestMustKeepCodeVersionAndAmount`, `OrderIntegrationTest` |
| OI3 | 상태 열은 enum 값만 | 목표 CHECK 5개(MC-O1·O2·OR-T10) | 없음 → MC-O1(이행 테스트) |
| OI4 | 기한 판정은 저장한 `expires_at`과 Order 시계, 기한 안 `PAYMENT_PROCESSING`은 시간으로 끝나지 않음 | `Order.beginPaymentProcessing`·`expire`·`releaseFailedPaymentProcessing`, `CloseExpiredOrders` | `OrderPaymentLifecycleTest#theDeadlineItselfRejectsAdmissionAndExpiresTheOrder`, `#anAdmittedPaymentCanCompleteAfterTheDeadlineWithoutBeingExpired`; 저장 열 기준은 없음 → OR-T1 |
| OI5 | 주문당 `OPEN` 영수증 ≤ 1, `OPEN` ⇔ 주문 `PAYMENT_PROCESSING` | 목표 생성 열 유일 제약 + `OrderPaymentAdmission` 전이 | 없음 → MC-O1 |
| OI6 | `PAID` ⇒ `payment_admission_id`가 그 주문의 `RESULT_APPLIED` 영수증(플랫폼 I12의 Order 쪽) | 목표 FK + CHECK, `Order.apply` 영수증 대조 | 없음 → T1 재현 (1)~(4) |
| OI7 | 결과 `eventId`는 반영·거절·종결 세 표 중 한 곳·한 주문에만 | 표별 유일 제약 + `ApplyPaymentResult.execute` 교차 조회 | `ReviewClosureAfterExpiryKafkaIntegrationTest`(종결 표 부분); 거절 표 없음 → OR-T3 |
| OI8 | `OrderPaid`는 처음 `PAID` 트랜잭션에서만, `OrderPaymentResultRejected`는 거절 결과당 하나 | `ApplyPaymentResult.execute`, 목표 `rejected_result_event_id` 유일 | `OrderTest#finalResultIsImmutableAndRepeatedDeliveryDoesNotChangeTimestamp`; 거절 없음 → OR-T3 |
| OI9 | 결과 종류는 이벤트 타입에서만 정한다 | 목표 `payment_result_kind` CHECK, `PaymentResultMapper` | 없음 → OR-T2 |
| OI10 | `PAID` ⇒ `paid_at` 있음, 자동 자격 = `received_at ≤ paid_at + 4h`(Order 시계끼리) | 목표 `chk_purchase_order_paid_at`, `RefundPolicy` | `RefundPolicyTest`(지금은 `resultOccurredAt` 기준) → MC-O4 |
| OI11 | 주문당 환불 하나, 자동 자격은 접수 때 한 번 | `uk_order_refund_order`, `automatic_refund_eligible updatable=false` | `RefundReceptionIntegrationTest`, `RefundPolicyTest` |
| OI12 | 실행 요청은 실제 정지 완료 뒤에만, 정지 요청 ID 일치 | `Refund.recordExecution`, `RefundAttempt.afterSuspension`·`afterRetrySuspension` | `RefundExecutionIntegrationTest`, `RefundSuspensionKafkaIntegrationTest` |
| OI13 | 승인 중단은 첫 시도 전에만 | `Refund.stopApproval`(`current_attempt_id IS NULL`) | `RefundApprovalStopIntegrationTest` |
| OI14 | `COMPLETED` ⇔ 금융 확인 칸, `LATE_CANCELLATION_COMPLETED` ⇔ 늦은 취소 확인 칸, 두 끝 상태는 `purchase_refunded_at` 있음 | `chk_order_refund_payment_confirmation`(V17:14), 목표 CHECK 2개 | `RefundPaymentConfirmationTest`; 늦은 취소 없음 → MC-O3 |
| OI15 | 환불당 `PurchaseRefunded` 한 번 | `Refund.confirmPayment`(바뀐 때만 true), `ApplyRefundPaymentConfirmation` | `RefundPaymentConfirmationKafkaIntegrationTest` |
| OI16 | 시도 하나는 `COMPLETED`·`FAILED` 중 하나만(플랫폼 I18 Order 쪽), 실패 사실은 출처 하나(I16) | 시도 상태 한 열, 목표 `failure_source` CHECK | `RefundAttemptTest`; 출처 없음 → T4 |
| OI17 | 복구 요청은 실패 반영 트랜잭션에서만(I17) | `ApplyRefundRestoration` | `RefundRestorationTest` |
| OI18 | 상태별 대기 진입 시각 NOT NULL | V32·V34 CHECK | `LicenseGrantWaitIntegrationTest`, `RefundLicenseStepWaitIntegrationTest` |
| OI19 | 회차 순번·이전 회차·판정 명령 유일, 판정 정본은 회차 하나 | `uk_refund_review_no`·`_previous`·`_decision`; 목표 `order_refund.review_*` 제거 | `RefundReconsiderationIntegrationTest`; 정본 하나는 없음 → OR-T9 |
| OI20 | 다른 모듈·공급자 시각과 Order 시각, 또는 두 다른 시계의 시각을 비교해 거절하지 않음(플랫폼 I13 Order 쪽) | 목표: 식별자·상태 판정(U2 ORD-0~10 + OR-T4·OR-T5) | 없음 → T3(D-052 방식 ±5초) |

모듈을 넘는 불변식: I1(연결값 5개 대조 — `Order.requireConnection`), I3(`OrderPaid` 한 번), I4(`COMPLETED`·늦은 취소 완료의 Payment 근거), I8(Inbox + 업무 유일 키), I12(OI6), I13(OI20), I14(R10 게이지), I16·I17·I18(OI16·OI17)은 [플랫폼 design "불변식 표"](../platform/design.md)가 정본이다.

## 외부 연동과 adapter 메모

- `core.port.CatalogProducts` ← `adapter.CatalogHttpClient`: `/internal/v1/order-products/{code}`, 헤더 `X-Order-Service-Key`, 연결 3초·읽기 10초. 404 → 주문 404, 타임아웃·연결 실패·5xx·키 미설정/거절 → 503, 형식·필수 값 오류 → 502. 받는 값은 상품 ID·코드·제목·버전·금액·통화·판매 상태·`publicDetailAllowed`뿐이다. 호출은 트랜잭션 밖(`CreateOrder`·`AdmitOrderPayment`)이다.
- Payment가 부르는 내부 API는 `config.SecurityConfig`의 상수 시간 키 비교로 Payment만 통과시킨다. 결제 공급자 토큰·비밀값은 Order로 오지 않는다.
- Order는 결제 공급자를 직접 부르지 않는다. core 계약에 공급자 이름을 쓰지 않는다. `pgPaidAmount`·`PG_CANCELLATION_FAILED`의 "PG"는 중립 용어다. 지금 core에 남은 공급자 쪽 문자열은 `RefundPaymentConfirmation`의 `source` 값 `PG_RESPONSE`·`PG_QUERY_UNATTRIBUTED`(Payment 귀속 분류)이며 T19로 없앤다. 공급자 보장 기한(3시간)은 Order가 알지 않는다(T14).

## 설정

| 키 | 기본값 | 쓰는 곳 | 메모 |
|---|---|---|---|
| `stove.order.payment-window`(가칭, 목표) | `30m` | `CreateOrder`(→ `expires_at`) | 결정 32의 기한. 지금 `Order.java:77`·`CloseExpiredOrders.java:38` 상수(T12, OR-T1). 바꾸면 새 주문부터 적용(저장 값) |
| `stove.order.refund.license-grant-delay` | `10m` | `RefundConfiguration` → `ReadLicenseGrantDelays`·`ReadRefund` | 결정 125. R8.9·R8.12·R8.14 공통 경계 |
| `stove.order.payment-service-key` | 없음(`PAYMENT_ORDER_SERVICE_KEY`) | `SecurityConfig` | 미설정이면 내부 API 거절 |
| `stove.order.catalog-service-key`·`catalog-base-url`·`catalog-connect-timeout-ms`·`catalog-read-timeout-ms` | 키 없음, `http://localhost:8081`, 3000, 10000 | `CatalogClientConfiguration` | `catalog-base-url`의 개발용 기본값은 운영 계약에 남기지 않는다(OR-T12, 원칙 ⑦) |
| `stove.order.expiry-enabled` | `true` | `OrderExpiryScheduler`·`OrderPaymentLifecycleConfiguration` | 끄는 곳은 통합 테스트(`OrderIntegrationTestBase.java:26`)뿐이다 — 테스트 편의 스위치(OR-T12, 원칙 ⑦) |
| `stove.messaging.ops.enabled`·`republish-enabled`·`republish-owner=order` | `false`·`true`·`order` | 공통 `OutboxRepublishController` | 허용 목록은 플랫폼 이벤트 표에서 생성(R8.15) |
| `stove.outbox.*` | relay on, batch 200, poll 200ms, max-retry 10 | 공통 릴레이 | P2.2 |
| core 상수 | 자동 환불 4시간(`RefundPolicy`), 재심사 7일(`RefundReviewRound.APPLICATION_WINDOW`) | — | 값 변경 적용 기준은 범위 밖(Q9) |

운영·테스트 차이: 위 두 개(OR-T12)를 빼면 운영과 테스트가 같은 계약이다. 테스트는 `Clock` 주입으로 시각을 고정하고, `Order.create`·`Order.apply`·`ExpiredReviewClosure` 기록의 `Instant.now()`도 주입 `Clock`으로 바꾼다(OR-T7).

