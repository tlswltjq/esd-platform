# 설계: Payment

## 아키텍처 위치

- 포트 8083, 저장소 MySQL. 외부 요청은 Gateway(8080)의 `/api/v1/payments/**` 라우트를 거친다.
- `core/{domain, port, service}` + `api/{controller, listener, scheduler}` + `adapter/` + `config` 경계를 따른다.
- 외부 PG 호출은 `core.port`(PgGateway·승인/조회/환불 포트), HTTP 구현은 `adapter`, 환경 바인딩·조립은 `config`가 담당한다. 업무 서비스·도메인은 `EventEnvelope`·외부 이벤트 전체를 입력받지 않고 api.listener의 Mapper가 내부 입력으로 변환한다.
- Payment는 가격을 Catalog·Store에서 다시 조회하지 않고 Order DB·주문 상태를 직접 바꾸지 않는다. Payment의 최종 결과 저장과 Order의 반영 사이에는 이벤트 전달 지연이 있다.

## 핵심 설계 결정

- **세 트랜잭션 경계로 분리한다.** ① 접수(요청 비교→결제 생성→Inbox→커밋, PG 미호출), ② 공급자 호출(저장된 고정 입력으로 트랜잭션·잠금 밖에서 호출), ③ 결과(행 잠금→결과 연결·금액 검증→상태 전이→결과 메타데이터·Outbox 저장→커밋).
- **고정 paymentId를 멱등 키로 쓴다.** 접수 시 발급한 paymentId는 재시작·응답 유실·DB 롤백·동시 호출에도 바뀌지 않는다. 공급자 거래 ID와 구분한다.
- **확인된 결과만 확정한다.** `PAID`·`FAILED`만 최종이고, 미확정·응답 유실·결과 저장 실패는 `PENDING`을 유지한다. 시간 경과·재시도 횟수만으로 실패로 바꾸지 않는다.
- **provider별로 대상을 선별한다.** SIMULATED와 PORTONE_TEST 대기 건이 서로를 가로막거나 다른 공급자를 호출하지 않는다. PortOne 승인 어댑터는 `PAID`만 확정하고 그 밖은 미확정으로 둔다.
- **웹훅은 조회 힌트다.** 서명·스토어·재전달을 검증해 내구 접수하되, 최종 결제/환불 증명이 아니라 서버 PG 조회를 깨우는 힌트로만 쓴다.
- **환불 실행과 원구매를 분리한다.** 환불은 고정 attemptId·고정 본문·3시간 창으로 PG를 취소/조회하고, 확정돼도 원 `purchase_payment`의 `PAID`·원금액을 바꾸지 않는다.
- **비용은 미확정(null), 정산은 Settlement가 소유한다.** PG 비용을 0원으로 만들지 않고, Payment에 정산 중계 API를 두지 않는다. Settlement가 PG 명세를 직접 조회한다.

## 상태 모델

```
[*] --> PENDING        (주문 요청·Inbox 저장. 미호출·결과 미확인 포함)
PENDING --> PENDING     (일시 오류·응답 유실·미확정 결과·3시간 후 조회만)
PENDING --> PAID        (일치하는 승인 확인)
PENDING --> FAILED      (일치하는 최종 거절 확인)
PENDING --> EXPIRED     (Order가 만료를 전달. 금융 결과를 만들지 않음)
PENDING --> FAILED      (운영자 승인 보류 종결, reasonCode REVIEW_CLOSED. 결정 115)
PENDING --> EXPIRED     (접수 전 보류를 만료 통지 뒤 종결. 이벤트 없음. 결정 115)
[*] --> EXPIRED        (만료 뒤 늦은 OrderPlaced. 승인·조회 예약 없음)
```

- `PENDING`에는 아직 호출하지 않은 결제와 호출했으나 결과를 확인하지 못한 결제가 함께 있다. READY·PROCESSING·UNKNOWN을 별도 상태로 나누지 않는다.
- PortOne 개별 시도 FAILED는 상태 전이가 아니라 별도 이력이다. 같은 orderNo·paymentId로 재결제한다.
- `EXPIRED`는 Order가 소유한 만료 판단의 전달 결과이며 금융 성공/실패 제약을 만들지 않는다.

## 데이터 모델

