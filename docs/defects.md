# 결함 대장

테스트로 재현에 성공한 결함만 적는다. **추측은 넣지 않는다** —
모든 항목에 실패하는 테스트가 하나씩 붙어 있고, 그 테스트가 통과하면 항목을 닫는다.

```bash
./gradlew build         # 결함 테스트 제외하고 전건 통과
./gradlew defectTest    # 남아 있는 결함만 재현. 현재 0건
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
| [D-003](#d-003) | 브로커 장애가 길어지면 Outbox 이벤트 영구 유실 | 데이터 유실 | **수정됨** |
| [D-004](#d-004) | 계약 헤더 없는 메시지의 실패가 트랜잭션 한복판으로 미뤄짐 | 가용성 | **수정됨** |
| [D-006](#d-006) | 롤백된 환불 트랜잭션이 PG 환불은 실행 | 금전 불일치 | **수정됨** |
| [D-007](#d-007) | 상태 불일치 보상 요청이 무한 재시도 | 가용성 | **수정됨** |
| [D-008](#d-008) | 재사용된 PG 멱등키가 다른 주문의 승인을 삼킴 | 금전 손실 | **수정됨** |
| [D-009](#d-009) | 서버 측 금액 재계산에 수량 검증 없음 | 금액 조작 | **수정됨** |
| [D-010](#d-010) | 재처리해도 소유 이벤트가 재발행되지 않음 | 복구 불가 | **수정됨** |
| [D-011](#d-011) | 변화 없는 회수가 이벤트를 재발행 | 잡음 | **수정됨** |
| [D-012](#d-012) | 지각 회수 이벤트가 새 구매 권한을 거둠 | 사용자 영향 | **수정됨** |
| [D-013](#d-013) | 발행 실패한 이벤트를 같은 애그리거트의 뒤 이벤트가 추월 | 순서 역전 | **수정됨** |
| [D-014](#d-014) | 백오프로 빠진 앞 이벤트를 다음 회차에서 뒤 이벤트가 추월 | 순서 역전 | **수정됨** |
| [D-015](#d-015) | 형식이 깨진 요청이 400 이 아니라 500 으로 응답 | 오분류·알람 잡음 | **수정됨** |
| [D-016](#d-016) | 진행 중인 심의에 재신청이 오면 컨슈머가 멈춤 | 가용성 | **수정됨** |
| [D-017](#d-017) | 지각 심의 결과가 승인된 프로젝트를 강등 | 사용자 영향 | **수정됨** |
| [D-018](#d-018) | 결제 없는 주문의 보상 요청이 무한 재배달 | 가용성 | **수정됨** |
| [D-019](#d-019) | order 의 수량 검증이 어댑터에만 존재 | 금액 조작 | **수정됨** |
| [D-020](#d-020) | 범위를 벗어난 페이지 파라미터가 400 이 아니라 500 | 오분류·알람 잡음 | **수정됨** |
| [D-021](#d-021) | 스텁 격리 규칙이 실제 스텁을 한 번도 검사하지 않음 | 규칙의 공허 통과 | **수정됨** |
| [D-022](#d-022) | 롤백된 마감 트랜잭션이 세금계산서는 발행 | 금전 불일치 | **수정됨** |

D-016 ~ D-019 는 **서비스 계층에 테스트가 없던 모듈에 테스트를 붙이면서** 나왔다.
넷 중 셋이 컨슈머 경로의 예외 처리 문제이고, 하나는 이미 고친 결함(D-009)이
같은 이름의 다른 값 객체에 그대로 남아 있던 경우다.

D-020 ~ D-021 은 [testing.md](testing.md) 6절이 "위험"으로 표시해 둔 공백을 메우면서 나왔다.
**둘 다 이미 있던 방어선이 실제로는 작동하지 않고 있던 경우다** —
D-015 가 닫았다고 본 부류에 경계값이 빠져 있었고, ArchUnit 규칙 하나는 술어 결합 순서 때문에
대상 자체를 잘못 고르고 있었다. 통과하는 테스트와 잡아내는 테스트의 차이가
결함 목록에 그대로 나타난 사례다.

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

**상태** 수정됨
**영향** 데이터 유실 · 자동 복구 불가
**위치** `common/messaging/.../outbox/OutboxEvent.java`, `OutboxEventRepository`
**재현** `OutboxEventTest#toleranceCoversRealisticOutage`, `#deadCanBeRequeued`,
`OutboxBackOffQueryTest#backedOffEventIsSkippedUntilItsTime`

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

### 수정

**재시도 예산을 횟수가 아니라 시간으로 잡는다.** `next_attempt_at` 컬럼을 두고
실패할 때마다 1초 → 2초 → 4초 …(상한 5분)로 미룬다. 릴레이 조회에
`next_attempt_at IS NULL OR next_attempt_at <= NOW(6)` 조건이 붙었다.
기본 설정(max-retry 10)에서 감내 시간이 10초에서 **약 8분 30초**로 늘어난다.

`OutboxEvent#requeue()` 로 DEAD 를 발행 대기로 되돌릴 수 있다.
`findByStatusOrderByIdAsc(DEAD)` 로 대상을 조회한다.

