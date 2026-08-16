# 결함 대장

테스트로 재현에 성공한 결함만 적는다. **추측은 넣지 않는다** —
모든 항목에 실패하는 테스트가 하나씩 붙어 있고, 그 테스트가 통과하면 항목을 닫는다.

```bash
./gradlew build         # 결함 테스트 제외하고 전건 통과
./gradlew defectTest    # 남아 있는 결함만 재현. 현재 1건 ([D-026](#d-026))
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
| [D-023](#d-023) | 배포 게이트가 컨테이너 0개에도 통과 | 게이트의 공허 통과 | **수정됨** |
| [D-024](#d-024) | 알 수 없는 정렬 속성이 400 이 아니라 500 | 오분류·알람 잡음 | **수정됨** |
| [D-025](#d-025) | 정렬이 결정적이지 않아 페이지 경계에서 상품 중복·유실 | 데이터 정합성 | **수정됨** |
| [D-026](#d-026) | 컨슈머가 멈추면 랙 지표도 같이 멈춰 적체가 관측되지 않음 | 관측 불가 | **우회함** |
| [D-027](#d-027) | 인프라 장애를 지급 불가로 판정해 정상 결제를 환불 | 금전 손실 | **수정됨** |
| [D-028](#d-028) | 이미 지급된 주문에 보상 환불이 발동 | 금전 손실 | **수정됨** |
| [D-029](#d-029) | 미결제 주문에 만료가 없어 옛 가격으로 영구히 결제 | 금액 조작 | **수정됨** |
| [D-030](#d-030) | 이벤트 재처리로 유실을 복구할 수 없다 — Inbox 가드가 먼저 막음 | 복구 불가 | **절차로 닫음** |
| [D-031](#d-031) | 랙 알람이 조건이 참인 내내 한 번도 울리지 않음 | 관측 불가 | **수정됨** |
| [D-032](#d-032) | 익스포터가 스크레이프마다 지표를 정확히 한 줄씩 흘림 | 관측 불가 | **수정됨** |

D-016 ~ D-019 는 **서비스 계층에 테스트가 없던 모듈에 테스트를 붙이면서** 나왔다.
넷 중 셋이 컨슈머 경로의 예외 처리 문제이고, 하나는 이미 고친 결함(D-009)이
같은 이름의 다른 값 객체에 그대로 남아 있던 경우다.

D-020 ~ D-021 은 [testing.md](testing.md) 6절이 "위험"으로 표시해 둔 공백을 메우면서 나왔다.
**둘 다 이미 있던 방어선이 실제로는 작동하지 않고 있던 경우다** —
D-015 가 닫았다고 본 부류에 경계값이 빠져 있었고, ArchUnit 규칙 하나는 술어 결합 순서 때문에
대상 자체를 잘못 고르고 있었다. 통과하는 테스트와 잡아내는 테스트의 차이가
결함 목록에 그대로 나타난 사례다.

D-024 도 같은 계열의 세 번째다. e2e 가 목록 판정을 고치면서 처음으로 `sort` 를 써 봤고,
그러자 **저장소에서 `Pageable` 을 직접 받는 유일한 컨트롤러**가 500 을 냈다.
D-015 는 헤더·타입·본문을, D-020 은 범위를 벗어난 값을 닫았는데 —
`sort` 는 타입도 맞고 범위도 없는 **존재하지 않는 이름**이라 두 그물 사이로 빠졌다.

D-025 는 그 수정을 코드 리뷰로 다시 훑다가 나왔다. **고친 자리에서 한 칸 더 들어간 경우다** —
D-024 는 "모르는 이름" 을 닫았지만, 열어 준 이름 중 `price`·`name` 은 유일하지 않아
페이징 결과 자체가 결정적이지 않았다. e2e 주석이 이미 "22번째 상품에서 터졌다" 로
같은 함정을 적어 뒀는데, 그때는 **호출하는 쪽만 피해 갔다.**

D-026 은 **측정하다가** 나왔다. 지표가 전부 초록인데 브로커에 27,474건이 밀려 있었고,
그 적체를 볼 수 있는 자리가 한 곳도 없었다([measuring.md](measuring.md)).

D-027 ~ D-030 은 **부하를 건 채로 장애를 넣어** 나왔다([chaos.md](chaos.md)).
정상 상태 부하로는 한 번도 실행되지 않는 자리들이다 — Saga 보상·컨슈머 재시도·멱등 가드·DLT 는
평상시에 아무 일도 하지 않는다. 넷 다 이 대장에 이미 한 번씩 등장한 자리이기도 하다
(D-002·D-003·D-010·D-013). **고쳐 본 자리를 다시 고르게 되는 것은 우연이 아니다.**

그중 둘은 **이 대장의 기존 항목이 절반만 고쳐져 있던 경우**다. D-002 는 보상을 *언제* 할지
(재시도 뒤에)는 고쳤지만 *무엇을 보고* 할지는 그대로 두었고, D-010 은 재발행 로직을 고쳤지만
그것을 지키는 회귀 테스트가 **운영에 존재하지 않는 재처리 방식**을 쓰고 있었다.
D-021·D-023 과 같은 공허 통과의 세 번째·네 번째 사례다.

**D-026 과 D-027 은 같은 사건의 앞뒤다.** D-027 의 장애 회차에서 정상 결제가 환불된 이유는
블로킹 재시도가 파티션을 7초씩 멈춰 세웠기 때문인데, 그 정지를 앱 지표로는 볼 수 없다 —
그게 D-026 이다. `ConsumerStalled`(랙이 있는데 커밋 오프셋이 안 움직임)가
**D-027 이 만든 장애의 정확한 서명**이고, 아직 둘을 한 회차에서 같이 돌려 보지는 않았다.

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

<a id="d-023"></a>
## D-023 배포 게이트가 컨테이너 0개에도 통과

**상태** 수정됨
**영향** 게이트의 공허 통과 — 스택이 통째로 내려가 있는 것이 가장 조용한 상태였다
**위치** `scripts/smoke-stack.sh` 0장 → `scripts/stack-wait.sh` 로 옮기며 수정
**재현** 아래 절차 (셸이라 `@Tag("known-defect")` 를 붙일 자리가 없다 — 대신 관측한 출력을 남긴다)

### 무슨 일이

컨테이너 상태 판정이 이랬다.

```bash
unhealthy=$(docker ps --filter health=unhealthy --format '{{.Names}}')
[ -n "$unhealthy" ] && bad "healthcheck" "unhealthy: $unhealthy" || ok "unhealthy 없음"
```

**빈 결과를 "정상" 으로 읽는다.** `docker ps` 는 *실행 중인* 컨테이너만 보므로,
죽은 컨테이너는 unhealthy 목록에 아예 나타나지 않는다. 스택이 20개 다 내려가 있어도
목록은 비어 있고, 판정은 초록이다.

여기에 두 번째 구멍이 겹친다. **compose 20종 중 7종이 헬스체크 미정의**이고
(redis · kafka · kafka-ui · mongodb · prometheus · tempo · grafana)
그중에 브로커가 있다. Kafka 가 죽으면 앱은 계속 뜬 채로 API 에 응답하고 이벤트만 멈추는데,
부트에는 대응하는 헬스 지표가 없어 액추에이터 10종도 전부 UP 이다.
**끊긴 것이 어디에도 나타나지 않는다.**

[D-021](#d-021) 과 같은 부류다 — 거기서는 ArchUnit 규칙이 대상을 잘못 골라 아무것도 검사하지 않았고,
여기서는 게이트가 빈 결과를 통과로 읽어 아무것도 막지 않았다.
**둘 다 방어선이 없는 것이 아니라, 있는데 작동하지 않는 것이다.**

### 재현 — 앱 10종을 내리고 옛 판정식을 그대로 돌린다

```
$ docker compose -f docker-compose.apps.yml -f docker-compose.apps.ci.yml stop
$ docker ps --format '{{.Names}}' | grep -c stove-apps-
0
$ unhealthy=$(docker ps --filter health=unhealthy --format '{{.Names}}')
$ [ -n "$unhealthy" ] && echo "  ✗ unhealthy: $unhealthy" || echo "  ✓ unhealthy 없음"
  ✓ unhealthy 없음        ← 앱이 하나도 안 떠 있는데 초록이다
