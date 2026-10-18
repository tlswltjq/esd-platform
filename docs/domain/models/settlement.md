# 정산 매출·월 마감·PG 수령 모델

[전체 안내](../model-guide.md) · [정산 실행](../../settlement-guide.md) · [범위·검증](../../settlement-mvp-plan.md) · [Order·Payment](order-payment.md)

Settlement는 기존 OrderPaid의 불변 구매 근거를 매출로 기록한다. 판매자는 Catalog의 불변 ownerSubject,
PG 거래·비용과 지급 명세는 외부 모의 PG에서 직접 확인한다. Payment HTTP나 다른 모듈 DB를 경유하지 않는다.

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
- PgCost에 공급자·거래 ID·결정 시각·PG 정책·요율·비용을 보존한다.
- orderNo·원본 eventId·requestEventId·paymentResultEventId·paymentId와 PG 거래 연결은 각각 재사용을 거부한다.
- api.listener의 Mapper가 봉투·본문·키를 검증하고 PaidOrder로 변환한다. RecordPaidSale가 외부 조회 뒤 Inbox·매출·월 합계를 같은 트랜잭션으로 저장한다.
- 같은 입력은 전체 내용 비교 후 최초 원장·금액·반영 월을 유지한다. PG·Catalog 장애 중에도 저장된 중복은 처리한다.

saleMonth는 Order가 최초 PAID를 반영한 occurredAt의 KST 월이다. settlementMonth는 실제 월 합계에 반영한 월이다.
receivedAt은 이벤트 접수 시각이고 PG 수령 시각이 아니다. PG 결정 시각은 별도로 보존한다.

<a id="seller-settlement"></a>
## SellerMonthlySettlement — 판매자별 금액 합계와 마감

저장: seller_monthly_settlement. [모델](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/SellerMonthlySettlement.java)

업무 키는 sellerSubject·settlementMonth다. OPEN 상태에서 매출과 같은 트랜잭션으로 결제액·플랫폼 수수료·판매자 몫·PG 비용·플랫폼 잔여액·건수를 합산한다.
현재 KST 월보다 이전 월만 ADMIN이 마감한다. 월 행을 잠근 뒤 원장 합계와 비교하고 CLOSED·최초 closedAt을 확정한다.
마감 재요청은 최초 결과를 반환한다. 마감된 원래 월의 지연 매출은 접수 시점의 현재 월에 반영하고 원래 saleMonth를 보존한다.
CLOSED는 금액 마감이며 실제 송금 완료가 아니다. PG 수령 확인 전에도 마감할 수 있다.

## PgReceipt·PgReceiptItem — PG 명세와 모의 수령 대사

저장: pg_receipt·pg_receipt_item. [헤더](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/PgReceipt.java) · [항목](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/PgReceiptItem.java)

ADMIN이 기존 providerSettlementId로 수집하면 외부 PG의 불변 명세 전체를 저장한다. 플랫폼에서 PG 정산 회차를 생성하지 않는다.
접수 뒤 별도 트랜잭션으로 헤더를 잠그고 각 항목의 주문·결제·거래·금액·비용을 SettlementSale과 대조한다.

| 상태 | 의미 |
|---|---|
| REPORTED_PAID | 공급자가 지급했다고 보고했지만 내부 매출 미도착 등으로 대기 |
| MISMATCH | 내부에 존재하는 거래와 PG 근거가 다름 |
| SIMULATED_RECEIVED | 모든 항목이 일치하여 모의 수령 확정 |

공급자·정산번호와 항목의 공급자·결제번호/거래 ID에 유일 제약을 둔다. 같은 거래를 다른 명세에 접수하지 않는다.
수령 합계는 SIMULATED_RECEIVED 명세의 payoutAmount 합계다. 일부만 일치한 명세의 금액을 부분 가산하지 않는다.
재대사는 저장된 원장만 사용하고 최초 simulatedReceivedAt을 유지한다. 실제 은행 입금 증거는 후속 범위다.

## 외부 모의 PG

[독립 앱](../../../simulators/mock-pg/src/main/java/com/stove/mockpg/MockPgApplication.java)은 pg_charge·pg_settlement·pg_settlement_item을 자기 MySQL에 보존한다.
Payment는 승인·조회 키를, Settlement는 읽기 키를 사용한다. 검증용 정산 회차 제어 키는 업무 앱에 전달하지 않는다.
기존 내장 simulated_payment_charge는 이관 근거로 보존하고 신규 결정을 쓰지 않는다. 기존 미산정 비용을 0원으로 채우지 않는다.

구 settlement_record·seller_settlement·ShedLock 자료는 보존하지만 현재 업무에서는 사용하지 않는다.
환불·할인 부담·재마감·세금계산서·판매자 송금·CSV는 이번 MVP에 없다.
