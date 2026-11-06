# 요구사항: Settlement (매출 배분·월 마감·PG 비용/환불/지급 자료 대사)

## 개요

Settlement는 결제 완료 주문(`OrderPaid`)을 소비해 판매자 매출을 기록하고, 플랫폼 수수료·판매자 정산액을 배분하며, 판매자·월별 합계를 OPEN→CLOSED로 마감한다.
PG 거래·비용과 PG 지급 명세는 **Settlement 소유 포트와 HTTP 어댑터로 외부 PG에서 직접 조회**한다(Payment 경유 없음). 상품 소유자는 Catalog 내부 GET으로 확인한다.
PG 비용은 **플랫폼이 전액 부담**한다. 고객 결제액과 기존 주문·결제·이용권 계약은 바꾸지 않는다.

MVP 재작성은 2026-10-18 완료했다(단위 1,014건·관련 통합 152건·실제 프로세스 여정 32단계). 이후 G3에서 외부 부담 혜택 배분, PG 비용 독립 월 마감, 전액 환불 원장·월 합계, 토스페이 정산 API 수집, PG 비용의 업무 반영 월 연결·지급일 대사를 차례로 연결했다.
포트원 TEST 승인 읽기를 쓰지만 PG 비용은 미확정(LIVE 미접수)이다. 남은 범위는 아래 "범위 밖(후속)"을 따른다.

## 용어

- **OrderPaid**: Order가 최초 PAID를 반영할 때 발행하는 이벤트. 결제 완료 주문의 13개 값을 담으며 판매자·PG 비용은 담지 않는다.
- **sellerSubject**: 판매자 식별자. Catalog가 보존하는 상품 소유자 `ownerSubject`이며 CREATOR 본인 조회에서는 JWT sub다.
- **플랫폼 잔여액 (platformNetAmount)**: 플랫폼 수수료에서 PG 비용을 뺀 배분 잔액. 음수일 수 있으며 회계상 순이익이 아니다.
- **saleMonth / settlementMonth**: 매출 발생 월(OrderPaid.occurredAt의 KST) / 실제 월 합계에 반영한 월. 정상 수신은 둘이 같다.
- **PgFunding / PgEvidence**: PortOne 원승인의 총액·실결제·외부 부담 혜택과 거래 근거를 보존하는 값. 현재 TEST 계약(`PORTONE_TEST`)이다.
- **REPORTED_PAID / MISMATCH / SIMULATED_RECEIVED**: SIMULATED 공급자 지급 명세의 접수·불일치·시험용 수령 확정 상태.
- **PG 정본 거래·보고 지급일(dueDate)**: 토스페이 정산 API가 보고한 거래 행과 입금일. 독립 은행 실수령 확인과 구분한다.

## 요구사항

### R1. OrderPaid 매출 접수와 원장 원자성
**사용자 스토리:** 플랫폼 운영자로서, 결제 완료 주문을 판매자 매출로 빠짐없이 한 번만 기록하고 싶다. 그래야 정산 근거가 정확하다.

#### 수용 기준
1. WHEN `settlement-paid-order` 그룹이 `OrderPaid`(토픽 `stove.order.v1`, 키 `orderNo`)를 수신하면 THEN 시스템 SHALL 봉투·본문의 `eventId`·`eventType`·키와 13개 값의 유효성을 확인해 내부 입력으로 변환한다.
2. WHEN 새 매출을 접수하면 THEN 시스템 SHALL 외부 PG에서 승인 거래·비용과 Catalog 상품 소유자를 직접 조회·대조한 뒤, Inbox·`SettlementSale`·OPEN 월 합계를 하나의 트랜잭션으로 저장한다.
3. WHILE 외부 PG·Catalog를 조회하는 동안 THE 시스템 SHALL 업무 트랜잭션·월 잠금을 잡지 않는다.
4. WHEN 같은 주문의 이벤트가 동일 본문으로 재전달되면 THEN 시스템 SHALL 기존 원장·월 합계·시각을 유지하고 외부 서비스 중단 중에도 동일 재전달을 처리한다.
5. IF 같은 주문의 `eventId`·발생 시각·본문이 바뀌거나, `eventId`·`requestEventId`·`paymentResultEventId`·`paymentId`를 다른 주문에 재사용하면 THEN 시스템 SHALL 충돌로 거절하고 기존 원장을 유지한다.
6. IF PG/Catalog 조회가 실패·미산정·비승인이거나 값이 불일치하면 THEN 시스템 SHALL 정상 원장·Inbox·월 합계를 만들지 않고 결제 상태는 유지한다.
7. WHERE Kafka 재시도가 소진되어 DLT로 이동한 입력에서 THE 시스템 SHALL 자동 복구로 설명하지 않고 원인 제거 후 원본 키·본문·헤더를 유지한 수동 재전달 절차로만 처리한다.