시간 조건은 네이티브 SQL 안에 있어 대역으로는 검증되지 않는다.
실제 MySQL 을 띄우는 `OutboxBackOffQueryTest` 가 그 부분을 맡는다 —
조건이 빠지면 백오프를 아무리 계산해도 릴레이가 곧바로 다시 집어간다.

마이그레이션은 outbox 를 쓰는 7개 서비스에 동일하게 들어갔다
(`V*__outbox_retry_backoff.sql`).

### 남은 부분

`relay()` 가 `@Transactional` 안에서 배치 전체를 `send().get()` 으로 순차 대기하는 구조는
그대로다. 브로커가 느려지면 트랜잭션과 `FOR UPDATE SKIP LOCKED` 락이
`batchSize × 타임아웃` 만큼 유지된다. 이건 처리량 문제라 부하 테스트로 정량화한 뒤 손대는 편이 낫다.

---

<a id="d-004"></a>
## D-004 계약 헤더 없는 메시지의 실패가 트랜잭션 한복판으로 미뤄짐

**상태** 수정됨
**영향** 가용성 (poison message)
**위치** `common/event/.../kafka/EventEnvelope.java`, `common/messaging/.../inbox/ProcessedEventGuard.java`
**재현** `EventEnvelopeTest#rejectsRecordWithoutEventId`, `#rejectsRecordWithoutEventType`, `ProcessedEventGuardTest#rejectsNullEventId`

### 무슨 일이

`EventEnvelope.from()` 은 헤더가 없으면 `null` 을 그대로 반환한다.
`ProcessedEventGuard` 도 `null` 을 걸러내지 않고 조회 결과가 없다는 이유로 처리를 **허용**한다.

실패는 `event_id NOT NULL` 제약에 걸리는 커밋 시점까지 미뤄진다. 그 결과
**비즈니스 로직이 이미 실행된 뒤 롤백되고**, 오프셋이 커밋되지 않아 같은 레코드가 재전송된다.
그 뒤 몇 번 재시도하고 어떻게 끝나는지는 컨슈머의 에러 핸들러 정책에 달렸는데,
**어느 서비스에도 그 정책이 정의되어 있지 않았다** — 조용히 버려지든 계속 되돌아오든
결제 경로 이벤트에는 둘 다 받아들일 수 없다.

`eventType` 이 `null` 인 경우는 더 조용하다. 모든 `isType()` 이 false 라
리스너가 아무 분기도 타지 않고 정상 리턴한다. **로그에도 흔적이 남지 않는다.**

### 수정

봉투 생성 시점에 계약 위반을 거부한다(`IllegalStateException`).
멱등 가드도 빈 `eventId` 를 거부한다 — 판단할 수 없는 입력에 "처음 본 이벤트"라고
답하면 부수효과가 그대로 일어난다. 예외 메시지에 topic/partition/offset/key 를 실어
어느 메시지인지 찾을 수 있게 했다.

그리고 **재시도 정책을 명시했다.** `common:messaging` 이 지수 백오프
(`ConsumerRetryPolicy`, 1→2→4초)를 단 기본 `DefaultErrorHandler` 를 자동 구성한다.
재시도가 소진되면 ERROR 로 남기고 건너뛴다 — 계약 위반 메시지 한 건이 파티션을 막지 않되,
유실이 아니라 **관측 가능한 포기**가 되도록. 도메인 처리가 필요한 서비스는
자기 `CommonErrorHandler` 빈으로 대신한다(license 의 보상 recoverer).

`download` 는 `common:messaging` 을 쓰지 않아 이 기본값이 적용되지 않는다.
Outbox/Inbox 가 필요 없는 모듈이라 의존을 늘리지 않았고, 대신 여기 남긴다.

> **그 뒤** — 위 두 문단은 수정 당시의 모습이다. [decisions.md](decisions.md) 19번이
> 수신 측 정책을 `common:kafka` 로 갈라내면서 둘 다 바뀌었다.
> `ConsumerRetryPolicy` 는 `common/kafka/` 로 옮겼고 그 모듈이 JPA 를 모르게 되어
> **store·download 를 포함한 9개 서비스 전부**가 같은 정책을 갖는다 — 여기 남겨 둔 예외는 닫혔다.
> 재시도가 소진돼도 건너뛰지 않고 `<원본토픽>.DLT` 로 보낸다.

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

<a id="d-013"></a>
## D-013 발행 실패한 이벤트를 같은 애그리거트의 뒤 이벤트가 추월

**상태** 수정됨 (D-003 수정 중 발견)
**영향** 순서 역전
**위치** `common/messaging/.../outbox/OutboxRelay.java`
**재현** `OutboxRelayTest#failureHoldsLaterEventsOfSameAggregate`,
`#failureOfOneKeyDoesNotBlockOtherKeys`, `#sameKeyIsPublishedInOrder`

### 깨지는 지점은 '수신'이 아니라 '발행'이다

