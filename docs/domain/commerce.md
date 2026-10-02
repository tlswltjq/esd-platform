# 구매·이용·정산

[용어집](../domain-glossary.md) · [업무 흐름](workflows.md#purchase-flow) · [미결 질문](open-questions.md)

구매자는 판매 중인 상품을 주문하고 대금을 결제한다. 결제 완료 사실을 받아 주문이 확정되고, 이용권이 지급되며, 판매자별 매출이 기록된다. 다운로드는 별도로 보유권과 공개 파일을 확인한다. 환불과 월 마감도 각각 자신이 책임지는 기록을 바꾼다.

아래 **업무 규칙 제안**은 담당자 검토 대상이다. **현재 구현**과 기존 테스트의 확인 범위는 별도로 표시한다. 인증·PG 콜백 형식은 [커머스 인증 경계](../p3-commerce-security.md), API는 [서비스 명세](../services.md)를 참고한다.

<a id="order"></a>
## 10. 주문 — Order (`Order`)

**업무 정의·맥락:** 구매자가 어떤 상품을 얼마에 구매하려는지 고정한 구매 요청이다. Order가 구매자·항목·금액·주문 상태를 책임진다. “주문을 생성한다·취소한다”로 표현하며 결제 승인과 구별한다.

**관계·경계:** 항목에는 `productId`, 상품명, 판매자, 단가, 수량과 정가·할인·행사 ID·부담 주체·정산 기준액·수수료율·수수료·판매자 지급액의 사본을 담는다. `orderNo`가 Payment·License·Settlement를 연결한다. 주문 DB의 `id`와는 별개다. 주문 생성이나 `PAID` 상태만으로 Download의 권한 반영 완료를 보장하지 않는다.

**행위·규칙 제안:** 인증된 구매자의 주문을 서버 가격으로 계산하고, 기대 금액이 있으면 비교한다. 주문 시 고정된 항목을 결제와 정산에 사용한다. 취소·승인 거절·시간 만료를 서로 다른 업무 결과로 기록한다.

**현재 구현:** Catalog에서 판매 가능 여부·가격·통화 일치를 확인하고 항목 합계로 생성한다. 견적 시각의 활성 할인 행사를 적용하고, 판매자 부담이면 할인가를, 플랫폼 부담이면 정가를 정산 기준으로 삼는다. Catalog에서 확정한 금액·수수료 사본과 통화를 주문에 보존한다. 구매자는 JWT의 회원 ID로 식별한다. `CREATED → PAID/FAILED/CANCELED/EXPIRED`, `PAID → CANCELED`가 가능하다. `PaymentCompleted`는 결제 확정, `PaymentFailed`는 승인 거절, `PaymentCancelled`는 환불 결과를 반영한다. 현재 사용자 주문 취소도 `Order.cancel()`을 호출해 `PAID`를 허용하지만 Payment는 `OrderCanceled`를 처리하지 않는다([Q9](open-questions.md#q9)).

**사례:** 10,000원 상품 1개의 기대 금액을 10,000원으로 보내면 주문할 수 있다. 1,000원으로 보내거나 판매 중지 상품을 주문하면 거절된다. 같은 상품 수량·반복 구매의 이용권 의미는 [Q5](open-questions.md#q5)에 남긴다.

**근거:** [Order](../../apps/order/src/main/java/com/stove/order/core/domain/Order.java), [PlaceOrderFacade](../../apps/order/src/main/java/com/stove/order/api/application/PlaceOrderFacade.java), [OrderCommandService](../../apps/order/src/main/java/com/stove/order/core/service/OrderCommandService.java). [PlaceOrderFacadeTest](../../apps/order/src/test/java/com/stove/order/api/application/PlaceOrderFacadeTest.java), [OrderTest](../../apps/order/src/test/java/com/stove/order/core/domain/OrderTest.java), [ProductQuoteTest](../../apps/catalog/src/integrationTest/java/com/stove/catalog/core/service/ProductQuoteTest.java)가 금액·상태·견적 조건을 검증한다. 인증은 [TrackBCommerceFlowTest](../../e2e/src/test/java/com/stove/e2e/TrackBCommerceFlowTest.java)의 `forgedOrderBodyCannotSelectAnotherOwner`.

**합의 상태:** 재검토 필요. 수량·지급 정책 [Q5](open-questions.md#q5), 취소와 환불 [Q9](open-questions.md#q9).

<a id="payment"></a>
## 11. 결제 — Payment (`Payment`)

**업무 정의·맥락:** 주문 대금의 PG 사전등록·승인·거절·환불을 추적하는 기록이다. Payment가 대금 처리 결과를 책임진다. “결제를 준비한다·승인 결과를 반영한다·환불한다”로 표현한다. PG 승인과 내부 심사 승인은 대상이 다르다.

**관계·경계:** 현재 주문번호당 결제 한 건이며 주문의 금액·구매자·항목을 보관한다. `pgTxId`는 사전등록한 PG 거래, `idempotencyKey`는 같은 주문의 콜백 중복 판정 값이다. 주문 취소 상태나 환불 요청 접수만으로 환불 완료를 단정하지 않는다.

**행위·규칙 제안:** 사전등록한 거래·금액과 일치하는 인증된 콜백만 반영한다. 승인 거절은 실패로 끝내고, 승인된 대금의 취소는 환불 완료를 확인한 뒤 확정한다. 같은 승인 재전송으로 이용권·매출을 추가 지급하지 않는다.

**현재 구현:** `READY → PENDING → PAID/FAILED`, `PAID → CANCELING → CANCELED`. 준비 가능 시간을 검사하며 `PENDING`에서 재준비할 수도 있다. PG 거래 ID·금액을 대조하고, 같은 멱등키의 PAID 콜백은 재발행하지 않는다. 멱등키는 전역 유니크가 아니다. 환불은 의도 기록→PG 호출→완료 기록 순서이고, 중단된 `CANCELING`은 재시도한다. 결제창 시간 초과 뒤 도착한 승인은 기록 후 자동 환불하며 `PaymentCompleted`를 발행하지 않는다.

**사례:** 사전등록 10,000원·거래 A에 대한 10,000원 승인은 처리한다. 거래 B 또는 9,000원 콜백은 거절한다. PG가 거절한 건은 `FAILED`이며 매출·권한 지급의 근거가 되지 않는다. 환불 중 PG 오류가 나면 완료로 표시하지 않고 `CANCELING`에서 재개한다.

**근거:** [Payment](../../apps/payment/src/main/java/com/stove/payment/core/domain/Payment.java), [PaymentService](../../apps/payment/src/main/java/com/stove/payment/core/service/PaymentService.java), [RefundFacade](../../apps/payment/src/main/java/com/stove/payment/api/application/RefundFacade.java). [PaymentTest](../../apps/payment/src/test/java/com/stove/payment/core/domain/PaymentTest.java), [PaymentGateTest](../../apps/payment/src/integrationTest/java/com/stove/payment/core/service/PaymentGateTest.java), [PaymentCheckoutWindowTest](../../apps/payment/src/integrationTest/java/com/stove/payment/core/service/PaymentCheckoutWindowTest.java), [StrandedRefundResumeTest](../../apps/payment/src/integrationTest/java/com/stove/payment/core/service/StrandedRefundResumeTest.java).

**합의 상태:** 초안. 실제 PG 연동은 [기존 #82](https://github.com/tlswltjq/esd-platform/issues/82), 취소 정책·통화 전달은 [Q9](open-questions.md#q9)의 후속 대상이다.

개발 환경에서는 [결제 시뮬레이터](../p3-payment-simulator.md)로 실제 결제 없이 승인·거절·환불 흐름을 재현한다. 시뮬레이터 결과는 실제 PG 연동 완료의 근거가 아니다.

<a id="license"></a>
## 12. 이용권 — License (`License`)

**업무 정의·맥락:** 회원에게 상품 이용 권한을 지급하고 회수한 원본 기록이다. License 서비스가 책임지며, 라이브러리는 활성 이용권을 보여준다. “이용권을 지급한다·회수한다”를 권장한다. 코드의 “라이선스”는 같은 대상을 가리키며 법적 소유권 이전이나 외부 게임 키 발급을 뜻한다고 확대하지 않는다.

**관계·경계:** `(orderNo, productId)`마다 하나의 이용권을 관리하고 `memberId`가 수혜자를 나타낸다. `licenseKey`는 현재 내부에서 생성하는 식별 문자열이다. 다운로드 URL이나 Entitlement 문서와 다르다.

**행위·규칙 제안:** 결제 완료를 근거로 지급하고 환불 완료를 근거로 회수한다. 같은 주문 상품을 중복 지급하지 않고 지급·회수 이력을 남긴다.

**현재 구현:** `PaymentCompleted` 수신 시 주문 항목의 서로 다른 상품 ID마다 `ACTIVE` 이용권을 만든다. 같은 주문·상품은 DB 유니크 제약과 Inbox로 중복을 막는다. `PaymentCancelled`는 해당 주문의 이용권을 `REVOKED`로 바꾸고 변경된 대상만 알린다. 지급의 최종 실패는 `LicenseIssueFailed`로 보상 환불을 요청한다. 일시 장애를 모두 즉시 환불로 바꾸는 것은 아니다.

**사례:** 같은 결제 이벤트가 다시 와도 이용권은 하나다. 환불로 회수된 같은 주문의 이용권을 결제 이벤트 재처리로 되살리는 것은 허용하지 않는다. 현재 수량 2개도 상품별 이용권은 한 건이므로 다중 좌석 구매 정책으로 해석할 수 없다.

**근거:** [License](../../apps/license/src/main/java/com/stove/license/core/domain/License.java), [LicenseService](../../apps/license/src/main/java/com/stove/license/core/service/LicenseService.java), [PaymentEventListener](../../apps/license/src/main/java/com/stove/license/api/listener/PaymentEventListener.java). [LicenseIdempotencyTest](../../apps/license/src/integrationTest/java/com/stove/license/core/service/LicenseIdempotencyTest.java), [LicenseIssueEventTest](../../apps/license/src/integrationTest/java/com/stove/license/core/service/LicenseIssueEventTest.java), [LicenseIssueFailureTest](../../apps/license/src/integrationTest/java/com/stove/license/core/service/LicenseIssueFailureTest.java). 실패 분류의 설계 근거는 [decisions.md 22번](../decisions.md#22-실패-분류는-두-번째-사례가-나올-때까지-license-안에-둔다).

**합의 상태:** 초안. 번들·데모·수량·재구매 정책은 [Q5](open-questions.md#q5).

<a id="entitlement"></a>
## 13. 다운로드 보유권 사본 — Entitlement (`Entitlement`)

**업무 정의·맥락:** 특정 회원이 특정 상품 파일을 다운로드할 수 있는지 판정하기 위한 이용권 사본이다. Download가 이벤트로 유지한다. “보유권 사본에 지급·회수를 반영한다”로 표현한다. “권한”만 쓸 때는 로그인 역할인지 상품 이용권인지 대상을 명시한다.

**관계·경계:** 문서 키는 `memberId:productId`이며 현재 권한의 출처인 `orderNo`를 보관한다. 원본 이용권은 주문별 여러 건일 수 있지만 사본은 회원·상품별 한 문서다. 티켓은 사본뿐 아니라 `productCode → productId/releaseId` 참조와 같은 릴리스의 매니페스트도 필요하다.

**행위·규칙 제안:** 지급·회수 사실을 다운로드에 반영하고, 회수된 옛 주문이 새 구매의 접근을 막지 않도록 한다. 권한과 배포 파일이 준비된 때만 서명 URL을 발급한다.

**현재 구현:** `LicenseIssued`는 활성 문서를 저장하고 `LicenseRevoked`는 현재 문서의 주문번호가 같을 때만 비활성화한다. 없는 권한·비활성 권한은 접근 거절이다. 공개 릴리스 참조나 매니페스트가 맞지 않으면 티켓을 발급하지 않는다. 플랫폼·아키텍처를 함께 선택하고, 일치하는 델타가 없으면 같은 환경의 전체 파일로 돌아간다. 요청마다 원본 License를 조회하지 않으므로 이벤트 반영 지연이 있다. 단순 upsert가 모든 순서 역전을 해결한다는 뜻은 아니다.

**사례:** 주문 A 환불 뒤 주문 B로 재구매했을 때 늦게 온 A의 회수 이벤트는 B의 사본을 회수하지 않는다. 보유권이 없으면 공개 매니페스트가 존재해도 티켓을 받을 수 없다. 늦은 지급 이벤트와 여러 활성 주문의 합산은 [Q5](open-questions.md#q5).

**근거:** [Entitlement](../../apps/download/src/main/java/com/stove/download/core/domain/Entitlement.java), [EntitlementService](../../apps/download/src/main/java/com/stove/download/core/service/EntitlementService.java), [DownloadTicketService](../../apps/download/src/main/java/com/stove/download/core/service/DownloadTicketService.java). [DownloadEntitlementTest](../../apps/download/src/integrationTest/java/com/stove/download/core/service/DownloadEntitlementTest.java)의 `staleRevokeMustNotAffectNewEntitlement`, `rejectsUnownedProduct`, `selectsPlatformAndDeltaWithFullFallback`.

**합의 상태:** 재검토 필요. 원본과 사본의 수량·순서 의미는 [Q5](open-questions.md#q5). 복구 절차는 [이용권 DB 유실 runbook](../runbooks/license-db-loss.md).

<a id="settlement-record"></a>
## 14. 정산 원장 항목 — Settlement Record (`SettlementRecord`)

**업무 정의·맥락:** 주문 상품 한 건에 대한 매출 또는 환불, 수수료, 판매자 몫을 기록한 원장 한 줄이다. Settlement가 책임진다. “매출을 기록한다”, “환불 원장을 추가해 상계한다”로 표현한다.

**관계·경계:** 주문번호·상품·판매자·귀속 월을 연결한다. `SALE`과 `REFUND`는 별도 항목이다. `paidAmount`는 고객 실결제액, `grossAmount`는 판매자 정산 기준액, `feeAmount`는 수수료, `netAmount`는 판매자 정산액이다. 플랫폼 부담 할인에서는 실결제액과 정산 기준액이 다르며 `platformExpense`에 플랫폼 부담액을 기록한다. 실제 송금 기록은 아니다.

**행위·규칙 제안:** 결제 당시 주문 항목으로 매출을 기록하고 환불은 원매출의 금액·수수료율로 상계한다. 중복 이벤트로 원장을 중복 집계하지 않는다. 월 마감은 포함한 원장을 표시한다.

**현재 구현:** `(orderNo, productId, recordType)`이 유일하며 Inbox도 적용한다. 같은 상품의 반복 항목은 판매자·가격·할인·수수료율 사본의 일치를 확인한 뒤 수량·금액을 합산한다. 주문에 고정된 정산 기준액·수수료율·수수료·판매자 지급액을 우선 사용하며, 그 값이 없는 이전 이벤트에는 현재 설정을 적용한다. 수수료 계산의 기본식은 `gross × rate`의 정수 단위 HALF_UP 반올림이고 `net = gross − fee`다. 환불은 원매출 금액·할인 사본을 기준으로 상계하며, 이미 마감한 매출이면 `adjustmentForMonth`에 원래 귀속 월을 기록한다. 귀속 월은 이벤트 처리 시점의 서버 `LocalDate.now()`로 정한다. 마감 시 `closed=false → true`. 부분 환불을 표현하는 별도 모델은 없다.

**사례:** 설명용 수수료율 10%, 매출 10,000원이라면 수수료 1,000원·정산액 9,000원이다. 전액 환불 항목은 각각 −10,000/−1,000/−9,000원으로 상계된다. 같은 상품의 동일 SALE을 다시 넣어 이중 매출로 기록하는 것은 허용하지 않는다. 이 예의 요율은 서비스 계약이나 기본 설정의 확정값이 아니다.

입점 수수료율 30%, 정가 10,000원·할인 2,000원 예에서는 고객이 모두 8,000원을 결제한다. 판매자 부담이면 정산 기준액 8,000원·수수료 2,400원·지급액 5,600원이다. 플랫폼 부담이면 정산 기준액 10,000원·수수료 3,000원·지급액 7,000원이고 플랫폼 부담액 2,000원을 별도로 기록한다. [PromotionLedgerTest](../../apps/settlement/src/test/java/com/stove/settlement/core/domain/PromotionLedgerTest.java), [PromotionReconciliationTest](../../apps/settlement/src/integrationTest/java/com/stove/settlement/core/service/PromotionReconciliationTest.java)가 할인 부담·주문 사본 우선·반복 항목 합산·마감 후 조정을 검증한다.

**근거:** [SettlementRecord](../../apps/settlement/src/main/java/com/stove/settlement/core/domain/SettlementRecord.java), [SettlementRecordService](../../apps/settlement/src/main/java/com/stove/settlement/core/service/SettlementRecordService.java), [FeePolicy](../../apps/settlement/src/main/java/com/stove/settlement/core/domain/FeePolicy.java). [SettlementRecordTest](../../apps/settlement/src/test/java/com/stove/settlement/core/domain/SettlementRecordTest.java)의 `refundOffsetsSale`, `feeRoundsToWon`; [SettlementIdempotencyTest](../../apps/settlement/src/integrationTest/java/com/stove/settlement/core/service/SettlementIdempotencyTest.java).

**합의 상태:** 재검토 필요. 귀속 월·부분 환불은 [Q6](open-questions.md#q6), 중복 상품 항목의 의미는 [Q5](open-questions.md#q5).

<a id="seller-settlement"></a>
## 15. 판매자 월 마감 — Seller Settlement (`SellerSettlement`)

**업무 정의·맥락:** 한 판매자의 특정 귀속 월 원장을 합산한 마감 결과다. Settlement가 합계와 계산서 번호를 관리한다. 행위는 “판매자 월 마감을 수행한다”, 결과는 “월 마감 결과”로 부른다. “확정본”이라는 기존 표현은 이후 금액 변경 가능성과 함께 읽는다.

**관계·경계:** `(sellerId, settlementMonth)`별 한 건이며 총액·수수료·정산액·원장 수를 포함한다. 최초 마감 금액은 `base*`, 후속 누적 금액은 `adjustment*`에 별도로 보존한다. 원장 한 줄이나 PG 환불 결과와 다르다. 마감, 계산서 발행, 판매자 송금은 각각 별도 업무다.

**행위·규칙 제안:** 대상 월의 미마감 원장만 합산하고 같은 원장을 두 번 반영하지 않는다. 지각 원장과 계산서 정정의 처리 기준을 정한다.

**현재 구현:** 원장 마감 표시와 판매자 합계를 같은 트랜잭션에서 갱신한다. 최초 생성 때 `closedAt`과 `base*`를 기록하며, 재마감 때 새 원장을 기존 합계와 `adjustment*`에 누적하고 최초 시각·금액은 유지한다. 대사 API는 고객 결제액·할인 부담·정산 기준·수수료·지급액·마감액 차이를 반환한다. 계산서는 DB 마감 뒤 별도 호출로 발행한다. `netAmount > 0`이고 번호가 없을 때만 발행 대상이다. 현재 MockTaxInvoiceIssuer 결과는 `taxInvoiceStatus=SIMULATED`이며 실제 발행이 아니다. 기존 번호가 있는 결과에 지각 원장을 더하면 수정 필요 로그를 남기며 자동 수정계산서 발행은 구현되어 있지 않다. 순액이 0 이하이면 발행하지 않지만, 다음 달로 옮기는 원장 생성까지 구현된 것은 아니다.

**사례:** 10월 원장 2건을 마감한 뒤 같은 10월 미마감 원장 1건이 추가되면 재마감에서 3건 합계가 된다. 기존 2건을 다시 더하지 않는다. 마감 완료를 근거로 판매자 계좌 송금도 끝났다고 표현할 수 없다.

**근거:** [SellerSettlement](../../apps/settlement/src/main/java/com/stove/settlement/core/domain/SellerSettlement.java), [SellerSettlementService](../../apps/settlement/src/main/java/com/stove/settlement/core/service/SellerSettlementService.java), [SettlementCloseFacade](../../apps/settlement/src/main/java/com/stove/settlement/api/application/SettlementCloseFacade.java). [SettlementCloseTest](../../apps/settlement/src/integrationTest/java/com/stove/settlement/core/service/SettlementCloseTest.java)의 `repeatedCloseDoesNotDuplicateSettlement`, `lateRecordShouldBeSettledOnNextClose`, `revisionDoesNotReissueTaxInvoice`.

**합의 상태:** 재검토 필요. 마감의 확정성·귀속 월·이월·송금 경계는 [Q6](open-questions.md#q6).
