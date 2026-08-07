# 죽은 코드 조사

저장소 전체에서 **더 이상 쓰이지 않는 파일과 코드**를 찾은 기록이다.
고치지 않고 **찾은 것과 그 근거만** 남긴다 — 무엇을 지울지는 항목마다 판단이 다르고,
그 판단을 코드 변경과 섞으면 리뷰에서 둘 다 제대로 못 본다.

조사 범위 — Java 301개(main 192 · test 109), Gradle 모듈 17개, Flyway 마이그레이션 20개,
docs 8개, scripts 11개, docker-compose 4개, prometheus 설정.

---

## 결론

**버려진 파일은 없다. 타입 단위로 죽은 클래스도 없다.**
미사용 import 는 301개 파일 중 **2개**뿐이고 둘 다 테스트다.

참조가 0인 최상위 타입으로 20개가 걸렸지만 전수 확인 결과 **전부 프레임워크가 부르는 것**이었다
(`@SpringBootApplication` 9개, `@AutoConfiguration` 3개, `@ConditionalOnProperty` 로 갈리는
Mock/S3 어댑터, `@Configuration`). 진짜 잔재는 하나도 없었다.

잔재는 **메서드·상수·의존성 단위**에서 나왔다. 성격이 둘로 갈린다.

| | 개수 | 성격 |
|---|---|---|
| [A. 명백한 잔재](#a-명백한-잔재) | 7 | 참조 0이고 설계 판단이 끼어들 여지가 없다 |
| [B. 판단이 필요한 것](#b-판단이-필요한-것) | 8 | 미구현 계약 · 도달 불가능한 상태 · 테스트 전용 프로덕션 코드 |

C 절에는 **죽은 것처럼 보이지만 살아 있는 것**을 적어 둔다. 다음에 같은 조사를 하는 사람이
같은 함정을 다시 밟지 않게 하기 위해서다.

---

## A. 명백한 잔재

### A-1. order 의 Redis 스택 전체

order 는 Redis 를 **한 줄도 쓰지 않는다.** `@Cacheable`·`RedisTemplate`·`CacheManager`·
`@EnableCaching` 어느 것도 없다. 그런데 의존성·설정·테스트 컨테이너가 다 붙어 있다.

| 위치 | 내용 |
|---|---|
| [apps/order/build.gradle:5](../apps/order/build.gradle#L5) | `spring-boot-starter-data-redis` |
| [apps/order/.../application.yml:30-33](../apps/order/src/main/resources/application.yml#L30-L33) | `spring.data.redis` 블록 |
| [OrderContextTest.java:21](../apps/order/src/test/java/com/stove/order/OrderContextTest.java#L21) | `@Import` 의 `InfraContainers.Redis.class` |

[README.md:23](../README.md#L23) 도 order 를 `MySQL` 로만 적어 두었다 — Redis 가 붙은 것은
catalog 와 store 뿐이다. **문서가 이미 맞고 코드가 틀렸다.**

지우면 order 모듈 테스트 JVM 이 Redis 컨테이너를 하나 덜 띄운다.
Docker 3.83GB 랩탑에서 도는 프로젝트라 이게 그냥 이득이다.

`docker-compose.apps.yml` 의 `REDIS_HOST`/`REDIS_PORT` 는 9개 서비스 공용 YAML 앵커
(`x-common-env`)이므로 건드리지 않는다 — catalog·store 가 쓴다.

### A-2. 호출자가 0인 메서드 2개

| 위치 | 왜 죽었나 |
|---|---|
| [Order.java:85](../apps/order/src/main/java/com/stove/order/core/domain/Order.java#L85) `toOrderLines()` | `OrderCreatedEvent` 의 lines 는 `Quote` 에서 만들어진다. 엔티티에서 되돌리는 경로는 쓰인 적이 없다 |
| [ProductDocument.java:68](../apps/store/src/main/java/com/stove/store/core/domain/ProductDocument.java#L68) `onSale()` | `StoreService` 가 자기 `ON_SALE` 상수로 ES 쿼리를 건다. 문서 쪽 판정은 부르는 데가 없다 |

### A-3. 쓰이지 않는 Spring Data 파생 쿼리 2개 (download)

`DownloadService` 는 두 컬렉션 모두 **문서 ID 직접 접근**만 한다.

- [EntitlementRepository.java:8](../apps/download/src/main/java/com/stove/download/core/domain/EntitlementRepository.java#L8) — `findByMemberIdAndActiveIsTrue()`
- [ProductRefRepository.java:8](../apps/download/src/main/java/com/stove/download/core/domain/ProductRefRepository.java#L8) — `findByProductId()`

문서 ID 를 각각 `memberId:productId` 와 `productCode` 로 고정한 것이 **이 서비스의 멱등 전략**이라
(Inbox 테이블 없이 upsert 로 중복 수신을 흡수한다) 파생 쿼리가 애초에 설 자리가 없다.

### A-4. 미사용 import 2건 (전부 테스트)

- [PaymentCallbackLookupTest.java:12](../apps/payment/src/test/java/com/stove/payment/core/service/PaymentCallbackLookupTest.java#L12) — `PaymentPreparation`
- [StoreCacheProxyTest.java:11](../apps/store/src/test/java/com/stove/store/core/service/StoreCacheProxyTest.java#L11) — `ProductDocument`

### A-5. 대상이 없는 annotationProcessor

[common/web/build.gradle:7](../common/web/build.gradle#L7) 의
`spring-boot-configuration-processor` — `common:web` 에는 `@ConfigurationProperties` 가
하나도 없다. 메타데이터를 만들 대상이 없어 매 빌드마다 헛돈다.

`common:messaging` 의 같은 선언은 `OutboxProperties` 가 있으므로 **유효하다.**

---

## B. 판단이 필요한 것

지우는 것이 답이 아닐 수 있는 항목들이다. 근거와 선택지를 적어 둔다.

### B-1. 결제 실패 라인이 통째로 미구현

`OrderStatus.FAILED` 를 조사하다 나왔다. 죽은 것은 상수 하나가 아니라 **경로 전체**다.

| 위치 | 상태 |
|---|---|
| [Payment.java:186](../apps/payment/src/main/java/com/stove/payment/core/domain/Payment.java#L186) `fail()` | 호출자 **0**. `PaymentStatus.FAILED` 를 세팅하는 유일한 코드인데 아무도 안 부른다 |
| `PaymentStatus.FAILED` | 따라서 도달 불가능 |
| `EventType` | `PaymentFailed` 타입 자체가 **없다** |
| `OrderStatus.FAILED` | order 는 결제 실패를 영영 알 수 없다 |

즉 **PG 승인 실패 → payment FAILED → PaymentFailed 발행 → order FAILED** 라인이 비어 있다.
현재 승인 실패는 아무 상태도 남기지 않고, 주문은 `CREATED` 에 영구히 머문다.

> 선택지 — (a) Saga 실패 경로를 구현한다, (b) 도달 불가 상태를 지우고 "결제 실패는 취소로만
> 표현한다"를 명시한다. **(a) 를 권한다** — 지금은 실패가 관측되지 않는 상태다.

### B-2. `ProductStatus.CLOSED` — 판매 종료 경로 없음

[ProductStatus.java:19](../apps/catalog/src/main/java/com/stove/catalog/core/domain/ProductStatus.java#L19).
`Product` 에 `close()` 가 없고 `ProductController` 에도 엔드포인트가 없다.
운영툴 경로는 `sale-open` / `suspend` 둘뿐이다.

B-1 과 달리 **catalog 한 서비스 안에서 끝난다** — 엔티티 메서드 + 컨트롤러 + 기존
`publishChanged` 재사용이면 된다.

### B-3. `EventHeaders.CORRELATION_ID` — 계약만 있고 3군데 모두 미구현

지금 correlationId 가 실제로 이어지는 구간은 HTTP 뿐이다.

```
[HTTP]  client → order      CorrelationIdFilter 가 X-Correlation-Id 를 읽거나 새로 만들어 MDC 에 심는다
[HTTP]  order  → catalog    RestClientConfig 가 MDC 값을 꺼내 헤더로 다시 실어보낸다     ✅
[Kafka] order  → payment    ─────────────────────────────────────────────────         ❌
```

| 단계 | 위치 | 현재 |
|---|---|---|
| 적재 | [OutboxRecorder.java:23](../common/messaging/src/main/java/com/stove/common/messaging/outbox/OutboxRecorder.java#L23) | eventId·type·topic·key·payload 만 저장. `OutboxEvent` 에 correlation_id 컬럼 자체가 없다 |
| 발행 | [OutboxRelay.java:171](../common/messaging/src/main/java/com/stove/common/messaging/outbox/OutboxRelay.java#L171) | `EVENT_ID`·`EVENT_TYPE`·`OCCURRED_AT` 3개만 싣는다 |
| 수신 | [EventEnvelope.java:26](../common/event/src/main/java/com/stove/common/event/kafka/EventEnvelope.java#L26) | `EVENT_ID`·`EVENT_TYPE` 만 읽는다. 게다가 Kafka 리스너는 서블릿 필터를 안 타므로 MDC 가 애초에 비어 있다 |

**결과** — 주문 하나가 order → payment → license → settlement 로 흐를 때 로그를 correlationId 로
묶으면 order 에서 끊긴다. payment 이후는 로그 패턴이 `[payment,]` 로 빈칸을 찍는다.

직접 구현하려면 `OutboxEvent` 컬럼 + 마이그레이션 7개(studio·review·catalog·order·payment·
license·settlement), MDC 키 상수를 `common:core` 로 이동(ArchUnit 이 `common:messaging →
common:web` 의존을 막는다), 릴레이 한 줄, `EventEnvelope` 필드 + Kafka `RecordInterceptor`.

> [README.md:290](../README.md#L290) 가 "분산 추적(Micrometer Tracing + OTLP)으로 correlationId 를
> traceId 로 승격"을 이미 향후 과제로 적어뒀다. Micrometer Tracing 을 넣으면 Kafka 계측이 전파를
> 자동으로 하므로 **지금 손으로 만든 헤더는 그때 중복이 된다.**
> 상수를 지우고 README 과제에 "Kafka 구간 끊김"을 명시하는 쪽을 권한다.

### B-4. `Product.draft()` — 테스트 전용이지만 지우면 안 된다

[Product.java:69](../apps/catalog/src/main/java/com/stove/catalog/core/domain/Product.java#L69).
프로덕션 생성 경로는 `Product.fromReview(...)` 하나뿐인데, 이건 생성자 직후
`applyReviewApproval()` 을 불러 **APPROVED 로 올려버린다.** 즉 `fromReview` 로는 DRAFT 상태의
Product 를 만들 수 없다.

그런데 `ProductTest` 가 검증하는 규칙이 정확히 그 상태다.

```java
Product product = Product.draft("GAME-001", ...);
assertThatThrownBy(product::openSale).isInstanceOf(BusinessException.class);  // 심의 전 판매 차단
```

죽은 코드가 아니라 **상태머신의 시작점을 만드는 유일한 팩토리**다.
지우면 이 규칙을 검증할 진입점이 사라진다.

> 유지하되 javadoc 로 의도를 밝히는 것을 권한다. 그래야 다음 조사에서 또 걸리지 않는다.

### B-5. `SettlementRecordRepository.findBySettlementMonthAndClosedIsFalse()` — 테스트 전용

[SettlementRecordRepository.java:14](../apps/settlement/src/main/java/com/stove/settlement/core/domain/SettlementRecordRepository.java#L14).
프로덕션 마감은 판매자 단위로 쪼개 돈다 — `findSellerIdsToClose(month)` →
`findBySettlementMonthAndSellerIdAndClosedIsFalse(month, seller)`.
월 전체를 한 번에 읽는 쿼리는 프로덕션에 쓸 데가 없다.

[SettlementCloseTest.java:107](../apps/settlement/src/test/java/com/stove/settlement/core/service/SettlementCloseTest.java#L107) 의
"마감 후 미마감 0건" 검증은 기존 `findSellerIdsToClose(month.toString())` 가 비었는지로 대체된다.
**오히려 프로덕션이 실제로 쓰는 쿼리를 검증하게 되므로 검증력이 는다.**

B-4 와 달리 이건 지워도 잃는 것이 없다.

### B-6. `ErrorCode.UNAUTHORIZED`, `ErrorCode.LICENSE_NOT_FOUND` — 참조 0

[ErrorCode.java](../common/core/src/main/java/com/stove/common/core/error/ErrorCode.java).
`UNAUTHORIZED` 는 인증 계층이 아직 없어서 안 쓰인다 — **"잔재"가 아니라 "미도래"**다.
바로 옆의 `FORBIDDEN` 은 쓰이고 있어서, 하나만 지우면 카탈로그가 자의적으로 보인다.
`LICENSE_NOT_FOUND` 는 license 가 `NOT_FOUND` 로 처리하고 있어 성격이 다르다.

> 판단 필요. 공통 에러 카탈로그를 "미리 정의해 두는 목록"으로 볼지
> "쓰이는 것만 두는 목록"으로 볼지의 문제다.

### B-7. `OutboxProperties` — 컴팩트 생성자의 기본값 절반이 죽어 있다

[OutboxProperties.java](../common/messaging/src/main/java/com/stove/common/messaging/outbox/OutboxProperties.java).
`relayEnabled()` 와 `pollIntervalMs()` 는 **접근자 호출이 0**이다. 실제로는 다른 경로로 소비된다.

| 컴포넌트 | 실제 소비처 | 컴팩트 생성자 기본값 |
|---|---|---|
| `relayEnabled` | `@ConditionalOnProperty(... matchIfMissing = true)` | **효력 없음** |
| `pollIntervalMs` | `@Scheduled(fixedDelayString = "${stove.outbox.poll-interval-ms:1000}")` | **효력 없음** — 플레이스홀더가 `1000` 을 따로 들고 있다 |
| `batchSize`·`maxRetry`·`maxBatchesPerCycle` | 접근자로 읽는다 | 유효 |

`pollIntervalMs` 는 기본값이 **두 곳에 이중 관리**된다. 한쪽만 바꾸면 조용히 어긋난다.

> 기본값을 한 곳으로 모으는 것을 권한다.

### B-8. `docs/remote-dev-plan.md` — 지금은 유지

어디서도 링크되지 않는 유일한 문서다. 그런데 문서 스스로 이렇게 적어 두었다.

> 이 문서는 **6·7 이 끝날 때까지** 남는다 — 그때 지운다.

Phase 6 은 ✅, Phase 7(CD)이 ⏸ 이다. **자기 기준으로 아직 살아 있다.**
Phase 7 이 끝나면 그때 지운다.

---

## C. 죽은 것처럼 보이지만 살아 있는 것

정적 조사에 반드시 걸리는데 전부 정상인 것들이다. 재조사 비용을 줄이려고 남긴다.

| 걸리는 것 | 왜 살아 있나 |
|---|---|
| `*Application` 9개, `*AutoConfiguration` 3개 | 프레임워크 진입점 / `AutoConfiguration.imports` 등록 |
| `MockPgClient`·`MockRatingBoardClient`·`MockBuildStorage`·`MockTaxInvoiceIssuer` | `@ConditionalOnProperty` 로 갈리는 기본 구현 |
| `@Bean` 메서드, 컨트롤러 핸들러, `@KafkaListener`, `@Scheduled`, `@ExceptionHandler` | 전부 프레임워크가 호출 |
| `OrderLinesConverter` 의 `convertTo*` | JPA `AttributeConverter` 계약 |
| `common/archunit` 의 한글 `public static` 필드 27개 | ArchUnit JUnit5 러너가 `@ArchTest` 로 자동 수집 |
| `SharedContainers` | `InfraContainers` 하나만 참조하지만 그게 정상 구조 |
| `PaymentRepository.findByIdempotencyKey()` | 테스트 전용이지만 javadoc 에 **"운영 조회용"**이라 명시 (D-008 참고) |
| `EventRecords.ofUnrelatedType()` | `common:test` 는 main 소스셋이 곧 테스트 지원 코드 |
| `BaseTimeEntity.updatedAt` | 게터 호출은 없지만 JPA 감사로 채워져 `updated_at` 컬럼에 영속된다 |
| `scripts/perf/relay-off.override.yml` | 어느 문서도 링크하지 않지만 파일 헤더 주석이 곧 사용법인 수동 측정 도구 |

### 인접 발견 — prometheus 스크랩 대상

[infra/prometheus/prometheus.yml](../infra/prometheus/prometheus.yml) 의 타깃이 전부
`host.docker.internal` 이다. 이건 Docker Desktop / OrbStack 전용 이름이라
**Linux 원격 러너에서는 해석되지 않는다.** 파일 주석이 이미 "컨테이너로 띄우면 서비스명:포트로
교체"라고 인정하고 있다. 죽은 코드는 아니지만 `docker-compose.apps.yml` 로 스택을 띄우면
스크랩이 전부 실패한다. **별건으로 다룬다.**

---

## 조사 방법

같은 조사를 다시 할 때를 위해 적어 둔다. 전부 읽기 전용이다.

1. **타입 단위** — 각 `*.java` 의 파일명을 저장소 전체(`*.java`·`*.yml`·`*.sql`·`*.gradle`)에서
   자기 파일 제외하고 grep. 0건이면 후보.
2. **메서드 단위** — main 소스의 메서드 선언을 정규식으로 뽑아, 이름 등장 횟수가
   자기 파일 안 1회(= 선언 자체)뿐인 것을 추린다.
3. **상수 단위** — enum 상수와 `public static final` 을 모듈 안에서 개별 카운트.
   같은 이름이 여러 enum 에 있으므로(`APPROVED` 등) **모듈 범위로 좁혀야** 한다.
4. **import** — import 절의 단순명이 import 를 제외한 본문에 등장하는지 확인.
5. **의존성** — 각 모듈 `build.gradle` 의 라이브러리가 그 모듈 소스에서 실제로 쓰이는지.

**함정** — 1~3 은 프레임워크 호출을 절대 못 본다. 후보가 나오면 C 절 표를 먼저 확인하고,
어노테이션·`AutoConfiguration.imports`·`@ConditionalOnProperty`·프로퍼티 플레이스홀더를
직접 눈으로 확인한 뒤에 판정한다.

**검증** — A 절 항목을 실제로 지우고 `./gradlew compileJava compileTestJava` 를 돌려
17개 모듈 전부 통과하는 것을 확인했다(로컬에 JDK 가 없으므로 `./scripts/dev.sh` 안에서).
참조 0 판정이 맞았다는 뜻이다. 확인 후 변경은 되돌렸다 — 이 문서는 조사 기록이고,
무엇을 지울지는 항목마다 따로 결정한다.
