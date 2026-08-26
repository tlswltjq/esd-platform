# 인계노트 — 이벤트 인프라 학습 세션

**기간** 2026-08-06 ~ 08-07
**기준 커밋** `e49f92a` (main)
**코드 변경** 없음 — 이 노트를 제외하면 전부 읽기·설명이었다.

새 세션에서 학습을 이어가기 위한 출발점이다.
**2·3장은 이미 끝난 것**이므로 다시 설명하거나 다시 확인할 필요가 없고,
**4장부터가 다음에 할 일**이다.

> **2026-08-10 갱신.** 이 노트를 쓴 뒤 [decisions.md](decisions.md) 16~19 번이 들어가면서
> 근거로 든 코드가 몇 군데 움직였다. 달라진 자리는 본문에 그때그때 표시했고,
> **5장의 열린 질문 ②③ 은 닫혔다.** 3장의 "확인한 사실" 은 이 갱신에서 다시 대조한 값이다.
> 결론(2장)은 하나도 바뀌지 않았다 — 움직인 것은 근거의 위치이지 사실이 아니다.

---

## 1. 이 세션의 대화 규칙

새 세션도 같은 방식으로 이어가려면 아래를 따른다.

| 규칙 | 내용 |
|---|---|
| **caveman 미적용** | `MEMORY.md` 에 "stove 프로젝트는 caveman 스타일" 이 있으나, 이 학습 세션에서는 명시적으로 해제했다. **상세히 설명한다.** |
| 근거 우선 | 일반론이 아니라 **이 저장소의 커밋과 실제 코드**로 설명한다. `파일:줄번호` 로 짚는다. |
| 범위는 좁게 | 질문에 답하는 데 필요한 만큼만. 옆 주제로 번지지 않는다. |
| 모르는 것은 모른다고 | 확인하지 않은 것은 "관찰" 로 표시하고 단정하지 않는다. |

---

## 2. 이 세션에서 이해한 것

질문 순서대로 결론만 적는다. 각 항목의 근거 파일을 함께 둔다.

### 2.1 왜 릴레이인가 — 동기 발행이 안 되는 이유

동기 발행은 두 갈래인데 **둘 다 이 프로젝트의 보장을 깬다.**

- **(A) 트랜잭션 안에서 발행** → dual write. Kafka ack 후 커밋 실패 시 롤백 불가능한 유령 이벤트가 하류로 번진다. 반대로 커밋 후 ack 실패 시 "발행 안 됨" 이 어디에도 기록되지 않아 재시도 근거가 없다. 덤으로 `SELECT … FOR UPDATE` 락을 네트워크 왕복 시간만큼(건당 2.35ms) 붙들고, 브로커 장애가 곧 결제 장애가 된다.
- **(B) `afterCommit` 에서 발행** → 유령 이벤트는 사라지지만 **커밋~발행 사이 창**이 남는다. 그 순간 프로세스가 죽으면 이벤트는 영영 사라진다.

Outbox 의 본질은 발행이 아니라 **"아직 발행되지 않았다"는 사실을 비즈니스 데이터와 같은 커밋에 남기는 것**이다. 그 위에서만 재시도·백오프·DEAD 회수·순서 보장·적체 관측이 성립한다.

> 근거 — `common/messaging/outbox/` 전체, `OutboxRecorder.java:21`(`Propagation.MANDATORY`), `docs/decisions.md` 3·8절, `docs/performance.md` 4·5절

### 2.2 환불 순서 사고는 **두 종류**이고 해법이 다르다

| | 사고 A (D-013/D-014) | 사고 B (D-012) |
|---|---|---|
| 무엇 | payment outbox 안에서 `PaymentCompleted`(실패·PENDING) 를 `PaymentCancelled` 가 추월 | 주문1 환불 → 주문2 재구매 후, 주문1의 `LicenseRevoked` 가 지각 도착해 주문2 권한을 회수 |
| 파티션 키 | **같음** (`ORD-1` / `ORD-1`) | **다름** (`ORD-1` / `ORD-2`) |
| 막는 층 | 층위 2 — 릴레이 키 웨이브 | 순서로는 못 막음 |
| 해법 | `publishPreservingOrder` | 소비 측 정체성 대조 `belongsTo(orderNo)` |

**사고 B의 근본 원인**: 순서 보장의 단위(`orderNo`)와 download 가 관리하는 상태의 단위(`memberId:productId`)가 다르다. 두 주문이 같은 문서 하나를 놓고 경합하는데 그 문서를 키로 하는 순서 보장은 어디에도 없다.

