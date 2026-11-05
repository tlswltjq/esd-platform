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
| GET | `/api/v1/admin/refund-delays` | ADMIN | 장기 미확정 목록·상세·조사 이력/기록 |

- 익명 401, 역할 없음 403, 타인·없는 결제 404, 잘못된 페이지 400. checkout의 로컬 `PAID`/`FAILED`·다른 공급자는 409.
- MEMBER 조회의 구매자는 `CurrentSubject`의 JWT sub다. 본문·쿼리·헤더·`X-Member-Id`로 소유자를 바꾸지 않는다.
- 구 prepare·callback·cancel·simulator·승인 주입 업무 엔드포인트와 Gateway의 익명/CSRF·simulator 예외는 제거했다.
- 승인 접수 상태: `REQUESTED`·`ADMITTED`·`REVIEW_REQUIRED`·`FAILURE_CONFIRMED`·`SUCCEEDED`·`FAILED`·`EXPIRED`. 개별 승인 `FAILED`는 같은 주문 재결제 근거이고 전역 실패가 아니다.

## 이벤트 계약

| 방향 | 이벤트 · 토픽 | 연결과 금액 |
|---|---|---|
| 수신 | `OrderPlaced` · `stove.order.v1` (그룹 `payment-order-request`) | eventId·occurredAt·orderNo·buyerSubject·상품 스냅샷·listAmount·discountAmount·amount·currency |
| 발행 | `OrderPaymentCompleted` · `stove.payment.v1` | eventId·occurredAt·orderNo·requestEventId·paymentId·buyerSubject·amount·currency |
| 발행 | `OrderPaymentFailed` · `stove.payment.v1` | 완료와 같은 연결·금액 + 필수 reasonCode·reason |
| 수신 | `RefundExecutionRequested` · (환불 접수) | 고정 attemptId·원 PAID 연결로 PG 취소/조회 |
| 발행 | `RefundPaymentConfirmed` · `stove.payment.v1` | refund/attempt·원 실행 요청·주문·결제·구매자·금액·통화·`PG_QUERY_UNATTRIBUTED` 또는 `PG_RESPONSE`·원 PG 거래·취소 ID들. '우리 실행 성공' 이벤트가 아님 |

- Kafka 키와 결과 Outbox aggregateId는 orderNo다. requestEventId는 원래 `OrderPlaced.eventId`이며 구매자 requestId와 다르다.
- eventId·requestEventId·paymentId 최대 64자, buyerSubject 최대 100자. 금액은 정수 long·양수·KRW이고 `listAmount - discountAmount == amount`. 소수 절삭 입력은 저장 전에 거부한다.
- 결과 이벤트 ID·시각은 결과 Outbox와 같은 트랜잭션에서 최초 한 번만 확정한다. DEAD가 된 결과는 보존하며 재발행은 저장된 ID·본문을 그대로 쓴다.

## 외부 연동

- `PgGateway`(SIMULATED → 외부 모의 PG `SimulatedPgHttpGateway`, PORTONE_TEST → `AdmittedPortOnePgGateway`/`PortOnePaymentApprovalGateway`)로 승인·조회한다. 선택은 `stove.payment.pg-provider`·`PORTONE_TEST` 설정으로 조립한다.
- PortOne 조회는 상점·V2·TEST 채널·채널 키·`TOSSPAY_V2`·KRW·총액/실결제·무할인/미취소·`customData.orderNo`를 대조한다. PG 비용이 없는 응답은 미확정 null로 보존한다.
- 웹훅 공개 경로는 edge(`https://<IP>/api/v1/payments/webhooks/portone`)가 Payment로 직접 전달한다. 서명 검증과 회원 checkout의 JWT 인증을 구분한다.
- Payment 승인/조회 키, Settlement 읽기 키, PG 제어 키는 분리하며 제어 키를 업무 앱에 주지 않는다. 비밀값은 서버 설정으로만 주입하고 저장소·로그·응답에 넣지 않는다.

## 처리·복구 동작 요약

- 주기 실행은 단일 인스턴스 fixed-delay(기본 1초·20건)로 due인 `PENDING`을 `nextAttemptAt, id` 순으로 고른다. 오류·미확정은 기본 5초 뒤 재예약하고 결제별로 처리한다. `stove.payment.processing-enabled`는 기본 true, 검증에서 false→true 재시작으로 접수/재개를 나눈다.
- 승인 복구: 최초 준비부터 3시간 미만은 같은 승인 ID·멱등 키·본문으로 자동 재송신+조회, 3시간부터는 GET만. 암호화 본문은 복호화한 원래 바이트만 재사용한다.
- 환불: 고정 attemptId를 Idempotency-Key로, 원액을 amount·currentCancellableAmount에 명시. 부분 취소·명시적 잔액 불일치(409 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN`)는 자동 POST 중단·조회 유지. 검증된 전액 취소 조회 또는 검증된 취소 완료 응답으로만 완료를 확정한다.
- 중복 공급자 호출은 같은 키의 멱등 처리와 최종 행 잠금으로 중복 승인·중복 결과를 막는다. 다중 인스턴스 임대는 후속 범위다.