| 테이블 | 내용 |
|---|---|
| `purchase_payment` | 결제 본체. paymentId·orderNo·requestEventId·orderOccurredAt·buyerSubject·상품 스냅샷·listAmount/discountAmount/amount/currency·provider·status·providerTransactionId/providerOccurredAt·resultEventId/resultOccurredAt·failureReasonCode/failureReason·nextAttemptAt/lastErrorCode·createdAt/updatedAt |
| 모의 PG 원장(simulators/mock-pg 전용 DB) | paymentId 유일 키·주문/금액/통화·공급자 거래 ID·APPROVED/DECLINED·사유·결정 시각. Payment 엔티티를 참조하지 않고 독립 커밋 |
| 웹훅 접수(`payment_pg_webhook`) | 웹훅 ID·본문 해시·재조회 예약. 같은 ID·본문은 최초 기록 유지 |
| 승인 접수·환불 실행·취소 예약/중단·실패 시도 이력·비용 스냅샷 | 암호화 고정 본문·토큰 지문·최초 준비 시각·3시간 창·lease·attemptId·조사 기록 등. 후속 마이그레이션으로 추가 |
| `payment_request_trace`(V13) | 최초 접수의 W3C v00 추적 부모. 누락·이상·중복 헤더는 부모 없음, 재전달로 덮어쓰지 않음(결정 28) |
| `failed_payment_attempt`(V28) | 공급자 transactionId별 불변 실패 근거·공개 attemptId·`next_failure_lookup_at`. 공급자 원문 사유 미저장 |
| 승인 접수(V29) | 암호화 고정 본문·토큰 지문·원구매·승인 상태·최초 준비·다음 일정·lease, 명령/공급자 시도/진행 중 작업 유일 키 |
| `payment_order_lifecycle`(V31) | 주문별 잠금과 최초 만료 근거. Payment 없이도 존재, cascade 삭제 없음 |
| `pg_paid_amount`·`pg_discount_amount`(V32) | PG 실결제/할인 쌍. NULL 일치·`PAID`·음수 금지·합계=amount CHECK |
| `approval_investigation`(V34·V38) | 승인 보류 조사의 append-only 기록. V38부터 승인 FK에 삭제 cascade 없음(RESTRICT) |
| `approval_closure`(V37) | 승인 보류 운영 종결의 append-only 기록. approvalId·requestId(각각 유일)·종결자·note·근거 상태(CANCELLED·FAILED·READY)·관측/종결 시각. 승인 FK에 삭제 cascade 없음 |

- paymentId·orderNo·requestEventId·nullable resultEventId와 nullable `(provider, providerTransactionId)`에 각각 유일 제약을 둔다. 문자열 식별자는 대소문자를 구분하고 `(status, nextAttemptAt, id)`·`(buyerSubject, id)` 조회를 지원한다.
- V1~V10 체크섬을 보존하고 `V11`이 `purchase_payment`를 추가한다. 이후 V12 비용 스냅샷(외부 모의 PG 전환), PortOne 웹훅·checkout·승인 접수·환불 취소/조회·실패 시도 이력·암호화 본문·EXPIRED 허용 등을 후속 마이그레이션으로 쌓았다.
- 모델 속성은 `docs/domain/models/order-payment.md#payment` 참조.

## API 계약

| 메서드 | 경로 | 역할 | 동작 |
|---|---|---|---|
| GET | `/api/v1/payments?page=0&size=20` | MEMBER | 본인 결제 목록(`createdAt DESC, id DESC`) |
| GET | `/api/v1/payments/{orderNo}` | MEMBER | 본인 주문의 결제 상세 |
| GET | `/api/v1/payments/{orderNo}/checkout` | MEMBER | 본인 `PENDING`/`PORTONE_TEST`의 SDK 설정. `no-store`. 생성·승인 아님 |
| GET | `/api/v1/payments/{orderNo}/attempt-failures?page=0&size=20` | MEMBER | 본인 실패 시도 이력·수집 상태. `no-store`, DB 조회만 |
| POST | `/api/v1/payments/{orderNo}/approvals` | MEMBER | PortOne 승인 내구 접수(202). `PORTONE_TEST` 모드만 |
| GET | `/api/v1/payments/{orderNo}/approvals/{requestId}` | MEMBER | 승인 진행 조회. `no-store`, 토큰·암호문 없음 |
| POST | `/api/v1/payments/webhooks/portone` | 공개(서명) | 서명·스토어 검증 뒤 조회 힌트 접수. 공개 edge가 직접 전달 |
| GET/POST | `/api/v1/admin/refund-cancellation-holds` | ADMIN | 운영 중단 목록·상세·재개 |
| GET/POST | `/api/v1/admin/approval-reviews` | ADMIN | REVIEW_REQUIRED 목록(reason 필터)·상세·조사 이력 GET(종결 기록 포함), append-only 조사 기록 POST |
| POST | `/api/v1/admin/approval-reviews/{approvalId}/closure` | ADMIN | PG 재조회 근거로 보류 종결(`PORTONE_TEST`만). `no-store`, requestId 멱등. PG 취소·승인 없음 |
| GET/POST | `/api/v1/admin/refund-delays` | ADMIN | 장기 미확정 목록·상세·조사 이력 GET, 조사 기록 POST |

