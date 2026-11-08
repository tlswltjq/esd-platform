# 작업: Order

## 완료 (검증 결과는 [근거 색인](../../../docs/evidence.md))

- [x] 단일 상품 주문 생성, Catalog 내부 근거 조회·기대 버전/금액 비교, 스냅샷(정가·할인 0·청구액) 고정과 `OrderPlaced` Outbox 원자 저장 — 2026-10-16
- [x] `(buyerSubject, requestId)` 유일 제약·동일 요청 재조회·동시 생성 한 건 확정 — 2026-10-16
- [x] 내 주문 목록/상세 페이지, JWT·MEMBER·본인 조회·타인 404·소유자/상태 위조 무효 — 2026-10-16
- [x] 결제 결과 반영: `OrderPaymentCompleted`/`Failed` 연결·금액 검증·행 잠금·Inbox·최종 상태, 재전달 무효과·동시 상충 결과 한 번 확정 — 2026-10-16
- [x] 구 견적·항목·Redis·ShedLock·만료 Sweeper·취소 API 제거, V10 신규 테이블·구 자료 보존·전환 SQL — 2026-10-16
- [x] 최초 `PAID`에 `OrderPaid` Outbox 기록(License 연계), 재전달·FAILED 비발행 — 2026-10-17
- [x] (결정 89로 폐지, V28) 중복 구매 제한: 같은 상품 `PENDING`→409 `PURCHASE_IN_PROGRESS`·`PAID`→409 `PRODUCT_ALREADY_PURCHASED`, V11 유일 제약 — 2026-10-18
- [x] 환불 지급 확인·정지 완료 대조·첫 실행 시도·`RefundExecutionRequested`, 금융 완료(조회 A·직접 응답 A)·늦은 성공 역순 처리, 검증된 실패→실제 복구→FAILED, 복구 뒤 명시적 재시도 — 2026-10-27
- [x] 고객 환불 접수/조회 API(4시간 자격 보존·중복 단일 접수), ADMIN 목록/상세/판정(먼저 커밋 확정 A), 고객 명시적 재시도 HTTP — 2026-10-28
- [x] 실제 PortOne TEST 자동 전액 환불 1건(지급→접수→실제 정지→PG 전액 취소→COMPLETED·신규 Download 403) — 2026-10-28
- [x] 반려 뒤 고객 재심사(7일·사유 필수·횟수 무제한·회차 이력), 잘못된 승인 중단·새 심사 복귀(V22), 장기 미확정 지연 안내·준비 이벤트 보관(V23) — 2026-10-29
- [x] 결제 생명주기: `PAYMENT_PROCESSING`/`EXPIRED`, `createdAt+30분` 고정 기한(저장하지 않고 `Order.getExpiresAt()`가 매번 계산), 승인 진행 확정 내부 API·불변 영수증·같은 주문 잠금·만료 거절 커밋, 실패 종료 영수증(V25)·자동 만료 스캔(V26) — 2026-10-29
- [x] `OrderExpired` 발행: 자동 스캔·기한 후 접수 거절·실패 종료의 원자 발행과 Payment 전달, 역순·중복 처리 — 2026-10-29
- [x] 환불 금액 상세(amountDetails) A안: 주문·승인 PG 실결제/할인·확인 취소/포인트·미확인 목록, Order V27 — 2026-10-30
- [x] 실제 ADMIN 수동 심사 PG 환불 1건(할인 거래, 취소 총액·보고 포인트 상세·결제수단 반환액 NULL) — 2026-10-30
- [x] 공개 상세 B·판매 상태: `publicDetailAllowed` false 신규 주문 404, 관리자 BLOCKED 새 승인 접수 차단, 판매자 STOPPED가 기존 주문·기한 미변경 — 2026-11-02
- [x] [결정 115](../../../docs/decisions.md#approval-review-closure-outcome): 이미 `EXPIRED`인 주문에 늦게 온 운영자 종결 실패(`REVIEW_CLOSED`)는 만료를 유지하고 결과 식별을 따로 기록(V31). 같은 재전달만 무효과, 바뀐 재전달·eventId 재사용·기록 없는 Inbox 중복은 충돌(재시도·DLT), 그 밖의 결과는 충돌 유지 — 2026-11-07

## 진행 중 / 남음 (일반화 공백)

- [ ] G1 종료 검증([결정 119](../../../docs/decisions.md#g1-exit-verification)): PortOne 흉내 서버(`tools/verification/support/portone_fake.py`)에 FAILED 시도 이력·PAY_PENDING·승인 근거 없는 PAID 생성을 검증 도구 쪽에만 더한다. 그 서버로 11개 서비스 실제 스택 여정을 만들어 시도 실패 뒤 같은 주문 재결제, `PAID_WITHOUT_ADMISSION` 보류 → 콘솔 취소 → `closure` 종결을 검증한다. 남은 이탈/재진입 조합은 [결정 120](../../../docs/decisions.md#g1-reentry-scope)의 범위로 Payment 항목에서 더한다. 금융 보류 건의 다음 행동과 `EXPIRED` 주문에 승인 근거 없이 도착한 성공(`PAID_WITHOUT_ADMISSION`)의 운영 대응은 운영자 조사·PG 재조회 근거 종결로 정했다([결정 114](../../../docs/decisions.md#approval-review-hold-closure)·[115](../../../docs/decisions.md#approval-review-closure-outcome)). 시스템 자동 취소는 지표로 빈도를 본 뒤 다시 정한다 — G1
  - 진행(2026-11-08): 흉내 서버에 시도별 이력과 콘솔 전용 제어(`fail-attempt`·`arm-confirm`·`settle`·`console-approve`·`console-cancel`)를 더했다. `tools/verification/verify-approval-journeys.py`가 승인 근거 없는 PAID 보류 → 콘솔 취소 → `closure` → 주문 `FAILED`·새 주문, 그 새 주문의 결제창 실패·confirm 실패 뒤 같은 주문 재결제 PAID·License를 검증하고 야간 CI에서 돈다([근거 색인](../../../docs/evidence.md) 2026-11-08). 이탈/재진입 조합은 결정 120 범위로 30분 안 조합과 기한을 넘는 두 조합을 더했다(Payment 항목 진행 참고). 남음: 접수 전 PAY_PENDING 만료(D-044)와 3시간 조합은 Payment 항목에서 한다.
- [x] G2 지급 확인 누락의 Order 측 감지([결정 125](../../../docs/decisions.md#license-grant-wait-detection)): V32 진입 시각(옛 행 호환 테스트), 게이지 `stove.order.refund.license.grant.delayed`·경보 `RefundLicenseGrantsDelayed`, ADMIN 목록·상세 `licenseGrantWaitStartedAt`, 고객 `processingNotice`(`LICENSE_GRANT_CONFIRMATION_DELAYED`). 복구는 결정 37 절차 — G2, [PR #151](https://github.com/tlswltjq/esd-platform/pull/151)
- [x] 환불 후 재구매(P4): 구매 슬롯·유일 제약 제거(판매 금지만 거절), 환불 완료 시 `PurchaseRefunded` 발행, 복구의 보관함 반환을 환불 응답에 표시 — [결정 89~98](../../../docs/decisions.md#purchase-blocked-only-by-sale-ban)
- [ ] [외부 대기] 재심사·잘못된 승인 중단의 실제 사용자 흐름: 계약·API·DB/Kafka 검증은 완료. 실제 ADMIN 계정·운영 화면·실제 PG를 잇는 전체 여정은 남음 — G2 후속
- [x] 원본이 사라진 지급·지급 확인 이벤트의 같은 ID 재발행([결정 129](../../../docs/decisions.md#orphan-license-grant-republish)): 이벤트를 소유한 모듈(License `LicenseGranted`, Order `OrderPaid`)의 Outbox 운영 API에 `SENT` 행 재발행(예: `POST /api/v1/ops/outbox/{eventId}/republish`)을 더한다. ADMIN만, 한 건씩, 실행자·시각·사유 기록, 원래 `event_id`·본문·키 유지, 새 ID 없음. 이미 처리된 이벤트의 재발행이 받는 쪽 Inbox에서 효과가 없음과, 미처리 건이 `PENDING_LICENSE_GRANT`를 정지 요청으로 잇는 것을 테스트한다. 절차서의 "원본 없음"을 "Outbox 행도 없음"으로 좁히고 재발행 전 확인 순서를 적는다 — G2, PR_LINK
  - 완료(2026-11-08): 공통 `OutboxRepublishController`·`OutboxRepublishService`(ops 스위치 아래, `stove.messaging.ops.republish-enabled=true`인 Order·License만), 허용 목록 `OutboxRepublishTargets` 한 곳, 기록 표 `outbox_republish`(Order V33·License V11, 새 표만). 없는 행·`SENT` 아닌 행 404, 허용 목록 밖 타입·사유 없음 400. Gateway 미노출·ADMIN 전용은 기존 ops 규칙 그대로
- [ ] G2 정지·복구·승인 정정 대기 환불의 유실 감지·같은 ID 재발행([결정 125](../../../docs/decisions.md#license-grant-wait-detection)·[결정 129](../../../docs/decisions.md#orphan-license-grant-republish)의 범위 확장, 새 정책 없음): Order는 환불이 `PENDING_LICENSE_SUSPENSION`·`PENDING_LICENSE_RESTORATION`·`PENDING_APPROVAL_STOP`에 들어간 시각을 저장한다(새 이행·옛 행 호환 테스트, 진입 경로는 지급 확인·자동 접수·심사 승인·재심사 승인·재시도·확정 실패·승인 중단 전부). 상태별 10분 초과 건수를 결정 125 게이지에 `status` 라벨로 더하고 경보를 같은 규칙으로 넓힌다. ADMIN 환불 목록·상세에 진입 시각을 보이고, 같은 경계를 넘은 고객 조회에 기존 지연 안내 모양의 `processingNotice`를 붙인다(`PENDING_LICENSE_RESTORATION`은 PG 실패가 확정된 상태라 문구를 상태에 맞게 고른다). 결정 129의 `SENT` 재발행 대상에 `LicenseSuspensionRequested`·`LicenseRestorationRequested`·`LicenseApprovalCorrectionRequested`(Order 소유)와 `LicenseSuspended`·`LicenseRestored`·`LicenseApprovalCorrected`(License 소유)를 더한다. License는 이미 처리한 요청을 다시 받아도 결과 이벤트를 다시 내지 않으므로, 결과 이벤트 유실은 License 쪽 Outbox 행을 재발행해야 한다. 이미 처리된 재발행이 받는 쪽 Inbox에서 효과가 없음과, 미처리 `LicenseSuspended`가 `PENDING_LICENSE_SUSPENSION`을 PG 실행 요청으로, `LicenseRestored`가 `PENDING_LICENSE_RESTORATION`을 `FAILED`로 잇는 것을 테스트한다. 절차서 `license-event-recovery-runbook.md`에 여섯 이벤트의 DEAD·DLT·`SENT` 행과 재발행 전 확인 순서(Outbox `SENT`·상대 Inbox 없음·Order 환불이 그 요청 ID의 대기 상태에 있음)를 더한다 — G2
- [x] 환불 자격 시간 역전의 테스트·문구 고정(2026-11-08 Q8 조사, 새 정책 없음): `RefundPolicyTest`에 "결제 확정보다 1마이크로초 앞선 접수 → 자동 자격"을 더하고, design에 "확정 시각보다 이른 접수도 자격 안"을 적는다. 접수 시각은 잠금 전 요청 수신 시각이라 스펙 문장을 "요청 수신 시각"으로 맞추고, tasks의 "`createdAt+30분` 고정 기한"은 저장이 아니라 매번 계산이라는 점을 바로잡는다 — G2, [PR #159](https://github.com/tlswltjq/esd-platform/pull/159)
- [x] 실패한 환불의 고객 안내([결정 134](../../../docs/decisions.md#refund-failure-customer-notice)): 고객 환불 응답(`FAILED`)에 고객용 실패 사유 코드(저장된 `RefundAttempt.failureReasonCode`에서 옮김, 내부 PG 오류 원문 비노출)와 "다시 신청하면 환불됩니다" 안내를 넣는다. 새 시도는 지금처럼 고객 재신청(`POST /api/v1/orders/{orderNo}/refund/retries`)으로만 만든다. 운영 절차서에 "환불해 줄 건이면 실패 종결보다 콘솔 취소 → 결정 127 종결을 먼저"를 적는다. OpenAPI 스냅샷을 갱신한다 — G2, [PR #159](https://github.com/tlswltjq/esd-platform/pull/159). 응답 `failureNotice`(`PG_CANCELLATION_FAILED` → `PAYMENT_NOT_CANCELLED`). 절차서 문장은 main에 환불 절차서가 없어 결정 127 구현 [PR #157](https://github.com/tlswltjq/esd-platform/pull/157)의 `docs/runbooks/refund-delay-closure.md`에 둔다(병합 대기)
- [x] 환불 조사 이후 조치·종결의 정책 결정(구현은 Payment·Order 각 항목): 공통 종결은 PG 재조회 근거의 Payment ADMIN 종결로 정했다([결정 127](../../../docs/decisions.md#refund-delay-closure), 구현은 Payment 항목). PG가 취소를 거절한 건의 실패 종결도 Payment ADMIN 실패 종결로 정했다([결정 128](../../../docs/decisions.md#refund-failure-closure), 종결 뒤에는 기존 보유권 복구·고객 재시도를 쓴다). 원본 이벤트가 Kafka에서 사라진 `PENDING_LICENSE_GRANT`는 원래 Outbox 이벤트의 같은 ID 재발행으로 잇기로 정했다([결정 129](../../../docs/decisions.md#orphan-license-grant-republish), 구현은 아래 항목). 정지·복구·승인 정정 이벤트 유실은 결정 125·129의 이유가 그대로 맞아 새 정책 없이 범위를 넓혀 구현한다(아래 항목). 담당 배정은 두지 않고 Payment 조사 목록에 마지막 조사자·시각만 보인다([결정 131](../../../docs/decisions.md#investigation-trail-no-assignee)). 원래 3시간 경계 유지, 시간만으로 실패/복구/새 금융 동작 추가 금지 — 2026-11-08
- [ ] 새 환불 흐름의 Tempo 전체 연결, 실제 PG 오류·응답 유실 뒤 환불 전체 검증(현재는 루프백 계약 픽스처) — 후속 검증

## 범위 밖(후속)

- 장바구니·복수 상품·수량 선택·선물·쿠폰·할인 조합·다중 통화·무료 상품 지급. 구매→보관함→사용/등록 흐름은 제품 확장 제안이며 현재 구매 제한·자동 지급·환불 계약을 바꾸지 않음.
- 부분 환불·다중 PG·조사 담당 배정([결정 131](../../../docs/decisions.md#investigation-trail-no-assignee))·추가 알림.
- 기한 정책 변경의 과거 주문 적용([Q9](../../../docs/domain/open-questions.md#q9)): 자동 환불 4시간(`RefundPolicy`)·주문 30분(`Order.getExpiresAt`)·재심사 7일(`RefundReviewRound.reconsiderationDeadline`)은 코드 상수다. 접수된 환불의 자동 자격만 `automatic_refund_eligible`로 행에 고정되고, 아직 접수하지 않은 주문·`PENDING` 주문·반려된 회차는 배포한 새 값으로 계산된다. 기한을 바꿀 계획이 없고(4시간은 사업 근거 없는 초기값, 결정 29) G1·G2 완료 조건이 아니라 범위 밖으로 둔다. 기한 값을 바꾸는 첫 PR에서 적용 기준(새 주문부터 / 배포 시점부터 전부 / 고객에게 유리한 쪽)을 사용자에게 묻는다(2026-11-08 조사).
- (해소, 2026-11-08) 접수 시각이 결제 확정 시각보다 앞서는 경우: 결제 확정 시각은 Payment 서버 시각이고 환불은 Order가 `PAID`일 때만 접수하므로, 역전은 서비스 간 시계 차이로만 생긴다. 결정 29 하위 "기한 시작: Payment 최초 확정"·"기한 판정: 접수 시각 고정"에 따라 4시간 안으로 보고 자동 환불한다(새 정책 없음). `RefundPolicyTest`로 고정했다([PR #159](https://github.com/tlswltjq/esd-platform/pull/159)).
- 주문 전용 지표·전용 운영 화면·성능/처리량 고도화.