```

브로커만 내렸을 때도 같다. `docker stop stove-kafka` 뒤에 액추에이터 10종이 전부 UP 이고
0장도 초록이라, 옛 스크립트에서는 **어느 장에서도 빨개지지 않았다.**

### 수정

판정을 `scripts/stack-wait.sh` 로 옮기면서 셋을 바꿨다.

1. **없는 것을 세려면 있어야 할 것을 알아야 한다.** 기대 컨테이너 20종의 이름을 적고
   `docker ps` 와 대조한다. 그리고 몇 개가 없다가 아니라 **무엇이 없는지를 이름으로 말한다.**
2. **브로커를 직접 찌른다.** `kafka-broker-api-versions.sh --bootstrap-server kafka:19092` —
   부트에 지표가 없는 자리를 게이트가 대신 본다.
3. **액추에이터 10종을 맨 앞으로.** 부트의 health 가 DataSource·Redis·MongoDB·Elasticsearch 를
   집계하므로, 헬스체크 미정의 7종 중 **redis·mongodb 는 여기서 간접적으로 덮인다.**
   kafka 는 2번이 직접 찌르고, 남는 넷(kafka-ui · prometheus · tempo · grafana)은 1번이 본다.
   셋을 합쳐야 20종이 빠짐없이 덮인다 — 어느 하나로도 충분하지 않은 것이 이 결함의 모양이었다.

종료 코드는 `2` 로 세운다. 게이트가 막았다는 것과 시나리오가 실패했다는 것은 대응이 다르다.

### 수정 뒤 같은 절차

```
--- 옛 판정식 ---
  ✓ unhealthy 없음
--- 새 게이트 ---
  ✗ quote 는 게이트웨이로 못 부른다 — 기대 404, 실제 무응답
  ✗ 컨테이너 20종 — 실행되지 않음: gateway(앱) catalog(앱) order(앱) payment(앱) license(앱)
                                    studio(앱) review(앱) store(앱) download(앱) settlement(앱)
  통과 2 / 실패 12
EXIT=2
```

브로커만 내린 경우도 확인했다 — `docker stop stove-kafka` 에서 앱 10종은 여전히 전부 UP 이고,
새 게이트는 2건(브로커 응답 · 컨테이너 집합)으로 빨개진다.

> **초록 실행은 게이트가 동작한다는 증명이 아니다.** 이 항목이 그 자체로 증거다 —
> 옛 판정은 통과한 적은 많고 막은 적은 없었다. 그래서 고친 뒤에도 통과를 확인하는 것으로 끝내지 않고,
> 위 두 가지를 일부러 깨뜨려 빨개지는 것까지 봤다([testing.md](testing.md) 3절과 같은 규칙).

---

<a id="d-024"></a>
## D-024 알 수 없는 정렬 속성이 400 이 아니라 500 으로 응답

**상태** 수정됨
**영향** 오분류·알람 잡음 (D-015 계열)
**위치** `apps/catalog/.../core/domain/ProductSort.java`, `common/web/.../UnknownPropertyExceptionHandler.java`
**재현** `ProductSortTest` (통합 소스셋)

### 무슨 일이

`catalog` 의 상품 목록은 게이트웨이의 `catalog-public` 라우트로 인증 없이 열려 있다.
`sort` 에 엔티티가 모르는 이름을 주면 Spring Data 가 `PropertyReferenceException` 을 던지고,
그 예외가 `GlobalExceptionHandler` 의 malformed 목록에 없어 마지막 분기로 흘렀다.

```
GET /api/v1/products?sort=productId,desc     →  500   No property 'productId' found for type 'Product'
GET /api/v1/products?sort=unknownField,asc   →  500
GET /api/v1/products?sort=id,desc            →  200   ← 엔티티의 실제 필드명
```

**계약이 뒤집혀 있었다.** `productId` 는 응답 DTO 가 실제로 돌려주는 이름이고 엔티티는 `id` 다.
즉 **응답에 보이는 이름으로 정렬하면 500, 응답 어디에도 없는 내부 이름을 알아야 200** 이다.

[D-015](#d-015) 가 헤더·타입·본문을 닫고 [D-020](#d-020) 이 범위를 벗어난 값을 닫았는데,
`sort` 는 그 두 그물 사이로 빠졌다 — 타입은 맞고(문자열) 범위도 없다.
**존재하지 않는 이름**이라는 세 번째 부류다.

### 왜 지금까지 안 걸렸나

`Pageable` 을 받는 컨트롤러가 저장소 전체에 `ProductController#list` 하나뿐이고,
아무도 `sort` 를 써 본 적이 없다. e2e 가 페이지네이션 함정을 피하려고 정렬을 주면서 처음 밟았다.

`store` 의 검색은 `Pageable` 을 직접 받지 않아 같은 입력에도 200 이다.

### `id` 는 계약이었던 적이 없다

커밋된 OpenAPI 스냅샷(`apps/catalog/src/integrationTest/resources/openapi/catalog.json`)은
`sort` 를 `string[]` 로만 적어 두고 **어떤 키가 유효한지 약속한 적이 없다.**
`id → 200` 은 공개된 계약이 아니라 요청 문자열이 검사 없이 엔티티 속성으로 해석되던 통로의
부작용이고, `productId → 500` 은 같은 통로의 앞뒷면이다.

그래서 수정은 그 통로를 닫는 것이고, 닫는 순간 `id` 는 명시적으로 허용하거나 거절해야 한다.
**거절을 골랐다** — 저장소 전체에서 `sort=` 를 쓰던 곳은 e2e 한 줄뿐이라 지금 닫는 값이 가장 싸고,
한 번 받기 시작하면 호출자를 알 수 없어 뗄 수 없다.

### 수정

두 겹이다. **본질은 앞의 것이고 뒤의 것은 안전망이다.**

