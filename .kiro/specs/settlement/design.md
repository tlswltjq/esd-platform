# 설계: Settlement

> 상태: 작성 중(결정 150·178). 이벤트의 뜻·키·순서·소비 그룹, 모듈 간 흐름과 실패 표, 모듈 간 불변식은 [플랫폼 design](../platform/design.md)이 정본이다. 이 문서는 Settlement가 그 계약을 **어떻게 지키는지**와 자기 상태·API·내부 규칙만 적는다. 경로 줄임: `S/…` = `apps/settlement/src/main/java/com/stove/settlement/…`, `V<n>` = `apps/settlement/src/main/resources/db/migration/V<n>__*.sql`. 줄 번호는 2026-11-09 `docs/spec-foundation` 기준. 지금 코드와 다른 곳은 [tasks.md](tasks.md)의 `MC-S*`·`SE-T*` 번호로 표시한다.

## 아키텍처 위치

- 포트 8089, 저장소 MySQL 스키마 `stove_settlement`만 읽고 쓴다([P1.1-2](../platform/requirements.md)).
- 계층: `core/{domain, port, service}` + `api/{controller, listener}` + `adapter` + `config`([P1.1](../platform/requirements.md), AGENTS.md 3절). core는 공급자 이름·상수를 쓰지 않는다([P1.1-4](../platform/requirements.md), T28, SE-T1).
- 동기 의존: Settlement → Catalog(`GET /internal/v1/products/{code}/owner`), Settlement → PG(승인·취소·정산 자료 조회). 다른 업무 모듈을 동기 호출하지 않고, Settlement를 동기 호출하는 업무 모듈도 없다([design "동기 의존"](../platform/design.md#동기-의존-http)).
- 비동기 의존: 소비 `OrderPaid`(Order), `RefundPaymentConfirmed`·`RefundLateCancellationConfirmed`(Payment). 발행(목표) `SettlementSaleRecorded`(→ Order), `SettlementRefundRecorded`(→ Payment) — 응답 쌍([design "정방향 응답 쌍"](../platform/design.md#정방향-응답-쌍-결정-158)).
- Gateway가 `/api/v1/settlements/**`를 라우팅한다. `/me/**`는 CREATOR, 나머지는 ADMIN. Settlement도 JWT 서명·issuer·만료·`aud=esd-api`·역할을 자체 검증한다(`S/config/SecurityConfig.java`).
- 정본 소유: 매출 배분·판매자 월 합계·이월·환불 조정·PG 비용(`settlement_pg_fee`)·정산 자료 대사([design "정본 소유"](../platform/design.md#정본-소유), [결정 165](../../../docs/decisions.md#pg-fee-owner)). 사본: 주문 사실(`OrderPaid`), 금융 확인(`RefundPaymentConfirmed.confirmedAt`), 상품 소유자(Catalog).

## 데이터 모델

| 테이블 (엔티티) | 업무 키·유일 제약 | 금액·상태 CHECK | 비고 |
|---|---|---|---|
| `settlement_sale` (`SettlementSale`) | `uk_sale_order_no`·`uk_sale_event_id`·`uk_sale_request_event_id`·`uk_sale_payment_result_event_id`·`uk_sale_payment_id`·`uk_sale_pg_transaction(provider, provider_transaction_id)`, FK `fk_sale_pg_transaction` | `ck_sale_allocation`(V8: `amount>0`·KRW·`amount = platform_fee_amount + seller_payout_amount`). 목표 추가: `settlement_month >= sale_month`(SE-T6) | 삽입 뒤 불변(`updatable=false`). 목표 칸: `payment_confirmed_at`(nullable, 결정 166 값 보존, MC-S4). V8 `legacy_*` 칸은 읽거나 쓰지 않는다 |
| `seller_monthly_settlement` (`SellerMonthlySettlement`) | `uk_seller_month(seller_subject, settlement_month)` | `ck_month_allocation`(V8), `ck_month_refund_allocation`(V10), `ck_month_closed`(V6: `OPEN`이면 `closed_at` NULL, `CLOSED`면 NOT NULL — 이 식이 `status`를 두 값으로 제한한다) | 목표: `CLOSED` 행의 합계·건수·`closed_at`·`status` 변경을 DB가 거절(트리거, MC-S1) |
| `settlement_refund` (`SettlementRefund`) + `settlement_refund_cancellation` | `uk_refund_id`·`uk_refund_order`·`uk_refund_event`, FK `fk_refund_sale(order_no, payment_id, provider, provider_transaction_id)`, 취소 PK `(provider, cancellation_id)` | `ck_refund_money`, `ck_refund_source`. 목표 추가: `month_reason IN ('CONFIRMED_MONTH','CLOSED_MONTH_ROLLOVER')`, `settlement_month >= refund_month`(SE-T6), `source IS NULL OR source IN (…)`(MC-S2) | 불변. `source`는 SP-5의 사본이라 새 행은 NULL, 옛 행 호환(MC-S2, T19). 목표 칸 `confirmation_event_type`(어느 금융 확인 사실로 반영했는지, SE-T4) |
| (목표) `settlement_refund_awaiting_sale` (`AwaitingSaleRefund`, 가칭) + `settlement_refund_awaiting_cancellation` | PK `order_no`(주문당 하나), `uk_awaiting_refund_id(refund_id)`, `uk_awaiting_event(event_id)`, 취소 PK `(order_no, cancellation_id)` | `status IN ('AWAITING_SALE','APPLIED','CONFLICTED')`; `AWAITING_SALE` ⇔ `applied_at`·`refund_record_id`·`conflict_code` 모두 NULL; `APPLIED` ⇔ `applied_at`·`refund_record_id` NOT NULL, `conflict_code` NULL; `CONFLICTED` ⇔ `conflict_code IN ('SALE_MISMATCH','PG_REFUND_MISMATCH')`·`conflicted_at` NOT NULL. `amount>0`·KRW | 받은 확인의 칸 전부(`event_id`·`event_type`·`confirmed_at`·`refund_id`·`attempt_id`·`request_event_id`·`payment_id`·`buyer_subject`·`amount`·`currency`·`provider_transaction_id`)와 `received_at`. FK `refund_record_id → settlement_refund(id)`(MC-S3) |
| `settlement_seller_lock` (`SellerLock`) | PK `seller_subject` | — | V14. 업무 값 없음. 첫 반영·마감 때 삽입 |
| `settlement_seller_carryover` (`SellerCarryover`) | `uk_seller_carryover_month(seller_subject, settlement_month)`, `uk_seller_carryover_source(seller_subject, source_month)`(V15) | `ck_seller_carryover_amount`·`ck_seller_carryover_source`(V13) | 마감 트랜잭션에서 한 번 삽입, 불변 |
| `settlement_pg_transaction` (`PgTransactionRecord`) | `uk_pg_transaction_payment`·`_order`·`_identity`·`_link` | `ck_pg_transaction_money`, `ck_pg_transaction_funding`(V9: 전부 없음 또는 전부 검증) | 불변. 목표: V9 CHECK의 공급자 상수(`provider='PORTONE_TEST'`·`provider_channel_type='TEST'`·`provider_pg_provider='TOSSPAY_V2'`)를 중립 환경 칸 기준으로 바꾸되 옛 행 호환(SE-T1, M-SE-3) |
| `settlement_pg_fee` (`PgFeeRecord`) | `uk_pg_fee_payment`·`uk_pg_fee_transaction`, FK `fk_pg_fee_transaction` | `ck_pg_fee_money` | 행 없음 = 미확정, 있음 = 확인(0원 포함). PG 비용의 정본(결정 165) |
| `settlement_pg_statement` (`PgStatement`) + `_page` + `_entry` (`PgStatementEntry`) + `_item` (`PgStatementItem`) | 헤더 `uk_pg_statement_fingerprint`, 페이지 PK `(statement_id, page_position)`, 정본 거래 `uk_pg_statement_entry(merchant_id, environment, transaction_id)`, 항목 `uk_pg_statement_item_position`·`_entry` | `ck_pg_statement_scope`·`_totals`, `ck_pg_entry_scope`·`_amount`·`_fee`. 목표 추가: 헤더 `status` CHECK, 항목 `check_code` CHECK(SE-T5) | 목표: 헤더의 공급자 이름 칸 `portone_store_id`·`portone_channel_key`를 중립 이름(`provider_store_id`·`provider_channel_key`)으로 읽는다. 열 이름은 이행 파일에서 바꾸거나 매핑만 바꾼다(SE-T1) |
| `settlement_pg_cost_posting` (`PgCostPosting`) | `uk_pg_cost_source(source_entry_id)`, FK 원매출·환불·정본 거래·검증 수집본 | 목표 추가: `check_code IN ('MATCHED_REPORTED','AMBIGUOUS_PAYMENT','EXCESS_REFUND')`(SE-T5) | 정본 거래 하나에 연결 하나 |
| `pg_receipt` / `pg_receipt_item` (`PgReceipt`) | `uk_pg_receipt`, 항목 `uk_pg_receipt_payment`·`_transaction`·`_position` | `ck_receipt_money`, `ck_receipt_state` | 목표: 옛 기록 읽기 전용(R5, 결정 162) |
| `processed_event` (공통) | `uk_processed_event(event_id, consumer_group)` | — | Inbox. 대소문자 구분(V6) |
| `outbox_event` (공통) | `uk_outbox_event_id` | 공통 | V1에 있으나 지금 릴레이 비활성(`application.yml` `stove.outbox.relay-enabled: false`). 목표: 켬(R12, SE-T3) |
| (목표) `outbox_republish` (공통) | 공통 | 공통 | 결정 161 재발행 기록(SE-T3) |
| (목표) 실패 보관 표 (공통) | 공통 | 공통 | 결정 160, 소비 그룹 2개의 정지 키·실패 레코드(SE-T3) |

금액 정책:

```text
paidAmount         = OrderPaid.amount
platformFeeAmount  = paidAmount × platformFeeRate (원 단위 HALF_UP)
sellerPayoutAmount = paidAmount − platformFeeAmount
pgFeeAmount        = settlement_pg_fee 또는 MATCHED_REPORTED 비용 연결의 값 (없으면 NULL)
platformNetAmount  = platformFeeAmount − pgFeeAmount          (음수 허용, 비용 NULL이면 NULL)

전액 환불 조정(FULL_REFUND_REVERSE_ORIGINAL_V1) = 원매출 (amount, platformFeeAmount, sellerPayoutAmount)의 음수
판매자 월 순액 net = Σ sale.sellerPayoutAmount − Σ refund.sellerPayoutAmount  (반영 월 기준)
마감(결정 121·130): carriedIn = 직전 CLOSED 월의 carriedOut(없으면 0), total = net + carriedIn,
                   payable = max(0, total), carriedOut = min(0, total)
```

| 정책 ID | 값 |
|---|---|
| `PLATFORM_MVP_V1` | 1,000bps = 10%, 원 단위 HALF_UP, 고정 |
| 외부 부담 혜택 정책(지금 ID `TOSSPAY_EXTERNAL_BENEFIT_V1`) | 공급자 근거로 외부 부담이 확인된 혜택만 주문 금액 기준 배분(결정 40). ID의 공급자 이름은 저장 값이라 유지하고 core 비교는 adapter가 준 중립 판정으로 바꾼다(SE-T1) |
| `FULL_REFUND_REVERSE_ORIGINAL_V1` | 원배분 음수 상계(결정 41) |
| `REFUND_CONFIRMED_MONTH_V1` | 확인 월 `OPEN`이고 뒤 월 `CLOSED` 없음 → 확인 월, 아니면 현재 `OPEN` 월(결정 42·130) |
| `LINKED_BUSINESS_MONTH_V1` | PG 비용은 원매출·환불 조정의 반영 월에 연결(결정 45) |

## 상태 모델

### 트리거 목록

상태 × 트리거 표의 열 이름이다. 실패 보관 표의 ADMIN 재투입(결정 160)은 같은 리스너 경로를 다시 타므로 해당 이벤트 트리거와 같은 행을 따른다. 결과 칸: **허용 → 다음 상태** / **무변화**(멱등, 이 엔티티를 쓰지 않음 포함) / **거절**(오류 코드 · 재시도 가능 또는 영구 실패). Kafka 경로의 "재시도 가능"은 공통 재시도 뒤 실패 보관·키 정지, "영구 실패"는 재시도 없이 실패 보관·키 정지·경보다([P2.6](../platform/requirements.md)).

| ID | 트리거 | 종류 | 진입점 |
|---|---|---|---|
| E1 | `OrderPaid` 수신 | 소비 이벤트 | `OrderEventListener` → `SettlementOrderMapper.from` → `RecordPaidSale.execute` |
| E2 | 금융 확인 사실 수신(`RefundPaymentConfirmed`, 목표 `RefundLateCancellationConfirmed`) | 소비 이벤트 | `RefundEventListener` → `SettlementRefundMapper.from` → `RecordConfirmedRefund.execute` |
| E3 | 구독 토픽의 그 밖의 `eventType` | 소비 이벤트 | 리스너가 건너뜀 + (그룹, 타입) 지표(P2.3-7, SE-T10) |
| H1 | `POST /api/v1/settlements/sellers/{sellerSubject}/months/{month}/close` | HTTP(ADMIN) | `CloseSellerMonth.execute` |
| H2 | `POST /api/v1/settlements/pg-transactions/{paymentId}/refresh` | HTTP(ADMIN) | `SynchronizePgTransaction.execute` |
| H3 | `POST /api/v1/settlements/pg-statements` | HTTP(ADMIN) | `ReceivePgStatement.execute` |
| H4 | `POST /api/v1/settlements/pg-statements/{id}/reconcile` | HTTP(ADMIN) | `ReconcilePgStatement.execute` |
| J1 | 정산 자료 예약 수집 | 예약 작업 | `SynchronizePgStatements`(`S/config/PgStatementConfiguration.java:37`) |
| J2 | 정산 자료 예약 재대사 | 예약 작업 | `ReconcilePendingPgStatements.run` |
| J3 | 대기 환불 게이지 수집 | 예약 작업(지표) | (목표) 대기 환불 게이지 |
| O1 | 실패 보관 표 재투입 | 운영자 | 공통(결정 160) → E1·E2와 같은 행 |
| O2 | Outbox 재발행 | 운영자 | 공통(결정 161). Outbox 행만 `SENT → PENDING`, 업무 상태 무변화 |
| O3 | 매출 대기 환불 운영자 처리 | 운영자 | `[결정 필요 M-SE-1]` |
| Q | 모든 `GET` 조회 | HTTP | 상태를 바꾸지 않고 행을 만들지 않는다(R8.6) |

### 원매출 (`SettlementSale`)

| 상태 | 뜻 |
|---|---|
| (없음) | 이 주문의 매출을 아직 기록하지 않았다 |
| `RECORDED` | 기록했다. 행이 있는 것이 곧 이 상태이고, 삽입 뒤 어느 칸도 바뀌지 않는다(상태 칸이 필요 없는 불변 기록) |

반영 사유 칸은 두지 않는다. `settlementMonth = saleMonth`면 귀속 월 반영, `settlementMonth > saleMonth`면 마감 때문에 현재 월로 넘긴 것이다(같은 사실을 두 칸에 쓰지 않음, 원칙 ③).

| 상태 | 트리거 | 가드 | 결과 | 부수 효과 | 근거 |
|---|---|---|---|---|---|
| (없음) | E1 | 트랜잭션 밖 PG 승인·Catalog 소유자 조회 실패 | 거절 · 재시도 가능(`UPSTREAM_UNAVAILABLE`) | PG·Catalog `GET`만 | R1.3·R1.6, P3.15-4 |
| (없음) | E1 | PG 승인이 주문과 다름(결제 키·금액·통화·상태·결제 구성) 또는 소유자 응답 형식 오류 | 거절 · 영구 실패(`CONFLICT`) | 없음 | R1.6 |
| (없음) | E1 | 판매자 잠금 뒤 귀속 월(R3.1)이 `OPEN`(없으면 생성)이고 뒤 월 `CLOSED` 없음 | 허용 → `RECORDED`, `settlementMonth = saleMonth` | Inbox, PG 승인 근거(없을 때), 보고된 비용이면 `settlement_pg_fee`, 월 `add`, `SettlementSaleRecorded` Outbox, 같은 주문의 대기 환불 처리(아래 "매출 대기 환불") | R1.2·R3.5·R12.1 |
| (없음) | E1 | 귀속 월이 `CLOSED`이거나 뒤 월이 `CLOSED`(귀속 월 행은 만들지 않음) | 허용 → `RECORDED`, `settlementMonth` = 현재 KST 월 | 위와 같음, 현재 월 행에 `add` | R3.5, 결정 42·130 |
| (없음) | E1 | 현재 월 행도 `CLOSED`이거나 3번 고르는 동안 월이 바뀜 | 거절 · 재시도 가능(`CONFLICT`) | 없음(롤백) | R3.5 |
| (없음) | E1 | 커밋 중 유일 제약 충돌·일시 오류 | 같은 호출에서 최대 5회 다시 시도. 다음 시도가 `RECORDED`를 보면 아래 행, 출처 재사용이면 영구 실패 | 없음(롤백) | R9.1 |
| `RECORDED` | E1 | 같은 주문 원장의 주문 사실 값(+ `paymentConfirmedAt`)이 같고 Inbox에 있음 | 무변화 | 외부 조회 없음, Outbox 없음 | R1.4 |
| `RECORDED` | E1 | 값이 다르거나, 원장은 있는데 그 `eventId`가 Inbox에 없음 | 거절 · 영구 실패(`CONFLICT`) | 없음 | R1.5, P2.3-4 |
| (없음)·`RECORDED` | E2 | — | 무변화(환불은 아래 두 엔티티가 처리) | — | — |
| (없음)·`RECORDED` | E3·H1~H4·J1~J3·O2·O3·Q | — | 무변화(H1은 읽기만) | — | — |

### 환불 조정 (`SettlementRefund`)

| 상태 | 뜻 |
|---|---|
| (없음) | 이 주문의 전액 환불을 아직 반영하지 않았다 |
| `RECORDED` | 원배분을 음수 상계해 반영했다. 불변. 원매출당 최대 1건(`uk_refund_order`) |

직교 칸: `monthReason` ∈ {`CONFIRMED_MONTH`, `CLOSED_MONTH_ROLLOVER`}(반영 월 선택 사유, 목표 CHECK SE-T5). `source`는 옛 행만 값이 있다(MC-S2).

| 상태 | 트리거 | 가드 | 결과 | 부수 효과 | 근거 |
|---|---|---|---|---|---|
| (없음) | E2 | 원매출 없음 | 무변화(대기 환불로 저장) | 아래 "매출 대기 환불" | R6.7·R11.1 |
| (없음) | E2 | 원매출과 확인 값이 다름(`ConfirmedRefund.requireSale`) | 거절 · 영구 실패(`CONFLICT`) | 없음 | R6.4, I2 |
| (없음) | E2 | 트랜잭션 밖 `PgRefunds` 조회 실패 | 거절 · 재시도 가능(`UPSTREAM_UNAVAILABLE`) | PG `GET`만 | R6.4 |
| (없음) | E2 | PG 근거가 전액 취소가 아니거나 원승인·결제 구성·취소 ID 목록이 다름 | 거절 · 영구 실패(`CONFLICT`) | PG `GET`만 | R6.4·R6.9 |
| (없음) | E2 | 원매출 잠금 → 판매자 잠금, 확인 월 `OPEN`이고 뒤 월 `CLOSED` 없음 | 허용 → `RECORDED`, `CONFIRMED_MONTH`, `settlementMonth = refundMonth` | 배분, 취소 근거 행, Inbox, 월 `addRefund`, `SettlementRefundRecorded` Outbox | R6.3·R6.5·R12.2, 결정 41·42 |
| (없음) | E2 | 확인 월 `CLOSED` 또는 뒤 월 `CLOSED`(확인 월 행은 만들지 않음) | 허용 → `RECORDED`, `CLOSED_MONTH_ROLLOVER`, `settlementMonth` = 현재 KST 월 | 위와 같음, 현재 월 행에 `addRefund` | R6.5, 결정 42·130 |
| (없음) | E2 | 현재 월도 `CLOSED`, 3번 고르는 동안 월이 바뀜 | 거절 · 재시도 가능(`CONFLICT`) | 없음 | R6.6 |
| (없음) | E2 | 커밋 중 유일 제약 충돌: 취소 PK가 다른 주문에 이미 쓰임 | 거절 · 영구 실패(`CONFLICT`) | 없음 | R9.3 |
| (없음) | E2 | 커밋 중 그 밖의 유일 제약 충돌·일시 오류 | 최대 5회 다시 시도 → 다음 시도에서 위 행 중 하나 | 없음 | R9.3 |
| (없음) | E1 | 같은 주문의 대기 환불 `AWAITING_SALE`이 대조와 PG 근거를 통과 | 허용 → `RECORDED`(사유는 R6.5 규칙) | 대기 환불 `APPLIED`, 월 `addRefund`, `SettlementRefundRecorded` Outbox — 매출 기록과 같은 트랜잭션 | R11.3, 결정 156 |
| `RECORDED` | E2 | 같은 `eventId`(Inbox 있음) 또는 같은 `refundId`·주문·확인 시각·취소 근거 | 무변화(최초 월·정책·금액, 외부 조회 없음) | 없음 | R6.8 |
| `RECORDED` | E2 | 다른 `refundId`·다른 본문으로 같은 주문 | 거절 · 영구 실패(`CONFLICT`) | 없음 | R6.8, P2.3-4 |
| `RECORDED` | E2 | 다른 금융 확인 사실이 같은 `refundId`·결제·금액·취소 ID 집합을 가리킴(재신청 시도의 확인과 실패 시도의 늦은 취소 확인) | `[결정 필요 M-SE-2]`(1회차 지적 A2·B2, 판정 전) | — | R6.8 |
| (없음)·`RECORDED` | E3·H1~H4·J1~J3·O2·O3·Q | — | 무변화(H1·J2·H4는 읽기만) | — | — |

### 매출 대기 환불 (`AwaitingSaleRefund`, 가칭 — 목표, MC-S3)

| 상태 | 뜻 |
|---|---|
| (없음) | 기다리는 환불이 없다 |
| `AWAITING_SALE` | 받을 때 검증을 마친 금융 확인을 보관했고 원매출을 기다린다 |
| `APPLIED` | 원매출 기록 트랜잭션에서 대조를 통과해 환불 조정(`refund_record_id`)으로 반영했다. 끝 상태 |
| `CONFLICTED` | 원매출 또는 PG 취소 근거와 맞지 않아 반영하지 않았다(`conflict_code`). 경보. 처리 경로는 `[결정 필요 M-SE-1]` |

받을 때와 반영할 때의 검증 경계([결정 156](../../../docs/decisions.md#refund-awaiting-sale)이 모듈 스펙에 맡김):

- **받을 때(E2, 원매출 없음)**: `SettlementRefundMapper.from`의 봉투·본문·키 검증과 `ConfirmedRefund` 생성 검증(식별자 형식, `amount > 0`, KRW, 취소 ID 목록 비어 있지 않고 중복 없음). PG를 조회하지 않는다 — 원매출이 없으면 PG 승인 근거 행도 없어 조회할 결제 근거가 없다.
- **반영할 때(E1)**: `RecordPaidSale`이 트랜잭션 밖에서 PG 승인·소유자를 조회할 때, 같은 주문의 `AWAITING_SALE`이 있으면 `PgRefunds`도 함께 조회한다. 트랜잭션 안에서 대기 환불을 잠그고 원매출과 대조(`ConfirmedRefund.requireSale`과 같은 칸)·PG 근거 대조(`PgRefund.requireOriginal`·`requireConfirmation`)를 한다. PG 조회가 실패하면 매출 기록도 재시도 가능 실패로 끝난다(둘이 한 트랜잭션이므로).
- **직렬화(R11.5)**: 대기 환불 저장 트랜잭션은 저장 뒤 같은 `orderNo`의 원매출을 잠금 읽기(`SettlementSaleRepository.lockByOrderNo`)로 다시 확인하고, 매출 기록 트랜잭션은 원매출 삽입 뒤 같은 `orderNo`의 대기 환불을 잠금 읽기로 확인한다. InnoDB 잠금 읽기는 상대가 삽입하고 아직 커밋하지 않은 행에서 기다리므로 둘 중 하나는 상대 행을 본다. 교착은 두 서비스의 기존 5회 재시도(`TransientDataAccessException`)가 새 트랜잭션에서 다시 판정한다. 원매출이 그 사이 생겼으면 대기 환불 저장 쪽은 롤백하고 원매출 있는 경로(위 "환불 조정")로 다시 처리한다.

| 상태 | 트리거 | 가드 | 결과 | 부수 효과 | 근거 |
|---|---|---|---|---|---|
| (없음) | E2 | 원매출 없음, 받을 때 검증 통과 | 허용 → `AWAITING_SALE` | Inbox(받은 `eventType`), 확인 칸·취소 ID 저장, `received_at` | R11.1 |
| (없음) | E2 | 원매출 없음, 받을 때 검증 실패 | 거절 · 영구 실패(`INVALID_REQUEST`) | 없음 | R11.1, P2.6-3 |
| (없음) | E2 | 원매출 있음 | 무변화(위 "환불 조정"이 처리) | — | R6.4 |
| (없음) | E1 | 같은 주문의 대기 환불 없음 | 무변화 | — | — |
| `AWAITING_SALE` | E2 | 같은 `eventId`(Inbox 있음) | 무변화 | 없음 | R11.2 |
| `AWAITING_SALE` | E2 | 다른 `eventId`, 같은 주문, 다른 `refundId`나 금액·취소 ID | 거절 · 영구 실패(`CONFLICT`) | 없음 | R11.2 |
| `AWAITING_SALE` | E2 | 다른 금융 확인 사실이 같은 `refundId`·금액·취소 ID 집합 | `[결정 필요 M-SE-2]` | — | R6.8 |
| `AWAITING_SALE` | E1 | 매출 기록 성공, 원매출 대조와 PG 취소 근거 통과 | 허용 → `APPLIED` | 환불 조정 `RECORDED`(같은 트랜잭션), `applied_at`·`refund_record_id`, `SettlementRefundRecorded` Outbox | R11.3 |
| `AWAITING_SALE` | E1 | 매출 기록 성공, 원매출과 다름 | 허용 → `CONFLICTED`(`SALE_MISMATCH`) | 매출만 기록, 경보 지표 | R11.4 |
| `AWAITING_SALE` | E1 | 매출 기록 성공, PG 취소 근거가 전액 취소 아님·취소 ID 다름 | 허용 → `CONFLICTED`(`PG_REFUND_MISMATCH`) | 매출만 기록, 경보 지표 | R11.4 |
| `AWAITING_SALE` | E1 | PG 승인·소유자·취소 조회 실패, 매출 대조 실패, 월 선택 실패 | 무변화(매출 트랜잭션과 함께 롤백, E1은 재시도 가능 또는 영구 실패) | 없음 | R11.3 |
| `AWAITING_SALE` | J3 | — | 무변화 | 건수·최장 대기 게이지에 셈 | R11.6 |
| `AWAITING_SALE` | O3 | — | `[결정 필요 M-SE-1]`(원매출이 끝내 오지 않는 경우 포함, 경보 기준은 결정 159) | — | R11.4·R11.6 |
| `APPLIED` | E1·E2 | 재전달 | 무변화(원매출·환불 조정의 멱등이 처리) | 없음 | R1.4·R6.8 |
| `CONFLICTED` | E1 | 재전달 | 무변화 | 없음 | R1.4 |
| `CONFLICTED` | E2 | 같은 `eventId` / 그 밖 | 무변화 / 거절 · 영구 실패(`CONFLICT`) | 없음 | R11.2 |
| `CONFLICTED` | J3 | — | 무변화 | `CONFLICTED` 게이지(1건부터 경보) | R11.6 |
| `CONFLICTED` | O3 | — | `[결정 필요 M-SE-1]` | — | R11.4 |
| `APPLIED` | J3·O3 | — | 무변화 / 거절(`CONFLICT`, 끝난 건) | — | — |
| 모두 | E3·H1~H4·J1·J2·O2·Q | — | 무변화(H1은 대기 환불을 읽지 않는다, R11.7) | — | R11.7 |

### 판매자 월 (`SellerMonthlySettlement`)

| 상태 | 뜻 |
|---|---|
| (없음) | 아직 반영도 마감도 없는 월. 같은 판매자의 뒤 월이 `CLOSED`면 행이 없어도 마감된 것으로 본다(행은 만들지 않는다) |
| `OPEN` | 매출·환불 합계를 더하는 중. `closed_at` NULL |
| `CLOSED` | 마감. 합계·건수·`closed_at`·이월이 확정되고 바뀌지 않는다. `CLOSED → OPEN` 없음 |

| 상태 | 트리거 | 가드 | 결과 | 부수 효과 | 근거 |
|---|---|---|---|---|---|
| (없음) | E1·E2(반영) | 이 월이 반영 월로 골라짐(뒤 월 `CLOSED` 없음) | 허용 → `OPEN` | 합계 0 행 삽입 뒤 가산 | R3.6 |
| (없음) | E1·E2(반영) | 뒤 월 `CLOSED` | 무변화(현재 월에 반영) | 없음 | R3.5 |
| `OPEN` | E1(매출 반영) | — | 허용 → `OPEN` | `paidAmount`·`platformFeeAmount`·`sellerPayoutAmount`·`recordCount` 가산, `updatedAt` | R1.2 |
| `OPEN` | E1·E2(환불 반영, 대기 환불 반영 포함) | — | 허용 → `OPEN` | `refundAmount`·`refundedPlatformFeeAmount`·`refundedSellerPayoutAmount`·`refundCount` 가산 | R6.4·R11.3 |
| `CLOSED` | E1·E2(반영) | 서비스가 이미 현재 월로 넘김 | 무변화. 도메인 `add`·`addRefund`를 직접 부르면 거절(`CONFLICT`) | 없음 | R6.11, I6 |
| 모두 | H1 | `month` ≥ 현재 KST 월(Settlement 시계) | 거절(`CONFLICT` 409) | 없음 | R3.3 |
| `CLOSED` | H1 | 판매자·월 잠금 뒤 | 무변화(최초 금액·`closedAt`·이월 반환, 원장 행 추가 없음) | 없음 | R3.4 |
| (없음)·`OPEN` | H1 | 같은 판매자의 뒤 월이 `CLOSED` | 거절(`CONFLICT` "이후 월이 이미 마감됐습니다") | 행 만들지 않음 | R3.8 |
| (없음)·`OPEN` | H1 | 같은 판매자의 앞 월에 `OPEN` 행 | 거절(`CONFLICT` "이전 월을 먼저 마감해야 합니다") | 없음 | R3.8 |
| (없음)·`OPEN` | H1 | 매출·환불 원장의 합계·건수가 월 행과 다름 | 거절(`CONFLICT`) | 없음(롤백) | R3.2·R6.11, I5 |
| (없음)·`OPEN` | H1 | 위 가드 모두 통과 | 허용 → `CLOSED`, `closedAt = updatedAt = now`. 행이 없으면 합계 0 행을 만들고 바로 마감 | 같은 트랜잭션에서 이월 원장 한 행 | R3.9·R3.10·R3.11 |
| (없음)·`OPEN` | H1 | 유일 제약 충돌·일시 오류 | 최대 5회 다시 시도, 소진하면 거절(`CONFLICT`) | 없음(롤백) | R3.10, SE-T7 |
| `CLOSED` | DB 직접 갱신(운영자 SQL 등) | 합계·건수·`closed_at`·`status` 변경 | 거절(DB 트리거, 목표) | 없음 | R9.9, MC-S1 |
| 모두 | H2~H4·J1~J3·O2·O3·E3 | — | 무변화(PG 비용·정산 자료와 독립, 결정 39) | — | R3.7 |
| 모두 | Q | — | 무변화(조회가 행을 만들지 않음) | — | R8.6 |

직교 행:

| 행 | 값 | 뜻·변화 | 코드 위치 |
|---|---|---|---|
| `settlement_seller_lock` | 없음 / 있음 | 판매자를 처음 반영·마감할 때 삽입, 바뀌지 않음. 동시 삽입 충돌은 각 서비스의 5회 재시도가 새 트랜잭션에서 다시 잠근다 | `SellerLock.of`, `RecordPaidSale.lockSeller`(`S/core/service/RecordPaidSale.java:105`), `RecordConfirmedRefund.lockSeller`, `CloseSellerMonth.close`(`:127`) |
| `settlement_seller_carryover` | 없음 / 있음(불변) | 마감 때 한 번 삽입. `sourceMonth` = 앞의 마지막 `CLOSED` 월(없으면 NULL, `carriedIn = 0`) | `CloseSellerMonth.carry`(`:145`), `SellerMonthBalance.settle`·`of` |
| 월 응답 `carriedIn`·`payable`·`carriedOut` | `OPEN`이면 `null` / `CLOSED`·원장 있음이면 원장 값 / `CLOSED`·원장 없음(V13 이전)이면 `carriedIn = 0`과 순액으로 계산 | 조회 때 계산, 저장 값 불변 | `SellerMonthBalance.of` |

잠금 순서: 환불은 원매출 행(`lockByOrderNo`) → 판매자 잠금 → 월 행. 매출은 판매자 잠금 → 월 행 → (원매출 삽입 뒤) 대기 환불. 마감은 판매자 잠금 → 월 행. 마감은 원매출·대기 환불을 잠그지 않아 순환 대기가 없다. 대기 환불 저장은 원매출 잠금 읽기만 하고 판매자 잠금을 잡지 않는다(판매자 미정).

### PG 승인 근거·비용 (`PgTransactionRecord`·`PgFeeRecord`)

`PgEvidence`·`PgPayment`·`PgFunding`은 값 객체다. 목표: 공급자 이름 대신 adapter가 준 중립 값(공급자 식별 문자열은 저장만, 환경 `TEST`·`LIVE`, 결제 구성 검증 여부)으로 검사한다(SE-T1).

| 행 | 상태 | 뜻 |
|---|---|---|
| `settlement_pg_transaction` | 없음 / 있음(불변) | 매출 기록 때 접수한 PG 승인 근거 |
| `settlement_pg_fee` | 없음 = 미확정(`pgCostStatus=UNCONFIRMED`) / 있음 = 확인(`CONFIRMED`, 0원 포함) | 거래별 확인 비용(정본) |

| 상태 | 트리거 | 가드 | 결과 | 부수 효과 | 근거 |
|---|---|---|---|---|---|
| 거래 없음 | E1 | 매출 기록 트랜잭션 안 | 허용 → 거래 있음. 비용이 보고되면 비용 있음(`recordedBy = settlement-paid-order`) | — | R1.2·R4.5 |
| 거래 있음 | E1·H2 | 보고된 거래 근거나 결제 구성이 다름 | 거절(`CONFLICT`) | 없음 | R10.4 |
| 거래 없음 | H2 | — | 거절(`NOT_FOUND` 404) | 없음 | R10.1 |
| 비용 없음 | H2 | 트랜잭션 밖 PG 조회가 비용을 보고, 거래 행 잠금 | 허용 → 비용 있음(`recordedBy` = JWT `sub`) | 매출·월 합계 무변화 | R10.1·R10.3 |
| 비용 없음 | H2 | 비용 보고 없음 | 무변화 | 없음 | R10.2 |
| 비용 없음 | H2 | PG 조회 실패 | 거절(`UPSTREAM_UNAVAILABLE` 503) | 없음 | R10.1 |
| 비용 있음 | H2 | 같은 비용 / 다른 비용 | 무변화 / 거절(`CONFLICT`) | 없음 | R10.2 |
| 모두 | E2·E3·H1·H3·H4·J1~J3·O2·O3·Q | — | 무변화(E2·H4·J2는 읽기만) | — | — |

### PG 정산 자료 수집본 (`PgStatement`, `settlement_pg_statement.status`)

| 상태 | 뜻 |
|---|---|
| `EMPTY_TEST` / `EMPTY` | 시험 / 실거래 조회 결과가 비었다. 끝 상태(재대사는 `checked_at`만 바꾼다). 0원 비용·지급 완료가 아니다 |
| `RECEIVED` | 행이 있는 원본을 저장했고 아직 대사하지 않았다 |
| `PENDING` | 불일치는 없고 대기 항목이 있다 |
| `MISMATCH` | 불일치 항목이 있다 |
| `MATCHED_REPORTED` | 모든 항목이 일치. 은행 실입금·송금 완료가 아니다 |

시험 환경 수집본은 행을 가질 수 없다(`PgStatementReport`가 거절, `S/core/domain/PgStatementReport.java:24`). 그래서 시험 수집본은 늘 `EMPTY_TEST`이고 예약 재대사 대상이 아니다. 헤더 상태는 그 수집본을 마지막으로 대사한 결과이며, 비용 확정의 정본은 비용 연결(`PgCostPosting`)이다. 다른 수집본에서 생긴 `AMBIGUOUS_PAYMENT`·`EXCESS_REFUND`는 연결의 코드를 바꾸고, 이미 `MATCHED_REPORTED`인 옛 헤더는 다시 대사할 때까지 그대로다(R7.5).

| 상태 | 트리거 | 가드 | 결과 | 부수 효과 | 근거 |
|---|---|---|---|---|---|
| (없음) | H3·J1 | 미래 날짜, adapter 판정 `OUT_OF_RANGE` | 거절(`INVALID_REQUEST` 400) | 없음 | R7.16 |
| (없음) | H3·J1 | adapter 판정 `NOT_YET_PUBLISHED`(당일 자료 공개 전) | 거절(`CONFLICT` 409). J1은 다음 주기에 다시 시도 | 없음 | R7.16 |
| (없음) | H3·J1 | 수집 자격 미설정 | 거절(`UPSTREAM_UNAVAILABLE` 503) | 없음 | R7.17 |
| (없음) | H3·J1 | 수집 중 형식 오류·중간 실패·커서 순환·페이지 제한·합계 불일치 | 거절(`UPSTREAM_UNAVAILABLE` 또는 `CONFLICT`) | 부분 원본 저장 없음 | R7.9 |
| (없음) | H3·J1 | 같은 지문 없음, 행 0개 | 허용 → `EMPTY_TEST`/`EMPTY` | 페이지 원문 저장 | R7.7·R7.11 |
| (없음) | H3·J1 | 같은 지문 없음, 행 있음 | 허용 → `RECEIVED` → 커밋 뒤 같은 호출에서 재대사 | 페이지 원문, 정본 거래(없을 때), 항목 `UNCHECKED` | R7.1·R7.12 |
| (없음) | H3·J1 | 같은 가맹점·환경·거래 ID의 정본 거래 내용이 다름 | 거절(`CONFLICT` 409) | 없음(롤백) | R7.11 |
| 모두 | H3·J1 | 같은 지문이 이미 있음 | 무변화(최초 수집자·시각 유지) → 재대사 | 없음 | R7.11 |
| `RECEIVED`·`PENDING`·`MISMATCH`·`MATCHED_REPORTED` | H4·수집 직후 재대사 | 항목 중 불일치 코드 있음 | 허용 → `MISMATCH` | 헤더·정본 거래 잠금, 항목 `check_code`·`payment_id`·`checked_at`, 비용 연결 생성·갱신 | R7.2·R7.5 |
| 같음 | 같음 | 불일치 없고 대기 코드 있음 | 허용 → `PENDING` | 같음 | R7.13·R7.14 |
| 같음 | 같음 | 모두 일치 | 허용 → `MATCHED_REPORTED` | 같음 | R7.3·R7.4 |
| `RECEIVED`·`PENDING` | J2 | ID 커서로 100개씩, PG 재호출 없음 | 위 세 결과 중 하나. 실패는 이 수집본만 건너뛰고 다음 순회에 다시 | 같음 | R7.6 |
| `MISMATCH`·`MATCHED_REPORTED` | J2 | — | 무변화(선택하지 않음) | — | R7.6 |
| `EMPTY_TEST`·`EMPTY` | H4 | — | 무변화(`checked_at`만 갱신) | 없음 | R7.7 |
| `EMPTY_TEST`·`EMPTY` | J2 | — | 무변화(선택하지 않음) | — | R7.6 |
| 모두 | E1·E2·E3·H1·H2·J3·O2·O3·Q | — | 무변화 | — | — |

항목 확인 코드(`PgStatementItem.checkCode`, 처음 `UNCHECKED`, 재대사마다 `PgStatementCheck.compare` 결과로 덮어씀, 위에서 처음 맞는 값):

| 코드 | 조건 | 분류 |
|---|---|---|
| `TEST_NOT_SETTLEABLE` | 수집 범위가 시험 환경 | 대기 |
| `MISSING_SALE` | 정본 거래의 PG 주문 키에 해당하는 원매출·승인 근거 없음 | 대기 |
| `SOURCE_ENVIRONMENT_PENDING` | 승인 근거가 실거래 환경이 아님 | 대기 |
| `IDENTITY_UNVERIFIED` | 거래 키 대응 미검증 | 대기 |
| `DIFFERENT_MERCHANT` · `DIFFERENT_ORDER` · `DIFFERENT_TRANSACTION` | 상점·채널 / 주문 / PG 거래 키가 다름 | 불일치 |
| `AMBIGUOUS_PAYMENT` | 같은 결제 묶음에 승인 2건 이상 | 불일치 |
| `MATCHED_REPORTED` · `DIFFERENT_AMOUNT` | 승인 행: 금액 같음 / 다름 | 일치 / 불일치 |
| `MISSING_REFUND` | 환불 행인데 환불 조정 없음 | 대기 |
| `DIFFERENT_TRANSACTION` | 환불 행의 PG 거래 키가 환불 조정 거래와 다름 | 불일치 |
| `EXCESS_REFUND` | 관측 환불 합계 > 내부 전액 환불 | 불일치 |
| `MATCHED_REPORTED` · `REFUND_AMOUNT_PENDING` | 관측 환불 합계 = / < 내부 전액 환불 | 일치 / 대기 |

### 비용 연결 (`PgCostPosting`, `settlement_pg_cost_posting.check_code`)

| 상태 | 트리거 | 가드 | 결과 | 부수 효과 | 근거 |
|---|---|---|---|---|---|
| (없음) | H4·J2·수집 직후 재대사 | 항목 `MATCHED_REPORTED`, 정본 거래·승인 근거 모두 실거래, 거래 일치 | 허용 → `MATCHED_REPORTED` | `business_month` = 승인이면 `SettlementSale.settlementMonth`, 환불이면 `SettlementRefund.settlementMonth`, 정책 `LINKED_BUSINESS_MONTH_V1` | R7.3·R7.4, 결정 45 |
| (없음) | 같음 | 항목이 일치가 아님 | 무변화 | 없음 | R7.15 |
| `MATCHED_REPORTED`·`AMBIGUOUS_PAYMENT`·`EXCESS_REFUND` | 같음 | 새 항목 코드가 이 셋 중 하나 | 허용 → 그 코드(일치면 기존 연결과 같은지 확인, 다르면 거절 `CONFLICT`) | 확정 집계에서 빠지거나 다시 들어감 | R7.5 |
| 모두 | 같음 | 새 항목 코드가 그 밖(`IDENTITY_UNVERIFIED`·`DIFFERENT_*` 등) | 무변화 | 없음 | R7.15 |
| 모두 | E1~E3·H1~H3·J1·J3·O2·O3·Q | — | 무변화 | — | — |

월 금융 요약(`PgCostPostingRepository.summarize`)과 지급일 집계(`PgStatementEntryRepository.payoutTotals`)는 `check_code = 'MATCHED_REPORTED'`만 확정으로 센다. 지금 실거래 승인 근거를 받을 수 없어 `MATCHED_REPORTED` 연결은 생기지 않는다(`[결정 필요 M-SE-3]`, G7).

### 옛 지급 명세 (`PgReceipt`, 목표 읽기 전용)

| 상태 | 뜻 |
|---|---|
| `REPORTED_PAID` · `MISMATCH` · `SIMULATED_RECEIVED` | 시뮬레이터 시절 저장된 대사 결과. 목표에서 더 바뀌지 않는다(V7 `ck_receipt_state`는 유지) |

| 상태 | 트리거 | 결과 | 근거 |
|---|---|---|---|
| (없음)·모든 상태 | 지금의 접수 `POST /pg-receipts`·재대사 `POST /pg-receipts/{id}/reconcile` | 목표: 경로 없음(제거, 결정 162). 지금 코드와의 차이는 SE-T2 | R5.1 |
| 모든 상태 | Q(`GET /pg-receipts`·`/{providerSettlementId}`) | 무변화 | R5.2 |
| 모든 상태 | E1~E3·H1~H4·J1~J3·O1~O3 | 무변화(매출 접수·마감·비용 확정에 쓰지 않음) | R5.3 |

## API 계약

공통: 목록 `page=0`·`size=20`(page ≥ 0, 1 ≤ size ≤ 100). 익명 401, 역할 없음·CREATOR의 ADMIN 경로 403, 잘못된 필터·페이지·월 400(`INVALID_REQUEST`). 오류 코드는 `BusinessException`의 `INVALID_REQUEST`(400)·`NOT_FOUND`(404)·`CONFLICT`(409)·`UPSTREAM_UNAVAILABLE`(503). 쓰기 API는 대상 키(판매자·월, `paymentId`, 조회 기준+기준일 지문, 수집본 ID)로 멱등하며 별도 멱등 헤더를 두지 않는다.

| 메서드 | 경로 | 역할 | 요청 | 응답·오류 | 멱등 |
|---|---|---|---|---|---|
| GET | `/api/v1/settlements/me/sales` | CREATOR | `page`·`size`·`month` | 본인 매출 페이지 + `refund` 연결(R8.10) | — |
| GET | `/api/v1/settlements/me/months` | CREATOR | `page`·`size` | 본인 월 합계, `CLOSED`면 이월 3값(R8.11) | — |
| GET | `/api/v1/settlements/me/refunds` | CREATOR | `page`·`size` | 본인 환불 조정(음수 조정액·확인/반영 월·사유). 구매자·PG 실행 정보 없음 | — |
| GET | `/api/v1/settlements/sales` | ADMIN | `sellerSubject`·`month`·`page`·`size` | 매출 + `sellerSubject`·PG 비용·플랫폼 잔여액·정책(R8.5) | — |
| GET | `/api/v1/settlements/months` | ADMIN | 같음 | 월 금융 요약(R8.7·R8.8) | — |
| GET | `/api/v1/settlements/refunds` · `/{refundId}` | ADMIN | 필터 | 환불 목록·상세(취소별 근거). 없으면 404 | — |
| POST | `/api/v1/settlements/sellers/{sellerSubject}/months/{month}/close` | ADMIN | 본문 없음 | 월 응답. 409(현재·미래 월, 이전 월 `OPEN`, 이후 월 `CLOSED`, 합계 불일치, 재시도 소진) | 같은 판매자·월은 최초 결과(R3.4) |
| POST | `/api/v1/settlements/pg-transactions/{paymentId}/refresh` | ADMIN | 본문 없음 | 거래 응답. 404(거래 없음), 409(다른 근거·비용), 503(PG 조회 실패) | 같은 비용이면 무변화(R10) |
| POST | `/api/v1/settlements/pg-statements` | ADMIN | `{"dateType":"DUE"\|"SETTLE","baseDate":"yyyy-MM-dd"}` | 수집본 응답. 400(미래·범위 밖), 409(공개 전·정본 거래 충돌), 503(미설정·수집 실패) | 같은 지문이면 최초 수집본(R7.11) |
| GET | `/api/v1/settlements/pg-statements` · `/{id}` · `/{id}/items` · `/{id}/pages/{position}` | ADMIN | 필터·페이지 | 수집본·항목·원문 페이지. 키는 응답하지 않음 | — |
| POST | `/api/v1/settlements/pg-statements/{id}/reconcile` | ADMIN | 본문 없음 | 수집본 응답. 404 | 결과가 같으면 같은 상태 |
| GET | `/api/v1/settlements/pg-payouts` · `/items` | ADMIN | 월·필터 | 보고 지급일 기준 월별 거래액·수수료·VAT·순액, 정본 거래 페이지(R8.9) | — |
| GET | `/api/v1/settlements/pg-receipts` · `/{providerSettlementId}` | ADMIN | 필터 | 옛 지급 명세(읽기 전용, R5.2). 없으면 404 | — |
| ~~POST~~ | ~~`/api/v1/settlements/pg-receipts`~~ · ~~`/{providerSettlementId}/reconcile`~~ | — | — | 목표: 제거(R5.1, SE-T2) | — |
| GET | (목표, 가칭) `/api/v1/settlements/awaiting-refunds` | ADMIN | `status`·`page`·`size` | 매출 대기 환불 목록(상태·사유·받은 시각, 읽기만). 운영자 처리 API는 `[결정 필요 M-SE-1]` | — |

월·매출 응답 금액 필드는 지금 계약 그대로다: `pgCostStatus`(매출 `CONFIRMED`/`UNCONFIRMED`, 월은 `PARTIAL` 추가), `confirmedPgFeeAmount`·`salePgFeeAmount`·`pendingPgCostCount`, 환불 양수 합계 4개, `net*` 순액 3개(음수 허용), 이월 3값(`OPEN`이면 `null`), 환불 비용 반환·VAT·`confirmedNetPgCostAmount`, `pgFeeAmount`·`platformNetAmount`(미확인이 하나라도 있으면 NULL). 공개 명세는 `apps/settlement/src/integrationTest/resources/openapi/settlement.json`.

## 이벤트

공통: 봉투·Inbox·키·재시도·실패 보관·재발행은 [P2](../platform/requirements.md)를 따른다. 소비 레코드 하나는 리스너 스레드 안에서 끝내고 커밋 뒤 오프셋을 커밋한다.

### 소비

| 이벤트 (플랫폼 행) | 소비 그룹 · 리스너 | `*Mapper.from` 검증 | 서비스 · 업무 멱등 키 | 실패 처리 |
|---|---|---|---|---|
| `OrderPaid` ([#17](../platform/design.md#eventtype-상수-42개--현행목표-분리안)) | `settlement-paid-order` · `OrderEventListener`(`Topics.ORDER`) | `SettlementOrderMapper.from`: 봉투·본문 `eventId`·`eventType`·키 = `orderNo` 일치, (`eventType`, `schemaVersion`)으로 모양 선택(결정 170, SE-T9), 주문 사실 값 13개 + 선택 칸 `paymentConfirmedAt`(가칭, 없으면 null — 옛 이벤트). `PaidOrder` 생성 검증(UUID `orderNo`, 64자 식별자, `amount > 0`, KRW, 마이크로초 시각) | `RecordPaidSale.execute`. Inbox `(eventId, settlement-paid-order)` + `uk_sale_order_no`·`uk_sale_event_id`·`uk_sale_request_event_id`·`uk_sale_payment_result_event_id`·`uk_sale_payment_id`. 중복 판정 지문 = 주문 사실 값 + `paymentConfirmedAt`(옛 행은 null과 비교, P2.7-3 호환 테스트, MC-S4) | 외부 조회 실패·월 선택 실패 = 재시도 가능, 계약·값 불일치·출처 재사용 = 영구 실패(T20). 소진·영구 실패는 실패 보관 표·키 정지(결정 155·160) |
| `RefundPaymentConfirmed` ([#28](../platform/design.md#eventtype-상수-42개--현행목표-분리안)) | `settlement-confirmed-refund` · `RefundEventListener`(`Topics.PAYMENT`) | `SettlementRefundMapper.from`: 봉투·본문·키 일치, `schemaVersion` 1(`source` 있음)·2(`source` 없음, T19) 모두 받음. 내부 입력 `ConfirmedRefund`는 `source`를 갖지 않는다(MC-S2) | `RecordConfirmedRefund.execute`. Inbox + `uk_refund_id`·`uk_refund_event`·`uk_refund_order`, 대기 환불 PK `order_no`·`uk_awaiting_refund_id`·`uk_awaiting_event`, 취소 PK `(provider, cancellation_id)` | 원매출 없음 = 대기 환불(R11, 실패 아님). PG 조회 실패·월 선택 실패 = 재시도 가능, 값 불일치·취소 재사용 = 영구 실패 |
| `RefundLateCancellationConfirmed` ([목표 행](../platform/design.md#목표-행-아직-코드에-없음)) | `settlement-confirmed-refund` · `RefundEventListener`(같은 리스너, 같은 그룹) | `SettlementRefundMapper.from`이 타입별 모양으로 읽어 같은 `ConfirmedRefund`로 바꾼다. 확인 시각 = 이 이벤트의 늦은 취소 확인 시각(R6.13). 필요한 칸(`refundId`·`attemptId`·`requestEventId`·`orderNo`·`paymentId`·`buyerSubject`·`amount`·`currency`·`providerTransactionId`·`cancellationIds`)이 본문에 있어야 한다(초안 메모 F) | 위와 같음. Inbox `event_type`은 받은 값 그대로(P2.1-6) | 위와 같음. 같은 주문에 다른 금융 확인 사실이 이미 반영된 경우는 `[결정 필요 M-SE-2]`(1회차 지적 A2·B2·A5, 판정 전) |
| `Topics.ORDER`·`Topics.PAYMENT`의 그 밖의 타입(`RefundExecutionSucceeded`·`RefundExecutionFailed`·`RefundFailureClosed` 포함) | 위 두 그룹 | `EventEnvelope.from`만(헤더 없으면 거절) | 건너뜀, (그룹, 타입) 지표(P2.3-7, SE-T10) | 없음. Settlement는 귀속 보강·실패 사실을 소비하지 않는다(P3.26-4, U5, MC-S2, 1회차 지적 A4·B5, 판정 전) |

### 발행 (목표, 결정 158 — SE-T3)

| 이벤트 (플랫폼 행) | 발행 지점 | 토픽 · 키 | 업무 사실 | 본문 칸 제안(플랫폼 표에서 확정, 1회차 지적 A5, 판정 전) | 받는 쪽 |
|---|---|---|---|---|---|
| `SettlementSaleRecorded` (가칭, [목표 행](../platform/design.md#목표-행-아직-코드에-없음)) | `RecordPaidSale.record` — 원매출 삽입과 같은 트랜잭션, `OutboxRecorder.record("SettlementSale", orderNo, event)` | `stove.settlement.v1` · `orderNo` | Settlement가 한 주문의 매출 배분을 기록했다 | `eventId`·`occurredAt`(Settlement 기록 시각), `orderNo`, `orderPaidEventId`(받은 `OrderPaid.eventId`), `paymentId` | Order(가칭 그룹 `order-settlement-sale`)가 `PAID`인데 응답 없는 주문을 셈(P3.15-5, 1회차 지적 B4, 판정 전) |
| `SettlementRefundRecorded` (가칭) | `RecordConfirmedRefund.record`, 대기 환불 반영 때 `RecordPaidSale.record` — 환불 조정 삽입과 같은 트랜잭션 | `stove.settlement.v1` · `orderNo` | Settlement가 한 환불의 정산 조정을 기록했다 | `eventId`·`occurredAt`, `orderNo`, `refundId`, `confirmationEventId`(반영한 금융 확인 사실의 `eventId`), `confirmationEventType` | Payment(가칭 그룹 `payment-settlement-refund`)가 금융 확인·늦은 취소 확인인데 응답 없는 건을 셈(1회차 지적 B4, 판정 전) |

- 금액·판매자·월은 싣지 않는다. 응답 쌍을 잇는 식별자만 필요하고 정산 금액의 정본은 Settlement다(원칙 ③).
- 반영당 한 번: 원매출은 `uk_sale_order_no`, 환불 조정은 `uk_refund_id`가 두 번째 사실을 막는다. 재전달·재투입은 무변화 경로라 Outbox를 쓰지 않는다.
- 같은 키 순서: 같은 주문에서 `SettlementSaleRecorded`가 `SettlementRefundRecorded`보다 먼저 커밋된다(대기 환불 반영이면 같은 트랜잭션에서 매출 사실을 먼저 기록). 릴레이 키 보류는 P2.2-4.

## 모듈 안 불변식

모듈을 넘는 것은 플랫폼 번호를 링크한다. "없음 → tasks"는 지키는 곳이나 테스트가 아직 없다는 뜻이다.

| ID | 불변식 | 지키는 곳 | 확인 테스트 | 플랫폼 |
|---|---|---|---|---|
| SE-I1 | 주문당 원매출 1행, 주문 사실 식별자는 한 매출에만 쓰인다 | `uk_sale_*`(V6), `RecordPaidSale.existing`(`:65`), `SettlementSale.requireSame` | `SettlementIntegrationTest#eventAndPaymentIdentityReuseCannotCreateAnotherSale`·`#fullSourceComparisonHappensBeforeInboxDeduplication`·`#concurrentDuplicatesOnlyIncreaseMonthlyAmountsOnce` | I8 |
| SE-I2 | `amount = platformFeeAmount + sellerPayoutAmount`, 비음수, HALF_UP·오버플로 거절 | `ck_sale_allocation`(V8), `PlatformFeePolicy`, `SettlementSale.record` | `SettlementDomainTest#halfUpAndOverflowAreExplicit` | — |
| SE-I3 | 판매자 몫은 PG 비용과 무관, 플랫폼 잔여액만 비용으로 바뀜 | `SettlementSale`(비용 칸 없음), `PgFeeRecord` 분리 | `SettlementSeparationTest#lateFeeChangesOnlyThePgViewNotTheSellerFacts`, `SettlementDomainTest#pgExpenseOnlyChangesPlatformShareIncludingNegativeRemainder` | I7 |
| SE-I4 | 환불 조정 = 원매출 배분의 음수, 원매출당 1건 | `ReverseOriginalFullRefundPolicy.allocate`, `ConfirmedRefund.requireSale`, `ck_refund_money`·`fk_refund_sale`·`uk_refund_order`(V10) | `FullRefundAllocationPolicyTest#fullRefundReversesBothOriginalSharesAndPreservesTheirPolicyIdentity`, `SettlementIntegrationTest#concurrentRefundDeliveriesPostOneReversal` | I2 |
| SE-I5 | 취소 근거 (공급자, 취소 ID)는 한 환불에만 | 취소 PK(V10) | `SettlementIntegrationTest#reusedCancellationCannotReverseAnotherOrder` | I8 |
| SE-I6 | `CLOSED` 월 합계 = 반영 월 원장 합계(매출·환불 각각) | `SellerMonthlySettlement.close`(`:63`) | `SettlementRefundPostingTest#closeChecksRefundTotalsAsWellAsGrossSales` | I5 |
| SE-I7 | `CLOSED` 월은 바뀌지 않고 `OPEN`으로 돌아가지 않는다 | 코드 `SellerMonthlySettlement.add`(`:54`)·`addRefund`(`:48`). DB: `ck_month_closed`는 상태·`closed_at` 짝만 — 합계 변경은 막지 않음 | DB 거절 테스트 없음 → tasks MC-S1 | I6 |
| SE-I8 | 반영 월 ≥ 귀속 월, 귀속 월 보존 | 코드 `RecordPaidSale.record`(`:79-91`), `RecordConfirmedRefund.record`. DB CHECK 없음 | `SettlementIntegrationTest#closingPastMonthKeepsLateSalesInCurrentMonthAndDuplicateKeepsOriginalAllocation`·`#lateRefundOfAMonthWithoutRowBeforeALaterClosedMonthMovesToTheCurrentMonth`. DB CHECK → tasks SE-T6 | I6 |
| SE-I9 | 같은 판매자의 반영·마감은 직렬, 뒤 월 `CLOSED`면 앞 월 행을 만들지 않는다 | `SellerLockRepository.lock`(`S/core/domain/SellerLockRepository.java:13`), 세 서비스의 뒤 월 확인 | `SettlementIntegrationTest#closingALaterMonthWaitsForAnEarlierCloseHoldingTheSellerLock`·`#concurrentClosingAndSaleChooseAConsistentBucket`·`#refundRacingWithCloseChoosesOneConsistentMonth` | I6 |
| SE-I10 | 오래된 월부터 마감, 이월 등식, 출처 월 한 번 | `CloseSellerMonth.close`(`:132-135`)·`carry`, `ck_seller_carryover_*`·`uk_seller_carryover_month`(V13)·`uk_seller_carryover_source`(V15) | `SellerCarryoverTest#negativeTotalIsCarriedOutAndPayableStaysAtZero`, `SettlementIntegrationTest#negativeNetCarriesForwardOnlyThroughClosingsInMonthOrder`·`#theCarryLedgerAcceptsEachSourceMonthOncePerSeller` | I5 |
| SE-I11 | 미확정 PG 비용은 NULL, 마감은 비용을 읽지 않는다 | `ck_pg_fee_money`(V8), `SettlementSaleView`·`SellerMonthView`의 `pgCostStatus`, `CloseSellerMonth`(비용 참조 없음) | `ReadPgFinancialsContractTest`, `SettlementSeparationTest#verifiedPaymentWithoutFeeStillProducesTheOriginalSellerAllocation` | I7 |
| SE-I12 | 정본 거래당 비용 연결 1, 반영 월은 저장된 업무 반영 월 | `uk_pg_cost_source`(V12), `PgCostPosting.record` | `PgCostPostingTest#approvalUsesTheStoredSaleMonthAndKeepsThePgPayoutDateSeparate`·`#returnUsesTheRefundPostingMonthRatherThanTheOriginalSaleMonth` | — |
| SE-I13 | 정본 거래 내용 불변, 같은 원본은 한 수집본 | `uk_pg_statement_entry`·`PgStatementEntry.requireSame`, `uk_pg_statement_fingerprint`(V11) | `ReconcilePgStatementContractTest`, `PgStatementRecoveryTest` | — |
| SE-I14 | 매출 대기 환불은 주문당 1, `APPLIED`면 그 주문의 환불 조정이 있다, 대기 저장과 매출 기록 경쟁에서 `AWAITING_SALE`이 남지 않는다 | (목표) 대기 환불 PK·CHECK·FK, 양방향 잠금 읽기 | 없음 → tasks MC-S3 | I4·I14 |
| SE-I15 | 반영 사실 Outbox는 반영 행과 같은 트랜잭션에 반영당 1건 | (목표) `OutboxRecorder.record`(`Propagation.MANDATORY`), `uk_sale_order_no`·`uk_refund_id` | 없음 → tasks SE-T3 | I14 |
| SE-I16 | 모든 상태·사유 칸은 enum 값만 | `ck_month_closed`(V6), `ck_receipt_state`(V7), `ck_refund_source`(V10). 헤더 `status`·항목 `check_code`·연결 `check_code`·`month_reason`은 CHECK 없음 | 없음 → tasks SE-T5 | — |
| SE-I17 | 다른 모듈·PG 시각과 Settlement 시계를 비교해 거절하지 않는다 | 위반: `RefundMonthInput`(`S/core/domain/RefundMonthInput.java:11`), `RefundMonthSelection`(`:48`·`:51`) | 없음 → tasks SE-T8(T3) | I13 |
| SE-I18 | 매출 귀속 월 = `paymentConfirmedAt` KST 월(없으면 `occurredAt`) | (목표) `SettlementOrderMapper.from`, `RecordPaidSale.record`(지금 `:78`은 `occurredAt`) | 없음 → tasks MC-S4 | P3.15-3 |

## 외부 연동과 adapter 메모

| 포트(`core.port`, 중립 계약) | adapter(목표) | 용도 |
|---|---|---|
| `PgPayments` | PG 승인 근거 읽기 adapter 하나(지금 `PortOnePaymentHttpClient`) | 주문 근거에 연결할 승인 근거·결제 구성·(보고되면) 비용 |
| `PgRefunds` | PG 취소 근거 읽기 adapter 하나(지금 `PortOneRefundHttpClient`) | 전액 취소·성공 취소 ID·합계·원승인 구성 |
| `PgStatementSource` | PG 정산 자료 adapter(지금 `TossPayStatementHttpClient`), 자격 미설정이면 `UnavailablePgStatementSource`(503) | 전체 페이지·원본 수집, 수집 가능 판정 |
| `CatalogOwners` | `CatalogOwnerHttpClient` | Catalog 소유자 GET, Settlement 전용 서비스 키 |
| (제거) `PgSettlements` | `SimulatedPgSettlementHttpClient`·`UnavailablePgSettlements` 제거(결정 162) | — |
| (제거) 시뮬레이터 분기 | `SimulatedPgPaymentHttpClient`·`UnavailablePgRefunds` 제거(결정 162) | — |

adapter 메모(공급자 규칙은 여기에만 둔다 — 원칙 ②, 결정 140):

- **PortOne(승인·취소 읽기)**: `GET /payments/{paymentId}?storeId=...`만 호출한다. 응답의 `V2`·`PAID`·`KRW`·채널 유형 `TEST`·상점/채널 키·거래 ID(`pgTxId`)·총액/실결제/혜택을 엄격히 검증하고(숫자 문자열·누락·중복 JSON 키·후행 JSON·다른 채널 거절), 나노초 시각은 마이크로초로 정규화한다. 접수 대상은 `PAID`·취소액 0의 원승인과, 전액 성공 취소가 검증된 `CANCELLED` 원승인(늦은 원매출)이다. 외부 부담 혜택은 PG 공급자 `TOSSPAY_V2`의 즉시할인·포인트이며 정책 ID `TOSSPAY_EXTERNAL_BENEFIT_V1`로 저장한다. 공급자 식별 값은 지금 `PORTONE_TEST`(저장 값)다. 기본 URL HTTPS `api.portone.io`, 연결 2초·읽기 5초. 계약 검증에서만 루프백을 허용한다. 개발·여정·통합 테스트는 같은 adapter를 기준 시뮬레이터(PortOne 흉내 서버)에 붙인다(결정 141·162).
- **토스페이(정산 자료)**: `POST /api/v2/settlement-details` 전체 페이지·원본 수집(결정 43). 조회 기준 `DUE`·`SETTLE`. 당일 `DUE` 자료는 KST 10시 이후 공개(그 전은 `NOT_YET_PUBLISHED`), 조회 가능 기간은 오늘부터 1년 전까지(밖은 `OUT_OF_RANGE`), 당일 `SETTLE`은 범위 밖 — 지금 이 규칙이 core `ReceivePgStatement`(`S/core/service/ReceivePgStatement.java:43-47`)에 있다(SE-T1). 거래 키 대응: PortOne `pgTxId` ↔ 토스페이 `payToken`, PortOne `paymentId` ↔ 토스페이 `orderNo`(내부 업무 `orderNo` 아님). 가맹점 키는 `sk_test_`/`sk_live_`이며 PortOne Secret과 서로 바꾸지 않는다. 키는 요청 본문에만 쓰고 원본·설정 문자열·로그에 남기지 않는다. 기본 URL HTTPS `pay.toss.im`, `max-pages` 100, 연결 2초·읽기 10초.
- 연결·조회 실패를 거절·미존재·지급 완료로 바꾸지 않는다. 불완전한 설정·미지원 공급자는 시작 때 거절하고 자동 대체·TEST→LIVE 자동 전환이 없다(R4.7).
- PG 접근 키는 역할별로 나눈다. Settlement에는 읽기 전용 키만 주고 취소·승인 제어 키는 주지 않는다. 플랫폼 JWT·Gateway ADMIN은 PG 제어 권한이 아니다.
- Catalog 소유자 GET은 `CATALOG_SETTLEMENT_SERVICE_KEY`(Download 키와 다름)를 쓰고 Gateway에 `/internal/**`을 더하지 않는다.

## 설정

| 설정 키 (환경 변수) | 기본값 | 역할 | 운영·테스트 차이 |
|---|---|---|---|
| `stove.settlement.pg-provider` (`SETTLEMENT_PG_PROVIDER`) | 지금 `SIMULATED` → 목표: 키 제거, 승인·취소 adapter는 하나(SE-T2) | 승인·취소 근거 adapter 선택 | 목표: 없음(모든 환경 같은 adapter, 주소만 다름) |
| `stove.settlement.pg-base-url`·`pg-api-key` (`MOCK_PG_URI`·`MOCK_PG_SETTLEMENT_KEY`) | 지금 `http://localhost:8092` | 시뮬레이터 주소·키 | 목표: 제거(결정 162, SE-T2) |
| `stove.settlement.portone.base-url`·`api-secret`·`store-id`·`channel-key` | `https://api.portone.io`, 나머지 빈 값(필수 주입) | 승인·취소 읽기 adapter | 기준 시뮬레이터는 `base-url`만 바꾼다. 테스트 전용 기본값 없음 |
| `stove.settlement.catalog-base-url`·`catalog-key` | `http://localhost:8081`, 빈 값 | Catalog 소유자 조회 | 같음 |
| `stove.settlement.connect-timeout`·`read-timeout` | 2s·5s | 승인·취소·Catalog HTTP 제한 | 같음 |
| `stove.settlement.tosspay.enabled`·`scheduled-enabled` | false·true | 정산 자료 수집·예약 수집 | 같음 |
| `stove.settlement.tosspay.base-url`·`api-key`·`merchant-id`·`environment` | `https://pay.toss.im`, 빈 값, 빈 값, `TEST` | 정산 자료 adapter | 같음. `environment=TEST`면 `identity-mapping-verified=true` 금지(`TossPayStatementProperties`) |
| `stove.settlement.tosspay.portone-store-id`·`portone-channel-key`·`identity-mapping-verified` | 빈 값, 빈 값, false | 대조 범위·거래 키 대응 검증 | 같음. 목표: core 이름은 중립(`provider-store-id` 등)으로 읽되 환경 변수는 호환(SE-T1) |
| `stove.settlement.tosspay.max-pages`·`poll-delay`·`lookback-days`·타임아웃 | 100·1h·7(1–31)·2s/10s | 수집 범위·주기 | 같음 |
| `stove.outbox.relay-enabled` | 지금 false → 목표 true(SE-T3) | 반영 확인 사실 발행 | 같음 |
| `stove.messaging.ops.enabled` | 지금 false → 목표: 실패 보관 재투입·재발행 ADMIN 경로를 켬(결정 160·161, SE-T3) | 운영 경로 | 같음 |
| (목표) `republish-owner` | `settlement` | 재발행 허용 이벤트(현행 2종) | 같음 |

운영 계약에 테스트 전용 경로·기본값을 두지 않는다(원칙 ⑦). 지금 남은 예외는 시뮬레이터 공급자 기본값(`SIMULATED`·`MOCK_PG_URI`)이며 SE-T2가 없앤다. 계약 검증의 루프백 허용은 adapter 테스트 구성(`SettlementProviderConfigurationTest`)에만 있다.
