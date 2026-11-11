# 요구사항: Settlement (매출 배분·판매자 월 마감·환불 조정·PG 비용과 정산 자료 대사)

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다. 이 문서는 **목표 계약**이며, 지금 코드와 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"에 있다. `[결정 필요 M-SE-n]`은 정책이 정해지지 않은 자리다(배경은 초안 메모). `(1회차 지적 Xn, 판정 전)`은 플랫폼 tasks "독립 평가 1회차 지적"이 걸린 곳이며 고친 것으로 치지 않는다(결정 178).

## 개요

Settlement는 결제 완료 주문 사실을 받아 판매자 매출을 한 번 기록하고 플랫폼 수수료·판매자 몫을 배분한다. 전액 환불 금융 확인을 받아 원배분을 음수로 상계하고, 판매자·월 합계를 오래된 월부터 `OPEN → CLOSED`로 마감하며 음수 순액을 다음 마감으로 이월한다. PG 거래·취소·비용·정산 자료는 Settlement 소유 `core.port`와 `adapter`로 PG에서 직접 읽는다(Payment 경유 없음). PG 비용의 정본은 Settlement 하나이고 플랫폼이 전액 부담한다([결정 38](../../../docs/decisions.md)·[39](../../../docs/decisions.md#seller-close-independent-of-pg-cost)·[165](../../../docs/decisions.md#pg-fee-owner)).

Settlement는 자기 반영 사실(매출 기록·환불 조정 기록)을 Outbox로 내어, 사실을 낸 모듈(Order·Payment)이 응답을 받지 못한 자기 기록을 셀 수 있게 한다([결정 158](../../../docs/decisions.md#forward-gap-counting)).

모듈을 넘는 흐름: 흐름 2 S11(매출 배분), 흐름 3 S13(정산 조정·이월)·S14(늦은 취소 반영) — [플랫폼 design "모듈 간 흐름"](../platform/design.md#모듈-간-흐름). 이 모듈이 함께 지키는 돈 불변식: I2·I4·I5·I6·I7·I8·I13·I14([플랫폼 design "불변식 표"](../platform/design.md#불변식-표-i1--i20)).

## 용어

- **원매출(`SettlementSale`)**: 결제 완료 주문 하나의 불변 매출 기록. 주문 사실 값, 판매자, 배분, PG 승인 근거 연결, 귀속 월·반영 월을 담는다.
- **판매자(`sellerSubject`)**: 상품 소유자. Catalog가 정본인 불변 `ownerSubject`이며 매출 기록 때 고정한다([P1.4-6](../platform/requirements.md)).
- **귀속 월 / 반영 월**: 귀속 월은 사실이 생긴 금융 확인 시각의 KST 월(매출 `saleMonth`, 환불 `refundMonth`). 반영 월(`settlementMonth`)은 실제로 합계에 더한 월이다. 귀속 월이나 그 뒤 월이 마감됐으면 반영 월은 현재 KST `OPEN` 월이다([결정 42](../../../docs/decisions.md#settlement-refund-posting-month)·[130](../../../docs/decisions.md#seller-close-order-carryover)·[166](../../../docs/decisions.md#sale-month-by-payment-confirmation)).
- **환불 조정(`SettlementRefund`)**: 전액 환불 금융 확인 하나로 원매출의 배분을 그대로 음수 상계한 불변 기록.
- **매출 대기 환불(`AwaitingSaleRefund`, 가칭)**: 원매출보다 먼저 도착한 환불 금융 확인을 명시 상태로 보관한 기록([결정 156](../../../docs/decisions.md#refund-awaiting-sale)).
- **금융 확인 사실**: Payment의 `RefundPaymentConfirmed`(원결제 전액 취소를 처음 확인)와 `RefundLateCancellationConfirmed`(실패로 끝난 시도 뒤 ADMIN이 PG 근거로 전액 취소를 확인, [결정 157](../../../docs/decisions.md#late-cancellation-confirmation)). Settlement는 둘을 같은 내부 입력으로 바꾼다.
- **플랫폼 잔여액(`platformNetAmount`)**: 플랫폼 수수료 − 확인된 PG 비용. 음수일 수 있고 회계상 순이익이 아니다.
- **이월(`carriedIn`·`payable`·`carriedOut`)**: 마감 때 직전 마감 월에서 받은 음수, 지급 기준액(≥ 0), 다음 마감에 넘기는 음수([결정 121](../../../docs/decisions.md#seller-negative-net-carryover)).
- **PG 정산 자료**: PG가 보고한 거래별 수수료·VAT·지급일 자료. 보고 지급일은 은행 실수령이 아니다.
- **정본 거래(`PgStatementEntry`)**: (가맹점, 환경, PG 거래 ID)마다 한 행인 정산 자료 거래.
- **거래 키 대응 검증**: PG 승인 근거의 거래·주문 키와 정산 자료의 거래·주문 키가 같은 거래를 가리킨다는 검증. 검증 전에는 비용을 확정 근거로 쓰지 않는다.
- **환경(`TEST`·`LIVE`)**: PG 근거가 시험 거래인지 실거래인지. 시험 거래의 비용은 확정하지 않는다.

## 요구사항

### R1. 매출 접수와 원장 원자성

**사용자 스토리:** 플랫폼 운영자로서, 결제 완료 주문을 판매자 매출로 빠짐없이 한 번만 기록하고 싶다.

#### 수용 기준

1. WHEN `settlement-paid-order` 그룹이 `OrderPaid`([design "이벤트 목록" #17](../platform/design.md#eventtype-상수-42개--현행목표-분리안))를 받으면 THEN Settlement SHALL `SettlementOrderMapper.from`에서 봉투·본문의 `eventId`·`eventType`·키(`orderNo`)가 같은지, (`eventType`, `schemaVersion`)이 아는 모양인지 확인하고 주문 사실 값과 선택 칸 `paymentConfirmedAt`(가칭)을 내부 입력 `PaidOrder`로 바꾼다([P2.1](../platform/requirements.md)·[P2.7-2](../platform/requirements.md)).
2. WHEN 새 매출을 접수하면 THEN Settlement SHALL DB 트랜잭션 밖에서 PG 승인 근거와 Catalog 불변 소유자를 조회·대조한 뒤, 판매자 잠금 아래 Inbox·`SettlementSale`·PG 승인 근거·판매자 월 합계·매출 기록 확인 사실 Outbox(R12)를 한 트랜잭션에 저장한다([P3.15-3](../platform/requirements.md)).
3. WHILE PG·Catalog를 조회하는 동안 THE Settlement SHALL 업무 트랜잭션·판매자 잠금·월 잠금을 잡지 않는다([P1.2-4](../platform/requirements.md)).
4. WHEN 같은 주문의 이벤트가 같은 본문으로 다시 오면 THEN Settlement SHALL 기존 원장·월 합계·시각을 유지하고 새 Outbox 행을 쓰지 않으며, PG·Catalog가 중단돼도 외부 조회 없이 처리한다.
5. IF 같은 주문에 다른 `eventId`·사실 시각·본문이 오거나, `eventId`·`requestEventId`·`paymentResultEventId`·`paymentId`를 다른 주문에 다시 쓰면 THEN Settlement SHALL 덮어쓰지 않고 영구 실패로 거절한다([P2.3-4](../platform/requirements.md)).
6. IF PG·Catalog 조회가 실패하거나 PG 승인이 주문과 다르면(결제 키·금액·통화·상태) THEN Settlement SHALL 원장·Inbox·월 합계를 만들지 않는다. 조회 실패는 재시도 가능 실패, 값 불일치는 영구 실패다([P3.15-4](../platform/requirements.md)).
7. WHEN 재시도를 소진하거나 영구 실패가 나면 THEN Settlement SHALL 공통 실패 보관 표에 레코드를 원본 그대로 저장하고 그 키를 정지하며, ADMIN 재투입은 같은 리스너 경로로 다시 처리한다([P2.6-4·5](../platform/requirements.md), [결정 160](../../../docs/decisions.md#dead-letter-store)). 자동 복구로 설명하지 않는다.

### R2. 플랫폼 전액 부담 배분과 금액 무결성

**사용자 스토리:** 운영자로서, 판매자 몫이 고객 결제액 기준으로 정해지고 PG 비용에 흔들리지 않기를 원한다.

#### 수용 기준

1. WHEN 매출을 배분하면 THEN Settlement SHALL `platformFeeAmount = paidAmount × platformFeeRate`(원 단위 HALF_UP), `sellerPayoutAmount = paidAmount − platformFeeAmount`로 계산한다.
2. THE 플랫폼 수수료 기준 SHALL 고객 결제액(`OrderPaid.amount`)이며, PG 비용을 먼저 뺀 금액을 기준으로 삼지 않는다(결정 38·39).
3. WHEN PG 비용이 확인되면 THEN Settlement SHALL `platformNetAmount = platformFeeAmount − pgFeeAmount`로 보이고, 음수여도 0으로 보정하거나 판매자에게 전가하지 않는다.
4. THE 모든 금액 SHALL 정수 `long`·KRW이며, 비율 계산은 BigDecimal·HALF_UP 뒤 정확한 정수 변환을 쓰고 누적 합계·건수의 오버플로를 거절한다.
5. WHERE PG 근거로 외부 부담이 확인된 결제 혜택이 있는 거래에서 THE Settlement SHALL 총액·실결제·혜택을 보존하고 주문 금액으로 배분하며, 부담 주체가 확인되지 않은 혜택이나 플랫폼·판매자 쿠폰에 적용하지 않는다([결정 40](../../../docs/decisions.md#verified-external-benefit-allocation)).
6. THE 플랫폼 수수료 정책 SHALL `PLATFORM_MVP_V1`(1,000bps = 10%)의 고정 규칙이며 config가 조립하고, 운영 중 요율 변경 기능을 두지 않는다.

### R3. 귀속 월·수동 마감·지연 거래 반영

**사용자 스토리:** 운영자로서, 지난 월의 판매자 정산액을 확정 마감하고, 늦게 온 거래도 규칙대로 반영되기를 원한다.

#### 수용 기준

1. THE 매출 귀속 월 `saleMonth` SHALL `OrderPaid.paymentConfirmedAt`(가칭, Payment 승인 확정 시각)의 Asia/Seoul yyyy-MM이고, 그 칸이 없는 옛 `OrderPaid`는 `occurredAt`의 Asia/Seoul yyyy-MM이다. 서버 기본 시간대·Settlement 수신 시각으로 정하지 않는다([결정 166](../../../docs/decisions.md#sale-month-by-payment-confirmation), [P3.15-3](../platform/requirements.md), 1회차 지적 A6, 판정 전).
2. WHEN ADMIN이 판매자·월 마감을 요청하면 THEN Settlement SHALL Settlement 시계의 현재 KST 월보다 이전 월만 받고, 판매자 잠금과 월 행 잠금 아래 원장 합계·건수를 대조해 `CLOSED`·`closedAt`을 함께 확정한다.
3. IF 현재·미래 월 마감을 요청하면 THEN Settlement SHALL `CONFLICT`(409)로 거절한다.
4. WHEN 이미 `CLOSED`인 판매자·월의 마감을 다시 요청하면 THEN Settlement SHALL 최초 금액·`closedAt`·이월 값을 그대로 반환하고 다시 집계하지 않는다.
5. IF 반영할 사실의 귀속 월이 `CLOSED`이거나 같은 판매자의 더 뒤 월이 `CLOSED`이면(귀속 월에 행이 없어도) THEN Settlement SHALL 판매자 잠금 아래 현재 KST `OPEN` 월에 반영하고, 귀속 월 행은 만들지 않으며 귀속 월을 보존한다([결정 42](../../../docs/decisions.md#settlement-refund-posting-month)·[130](../../../docs/decisions.md#seller-close-order-carryover), [P4.4-2](../platform/requirements.md)).
6. WHERE 매출·환불 반영과 판매자 월 마감에서 THE Settlement SHALL 먼저 판매자 잠금 행을 잠가 같은 판매자의 월 판정·행 생성·마감을 직렬화하고, 둘 이상의 월 행은 판매자·월 순서로 잠근다.
7. THE 월 마감 SHALL PG 비용 확정·PG 정산 자료 대사와 독립이며, `CLOSED`를 PG 입금이나 판매자 송금 완료로 해석하지 않는다([결정 39](../../../docs/decisions.md#seller-close-independent-of-pg-cost), [P4.4-3](../platform/requirements.md)).
8. IF 같은 판매자의 M보다 이전 월에 `OPEN` 행이 있는데 M 마감을 요청하면 THEN Settlement SHALL `CONFLICT`("이전 월을 먼저 마감해야 합니다")로 거절하고 아무 행도 바꾸지 않는다. 행이 없는 월은 순서 검사에서 건너뛴다. IF M이 `CLOSED`가 아닌데 같은 판매자의 M보다 뒤 월이 `CLOSED`이면 THEN Settlement SHALL `CONFLICT`("이후 월이 이미 마감됐습니다")로 거절하고 M 행을 만들지 않는다([결정 130](../../../docs/decisions.md#seller-close-order-carryover)).
9. WHEN 판매자 월 M을 마감하면 THEN Settlement SHALL 같은 트랜잭션에서 `carriedIn` = M보다 이전의 마지막 `CLOSED` 월의 `carriedOut`(없으면 0)으로 두고, `netSellerPayoutAmount + carriedIn`이 음수면 `payable = 0`·`carriedOut =` 그 값, 0 이상이면 `payable =` 그 값·`carriedOut = 0`인 이월 원장 한 행을 쓴다([결정 121](../../../docs/decisions.md#seller-negative-net-carryover), [P3.26-3](../platform/requirements.md)). 송금·청구·회수는 하지 않는다.
10. THE 이월 원장 SHALL `(sellerSubject, settlementMonth)`마다 최대 한 행이고, 같은 판매자의 출처 월을 한 번만 받으며, 삽입 뒤 바뀌지 않는다. 원장 행이 없는 옛 `CLOSED` 월(V13 이전 마감)은 `carriedIn = 0`으로 읽고, 다음 월 마감은 그 월의 `min(0, 순액)`을 이월로 받는다.
11. WHEN 행이 없는 지난 월 M을 순서 검사를 통과해 마감하면 THEN Settlement SHALL 합계 0의 `CLOSED` 행과 이월 원장 한 행(`carriedOut = carriedIn`)을 만든다. 그 뒤 M 이전 월의 늦은 사실은 5에 따라 현재 월에 반영된다.
12. THE 반영 월 SHALL 귀속 월보다 앞서지 않는다(`settlementMonth ≥ saleMonth`, `settlementMonth ≥ refundMonth`). Settlement는 자기 수신 시각이 금융 확인 시각보다 이르다는 이유로 사실을 거절하지 않는다([P3.26-2](../platform/requirements.md), [P4.5-1·2](../platform/requirements.md), U2 SET-1).

### R4. PG·Catalog 직접 조회 경계

**사용자 스토리:** 운영자로서, PG 근거를 Payment를 거치지 않고 Settlement가 직접 확인하기를 원한다.

#### 수용 기준

1. THE Settlement SHALL `core.port`의 `PgPayments`(승인 근거)·`PgRefunds`(취소 근거)·`PgStatementSource`(정산 자료)와 각 `adapter`로 PG를 직접 조회하고, Payment HTTP·DB를 거치지 않는다([P1.1-3](../platform/requirements.md)).
2. WHEN 상품 소유자를 확인하면 THEN Settlement SHALL Catalog 내부 `GET /internal/v1/products/{productCode}/owner`를 Settlement 전용 서비스 키로만 호출하고 `ownerSubject`를 `sellerSubject`로 매출 행에 고정하며 다시 조회하지 않는다([design "동기 의존"](../platform/design.md#동기-의존-http)).
3. THE Settlement SHALL 다른 모듈 DB를 읽지 않는다([P1.1-2](../platform/requirements.md)).
4. THE PG 응답 검증 SHALL adapter가 공급자 형식(숫자 문자열·누락·중복 키·후행 JSON·다른 상점/채널/구매)을 엄격히 거절하고, core는 중립 값(결제 키·금액·통화·상태·거래 ID·환경·결제 구성)만 받는다([P1.1-4](../platform/requirements.md), 결정 140).
5. THE 승인 근거 조회 SHALL PG 비용을 확정하지 않으며, 비용이 없으면 `pgCostStatus=UNCONFIRMED`·비용 NULL을 유지한 채 판매자 월 마감을 허용한다([P4.4-3](../platform/requirements.md)).
6. THE 모든 실행 환경(개발·여정·실험·통합 테스트) SHALL 같은 `PgPayments`·`PgRefunds` 계약을 쓴다. 시뮬레이터 전용 공급자 분기, 근거를 만들지 못한다고 거절하는 대체 구현, 검증 생략 경로를 두지 않는다([결정 162](../../../docs/decisions.md#single-reference-simulator), [P3.26-5](../platform/requirements.md)).
7. IF 시작 설정이 불완전하거나 지원하지 않는 공급자를 고르면 THEN Settlement SHALL 시작을 거절하고, 다른 공급자로 자동 대체하거나 `TEST` 근거를 `LIVE`로 바꾸지 않는다.

### R5. 옛 지급 명세 기록(읽기 전용)

**사용자 스토리:** 운영자로서, 시뮬레이터 시절에 접수한 지급 명세 기록을 계속 볼 수 있기를 원한다.

#### 수용 기준

1. THE Settlement SHALL 새 지급 명세(`PgReceipt`) 접수와 재대사 경로를 두지 않는다. 이 경로는 시뮬레이터 전용 공급자에만 있던 것이다([결정 162](../../../docs/decisions.md#single-reference-simulator)). 실제 PG의 지급 자료는 R7이 다룬다.
2. WHEN ADMIN이 저장된 지급 명세 목록·상세를 조회하면 THEN Settlement SHALL 저장된 헤더·항목·대사 결과·최초 수령 시각을 그대로 보이고 바꾸지 않는다([결정 104](../../../docs/decisions.md) 옛 자료 호환).
3. THE 저장된 지급 명세 SHALL 판매자 월 합계·마감·PG 비용 확정에 쓰이지 않는다.

### R6. 전액 환불 원배분 상계와 환불 반영 월

**사용자 스토리:** 운영자로서, 전액 환불이 확정되면 원매출의 판매자·플랫폼 배분을 그대로 상계하고 싶다.

#### 수용 기준

1. WHEN `settlement-confirmed-refund` 그룹이 금융 확인 사실(`RefundPaymentConfirmed` [#28](../platform/design.md#eventtype-상수-42개--현행목표-분리안), `RefundLateCancellationConfirmed` [목표 행](../platform/design.md#목표-행-아직-코드에-없음))을 받으면 THEN Settlement SHALL `api.listener`에서 봉투·본문·키를 검증하고 두 사실을 같은 내부 입력(`ConfirmedRefund`)으로 바꾸되, 받은 `eventType`을 Inbox에 그대로 남긴다([P2.1-6](../platform/requirements.md)).
2. THE Settlement SHALL `RefundExecutionSucceeded`·`RefundExecutionFailed`·`RefundFailureClosed`를 소비하지 않는다. 정산 금액은 첫 금융 확인 사실 하나로 정해지고, 취소 귀속은 Settlement 자신의 `PgRefunds` 조회 근거로 기록한다([P3.26-4](../platform/requirements.md)·[P4.3-3](../platform/requirements.md), U5, 1회차 지적 A4·B5, 판정 전).
3. WHEN 전액 환불을 배분하면 THEN Settlement SHALL `FULL_REFUND_REVERSE_ORIGINAL_V1`로 원매출의 금액·플랫폼 수수료·판매자 몫을 그대로 음수 상계하고, 현재 요율로 다시 계산하거나 외부 혜택만큼 판매자 몫을 줄이지 않는다([결정 41](../../../docs/decisions.md#settlement-full-refund-allocation), I2).
4. WHEN 원매출이 있는 환불을 기록하면 THEN Settlement SHALL 트랜잭션 밖에서 `PgRefunds`로 같은 결제를 조회해 전액 취소·성공 취소 ID 전체·합계·원승인 결제 구성을 확인한 뒤, 원매출 잠금 → 판매자 잠금 아래 Inbox·환불 조정·취소 근거·월 환불 합계·환불 조정 확인 사실 Outbox(R12)를 한 트랜잭션에 저장한다([P3.26-1](../platform/requirements.md)).
5. WHERE 환불 반영 월을 고를 때 THE Settlement SHALL `REFUND_CONFIRMED_MONTH_V1`로 확인 월(R6.13)이 `OPEN`이고 뒤 월에 `CLOSED`가 없으면 그 월(`CONFIRMED_MONTH`), 아니면 현재 KST `OPEN` 월(`CLOSED_MONTH_ROLLOVER`)을 고르고 확인 월은 보존한다(R3.5, R3.12).
6. IF 확인 월과 현재 월이 모두 `CLOSED`이면 THEN Settlement SHALL 임의 월을 고르지 않고 재시도 가능 실패로 끝낸다.
7. IF 원매출보다 금융 확인 사실이 먼저 오면 THEN Settlement SHALL R11로 처리하고 실패 보관 표로 보내지 않는다([P3.26-6](../platform/requirements.md), [결정 156](../../../docs/decisions.md#refund-awaiting-sale)).
8. WHEN 같은 환불 근거가 다시 오면(같은 `eventId`, 또는 같은 `refundId`·주문·확인 시각·취소 근거) THEN Settlement SHALL PG 장애·월 마감 뒤에도 최초 반영 월·정책·금액을 그대로 두고 외부 조회를 하지 않는다. IF 같은 주문을 다른 환불·다른 본문으로 다시 상계하려 하면 THEN Settlement SHALL 영구 실패로 거절한다. 같은 주문에 다른 금융 확인 사실이 같은 PG 취소를 가리키는 경우도 같은 영구 실패다. Payment가 한 환불에 금융 확인 사실을 하나만 내므로(늦은 취소 반영과 후속 실행 생성의 직렬화), 두 번째 사실은 흡수하지 않고 결함으로 드러낸다([결정 179](../../../docs/decisions.md#late-cancellation-during-retry), 1회차 지적 A2·B2, 판정 전).
9. WHERE 원매출도 늦게 온 경우에서 THE Settlement SHALL PG가 보고한 원결제가 전액 취소됐고 원승인·결제 구성·성공 취소 합계가 검증될 때만 그 원매출을 접수한다. 부분·진행 중 취소나 불명확한 채널·거래·결제 구성은 전액 취소로 보지 않는다.
10. IF PG가 취소 행의 시각을 보고하지 않으면 THEN Settlement SHALL 그 칸을 NULL로 보존하고 다른 시각으로 채우지 않는다. PG가 찍은 시각은 값으로만 저장하고 Settlement 시계와 비교하지 않는다([결정 163](../../../docs/decisions.md#external-clock-principle)).
11. THE 월 마감 SHALL 매출 원장 합계와 환불 원장 합계를 각각 월 행과 대조하며, 환불 반영이 원매출·기존 `CLOSED` 월·최초 `closedAt`을 바꾸지 않는다.
12. WHEN Settlement가 `RefundLateCancellationConfirmed`를 처음 받으면 THEN Settlement SHALL `RefundPaymentConfirmed`와 같은 규칙(3·4·5·8, 원매출이 없으면 R11)으로 환불 조정을 기록한다([P3.23-8](../platform/requirements.md), [결정 157](../../../docs/decisions.md#late-cancellation-confirmation)).
13. THE 환불 확인 월 `refundMonth` SHALL 금융 확인 사실의 사실 시각(`RefundPaymentConfirmed.confirmedAt`, `RefundLateCancellationConfirmed`는 Payment의 늦은 취소 확인 시각)의 KST 월이다([P4.5-2](../platform/requirements.md), 결정 42).

### R7. PG 정산 자료(비용·반환·지급일) 수집·대사

**사용자 스토리:** 운영자로서, PG 수수료·VAT·환불 반환·지급일을 PG 정산 자료로 자동 수집하고 내부 원장과 대사하고 싶다([결정 43](../../../docs/decisions.md#tosspay-statement-api-source)).

#### 수용 기준

1. WHEN ADMIN이 조회 기준(`dateType` = `DUE`(지급 예정일) | `SETTLE`(정산일))과 기준일로 수집을 요청하면 THEN Settlement SHALL 공급자 전체 페이지와 원본 응답을 수집하고, 금액·수령 상태를 요청 본문으로 받지 않는다.
2. THE Settlement SHALL 승인 비용과 환불 반환의 수수료·VAT·부호·합계를 PG가 보고한 값 그대로 보존하고 내부 원장과 대조한다.
3. THE 정본 거래 SHALL (가맹점, 환경, PG 거래 ID)마다 한 행이고, 같은 정본 거래의 검증된 비용 연결은 한 번만 저장한다.
4. WHEN 검증된 PG 비용을 업무 반영 월에 연결하면 THEN Settlement SHALL 승인 비용은 `SettlementSale.settlementMonth`, 환불 반환은 `SettlementRefund.settlementMonth`를 쓰고 수집일·지급일로 다시 정하지 않는다([결정 45](../../../docs/decisions.md#pg-cost-business-month-and-payout-date)).
5. IF 같은 결제 묶음에서 승인이 둘 이상(`AMBIGUOUS_PAYMENT`)이거나 관측 환불 합계가 내부 전액 환불보다 크면(`EXCESS_REFUND`) THEN Settlement SHALL 기존 연결의 확인 코드를 그 값으로 바꿔 확정 집계에서 빼고 월 금융 조회를 미확정으로 표시한다.
6. WHILE 예약 재대사가 돌면 THE Settlement SHALL 대사가 끝나지 않은 수집본(`RECEIVED`·`PENDING`)을 ID 커서로 최대 100개씩 PG 재호출 없이 다시 대사하고, 실패는 수집본 단위로 격리해 다음 순회에서 다시 시도한다.
7. THE 시험 환경(`TEST`) 근거의 비용 SHALL 미확정으로 남고, 빈 시험 결과를 확정 0원·지급 완료로 해석하지 않는다.
8. IF 보고된 반환이 명시적 0원이면 THEN Settlement SHALL 근거가 확인된 0원으로 구분하고, 미도착은 NULL로 둔다.
9. IF 수집 중 형식 오류·중간 HTTP 실패·커서 순환·페이지 제한 초과·페이지 합계 불일치가 있으면 THEN Settlement SHALL 부분 수집본을 저장하지 않는다. 알 수 없는 부가 칸은 원본에 보존한다.
10. THE 부호 SHALL 승인은 양수 거래액·음수 수수료/VAT, 환불은 음수 거래액·양수 반환 수수료/VAT로 보존하며, 보고 순액(거래액 + 수수료 + VAT)은 회계상 순이익·은행 잔액이 아니다.
11. WHEN 같은 조회 범위·원본이 다시 또는 동시에 수집되면 THEN Settlement SHALL 원본 지문(fingerprint)으로 하나로 모으고 최초 수집자·시각을 유지한다. 다른 조회 기준의 같은 거래는 정본 거래 하나를 함께 쓰고, 같은 거래 ID의 내용이 바뀌면 덮어쓰지 않고 `CONFLICT`로 거절한다.
12. THE 수집본 목록 SHALL 관측 이력이며 그 합계를 더해 비용 원장으로 쓰지 않는다. 대사는 원본 저장 커밋 뒤 별도 트랜잭션에서 하고, 대사가 실패해도 원본을 보존한다.
13. WHEN 환불을 대사하면 THEN Settlement SHALL 같은 결제 묶음의 중복 없는 PG 환불 관측 합계가 내부 전액 환불보다 작으면 `REFUND_AMOUNT_PENDING`, 크면 `EXCESS_REFUND`로 두고, 부분 관측으로 환불 비용 확인을 끝내지 않는다. 합계가 완성되면 같은 묶음의 과거 행도 함께 연결한다.
14. THE 거래 키 대응 SHALL 설정 `identity-mapping-verified=true`(기본 false, 시험 환경에서는 켤 수 없음)일 때만 비용 확정 근거로 쓰이며, 내부 `orderNo`를 PG 주문 키로 간주하지 않는다. 미검증이면 `IDENTITY_UNVERIFIED`, 실거래 정산 자료에 대응하는 실거래 승인 근거가 없으면 `SOURCE_ENVIRONMENT_PENDING`으로 두고 시험 승인 근거를 실거래로 바꾸지 않는다. 실거래 승인 근거를 받는 계약은 `[결정 필요 M-SE-3]`.
15. IF 근거가 약한 관측(`IDENTITY_UNVERIFIED` 등)만 새로 생기면 THEN Settlement SHALL 기존 검증된 비용 연결을 덮어쓰지 않는다.
16. WHERE 자동 수집에서 THE Settlement SHALL `enabled`·`scheduled-enabled`가 모두 참일 때만 오늘 지급 예정일 자료와 lookback(기본 7일, 1–31일)의 두 기준 자료를 수집하고, 날짜별 실패를 격리해 다음 주기에 다시 시도한다. 공급자가 자료를 주는 범위·시각(조회 가능 기간, 당일 자료 공개 시각)은 adapter가 정하고 core는 adapter가 준 판정(가칭 `AVAILABLE` 수집 가능 · `NOT_YET_PUBLISHED` 아직 공개 전 · `OUT_OF_RANGE` 범위 밖)만 쓴다(원칙 ②). 미래 날짜는 거절한다.
17. IF 정산 자료 수집 자격(가맹점 키)이 설정되지 않으면 THEN Settlement SHALL 수집을 `UPSTREAM_UNAVAILABLE`(503)로 끝내고 빈 수집본을 만들지 않는다.

### R8. 권한·조회 계약

**사용자 스토리:** 판매자·운영자로서, 역할에 맞는 정산 정보만 보고 싶다.

#### 수용 기준

1. THE Settlement SHALL 서명·issuer·만료·`esd-api` audience·역할을 검증하고 나머지 경로는 기본 거부한다.
2. IF JWT `sub`가 없거나 공백·100자 초과이면 THEN Settlement SHALL 401로 거절한다.
3. WHERE CREATOR 본인 조회(`/me/**`)에서 THE Settlement SHALL `sellerSubject`를 JWT `sub`로 제한하고 구매자·PG 비용·플랫폼 잔여액·다른 판매자 정보를 보이지 않는다.
4. WHERE 전체·판매자별 조회, 지난 월 마감, PG 정산 자료 수집·재대사, 승인 근거 재조회, 매출 대기 환불 조회에서 THE Settlement SHALL ADMIN을 요구하고, 고객이 PG 금액·수령 상태를 입력하지 못하게 한다.
5. THE ADMIN 매출 응답 SHALL `sellerSubject`·PG 비용·플랫폼 잔여액·정책·결제 연결 근거를 더 보이되 공급자 자격 증명·키·원문은 보이지 않는다.
6. THE 목록 SHALL `page=0`·`size=20`(page ≥ 0, 1 ≤ size ≤ 100), 매출은 `occurredAt DESC, id DESC`, 월은 `settlementMonth DESC, id DESC`로 정렬하고, 조회가 월 행을 만들지 않는다.
7. THE 월 금융 요약 SHALL 미확인 비용이 하나라도 있으면 `pgFeeAmount`·`platformNetAmount`를 NULL로 두고, 환불 반영 월의 순비용·플랫폼 잔여액은 음수를 허용한다([P4.4-3](../platform/requirements.md), I7).
8. THE 월 응답의 `paidAmount`·`platformFeeAmount`·`sellerPayoutAmount`·`recordCount` SHALL 매출 총합이고, 환불은 양수 합계와 차감 뒤 `net*` 순액(음수 허용)으로 따로 보인다.
9. THE 지급일 조회(`pg-payouts`) SHALL 수집본 헤더 합계를 누적하지 않고 중복 없는 정본 거래를 집계하며, `MATCHED_REPORTED`를 은행 실수령·월 자료 완비로 표시하지 않고 미관측(`NO_EVIDENCE`)과 수집한 빈 관측(`EMPTY_REPORTED`)을 구분한다.
10. THE 매출 응답(`/me/sales`·`/sales`의 `SaleResponse`) SHALL 원매출을 상계한 환불 조정이 있으면 `refund`에 `refundId`·`confirmedAt`·`refundMonth`·`settlementMonth`·`monthReason`을, 없으면 `null`을 담고, 매출 페이지당 환불 조정을 한 번에 조회한다(N+1 없음).
11. THE 월 응답 SHALL `CLOSED` 월에 `carriedIn`(≤ 0)·`payable`(≥ 0)·`carriedOut`(≤ 0)을 담고, `OPEN` 월은 셋 모두 `null`로 둔다. 페이지당 이월 원장을 한 번에 조회한다.

### R9. 저장 모델과 중복·출처 재사용 차단

**사용자 스토리:** 운영자로서, 중복 매출·출처 재사용·상태 값 오류가 DB 차원에서 막히기를 원한다.

#### 수용 기준

1. THE `SettlementSale` SHALL 삽입 뒤 불변이며 `orderNo`·`eventId`·`requestEventId`·`paymentResultEventId`·`paymentId`에 각각 유일 제약을 둔다(I8).
2. THE 판매자 월(`SellerMonthlySettlement`)의 업무 키 SHALL `(sellerSubject, settlementMonth)`이며 유일하다.
3. THE 환불 조정 SHALL 원매출당 최대 한 행이고 환불·주문·최초 확인 근거와 취소별 공급자·취소 ID를 불변 저장하며, 취소 근거의 (공급자, 취소 ID)는 전체 원장에서 한 번만 쓰인다.
4. THE 식별자 SHALL 원문·대소문자를 보존하고 DB 비교·유일 제약도 같게 하며, 시각은 UTC·마이크로초로 저장한다.
5. THE DB SHALL 비음수 결제·수수료·판매자 몫, 금액 보존식, 반영 월 ≥ 귀속 월, 월 상태·`closedAt` 일관성을 CHECK로 지키고, `platformNetAmount`와 그 합계의 음수는 허용한다.
6. THE 모든 상태·사유 칸 SHALL design "상태 모델"의 enum 값만 받는 DB CHECK를 가진다(원칙 ④). 보조 칸의 NULL 조합으로 하위 상태를 만들지 않는다.
7. THE PG 결제 구성 SHALL 전부 없음 또는 전부 검증된 구성만 허용하고, 과거 행에 소급 생성하지 않는다.
8. THE 이월 원장 SHALL `UNIQUE(seller_subject, settlement_month)`·`UNIQUE(seller_subject, source_month)`와 CHECK(`carried_in ≤ 0`·`payable ≥ 0`·`carried_out ≤ 0`·둘 중 하나는 0·`순액 + carried_in = payable + carried_out`, 출처 월이 없으면 `carried_in = 0`이고 있으면 마감 월보다 이전)를 두며, 옛 `CLOSED` 월에 행을 소급 생성하지 않는다.
9. THE `CLOSED` 판매자 월 SHALL 합계·건수·`closedAt`이 바뀌지 않고 `OPEN`으로 돌아가지 않으며, 코드 가드에 더해 DB가 그 변경을 막는다(I6, MC-S1).

### R10. 접수한 PG 거래의 비용 재조회

**사용자 스토리:** 운영자로서, 비용이 미확정이던 거래를 나중에 PG에서 다시 확인하고 싶다.

#### 수용 기준

1. WHEN ADMIN이 `POST /api/v1/settlements/pg-transactions/{paymentId}/refresh`를 보내면 THEN Settlement SHALL 트랜잭션 밖 PG 조회와 거래 동일성 검증 뒤 거래 행을 잠그고 확인된 비용을 한 번만 저장하며 JWT `sub`를 최초 접수 주체로 기록한다. 본문으로 비용을 받지 않는다.
2. IF 이미 확인된 비용과 다른 비용이 보고되면 THEN Settlement SHALL `CONFLICT`로 기존 비용을 보존한다. IF 응답에 비용이 없으면 THEN Settlement SHALL 기존 비용을 지우지 않고 미확정을 유지한다.
3. THE 비용 재조회 SHALL 원매출·판매자 배분·월 합계·최초 `closedAt`을 바꾸지 않으며, 확인된 0원은 별도 확정 행으로 미확정(행 없음)과 구분한다.
4. WHEN 같은 거래의 원승인 결제 구성이 나중에 다르게 보고되면 THEN Settlement SHALL `CONFLICT`로 최초 근거를 보존한다.

### R11. 매출 대기 환불 (결정 156)

**사용자 스토리:** 운영자로서, 원매출보다 환불 확인이 먼저 와도 환불 조정이 빠지지 않고, 기다리는 건이 눈에 보이기를 원한다.

#### 수용 기준

1. IF 금융 확인 사실을 받았는데 같은 `orderNo`의 원매출이 없으면 THEN Settlement SHALL 실패 보관 표로 보내지 않고, 받을 때 검증(본문·식별자·금액·통화·취소 ID 목록 형식)을 마친 확인을 매출 대기 환불 `AWAITING_SALE`로 Inbox와 같은 트랜잭션에 저장한다([P3.26-6](../platform/requirements.md)). 그 키는 정지 대상이 아니다.
2. THE 매출 대기 환불 SHALL 주문당 최대 한 건이고 전액 환불만 담는다. 같은 `eventId`의 재전달은 무변화, 같은 주문에 내용이 다른 확인은 영구 실패다.
3. WHEN `OrderPaid`가 그 주문의 매출을 기록하면 THEN Settlement SHALL 같은 트랜잭션에서 대기 환불을 원매출과 대조하고(주문·결제 키·구매자·금액·통화·PG 거래 ID), 트랜잭션 밖에서 미리 받아 둔 PG 취소 근거(R6.4와 같은 `PgRefunds` 조회)로 전액 취소·취소 ID 목록을 확인한 뒤, 환불 조정·월 환불 합계·환불 조정 확인 사실 Outbox를 쓰고 대기 환불을 `APPLIED`로 바꾼다. 반영 월은 R6.5·R6.13을 따른다(결정 42·130).
4. IF 대조나 PG 취소 근거가 맞지 않으면 THEN Settlement SHALL 매출은 기록하되 환불 조정은 반영하지 않고 대기 환불을 `CONFLICTED`(사유 코드 포함)로 바꾸며 경보 지표를 낸다. `CONFLICTED`의 운영자 처리는 `[결정 필요 M-SE-1]`.
5. THE 같은 `orderNo`의 대기 환불 저장과 매출 기록 SHALL 직렬화되어, 둘이 동시에 커밋돼도 대기 환불이 `AWAITING_SALE`로 남지 않는다.
6. THE Settlement SHALL 대기 건수(`AWAITING_SALE`, `CONFLICTED` 라벨)와 가장 오래 기다린 시간을 게이지로 낸다. `AWAITING_SALE`의 경보 기준 시간은 측정 뒤 정하고([결정 159](../../../docs/decisions.md#forward-gap-threshold)), `CONFLICTED`는 1건부터 경보한다.
7. WHILE 대기 환불이 `AWAITING_SALE`이면 THE 판매자 월 마감 SHALL 그 건을 읽지 않는다(판매자가 아직 정해지지 않았다).

### R12. 반영 확인 사실 발행 (결정 158)

**사용자 스토리:** Order·Payment 운영자로서, Settlement가 매출·환불 조정을 실제로 반영했는지 사실로 받아 누락을 세고 싶다.

#### 수용 기준

1. WHEN Settlement가 원매출을 처음 기록하면 THEN Settlement SHALL 같은 트랜잭션에서 `SettlementSaleRecorded`(가칭)를 Outbox(`stove.settlement.v1`, 키 `orderNo`)에 쓴다([design "목표 행"](../platform/design.md#목표-행-아직-코드에-없음), [design "정방향 응답 쌍"](../platform/design.md#정방향-응답-쌍-결정-158), 1회차 지적 B4·A5, 판정 전).
2. WHEN Settlement가 환불 조정을 처음 기록하면(매출 대기 환불의 반영 포함) THEN Settlement SHALL 같은 트랜잭션에서 `SettlementRefundRecorded`(가칭)를 Outbox에 쓴다. 대기 환불이 `AWAITING_SALE`·`CONFLICTED`인 동안에는 쓰지 않는다(Payment가 응답 없음으로 센다).
3. THE 두 사실 SHALL 반영당 한 번만 생기고, 같은 입력의 재전달·재투입은 새 사실을 만들지 않는다. 원본이 사라지면 ADMIN 재발행(같은 `eventId`·본문·키)으로만 다시 낸다([P2.2-7](../platform/requirements.md), [결정 161](../../../docs/decisions.md#republish-all-current)).
4. THE Settlement SHALL 공통 Outbox 릴레이를 켜고, `outbox_republish`·`republish-owner`·ADMIN 재발행 경로를 갖는다([P2.2](../platform/requirements.md)).
5. THE 두 사실의 본문 SHALL 응답 쌍을 잇는 식별자만 싣고(금액·판매자·월은 싣지 않는다), 칸 목록은 플랫폼 이벤트 표에서 확정한다(design "이벤트" 발행 절의 제안, 1회차 지적 A5, 판정 전).

## 범위 밖(후속)

- 실제 은행 입금 확인·판매자 송금·청구·회수·지급대행·세금계산서·원천징수·세무 회계. 판매자 음수 순액은 마감 때 이월만 한다(R3.9). 플랫폼 잔여액 음수는 이월하지 않는다(R2.3). 송금·회수 정책은 `[결정 필요 M-SE-4]`(G3 이후).
- PG 비용 실거래 확정(실제 가맹점 키·거래 키 대응 검증)과 지급 수령 대사는 G7([결정 140](../../../docs/decisions.md#defer-real-pg)·[142](../../../docs/decisions.md#g3-exit-current-contract)). 실거래 승인 근거 수용 계약은 `[결정 필요 M-SE-3]`.
- 자동 마감 배치, 마감 취소·재마감, 확정 금액 수정.
- 할인·쿠폰·플랫폼 보조금·복수 상품·다중 통화·판매자별 요율·차지백·부분 환불. 환불 뒤 재구매는 새 주문 = 새 원매출이라 계약 변경이 없다([결정 89](../../../docs/decisions.md#purchase-blocked-only-by-sale-ban)).
- 플랫폼 수수료 정책의 인터페이스화(지금은 환불 배분·환불 월 정책만 교체 가능).