**판별 기준은 하나** — 두 이벤트의 파티션 키가 같은가. 같으면 릴레이가 막고, 다르면 소비 측이 막아야 한다.

서비스 **간** 순서(payment → license)는 따로 지킬 필요가 없다. `PaymentCancelled` 를 소비해야 `LicenseRevoked` 가 태어나므로 **인과관계로 자연히 잡힌다.** 위험한 건 언제나 한 outbox 안의 "생성/소멸" 짝이다.

> 근거 — `docs/event-ordering.md` 2·4·6절, `docs/defects.md` D-010~D-014, `DownloadService.java:73-80`, `Entitlement.java:39-62`

### 2.3 파티션 = 토픽을 쪼갠 append-only 로그

- 토픽은 논리적 이름, 실제 데이터는 파티션에 나뉘어 저장된다. 이 프로젝트는 **3개**(`KAFKA_NUM_PARTITIONS: 3`).
- 파티션은 **추가만 되는 로그 파일**이고, 오프셋은 그 안에서의 줄 번호다. 파티션마다 따로 센다.
- 어느 파티션에 갈지는 키가 정한다 — `hash(키) % 파티션수`. 같은 키는 언제나 같은 파티션.
- **순서 보장은 파티션 안에서만** 성립한다. 파티션 사이에는 순서라는 개념 자체가 없다.
- Kafka 는 정렬해주지 않는다. **넣어준 순서를 보존만** 한다.

발행 재시도·DEAD 전이·발행 순서 결정은 파티션이 아니라 **outbox/릴레이(앱)** 가 한다. 파티션이 하는 일은 "넣은 순서 보존" 과 "병렬 소비 단위" 둘뿐이다.

> 근거 — `docker-compose.yml:52`, `Topics.java`, `OutboxRelay.java:171-173`

### 2.4 컨슈머 그룹 — "파티션 1개 = 컨슈머 1개" 는 **그룹 내부** 규칙

여러 그룹이 같은 파티션을 동시에 읽을 수 있다. Kafka 는 큐가 아니라 로그라서 **읽어도 메시지가 사라지지 않고**, 그룹마다 "어디까지 읽었는지"(오프셋)만 따로 기억하기 때문이다.

```
stove.payment.v1 을 소비하는 그룹 셋:  license · order · settlement
→ PaymentCancelled 한 건을 세 그룹이 각자 한 번씩 받는다
```

- **그룹 사이** — 제한 없음. 100개 붙여도 다 읽는다.
- **그룹 안** — 파티션 수가 컨슈머 수의 천장.

부수 정정 두 가지:
- **"환불 토픽" 은 없다.** 토픽은 애그리거트 단위이고, 환불은 `stove.payment.v1` 의 `PaymentCancelled` 이벤트다.
- **download 는 payment 토픽을 보지 않는다.** license 토픽 구독자다. 환불 경로는 동시 소비가 아니라 연쇄(`payment → license → download`).

> 근거 — `@KafkaListener` 12개 전수, 각 서비스의 `CONSUMER_GROUP` 상수, `Topics.java` 주석
>
> *(갱신)* 이 노트를 쓸 때는 `application.yml` 의 `group-id` 도 근거였다.
> [decisions.md](decisions.md) 16번이 **9개 앱 전부에서 한 번도 읽히지 않던 그 값을 지웠다** —
> 리스너가 `groupId` 를 명시하므로 yml 값은 덮어써지고 있었다. 지금 그룹 이름의 출처는 상수 한 곳이다.

### 2.5 파티션 수 = 병렬 처리량의 **상한**

비유: 파티션은 줄(lane), 컨슈머는 직원. **한 줄은 직원 한 명만 맡는다.**

```
직원 < 줄   →  정상. 한 명이 여러 줄을 맡는다.   ← 현재 상태 (1명 / 3줄)
직원 = 줄   →  최대 병렬.
직원 > 줄   →  초과분은 논다.
직원 = 0    →  메시지는 쌓인다. 살아나면 따라잡는다. 단 retention 안에.
```

한 줄에 직원 둘을 붙일 수 없는 이유는 성능 타협이 아니라 **순서와 오프셋이 동시에 깨지기 때문**이다.

컨슈머가 줄면 **리밸런스**로 남은 컨슈머가 자동 인수한다. 그래서 "직원이 부족해 방치되는 줄" 은 구조적으로 생기지 않는다 — 느려질 뿐 멈추지 않는다.

