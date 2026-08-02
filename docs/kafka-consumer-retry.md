# Kafka 컨슈머 재시도는 왜 "예외 전파"에 의존하는가

이 저장소의 `apps/license/src/main/java/com/stove/license/api/listener/PaymentEventListener.java`
를 읽다가 나온 질문을 정리한 학습 노트다.

> 리스너 안에서 `try/catch` 로 예외를 잡으면 왜 Kafka 재시도가 돌지 않는가?

결론부터: **컨테이너에게 "실패했다"를 알리는 채널이 예외 하나뿐이기 때문**이다.
리턴값은 보지 않는다. 아래는 그 근거를 스프링 카프카 내부 동작으로 따라간 기록이다.

---

## 1. `@KafkaListener` 뒤에 실제로 무엇이 있나

애노테이션은 마법이 아니다. 스프링이 그 메서드를 감싸는 **컨테이너**를 만들고,
전용 스레드에서 무한 루프를 돌린다.

```
KafkaMessageListenerContainer
  └─ ListenerConsumer (전용 스레드 1개)
       └─ while (running) {
              ConsumerRecords records = consumer.poll(timeout);   // 브로커에서 레코드 뭉치를 받는다
              invokeListener(records);
          }

invokeListener → doInvokeListener
       └─ for (record : records) {
              try {
                  doInvokeRecordListener(record);   // ← 우리 @KafkaListener 메서드가 여기서 호출된다
                  ackCurrent(record);               // ← 정상 리턴 → 오프셋 커밋
              }
              catch (RuntimeException e) {
                  invokeErrorHandler(record, iterator, e);   // ← 예외가 나야만 여기로 온다
              }
          }
```

우리 코드는 `doInvokeRecordListener` 안쪽에서 리플렉션으로 불린다.

**컨테이너가 성공/실패를 판정하는 지점은 저 `try/catch` 하나뿐이다.**
리턴값을 검사하는 코드는 없다. `void` 든 `boolean` 이든 무관하고,
`return false` 로 실패를 알릴 방법도 없다.

- 메서드가 정상 리턴 → `catch` 진입 안 함 → `ackCurrent(record)` → **오프셋 커밋**
- 메서드가 예외 던짐 → `catch` 진입 → `invokeErrorHandler` → 재시도 로직 시작

---

## 2. 오프셋 커밋 = "처리 완료" 선언

Kafka 에는 "메시지 삭제"가 없다. 대신 **오프셋**이라는 읽은 위치 표시가 있다.

```
파티션 0의 로그:
  offset:  100    101    102    103    104
  메시지:  [A]    [B]    [C]    [D]    [E]
                          ↑
                   커밋된 위치 = 102
                   → 다음 poll() 은 103부터 준다
```

이 저장소의 컨슈머 설정(`apps/*/src/main/resources/application.yml`):

```yaml
consumer:
  enable-auto-commit: false    # 시간 기반 자동 커밋 끔
listener:
  ack-mode: record             # 레코드 1건 처리 성공마다 커밋
```

`ack-mode: record` 는 **"리스너 메서드가 정상 리턴할 때마다 커밋"** 이라는 뜻이다.
설정 자체는 안전한 쪽이라 좋다. 문제는 그 계약이다 —
예외를 안에서 잡으면 컨테이너 입장에선 정상 리턴이므로 오프셋이 커밋된다.
**커밋되는 순간 그 메시지는 이 컨슈머 그룹에게 두 번 다시 오지 않는다.**

---

## 3. 재시도의 정체는 "seek 되감기"다

예외가 밖으로 나오면 `DefaultErrorHandler` 가 받는다.
이름과 달리 하는 일은 "메서드 재호출"이 아니라 **"소비 위치 되감기"** 다.

```
DefaultErrorHandler.handleRemaining(exception, records, consumer, container)
  └─ SeekUtils.seekOrRecover(...)
       ├─ BackOff 확인 → 재시도 여유 있음?
       │    YES → consumer.seek(partition, 실패한_레코드의_offset)   ← 되감기
       │           → 다음 poll() 이 같은 레코드를 다시 준다
       │           → 그것이 곧 '재시도'
       │
       └─ 여유 소진 → recoverer.accept(record, exception)
                      → 기본 recoverer 는 ERROR 로그를 찍고 레코드를 건너뛴다
                      → 오프셋 커밋 후 다음으로 진행
```

여기서 두 가지가 따라온다.

1. **오프셋 커밋 전에 개입해야 한다.** 커밋된 뒤에는 되감을 대상이 없다.
2. **재시도 중에는 그 파티션이 앞으로 못 나간다.** 되감기 방식의 필연적 대가이며,
   이것을 **블로킹 재시도(blocking retry)** 라고 부른다. 순서 보장과 맞바꾼 것이다.

---

## 4. 현재 코드에서 실제로 벌어지는 일

