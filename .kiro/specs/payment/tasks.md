# 작업: Payment

근거 수치·원문은 [근거 색인](../../../docs/evidence.md)을 따른다.

## 완료

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

## 진행 중 / 남음

- [x] G1 종료 조건 대조 — 금융 보류 건 고객 안내([결정 123](../../../docs/decisions.md#approval-review-customer-notice)): 결제 상세·승인 조회 응답에 청구 여부별 `reviewNotice {code, message, since}`(`CHARGED`·`CHARGE_CANCELLED`·`CHARGE_UNCONFIRMED`·`NOT_CHARGED`, 종결 뒤 `CLOSED_CHARGE_CANCELLED`·`CLOSED_NOT_CHARGED`)를 더했다(R14). `PG_INVALID_EVIDENCE`는 `CHARGE_UNCONFIRMED`로 고정하고 나머지 사유는 마지막 PG 관측으로 정한다(결정 132). 알려진 보류 사유 8개를 모두 묶음에 매핑하고, 사유 목록과 매핑이 어긋나면 `ApprovalReviewNoticeTest`가 실패한다. 결제창 409 메시지를 한국어로 바꿨다(보류면 같은 안내 문구). 원래 사유 코드는 노출하지 않고, Order 계약은 바꾸지 않았다 — G1, 2026-11-08 PR #155
- [ ] 결제창 이탈 후 재진입의 Order 결과/다음 행동 검증([결정 120](../../../docs/decisions.md#g1-reentry-scope)): 실제 TEST로는 시도 실패(결제창 닫기)→같은 주문 재결제와 이탈→30분 자연 만료를 확인했다. 흉내 서버 실제 스택 여정에 30분 안 위험 조합(승인 요청 미도달 뒤 재결제, 처리 중 결제창 재요청 거절, 처리 중 새 주문 이중 구매, PAY_PENDING 유지, confirm 400/403 보류)을 기존 야간 작업으로 넣는다. 기한을 넘는 조합(기한 뒤 FAILED, 기한 안 접수 뒤 늦은 PAID, 접수 전 PAY_PENDING 만료)은 31분 대기 한 번으로 묶은 별도 야간 작업으로 넣는다. 3시간 조합은 통합 테스트로 인정하되, 기존 `PaymentApprovalIntegrationTest:220-227`은 PG READY만 확인하므로 PAY_PENDING으로 3시간을 넘기는 통합 테스트를 새로 더한다 — G1
  - 진행(2026-11-08): 30분 안 조합 c1~c5를 `tools/verification/verify-approval-journeys.py`에 더했다(기존 야간 작업). 승인 요청 미도달 뒤 같은 주문 새 시도 PAID(실패 이력 0), 처리 중 checkout 409 `APPROVAL_IN_PROGRESS`, PAY_PENDING 유지(재송신 0·조회만), 처리 중 새 주문 이중 구매 뒤 두 주문 PAID·두 구매분 보관함(등록 409 `PRODUCT_ALREADY_OWNED`), 처리 중 FAILED 뒤 주문 PENDING·같은 주문 재결제, confirm 400/403 `PG_REQUEST_REJECTED` 보류·READY 근거 종결. 기한을 넘는 두 조합(기한 뒤 FAILED → 주문·결제 `EXPIRED`, 기한 안 접수 뒤 늦은 PAID → 원래 주문 PAID)은 새 `tools/verification/verify-expiry-journeys.py`와 별도 야간 작업(31분 대기, 제한 60분)에 넣었다([근거 색인](../../../docs/evidence.md) 2026-11-08). 남음: 접수 전 PAY_PENDING 만료(D-044, [결정 126](../../../docs/decisions.md#pre-admission-pending-expiry) 수정 PR #145 뒤 기한 여정에 더함)
  - 3시간 조합(2026-11-08): PAY_PENDING으로 3시간을 넘기는 통합 테스트를 결정 124 구현과 함께 더했다(`PaymentApprovalIntegrationTest` `anAdmittedPayPendingApprovalPastTheConfirmWindow…`, READY 3시간 경계 테스트 기대값 갱신).
- [x] confirm 기한이 지난 접수 승인의 보류([결정 124](../../../docs/decisions.md#confirm-window-expired-hold)): `ADMITTED`이고 `firstPreparedAt + 3시간`이 지났으며 PG가 `PENDING`·`READY`인 승인을, 조회 직후 `CONFIRM_WINDOW_EXPIRED`로 `REVIEW_REQUIRED`에 올린다. 게이지 사유 목록·경보 테스트·R4.6·R12.4 문구를 갱신하고, PAY_PENDING 3시간 통합 테스트를 더했다. 고객 안내(결정 123 구현)는 위 G1 항목에 남음(PG `PENDING`이면 `CHARGE_UNCONFIRMED`, `READY`면 `NOT_CHARGED`) — G1, 2026-11-08 PR #152
- [ ] [결정 필요] 같은 공급자 거래/비용·지급 자료로 Settlement까지 연결, 미확정 비용 모델 확정 — G3 ([계획](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/platform-generalization-plan.md))
- [ ] [외부 대기] 실제 PG 오류(POST 403·정확한 400 등) 뒤 운영 재개를 실제 ADMIN 경로로 검증 — G2 후속
- [ ] [결정 필요] 나머지 HTTP 오류(5xx·일반 409·기타 400)의 업무 분류와 `INVALID_REQUEST` 본문 정정 계약 — G2 후속
- [ ] 장기 미확정·귀속 미확정 환불의 ADMIN 종결([결정 127](../../../docs/decisions.md#refund-delay-closure)): 귀속 미확정(`PG_QUERY_UNATTRIBUTED`) 실행도 ADMIN 목록에서 찾게 하고, `POST /api/v1/admin/refund-delays/{attemptId}/closure`(가칭)를 둔다. 결정 115와 같은 구조(작업자와 같은 lease·트랜잭션 밖 PG 재조회·별도 잠금 트랜잭션)로, 같은 원결제의 전액 취소가 확인될 때만 닫는다. 미완료 건은 기존 완료 경로로 즉시 완료, 완료된 귀속 미확정 건은 판정만 기록한다. 귀속 판정·ADMIN·근거·시각을 저장하고, PG가 아직 결제 상태면 409. PG 취소는 실행하지 않는다. Order 조사 표시 해제 방식은 기존 이벤트 계약을 확인해 정하고, 운영 절차서에 "콘솔 취소 → 종결"을 적는다 — G2
- [ ] PG가 취소를 거절했거나 끝내 확정되지 않은 환불의 ADMIN 실패 종결([결정 128](../../../docs/decisions.md#refund-failure-closure)): 자동 POST가 멈춘 실행(403·400 중단, 잔액 변화 차단, 3시간 창 종료)에 `POST /api/v1/admin/refund-delays/{attemptId}/failure-closure`(가칭)를 둔다. 결정 115·127과 같은 구조로, 재조회 결과가 원결제 `PAID`·취소액 0·진행 중이거나 성공한 취소 없음일 때만 닫고, `REQUESTED` 취소나 일부 취소가 있으면 거절한다. 근거를 저장하고 기존 `RefundExecutionFailed`로 복구·재시도에 잇는다. 고객 재시도 사전 확인(`RequestRefundCancellation`의 이전 실패 확인·preflight)이 이 종결 실패를 받도록 고친다. 실패 뒤에도 낮은 빈도로 PG를 조회해 늦은 전액 취소는 조사·경보로 올리고 보유권·상태는 자동으로 바꾸지 않는다(지금은 실패 확정 때 조회가 멈춤). 운영 절차서에 순서를 적는다 — G2
- [ ] [결정 필요] 장기 미확정 환불의 나머지: 담당 배정, 귀속 미확정 미종결 건의 경보와 3시간 뒤 조회 간격 — G2 후속
- [ ] 처리량 증가 시 첫 환불 송신을 별도 DB 송신 작업자로 옮기는 B안(전환 판단 지표: 접수 지연·PG p95/p99·소비 lag·최장 대기) — G6. 결정 29 하위 "첫 취소 송신"에서 처리량 고도화 계획으로 정했고, 전환은 지표가 기준을 넘을 때 한다
- [ ] 새 환불 이벤트·재개 작업의 Tempo 추적 전체 연결 검증 — 후속
- [ ] [외부 대기] 실제 PortOne·PG로 승인·취소 다시 확인: 토스페이 공용 예제 키가 2026-11-07 거절돼 우리 전용 테스트 가맹점이 필요. 방법은 가맹점 준비 뒤 정함 — [결정 110·111](../../../docs/decisions.md#portone-fake-journey). 반복 검증은 PortOne 흉내 서버 여정(`verify-refund-settlement.py`)이 맡음 함께 확인할 것: PortOne `PAY_PENDING`이 "우리 confirm을 기다림"을 뜻한다면 지금 코드는 READY에서만 confirm을 보내므로(`ResolvePaymentApproval.java:147`) 그 건은 처음부터 멈춘다(2026-11-08 조사, 저장소 근거 없는 추정).
- [ ] [외부 대기] 실제 TEST에서 실패 시도 거래 이력 필드 충족 여부 확인 — G1 재결제 여정과 함께

## 범위 밖(후속)

- LIVE 결제·실제 PG 지급 자료·은행 입금·판매자 송금(별도 계약·설정·운영 검증).
- 다중 PG·다중 워커 최적화·처리량 향상·분산 임대/ShedLock.
- 카드 정보 저장·결제 수단 다양화·쿠폰/다중 통화/무료 상품 지급·복수 상품/수량.
- 전용 고객 결제 화면(현재 백엔드 범위 밖).