### 2.6 파티션 편중 — 빈 줄은 사고가 아니다

- 빈 파티션이 생겨도 잃는 것은 **"그 줄을 맡은 컨슈머가 논다"** 뿐이다. 유실·순서 문제는 없다.
- 편중 여부는 손님 수가 아니라 **키의 종류 수(카디널리티)** 가 정한다. 경험칙으로 **키 종류 ≥ 파티션 수 × 10** 이면 안전권.
- 이 프로젝트: `orderNo` 계열 7개 이벤트는 무한 증가라 안전. `productCode` 계열 5개는 게임 수가 적은 초기에 편중되지만, 그 시점엔 트래픽도 적어 무해하다.
- **진짜 위험은 반대 방향** — hot partition. 빈 줄은 "직원을 덜 뽑으면 되는" 문제이고, 몰린 줄은 "직원을 더 뽑아도 안 되는" 문제다.

### 2.7 `concurrency` 를 올려도 순서가 지켜지는 진짜 이유

로그 순서 때문이 아니라 **배정의 배타성** 때문이다. 고리 3개가 다 걸려야 한다.

```
[1] 같은 키   → 항상 같은 파티션        (프로듀서 해시)     ← Kafka 보장
[2] 한 파티션 → 그룹 내 스레드 하나 독점  (컨슈머 그룹)      ← Kafka 보장
[3] 그 스레드 → 한 번에 한 건씩 순차 처리 (리스너)          ← 개발자 몫
```

`concurrency` 상향은 셋 중 어느 것도 건드리지 않아 안전하다. 깨지는 건 [3] 이다 — 리스너 안에서 스레드풀에 넘기면 `concurrency: 1` 이어도 깨진다.

**그리고 [3]은 이 저장소에서 ArchUnit 으로 강제되고 있다** (`common/archunit/EventOrderingRules.java`):

```
리스너는_다른_스레드로_넘기지_않는다
리스너_메서드에_Async_를_붙이지_않는다
논블로킹_재시도를_쓰지_않는다        // @RetryableTopic 금지
```

> **파티션 경계를 따라 쪼개는 병렬화는 안전하고, 파티션을 무시하고 쪼개는 병렬화는 순서를 깬다.**

### 2.8 파티션 증설이 필요해지면

사고 조건은 **"옛 파티션에 같은 키의 미처리 메시지가 남은 채 배정 규칙이 바뀌는 것"** 이다. 뒤집으면 **증설 시점에 옛 파티션이 비어 있으면 재배치는 무해하다.**

**방법 A — 배수 후 증설 (권장)**

```
① 발행자 릴레이 끄기   STOVE_OUTBOX_RELAYENABLED=false 로 재기동
                       → API 는 정상. 이벤트는 outbox 에 PENDING 으로 쌓인다 (유실 0)
② 그 토픽의 모든 컨슈머 그룹 랙이 0 이 될 때까지 대기 (kafka-ui 로 확인)
③ kafka-topics.sh --alter --topic <t> --partitions <n>
④ 릴레이 다시 켜기     → 쌓인 PENDING 이 새 배정으로 발행
```

성격은 **서비스 중단이 아니라 이벤트 전달 지연**이다. 실측 릴레이 처리량 480.5 events/s 기준, 5분간 초당 50건이 쌓였어도 재개 후 약 31초면 배수된다.

> **이 절차가 가능한 이유가 곧 outbox 를 쓰는 이유다.** 동기 발행이었다면 발행을 멈추려면 결제 API 를 멈춰야 하고, 안 멈추면 그동안의 이벤트가 사라진다.

**방법 B — 새 토픽 (`stove.payment.v2`)**. `Topics.java` 의 `v1` 접미사가 이걸 대비한 것이다. 무지연이지만 컨슈머 배포 2회 + 두 토픽 운영으로 복잡하다.

**함정 두 개**
1. `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"` + 토픽 명시 생성 코드 없음 → 새 토픽에 그냥 발행하면 **기본 3파티션으로 조용히 생긴다.** 반드시 `--create --partitions N` 으로 먼저 만든다.
2. **파티션은 늘릴 수만 있고 줄일 수 없다.** 그 전에 `concurrency` 상향(미사용, 3배 여유) → 인스턴스 증설 → 처리 최적화를 먼저 쓴다.

---

## 3. 코드로 확인한 사실 (2026-08-07 확인 · **2026-08-10 재대조: 전 항목 그대로**)

