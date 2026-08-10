# 테스트 전략

무엇을 어느 층에서 검증하고, 왜 그 층인지 적는다.
개별 결함 목록은 [defects.md](defects.md) 에 있다.

---

## 1. 이 시스템에서 테스트가 어려운 이유

9개 서비스가 Kafka 로만 연결된다. 서비스 하나를 아무리 꼼꼼히 검증해도
**서비스 사이의 틈**은 보이지 않는다. 그리고 사고는 대부분 그 틈에서 난다 —
이벤트가 유실되거나, 순서가 뒤바뀌거나, 두 번 도착하거나, 한쪽만 커밋되거나.

이런 결함의 공통점은 **터진 자리와 원인의 자리가 다르다**는 것이다.
결제 서버는 정상 종료됐는데 사용자는 게임을 못 받는다. 로그에는 에러가 없다.

그래서 테스트를 층으로 나누되, **경계마다 무엇을 보장하는지**를 명시적으로 검증한다.

---

## 2. 테스트 계층

| 층 | 검증 대상 | 인프라 | 소스셋 | 위치 | 1건당 | 언제 도는가 |
|---|---|---|---|---|---|---|
| **L0 도메인** | 상태 전이, 금액 계산 | 없음 | `test` | `apps/*/core/domain` | ms | 매 빌드 |
| **L1 서비스** | 트랜잭션 경계, 멱등, Outbox 적재 | MySQL/Mongo | `integrationTest` | `apps/*/core/service` | 초 | 매 빌드 |
| **L2 어댑터·계약** | 봉투 파싱, 라우팅, 실패 분기, 직렬화 | 없음(대역) | `test` | `apps/*/api/listener`, `common/event` | ms | 매 빌드 |
| **L3 메시징 인프라** | 릴레이 재시도, 멱등 가드 | 없음(대역) | `test` | `common/messaging` | ms | 매 빌드 |
| **L4 구조** | 패키지 경계, 의존 방향, 순서 보장 전제 | 없음 | `test` | `*ArchitectureTest` | ms | 매 빌드 |
| **L5 기동** | 빈 구성, Flyway ↔ 엔티티 정합 | 전체 | `integrationTest` | `*ContextTest` | 십수 초 | 매 빌드 |
| **L6 인수** | 서비스 *사이* — 이벤트가 건너가는가, 되감기는가 | 배포된 스택 20종 | `e2e` 모듈 | `e2e/src/test` | 46건에 36초 | **main push** |

L6 만 `build` 밖이다. 스택이 떠 있어야 돌기 때문이고, 그 조건을 기본 빌드에 넣으면
컨테이너가 없는 로컬에서 전체 빌드가 항상 빨개진다. 대신 `./gradlew :e2e:e2eTest` 로 따로 부른다.
**러너가 1대라 PR 마다가 아니라 통합 시점에 붙는 것**도 같은 줄의 판단이다
([test-audit.md](test-audit.md) 4.3).

그 앞에 하나가 더 있는데, 테스트가 아니라 **배포의 일부**라 층으로 세지 않는다 —
`scripts/stack-wait.sh` 의 게이트 14건이다. 데이터가 필요 없고 수 초에 끝나며
`remote.sh stack up` 과 CI e2e 잡이 자동으로 부른다.

### 소스셋이 층을 강제한다

**"인프라가 필요한가"는 규약이 아니라 클래스패스가 정한다.**
컨테이너는 `common:testcontainers` 에 있고 이 모듈은 `integrationTestImplementation` 으로만
걸린다 — `src/test` 의 클래스는 `InfraContainers` 를 **임포트조차 할 수 없다.**

```
./gradlew test              816건 · Docker 불필요 · 수 초
./gradlew integrationTest   175건 · Testcontainers · 동시 스택 수는 따로 조인다
./gradlew build             둘 다 (단위가 먼저 돈다)
```

태그로 가르지 않은 이유가 여기 있다. 태그는 붙이는 것을 잊을 수 있고,
잊으면 컨테이너 테스트가 빠른 소스셋에 섞여 **예전 상태로 조용히 돌아간다.**
실제로 분리 작업 중에 `InfraContainers` 를 쓰지 않고 Testcontainers 를 직접 쓰던
테스트 2건(`S3PresignedUrlSignerTest`·`S3BuildStorageTest`)이 있었는데,
찾아낸 것은 사람의 검토가 아니라 **컴파일 실패**였다.