### R2. 플랫폼 전액 부담 배분과 금액 무결성
**사용자 스토리:** 운영자로서, 플랫폼 수수료와 판매자 정산액이 고객 결제액 기준으로 일관되게 배분되기를 원한다. 그래야 PG 비용이 달라져도 판매자 몫이 흔들리지 않는다.

#### 수용 기준
1. WHEN 매출을 배분하면 THEN 시스템 SHALL `platformFeeAmount = paidAmount × platformFeeRate`(원 단위 HALF_UP), `sellerPayoutAmount = paidAmount − platformFeeAmount`로 계산한다.
2. THE 플랫폼 수수료 기준 SHALL 고객 결제액(`OrderPaid.amount`)이며, PG 비용을 먼저 뺀 잔액을 기준으로 삼지 않는다.
3. WHEN PG 비용이 확인되면 THEN 시스템 SHALL `platformNetAmount = platformFeeAmount − pgFeeAmount`로 보존하고, 음수여도 0으로 보정하거나 판매자에게 전가하지 않는다.
4. THE 모든 금액 SHALL 정수 long·KRW이며, BigDecimal 비율 계산·HALF_UP 후 정확한 정수 변환을 사용하고 누적 합계·건수의 오버플로를 거부한다.
5. WHERE 확인된 외부 부담 혜택(토스 포인트·즉시할인 등 `TOSSPAY_EXTERNAL_BENEFIT_V1`) 맥락에서 THE 시스템 SHALL 총액·실결제·혜택을 보존하고 주문 금액으로 배분하며, 다른 결제사 혜택이나 플랫폼/판매자 쿠폰에 자동 적용하지 않는다.
6. THE 플랫폼 수수료 정책 SHALL `PLATFORM_MVP_V1`(1,000bps=10%)의 고정 규칙이며 config가 조립하고 운영 중 요율 변경 기능을 제공하지 않는다.

### R3. 귀속 월·수동 마감·지연 거래 반영
**사용자 스토리:** 운영자로서, 지난 월의 판매자 정산액을 확정 마감하고 싶다. 그래야 늦게 도착한 거래도 규칙대로 반영된다.

#### 수용 기준
1. THE `saleMonth` SHALL `OrderPaid.occurredAt`을 Asia/Seoul로 변환한 yyyy-MM이며, 서버 기본 시간대·수신 시각으로 정하지 않는다.
2. WHEN ADMIN이 판매자·월 마감을 요청하면 THEN 시스템 SHALL 현재 KST 월보다 이전 월만 허용하고, 월 행을 잠근 상태에서 원장 합계·건수를 대조해 CLOSED·closedAt을 함께 확정한다.
3. IF 현재·미래 월 마감을 요청하면 THEN 시스템 SHALL 409로 거절한다.
4. WHEN 동일 판매자·월의 마감을 재요청하면 THEN 시스템 SHALL 최초 금액·closedAt을 그대로 반환하고 재집계로 확정 금액을 바꾸지 않는다.
5. IF 늦은 거래의 `saleMonth`가 이미 CLOSED이면 THEN 시스템 SHALL 접수 트랜잭션 시점의 현재 KST 월에 반영하고 `saleMonth`를 보존한다.
6. WHERE 둘 이상의 월 행을 잠그는 맥락에서 THE 시스템 SHALL 판매자·월의 일정한 순서로 잠근다.
7. THE 월 마감 SHALL PG 수령 확인·PG 비용 확정과 독립적으로 수행하며, CLOSED를 수령 완료나 판매자 지급 완료로 해석하지 않는다.

