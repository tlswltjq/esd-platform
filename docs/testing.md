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

| 층 | 검증 대상 | 인프라 | 위치 | 1건당 |
|---|---|---|---|---|
| **L0 도메인** | 상태 전이, 금액 계산 | 없음 | `apps/*/core/domain` | ms |
| **L1 서비스** | 트랜잭션 경계, 멱등, Outbox 적재 | MySQL/Mongo | `apps/*/core/service` | 초 |
| **L2 어댑터·계약** | 봉투 파싱, 라우팅, 실패 분기, 직렬화 | 없음(대역) | `apps/*/api/listener`, `common/event` | ms |
| **L3 메시징 인프라** | 릴레이 재시도, 멱등 가드 | 없음(대역) | `common/messaging` | ms |
| **L4 구조** | 패키지 경계, 의존 방향, 순서 보장 전제 | 없음 | `*ArchitectureTest` | ms |
| **L5 기동** | 빈 구성, Flyway ↔ 엔티티 정합 | 전체 | `*ContextTest` | 십수 초 |

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

| 서비스 | 항목 | 성격 |
|---|---|---|
| **payment** | **게이트 2 미검증** — 저장소 전체에 `verify(pgClient).prepare(...)` 가 없다. "서버가 확정한 금액을 PG 에 등록한다"는 게이트 2의 핵심 주장이 단언된 적이 없다 | 위험 |
| **payment** | **게이트 3 미검증** — `PAYMENT_AMOUNT_MISMATCH` 를 단언하는 테스트가 없다. 두 미스매치 테스트 모두 `isInstanceOf(BusinessException.class)` 뿐이라 `PAYMENT_ALREADY_PROCESSED` 가 나와도 통과한다. **과다결제(`paidAmount > amount`)도 미검증** | 위험 |
| **payment** | `createReady` 의 두 번째 가드 `existsByOrderNo` — 기존 테스트는 같은 eventId 라 첫 가드에서 반환된다. **삭제해도 깨지는 테스트가 없다** | 미커버 |
| **payment** | `RefundFacade.settle` 에서 `pgClient.cancel` 자체가 실패하는 경로 (지금은 그 다음 단계인 Outbox 실패만 본다) | 미커버 |
| **settlement** | **`FeePolicy` 무테스트.** `recordSale` 경유로도 SELF 경로에 닿지 않는다(테스트가 sellerId 1001/1002 를 쓰는데 `self-seller-id: 1`). `selfSaleHasNoFee` 는 `SaleType.SELF` 와 `ZERO` 를 직접 넘겨 산술만 본다 — **`saleTypeOf`/`feeRateOf` 를 뒤집어도 깨지지 않는다** | 위험 |
| **settlement** | `net == 0` 경계(매출이 환불로 정확히 상계 — 가장 흔한 이월 케이스), 환불 역산의 `sales.isEmpty()` 조기 반환, 월 경계 환불(원매출의 달이 아니라 **현재 달**로 역산한다) | 미커버 |
| **settlement** | `SettlementBatch` 무테스트 — 연말 경계(1월 → 전년 12월), 다중 인스턴스 단일 실행(문서상 TODO) | 미커버 |
| **store** | **`featured()` 본문이 어떤 테스트에서도 실행되지 않는다** (컨트롤러 테스트는 `StoreService` 를 mock) | 미커버 |
| **store** | **Redis 캐시가 프록시 없이 테스트된다** — `StoreIndexTest` 는 `new StoreService(repository)` 라 `@Cacheable`/`@CacheEvict` 가 비활성이다. **`@CacheEvict` 를 지워도 깨지지 않는다** | 위험 |
| **store** | 실 ES 쿼리·매핑 — `findByStatusAndNameContaining` 은 대역의 `String.contains` 와 의미가 다르다. `ElasticsearchIndexInitializer` 의 매핑 적용(`status` 가 `keyword` 인지)도 미검증 | 미커버 |
| **store** | `?page=-1`, `?size=0` → `PageRequest.of` 가 `IllegalArgumentException` → 500 (D-015 계열) | 위험 |
| **download** | **`CdnUrlSigner` 무테스트** — `matchIfMissing = true` 인 **기본(운영) 어댑터**다. HMAC 계산·`s3://` 접두사 제거·TTL 전부 미검증 | 위험 |
| **download** | `DownloadControllerTest#validTicketRequestReachesTheService` 에 **`andExpect(status())` 가 없다.** mock 이 `null` 을 반환해 실제로는 500 인데 통과한다 | 즉시 교정 |
| **download** | 403(미보유) vs 404(상품 미등록) 구분이 어느 테스트에도 없다. 매니페스트 최신본 선택(모든 테스트가 버전 1개만 등록), upsert 멱등 | 미커버 |
| **license** | `recordIssueFailure` 가 **한 번도 실행되지 않는다** — 관련 테스트가 전부 `LicenseService` 를 mock 한다. `REQUIRES_NEW` 로 별도 커밋된다는 이 메서드의 존재 이유가 미검증 | 위험 |
| **license** | `republishedEventCarriesFullOwnership` 은 이름과 달리 **저장소 크기만** 단언한다. 부분 회수에서 변경된 것만 이벤트에 실리는지도 미검증 | 즉시 교정 |
| **catalog** | `Product` 의 `applyReviewApproval` `REVIEWING` 분기, `suspend` 무가드. `ProductView` 를 실제 Redis 직렬화기로 왕복(이 클래스의 존재 이유가 역직렬화 가능성이다) | 미커버 |
| **review** | `approve`/`reject`/`getRequests` — 접수 경로는 덮었지만 결정 경로가 남았다 | 미커버 |
| **review** | `ReviewControllerTest#approvalUsesGivenRating` 이 `approve(anyLong(), anyString())` 로 단언해 **`"ADULT"` 가 전달되는지 확인하지 않는다.** 등급이 뒤바뀌어도 통과한다 | 즉시 교정 |
| **order** | `CatalogRestAdapterTest` 가 catalog 4xx → 503 을 고정한다. D-019 수정으로 잘못된 수량이 order 에서 걸리므로 재검토 대상 | 판단 필요 |
| **gateway** | 라우트 `uri` 가 단언되지 않는다 — id 만 본다. store/catalog URI 가 뒤바뀌어도 전부 통과한다 | 미커버 |