동시에 뜨는 컨테이너 스택 수는 공유 빌드 서비스(`InfraTestThrottle`)가 따로 센다.
예전에는 `org.gradle.workers.max` 하나가 컴파일 병렬도와 컨테이너 수를 같이 정해서,
컨테이너를 지키려고 2 로 낮추면 인프라가 필요 없는 테스트까지 함께 느려졌다.

### 경계 두 곳은 층이 아니라 부재로 지켜진다

표에 안 들어가지만 따로 검증하는 것이 둘 있다.

- **게이트웨이 라우팅**(`GatewayRouteTest`) — 운영툴 API 는 차단 규칙이 있어서 막히는 게 아니라
  **매칭되는 라우트가 없어서** 막힌다. `Method=GET` 술어 한 줄이 유일한 통제라
  넓히는 순간 조용히 열린다. 그래서 "무엇이 통과하지 않는가"를 단언한다
- **컨슈머 층 순서 전제**(`EventOrderingRules`) — 리스너에서 비동기로 넘기거나
  `@RetryableTopic` 을 쓰면 순서 보장이 무효가 되는데, 둘 다 **도입해도 티가 안 난다.**
  ArchUnit 으로 못 들어오게 막는다

### 층을 고르는 기준

**인프라를 띄우는 비용이 검증의 가치를 넘지 않는가.**

예를 들어 "리스너가 예외를 밖으로 내보내는가"는 Kafka 재시도의 전제조건이지만,
확인하는 데 브로커가 필요 없다. 대역 하나로 밀리초 만에 판정된다(L2).
반대로 "Flyway 마이그레이션과 JPA 매핑이 맞는가"는 진짜 MySQL 없이는 의미가 없다(L5).

### 왜 L3 를 대역으로 하는가

`OutboxRelay` 검증의 관심사는 **브로커가 죽었을 때 릴레이가 무엇을 하는가**이지
브로커 구현이 아니다. `KafkaTemplate` 을 대역으로 세우고 폴링 주기를
`relay()` 호출 횟수로 모사하면, 실제로는 재현하기 어려운
"30초짜리 브로커 장애"를 결정적으로(deterministic) 만들 수 있다.

---

## 3. 결함 재현 테스트

### 규칙

발견한 결함은 **고치기 전에 재현 테스트부터 커밋한다.** `@Tag("known-defect")` 를 붙인다.

```java
@Test
@Tag("known-defect")
@DisplayName("[D-001] 마감 후 도착한 원장은 다음 마감에서 확정본에 반영되어야 한다")
void lateRecordShouldBeSettledOnNextClose() {
    ...
    // 기대: 원장 합계 == 확정본 합계
    // 실제: 차액 35,000 이 어디에도 잡히지 않는다
    assertThat(settledNet).isEqualTo(ledgerNet);
}
```

- 단언은 **의도한 올바른 동작**을 쓴다. 현재 동작을 쓰면 결함이 사양으로 굳는다.
- 주석에 **기대 / 실제 / 영향**을 남긴다. 반년 뒤 읽을 사람이 판단할 수 있어야 한다.
- `[D-00N]` 로 [defects.md](defects.md) 와 연결한다.

### 실행

```bash
./gradlew build         # 결함 테스트 제외. CI 는 항상 초록이어야 한다
./gradlew defectTest    # 결함 테스트만. 실패 = 결함이 아직 살아 있음
```

`test` 태스크만 `excludeTags 'known-defect'` 를 걸었다.
`tasks.withType(Test)` 로 걸면 `defectTest` 까지 같이 걸러져 아무것도 돌지 않는다.

**현재 태그가 붙은 테스트는 0건이다** — D-001~D-024 가 전부 수정돼 태그가 떨어졌다.

그래서 `defectTest` 는 합산해서 말한다. 모듈별 태스크는 `ignoreFailures = true` 라
실패해도 초록이고, 태그가 없으면 아무것도 출력하지 않는다 — 그대로 두면
**"재현 대기 중인 결함이 없다" 와 "전부 통과했다" 와 "돌기는 했나" 가 구분되지 않는다.**

```
0건    재현 대기 중인 결함이 없다. (통과가 아니라 미실행이다)
N중M   결함 재현 N건 중 M건이 아직 살아 있다
전부   N건이 전부 통과했다. (태그를 떼고 회귀 방어선으로 승격한다)
```