```java
@KafkaListener(topics = Topics.PAYMENT, groupId = "license")
public void onPaymentEvent(ConsumerRecord<String, String> record) {
    ...
    try {
        licenseService.issue(...);          // DB 예외 발생
    } catch (Exception e) {                 // ← 여기서 잡힌다. 밖으로 안 나간다
        log.error(...);
        licenseService.recordIssueFailure(...);   // 곧바로 보상(환불) 요청
    }
    // ← 메서드가 정상 리턴된다
}
```

시간 순으로 펼치면:

| 시각 | 주체 | 동작 |
|---|---|---|
| t0 | 컨테이너 | `doInvokeRecordListener(record)` 호출 |
| t1 | 리스너 | `licenseService.issue()` 호출 |
| t2 | 서비스 | DB 커넥션 획득 실패 → `CannotGetJdbcConnectionException` |
| t3 | 트랜잭션 | `issue()` 의 `@Transactional` 롤백 — 멱등 가드 마킹도 함께 롤백 ✅ |
| t4 | 리스너 | `catch` 로 잡음 |
| t5 | 리스너 | `recordIssueFailure()` → `REQUIRES_NEW` 로 **별도 트랜잭션 커밋** |
| t6 | 리스너 | 정상 리턴 |
| t7 | 컨테이너 | 예외 없음 → `ackCurrent(record)` → **오프셋 커밋** |
| t8 | 컨테이너 | `DefaultErrorHandler` 호출 안 됨. 다음 레코드로. |

t3 의 롤백은 잘 설계된 부분이다 — "다시 시도하면 처리 가능한 상태"로 되돌아갔다.
그런데 t7 에서 오프셋이 커밋되어 **다시 시도할 기회 자체가 사라진다.**
재처리 가능하게 만들어 놓고 재처리를 안 하는 셈이다.

동시에 t5 의 보상 이벤트는 `REQUIRES_NEW` 라 롤백되지 않고 커밋된다. 결과:

```
라이선스   : 미발급 (롤백)
보상 이벤트: 발행됨 (커밋)
Kafka      : 처리 완료로 간주 (오프셋 커밋)
→ 결제 서버가 환불 실행
```

DB 가 1초 뒤 복구돼도 되돌릴 방법이 없다.
**정상 결제가 일시 장애 한 번에 환불된다.**

---

## 5. 의도대로라면 이렇게 생겨야 한다

리스너의 주석은 "DefaultErrorHandler 의 재시도까지 소진된 뒤 도달하는 경로"라고 말한다.
그 문장이 참이 되려면 보상 트리거가 **리스너 안이 아니라 recoverer** 에 있어야 한다.

```java
// 리스너: 예외를 잡지 않고 그대로 던진다
@KafkaListener(topics = Topics.PAYMENT, groupId = "license")
public void onPaymentEvent(ConsumerRecord<String, String> record) {
    ...
    licenseService.issue(...);   // 실패하면 예외가 컨테이너까지 올라간다
}

// 설정: 재시도 소진 후 호출될 recoverer 를 등록한다
@Bean
public DefaultErrorHandler errorHandler(LicenseService licenseService, ObjectMapper om) {
    ConsumerRecordRecoverer recoverer = (record, ex) -> {
        // 여기가 진짜 '최종 실패' 지점 — 모든 재시도가 끝난 뒤에만 도달한다
        var envelope = EventEnvelope.from((ConsumerRecord<String, String>) record);
        var event = envelope.payloadAs(om, PaymentCompletedEvent.class);
        licenseService.recordIssueFailure(event.orderNo(), event.memberId(), ex.getMessage());
    };
    return new DefaultErrorHandler(recoverer, new ExponentialBackOffWithMaxRetries(3));
}
```

recoverer 는 정의상 재시도가 전부 소진된 뒤에만 호출되므로 주석이 실제로 성립한다.

---

## 6. 예외를 밖으로 빼는 것만으로는 부족하다

전 모듈을 grep 해도 커스텀 `ErrorHandler` / `BackOff` 빈이 **하나도 없다**.
따라서 스프링 부트가 자동 설정한 기본값이 그대로 쓰인다.

```java
// spring-kafka 3.3.10, org.springframework.kafka.listener.SeekUtils
DEFAULT_MAX_FAILURES = 10;
DEFAULT_BACK_OFF     = new FixedBackOff(0L, DEFAULT_MAX_FAILURES - 1);   // = (0L, 9L)
                                          ↑    ↑
                                    간격 0ms   재시도 9회
```

(클래스 파일을 직접 열어 확인한 값이다 — `javap -c SeekUtils` 의 static 초기화 블록.)

**최초 배달 1회 + 재시도 9회 = 총 10회, 간격 0밀리초.** 예외를 밖으로 빼도 이렇게 된다:

```
t+0ms   시도 1 → DB 연결 실패
t+0ms   시도 2 → 실패
...
t+5ms   시도 10 → 실패
t+5ms   재시도 소진 → 최종 실패 처리
```