### R4. PG 거래·비용·지급 자료의 Settlement 소유 직접 조회
**사용자 스토리:** 운영자로서, PG 거래·비용·지급 명세를 Payment를 거치지 않고 Settlement가 직접 확인하기를 원한다. 그래야 책임 경계가 분명하다.

#### 수용 기준
1. THE 시스템 SHALL `core.port`의 `PgPayments`·`PgSettlements`·`PgRefunds`와 각 HTTP 어댑터로 외부 PG를 직접 조회하고, Payment HTTP·DB를 경유하지 않는다.
2. WHEN 상품 소유자를 확인하면 THEN 시스템 SHALL Catalog 내부 `GET /internal/v1/products/{productCode}/owner`를 전용 서비스 키(`CATALOG_SETTLEMENT_SERVICE_KEY`, Download 키와 다름)로만 호출하고 `ownerSubject`를 `sellerSubject`로 보존한다.
3. THE 시스템 SHALL 업무 모듈 DB를 직접 읽지 않는다.
4. WHERE PortOne 원승인 읽기 맥락에서 THE 시스템 SHALL 상점·채널·`V2`·`PAID`·`KRW`·`TEST`·거래 ID/시각·총액/실결제/혜택을 엄격히 검증하고, 숫자 문자열·누락·중복 JSON 키·후행 JSON·다른 채널/구매를 접수하지 않는다.
5. IF SIMULATED 외의 공급자(PORTONE)로 신규 지급 명세 접수를 시도하면 THEN 시스템 SHALL 503으로 명시적 미연동을 반환하고 모의 명세나 빈 0원 수령을 만들지 않는다.
6. THE 원승인 조회 SHALL PG 비용을 만들지 않으며, `pgCostStatus=UNCONFIRMED`·비용 NULL을 유지한 채 판매자 월 마감을 허용한다.

### R5. SIMULATED 지급 명세 접수·대사·모의 수령
**사용자 스토리:** 운영자로서, 모의 PG 지급 명세를 접수하고 내부 매출과 대사해 시험용 수령을 확인하고 싶다.

#### 수용 기준
1. WHEN ADMIN이 `providerSettlementId`로 수집을 요청하면 THEN 시스템 SHALL 기존 접수를 확인하고 새 입력만 DB 트랜잭션 밖에서 PG 명세를 직접 조회해 헤더·항목 전체를 REPORTED_PAID로 원자 저장한다.
2. WHEN 접수 커밋 뒤 대사하면 THEN 시스템 SHALL 헤더를 잠그고 거래별 내부 `SettlementSale`과 식별자·금액·비용 전체를 대조한다.
3. IF 모든 항목이 일치하면 THEN 시스템 SHALL SIMULATED_RECEIVED·최초 `simulatedReceivedAt`을 함께 확정한다.
4. IF 내부 매출이 없는 항목이 있으면 THEN 시스템 SHALL 건너뛰지 않고 REPORTED_PAID로 남긴다. IF 값이 다른 항목이 있으면 THEN 시스템 SHALL MISMATCH와 항목별 사유를 남긴다.
5. THE 명세의 포함 거래·금액·지급 결과 SHALL 삽입 후 불변이며, 상태·대사 사유·대사 시각만 갱신하고 최초 수령 시각을 덮어쓰지 않는다.
6. THE 수령 합계 SHALL SIMULATED_RECEIVED 명세의 `payoutAmount` 합계로 구하며, 재시도·재대사·동시 실행으로 다시 가산하지 않는다.
7. IF 명세 형식·합계·항목 중복 오류가 있거나 같은 `providerSettlementId`에 다른 명세가 오거나 이미 다른 명세에 접수한 거래가 포함되면 THEN 시스템 SHALL 접수 자체를 거절하고, 원장 수정·비용 덮어쓰기로 대사를 맞추지 않는다.
8. THE 재대사 응답 항목 SHALL `PG_COST_PENDING`·`DIFFERENT_TRANSACTION`·`DIFFERENT_COST`를 구분하며, 비용 대기만으로 SIMULATED_RECEIVED를 확정하지 않는다.
9. WHILE PG·Catalog·Payment가 중단된 동안 THE 저장 명세 조회·재대사 SHALL 저장된 사실만으로 동작하며, 미도착 매출 반영 후 재대사는 운영자가 명시적으로 요청한다(자동 대사 스케줄러 없음).

