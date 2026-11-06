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
```

| 정책 | 값 |
|---|---|
| 플랫폼 수수료 | `PLATFORM_MVP_V1`, 1,000bps=10%, 원 단위 HALF_UP (고정, 운영 중 변경 없음) |
| 외부 부담 혜택 | `TOSSPAY_EXTERNAL_BENEFIT_V1` (확인된 외부 부담만) |
| 전액 환불 배분 | `FULL_REFUND_REVERSE_ORIGINAL_V1` (원배분 음수 상계) |
| 환불 반영 월 | `REFUND_CONFIRMED_MONTH_V1` (확인 월 OPEN→그 월, CLOSED→현재 OPEN 월) |
| PG 비용 월 연결 | `LINKED_BUSINESS_MONTH_V1` (저장된 반영 월 사용) |

금액은 모두 정수 long·KRW. 음수 플랫폼 잔여액·음수 월 순액은 자동 이월·계좌 회수·송금 실행을 뜻하지 않는다.

## API 계약

공개 명세는 현재 19개 경로·21개 연산이다. 기존 MVP 9개 연산에 환불 조회 3개, 토스페이 정산 수집 6개, PG 지급일/비용 조회 2개를 더했다. CREATOR `/me/**`는 JWT sub로 제한하고 나머지는 ADMIN 전용이다.

| 메서드 | 경로 | 권한·동작 |
|---|---|---|
| GET | `/api/v1/settlements/me/sales` | CREATOR 본인 매출 페이지 |
| GET | `/api/v1/settlements/me/months` | CREATOR 본인 월 합계 페이지 |
| GET | `/api/v1/settlements/me/refunds` | CREATOR 본인 환불 조정(음수 조정액·확인/접수 월·사유). 구매자·PG 실행 정보 미노출 |
| GET | `/api/v1/settlements/sales` | ADMIN 전체/판매자별 매출. `sellerSubject`·PG 비용·플랫폼 잔여액·정책 포함 |
| GET | `/api/v1/settlements/months` | ADMIN 월 금융 요약. 반영 월의 매출 비용·환불 반환 조합. 미확인 시 NULL |
| GET | `/api/v1/settlements/refunds` · `/{refundId}` | ADMIN 환불 목록(필터·정책/출처 요약)·상세(취소별 근거) |
| POST | `/api/v1/settlements/sellers/{sellerSubject}/months/{month}/close` | ADMIN 지난 월 마감 |
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
| `refundPgFeeReturnAmount` · `confirmedRefundPgFeeReturnAmount` · 승인/반환 VAT · `confirmedNetPgCostAmount` | 환불 비용 반환과 VAT, 확인된 순비용 |
| `pgFeeAmount` · `platformNetAmount` | 매출 비용(수수료+VAT) − 확인된 반환 / 환불 조정 후 플랫폼 몫 − 순비용. 미확인이 하나라도 있으면 NULL |

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

- **매출 접수(RecordPaidSale):** 기존 원장 조회 → (신규) PG 승인/비용·Catalog 소유자 직접 조회·대조 → 월 행 생성/잠금·마감·지연 규칙으로 반영 월 확정 → Inbox·SettlementSale·OPEN 월 합계 원자 커밋.
- **월 마감(CloseSellerMonth):** 이전 월만, 월 행 잠금, 원장 합계·건수 대조 후 CLOSED·closedAt 확정. 재요청은 최초 결과 반환.
- **PG 수령(ReceivePgSettlement/ReconcilePgReceipt):** 명세 접수(REPORTED_PAID) → 별도 트랜잭션 재대사 → 전 항목 일치 시 SIMULATED_RECEIVED.
- **환불(RecordConfirmedRefund):** 금융 확인 소비 → 원매출·PG 취소 직접 GET 대조 → 배분 A·월 정책 A 적용 → Inbox·환불 원장·월 환불 합계 원자 저장.
- **PG 비용(ReconcilePgStatement/ReconcilePendingPgStatements):** 토스페이 수집(트랜잭션 밖, 원본 선행 커밋) → 정본 거래 잠금·대조·비용 연결 원자 저장 → 주기 재대사(PG 재호출 없음).

## DB 전환과 보존

- Settlement V1~V5 보존, V6(매출 2개 테이블)·V7(PG 명세 2개)·V8(책임 분리·legacy 열 이름 변경)·V9(PgFunding CHECK)·V10(환불 원장)·V11(토스페이 수집)·V12(PG 비용 연결)로 확장.
- Payment V12는 `purchase_payment` 비용 스냅샷만 추가하고, 외부 모의 PG는 자체 Flyway로 공급자 테이블을 만든다. 플랫폼 Flyway와 스키마/이력을 공유하지 않는다.
- 과거 열은 nullable `legacy_*`로 보존하고 현재 모델은 읽거나 갱신하지 않는다. V9 CHECK는 전부 없음/전부 검증된 PgFunding 구성만 허용하며, 과거 행에 실결제·혜택·외부 부담을 소급 생성하지 않는다.

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