- 익명 401, 역할 없음 403, 타인·없는 결제 404, 잘못된 페이지 400. checkout의 로컬 `PAID`/`FAILED`·다른 공급자는 409.
- MEMBER 조회의 구매자는 `CurrentSubject`의 JWT sub다. 본문·쿼리·헤더·`X-Member-Id`로 소유자를 바꾸지 않는다.
- 구 prepare·callback·cancel·simulator·승인 주입 업무 엔드포인트와 Gateway의 익명/CSRF·simulator 예외는 제거했다.
- 승인 접수 상태: `REQUESTED`·`ADMITTED`·`REVIEW_REQUIRED`·`FAILURE_CONFIRMED`·`SUCCEEDED`·`FAILED`·`EXPIRED`. 개별 승인 `FAILED`는 같은 주문 재결제 근거이고 전역 실패가 아니다. 운영자 종결로 끝난 승인도 `FAILED`지만 이때 결제는 `FAILED`/`EXPIRED`라 새 주문으로 안내하며, 종결 기록으로 구분한다(결정 115).
- 승인 접수 요청 `{requestId(한 인증 결과에 고정한 UUID), txId, paymentToken}` → 202 `requestId·orderNo·paymentId·receivedAt`, Location `/approvals/{requestId}`.

| 관측 | Order | 승인 작업 | Payment | 고객의 다음 행동 |
|---|---|---|---|---|
| 인증 결과 202 접수 | PENDING일 수 있음 | REQUESTED | PENDING | Location 조회. 성공으로 표시하지 않음 |
| 기한 내 Order 접수 영수증 저장 | PAYMENT_PROCESSING | ADMITTED | PENDING | 기존 진행 결과 확인. 새 결제창 열지 않음 |
| 기한 내 개별 실패·Order 종료 확인 | PENDING | FAILED | PENDING | 같은 주문·금액으로 새 인증 |
| Order 만료 응답 또는 기한 후 실패 종료 | EXPIRED | EXPIRED/FAILED | EXPIRED | 현재 상품 조건으로 새 주문 |
| PG 성공 재조회·Payment/Outbox 커밋 | 이벤트 반영 뒤 PAID | SUCCEEDED | PAID | 주문·라이브러리 후속 반영 확인 |
| HTTP 결과 유실·3시간 경과·운영 확인 필요 | 진행 유지 | ADMITTED/REVIEW_REQUIRED | PENDING | 기존 결과 확인. 시간만으로 새 결제 없음 |
| 운영자 보류 종결(재조회 근거 전액 CANCELLED·FAILED·READY) | 이벤트 반영 뒤 FAILED(이미 EXPIRED면 유지) | FAILED(보류 사유 유지) | FAILED(`REVIEW_CLOSED`), 접수 전·만료 통지 뒤면 EXPIRED | 현재 상품 조건으로 새 주문 |

- `SUCCEEDED`는 Payment 금융 결과/Outbox 저장이며 OrderPaid·License 지급까지 동기 완료를 뜻하지 않는다.

## 이벤트 계약

