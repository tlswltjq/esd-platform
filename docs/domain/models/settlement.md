# 정산 매출·월 마감·PG 수령 모델

[전체 안내](../model-guide.md) · [정산 실행](../../../.kiro/specs/settlement/design.md#사용-안내) · [MVP 기록](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/settlement-mvp-plan.md) · [책임 분리](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/settlement-separation-verification.md) · [PortOne 혜택 A](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/settlement-external-benefit-verification.md) · [Order·Payment](order-payment.md)

Settlement는 기존 OrderPaid의 불변 구매 근거를 매출로 기록한다. 판매자는 Catalog의 불변 ownerSubject,
PG 근거는 선택한 공급자에서 직접 확인한다. SIMULATED는 외부 모의 PG의 거래·비용·명세, PORTONE_TEST는 현재 TOSSPAY_V2 원승인·지급 구성을 조회한다. Payment HTTP나 다른 모듈 DB를 경유하지 않는다.

<a id="money-example"></a>
## 플랫폼 전액 부담 배분

모의 정책은 플랫폼 수수료 10%, PG 비용 3%다. 실제 사업 계약을 의미하지 않는다.

| 결제액 | 플랫폼 수수료 | 판매자 정산액 | 플랫폼 부담 PG 비용 | 플랫폼 잔여액 | PG 순지급액 |
|---:|---:|---:|---:|---:|---:|
| 10,000 | 1,000 | 9,000 | 300 | 700 | 9,700 |

판매자 몫은 결제액 − 플랫폼 수수료다. PG 비용은 플랫폼 수수료에서 부담하며 비용이 커져도 판매자 몫은 같다.
플랫폼 잔여액은 음수가 될 수 있고 다른 비용·세금 차감 전 배분 잔액이다. 회계상 순이익이 아니다.
금액은 long·KRW, 플랫폼 수수료는 BigDecimal·원 단위 HALF_UP이다. PG 비용은 공급자 확정 값을 보존하고 재계산하지 않는다.

<a id="settlement-record"></a>
## SettlementSale — 결제 완료 주문의 불변 매출

저장: settlement_sale. [모델](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/SettlementSale.java)

- OrderPaid의 13개 값 전체, sellerSubject, 플랫폼 정책·적용 요율·금액을 보존한다.
- PG 거래의 paymentId·공급자·거래 ID를 참조한다. PG 비용과 플랫폼 잔여액은 이 매출 엔티티에 저장하지 않는다.
- orderNo·원본 eventId·requestEventId·paymentResultEventId·paymentId와 PG 거래 연결은 각각 재사용을 거부한다.
- api.listener의 Mapper가 봉투·본문·키를 검증하고 PaidOrder로 변환한다. RecordPaidSale가 외부 조회 뒤 Inbox·매출·월 합계·검증한 PG 거래와 있으면 확정 비용을 같은 트랜잭션으로 저장한다.
- 같은 입력은 전체 내용 비교 후 최초 원장·금액·반영 월을 유지한다. PG·Catalog 장애 중에도 저장된 중복은 처리한다.

saleMonth는 Order가 최초 PAID를 반영한 occurredAt의 KST 월이다. settlementMonth는 실제 월 합계에 반영한 월이다.
receivedAt은 이벤트 접수 시각이고 PG 수령 시각이 아니다. PG 결정 시각은 별도로 보존한다.

<a id="seller-settlement"></a>
## SellerMonthlySettlement — 판매자별 금액 합계와 마감

저장: seller_monthly_settlement. [모델](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/SellerMonthlySettlement.java)

업무 키는 sellerSubject·settlementMonth다. OPEN 상태에서 매출과 같은 트랜잭션으로 결제액·플랫폼 수수료·판매자 몫·건수를 합산한다. PG 비용·플랫폼 잔여액은 독립된 PG 기록에서 조회 시 조합한다.
현재 KST 월보다 이전 월만 ADMIN이 마감한다. 월 행을 잠근 뒤 원장 합계와 비교하고 CLOSED·최초 closedAt을 확정한다.
마감 재요청은 최초 결과를 반환한다. 마감된 원래 월의 지연 매출은 접수 시점의 현재 월에 반영하고 원래 saleMonth를 보존한다.
CLOSED는 금액 마감이며 실제 송금 완료가 아니다. [결정 39](../../decisions.md#seller-close-independent-of-pg-cost)에 따라 PG 비용이 미확정이어도 검증된 판매자 금액을 마감한다. 늦은 비용 확인은 판매자 금액·최초 closedAt을 바꾸지 않는다.

환불 조정은 [원배분 전액 상계 계산 정책](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/settlement-refund-allocation-policy.md)과 [월 선택 정책 A](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/settlement-refund-month-policy.md)를 서비스의 잠금·[환불 원장/월 합계 원자 저장](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/settlement-refund-ledger-verification.md)에 연결했다. 기존 금액은 매출 총합으로 보존하고 양수 환불 합계 4개와 차감 후 순액 3개를 제공한다. 마감 시 두 원장 합계를 각각 대조한다. 환불만 있는 월의 순액은 음수일 수 있으며 자동 이월·지급/회수는 별도다.

## SettlementRefund — 최초 금융 확인과 불변 전액 조정

저장: settlement_refund·settlement_refund_cancellation. 원매출의 배분 정책/금액·판매자/상품 스냅샷, 최초 금융 확인·접수 시각, 환불/시도/요청·주문/결제 키, 배분/월 정책과 선택 사유, 독립 PG 조회의 성공 취소 ID/금액/시각을 보존한다. 취소 행의 제공사 시각이 없으면 NULL이며 전체 취소 시각과 구분한다. 환불 ID·주문·사건과 공급자별 취소 ID의 유일 제약으로 중복 상계를 막는다.

`RefundPaymentConfirmed`의 봉투·본문·키를 Mapper에서 검증한 뒤 내부 입력을 받는다. 원매출/원승인·전액 성공 취소/ID·합계·지급 구성을 직접 대조하고 업무 트랜잭션에서 Inbox·원장·월 환불 합계를 저장한다. 금융 확인 월이 OPEN이면 해당 월, CLOSED이면 현재 OPEN 월을 잠근다. 동일 재처리는 PG 조회나 정책 재선택 없이 최초 기록을 반환한다. 늦은 `RefundExecutionSucceeded`는 새 상계로 처리하지 않는다.

원매출이 아직 없으면 저장 없이 재시도/DLT로 남는다. 완전히 CANCELLED된 거래에서도 검증한 원승인으로 원매출을 복구한 뒤 원래 금융 확인 사건을 재처리할 수 있다. 부분 취소·미확정은 거절한다. V10은 기존 매출/거래/비용/명세/마감을 보존하고 과거 환불을 추정 생성하지 않는다. ADMIN만 취소별/금융 상세를 조회하며 CREATOR는 자기 판매자 조정 요약만 조회한다.

## PgTransactionRecord·PgFeeRecord — 승인 거래와 확인된 비용

저장: settlement_pg_transaction·settlement_pg_fee. 거래는 paymentId·orderNo·금액·통화·공급자 거래 ID/시각과 최초 접수 시각을 보존한다. `PgEvidence`는 비용 없이도 거래 근거를 표현한다. 비용은 해당 거래와 일치하는 확정 자료만 별도 행으로 저장하고 최초 확인 시각·접수 주체를 기록한다. 기존 확정 비용을 다른 관측으로 덮어쓰지 않는다.

ADMIN 거래 재조회는 이미 접수한 paymentId의 외부 PG GET만 호출한다. 거래 동일성 검증 뒤 잠금·확정 비용 저장을 조율하며 중복·동시 요청은 하나의 비용 기록으로 수렴한다. PG 조회 중 DB 업무 트랜잭션을 유지하지 않는다. 응답에서 비용이 빠져도 기존 확정 비용을 지우지 않는다.

ADMIN 매출은 `pgCostStatus`와 nullable `pgCost`·`platformNetAmount`를 제공한다. 월 조회는 CONFIRMED·UNCONFIRMED·PARTIAL과 확인된 비용 부분합·미확인 건수를 제공한다. 전체 비용·플랫폼 잔여액은 모든 비용이 확인됐을 때만 숫자다. 환불의 PG 비용 반환은 아직 미연동이므로 환불 건수를 비용 미확인에 포함하고 전체 비용/플랫폼 잔여액은 NULL로 둔다. 기존 매출 비용 부분합과 `salePgFeeAmount`를 별도로 보존한다. 확인된 0원과 비용 미확정은 구분한다. CREATOR 조회는 판매자 금액/조정만 제공한다.

PortOne 원승인에는 `PgFunding`을 embedded로 저장한다. 총액·실결제·혜택과 검증한 상점·TEST 채널·TOSSPAY_V2·외부 부담 정책을 보존하고 재조회로 덮어쓰지 않는다. [결정 40](../../decisions.md#verified-external-benefit-allocation)에 따라 확인된 외부 부담 혜택은 판매자 배분에서 차감하지 않는다. 주문 9,000원·고객 실결제 8,976원·혜택 24원이면 기존 10% 예시의 판매자는 8,100원이다. PG 비용은 별도로 미확정일 수 있다. ADMIN에만 funding을 표시한다. V9는 과거 거래에 이 근거를 소급 생성하지 않는다.

## PgReceipt·PgReceiptItem — PG 명세와 모의 수령 대사

저장: pg_receipt·pg_receipt_item. [헤더](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/PgReceipt.java) · [항목](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/PgReceiptItem.java)

ADMIN이 기존 providerSettlementId로 수집하면 외부 PG의 불변 명세 전체를 저장한다. 플랫폼에서 PG 정산 회차를 생성하지 않는다.
접수 뒤 별도 트랜잭션으로 헤더를 잠그고 각 항목의 주문·결제·거래·금액·비용을 매출·PG 거래·확정 비용과 대조한다. 거래 불일치(DIFFERENT_TRANSACTION), 비용 대기(PG_COST_PENDING), 비용 불일치(DIFFERENT_COST)를 구분한다. 명세에 비용이 있다는 이유로 내부 확정 비용을 자동 생성하지 않는다.

| 상태 | 의미 |
|---|---|
| REPORTED_PAID | 공급자가 지급했다고 보고했지만 내부 매출 미도착·비용 확인 대기 |
| MISMATCH | 내부에 존재하는 거래와 PG 근거가 다름 |
| SIMULATED_RECEIVED | 모든 항목이 일치하여 모의 수령 확정 |

공급자·정산번호와 항목의 공급자·결제번호/거래 ID에 유일 제약을 둔다. 같은 거래를 다른 명세에 접수하지 않는다.
수령 합계는 SIMULATED_RECEIVED 명세의 payoutAmount 합계다. 일부만 일치한 명세의 금액을 부분 가산하지 않는다.
재대사는 저장된 원장만 사용하고 최초 simulatedReceivedAt을 유지한다. 실제 은행 입금 증거는 후속 범위다.

## PgStatement — 토스페이 정산 API의 원본 관측·거래 정본·대사

[결정 43](../../decisions.md#tosspay-statement-api-source)에 따라 PG 명세 수집 포트·읽기 어댑터·주기 수집을 추가했다. `PgStatement`는 가맹점/환경·상점/채널·확인한 키 대응 여부, 조회 기준·일자·원본 페이지·fingerprint·합계와 수집/대사 시각을 소유한다. `PgStatementEntry`는 가맹점+환경+PG 거래 ID의 불변 결제/환불·금액·수수료·수수료 부가세·지급일이고, `PgStatementItem`은 관측과 정본의 연결 및 해당 대사 결과다. 여러 날짜 기준으로 관측해도 같은 거래는 한 정본으로 수렴한다. 수집본 목록의 합계를 모두 더해 비용으로 사용하지 않는다.

승인 행의 금액은 양수·수수료/VAT는 음수, 환불 행은 그 반대 부호로 보고한 값을 보존한다. 원본 전체 페이지·커서·합계가 검증돼야 하나의 트랜잭션으로 접수한다. 대사는 저장한 원본과 내부 구매/환불을 별도로 대조하며 미도착·불일치·환경/키 대응 미검증을 구분한다. `MATCHED_REPORTED`는 은행 실수령·판매자 송금 완료가 아니다.

현재 내부 승인 경로는 PORTONE_TEST다. TEST 빈 결과는 `EMPTY_TEST` 관측일 뿐 기존 구매의 확정 비용 0원이 아니며 LIVE 명세와 TEST 구매를 연결해 비용을 확정하지 않는다. LIVE 승인 접수와 실제 가맹점별 키 대응은 이번에 추가/검증하지 않았다. 기존 월 금융 요약은 그대로이고 PG 보고 비용/반환/VAT는 새 ADMIN 명세·항목 조회에서 확인한다. PG 비용의 월 귀속/요약 통합과 API 밖의 차액 정산 등은 후속이다. [정확한 구현·검증 범위](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/settlement-pg-evidence-verification.md)를 따른다.

## 외부 모의 PG

[독립 앱](../../../simulators/mock-pg/src/main/java/com/stove/mockpg/MockPgApplication.java)은 pg_charge·pg_settlement·pg_settlement_item을 자기 MySQL에 보존한다.
Payment는 승인·조회 키를, Settlement는 읽기 키를 사용한다. 검증용 정산 회차 제어 키는 업무 앱에 전달하지 않는다.
기존 내장 simulated_payment_charge는 이관 근거로 보존하고 신규 결정을 쓰지 않는다. 기존 미산정 비용을 0원으로 채우지 않는다.

구 settlement_record·seller_settlement·ShedLock 자료는 보존하지만 현재 업무에서는 사용하지 않는다.
V8은 기존 매출의 검증 거래·비용을 분리 복사하고 기존 비용/잔여액 열을 nullable `legacy_*`로 보존한다. 새 행의 미확정 값을 과거 열에 0원으로 채우지 않는다. 과거 명세·월 마감은 그대로 보존한다.

HTTP 조립은 공급자 설정에 따라 모의 PG 또는 PortOne TEST 원승인 읽기를 선택한다. PortOne 비용·지급 자료는 아직 미연동이며 빈 0원 결과를 만들지 않는다. 일반 할인 부담·환불 조정·재마감·세금계산서·판매자 송금·CSV는 후속 범위다.
