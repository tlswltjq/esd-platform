# 작업: Platform (모듈을 넘는 계약)

> [결정 150](../../../docs/decisions.md#spec-foundation)·[결정 151](../../../docs/decisions.md#platform-spec). 스펙 작성 항목과, 목표 계약과 코드가 다른 모듈 간 항목을 둔다.

## 스펙 작성

- [x] 평가 방법·완료 조건 결정([결정 152](../../../docs/decisions.md#spec-evaluation))
- [ ] P1~P4 요구사항과 설계 작성(P3에 흐름 단계별 실패 표) — 초안 5개 통합본(2026-11-09) 검토 대기
- [ ] 부하·장애 실험으로 기준선을 잰 뒤 P5 품질 시나리오를 더하고 다시 평가
- [ ] 정방향 응답 쌍의 경보 기준 시간을 G6 실험 측정값으로 쌍마다 확정(결정 159, P4.3-4) — G7 완료 조건
- [ ] `docs/domain/workflows.md`의 돈 불변식(I1~I11·U1~U6)과 이벤트 뜻을 이 스펙으로 옮기고 `docs/`에는 설명과 링크만 남김 — 낡은 서술도 함께 정리: `workflows.md:43-56`("방문자" 절의 `contentVersion` 비교·재시작 서술, 실제는 `catalogVersion`·자동 재시도), `docs/event-ordering.md:236`(구 계약 `ProductChangedEvent`·`projectionVersion` 예시)
- [ ] 모듈 스펙 11개가 이벤트·흐름·불변식을 다시 적지 않고 이 스펙을 링크하게 정리 — 한 모듈 안에서 끝나는 코드 차이(`MC` 번호)를 각 모듈 `tasks.md`로 옮김
- [ ] `[결정 필요]` PD-1~PD-20을 한 건씩 묻고 결정 번호로 바꿈 — PD-1 → 결정 153, PD-2 → 결정 154, PD-3 → 결정 155, PD-4 → 결정 156, PD-5 → 결정 157, PD-6 → 결정 158·159, PD-7 → 결정 158 적용, PD-8 → 결정 160, PD-9 → 결정 161, PD-10 → 결정 162, PD-11 → 결정 163, PD-12 → 결정 164, PD-13 → 결정 165, PD-14 → 결정 166, PD-15 → 결정 167, PD-16 → 결정 168, PD-17 → 결정 169, PD-18 → 결정 170 완료(돈·보유권 영향 순: PD-1·PD-2·PD-3 먼저)
- [ ] 소급 후보 RC-1~RC-19(결정 147 원칙에 어긋난 옛 결정)를 한 건씩 다시 물음. 다시 고르기 전까지 기존 결정 유효
- [ ] 독립 아키텍처 평가 → 수정 → 재평가

## 코드와 맞출 것

모듈 둘 이상에 걸친 차이만 둔다. 돈·보유권 영향이 큰 순서다. 형식: 무엇이 · 어디서(`파일:줄`) · 목표 · 영향 · 재현 테스트 아이디어. 경로 줄임은 [requirements.md](requirements.md)와 같다(`<모듈>:` = `apps/<모듈>/src/main/java/com/stove/<모듈>/`). 줄 번호는 2026-11-09 `docs/spec-foundation` 기준이다.

- [ ] **T1. U1 — Order가 결과를 승인 진행 영수증(`admissionId`)과 대조하지 않는다** ([결정 153](../../../docs/decisions.md#late-success-rejection))
  - 어디서: `order:core/domain/Order.java:152-172`(`PAYMENT_PROCESSING`일 때만 `paymentId` 대조 `:163-164`, `PENDING`이면 그대로 `PAID`), `order:core/service/ApplyPaymentResult.java:50-57`(`PAID`면 `OrderPaid` Outbox), `common/kafka/.../contract/payload/OrderPaymentCompletedEvent.java:11-12`(`admissionId` 칸 없음), `payment/db/V11__purchase_payment.sql:29`(`uk_purchase_payment_order` — 주문당 `paymentId` 고정), Order V10(`purchase_order`에 반영 영수증 열 없음). Payment 쪽: `payment:adapter/pg/AdmittedPortOnePgGateway.java:21-28`가 시도를 잠금 밖에서만 확인, `payment:core/service/ResolvePayment.java:103,112-119`의 확정 트랜잭션은 결제만 잠그고 시도가 여전히 접수된 현재 시도인지 다시 보지 않는다.
  - 목표: P3.12-1·P3.14-1·P4.2-5, I12·I15. `OrderPaymentCompleted.admissionId`(필수), `OrderPaymentFailed`·`OrderPaymentReviewClosed.admissionId`(접수된 시도일 때만). Order는 열린 최신 영수증과 `admissionId`·`paymentId`가 같을 때만 `PAID`, `purchase_order.payment_admission_id` FK + `CHECK (status<>'PAID' OR payment_admission_id IS NOT NULL)`. Payment는 결제 → 시도 잠금 뒤 시도 재확인하고, 실패 통지·종결·만료로 닫힌 시도의 성공은 결과로 내지 않고 `PAID_WITHOUT_ADMISSION` 보류(A1). 연결값은 맞지만 반영할 수 없는 성공은 Order가 거절 기록 + `OrderPaymentResultRejected`(P3.14-2, 409 → DLT 아님), Payment가 받아 보류 흐름에 올림(P3.13-7, A2) — 결정 153. Payment는 주문에 연결되지 않은 청구 기록(`UnlinkedCharge`, 출처 `ORDER_REJECTED`, 결정 154)에 담는다(MC-P13). 옛 `PAID` 행(모의 PG·옛 경로)의 이행 방침을 같은 PR에 적는다.
  - 영향: **돈·보유권** — 접수 실패로 `PENDING`에 돌아온 주문에 이전 시도의 늦은 성공이 오면 `PAID` → `OrderPaid` → 구매 근거·정산 매출. 같은 고객이 새 시도로 이중 청구될 수 있다. 영수증 B가 열려 있을 때 시도 A의 결과도 구분하지 못한다.
  - 재현: Order 통합 — (1) 접수 → `/failure`로 `PENDING` → 같은 `paymentId`의 `OrderPaymentCompleted` 주입 → `PAID`면 Red (2) `createdAt` 31분 전 `PENDING` 주문(스캔 정지)에 결과 주입 → Red (3) 영수증 B 열림 + 결과 `admissionId`=A → 거절, (4) `EXPIRED` 주문에 성공 주입 → DLT가 아니라 `OrderPaymentResultRejected` Outbox, Payment 보류 목록에 보임. Payment 경합 — `charge` 직후 시도 A를 실패 종료로 바꾸고 PG를 PAID로 둔 뒤 확정 → 결과가 나오면 Red. 계약 테스트: 스키마에 `admissionId`.

- [ ] **T2. 같은 키의 앞 이벤트가 Outbox `DEAD`·소비 DLT로 빠진 뒤 뒤 이벤트가 먼저 흐르고, 재투입이 순서를 뒤집는다** ([결정 155](../../../docs/decisions.md#key-stop-on-dead))
  - 어디서: `common/messaging/.../outbox/OutboxEvent.java:204-212`(`holdUntil(null)`은 아무것도 안 함), `OutboxRelay.java:109-113`, `OutboxEventRepository.java:20-28`(`lockPendingBatch`가 `DEAD` 키를 거르지 않음), `common/kafka/.../ops/DltOpsService.java:65-`(재투입이 같은 키의 이후 처리 여부를 보지 않음, `docs/architecture.md` R2).
  - 목표: P2.2-4·P2.6-5. 결정 155: 키 정지 — Outbox는 `SENT`가 아닌 앞 행이 있는 키의 뒤 행을 보류(`lockPendingBatch`), 소비는 DLT로 보낸 (그룹, 토픽, 키)를 모듈별 정지 목록(`processed_event` 옆)에 올리고 뒤 레코드도 같은 DLT로, 재투입은 키 단위 offset 순·다 처리되면 정지 해제. 정지 키 수 지표·경보(P2.6-5), 키 단위 회수 API. 결정 160: 정지 목록과 실패 레코드 보관을 소비 모듈 DB의 한 표로 둔다(기한 없음, 1건 즉시 경보). DLT 토픽과 `DltOpsService.replay`는 이 표의 키 단위 재처리로 바꾼다. 표 구조·회수 API는 common messaging 설계.
  - 영향: **돈·보유권(추정)** — `OrderPaid` `DEAD` 중 `LicenseSuspensionRequested`가 먼저 License에 가 DLT, `OrderPaid` 회수 뒤 구매 근거는 생기지만 정지는 DLT에 남아 환불 진행 중 보유권이 열림. DLT 재투입 순서에 따라 환불된 주문에 구매 근거, 환불된 매출이 정산에 남음.
  - 재현: 릴레이 통합 — `max-retry=1`, 같은 키 두 행, 첫 행을 `DEAD`로 만든 뒤 둘째 행이 `SENT`가 되지 않음을 단언. 소비 — `OrderPaid`를 DLT로 보낸 뒤 같은 주문 환불을 끝내고 재투입, License 상태가 `REFUNDED` 밖으로 가지 않음.

- [ ] **T3. U2 — 다른 모듈·외부 PG 시각과의 선후 비교 13곳 + PG-* 4곳**
  - 어디서: design "U2" 표 ORD-1~ORD-10·PAY-1·SET-1 — Order `order:core/domain/Refund.java:173`·`:255`, `RefundAttempt.java:39-40`·`:98`·`:110`·`:123`·`:150`·`:166-167`, `RefundApprovalStop.java:48`·`:56`·`:67`; Payment `payment:core/service/ReceiveRefundExecution.java:66`; Settlement `settlement:core/domain/RefundMonthInput.java:11`. PG-*(결정 163): `payment:core/domain/RefundExecution.java:164`·`:224`·`:276`, `payment:adapter/pg/PortOneRefundCancellationGateway.java:131`, #166 `recordLateCancellation`. PR #166 diff도 `RefundAttempt.recordRefundFailure`의 비교를 그대로 둔다.
  - 목표: P4.5-1·I13. 식별자·상태·잠금으로 판정하고 받은 시각은 값으로 저장(U2 표 "목표 판정 방식"). Settlement는 반영 월 = max(원래 월, 현재 `OPEN` 월). 정적 검사(입력의 `occurredAt()`/`confirmedAt()`을 인자로 받는 `isBefore`/`isAfter` 금지)를 ArchUnit 또는 소스 검사로.
  - 영향: **돈·보유권** — 호스트 시계가 몇 초 어긋나면 금융 완료·실패·복구·재시도 이벤트가 재시도 7초 뒤 DLT, 재투입도 같은 결과. 환불이 `PENDING_PAYMENT_REFUND`·`PENDING_LICENSE_RESTORATION`에 멈춰 돈은 돌려줬는데 회수가 안 되거나 돈도 게임도 없는 상태. SET-1은 환불 조정 누락 → 판매자 과지급.
  - 재현: D-052 방식(고정 `Clock`, 실제 MySQL·Kafka 컨테이너, 상대 이벤트 `occurredAt`을 받는 쪽 시각 ±5초) — License `LicenseRestorationIntegrationTest`를 Order·Payment·Settlement에 복제. 행마다 U2 표 "확인 테스트" 열.

- [ ] **T4. 운영자 환불 실패 종결(결정 128·148)이 main에 없고, 참고 구현이 목표와 다르다** (PR #166 이어 가기) — 늦은 취소 반영은 [결정 157](../../../docs/decisions.md#late-cancellation-confirmation)
  - 어디서: main에 `RefundFailureClosed`·`CloseRefundFailure` 없음(`common/kafka/.../contract/EventType.java`, `docs/runbooks/refund-delay-closure.md:13` "미구현"). 실패 뒤 낮은 빈도 조회도 main에 없음(`confirmFailure`가 조회 일정을 멈춤, payment `tasks.md:50`). 참고 구현(`esd-worktrees/refund-failure-closure`, 커밋 안 됨): `RefundFailureClosedEvent.observedPaymentStatus`가 언제나 `"PAID"` 상수, Order `RefundFailureConfirmation`이 Payment 시각끼리(`evidenceObservedAt ≤ occurredAt`) 다시 검사, Order V35가 출처를 `failure_reason_code`(고객 안내 코드)와 `failure_closure_id`에 겹쳐 담음, 결정 128의 근거 종류 칸 없음. 문서: `.kiro/specs/order/design.md:201`·`:287`, `.kiro/specs/payment/tasks.md:47`, 결정 128 본문이 아직 "기존 `RefundExecutionFailed`".
  - 목표: P3.23 전체, design "목표 행" `RefundFailureClosed`(본문은 시도 식별자·`closureId`·`failedCancellationId`만, 근거 상세는 Payment `refund_failure_closure`), I16·I19. Order 시도 출처는 사유 코드와 분리한 enum 열(`PG_CANCELLATION_FAILED`·`OPERATOR_FAILURE_CLOSED`) + CHECK. 실패 뒤 낮은 빈도 조회와 `LATE_CANCELLATION` 조사·경보(P3.23-5). 문서 정정.
  - 영향: **돈·보유권** — 미병합인 동안 PG 형식 거절·잔액 변화·보장 창 종료로 멈춘 환불은 닫을 길이 없어 고객에게 돈도 게임도 없는 상태가 기한 없이 남는다. 실패 뒤 실제 취소가 생겨도 시스템이 모른다(고객이 돈과 게임을 둘 다 가짐 — 반영은 결정 157의 ADMIN 근거 반영 S14, #166 다음에 구현).
  - 재현: 결정 128 예(11:00 접수 → 403 → 14:00 창 종료) 뒤 종결 API → Order가 복구 요청을 내고 고객 재시도가 열리는지. #166 `RefundFailureClosureIntegrationTest`(R1 Red→Green). Order에 근거 칸 없는 `RefundFailureClosed`를 넣어 거절되지 않는지. 실패 확정 뒤 흉내 서버에서 전액 취소 → 조사 목록 `LATE_CANCELLATION`.

- [ ] **T5. 끝난 결제(종결·만료·실패)에 뒤늦게 생긴 PG 청구를 감지하지 않는다** ([결정 154](../../../docs/decisions.md#unlinked-charge-discovery))
  - 어디서: `payment:core/domain/Payment.java:172-178`(결제가 `PENDING`이 아니면 웹훅 무시), `payment:core/service/ResolvePayment.java:89`(재개 작업 대상에서 뺌).
  - 목표: P3.13-7·8·9, P4.2-7, I20. 끝난 결제·시도는 다시 열지 않고 주문에 연결되지 않은 청구 기록(가칭 `UnlinkedCharge`, `OPEN` → `CLOSED`, 출처 `LATE_CHARGE`·`ORDER_REJECTED`)을 둔다. 종결·만료·실패로 끝났고 PG 요청이 있었던 결제를 공급자 보장 기한까지 주기 조회, 웹훅이 오면 조회를 앞당김. `PAID`면 기록·보류 목록·경보·안내 청구됨, 운영자 콘솔 취소 뒤 PG 근거로 종결. 조회 간격·기한은 adapter 설정 — **[외부 대기]** 공급자 문서로 "더 이상 결제될 수 없는 시점" 확인(결정 140 중립화와 함께). 결정 153의 거절 사실도 같은 기록(MC-P13).
  - 영향: **돈** — READY 근거로 종결한 뒤 콘솔 승인 등으로 청구되면 주문·보류 어디에도 연결되지 않은 청구가 남고, 고객은 "청구된 금액은 없습니다"(`CLOSED_NOT_CHARGED`)를 본다.
  - 재현: 흉내 서버 여정 — (1) 종결(READY 근거) 뒤 `console-approve`, 웹훅 없이 → 기간 안 조회로 기록·경보가 없으면 Red (2) 같은 상황에서 웹훅 전송 → 조회가 앞당겨지는지 (3) PG 조회 이력 없이 만료된 결제가 뒤에 `PAID` → 기록 (4) 공급자 기한이 지난 결제는 조회하지 않음.

- [ ] **T6. U3 — 정방향 반영 누락을 세는 곳이 없다(모듈 간 확인 SQL 실행 주체 없음)** (감지 주체 [결정 158](../../../docs/decisions.md#forward-gap-counting), 기준 시간 [결정 159](../../../docs/decisions.md#forward-gap-threshold))
  - 어디서: `tools/verification/`(불변식 검사기 없음, `docs/domain/workflows.md:136`이 "다음 단계 검사기의 명세"라 함), 게이지는 Order 환불 대기만(`order:adapter/RefundLicenseGrantMetrics.java`, `OrderExpiryMetrics.java`, 결정 125·129), Store 대조 경보는 대조가 도는지만(결정 85). 출시 흐름: Studio↔Review 판정, Studio↔Catalog 출시 결과, RI-2, 활성 자리(RI-7), 원요청 없는 미해결 철회, 장기 `PENDING` 명령을 대조하는 곳 없음.
  - 목표: P2.6-6·P3.9-2·P3.15-5·P4.3-1, I14. 결정 158: 사실을 낸 모듈이 응답을 받지 못한 자기 기록을 셈(design "정방향 응답 쌍"). 새 확인 사실 3개(가칭 `SettlementSaleRecorded`·`SettlementRefundRecorded`·`LicenseRefundApplied`)와 Settlement Outbox, 모듈별 대기 게이지. 경보 기준 시간은 결정 159: 게이지 먼저, G6 실험 측정 뒤 쌍마다 확정, G7 전 관문(P4.3-4). 그리고 design 불변식 표의 확인 SQL(I1~I20)과 출시 정합 조건(RI)을 실제 스택 여정(`verify-*.py`) 끝에서 한 번 돌리는 검사는 둔다.
  - 영향: **보유권·돈** — `OrderPaid` DLT·유실 시 보유권 미지급, 정산 매출·환불 조정 누락이 조용히 남는다. 출시 결과가 DLT로 빠져도 화면에서만 드러난다.
  - 재현: Settlement 소비자를 멈춘 채 결제 → T 경과 → 게이지·검사기가 1건. 일부러 `stove_order.purchase_order.amount`를 바꾼 픽스처에서 I1 SQL이 1행. 출시 결과를 DLT로 보낸 뒤 Studio `REQUESTED`·Catalog `COMPLETED`를 검사기가 잡는지.

- [ ] **T7. 원본이 사라진 이벤트를 같은 ID로 다시 낼 수 있는 모듈이 Order·License뿐이고, DLT 보존 기간이 재투입 기한이다** (범위 [결정 161](../../../docs/decisions.md#republish-all-current), 보관 [결정 160](../../../docs/decisions.md#dead-letter-store))
  - 어디서: `common/messaging/.../ops/OutboxRepublishTargets.java:27-37`(Order·License 8종), Payment·Settlement `application.yml`에 재발행 설정 없음, DLT 보존 7일(`docs/architecture.md` R9).
  - 목표: P2.2-7·P2.6-4. 결정 161: 대상은 design 이벤트 목록의 현행 이벤트 전부, `OutboxRepublishTargets`는 표에서 생성하거나 표 대조 테스트로 지킴, 모든 발행 모듈에 `outbox_republish` 이행·`republish-owner` 설정·ADMIN 경로, 같은 키 안 발행 순번 헤더와 회수 정렬. 보관·경보는 결정 160(소비 모듈 DB 실패 보관 표에 기한 없이, 1건 즉시 경보, Outbox `DEAD`도 즉시 경보).
  - 영향: **돈·보유권** — `RefundPaymentConfirmed` 원본 소실 시 Order 환불 완료·Settlement 환불 조정이 둘 다 멈추고 복구 수단이 없다. `PurchaseRefunded`가 사라지면 회수가 안 된다.
  - 재현: Payment에서 `SENT` 행을 재발행 API로 되돌리면 지금은 400(현 동작 고정) → 확장 뒤 통과로 바꿈.

- [ ] **T8. Settlement에 원매출보다 환불 확인이 먼저 오면 404 → DLT** ([결정 156](../../../docs/decisions.md#refund-awaiting-sale))
  - 어디서: `settlement:core/domain/ConfirmedRefund.java:22`(`requireSale`), `settlement:core/service/RecordConfirmedRefund.java`(그룹 `settlement-confirmed-refund`). 원매출은 `stove.order.v1`, 환불 확인은 `stove.payment.v1`이라 순서 보장이 없다(P2.5-2).
  - 목표: P3.26-6. 결정 156: 원매출이 없을 때 온 환불 확인은 "매출 대기 환불"로 저장(Inbox와 같은 트랜잭션, DLT 아님), `OrderPaid` 매출 기록 트랜잭션에서 원매출과 대조해 함께 반영, 대조 불일치는 반영하지 않고 경보, 대기 게이지(건수·최장 대기). 상태 이름·표·상태×트리거 표는 Settlement 모듈 스펙(MC-S3).
  - 영향: **돈** — 재투입 전 마감되면 판매자 월에 매출만 남고 환불 조정이 빠짐(과지급).
  - 재현: Settlement를 멈춘 채 결제 → 자동 환불 금융 완료 → Settlement가 `stove.payment.v1`을 먼저 소비하도록 재시작 → 지금 DLT, 목표 대기 후 반영.

- [ ] **T9. U4 — 결제 공급자가 환불 근거를 줄 수 없는 환경에서 Settlement가 환불 확인을 거절한다** ([결정 162](../../../docs/decisions.md#single-reference-simulator))
  - 어디서: `settlement:adapter/UnavailablePgRefunds.java:12`(`SIMULATED`면 `UPSTREAM_UNAVAILABLE`).
  - 목표: P4.3-2·P3.26-5. 결정 162: `simulators/mock-pg`·`SIMULATED` 분기·`SimulatedPgHttpGateway`·`UnavailablePgRefunds`를 없애고 개발·여정·실험·통합 테스트를 PortOne 어댑터 + 기준 시뮬레이터로 옮긴다(기본 공급자 설정 포함). 옛 `SIMULATED` 값 읽기는 결정 104 호환 테스트로 유지. 결정 140 중립화와 같은 작업 묶음. 범위: 운영 코드 Payment 3·Settlement 10개 파일, 테스트 21개 파일, Compose·검증 스크립트.
  - 영향: **돈(검증 환경)** — 판매자 월 환불 합계가 빠져 순액이 크게 남는다.
  - 재현: 시뮬레이터 스택에서 환불 완료 → `settlement_refund` 1행.

- [ ] **T10. 릴레이 1대 제약(같은 키 동시 선점 금지)이 코드로 강제되지 않는다**
  - 어디서: `common/messaging/.../outbox/OutboxRelay.java:20-23`(`SKIP LOCKED`는 키를 모름), `docs/event-ordering.md` 7절(R13).
  - 목표: P2.2-6. 선점 잠금(서비스별 리더 잠금 등) 또는 키 배정.
  - 영향: **돈·보유권(순서)** — 앱 인스턴스를 2대로 늘리면 같은 키 순서 보장이 조용히 무효가 되어 T2와 같은 역전이 평시에도 생긴다.
  - 재현: 두 릴레이 인스턴스를 같은 DB에 붙이고 같은 키 3건 중 앞 건 실패를 주입, 뒤 건이 먼저 `SENT`되는지.

- [ ] **T11. 토픽 자동 생성, 파티션 수가 코드에 없다**
  - 어디서: `docker-compose.yml:69-73`(자동 생성, 파티션 3, 복제 1, R7).
  - 목표: P2.4-3. 토픽·파티션 수를 선언(`NewTopic` 또는 배포 스크립트)으로 관리하고 자동 생성을 끈다.
  - 영향: **돈(유실)** — 오타 토픽이 생겨 이벤트가 사라진다. 파티션 수를 바꾸면 키 순서가 깨진다.
  - 재현: 자동 생성을 끈 브로커에서 없는 토픽으로 보내면 실패하는지.

- [ ] **T12. 결제 기한(30분)이 7곳에서 다시 계산되고, Payment가 자기 시계로 결제창을 막는다** ([결정 169](../../../docs/decisions.md#checkout-open-until-expiry-fact))
  - 어디서: Order `order:core/domain/Order.java:77`(`createdAt + 30분`, 열 없음), `order:core/service/CloseExpiredOrders.java:38`(스캔 경계 `now − 30분`); Payment `payment:core/domain/PaymentApproval.java:82`(`orderOccurredAt + 30분`), `payment:core/domain/PaymentOrderLifecycle.java:33`·`:36`·`:48`(만료 기한 재계산, `OrderExpired.expiresAt`과 다르면 `conflict()`), `payment:core/service/ReadPaymentCheckout.java:50-51`(Payment 시계로 결제창 `ORDER_EXPIRED`), `payment:core/domain/OrderAdmissionReceipt.java:13-14`(`expiresAt − 30분`). 이벤트에 기한 없음: `common/kafka/.../payload/OrderPlacedEvent.java:9-11`.
  - 목표: P1.4-3·P3.10-1·P3.10-2. Order 설정 키 하나 + `purchase_order.expires_at` 열, `OrderPlaced.expiresAt`. Payment는 저장만 하고 만료는 접수 거절·`OrderExpired` 사실로만 안다. 결정 169: 결제창은 `OrderExpired`를 받은 뒤에만 409 `ORDER_EXPIRED`(`ReadPaymentCheckout.java:50`의 시계 비교 제거).
  - 영향: **돈** — 기한 정책을 바꾸면(예: 40분) `OrderExpired`가 DLT로 가 접수 전 시도가 만료를 모르고 결정 126의 만료 전 PG 재조회도 멈춘다. 결제창이 Order와 다른 시각에 닫힌다. 두 호스트 시계가 어긋나면 기한 안인데 결제창이 닫힌다.
  - 재현: 아키텍처 테스트 — Payment 소스에 결제 기한 계산(`ofMinutes(30)` 등) 0건. 통합 — Order 기한 설정을 바꿔 만료 → Payment가 받아들이는지. 단위 — Payment `Clock`을 1분 앞당겨 29분 30초 주문의 결제창이 거절되면 Red.

- [ ] **T13. 모의 PG 경로가 승인 진행 접수 없이 결과를 확정한다(원칙 ⑦)** — 결정 162로 그 경로 자체를 없앤다(T9와 함께)
  - 어디서: `payment:core/service/ResolvePayment.java:99-126`(`gateway.charge`), `payment:adapter/pg/SimulatedPgHttpGateway.java`, Order 설계 상태×이벤트 표의 `PENDING → PAID` 전이.
  - 목표: P3.11-2·I15. 공급자와 무관하게 접수 뒤에만 확정, 모의 PG도 같은 시도·접수 경로. Order 상태 모델에서 `PENDING → PAID`를 없앤다.
  - 영향: **돈·보유권(모의 환경)** — 기한 지난 `PENDING` 주문이 `PAID`(T1과 겹침), 만료된 주문에 성공이 와 DLT. 테스트 공급자 때문에 운영 계약이 넓어졌다.
  - 재현: Payment 통합(`SIMULATED`) — 접수 없이 `OrderPaymentCompleted`가 나오면 Red.

- [ ] **T14. Order가 Payment의 환불 결과 확인 지연(3시간)을 다시 계산한다**
  - 어디서: `order:core/domain/RefundPreparationConfirmation.java:17`(`confirmationDelayAt.equals(firstPreparedAt.plus(Duration.ofHours(3)))`). 같은 3시간: Payment `payment:core/domain/RefundCancellationWindow.java:8`, `PaymentApproval.java:25`(`CONFIRM_WINDOW`), `payment:adapter/pg/PortOnePaymentApprovalGateway.java:93`, Order V23·Payment V26.
  - 목표: P1.4-4·P1.4-5·P3.19-3. Order는 받은 값을 그대로 쓰고, 공급자 보장 기한은 Payment adapter 설정에서 주입(Payment 쪽 정리는 MC-P2).
  - 영향: **보유권** — 공급자를 바꾸거나 보장 기한이 3시간이 아니면 Order가 정상 `RefundExecutionPrepared`를 거절해 환불이 보유권 정지 상태로 멈춘다(추정).
  - 재현: 보장 창 설정을 2시간으로 바꾼 `RefundExecutionPrepared`를 Order가 받아들이는지.

- [ ] **T15. Payment가 환불 실행 요청을 받지 못한 경우를 Order가 감지하지 않는다** ([결정 158](../../../docs/decisions.md#forward-gap-counting) 적용)
  - 어디서: 게이지 대상이 `PENDING_LICENSE_GRANT`·`PENDING_LICENSE_SUSPENSION`·`PENDING_LICENSE_RESTORATION`·`PENDING_APPROVAL_STOP` 4개(order `design.md:210`·`:267-271`). Payment에는 행이 없어 `RefundConfirmationsDelayed`도 안 울린다.
  - 목표: P3.19-4. 결정 158 적용: 결정 125 게이지에 "`PENDING_PAYMENT_REFUND` + 현재 시도 준비 기록 없음" 라벨, 진입 시각은 시도 `startedAt`, 같은 10분 경계·경보·고객 안내(`processingNotice`). Payment 쪽 게이지는 DLT 건을 못 보므로 두지 않는다.
  - 영향: **보유권** — Payment 소비 중단·DLT 때 보유권 정지·고객 `PROCESSING`이 경보 없이 남는다.
  - 재현: Payment `payment-refund-execution` 소비를 막고 10분 경과 → 게이지 1.

- [ ] **T16. 공개를 확정하는 Catalog가 심사 승인을 확인하지 않는다(RI-2)** ([결정 167](../../../docs/decisions.md#catalog-verifies-approval))
  - 어디서: `catalog:core/service/ReceiveProjectRelease.java:38-63`(`reviewRequestId`는 저장만), Catalog 설계 "Studio 명령을 신뢰". 가드는 Studio `studio:core/service/release/RequestProjectRelease.java:45-47·58·64`뿐.
  - 목표: P3.4-2, design 흐름 1 RI-2·S3a. 결정 167: Catalog가 `ProjectReviewApproved`를 새 그룹 `catalog-review-approval`로 받아 보관, 공개 확정 전 `(reviewRequestId, projectId, contentVersion, 스냅샷 값)` 대조, 맞는 승인이 없으면 승인 대기 보류(같은 잠금 안에서 해제), 값이 다르면 `ProjectReleaseRejected`, 승인 대기 게이지. 세부는 Catalog 모듈 스펙(MC-C2).
  - 영향: **돈·보유권** — Studio 결함·`stove.studio.v1` 생산 권한 오용 때 승인 안 된 자료가 판매되고 그 자료로 보유권이 지급된다.
  - 재현: 승인 기록 없는 `reviewRequestId`로 `ProjectReleaseRequested`를 직접 넣어 공개되는지(지금 통과).

- [ ] **T17. PG 비용이 Payment와 Settlement 두 곳에 기록된다** ([결정 165](../../../docs/decisions.md#pg-fee-owner))
  - 어디서: `payment/db/V12__pg_fee_snapshot.sql:1-9`, `payment:core/domain/Payment.java:38-40`·`:130`, `payment:adapter/pg/SimulatedPgHttpGateway.java:43-45`; Settlement `settlement/db/V8__...sql:25-33`(`settlement_pg_fee`).
  - 목표: P1.4-2. 결정 165: Settlement `settlement_pg_fee`만 정본. Payment는 새 결제에 `pg_fee_*`를 쓰지 않고 공급자 승인 응답의 비용을 core 계약으로 받지 않음, 옛 값은 읽기만(결정 104). 칸 제거 시점은 Payment 모듈 스펙 이행 계획.
  - 영향: **돈(정산)** — 두 값이 다르면 플랫폼 순액의 근거를 정할 수 없다.
  - 재현: 같은 결제에 두 값이 다를 때 ADMIN 응답이 어느 것을 쓰는지 고정하는 계약 테스트.

- [ ] **T18. 운영자 승인 보류 종결이 `OrderPaymentFailed(reasonCode=REVIEW_CLOSED)`에 실린다** (분리안 SP-1, 소급 RC-1)
  - 어디서: `payment:core/domain/ApprovalClosure.java:27`, `payment:core/domain/Payment.java:147-156`(`failAfterReview`); Order `order:core/domain/PaymentResult.java:34`(문자열 `REVIEW_CLOSED`로 판정), `Order.java:147-150`(`keepsExpiryFor`), `order:core/service/ApplyPaymentResult.java:47,65-72`, `order:core/domain/ExpiredReviewClosure.java:17`, Order V31.
  - 목표: P3.13-4·P3.14-3, design "목표 행" `OrderPaymentReviewClosed`(그룹 `order-payment-result`). Order 리스너가 두 타입을 받아 결과 종류 enum으로 구별. P2.7-4 순서로 배포. RC-1을 다시 물은 뒤.
  - 영향: 동작은 같다. 소비자가 사유 문자열로 사실을 구별해 오타·새 소비자 오해 여지.
  - 재현: 계약 테스트 — `OrderPaymentFailed.reasonCode`에 `REVIEW_CLOSED` 0건, Order가 두 타입을 같은 입력으로 합치는지.

- [ ] **T19. `RefundPaymentConfirmed.source`가 "근거 종류"와 "시도 귀속"을 함께 말한다** (분리안 SP-5, 소급 RC-8) — 내보내는 방법은 [결정 170](../../../docs/decisions.md#schema-version-header)
  - 어디서: `common/kafka/.../payload/RefundPaymentConfirmedEvent.java`(`source`), Payment `payment:core/service/RecordRefundCancellationSuccess.java:40`(`PG_RESPONSE`), `RefundConfirmationOutbox`(`PG_QUERY_UNATTRIBUTED`), `payment:core/domain/RefundExecution.java:256,292`; Order `order:core/domain/Refund.java:239`, `RefundPaymentConfirmation.java:18-20`; Settlement `settlement_refund.source`(V10).
  - 목표: P3.21-2. `source` 삭제(칸 삭제는 비호환 — `RefundPaymentConfirmed`의 새 `schemaVersion`으로, P2.7-5 배포 순서, 결정 170), 귀속은 Payment 기록만([결정 164](../../../docs/decisions.md#attribution-in-payment)): Order는 `RefundExecutionSucceeded`를 소비하지 않고 `RefundAttempt.reconciliationRequired`를 없앤다. 소비자가 없어진 `RefundExecutionSucceeded`는 발행을 멈추고 계약에서 뺀다(옛 이벤트 읽기는 결정 104 호환).
  - 영향: 돈 직접 영향 없음. `source` 값 오타는 시도 귀속을 조용히 빠뜨리고, 귀속 판정을 바꾸려면 금융 사실 이벤트의 뜻이 함께 바뀐다(결정 127 후속이 막힌 원인).
  - 재현: 계약 테스트 — `PG_RESPONSE` 경로에서 두 이벤트가 같은 트랜잭션에 쓰이는지(목표 Red).

- [ ] **T20. 영구 실패도 4회 재시도하고, 공통 분류가 없다**
  - 어디서: `common/kafka/.../KafkaConsumerAutoConfiguration.java:37-43`, 재시도 제외는 Order `order:config/RefundKafkaConfiguration.java:20`의 `RefundCompletionRejected` 하나(R11).
  - 목표: P2.6-3. `common/kafka`에 영구 실패 예외 하나, 공통 핸들러가 재시도에서 뺀다.
  - 영향: 계약 위반 한 건이 파티션을 7초 이상 멈춘다(같은 키 뒤 이벤트 지연).
  - 재현: 헤더 없는 레코드를 넣고 DLT 도착까지 시도 1회 단언.

- [ ] **T21. 철회 결과 이벤트 하나가 "철회됨"과 "철회 거절"을 겸한다** (분리안 SP-7·SP-8, 소급 RC-9)
  - 어디서: `review:core/domain/ReviewWithdrawal.java:63-68`, `catalog:core/domain/ReleaseWithdrawal.java:63-67`, payload `ProjectWithdrawalResult.java`(`String status`), Studio `studio:api/listener/ProjectResultListener.java:33-49`.
  - 목표: P3.2-5·P3.4-5. `ProjectReviewWithdrawn`/`ProjectReviewWithdrawalDenied`, `ProjectReleaseWithdrawn`/`ProjectReleaseWithdrawalDenied`(Denied는 `targetStatus` enum만). Studio 리스너가 변환.
  - 영향: 돈 없음. 창작자 영수증의 `finalActor`·`finalFeedback`이 뜻이 바뀌는 칸이라 화면·알림이 잘못 읽는다.
  - 재현: 판정 뒤 철회 → 결과 이벤트의 `actor`가 심사자, `feedback`이 반려 사유임을 단언(지금 통과) → 목표 계약 테스트로 바꾸면 Red.

- [ ] **T22. `CatalogProductPublished`가 최초 공개·공개본 교체·판매 상태·노출 변경을 모두 뜻한다** (분리안 SP-4, 소급 RC-6) [결정 필요 PD-19]
  - 어디서: `catalog:core/service/PublishProjectRelease.java:122-148`, `RecordCatalogSaleChange.java:20`, `ChangeCatalogListing.java:64`.
  - 목표: P3.5-6. PD-19(추천: 사실별 4개, 같은 `CatalogProductSnapshot` 본문, Store 리스너가 합침).
  - 영향: 지금 소비자는 Store 하나라 영향 없음. 새 소비자(알림·검색 색인)가 판매 중지를 새 공개로 읽는다.
  - 재현: 판매 중지 명령 뒤 Outbox 유형이 `CatalogProductPublished`임을 단언(지금 통과).

- [ ] **T23. Catalog 공개 작업이 재시도로 풀리지 않는 실패도 무한 재시도하고 경보가 없다** ([결정 168](../../../docs/decisions.md#publication-blocked-closure))
  - 어디서: `catalog:core/service/PublishQueuedReleases.java:44-48`(모든 `RuntimeException` 같은 처리), `catalog:core/domain/CatalogPublicationJob.java:68-79`(상한·종료 상태 없음), `infra/prometheus/alerts.yml`(공개 작업 규칙 없음).
  - 목표: P3.5-5. 일시 오류와 계약 위반을 나누고 계약 위반은 명시적 상태 + 경보. 결정 168: 정지 상태 `BLOCKED`(1건부터 경보), 운영자 재개 또는 종결, 종결은 전용 이벤트 `ProjectReleaseClosed`(가칭, design 목표 행) → Studio가 요청을 끝내고 활성 자리 해제. 상태 이름·운영 API는 Catalog·Studio 모듈 스펙(MC-C3).
  - 영향: 돈 없음. 보유권: 업데이트 공개가 멈춰 기존 보유자가 새 빌드를 못 받는다. Studio 활성 출시 자리가 풀리지 않아 그 Project의 다음 출시가 모두 409.
  - 재현: 같은 Project·같은 버전 다른 스냅샷의 두 요청 → 작업 하나가 `attemptCount`만 늘고 경보 지표가 없음.

- [ ] **T24. 공통 계약 `CatalogProductSnapshot`이 빠진 칸을 기본값으로 만든다**
  - 어디서: `common/kafka/.../payload/CatalogProductSnapshot.java`(compact 생성자: `listed` 없으면 판매 상태로 추정, `saleStatus`·`catalogVersion` 없으면 `ON_SALE`·`contentVersion`). Store HTTP 경로는 먼저 검사해 적용되지 않음(`store:adapter/CatalogHttpClient.java:71-75`), Kafka 경로는 적용.
  - 목표: 원칙 ①(없는 사실을 받는 쪽이 만들지 않는다). 과거 형식 이벤트가 토픽 보존 기간 밖이면 기본값을 지우고 필수 칸으로, 남겨야 하면 Store `api.listener` 변환으로 옮긴다.
  - 영향: 돈 없음(Order는 Catalog HTTP 정본). 과거 이벤트가 최초 삽입이면 숨긴 상품을 `listed=true`로 보일 수 있다.
  - 재현: 버전 칸 없는 과거 JSON을 빈 Store에 넣어 `listed=true`·`ON_SALE` 사본이 생기는지.

- [ ] **T25. 헤더 `occurredAt`이 Outbox 적재 시각이라 본문 사실 시각과 다르고, `schemaVersion` 헤더가 없다** ([결정 170](../../../docs/decisions.md#schema-version-header))
  - 어디서: `common/messaging/.../outbox/OutboxRelay.java:151-152`, 예 `order:core/service/ApplyApprovalCorrection.java:50-51`(본문은 `stop.getRestorationRequestedAt()`), `common/kafka/.../EventEnvelope.java:17`.
  - 목표: P2.1-1·P2.1-2. 헤더 `occurredAt` = 본문 값. 결정 170: `outbox_event.schema_version`(종류별 정수, 1부터), 릴레이가 헤더 `schemaVersion`으로 냄, 헤더 없는 옛 메시지는 1, 리스너는 (`eventType`, `schemaVersion`)으로 매퍼 선택, `Topics.java` 주석을 "토픽 `vN` = 파티션 수·키 규칙 세대"로 정정.
  - 영향: 헤더를 읽는 도구(추적·DLT 조회 `DltRecordResponse`)와 본문을 읽는 업무 코드가 다른 시각을 본다.
  - 재현: `OutboxRelay` 단위 — 본문 `occurredAt`을 과거로 둔 이벤트의 헤더 값 == 본문 값.

- [ ] **T26. 현행 출시·심사 이벤트 10개가 `EventType`에 없고 문자열 리터럴이다**
  - 어디서: payload `ProjectReviewRequestedEvent.java:19`·`ProjectReleaseRequestedEvent.java:28`·`ProjectReviewApprovedEvent.java:36`·`ProjectReviewRejectedEvent.java:39`·`ProjectReleaseCompletedEvent.java:36`·`ProjectReleaseRejectedEvent.java:39`·`ProjectReviewWithdrawalRequestedEvent.java:14`·`ProjectReleaseWithdrawalRequestedEvent.java:14`·`ProjectReviewWithdrawalResolvedEvent.java:14`·`ProjectReleaseWithdrawalResolvedEvent.java:14`(모두 `common/kafka/.../contract/payload/`); 리스너 `catalog:api/listener/StudioReleaseEventListener.java:27,30`, `review:api/listener/StudioEventListener.java:27,30`, `studio:api/listener/ProjectResultListener.java:33-49`.
  - 목표: P2.1-4. 상수로 올리고 payload·리스너가 상수를 참조. T21의 분리와 같은 PR이면 새 이름으로 바로 올린다.
  - 영향: 이름을 한쪽만 바꾸면 리스너가 조용히 건너뛴다(P2.3-7 때문에 오류도 없음).
  - 재현: 소스 검사 — `isType("` 리터럴 0건, 모든 payload `eventType()`이 `EventType` 필드 값.

- [ ] **T27. `EventType` 목록과 이벤트 목록 표를 대조하는 테스트(결정 151), 런타임 의존을 표와 대조하는 테스트**
  - 어디서: 없음. `EventContractTest.catalogCoversEveryDeclaredEvent`(`common/kafka/src/test/java/.../contract/EventContractTest.java:443-460`)는 payload 패키지와 테스트 카탈로그만 비교한다. `adapter` HTTP 경로·`@KafkaListener`를 design 동기·비동기 의존 표와 대조하는 테스트도 없다.
  - 목표: P2.8-4·P1.5. design 이벤트 목록(현행·리터럴·목표 행)을 파싱해 `EventType` 필드 집합·소유·토픽·키·소비 그룹과 비교. T26 뒤 켠다. 같은 방식으로 (호출 모듈, 대상 경로)·(소비 그룹, 이벤트 유형) 쌍 대조.
  - 영향: 표와 코드가 어긋나도 CI가 모른다. 새 의존(예: 순환 동기 호출)이 스펙 없이 생긴다.
  - 재현: 표에 없는 상수·HTTP 경로를 둔 픽스처가 실패하는지.

- [ ] **T28. core의 공급자 이름을 막는 ArchUnit 규칙이 없다(결정 140)**
  - 어디서: `common/archunit/.../ModulePackageRules.java`(해당 규칙 없음). 예: Payment core 공급자 상수(MC-P3), PG 통지 경로 `/api/v1/payments/webhooks/portone`(`payment:api/controller/PortOneWebhookController.java:28`), 결정 140이 센 core 29곳.
  - 목표: P1.1-4. `core..` 패키지의 공급자 이름 클래스·문자열 금지 규칙. PG 통지 경로 이름 중립화는 결정 140 작업과 함께.
  - 영향: 공급자 규칙이 core에 다시 섞인다.
  - 재현: `core` 패키지에 `PortOne` 이름 클래스를 둔 픽스처가 실패하는지.

- [ ] **T29. 모듈별 DB 계정이 자기 스키마만 쓸 수 있는지 확인되지 않았다**
  - 어디서: `docker-compose*.yml`·`infra/`(미확인).
  - 목표: P1.1-2를 DB 권한으로도 지킨다. 운영 도구 계정에도 예외를 두지 않는다(결정 158). 모든 스키마를 읽는 검사기 계정은 시험·실험 환경에만 둔다.
  - 영향: 한 모듈 코드가 다른 스키마를 쓰는 회귀를 DB가 막지 못할 수 있다.
  - 재현: 각 모듈 계정으로 다른 스키마 `SELECT`가 거절되는지.

- [ ] **T30. 발행·소비가 없는 상수 22개(구 계약 13 + 제거 대상 9)와 payload가 남아 있다** [결정 필요 PD-20]
  - 어디서: `common/kafka/.../contract/EventType.java`, `contract/payload/` 같은 이름 22개(design 이벤트 목록의 구 계약·제거 대상 표).
  - 목표: P2.8-2·P2.8-3. 제거 대상 9개는 P2.8-2 절차로 지우고, 구 계약 13개는 PD-20.
  - 영향: 리뷰어가 현행 계약을 구별하기 어렵다(원칙 ③). 새 소비자가 영원히 오지 않는 이벤트를 기다릴 수 있다.
  - 재현: 서비스별 `SELECT event_type, status, COUNT(*) FROM outbox_event WHERE event_type IN (...) AND status IN ('PENDING','DEAD') GROUP BY 1,2` 0행 확인.

- [ ] **T31. 구독 토픽의 모르는 `eventType`을 지표 없이 건너뛴다**
  - 어디서: 모든 리스너의 `isType` 분기(예: `order:api/listener/RefundSuspensionEventListener.java:27`의 `return`).
  - 목표: P2.3-7. 건너뛴 수를 (소비 그룹, 타입) 지표로.
  - 영향: 발행 쪽 이름 변경 사고를 알 수 없다.
  - 재현: 모르는 타입 1건 소비 뒤 지표 1.

- [ ] **T32. `EventType` 묶음 주석과 `Topics.RELEASE` 주석이 소유 모듈과 다르다**
  - 어디서: `common/kafka/.../contract/EventType.java`(`REFUND_EXECUTION_PREPARED`·`ORDER_PAYMENT_COMPLETED`·`ORDER_PAYMENT_FAILED`는 Payment 발행인데 `// order` 아래, `REFUND_EXECUTION_FAILED`는 Payment, `LICENSE_RESTORATION_REQUESTED`·`LICENSE_APPROVAL_CORRECTION_REQUESTED`는 Order 발행인데 `// license` 아래), `Topics.java:120`.
  - 목표: design 토픽 표·이벤트 목록 "소유" 열이 정본. 주석은 지우거나 소유 모듈로 고친다.
  - 영향: 소유자를 잘못 읽고 발행 코드를 다른 모듈에 둘 수 있다.
  - 재현: T27 대조 테스트가 소유 모듈 열까지 비교.
