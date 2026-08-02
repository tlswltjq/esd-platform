# 결함 대장

테스트로 재현에 성공한 결함만 적는다. **추측은 넣지 않는다** —
모든 항목에 실패하는 테스트가 하나씩 붙어 있고, 그 테스트가 통과하면 항목을 닫는다.

```bash
./gradlew build         # 결함 테스트 제외. 항상 초록이어야 한다
./gradlew defectTest    # 남아 있는 결함만 재현. 실패 = 아직 살아 있음
```

수정이 끝난 항목은 재현 테스트에서 `known-defect` 태그를 떼어
기본 빌드가 지키는 회귀 방어선으로 옮긴다.

작성 규칙과 태그 운용은 [testing.md](testing.md) 참고.

---

## 요약

| ID | 제목 | 영향 | 상태 |
|---|---|---|---|
| [D-001](#d-001) | 마감 후 도착한 정산 원장이 지급 대상에서 누락 | 금전 손실 | **수정됨** |
| [D-002](#d-002) | 일시 장애 한 번에 Saga 보상 환불 발동 | 금전 손실 | **수정됨** |
| [D-003](#d-003) | 브로커 장애가 길어지면 Outbox 이벤트 영구 유실 | 데이터 유실 | 미수정 |
| [D-004](#d-004) | 계약 헤더 없는 메시지가 파티션을 정지시킴 | 가용성 | 미수정 |
| [D-006](#d-006) | 롤백된 환불 트랜잭션이 PG 환불은 실행 | 금전 불일치 | **수정됨** |
| [D-007](#d-007) | 상태 불일치 보상 요청이 무한 재시도 | 가용성 | **수정됨** |
| [D-008](#d-008) | 재사용된 PG 멱등키가 다른 주문의 승인을 삼킴 | 금전 손실 | **수정됨** |
| [D-009](#d-009) | 서버 측 금액 재계산에 수량 검증 없음 | 금액 조작 | **수정됨** |
| [D-010](#d-010) | 재처리해도 소유 이벤트가 재발행되지 않음 | 복구 불가 | **수정됨** |
| [D-011](#d-011) | 변화 없는 회수가 이벤트를 재발행 | 잡음 | **수정됨** |
| [D-012](#d-012) | 지각 회수 이벤트가 새 구매 권한을 거둠 | 사용자 영향 | **수정됨** |

---

<a id="d-001"></a>
## D-001 마감 후 도착한 정산 원장이 지급 대상에서 누락

**상태** 수정됨
**영향** 금전 손실 · 자동 복구 불가
**위치** `apps/settlement/.../SettlementService.java`
**재현** `SettlementCloseTest#lateRecordShouldBeSettledOnNextClose`, `#everyClosedRecordIsReflectedInSettlement`

```
expected: 105000L
 but was:  70000L
```

### 무슨 일이

```java
List<SellerSettlement> closed = bySeller.entrySet().stream()
        .filter(entry -> sellerSettlementRepository
                .findBySellerIdAndSettlementMonth(entry.getKey(), monthKey).isEmpty())  // 이미 마감된 판매자 제외
        .map(entry -> closeSeller(...))
        .toList();

targets.forEach(SettlementRecord::close);   // ← 제외된 판매자의 원장까지 전부 close
```

집계에서 제외한 판매자의 원장에도 마감 도장을 찍는다.

```
5/20  판매 100,000  기록
5/31  마감 → 확정본 net 70,000 생성, 원장 closed
6/01  지각 도착한 5월 매출 50,000 기록
6/01  마감 재실행
      → 판매자는 '확정본 있음'으로 제외 (50,000 미반영)
      → 그런데 50,000 원장에는 closed 도장이 찍힘
```

`closed = true` 가 되면 `findBySettlementMonthAndClosedIsFalse` 에 안 걸린다.
**재실행으로도 복구되지 않는다.** 차액 35,000(net 기준)이 영구히 사라진다.

### 왜 흔한 상황인가

지각 원장은 예외가 아니다 — 이벤트 재전송, 월말 경계 지연, 수동 보정,
그리고 [D-006](#d-006) 같은 다른 결함의 후처리에서 일상적으로 생긴다.

### 수정

판매자를 건너뛰는 필터를 없애고, 이미 확정본이 있으면 **거기에 더한다**
(`SellerSettlement#accumulate`). 반영 대상과 close 대상이 항상 같아지므로
"마감됐는데 어디에도 없는 금액"이 생길 수 없다.

세금계산서는 아직 발행되지 않았고 순액이 양수가 되면 그때 발행한다.
이미 발행된 확정본의 금액이 바뀌면 수정세금계산서 대상이므로 `log.warn` 으로 남긴다.

불변식을 테스트로 고정했다 — **close 도장이 찍힌 원장은 반드시 어떤 확정본에 들어가 있다.**

---

<a id="d-002"></a>
## D-002 일시 장애 한 번에 Saga 보상 환불 발동

**상태** 수정됨
**영향** 금전 손실 · 사용자 영향
**위치** `apps/license/.../api/listener/PaymentEventListener.java`
**재현** `PaymentEventListenerTest#propagatesTransientFailureForRetry`, `#doesNotCompensateOnTransientFailure`,
`KafkaErrorHandlerConfigTest#recoversByRequestingCompensation`

### 무슨 일이

```java
try {
    licenseService.issue(...);
} catch (Exception e) {
    // 주석: "DefaultErrorHandler 의 재시도까지 소진된 뒤 도달하는 경로로 운영한다"
    licenseService.recordIssueFailure(...);   // 곧바로 보상 환불 요청
}
```

주석이 말하는 동작이 실제로는 성립하지 않는다.
스프링 카프카의 재시도는 **예외가 리스너 밖으로 나올 때만** 작동한다.
컨테이너는 리턴값을 보지 않고, 정상 리턴을 처리 성공으로 간주해 오프셋을 커밋한다
(`ack-mode: record`). 커밋되면 `DefaultErrorHandler` 가 되감을 대상이 사라진다.

즉 **재시도가 0회**다. DB 커넥션이 1초 끊겼다 붙는 흔한 상황에서 정상 결제가 환불된다.

```
결제 완료 → 지급 시도 → 커넥션풀 순간 고갈
         → @Transactional 롤백 (여기까진 정상)
         → catch → 보상 이벤트를 REQUIRES_NEW 로 커밋
         → 오프셋 커밋 → 재시도 없음
         → 결제 서버가 환불 실행
```

지급 트랜잭션이 롤백되어 "재시도 가능한 상태"로 되돌아갔는데,
정작 재시도할 기회를 없애는 구조다.

### 2차 결함

전 모듈에 커스텀 `ErrorHandler` 빈이 없다. 기본값은 `SeekUtils.DEFAULT_BACK_OFF`
= `FixedBackOff(0L, 9L)` — **간격 0ms 로 총 10회**다(spring-kafka 3.3.10 클래스 파일 확인).
예외를 밖으로 빼는 것만으로는 부족하고 지수 백오프가 함께 필요하다.

### 수정

리스너의 `try/catch` 를 걷어내 예외가 컨테이너까지 올라가게 했다.
보상 트리거는 `KafkaErrorHandlerConfig` 의 recoverer 로 옮겼다 —
recoverer 는 정의상 재시도가 전부 소진된 뒤에만 호출되므로 원래 주석의 의도가 실제로 성립한다.

2차 결함도 함께 고쳤다. 백오프를 `ExponentialBackOffWithMaxRetries(3)`
(1초 → 2초 → 4초, 총 7초)로 명시했다. 블로킹 재시도라 그동안 파티션이 멈추므로
총 대기가 `max.poll.interval.ms`(기본 5분) 안에 들어오는지도 테스트로 고정했다.

recoverer 안에서 예외가 나가면 레코드가 되감겨 무한 재전송이 되므로,
마지막 방어선답게 무엇이 터지든 로그로 끝낸다.

메커니즘 상세는 [kafka-consumer-retry.md](kafka-consumer-retry.md).

---

<a id="d-003"></a>
## D-003 브로커 장애가 길어지면 Outbox 이벤트 영구 유실

**영향** 데이터 유실 · 자동 복구 불가
**위치** `common/messaging/.../outbox/OutboxRelay.java`, `OutboxEvent#markFailed`
**재현** `OutboxRelayTest#shouldSurviveProlongedOutage`

### 무슨 일이

재시도는 폴링 주기(기본 1초)로 **고정**되고, `retryCount >= maxRetry` 면 `DEAD` 로 간다.
`DEAD` 를 `PENDING` 으로 되돌리는 전이는 도메인에도 리포지토리에도 없고,
`lockPendingBatch` 는 `PENDING` 만 집는다. **종착점이다.**

장애 감내 시간이 `maxRetry × pollInterval` 로 못박힌다 — 기본 설정에서 **약 10초**.
브로커 롤링 재시작이나 리더 선출은 그보다 오래 걸리는 일이 흔하다.
그 순간 대기 중이던 이벤트가 **한꺼번에** DEAD 가 된다.

결제 완료 이벤트가 이렇게 되면 돈은 받고 라이선스 지급·주문 확정·정산 집계가
전부 일어나지 않는다. 이벤트 유실을 막으려고 도입한 장치가 유실의 원인이 된다.

### 부수 문제

`relay()` 는 `@Transactional` 안에서 배치 전체를 `send().get()` 으로 순차 대기한다.
브로커가 느려지면 트랜잭션과 `FOR UPDATE SKIP LOCKED` 락이 `batchSize × 타임아웃` 만큼 유지된다.

### 수정 방향

지수 백오프(다음 시도 시각 컬럼 + 조회 조건 추가), DEAD 회수 경로,
DEAD 전이 시 관측 가능한 신호(메트릭·알림).

---

<a id="d-004"></a>
## D-004 계약 헤더 없는 메시지가 파티션을 정지시킴

**영향** 가용성 (poison message)
**위치** `common/event/.../kafka/EventEnvelope.java`, `common/messaging/.../inbox/ProcessedEventGuard.java`
**재현** `EventEnvelopeTest#shouldRejectRecordWithoutEventId`, `#shouldRejectRecordWithoutEventType`, `ProcessedEventGuardTest#shouldRejectNullEventId`

### 무슨 일이

`EventEnvelope.from()` 은 헤더가 없으면 `null` 을 그대로 반환한다.
`ProcessedEventGuard` 도 `null` 을 걸러내지 않고 조회 결과가 없다는 이유로 처리를 **허용**한다.

실패는 `event_id NOT NULL` 제약에 걸리는 커밋 시점까지 미뤄진다. 그 결과:

1. 비즈니스 로직이 이미 실행된 뒤 롤백된다
2. 오프셋이 커밋되지 않아 같은 레코드가 무한 재전송된다
3. **파티션 전체가 그 자리에서 멈춘다** — 뒤의 정상 이벤트도 전부 막힌다

`eventType` 이 `null` 인 경우는 더 조용하다. 모든 `isType()` 이 false 라
리스너가 아무 분기도 타지 않고 정상 리턴한다. **로그에도 흔적이 남지 않는다.**

### 수정 방향

봉투 생성 시점에 계약 위반을 거부한다. 판단 불가능한 입력은 부수효과 이전에 끊는다.
운영에서는 DLQ 로 치워 파티션이 계속 흐르게 한다.

---

<a id="d-006"></a>
## D-006 롤백된 환불 트랜잭션이 PG 환불은 실행

**상태** 수정됨
**영향** 금전 불일치 · 이중 환불 위험
**위치** `apps/payment/.../core/service/PaymentService.java`
**재현** `PaymentCancelTest#brokenFinalizationLeavesObservableState`, `#interruptedCancelCompletesOnRetry`

### 무슨 일이

```java
payment.cancel(reason);                              // 되돌릴 수 있음
pgClient.cancel(payment.getPgTxId(), amount, reason); // 되돌릴 수 없음 ← 트랜잭션 안
outboxRecorder.record(...);                          // 되돌릴 수 있음
```

3번이나 커밋이 실패하면 DB 는 `PAID` 로 롤백되는데 **PG 환불은 이미 나갔다.**
장부와 실제 돈이 어긋나고, 재시도하면 같은 결제를 두 번 환불한다.

### 원칙

되돌릴 수 없는 외부 호출(결제, 환불, 메일 발송)은 DB 트랜잭션 밖에 둔다.

### 수정

취소를 세 걸음으로 나누고, 조율은 `api/application/RefundFacade` 가 맡는다
(order 의 `PlaceOrderFacade` 와 같은 이유·같은 자리다).

```
1) 의도 기록 커밋   PAID → CANCELING
2) PG 환불          트랜잭션 밖
3) 확정 커밋        CANCELING → CANCELED + PaymentCancelled 적재
```

어느 걸음에서 멈추든 결과가 관측 가능하다. 1번 전이면 아무 일도 없었고,
2·3번에서 멈추면 `CANCELING` 으로 남아 재시도 대상이 된다 —
수정 전처럼 "돈은 나갔는데 장부는 PAID" 로 조용히 묻히지 않는다.

재시도가 이중 환불이 되지 않으려면 PG 취소가 `pgTxId` 기준 멱등이어야 한다.
암묵적이던 이 전제를 `PgClient#cancel` 의 계약으로 명시했다.

---

<a id="d-007"></a>
## D-007 상태 불일치 보상 요청이 무한 재시도

**상태** 수정됨
**영향** 가용성
**위치** `apps/payment/.../core/service/PaymentService.java`
**재현** `PaymentCancelTest#compensationOnInconsistentStateDoesNotThrow`, `#compensationOnInconsistentStateChangesNothing`

### 무슨 일이

`compensate()` 는 멱등 가드를 마킹한 뒤 `cancel()` 을 부른다.
결제가 `PAID` 가 아니면 `Payment.cancel()` 이 `CONFLICT` 를 던지고,
**가드 마킹까지 함께 롤백**된다. 오프셋도 커밋되지 않는다.

→ 같은 이벤트가 영원히 재전송되고 파티션이 멈춘다.
결제 상태는 스스로 `PAID` 가 될 수 없으므로 재시도로는 절대 풀리지 않는다.

### 수정

`Payment#cancelable()` 로 **예외 대신 값으로** 묻는다.
취소 가능한 상태가 아니면 `log.error` 로 남기고 아무것도 하지 않은 채 끝낸다 —
가드 마킹이 커밋되므로 소비가 진행되고 파티션이 흐른다.

정상 흐름에서 생길 수 없는 상태 조합은 재시도 대상이 아니다.
결제가 스스로 `PAID` 가 될 수 없으므로 재시도로는 절대 풀리지 않는다.

---

<a id="d-008"></a>
## D-008 재사용된 PG 멱등키가 다른 주문의 승인을 삼킴

**상태** 수정됨
**영향** 금전 손실 · 관측 불가
**위치** `apps/payment/.../core/service/PaymentService.java`
**재현** `PaymentCallbackLookupTest#reusedIdempotencyKeyMustNotHijackAnotherOrder`,
`#secondDistinctApprovalIsRejectedLoudly`, `PaymentTest#secondApprovalWithDifferentKeyIsRejected`

### 무슨 일이

```java
Payment payment = paymentRepository.findByIdempotencyKey(approval.idempotencyKey())
        .orElseGet(() -> findPayment(approval.orderNo()));
```

멱등키를 **주문번호보다 먼저** 본다. 멱등키는 PG 가 만드는 값이라
우리 쪽에서 유일성을 강제할 수 없다.

```
주문 X 승인 (키 K)
주문 Y 콜백 (PG 가 키 K 재사용)
  → 키 K 조회 → 주문 X 의 결제가 나옴
  → 이미 PAID → '중복 콜백'으로 판정 → 조용히 무시
  → 주문 Y 는 PG 에서 승인됐는데 장부에는 PENDING
```

라이선스 미지급, 주문 미확정, 정산 누락이 한꺼번에 발생하고
**예외도 경고 로그도 남지 않는다.**

### 수정

주문번호로 찾는다. 주문번호는 우리가 만든 값이라 신뢰할 수 있다.
중복 판정은 엔티티가 **저장된 멱등키와 비교**해서 한다 — 같으면 재전송(무시),
다르면 PG 오류이거나 위·변조이므로 예외로 드러낸다. 조용히 넘기면 사고가 관측되지 않는다.

멱등키의 전역 유니크 제약은 걷어냈다(`V2__scope_idempotency_key.sql`).
외부가 만드는 값에 전역 유일성을 기대한 것이 결함의 뿌리였다.
그 제약이 겸하던 **동시 중복 콜백 방어**는 `SELECT ... FOR UPDATE`
(`PaymentRepository#findByOrderNoForUpdate`)로 옮겼다 — 우리가 통제할 수 있는 수단이다.

---

<a id="d-009"></a>
## D-009 서버 측 금액 재계산에 수량 검증 없음

**상태** 수정됨
**영향** 금액 조작
**위치** `apps/catalog/.../core/domain/QuoteItem.java`
**재현** `ProductQuoteTest#nonPositiveQuantityIsRejected`, `#negativeQuantityCannotReduceTotal`

### 무슨 일이

수량 검증(`@Min(1)`)이 HTTP DTO `QuoteRequest.Item` 에만 있다.
core 진입점 `quote(List<QuoteItem>)` 에는 없다.

음수 수량이 통과하면 `lineAmount()` 가 음수가 되어 총액을 임의로 낮출 수 있다.

```
100,000원 게임 × 1 + 10,000원 게임 × (-9) = 10,000원
```

그리고 이 총액이 **게이트 2·3·4 의 기준값**이 된다.
PG 사전등록도, 콜백 금액 대조도, 전부 조작된 금액 위에서 정상 통과한다.
검증 게이트를 4겹으로 쌓아도 첫 단추가 틀리면 나머지는 의미가 없다.

### 왜 지금은 안 터졌나

유일한 호출자가 컨트롤러라 DTO 검증에 막혔다.
어댑터가 하나 더 생기는 순간(배치, gRPC, 내부 호출) 무방비가 된다.

### 수정

`QuoteItem` 의 compact 생성자에서 검증한다. 값 객체가 애초에 잘못된 상태로
**존재할 수 없게** 만드는 쪽이, 사용하는 쪽마다 검사를 반복하는 것보다 확실하다.
DTO 의 `@Min(1)` 은 그대로 둔다 — 어댑터 단계에서 400 으로 끊는 편이 응답이 낫다.

---

<a id="d-010"></a>
## D-010 재처리해도 소유 이벤트가 재발행되지 않음

**상태** 수정됨
**영향** 하위 서비스 복구 불가
**위치** `apps/license/.../core/service/LicenseService.java`
**재현** `LicenseIssueEventTest#replayShouldRepublishOwnershipEvent`, `#revokedOrderDoesNotRepublishOwnership`

### 무슨 일이

새로 지급된 라이선스가 없으면(`issued.isEmpty()`) `LicenseIssued` 를 발행하지 않는다.

download 는 자기 DB 없이 이 이벤트만으로 권한 사본을 만든다.
download 가 최초 이벤트를 놓쳤다면([D-003](#d-003) 이나 컨슈머 장애로),
**운영에서 이벤트를 재처리해도 복구할 방법이 없다.**

사용자는 라이브러리에 게임이 보이는데 다운로드만 안 되는 상태에 갇힌다.

### 수정

`LicenseIssued` 가 '변화'가 아니라 **현재 소유 상태**를 싣도록 바꿨다.
새로 지급된 것이 없어도 그 주문의 ACTIVE 라이선스 전체를 실어 재발행한다.
수신 측(download)은 문서 ID 고정 upsert 라 같은 상태를 여러 번 받아도 안전하다.

단, 이미 전부 회수된 주문에는 발행하지 않는다 —
소유하지 않은 상태를 '지급'으로 알릴 수는 없다.

---

<a id="d-011"></a>
## D-011 변화 없는 회수가 이벤트를 재발행

**상태** 수정됨
**영향** 잡음 · 하위 서비스 불필요 처리
**위치** `apps/license/.../core/service/LicenseService.java`
**재현** `LicenseIssueEventTest#repeatedRevokeShouldNotRepublish`

라이선스 목록이 비어 있지 않다는 이유만으로 `LicenseRevoked` 를 무조건 발행한다.
이미 전부 `REVOKED` 라 실제 상태 변화는 없는데도 이벤트가 나간다.

지급 경로는 변화 여부를 따지고(`issued.isEmpty()`) 회수 경로는 따지지 않는 **비대칭**이었다.

### 수정

`License#revoke` 가 실제 전이 여부를 boolean 으로 돌려주고,
하나라도 바뀌었을 때만 `LicenseRevoked` 를 발행한다.

[D-010](#d-010) 과 함께 정책을 맞췄다 — **지급은 상태를 알리고, 회수는 변화를 알린다.**
지급은 하위 서비스의 복구 수단이어야 하고, 회수는 이미 거둔 권한을 또 거둘 이유가 없기 때문이다.

---

<a id="d-012"></a>
## D-012 지각 회수 이벤트가 새 구매 권한을 거둠

**상태** 수정됨
**영향** 사용자 영향 · 원인 추적 어려움
**위치** `apps/download/.../core/service/DownloadService.java`
**재현** `DownloadEntitlementTest#staleRevokeMustNotAffectNewEntitlement`, `#revokeOfOwningOrderStillWorks`

### 무슨 일이

권한 문서 키가 `memberId:productId` 뿐이라 **어느 주문의 회수인지 구분할 수 없다.**

```
주문1 구매 → 환불 → 주문2 로 재구매 (권한 활성)
주문1 의 LicenseRevoked 가 지각 도착/재전송
  → 주문2 로 얻은 권한이 회수됨
```

license 서비스에는 `ACTIVE` 로 남아 있어 사용자 문의가 들어와도 원인을 찾기 어렵다.

### 수정

`revoke` 가 `orderNo` 를 받아 저장된 권한의 주문번호와 대조한다(`Entitlement#belongsTo`).
다른 주문의 권한이면 건드리지 않고 로그만 남긴다 — 조용히 지나가면 나중에 추적할 수 없다.

---

## 닫힌 항목

없음.

### 결번

- **D-005** — 작성 중 철회. "DEAD 전이를 관측할 수 있어야 한다"는 항목이었으나
  [D-003](#d-003) 의 일부이고 단독 재현 테스트가 구조 검사에 가까워 합쳤다.
  번호는 재사용하지 않는다(기존 커밋·주석의 참조가 흔들린다).