### R6. 전액 환불 원배분 상계와 환불 반영 월
**사용자 스토리:** 운영자로서, 전액 환불이 확정되면 원매출의 판매자/플랫폼 배분을 그대로 상계하고 싶다. 그래야 월 순액이 정확하다.

#### 수용 기준
1. WHEN `settlement-confirmed-refund` 그룹이 Payment의 `RefundPaymentConfirmed`를 수신하면 THEN 시스템 SHALL 봉투·본문을 검증해 내부 입력으로 변환한다.
2. THE 늦은 `RefundExecutionSucceeded` SHALL 최초 금융 확인을 보강하는 사건으로만 처리하고 두 번째 금액 조정으로 소비하지 않는다.
3. WHEN 전액 환불을 배분하면 THEN 시스템 SHALL `FULL_REFUND_REVERSE_ORIGINAL_V1`로 원매출의 총액·플랫폼 수수료·판매자 몫을 그대로 음수 상계하고, 현재 요율로 재계산하거나 확인된 외부 혜택만큼 판매자 몫을 줄이지 않는다.
4. WHEN 환불을 기록하면 THEN 시스템 SHALL `PgRefunds`로 같은 결제를 직접 GET해 전액 취소·성공 취소 ID 전체·합계·기존 지급 구성을 확인한 뒤, Inbox·불변 환불 원장·월 환불 합계를 원자 저장한다.
5. WHERE 환불 반영 월 선택 맥락에서 THE 시스템 SHALL `REFUND_CONFIRMED_MONTH_V1`로 최초 금융 확인 월이 OPEN이면 그 월, CLOSED이면 현재 OPEN 월을 선택한다(`CONFIRMED_MONTH` / `CLOSED_MONTH_ROLLOVER`).
6. IF 확인 월과 현재 월이 모두 CLOSED이면 THEN 시스템 SHALL 임의 월을 선택하지 않고 충돌로 처리한다.
7. IF 환불이 원매출보다 먼저 도착하면 THEN 시스템 SHALL 원장·월 합계에 기록하지 않고 기존 Kafka 재시도/DLT로 남기며, 원매출 복구 뒤 원래 금융 확인 이벤트로 재처리한다.
8. IF SIMULATED 공급자의 환불 읽기를 요청하면 THEN 시스템 SHALL 503으로 반환하고 모의 취소 근거를 생성하지 않는다.
9. WHEN 같은 환불 근거(환불·주문·최초 금융 확인 시각)가 재전달되면 THEN 시스템 SHALL PG 장애·월 마감 이후에도 최초 반영 월·정책·금액을 반환하고, 다른 본문·다른 환불로 같은 주문을 다시 상계하지 않는다.
10. WHERE 원매출도 지연된 맥락에서 THE 시스템 SHALL PortOne의 완전한 `CANCELLED` 응답에서 원승인·지급 구성과 전액 성공 취소 합계가 검증될 때만 원매출을 접수하고, 부분·진행 중 취소나 불명확한 채널/거래/지급 구성은 전액 취소로 취급하지 않는다.
11. IF 제공사가 취소 행의 시각을 보고하지 않으면 THEN 시스템 SHALL NULL로 보존하고 전체 취소 시각으로 채우지 않는다.
12. THE 월 마감 SHALL 매출 원장 합계와 환불 원장 합계를 각각 월 행과 대조하며, 환불 반영이 원매출·기존 CLOSED 월·최초 closedAt을 바꾸지 않는다.

