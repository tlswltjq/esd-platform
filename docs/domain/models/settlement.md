# 정산 원장과 월 마감 모델

[전체 안내](../model-guide.md) · [구매 당시 금액 사본](order-payment.md#order-item)

정산은 “고객이 얼마를 냈는가”에서 더 나아가 “누가 할인을 부담했고 판매자에게 얼마를 배분할 것인가”를 기록한다. 원장 한 줄과 판매자 월 합계를 분리하며 모두 MySQL에 저장한다.

<a id="money-example"></a>
## 금액 필드부터 구별하기

정가 10,000원, 할인 2,000원, 수량 1, 입점 수수료율 30%인 설명용 사례다. 기본 설정과 같은 요율을 사용했지만 실제 사업 계약을 의미하지 않는다.

| 값 | 판매자 부담 할인 | 플랫폼 부담 할인 |
|---|---:|---:|
| 정가 합계 `listAmount` | 10,000 | 10,000 |
| 할인액 `discountAmount` | 2,000 | 2,000 |
| 고객 결제액 `paidAmount` | 8,000 | 8,000 |
| 플랫폼 부담액 `platformExpense` | 0 | 2,000 |
| **정산 기준액 `grossAmount`** | **8,000** | **10,000** |
| 수수료 `feeAmount` | 2,400 | 3,000 |
| 판매자 몫 `netAmount` | 5,600 | 7,000 |

`grossAmount`를 무조건 “고객 매출액”으로 읽으면 플랫폼 부담 할인에서 틀린다. 주문의 `settlementBasis`가 정산의 `grossAmount`, `sellerPayout`이 `netAmount`에 대응한다.

<a id="settlement-record"></a>
## SettlementRecord — 매출 또는 환불 원장 한 줄

저장: `settlement_record`. `(orderNo, productId, recordType)`별 한 건. [모델](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/SettlementRecord.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`Long`), `orderNo` (`String`), `productId`, `sellerId` (`Long`) | 원장 ID·주문·상품·판매자 |
| `saleType` (`SaleType`) | `SELF` 자체 판매 또는 `PARTNER` 입점 판매 |
| `recordType` (`RecordType`) | `SALE` 매출 또는 `REFUND` 환불. 기존 매출을 삭제하지 않고 환불 행 추가 |
| `grossAmount` (`long`) | 수수료·판매자 몫 계산의 기준액 |
| `paidAmount`, `listAmount`, `discountAmount` (`long`) | 고객 실결제액·정가 합계·할인 합계 |
| `platformExpense` (`long`) | 플랫폼이 부담한 할인액. 판매자 부담이면 0 |
| `promotionId` (`Long`), `discountBearer` (`String`) | 행사 ID·할인 부담 주체 |
| `feeRate` (`BigDecimal`), `feeAmount`, `netAmount` (`long`) | 수수료율·수수료·판매자 몫 |
| `settlementMonth` (`String`) | 귀속 월 `yyyy-MM`. 현재 이벤트 처리 서버의 날짜에서 결정 |
| `closed` (`boolean`) | 월 마감 합계에 이미 포함했는지 |
| `adjustmentForMonth` (`String`) | 이미 마감한 매출을 환불했다면 그 매출의 원래 월. 환불 자신의 귀속 월과 다를 수 있음 |

**만들고 쓰는 때:** `PaymentCompleted`의 항목 사본으로 매출을 만든다. 같은 상품의 반복 항목은 가격·할인·요율 등의 일치를 확인한 뒤 합산해 한 줄로 저장한다. 주문 사본에 정산 기준·수수료·판매자 몫이 있으면 그 값을 우선하고, 없는 이전 형식에는 설정된 정책을 사용한다.

`PaymentCancelled`가 오면 같은 주문의 SALE을 찾아 REFUND를 추가한다. 고객 금액·정가·할인·기준액·플랫폼 비용은 원장 금액의 부호를 뒤집는다. **현재 `feeAmount`·`netAmount`는 원 값을 그대로 뒤집지 않고 음수 기준액과 요율로 다시 계산한다.** 따라서 반복 항목의 반올림된 수수료를 합산했던 경우 완전 상계가 어긋날 수 있다. [Q12](../open-questions.md#q12)에 정적 추적 반례를 기록했다.

부분 환불 횟수·수량을 표현하는 구조는 없다. 같은 상품의 REFUND는 하나이므로 현재 모델은 주문 상품별 전액 환불을 전제로 읽는다. [SettlementRecordService](../../../apps/settlement/src/main/java/com/stove/settlement/core/service/SettlementRecordService.java)

**업무 규칙 제안:** 결제 당시 주문 항목으로 매출을 기록하고 환불은 원매출의 금액·수수료율로 상계한다. 중복 이벤트로 원장을 중복 집계하지 않는다. 월 마감은 포함한 원장을 표시한다.

**사례:** 설명용 수수료율 10%, 매출 10,000원이라면 수수료 1,000원·정산액 9,000원이다. 전액 환불 항목은 각각 −10,000/−1,000/−9,000원으로 상계된다. 같은 상품의 동일 SALE을 다시 넣어 이중 매출로 기록하는 것은 허용하지 않는다. 이 예의 요율은 서비스 계약이나 기본 설정의 확정값이 아니다.

**합의 상태:** 재검토 필요. 귀속 월·부분 환불은 [Q6](../open-questions.md#q6), 중복 상품 항목의 의미는 [Q5](../open-questions.md#q5).

**검증 근거:** [SettlementRecord](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/SettlementRecord.java), [SettlementRecordService](../../../apps/settlement/src/main/java/com/stove/settlement/core/service/SettlementRecordService.java), [FeePolicy](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/FeePolicy.java). [SettlementRecordTest](../../../apps/settlement/src/test/java/com/stove/settlement/core/domain/SettlementRecordTest.java)의 `refundOffsetsSale`, `feeRoundsToWon`; [SettlementIdempotencyTest](../../../apps/settlement/src/integrationTest/java/com/stove/settlement/core/service/SettlementIdempotencyTest.java).

<a id="seller-settlement"></a>
## SellerSettlement — 판매자 한 명의 한 달 마감

저장: `seller_settlement`. `(sellerId, settlementMonth)`별 한 건. [모델](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/SellerSettlement.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id`, `sellerId` (`Long`), `settlementMonth` (`String`) | 마감 ID·판매자·귀속 월 |
| `grossAmount`, `feeAmount`, `netAmount` (`long`) | 현재까지 마감에 포함한 기준액·수수료·판매자 몫 합계 |
| `baseGrossAmount`, `baseFeeAmount`, `baseNetAmount` (`long`) | 최초 마감 당시 세 금액. 이후 재마감에도 보존 |
| `adjustmentGrossAmount`, `adjustmentFeeAmount`, `adjustmentNetAmount` (`long`) | 첫 마감 이후 추가로 포함한 금액 |
| `recordCount` (`int`) | 포함한 원장 행 수. 상품 수·주문 수와 다름 |
| `taxInvoiceNo` (`String`) | 계산서 발행 어댑터가 반환한 번호. 현재는 대역 발행 |
| `closedAt` (`Instant`) | 최초 마감 시각. 재마감 시각으로 덮어쓰지 않음 |

**만들고 쓰는 때:** 해당 판매자·월의 미마감 원장을 합산하고 `closed=true`로 표시한다. 기존 월 마감이 있으면 추가 금액을 합계와 `adjustment*`에 누적한다. 원장 합계 저장, 외부 계산서 발행, 번호 저장을 분리해 재실행할 수 있게 한다. [SellerSettlementService](../../../apps/settlement/src/main/java/com/stove/settlement/core/service/SellerSettlementService.java), [SettlementCloseFacade](../../../apps/settlement/src/main/java/com/stove/settlement/api/application/SettlementCloseFacade.java)

예를 들어 첫 마감의 `baseNetAmount=7000`이고 같은 월에 뒤늦게 −7000의 환불 원장을 추가로 마감하면 `adjustmentNetAmount=-7000`, `netAmount=0`이 된다. 다음 달 환불이면 환불은 다음 달 원장이고 `adjustmentForMonth`가 원래 월을 가리킨다. 모든 지각 환불이 과거 월의 합계를 직접 바꾼다는 뜻은 아니다.

계산서 번호가 없고 순액이 양수일 때 발행을 시도한다. 응답의 `taxInvoiceStatus=SIMULATED`는 계산서 번호 유무로 만든 표시 값이며 이 엔티티의 저장 필드가 아니다. 실제 은행 송금·수정계산서·다음 달 이월 원장은 구현된 것으로 설명하지 않는다. [Q6](../open-questions.md#q6)

**업무 규칙 제안:** 대상 월의 미마감 원장만 합산하고 같은 원장을 두 번 반영하지 않는다. 지각 원장과 계산서 정정의 처리 기준을 정한다.

**사례:** 10월 원장 2건을 마감한 뒤 같은 10월 미마감 원장 1건이 추가되면 재마감에서 3건 합계가 된다. 기존 2건을 다시 더하지 않는다. 마감 완료를 근거로 판매자 계좌 송금도 끝났다고 표현할 수 없다.

**합의 상태:** 재검토 필요. 마감의 확정성·귀속 월·이월·송금 경계는 [Q6](../open-questions.md#q6).

**검증 근거:** [SellerSettlement](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/SellerSettlement.java), [SellerSettlementService](../../../apps/settlement/src/main/java/com/stove/settlement/core/service/SellerSettlementService.java), [SettlementCloseFacade](../../../apps/settlement/src/main/java/com/stove/settlement/api/application/SettlementCloseFacade.java). [SettlementCloseTest](../../../apps/settlement/src/integrationTest/java/com/stove/settlement/core/service/SettlementCloseTest.java)의 `repeatedCloseDoesNotDuplicateSettlement`, `lateRecordShouldBeSettledOnNextClose`, `revisionDoesNotReissueTaxInvoice`.

## FeePolicy — 수수료 판단 규칙

[모델](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/FeePolicy.java). config가 전달한 자체 판매자 ID·수수료율을 사용해 자체 판매자 ID이면 SELF·0%, 그 외이면 PARTNER·설정된 요율(기본 30%)로 판단한다. 별도 수수료 계약 테이블은 아니다. 현재 주문에 들어가는 배분 조건은 Catalog 견적에서도 계산하며, Settlement에서는 과거 사본이 없는 자료의 보완 기준으로 이 정책을 사용한다.

## ReconciliationResult — 원장과 마감의 대사 결과

“대사”는 두 기록의 금액·건수가 맞는지 비교하는 작업이다. [모델](../../../apps/settlement/src/main/java/com/stove/settlement/core/domain/ReconciliationResult.java)은 조회 때 계산하는 record이며 저장 엔티티가 아니다.

| 속성 | 뜻과 사용처 |
|---|---|
| `sellerId`, `month` | 비교 대상 판매자·월 |
| `customerPaid`, `sellerDiscount`, `platformExpense` | 고객 결제·판매자 할인 부담·플랫폼 비용 합계 |
| `settlementBasis`, `fee`, `sellerPayout` | 정산 기준·수수료·판매자 몫 합계 |
| `recordCount`, `unclosedCount`, `adjustmentCount` | 전체·미마감·원래 마감 월을 참조하는 조정 원장 수 |
| `closingBasisVariance`, `closingFeeVariance`, `closingPayoutVariance` | 마감 결과와 마감된 원장 합계의 차이 |
| `balanced`, `warnings` | 검사한 식·건수·마감 차이가 모두 맞는지와 경고 목록 |

**만들고 쓰는 때:** 운영자가 월별 정산을 확인하거나 CSV로 내보낼 때 원장·마감 합계를 읽어 계산한다. `balanced=true`는 이 서비스 내부의 검사 결과다. 외부 PG 입금·은행 송금과 맞췄다는 뜻도, 모든 환불이 원 매출을 정확히 상계한다는 뜻도 아니다. [ReconciliationService](../../../apps/settlement/src/main/java/com/stove/settlement/core/service/ReconciliationService.java)

기존 검증: [PromotionLedgerTest](../../../apps/settlement/src/test/java/com/stove/settlement/core/domain/PromotionLedgerTest.java), [PromotionReconciliationTest](../../../apps/settlement/src/integrationTest/java/com/stove/settlement/core/service/PromotionReconciliationTest.java), [SettlementCloseTest](../../../apps/settlement/src/integrationTest/java/com/stove/settlement/core/service/SettlementCloseTest.java). 반올림 합산 후 환불 반례는 이 테스트들의 현재 예시와 구별한다.