### 6.3 남은 것 — common

- **`common/core` 전 모듈 무테스트.** `ErrorCode` 상수의 `HttpStatus` 매핑,
  `ApiResponse` 의 `@JsonInclude(NON_NULL)` 계약. **저장소 전체에 `jsonPath` 단언이 하나도 없어**
  응답 형식이 통째로 바뀌어도 잡히지 않는다
- **`common/web`** — `CorrelationIdFilter` 무테스트(헤더 재사용·생성·MDC 누수),
  `GlobalExceptionHandler` 는 D-015 계열만 검증
- **`common/event`** — `EventContractTest.events()` 가 **손으로 유지하는 목록**이다.
  새 페이로드는 누가 이 목록을 고치기 전까지 계약 검증을 전혀 받지 않는다.
  패키지를 스캔해 모든 `DomainEvent` 구현이 목록에 있는지 단언해야 한다
- **`common/messaging`** — `OutboxRecorder` 무테스트(`propagation = MANDATORY` 가 이 클래스의 존재 이유),
  `lockPendingBatch` 네이티브 쿼리가 이 모듈에서는 대역으로만 검증된다
  (실 SQL 은 `apps/payment` 의 `OutboxBackOffQueryTest` 에서만 — 9개 서비스가 의존하는 쿼리가 앱 모듈에 인질로 잡혀 있다)
- **`common/archunit`** — 모든 규칙이 `.allowEmptyShould(true)` 다.
  술어가 매칭을 멈추면 규칙이 **조용히 공허 통과**한다. 위반 픽스처로 규칙이 실제로 실패하는지 봐야 한다

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
  측정 결과는 [performance.md](performance.md)

### 6.6 작업할 때

- **테스트를 붙일 모듈은 `pitest` 로 확인한다.** 통과하는 테스트와 잡아내는 테스트는 다르다(5절).
  위 표의 "위험"은 대부분 *통과하지만 못 잡는* 자리다
- **단언은 값으로 한다.** `anyString()` 은 "무엇이 전달되는가"가 검증 대상일 때 쓰지 않는다
- **Outbox 단언을 `count()` 차이로 끝내지 않는다.** 잘못된 이벤트를 발행해도 `count` 는 똑같이 1이다.
  `eventType`·`aggregateId`·`partitionKey` 와 페이로드를 읽는다
- **`@Cacheable`/`@CacheEvict` 는 프록시 위에서만 동작한다.** `new` 로 만든 인스턴스에 걸면
  캐시를 안 지워도 통과하는 테스트가 된다
- 데이터 격리는 삭제가 아니라 **키 공간 분리**다(4절). `SettlementCloseTest` 의 `uniqueMonth()` 처럼
  순번을 쓰는 헬퍼는 **소진 한계**를 확인하고 늘린다