```
[적재]  outbox 테이블에 id 순서대로 쌓인다          순서 맞음
[발행]  릴레이가 Kafka 로 넣는다                    ← 여기서 뒤집힌다
[전달]  같은 키 → 같은 파티션 → 넣은 순서대로        Kafka 는 약속을 지킨다
[수신]  컨슈머가 받은 순서대로 처리한다              받은 게 이미 뒤집혀 있다
```

릴레이는 배치를 id 순으로 돌면서 **실패한 건을 건너뛰고 계속 발행한다.**
같은 `partitionKey` 의 뒤 이벤트가 앞 이벤트를 추월한다.

README 는 "메시지 키는 주문번호/상품코드 — 같은 애그리거트의 순서가 보장된다"고 말한다.
Kafka 파티션 안에서는 맞지만 **파티션에 넣는 순서**가 이미 어긋나 있다.

### 컨슈머가 순서를 전제하고 있다

`LicenseService.revoke()` 는 회수 대상이 없으면 그냥 리턴한다.
순서가 보장된다는 전제 아래에서는 "이미 회수됐거나 지급 대상이 아니었다"는 뜻이라 맞는 코드다.

순서가 깨지면 **이 방어 코드가 사고를 조용히 삼킨다.** 예외도 경고 로그도 남지 않는다.
`SettlementService.recordRefund()` 도 같다 — 경고 한 줄 남기고 멱등 가드에 '처리 완료'를 찍는다.

### 언제 터지나

조건은 하나다. **같은 애그리거트의 이벤트 2건이 같은 배치에 `PENDING` 으로 있고, 앞의 것이 발행에 실패한다.**

이벤트가 서비스를 한 번 건널 때마다 폴링 주기(1초)가 든다. 따라서
"A 발행 → 컨슈머 처리 → B 적재" 는 같은 배치에 못 들어온다.
같은 배치에 들어오려면 **한 서비스가 Kafka 왕복 없이 연달아 2건을 적재**해야 한다.

**시나리오 A — catalog 상품 상태 (평시 최다)**

`ProductCommandService` 는 상태 변경마다 `ProductChanged` 를 적재하고 키는 전부 `productCode` 다.
심의 승인(`APPROVED`) 직후 노출 전환(`openSale` → `ON_SALE`)이 1초 안에 들어가면 두 건이 한 배치에 있다.

앞의 것이 실패하면 store 색인이 `ON_SALE` 로 갱신됐다가 **1초 뒤 `APPROVED` 로 덮어써진다.**
`StoreService.indexProduct()` 가 버전 검사 없는 통짜 `save()` 라 나중에 온 옛 상태가 이긴다.
`search()` 는 `ON_SALE` 만 조회하므로 **판매 중인 상품이 검색에서 사라진다.**

**시나리오 B — 릴레이가 밀렸다가 복구할 때 (최다 위험)**

브로커가 다운됐거나 릴레이가 멈춰 있었으면 outbox 에 이벤트가 쌓인다.
복구되면 한 배치에 같은 애그리거트 이벤트가 여러 건 들어오고,
**복구 직후는 발행 실패가 가장 잦은 시점**이다(브로커 안정화 중, 파티션 리더 재선출).

즉 **이 결함은 시스템이 장애에서 회복하는 순간에 가장 잘 터진다.**
평소엔 조용하다가 사고 수습 중에 두 번째 사고를 만든다.

이때 payment 의 `PaymentCompleted`/`PaymentCancelled` 쌍이 같은 배치에 들어오면:

```
license: revoke 먼저 수신  → 라이선스 없음 → 조용히 no-op
license: issue 나중에 수신 → 라이선스 발급

→ 환불은 됐는데 라이선스가 살아있다
```

settlement 도 같은 방식으로 **환불된 주문의 매출이 원장에 남는다.**

### Saga 보상 경로는 해당되지 않는다

보상이 돌려면 `PaymentCompleted` 가 **이미 발행됐어야** 한다 —
그래야 license 가 받아서 지급을 시도하고 실패한다.
발행된 이벤트는 `SENT` 라 다음 배치에 안 잡힌다.
따라서 payment 의 쌍은 **보상 경로에서는 추월할 수 없다.**
같은 배치에 놓이려면 시나리오 B 처럼 릴레이가 밀려 있어야 한다.

### D-003 과의 관계

백오프가 재시도 간격을 늘리면서 추월 창이 **1초에서 최대 5분으로** 넓어졌다.
백오프는 필요한 수정이었고(없으면 10초 장애에 이벤트가 영구 유실),
이 결함은 그 이전부터 있었다. 백오프가 만든 게 아니라 넓혔다.

### 수정

배치를 `partitionKey` 로 묶고 **웨이브 단위로 발행한다.**
웨이브 n 은 살아 있는 각 키의 n 번째 이벤트이며, 통째로 전송에 걸어두고 한 번에 기다린 뒤
**성공한 키만** 다음 웨이브로 넘어간다.

```
웨이브 1: [키A-1, 키B-1, 키C-1]  → 동시 발행, 1회 대기
웨이브 2: [키A-2,        키C-2]  → 1번이 성공한 키만
```

