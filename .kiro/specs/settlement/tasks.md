# 작업: Settlement

> 상태: 작성 중(결정 150·178). 경로 줄임: `S/…` = `apps/settlement/src/main/java/com/stove/settlement/…`, `V<n>` = `apps/settlement/src/main/resources/db/migration/V<n>__*.sql`. 줄 번호는 2026-11-09 `docs/spec-foundation` 기준. 모듈을 넘는 작업은 [플랫폼 tasks](../platform/tasks.md)의 `T` 번호에 있고 여기서는 Settlement 쪽 몫만 적는다. 순서는 돈·보유권 영향 순.

## 완료 (검증 결과는 근거 색인)

- [x] 독립 외부 모의 PG(simulators/mock-pg): 전용 프로세스·DB·HTTP API·키별 권한·승인/조회·정산 명세·검증용 제어·멱등/배정 규칙 — 2026-10-18 (완료 모듈 수 미포함). 목표에서 제거 대상(결정 162, SE-T2)
- [x] Payment 외부화: `PgGateway`의 HTTP 구현·비용 보존·V12·응답 유실/PG 중단 복구·구 원장 이관 도구 — 2026-10-18
- [x] 매출 접수(RecordPaidSale): V6·입력 mapper·PG 거래/비용·Catalog 소유자 직접 조회·원장/월 합계/Inbox 원자 트랜잭션·13개 값 비교·중복/출처 재사용 차단 — 2026-10-18
- [x] PG 수령(ReceivePgSettlement·ReconcilePgReceipt): V7·SIMULATED 명세 접수/재대사·미도착(REPORTED_PAID)/불일치(MISMATCH)·최초 수령(SIMULATED_RECEIVED)·재시작 복구 — 2026-10-18. 목표에서 읽기 전용(R5, SE-T2)
- [x] 월 마감·조회: 월 잠금·지연 거래(마감 후 현재 월 반영)·수동 마감·본인/운영/PG 수령 조회·보안·OpenAPI·구 코드/설정 정리 — 2026-10-18
- [x] 로컬 OrbStack 검증: 집중 검사 + 완료 모듈 회귀 + 열한 업무 앱 + 외부 PG 여정 (단위 1,014·관련 통합 152·실제 여정 32단계, 실패·오류·건너뜀 0) — 2026-10-18
- [x] G3 책임 분리·PG 비용 독립 월 마감: 원매출/배분·판매자 월·PG 거래/비용/대사 분리, V8, 비용 미확정 마감 (OCI 75건) — 2026-10-31, 결정 38·39
- [x] G3 외부 부담 혜택 A: PortOne TEST 원승인 직접 읽기(`PgFunding`)·총액/실결제/혜택 보존·주문 금액 배분·V9 (OCI 84건) — 2026-10-31, 결정 40
- [x] G3 전액 환불 배분 A: `ReverseOriginalFullRefundPolicy` 원배분 음수 상계 계산 (OCI 92건) — 2026-11-02, 결정 41
- [x] G3 환불 반영 월 A: `UseConfirmedRefundMonthPolicy` 확인 월 OPEN/CLOSED 이월 (OCI 102건) — 2026-11-02, 결정 42
- [x] G3 환불 원장·월 합계 연결: `RefundPaymentConfirmed` 소비·`PgRefunds` 취소 직접 GET·불변 환불 원장·월 환불 합계·역할별 조회·V10 (OCI 120건) — 2026-11-02
- [x] G3 토스페이 정산 API 수집 A: 전체 페이지·불변 원본/거래 정본·중복/충돌·거래별 대사·ADMIN 조회·주기 재수집·V11 (OCI 144건) — 2026-11-02, 결정 43
- [x] G3 PG 비용 업무 반영 월 연결·지급일 대사: `settlement_pg_cost_posting`·월 금융 요약·`pg-payouts` 조회·원본 재대사·V12 (OCI 167건) — 2026-11-02, 결정 45
- [x] G3 테스트 경계 정비: 루프백 HTTP 서버·저장소 대역 합성 LIVE 자료 분리, 제약 해제 픽스처 제거 (OCI 176건) — 2026-11-02, 결정 46
- [x] 환불 → 정산 조정 실제 스택 여정: `PORTONE_TEST` 모드의 Settlement가 PortOne 흉내 서버로 원승인(`PgFunding`)·취소(`PgRefunds`)를 직접 조회해 매출 2건·환불 1건 반영(`verify-refund-settlement.py`) — 2026-11-07, [결정 111](../../../docs/decisions.md#portone-fake-journey), PR #129
- [x] 환불 조정 완료와 판매자 순액 전체 연결 — 결정 121·130. 이전 월 OPEN이면 마감 409, V13 불변 이월 원장, 월 응답 `carriedIn`·`payable`·`carriedOut` — 2026-11-08, 단위·아키텍처 143·통합 70건 통과
- [x] 늦은 앞 월 이벤트를 마감 경계 뒤로 반영(결정 42·130 적용) — [PR #156](https://github.com/tlswltjq/esd-platform/pull/156): V14 판매자 잠금·V15 이월 출처 유일, [D-045](../../../docs/defects.md#d-045)·[D-046](../../../docs/defects.md#d-046) 수정, [근거 색인](../../../docs/evidence.md) 2026-11-08 settlement 행
- [x] 매출 조회(`/me/sales`·`/sales`)에 환불 연결(`SaleResponse.refund`) — 2026-11-08, 단위 137·통합 64건 통과, PR #142
- [x] PORTONE_TEST 원승인·취소 읽기 설정을 Compose override(`docker-compose.settlement-portone.yml`)로 켜고 런타임 검증 — 2026-11-09, 결정 111, [근거 색인](../../../docs/evidence.md) 2026-11-09 settlement 행
- [x] G3 변경 이후 열한 업무 앱 + 모의 PG 전체 여정(`tools/verification/verify-settlement.py`) 재실행 — 2026-11-07 21단계 통과(모의 PG 기준)
- [x] (정리) 환불 → 정산 조정의 실제 스택 여정 — 위 2026-11-07 항목·결정 111로 끝남

## 코드와 맞출 것

형식: 무엇이 · 어디서 · 목표 · 영향 · 재현 테스트. `MC-S*`는 모듈 스펙 이월 목록(초안 작업 폴더 `module-carryover.md`)의 번호를 그대로 쓴다.

- [ ] **MC-S3. 매출 대기 환불이 없다 — 원매출보다 먼저 온 환불 확인이 404 → DLT** ([결정 156](../../../docs/decisions.md#refund-awaiting-sale), 플랫폼 T8)
  - 어디서: `S/core/service/RecordConfirmedRefund.java:63`(`NOT_FOUND` "환불에 대응하는 원매출이 아직 없습니다."), `S/core/service/RecordPaidSale.java:74-101`(대기 환불을 보지 않음).
  - 목표: R11·design "매출 대기 환불". 새 이행 파일로 `settlement_refund_awaiting_sale`(PK `order_no`, `uk_awaiting_refund_id`·`uk_awaiting_event`, 상태 CHECK `AWAITING_SALE`·`APPLIED`·`CONFLICTED`와 칸 짝 CHECK, FK `refund_record_id`)·`settlement_refund_awaiting_cancellation`. `RecordConfirmedRefund`는 원매출이 없으면 Inbox와 함께 `AWAITING_SALE` 저장 뒤 원매출 잠금 읽기로 경쟁을 확인. `RecordPaidSale`은 트랜잭션 밖에서 대기 환불이 있으면 `PgRefunds`도 조회하고, 원매출 삽입 뒤 대기 환불을 잠금 읽기해 `APPLIED`(환불 조정 + `SettlementRefundRecorded`) 또는 `CONFLICTED`(`SALE_MISMATCH`·`PG_REFUND_MISMATCH`, 경보). 게이지 `AWAITING_SALE`·`CONFLICTED` 건수·최장 대기. ADMIN 목록(가칭 `GET /awaiting-refunds`, 읽기만)·재검증 명령(가칭 `POST /awaiting-refunds/{orderNo}/revalidate`, 결정 195, R11.8~12). 보관한 사실·원매출·새 PG 근거를 같은 검증으로 대조하고, 일치하면 환불 조정·월 합계·`APPLIED`·확인 Outbox·성공 이력을 원자 기록한다. 불일치 시 반영하지 않고 사유·검사 이력을 남긴다. 실행자·사유·검사 시각·근거 식별자를 기록하며 최초 접수 Inbox·원본을 보존한다.
  - 영향: **판매자 돈** — 재투입 전 마감되면 판매자 월에 매출만 남고 환불 조정이 빠져 지급 기준액이 실제보다 크게 계산될 수 있고, 마감 후 반영은 현재 OPEN 월로 넘어간다. 실제 판매자 송금은 현재 범위 밖이다.
  - 재현: Settlement를 멈춘 채 결제 → 자동 환불 금융 완료 → `stove.payment.v1`을 먼저 소비하도록 재시작 → 지금 DLT / 목표 `AWAITING_SALE` 뒤 `OrderPaid`로 `APPLIED`·월 환불 합계 1건. 원매출과 금액이 다른 대기 환불 → `CONFLICTED`·매출만 기록. 대기 환불 저장과 매출 기록 동시 실행(두 스레드 + 실제 MySQL) → `AWAITING_SALE`이 남지 않음. 재검증 테스트: 정상 근거를 검증 결함으로 거절한 상태를 구성 → 검증 수정 후 명령으로 `APPLIED`·환불 조정·확인 Outbox 각 1건. 반복·동시 호출도 각 1건, 이미 반영됐으면 PG 조회 없음. 원본 오류·금액/취소 ID 불일치가 계속되면 409·미반영·검사 이력 보존. PG 장애 503·상태 무변화, 원매출 없음 409, 보관 기록 없음 404, 권한 검사, 원본·Inbox 보존. 이 상태 구성은 설계 검증이며 실제 PG 불일치를 재현했다는 근거로 쓰지 않는다.

- [ ] **MC-S1. CLOSED 월 합계·`closed_at` 불변을 DB가 막지 않는다(I6)**
  - 어디서: `V6:62`(`ck_month_closed`는 상태·`closed_at` NULL 여부만), 코드 `S/core/domain/SellerMonthlySettlement.java:48`·`:54`(CLOSED면 409).
  - 목표: R9.9. `BEFORE UPDATE` 트리거로 `OLD.status='CLOSED'`인 행의 합계·건수·`closed_at`·`status` 변경을 `SIGNAL`로 거절(또는 검사기 회차 비교, 플랫폼 T6). 이행 파일은 기존 행을 바꾸지 않는 한 문장.
  - 영향: **판매자 돈** — 마감 뒤 합계가 바뀌어도 DB가 막지 않는다.
  - 재현: `migrationTest`에서 CLOSED 행 `UPDATE … SET seller_payout_amount=…`·`SET status='OPEN'`이 실패하는지, OPEN 행 갱신은 그대로 되는지.

- [ ] **SE-T8. 환불 반영 월 선택이 Payment 시각과 Settlement 시계를 비교해 거절한다(U2 SET-1)** (플랫폼 T3, RC-5 닫힘)
  - 어디서: `S/core/domain/RefundMonthInput.java:11`(`!receivedAt.isBefore(confirmedAt)`), `S/core/domain/RefundMonthSelection.java:48`(`!firstReceivedAt.isBefore(confirmedAt)`)·`:51`(`settlementMonth ≤ month(firstReceivedAt)`).
  - 목표: R3.12·P3.26-2·P4.5. 두 시각 비교를 없앤다. 반영 월은 확인 월이 `OPEN`이고 뒤 월 `CLOSED`가 없으면 확인 월(Settlement 시계의 현재 월보다 뒤여도), 아니면 현재 `OPEN` 월. 불변식은 `settlementMonth ≥ refundMonth`(SE-T6)로 지킨다.
  - 영향: **판매자 돈** — 월 경계에서 Payment 시계가 몇 초 빠르면 정상 환불이 영구 거절 → DLT, 재투입해도 같아 환불 조정이 빠진다.
  - 재현: 고정 `Clock`을 `confirmedAt`보다 5초 이르게 두고 KST 월 경계(10-31 23:59:58 확인, Settlement 23:59:53) — 지금 Red, 목표 11월 확인 월 행에 반영.

- [ ] **MC-S4. 매출 귀속 월이 Order `PAID` 반영 시각이다(결정 166)** (플랫폼 1회차 지적 A6, 판정 전)
  - 어디서: `S/api/listener/SettlementOrderMapper.java:71-73`(`paymentConfirmedAt` 없음), `S/core/domain/PaidOrder.java:105`, `S/core/service/RecordPaidSale.java:78`(`SettlementMonth.at(input.occurredAt())`), `V6`(칸 없음).
  - 목표: R3.1·SE-I18. `PaidOrder`에 선택 칸 `paymentConfirmedAt`, `settlement_sale.payment_confirmed_at`(nullable) 이행, `saleMonth` = 그 칸의 KST 월(없으면 `occurredAt`). 중복 판정 지문에 그 칸을 넣고 옛 행(null)은 옛 지문으로 비교. Order 쪽 칸 추가는 Order 모듈 작업.
  - 영향: **판매자 돈(월 귀속)** — 월말 승인이 다음 달 매출이 되어 PG 정산 자료(G7)와 월이 어긋나고 마감 월이 바뀐다.
  - 재현: 승인 확정 10-31 23:59:50 KST·`OrderPaid.occurredAt` 11-01 00:00:20 → `saleMonth=2026-10`. 칸 없는 옛 본문 견본 → `occurredAt` 월(P2.7-3 호환 테스트). 같은 주문 옛 행에 새 칸 있는 재전달 → 무변화인지 충돌인지 고정.

- [ ] **SE-T2. 시뮬레이터 전용 공급자 경로가 운영 코드에 있다(U4)** (결정 162, 플랫폼 T9·T13)
  - 어디서: `S/adapter/UnavailablePgRefunds.java:12`(SIMULATED면 `UPSTREAM_UNAVAILABLE`), `SimulatedPgPaymentHttpClient`·`SimulatedPgSettlementHttpClient`·`UnavailablePgSettlements`, `S/core/domain/PgEvidence.java:7`·`PgCost.java:28`·`PgSettlement.java:12`·`PgRefundCancellation.java:20`, `S/core/service/ReceivePgSettlement.java:33-47`·`ReconcilePgReceipt.java:30`·`ReadPgReceipts.java:21`, `application.yml` `pg-provider: ${SETTLEMENT_PG_PROVIDER:SIMULATED}`·`MOCK_PG_URI`.
  - 목표: R4.6·R5. 승인·취소 adapter 하나(기준 시뮬레이터는 주소만), `pg-provider`·`MOCK_PG_*` 설정 제거, `POST /pg-receipts`·`/reconcile` 제거, `GET /pg-receipts`는 옛 행 읽기 전용. 옛 `SIMULATED` 행 읽기는 결정 104 호환 테스트.
  - 영향: **판매자 돈** — 시뮬레이터 환경에서 전액 환불이 월 환불 합계에 들어가지 않아 판매자 순액이 크게 남는다.
  - 재현: 기준 시뮬레이터 스택에서 환불 완료 → `settlement_refund` 1행. 옛 `SIMULATED` `settlement_sale`·`pg_receipt` 행이 있는 DB에서 조회 응답이 그대로인지.

- [ ] **SE-T6. 반영 월 ≥ 귀속 월을 DB가 지키지 않는다(I6)**
  - 어디서: `V6`·`V10`(CHECK 없음), 코드만 `RecordPaidSale.record`·`RecordConfirmedRefund.record`.
  - 목표: R9.5·SE-I8. `ck_sale_month_order CHECK(settlement_month >= sale_month)`, `ck_refund_month_order CHECK(settlement_month >= refund_month)`(yyyy-MM 문자열 비교). 적용 전 위반 행 수를 `SELECT … WHERE settlement_month < sale_month`로 센다(0이 아니면 사용자에게 묻는다).
  - 영향: **판매자 돈** — 반영 월 선택 회귀가 마감된 과거 월에 매출·환불을 넣어도 DB가 막지 않는다.
  - 재현: `migrationTest`에서 `settlement_month < sale_month` 삽입 실패.

- [ ] **SE-T3. Settlement Outbox·재발행·실패 보관이 꺼져 있다(결정 158·160·161)** (플랫폼 T6·T7, 1회차 지적 B4·A5, 판정 전)
  - 어디서: `application.yml` `stove.outbox.relay-enabled: false`·`stove.messaging.ops.enabled: false`, `V1:55-73`(`outbox_event`만), `common/messaging/.../ops/OutboxRepublishTargets.java:27-37`(Settlement 없음), `RecordPaidSale`·`RecordConfirmedRefund`(Outbox 기록 없음).
  - 목표: R12·R1.7. `SettlementSaleRecorded`·`SettlementRefundRecorded`(가칭)를 `EventType`·payload·`Topics`(`stove.settlement.v1`)에 더하고 반영 트랜잭션에서 `OutboxRecorder.record`. 릴레이 켬, `outbox_republish` 이행·`republish-owner: settlement`, 두 소비 그룹의 실패 보관 표(공통 자동 구성).
  - 영향: **판매자 돈·고객 돈(감지)** — 매출·환불 조정 누락을 Order·Payment가 셀 수 없고, Settlement DLT가 7일 보존 뒤 사라지면 조정이 영구 누락된다.
  - 재현: 매출 기록 뒤 `outbox_event` 1행(같은 트랜잭션, 롤백 시 0행), 재전달 시 추가 0행. 대기 환불 반영 시 두 사실이 같은 키·id 순. 재발행 API로 `SENT → PENDING`.

- [ ] **SE-T7. 마감 재시도 소진 때 원래 DB 예외를 던진다**
  - 어디서: `S/core/service/CloseSellerMonth.java:120`(`throw collision`).
  - 목표: 매출·환불과 같이 5회 소진 뒤 `CONFLICT`(409). 롤백이라 행은 바뀌지 않는다.
  - 영향: 돈 영향 없음. 운영자 응답이 500일 수 있다.
  - 재현: 저장소 대역이 `DataIntegrityViolationException`을 5번 던질 때 409.

- [ ] **SE-T5. 상태·사유 칸에 CHECK가 없다(원칙 ④)**
  - 어디서: `V11:11`(`settlement_pg_statement.status`), `V11:41`(`_item.check_code`), `V12:51`(`settlement_pg_cost_posting.check_code`), `V10`(`settlement_refund.month_reason`), `V7:40`(`pg_receipt_item.mismatch_code`).
  - 목표: R9.6. design "상태 모델"의 enum 값 CHECK. 적용 전 `SELECT DISTINCT`로 다른 값이 없는지 센다.
  - 영향: 직접 돈 영향 없음. 오타 값이 확정 집계 판정(`check_code='MATCHED_REPORTED'`)에서 조용히 빠진다.
  - 재현: 각 칸에 목록 밖 값 삽입 실패.

- [ ] **MC-S2. 스펙이 늦은 `RefundExecutionSucceeded`를 "보강 사건"으로 처리한다고 쓰지만 목표는 소비하지 않는 것이다(U5)** (1회차 지적 A4·B5, 판정 전)
  - 어디서: 옛 `.kiro/specs/settlement/requirements.md:92`(R6.2), `design.md:264`·`:333`. 코드는 목표와 같다: `S/api/listener/RefundEventListener.java:47`. `settlement_refund.source`(V10 NOT NULL·CHECK), `S/core/domain/ConfirmedRefund.java:144`(`source` 필수)·`SettlementRefund.java:71`·`:87`(재전달 비교에 포함).
  - 목표: R6.2(문서는 이 초안으로 정정). T19 때 `ConfirmedRefund`에서 `source`를 빼고, `settlement_refund.source`는 NULL 허용·CHECK `source IS NULL OR source IN ('PG_RESPONSE','PG_QUERY_UNATTRIBUTED')`, 새 행 NULL. 재전달 비교에서 `source`를 뺀다.
  - 영향: 문서는 없음. T19(`schemaVersion` 2, `source` 없음) 배포 때 지금 `ConfirmedRefund`가 v2 본문을 거절해 환불 조정이 멈춘다 — **판매자 돈(배포 순서)**.
  - 재현: v2 견본(`source` 없음) 매퍼 테스트, 옛 행(`PG_RESPONSE`)에 v1 재전달 무변화.

- [ ] **SE-T4. `RefundLateCancellationConfirmed`를 소비하지 않는다(결정 157)** (플랫폼 T4, 1회차 지적 A2·B2·A5, 판정 전)
  - 어디서: `S/api/listener/RefundEventListener.java:47`(`REFUND_PAYMENT_CONFIRMED`만), `S/api/listener/SettlementRefundMapper.java`.
  - 목표: R6.1·R6.12·R6.13. 같은 그룹에서 받아 같은 `ConfirmedRefund`로 바꾸고, `settlement_refund.confirmation_event_type`(가칭)에 받은 타입을 남긴다. 같은 주문의 두 번째 금융 확인 사실은 영구 실패([결정 179](../../../docs/decisions.md#late-cancellation-during-retry)). 이벤트 본문은 Payment 모듈·플랫폼 표에서 확정 뒤.
  - 영향: **판매자 돈** — 실패 시도 뒤 실제 취소가 반영돼도 판매자 과지급이 남는다.
  - 재현: 실패 종결 → 늦은 취소 반영 → `settlement_refund` 1행(`CONFIRMED_MONTH`/`CLOSED_MONTH_ROLLOVER`), 원매출 없으면 `AWAITING_SALE`.

- [ ] **SE-T1. core에 공급자 이름·규칙이 있다(결정 140)** (플랫폼 T28)
  - 어디서: `S/core/domain/PgCostPosting.java:37`(`PORTONE_LIVE`·`LIVE`), `PgStatementCheck.java:15`·`:17`, `PgFunding.java:30-35`(`TEST`·`TOSSPAY_V2`·`TOSSPAY_EXTERNAL_BENEFIT_V1`·`PORTONE_TEST`), `PgPayment.java:10-11`, `PgStatement.java:30-31`·`PgStatementScope.java:4`(`portoneStoreId`·`portoneChannelKey`), `S/core/service/ReceivePgStatement.java:43-47`(KST 10시·1년 범위), `V9` `ck_pg_transaction_funding`의 공급자 상수.
  - 목표: R4.4·R7.16·design "adapter 메모". adapter가 중립 값(환경, 결제 구성 검증 여부, 수집 가능 판정 `AVAILABLE`·`NOT_YET_PUBLISHED`·`OUT_OF_RANGE`)을 주고 core는 그것만 비교. 저장 값·열은 호환(결정 104). 실거래 근거 수용 범위는 `[결정 필요 M-SE-3]`.
  - 영향: 돈 직접 영향 없음. 지금 `MATCHED_REPORTED` 비용 연결이 만들어질 수 없다(옛 design "찾은 불일치" 5) — 공급자를 붙이면 비용 확정·환불 경로가 막힌다.
  - 재현: ArchUnit 규칙(core에 `PortOne`·`TossPay`·공급자 상수 금지)이 지금 실패하는지. 고정 `Clock` 09:59 KST로 당일 `DUE` 요청 → adapter 판정 `NOT_YET_PUBLISHED` → 409.

- [ ] **SE-T9. 소비 매퍼가 `schemaVersion`을 보지 않는다(결정 170)**
  - 어디서: `S/api/listener/SettlementOrderMapper.java:65`, `SettlementRefundMapper.java:87`.
  - 목표: P2.7-2. (`eventType`, `schemaVersion`)으로 모양을 고르고 모르는 버전은 영구 실패.
  - 영향: 간접(T19의 v2 도입 순서).
  - 재현: `schemaVersion=99` 헤더 → 영구 실패, 헤더 없음 → 1.

- [ ] **SE-T10. 구독 토픽의 모르는 `eventType`을 지표 없이 건너뛴다** (플랫폼 T31)
  - 어디서: `S/api/listener/OrderEventListener.java:23`, `RefundEventListener.java:47`.
  - 목표: P2.3-7. (그룹, 타입) 건너뜀 지표.
  - 영향: 없음(관측).
  - 재현: `RefundExecutionSucceeded` 레코드 → 상태 무변화·지표 1.

## 진행 중 / 남음

- [x] M-SE-1 운영자 처리 — [결정 195](../../../docs/decisions.md#settlement-refund-revalidation): 원인 해소 후 운영자 재검증 명령. 구현·검증은 MC-S3의 미완료 범위다. 원매출 미도착은 원천 이벤트 복구 경로를 쓴다.
- [ ] [결정 필요] 미해결 환불이 있는 정산의 마감 허용 범위 — 결정 195와 별개로 다룬다. 현 R11.7은 유지하며 마감 차단·제외 범위를 미리 확정하지 않는다.
- [x] 같은 주문에 같은 PG 취소를 가리키는 두 번째 금융 확인 사실 — [결정 179](../../../docs/decisions.md#late-cancellation-during-retry): 지금 규칙(영구 실패) 유지, SE-T4 구현 때 두 번째 사실 거절 테스트를 둔다
- [ ] [외부 대기] PG 비용 실거래 확정: 실제 가맹점 키·거래 키 대응 검증과 `identity-mapping-verified` 활성화 — G7([결정 140](../../../docs/decisions.md#defer-real-pg))
- [ ] [결정 필요 M-SE-3] 실거래 승인 근거 수용의 도메인·스키마·조회 계약 — G7. 선행: SE-T1. 그 뒤 정상 실거래 입력 → 비용 저장·SQL 집계, 동시 첫 비용 INSERT(`uk_pg_cost_source`), INSERT 실패 롤백을 루프백 응답 서버 + MySQL Testcontainers로 검증(결정 46: 저장된 TEST 근거를 SQL로 LIVE로 바꾸는 우회 금지)
- [ ] [외부 대기] V13 이전 순서 없는 마감 자료 탐지: 마감된 월 뒤 `OPEN` 행이 남은 판매자, 원장 없는 `CLOSED` 월이 연속하며 앞쪽이 음수인 판매자를 읽기 전용 SQL로 센다(공유 환경 자료가 생기면). 행이 나오면 처리 방법을 사용자에게 묻는다 — G3
- [ ] [결정 필요 M-SE-4] 판매자 실제 송금·청구/회수 정책 — G3 이후(결정 121은 이월·지급 기준액만)
- [ ] [외부 대기] 정산 자료 수집을 실제 스택에서 켜고 런타임 검증 — G7. 가맹점 키와 거래 키 대응 검증이 있어야 한다. 정산 자료 흉내 서버는 없고 HTTP 계약은 `TossPayStatementHttpTest`의 루프백 응답 서버로만 확인
- [ ] [외부 대기] 승인·취소 읽기 adapter를 실제 PG에 연결해 확인 — G7(결정 110·111 후속)
- [ ] [외부 대기] 실제 PG 지급 수령 확인: 지급 보고 ≠ 은행 실입금을 구분한 별도 수령 계약 — G7
- [ ] G3 종료 여정([결정 142](../../../docs/decisions.md#g3-exit-current-contract)): SE-T1·SE-T2를 마친 코드로 기준 시뮬레이터 실제 스택 여정 — 원매출·환불 조정·판매자/플랫폼 순액·마감 전후 반영, 비용·지급 미확정 표시(`pgFeeAmount`·`platformNetAmount` NULL, 빈 TEST 결과를 0원으로 보지 않음). 목표에서는 PORTONE 지급 명세 503 확인 대신 지급 명세 접수 경로가 없음을 확인한다(SE-T2)
- [ ] 정방향 응답 쌍 2개(매출·환불 조정)의 경보 기준 시간과 `AWAITING_SALE` 경보 기준 — G6 실험 측정 뒤([결정 159](../../../docs/decisions.md#forward-gap-threshold)), G7 완료 조건

## 범위 밖(후속)

- 실제 은행 입금 확인·판매자 송금·지급대행, 세금계산서·원천징수·세무 회계.
- 자동 마감 배치·마감 취소/재마감·확정 금액 수정.
- 할인·쿠폰·플랫폼 보조금·복수 상품·다중 통화·판매자별 요율/계약·차지백·수수료 반환·부분 환불·부분/진행 중 취소 원매출의 지연 접수.
- 일반 정산 API 밖의 카드 차액 정산 등 별도 비용 자료 출처.
- 플랫폼 수수료(`PlatformFeePolicy`)의 정책 인터페이스화.
- 공유 스택의 구 내장 PG 원장 실제 이관(도구·전용 DB 검증만 완료), 미산정 비용의 사후 확정, 옛 `pg_receipt` 표 삭제 시점.