DB 커넥션풀 고갈이 1초만 지속돼도 10회가 5밀리초 안에 소진된다.
**재시도의 의미가 없다.** `common/messaging` 의 Outbox 릴레이에도 같은 병이 있다
(실패 시 대기 없이 1초마다 재시도 → `max-retry` 소진 → DEAD).

그래서 필요한 수정은 두 가지다.

1. 예외를 밖으로 내보내기 — 재시도 기회 확보
2. 지수 백오프 설정 — 재시도가 의미를 갖도록

### 제약: `max.poll.interval.ms`

블로킹 재시도 중에는 컨슈머 스레드가 `poll()` 을 호출하지 못한다.
Kafka 는 `max.poll.interval.ms`(기본 5분) 동안 poll 이 없으면 그 컨슈머를 죽은 것으로 보고
그룹에서 쫓아낸다(리밸런싱). 따라서 **백오프 총합이 5분을 넘으면 안 된다.**

더 오래 버텨야 하면 블로킹 재시도 대신 별도 재시도 토픽(`@RetryableTopic`)을 써서
논블로킹으로 가야 한다. 대신 그 경우 **순서 보장이 깨진다** — 실패한 메시지가 뒤로 밀리기 때문이다.

| 방식 | 순서 보장 | 파티션 정지 | 긴 대기 |
|---|---|---|---|
| 블로킹 재시도 (`DefaultErrorHandler`) | ✅ | ✅ 멈춘다 | ❌ 5분 한계 |
| 논블로킹 재시도 (`@RetryableTopic`) | ❌ | ❌ 안 멈춤 | ✅ 자유 |

이 저장소는 "같은 애그리거트의 순서 보장"을 설계 전제로 삼았으므로(README 2절)
블로킹 재시도가 맞는 선택이다. 다만 백오프를 명시해야 한다.

---

## 7. 직접 확인해 보는 법

인프라 없이 검증할 수 있다. 도커도 Kafka 도 필요 없다 —
재시도의 유일한 전제조건이 "예외 전파"이므로, 그것만 리스너 단위에서 확인하면 된다.

```java
@Test
void 지급_중_예외는_리스너_밖으로_전파되어야_한다() {
    doThrow(new CannotGetJdbcConnectionException("pool exhausted"))
            .when(licenseService).issue(any(), any(), any(), any(), any());

    assertThatThrownBy(() -> listener.onPaymentEvent(paymentCompletedRecord()))
            .isInstanceOf(DataAccessException.class);          // 재시도의 전제조건

    verify(licenseService, never()).recordIssueFailure(any(), any(), any());  // 보상 오발동 없음
}
```

실제 테스트는 `apps/license/src/test/java/com/stove/license/api/listener/PaymentEventListenerTest.java`
에 있다. 현재 코드에서는 **실패한다** — 그것이 결함의 증거다.

---

## 8. 요약 카드

| 질문 | 답 |
|---|---|
| 왜 예외여야 하나 | 컨테이너의 성공/실패 판정 지점이 `try/catch` 하나뿐. 리턴값 검사 없음 |
| 왜 커밋이 중요한가 | 재시도 = `consumer.seek()` 되감기. 커밋 후엔 되감을 대상이 없음 |
| 현재 코드의 결과 | 예외를 삼킴 → 정상 리턴 → 오프셋 커밋 → ErrorHandler 미호출 → 재시도 0회 |
| 보상 트리거의 올바른 위치 | 리스너가 아니라 `ErrorHandler` 의 recoverer |
| 숨은 2차 결함 | 커스텀 ErrorHandler 없음 → 기본 `FixedBackOff(0ms, 9회)` → 예외를 빼도 5ms 만에 소진 |
| 제약 | 블로킹 재시도 총합 < `max.poll.interval.ms`(5분). 넘으면 리밸런싱 |

### 되새길 원칙

> **프레임워크가 제어 흐름을 관장할 때, 그 프레임워크가 읽는 신호를 가로채면 안 된다.**

`try/catch` 는 "내가 이 실패를 책임진다"는 선언이다.
컨테이너·트랜잭션 매니저·서킷브레이커처럼 예외를 신호로 삼는 장치 아래에서는,
예외를 잡는 순간 그 장치를 무력화한 것이다.
잡아야 한다면 **처리한 뒤 다시 던지거나**, 애초에 그 장치의 확장점(recoverer 등)에 붙여야 한다.

---

## 참고

- Spring for Apache Kafka Reference — *Handling Exceptions* / `DefaultErrorHandler`
- `org.springframework.kafka.listener.SeekUtils` — 되감기 로직 원본
- `org.springframework.kafka.listener.DefaultErrorHandler` — 기본 백오프 상수
- 이 저장소: `docs/defects.md` (D-002 항목), `docs/testing.md` (테스트 계층 설계)
