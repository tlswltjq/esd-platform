# 설계: Settlement

## 아키텍처 위치

- 포트 8089, 저장소 MySQL.
- `core/{domain, port, service}` + `api/{controller, listener}` + `adapter/` + `config` 경계를 따른다.
- Gateway(8080)가 `/api/v1/settlements/**`를 라우팅하며 `/me/**`는 CREATOR, 나머지는 ADMIN이다. Settlement도 서명·issuer·만료·`aud=esd-api`·역할을 자체 검증한다.
- 외부 모의 PG(simulators/mock-pg)와 실제 PortOne TEST는 **Settlement 소유 포트 + HTTP 어댑터**로 직접 호출한다. Payment를 경유하지 않는다.
- 상품 소유자는 Catalog 내부 GET으로 확인한다. 업무 모듈 DB는 직접 읽지 않는다.

## 핵심 설계 결정

- **플랫폼 전액 부담 배분.** 플랫폼 수수료 기준은 고객 결제액이며 PG 비용을 먼저 빼지 않는다. PG 비용이 달라져도 같은 결제액의 판매자 정산액은 같다. `platformNetAmount`는 음수를 허용한다. (결정 38·39)
- **책임 분리.** 원매출·배분(`SettlementSale`), 판매자 월 합계·마감(`SellerMonthlySettlement`), PG 승인 거래(`PgTransactionRecord`/`PgFunding`), 확인된 PG 비용, PG 명세 대사(`PgReceipt`)를 각각 분리한다. 판매자 금액은 PG 비용 확정과 독립적으로 마감한다.
- **외부 조회와 트랜잭션 분리.** PG·Catalog HTTP 조회는 DB 트랜잭션·월 잠금 밖에서 수행하고, 대조 후 원장·월 합계·Inbox를 하나의 트랜잭션으로 커밋한다.
- **중복/출처 재사용 차단.** OrderPaid 13개 값 전체 비교와 업무 유일 제약(orderNo·eventId·requestEventId·paymentResultEventId·paymentId)으로 중복 생성과 출처 재사용을 막는다.
- **원승인 외부 부담 혜택 보존.** PortOne 원승인의 총액·실결제·외부 혜택을 보존하고 주문 금액으로 배분한다(`TOSSPAY_EXTERNAL_BENEFIT_V1`). 현재 TEST 비용은 미확정이다. (결정 40)
- **전액 환불 원배분 상계·반영 월.** 원매출 배분을 그대로 음수 상계하고(`FULL_REFUND_REVERSE_ORIGINAL_V1`, 결정 41), 확인 월 OPEN이면 그 월, CLOSED이면 현재 OPEN 월에 반영한다(`REFUND_CONFIRMED_MONTH_V1`, 결정 42).
- **마감 경계.** 같은 판매자의 더 뒤 월이 CLOSED면 그 앞 월은 행이 없어도 CLOSED로 본다. 늦은 앞 월 매출·환불은 앞 월 행을 만들지 않고 현재 KST 월에 반영한다(`CLOSED_MONTH_ROLLOVER`, `saleMonth`·확인 월 보존). 매출·환불 반영과 마감은 판매자 잠금 행(`settlement_seller_lock`)을 먼저 잠가, "뒤 월 CLOSED" 판정과 앞 월 행 생성이 엇갈리지 않게 한다([결정 42](../../../docs/decisions.md#settlement-refund-posting-month)·[결정 130](../../../docs/decisions.md#seller-close-order-carryover) 적용, D-045).
- **판매자 음수 순액 이월·마감 순서.** 판매자 월은 오래된 월부터 마감한다(이전 월에 OPEN 행이 있거나, 대상 월이 CLOSED가 아닌데 뒤 월이 CLOSED면 409). 마감 때 직전 CLOSED 월의 `carriedOut`을 받아 `payable = max(0, 순액 + carriedIn)`을 확정하고 남은 음수는 `carriedOut`으로 넘긴다. 행 없는 월은 건너뛴다. 이월은 불변 원장(`settlement_seller_carryover`)에 마감 트랜잭션 안에서 한 번만 쓴다. 송금·청구·회수는 하지 않는다. ([결정 121](../../../docs/decisions.md#seller-negative-net-carryover)·[결정 130](../../../docs/decisions.md#seller-close-order-carryover))
- **PG 비용의 업무 반영 월 연결·지급일 대사.** 검증된 PG 정본 거래 비용을 저장된 반영 월에 한 번 연결하고, 월 금융 요약은 반영 월, PG 대사는 보고 지급일(`dueDate`) 기준으로 조회한다. (결정 43·45)
- **테스트 경계.** 상시 모의 PG 서비스를 복원하지 않고 루프백 HTTP 응답 서버·명시적 시험 자료로 계약을 검증한다. 운영 TEST 허용 계약을 테스트 편의로 확장하지 않는다. (결정 46)

## 데이터 모델

| 테이블 | 보존할 값 |
|---|---|
| `settlement_sale` (`SettlementSale`) | OrderPaid 13개 값 전체, `sellerSubject`, 플랫폼 정책 ID·요율·수수료·판매자 정산액, PG 정책 ID·요율·비용·공급자·거래 ID·결정 시각, `platformNetAmount`, `saleMonth`·`settlementMonth`·`receivedAt`. PG 지급 구성(`PgFunding`) 열 포함. 삽입 후 불변 |
| `seller_monthly_settlement` (`SellerMonthlySettlement`) | `(sellerSubject, settlementMonth)`, OPEN/CLOSED, 결제액·수수료·판매자 몫·PG 비용·플랫폼 잔여액 합계·`recordCount`, 환불 합계 4개 열, createdAt·updatedAt·closedAt |
| `settlement_pg_transaction` (`PgTransactionRecord`) | V8. 결제/주문 키·금액·통화·공급자 거래 ID/시각·최초 접수 시각, V9 `PgFunding`(전부 없음/전부 검증). 불변 |
| `settlement_pg_fee` (`PgFeeRecord`) | V8. 거래와 일치하는 확정 비용·최초 확인 시각·접수 주체(이관분은 `MIGRATED_V8`). 미확정이면 행 없음, 확인된 0원은 별도 행 |
| `pg_receipt` / `pg_receipt_item` (`PgReceipt`) | SIMULATED 공급자 명세 헤더·불변 항목, 세 합계·건수, 대사 상태(REPORTED_PAID/MISMATCH/SIMULATED_RECEIVED)·운영자 sub·reportedAt·lastCheckedAt·simulatedReceivedAt |
| `settlement_refund` (+ 취소 근거) (`SettlementRefund`) | 최초 금융 확인/접수 시각, 환불/시도/요청/원매출 식별자, 당시 판매자/상품 스냅샷, 원래 배분 정책·금액, 두 정책 ID, 확인 월·반영 월·선택 사유, 독립 조회한 취소별 근거. 불변 |
| `settlement_pg_statement` (+ `_page`·`_entry`·`_item`) | V11 토스페이 수집본: 헤더(가맹점/환경·상점/채널·검증 매핑 여부, 조회 기준/일자, 원본 fingerprint·합계·최초 수집자/시각·대사 상태), 페이지(요청/다음 커서·응답 원문), 정본 거래 entry(가맹점+환경+거래 ID별 불변 금액·수수료·VAT·지급일), item(수집본↔정본 연결·대사 코드·결제 연결) |
| `settlement_seller_lock` (`SellerLock`) | V14. 판매자마다 한 행(`sellerSubject` PK, createdAt). 처음 쓸 때 만들고, 매출·환불 반영과 마감이 다른 행보다 먼저 `PESSIMISTIC_WRITE`로 잠근다. 업무 값은 없다 |
| `settlement_seller_carryover` (`SellerCarryover`) | V13. `(sellerSubject, settlementMonth)` 유일, V15 `(sellerSubject, sourceMonth)` 유일(출처 NULL은 제외), 마감 시점 `netSellerPayoutAmount`·`carriedIn`·`payable`·`carriedOut`·출처 월(`sourceMonth`, 직전 CLOSED 월 또는 NULL)·createdAt. 마감 트랜잭션에서 한 번 삽입, 불변 |
| `settlement_pg_cost_posting` | `source_entry_id`(PG 정본 거래, 유일), 원매출·선택적 환불·판매자·업무 반영 월·`LINKED_BUSINESS_MONTH_V1`·검증 명세 ID·최초 검증 시각·`check_code` |
| 공통 `processed_event` | Kafka 매출/환불 접수 Inbox. 대소문자 구분 비교 |

모델 속성은 `docs/domain/models/settlement.md` 참조.

## 금액 정책

```text
paidAmount         = OrderPaid.amount
platformFeeAmount  = paidAmount × platformFeeRate (원 단위 HALF_UP)
sellerPayoutAmount = paidAmount − platformFeeAmount
pgFeeAmount        = 외부 PG가 거래별로 확정·보존한 비용 (현재 TEST 미확정)
platformNetAmount  = platformFeeAmount − pgFeeAmount        (음수 허용)

paidAmount = sellerPayoutAmount + platformNetAmount + pgFeeAmount

판매자 월 마감(결정 121·130):
carriedIn  = M 이전 마지막 CLOSED 월의 carriedOut (없으면 0, 행 없는 월은 건너뜀, ≤ 0)
total      = netSellerPayoutAmount + carriedIn
payable    = max(0, total)
carriedOut = min(0, total)
```

| 정책 | 값 |
|---|---|
| 플랫폼 수수료 | `PLATFORM_MVP_V1`, 1,000bps=10%, 원 단위 HALF_UP (고정, 운영 중 변경 없음) |
| 외부 부담 혜택 | `TOSSPAY_EXTERNAL_BENEFIT_V1` (확인된 외부 부담만) |
| 전액 환불 배분 | `FULL_REFUND_REVERSE_ORIGINAL_V1` (원배분 음수 상계) |
| 환불 반영 월 | `REFUND_CONFIRMED_MONTH_V1` (확인 월 OPEN→그 월, CLOSED→현재 OPEN 월) |
| PG 비용 월 연결 | `LINKED_BUSINESS_MONTH_V1` (저장된 반영 월 사용) |

금액은 모두 정수 long·KRW. 판매자 음수 월 순액은 마감 때 `carriedOut`으로 다음 마감에 넘길 뿐 계좌 회수·청구·송금을 뜻하지 않는다. `payable`은 지급 기준액이며 지급 완료가 아니다. 음수 플랫폼 잔여액은 이월하지 않는다.

## 상태×이벤트 표 (코드 기준, 결정 143)

main `9fe2e18e`의 코드에서 옮겼다. 코드 위치의 클래스는 `apps/settlement/src/main/java/com/stove/settlement/` 아래에 있다. Settlement는 업무 이벤트를 발행하지 않는다(Outbox 비활성). 그래서 부수 효과 칸에 발행 이벤트가 없다. "409"·"400"·"404"·"503"은 각각 `BusinessException`의 `CONFLICT`·`INVALID_REQUEST`·`NOT_FOUND`·`UPSTREAM_UNAVAILABLE`이다. Kafka 경로에서는 HTTP 응답이 아니라 예외로 끝나고 공통 재시도/DLT로 넘어간다. 같은 판매자의 반영과 마감은 모두 `SellerLockRepository.lock`(PESSIMISTIC_WRITE)을 먼저 잡는다(R3.6, D-045).

### 매출 (`SettlementSale`, `settlement_sale`)

상태 enum이 없다. 행이 있는지가 곧 상태다.

| 상태 | 뜻 |
|---|---|
| (행 없음) | 아직 접수하지 않은 주문 |
| 기록됨 | 접수가 끝났다. 모든 열이 `updatable=false`라 삽입 뒤에는 바뀌지 않는다 |

반영 사유를 담는 열은 없다. `saleMonth`(`OrderPaid.occurredAt`의 KST 월)와 `settlementMonth`(실제 반영 월)가 같으면 정상 반영이고, 다르면 마감 때문에 이월해 반영한 것이다.

| 현재 상태 | 트리거 | 가드(조건) | 다음 상태 | 부수 효과 | 코드 위치 | 근거 |
|---|---|---|---|---|---|---|
| 기록됨 | `OrderPaid` 수신(`settlement-paid-order`) | 같은 `orderNo` 원장의 13개 값이 같고 Inbox에 이미 있음 | 기록됨(그대로) | 없음. 기존 원장을 반환 | `RecordPaidSale.existing` | R1.4 |
| 기록됨 | `OrderPaid` 수신 | 13개 값이 다름, 또는 원장은 있는데 Inbox에는 첫 전달 | 기록됨(그대로), 409 | 없음 | `SettlementSale.requireSame`, `RecordPaidSale.existing` | R1.5 |
| 없음 | `OrderPaid` 수신 | 트랜잭션 밖에서 PG 승인 조회·주문 대조 또는 Catalog 소유자 조회·대조가 실패 | 없음 | PG `GET`·Catalog `GET`만 함. 원장·Inbox·월 합계는 만들지 않음 | `RecordPaidSale.execute`, `PgPayment.requireOrder`, `ProductOwner.requireOrder` | R1.3·R1.6 |
| 없음 | `OrderPaid` 수신 | 판매자 잠금 뒤 `saleMonth`보다 뒤 CLOSED 월이 없고, `saleMonth` 월 행이 OPEN(없으면 생성) | 기록됨, `settlementMonth = saleMonth` | `settlement_pg_transaction` 삽입(없을 때), 비용이 보고되면 `settlement_pg_fee` 삽입, Inbox 저장, 월 행 `add` | `RecordPaidSale.record`, `SettlementSale.record`, `RecordPgTransaction.execute` | R1.2·R2·R3.6 |
| 없음 | `OrderPaid` 수신 | 뒤 월이 CLOSED(이때 `saleMonth` 행은 만들지 않음) 또는 `saleMonth` 행이 CLOSED | 기록됨, `settlementMonth` = 현재 KST 월 | 현재 월 행을 잠그거나 만든 뒤 위와 같이 삽입 | `RecordPaidSale.record` | R3.5, 결정 42·130 |
| 없음 | `OrderPaid` 수신 | 이월할 현재 월 행이 CLOSED이거나, 3번 시도하는 동안 월이 바뀜 | 없음, 409 | 없음 | `RecordPaidSale.record` | — |
| 없음 | `OrderPaid` 수신 | 커밋 중 유일 제약 충돌 또는 일시 오류 | 5회 재시도 뒤 없음, 409 | 없음 | `RecordPaidSale.execute` | R9.1 |

### 확인된 환불 (`SettlementRefund`, `settlement_refund` + `settlement_refund_cancellation`)

상태 enum이 없다. 행이 있는지가 곧 상태다. 원매출 하나에 최대 1건이다(`uk_refund_order`).

| 상태 | 뜻 |
|---|---|
| (행 없음) | 아직 반영하지 않은 전액 환불 |
| 기록됨 | 원배분을 음수로 상계해 반영했다. 이후 바뀌지 않는다 |

| 직교 필드 | 값 | 뜻 |
|---|---|---|
| `monthReason` | `CONFIRMED_MONTH` / `CLOSED_MONTH_ROLLOVER` | 확인 월에 반영 / 확인 월이 마감돼 현재 OPEN 월에 반영 |
| `source` | `PG_RESPONSE` / `PG_QUERY_UNATTRIBUTED` | Payment가 보낸 금융 확인의 출처(V10 CHECK) |

| 현재 상태 | 트리거 | 가드(조건) | 다음 상태 | 부수 효과 | 코드 위치 | 근거 |
|---|---|---|---|---|---|---|
| 기록됨 | `RefundPaymentConfirmed` 수신(`settlement-confirmed-refund`) | `refundId` 또는 `orderNo`로 찾은 원장의 금융 확인이 같고 Inbox에 이미 있음 | 기록됨(그대로) | 없음. 최초 반영 월·정책·금액을 반환 | `RecordConfirmedRefund.existing` | R6.9 |
| 기록됨 | `RefundPaymentConfirmed` 수신 | 금융 확인 값이 다름, 또는 원장은 있는데 Inbox에는 첫 전달 | 기록됨(그대로), 409 | 없음 | `SettlementRefund.requireSame` | R6.9 |
| 없음 | `RefundPaymentConfirmed` 수신 | 원매출이 없음 | 없음, 404 | 없음 | `RecordConfirmedRefund.execute` | R6.7 |
| 없음 | `RefundPaymentConfirmed` 수신 | 원매출이나 `settlement_pg_transaction`과 값이 다름, 또는 거래 행이 없음 | 없음, 409 | 없음 | `ConfirmedRefund.requireSale`, `PgTransactionRecord.requireSale` | R6.4 |
| 없음 | `RefundPaymentConfirmed` 수신 | `pg-provider=SIMULATED` | 없음, 503 | 없음 | `UnavailablePgRefunds.require` | R6.8 |
| 없음 | `RefundPaymentConfirmed` 수신 | PortOne 취소 조회 결과가 전액이 아니거나, 원승인·지급 구성·취소 ID 목록이 다름 | 없음, 400/409 | PortOne `GET`만 함 | `PgRefund`, `PgRefund.requireOriginal`, `PgRefund.requireConfirmation` | R6.4·R6.10 |
| 없음 | `RefundPaymentConfirmed` 수신 | 원매출 행 잠금(`lockByOrderNo`) → 판매자 잠금. 확인 월보다 뒤 CLOSED가 없고, 확인 월 행이 OPEN(없으면 생성) | 기록됨, `CONFIRMED_MONTH`, `settlementMonth = refundMonth` | `FULL_REFUND_REVERSE_ORIGINAL_V1` 배분, 취소 근거 행, Inbox, 월 행 `addRefund` | `RecordConfirmedRefund.record`, `ReverseOriginalFullRefundPolicy.allocate`, `UseConfirmedRefundMonthPolicy.select` | R6.3·R6.5, 결정 41·42 |
| 없음 | `RefundPaymentConfirmed` 수신 | 뒤 월이 CLOSED(확인 월 행은 만들지 않음) 또는 확인 월 행이 CLOSED | 기록됨, `CLOSED_MONTH_ROLLOVER`, `settlementMonth` = 현재 KST 월 | 위와 같음. `addRefund`는 현재 월 행에 함 | `RecordConfirmedRefund.record`, `UseConfirmedRefundMonthPolicy.select` | R6.5, 결정 42·130 |
| 없음 | `RefundPaymentConfirmed` 수신 | 현재 월 행도 CLOSED, 3번 시도하는 동안 월이 바뀜, 또는 정책이 고른 월이 잠근 월과 다름 | 없음, 409 | 없음 | `RecordConfirmedRefund.record` | R6.6 |
| 없음 | `RefundPaymentConfirmed` 수신 | 커밋 중 유일 제약(`uk_refund_*`, 취소 PK `(provider, cancellation_id)`) 충돌 | 5회 재시도 뒤 없음, 409 | 없음 | `RecordConfirmedRefund.execute` | R9.4 |

### 판매자 월 (`SellerMonthlySettlement`, `seller_monthly_settlement`)

| 상태 | 뜻 |
|---|---|
| (행 없음) | 아직 반영도 마감도 없는 월. 같은 판매자의 뒤 월이 CLOSED면 행이 없어도 CLOSED로 본다(행은 만들지 않는다) |
| `OPEN` | 매출·환불 합계를 더하는 중. `closedAt IS NULL`(V6 `ck_month_closed`) |
| `CLOSED` | 마감됐다. `closedAt`이 확정되고 이후 합계는 바뀌지 않는다. CLOSED→OPEN 전이는 없다(마감 취소는 범위 밖) |

| 현재 상태 | 트리거 | 가드(조건) | 다음 상태 | 부수 효과 | 코드 위치 | 근거 |
|---|---|---|---|---|---|---|
| 없음 | 매출·환불 반영 | 대상 월보다 뒤 CLOSED가 없음 | `OPEN`(합계 0) | 행 삽입 | `RecordPaidSale.lockMonth`, `RecordConfirmedRefund.lockMonth`, `SellerMonthlySettlement.open` | R3.6 |
| 없음 | 매출·환불 반영 | 대상 월보다 뒤 CLOSED가 있음 | 없음(그대로) | 현재 KST 월 행에 반영 | `RecordPaidSale.record`, `RecordConfirmedRefund.record` | R3.5·R6.5, 결정 130 |
| `OPEN` | 매출 반영 | — | `OPEN` | `paidAmount`·`platformFeeAmount`·`sellerPayoutAmount`·`recordCount` 가산, `updatedAt` 갱신 | `SellerMonthlySettlement.add` | R1.2 |
| `OPEN` | 환불 반영 | — | `OPEN` | `refundAmount`·`refundedPlatformFeeAmount`·`refundedSellerPayoutAmount`·`refundCount` 가산, `updatedAt` 갱신 | `SellerMonthlySettlement.addRefund` | R6.4 |
| `CLOSED` | `add`·`addRefund` 호출 | 항상 | `CLOSED`, 409 | 없음. 서비스는 이 호출 전에 이월 경로로 돌린다 | `SellerMonthlySettlement.add`, `addRefund` | R6.12 |
| 모두 | `POST /api/v1/settlements/sellers/{sellerSubject}/months/{month}/close` | `month`가 현재 KST 월 이상 | 그대로, 409 | 없음 | `CloseSellerMonth.close` | R3.2·R3.3 |
| `CLOSED` | 같은 close | 판매자 잠금·월 행 잠금 뒤 | `CLOSED`(그대로) | 없음. 최초 금액과 `closedAt`을 반환하고 이월 원장 행을 더하지 않음 | `CloseSellerMonth.close` | R3.4 |
| 없음·`OPEN` | 같은 close | 같은 판매자의 뒤 월이 CLOSED | 그대로, 409 "이후 월이 이미 마감됐습니다" | 행을 만들지 않음 | `CloseSellerMonth.close` | R3.8, 결정 130 |
| 없음·`OPEN` | 같은 close | 같은 판매자의 앞 월에 `OPEN` 행이 있음 | 그대로, 409 "이전 월을 먼저 마감해야 합니다" | 없음 | `CloseSellerMonth.close` | R3.8, 결정 130 |
| 없음·`OPEN` | 같은 close | 매출·환불 원장의 합계·건수가 월 행과 다름 | 그대로, 409 | 없음(롤백) | `SellerMonthlySettlement.close` | R3.2·R6.12 |
| 없음·`OPEN` | 같은 close | 위 가드를 모두 통과 | `CLOSED`, `closedAt = updatedAt = now`. 행이 없으면 합계 0 행을 만들고 바로 마감 | 같은 트랜잭션에서 `settlement_seller_carryover` 한 행 삽입 | `CloseSellerMonth.close`, `CloseSellerMonth.carry`, `SellerCarryover.record` | R3.9·R3.10, 결정 39·121·130 |
| 없음·`OPEN` | 같은 close | 유일 제약 충돌 또는 일시 오류 | 5회 재시도 뒤 그대로, 원래 예외 | 없음 | `CloseSellerMonth.execute` | R3.10 |

직교 행:

| 직교 행 | 값 | 뜻·변화 | 코드 위치 |
|---|---|---|---|
| `settlement_seller_lock`(V14, `SellerLock`) | 없음 / 있음 | 판매자를 처음 반영하거나 마감할 때 삽입한다. 업무 값이 없고 바뀌지 않는다. 동시 삽입으로 충돌하면 각 서비스의 5회 재시도가 새 트랜잭션에서 다시 잠근다 | `SellerLock.of`, 세 서비스의 `lockSeller` |
| `settlement_seller_carryover`(V13, `SellerCarryover`) | 없음 / 있음(불변) | 마감할 때 한 번 삽입한다. `sourceMonth`는 앞의 마지막 CLOSED 월(없으면 NULL이고 `carriedIn = 0`). `carriedIn`은 그 월의 `carriedOut`(그 월에 원장 행이 없으면 `min(0, 순액)`). `payable = max(0, 순액 + carriedIn)`, `carriedOut = min(0, 순액 + carriedIn)`. 유일 키는 `(seller, month)`와 V15 `(seller, sourceMonth)` | `CloseSellerMonth.carry`, `SellerMonthBalance.settle`·`of` |
| 월 응답의 `carriedIn`·`payable`·`carriedOut` | `OPEN`이면 모두 `null` / `CLOSED`이고 원장이 있으면 원장 값 / `CLOSED`이고 원장이 없으면(V13 이전) `carriedIn = 0`과 순액으로 계산 | 조회할 때 계산하며 저장값은 바꾸지 않는다 | `SellerMonthBalance.of` |

### PG 지급 명세 (`PgReceipt`, `pg_receipt` + `pg_receipt_item`)

SIMULATED 공급자 전용이다. 조회와 재대사는 `provider = "SIMULATED"`로 고정돼 있다.

| 상태 | 뜻 |
|---|---|
| `REPORTED_PAID` | 공급자 명세를 접수했고 아직 모든 항목이 맞지는 않음(내부 매출 미도착·비용 대기 포함) |
| `MISMATCH` | 한 항목 이상이 `DIFFERENT_TRANSACTION`·`DIFFERENT_COST` |
| `SIMULATED_RECEIVED` | 모든 항목이 일치. `simulatedReceivedAt`이 확정된다(V7 `ck_receipt_state`). 이후에는 재대사하지 않는다 |

| 현재 상태 | 트리거 | 가드(조건) | 다음 상태 | 부수 효과 | 코드 위치 | 근거 |
|---|---|---|---|---|---|---|
| 없음 | `POST /api/v1/settlements/pg-receipts` | `pg-provider=PORTONE_TEST` | 없음, 503 | 없음 | `UnavailablePgSettlements.require` | R4.5 |
| 없음 | 같은 POST | SIMULATED. 트랜잭션 밖에서 명세를 조회하고, 응답 ID가 요청 ID와 같음 | `REPORTED_PAID` → 같은 호출에서 재대사 | 헤더·항목 삽입, `reportedBy` = JWT sub | `ReceivePgSettlement.execute`·`store`, `PgReceipt.receive` | R5.1 |
| 없음 | 같은 POST | 응답 ID가 다름(503), 같은 ID로 다른 명세가 옴(409), 항목의 거래가 다른 명세에 이미 있음(409) | 없음 | 없음 | `ReceivePgSettlement.execute`, `PgReceipt.requireSame` | R5.7 |
| 모두 | 같은 POST | 같은 ID의 접수본이 이미 있음 | 아래 재대사 결과 | 공급자를 다시 조회하지 않음 | `ReceivePgSettlement.execute` | R5.1 |
| `REPORTED_PAID`·`MISMATCH` | 재대사(`POST .../pg-receipts/{providerSettlementId}/reconcile` 또는 접수 직후) | 항목에 `DIFFERENT_TRANSACTION`·`DIFFERENT_COST`가 있음 | `MISMATCH` | 헤더 잠금, 항목 `mismatchCode`·`mismatchReason`과 `lastCheckedAt` 갱신 | `ReconcilePgReceipt.execute`, `PgReceiptItem.compare`, `PgReceipt.finishCheck` | R5.2·R5.4 |
| `REPORTED_PAID`·`MISMATCH` | 같은 재대사 | 불일치는 없고 `MISSING_SALE`·`PG_COST_PENDING`이 있음 | `REPORTED_PAID` | 같음 | 같음 | R5.4·R5.8 |
| `REPORTED_PAID`·`MISMATCH` | 같은 재대사 | 모든 항목 코드가 `null` | `SIMULATED_RECEIVED` | `simulatedReceivedAt` = now | `PgReceipt.finishCheck` | R5.3 |
| `SIMULATED_RECEIVED` | 같은 재대사 | — | 그대로 | 없음(대사 결과·시각을 갱신하지 않음) | `ReconcilePgReceipt.execute` | R5.5·R5.6 |

항목 `mismatchCode`(`PgReceiptItem.compare`). 위에서부터 처음 맞는 값을 쓴다.

| 코드 | 조건 |
|---|---|
| `MISSING_SALE` | 같은 `orderNo`의 `SettlementSale`이 없음 |
| `DIFFERENT_TRANSACTION` | `settlement_pg_transaction`이 없음, 또는 결제 키·금액·공급자 거래가 매출·거래 행과 다름 |
| `PG_COST_PENDING` | `settlement_pg_fee` 행이 없음 |
| `DIFFERENT_COST` | 확인된 비용과 명세 비용이 다름 |
| `null` | 일치 |

### PG 근거·비용 (`PgEvidence`·`PgPayment`·`PgFunding`·`PgTransactionRecord`·`PgFeeRecord`·`PgStatement`·`PgStatementItem`·`PgStatementCheck`·`PgCostPosting`)

`PgEvidence`·`PgPayment`·`PgFunding`은 값 객체라 상태가 없다. 생성할 때 이렇게 검사한다.
- `PgEvidence.provider`는 `SIMULATED`·`PORTONE_TEST`만 받는다.
- `PgPayment`는 `PORTONE_TEST`일 때만 `PgFunding`이 필수이고, 다른 공급자는 NULL이어야 한다. 비용은 결제액 이하여야 한다.
- `PgFunding`은 `providerChannelType = "TEST"`와 `benefitPolicyId = TOSSPAY_EXTERNAL_BENEFIT_V1`로 고정된다(V9 CHECK: 전부 없음 또는 전부 검증).

**승인 거래·비용 행** — 행이 있는지가 곧 상태다.

| 행 | 상태 | 뜻 |
|---|---|---|
| `settlement_pg_transaction`(`PgTransactionRecord`) | 없음 / 있음(불변) | 접수한 PG 승인 근거 |
| `settlement_pg_fee`(`PgFeeRecord`) | 없음 = 미확정(`pgCostStatus=UNCONFIRMED`) / 있음 = 확인(`CONFIRMED`, 0원 포함) | 거래별로 확인된 비용 |

| 현재 상태 | 트리거 | 가드(조건) | 다음 상태 | 부수 효과 | 코드 위치 | 근거 |
|---|---|---|---|---|---|---|
| 거래 없음 | `OrderPaid` 매출 기록 | 매출 트랜잭션 안 | 거래 있음. 비용이 보고되면 비용도 있음(`recordedBy = settlement-paid-order`) | — | `RecordPgTransaction.execute` | R1.2·R4.6 |
| 거래 있음 | 매출 기록·비용 재조회 | 보고된 거래 근거나 `PgFunding`이 다름 | 그대로, 409 | 없음 | `PgTransactionRecord.requireSame` | R10.4 |
| 거래 없음 | `POST /api/v1/settlements/pg-transactions/{paymentId}/refresh` | — | 그대로, 404 | 없음 | `SynchronizePgTransaction.execute` | R10.1 |
| 비용 없음 | 같은 refresh | 트랜잭션 밖 공급자 `GET`이 비용을 보고하고, 거래 행을 잠금 | 비용 있음(`recordedBy` = JWT sub) | 매출·월 합계는 바뀌지 않음 | `SynchronizePgTransaction.execute`, `PgFeeRecord.record` | R10.1·R10.3 |
| 비용 없음 | 같은 refresh | 비용 보고가 없음 | 비용 없음(그대로) | 없음 | 같음 | R10.2 |
| 비용 있음 | 같은 refresh | 같은 비용 / 다른 비용 | 그대로 / 그대로, 409 | 없음 | `PgFeeRecord.requireSame` | R10.2 |

**토스페이 수집본 헤더** (`PgStatement`, `settlement_pg_statement`, `status`):

| 상태 | 뜻 |
|---|---|
| `EMPTY_TEST` / `EMPTY` | TEST / LIVE 조회 결과가 비어 있음. 재대사해도 상태는 그대로이고 `checkedAt`만 바뀐다 |
| `RECEIVED` | 행이 있는 원본을 저장했고 아직 대사하지 않음 |
| `PENDING` | 불일치는 없지만 대기 항목이 있음 |
| `MISMATCH` | 불일치 항목이 있음 |
| `MATCHED_REPORTED` | 모든 항목이 일치 |

| 현재 상태 | 트리거 | 가드(조건) | 다음 상태 | 부수 효과 | 코드 위치 | 근거 |
|---|---|---|---|---|---|---|
| 없음 | `POST /api/v1/settlements/pg-statements` 또는 스케줄 `SynchronizePgStatements`(`enabled`·`scheduled-enabled`가 켜져 있을 때, KST 10시 이후, 오늘 DUE와 lookback 기간의 DUE·SETTLE) | 미래·1년 넘은 날짜·오늘 SETTLE은 400, 10시 전 오늘 DUE는 409, 수집 미설정은 503 | 없음 | 없음 | `ReceivePgStatement.execute`, `UnavailablePgStatementSource.fetch` | R7.16·R7.17 |
| 없음 | 같은 수집 | 같은 fingerprint가 없음 | 행이 0개면 `EMPTY_TEST`/`EMPTY`, 아니면 `RECEIVED` → 같은 호출에서 재대사 | 페이지 원문, 정본 거래 `entry`(없을 때), 항목 `UNCHECKED` 삽입. 원본을 먼저 커밋 | `ReceivePgStatement.record`, `PgStatement.record` | R7.1·R7.11·R7.12 |
| 모두 | 같은 수집 | 같은 fingerprint가 있음 | 그대로 → 재대사 | 최초 수집자·시각 유지 | `ReceivePgStatement.record` | R7.11 |
| 없음 | 같은 수집 | 같은 가맹점·환경·거래 ID의 `entry` 내용이 다름 | 없음, 409 | 없음(롤백) | `PgStatementEntry.requireSame` | R7.11 |
| `RECEIVED`·`PENDING`·`MISMATCH`·`MATCHED_REPORTED` | 재대사(수집 직후, `POST .../pg-statements/{id}/reconcile`, 수집 주기 끝의 `ReconcilePendingPgStatements`) | 항목 중 불일치 코드가 있음 | `MISMATCH` | 헤더와 관련 `entry` 잠금, 항목 `checkCode`·`paymentId`·`checkedAt` 갱신, `PgCostPosting` 생성·갱신 | `ReconcilePgStatement.execute`, `PgStatement.finishCheck` | R7.2·R7.5 |
| 같음 | 같은 재대사 | 불일치는 없고 대기 코드가 있음 | `PENDING` | 같음 | 같음 | R7.13·R7.14 |
| 같음 | 같은 재대사 | 모두 `MATCHED_REPORTED` | `MATCHED_REPORTED` | 같음 | 같음 | R7.3·R7.4 |
| `RECEIVED`·`PENDING` | 스케줄 재대사 | ID 커서로 100개씩, PG를 다시 부르지 않음, 실패는 로그만 남기고 다음 순회에 다시 시도 | 위 셋 중 하나 | 같음 | `ReconcilePendingPgStatements.run`, `PgStatementRepository.pendingAfter` | R7.6 |

**수집본 항목** (`PgStatementItem.checkCode`): 처음에는 `UNCHECKED`이고, 재대사할 때마다 `PgStatementCheck.compare` 결과로 덮어쓴다. 위에서부터 처음 맞는 값을 쓰며, 분류는 `PgStatementCheck.matched`·`mismatch`를 따른다.

| 코드 | 조건 | 분류 |
|---|---|---|
| `TEST_NOT_SETTLEABLE` | 수집 범위의 환경이 TEST | 대기 |
| `MISSING_SALE` | 정본 거래 `orderNo`(= PortOne `paymentId`)에 해당하는 매출 또는 거래 행이 없음 | 대기 |
| `SOURCE_ENVIRONMENT_PENDING` | 거래 행의 공급자가 `PORTONE_LIVE`가 아니거나 채널 유형이 `LIVE`가 아님 | 대기 |
| `IDENTITY_UNVERIFIED` | `identity-mapping-verified=false` | 대기 |
| `DIFFERENT_MERCHANT` · `DIFFERENT_ORDER` · `DIFFERENT_TRANSACTION` | 상점·채널 / 주문 / payToken이 다름 | 불일치 |
| `AMBIGUOUS_PAYMENT` | 같은 결제 묶음에 승인이 2건 이상 | 불일치 |
| `MATCHED_REPORTED` · `DIFFERENT_AMOUNT` | PAY 행: 금액이 같음 / 다름 | 일치 / 불일치 |
| `MISSING_REFUND` | 환불 행인데 환불 원장이 없음 | 대기 |
| `DIFFERENT_TRANSACTION` | 환불 행의 payToken이 환불 원장 거래와 다름 | 불일치 |
| `EXCESS_REFUND` | 관측한 환불 합계가 내부 전액 환불보다 큼 | 불일치 |
| `MATCHED_REPORTED` · `REFUND_AMOUNT_PENDING` | 관측한 환불 합계가 내부 전액 환불과 같음 / 작음 | 일치 / 대기 |

**비용 연결** (`PgCostPosting`, `settlement_pg_cost_posting.check_code`. 정본 거래 하나에 한 행, `uk_pg_cost_source`):

| 현재 상태 | 트리거 | 가드(조건) | 다음 상태 | 부수 효과 | 코드 위치 | 근거 |
|---|---|---|---|---|---|---|
| 없음 | 수집본 재대사 | 항목 검사가 `MATCHED_REPORTED`이고, 정본 거래가 LIVE, 매출 공급자가 `PORTONE_LIVE`, 거래가 일치 | `MATCHED_REPORTED` | `businessMonth`는 PAY면 `SettlementSale.settlementMonth`, 환불이면 `SettlementRefund.settlementMonth`. 정책 `LINKED_BUSINESS_MONTH_V1` | `PgCostPosting.record` | R7.3·R7.4, 결정 45 |
| 없음 | 같은 재대사 | 검사가 일치가 아님 | 없음 | 없음 | `ReconcilePgStatement.execute` | — |
| `MATCHED_REPORTED`·`AMBIGUOUS_PAYMENT`·`EXCESS_REFUND` | 같은 재대사 | 새 검사가 `MATCHED_REPORTED`·`AMBIGUOUS_PAYMENT`·`EXCESS_REFUND` 중 하나 | 그 코드 | 일치면 기존 연결과 같은지 확인하고, 다르면 409 | `PgCostPosting.check`, `PgCostPosting.requireSame` | R7.5 |
| 모두 | 같은 재대사 | 새 검사가 그 밖의 코드(`IDENTITY_UNVERIFIED`·`DIFFERENT_*` 등) | 그대로 | 없음 | `PgCostPosting.check` | R7.15 |

월 금융 요약(`PgCostPostingRepository.summarize`)과 지급일 집계(`PgStatementEntryRepository.payoutTotals`)는 `check_code = 'MATCHED_REPORTED'`인 연결만 확정으로 센다.

### 찾은 불일치 (결정 143 1부)

경로는 저장소 기준이다. `…/settlement/`는 `apps/settlement/src/main/java/com/stove/settlement/`를 줄여 쓴 것이다.

1. **매출에는 `CLOSED_MONTH_ROLLOVER` 사유가 저장되지 않는다.** 설계는 늦은 매출을 `CLOSED_MONTH_ROLLOVER`로 반영한다고 쓴다(`.kiro/specs/settlement/design.md:19`). 그러나 `SettlementSale`에는 사유 열이 없고 `saleMonth`·`settlementMonth`만 저장한다(`…/settlement/core/domain/SettlementSale.java:38`·`:64`). 이 값은 환불 월 정책에만 있다(`…/settlement/core/domain/UseConfirmedRefundMonthPolicy.java:13`). 영향: 없음. 두 월을 비교하면 같은 사실을 알 수 있다.
2. **설계의 "PG 수집·대사 상태" 표가 코드에 있는 값과 다르다.** `TEST_NOT_SETTLEABLE`(`…/settlement/core/domain/PgStatementCheck.java:13`)이 표(`.kiro/specs/settlement/design.md:316`~`:326`)에 없다. SIMULATED 명세 항목 코드인 `PG_COST_PENDING`·`DIFFERENT_COST`(`…/settlement/core/domain/PgReceiptItem.java:43`~`:44`)도 표에 없고 요구사항 R5.8(`.kiro/specs/settlement/requirements.md:84`)에만 나온다. 이 두 값은 `PgStatementCheck`가 아니라 `PgReceiptItem`의 코드다. `DIFFERENT_TRANSACTION`은 두 곳에서 서로 다른 조건으로 쓰인다. 영향: 없음(문서 누락).
3. **TEST 수집본은 행이 있으면 `PENDING`에서 벗어나지 못하고 주기마다 다시 대사된다.** TEST 범위의 항목은 모두 `TEST_NOT_SETTLEABLE`(대기)이라 헤더가 `PENDING`이 된다(`…/settlement/core/domain/PgStatement.java:67`). 그리고 `pendingAfter`가 `RECEIVED`·`PENDING`을 계속 고른다(`…/settlement/core/domain/PgStatementRepository.java:20`). 요구사항은 TEST 비용을 미확정으로 둔다고만 쓴다(`.kiro/specs/settlement/requirements.md:114`). 영향: 금액에는 없음. 주기마다 쓸모없는 재대사가 생긴다(추정).
4. **다른 수집본에서 `AMBIGUOUS_PAYMENT`·`EXCESS_REFUND`가 나와도, 이미 `MATCHED_REPORTED`로 끝난 헤더는 다시 대사되지 않는다.** 연결의 `check_code`는 바뀐다(`…/settlement/core/domain/PgCostPosting.java:53`). 하지만 스케줄 재대사는 `RECEIVED`·`PENDING`만 고른다(`…/settlement/core/domain/PgStatementRepository.java:20`). 요구사항 R7.5(`.kiro/specs/settlement/requirements.md:112`)는 연결만 갱신한다고 쓰므로 코드와 충돌하지는 않는다. 다만 옛 헤더와 항목은 `MATCHED_REPORTED`로 남는다. 영향: 없음(월 집계는 연결 기준). 수집본 목록의 표시만 어긋난다(추정).
5. **지금 코드로는 `MATCHED_REPORTED` 비용 연결이 만들어질 수 없다.** `PgEvidence`는 `SIMULATED`·`PORTONE_TEST`만 받고(`…/settlement/core/domain/PgEvidence.java:7`), `PgFunding.providerChannelType`은 `"TEST"`로 고정이다(`…/settlement/core/domain/PgFunding.java:30`). 그런데 일치 판정과 연결 생성은 `PORTONE_LIVE`·`LIVE`를 요구한다(`…/settlement/core/domain/PgStatementCheck.java:15`, `…/settlement/core/domain/PgCostPosting.java:37`). 그래서 운영 경로로 접수한 거래는 항상 `SOURCE_ENVIRONMENT_PENDING`에서 멈춘다. 설계는 "검증된 PG 정본 거래 비용을 … 한 번 연결"한다고 쓰고(`.kiro/specs/settlement/design.md:21`), 한계 절(`.kiro/specs/settlement/design.md:374`)은 LIVE를 검증 공백이라고만 적는다. 더 나아가 DB 픽스처로 넣은 `PORTONE_LIVE` 거래 행은 `PgTransactionRecord.payment()`가 `PgEvidence`를 만들 때 400으로 실패한다(`…/settlement/core/domain/PgTransactionRecord.java:40`~`:41`). 이 메서드를 환불 기록(`…/settlement/core/service/RecordConfirmedRefund.java:66`)과 SIMULATED 명세 대사(`…/settlement/core/domain/PgReceiptItem.java:39`)가 부른다(추정). 영향: 불명. 지금은 비용 미확정이 유지되지만, LIVE로 바꾸면 비용 확정과 환불 반영 경로가 막힌다.
6. **`RefundExecutionSucceeded`를 처리하는 Settlement 코드가 없다.** 설계와 요구사항은 늦은 `RefundExecutionSucceeded`를 보강 사건으로 처리한다고 쓴다(`.kiro/specs/settlement/design.md:333`, `.kiro/specs/settlement/requirements.md:92`). 그러나 리스너는 `REFUND_PAYMENT_CONFIRMED`만 처리하고 다른 종류는 무시한다(`…/settlement/api/listener/RefundEventListener.java:21`). Payment가 보강을 `RefundPaymentConfirmed`(`source=PG_QUERY_UNATTRIBUTED`)로 바꿔 보내는지는 확인하지 못했다. 영향: 불명.
7. **SIMULATED 환불의 "503"은 HTTP 응답이 아니다.** R6.8은 503을 반환한다고 쓴다(`.kiro/specs/settlement/requirements.md:98`). 실제로는 Kafka 소비 중에 `UPSTREAM_UNAVAILABLE` 예외가 나고(`…/settlement/adapter/UnavailablePgRefunds.java:12`, `…/settlement/core/service/RecordConfirmedRefund.java:67`) 공통 재시도/DLT로 간다. 재시도 설정은 확인하지 못했다. 영향: 있음. SIMULATED 환경의 전액 환불은 월 환불 합계에 들어가지 않아 판매자 순액이 그만큼 크게 남는다. 다만 의도된 미연동이다.
8. **마감 재시도가 다 떨어지면 409가 아니라 원래 DB 예외를 던진다.** 매출·환불·명세 수집은 5회 뒤 `CONFLICT`로 바꾼다. 그런데 마감은 `DataIntegrityViolationException`·`TransientDataAccessException`을 그대로 던진다(`…/settlement/core/service/CloseSellerMonth.java:46`). 설계와 요구사항에는 이 경우의 응답이 없다(`.kiro/specs/settlement/design.md:356`, `.kiro/specs/settlement/requirements.md:60`). HTTP 상태는 공통 오류 처리기에 달려 있으며 확인하지 못했다. 영향: 없음. 롤백되므로 행은 바뀌지 않는다.
9. **행이 없는 지난 월을 마감하면 합계 0의 CLOSED 행과 이월 원장 행이 생긴다.** `CloseSellerMonth.close`가 OPEN 행을 만든 뒤 바로 마감한다(`…/settlement/core/service/CloseSellerMonth.java:62`). 설계와 요구사항은 "행 없는 월은 건너뛴다"를 이월 출처와 순서 검사에만 쓰고(`.kiro/specs/settlement/design.md:20`, `.kiro/specs/settlement/requirements.md:58`), 빈 월을 마감하면 행이 생긴다는 말은 없다. 이 행이 생기면 그 판매자의 앞 월은 모두 "뒤 월 CLOSED"로 판정돼, 늦게 온 매출·환불이 현재 월로 넘어간다. 영향: 있음(추정). 반영 월이 바뀐다. 이월액은 그대로 다음 월로 넘어간다(순액이 0이면 `carriedOut = carriedIn`). — 확인(결정 143 검토): 늦은 거래를 현재 월로 넘기는 것은 R3.5("더 뒤 월이 CLOSED이면(그 월에 행이 없어도)")와 결정 130대로다. 결함이 아니라 "빈 월 마감은 합계 0 행을 만든다"는 문장이 요구사항에 없는 문서 공백이다.

## API 계약

공개 명세는 현재 19개 경로·21개 연산이다. 기존 MVP 9개 연산에 환불 조회 3개, 토스페이 정산 수집 6개, PG 지급일/비용 조회 2개를 더했다. CREATOR `/me/**`는 JWT sub로 제한하고 나머지는 ADMIN 전용이다.

| 메서드 | 경로 | 권한·동작 |
|---|---|---|
| GET | `/api/v1/settlements/me/sales` | CREATOR 본인 매출 페이지. 환불된 매출은 `refund` 연결(환불 ID·확인/반영 월·사유) |
| GET | `/api/v1/settlements/me/months` | CREATOR 본인 월 합계 페이지 |
| GET | `/api/v1/settlements/me/refunds` | CREATOR 본인 환불 조정(음수 조정액·확인/접수 월·사유). 구매자·PG 실행 정보 미노출 |
| GET | `/api/v1/settlements/sales` | ADMIN 전체/판매자별 매출. `sellerSubject`·PG 비용·플랫폼 잔여액·정책 포함, `sale.refund`는 본인 조회와 같은 연결 |
| GET | `/api/v1/settlements/months` | ADMIN 월 금융 요약. 반영 월의 매출 비용·환불 반환 조합. 미확인 시 NULL |
| GET | `/api/v1/settlements/refunds` · `/{refundId}` | ADMIN 환불 목록(필터·정책/출처 요약)·상세(취소별 근거) |
| POST | `/api/v1/settlements/sellers/{sellerSubject}/months/{month}/close` | ADMIN 지난 월 마감. 같은 판매자의 이전 월에 OPEN 행이 있거나, 대상 월이 CLOSED가 아닌데 뒤 월이 CLOSED면 409 |
| POST | `/api/v1/settlements/pg-transactions/{paymentId}/refresh` | ADMIN 접수한 거래의 PG 근거 재조회·확인 비용 1회 저장(R10). 본문 없음, 다른 비용 409 |
| POST·GET | `/api/v1/settlements/pg-receipts` (+ `/{providerSettlementId}`, `/reconcile`) | ADMIN SIMULATED 명세 수집·접수·목록·상세·재대사 |
| POST·GET | `/api/v1/settlements/pg-statements` (+ `/{id}`, `/items`, `/pages/{position}`, `/reconcile`) | ADMIN 토스페이 정산 전체 페이지 수집·원본/거래·대사·재대사 |
| GET | `/api/v1/settlements/pg-payouts` · `/items` | ADMIN 보고 지급일(`PG_REPORTED`) 월별 거래액·수수료·VAT·순액과 정본 거래 페이지 |

- 목록 `page=0`·`size=20`(page ≥ 0·1 ≤ size ≤ 100). 매출 `occurredAt DESC·id DESC`, 월 `settlementMonth DESC·id DESC`.
- 익명 401, 역할 없음·CREATOR의 운영 경로 접근 403, 잘못된 필터/페이지/월 400. 조회가 월 행을 생성하지 않는다.
- SIMULATED 외 공급자의 신규 명세 수집은 503, 공급자 명세 없음 404, 키·명세 충돌 409.

### 월·매출 응답의 금액 필드

| 필드 | 의미 |
|---|---|
| `pgCostStatus` | 매출: CONFIRMED/UNCONFIRMED. 월: CONFIRMED/UNCONFIRMED/PARTIAL |
| `confirmedPgFeeAmount` · `salePgFeeAmount` · `pendingPgCostCount` | 확인된 승인 비용 부분합 / 매출 비용 전체 확인 시 총액 / 미확인 건수(환불 반환 미확인 포함) |
| `refundAmount` · `refundedPlatformFeeAmount` · `refundedSellerPayoutAmount` · `refundCount` | 양수 환불 합계 |
| `netPaidAmount` · `netPlatformFeeAmount` · `netSellerPayoutAmount` | 매출 총합 − 환불 합계. 음수 허용 |
| `carriedIn` · `payable` · `carriedOut` | CLOSED 월: 직전 CLOSED 월에서 받은 이월(≤ 0) / 지급 기준액(≥ 0) / 다음 마감에 넘기는 이월(≤ 0). 이월 원장이 없는 옛 CLOSED 월은 `carriedIn = 0`, `payable = max(0, 순액)`, `carriedOut = min(0, 순액)`. OPEN 월은 확정 전이라 세 값 모두 `null` |
| `refundPgFeeReturnAmount` · `confirmedRefundPgFeeReturnAmount` · 승인/반환 VAT · `confirmedNetPgCostAmount` | 환불 비용 반환과 VAT, 확인된 순비용 |
| `pgFeeAmount` · `platformNetAmount` | 매출 비용(수수료+VAT) − 확인된 반환 / 환불 조정 후 플랫폼 몫 − 순비용. 미확인이 하나라도 있으면 NULL |

### 매출의 환불 연결 (`SaleResponse.refund`)

환불 원장은 `uk_refund_order(order_no)`와 전액 환불만 받는 계약 때문에 원매출당 최대 1건이다. 그래서 목록이 아닌 단일 객체이며, 환불이 없으면 `null`이다.

| 필드 | 의미 |
|---|---|
| `refundId` | 환불 원장의 환불 ID. `/me/refunds`·`/refunds/{refundId}`와 같은 값 |
| `confirmedAt` · `refundMonth` | 최초 금융 확인 시각과 그 KST 월 |
| `settlementMonth` · `monthReason` | 환불을 실제 반영한 월과 선택 사유(`CONFIRMED_MONTH`/`CLOSED_MONTH_ROLLOVER`). 매출의 `settlementMonth`와 다를 수 있다 |

- 금액 조정(음수 상계액)은 환불 조회가 정본이며 매출 응답에 다시 싣지 않는다. 매출 조회의 월 필터는 매출 반영 월 기준이고 환불 반영 월로 거르지 않는다.
- `SearchSales`가 매출 페이지의 `orderNo` 묶음으로 `findByOrderNoIn`을 한 번 호출해 붙인다(페이지가 비면 조회하지 않음). 스키마 변경 없음.

### PG 수집·대사 상태

| 코드 | 뜻 |
|---|---|
| `EMPTY_TEST` / `EMPTY` | TEST·LIVE 조회의 빈 관측. 비용 0원·지급 완료가 아님 |
| `RECEIVED`·`UNCHECKED` / `PENDING` | 원본 접수 후 대사 전 / 근거 대기 |
| `MISSING_SALE`·`MISSING_REFUND` | 내부 대응 사실 미도착 |
| `SOURCE_ENVIRONMENT_PENDING` · `IDENTITY_UNVERIFIED` | LIVE 승인 근거 없음 · 거래 키 대응 미검증 |
| `REFUND_AMOUNT_PENDING` | 관측 PG 환불 합계 < 내부 전액 환불 |
| `MISMATCH` | DIFFERENT_MERCHANT/ORDER/TRANSACTION/AMOUNT·`EXCESS_REFUND`·`AMBIGUOUS_PAYMENT` |
| `MATCHED_REPORTED` | 검증 범위에서 PG 보고와 내부 사실 일치. 은행 실입금·송금 완료 아님 |

## 이벤트 계약

| 방향 | 이벤트 | 그룹·비고 |
|---|---|---|
| 수신 | `OrderPaid` (stove.order.v1, 키 orderNo) | `settlement-paid-order`. 매출 접수. 13개 값·Inbox 원자 저장 |
| 수신 | `RefundPaymentConfirmed` (Payment) | `settlement-confirmed-refund`. 전액 환불 금융 확인. 늦은 `RefundExecutionSucceeded`는 보강 사건 |

Settlement는 업무 이벤트·Outbox를 발행하지 않는다(별도 정산 소비자 없음). 공통 Outbox 릴레이는 비활성화한다.

## 외부 연동

| 포트 (Settlement 소유) | 어댑터 | 선택·용도 |
|---|---|---|
| `PgPayments` | `SimulatedPgPaymentHttpClient` / `PortOnePaymentHttpClient` | 주문 근거에 연결할 승인 거래·비용 조회. `stove.settlement.pg-provider` = `SIMULATED` 기본, `PORTONE_TEST` 명시 선택 |
| `PgSettlements` | `SimulatedPgSettlementHttpClient` | SIMULATED 지급 명세 조회 (PORTONE 프로필은 미연동 503) |
| `PgRefunds` | `PortOneRefundHttpClient` | 전액 취소·성공 취소 ID·합계·지급 구성 직접 GET (SIMULATED 미연동 503) |
| 토스페이 정산 | `TossPayStatementHttpClient` | `POST /api/v2/settlement-details` 전체 페이지·원본 수집 (결정 43) |
| `CatalogOwners` | `CatalogOwnerHttpClient` | Catalog 내부 소유자 GET, 전용 키 |

- PG 접근 키는 역할별로 분리한다: Settlement에는 읽기 전용 키만 제공하고 제어 키는 주지 않는다. 플랫폼 JWT·Gateway ADMIN은 PG 제어 권한이 아니다.
- Catalog 소유자 GET은 `CATALOG_SETTLEMENT_SERVICE_KEY`(Download 키와 다름)를 사용하고 Gateway에 `/internal/**`·PG 제어 경로를 추가하지 않는다.
- PortOne 읽기 어댑터는 `GET /payments/{paymentId}?storeId=...`만 호출한다. 접수 대상은 PAID·취소액 0의 원승인과, 전액 성공 취소가 검증된 `CANCELLED` 원승인(지연 원매출)이다. 나노초 시각은 마이크로초로 정규화한다.
- 토스페이 키는 요청 본문에만 쓰고 원본·설정 문자열·로그에 남기지 않는다. 기본 URL은 HTTPS `pay.toss.im`, PortOne은 HTTPS `api.portone.io`이며 계약 검증에서만 루프백을 허용한다. 불완전한 설정·미지원 공급자는 시작 시 거절하고 자동 fallback·TEST→LIVE 자동 전환이 없다.
- base URL·키·연결(2초)/읽기(5초) 제한은 config에서 조립하고 로그·검증 요약에 키를 남기지 않는다. 연결/조회 실패를 거절·미존재·지급 완료로 바꾸지 않는다.

## 처리 흐름 요약

- **매출 접수(RecordPaidSale):** 기존 원장 조회 → (신규) PG 승인/비용·Catalog 소유자 직접 조회·대조 → 판매자 잠금 → 뒤 월 CLOSED 확인(참이면 원래 월 행 없이 현재 월로) → 월 행 생성/잠금·마감·지연 규칙으로 반영 월 확정 → Inbox·SettlementSale·OPEN 월 합계 원자 커밋.
- **월 마감(CloseSellerMonth):** 이전 월만, 판매자 잠금, 월 행 잠금, 이미 CLOSED면 최초 결과 반환, 뒤 월이 CLOSED면 409(행 생성 없음), 같은 판매자의 더 이전 OPEN 행이 있으면 409, 원장 합계·건수 대조 후 CLOSED·closedAt 확정, 같은 트랜잭션에서 직전 CLOSED 월의 이월을 받아 이월 원장 한 행 삽입. 재요청은 최초 결과 반환(원장 행 추가 없음). 같은 판매자의 마감·반영은 판매자 잠금으로 한 줄로 서고, 유일 제약(월·출처 월)이 두 번째 행을 막는다.
- **판매자 잠금 순서.** 환불은 원매출 행(`lockByOrderNo`) → 판매자 잠금 → 월 행, 매출과 마감은 판매자 잠금 → 월 행 순서로 잠근다. 마감은 매출 행을 잠그지 않으므로 순환 대기가 없다. 판매자 잠금 행이 없으면 그 트랜잭션이 삽입하고, 동시 삽입의 유일 키 충돌은 서비스의 기존 재시도(5회)가 새 트랜잭션에서 다시 잠근다.
- **PG 수령(ReceivePgSettlement/ReconcilePgReceipt):** 명세 접수(REPORTED_PAID) → 별도 트랜잭션 재대사 → 전 항목 일치 시 SIMULATED_RECEIVED.
- **환불(RecordConfirmedRefund):** 금융 확인 소비 → 원매출·PG 취소 직접 GET 대조 → 배분 A·월 정책 A 적용 → Inbox·환불 원장·월 환불 합계 원자 저장.
- **PG 비용(ReconcilePgStatement/ReconcilePendingPgStatements):** 토스페이 수집(트랜잭션 밖, 원본 선행 커밋) → 정본 거래 잠금·대조·비용 연결 원자 저장 → 주기 재대사(PG 재호출 없음).

## DB 전환과 보존

- Settlement V1~V5 보존, V6(매출 2개 테이블)·V7(PG 명세 2개)·V8(책임 분리·legacy 열 이름 변경)·V9(PgFunding CHECK)·V10(환불 원장)·V11(토스페이 수집)·V12(PG 비용 연결)·V13(판매자 이월 원장)·V14(판매자 잠금)·V15(이월 출처 유일)로 확장.
- Payment V12는 `purchase_payment` 비용 스냅샷만 추가하고, 외부 모의 PG는 자체 Flyway로 공급자 테이블을 만든다. 플랫폼 Flyway와 스키마/이력을 공유하지 않는다.
- 과거 열은 nullable `legacy_*`로 보존하고 현재 모델은 읽거나 갱신하지 않는다. V9 CHECK는 전부 없음/전부 검증된 PgFunding 구성만 허용하며, 과거 행에 실결제·혜택·외부 부담을 소급 생성하지 않는다.
- **V13 적용 절차.** V13은 빈 새 테이블 하나(`CREATE TABLE` 한 문장, CHECK·유일 제약 포함)만 만들고 기존 테이블을 바꾸거나 채우지 않는다. 그래서 기존 행이 새 CHECK에 걸려 이행이 중간에 멈추는 경우가 없다. MySQL 8의 `CREATE TABLE`은 원자 DDL이라 실패하면 테이블이 남지 않는다. 실패 시 Flyway 이력에 실패 행이 남으므로 원인을 고친 뒤 `flyway repair`로 그 행을 지우고 다시 `migrate`한다. 적용 전 마감한 월은 원장 행이 없고 `carriedIn = 0`으로 읽는다([결정 130](../../../docs/decisions.md#seller-close-order-carryover)). 적용 뒤 첫 마감은 직전 CLOSED 월이 옛 월이면 그 월의 `min(0, 순액)`을 이월로 받는다.
- **V14·V15 적용 절차.** 둘을 나눈 것은 부분 실패 범위를 한 문장씩으로 묶기 위해서다. V14는 빈 `settlement_seller_lock` 테이블 하나(`CREATE TABLE` 한 문장)만 만든다. 기존 행을 읽거나 채우지 않고, 판매자 행은 첫 반영·마감 때 생긴다. V15는 `settlement_seller_carryover`에 `uk_seller_carryover_source (seller_subject, source_month)`를 더하는 `ALTER TABLE` 한 문장이다. MySQL 8 InnoDB의 `ALTER TABLE ... ADD UNIQUE`는 원자 DDL이어서, 실패하면 색인이 남지 않고 원장 행도 바뀌지 않는다. 같은 판매자가 같은 출처 월을 두 번 받은 원장 행(D-046이 운영에서 이미 일어난 경우)이 있으면 V15만 실패하고 V14는 적용된 채 남는다. 이때 앱은 Flyway 검증에서 멈춘다. 적용 전에 아래 SQL로 겹친 출처를 센다: `SELECT seller_subject, source_month, COUNT(*) FROM settlement_seller_carryover WHERE source_month IS NOT NULL GROUP BY seller_subject, source_month HAVING COUNT(*) > 1`. 0행이면 그대로 적용한다. V13이 PR #154로 막 들어왔고 PORTONE_TEST뿐이라 0행일 가능성이 크다(추정). 행이 나오면 원장을 고치거나 지우지 않는다. 마감본·원장 정정은 정책 선택이라(결정 42 마감본 불변, 결정 38 자료 삭제 승인) 사용자에게 묻고, 결정에 따라 정정한 뒤 `flyway repair`로 실패 이력 행을 지우고 다시 `migrate`한다. 옛 순서 없는 마감 자료(뒤 월 CLOSED 앞의 OPEN 행)는 V14·V15가 바꾸지 않는다. 그 처리는 옛 자료 탐지 작업 항목([PR #153](https://github.com/tlswltjq/esd-platform/pull/153)의 settlement `tasks.md`)이 맡는다.

## 검증 범위와 한계

- MVP 완료(2026-10-18): 단위 1,014·관련 통합 152·실제 프로세스 여정 32단계.
- G3 누적: 책임 분리 75 → 외부 혜택 84 → 환불 월 102 → 환불 원장 120 → 토스페이 수집 144 → PG 비용 반영 월 167 → 테스트 경계 176건, 각 check·bootJar 통과.
- 현재 PortOne TEST 비용은 미확정. 정상 LIVE 입력→비용 저장/SQL 집계, 동시 첫 비용 INSERT, 해당 INSERT 실패 롤백은 제약 보존 상태의 검증 공백으로 남는다. 합성 LIVE DB 픽스처·루프백 계약 검증을 실제 LIVE 금융 검증으로 설명하지 않는다.

## 사용 안내

| 설정 | 값·역할 |
|---|---|
| `MOCK_PG_URI`·`MOCK_PG_SETTLEMENT_KEY` | SIMULATED(기본) 공급자 주소와 Settlement 읽기 전용 키. Payment 키·제어 키와 서로 달라야 한다 |
| `CATALOG_BASE_URL`·`CATALOG_SETTLEMENT_SERVICE_KEY` | 상품 소유자 조회 |
| `SETTLEMENT_PG_PROVIDER=PORTONE_TEST` + `PORTONE_API_BASE_URL`·`PORTONE_API_SECRET`·`PORTONE_STORE_ID`·`PORTONE_CHANNEL_KEY` | PortOne TEST 원승인 읽기 선택. 이 모드의 신규 `/pg-receipts` 수집은 503 |
| `SETTLEMENT_TOSSPAY_ENABLED`(기본 false)·`TOSSPAY_API_BASE_URL`·`TOSSPAY_API_KEY`·`TOSSPAY_MERCHANT_ID`·`TOSSPAY_ENVIRONMENT` | 토스페이 정산 수집. 키는 해당 가맹점의 sk_test_/sk_live_, PortOne Secret과 별개 |
| `TOSSPAY_PORTONE_STORE_ID`·`TOSSPAY_PORTONE_CHANNEL_KEY`·`TOSSPAY_IDENTITY_MAPPING_VERIFIED`(기본 false)·`TOSSPAY_SCHEDULED_ENABLED`(기본 true) | 대조 범위·키 대응 검증 여부·주기 수집 |
| `stove.settlement.tosspay.*` | `max-pages` 100, 연결/읽기 2초/10초, `poll-delay` 1시간, `lookback-days` 7 |

- 모의 PG 요율은 `MOCK_PG_FEE_RATE_BPS`(기본 300bps)·`MOCK_PG_DECISION`·`MOCK_PG_FEE_POLICY_ID`로 바꾸며 새 결제번호에만 적용된다. 같은 정책 ID에 다른 요율은 거절되므로 요율을 바꿀 때 정책 ID도 새 버전으로 지정한다.
- SIMULATED 수령: 검증 운영자가 모의 PG `POST /test-support/v1/settlement-runs`(제어 키·UUID requestId)로 회차를 만들고, ADMIN이 받은 `providerSettlementId`를 `POST /api/v1/settlements/pg-receipts`에 전달한다. 금액·수령 상태는 입력하지 않는다. 미도착 매출이 반영되면 `/{providerSettlementId}/reconcile`로 재대사한다.
- 토스페이 수집: ADMIN `POST /api/v1/settlements/pg-statements`에 `{"dateType":"DUE","baseDate":"yyyy-MM-dd"}`만 보낸다. 공급자 1년 범위 안의 과거 날짜도 다시 수집할 수 있다.
- DLT로 간 매출·환불은 원인 해소(환불은 원매출 복구) 뒤 원래 키·본문·이벤트 ID·종류를 유지해 재전달한다.
- 구 내장 PG 이관: 구 Payment 처리·내장 PG 쓰기를 중지한 뒤 `tools/migrations/mock-pg/migrate.py --source-container … --source-db stove_payment --target-container … --target-db mock_pg`(암호는 `SOURCE_MYSQL_PASSWORD`·`TARGET_MYSQL_PASSWORD`)를 실행한다. V11 `simulated_payment_charge`만 읽고 원래 키·결정을 보존하며 비용은 미산정으로 둔다(신규 명세 대상에서 제외). 같은 행은 건너뛰고 다른 값은 충돌로 중단한다. Payment를 처리 비활성으로 HTTP에 연결해 PENDING 복구를 대조한 뒤 재개하며, 구 내장 PG와 외부 PG에 동시에 쓰지 않는다.