### R7. 토스페이 정산 API 비용/반환/지급 자료 수집·대사
**사용자 스토리:** 운영자로서, PG 수수료·부가세·환불 반환·지급일을 공식 정산 API로 자동 수집하고 내부 원장과 대사하고 싶다.

#### 수용 기준
1. WHEN ADMIN이 `dateType`·`baseDate`로 수집을 요청하면 THEN 시스템 SHALL 공급자 전체 페이지와 원본 응답을 수집하고 금액·수령 상태를 입력받지 않는다.
2. THE 시스템 SHALL 승인 비용과 환불 반환의 수수료·부가세(VAT) 및 부호·합계를 직접 보고한 값으로 보존하고 내부 원장과 대조한다.
3. THE 수집본 `source_entry_id`(PG 정본 거래) SHALL 유일 키이며, 같은 정본 거래의 검증된 비용 연결은 한 번만 저장한다.
4. WHEN 검증된 PG 비용을 업무 반영 월에 연결하면 THEN 시스템 SHALL 승인 비용은 `SettlementSale.settlementMonth`, 환불 반환은 `SettlementRefund.settlementMonth`를 사용하고 수집일·PG 지급일로 반영 월을 다시 정하지 않는다.
5. IF 같은 결제 묶음에서 `AMBIGUOUS_PAYMENT`·`EXCESS_REFUND`가 발견되면 THEN 시스템 SHALL 기존 연결의 `check_code`만 갱신하고 현재 확정 집계에서 제외하며 월 금융 조회를 다시 미확정으로 표시한다.
6. WHILE 설정된 수집 주기의 마지막에 THE 시스템 SHALL 저장된 RECEIVED/PENDING을 ID 커서로 최대 100개씩 PG 재호출 없이 재대사하고 실패를 격리해 다음 순회에서 재시도한다.
7. THE 현재 TEST 비용 SHALL 미확정으로 유지하며, 빈 TEST 결과를 확정 0원·지급 완료로 해석하지 않는다.
8. IF 보고된 반환이 명시적 0원이면 THEN 시스템 SHALL 근거가 확인된 0원으로 구분하고, 미도착은 NULL로 둔다.
9. IF 수집 중 숫자 문자열·누락/비정상 값·중복 JSON 키·후행 JSON·중간 HTTP 실패·커서 순환·페이지 제한 초과·페이지 합계 불일치가 있으면 THEN 시스템 SHALL 부분 명세를 저장하지 않는다. 알 수 없는 부가 필드는 원본에 보존한다.
10. THE 부호 SHALL 승인은 양수 거래액·음수 수수료/VAT, 환불은 음수 거래액·양수 반환 수수료/VAT로 보존하며, `reportedNetAmount`(거래액+수수료+VAT)는 회계상 순이익·은행 잔액이 아니다.
11. WHEN 같은 조회 범위·원본이 다시 수집되거나 동시에 수집되면 THEN 시스템 SHALL fingerprint로 하나로 수렴하고 최초 수집자·시각을 유지한다. DUE·SETTLE 등 다른 관측의 같은 거래는 정본 거래 하나로 공유하고, 같은 거래 ID의 내용이 바뀌면 덮어쓰지 않고 충돌로 거절한다.
12. THE 수집본 목록 SHALL 관측 이력이며 그 합계를 모두 더해 비용 원장으로 쓰지 않는다. 대사는 원본 저장 커밋 뒤 별도 트랜잭션에서 수행하고 대사 실패 시 원본을 보존한다.
13. WHEN 환불을 대사하면 THEN 시스템 SHALL 가맹점/환경 내 중복 없는 PG 환불 관측 합계가 내부 전액 환불보다 작으면 `REFUND_AMOUNT_PENDING`, 크면 `EXCESS_REFUND` 불일치로 두고, 부분 관측으로 환불 비용 확인을 완료하지 않는다. 합계가 완성되면 같은 결제 묶음의 과거 행도 함께 연결한다.
14. THE 거래 키 대응(`PortOne pgTxId ↔ 토스페이 payToken`, `PortOne paymentId ↔ PG orderNo`) SHALL `identity-mapping-verified=true`(기본 false, TEST 불가)일 때만 확정 근거로 쓰며, 내부 업무 orderNo를 PG orderNo로 간주하지 않는다. 미검증이면 `IDENTITY_UNVERIFIED`, LIVE 명세에 대응할 LIVE 승인 근거가 없으면 `SOURCE_ENVIRONMENT_PENDING`으로 두고 PORTONE_TEST 승인을 LIVE로 승격하지 않는다.
15. IF 근거가 약한 관측(`IDENTITY_UNVERIFIED` 등)만 새로 생기면 THEN 시스템 SHALL 기존 검증된 비용 연결을 덮어쓰지 않는다.
16. WHERE 자동 수집 맥락에서 THE 시스템 SHALL `enabled`·`scheduled-enabled`가 모두 true일 때만 KST 10시 이후 오늘 DUE와 lookback(기본 7일, 1–31일)의 DUE/SETTLE을 수집하고, 날짜별 실패를 격리해 다음 주기에 재시도한다. 미래·공급자 범위(1년) 밖 조회와 10시 전 오늘 DUE는 거절한다.
17. IF 토스페이 가맹점 키가 설정되지 않으면 THEN 시스템 SHALL 수집을 미연동 503으로 반환하고 빈 보고서를 만들지 않는다.