새 세션에서 다시 확인하지 않아도 된다. 단, **코드가 바뀌면 무효**이므로 이상하면 재확인할 것.

| 확인한 것 | 결과 |
|---|---|
| 릴레이의 실행 형태 | **별도 프로세스 아님.** 각 앱 안의 `@Scheduled` 빈 (`MessagingAutoConfiguration:58`) |
| `stove.outbox.*` 기본값 | batch-size 200 · poll 1s · max-retry 10 · maxBatchesPerCycle 10 |
| 이벤트 12종의 파티션 키 | `orderNo` 7개 · `productCode` 5개. 전부 고카디널리티 |
| 파티션 수 | 3 (`KAFKA_NUM_PARTITIONS`, 브로커 기본값으로만 설정) |
| 토픽 명시 생성 | **없음.** `NewTopic`/`KafkaAdmin` 빈 부재 → auto-create 의존 |
| retention | **미설정** → 브로커 기본 7일 |
| `concurrency` | **전 서비스 미설정** → 리스너당 스레드 1개. 3배 여유가 통째로 남아 있음 |
| `auto-offset-reset` | 9개 서비스 전부 `earliest` (유실 회피 방향) |
| `ack-mode` | `record` — 한 건 처리마다 커밋 |
| `@KafkaListener` 개수 | download 3 · payment 2 · 나머지 7개 서비스 각 1 (총 12) |
| 리스너의 비동기 오프로드 | **없음.** 그리고 ArchUnit 이 강제 중 |
| `relay-enabled: false` | settlement 하나. "이벤트를 발행하지 않는 종단 컨슈머" 라 의도된 것 |
| 릴레이 다중화 | **불가.** 서비스당 1대 제약 (`FOR UPDATE SKIP LOCKED` 가 파티션 키를 모름) |

---

## 4. 다음 학습 순서

| 순서 | 대상 | 왜 이 순서인가 |
|---|---|---|
| 1 | `common/messaging/inbox/` + `docs/decisions.md` 8절 | **Outbox 의 짝.** at-least-once 를 고른 대가를 치르는 곳. `(event_id, consumer_group)` 유니크. 그림이 닫힌다 |
| 2 | `docs/kafka-consumer-retry.md` (8절) | 지금 대화의 직속 후속편. *"재시도의 정체는 seek 되감기다"*, `@RetryableTopic` 을 안 쓰는 이유 |
| 3 | `docs/defects.md` 전체 (21건) | 재현 테스트 이름까지 달려 있다. **패턴 학습보다 함정 학습이 빠르다** |
| 4 | Saga — D-002, D-006, `RefundFacade` | 보상 트랜잭션의 실전 함정. "무엇을 실패로 볼 것인가" 를 틀리면 정상 결제를 환불한다 |
| 5 | `common/archunit/` (앱당 36규칙) | 설계 규칙을 테스트로 강제하는 법. `EventOrderingRules` 는 분산 동작 보장을 정적 규칙으로 옮긴 드문 사례 |

이후 여유가 있으면: CQRS(catalog↔store), Database per Service + Flyway `ddl-auto: validate`, 권한 사본으로 결합 끊기(`download/Entitlement`), 뮤테이션 테스트(`docs/testing.md` 5절), **추적 컨텍스트를 Outbox 에 실어 Kafka 구간을 잇는 법**(`common/messaging/trace`, [decisions.md](decisions.md) 17번), gateway 내부 API 차단.

> *(갱신)* 원래 여기 `CorrelationIdFilter` 가 있었다. 17번이 그 필터를 `TraceIdResponseFilter` 로
> 대체했다 — 식별자 생성과 MDC 적재는 Micrometer Tracing 이 더 잘 하므로 응답 헤더를 돌려주는 일만 남겼다.
> 학습 대상으로 더 값이 큰 것은 그 자리를 대신한 **Outbox ↔ 분산 추적의 충돌**이라 그쪽으로 바꿨다.

**1·2번은 Kafka 구조가 머릿속에 올라와 있는 지금 보는 게 효율이 가장 좋다.**

---

## 5. 열린 질문 — 확인되지 않은 것

단정하지 말 것. 확인 후 결론이 나면 이 절을 갱신한다.

**남은 것은 ① 하나다.** ②③ 은 닫혔고, 무엇으로 닫혔는지를 아래에 남긴다.

**① `DownloadService.grant` 에는 `belongsTo` 가드가 없다** (관찰 — **2026-08-10 재확인, 그대로다**)