| 방향 | 이벤트 · 토픽 | 연결과 금액 |
|---|---|---|
| 수신 | `OrderPlaced` · `stove.order.v1` (그룹 `payment-order-request`) | eventId·occurredAt·orderNo·buyerSubject·상품 스냅샷·listAmount·discountAmount·amount·currency |
| 발행 | `OrderPaymentCompleted` · `stove.payment.v1` | eventId·occurredAt·orderNo·requestEventId·paymentId·buyerSubject·amount·currency |
| 발행 | `OrderPaymentFailed` · `stove.payment.v1` | 완료와 같은 연결·금액 + 필수 reasonCode·reason |
| 수신 | `OrderExpired` · `stove.order.v1` (orderNo 키) | orderNo·원 OrderPlaced ID·구매자·청구액·통화·고정 기한·만료 확정 시각. 금융 실패 아님 |
| 수신 | `RefundExecutionRequested` · (환불 접수) | 고정 attemptId·원 PAID 연결로 PG 취소/조회 |
| 발행 | `RefundPaymentConfirmed` · `stove.payment.v1` | refund/attempt·원 실행 요청·주문·결제·구매자·금액·통화·`PG_QUERY_UNATTRIBUTED` 또는 `PG_RESPONSE`·원 PG 거래·취소 ID들. '우리 실행 성공' 이벤트가 아님 |
| 발행 | `RefundExecutionSucceeded` · `stove.payment.v1` | 조회 완료가 먼저 확정된 뒤 같은 취소 근거의 직접 성공을 보완. 최초 금융 이벤트 참조 |
| 발행 | `RefundExecutionFailed` · `stove.payment.v1` | 연결된 취소 ID FAILED와 원결제 전액 미취소를 함께 확인한 실패 근거. Order 이용권 복구의 근거 |
| 발행 | `RefundExecutionPrepared` · `stove.payment.v1` | 최초 준비 시각·3시간 경계를 한 번만 발행. PG 송신·성공을 뜻하지 않음 |