키가 다른 이벤트는 계속 나가므로 막히는 범위가 "전체"에서 "그 애그리거트 하나"로 줄어든다.
대가는 지연이다 — 앞 이벤트가 재시도를 기다리는 동안 뒤 이벤트도 함께 기다린다.
**순서 틀린 이벤트를 빨리 보내는 것보다 늦게라도 순서대로 보내는 편이 낫다.**

이 묶음이 처리량 개선의 단위이기도 했다. 순서를 지켜야 할 대상이 명확해지자
나머지는 동시에 보낼 수 있게 되어, 같은 수정으로 배치 직렬 대기가 사라졌다 —
릴레이 처리량이 138 → 480 events/s 로 올랐다. [performance.md](performance.md) 참고.

**딸려오는 제약** — 이 보장은 한 릴레이가 그 키의 이벤트를 전부 들고 있을 때만 성립한다.
`FOR UPDATE SKIP LOCKED` 는 파티션 키를 모르므로 **릴레이를 다중화하면 조용히 무효가 된다.**
순서 보장이 프로듀서·발행자·컨슈머 세 층에 어떻게 나뉘는지와 함께
[event-ordering.md](event-ordering.md) 에 정리했다.

---

<a id="d-014"></a>
## D-014 백오프로 빠진 앞 이벤트를 다음 회차에서 뒤 이벤트가 추월

**상태** 수정됨 (D-013 수정의 사각지대)
**영향** 순서 역전
**위치** `common/messaging/.../outbox/OutboxRelay.java`
**재현** `OutboxRelayTest#backOffDoesNotLetLaterEventOvertakeAcrossCycles`

### D-013 을 고쳤는데 왜 또 뒤집히나

