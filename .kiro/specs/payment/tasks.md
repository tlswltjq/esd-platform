# 작업: Payment

> 상태: 작성 중(결정 150·178). 목표 계약은 [requirements.md](requirements.md)·[design.md](design.md). 모듈을 넘는 작업은 [플랫폼 tasks](../platform/tasks.md)의 T 번호다. 경로 줄임: `payment:` = `apps/payment/src/main/java/com/stove/payment/`, `Vn` = Payment Flyway 이행 파일. 줄 번호는 2026-11-09 `docs/spec-foundation` 기준.

## 완료 (검증 결과는 근거 색인)

근거 수치·원문은 [근거 색인](../../../docs/evidence.md)을 따른다. 아래는 지금 `tasks.md`의 완료 항목을 그대로 옮긴 것이다(R 번호는 옛 번호 — 옛 둘째 R14는 새 R15, notes I).

- [x] [결정 114](../../../docs/decisions.md#approval-review-hold-closure) 1부: 승인 보류 ADMIN 조회·최신 PG 관측·조사 기록·사유별 게이지/경보 — 2026-11-07 (간격·고객 안내는 G1 미정)
- [x] [결정 115](../../../docs/decisions.md#approval-review-closure-outcome)(결정 114 2부): PG 재조회 근거의 ADMIN 보류 종결(작업자와 같은 lease·트랜잭션 밖 조회·전액 CANCELLED/FAILED/READY만), 주문 `FAILED`(`REVIEW_CLOSED`) 또는 이미 만료면 `EXPIRED`, 전액 CANCELLED 관측·`PG_CANCELLED` 보류·경보, V36·V37 — 2026-11-07
- [x] 금융 보류 건 PG 재조회 간격([결정 122](../../../docs/decisions.md#approval-review-requery-interval)): `REVIEW_REQUIRED` 승인의 다음 조회를 `now + min(10분, max(5초, now − review_started_at))`로 잡는다. 시작값·상한은 보류 전용 설정 키로 두고 `PAYMENT_RETRY_MS`는 바꾸지 않으며, `review_started_at`이 없는 옛 행은 상한을 쓴다 — 2026-11-08 (`PAYMENT_REVIEW_RETRY_INITIAL_MS`·`PAYMENT_REVIEW_RETRY_MAX_MS`, R13.10)
- [x] 접수 전 PAY_PENDING 승인의 만료 종결([D-044](../../../docs/defects.md#d-044), [결정 126](../../../docs/decisions.md#pre-admission-pending-expiry)): PG 관측 이력(`latestPgState`)이 있는 접수 전 `REQUESTED` 승인만 만료로 닫기 전에 PG를 한 번 조회한다. PAID → `PAID_WITHOUT_ADMISSION`, 전액 CANCELLED → `PG_CANCELLED`, PENDING → `PG_PENDING_AT_ORDER_EXPIRY` 보류, READY·FAILED → `EXPIRED`. 관측 이력이 없으면 PG 호출 없이 닫는다. R11.5·R12.4, 게이지 사유 목록·경보 테스트 갱신 — 2026-11-08 (PR #145)
- [x] `OrderPlaced` 내구 접수(요청 전체 비교·Inbox·접수 경합), `PENDING` 결제 생성 — 2026-10-16
- [x] 미확정 건 주기 재개(기본 1초·20건·오류 재시도 5초), 재시작 후 DB `PENDING` 재개 — 2026-10-16
- [x] 세 트랜잭션 경계(접수·트랜잭션 밖 PG 호출·결과 행잠금), 고정 paymentId 멱등 키 — 2026-10-16
- [x] 확인된 승인·거절만 `PAID`/`FAILED` 확정, 결과·Outbox 동시 커밋, 응답 유실·결과 저장 실패는 `PENDING` 유지 — 2026-10-16
- [x] `OrderPaymentCompleted`/`OrderPaymentFailed` 발행, DEAD 보존·원래 ID/본문 재발행 — 2026-10-16
- [x] 내 결제 목록·상세 GET, JWT sub 소유권·타인 404·페이지 정렬, 구 prepare/callback/cancel/simulator·Gateway 예외 제거 — 2026-10-16
- [x] 8개 앱 실제 연계 11단계(승인·거절·중단·재시작·결과 전달 복구) — 2026-10-16 ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/payment-mvp-plan.md#재작성-완료-기록))
- [x] 외부 모의 PG 분리(simulators/mock-pg 전용 프로세스·DB·HTTP), `SimulatedPgHttpGateway`, V12 비용 스냅샷(미산정 null) — 2026-10-18
- [x] PortOne V2·토스페이(`tosspay_v2`) TEST 채널 독립 결제·전액 취소 검증 — 2026-10-27 ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/pg-test-integration-plan.md))
- [x] 웹훅 접수(서명·스토어·재전달 검증, 조회 힌트), checkout 전용 API(A안), 공개 HTTPS edge·실제 Ready/Paid 웹훅 수신 — 2026-10-27
- [x] 같은 주문 재결제 A안: 개별 FAILED 별도 이력, `PENDING` checkout 재사용, 실패 시도 이력 GET, 승인/Outbox 선행 커밋 — 2026-10-29 (결정 31)
- [x] 서버 승인 연결: 암호화 접수(AES-GCM)·3시간 복구 창·검증된 실패 재진입·자동 만료 스캔 연동, `PAID`만 확정 — 2026-10-29 ([결정 32](../../../docs/decisions.md#order-expiry-approval-admission)·[결정 33](../../../docs/decisions.md#payment-approval-recovery), [원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/payment-confirmation-plan.md))
- [x] Order 만료 결과 수신(`OrderExpired`·원구매 대조·주문별 잠금·늦은 `OrderPlaced`·조회 예약 종료) — 2026-10-29 ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/order-expiry-propagation-plan.md))
- [x] 실제 TEST 생명주기: 서버 승인→PAID, 시도 실패 뒤 같은 주문 재결제, 승인 응답 유실 복구, 30분 자연 만료·새 주문, PG 할인 A안·V32 — 2026-10-29 ([결정 34](../../../docs/decisions.md#provider-discount-payment-amounts), [원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g1-portone-live-verification.md))
- [x] 승인 전 검증 보류(`REVIEW_REQUIRED`·`PG_INVALID_EVIDENCE`)의 만료 반영 경로 보완 — 2026-10-30 ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g1-unadmitted-expiry-fix.md))
- [x] 환불 실행·확인: 고정 attemptId·3시간 창·원액 잔액 조건, 전액 취소 조회 확인(A)·검증된 취소 완료 응답(A), `RefundPaymentConfirmed`, 원 PAID 불변 — ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/pg-test-integration-plan.md)·[인계](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/refund-session-handoff.md))
- [x] 환불 운영: ADMIN `/api/v1/admin/refund-cancellation-holds` 중단/재개, `/api/v1/admin/refund-delays` 장기 미확정 조사 — 2026-10-29
- [x] 실제 자동 환불 전체 흐름 1건, 실제 ADMIN 수동 심사 PG 환불(2차 승인→License SUSPENDED→PortOne CANCELLED) 1건 — 2026-10-30
- [x] G5 웹훅 수신 경계: 누락/중복 헤더·시간/원문/크기·접수 보존·비활성 제공자·키 조립 거절 — 2026-11-05 ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g5-webhook-verification.md); 공개 HTTPS·실제 제공사·배포 검증은 별도)
- [x] G1 종료 조건 대조 — 금융 보류 건 고객 안내([결정 123](../../../docs/decisions.md#approval-review-customer-notice)): 결제 상세·승인 조회 응답에 청구 여부별 `reviewNotice {code, message, since}`(`CHARGED`·`CHARGE_CANCELLED`·`CHARGE_UNCONFIRMED`·`NOT_CHARGED`, 종결 뒤 `CLOSED_CHARGE_CANCELLED`·`CLOSED_NOT_CHARGED`)를 더했다(R14). `PG_INVALID_EVIDENCE`는 `CHARGE_UNCONFIRMED`로 고정하고 나머지 사유는 마지막 PG 관측으로 정한다(결정 132). 알려진 보류 사유 8개를 모두 묶음에 매핑하고, 사유 목록과 매핑이 어긋나면 `ApprovalReviewNoticeTest`가 실패한다. 결제창 409 메시지를 한국어로 바꿨다(보류면 같은 안내 문구). 원래 사유 코드는 노출하지 않고, Order 계약은 바꾸지 않았다 — G1, 2026-11-08 PR #155
- [x] 결제창 이탈 후 재진입의 Order 결과/다음 행동 검증([결정 120](../../../docs/decisions.md#g1-reentry-scope)): 실제 TEST로는 시도 실패(결제창 닫기)→같은 주문 재결제와 이탈→30분 자연 만료를 확인했다. 흉내 서버 실제 스택 여정에 30분 안 위험 조합(승인 요청 미도달 뒤 재결제, 처리 중 결제창 재요청 거절, 처리 중 새 주문 이중 구매, PAY_PENDING 유지, confirm 400/403 보류)을 기존 야간 작업으로 넣는다. 기한을 넘는 조합(기한 뒤 FAILED, 기한 안 접수 뒤 늦은 PAID, 접수 전 PAY_PENDING 만료)은 31분 대기 한 번으로 묶은 별도 야간 작업으로 넣는다. 3시간 조합은 통합 테스트로 인정하되, 기존 `PaymentApprovalIntegrationTest:220-227`은 PG READY만 확인하므로 PAY_PENDING으로 3시간을 넘기는 통합 테스트를 새로 더한다 — G1
  - 진행(2026-11-08): 30분 안 조합 c1~c5를 `tools/verification/verify-approval-journeys.py`에 더했다(기존 야간 작업). 승인 요청 미도달 뒤 같은 주문 새 시도 PAID(실패 이력 0), 처리 중 checkout 409 `APPROVAL_IN_PROGRESS`, PAY_PENDING 유지(재송신 0·조회만), 처리 중 새 주문 이중 구매 뒤 두 주문 PAID·두 구매분 보관함(등록 409 `PRODUCT_ALREADY_OWNED`), 처리 중 FAILED 뒤 주문 PENDING·같은 주문 재결제, confirm 400/403 `PG_REQUEST_REJECTED` 보류·READY 근거 종결. 기한을 넘는 두 조합(기한 뒤 FAILED → 주문·결제 `EXPIRED`, 기한 안 접수 뒤 늦은 PAID → 원래 주문 PAID)은 새 `tools/verification/verify-expiry-journeys.py`와 별도 야간 작업(31분 대기, 제한 60분)에 넣었다([근거 색인](../../../docs/evidence.md) 2026-11-08). 접수 전 PAY_PENDING 만료(D-044, [결정 126](../../../docs/decisions.md#pre-admission-pending-expiry))는 같은 기한 여정의 p1로 더했다: 주문 자연 만료 뒤 승인 `PG_PENDING_AT_ORDER_EXPIRY` 보류(미접수, 결제 PENDING, 안내 `CHARGE_UNCONFIRMED`, 게이지 1, confirm 0) → 보류 중 늦은 PAID는 `PAID_WITHOUT_ADMISSION`(`CHARGED`, 종결 409 `CLOSURE_PG_PAID`) → 콘솔 전액 취소 뒤 종결로 승인 FAILED·결제 `EXPIRED`·주문 `EXPIRED`·`OrderPaymentFailed` 0 → 새 주문([근거 색인](../../../docs/evidence.md) 2026-11-08 p1 행). 실제 PortOne 재확인은 아래 `[외부 대기]` 항목에 남는다 — 2026-11-08
  - 3시간 조합(2026-11-08): PAY_PENDING으로 3시간을 넘기는 통합 테스트를 결정 124 구현과 함께 더했다(`PaymentApprovalIntegrationTest` `anAdmittedPayPendingApprovalPastTheConfirmWindow…`, READY 3시간 경계 테스트 기대값 갱신).
- [x] confirm 기한이 지난 접수 승인의 보류([결정 124](../../../docs/decisions.md#confirm-window-expired-hold)): `ADMITTED`이고 `firstPreparedAt + 3시간`이 지났으며 PG가 `PENDING`·`READY`인 승인을, 조회 직후 `CONFIRM_WINDOW_EXPIRED`로 `REVIEW_REQUIRED`에 올린다. 게이지 사유 목록·경보 테스트·R4.6·R12.4 문구를 갱신하고, PAY_PENDING 3시간 통합 테스트를 더했다. 고객 안내(결정 123 구현)는 위 G1 항목에 남음(PG `PENDING`이면 `CHARGE_UNCONFIRMED`, `READY`면 `NOT_CHARGED`) — G1, 2026-11-08 PR #152
- [x] ([결정 142](../../../docs/decisions.md#g3-exit-current-contract)로 정리: 미확정 비용은 NULL로 두고 판매자 마감과 분리[결정 39], 실제 공급자 비용·지급 자료 연결은 G7) 같은 공급자 거래/비용·지급 자료로 Settlement까지 연결, 미확정 비용 모델 확정 — G3 ([계획](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/platform-generalization-plan.md))
- [x] 장기 미확정·귀속 미확정 환불의 ADMIN 종결([결정 127](../../../docs/decisions.md#refund-delay-closure)): 귀속 미확정(`PG_QUERY_UNATTRIBUTED`) 실행도 ADMIN 목록에서 찾게 하고(`GET /api/v1/admin/refund-delays?kind=UNATTRIBUTED`), `POST /api/v1/admin/refund-delays/{attemptId}/closure`를 둔다. 결정 115와 같은 구조(작업자와 같은 lease·트랜잭션 밖 PG 재조회·별도 잠금 트랜잭션)로, 같은 원결제의 전액 취소가 확인될 때만 닫는다. 미완료 건은 기존 완료 경로로 즉시 완료, 완료된 귀속 미확정 건은 판정만 기록한다. 귀속 판정·ADMIN·근거·시각을 저장하고(V39 `refund_closure`), PG가 아직 결제 상태면 409. PG 취소는 실행하지 않는다. 귀속 미확정 실행은 조회 일정이 멈춰 lease 없이 행 잠금·UNIQUE·requestId로 한 번만 닫는다(R14.3). 운영 절차서([환불 종결](../../../docs/runbooks/refund-delay-closure.md))에 "콘솔 취소 → 종결"과 결정 134의 "환불할 건이면 콘솔 취소 → 결정 127 먼저"를 적었다. Order 조사 표시 해제는 아래 `[결정 필요]`로 남김 — G2, 2026-11-08 PR #157
- [x] 귀속 미확정 미종결 환불의 게이지·경보([결정 127](../../../docs/decisions.md#refund-delay-closure)·[결정 131](../../../docs/decisions.md#investigation-trail-no-assignee) 범위, 새 정책 없음): `status = PENDING`이고 `refundConfirmationSource = PG_QUERY_UNATTRIBUTED`이며 결정 127 종결 기록이 없는 실행의 건수를 게이지 `stove.payment.refund.unattributed.open`으로 낸다. Prometheus 경보 `RefundsUnattributed`는 `> 0`, `for` 없음, warning이다(돈은 PG 전액 취소로 이미 돌아갔고 종결만 남은 점이 승인 보류 `PG_CANCELLED`와 같다). 경보 설명에 `GET /api/v1/admin/refund-delays?kind=UNATTRIBUTED`와 결정 127 종결 경로를 적었다. 기존 `RefundConfirmationsDelayed`의 대상·식은 바꾸지 않았다. 경보 규칙 테스트(`infra/prometheus/tests/refund-unattributed.yml`)·R14.8·design을 갱신했다 — G2, 2026-11-08 PR #157
- [x] 새 환불 이벤트·재개 작업의 Tempo 추적 전체 연결 검증 — 2026-11-09 `verify-refund-tracing.py`(흉내 PortOne·격리 Tempo). 정상 환불은 요청 HTTP → Order Outbox → License → Payment 수신·PG 취소 → `RefundPaymentConfirmed` → Order `PurchaseRefunded` → License·Settlement가 한 trace로 이어진다. 응답 유실·PG 5xx 뒤 조회·재송신 작업자 경로는 처음 검증에서 끊겼고([D-050](../../../docs/defects.md#d-050), 아래 항목), D-050 수정 뒤 같은 도구로 세 경우 모두 한 trace로 이어진다
- [x] [D-050](../../../docs/defects.md#d-050) 환불 조회(`ResolveRefundLookup`)·재송신(`ResolveRefundRetry`) 작업자가 `refund_execution_trace`의 저장 부모를 복원하지 않는다(`RefundExecutionTraceContexts.find` 운영 호출 0곳). 결정 28대로 처리 시도마다 저장 부모의 새 자식 span에서 PG 호출·결과 Outbox를 적재하게 한다. 먼저 실제 tracer 통합 `known-defect` 재현 테스트를 쓰고, 고친 뒤 `verify-refund-tracing.py`가 통과해야 닫는다 — G2, 2026-11-09 수정(`RefundWorkerTracingIntegrationTest` Red 2→Green, 도구 세 경우 통과)
- [x] [D-051](../../../docs/defects.md#d-051) 결제 재조회·웹훅이 접수된 보류 승인의 결제를 `PAID`로 확정해도 승인은 다음 보류 재조회까지 `REVIEW_REQUIRED`로 남아 고객 안내(결정 123)·ADMIN 보류 목록·게이지가 옛 관측을 보였다. `ResolvePayment.confirm`이 같은 트랜잭션·같은 잠금 순서로 접수된 `ADMITTED`·`REVIEW_REQUIRED` 승인을 `succeed`한다(작업자 PAID 분기와 같은 규칙, 시점만 당김) — G1, 2026-11-09 수정(`PaymentApprovalIntegrationTest` Red 1→Green)
- [x] 이미 보류된 승인은 처음 보류 사유를 유지([결정 144](../../../docs/decisions.md#held-approval-keeps-first-reason)): `PaymentApproval.stopPosts`가 `REVIEW_REQUIRED`의 `lastErrorCode`·`reviewStartedAt`을 바꾸지 않고 `PAID_WITHOUT_ADMISSION`만 사유를 올린다. 버린 사유는 `ResolvePaymentApproval.stop` 로그. R12.10 — G1, 2026-11-09 PR #185

## 코드와 맞출 것

형식: 무엇이 · 어디서 · 목표 · 영향 · 재현 테스트. 돈·보유권 영향 순. `MC-P*`는 초안 자료 `esd-spec-drafts/module-carryover.md`(저장소 밖)에서 이월(번호 유지), `PA-T*`는 이번에 찾은 것. 모듈을 넘는 작업은 플랫폼 T 번호에 붙인다.

### 돈·보유권 영향

- [ ] **MC-P14. 끝난 결제의 늦은 청구를 감시하지 않는다** ([결정 154](../../../docs/decisions.md#unlinked-charge-discovery), 플랫폼 T5)
  - 어디서: `payment:core/domain/Payment.java:172-178`(`requestRecheck`가 `PENDING`만), `payment:core/service/ResolvePayment.java:89`(재개 대상에서 뺌).
  - 목표: R16·R17, design 상태 모델 4·5. `payment_charge_watch`·`unlinked_charge`·`unlinked_charge_investigation` 표, `ResolveChargeWatch`·`ChargeWatchScheduler`, ADMIN 목록·종결 API, 게이지 `stove.payment.unlinked-charge.open{source}`(critical), 감시 게이지, 고객 안내 R14.4, adapter 설정(간격·`watch_until`, `[외부 대기]`).
  - 영향: **돈** — `READY` 근거 종결 뒤 콘솔 승인으로 생긴 청구가 보이지 않고 고객은 `CLOSED_NOT_CHARGED`를 본다.
  - 재현: 흉내 서버 여정 — `READY` 종결 → `console-approve` → 기록 `OPEN`·경보·`reviewNotice=CHARGED`. 만료 뒤 `PAID`도 같음.
- [ ] **MC-P13. `OrderPaymentResultRejected`를 소비하지 않는다** ([결정 153](../../../docs/decisions.md#late-success-rejection), 플랫폼 T1)
  - 어디서: 리스너 없음(`payment:api/listener/`), 이벤트 없음(Order 쪽 T1).
  - 목표: R17.1~17.4, 그룹 `payment-order-result-rejection`, `unlinked_charge`(`ORDER_REJECTED`), 결제 `PAID`·시도 `SUCCEEDED` 불변. 종결 뒤 표현은 M-PA-4.
  - 영향: **돈** — Order가 거절한 성공(돈은 나감)이 지금은 409 → DLT로 묻힌다.
  - 재현: Payment 통합 — `PAID` 결제의 결과 ID로 거절 사실 주입 → 기록 1건·게이지 1·재전달 무변화·다른 결과 ID 영구 실패.
- [ ] **PA-T11. 결과 이벤트가 시도를 가리키지 않고, 확정 트랜잭션이 시도를 다시 확인하지 않는다** (플랫폼 T1·T18, 결정 153·174, (1회차 지적 A3, 판정 전))
  - 어디서: `common/kafka/.../payload/OrderPaymentCompletedEvent.java:11-12`(`admissionId` 없음), `payment:core/service/PaymentResultOutbox.java:15·20`, `payment:adapter/pg/AdmittedPortOnePgGateway.java:21-28`(잠금 밖 시도 조회), `payment:core/domain/ApprovalClosure.java:27`·`Payment.java:147-156`(`REVIEW_CLOSED`를 `OrderPaymentFailed`로).
  - 목표: R3.1·R3.12·R13.5. `OrderPaymentCompleted` `admissionId` 필수 + 새 `schemaVersion`(P2.7-5 순서: Payment 늦은 성공 차단 → Order가 v1·v2 읽음 → Payment v2 → v1 행 0건 → v1 읽기 제거). 확정 트랜잭션 결제 → 시도 잠금·재확인. 종결은 `OrderPaymentReviewClosed`.
  - 영향: **돈·보유권** — 실패 통지한 시도의 늦은 성공이 다른 시도의 성공처럼 `PAID`가 되면 이중 청구·잘못된 보유권(U1).
  - 재현: Payment 통합 — 확정 직전 시도를 실패 종료로 바꿈 → `OrderPaymentCompleted` 0건. 계약 테스트 — `admissionId` 누락 v2 거절, v1 견본 읽기.
- [ ] **PA-T1. DB CHECK가 PG 시각과 Payment 시각을 비교한다** ([결정 163](../../../docs/decisions.md#external-clock-principle), 플랫폼 T3 범위에 더함)
  - 어디서: V17·V19 `chk_refund_payment_confirmation`(`provider_refunded_at <= refund_confirmed_at`), V19·V21 `chk_refund_execution_success`(`cancellation_requested_at <= cancellation_succeeded_at`, `response_cancelled_at <= cancellation_succeeded_at`), V21 `chk_refund_execution_receipt`(`receipt_requested_at <= receipt_observed_at`).
  - 목표: R7.18. 이 비교를 지운 새 CHECK로 바꾸는 이행. PG 시각끼리 비교(`response_cancelled_at >= cancellation_requested_at`)는 남긴다.
  - 영향: **돈·보유권 지연** — PG 시계가 몇 초 빠르면 금융 확인 INSERT가 CHECK 위반으로 실패하고 조회가 반복돼 환불 완료·보유권 회수가 늦는다(코드 비교를 지워도 DB가 막음, 추정).
  - 재현: 통합 — PG 흉내 서버 시계 +5초에서 조회 완료·직접 성공 저장이 커밋되는지(지금 Red 예상).
- [ ] **MC-P10. Payment core가 PG 시각과 자기 시각을 비교해 근거를 거절한다** (결정 163, 플랫폼 T3 PG-*)
  - 어디서: `payment:core/domain/RefundExecution.java:163-164`(`requireReceipt`), `:224-225`(`confirmCancellationSuccess`), `:276`(`confirmRefund`), `payment:adapter/pg/PortOneRefundCancellationGateway.java:131`, #166 `recordLateCancellation`.
  - 목표: R7.18, P4.5-3. 값으로만 저장, 진위는 취소 ID·결제 ID·금액·상태.
  - 영향: **돈·보유권 지연** — 정상 성공·취소 근거가 `IllegalArgumentException`, 3시간 뒤 경보(추정, 미재현).
  - 재현: PA-T1과 같은 시험(단위: 고정 `Clock`).
- [ ] **MC-P9. 결정 133(형식 갖춘 거절 즉시 중단)이 구현되지 않았다** (옛 tasks:41)
  - 어디서: `payment:adapter/pg/PortOneRefundCancellationGateway.java`(분류), `payment:core/service/RequestRefundCancellation.java`(`recordRejection`), V24·V25 보류 사유 CHECK.
  - 목표: R7.8, P3.20-2. 형식 거절이면 첫 응답 뒤 `dispatch_state=HELD`, 보류 사유 `PG_REQUEST_REJECTED`·상태·`type` 저장. 분류표는 design adapter 메모.
  - 영향: **보유권·돈 지연** — 409 형식 거절이면 기한까지 약 180번 재송신, 그동안 보유권 정지·고객 `PROCESSING`, 실패 종결도 기한 뒤에야 가능.
  - 재현: 흉내 서버가 형식 갖춘 409 → 첫 응답 뒤 `HELD`, 재송신 0.
- [ ] **PA-T12. 운영자 실패 종결·실패 뒤 감시·늦은 취소 반영이 main에 없다** (플랫폼 T4, 결정 128·148·157, (1회차 지적 A2·B2, 판정 전))
  - 어디서: `RefundFailureClosed`·`CloseRefundFailure` 없음(참고 구현 `esd-worktrees/refund-failure-closure`, 커밋 안 됨), `RefundExecution.confirmFailure`가 조회 일정을 멈춤(V21 `chk_refund_execution_failure`가 `FAILED`의 일정 NULL 요구), 웹훅 `wake`는 `refundConfirmedAt is null`만.
  - 목표: R18·R19, design 상태 모델 6 #14~#17·7. `refund_failure_closure`·`refund_late_cancellation` 표, `FAILED` 실행의 실패 뒤 감시 일정 허용 CHECK, `kind=LATE_CANCELLATION` 목록·경보, 늦은 취소 반영 API(뒤 실행 있으면 409, 확인 뒤 후속 실행 요청은 실행 없이 기록 — 결정 179), 고객 재시도 사전 확인이 종결 실패를 받음.
  - 영향: **돈·보유권** — 실패 종결이 없어 송신이 멈춘 환불이 끝나지 않고, 늦은 취소가 생기면 고객이 돈과 게임을 함께 갖고 판매자가 과지급된다.
  - 재현: #166 `RefundFailureClosureIntegrationTest`(R1 Red→Green 포함), 늦은 취소 — 실패 종결 → 흉내 서버 콘솔 취소 → 조사 목록 → 반영 → `RefundLateCancellationConfirmed` 1건·재요청 무변화.
- [ ] **MC-P1. 환불 실행 금액 = 원결제액을 DB가 지키지 않는다** (I2)
  - 어디서: V14:26 `chk_refund_execution_price`(금액>0·KRW만), 일치는 `payment:core/domain/Payment.java:73`(`requireRefundRequest`)만.
  - 목표: `purchase_payment`에 `(payment_id, amount)` 유일 키 + `refund_execution(payment_id, amount)` 복합 FK.
  - 영향: **돈** — 코드 회귀 시 부분 환불 실행 행이 생길 수 있다.
  - 재현: 다른 금액으로 `refund_execution` 직접 INSERT가 실패하는지.
- [ ] **MC-P7. 확정된 결제에 만료가 와도 표시만 남고 지표·경보가 없다**
  - 어디서: `payment:core/service/ApplyOrderExpiry.java:44-47`, `PaymentOrderLifecycle.retainFinancialConflict`.
  - 목표: R11.7. 결제 `PAID` ∧ 생명주기 `EXPIRED` 건수 게이지 + 경보(표시 칸은 PA-T6에서 삭제). 결제 `FAILED` ∧ `EXPIRED`는 정상 경합이라 세지 않는다.
  - 영향: **돈** — 청구된 결제가 만료 주문에 붙은 건이 보이지 않는다(접수 필수 T13 뒤에는 불변식 위반 신호).
  - 재현: 단위 — 조합 생성 시 게이지 1.
- [ ] **PA-T14. 정산 환불 반영 응답을 받지 않고 세지 않는다** ([결정 158](../../../docs/decisions.md#forward-gap-counting), 플랫폼 T6, (1회차 지적 B4, 판정 전))
  - 어디서: 리스너 없음, `stove.settlement.v1` 토픽 없음(Settlement Outbox 꺼짐).
  - 목표: R20.1·20.2. 그룹 `payment-settlement-refund`, 실행·늦은 취소 기록에 응답 ID, 대기 게이지(건수·최장). `PAID` → Order 반영 응답은 M-PA-2((1회차 지적 A1, 판정 전)).
  - 영향: **돈** — 환불 조정이 DLT에 묻히면 판매자 과지급이 아무 데도 세어지지 않는다(U3).
  - 재현: Settlement 소비를 막고 금융 확인 → 게이지 1, 응답 주입 → 0.
- [ ] **PA-T13. Payment에 운영 재발행 경로가 없다** (플랫폼 T7, 결정 161)
  - 어디서: `common/messaging/.../ops/OutboxRepublishTargets.java:27-37`(Order·License만), Payment `application.yml`에 `republish-owner` 없음.
  - 목표: R20.4. Payment 현행·목표 이벤트 전부 허용 목록.
  - 영향: **돈·보유권** — `RefundPaymentConfirmed` 원본 소실 시 Order 환불 완료·Settlement 조정이 멈추고 복구 수단이 없다.
  - 재현: Payment `SENT` 행 재발행 API — 지금 400(현 동작 고정) → 확장 뒤 통과.
- [ ] **PA-T9. 귀속 미확정 실행이 진행 중 실행 가드에서 빠진다** (MC-P11과 같이)
  - 어디서: V14 `pending_payment_guard`·`pending_refund_guard`(`status='PENDING'`일 때만). 지금 귀속 미확정도 `PENDING`이라 막히지만, `CONFIRMED_UNATTRIBUTED`로 나누면 빠진다.
  - 목표: 생성 열을 `status IN ('PENDING','CONFIRMED_UNATTRIBUTED')`로. 금융 완료된 결제에 새 실행 접수 금지.
  - 영향: **돈** — 이행 순서를 놓치면 같은 결제에 두 번째 실행이 접수될 수 있다.
  - 재현: 이행 테스트 — 귀속 미확정 행이 있는 결제에 새 실행 INSERT 실패.

### 상태 표현(원칙 ④)·정본(원칙 ③)

- [ ] **MC-P8. 환불 실행 송신 쪽 상태가 네 칸의 NULL 조합이다** (P3.1-4)
  - 어디서: `payment:core/domain/RefundExecution.java:87-129`(`receiptCancellationId`·`activeCancellationHoldId`·`cancellationBalanceChangedAt`·`automaticCancellationStoppedAt`).
  - 목표: `dispatch_state` enum + CHECK(design 상태 모델 6), 실패 종결 대상 = `dispatch_state ∈ {HELD, BALANCE_BLOCKED, WINDOW_ENDED}`. 근거 칸(접수 응답·보류 기록)은 값으로 남긴다.
  - 영향: 없음(표현). 실패 종결 대상 판정이 이 조합을 읽는다.
  - 재현: 이행 테스트 — 조합별 옛 행 → `dispatch_state` 변환, CHECK 위반 0.
- [ ] **MC-P11. 귀속 미확정이 `PENDING` + `refundConfirmedAt`으로 숨어 있다** ([결정 164](../../../docs/decisions.md#attribution-in-payment))
  - 어디서: `RefundExecution.unattributed()`(`:40-42`), V19 `chk_refund_payment_confirmation`.
  - 목표: `status=CONFIRMED_UNATTRIBUTED`, `refund_confirmation_source` 삭제(PA-T4). 귀속 정본은 Payment(`status`·`refund_closure`).
  - 영향: 없음.
  - 재현: MC-P8과 같은 이행 테스트.
- [ ] **PA-T3. `RefundExecutionSucceeded`를 아직 발행한다** (결정 164, 플랫폼 T19, (1회차 지적 A4·B5, 판정 전))
  - 어디서: `payment:core/service/RecordRefundCancellationSuccess.java:46`, V19 `cancellation_success_event_id`·`uk_refund_execution_success`.
  - 목표: R7.13. 귀속 미확정 → `SUCCEEDED` 전이만 하고 이벤트 없음. Order 소비 제거(Order 스펙)가 먼저 배포된 뒤 발행 중단, P2.8 제거 절차. 옛 행 읽기 유지.
  - 영향: 없음(받는 쪽 반응 없음, Settlement는 소비 안 함).
  - 재현: 통합 — 귀속 미확정 뒤 직접 성공 → Outbox `RefundExecutionSucceeded` 0건, 실행 `SUCCEEDED`.
- [ ] **PA-T4. `RefundPaymentConfirmed.source`가 근거 종류와 귀속을 함께 말한다** (플랫폼 T19, SP-5, 소급 RC-8)
  - 어디서: `RecordRefundCancellationSuccess.java:40`(`PG_RESPONSE`), `RefundConfirmationOutbox`(`PG_QUERY_UNATTRIBUTED`), `RefundExecution.java:256,292`.
  - 목표: 칸 삭제를 새 `schemaVersion`으로(결정 170, P2.7-5).
  - 영향: 없음.
  - 재현: 계약 테스트 — v2 본문에 `source` 없음, v1 견본 읽기.
- [ ] **MC-P4. `last_error_code` 한 칸이 보류 사유·판매 제한 대기·오류 코드를 겸하고 CHECK가 없다** (소급 RC-2, notes E-1)
  - 어디서: `payment:core/domain/PaymentApproval.java:137`(`admit`이 지움), `:143`(`recordSaleRestriction`), `:185`(`stopPosts`), V29(열).
  - 목표: R12.4. `review_reason`(enum 8개 + CHECK, `REVIEW_REQUIRED`일 때 NOT NULL·끝 상태에서 보존) + `last_error_code`(오류 코드만). 판매 제한 대기 표현은 RC-2 답. 결정 144 규칙은 새 칸에 그대로.
  - 영향: 고객 안내·R11.8 자동 만료가 이 칸을 읽어, 잘못 쓰이면 안내가 틀림(돈 이동 없음).
  - 재현: 이행 테스트 — 옛 `last_error_code` → 새 칸, CHECK 위반 0.
- [ ] **MC-P5. `REVIEW_REQUIRED` 하나가 접수 전·후에 따라 다르게 동작한다** (소급 RC-13, notes E-2)
  - 어디서: `PaymentApproval.java:188-195`(`expireAfterVerifiedReady`의 `admittedAt == null`), `CloseApprovalReview.execute`(종결 결과 갈래).
  - 목표: RC-13 답(A 상태 둘 / B 직교 칸)에 따라. 어느 쪽이든 design 상태 모델 2의 `RV-전`·`RV-후` 열이 정본.
  - 영향: 없음(동작은 결정대로, 숨은 하위 상태).
  - 재현: 상태×트리거 표 대조 테스트(모든 쌍 허용·거절).
- [ ] **PA-T8. `post_stopped` 플래그가 상태와 겹친다**
  - 어디서: V29 `post_stopped`, `PaymentApproval.recordFailure`·`stopPosts`.
  - 목표: 송신 금지 = `status ∈ {REVIEW_REQUIRED, FAILURE_CONFIRMED}` ∨ 끝 상태로 판정하고 칸 삭제(또는 CHECK로 상태와 묶음). R21.2.
  - 영향: 없음.
  - 재현: 이행 테스트 — 모든 행에서 `post_stopped` = 상태 판정.
- [ ] **PA-T6. 주문 생명주기가 NULL 조합·플래그로 상태를 만들고, Order 불변식을 다시 검사한다**
  - 어디서: V31 `expiry_event_id`·`expired_at`·`financial_conflict`, `ck_payment_order_lifecycle_expiry`(`expired_at >= expires_at`), `payment:core/domain/PaymentOrderLifecycle.java:33·36·48`(`occurredAt + 30분` 재계산·대조).
  - 목표: design 상태 모델 3. `status`(`OPEN`·`EXPIRED`) + CHECK, `financial_conflict` 삭제(MC-P7 게이지), Order 시각끼리 CHECK 삭제, `expires_at`은 이벤트 값.
  - 영향: 만료 사실이 재계산 불일치로 DLT로 가면 Payment가 만료를 모른다(T12).
  - 재현: Order 기한 설정을 바꿔 만료 → Payment가 받아들이는지.
- [ ] **PA-T5. Payment가 결제 기한을 다시 계산하고 결제창을 자기 시계로 막는다** (플랫폼 T12, [결정 169](../../../docs/decisions.md#checkout-open-until-expiry-fact))
  - 어디서: `payment:core/service/ReadPaymentCheckout.java:50`, `PaymentApproval.java:82`(`expiresAt = orderOccurredAt + 30분`), `PaymentOrderLifecycle.java:33·36`.
  - 목표: R1.12·R4.8·R11.1. `OrderPlaced.expiresAt` 저장만, 결제창 409는 만료 사실 수신 뒤만.
  - 영향: Payment 서버 시계가 1분 빠르면 기한 안 결제창이 막힌다.
  - 재현: 아키텍처 — Payment 소스에 `ofMinutes(30)` 0건. 단위 — `Clock` +1분에서 29분 30초 주문 결제창 허용.
- [ ] **MC-P2. 확정 송신 기한·환불 재송신 기한(3시간)이 core 상수이고 adapter도 다시 계산한다** (P1.4-5, 결정 140, RC-14)
  - 어디서: `PaymentApproval.java:24-25,150-153,165`(`CONFIRM_WINDOW`), `RefundCancellationWindow.java:8`, `PortOnePaymentApprovalGateway.java:93`.
  - 목표: adapter 설정 한 곳, core는 `confirm_deadline`·`cancellation_resend_deadline`을 기록해 그 값으로만 판정. Order 쪽 재계산 제거는 T14.
  - 영향: 돈 없음. 공급자를 바꾸면 core 수정 필요.
  - 재현: 아키텍처 — core에 `ofHours(3)` 0건. 단위 — adapter 설정을 바꾸면 `canPost`·보류 경계가 따라옴.
- [ ] **PA-T7. 시도·조사 기록이 결제 삭제로 함께 지워질 수 있다**
  - 어디서: V29 `fk_payment_approval_payment ON DELETE CASCADE`.
  - 목표: RESTRICT(V38·V37과 같은 방침). 감사 기록이 조용히 사라지지 않게.
  - 영향: 없음(운영 삭제 경로 없음).
  - 재현: 결제 DELETE가 거절되는지.
- [ ] **PA-T10. 금융 확인 뒤 실패 근거 거절을 확인하는 테스트가 없다** (I18)
  - 어디서: `RefundExecution.confirmFailure`(금융 확인 있으면 무시), 테스트 없음.
  - 목표: PI12 확인 테스트.
  - 영향: 없음(지금 코드와 DB CHECK가 지킴).
  - 재현: 단위 — 금융 확인 뒤 실패 근거 → 상태 불변·`RefundExecutionFailed` 0건.

### 공급자 중립(원칙 ②)·테스트 편의(원칙 ⑦)

- [ ] **PA-T15. 시뮬레이터 전용 공급자 경로가 운영 코드에 있다** ([결정 162](../../../docs/decisions.md#single-reference-simulator), 플랫폼 T9·T13)
  - 어디서: `payment:adapter/pg/SimulatedPgHttpGateway.java`, `payment:config/PaymentConfiguration.java:65·69`(`matchIfMissing=true`로 `SIMULATED` 기본), `application.yml:74-76`(`PAYMENT_PG_PROVIDER:SIMULATED`, `MOCK_PG_*`), `payment:core/service/ResolvePayment.java:99-126`(`gateway.charge`, 접수 없는 확정).
  - 목표: R9.2·R22. PortOne adapter + 흉내 서버 하나, 모든 확정이 시도·접수 경로. 옛 `SIMULATED` 행 읽기(결정 104).
  - 영향: 모의 경로가 접수 없이 결과를 내 결정 153 대조와 어긋난다(T13).
  - 재현: Payment 통합 — 접수 없는 `OrderPaymentCompleted` 0건, 옛 `SIMULATED` 행 조회.
- [ ] **MC-P3. core 도메인이 공급자 상수 `"PORTONE_TEST"`를 비교한다** (플랫폼 T28)
  - 어디서: `Payment.java:60·66·93·148`, `PaymentApproval.java:72`, `RefundExecution.java:168·228·274`, 저장소·서비스 12곳(옛 tasks:58).
  - 목표: R22.1. core는 능력 값만, 공급자 키는 config.
  - 영향: 없음(구조).
  - 재현: T28 ArchUnit 규칙이 이 줄들을 잡는지.
- [ ] **PA-T17. 통지 경로·core 타입 이름이 공급자 이름을 담는다** (플랫폼 T28, 옛 tasks:59)
  - 어디서: `payment:api/controller/PortOneWebhookController.java:28`(`/api/v1/payments/webhooks/portone`), `core.domain.PortOneWebhookNotice`, `core.port.PortOneWebhookReceipts`·`PortOneWebhookVerifier`, `core.service.ReceivePortOneWebhook`.
  - 목표: R6.1·R22.3. 중립 이름·경로(가칭 `/api/v1/payments/provider-notifications`), 공급자 본문 해석·서명은 adapter. Gateway 라우트·edge·공급자 콘솔 통지 주소를 함께 바꾼다(Gateway 스펙과 맞춤).
  - 영향: 없음(이행 중 두 경로를 함께 둘지 배포 계획 필요).
  - 재현: ArchUnit + 통합 — 새 경로로 서명 통지 접수.
- [ ] **PA-T16. Payment가 새 결제에 PG 비용을 쓴다** ([결정 165](../../../docs/decisions.md#pg-fee-owner), 플랫폼 T17)
  - 어디서: `Payment.java:131-133`(`confirm`), `payment:core/domain/PgResult.java`의 `fee`, V12.
  - 목표: R9.3. 새 행 `pg_fee_*` NULL, core 계약에서 비용 제거, 옛 값 읽기. 칸 제거는 M-PA-6.
  - 영향: 없음(채우던 곳은 `SimulatedPgHttpGateway`뿐).
  - 재현: 단위 — 비용이 있는 응답으로 확정해도 칸 NULL.
- [ ] **PA-T2. 실패 시도 이력이 PG 실패 시각과 자기 시각을 비교한다** (결정 163)
  - 어디서: `Payment.java:59-62`(`requireFailedAttempt`의 `at.isBefore(input.failedAt())`).
  - 목표: R5.7. 비교 제거.
  - 영향: 고객 실패 이력 수집이 PG 시계가 앞서면 반복 실패(돈 없음).
  - 재현: 단위 — 실패 시각이 관측 시각 +5초인 근거가 저장되는지.
- [ ] **MC-P6. Order 승인 접수·실패 통지의 Order 장애를 PG 장애로 던진다**
  - 어디서: `payment:adapter/order/HttpOrderPaymentAdmissions.java:57·62`(`PgUnavailable`).
  - 목표: R4.17, P3.11-4. `OrderUnavailable`(가칭) 분류·지표.
  - 영향: 운영 — 지표·로그에서 Order 장애가 PG 장애로 보인다.
  - 재현: 단위 — Order 503 → 오류 분류 확인.

### 이월 항목 중 이 문서로 옮긴 표

- [x] **MC-P12. 승인 보류 사유 표를 Payment 모듈 설계로 옮긴다** — design "승인 보류 사유 표". 사유 칸 분리는 MC-P4.
- [ ] **MC-P15. 늦은 취소 반영 API와 확인 기록** (결정 157) — 구현은 PA-T12에 묶었다. "같은 결제의 새 실행이 진행 중일 때"는 [결정 179](../../../docs/decisions.md#late-cancellation-during-retry)로 정했다(R19.3·R19.6).

## 진행 중 / 남음

- [ ] [결정 필요 M-PA-1~M-PA-6] notes D의 답을 받은 뒤 해당 항목(R3.11·R20.3·R19.3·R17.7·R12.8·R9.3)을 목표 문장으로 바꾼다.
- [ ] 소급 후보 RC-2·RC-13 재질문(notes E) 뒤 MC-P4·MC-P5의 목표 모양 확정.
- [ ] [외부 대기] 실제 PG 오류(형식 거절·`INVALID_REQUEST` 등) 뒤 운영 재개를 실제 ADMIN 경로로 검증 — G7([결정 140](../../../docs/decisions.md#defer-real-pg))
- [ ] `INVALID_REQUEST` 정정 절차를 운영 절차서에 적는다(결정 133): 같은 시도 본문은 고치지 않고 원인을 고친 뒤 실패 종결(R18) → 고객 재신청(새 `attemptId`·본문·기한) — G2
- [ ] 결함 후보 재현(2026-11-08 HTTP 오류 조사): ① 환불 409 본문이 깨지거나 `type`이 없으면 `IllegalArgumentException`이 새어 그 회 조회를 건너뛰고 첫 송신이 Kafka 재전달이 된다 ② 승인 조회가 계속 실패하면 `confirm_deadline`이 지나도 `CONFIRM_WINDOW_EXPIRED`에 오르지 않는다 ③ 환불 저장 본문이 현재 설정과 달라지면 `requireBody` 예외로 보류 기록 없이 영원히 보내지 못한다. 재현되면 결함 대장 — G2
- [ ] [외부 대기] 502 `PG_PROVIDER` 분류와 실제 공급자 환불 오류 타입 확인(결정 133) — G7
- [ ] 운영 조사 목록의 조사 흔적([결정 131](../../../docs/decisions.md#investigation-trail-no-assignee)): 환불 지연·승인 보류·(목표)연결되지 않은 청구 목록 항목에 `investigationCount`·`lastInvestigatedBy`·`lastInvestigatedAt`(R8.9) — G2
- [ ] 기한 뒤 미확정 환불과 실패 뒤 감시의 조회 간격(결정 122 모양, 결정 128 "낮은 빈도"): `min(10분, max(60초, 경과))`, 환불 전용 설정 키, 통지·종결 API의 직접 재조회는 간격과 무관(R19.1) — G2. 구현은 PA-T12와 같이.
- [ ] 처리량 증가 시 첫 환불 송신을 별도 DB 송신 작업자로 옮기는 B안(전환 지표: 접수 지연·PG p95/p99·소비 lag·최장 대기) — G6
- [ ] [외부 대기] 실제 PG로 승인·취소 다시 확인(전용 테스트 가맹점 필요, `PAY_PENDING`의 뜻 포함) — G7([결정 140](../../../docs/decisions.md#defer-real-pg)), 반복 검증은 흉내 서버 여정
- [ ] [외부 대기] 실제 TEST에서 실패 시도 거래 이력 필드 충족 여부 — G7
- [ ] [외부 대기] 공급자 보장 기한 값(확정 송신·환불 재송신 3시간 근거, 끝난 결제 감시 `watch_until`) 공급자 문서 확인 — G7(결정 154)
- [ ] 기준 시뮬레이터 확장([결정 141](../../../docs/decisions.md#portone-fake-as-reference-simulator)): 흉내 서버에 응답 지연·5xx·연결 끊김·취소 대기 유지와 뒤늦은 확정·통지 중복/역순/누락, 콘솔 승인(MC-P14 재현용)·시계 어긋남(PA-T1 재현용). 운영 코드는 바꾸지 않는다 — G3
- [x] (옛 tasks:46 `[결정 필요]` Order 시도의 `reconciliationRequired` 해제) — [결정 164](../../../docs/decisions.md#attribution-in-payment)로 닫힘: Order 시도는 귀속을 갖지 않는다. Payment 쪽은 PA-T3·R15.9.
- [x] (옛 tasks:47 결정 128 "기존 `RefundExecutionFailed`로 잇는다") — 결정 147·148로 전용 이벤트 `RefundFailureClosed`. PA-T12·R18(소급 RC-7, 문서 정정).
- [x] (옛 tasks:58~61 PG 계약 중립화 4건) — MC-P2·MC-P3·PA-T17·T28로 옮김.

## 범위 밖(후속)

- 실제 PG 연동 확인 전체 — G7.
- 운영 결제·실제 PG 지급 자료·은행 입금·판매자 송금(별도 계약·설정·운영 검증).
- 다중 PG·다중 작업자 최적화·처리량 향상·분산 임대/ShedLock.
- 카드 정보 저장·결제 수단 다양화·쿠폰/다중 통화/무료 상품 지급·복수 상품/수량.
- 전용 고객 결제 화면.