- `RefundPaymentConfirmed`·`RefundExecutionSucceeded`는 선택적 `RefundAmountsSnapshot`(원승인 실결제/할인 쌍, 성공 취소별 ID·총액·선택적 포인트, 제공사 취소 시각)을 담는다. 저장된 성공 근거만 쓰고 반환 현금을 계산하지 않으며, 접수·실패 응답의 금액을 성공 반환으로 전달하지 않는다([결정 36](../../../docs/decisions.md#refund-amount-details)).
- `OrderPaymentFailed`의 reasonCode `REVIEW_CLOSED`는 운영자가 승인 보류를 PG 재조회 근거로 종결했다는 뜻이다(결정 115). 공급자 거절이 아니며, providerTransactionId는 보류된 PortOne 시도(txId), 결정 시각은 종결 재조회 시각이다. reason은 근거 상태를 담은 기술 사유이고 고객 문구가 아니다. 주문이 이미 스스로 만료됐으면 Order는 만료를 유지한다.
- Kafka 키와 결과 Outbox aggregateId는 orderNo다. requestEventId는 원래 `OrderPlaced.eventId`이며 구매자 requestId와 다르다.
- eventId·requestEventId·paymentId 최대 64자, buyerSubject 최대 100자. 금액은 정수 long·양수·KRW이고 `listAmount - discountAmount == amount`. 소수 절삭 입력은 저장 전에 거부한다.
- 결과 이벤트 ID·시각은 결과 Outbox와 같은 트랜잭션에서 최초 한 번만 확정한다. DEAD가 된 결과는 보존하며 재발행은 저장된 ID·본문을 그대로 쓴다.
- 입력: orderNo UUID, 상품 코드 50자 패턴, 제목 200자 이하, 상품 ID·contentVersion 양수, 정가 양수·할인 0~정가. 할인 검사는 가격 구조 일관성이며 할인 자격 재판정이 아니다. 실패 사유 reasonCode 100자·reason 500자 이하 필수. 주문·결과 시각은 UTC 마이크로초.
- 결과 트랜잭션이 롤백돼 발행된 적 없는 후보 결과 ID는 최종 결과가 아니다. 업무 결과를 다시 확정하는 API는 두지 않는다.

## 외부 연동

- `PgGateway`(SIMULATED → 외부 모의 PG `SimulatedPgHttpGateway`, PORTONE_TEST → `AdmittedPortOnePgGateway`/`PortOnePaymentApprovalGateway`)로 승인·조회한다. 선택은 `stove.payment.pg-provider`·`PORTONE_TEST` 설정으로 조립한다.
- PortOne 조회는 상점·V2·TEST 채널·채널 키·`TOSSPAY_V2`·KRW·주문 총액·미취소·`customData.orderNo`·원래 승인 시도를 대조하고, `실결제액 + PG 할인액 = 주문 총액`을 요구한다([결정 34](../../../docs/decisions.md#provider-discount-payment-amounts)). PG 비용이 없는 응답은 미확정 null로 보존한다.
- Payment는 Order 내부 승인 접수/실패 통지 API를 `core.port` 계약의 HTTP adapter로 호출한다. 두 앱이 같은 서비스 키를 공유하고 MEMBER Bearer는 거절되며 Gateway는 이 경로를 제공하지 않는다.
- 승인 본문 암호화 키는 32바이트(Base64)이며 재시작해도 같아야 한다. 미완료 암호문이 있는 동안 교체하지 않는다.
- 웹훅 공개 경로는 edge(`https://<IP>/api/v1/payments/webhooks/portone`)가 Payment로 직접 전달한다. 서명 검증과 회원 checkout의 JWT 인증을 구분한다.
- Payment 승인/조회 키, Settlement 읽기 키, PG 제어 키는 분리하며 제어 키를 업무 앱에 주지 않는다. 비밀값은 서버 설정으로만 주입하고 저장소·로그·응답에 넣지 않는다.

## 처리·복구 동작 요약

- 주기 실행은 단일 인스턴스 fixed-delay(기본 1초·20건)로 due인 `PENDING`을 `nextAttemptAt, id` 순으로 고른다. 오류·미확정은 기본 5초 뒤 재예약하고 결제별로 처리한다. `stove.payment.processing-enabled`는 기본 true, 검증에서 false→true 재시작으로 접수/재개를 나눈다.
- 승인 복구: 최초 준비부터 3시간 미만은 같은 승인 ID·멱등 키·본문으로 자동 재송신+조회, 3시간부터는 GET만. 암호화 본문은 복호화한 원래 바이트만 재사용한다.
- 환불: 고정 attemptId를 Idempotency-Key로, 원액을 amount·currentCancellableAmount에 명시. 부분 취소·명시적 잔액 불일치(409 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN`)는 자동 POST 중단·조회 유지. 검증된 전액 취소 조회 또는 검증된 취소 완료 응답으로만 완료를 확정한다.
- 중복 공급자 호출은 같은 키의 멱등 처리와 최종 행 잠금으로 중복 승인·중복 결과를 막는다. 다중 인스턴스 임대는 후속 범위다.
- 원자성은 같은 DB 안의 상태·Outbox에만 적용한다. 공급자 결정과 Payment DB를 한 트랜잭션으로 묶거나 exactly-once 전달을 가정하지 않는다. 스케줄러는 유스케이스에만 위임한다.
- 순서 역전: `OrderPlaced`·`OrderExpired`가 같은 `payment_order_lifecycle` 행을 먼저 잠근다. 만료가 먼저면 근거만 보존하고, 늦은 `OrderPlaced`는 원구매 대조 뒤 예약 없는 `EXPIRED`로 만든다. 확정 금융 결과가 있으면 덮어쓰지 않고 충돌을 표시한다.
- 실패 시도 이력은 `PAID`·결과 Outbox 커밋 뒤 별도 트랜잭션으로 수집하고 장애는 `next_failure_lookup_at`으로 재예약한다. 오래된 `PENDING` 응답이 승인 뒤 예약을 지우지 않는다.
- 추적: 재시도마다 원래 부모의 새 자식 span을 만들고 다른 주문·스케줄러 scope와 분리한다. 샘플링·수집기 상태는 상태 전이 조건이 아니다.

환불 실행 일정·분류:

| 항목 | 규칙 |
|---|---|
| 첫 송신 | `RefundExecutionRequested` 소비자가 접수 커밋 뒤 수행(한 번에 1레코드). 이미 준비된 실행의 재전달은 GET만 |
| 결과 GET | 최초 준비 후 5·15·35초, 이후 1분. 밀린 단계는 건너뛰고 몰아서 실행하지 않는다. 3시간 뒤에도 GET 유지. 동시 최대 4·대기 큐 0·임대(연결+응답 제한+30초) |
| POST 재송신 | 한 회 종료 시각 기준 10초→30초→이후 1분. 임대(HTTP 3회 제한 합+30초)·스캔 1초·작업자 1개. 늦은 소유자의 종료는 새 임대를 덮어쓰지 못한다 |
| HTTP 분류 | 401·GET 403/404·POST 404 → 미확정, 같은 요청 자동 재개 / POST 403 → 중단 `FORBIDDEN` / 정확한 POST 400 `INVALID_REQUEST` → 중단 `PG_REQUEST_INVALID`. 오류 본문은 파싱·저장하지 않는다 |
| 활성화 | `PAYMENT_REFUND_LOOKUP_ENABLED`·`PAYMENT_REFUND_EXECUTION_ENABLED`·`PAYMENT_REFUND_RETRY_ENABLED` 기본 false. 송신은 조회, 재송신은 송신·조회 조립이 없으면 기동 실패 |
- 할인 거래 환불 사전 조회는 원승인의 실결제/할인과 대조하고, 제공사가 보고한 포인트 반환 근거를 보존한다([결정 35](../../../docs/decisions.md#provider-discount-full-refund)).

## 사용 안내

| 환경변수 | 기본값·범위 |
|---|---|
| `PAYMENT_PG_PROVIDER` | `SIMULATED` (`PORTONE_TEST` 선택 가능) |
| `PAYMENT_PROCESSING_ENABLED` | true. false면 접수·조회만(승인 작업자 포함 중지) |
| `PAYMENT_POLL_MS` / `PAYMENT_BATCH_SIZE` / `PAYMENT_RETRY_MS` | 1000(100~60000) / 20(1~200) / 5000(100~300000) |
| `PAYMENT_REVIEW_RETRY_INITIAL_MS` / `PAYMENT_REVIEW_RETRY_MAX_MS` | 5000(100~300000) / 600000(1000~3600000, 시작값 이상). 보류(`REVIEW_REQUIRED`) 승인의 PG 재조회 시작값·상한만 정한다([결정 122](../../../docs/decisions.md#approval-review-requery-interval)) |
| `MOCK_PG_URI` / `MOCK_PG_PAYMENT_KEY` | `http://localhost:8092` / 필수, Payment 전용 승인·조회 키 |
| `PAYMENT_ORDER_SERVICE_KEY` | Order와 같은 값. Order 내부 승인/실패 API 전용 |
| `PAYMENT_APPROVAL_PAYLOAD_KEY` / `ORDER_URI` | 32바이트 Base64, 교체 금지 / Payment가 접근할 Order 주소 |

- 목록은 `page≥0`, `size 1~100`, `createdAt DESC, id DESC`. 주문 생성 직후 Kafka 접수 전에는 결제 상세가 잠시 404다.
- 검증: `./gradlew :apps:payment:test :apps:payment:integrationTest`, `python3 tools/verification/verify-payment.py`(8개 업무 앱+외부 모의 PG 격리, 기본 포트 오프셋 30000은 `PAYMENT_TEST_PORT_OFFSET`, 11단계). 장애 주입 클래스는 bootJar에 포함하지 않는다.
- PortOne 실사용 전 채널의 **수동 승인**을 켜야 한다(자동 승인 채널을 서버 코드만으로 막을 수 없음). SDK의 paymentToken·txId를 승인 접수 API로 보내고 Location을 조회한다. `tools/verification/support/portone_checkout.html`은 Order와 무관한 직접 PG 탐침이다.
- 공유 환경 전환: 구 V1~V10·자료는 보존하고 자동 변환·자동 결제하지 않는다. 구 쓰기·릴레이를 멈추고 `tools/migrations/payment/payment-transition.sql`(반복 실행 가능)로 구 PENDING·DEAD Outbox를 보관·격리한다. 새 그룹 `payment-order-request`의 시작 오프셋은 과거 OrderPlaced·미발행 Outbox·기존 결과를 대조해 정하며 earliest로 바로 켜거나 구 Inbox를 지우지 않는다.
- 내장 모의 PG(V11 `simulated_payment_charge`) 이관: 처리기를 멈추고 원래 결정을 외부 PG로 이관·대조한 뒤 재개한다. 빈 PG로 기존 `PENDING`을 재개하면 결정을 잃으므로 자동 전환하지 않는다. V12 비용은 NULL로 두고 0원으로 채우지 않는다.
- DEAD 결과 Outbox는 원인을 해소한 뒤 원래 eventId·본문으로 공통 requeue한다. 새 결제·결과 이벤트로 대신 보내지 않는다.

### 승인 보류 운영 조회 (결정 114, 1부)

`GET /api/v1/admin/approval-reviews?page=0&size=20&reason=PG_INVALID_EVIDENCE`는 현재 보류 목록을, `/{approvalId}`는 현재·과거 보류 상세를 DB에서 조회하며 `Cache-Control: no-store`를 반환한다. 접수·입장·최초 준비·보류 시작 시각, 보류 경과 초, 마지막 PG 상태/관측 시각, 조사 건수를 제공한다. 토큰·암호문·보호 본문은 응답에 포함하지 않는다. 기존 행의 보류 시작 시각은 NULL이며 경과 시간도 NULL이다.

V34는 기존 행을 갱신하지 않고 관측 열과 `approval_investigation`을 추가한다. resolver의 PG inspect 결과는 inspect 후 별도 payment→approval 잠금 트랜잭션에서 저장한다. `/{approvalId}/investigations` GET/POST는 500자 이내 확인 내용을 추가하고 JWT sub와 서버 시각을 기록한다. 같은 requestId의 다른 내용은 충돌이다. 금융 상태·재시도 간격은 변경하지 않는다. V34의 승인 FK는 삭제 cascade였으나, V38이 RESTRICT로 다시 만들어 승인이나 그 결제를 지우면 조사 기록이 조용히 사라지지 않고 삭제가 거절된다(옛 행은 고치지 않음, 결정 104 이행 테스트 대상 아님).

`stove.payment.approval.review.required{reason}`는 현재 보류 건수이며 6개 알려진 사유(2부의 `PG_CANCELLED` 포함) 외 값은 OTHER로 합산한다. `PaymentApprovalsRequireReview` 경보는 ADMIN API로 안내한다. 종결은 아래 2부, 재조회 간격은 아래 "보류 재조회 간격"이고, 고객 안내는 미정이다.

승인 보류 상세·조사 이력 GET은 `reviewStartedAt != null`인 과거 보류도 현재 `status`와 함께 제공한다. 한 번도 보류되지 않은 승인은 404이고 조사 POST는 현재 REVIEW_REQUIRED에만 허용된다. `latestPgState`는 마지막 유효 관측(the last valid observation)이며 최근 조회 성공을 뜻하지 않는다. `lastInspectedAt`·`lastInspectionError`는 inspect 후 별도 잠금 트랜잭션에서 현재 lease 소유자만 기록한다. 오류는 INVALID_EVIDENCE 또는 UNAVAILABLE이고 성공 시 NULL로 지운다. 기존 예외 분류·원래 예외는 유지한다. CANCELLED 인식과 종결은 아래 2부에서 더했다.

V35는 `(status,last_error_code,received_at,id)` 색인, 조회 시각·오류 열 및 상태 집합/관측 쌍/보류 시각 CHECK를 추가한다. V34의 과거 NULL 행은 모두 제약을 충족하며 이후 값은 도메인이 같은 쌍·시간 순서로만 저장했으므로 기존 행을 수정하거나 과거 자료를 정정할 필요가 없다. 게이지는 스크레이프당 GROUP BY 한 번으로 6개 KNOWN(2부의 `PG_CANCELLED` 포함) 및 OTHER를 집계한다. PAID_WITHOUT_ADMISSION 경보는 critical, 나머지는 warning이며 알려진 과거 보류는 Alertmanager silence를 사용한다.

### 승인 보류 종결 (결정 115, 2부)

`POST /api/v1/admin/approval-reviews/{approvalId}/closure`(`PORTONE_TEST`만, ADMIN)는 `{requestId, note}`를 받아 JWT sub를 종결자로 쓴다. 순서는 작업자와 같다.

1. requestId로 최초 기록을 찾으면 같은 내용만 그대로 돌려준다(PG 미호출). 다른 내용은 409 `APPROVAL_CLOSURE_CHANGED`.
2. payment→approval 잠금에서 `REVIEW_REQUIRED`를 확인하고 작업자와 같은 lease(`leaseToken`·`leaseUntil`, 길이도 같음)를 잡는다. 다음 조회 시각(`nextAt`)은 기다리지 않지만 살아 있는 lease는 빼앗지 않는다(409 `APPROVAL_CLOSURE_BUSY`). lease를 잡는 동안 `nextAt=leaseUntil`이라 작업자 스캔에 걸리지 않는다.
3. 트랜잭션 없이 PortOne `inspect`.
4. payment→approval 잠금에서 lease 소유를 다시 확인하고 관측을 기록한다. PAID·PENDING·이미 최종 결과인 결제는 거절하고 lease를 돌려주며 원래 `nextAt`을 복원한다. 조회 실패는 `lastInspectionError`(INVALID_EVIDENCE/UNAVAILABLE)만 남기고 409다.
5. 근거가 맞으면 같은 트랜잭션에서 승인 `FAILED`(보류 사유 유지)·`approval_closure` 기록·결제 전이를 커밋한다. 접수 전 승인이고 `payment_order_lifecycle`이 만료 통지를 이미 받았으면 `EXPIRED`(이벤트 없음), 그 밖은 `FAILED`(`REVIEW_CLOSED`)와 `OrderPaymentFailed` Outbox다. 결과 이벤트 작성은 `ResolvePayment`와 같은 코드(`PaymentResultOutbox`)를 쓴다.

승인 작업 상태는 새 값을 만들지 않고 `FAILED`로 끝낸다. 터미널·활성 판정(`terminal()`, `active_guard`, `chk_payment_approval_resolution`)과 고객 승인 조회 상태 집합을 그대로 쓰고, 고객의 다음 행동은 결제·주문 상태(FAILED/EXPIRED)가 정한다. 운영 종결 여부는 상세·조사 이력의 `closure` 기록으로 구분한다.

작업자가 전액 CANCELLED를 보면 보류 전 승인은 `PG_CANCELLED`로 송신을 멈추고, 보류된 승인은 관측만 남긴다. 저절로 닫지 않는다. 전액 판정은 같은 시도·`cancelled == total`·`paid + discount == total`·성공 취소 합계 = 총액(FAILED 내역 제외, REQUESTED 등은 잘못된 근거)이다.

V36은 `chk_approval_pg_state`를 지우고 CANCELLED를 더해 다시 만든다(허용 집합만 넓어져 기존 행은 그대로 충족). V37은 새 표만 더한다. `approval_closure`의 승인 FK는 삭제 cascade가 없어(RESTRICT), 종결된 승인이나 그 결제를 지우려 하면 감사 기록이 조용히 사라지지 않고 삭제가 거절된다. 둘 다 옛 행을 고치지 않아 결정 104의 이행 테스트 대상이 아니다. 주문이 스스로 만료된 뒤 도착한 `REVIEW_CLOSED`는 Order가 만료를 유지하고 결과 식별만 남겨 DLT로 보내지 않는다. 같은 결과의 재전달만 무효과다([Order R4.8](../order/requirements.md)).

### 보류 재조회 간격 (결정 122)

`ResolvePaymentApproval`은 한 회를 끝낼 때(`PaymentApproval.finish`) 잠금 안의 현재 상태로 다음 조회 시각을 정한다. `REVIEW_REQUIRED`이면 `now + ApprovalReviewRequeryInterval.after(reviewStartedAt, now)` = `now + min(상한, max(시작값, now − reviewStartedAt))`이고, 그 밖은 `now + PAYMENT_RETRY_MS`다. 시작값·상한은 `stove.payment.review-retry-initial-ms`·`review-retry-max-ms`(`PAYMENT_REVIEW_RETRY_INITIAL_MS`·`PAYMENT_REVIEW_RETRY_MAX_MS`, 기본 5초·10분)이고 `PaymentProcessingProperties`가 범위를 검증한다. 같은 회에 막 보류된 승인은 경과가 0이라 시작값으로 시작한다. `reviewStartedAt`이 NULL인 옛 보류 행(V34 이전)은 상한을 쓰며, 새 열·이행은 없다. 종결은 `claimForClosure`로 `nextAt`을 기다리지 않고, 거절·실패하면 잡기 전의 `nextAt`(늘어난 간격 그대로)으로 돌려준다. 보류가 저절로 풀리는 분기(PAID·FAILED·READY 만료)는 바꾸지 않았고, 다음 조회가 늦어지는 만큼만 늦게 일어난다(예: 30분 보류 뒤 만료 통지를 받은 READY 건은 최대 10분 뒤 닫힌다).