1. `ProductSort` 가 허용 키를 명시하고 엔티티 속성으로 옮긴다
   (`productId → id`, `productCode`·`name`·`price` 는 항등). 목록에 없는 이름은
   `INVALID_REQUEST` 400 이다. 컨트롤러가 아니라 `ProductQueryService` 가 이것을 부른다 —
   [D-019](#d-019)·[D-020](#d-020) 과 같은 이유로, 어댑터에만 두면 그 경로 하나만 지켜진다.
2. `UnknownPropertyExceptionHandler` 가 `PropertyReferenceException` 을 400 으로 받는다.
   허용 키를 명시하는 것을 빠뜨린 **다음 엔드포인트**를 위한 그물이다.

`GlobalExceptionHandler` 의 malformed 목록에 얹지 않고 클래스를 나눈 이유는 **메시지**다.
그 분기는 `e.getMessage()` 를 그대로 싣는데 이 예외의 메시지는
`No property 'productId' found for type 'Product'` 라서, 인증 없는 공개 경로로
**엔티티 타입명이 나간다.** 속성 이름만 돌려주려면 분기가 따로 필요하다.

### 안전망이 조용히 죽어 있을 뻔했다

어드바이스 사이의 선택은 가장 구체적인 핸들러가 아니라 **먼저 오는 어드바이스**가 이긴다.
`GlobalExceptionHandler` 에 `Exception.class` 분기가 있으므로 `@Order` 를 주지 않으면
새 핸들러는 **한 번도 실행되지 않는다.** 클래스 하나만 세운 MockMvc 테스트는 통과했고,
앱에 태워 보고서야 여전히 500 인 것이 드러났다 — [D-021](#d-021) 과 같은 부류다.

그래서 테스트는 두 어드바이스를 **불리한 순서로**(catch-all 을 앞에) 세운다.
`@Order` 를 떼면 2건 다 빨개지는 것을 확인했다.

### 두 겹이 각각 무엇을 하는지 눈으로 봤다

화이트리스트 호출을 잠시 우회시키고 안전망만 남긴 채 돌렸다.

```
unknownField,asc   500 → 400   PASSED     안전망이 실제 앱에서 동작한다
productId,desc     expected:<200> but was:<400>
id,desc            expected:<400> but was:<200>
```

**안전망만으로는 증상만 낫는다.** 모르는 이름이 400 이 될 뿐,
응답에 보이는 이름은 실패하고 내부 이름만 성공하는 계약 역전은 그대로 남는다.

### 표가 썩지 않게

`ProductSortKeysTest` 가 허용 표를 전수로 건다 — 왼쪽은 전부 응답이 내보내는
이름이어야 하고, 오른쪽은 전부 `Product` 에 실제로 있는 속성이어야 한다.
어느 쪽 필드를 지우거나 이름을 바꿔도 표가 조용히 썩지 않고 그 자리에서 깨진다.
키를 하나씩 적어 단언하지 않는 이유는 [ErrorCodeTest](testing.md) 와 같다 —
표를 옮겨 적은 단언은 표가 틀리면 같이 틀린다.

> 이 수정을 다시 훑으면서 [D-025](#d-025) 가 나왔다. **표가 열어 준 키 자체**의 문제였고,
> 같은 자리에서 세 가지를 함께 정비했다 — 되싣는 문자열의 개행 제거(`EchoedInput`),
> 상위 클래스를 못 보던 전수 대조, 그리고 `ProductView` 로 걸려 있던 "응답이 내보내는 이름" 의 기준.
> 표의 왼쪽 끝은 이제 실제 응답을 만드는 `ProductResponse` 다.

---

<a id="d-025"></a>
## D-025 정렬 결과가 결정적이지 않아 페이지 경계에서 상품이 중복·유실

**상태** 수정됨
**영향** 데이터 정합성 — 페이지 경계 중복·유실
**위치** `apps/catalog/.../core/domain/ProductSort.java`
**재현** `ProductSortKeysTest`, `ProductSortTest` (통합 소스셋)

### 무슨 일이

[D-024](#d-024) 가 연 정렬 키 중 `price` 와 `name` 은 **유일하지 않다.**
동값이 한 페이지를 넘으면 동순위 사이의 순서를 DB 가 statement 마다 다시 정한다.

```
GET /api/v1/products?sort=price,asc&page=0   →  ORDER BY price LIMIT 20 OFFSET 0
GET /api/v1/products?sort=price,asc&page=1   →  ORDER BY price LIMIT 20 OFFSET 20
```

18,000원짜리 상품이 20건을 넘는 순간, 두 요청 사이에 동순위의 순서가 유지된다는 보장이 없다 —
**어떤 상품은 두 페이지에 모두 나오고, 어떤 상품은 어느 페이지에도 안 나온다.**
정렬을 주지 않은 요청은 `ORDER BY` 가 아예 없어 같은 부류이고, 그쪽이 훨씬 흔하다.

### 왜 지금까지 안 걸렸나

`TrackACreatorFlowTest` 가 **e2e 에서 이미 밟았다.** 정렬 없이 첫 페이지에서 상품을 찾다가
"22번째 상품에서 터졌다" 는 주석을 남기고 `?sort=productId,desc` 로 바꿨다.
거기서 함정은 **호출하는 쪽이 피해 갔을 뿐** 서버는 그대로였다 — 정렬을 주지 않거나
유일하지 않은 키로 정렬하는 다음 클라이언트가 같은 자리를 다시 밟는다.

통합 테스트도 이것을 잡을 수 없었다. 상태 코드와 `$.data` 가 배열인지만 봤고,
픽스처가 1건이라 순서를 단언할 수조차 없었다 — `apply` 가 정렬을 통째로 떨어뜨려도 초록이었다.

### 수정

표를 통과한 뒤 **마지막 절로 `id desc` 를 붙인다.** 정렬을 주지 않은 요청도 같은 줄을 지나
`id desc` 하나를 받는다(최신순 — e2e 가 명시적으로 요구하던 바로 그 순서다).
이미 `id` 로 정렬하는 요청에는 붙이지 않는다.

`id` 를 **허용 키로는 거절하면서** 정렬 절로는 항상 붙이는 것이 모순이 아닌 이유 —
허용 목록이 정하는 것은 **계약의 이름**이고, 꼬리표가 만드는 것은 **결정성**이다.
부르는 쪽은 여전히 `id` 라는 이름을 쓸 수 없고, 알 필요도 없다.

### 같은 자리에서 함께 막은 것

인증 없는 공개 경로(`catalog-public`)라 되싣는 값과 절 개수도 그대로 두면 안 됐다.

- **되싣기** — 거절 메시지는 요청 문자열을 인용하고, 그 메시지는 응답이자 로그다.
  `?sort=%0A2026-08-13 ERROR ...` 의 개행이 `log.warn` 을 타고 **로그 줄을 위조**한다.
  `EchoedInput.safe` 가 제어문자를 지우고 길이를 자른다. 되싣는 자리가 둘(`ProductSort`,
  `UnknownPropertyExceptionHandler`)이라 `common:core` 에 한 벌만 둔다.
- **절 개수** — 스프링은 `sort` 파라미터의 **반복 횟수**를 막지 않는다
  (`PageableHandlerMethodArgumentResolver` 가 상한을 두는 것은 `size` 뿐이다).
  허용 키만 써도 수백 개 절짜리 `ORDER BY` 를 만들 수 있어, 표보다 많은 절은 거절한다 —
  표보다 많으면 반드시 중복이고 두 번째 절부터는 순서에 영향이 없다.
- **안전망의 로그** — `UnknownPropertyExceptionHandler` 는 이제 **타입명을 로그에 남긴다.**
  이 예외는 클라이언트 오타로도 나지만 서버가 만든 잘못된 `Sort.by("...")` 로도 나고,
  둘 다 400 이라 5xx 알람으로는 구분되지 않는다. 타입명이 그 둘을 가르는 유일한 단서다.
  나가면 안 되는 것은 응답이지 로그가 아니다.

  원본 메시지를 통째로 싣지는 않는다 — 거기에 **요청 문자열이 들어 있다.**
  속성 이름만 `EchoedInput` 을 통과시키고 타입은 그대로 싣는다(서버가 정한 값이라 안전하다).

  이 성질은 **응답으로 확인할 수 없다.** JSON 직렬화가 개행을 이스케이프하므로
  막지 않아도 응답 본문에는 날 개행이 없다 — 위조가 일어나는 곳은 로그 스트림이다.
  그래서 테스트가 어펜더를 붙여 그 줄을 직접 읽는다.

### 가드가 두 군데서 어긋나 있었다

- `entityProperties()` 가 `getDeclaredFields()` 만 봤다. `Product` 는 `BaseTimeEntity` 를
  상속하므로 `createdAt`·`updatedAt` 이 안 보인다 — Spring Data 는 문제없이 해석하는데
  **맞는 키를 표에 넣는 순간 가드가 거짓으로 깨진다.** 상위 클래스 체인을 훑도록 고쳤다.
- "허용 키는 응답이 내보내는 이름" 을 `ProductView` 로 걸어 뒀다. 그 레코드는 스스로
  "캐시 페이로드가 API 응답 계약과 분리된다"고 적어 둔 읽기 모델이라, 응답 필드가 바뀌어도
  이 가드는 초록으로 남는다. 기준을 실제 응답을 만드는 `ProductResponse` 로 옮겼다.

### 테스트가 실제로 잡는지 확인했다

꼬리표를 붙이는 줄을 지우고 단위 테스트를 돌렸다.

```
[D-025] 유일하지 않은 키로 정렬하면 마지막에 id 가 붙는다   FAILED
[D-025] 정렬을 주지 않으면 기본 순서는 최신순이다            FAILED
unpaged 로 불러도 터지지 않는다                              FAILED
```

`apply` 가 정렬을 통째로 떨어뜨리게 두고 통합 테스트를 돌렸다 — **5건 전부 빨개진다.**
고치기 전이라면 같은 변경에서 4건이 초록이었다.

되싣기 방어도 같은 방식으로 확인했다. 안전망에서 `EchoedInput` 을 빼자
로그 어펜더를 읽는 테스트가 빨개진다 — 응답만 보던 단언은 그대로 초록이었다.

---

<a id="d-026"></a>
## D-026 컨슈머가 멈추면 랙 지표도 같이 멈춰 적체가 관측되지 않는다

**상태** 우회함 — 지표 자체는 고칠 수 없다. 판정 근거를 브로커로 옮겼다
**영향** 관측 불가 — 사고가 알람에 도달하지 않는다
**위치** `kafka_consumer_fetch_manager_records_lag` (Kafka 클라이언트 지표)
**재현** `ConsumerLagMetricTest#appMetricGoesSilent`
**대조** `ConsumerLagMetricTest#brokerOffsetsSeeBacklog` — 같은 상황에서 브로커는 맞는 값을 낸다

```
expected: 5000L
 but was:    0L
```

### 무슨 일이

이 지표는 컨슈머 클라이언트가 **직전 fetch 응답에서 본 값**이다.
그래서 fetch 가 멈추면 값도 멈춘다.

```
컨슈머 정상 소비 → 랙 0 보고            ← 지표가 붙어 있다는 것은 확인된다
컨슈머 정지
백로그 5,000건 유입
                 → 브로커: 랙 5,000     ← 커밋 오프셋은 컨슈머와 무관하게 남아 있다
                 → 지표  : 랙 0         ← 마지막으로 본 것이 0 이었다
```

**랙이 위험한 상황은 대개 컨슈머가 멈춘 상황이다.** 즉 지표가 가장 필요한 순간에 침묵한다.
값이 틀린 것이 아니라 갱신이 멈춘 것이라, 그래프만 보면 "적체 없음"과 구분되지 않는다.

### 우리 코드의 버그가 아닌데 왜 대장에 올리나

**틀린 것은 지표가 아니라 그 지표를 판정 근거로 고른 선택이다.** 그 선택이 세 군데 박혀 있었다.

- `scripts/perf/collect-consumer.sh` 가 이 값을 `lag` 컬럼으로 뽑았다
- [performance.md](performance.md) 11-1 이 이 값을 **랙의 주 판정 수단**으로 명시했다
- 11-2 는 그 값으로 "랙 0" 을 다섯 컨슈머에 대해 보고했다 — 그것이 근거가 되지 못한다

그리고 컨슈머 적체에는 **알람이 아예 없었다.** `stove_outbox_pending` 은 발행이 끝나면 0 이라
컨슈머가 밀려도 생산자 지표에는 나타나지 않는다(11장). 볼 수 있는 유일한 자리가 이 지표였고,
그 자리가 비어 있었다.

### 실측 둘

| 환경 | 실제 랙 (브로커) | 지표가 보고한 값 |
|---|---:|---:|
| OCI — payment 를 내려 둔 동안 ([perf-tuning.md](perf-tuning.md) 4절) | **113,517** | **0** |
| 로컬 60 RPS soak ([performance.md](performance.md) 12장) | **2,479** | **209** (최대) |

두 번째 줄이 첫 줄보다 나쁘다. **0 이면 "이 지표는 안 붙었나" 라도 의심하는데,
209 는 그럴듯해서 그대로 믿게 된다.**

### 수정

지표를 고칠 수는 없다 — 클라이언트가 못 본 것을 말하게 할 방법이 없다.
판정 근거를 브로커의 커밋 오프셋으로 옮겼다.

| | |
|---|---|
| 측정 | `scripts/perf/collect-lag.sh` — `kafka-consumer-groups.sh --describe` 를 주기적으로 |
| 상시·알람 | `docker-compose.yml` 의 `kafka-exporter` → Prometheus `kafka-exporter` 잡 |
| 알람 | `infra/prometheus/alerts.yml` 의 `stove-consumer` — `ConsumerLagGrowing` · `ConsumerStalled` |
| 표기 | `collect-consumer.sh` 의 컬럼을 `lag` → `lag_reported` 로. 계속 긁되 판정에는 쓰지 않는다 |

`ConsumerStalled` 가 이 결함이 직접 낳은 규칙이다 — 랙이 있는데 커밋 오프셋이 5분간
움직이지 않는 상태. **이 결함이 지목하는 바로 그 상황이고, 앱 지표로는 볼 수 없다.**

### 이 항목이 닫히는 조건

다른 항목과 다르다. **재현 테스트가 통과해서 닫히지 않는다** — 통과할 수 없다.
닫히는 조건은 **앱 지표를 랙 판정에 쓰는 자리가 하나도 남지 않는 것**이고,
그때 이 테스트는 태그를 떼는 것이 아니라 지운다. 되돌아갈 회귀 방어선이 없기 때문이다.

남겨 두는 동안의 값은 하나다 — 누군가 다시 이 지표로 알람을 걸려고 하면 이 테스트가 막는다.
`brokerOffsetsSeeBacklog` 쪽은 태그가 없어 기본 빌드에 남는다. **그쪽이 진짜 방어선이다.**

### 이 결함이 드러낸 빌드 구멍

`known-defect` 제외가 `test` 태스크에만 걸려 있었다. `defectTest` 는 "결함은 층을 가리지 않는다"며
통합 소스셋까지 훑는데, 정작 그 소스셋에 결함 테스트를 두면 `./gradlew build` 가 빨개진다.
**결함 재현 테스트가 0건인 동안에는 드러나지 않던 구멍이고**, 실 브로커가 있어야 재현되는
D-026 이 통합 계층에 들어오면서 처음 걸렸다. `integrationTest` 에도 같은 제외를 걸었다.

---

<a id="d-027"></a>
## D-027 인프라 장애를 지급 불가로 판정해 정상 결제를 환불

**상태** 수정됨
**영향** 금전 손실 · 사용자 영향
**위치** `apps/license/.../config/KafkaErrorHandlerConfig.java`
**재현** `KafkaErrorHandlerConfigTest#storageFailureIsParkedNotCompensated`,
`#transactionFailureIsStorageFailure`, `#nestedSqlExceptionIsStorageFailure`
**실측** [chaos.md](chaos.md) 3장 — 60초 DB 장애에 정상 결제 **8건 환불**

### 무슨 일이

[D-002](#d-002) 가 보상 트리거를 리스너에서 recoverer 로 옮겼다. 그래서 보상은 이제
재시도가 전부 소진된 뒤에만 불린다 — **언제** 보상하는가는 고쳐졌다.

그런데 **무엇을 보고** 보상하는가는 그대로였다. recoverer 의 판단 근거는 하나뿐이다.

```java
// 재시도가 소진됐다 → 곧바로 보상
licenseService.recordIssueFailure(event.orderNo(), event.memberId(), reasonOf(exception));
```

재시도 소진은 **"지급할 수 없다"가 아니라 "네 번 물어봤는데 답을 못 받았다"**는 뜻이다.
그 둘을 같게 취급하면 license 의 DB 가 1분 끊긴 것만으로 정상 결제가 환불된다.

**D-002 의 회귀 테스트가 그 동작을 고정하고 있었다.**

```java
recoverer.accept(record, new DataAccessResourceFailureException("connection pool exhausted"));
verify(licenseService).recordIssueFailure(...);   // "커넥션풀이 마르면 환불한다"
```

### 왜 여태 안 터졌나 — 우연히 안전했다

`license-db-denied`(스키마 전체 차단)로 재면 **환불이 0건**이다. 보상을 기록하려는
`outbox_event` 쓰기도 같이 실패해 예외가 나가고 DLT 로 가기 때문이다.

`license-table-denied`(원장 테이블만 차단)로 재면 **8건이 환불된다.**
`outbox_event` 는 멀쩡하므로 보상 이벤트가 성공적으로 적재된다.

**차이는 시스템이 내린 판단이 아니라 장애가 어디에 걸렸느냐다.**
지급은 `license` 에 쓰고 보상은 `outbox_event` 에 쓴다 — 한쪽만 끊기는 상황이 실재한다.

환불 건수는 결제 건수가 아니라 **재시도 회전 수**에 비례한다. 블로킹 재시도가 파티션을
멈추므로 레코드 하나가 7초를 쓴다 — 대략 `장애 지속시간 ÷ 7초` 다.

### 수정

보상에 관문 둘을 세웠다. 자동 환불의 조건이 **"실패했다"**에서
**"실패했고, 그 실패가 저장소 탓이 아니며, 실제로 지급되지도 않았다"**로 좁아진다.

| 관문 | 판정 | 아니면 |
|---|---|---|
| 원인 | 저장소 장애인가 (`DataAccessException`·`TransactionException`·`SQLException`) | 보상하지 않고 DLT |
| 결과 | 그 주문에 라이선스가 이미 있는가 → [D-028](#d-028) | 보상하지 않고 DLT |

근거는 **비용의 비대칭**이다. 환불은 되돌리기 어렵고 보류는 되돌리기 쉽다.
좁아진 만큼은 DLT 에 쌓이고 `MessagesDeadLettered`(critical)가 사람을 부른다.

원인 사슬을 **끝까지** 훑는다. 리스너의 예외는 컨테이너와 스프링 변환기를 거치며 두 번 감싸이므로
맨 바깥만 보면 거의 항상 놓친다. 세 갈래를 잡는 것도 그래서다 —
실측에서 DB 를 끊었을 때 실제로 나온 것은 `CannotCreateTransactionException`
("Could not open JPA EntityManager")이고 **이건 `DataAccessException` 이 아니다.**

### 다시 확인한 숫자

| | 수정 전 | 수정 후 |
|---|---:|---:|
| `license-table-denied` 60초 — 환불 | **8** | **0** |
| 같은 회차 — 보류(DLT) | 0 | **7** |
| `license-db-denied` 60초 — 환불 | 0 | 0 |
| DLT 재투입 후 지급 | — | **7건 전부, 1초 이내** |
| 최종 대사(결제완료 : 라이선스) | — | **115 : 115** |

같은 대사표에 남은 `CANCELED 8` 이 수정 전 회차의 오지급 환불이다. **그건 되돌아오지 않는다.**

### 남은 부분

**장애 발생부터 알람까지의 시간을 재지 않았다.** `MessagesDeadLettered` 는
`increase(...[5m])` 라 발화까지 최대 5분인데 실측으로 확인하지는 않았다.
보류가 안전한 것은 **사람이 제때 본다는 전제 위에서**이므로 그 전제를 재는 것이 다음 순서다.

그리고 **자동 환불이 실제로 발동하는 경우가 크게 줄었다.** 남는 방아쇠는 저장소와 무관한
실패뿐인데(계약 위반 이벤트, 도메인 거절 등) 그런 경우는 대개 코드 결함이라 사람이 봐야 한다.
**Saga 보상이 사실상 수동에 가까워진 것**은 의도한 결과이지만, 그렇다면
"자동 보상" 이라는 이름값을 하는지는 다시 볼 문제다. 지금은 **틀린 자동 환불보다 느린 수동 처리가
낫다**는 판단으로 두었고, 근거는 위 대사표다 — 보류 7건은 1초 만에 돌아왔고 환불 8건은 돌아오지 않았다.

---

<a id="d-028"></a>
## D-028 이미 지급된 주문에 보상 환불이 발동

**상태** 수정됨
**영향** 금전 손실 — 물건을 받은 주문의 환불
**위치** `apps/license/.../config/KafkaErrorHandlerConfig.java`, `core/service/LicenseService.java`
**재현** `KafkaErrorHandlerConfigTest#alreadyIssuedOrderIsNotCompensated`
**실측** [chaos.md](chaos.md) 3-4 — 장애 회차 로그에서 관측

### 무슨 일이

[D-027](#d-027) 회차의 로그에 이 두 줄이 **같은 주문번호로** 남아 있었다.

```
15:33:26.291 INFO  라이선스 지급 orderNo=ORD…BY35MMJ7MD products=[1]
15:34:08.523 ERROR 라이선스 지급 최종 실패 — 보상으로 종결한다 orderNo=ORD…BY35MMJ7MD
```

**지급은 성공했다.** 트랜잭션이 커밋된 뒤 오프셋이 커밋되기 전에 커넥션이 끊겨
같은 레코드가 다시 배달됐고, 그 재처리가 실패하자 recoverer 가 보상을 시작했다.

이 회차에서는 보상 기록마저 실패해 DLT 로 갔지만, 원장 테이블만 끊긴 조건이었다면
그대로 **`inconsistent` — 환불했는데 물건은 줬다**가 된다. 네 결말 중 가장 나쁜 칸이다.

### 왜 D-027 과 나누는가

원인이 다르다. D-027 은 **왜 실패했는지**를 안 보는 문제이고, 이쪽은 **정말 실패했는지**를
안 보는 문제다. D-027 의 관문 1을 통과한 실패(저장소 탓이 아닌 실패)에도 이 경로는 열려 있다.

그리고 이건 저장소 장애에만 딸린 것이 아니다. **오프셋 커밋과 트랜잭션 커밋이
원자적이지 않은 한** 중복 배달은 정상 동작이고, 그 위에서 "실패했으니 환불" 은 항상 틀릴 수 있다.

### 수정

보상 직전에 `LicenseService#isIssued(orderNo)` 로 원장을 확인한다. 있으면 보상하지 않고
DLT 로 보낸다 — 재투입하면 `issue()` 가 멱등하게 돌고 소유 상태 이벤트를 재발행하므로
하위 서비스까지 다시 맞춰진다([D-010](#d-010)).

회수된 라이선스도 '있음' 으로 센다. 지급 자체는 일어났으므로 "지급 실패" 라는 보고가
사실이 아닌 것은 마찬가지이고, 회수됐다면 환불은 이미 그쪽 경로로 처리됐다.

확인 질의가 실패하면 그건 저장소 장애이므로 관문 1이 받는다 — 두 관문의 순서가 그래서 중요하다.

---

<a id="d-029"></a>
## D-029 미결제 주문에 만료가 없어 옛 가격으로 영구히 결제

**상태** 수정됨
**영향** 금액 조작 — 지난 가격으로 구매
**위치** `apps/payment/.../core/domain/Payment.java`, `core/service/PaymentService.java`
**재현** `PaymentWindowTest` · `PaymentCheckoutWindowTest` · `StrandedRefundResumeTest` (통합 소스셋) 15건
**실측** [chaos.md](chaos.md) 5장 — **30일 지난 주문이 옛 가격으로 승인됨**

### 무슨 일이

금액이 서버 가격으로 확정되는 것은 **주문을 만드는 순간 한 번뿐이다**(`PlaceOrderFacade` →
`catalogPort.quote`). 그 뒤 단계는 전부 "아까 정한 금액과 같은가"만 본다 —
사전등록은 `payment.getAmount()` 를 PG 에 넘기고, 승인 콜백은 `paidAmount != this.amount`
만 대조하고, 지급은 금액을 보지도 않는다. **아무도 catalog 에 다시 묻지 않는다.**

그런데 `Order` 에도 `Payment` 에도 만료 개념이 없다. 상태값에도 없다
(`CREATED·PAID·CANCELED·FAILED`). 즉 **창의 크기가 무한이다.**

전체 스택에서 확인했다.

```
39,000원 주문 생성 → 상품 가격 78,000원으로 변경 → 주문 나이를 30일로 되돌림
                   → 결제 승인 → PAID 39,000원 → 라이선스 지급
```

기획한 할인이 끝나도, 가격을 올려도, 이미 만들어 둔 주문은 옛 가격 그대로 성립한다.

### 고칠 방법 후보와 버린 이유

- **승인 콜백 시점에 검사** — 버렸다. 그 시점에는 이미 PG 에서 돈이 움직였다.
  되돌리려면 환불이 필요해지므로 **문제를 막는 대신 문제를 하나 더 만든다.**
- **order 의 스케줄러로 오래된 주문을 만료** — 버렸다. order 가 만료를 표시해도
  payment 는 `OrderCanceled` 를 듣지 않아 결제 대기 레코드가 그대로 열려 있다.
  **아무도 반응하지 않는 스케줄러는 안 넣는 것보다 나쁘다** — 있는 것처럼 보이기 때문이다.
- **결제 준비 시점에 catalog 에 재견적** — 버렸다. 쓰기 경로에 동기 홉이 하나 더 늘고,
  catalog 장애가 결제 불가로 번진다. 얻는 것은 정확도인데 **창을 좁히는 것으로 충분하다.**

### 수정

`stove.payment.window`(기본 30분)를 넘긴 주문은 **사전등록이 막힌다**(`PAYMENT_WINDOW_EXPIRED`).

승인은 `PENDING` 에서만 열리므로 사전등록을 닫으면 **돈이 움직이는 길 전체가 닫힌다.**
검사는 PG 호출보다 **앞**에 둔다 — 뒤에 두면 우리 장부에는 없는 PG 거래가 하나 생겨
대사에서 원인을 알 수 없는 잔여로 남는다.

기준 시각은 `payment.created_at` 이다. 이 행은 `OrderCreated` 를 받아 만들어지므로
주문 생성 시각의 대리값이고, **금액이 확정된 시각이 바로 그때다.**

### 다시 확인한 숫자

| | 수정 전 | 수정 후 |
|---|---|---|
| 30일 지난 미결제 주문의 결제 | **승인됨(옛 가격 39,000)** | `PAYMENT_WINDOW_EXPIRED` |
| 만료 주문의 PG 거래 생성 | — | **0건** |
| 만료 후 결제 상태 | PAID | READY 유지 |
| 창 안(30분 이내) 주문 | 승인됨 | 승인됨 — 변화 없음 |

### 후속 — 사전등록 이후의 창도 닫았다 (자동 환불)

**재현** `PaymentCheckoutWindowTest` (통합 소스셋) 5건

처음 수정은 창을 하나만 닫았다. 사전등록까지는 창 안이었는데 **결제창을 며칠 열어 둔** 경우가
그대로 남아 있었고, 그걸 닫으려면 먼저 정해야 할 것이 있었다 —
**콜백을 거절하는 순간 "PG 에는 승인, 우리는 거절" 이 생긴다.**

거절하지 않기로 했다. 승인 콜백이 도착한 시점에는 PG 에서 이미 돈이 움직였고,
예외를 던지면 우리 장부에만 없는 상태가 되어 **대사에서 원인을 알 수 없는 잔여**가 된다.
그건 막으려던 문제를 다른 문제로 바꾼 것이지 없앤 것이 아니다.

**승인을 장부에 적고 곧바로 되돌린다.**

```
결제창 만료 후 승인 도착
  → PENDING → PAID    (PG 와 우리 장부가 일치한다)
  → PAID → CANCELING  (같은 트랜잭션)
  → PG 환불           (트랜잭션 밖 — RefundFacade 와 같은 순서)
  → CANCELING → CANCELED + PaymentCancelled 적재
```

**`PaymentCompleted` 는 내보내지 않는다.** 내보내면 license 가 지급하고 settlement 가 매출을 적은 뒤
곧이어 둘 다 되돌린다 — 사용자에게는 게임이 잠깐 생겼다 사라지고 원장에는 매출과 상계가 한 쌍 남는다.
**일어나지 않을 판매는 알리지 않는다.** 하위 셋은 뒤이은 `PaymentCancelled` 만 받고
전부 "되돌릴 것이 없다"로 정상 종료한다(order 는 `CREATED` 에서 취소, license 는 라이선스 없음,
settlement 는 매출 원장 없음 → `warn` 후 종료).

**시계를 둘로 나눈다.** `prepared_at` 컬럼을 새로 둔다(`V6__payment_checkout_window.sql`).

| 값 | 지키는 것 | 기본 |
|---|---|---|
| `stove.payment.window` | 옛 가격이 유효한 기간 (주문 → 사전등록) | 30분 |
| `stove.payment.checkout-window` | 카드번호를 넣는 데 주는 시간 (사전등록 → 승인) | 15분 |

하나로 재면 주문 창이 30분일 때 29분째에 결제창을 연 사용자에게 1분만 주게 되고,
그걸 피하려고 창을 넓히면 이번에는 옛 가격이 오래 유효해진다.
**한쪽을 고치면 다른 쪽이 나빠지는 값은 같은 값이 아니다.**

`prepared_at` 이 없으면 **만료가 아니라고 답한다.** 판단 근거가 없는데 '만료됨'이라고 답하면
정상 결제가 자동 환불된다 — [D-027](#d-027) 의 "모르면 환불하지 않는다"와 같은 규칙이다.

**자동 환불에는 알람을 붙였다.** 이건 사용자 요청도 운영자 조작도 아닌,
시스템이 스스로 돈을 되돌리는 유일한 HTTP 경로다. 조용히 늘어나면
**사용자만 "결제했는데 취소됐다"를 겪고 우리는 모른다.**
지표는 `stove.payment.auto-refunded`(사유 태그), 규칙은 `AutoRefundsRising`
— 한 건이 아니라 **추세**로 본다(15분 5건). 늘었다면 우리 창이 PG 세션보다 짧아졌다는 뜻이다.

### 후속 2 — `CANCELING` 에서 멈춘 건을 이어서 끝낸다

**재현** `StrandedRefundResumeTest` (통합 소스셋) 6건

취소는 "의도 기록 → PG 환불 → 확정" 세 걸음이고 PG 환불이 트랜잭션 밖에 있다. 그 사이에서
멈추면 `CANCELING` 으로 남는데, 그건 **돈이 나갔는지 불확실하다**는 뜻이다.

`RefundFacade` 는 그 상태를 두고 *"재시도 대상이 된다"* 고 적어 두었다. **그런데 재시도를
거는 쪽이 없었다.** 관측 가능하게 만들어 둔 것과 실제로 해소되는 것은 다르다 —
사용자에게는 그동안 "환불했다는데 돈이 안 들어왔다" 로 보인다.

자동 환불(후속 1)을 넣으면서 더 급해졌다. **사용자 요청 환불은 사람이 다시 누르기라도 하는데,
자동 환불은 아무도 다시 누르지 않는다.**

`RefundSweeper` 가 1분마다 `refund-resume-after`(기본 2분)를 넘긴 `CANCELING` 을 집어
재개한다. 안전한 근거는 `PgClient#cancel` 의 **멱등 계약 하나**다 —
실제로 나갔는지 모르는 채로 다시 걸어도 이중 환불이 되지 않는다.
**그 계약이 없으면 이 기능은 존재할 수 없다.**

세부 판단 넷:

- **사유를 이어 쓴다.** 착수할 때 적힌 `cancel_reason` 을 그대로 쓴다. 새로 지어내면
  "사용자 환불" 로 시작한 건이 재개 뒤 "시스템 재시도" 로 남아 정산·CS 가 원인을 되짚을 수 없다.
- **한 건의 실패가 다음 건을 막지 않는다.** PG 가 죽어 있으면 전부 실패할 텐데, 첫 건에서 멈추면
  나머지는 **시도조차 안 된 것인지 실패한 것인지 구분되지 않는다.**
- **방금 착수한 건은 집지 않는다.** 진행 중인 환불을 옆에서 한 번 더 부르게 된다 —
  멱등이라 사고는 아니지만 "몇 번 시도했나" 가 흐려지고, 그 값이 PG 연동을 보는 창이다.
- **분산 락(ShedLock)을 건다.** 이중 환불을 막는 장치가 아니다(그건 멱등 계약이 한다).
  **락이 막는 것은 재개 로그와 이벤트가 대수만큼 겹쳐 시도 횟수를 알 수 없게 되는 것**이다.

**알람은 게이지에 건다.** 재개 카운터는 스윕이 계속 실패해도 늘지 않고, 계속 성공해도
남아 있는 건수를 말해 주지 않는다. 물어야 하는 것은 "지금 몇 건이 불확실한가" 이므로
`stove.payment.canceling`(게이지) → `RefundsStuckInCanceling`(critical, 10분).

### 남은 부분

주문 쪽 상태는 여전히 `CREATED` 로 남는다(만료 케이스는 `PaymentCancelled` 로 취소된다).
금전 경로는 닫혔으므로 정합성 문제는 아니지만, **결제를 시작조차 하지 않은 주문**은 계속 쌓인다.

---

<a id="d-030"></a>
## D-030 이벤트 재처리로 유실을 복구할 수 없다 — Inbox 가드가 먼저 막는다

**상태** 절차로 닫음 (코드 변경 없음)
**영향** 복구 불가 — **조용히** 실패한다
**위치** `common/messaging/.../inbox/ProcessedEventGuard.java` 와 그 호출부
**재현** `LicenseReplayRecoveryTest` (통합 소스셋) 4건
**절차** [runbooks/license-db-loss.md](runbooks/license-db-loss.md)
**실측** [chaos.md](chaos.md) 4장 — 200건 유실 후 오프셋 리셋으로 **0건 복구, 예외 0건**

### 무슨 일이

`stove_license.license` 의 행이 사라져도 `PaymentCompleted` 는 카프카에 남아 있으므로
오프셋을 되돌리면 원장을 되살릴 수 있어야 한다. 그런데 Inbox 멱등 가드가
`(event_id, consumer_group)` 로 **먼저** 막는다.

```
PaymentCompleted(event_id=E) → firstDelivery(E,"license") → 이미 있다 → return false
                                                              issue() 가 그대로 종료
```

원장만 지워지고 가드 행이 살아 있으면 되읽은 이벤트가 **전부 "이미 처리함"** 이 된다.

그리고 **운영이 쓸 수 있는 재처리 수단은 셋 다 `event_id` 를 보존한다.**

| 수단 | event_id |
|---|---|
| 컨슈머 그룹 오프셋 리셋 | 원본 그대로 |
| `DltOpsService#replay` | 원본 헤더를 그대로 되돌린다 |
| Outbox 재적재 | 적재된 이벤트 그대로 |

즉 **`event_id` 가 바뀌는 경로는 운영에 존재하지 않는다.**

### 가장 나쁜 것은 조용하다는 점이다

예외도 없고, 실패 지표도 안 오르고, 로그는 `info`("중복 이벤트 스킵")다.
200건을 되읽어 **0건이 복구됐는데 컨슈머 ERROR 로그가 0건**이었다.
복구 절차를 돌린 사람에게는 성공과 구분되지 않는다.

### [D-010](#d-010) 의 회귀 테스트가 없는 경로를 지키고 있었다

`LicenseIssueEventTest#replayShouldRepublishOwnershipEvent` 는 **새 `event_id`** 로
`issue()` 를 부르고, 그 주석은 그것을 *"운영에서 이벤트 유실을 복구하는 표준 수단"* 이라고
적어 두었다. 그런 수단은 없다.

**D-010 이 고친 재발행 로직은 가드 뒤에 있어 실제 복구 경로에서는 도달조차 하지 않는다.**
[D-021](#d-021)(규칙이 대상 0건을 검사)·[D-023](#d-023)(게이트가 컨테이너 0개에도 통과)과
같은 **공허 통과**다 — "방어선이 있다"와 "방어선이 그 자리를 지킨다"는 다르다.

### 고칠 방법 후보와 버린 이유

- **가드를 걷어낸다** — 버렸다. `issue()` 는 멱등하지만 `revoke()` 는 아니다.
  가드가 유일한 방어선인 경로가 남아 있다.
- **재투입 시 새 `event_id` 를 부여한다** — 버렸다. 그 이벤트를 함께 듣는 다른 컨슈머
  (order·settlement)에서는 **정상 처리된 이벤트가 새 이벤트로 다시 반영된다.**
  한 서비스의 복구를 위해 다른 서비스의 멱등을 깬다.
- **인박스 일괄 삭제 운영 엔드포인트** — 버렸다. 범위를 좁히지 못하는 삭제 API 는
  누르는 순간 전 컨슈머의 멱등 보호가 벗겨진다. 위험이 도구의 편의보다 크다.

### 수정 — 코드가 아니라 절차다

가드는 제 일을 하고 있다. 원장과 가드는 **같은 트랜잭션**에서 쓰이므로 정합적인 백업이면
둘이 함께 돌아온다. 깨지는 것은 **원장만 잃은 경우**뿐이다.

그래서 산출물은 런북이다 — [runbooks/license-db-loss.md](runbooks/license-db-loss.md).
핵심은 두 줄이다.

1. 가드 행을 **범위를 좁혀** 지운다 (`consumer_group='license' and event_type='PaymentCompleted'`).
   `event_type` 을 빼면 회수 가드까지 벗겨져 **되읽기가 회수를 다시 실행한다.**
2. 마지막에 **대사로 판정한다.** 실패가 관측되지 않으므로 복구를 판정할 수 있는 자리가 거기뿐이다.

`scripts/chaos/recover-license.sh` 가 절차를 그대로 실행하고, `--skip-inbox-purge` 로
**그 줄을 빼고 돌려 볼 수 있다.** 이유가 적혀 있지 않은 줄은 바쁠 때 생략되고,
이 줄은 특히 그렇다 — 빼도 아무 데서도 실패하지 않기 때문이다.

### 다시 확인한 숫자

| | 가드 삭제 없이 | 가드 삭제 포함 |
|---|---:|---:|
| 복구된 주문 | **0** | **200** |
| 대사(결제완료 : 라이선스) | **200 : 0** | 200 : 200 |
| 복구 속도 | — | 16.7 건/s |
| 컨슈머 ERROR 로그 | **0** | 0 |

---

<a id="d-031"></a>
## D-031 랙 알람이 조건이 참인 내내 한 번도 울리지 않는다

**상태** 수정됨
**영향** 관측 불가 — **알람의 공허 통과**
**위치** `infra/prometheus/alerts.yml` 의 `ConsumerLagGrowing`
**재현** [chaos.md](chaos.md) 9장 회차 A — 랙 592 가 10분 넘게 고정인데 발화 0회
**실측** 스크레이프 133회 중 랙 표본 126개(결측 5.3%), `up` 은 내내 1

### 무슨 일이

`license` 컨슈머를 15분간 세우고 알람을 지켜봤다. 랙은 592 에서 꿈쩍하지 않았고
임계는 500 이다. 그런데 **`ConsumerLagGrowing` 은 끝까지 울리지 않았다.**

```
+275s  pending   (랙 508)      ← 10분 시계 시작
+682s  inactive               ← 리셋
+692s  pending
+763s  inactive               ← 리셋
+774s  pending
+916s  inactive               ← 리셋
```

랙은 그 사이 내내 592 였다. **조건이 거짓이 된 적이 없는데 시계가 네 번 리셋됐다.**

### 원인 — 스크레이프는 성공하는데 시계열만 빠진다

```
count_over_time(up{job="kafka-exporter"}[30m])              → 133
count_over_time(kafka_consumergroup_lag_sum{...}[30m])      → 126
count_over_time(stove_outbox_pending{...}[30m])             → 180 / 180
```

`up` 이 1이므로 **스크레이프는 전부 성공했다.** 익스포터가 응답은 하면서
그 컨슈머그룹 시계열만 빼고 준 것이다. 앱이 직접 내는 지표는 같은 구간에 한 번도 안 빠졌으니
익스포터 쪽 성질이고, 우리가 고칠 수 있는 자리가 아니다.

**`for` 는 조건이 한 번이라도 거짓이면 시계를 0으로 되돌린다.** 결측은 "거짓"이 아니라
"모름"인데 `for` 에게는 둘이 같다. 표본당 결측 5.3% 로 60표본을 연속으로 채울 확률은

```
0.947 ^ 60 ≈ 0.04
```

**약 4%다.** 규칙은 있고 문법도 맞고 조건도 참인데, 스물다섯 번에 한 번 울린다.

### 왜 여태 몰랐나

이 규칙은 [D-026](#d-026) 을 고치면서 새로 만들어졌고 `promtool check rules` 로 검증됐다.
**문법 검사는 통과한다.** 그리고 규칙을 만든 회차는 발화를 확인하지 않았다 —
"규칙 6개 로드" 까지가 검증이었다.

[D-021](#d-021)(규칙이 대상 0건을 검사) · [D-023](#d-023)(게이트가 컨테이너 0개에도 통과) ·
[D-030](#d-030)(회귀 테스트가 없는 경로를 지킴)과 같은 **공허 통과의 네 번째**다.
넷 다 "장치가 있다"와 "장치가 그 자리를 지킨다"가 다르다는 같은 이야기이고,
이번 것은 그중에서도 **가장 오래 안 보였을 종류**다. 알람이 안 울리는 것은 정상 상태와
모습이 같기 때문이다.

### 수정

```promql
# 전
kafka_consumergroup_lag_sum > 500
for: 10m

# 후
min_over_time(kafka_consumergroup_lag_sum[10m]) > 500
```

`min_over_time` 은 창 안의 **있는 표본만** 본다 — 결측이 값을 무너뜨리지 않는다.
지속 조건이 창 안에 들어 있으므로 `for` 는 필요 없어진다.

**같은 그룹의 `ConsumerStalled` 가 이미 그 형태였다.** #35 가 거기서 `for: 5m` 을 뗀 이유는
"창과 `for` 를 겹쳐 달면 시계가 두 배로 어긋난다" 였는데, 옆 규칙에는 그 판단이 적용되지 않았다.
`ConsumerStalled` 는 결측 때 한 평가만 침묵하고 곧바로 다시 울렸다(실측 3회, 각 10초).
**같은 결측에 한쪽은 10초를 잃고 한쪽은 전부를 잃었다.**

### 다시 확인한 숫자

| | 수정 전 | 수정 후 |
|---|---|---|
| 랙 592 · 10분 지속 시 발화 | **0회** (pending 4회 리셋) | 발화 |
| 결측 1회의 대가 | **시계 전체 리셋** | 없음 |
| `ConsumerStalled`(대조, 원래 창 형태) | 결측당 10초 침묵 후 재발화 | 변화 없음 |

### 남은 부분

**익스포터가 왜 시계열을 빠뜨리는지는 안 봤다.** 브로커의 그룹 조회가 순간 실패하면
익스포터가 그 그룹을 통째로 생략하는 것으로 보이는데, 확인하려면 익스포터 쪽을 읽어야 한다.
우리 규칙이 결측에 견디게 만든 것으로 증상은 닫혔지만, **결측률이 올라가면
`min_over_time` 도 창 안에 표본이 없어 무응답이 된다.** 결측률 자체를 지켜보는 편이 낫다 —
`count_over_time(...[10m]) < N` 형태의 규칙이 다음 후보다.

> **원인은 [D-032](#d-032) 가 규명했다.** 브로커의 그룹 조회 실패가 아니었다 —
> 익스포터가 응답을 조립하는 경로에서 지표 한 줄을 흘리고 있었다.
> 위 추측("그룹을 통째로 생략")은 **틀렸다**: 통째로 빠지는 것이 아니라 집계값만 빠진다.

---

## D-032 익스포터가 스크레이프마다 지표를 정확히 한 줄씩 흘린다

**상태** 수정됨
**영향** 관측 불가 — [D-031](#d-031) 의 원인
**위치** `docker-compose.yml` 의 `kafka-exporter` (원인은 `danielqsj/kafka-exporter:v1.9.0` 안)
**재현** 익스포터를 1초 간격으로 직접 긁는다 — 60회 중 25회가 그룹 12개가 아니라 11개
**실측** 정상 응답 635줄 / 결측 응답 **634줄** (차이 정확히 1줄, 30회 관측)

### 무슨 일이

D-031 이 "익스포터가 왜 빠뜨리는지는 안 봤다" 로 남긴 자리다. 넷을 차례로 잘랐다.

**1. Prometheus 가 아니다.** 익스포터를 직접 긁어도 재현된다.

```
60회 스크레이프 중  12개 그룹 35회 · 11개 그룹 25회
```

**2. 응답 전체가 빠지는 것이 아니다.** 30분(10초 격자 181점)에서

```
12개 시리즈 : 115회
11개 시리즈 :  66회
10개 이하   :   0회      ← 두 그룹이 동시에 빠진 적이 없다
```

그룹마다 독립적으로 빠진다면 두 개가 겹치는 회차가 나와야 한다(12그룹 × 결측률 3%면
181회 중 9회쯤). **한 번도 없다.** 결측이 그룹의 성질이 아니라는 뜻이다.

**3. 그룹 조회는 성공한다.** 빠진 그룹의 다른 지표를 보면

```
kafka_consumergroup_current_offset{consumergroup="order",partition="0",topic="stove.payment.v1"} 461
kafka_consumergroup_current_offset{consumergroup="order",partition="1",...} 467
kafka_consumergroup_current_offset{consumergroup="order",partition="2",...} 488
kafka_consumergroup_current_offset_sum{consumergroup="order",topic="stove.payment.v1"} 1416
kafka_consumergroup_lag{consumergroup="order",partition="0",...} 0
kafka_consumergroup_lag{consumergroup="order",partition="1",...} 0
kafka_consumergroup_lag{consumergroup="order",partition="2",...} 0
                                                    ← lag_sum 만 없다
```

**커밋 오프셋도, 파티션별 랙도, 오프셋 합계도 전부 있다.** 빠지는 것은 `lag_sum` 하나다.

**4. 실패가 아니다.** `--verbosity=3 --log.enable-sarama` 로 올려도 결측 회차의 로그가
정상 회차와 **한 글자도 다르지 않다.** 응답은 `200` 이고 `promhttp_metric_handler_requests_total{code="500"}`
은 0이다 — 수집기가 오류를 만난 것도, 레지스트리가 떨어뜨린 것도 아니다.

### 원인

익스포터 소스에서 토픽 하나의 전송 순서는 이렇다.

```go
ch <- consumergroupCurrentOffset      // 파티션마다
ch <- consumergroupLag                // 파티션마다
ch <- consumergroupCurrentOffsetSum   // 토픽당
ch <- consumergroupLagSum             // 토픽당 ← 마지막
```

`lag_sum` 은 **토픽 블록의 마지막 전송**이고, 흘리는 것은 언제나 그 한 줄이다.
전송 자체에는 조건이 없다 — 바로 윗줄 `current_offset_sum` 이 나갔으면 `lag_sum` 도 나가야 한다.
**나가지 않았다는 것은 채널에서 사라졌다는 뜻이다.**

사라지는 자리는 `--concurrent.enable` 이 가른다. 기본값(꺼짐)은 여러 스크레이프가
수집 결과를 **공유**하는데, 그 공유 경로가 중간 채널을 비우는 끝에서 마지막 것을 놓친다.

### 수정

```yaml
command:
  - --kafka.server=kafka:19092
  - --web.listen-address=:9308
  - --concurrent.enable        # 추가
```

| | 수정 전 | 수정 후 |
|---|---|---|
| 그룹 12개가 온전한 스크레이프 | 35 / 60 | **40 / 40** |
| 응답 줄 수 | 635 또는 634 | 635 고정 |

실환경(Prometheus 10분 창, 스크레이프 60회)에서도 확인했다. **12개 그룹 전부 60/60 이다.**

```
up 스크레이프: 60
  catalog/stove.review.v1     60      payment/stove.license.v1    60
  download/stove.catalog.v1   60      payment/stove.order.v1      60
  download/stove.license.v1   60      review/stove.studio.v1      60
  download/stove.studio.v1    60      settlement/stove.payment.v1 60
  license/stove.payment.v1    60      store/stove.catalog.v1      60
  order/stove.payment.v1      60      studio/stove.review.v1      60
```

수정 전 같은 창의 보존율은 **0.750 ~ 0.983** 이었다(8-4 의 표). 전부 **1.000** 이 됐다 —
`ConsumerLagMetricGaps` 의 임계 0.5 가 이제 정상 상태에서 아주 멀어졌다.

> **대조군이 하나 우연히 생겼다.** 측정 도중 CI 가 `main` 의 compose 로 익스포터를 재생성해
> 플래그가 되돌아갔고, 같은 스택에서 **즉시 29/60 으로 돌아왔다.** 붙였다 뗐다 한 셈이다.

플래그 설명은 "큰 클러스터에서는 끄라" 고 경고한다 — 스크레이프마다 브로커를 실제로 부르기
때문이다. **브로커 1대에 컨슈머그룹 12개인 여기서는 그 경고가 적용되지 않는다.**
클러스터가 커지면 이 판단을 다시 봐야 한다.

### D-031 의 규칙은 그대로 둔다

원인을 고쳤다고 `min_over_time` 을 되돌리지 않는다. **결측에 견디는 것과 결측이 없는 것은
다른 보장이고, 둘 중 하나만 남기면 다음 익스포터 판올림에 다시 밟는다.**
`#39` 가 넣은 `ConsumerLagMetricGaps` 도 남긴다 — 그것이 이 수정이 풀리는 것을 잡는 장치다.

### 무엇이 틀렸었나

D-031 은 원인을 "브로커의 그룹 조회가 순간 실패하면 익스포터가 그 그룹을 통째로 생략" 으로
추측하고 "우리가 고칠 수 있는 자리가 아니다" 로 닫았다. **둘 다 틀렸다.**
그룹은 통째로 빠지지 않았고(집계값 한 줄만 빠졌다), 고칠 수 있는 자리였다(플래그 하나).

대장의 규칙이 여기서 값을 했다 — **추측은 넣지 않는다.**
그때 추측을 "원인" 으로 적었다면 이 항목은 열리지 않았을 것이다.

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