`revoke` 는 D-012 수정으로 주문번호를 대조하지만(`DownloadService.java:73-80`), `grant` 는 그냥 덮어쓴다(`:52-55`). 지각한 `ORD-1` 의 `LicenseIssued` 가 `ORD-2` 것보다 늦게 도착하면 문서의 `orderNo` 가 `ORD-1` 로 덮이고, 이후 정당한 `ORD-2` 회수가 `belongsTo` 에서 거부될 수 있다. 다만 D-010 이 *"이미 전부 회수된 주문에는 발행하지 않는다"* 로 재전송 경로를 막아둬서 창이 매우 좁다. **재현 테스트를 써 보기 전에는 결함이라 부르지 않는다.**

**② DEAD 알람이 없다** — ✅ **닫힘**

`stove.outbox.dead` 에 Prometheus 알람 규칙이 붙었다(`infra/prometheus/alerts.yml`, `promtool` 검증).
[decisions.md](decisions.md) 19번이 함께 넣었고, 같은 자리에서 `stove.kafka.dead-lettered` 도 생겼다 —
DLT 도 아무도 안 보면 유실과 운영상 다르지 않기 때문이다.

**다만 더 큰 구멍은 알람이 아니라 회수 경로였다.** `OutboxEvent#requeue()` 는 처음부터 있었고
주석도 정확했는데(*"회수 경로가 없으면 유실 방지 장치가 유실의 원인이 된다"*),
**그 메서드를 부르는 프로덕션 코드가 하나도 없었다** — 호출처는 테스트 4곳뿐이었다.
되살리려면 운영자가 프로덕션 DB 에 직접 UPDATE 를 쳐야 했다는 뜻이다.
지금은 HTTP 로 되살린다(`/api/v1/ops/outbox/dead/{id}/requeue`).

**③ 문서 참조가 어긋나 있다** — ✅ **닫힘**

`event-ordering.md` 6절 A-2 가 README 의 "남은 것" 절을 가리키는데 README 에 그런 절이 없었다.
②가 닫히면서 *"아직 없다"* 는 문장 자체도 낡았으므로, 깨진 참조를 지우고
실제 알람 규칙 파일과 회수 API 를 가리키도록 고쳤다.

---

## 6. 참조 지도

| 파일 | 무엇이 있나 |
|---|---|
| `common/messaging/outbox/` | Outbox 적재·릴레이·재시도·메트릭 |
| `common/messaging/inbox/` | 멱등 수신 가드 *(다음 학습 1순위)* |
| `common/messaging/trace/` | 적재 시점의 추적 컨텍스트를 붙잡아 발행 때 복원 (17번) |
| `common/messaging/ops/` | Outbox `DEAD` 회수 API |
| **`common/kafka/`** | 수신 측 정책 — `ConsumerRetryPolicy`, DLT, DLT 운영 API. **JPA 를 모른다** (19번) |
| `common/event/` | 서비스 간 계약 — payload 12종, `Topics`, Kafka 헤더 |
| `common/archunit/EventOrderingRules.java` | 순서 보장을 깨는 코드를 빌드에서 차단 |
| `docs/event-ordering.md` | 순서가 깨지는 3층위 + 해법 카탈로그 6종 + 릴레이 1대 제약 |
| `docs/defects.md` | 결함 36건, 각각 재현 테스트 명시 |
| `docs/decisions.md` | 설계 결정 22건, 각각 근거와 **대가**까지 |
| `docs/performance.md` | 릴레이 처리량 (138 → 480.5 events/s, ×3.48) + HTTP 재측정 (9장) |
| `docs/kafka-consumer-retry.md` | 컨슈머 재시도의 실제 동작 *(다음 학습 2순위)* |
| `docs/testing.md` | 테스트 계층·격리·뮤테이션 테스트 |
| `docs/services.md` | 서비스 9종의 API·상태머신·이벤트 |
| `docs/remote-dev-plan.md` | 원격 CI 를 세운 기록 — 로컬이 전체 스택을 못 띄우던 이유 |
| `scripts/perf/` | 릴레이 부하 측정 도구, 릴레이 off 대조군 |

> *(갱신)* `ConsumerRetryPolicy` 는 이 노트를 쓸 때 `common/messaging/` 아래에 있었다.
> 19번이 수신 측 정책만 `common/kafka/` 로 갈라낸 이유는, 정책만 쓰려는 store·download 에
> Outbox 와 JPA 까지 딸려오는 것이 문제였기 때문이다. 그 분리 덕에 **9개 서비스가 같은 실패 처리를 갖는다.**