[D-013](#d-013) 의 키 웨이브는 **한 배치 안**의 순서를 지킨다.
앞 이벤트가 실패하면 같은 키의 뒤 이벤트를 이번 회차에서 보류한다.

그런데 보류는 그 이벤트를 **건드리지 않는 것**이라, 두 건의 `next_attempt_at` 이 갈라진다.

```
앞 이벤트  실패 → next_attempt_at = 실패시각 + 백오프   → 조회에서 빠진다
뒤 이벤트  보류 → next_attempt_at = NULL               → 조회에 계속 잡힌다
```

다음 폴링에서 **뒤 이벤트만 배치에 들어온다.** 같은 배치에 없으므로 웨이브 구조가
볼 수 있는 범위 밖이고, 그대로 발행된다. 한 배치 안의 순서를 아무리 지켜도
**애초에 같이 오지 않으면 소용이 없다.**

재시도 간격은 1초 → 2초 → 4초…(상한 5분)로 벌어지는 반면 뒤 이벤트는 계속 즉시 대상이라,
회차가 갈수록 창이 넓어진다. D-003 의 백오프가 D-013 의 수정 범위 밖에 새 창을 만든 셈이다.

### 왜 안 보였나

테스트가 층으로 갈려 있었다.

| 검증 대상 | 어디서 | 무엇을 못 보나 |
|---|---|---|
| 키 단위 발행 순서 | `OutboxRelayTest` (대역) | `next_attempt_at` 필터를 모사하지 않았다 |
| 백오프 조회 조건 | `OutboxBackOffQueryTest` (실 MySQL) | 파티션 키를 보지 않는다 |

각자는 맞게 검증하는데 **둘이 만나는 지점을 아무도 안 봤다.**
대역이 실물과 다른 부분을 주석으로만 남겨두면 그 차이가 곧 사각이 된다.
스텁에 시간 필터를 넣자 결함이 바로 재현됐다.

### 수정

발행에 실패하면 **같은 키의 뒤 이벤트에 앞의 `next_attempt_at` 을 전파한다**
(`OutboxRelay#holdRemainder` → `OutboxEvent#holdUntil`).
보류의 의미를 "이번 회차에 안 보낸다"에서 "앞의 것이 재시도될 때까지 조회에도 안 잡힌다"로 넓힌 것이다.

`retryCount` 는 늘리지 않는다. 이 이벤트는 실패한 것이 아니라 순서 때문에 양보한 것이라,
시도해 보지도 않고 예산을 소진해 DEAD 가 되면 안 된다.

**앞 이벤트가 DEAD 가 되면 키를 푼다.** 순서 보장을 포기하는 유일한 지점인데,
대안인 "그 키의 영구 정지"가 더 나쁘기 때문이다.
DEAD 알람이 이 설계의 짝이라는 점은 [event-ordering.md](event-ordering.md) 6절 A-2 에 적혀 있다.

---

<a id="d-015"></a>
## D-015 형식이 깨진 요청이 400 이 아니라 500 으로 응답

**상태** 수정됨 (컨트롤러 테스트를 붙이다 발견)
**영향** 오분류 · 알람 잡음
**위치** `common/web/.../GlobalExceptionHandler.java`
**재현** `StudioControllerTest#missingRequiredHeaderIsClientError`

### 무슨 일이

`GlobalExceptionHandler` 는 `BusinessException` · `MethodArgumentNotValidException` ·
`ConstraintViolationException` 만 개별 처리하고 나머지는 전부 `Exception` 핸들러로 흘렸다.

그래서 **요청 자체가 형식을 못 갖춘 경우**가 전부 500 이 됐다.

| 상황 | 예외 | 기존 응답 |
|---|---|---|
| 필수 헤더 누락 | `MissingRequestHeaderException` | 500 |
| 필수 쿼리 파라미터 누락 | `MissingServletRequestParameterException` | 500 |
| 경로 변수 타입 불일치 (`/orders/abc`) | `MethodArgumentTypeMismatchException` | 500 |
| 깨진 JSON 본문 | `HttpMessageNotReadableException` | 500 |

### 왜 문제인가

**클라이언트 잘못을 서버 장애로 표시하는 것이다.** 결과가 둘로 갈린다.

- 클라이언트가 **재시도한다.** 5xx 는 "다시 걸어 보라"는 뜻이라 재시도 정책이 붙어 있다.
  형식이 틀린 요청은 몇 번을 보내도 같으므로 그대로 부하만 된다
- **5xx 알람이 울린다.** 잘못된 요청 하나마다 `log.error` + 스택 트레이스가 쌓여
  실제 장애의 신호가 묻힌다

발견 경위가 이 결함의 성격을 보여준다 — 컨트롤러 테스트를 처음 붙이면서
헤더를 빠뜨린 요청을 보냈더니 500 이 돌아왔다. **입구 층에 테스트가 없어서
아무도 이 응답을 본 적이 없었다.**

### 수정

네 예외를 묶어 400 으로 응답하는 핸들러를 추가했다.
`log.warn` 으로 낮추고 스택 트레이스도 남기지 않는다 —
이 부류는 잘못된 요청마다 한 건씩 쌓이므로 5xx 로그의 신호 대 잡음비를 떨어뜨린다.

`common:web` 에 있으므로 9개 서비스에 한 번에 적용된다.

---

<a id="d-016"></a>
## D-016 진행 중인 심의에 재신청이 오면 컨슈머가 멈춤

**상태** 수정됨 (서비스 계층 테스트를 붙이다 발견)
**영향** 가용성 · 자동 복구 불가
**위치** `apps/review/.../core/service/ReviewService.java`
**재현** `ReviewReceiveTest#resubmitOnLiveRequestMustNotStallTheConsumer`,
`#resubmitOnApprovedRequestMustNotStallTheConsumer`

### 무슨 일이

`receive()` 는 같은 `productCode` 의 레코드가 있으면 **상태를 보지 않고** `reopen()` 을 불렀다.

```java
reviewRepository.findByProductCode(event.productCode())
        .map(existing -> {
            existing.reopen(event.title(), event.price()); // 반려 후 재신청
            return existing;
        })
```

주석이 말하는 "반려 후"가 코드에는 없다. `reopen()` 은 `transitTo(REQUESTED)` 인데
`IN_REVIEW → REQUESTED` 와 `APPROVED → *` 는 둘 다 금지 전이다(`ReviewStatus`).
그래서 `BusinessException` 이 리스너 밖으로 나가고, 오프셋이 커밋되지 않는다.

→ studio 토픽의 해당 파티션이 무한 재시도에 빠진다.
심의 상태는 재시도로 바뀌지 않으므로 **뒤에 줄 선 다른 게임의 심의까지 전부 멈춘다.**

### 왜 멱등 가드가 못 막았나

가드는 **같은 `eventId`** 만 거른다. 이건 중복 전달이 아니라
새 `eventId` 를 단 **정상적인 두 번째 신청**이다 — studio 에서 재신청하거나,
운영자가 이벤트를 수동 재발행하면 그대로 도달한다.

### 수정

재신청은 `REJECTED` 인 건에만 적용한다. 그 외 상태에서 접수가 또 들어오면
`log.warn` 으로 남기고 넘어간다.

**컨슈머 경로에서는 거절도 예외로 하지 않는다.** 이 선택 기준은 D-007·D-018 과 같다 —
재시도로 풀리지 않는 상태에 예외를 던지면 파티션이 멈추는 것으로 끝난다.

---

<a id="d-017"></a>
## D-017 지각 심의 결과가 승인된 프로젝트를 강등

**상태** 수정됨 (도메인 테스트를 붙이다 발견)
**영향** 사용자 영향 · 서비스 간 상태 불일치
**위치** `apps/studio/.../core/domain/GameProject.java`
**재현** `GameProjectTest#lateRejectionMustNotDemoteApprovedProject`,
`#approvalWithoutSubmissionIsIgnored`

### 무슨 일이

`submit()` 에는 상태 가드가 있는데 `approve()` · `reject()` 에는 **하나도 없었다.**

```java
public void reject(String reason) {
    this.status = ProjectStatus.REJECTED;   // 어느 상태에서 부르든 통과
    this.rejectReason = reason;
}
```

같은 엔티티 안에서 방어 수준이 갈린 이유는 호출자가 다르기 때문이다 —
`submit()` 은 사람이, 나머지 둘은 컨슈머가 부른다. **이벤트 경로만 무방비였다.**

지각 `ReviewRejected` 가 `APPROVED` 프로젝트에 도달하면 `REJECTED` 로 강등된다.
catalog 는 상품을 계속 판매하는데 스튜디오 화면에만 반려로 보이고,
`submit()` 이 `APPROVED` 를 막으므로 **창작자가 스스로 되돌릴 수도 없다.**

### 수정

`SUBMITTED` 에서만 전이하고, 아니면 `false` 를 반환한다.
D-016 과 같은 이유로 예외를 던지지 않는다 — 서비스가 `log.warn` 으로 남기고 소비를 진행시킨다.

반환값을 `boolean` 으로 둔 것은 "무시했다"를 호출자가 관측할 수 있게 하기 위함이다.
`void` 로 조용히 넘기면 이벤트가 유실된 것과 구분되지 않는다.

---

<a id="d-018"></a>
## D-018 결제 없는 주문의 보상 요청이 무한 재배달

**상태** 수정됨
**영향** 가용성 · 자동 복구 불가
**위치** `apps/payment/.../core/service/PaymentService.java`
**재현** `PaymentCancelTest#compensationForUnknownOrderMustNotStallTheConsumer`,
`#compensationForUnknownOrderKeepsTheGuardMark`

### 무슨 일이

[D-007](#d-007) 과 **같은 결함이 같은 메서드의 한 줄 위에 남아 있었다.**

```java
if (!processedEventGuard.firstDelivery(...)) { return ...; }
Payment payment = findPayment(orderNo);   // ← PAYMENT_NOT_FOUND 를 던진다
if (!payment.cancelable()) {
    // "예외를 던지면 가드 마킹까지 롤백되어 같은 이벤트가 영원히 재전송된다(파티션 정지)"
```

D-007 을 고치면서 **상태 불일치** 분기는 값 반환으로 바꿨지만,
바로 위 **결제 자체가 없는** 경로는 그대로 예외였다.
결제는 재시도한다고 생기지 않으므로 결과도 D-007 과 같다 — 파티션 정지.

주문번호가 어긋났거나(연동 오류), 결제 생성 이벤트를 아직 못 받았거나, 수동 재처리일 때 나온다.

### 왜 놓쳤나

D-007 의 재현 테스트가 **`PENDING` 상태의 결제**로만 검증했다.
"결제가 없다"는 경우는 같은 부류의 입력인데 테스트 입력에 없었고,
수정 주석이 바로 아래 붙어 있어서 읽는 사람에게는 이미 방어된 것처럼 보였다.

### 수정

`findPayment` 대신 `findByOrderNo(...).orElse(null)` 로 받아
없으면 `log.error` 후 `PaymentCancellation.none()` 을 반환한다. D-007 분기와 동일한 처리다.

---

<a id="d-019"></a>
## D-019 order 의 수량 검증이 어댑터에만 존재

**상태** 수정됨
**영향** 금액 조작 (잠재)
**위치** `apps/order/.../core/domain/QuoteItem.java`
**재현** `QuoteItemTest#zeroQuantityIsRejectedByTheDomain`,
`#negativeQuantityIsRejectedByTheDomain`, `#missingProductIdIsRejectedByTheDomain`

### 무슨 일이

[D-009](#d-009) 를 고치면서 `catalog` 의 `QuoteItem` 에 컴팩트 생성자 가드를 넣고,
그 javadoc 에 근거까지 적었다 — *"어댑터는 늘어날 수 있고 도메인 규칙은 도메인이 지켜야 한다."*

**`order` 에 같은 이름의 값 객체가 하나 더 있다는 것을 그때 보지 못했다.**

```java
/** catalog 에 가격 재계산을 요청할 항목. */
public record QuoteItem(Long productId, int quantity) {
}
```

order 쪽 방어선은 `CreateOrderRequest.Item` 의 `@Min(1)` 하나뿐이었다 —
D-009 가 지적한 "어댑터에만 있는 검증" 바로 그 모양이다.

### 왜 지금은 안 터졌나

D-009 수정으로 catalog 가 잘못된 수량을 거절하므로 실제 금액 조작까지는 가지 않는다.
다만 거절이 **HTTP 왕복 뒤에** 일어나고, `CatalogRestAdapter` 가 catalog 의 4xx 를
`UPSTREAM_UNAVAILABLE`(503) 로 바꾸므로 **우리 입력 문제가 "업스트림 장애"로 보고된다.**

### 수정

catalog 와 같은 가드를 넣었다. 보내는 쪽에서 걸리면 왕복이 없고,
실패도 요청 오류(400)로 남는다.

> 같은 개념의 값 객체가 서비스마다 따로 있는 것은 이 구조의 의도된 대가다
> (`common` 에 도메인 모델을 두지 않는다 — [decisions.md](decisions.md)).
> 대신 **한쪽을 고칠 때 같은 이름의 다른 쪽을 함께 봐야 한다**는 부담이 생긴다.
> D-019 는 그 부담을 실제로 놓친 첫 사례다.

---

<a id="d-020"></a>

## D-020 범위를 벗어난 페이지 파라미터가 400 이 아니라 500 으로 응답

**상태** 수정됨
**영향** 오분류·알람 잡음 (D-015 계열)
**위치** `apps/store/.../core/service/StoreService.java`
**재현** `StoreControllerTest#negativePageIsRejected`, `#zeroSizeIsRejected`

### 무슨 일이

`store` 의 검색은 완전 공개 경로다. `?page=-1` 이나 `?size=0` 을 붙이면
`PageRequest.of` 가 `IllegalArgumentException` 을 던지는데, 이 예외는
`GlobalExceptionHandler` 의 malformed 목록에 없어서 마지막 분기로 흘렀다.

```
GET /api/v1/storefront/products?page=-1  →  500
GET /api/v1/storefront/products?size=0   →  500
```

[D-015](#d-015) 가 정확히 이 부류를 닫으려던 것이었는데, 그때는 헤더·타입·본문만 봤고
**범위를 벗어난 값**은 목록에 없었다. 인증이 없는 경로라 누구나 재현할 수 있고,
5xx 알람이 서버 장애로 울린다.

### 왜 기존 테스트가 못 잡았나

`StoreControllerTest` 는 `StoreService` 를 mock 으로 두므로 `PageRequest.of` 가
아예 실행되지 않는다. `[D-015] 페이지 번호가 숫자가 아니면 400` 테스트가 옆에 있었지만
그건 타입 변환 단계라 서비스에 닿기 전에 걸린다 — **경계값은 서비스 안에서 터진다.**

### 수정

`StoreService.search` 에서 범위를 검사해 `INVALID_REQUEST` 로 바꾼다.
컨트롤러가 아니라 서비스에 둔 이유는 [D-019](#d-019) 와 같다 —
어댑터에만 두면 그 경로 하나만 지켜지고, 어댑터는 늘어난다.

재현 테스트는 mock 이 아니라 **실제 서비스를 태운** MockMvc 로 돌린다.
그러지 않으면 같은 공백이 다시 생긴다.

---

<a id="d-021"></a>

## D-021 스텁 격리 규칙이 실제 스텁을 한 번도 검사하지 않음

**상태** 수정됨
**영향** 규칙의 공허 통과 (운영 사고 잠재)
**위치** `common/archunit/.../ModuleHygieneRules.java`
**재현** `ArchRuleEnforcementTest#everyStubPrefixIsChecked`

### 무슨 일이

`스텁_어댑터는_격리한다` 는 `infrastructure` 의 `Mock*`·`Stub*`·`Fake*` 클래스가
`@Profile` 이나 `@ConditionalOnProperty` 로 격리돼 있는지 보는 규칙이다
([결정 9](decisions.md)). 그런데 술어가 이렇게 쓰여 있었다.

```java
.that().resideInAPackage(INFRASTRUCTURE).and().haveSimpleNameStartingWith("Mock")
.or().resideInAPackage(INFRASTRUCTURE).and().haveSimpleNameStartingWith("Stub")
.or().resideInAPackage(INFRASTRUCTURE).and().haveSimpleNameStartingWith("Fake")
```

**ArchUnit 의 유창한 `and()`/`or()` 에는 우선순위가 없다.** 왼쪽부터 결합하므로
`A and Mock or A and Stub or A and Fake` 는
`((((A and Mock) or A) and Stub) or A) and Fake` 가 되고,
결국 **`Fake` 로 시작하는 클래스만** 검사한다.

리포의 스텁은 네 개이고 전부 `Mock*` 이다 —
`MockPgClient`, `MockRatingBoardClient`, `MockBuildStorage`, `MockTaxInvoiceIssuer`.
**규칙이 도입된 이래 실제 스텁을 한 번도 평가한 적이 없었다.**

넷 다 우연히 격리돼 있어 사고는 없었다. 하지만 누군가 `@Profile` 을 지웠어도
빌드는 초록이었을 것이고, 그 결과는 운영에서 스텁 PG 가 조용히 도는 것이다.

### 왜 아무도 몰랐나

모든 규칙에 `allowEmptyShould(true)` 가 걸려 있다. 클래스가 하나뿐인 모듈(gateway)에서도
같은 규칙 세트를 쓰기 위한 선택이지만, 대가로 **술어가 아무것도 매칭하지 못해도
규칙이 조용히 통과한다.** 규칙은 코드를 검사하는데, 규칙 자신을 검사하는 것은 없었다.

### 수정

술어를 `DescribedPredicate` 로 명시적으로 묶었다. 그리고 규칙 자체를 대상으로 하는
`ArchRuleEnforcementTest` 를 추가했다 — 위반 픽스처에 대해 규칙이 **실패하는지**,
준수 픽스처에 대해 **통과하는지** 양쪽을 본다. 접두사 세 개는 각각 따로 확인한다.
한꺼번에 검사하면 셋 중 하나만 잡혀도 규칙이 실패해 통과하기 때문이다 —
실제로 그 때문에 `Fake` 하나로 가려져 있었다.

> 다른 or-체인(`컨트롤러는_api_controller_에_둔다`)도 확인했다.
> 그쪽은 `and` 가 섞이지 않은 순수 OR 라 좌결합이어도 의미가 같다.

---

<a id="d-022"></a>

## D-022 롤백된 마감 트랜잭션이 세금계산서는 발행

**상태** 수정됨
**영향** 금전 불일치 (외부 시스템)
**위치** `apps/settlement/.../core/service/SettlementService.java`
**재현** `SettlementCloseFacadeTest#failedIssuanceKeepsTheClosing`,
`#oneSellerFailureDoesNotBlockOthers`, `#failedIssuanceIsRetriedOnNextRun`

### 무슨 일이

`closeMonth` 는 클래스 레벨 `@Transactional` 이었고, 그 트랜잭션 **안에서**
`taxInvoiceIssuer.issue(...)` 를 호출했다. 세금계산서 발행은 외부 시스템 호출이라
DB 롤백이 되돌리지 못한다.

**[D-006](#d-006) 과 정확히 같은 모양이다** — 결제에서는 "롤백된 환불 트랜잭션이 PG 환불은 실행"이었고,
정산에서는 "롤백된 마감 트랜잭션이 세금계산서는 발행"이다. payment 에서 고친 패턴이
settlement 에는 그대로 남아 있었다.

### 다중 인스턴스만의 문제가 아니었다

`SettlementBatch` 의 주석은 이것을 **ShedLock 이 필요한 다중 인스턴스 문제**로 적어 두었다.
추적해 보니 그 진단이 문제를 한 칸 얕게 짚고 있었다.

DB 는 이미 방어돼 있다. `uk_seller_settlement (seller_id, settlement_month)` 유니크가 있고,
시차를 두고 돌면 뒤엣놈은 마감 대상이 비어 안전하게 끝난다. 동시에 돌면 한쪽이 유니크 위반으로 롤백된다.
**그런데 롤백된 쪽도 계산서는 이미 발행했다.**

그리고 이건 **단일 인스턴스에서도 터진다.** 마감이 그 달 전체를 한 트랜잭션으로 돌았기 때문이다 —
판매자 100명 중 87번째에서 예외가 나면 앞의 86장이 이미 발행된 채 트랜잭션만 롤백된다.
DB 에는 마감 기록이 없는데 국세청에는 계산서가 있는 상태가 된다.

### 수정

결제 환불이 쓰는 3단계 구조를 그대로 가져왔다. `SettlementCloseFacade`(트랜잭션 없음)가 조율한다.

1. **확정본 커밋** — 판매자 한 명의 원장을 합산해 쓰고 그 판매자의 원장을 close
2. **세금계산서 발행** (트랜잭션 밖)
3. **발행 번호 커밋**

판매자마다 독립 트랜잭션이라 한 명의 실패가 나머지를 롤백시키지 않는다.
`TaxInvoiceIssuer#issue` 의 계약에 `(sellerId, month)` 기준 멱등을 명시했다 —
`PgClient#cancel` 이 `pgTxId` 기준 멱등을 요구하는 것과 같은 이유다.

### 고치면서 새로 생긴 경로 하나

재현 테스트를 쓰다가 발견했다. 발행이 트랜잭션 밖으로 나오면
**"확정본은 커밋됐고 발행은 실패한"** 상태가 생기는데, 그 판매자는 원장이 이미 close 되어
*미마감 원장 기준*으로는 다음 실행에서 잡히지 않는다 — 계산서 없는 확정본이 영구히 방치된다.

마감 대상을 두 부류의 합집합으로 바꿨다.

- 미마감 원장이 있는 판매자
- **마감은 끝났는데 계산서가 없는 판매자** (`findAwaitingTaxInvoice`)

> 이 경로는 수정이 만들어낸 것이지 원래 있던 결함이 아니다.
> 다만 테스트를 먼저 쓰지 않았다면 발견되지 않은 채 배포됐을 자리라 함께 적어 둔다.

### ShedLock 은 그다음에

근본 원인을 고친 뒤 `@SchedulerLock` 을 붙였다(`settlement-close-month`, MySQL 락).
**순서가 반대였다면** 락을 걸어 두고도 단일 인스턴스 부분 실패로 같은 사고가 났을 것이다.
락은 동시 실행 창만 닫는다.

---

## 닫힌 항목

### 결함이 아니었던 것

**게이트웨이 actuator 노출** — `apps/gateway` 의 `exposure.include` 에 `gateway` 가 들어 있어
라우트 목록과 `refresh`(쓰기)가 인증 없이 열려 있다고 보고 재현 테스트를 먼저 썼는데, **통과했다.**

Spring Cloud Gateway 4.x 부터 `management.endpoint.gateway.enabled` 기본값이 `false`,
`management.endpoint.gateway.access` 기본값이 `none` 이라 노출 목록에 넣는 것만으로는 열리지 않는다.

**설정이 막고 있는 것이 아니라 라이브러리 기본값이 막고 있다.** 그래서 항목은 닫되
테스트는 `GatewayActuatorExposureTest` 로 남겼다 — 누군가 그 기본값을 켜면 그때 걸린다.
이 모듈에는 security 의존성이 없다.

### 결번

- **D-005** — 작성 중 철회. "DEAD 전이를 관측할 수 있어야 한다"는 항목이었으나
  [D-003](#d-003) 의 일부이고 단독 재현 테스트가 구조 검사에 가까워 합쳤다.
  번호는 재사용하지 않는다(기존 커밋·주석의 참조가 흔들린다).