### R8. 권한·조회 계약
**사용자 스토리:** 판매자·운영자로서, 역할에 맞는 정산 정보만 보고 싶다. 그래야 다른 판매자·공급자 자격증명이 노출되지 않는다.

#### 수용 기준
1. THE 시스템 SHALL 서명·issuer·만료·`esd-api` audience와 역할을 검증하고 나머지 업무 경로에 기본 거부를 적용한다.
2. IF JWT의 sub가 없거나 공백·100자 초과이면 THEN 시스템 SHALL 401로 거절한다.
3. WHERE CREATOR 본인 조회(`/me/**`) 맥락에서 THE 시스템 SHALL `sellerSubject`를 JWT sub로 제한하고 구매자·PG 비용·플랫폼 잔여액·다른 판매자 정보를 노출하지 않는다.
4. WHERE 전체/판매자별 조회·지난 월 마감·PG 명세/정산 자료 수집·재대사 맥락에서 THE 시스템 SHALL ADMIN 역할을 요구하고, 고객이 PG 금액·수령 상태를 입력하지 못하게 한다.
5. THE ADMIN 매출 응답 SHALL `sellerSubject`·PG 비용·플랫폼 잔여액·정책·결제 연결 근거를 추가 제공하되 공급자 자격증명·키·원문은 응답하지 않는다.
6. THE 목록 SHALL `page=0`·`size=20`(page ≥ 0·1 ≤ size ≤ 100), 매출은 `occurredAt DESC·id DESC`, 월은 `settlementMonth DESC·id DESC`로 정렬하고, 조회가 월 행을 생성하지 않는다.
7. THE 월 금융 요약 SHALL 미확인 비용이 있으면 `pgFeeAmount`·`platformNetAmount`를 NULL로 두고, 환불 반영 월의 순비용·플랫폼 잔여액은 음수를 허용한다.
8. THE 월 응답의 기존 `paidAmount`·`platformFeeAmount`·`sellerPayoutAmount`·`recordCount` SHALL 매출 총합 의미를 유지하고, 환불은 양수 합계와 차감 후 `net*` 순액(음수 허용)으로 따로 제공한다.
9. THE 지급일 조회(`pg-payouts`) SHALL 수집본 헤더 합계를 누적하지 않고 중복 없는 정본 거래를 집계하며, `MATCHED_REPORTED`를 은행 실수령·월 자료 완비로 표시하지 않고 미관측(`NO_EVIDENCE`)과 수집한 DUE의 빈 관측(`EMPTY_REPORTED`)을 구분한다.

### R9. 최소 저장 모델과 중복/출처 재사용 차단
**사용자 스토리:** 운영자로서, 중복 매출·출처 재사용·명세 재사용이 DB 차원에서 차단되기를 원한다.