**통과처럼 읽히는 미실행**은 이 저장소가 반복해서 막아 온 실패 방식이고, 같은 장치가 세 군데 더 있다.

| 어디 | 무엇으로 |
|---|---|
| 배포 게이트 (`stack-wait.sh`) | 기대 판정 수 대조 + 컨테이너 이름 대조. 빈 결과를 정상으로 읽지 않는다([D-023](defects.md#d-023)) |
| 인수 (`:e2e`) | 실행 건수를 요약에 찍는다. 그리고 **`Assumptions` 를 쓰지 않는다** — 선행이 값을 못 만들면 스킵이 아니라 실패다 |
| CI 의 모든 Test 태스크 | `UP-TO-DATE`·`FROM-CACHE` 로 건너뛰지 않는다([decisions.md](decisions.md) 12번 옆 흐름) |

### 왜 실패하는 테스트를 남겨두나

**결함 목록을 먼저 완성해야 우선순위를 매길 수 있다.** 발견할 때마다 고치면
전체 그림이 안 보이고, 급하지 않은 것을 먼저 고치게 된다.

**그리고 실패하는 테스트가 곧 명세다.** 수정 시점에 "이 테스트가 통과하면 고친 것"이라는
판정 기준이 이미 있다. 고친 뒤에 테스트를 쓰면 무의식적으로
"지금 코드가 통과하는" 테스트를 쓰게 된다.

수정이 끝나면 태그를 떼고 회귀 방어선으로 승격한다.

### 재현 테스트는 "실패하는 것"까지 눈으로 확인한다

**단언을 쓰는 것과 결함을 재현하는 것은 다르다.** 테스트가 빨간 것을 봤다고 결함이 증명되지 않는다 —
빨간 이유가 결함이 아닐 수 있다.

실제로 겪었다. D-016·D-018 의 재현 테스트가 실패하는 것을 보고 결함으로 확정했는데,
사실은 `DOCKER_HOST` 가 없어(macOS + OrbStack) Testcontainers 가 컨테이너를 못 띄운 것이었다.
**컨텍스트 로딩 실패도 테스트 실패로 보인다.** 실행 요건은 [README](../README.md) 4절에 있다.

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
```

그래서 재현을 확정하기 전에 **실패 원인이 단언인지** 확인한다.
`build/test-results/**/TEST-*.xml` 에서 `AssertionFailedError` 인지
`IllegalStateException: Failed to load ApplicationContext` 인지가 갈린다.

반대 방향의 사고도 있었다. 게이트웨이 actuator 노출을 결함으로 보고 재현 테스트를 먼저 썼는데
**통과했다** — Spring Cloud Gateway 4.x 의 엔드포인트 기본값이 이미 막고 있었다
([defects.md](defects.md) 닫힌 항목). 코드를 읽고 세운 가설이 틀렸다는 것을 테스트가 알려준 경우다.

**재현 테스트를 먼저 쓰는 이유의 절반은 여기에 있다.** 고치기 전에 쓰면 가설이 틀렸을 때
아무것도 고치지 않고 끝난다. 고친 뒤에 쓰면 없는 결함을 고친 코드가 남는다.

### 짝 테스트

결함 테스트 옆에 **현재 동작을 고정하는 테스트**를 둔 경우가 있다.

```java
@Test
@DisplayName("현재 동작: 재처리는 이벤트를 발행하지 않는다")
```

이쪽은 태그 없이 기본 빌드에서 돈다. 결함을 고치면 이 테스트가 깨지므로,
**의도치 않은 동작 변경과 의도한 수정을 구분**할 수 있다.

---

## 4. 테스트 격리

`SharedContainers` 는 JVM(= Gradle 모듈)당 하나다. 모듈 안의 테스트는 **같은 DB 를 공유**하고,
스프링 테스트에 `@Transactional` 롤백을 걸지 않으므로 **데이터가 누적된다.**

그래서 전역 집계를 다루는 테스트는 데이터를 지우는 대신 **키 공간을 나눈다.**

```java
private static YearMonth uniqueMonth() {
    return YearMonth.of(2999, MONTH_SEQ.getAndIncrement());
}
```

지우는 방식보다 나은 이유는, 다른 테스트와 병렬로 돌아도 안전하고
"내가 만든 데이터만 보인다"는 조건이 테스트 안에서 자명해지기 때문이다.

### 캐시된 컨텍스트의 배경 스레드

대부분의 테스트는 `stove.outbox.relay-enabled=false` 로 릴레이를 끈다.
그런데 `*ContextTest` 는 전체 구성을 확인하는 것이 목적이라 켠 채로 뜬다
(`@ConditionalOnProperty` 라 끄면 빈 자체가 없어져 검증이 헐거워진다).

**스프링이 그 컨텍스트를 캐시하므로 릴레이 스레드는 테스트 JVM 이 끝날 때까지 살아 있다.**
릴레이를 끈 테스트에서 만든 Outbox 이벤트를 그 스레드가 발행해 버릴 수 있다.

실제로 겪었다 — 릴레이 처리량을 3.5배로 올리자 잠재돼 있던 이 경합이 드러나
`OutboxBackOffQueryTest` 가 깨졌다. 성능 개선이 테스트 결함을 노출시킨 셈이다.

**해결은 폴링을 재우는 것이다.** `*ContextTest` 에 `stove.outbox.poll-interval-ms=3600000` 을 준다.
빈은 그대로 있어 `@ConditionalOnProperty` 구성 검증이 유지되고,
실제 폴링은 컨텍스트 기동 직후 1회로 끝나 다른 테스트와 겹치지 않는다.

> 처음에는 단언을 푸는 쪽으로 대응했었다 — "발행 대상이었는가"를 묻는 자리에서
> **이미 발행된 경우도 통과**로 봤다. 논리는 맞지만 대가가 컸다.
> 그러면 조회 조건이 통째로 깨져도 테스트가 통과하므로 D-003 의 회귀 방어선이 사라진다.
>
> **경합을 피하려고 단언을 느슨하게 하면, 막으려던 결함까지 같이 놓친다.**
> 단언은 검증하려는 사실 그대로 두고 경합 쪽을 없애는 편이 낫다.

---

## 5. 뮤테이션 테스트

커버리지는 **"거쳐 갔는가"**를 재고, 뮤테이션은 **"잡아내는가"**를 잰다.
pitest 로 프로덕션 코드를 조금씩 바꿔 보고, 그래도 테스트가 통과하면 그 자리는 검증되지 않은 것이다.

```bash
./gradlew :common:messaging:pitest    # 모듈 단위로 돌린다
```

**CI 에 넣지 않는다.** 전 모듈 기준 수십 분이 걸려 피드백 루프를 망가뜨린다.
테스트를 새로 붙인 모듈에 수동으로 돌리고 결과를 판단하는 용도다.

대상은 도메인·서비스 계층(`com.stove.*.core.*`)으로 좁혔다.
어댑터는 대부분 위임이라 살아남는 뮤턴트가 "검증할 가치가 없는 위임"이어서 잡음이 크다.

### 실제로 무엇을 찾았나

`common:messaging` 첫 실행에서 살아남은 뮤턴트 13건 중 두 부류가 나왔다.

- **`OutboxMetrics` 전체(6건)** — 계측 호출을 전부 지워도 통과했다. 지표에 테스트가 하나도 없었다는 뜻이다.
  지표는 **조용히 망가지는 코드**다. 카운터가 안 올라가도 기능은 정상이고 테스트도 초록인데,
  드러나는 시점은 사고가 나서 대시보드를 열었을 때이고 그때는 과거 데이터가 없다
- **경계 조건(2건)** — "3회에 DEAD" 만 검증하면 `>=` 가 `>` 로 바뀌어도(4회에 DEAD) 통과한다

메우고 나서 13 → 3 으로 줄었다.

### 남은 3건 중 2건은 등가 뮤턴트다

**살아남았다고 전부 테스트 공백은 아니다.** 의미가 같아서 죽일 수 없는 뮤턴트가 있다.

| 자리 | 왜 못 죽이나 |
|---|---|
| `backOffDelay` 의 `seconds >= 300` | 백오프는 항상 2의 거듭제곱(1·2·4…512)이라 300 과 정확히 같아질 수 없다 |
| `markFailed` 의 `length() > 500` | 정확히 500자면 `substring(0, 500)` 결과가 원본과 같다 |

이런 것을 억지로 죽이려 들면 테스트가 구현에 달라붙는다.
**뮤테이션 점수를 100% 로 만드는 것이 목표가 아니라, 살아남은 것을 하나씩 판단하는 것이 목표다.**

---

## 6. 앞으로 채울 것

전 모듈을 전수 대조해 정리한 목록이다. 위에서부터 위험도 순이며,
**결함으로 이어질 수 있는 것**과 **단순 미커버**를 구분해 적는다.

### 6.1 지금 메우는 중

**서비스 계층(L1)은 5곳이 통째로 비어 있었다.** `OrderCommandService`·`OrderQueryService`·
`StudioService`·`ReviewService`·`ProductCommandService` 가 테스트에서 `mock()` 으로만 등장해
구현이 한 번도 실행되지 않았고, 그래서 `ProcessedEventGuard.firstDelivery` 호출 5개소가
전부 미검증이었다. 이 층을 채우는 과정에서 [D-016 ~ D-019](defects.md) 가 나왔다.

→ **메웠다.** 남은 것은 아래.

### 6.2 남은 것 — 서비스별

> **2026-08-05 갱신.** "위험"과 "즉시 교정"으로 표시했던 항목을 메웠다.
> 그 과정에서 [D-020](defects.md#d-020) 과 [D-021](defects.md#d-021) 이 나왔다.
> 아래 표에서 ~~취소선~~ 은 닫힌 항목이고, 남은 것은 전부 "미커버" 등급이다.

| 서비스 | 항목 | 성격 |
|---|---|---|
| **payment** | ~~게이트 2 미검증~~ → `PaymentGateTest#prepareRegistersTheServerHeldAmountWithPg` 가 PG 에 실린 금액을 captor 로 단언한다 | ✅ |
| **payment** | ~~게이트 3 미검증~~ → 과소·과다결제 양쪽을 `errorCode == PAYMENT_AMOUNT_MISMATCH` 로 단언한다 | ✅ |
| **payment** | ~~`createReady` 2차 가드~~ → 다른 eventId·같은 orderNo 로 2차 가드만 걸리는 경로를 덮었다 | ✅ |
| **payment** | ~~`pgClient.cancel` 자체 실패 경로~~ → `CANCELING` 으로 남아 재시도로 완결되는 것까지 덮었다 | ✅ |
| **settlement** | ~~`FeePolicy` 무테스트~~ → `saleTypeOf`/`feeRateOf` 를 직접 테스트하고, 요율·자체판매자 ID 가 **설정에서 온다**는 것까지 고정했다 | ✅ |
| **settlement** | `net == 0` 경계(매출이 환불로 정확히 상계 — 가장 흔한 이월 케이스), 환불 역산의 `sales.isEmpty()` 조기 반환, 월 경계 환불(원매출의 달이 아니라 **현재 달**로 역산한다) | 미커버 |
| **settlement** | ~~`SettlementBatch` 무테스트 — 다중 인스턴스 단일 실행(문서상 TODO)~~ → `@SchedulerLock` 선언과 락 테이블을 `SettlementBatchLockTest` 가 고정한다. 마감 자체는 [D-022](defects.md#d-022) 로 3단계 분리됐고 `SettlementCloseFacadeTest` 가 중간 실패 상태를 덮는다. **연말 경계(1월 → 전년 12월)는 아직 미커버** | 일부 ✅ |
| **store** | **`featured()` 본문이 어떤 테스트에서도 실행되지 않는다** (컨트롤러 테스트는 `StoreService` 를 mock) | 미커버 |
| **store** | ~~Redis 캐시가 프록시 없이 테스트된다~~ → `StoreCacheProxyTest` 가 프록시를 태워 `@Cacheable`/`@CacheEvict` 가 실제로 붙는지 본다 | ✅ |
| **store** | 실 ES 쿼리·매핑 — `findByStatusAndNameContaining` 은 대역의 `String.contains` 와 의미가 다르다. `ElasticsearchIndexInitializer` 의 매핑 적용(`status` 가 `keyword` 인지)도 미검증 | 미커버 |
| **store** | ~~`?page=-1`, `?size=0` → 500~~ → **[D-020](defects.md#d-020) 으로 확인·수정.** 서비스에서 막고 400 으로 응답한다 | ✅ |
| **download** | ~~`CdnUrlSigner` 무테스트~~ → HMAC 이 경로·회원·만료를 함께 덮는지, `s3://` 가 벗겨지는지, TTL 이 설정을 따르는지 덮었다 | ✅ |
| **download** | ~~`validTicketRequestReachesTheService` 에 `status()` 가 없다~~ → 200 과 응답 본문을 `jsonPath` 로 단언한다 | ✅ |
| **download** | 403(미보유) vs 404(상품 미등록) 구분이 어느 테스트에도 없다. 매니페스트 최신본 선택(모든 테스트가 버전 1개만 등록), upsert 멱등 | 미커버 |
| **license** | ~~`recordIssueFailure` 가 한 번도 실행되지 않는다~~ → `LicenseIssueFailureTest` 가 **바깥 트랜잭션을 롤백시켜** `REQUIRES_NEW` 를 검증한다 | ✅ |
| **license** | ~~`republishedEventCarriesFullOwnership` 이 저장소 크기만 단언~~ → 이벤트 페이로드를 파싱해 소유 상태 전체가 실리는지 본다. 회수 이벤트의 "변경된 것만" 도 덮었다 | ✅ |
| **catalog** | `Product` 의 `applyReviewApproval` `REVIEWING` 분기, `suspend` 무가드. `ProductView` 를 실제 Redis 직렬화기로 왕복(이 클래스의 존재 이유가 역직렬화 가능성이다) | 미커버 |
| **catalog** | ~~`?sort=<모르는 속성>` → 500~~ → **[D-024](defects.md#d-024) 로 확인·수정.** 서비스가 허용 키를 명시하고 400 으로 응답한다. 저장소에서 `Pageable` 을 직접 받는 유일한 컨트롤러였다 | ✅ |
| **review** | `approve`/`reject`/`getRequests` — 접수 경로는 덮었지만 결정 경로가 남았다 | 미커버 |
| **review** | ~~`approvalUsesGivenRating` 이 `anyString()` 으로 단언~~ → `approve(2L, "ADULT")` 로 값을 고정했다 | ✅ |
| **order** | `CatalogRestAdapterTest` 가 catalog 4xx → 503 을 고정한다. D-019 수정으로 잘못된 수량이 order 에서 걸리므로 재검토 대상 | 판단 필요 |
| **gateway** | 라우트 `uri` 가 단언되지 않는다 — id 만 본다. store/catalog URI 가 뒤바뀌어도 전부 통과한다 | 미커버 |

### 6.3 남은 것 — common

- ~~**`common/core` 전 모듈 무테스트.** `ErrorCode` 상수의 `HttpStatus` 매핑,
  `ApiResponse` 의 `@JsonInclude(NON_NULL)` 계약~~
  ~~**저장소 전체에 `jsonPath` 단언이 하나도 없어** 응답 형식이 통째로 바뀌어도 잡히지 않는다~~
  → **응답 형식은 메웠다 — 다만 다른 수단으로.** 단언을 31개 엔드포인트에 손으로 붙이는 대신
  OpenAPI 명세를 스냅샷으로 고정했다(decisions.md 18번). 각 앱의 `*ContextTest` 가
  `/v3/api-docs` 를 커밋된 스냅샷과 대조하므로, DTO 필드를 지우거나 응답 타입을 바꾸면 깨진다.
  **실제로 잡는지 확인했다** — `CreateOrderRequest.expectedAmount` 를 스냅샷에서 지우자 실패했다.
  → **상태 매핑도 메웠다.** 아래 참고

#### 오류 응답의 상태코드 — 셸에서 코드로 회수했다

한동안 이 계약을 지키는 것이 **CI 가 돌리지 않는 셸 스크립트 하나**였다.
저장소의 단언이 전부 `errorCode()` *열거값* 까지만 갔고
(`assertThat(e.errorCode()).isEqualTo(CONFLICT)`), 그 CONFLICT 가 409 로 나가는지는
셸 스모크(지금은 `:e2e`)만 봤다. 앱 9종의 컨트롤러 테스트도 `GlobalExceptionHandler` 를
붙이지만 400 계열(D-015)만 단언한다. **그래서 상태 매핑을 잘못 바꿔도 빌드는 초록이었다.**

세 자리로 나눠 막는다. 셋 다 컨테이너가 필요 없어 `test` 소스셋에 있다.

| 테스트 | 무엇을 지키나 |
|---|---|
| `ErrorCodeTest` | 전수 성질 — 모두 4xx/5xx, `*NOT_FOUND`→404, `*MISMATCH`→409, 기본 메시지 존재 |
| `BusinessExceptionStatusTest` | `@EnumSource` 로 **전 코드**를 실제 MockMvc 응답까지 태워 `code.status()` 와 대조 |
| `ApiResponseTest` | 봉투 직렬화 — 코드 이름(ordinal 아님), `NON_NULL` 로 `data`/`error` 키 생략 |

**개별 상수의 값을 하나씩 적지 않았다.** `CONFLICT 는 409 다` 같은 단언은 열거형 선언을
옮겨 적은 것이라, 값이 틀리면 테스트도 같이 틀린 값을 들고 있게 된다.
대신 이름↔상태 규칙을 전수로 걸어 **새 상수가 규칙을 어기는 순간** 깨지게 했다.

**두 방향 모두 발화하는 것을 확인했다.**

| 만든 상황 | 결과 |
|---|---|
| `PRICE_MISMATCH` 를 409 → 400 으로 | `ErrorCodeTest` 의 MISMATCH 규칙 + `:e2e` 의 금액 위조 판정 실패 |
| 핸들러가 `code.status()` 대신 400 을 하드코딩 | 전수 테스트 21건 중 **20건 실패**(400 인 `INVALID_REQUEST` 만 우연히 통과) |

두 번째가 이 분리의 이유다. 열거형만 보는 테스트는 열거형과 함께 틀리므로,
**열거형과 응답을 잇는 자리를 따로 봐야** 한다.
- ~~**`common/web`** — `CorrelationIdFilter` 무테스트(헤더 재사용·생성·MDC 누수)~~
  → **메웠다.** 그 필터는 `TraceIdResponseFilter` 로 대체됐고(decisions.md 17번),
  `TraceIdResponseFilterTest` 가 세 가지를 본다 — traceId 반환, **응답 커밋 이후에도 헤더가 남는가**,
  추적이 없을 때 통과. 마지막 항목이 실제로 결함을 잡았다: `Tracer.NOOP` 은 null 이 아니라
  **빈 문자열 traceId** 를 가진 스팬을 주므로 null 검사만으로는 빈 헤더가 나간다.
  헤더 재사용·MDC 누수는 검증 대상에서 빠졌다 — 이제 라이브러리의 책임이다
- **`common/web`** — `GlobalExceptionHandler` 는 D-015 계열만 검증
- ~~**`common/event`** — `EventContractTest.events()` 가 손으로 유지하는 목록이다~~
  → **메웠다.** 목록은 손으로 두되(각 이벤트를 만들려면 의미 있는 인자가 필요하다),
  `catalogCoversEveryDeclaredEvent` 가 `payload` 패키지를 스캔해 목록과 **대조**한다.
  목록에서 한 건을 빼면 실제로 깨지는 것을 확인했다
- ~~**`common/messaging`** — `OutboxRecorder` 무테스트~~ → **메웠다.**
  `OutboxRecorderTest` 가 적재 시점의 추적 컨텍스트가 이벤트와 같은 행에 남는지,
  추적이 없어도 적재가 그대로 되는지를 본다(decisions.md 17번).
  `propagation = MANDATORY` 자체는 여전히 무테스트다 — 검증하려면 실 트랜잭션 매니저가 필요하고,
  그 자리는 앱 모듈의 통합 테스트다.
- **`common/kafka`** (신설, decisions.md 19번) — 수신 실패 처리는 <b>브로커 없이</b> 검증한다.
  `DltOpsServiceTest` 가 `MockConsumer` 로 재투입을 본다 — 원본 토픽으로 가는가,
  진단 헤더(`kafka_dlt-*`)를 떼는가, 계약 헤더와 `traceparent` 를 남기는가,
  **조회가 커밋하지 않는가**, 발행이 끝난 뒤에만 커밋하는가.
  `ConsumerRetryPolicy` 의 백오프 총량은 license 의 `KafkaErrorHandlerConfigTest` 가 이미 본다
- **`common/messaging`** — `OutboxOpsServiceTest` 가 DEAD 회수 경로를 본다(단건·일괄·오탐).
  ~~`lockPendingBatch` 가 앱 모듈에 인질로 잡혀 있다~~ → **회수했다.**
  `OutboxPendingQueryTest` 가 이 모듈에서 실 MySQL 로 쿼리 계약(상태·시간 조건·순서·배치 크기)을 검증한다.
  앱 쪽 `OutboxBackOffQueryTest` 는 "이 서비스의 스키마에서도 도는가"를 보는 통합 검증으로 남긴다
- ~~**`common/archunit`** — 모든 규칙이 `.allowEmptyShould(true)` 라 공허 통과한다~~
  → **`ArchRuleEnforcementTest` 를 추가했다.** 위반 픽스처에 규칙이 실패하는지,
  준수 픽스처에 통과하는지 양쪽을 본다. 이 테스트를 붙이자마자
  [D-021](defects.md#d-021) 이 나왔다 — 스텁 격리 규칙이 술어 결합 순서 때문에
  `Fake*` 만 검사하고 있었고, 리포의 스텁 4개는 전부 `Mock*` 이었다.
  아직 전 규칙을 덮지는 않았다(네이밍 4·경계 3·위생 2). 나머지는 같은 방식으로 채운다

### 6.4 전 모듈 공통

리스너 6곳이 전부 `verify(service).xxx(anyString(), ...)` 로 **eventId 를 `anyString()` 으로** 받는다.
eventId 는 Inbox 가드의 유일한 입력이라, 상수나 `null` 을 넘기는 어댑터도 통과한다.

store·order·payment 리스너에는 **헤더 누락·페이로드 파손 테스트가 없다**
(`apps/license` 만 `EventRecords.record(...)` 로 헤더를 빼는 경로를 쓴다).

### 6.5 층으로 덮을 수 없는 것

- **컨슈머 층 파티션 증설 시나리오** — ArchUnit 으로 막을 수 없는 유일한 항목이라
  운영 절차로 남아 있다 ([event-ordering.md](event-ordering.md) 5절)
- **부하 테스트** — [scripts/perf/](../scripts/perf/) 에 k6 시나리오와 릴레이 배수 하네스가 있다.
  CI 에서는 돌리지 않는다(실행 환경 편차가 커서 판정 기준으로 쓸 수 없다).
  측정 결과는 [performance.md](performance.md).
  **원격 전체 스택에서만 유효한 숫자가 나온다** — 랩탑 환경으로 잰 7장은 스스로 무효 선언됐고,
  절차는 9장 기준으로 README 에 적혀 있다
- **종단 지연** — 부하 시나리오 셋이 전부 주문 생성 한 경로다. 읽기 경로, 결제 콜백
  (팬아웃 최대), 컨슈머 측, 그리고 **"주문을 넣고 몇 초 뒤 게임을 받는가"** 가 측정 밖이다.
  `pending` 기울기는 그 대리 지표일 뿐이라 둘 다 초록인데 종단 지연이 길 수 있다
  ([test-audit.md](test-audit.md) F5)

### 6.6 작업할 때

- **테스트를 붙일 모듈은 `pitest` 로 확인한다.** 통과하는 테스트와 잡아내는 테스트는 다르다(5절).
  위 표의 "위험"은 대부분 *통과하지만 못 잡는* 자리다
- **`targetTests` 를 지정하지 않으면 pitest 가 `targetClasses` 패턴을 테스트 선택에도 쓴다.**
  그래서 한동안 `api.*` 의 테스트(컨트롤러·리스너·파사드)가 뮤테이션 판정에
  **한 번도 참여하지 못했다** — 전 모듈을 통틀어 뮤턴트를 죽인 테스트가
  `core.service` 와 `core.domain` 두 패키지에서만 나왔다.
  파사드가 지키는 규칙은 전부 "생존"으로 보고되고 있었고, 그건 테스트 공백이 아니라
  **측정 도구의 사각지대**였다. 루트 `build.gradle` 에서 `targetTests = ['com.stove.*']` 로 고쳤다.
  (settlement 기준 31/40 → 33/40, `api.application` 테스트가 11개를 죽인다)
  뮤테이션 점수를 읽을 때는 **무엇이 측정에 포함됐는지**를 먼저 본다
- **단언은 값으로 한다.** `anyString()` 은 "무엇이 전달되는가"가 검증 대상일 때 쓰지 않는다
- **Outbox 단언을 `count()` 차이로 끝내지 않는다.** 잘못된 이벤트를 발행해도 `count` 는 똑같이 1이다.
  `eventType`·`aggregateId`·`partitionKey` 와 페이로드를 읽는다
- **`@Cacheable`/`@CacheEvict` 는 프록시 위에서만 동작한다.** `new` 로 만든 인스턴스에 걸면
  캐시를 안 지워도 통과하는 테스트가 된다
- 데이터 격리는 삭제가 아니라 **키 공간 분리**다(4절). `SettlementCloseTest` 의 `uniqueMonth()` 처럼
  순번을 쓰는 헬퍼는 **소진 한계**를 확인하고 늘린다