#### 수용 기준
1. THE `SettlementSale` SHALL 삽입 후 불변이며 `orderNo`·`eventId`·`requestEventId`·`paymentResultEventId`·`paymentId`에 각각 유일 제약을 둔다.
2. THE `SellerMonthlySettlement`의 업무 키 SHALL `(sellerSubject, settlementMonth)`이며 유일하다.
3. THE PG 지급 명세 SHALL `(provider, providerSettlementId)`에 유일 제약을, 항목은 `(provider, providerTransactionId)`·`(provider, paymentId)`에 유일 제약을 둔다.
4. THE 환불 원장 SHALL 환불/주문/최초 확인 근거와 취소별 공급자·ID를 불변 저장하고 취소 근거의 공급자·ID를 전체 원장에서 한 번만 사용한다.
5. THE 식별자 SHALL 원문·대소문자를 보존하고 DB 비교·유일 제약도 동일하게 적용하며, Instant는 UTC·마이크로초 정밀도로 저장한다.
6. THE DB SHALL 비음수 결제/수수료·판매자 몫, 금액 보존식, 월 상태·closedAt의 일관성을 검증하고 `platformNetAmount`와 그 합계의 음수는 허용한다.
7. THE PG 지급 명세 항목 SHALL 매출보다 먼저 도착할 수 있으므로 `SettlementSale` 존재를 강제하는 FK를 두지 않고 재대사 때 불변 결제 키로 연결한다.
8. THE PG 지급 구성(V9) SHALL 전부 없음 또는 전부 검증된 구성만 허용하며, `PORTONE_TEST` 거래에는 필수이고 과거 행에 소급 생성하지 않는다.

### R10. 접수한 PG 거래의 비용 재조회
**사용자 스토리:** 운영자로서, 비용이 미확정이던 거래를 나중에 공급자에서 다시 확인하고 싶다. 그래야 판매자 금액을 건드리지 않고 비용만 보완된다.

#### 수용 기준
1. WHEN ADMIN이 `POST /api/v1/settlements/pg-transactions/{paymentId}/refresh`를 요청하면 THEN 시스템 SHALL 공급자 GET과 거래 동일성 검증·거래 행 잠금 뒤 확인된 비용을 한 번만 저장하고 JWT sub를 최초 접수 주체로 기록한다. 본문으로 비용을 입력받지 않는다.
2. IF 이미 확인된 비용과 다른 비용이 보고되면 THEN 시스템 SHALL 409로 기존 비용을 보존한다. IF 응답에 비용이 없으면 THEN 시스템 SHALL 기존 비용을 지우지 않고 미확정을 유지한다.
3. THE 비용 재조회 SHALL 원매출·판매자 배분·월 합계·최초 closedAt을 바꾸지 않으며, 확인된 0원은 별도 확정 행으로 미확정(행 없음)과 구분한다.
4. WHEN 같은 거래의 원승인 근거(`PgFunding`)가 나중에 다르게 보고되면 THEN 시스템 SHALL 409로 최초 근거를 보존한다.

## 범위 밖(후속)

- 공통 운영 API(`/api/v1/ops/**`)·Outbox 릴레이·자동 마감 배치·계산서(세금계산서)·판매자 송금은 제공하지 않는다.
- 실제 은행 입금 확인·판매자 송금·환불 조정 완료·음수 잔액의 자동 이월/회수는 후속. PG 지급 보고를 실제 은행 입금으로 해석하지 않는다.
- **G3 남음:** PG 비용 LIVE 확정(실제 가맹점 키·거래 키 대응 검증, 정상 LIVE 입력→비용 저장/SQL 집계·INSERT 경합/실패 롤백은 검증 공백), 환불 조정 완료, 판매자 순액 전체 연결은 미완료다. 현재는 PORTONE_TEST 비용 미확정을 유지한다.
- 할인·쿠폰·복수 상품·다중 통화·판매자별 요율/계약·차지백·세무 회계, 마감 취소/재마감, P4 환불 후 재구매는 후속.
