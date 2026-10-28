# 테스트 전략

[Order 전액 환불의 RED 테스트 목록](#order-full-refund-tdd)은 7절, [Order 전체 기능·권한의 단위 테스트](#order-module-tests)는 8절을 따른다.

## 현행 Payment·Tempo 추적 검증

`python3 tools/verification/verify-tracing.py`는 기존 Jib 실행 기반에 격리된 Tempo·Grafana를 추가한다.
`--foundation-only`는 Payment 추적 저장·복원에 의존하지 않고 공통 HTTP 전파·수집·조회·Tempo 중단·샘플링 전환을 먼저 검증한다.
Payment 추적 통합은 실제 Micrometer/OpenTelemetry와 메모리 exporter로 접수/작업 스레드·부모·scope·HTTP client span을 대조한다. 별도 스케줄러 통합 검사는 처리 메서드를 직접 호출하지 않는다.
`:e2e:tracingE2eTest`는 현재 공개 구매 API·실제 ZIP 다운로드와 Tempo의 span/parent 관계를 판정하며, 원래 콜백 기반 `TracePropagationE2eTest`를 대체한다. 일반 `e2eTest`에서는 tracing 태그를 제외한다.
전용 실행 도구는 요청 저장 뒤 Payment 재시작·PG 응답 유실·결과 롤백·브로커 중단·Tempo 중단·추적 비활성·샘플링 0을 단계별로 주입한다. 필수 검사 0건·건너뜀은 통과로 판정하지 않는다.
범위·시나리오·실행 수·원자료는 [도입·검증 기록](tempo-tracing-plan.md), 조회 방법은 [실행 안내](getting-started.md#tempo에서-요청-추적-조회)를 따른다.

> 이 문서의 구 데모·성능·장애 실험 명령과 결과는 재작성 전 기록이다. 해당 실행 코드는 2026-10-21 정리했으며 [정리 전 소스](https://github.com/tlswltjq/esd-platform/blob/e32750ffc95dee1fe5b59b2e248859f84aa77154/scripts/)에서 확인할 수 있다. 현재 검증은 [tools/verification](../tools/verification/README.md)를 사용한다.


무엇을 어느 층에서 검증하고, 왜 그 층인지 적는다.
개별 결함 목록은 [defects.md](defects.md) 에 있다.

2026-10-18 License 통합 수정은 완료된 아홉 앱·공통 모듈의 단위 864건·통합 162건과 독립 환경의 License(아홉 앱) 13단계·Order(일곱 앱) 6단계·Payment(여덟 앱) 11단계, 총 30단계 실제 흐름을 OCI에서 검증했다. [범위·실행·완료 근거](license-mvp-plan.md#통합-수정-완료-기록)를 따른다. 통합 전 864건·146건 기록은 당시 근거로 보존한다. 아래 구 License 지급 실패 보상 테스트 기록은 재작성 전 이력이며 현재 업무·테스트 기준이 아니다.

2026-10-18 Download 재작업은 로컬 OrbStack에서 완료했다. 완료된 열 앱·공통 모듈의 **단위 961건**, Download·Catalog·Gateway·License의 **통합 67건**, 실제 열 앱의 **15단계**가 실패·오류·건너뜀 0건이다. 전체 앱·E2E 소스와 단위/통합 테스트 소스를 컴파일하고 OpenAPI는 갱신 옵션 없이 재검증했다. [완료 기록](download-mvp-plan.md#재작업-완료-기록) · [실행](download-guide.md)을 따른다. Download는 HTTP 대역·실제 JWT·MinIO를 검증하며 MongoDB·Kafka·업무 DB를 필요로 하지 않는다.

---

## 1. 이 시스템에서 테스트가 어려운 이유

출시·구매·권한 지급은 Kafka로 연결되고 Download는 License·Catalog를 HTTP로 조회한다. 서비스 하나를 아무리 꼼꼼히 검증해도
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
| **L2 어댑터·계약** | 봉투 파싱, 라우팅, 실패 분기, 직렬화 | 없음(대역) | `test` | `apps/*/api/listener`, `common/kafka` | ms | 매 빌드 |
| **L3 메시징 인프라** | 릴레이 재시도, 멱등 가드 | 없음(대역) | `test` | `common/messaging` | ms | 매 빌드 |
| **L4 구조** | 패키지 경계, 의존 방향, 순서 보장 전제 | 없음 | `test` | `*ArchitectureTest` | ms | 매 빌드 |
| **L5 기동** | 빈 구성, Flyway ↔ 엔티티 정합 | 전체 | `integrationTest` | `*ContextTest` | 십수 초 | 매 빌드 |
| **L6 인수** | 서비스 *사이* — 이벤트가 건너가는가, 되감기는가 | 배포된 스택 21종 | `e2e` 모듈 | `e2e/src/test` | 46건에 36초 | **main push** |
| **L7 장애 주입** | 서비스를 정지시켰을 때 격리되는가, 복구되는가 | **망가뜨려도 되는** 스택 | `e2e` 모듈 (`chaos` 태그) | `ServiceOutageChaosTest` | 회차에 수 분 | 손으로 |

L6·L7 이 `build` 밖이다. 스택이 떠 있어야 돌기 때문이고, 그 조건을 기본 빌드에 넣으면
컨테이너가 없는 로컬에서 전체 빌드가 항상 빨개진다. 대신 `./gradlew :e2e:e2eTest` 로 따로 부른다.

**L7 을 L6 에서 뗀 이유는 자원이 다르기 때문이다.** L6 은 떠 있는 스택이면 되지만
L7 은 그 스택을 **정지시킨다.** 한 회차에 두면 저니 중간에 서비스가 사라져 관계없는 판정이
무더기로 빨개지고, 그러면 "인수가 깨졌다" 와 "일부러 죽였다" 가 리포트에서 같은 모양이 된다.
시나리오와 판정 근거는 [resilience-scenarios.md](resilience-scenarios.md).
**러너가 1대라 PR 마다가 아니라 통합 시점에 붙는 것**도 같은 줄의 판단이다.

현재 스택 상태 확인은 `python3 tools/verification/stack-status.py`로 수행한다.
지정한 Compose 프로젝트의 필수 서비스와 초기화 작업만 검사하며 외부 PG·PG DB도 포함한다.
헬스체크가 없는 서비스는 실행 여부만 확인하므로 기능 검증은
`python3 tools/verification/verify-jib.py`의 격리 E2E로 별도 수행한다.

### 소스셋이 층을 강제한다

**"인프라가 필요한가"는 규약이 아니라 클래스패스가 정한다.**
컨테이너는 `common:testcontainers` 에 있고 이 모듈은 `integrationTestImplementation` 으로만
걸린다 — `src/test` 의 클래스는 `InfraContainers` 를 **임포트조차 할 수 없다.**

```
./gradlew test              945건 · Docker 불필요 · 수 초
./gradlew integrationTest   241건 · Testcontainers · 동시 스택 수는 따로 조인다
./gradlew build             둘 다 (단위가 먼저 돈다)
./gradlew :e2e:e2eTest      46건 · 떠 있는 스택이 필요하다
./gradlew :e2e:chaosTest    5건 · 스택을 실제로 정지시킨다 (L7)
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

**현재 태그가 붙은 테스트는 0건이다** — D-001~D-025 가 전부 수정돼 태그가 떨어졌다.

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

### 캐시된 컨텍스트가 쥐는 것은 스레드만이 아니다 — 커넥션도 쥔다

같은 자리에서 한 번 더 밟았다([D-036](defects.md#d-036)). 위가 **배경 스레드**의 문제라면
이번은 **커넥션 풀**이다.

컨텍스트는 `@SpringBootTest(properties = ...)` 조합마다 한 벌씩 만들어져 JVM 이 끝날 때까지
캐시에 남는다. 그 컨텍스트마다 HikariCP 가 하나씩 붙고, 앱 설정의 `maximum-pool-size: 20` 은
**운영 인스턴스 한 대**를 위한 값이다. Hikari 의 `minimumIdle` 기본값이 `maximumPoolSize` 라
**그 20개는 놀아도 열려 있다.**

MySQL 기본 `max_connections` 는 151 이므로 **여덟 벌이면 벽이다.** payment 는 이미 그 언저리였고,
통합 테스트 클래스를 **하나** 더하자 `Too many connections` 로 컨텍스트 로딩이 실패했다.
빨개진 것은 새로 넣은 클래스가 아니라 **기존 클래스**였고, 어느 것이 빨개질지는
실행 순서에 달려 회차마다 달랐다.

```groovy
// 루트 build.gradle 의 integrationTest 태스크
systemProperty 'spring.datasource.hikari.maximum-pool-size', '5'
```

**시스템 프로퍼티여야 한다.** `@SpringBootTest(properties = ...)` 로 덮으면 그 자체가
컨텍스트 캐시 키가 되어 **컨텍스트를 한 벌 더 만든다** — 줄이려던 것을 늘리는 셈이다.

> 이 수정은 **한계선을 미룬 것이지 없앤 것이 아니다.** 컨텍스트가 서른 벌을 넘기면 다시 닿는다.
> 그때는 프로퍼티 조합을 줄이는 쪽이 답이지 풀을 더 줄이는 쪽이 아니다 —
> 풀이 작아지면 이번에는 **풀 고갈이 간헐 실패로** 나타난다.

### 세 번째로 밟았다 — 컨슈머 그룹 멤버십도 쥔다

같은 자리에서 셋째다([D-037](defects.md#d-037)). 배경 스레드 · 커넥션 풀 다음은 **그룹 멤버십**이다.

앞의 둘은 캐시된 컨텍스트가 쥔 것이 **자원**이라 많이 쓰면 고갈됐다. 이번에 쥔 것은
**카프카 컨슈머 그룹의 자리 하나**고, 그것은 고갈되는 대신 **옮겨 간다.**

`ConsumerRestartCatchUpTest`(R-03)는 "지급 서비스가 중단됐다" 를 재현하려고
`KafkaListenerEndpointRegistry` 로 리스너를 멈춘다. 그런데 그 레지스트리는
**자기 컨텍스트의 것**이다. `LicenseOpenApiContractTest` 는 프로퍼티가 달라 캐시 키가 갈리므로
같은 JVM 에 **두 번째 컨텍스트**로 살아 있고, 그쪽 리스너도 같은 그룹 `license` 의 멤버다.

그래서 한쪽을 멈추면 서비스가 멈추는 것이 아니라 **파티션이 다른 쪽으로 넘어간다.**
카프카 입장에서 이것은 장애가 아니라 **정상적인 리밸런싱**이다.

```
[Consumer clientId=consumer-license-1, groupId=license] Revoke previously assigned partitions stove.payment.v1-0
```

**한 컨텍스트의 리스너를 멈추는 것과 그 서비스의 소비를 멈추는 것은 다르다.**
그 둘이 같아지려면 그룹에 멤버가 하나뿐이어야 하고, 그 조건은 테스트 클래스 하나가
지킬 수 있는 것이 아니다 — **JVM 안의 모든 컨텍스트가 함께 지켜야 한다.**

`*ContextTest` 에 `spring.kafka.listener.auto-startup=false` 를 준다. 위의 폴링 재우기와
같은 처방이다 — **빈과 엔드포인트 등록은 그대로 두어 배선 검증은 유지하고, 그룹에만 안 붙는다.**

> 셋 다 원인은 하나다. **컨텍스트 캐시는 "쓰고 버리는 것" 이 아니라 "계속 살아 있는 것" 이다.**
> `*ContextTest` 는 구성을 전부 켜는 것이 목적이라 이 함정에 가장 자주 걸린다 —
> 그러면서 정작 자기 판정은 켜진 것들이 **도는지**를 묻지 않으므로, 재워도 잃는 것이 없다.

#### 멤버를 늘리는 두 번째 길 — 빈 오버라이드

`*ContextTest` 를 재운 것으로 끝이 아니다. **`@MockitoSpyBean` · `@MockitoBean` 도 캐시 키를 바꾼다.**
그것을 쓰는 클래스는 자기 컨텍스트를 한 벌 더 만들고, 그 컨텍스트의 리스너도 같은 그룹에 붙는다.
즉 **장애를 주입하려고 목을 쓰는 순간 그룹 멤버가 하나 늘어난다** — 하필 컨슈머 정지를
전제로 하는 판정 옆에서.

실제로 `DeadLetterReplayRecoveryTest`(R-07)의 첫 판이 그랬다. 저장소 장애를 만들려고
`@MockitoSpyBean LicenseRepository` 를 썼더니 R-03 이 다시 깨졌다.

> **규칙** — 컨슈머 정지를 전제로 하는 테스트가 있는 모듈에서는
> **장애 주입에 빈 오버라이드를 쓰지 않는다.** 컨텍스트 키를 건드리지 않는 방법을 찾는다.
> R-07 은 목 대신 **원장 테이블을 다른 이름으로 옮긴다** —
> `scripts/chaos/fault.sh` 의 `license-table-denied` 와 같은 경계이고, 되돌리는 것도 `RENAME` 한 줄이다.
> 목보다 실물에 가깝기도 하다: **예외 클래스를 우리가 고르지 않는다.**

### ShedLock 의 락 행을 지우면 락이 꺼진다

락을 풀려고 `delete from shedlock` 을 했더니 **그 뒤의 모든 획득이 조용히 실패했다.**
대조군이 "0회 실행 · 락 행 없음" 으로 빨개져서 잡혔다.

ShedLock 의 저장소 기반 공급자는 **"이 이름의 행이 있다" 를 메모리에 캐시한다.**
있다고 믿는 동안은 INSERT 대신 `UPDATE ... WHERE lock_until <= now()` 로 획득하는데,
행이 사라졌으면 그 UPDATE 가 0건이라 **"이미 잠겨 있다" 와 구분되지 않는다.**
결과는 조용한 스킵이고, **락을 끈 것과 같은 상태**가 된다.

```sql
-- 지우지 않는다. 만료시킨다.
update shedlock set lock_until = locked_at where name = ?
```

행이 아예 없는 첫 회차에는 0건이 갱신되고, 그때는 공급자가 INSERT 경로로 정상 획득한다.

> **대조군이 있어서 잡혔다.** 동시성 판정만 두었으면
> "0회 실행" 을 보고 <b>락이 잘 막았다</b>고 읽었을 것이다 —
> 그래서 `RefundSweeperSingleExecutionTest` 는 "한 번 부르면 한 번 돈다" 를 먼저 묻는다.

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
`StudioService`(지금은 `GameProjectService`·`GameBuildService`)·`ReviewService`·`ProductCommandService` 가 테스트에서 `mock()` 으로만 등장해
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
| **catalog** | ~~`?sort=<모르는 속성>` → 500~~ → **[D-024](defects.md#d-024) 로 확인·수정.** 서비스가 허용 키를 명시하고 400 으로 응답한다. 저장소에서 `Pageable` 을 직접 받는 유일한 컨트롤러였다. 이어서 **[D-025](defects.md#d-025)** 가 열어 준 키의 결정성까지 닫았다 — 꼬리표(`id desc`)가 붙고, 통합 테스트가 상태 코드가 아니라 **순서**를 단언한다 | ✅ |
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
- **장애 주입** — [scripts/chaos/](https://github.com/tlswltjq/esd-platform/blob/e32750ffc95dee1fe5b59b2e248859f84aa77154/scripts/chaos/) 가 부하 중에 장애를 넣고 주문별 결말을 센다.
  CI 에서는 돌리지 않는다(전체 스택과 수 분의 벽시계가 필요하다). 측정은 [chaos.md](chaos.md).
  **여기서 나온 결함은 계층 테스트로는 안 나온다** — Saga 보상·컨슈머 재시도·멱등 가드·DLT 는
  평상시에 아무 일도 하지 않아서, 경로가 성립하는 테스트로는 실행 자체가 되지 않는다
- **부하 테스트** — [scripts/perf/](https://github.com/tlswltjq/esd-platform/blob/e32750ffc95dee1fe5b59b2e248859f84aa77154/scripts/perf/) 에 k6 시나리오와 릴레이 배수 하네스가 있다.
  CI 에서는 돌리지 않는다(실행 환경 편차가 커서 판정 기준으로 쓸 수 없다).
  측정 결과는 [performance.md](performance.md).
  **원격 전체 스택에서만 유효한 숫자가 나온다** — 랩탑 환경으로 잰 7장은 스스로 무효 선언됐고,
  절차는 9장 기준으로 README 에 적혀 있다
- **종단 지연** — 부하 시나리오 셋이 전부 주문 생성 한 경로다. 읽기 경로, 결제 콜백
  (팬아웃 최대), 컨슈머 측, 그리고 **"주문을 넣고 몇 초 뒤 게임을 받는가"** 가 측정 밖이다.
  `pending` 기울기는 그 대리 지표일 뿐이라 둘 다 초록인데 종단 지연이 길 수 있다

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

<a id="order-full-refund-tdd"></a>

## 7. Order 전액 환불의 RED 테스트 목록

2026-10-25. 기준은 [환불 정책 결정](decisions.md#full-refund-policy)과 완료된 모듈의 현재 계약이다.
아래 R 번호 표는 **작성할 행위 테스트 계획**이다. 개별 Red·Green 실행은 별도 기록하며 일부 도메인 테스트의 결과를 전체 시나리오 완료로 표시하지 않는다. R 번호는 시나리오 식별자이며 JUnit 실행 건수가 아니다.
정책을 새로 정해야 하는 사항은 [미결 질문](domain/open-questions.md#q9)과 7.8절에서 먼저 결정한다.

현재 구현/검증은 [인계의 검증 표](refund-session-handoff.md#검증과-근거)를 먼저 확인한다. 아래 날짜별 계획·미결 표기는 당시 상태이며 최신 합의를 대신하지 않는다.

### 7.1 기존 Green과 Order의 검증 경계

현재 [RefundPolicyTest](../apps/order/src/test/java/com/stove/order/core/domain/RefundPolicyTest.java)의 10건은 시간 판정의 Green으로 유지한다.
정확히 4시간인 접수의 허용, 마이크로초 경계, 초·시간 절삭 방지, 현재 날짜 대신 기록된 두 시각을 사용하는 규칙을 이미 검증한다.
새 접수 테스트에서는 실제 주문에 기록된 결제 확정 시각과 최초 접수 시각이 이 판정에 사용되는지 확인한다.

2026-10-25 첫 환불 모델 TDD는 [RefundTest](../apps/order/src/test/java/com/stove/order/core/domain/RefundTest.java)로 R04·R05의 도메인 부분부터 작성했다.
기한 내·정확한 4시간 경계는 PENDING_LICENSE_SUSPENSION, 기한 초과는 PENDING_REVIEW로 시작하고, 최초 접수·자동 허용 판정을 보존하며 Order의 PAID·원래 결제 결과는 유지하는 기대다.
`./gradlew :apps:order:test --tests com.stove.order.core.domain.RefundTest --console=plain` 실행에서 컴파일은 통과하고 Refund.request 미구현 예외로 3건 모두 실패했다.
원자료는 `runs/order-refund-domain-red-r6wowh0h/`의 JUnit XML·result.json·소스 사본과 SHA-256에 보존했다. DB 저장·멱등 접수·Outbox 원자성은 이 첫 도메인 Red의 검증 범위에 포함하지 않는다.

이후 필수 주문·접수 시각 누락과 PENDING·FAILED 주문 거절 4건을 추가하고, 미구현 상태에서 총 7건의 Red를 확인했다.
Refund.request가 기존 RefundPolicy로 최초 자격을 판정하고 식별자·주문 연결·접수 시각·초기 상태를 생성하도록 구현했다. Order는 변경하지 않는다.
`./gradlew :apps:order:test --console=plain`에서 RefundTest 7건과 기존 테스트를 포함한 전체 143건이 실패·오류·건너뜀 0건으로 통과했다.
Red·Green의 JUnit XML, 명령, 소스 사본·SHA-256은 `runs/order-refund-domain-tdd-jtwz7ekb/`에 단계별로 보존했다. 접수 시각 역전 처리 정책은 아직 결정하지 않았다.

2026-10-25, [RequestRefundTest](../apps/order/src/test/java/com/stove/order/core/service/RequestRefundTest.java)에 R04의 서비스 단위 Red 1건을 추가했다.
본인 PAID 주문을 결제 확정 후 3시간에 접수하고, 주문 잠금 뒤 기존 환불 조회·Refund 저장과 최초 정지 요청 Outbox 기록을 기대한다.
고정 Clock으로 최초 접수 시각·자동 허용 판정·PENDING_LICENSE_SUSPENSION을 확인하고, Outbox의 환불·주문·구매자·상품·결제 연결값과 Order 토픽·주문번호 키를 검증한다.
최초 결제 결과는 유지하고 Outbox에는 정지 요청만 한 번 기록해야 한다. 저장된 과거 주문 픽스처의 생성 시각은 결제 확정 2시간 전으로 두어, 주문 생성 시각을 기한 시작으로 쓰는 오구현도 잡도록 했다.
컴파일에 필요한 RequestRefund 미구현 골격·RefundRepository 저장 계약·LicenseSuspensionRequestedEvent를 추가했다. 서비스는 아직 Spring 빈으로 등록하지 않고, 실제 DB 저장 구현·트랜잭션 조립도 추가하지 않았다.
새 이벤트는 기존 공통 이벤트 계약 카탈로그에도 등록해 직렬화 왕복·메타데이터·전방 호환·토픽 검증에 포함했다.
`./gradlew :common:kafka:test :apps:order:test --continue --console=plain` 실행에서 컴파일은 통과했고, Order 144건 중 새 서비스 테스트 1건만 RequestRefund.execute의 미구현 예외로 실패했다. 기존 Order 143건과 공통 Kafka 171건은 실패·오류·건너뜀 없이 통과했다.
실행 로그·JUnit XML·명령·기준 커밋·소스 사본과 SHA-256은 `runs/order-refund-reception-red-hswhjlph/`에 보존했다.
이 Red는 Mockito 기반 서비스 조율 검증이며 실제 DB 원자성·잠금 효과, 동시 접수·실제 메시지 전달·License 업무 완료를 증명하지 않는다. R04의 실제 DB 검증과 R08·R09는 후속 범위다.

2026-10-25, 담당자는 논의에서 선택한 범위의 테스트·코드 작성을 위임했다. 새로운 정책과 중요한 대안은 담당자가 선택하고 이유를 설명한 뒤 구현하며, 합의한 범위의 코딩·검증에는 매번 재승인을 요청하지 않는다.
접수 서비스는 R04 Green 1건 → R05 Red·Green 2건 → R07 Red·Green 3건 순으로 확장했다. R05는 심사 대기에서도 정지 요청이 발행되는 실패, R07은 중복 접수에서 정지 요청이 다시 발행되는 실패를 각각 확인하고 수정했다.
누락·공백·길이 초과 입력 8건도 INVALID_REQUEST 대신 NOT_FOUND가 반환되는 Red를 확인한 뒤 서비스 입력 검증을 추가했다. 이때 함께 추가한 없는 주문·타인 주문·PENDING·FAILED 거절 4건은 기존 구현으로 Green이었으며 Red로 집계하지 않는다.
잠금 대기로 기한을 넘겨도 서비스 진입 시 확보한 접수 시각을 유지하는 회귀와 심사 대기 중복 접수 보존도 보완해 RequestRefundTest는 총 17건이다.
서비스의 READ_COMMITTED 트랜잭션, JPA Refund·저장소, V12 order_refund의 환불 ID·주문번호 유일 제약과 주문 연결 외래 키, config의 객체 조립을 추가했다.
운영 Clock은 기존 DATETIME(6) 저장 정밀도에 맞춘 마이크로초 단위이며 초 단위 절삭은 하지 않는다. 최초 접수 시각·자동 허용 판정·주문 연결은 생성 후 갱신하지 않는다.
`./gradlew :common:kafka:test :apps:order:test :apps:order:compileIntegrationTestJava --continue --console=plain`은 성공했다. Order 160건·공통 Kafka 171건이 통과했고 통합 테스트 소스도 컴파일했다. 공통 Kafka 태스크는 앞선 통과 결과와 소스가 같아 UP-TO-DATE였다.
단계별 명령·로그·JUnit XML·소스 사본과 SHA-256은 `runs/order-refund-reception-tdd-opx7e0k1/`의 r04-green·r05-red/green·r07-red/green·guards-red·final-green에 보존했다. R04의 최초 Red는 앞선 `runs/order-refund-reception-red-hswhjlph/`를 함께 사용한다.

[RefundReceptionIntegrationTest](../apps/order/src/integrationTest/java/com/stove/order/RefundReceptionIntegrationTest.java)는 실제 MySQL에서 접수·심사·중복·Outbox 삽입 후 실패 롤백과 재접수·동시 접수·서비스 잠금 우회 시 유일 제약·타인 주문·미결제 주문을 검증할 9건이다. Kafka 없이 DB 경계만 사용한다.
그중 정상 접수 한 건의 실제 실행을 시도했으나 DockerClientProviderStrategy의 Docker 환경 연결 실패로 컨텍스트 시작 단계에서 실패했다. 테스트 본문에는 진입하지 못했으며 이 결과는 업무 Red가 아니다.
`integration-infra-check/`에 해당 로그·JUnit XML을 보존했다. 당시 로컬 환경에는 접근 가능한 OrbStack/Docker 소켓이 없어 실제 DB 검증은 미검증으로 남겼다. 이후 명시적인 OCI 검증 요청에 따라 아래 명령의 원격 실행 결과를 별도로 확인했다.

```bash
./gradlew :apps:order:integrationTest --console=plain
```

2026-10-25, 사용자의 `ssh sng` OCI 검증 요청에 따라 이번 실행은 로컬 OrbStack 기본 지침의 예외로 원격에서 수행했다.
공유 작업본을 덮어쓰지 않고 `/tmp/stove-order-refund-check-c9ntg4wp/work`에 미커밋 변경을 포함한 859개 파일을 전달했다. 실행 전 각 파일의 SHA-256을 대조했다. 환경은 Linux ARM64·Temurin 21.0.12+8·Docker 28.1.1이며 Testcontainers의 전용 MySQL·Kafka와 임의 포트를 사용했다.
첫 실행은 Order 단위 160건·공통 Kafka 171건 통과, Order 통합 44건 중 새 환불 테스트 2건 실패였다. Outbox spy의 장애 주입 설정이 MANDATORY 프록시를 트랜잭션 밖에서 호출했고, 그 설정 실패가 다음 테스트의 UnfinishedStubbingException으로 이어졌다.
기존 통합 테스트와 같이 장애 주입 설정만 TransactionTemplate 안에서 수행하고 finally에서 spy를 초기화하도록 수정했다. 실제 서비스 호출은 테스트의 외부 트랜잭션 없이 실행하므로 서비스가 생성하는 커밋·롤백 경계를 검증한다. 이 수정은 테스트 픽스처 한 파일이며 운영 코드는 변경하지 않았다.
그 뒤 `./gradlew :apps:order:integrationTest --console=plain --no-daemon --max-workers=2`로 전체 Order 통합 44건을 다시 실행해 실패·오류·건너뜀 0건을 확인했다. 새 환불 접수 9건도 모두 통과했다. 단위 160건·171건은 첫 원격 실행의 통과 결과다.
V12 마이그레이션과 JPA 검증, R04·R05·R07의 실제 저장·분기·최초 자격 보존, R08의 Outbox 삽입 후 전체 롤백·재접수, R09의 동시 접수·서비스 잠금 우회 시 주문번호 유일 제약을 확인했다. 기존 Order HTTP·JWT·결제 결과·Kafka·마이그레이션 회귀도 함께 통과했다.
검증 전후 기존 컨테이너 18개의 ID·실행 여부·시작 시각·재시작 횟수·health와 기존 볼륨 55개가 동일했다. 추가 컨테이너·볼륨과 잔여 Testcontainers도 없었다. 공유 배포를 재시작하거나 교체하지 않았다.
원격의 `results/initial`·`results/rerun`·`results/preservation.json`과 로컬 `runs/order-refund-oci-zem6q_3u/`에 명령·로그·JUnit XML·초기/수정 소스 묶음·SHA-256·검증 전후 자원 목록을 보존했다. 집계는 로컬 validation-summary.json, 회수 원자료는 collected/results에 있다.
완료 범위는 Order의 환불 접수 서비스와 DB 경계다. 환불 HTTP API·관리자 승인/반려·License 정지/복구·Payment의 PG 환불과 전체 흐름은 후속 범위다.

2026-10-26, 합의한 License 지급 확인 이벤트와 Order의 대기·재개를 추가했다. 즉시 지급 확인을 조회하지 않아도 된다는 선택을 반영하며 기존 지급 기록의 일괄 보완·확인 요청 방식은 구현하지 않았다.
LicenseGranted는 지급 시각·보유권·주문·구매자·상품·결제 식별자를 전달한다. License의 최초 지급과 확인 Outbox가 함께 저장되고, Order는 내부 LicenseGrant로 변환·검증한 뒤 V13 order_license_grant에 확인 근거를 보존한다.
환불은 지급 미확인이면 PENDING_LICENSE_GRANT로 대기한다. 기존 확인 근거가 있거나 나중에 확인이 도착하면 한 번만 정지 요청을 발행한다. 접수와 확인 반영이 같은 주문 잠금을 사용하므로 두 처리의 순서·동시성에 관계없이 최초 자격과 정지 요청의 유일성을 유지한다.
도메인·발행 테스트는 도메인 10건 중 새 동작 5건 실패와 License 발행 1건 실패를 확인한 뒤 Green으로 만들었다. Order의 확인 반영 9건도 미구현 예외에 의한 Red 후 Green을 확인했다. 입력·리스너·기존 접수 테스트는 새 선행 조건에 맞춰 보완했다.
로컬 단위는 Order 185건·License 73건·공통 Kafka 175건 통과했다. 생성자 확장에 따른 통합 검증 픽스처·릴레이 계측 조립의 컴파일 오류를 수정하고 두 모듈 통합 소스의 컴파일도 통과했다. 이 컴파일 오류는 업무 Red로 집계하지 않는다.
원자료는 runs/refund-grant-tdd-wmjhghc1의 domain-producer-red/green·receiver-red/green·local-full·local-compile-fixed·local-compile-green에 보존했다.
사용자가 허용한 ssh sng OCI 검증을 같은 격리 방식으로 이어가 /tmp/stove-refund-grant-check-24p05p14/work에서 871개 소스 파일을 SHA-256으로 대조했다. 원격에서 Order 단위 185건·통합 49건, License 단위 73건·통합 43건, 공통 Kafka 175건이 모두 실패·오류·건너뜀 0건으로 통과했다.
실제 MySQL에서 확인 선행·후행, 4시간 이후 재개 시 최초 자격 유지, 확인·접수의 두 동시 순서, 중복 처리, 확인 Inbox·근거·환불 상태·정지 요청의 전체 롤백과 재처리를 검증했다. Kafka에서는 License의 확인 Outbox 발행 ACK와 Order의 확인 이벤트 수신·중복 재개 방지도 확인했다. 이는 각 모듈 계약 경계의 검증이며 전체 앱·PG 환불 E2E 완료를 뜻하지 않는다.
License 릴레이의 기본값을 활성화하고 DB 검증에서는 명시적으로 비활성화했다. V6 마이그레이션은 이전 계약의 대기 Outbox를 원본 보존 후 격리하며, 기존 구매의 지급 사실을 자동으로 재통지하지 않는다. 기존 보유권·Inbox는 보존한다.
공유 컨테이너 18개의 ID·실행·시작 시각·재시작 횟수·health와 기존 볼륨 55개가 동일했고 잔여 테스트 컨테이너·볼륨도 없었다. 원격 results와 로컬 runs/refund-grant-oci-m_f835mh/collected/results에 로그·JUnit XML·소스 묶음·명령·전후 자원 목록을 보존했다.
tools/verification/verify-license.py의 Outbox 0건 기대를 새 지급당 LicenseGranted 1건으로 갱신하고 Python 구문 검사를 통과했다. 해당 9개 앱·13단계 검증 스크립트는 이번에 다시 실행하지 않았다. 환불 HTTP·관리자 판정·실제 보유권 정지/복구·PG 환불·기존 지급 보완·새 흐름의 Tempo 검증은 후속 범위다.

2026-10-26, 정지된 보유권을 라이브러리에 표시하는 A안을 반영했다. License 상태 변경 도메인 3건, 정지 서비스 7건의 미구현 동작 Red를 확인한 뒤 Green으로 만들었다. Download HTTP 단위 33건 중 상태 미확인 허용을 잡는 새 6건도 Red 후 Green을 확인했다. 최초 실행의 License 컴파일 오류와 당시 복사된 이전 73건 XML은 도메인 Red 근거로 집계하지 않는다. 입력 검증·리스너 12건도 추가했다.
로컬 전체 단위는 공통 Kafka 179건·License 95건·Download 99건을 통과했고 두 앱의 통합 소스 컴파일도 통과했다. 원자료는 runs/license-suspend-tdd-pcqm9dw_의 access-red·license-state-red·access-green·suspension-service-red/green·local-full에 보존했다.
새 계약은 라이브러리 목록·상세의 status(ACTIVE/SUSPENDED)다. 정지 상품도 조회되지만 Download는 ACTIVE만 허용한다. SUSPENDED는 403, 상태 누락·미지원 값은 503으로 거절하고 Catalog·저장소 호출로 진행하지 않는다. 기존 presigned URL의 만료 전 사용은 허용한다. LicenseSuspended 발행 이후 Order의 완료 수신·Payment 실행·복구는 이번 구현에 포함하지 않는다.
사용자가 허용한 ssh sng로 /tmp/stove-license-suspend-check-fbie80hr/work에 격리 작업본을 만들고 885개 파일의 SHA-256을 대조했다. 공통 Kafka 179건, Order 단위 185건·통합 49건, License 단위 95건·통합 48건, Download 단위 99건·통합 10건, 합계 665건이 실패·오류·건너뜀 없이 통과했다.
License의 새 DB 통합 5건은 정지 후 구매 사실·지급 시각과 라이브러리 노출 유지, 과거 지급 재전달의 무복구, 정지 요청의 중복 처리, Outbox 삽입 후 상태·처리 근거·Inbox 전체 롤백과 같은 요청 재처리, 동시 요청의 한 번 처리, 변경된 요청 근거 거절과 Kafka 요청 수신을 확인했다. V5 이전 자료의 최신 마이그레이션에서 ACTIVE 초기값·원래 구매 근거도 확인했다. OpenAPI는 status가 추가된 스냅샷과 비교했다.
Download의 실제 MinIO 통합은 정지 후 새 요청 403·Catalog 미호출, 상태 누락·미지원 값 503, 기존 URL의 만료 전 GET 200을 확인했다. License 연동은 HTTP 픽스처이며 모든 실제 앱을 연결한 환불 E2E는 아니다. PG 환불·복구·기존 지급 확인 보완과 새 흐름의 Tempo 검증은 남아 있다.
검증 전후 공유 컨테이너 18개의 ID·실행·시작 시각·재시작 횟수·health와 볼륨 55개가 동일했다. 추가 컨테이너·볼륨·잔여 Testcontainers도 없었다. 공유 배포는 변경하지 않았다. 원격 results와 로컬 runs/license-suspend-oci-6onh6n_u/collected/results에 로그·JUnit XML·소스 묶음·명령·전후 자원 목록을 보존했다. SHA 대조 후 달라진 파일은 이 실행 결과를 기록한 문서 3개뿐이며 validation-summary.json에 구분했다.

2026-10-26, 고객·운영자에게 환불 처리 경위를 설명하기 위해 실행 이력을 별도로 보존하는 B안을 선택했다. 내부 refundId와 attemptId를 구분하며, PG 멱등 키 매핑은 별도 계약으로 남긴다. RefundAttempt의 시작·확정 성공/실패·결과 시각·실패 사유와 같은 결과의 중복 반영을 구현했다. 시도를 추가해도 이전 실패를 덮어쓰지 않고, 같은 기록의 다른 확정 결과도 거절한다. PENDING은 아직 확정 결과가 없다는 뜻이며 타임아웃을 확정 실패로 저장하는 경로는 추가하지 않았다.
도메인 12건의 미구현 동작 Red 뒤 Green을 확인했다. 로컬 Order 단위 197건과 통합 소스 컴파일이 통과했다. 최초 통합 픽스처에서 존재하지 않는 저장소 조회 메서드를 사용한 컴파일 오류를 수정했으며, 이는 업무 Red로 집계하지 않는다. 원자료는 runs/refund-attempt-tdd-fy3x99cm의 domain-red/green·local-full·local-compile-fixed에 보존했다.
V14 order_refund_attempt는 같은 환불의 실행 순번과 attemptId에 유일 제약, 기존 Refund에 외래 키를 둔다. 실제 DB 검증은 과거 실패·최초 접수·자격·원래 결제 보존, 두 실행의 순서 조회, 중복 순번/ID 거절, 없는 환불 참조 거절과 결과 변경·새 기록의 전체 롤백을 대상으로 한다. 이력 저장 능력의 검증이며 새로운 실행을 실제로 허용하거나 PG를 호출하는 검증과 구분한다.
사용자가 허용한 ssh sng OCI의 /tmp/stove-refund-attempt-check-crgt41aa/work에서 891개 파일을 SHA-256으로 대조하고 Order 단위 197건·통합 54건을 통과했다. 새 DB 통합은 위 저장 경계의 5건이다. 실패·오류·건너뜀은 모두 0건이며 공유 컨테이너 18개·볼륨 55개가 동일했고 추가 자원·잔여 Testcontainers도 없었다. 원격 results와 로컬 runs/refund-attempt-oci-3lfhuyf8/collected/results에 로그·JUnit XML·소스 묶음·명령·전후 자원 목록을 보존했다. validation-summary.json은 XML 집계·소스 해시와 실행 후 문서 변경을 구분한다.
이번 단계는 모델·저장 경계다. Order의 정지 완료 수신과 첫 실행 기록·Payment 요청 Outbox의 원자적 저장, 현재 실행 검증·동시 실행 차단, 실패 분류·복구 확인·사용자 재시도 허용과 PG 키 변환은 후속이다. DB 유일 제약과 이력 분리만으로 환불 실행의 멱등성을 보장했다고 주장하지 않는다.

2026-10-26, 근거 없는 정지 완료는 자동 업무 대기 대신 거절·격리하는 A안을 선택하고 Order의 완료 수신 경로를 구현했다. mapper·유효한 내부 입력 생성 뒤 주문 잠금으로 원래 PAID 주문·지급 확인·환불·실제 발행한 정지 요청 ID를 대조한다. 일치하면 PENDING_PAYMENT_REFUND로 전이하고 첫 RefundAttempt·완료 Inbox·RefundExecutionRequested Outbox를 같은 트랜잭션으로 저장한다. 최초 접수 시각과 환불 자격을 다시 계산하지 않는다.
도메인 7건은 요청 상관키 생성 누락과 전이 미구현으로 Red를 확인한 뒤 Green으로 만들었다. 완료 반영 서비스 16건도 미구현 Red 뒤 Green을 확인했다. 입력·리스너 21건을 보완했고 로컬 Order 단위 241건·공통 Kafka 183건과 통합 소스 컴파일이 통과했다. 원자료는 runs/refund-completion-tdd-d42873w8의 domain-red/green·service-red/green·local-full·local-integration-compile에 보존했다.
정지 요청 ID를 Outbox 발행 때마다 새로 만드는 대신 Refund의 상관키를 사용한다. 실행 기록에는 원래 정지 요청·완료 이벤트·완료 시각·보유권·Payment 요청 이벤트 ID를 보존한다. 중복 완료는 이 근거와 Inbox를 대조하며 새 실행·요청을 만들지 않는다. 현재 실행 포인터와 이력이 모순되거나 근거가 바뀐 재전달은 정상 중복으로 받아들이지 않는다.
전용 Kafka 소비 설정은 RefundCompletionRejected만 업무 재시도 없이 기존 DLT 발행자로 격리하고, DB 등 일시 장애는 기존 제한 재시도를 유지한다. 다른 Order 소비자의 재시도 정책은 유지한다. DLT 발행이 실패하면 원본 소비 완료로 처리하지 않는다. 격리된 통지는 환불 실패 확정이나 보유권 복구를 뜻하지 않는다.
V15는 기존 접수의 상관키를 로컬 Outbox의 단일 일치 요청과 PAID 주문·지급 확인에서만 복원한다. 요청 원문이 없거나 여러 건이거나 구매 근거가 맞지 않으면 NULL로 남기고 새 요청 ID를 추정 발급하지 않는다. 기존 구매의 LicenseGranted 확인 누락을 보완하는 작업은 아니다. Payment의 실제 접수·PG 환불·결과·복구·사용자 재시도와 새 흐름의 Tempo 검증은 후속이다.
ssh sng OCI의 /tmp/stove-refund-completion-check-2hvqx8gn/work에서 904개 파일을 SHA-256으로 대조했다. Order 단위 241건·공통 Kafka 183건을 통과했다. 실제 DB의 완료·중복, Outbox 삽입 후 전체 롤백·재처리, 동시 완료, 변경된 근거 거절, 미승인 심사·Inbox 유실 거절 6건과 기존 요청 상관키의 유일/부재/중복/구매자 불일치 마이그레이션 4건을 통과했다.
초기 통합 실행에서 생성 컬럼까지 복사한 마이그레이션 픽스처와 Boot KafkaTemplate 빈의 제네릭을 잘못 지정한 테스트 대역을 수정했다. 이후 전체 통합 68건 중 Kafka 원문 비교 1건이 JSON 숫자 노드 타입 차이로 실패했다. 원문 문자열을 직접 대조하도록 수정하고 해당 Kafka 클래스의 4건만 다시 실행해 모두 통과했다. 그 사이 운영 코드는 변경하지 않았다. 최종 통합 근거는 앞선 전체 실행의 나머지 64건과 선별 재실행 4건이며, 단일 전체 실행 68건이 한 번에 통과한 기록으로 표기하지 않는다.
Kafka 4건은 중복 완료의 실행 1건·소비 위치 커밋, 환불 근거 부재의 업무 처리 1회 후 DLT 원문·키·파티션·소비 그룹 보존, DB 일시 장애의 같은 완료 재처리, DLT 발행 실패 중 원본 소비 위치 미완료와 발행 복구 뒤 커밋을 검증했다. License 완료는 계약 픽스처이고 Payment의 실제 접수·PG HTTP를 연결한 E2E는 아니다. 앞선 Order 소비자의 재시도·DLT 회귀도 별도 기본 설정으로 유지했다.
마지막 kafka-final 실행에서는 격리 발행 실패 주입을 해당 환불 이벤트 ID로 한정해 같은 4건을 다시 통과했다. 다른 소비자의 DLT 발행 실패를 목표 환불의 장애로 오인하지 않게 했다. 최종 XML 출처 선택은 이 4건과 앞서 통과한 나머지 통합 64건이다.
공유 컨테이너 18개의 ID·실행·시작 시각·재시작 횟수·health와 볼륨 55개가 동일했고 추가 자원·잔여 Testcontainers도 없었다. 초기·수정 소스/명령/로그/JUnit과 전후 자원 목록은 원격 results와 로컬 runs/refund-completion-oci-8wcbu9hl/collected/results에 보존했다. validation-summary.json과 assembled-junit은 원본 XML을 수정하지 않고 통과한 64건·4건의 실행 출처를 구분한다. 결과 집계 이후 변경은 검증 기록 문서뿐이다.

2026-10-27, Payment가 RefundExecutionRequested를 내부 RefundExecutionRequest로 변환·검증하고 로컬 PAID 결제와 대조해 접수하는 경로를 추가했다. 결제의 최초 결과·PG 거래/비용·원래 주문 사실은 보존한다. Order의 환불 허용 판정은 다시 계산하지 않는다. 실제 청구액 9,000원·정가 10,000원인 구매의 전액 기준도 실제 청구액임을 검증했다.
도메인 13건과 접수 서비스 15건의 미구현 Red 뒤 Green을 확인했다. 할인액 회귀 1건과 입력·리스너 20건을 보완해 로컬 Payment 단위 123건을 통과했다. 새 통합 소스도 컴파일했다. 원자료는 runs/payment-refund-reception-tdd-fbrwbk1q의 domain-red/green·service-red/green·local-full·integration-compile에 보존했다.
V14 refund_execution은 attemptId·요청 이벤트 ID와 PENDING 결제/환불에 유일 제약, 원래 purchase_payment에 외래 키를 둔다. Inbox·접수·별도 refund_execution_trace를 같은 트랜잭션으로 저장하고, 추적 정보는 시도 ID로 연결해 최초 결제 추적 부모와 분리한다. 같은 요청의 재전달은 최초 본문·접수 시각·추적 부모를 유지한다. 새 시도는 기존 실행이 미확정인 동안 거절한다.
접수 서비스는 PG 포트를 호출하지 않는다. PG 환불 실행·미확정 재개·결과 발행·확정 실패 후 새 시도 허용·권한 복구와 새 환불의 Tempo E2E는 후속이다. 잘못된 실행 입력은 기존 Payment 소비자의 제한 재시도·DLT 계약을 유지하며, 이를 환불 실패 확정이나 복구 요청으로 바꾸지 않는다.
ssh sng OCI의 /tmp/stove-payment-refund-reception-check-ke_8kzmp/work에서 920개 소스 파일을 SHA-256으로 대조한 뒤 Payment 단위 123건·통합 48건과 공통 Kafka 183건을 실패·오류·건너뜀 없이 통과했다. 공통 Kafka는 변경되지 않은 입력의 Gradle 캐시 결과를 사용했다. Payment 단위·통합은 OCI에서 실행했다.
신규 통합 14건은 실제 DB의 접수·중복·최초 결제와 추적 부모 보존, 추적 행 삽입 후 전체 롤백·재처리, 동시 접수, 미확정 실행 중 새 시도 차단, 변경된 금액·근거와 Inbox 불일치 거절, DB 식별자·PENDING 결제/환불 유일 제약·외래 키를 검증했다. 별도 모의 PG 프로세스를 실제로 중단한 동안에도 접수를 저장하고 PG 어댑터를 호출하지 않음을 확인한 뒤 공급자를 재기동했다. 모두 이 검증의 격리된 공급자 프로세스·DB를 사용했다.
실제 Kafka에서는 중복 요청의 소비 위치를 모두 커밋하면서 접수·Inbox는 각각 한 건, 추적 부모는 첫 값으로 유지됨을 확인했다. 잘못된 금액의 요청은 원문·키·소비 그룹을 DLT에 보존하고 접수·Inbox·결과 Outbox와 PG 호출을 만들지 않았다. 기존 결제 HTTP·JWT·비용·공급자 응답 유실·마이그레이션·추적/스케줄러 회귀도 함께 통과했다. 환불의 실제 PG HTTP·Order 결과 반영·License 복구 E2E와는 구분한다.
검증 전후 공유 컨테이너 18개의 ID·실행·시작 시각·재시작 횟수·health와 볼륨 55개가 같았고 추가 자원·잔여 Testcontainers도 없었다. 원격 results와 로컬 runs/payment-refund-reception-oci-prqa5s5l/collected/results에 소스 묶음·해시·명령·로그·JUnit·전후 자원 목록을 보존했다. validation-summary.json은 원자료·해시·집계를 대조하고 검증 후 문서 변경을 구분한다.

2026-10-27, 사용자가 실제 PG 테스트 연동을 먼저 진행하기로 선택하고 포트원 V2·토스페이 테스트 채널 정보를 준비했다. 실제 GET /payments의 테스트 필터 조회는 HTTP 200·반환 0건으로 인증을 확인했다. 업무 앱과 별개인 portone-test-checkout 검증 도구의 핵심 9건과 취소 미확정 표시 1건은 Red → Green을 확인했고, 최종 메모리 기반 Python 테스트 20건·Python 컴파일·JavaScript 문법 검사를 통과했다. 자동 브라우저의 로컬 Origin 접근은 승인 단계에서 거절돼 실제 결제창·PG 승인·취소는 검증하지 못했다. 이 결과를 Payment 환불 구현이나 OCI 인프라·전체 환불 완료로 합치지 않는다. 원자료와 실행 범위는 [PG 테스트 연동 계획](pg-test-integration-plan.md#2026-10-27-독립-테스트-결제-검증-도구)을 따른다.

이어 사용자의 명시적 curl·헤드리스 요청에 따라 실제 HTTP·Chromium 검증을 수행했다. 로컬 페이지/설정/서버 조회 200, 잘못된 요청의 403/400, SDK 로딩과 초기 버튼 상태, 토스페이 QR 인증 팝업을 확인했다. 서버 재조회는 READY·TEST·TOSSPAY_V2·9,000원이며 휴대폰 인증·PAID 승인·실제 취소는 아직이다. 앞선 자동 브라우저 거절 뒤 사용자 요청으로 진행한 별도 검증이며 OCI 인프라나 업무 환불 E2E 완료가 아니다. 원자료는 [curl·헤드리스 기록](pg-test-integration-plan.md#2026-10-27-사용자-요청에-따른-curl헤드리스-검증)을 따른다.

이후 사용자 결제·취소 보고 뒤 포트원 서버를 독립 재조회해 TEST·TOSSPAY_V2·상점/채널 일치·KRW·총액/결제액/취소액 9,000원·CANCELLED와 SUCCEEDED 취소 1건을 확인했다. 완료된 검증 도구의 취소 재요청도 기존 결과·취소 ID·시도 키를 유지했다. 실제 PG의 응답 유실이나 다른 키 경합을 주입한 것은 아니다. 원자료는 `runs/portone-checkout-live-20261027/provider-final-verification.json`이다. 독립 제공사 연결 검증만 완료했으며 Payment·Order·License·Settlement의 업무 연동·OCI 검증 완료로 합치지 않는다.

사용자가 웹훅·주기 조회 병행의 B안을 선택한 뒤 Payment에 서명 검증·내부 입력 변환·내구 접수·재조회 예약과 포트원 PAID 조회 어댑터를 추가했다. 서명 5·mapper 3·서비스 5·조회 어댑터 4건의 단위 테스트를 추가했고 미구현 Red → Green 원자료는 `runs/payment-portone-tdd-nuclayzz/`에 보존했다. 로컬 단위 140건과 통합 소스 컴파일이 통과했다. 승인한 OCI 격리 작업본에서 소스 946개를 SHA-256 대조해 단위 140·통합 57건(신규 DB·HTTP 9건)을 통과했다. 실패·오류·건너뜀 0이며 공유 컨테이너 18개·볼륨 55개와 기존 상태를 보존했다. 원자료는 `runs/payment-portone-oci-e4wppb57/collected/results/`다. 실제 제공사 웹훅·공개 주소·SDK 주문 연결이나 전체 환불 E2E로 확대하지 않는다. 상세 범위는 [연동 계획](pg-test-integration-plan.md#2026-10-27-b안-웹훅-접수주기-조회-기반)을 따른다.

이어 사용자가 OCI 공개 IP HTTPS 구성을 승인했다. 로컬 Payment bootJar, 공개 TLS 신뢰·IP SAN·TLS 1.3, HTTP 경계 7건, IP 인증서 갱신 dry-run·systemd 성공을 확인했다. 실제 SDK의 noticeUrls로 포트원 TEST Transaction.Ready를 수신해 제공사 SUCCEEDED·HTTP 200·DB 접수를 대조했고, 실제 웹훅 재발송도 200이며 최초 접수 1건을 보존했다. 새 PG 거래는 READY·로컬 Payment는 PENDING인 합성 주문 픽스처다. 실제 Order 주문·결제 승인·환불 전체 연결 검증으로 확대하지 않는다. 기존 공유 컨테이너 18개·볼륨 55개를 보존하고 전용 nginx·MySQL만 추가했다. 원자료는 `runs/portone-public-https-cm9y22_z/`, 구성·로컬 연결의 실행 의존성은 [edge 기록](../tools/verification/support/portone-edge/README.md)을 따른다. 이 인프라 설정 뒤 앞선 140·57건을 재실행했다고 기록하지 않는다.

Order가 검증할 결과는 자기 DB의 접수·판정·진행 상태, Inbox와 Outbox다.
License 정지 완료는 정의한 LicenseSuspended 계약의 픽스처로 입력하며 Payment 환불 결과 계약은 후속 정의한다. 제거된 구 환불 이벤트를 호환성 요구로 가져오지 않는다.
환불 기록은 Order 모듈의 별도 모델을 접수 시 생성하고 그 모델이 FSM을 소유하기로 했다. 첫 도메인 테스트의 대상은 Refund와 RefundStatus다. 이후 정지 요청 계약과 V12 접수 테이블을 추가했다. API 계약과 후속 전이는 아직 확정하지 않았다. 표의 상태 표현은 업무 의미다.

| 다른 모듈의 검증 대상 | Order에서 대신 검증할 내용 |
|---|---|
| Payment의 PG HTTP 호출·조회·동일 시도 재호출·실제 중복 환불 방지 | Payment 실행 요청의 내용과 발행 조건, 확인된 결과의 대조·반영 |
| License의 실제 보유권 정지·복구와 지급 이벤트 경합 | 정지·복구 요청의 내용과 정지 완료 입력을 받은 뒤의 진행 조건 |
| Download의 새 URL 발급 거절·기존 presigned URL 만료 | 해당 없음. Order 테스트에서 S3 URL을 발급하거나 무효화하지 않음 |
| Settlement의 환불 원장·PG 비용·마감 후 반영 | 금액 정책과 결과 계약을 결정한 후 별도 검증 |

### 7.2 첫 단계: 환불 접수

2026-10-25 OCI 접수 검증 뒤, Order가 환불 허용과 License 지급 확인을 모두 확보한 뒤 정지 요청을 발행하기로 확정했다.
아래 표는 이 새 선행 조건을 포함한 목표다. 2026-10-25 접수 OCI 기록은 조건 도입 전 구현의 결과이고, 2026-10-26 기록은 새 지급의 이벤트 확인·대기·재개를 포함한다. 기존 지급 건의 확인 보완 방식은 아직 결정하지 않았다.

| ID | 상황·행동 | 기대 결과 | 주 검증 층 |
|---|---|---|---|
| R01 | 존재하지 않는 주문으로 접수 | 거절. 접수 기록과 정지·환불 실행 Outbox 추가 없음 | 서비스·실제 DB |
| R02 | 다른 구매자가 보유한 주문으로 접수 | JWT sub와 주문 구매자를 대조해 거절. 업무 변경과 Outbox 추가 없음 | 서비스·실제 DB, HTTP·JWT |
| R03 | PENDING 또는 FAILED 주문으로 접수 | 거절. 결제 상태를 바꾸거나 환불 업무를 생성하지 않음 | 도메인·서비스 |
| R04 | 본인 PAID 주문을 결제 확정 후 4시간 이내 접수 | 최초 접수 시각과 자동 허용 판정을 저장. 지급 미확인이면 Order에서 대기하며 정지·Payment 실행 요청 없음. 지급 확인 근거도 확보했다면 정지 요청 Outbox를 같은 트랜잭션으로 저장 | 서비스·실제 DB |
| R05 | 본인 PAID 주문을 4시간 초과 후 접수 | 심사 대기 기록 저장. 정지·Payment 실행 요청은 발행하지 않음 | 서비스·실제 DB |
| R06 | 환불 접수 후 기존 주문·결제 근거 조회 | 주문 금액·통화·상품 스냅샷과 최초 결제 성공 근거는 유지. 전액 환불의 금액 근거는 원래 주문·결제와 일치 | 도메인·서비스 |
| R07 | 처리 중인 같은 주문을 4시간이 지난 뒤 중복 접수 | 같은 환불 업무와 최초 접수 시각·판정 유지. 자동 허용을 심사 대기로 바꾸거나 정지 요청을 중복 생성하지 않음 | 서비스·실제 DB |
| R08 | 지급 확인 근거가 있는 접수에서 정지 요청 Outbox 저장 실패 | 접수 기록·판정 변경도 함께 롤백. 접수 완료로 응답하지 않음. 지급 확인 대기 후 정지 요청 생성 시의 상태 전이·Outbox 원자성도 후속 검증 | 실제 DB 트랜잭션 |
| R09 | 같은 주문의 환불 요청이 동시에 도착 | 환불 업무를 중복 생성하지 않음. 지급 미확인이면 정지 요청 0건, 지급 확인도 확보했다면 최초 정지 요청 1건. 실제 DB의 잠금·제약 아래 최종 저장 결과와 반환 결과 확인 | 실제 DB 동시성 |

R04·R05에서는 Order 생성 시각·최종 수정 시각과 결제 확정 시각을 의도적으로 다르게 둔다.
재처리 시각을 앞뒤로 바꾸어도 최초 접수의 판정을 다시 계산하지 않는지는 R07에서 검증한다.
R06은 환불 실패를 기존 결제의 FAILED로 덮어쓰는 구현도 잡아야 한다.

2026-10-25, R09의 구현 방향은 주문 행의 비관적 쓰기 잠금과 Refund 주문번호의 DB 유일 제약을 함께 사용하는 것으로 결정했다.
실제 DB에서 두 접수 트랜잭션을 겹쳐 실행하고, 성공한 두 응답의 refundId가 같으며 해당 주문의 Refund가 하나인지 검증한다. 새 지급 확인 조건에서는 확인 근거 유무에 따라 정지 요청 Outbox의 0건·1건도 검증한다.
기존 환불이 없는 상태의 동시 접수를 대상으로 하며, Mockito의 잠금 메서드 호출 확인만으로 동시성 검증을 대신하지 않는다. 해당 테스트와 서비스 잠금 우회 시 유일 제약 검증은 RefundReceptionIntegrationTest로 작성해 2026-10-25 OCI의 실제 MySQL에서 통과했다.

2026-10-25, 정지 요청의 본문은 refundId·orderNo·buyerSubject·productId·productCode·paymentId를 전달하기로 했다.
R04의 Outbox 검증에서는 환불 식별자와 주문에 보존된 구매자·상품·결제 식별자가 모두 일치하는지 확인한다. 외부 요청자가 전달한 구매 근거를 그대로 발행하지 않는다.
License가 자신의 구매 기록과 대조하고 불일치한 정지를 막는 검증은 2026-10-26 License 테스트에 추가했다. Order 테스트로 License 업무 완료를 대신 주장하지 않는다.
정지 요청은 기존 Order 토픽(`stove.order.v1`)과 orderNo 키를 사용하기로 했다. R04에서는 Outbox의 토픽·키도 확인한다.
이 저장 검증은 Kafka 전달 순서나 License 반영 순서까지 증명하지 않는다. 발행 재시도 중 선행 이벤트 추월과 소비 경계는 별도 검증 대상으로 유지한다.

### 7.3 관리자 심사

| ID | 상황·행동 | 기대 결과 | 주 검증 층 |
|---|---|---|---|
| R10 | ADMIN 권한 없이 승인·반려 요청 | 거절. 판정 변경과 정지·환불 실행 Outbox 추가 없음 | HTTP·실제 JWT |
| R11 | 관리자가 심사 대기 요청 승인 | 승인 판정을 저장. 지급 확인도 확보했다면 정지 요청을 원자적으로 저장하고, 미확인이면 Order에서 대기. 정지 완료 전 Payment 실행 요청 없음 | 도메인·실제 DB |
| R12 | 관리자가 심사 대기 요청 반려 | 반려 결과 저장. 보유권 정지·Payment 실행 요청 없음. 원래 결제 성공 근거 유지 | 도메인·실제 DB |
| R13 | 같은 승인 요청을 중복 처리 | 기존 승인 결과 유지. 정지 요청을 추가로 만들지 않음 | 서비스·실제 DB |

2026-10-28 A안으로 먼저 커밋된 판정을 확정했다. 동일 명령·환불·판정자·판정·사유는 최초 영수증을 반환하고 다른 지시는 409다. R10~R13과 반대 판정 경합·다른 환불의 명령 재사용·지급 경합·Outbox 롤백은 실제 JWT/DB로 검증했다. 반려 후 재심사는 별도 미결이다. [검증 기록](pg-test-integration-plan.md#2026-10-28-관리자-최초-환불-판정-확정-a안).

### 7.4 License 정지 완료 수신과 Payment 실행 요청

| ID | 상황·행동 | 기대 결과 | 주 검증 층 |
|---|---|---|---|
| R14 | 정지 완료 입력의 봉투·본문·키 불일치 또는 필수 값 오류 | `api.listener` mapper와 내부 입력 생성 경계에서 거절. 업무 서비스에 잘못된 입력을 넘기지 않음 | mapper·내부 입력 단위 |
| R15 | 현재 허용된 환불과 일치하는 정지 완료 수신 | 정지 확인 상태·Inbox·Payment 실행 요청 Outbox를 같은 트랜잭션으로 저장. 실행 대상과 전액 금액 근거가 원래 주문·결제에 연결됨 | 서비스·실제 DB |
| R16 | 완료 입력의 업무 연결값이 기존 환불·주문과 불일치 | 정상 완료나 정상 중복으로 받아들이지 않음. 상태 변경·Inbox·Payment 실행 요청 추가 없음 | 서비스·실제 DB |
| R17 | 정지 완료를 받지 못한 채 시간이 경과 | 정지 확인 대기 유지. 시간 경과만으로 Payment 실행을 허용하지 않음 | 도메인·서비스 |
| R18 | 동일 정지 완료를 반복 또는 동시에 수신 | 동일 확인 결과 유지. Payment 실행 업무를 중복 생성하지 않음 | 서비스·실제 DB 동시성 |
| R19 | 정지 완료 처리 중 Payment 실행 Outbox 저장 실패 | 정지 확인 상태·Inbox도 함께 롤백. 같은 완료 입력으로 재처리 가능 | 실제 DB 트랜잭션 |

정지 완료는 LicenseSuspended 계약을 사용한다. 실행 요청은 RefundExecutionRequested로 eventId·occurredAt·refundId·attemptId·orderNo·paymentId·buyerSubject·amount·currency·licenseSuspensionEventId를 전달하며 Order 토픽·orderNo 키를 사용한다. 금액·통화는 원래 주문의 전액 근거에서 만든다. 이 요청 등록은 실제 PG 실행 완료와 구분한다.
서비스에는 외부 이벤트 전체 대신 mapper가 검증한 내부 입력을 전달한다.
승인되지 않았거나 원래 환불·정지 요청을 확인할 수 없는 완료는 업무 진행을 거절·격리하는 A안으로 결정했다. DB 접속 등 일시 장애의 재시도와 구분한다.

### 7.5 Payment 확정 결과 반영

| ID | 상황·행동 | 기대 결과 | 주 검증 층 |
|---|---|---|---|
| R20 | 현재 실행 시도와 일치하는 확정 성공 수신 | 환불 성공 근거 저장. 원래 주문·결제 스냅샷 유지. 보유권 복구 요청 없음 | 도메인·서비스·실제 DB |
| R21 | 현재 실행 시도와 일치하는 확정 실패 수신 | 실패 근거와 사용자에게 안내할 사유를 저장하고 보유권 복구 요청 Outbox를 원자적으로 저장 | 서비스·실제 DB |
| R22 | 확정 결과가 도착하지 않은 상태로 장기 대기 | 대기 상태 유지. 시간 초과·조회 불가만으로 환불 실패를 확정하거나 복구 요청을 발행하지 않음 | 도메인·서비스 |
| R23 | 환불 결과의 봉투·본문·키 또는 주문·결제·금액·통화 연결값 불일치 | 입력 경계 또는 기존 사실과의 비교에서 거절. 상태·Inbox·후속 Outbox 변경 없음 | mapper·내부 입력 단위, 서비스 |
| R24 | 같은 내용의 확정 결과 재전달 | 기존 결과 유지. 보유권 복구 등 후속 요청 중복 생성 없음 | 서비스·실제 DB |
| R25 | 확정 결과 반영 중 저장 실패 | 결과 상태·Inbox·해당 후속 Outbox가 함께 롤백. 결과 재전달로 복구 가능 | 실제 DB 트랜잭션 |
| R26 | 환불 진행·결과 기록 후 최초 결제 완료 이벤트가 다시 도착 | 기존 구매·결제 근거의 재전달로 처리. 환불 진행 상태를 되돌리거나 OrderPaid를 다시 만들지 않음 | 기존 결제 처리 회귀·실제 DB |

R21에서 Outbox 적재만으로 실제 보유권이 복구됐다고 단언하지 않는다. 복구 완료 확인과 사용자에게 표시할 중간 상태는 별도 계약이 필요하다.
R22의 Order 테스트는 Payment에서 PG 결과를 알 수 없는 상황을 **확정 결과가 아직 전달되지 않은 상태**로 검증한다.
PG 타임아웃·외부 조회·자동 재시도 자체는 Payment 테스트에서 검증한다.
확정 실패 뒤 사용자 재시도와 이전 시도의 지연 결과는 실행 시도 식별 규칙을 결정한 후 추가한다.

### 7.6 API·내구성·추적

| ID | 상황·행동 | 기대 결과 | 주 검증 층 |
|---|---|---|---|
| R27 | 미인증 접수·조회 또는 다른 구매자의 환불 조회 | 기존 JWT·본인 조회 계약에 따라 거절. 다른 구매자의 환불 내용 노출과 업무 변경 없음 | HTTP·실제 JWT |
| R28 | License가 응답하지 않거나 Kafka 전달이 지연된 동안 접수 | Order 저장 성공 후 접수 상태 응답. License·Payment의 실제 완료를 기다리거나 완료됐다고 응답하지 않음 | HTTP·실제 DB·전달 장애 |
| R29 | 접수·심사·정지 대기·환불 처리·결과를 본인이 조회 | Order의 저장 상태와 일치하는 단계·결과 조회. PG나 License를 동기 조회해야만 화면을 구성하는 경로에 의존하지 않음 | HTTP·서비스 |
| R30 | 접수 응답 후 정지 요청 전달 전에 Order 프로세스 재시작 | 최초 접수·판정 유지. 저장된 Outbox가 전달되고 완료 픽스처 수신 후 후속 진행. 환불 업무·실행 요청 중복 생성 없음 | 격리된 로컬 OrbStack 프로세스 검증 |
| R31 | 정지 완료를 저장한 뒤 Payment 실행 요청의 브로커 전달 실패 | Order의 실행 요청 Outbox가 보존되고 전달 재개 가능. 새 환불 업무를 만들지 않음 | 실제 DB·Kafka |
| R32 | 서로 다른 환불의 비동기 전달·재처리 | 해당 처리의 추적 컨텍스트와 Outbox 헤더가 연결되고 서로 다른 환불이 섞이지 않음. 별도 재개 작업은 필요한 부모 보존·복원 검증 | 실제 tracer 통합·필요 시 Tempo |
| R33 | Tempo 수집기 중단 또는 샘플링 0 | 수집 실패나 trace 부재를 환불 실패로 취급하지 않고 Order 업무 상태·Outbox 처리는 진행 | 실제 업무·추적 장애 검증 |

API 경로·HTTP 상태·응답 스키마와 관리자의 조회 범위는 계약 결정 후 고정한다.
사용자 안내에서 접수 완료·정지 완료·환불 완료·복구 요청·복구 완료를 같은 의미로 사용하지 않는다.
R30은 객체를 다시 생성하거나 DB 행을 다시 읽는 검사만으로 프로세스 재시작 검증을 대신하지 않는다.
Outbox·Inbox 공통 라이브러리의 기존 단위 테스트를 그대로 복제하지 않고, Order의 새 업무 변경과 함께 원자적으로 조립됐는지 확인한다.

### 7.7 작성 순서와 단언 기준

첫 Red는 **R04: 본인 PAID 주문의 기한 내 접수**로 시작했다.
서비스 단위 R04 → R05(심사 분기) → R07(최초 접수 보존)의 Red·Green을 확인했고 R01~R03의 거절과 R06의 결제 근거 보존을 보완했다.
실제 DB의 접수 검증 뒤 2026-10-26 지급 확인 대기 조건도 Red·Green과 OCI로 확인했다. 이어 License 정지·완료 발행과 Download 상태 검증, 실행 이력 모델, Order의 정지 완료 반영·실행 요청과 Payment의 내구성 있는 접수를 추가했다. 다음은 모의 PG의 환불 승인·조회 계약과 처리이며, 미결 계약은 7.8절에서 먼저 결정한다.
33개 시나리오를 한 번에 실패 상태로 만들지 않는다.

- 순수 판정·입력 검증·mapper는 `src/test`에서 검증한다. 기존 `RefundPolicyTest`와 `OrderArchitectureTest`는 회귀로 유지한다.
- 트랜잭션·잠금·동시성·Inbox·Outbox의 원자성은 `src/integrationTest`에서 실제 MySQL로 검증한다. Mockito 호출 횟수만으로 커밋·롤백을 증명하지 않는다.
- 실제 메시지 수신·전달·재전달은 Kafka 경계를 사용한다. 실제 License·Payment 업무 대신 해당 계약 픽스처를 사용할 수 있다.
- Outbox의 건수뿐 아니라 종류·업무 연결값·파티션 키·금액·통화와 계약에 포함한 본문 전체를 확인한다. 기존 주문 이벤트가 있으므로 전체 테이블이 0건이라고 단언하지 않는다.
- 거절·불일치·롤백에는 업무 상태·Inbox·관련 Outbox 모두의 무변경을 확인한다. 시간 경과는 고정 시각으로 재현하고 `Thread.sleep`으로 경계를 넘기지 않는다.
- 각 Red는 컴파일 누락이 아니라 기대 동작 미구현 때문에 실패하는지 확인하고, Green 후 기존 Order 테스트와 함께 검증한다. 신규 기능 TDD를 구 결함 재현용 `known-defect` 태그로 숨기지 않는다.
- 기본 격리 환경은 로컬 OrbStack이다. 현재 환불 작업의 인프라 검증은 사용자가 승인한 ssh sng OCI를 사용하며 실행별 포트·DB·컨테이너를 분리하고 공유 자료를 보존한다.

### 7.8 현재 남은 계약과 검증

2026-10-29 기준이다. PG 멱등 키/고정 본문·내구 재송신/조회·확정 실패와 실제 복구·최초 관리자 판정·고객 명시적 재시도 HTTP·운영 중단 조회/재개 관리자 HTTP·반려 뒤 재심사·잘못된 승인 중단/실제 복구 후 새 심사·고객 지연 안내/준비 이벤트 보관·장기 미확정 운영 조회/조사 기록은 구현·검증했다. 다음 세션의 구체적 순서는 [공통 지시·다음 작업](refund-next-session.md)을 따른다.

| 남은 항목 | 테스트를 쓰기 전 확인할 것 |
|---|---|
| 재심사의 실제 사용자 흐름 | 선택한 재심사 정책과 API·이력/감사·기한·정지/복구 연계는 검증됨. 실제 운영 화면·실제 ADMIN/PG의 전체 흐름은 별도 검증 |
| 잘못된 승인의 실제 사용자 흐름 | 중단 경계 A안·실제 복구 후 새 심사 B안의 API·각 모듈 DB/Kafka·경합/중복·실제 flush 롤백·정보 제한·V22/V9·명세는 검증됨. 최종 OCI 967건. 실제 ADMIN/운영 화면/두 실제 서비스의 전체 사용자 검증은 후속 |
| 운영 중단 재개의 실제 사용 흐름 | 관리자 조회·재개 HTTP/권한/감사는 검증됨. 실제 ADMIN 계정·운영 화면·실제 PG 장애 뒤 재개는 별도 검증 |
| 지급 확인 누락·장기 미확정 운영 | 고객 지연 안내 A안·준비 이벤트 보관 A안의 경계/중복/종료/새 시도·실제 flush 롤백·Kafka·V23/V26·명세를 검증함. 최종 OCI 1193건. 운영 확인 대상 조회/조사 기록 A안과 V27·실제 DB 경합/롤백·명세는 최종 OCI 615건으로 검증함. 과거 지급 자료 보완·조사 후 조치/종결·실제 운영 화면은 후속 |
| 시간/정책 변경·재구매·정산 | 비정상 시각의 추가 입력 계약, 정책의 과거 적용, 환불 후 같은 상품 재구매, 마감 후 환불 조정은 별도 결정 |
| 실제 제공사/추적 전체 검증 | 실제 ADMIN 수동 환불, 실제 PG 오류·응답 유실과 Tempo 전체 연결은 계약 픽스처나 정상 자동 환불 1건으로 대체하지 않음 |

각 쟁점은 사용자가 선택과 이유를 설명한 뒤 반례·비용을 검토한다. 미결 정책을 테스트 기대값으로 먼저 확정하지 않는다.

<a id="order-module-tests"></a>

## 8. Order 전체 기능·권한의 단위 테스트

2026-10-25. 환불뿐 아니라 완료된 Order의 기능·권한 계약을 빠른 테스트로 검증한다.
기준은 [Order 완료 범위](order-mvp-plan.md), [현재 권한 설정](../apps/order/src/main/java/com/stove/order/config/SecurityConfig.java), [본인용 중복 구매 정책](decisions.md#25-본인용-단일-구매의-출처는-license에-함께-보존한다)다.
기존 기능은 이미 구현돼 있어 새 테스트가 처음부터 Green일 수 있다. 테스트 누락을 구현 누락이나 실제 Red로 표시하지 않는다.
새로운 기대 동작이 합의됐거나 현재 계약 위반이 재현되는 경우 그 실패를 다음 구현의 Red로 삼는다.

### 8.1 Order가 소유하는 책임

| 책임 | 검증할 행위 | 경계 |
|---|---|---|
| 주문 생성 | JWT sub 구매자와 Catalog 공개 상품으로 주문 당시 상품·금액을 고정하고 PENDING 생성 | 청구액은 Order의 책임. PG 승인·비용 산출은 Payment·외부 PG의 책임 |
| 동일 요청의 멱등 처리 | 같은 구매자·requestId·본문은 현재 주문 반환. 같은 키의 변경된 본문은 충돌 | 재조회 중 Catalog를 다시 호출하거나 새 결제 요청을 만들지 않음 |
| 동일 상품 추가 구매 제한 | 같은 구매자의 PENDING·PAID 주문이 있으면 다른 요청의 추가 구매를 막음 | 실제 동시 구매 방어는 DB 제약·잠금을 포함한 통합 검증 필요 |
| 내 주문 조회 | 상세는 orderNo·구매자, 목록은 구매자로 범위를 제한. 페이지 범위와 최신순 정렬을 적용 | 다른 구매자 주문 조회와 관리자 전체 조회를 같은 기능으로 취급하지 않음 |
| 결제 결과 반영 | 자기 주문·요청·구매자·금액·통화를 대조해 최종 결과 적용. 첫 성공만 저장된 주문 근거로 OrderPaid 발행 | Payment가 승인·실패를 결정하며 Order는 PG를 직접 호출하지 않음 |
| 환불 접수·허용 | 접수 시 4시간 판정, 기한 초과 심사, 지급 확인 뒤 정지 요청과 정지 확인 뒤 환불 실행 요청, 확정 결과 반영 | 단계별 구현·검증은 7절을 따른다. 지급 확인 대기·재개, License 정지·완료 발행·Download 접근 검증, 실행 이력과 Order의 완료 수신·실행 요청, Payment의 내구성 있는 접수를 구현했다. 환불 API·관리자 판정·PG 실행·결과·복구·기존 지급 보완은 후속 |

기존 OrderRequest·OrderPrice·Order·PaymentResult 검증, Catalog HTTP 오류 매핑, 이벤트 mapper, 아키텍처 테스트도 회귀로 유지한다.
새 서비스 단위 테스트는 이 기능들이 유스케이스에서 올바른 입력·연결값으로 조립되는지 검증한다.

### 8.2 권한 계약

| 행위 | 현재 허용 조건 | 테스트가 확인할 내용 |
|---|---|---|
| 주문 생성 | 인증된 MEMBER | 본문이나 별도 회원 식별자가 아닌 JWT sub를 구매자로 전달 |
| 주문 목록 조회 | 인증된 MEMBER | JWT sub로 범위를 제한하고 요청의 buyerSubject를 권한 근거로 사용하지 않음 |
| 주문 상세 조회 | 인증된 MEMBER이면서 본인 주문 | 서비스에 orderNo와 JWT sub를 함께 전달. 타인 주문은 본인 조회 결과로 반환하지 않음 |
| CREATOR만 또는 ADMIN만 보유한 계정 | 현재 일반 주문 생성·조회 권한 없음 | MEMBER 권한이 없는 경우 업무 서비스 실행 전 403 |
| MEMBER와 ADMIN을 함께 보유한 계정 | 일반 MEMBER 기능 가능 | 추가 ADMIN 역할이 본인 조회 범위를 전역으로 넓히지 않음 |
| 미인증·인증 실패 | 업무 API 접근 불가 | 401과 업무 서비스 미호출 |
| 구 취소 경로·운영 경로 | 현재 Order 업무 API로 허용하지 않음 | MEMBER의 호출이 업무 실행으로 이어지지 않음 |
| 환불 관리자 승인·반려 | 합의한 신규 ADMIN 기능, 아직 미구현 | 7절 R10~R13. 일반 주문 API의 현재 ADMIN 정책과 별도 계약으로 추가 |

ADMIN의 전체 주문 목록·타인 상세 조회, 미결제 취소·자동 만료·결제 재시도는 현재 완료 계약에 포함되지 않는다.
이 기능이 필요하다면 요구사항·권한을 선택한 뒤 새 Red를 작성한다. 구 API가 남아 있었던 사실만으로 다시 허용하지 않는다.

### 8.3 추가한 빠른 테스트

| 테스트 | 실행 건수 | 주요 행위 |
|---|---:|---|
| [CreateOrderTest](../apps/order/src/test/java/com/stove/order/core/service/CreateOrderTest.java) | 16 | 구매자·입력 거절, PENDING/PAID/FAILED 동일 요청 재조회, 변경된 요청 충돌, Catalog 스냅샷·OrderPlaced 내용, 상품 추가 구매 제한, Catalog 실패 중 기존 접수 복구 |
| [ReadOrderTest](../apps/order/src/test/java/com/stove/order/core/service/ReadOrderTest.java) | 3 | 본인 조건 조회, 타인 주문에 무제한 조회로 우회하지 않음, 없는 주문 처리 |
| [SearchOrdersTest](../apps/order/src/test/java/com/stove/order/core/service/SearchOrdersTest.java) | 7 | 구매자 필터·페이지 범위·최신순 정렬 조건 전달, 잘못된 페이지의 저장소 미호출 |
| [ApplyPaymentResultTest](../apps/order/src/test/java/com/stove/order/core/service/ApplyPaymentResultTest.java) | 11 | 성공 시 주문 근거 발행, 실패 시 OrderPaid 미발행, 동일 결과 멱등 처리, 없는 주문·이벤트 ID 재사용·Inbox 불일치·잘못된 입력 거절 |
| [OrderAccessTest](../apps/order/src/test/java/com/stove/order/api/controller/OrderAccessTest.java) | 22 | 일반 API 역할 행렬, 인증 거절, JWT sub 전달, 위조한 구매자·금액·상태의 무시, 페이지 기본값·요청값, 내부 식별자 비노출 |

앞의 37건은 DB·Spring 컨텍스트 없이 Mockito로 실행하는 서비스 단위 테스트다.
권한 22건은 `src/test`의 `@WebMvcTest`로 실제 Order SecurityFilterChain과 주체 인자 주입을 실행하는 웹 슬라이스 테스트다.
웹 슬라이스는 JwtDecoder와 업무 서비스를 대역으로 두므로 서명·issuer·audience·만료의 실제 검증이나 SQL 소유자 격리를 증명하지 않는다.
이 검증은 기존 실제 JWT·DB 통합 테스트에서 유지한다. Mockito 트랜잭션 매니저를 사용한 서비스 테스트도 실제 커밋·롤백 검증을 대신하지 않는다.

검증 명령은 `./gradlew :apps:order:test --console=plain`이다.
2026-10-25 실행에서 신규 59건을 포함한 전체 136건이 실패·오류·건너뜀 0건으로 통과했다. 운영 코드는 변경하지 않았다.

### 8.4 다음 Red의 범위

- 신규로 합의한 환불 접수·심사·정지 완료 확인·확정 결과는 7절의 시나리오를 순차적으로 테스트한다.
- 이미 Green인 주문 기능·권한 테스트는 회귀로 유지한다. 계약 위반이 발견되면 그 입력·기대 결과·실제 실패를 남긴다.
- 실제 DB의 소유자 필터·중복 키·구매 경합·Inbox/Outbox 롤백은 기존 통합 테스트를 유지·확장한다. 이를 순수 단위 테스트의 성공으로 대체하지 않는다.
- 새로운 주문 기능이나 권한 확대는 담당자의 선택·이유와 반례·비용 검토 후 기대 결과를 고정한다.

2026-10-27 후속으로 사용자가 본인 결제창 정보 전용 API A안을 선택했다. 서비스 미구현 Red 5건, SDK 응답·HTTPS 설정 검증의 Red 2건을 확인한 뒤 로컬 Payment 단위 148건·통합 컴파일·bootJar가 통과했다. OCI HTTP Red 3건은 신규 경로가 연결되기 전의 403이었다. 구현 후 실제 JWT/JWKS·Spring HTTP·MySQL로 본인 PENDING·고정 ID·할인 후 금액·공개 설정·서버 Secret 부재·캐시 금지·반복 읽기 불변·401/403/404/409·웹훅 후 PAID 및 새 checkout 거절을 검증했다. 최종 OCI 단위 148·통합 60건(신규 3건), 실패·오류·건너뜀 0이다. 소스 959개를 대조했고 공유 컨테이너 20개·볼륨 55개를 보존했다. 단위 원자료는 `runs/payment-checkout-tdd-p2__qvdo/`, OCI Red/Green·JUnit·소스·보존 기록은 `runs/payment-checkout-oci-zoy5astj/collected/results/`다. 실제 포트원 신규 승인이나 Order부터 시작한 E2E로 확대하지 않는다. API 계약·응답 이후 경합·접수 전 404의 범위는 [A안 기록](pg-test-integration-plan.md#2026-10-27-a안-본인-결제창-정보-전용-api)을 따른다.

2026-10-27 후속 실제 연결 검증에서는 업무 코드 변경 없이 실제 Auth 회원가입/PKCE·Catalog의 출시 요청 계약 픽스처 처리·Order 생성 HTTP·Outbox/Kafka·Payment 생성·checkout 응답·SDK·실제 Transaction.Ready 웹훅 200까지 확인했다. 주문과 결제는 같은 9,000 KRW·PENDING이며 사용자 휴대폰 승인 대기다. 구매 성공으로 집계하지 않는다. 최신 원자료는 `runs/portone-order-live-j2flh033/`다. 소스 959개·JAR 해시와 공개 응답 연결값·DB 접수·공유 컨테이너 20개/볼륨 55개 보존, 전용 컨테이너 2개/소유 볼륨 2개 추가를 기록했다. 기존 단위·통합 테스트 숫자를 이 실제 승인 대기 단계의 재실행 결과로 합치지 않는다.

2026-10-27 후속 사용자 휴대폰 승인 보고 뒤 공식 토스 테스트 페이지의 마지막 이동하기 버튼을 처리하고 실제 포트원 PAID·Payment PAID·Order PAID·9,000 KRW를 확인했다. 상점·채널·TEST/TOSSPAY_V2·주문·구매자·상품·금액·요청 ID·결과 ID가 일치하고 Ready/Paid 웹훅은 200이다. 실제 Paid 웹훅 재발송에도 최초 확정 결과·시각·웹훅 2개·OrderPlaced/OrderPaymentCompleted/OrderPaid의 SENT 각 1개를 보존했다. PAID 후 checkout은 409다. 최신 실행의 provider-paid-verification.json·confirmed-facts-before/after-replay.json·paid-webhook-replay.json·checkout-after-paid.json·validation-summary.json에 원자료를 보존했다. 공유 컨테이너 20개·기존 볼륨 55개 보존도 재확인했다. 완료 범위는 실제 TEST 구매의 Order·Payment 연결이며 Studio·Review의 실제 출시 업무·License 지급·정산·환불 전체 검증으로 합치지 않는다.

2026-10-27 사용자가 장기 미확정 환불 재전송 A안을 선택했다. 최초 송신 준비의 커밋 후 권한 반환, 동일 attemptId·원입력·3시간 기한 보존, 정확한 경계·오래된 권한의 송신 직전 재검사, 중단 관측의 내구 저장과 PENDING 유지의 준비 서비스를 구현했다. 도메인 6·서비스 4건의 미구현 Red를 확인한 뒤 로컬 단위 158건·통합 컴파일·bootJar가 통과했다. OCI full 실행 단위 158건은 통과했고 통합 65건 중 신규 픽스처 2건이 실패했다. 운영 코드를 바꾸지 않고 실제 EntityManager.flush 뒤 장애 주입, MySQL CHECK 이름·오류 번호 3819 검사로 픽스처 한 파일을 고쳤다. corrected 실행은 통합 전체 65건을 실패·오류·건너뜀 0으로 통과했으며 단위는 재실행한 것으로 합치지 않는다. 신규 DB 5건은 재조회·재시작의 기한 보존, 동시 준비, 실제 flush 후 롤백·재개, SQL 제약, V15 접수의 V16 이행을 검증한다. 공유 컨테이너 22개·볼륨 57개를 보존했고 잔여 Testcontainers는 없다. 단위 원자료는 runs/refund-window-tdd-u8jdb08z, OCI 소스 966개·두 실행·JUnit·보존 기록은 runs/refund-window-oci-22cus_i6/collected/results다. PG 취소·조회 작업자와 최종 환불 결과·운영 UI는 후속이며 이 준비 경계 검증을 실제 PG 환불 성공으로 표시하지 않는다.


2026-10-27 조회 확인 환불 완료 A안: Payment의 읽기 PG 검증·근거/Outbox와 Order의 완료/Inbox를 연결했다. 실행의 PENDING·결과 불명과 별도 조사 표시를 유지한다. Payment 미구현 Red 9건·Order Red 3건을 실제 확인했으며 초기 공급자 픽스처 오류와 corrected Red를 구분해 runs/refund-confirmation-tdd-7pavkmo2에 보존했다. 첫 OCI에서는 실제 Hibernate 컬렉션의 equals로 인한 동시 확인 거절을 재현해 불변 목록의 내용 비교로 수정했다. Outbox·Inbox MANDATORY spy의 트랜잭션 밖 장애 설정으로 생긴 두 픽스처 실패도 별도로 수정했다. 최종 corrected 실행은 --rerun-tasks로 공통 Kafka 단위 187·Payment 단위 172·Order 단위 247·Payment 통합 69·Order 통합 75건을 모두 실패·오류·건너뜀 0으로 통과했다. 단위 합계 606·통합 합계 144건이다. 신규 통합은 Payment 4건과 Order 7건이며, 실제 DB의 근거/Outbox·완료/Inbox 롤백·동시 처리, Kafka 중복/offset·일시 실패 재시도·격리·DLT 발행 실패 시 미커밋을 검증한다. OCI 소스 986개를 대조했고 공유 컨테이너 22개·볼륨 57개·ID/시작/재시작/건강 상태를 보존했다. 추가·잔여 Testcontainers와 볼륨은 없다. 원자료 full·corrected 명령/소스/로그/JUnit·보존 기록은 runs/refund-confirmation-oci-5naahx04/collected/results, 원격 격리는 /tmp/stove-refund-confirmation-check-E5y3zflx/work이다. 현재 운영·테스트 소스는 corrected 해시와 같으며 검증 이후에는 문서만 갱신했다. 로컬 bootJar와 통합 소스 컴파일도 통과했다. 실제 제공사 취소 송신·자동 조회 스케줄·전체 License/Settlement 환불 E2E의 완료 기록은 아니다.


2026-10-27 원결제 전액 잔액 조건 A안: 사용자가 자동화는 명확하고 간단한 경우에만 적용한다는 이유로 선택했다. 원액/예상 잔액 고정·최초 송신 본문 커밋·같은 키/원문 재사용·부분 취소 관측의 내구 중단을 구현했다. 단위 미구현 Red는 어댑터 8·도메인 3·서비스 4건이며 runs/refund-balance-tdd-t3jgf86j/red.log·service-red.log에 보존했다. 최종 OCI full 실행에서 Payment 단위 187건·통합 77건이 실패·오류·건너뜀 0으로 통과했다. 신규 통합 8건은 실제 DB 커밋의 외부 관측·HTTP 본문/키·선행 부분 취소·조회/POST 사이 잔액 변화·처리 뒤 결과 미확정과 조회 복구·배포/재시작의 최초 본문 보존·3시간 기한·실제 flush 후 롤백·동시 처리/Outbox 1건을 검증했다. HTTP 공급자는 격리 계약 픽스처이며 실제 포트원의 경합 원자성이나 실제 자금 이동을 검증한 것으로 표시하지 않는다. 소스 996개를 SHA-256 대조했다. 공유 컨테이너 22개·볼륨 57개와 상태를 보존했고 추가·잔여 Testcontainers/볼륨이 없다. 원자료는 runs/refund-balance-oci-gzscdzop/collected/results/full 및 preservation.json이다. 로컬 단위·통합 컴파일·bootJar도 통과했다. 이번 실행은 Payment만 재검증했으며 이전 단계의 Order 247·공통 Kafka 187 단위와 Order 75 통합을 다시 실행한 것으로 합치지 않는다.


2026-10-27 검증된 취소 완료 응답 A안: Payment 도메인 4·정상 응답 어댑터 1·Order 도메인 4건의 미구현 Red를 실제 확인했다. 정상 SUCCEEDED 응답은 후속 조회를 완료 조건으로 삼지 않고 금융 완료/시도 성공/Outbox를 원자 저장하며, 조회 선행이면 최초 금융 사실을 보존한 별도 실행 성공 이벤트로 보완한다. 불완전 응답·REQUESTED/FAILED의 기존 미확정 경로는 유지한다. Red·로컬 원자료는 runs/refund-success-tdd-9nvmjlg2다. OCI full 실행에서 공통 Kafka 단위 191·Payment 단위 195·Order 단위 254건(단위 합계 640), Payment 통합 81·Order 통합 79건(통합 합계 160)이 모두 실패·오류·건너뜀 0으로 통과했다. 신규 통합 8건은 성공 뒤 GET 장애와 무관한 확정, 실제 HTTP 응답을 지연한 조회 선행 경합, 성공/근거/Outbox 롤백과 동일 근거 재개, 동일 응답 동시 저장, Order 직접 성공/Inbox 롤백, 실제 Kafka의 정상 직접 완료 및 늦은 성공/금융 이벤트 양방향 순서·중복을 검증했다. 공급자는 HTTP 계약 픽스처이며 실제 PG 자금 이동 검증으로 확대하지 않는다. 소스 1005개를 SHA-256 대조했고 공유 컨테이너 22개·볼륨 57개의 ID/시작/재시작/건강 상태를 보존했다. 추가·잔여 Testcontainers/볼륨이 없다. 원자료 명령/소스/로그/JUnit·보존 기록은 runs/refund-success-oci-6tndqndq/collected/results/full 및 preservation.json이다. 검증 이후에는 문서만 갱신했으며 로컬 단위·통합 컴파일·두 bootJar도 통과했다.


2026-10-27 환불 결과 조회 간격 B안: 최초 준비 후 5·15·35초/이후 1분, DB 일정/임대·웹훅 조기 조회와 동일 알림 재전달 억제·조회 중 잠금 해제·장기 중단 후 단계 건너뛰기를 구현했다. 도메인 9개 중 미구현 Red 8건을 확인했고 로컬 Payment 단위 206건·통합 컴파일·bootJar를 통과했다. 최초 OCI full은 테스트 정리의 잘못된 trace 테이블명으로 통합 24건이 실패했다. 수정 후 --rerun-tasks 전체 corrected 실행에서 Payment 단위 206·통합 93건이 모두 실패·오류·건너뜀 0으로 통과했다. 신규 통합 12건은 실제 DB·HTTP·서명 웹훅의 주기/동시 선점/재시작/임대/중복/롤백/제약/V20 마이그레이션을 다룬다. 상세는 [B안 기록](pg-test-integration-plan.md#2026-10-27-requested-후-결과-조회-간격-b안)을 따른다. 소스 1,014개 SHA-256 대조·비밀값 포함 0건·공유 컨테이너 22개/볼륨 57개 보존·추가/잔여 자원 없음. runs/refund-lookup-tdd-1yp2c1o0 및 runs/refund-lookup-oci-lg92j8wa/collected/results/{full,corrected}에 최초 실패/수정본의 소스/명령/로그/JUnit·보존 기록을 남겼다. 실제 PG 환불이나 전체 환불 E2E의 완료 기록이 아니며 Order·공통 Kafka의 과거 수치를 재실행 결과에 합치지 않는다.


2026-10-27 PG 실패 뒤 미취소 확인·권한 복구 B안: 미구현 Red는 Payment 3·Order 2·License 2건이다. 최초 로컬의 카탈로그/변경 전 기대값/송신 제네릭 오류를 수정했고 최종 로컬 단위 공통 Kafka 203·Payment 213·Order 263·License 99건 및 세 모듈 통합 컴파일/bootJar가 통과했다. OCI full에서 7개 태스크 전체(공통 Kafka 203·Payment 213/통합 97·Order 261/통합 83·License 99/통합 53)를 통과했다. Order 발행 모듈 토픽 검증 보강 후 updated에서 Order 전체 단위 263/통합 83, Payment 관리자 전액 취소 경합 픽스처 추가 후 payment-final에서 통합 전체 98건을 각각 --rerun-tasks로 재검증했다. 모두 실패·오류·건너뜀 0이며 단일 전체 실행 수치로 합치지 않는다. 신규 통합은 Payment 5·Order 4·License 5건으로 실제 DB 원자성/롤백/재개/동시 복구·PG 조회의 보류/완료·옛 정지/복구 메시지·실제 Kafka의 중복/단계 반영을 검증했다. HTTP는 계약 픽스처이며 실제 PG 환불이나 전체 실거래 환불 E2E는 아니다. 소스 1,043개 대조·비밀값 포함 0건·공유 컨테이너 22개/볼륨 57개와 상태 보존·추가/잔여 자원 없음. runs/refund-restoration-tdd-ur27gau4 및 runs/refund-restoration-oci-fg84qzci/collected/results/{full,updated,payment-final}에 원자료를 보존했다. [계약·실행별 출처](pg-test-integration-plan.md#2026-10-27-실패-후-미취소-조회와-권한-복구-b안)를 따른다.


2026-10-27 실제 복구 후 명시적 재시도 A안: Order 미구현 Red 3건과 수정한 Payment 기준 구현의 업무 Red 1건을 확인했다. 최초 Payment Red의 모의 저장소 설정 오류는 업무 Red에서 제외하고 corrected baseline의 BusinessException을 보존했다. 최종 로컬/OCI 단위 공통 Kafka 205·Payment 218·Order 268, OCI Payment 통합 102·Order 통합 87건은 같은 full 입력에서 실패·오류·건너뜀 0으로 통과했다. 새 통합 8건과 확장한 Kafka 사례는 최초 자격/이력·동시/중복 접수·접수/Outbox 실제 flush 롤백·새 정지 완료·현재 시도/선행당 단일 후행·연속 실패 뒤 새 키/창·다른 진행 취소 미송신·현재 시도 웹훅·옛 메시지 대조를 검증한다. 통합 컴파일·두 bootJar 통과. 소스 1,050개 SHA-256 대조·비밀값 포함 0·공유 컨테이너 22개/볼륨 57개와 상태 보존·추가/잔여 자원 없음. runs/refund-retry-tdd-5qwhady7 및 runs/refund-retry-oci-q_c7wgvb/collected/results/full에 원자료를 남겼다. License는 변경/재실행하지 않았고 HTTP/License 완료는 계약 픽스처이므로 실제 PG 환불/전체 E2E로 확대하지 않는다. [상세·활성화 기록](pg-test-integration-plan.md#2026-10-27-복구-뒤-명시적-재시도-a안).


2026-10-27 이벤트 소비자 첫 취소 송신 A안: 최초 업무 Red 4건과 실제 Kafka 컨테이너 설정 누락 Red 1건을 확인했다. OCI full의 테스트 설정/기대값 2건과 corrected의 Kafka 설정 누락 1건을 보완한 final에서 Payment 단위 229·통합 109건이 모두 실패/오류/건너뜀 0으로 통과했다. 새 통합 7건은 커밋 후 송신·중복/동시/재전달·응답 유실과 GET 회복·준비 후 미송신 가능 구간·PG 성공 뒤 DB/Outbox 롤백·느린 PG의 Kafka 대기를 검증한다. 로컬 단위·통합 컴파일·bootJar 통과. 소스 1,057개 대조, 공유 컨테이너 22개/볼륨 57개 및 상태 보존, 추가/잔여 자원 없음. runs/refund-event-tdd-rb272_9q 및 runs/refund-event-oci-edsrov3x/collected/results/{full,corrected,final}에 원자료를 보존했다. [계약·실행별 기록·B안 고도화 계획](pg-test-integration-plan.md#2026-10-27-이벤트-소비자의-첫-취소-송신-a안)을 따른다. 실제 PG 취소/전체 환불 E2E 및 다른 모듈 재검증으로 확대하지 않는다.


2026-10-27 명확한 조건의 동일 시도 자동 재송신 A안+간격 B안: 일정/작업 배정의 Red 11건을 확인했다. 로컬 최종 Payment 단위 249건·통합 컴파일·bootJar가 통과했다. OCI full의 신규 복구 픽스처 모드 오류 2건을 수정하고 corrected 전체를 --rerun-tasks로 재실행해 Payment 단위 249·통합 121건 모두 실패/오류/건너뜀 0으로 통과했다. 운영 코드는 최초 OCI 이후 변경하지 않았다. 새 통합 12건은 종료 후 10/30/60초·같은 키/본문/준비·기존 준비 관측/중단 후 재개·금지 조건·임대/동시/늦은 결과/포화·3시간 경계·실제 Kafka/자동 타이머를 검증한다. 소스 1,066개 대조, 실제 비밀값 포함 0·컨테이너 22개/볼륨 57개와 상태 보존·추가/잔여 자원 없음. runs/refund-resend-tdd-ui0rl4k9 및 runs/refund-resend-oci-hpe9gmgp/collected/results/{full,corrected}에 실행별 근거가 있다. [상세 계약/검증](pg-test-integration-plan.md#2026-10-27-동일-시도-재송신-간격-b안)을 따른다. HTTP는 계약 픽스처이며 실제 PG 환불 E2E 및 다른 모듈 재검증이 아니다.


2026-10-27 401 인증 오류 뒤 자동 재개 B안: 기존 미확정 재시도 동작을 유지하고, 사전 GET/POST/결과 GET의 인증 오류 식별 Red 3건 뒤 PgAuthenticationUnavailable 분류와 안전한 로그를 추가했다. 로컬/OCI Payment 단위 253건·OCI 통합 126건은 모두 실패/오류/건너뜀 0이다. 새 통합 5건은 잘못된 키→올바른 키의 클라이언트 조립/같은 접수 재개, POST 401의 같은 본문/키, 조회 401 뒤 이미 완료된 환불의 미중복 처리, 3시간 경계, 부분 취소 금지 조건을 검증한다. 로컬 통합 컴파일·bootJar 통과. 소스 1,067개 대조·실제 비밀값 포함 0·컨테이너 22개/볼륨 57개 및 상태 보존·추가/잔여 자원 없음. runs/refund-auth-tdd-ep4o24t7 및 runs/refund-auth-oci-sf6_xxur/collected/results/full에 근거가 있다. [상세](pg-test-integration-plan.md#2026-10-27-인증-오류-401-뒤-자동-재개-b안)를 따른다. V23 유지, 실제 API 키 변경/실제 PG 환불 E2E 또는 다른 모듈 재실행의 완료가 아니다.


2026-10-28 취소 POST 403 자동 중단 A안: 어댑터 1·도메인 7건의 Red를 확인했다. 로컬 Payment 단위 264건·통합 컴파일·bootJar가 통과했다. OCI full의 롤백 저장소 spy 추상 실제 메서드 호출 오류 1건을 실제 EntityManager 삽입/flush 후 실패로 고쳤고, corrected 전체 --rerun-tasks에서 Payment 단위 264·통합 137건이 모두 실패/오류/건너뜀 0이다. 운영 코드는 최초 OCI 이후 바꾸지 않았다. 새 통합 11건은 중단/GET·원자 저장과 롤백·운영 재개/중복/새 중단·동시/늦은 결과·원래 3시간/금융 완료/부분 취소를 검증한다. 소스 1,074개 대조·실제 비밀값 포함 0·컨테이너 22개/볼륨 57개 및 상태 보존·추가/잔여 자원 없음. runs/refund-forbidden-tdd-4f_lh_a6 및 runs/refund-forbidden-oci-t90dzc63/collected/results/{full,corrected}에 원자료를 보존했다. [상세](pg-test-integration-plan.md#2026-10-28-취소-post-403-자동-중단-a안)를 따른다. 관리자 HTTP/권한 인증·실제 PG 환불 E2E·다른 모듈 재검증의 완료가 아니다.


2026-10-28 조회 GET 404 자동 재개 B안: 사전/결과 GET 404 식별의 Red 2건 뒤 PgPaymentNotFound 분류를 추가하고 기존 미확정 동작을 유지했다. 로컬/OCI Payment 단위 268건·OCI 통합 143건 모두 실패/오류/건너뜀 0이다. 새 통합 6건은 정상 구매 확인 뒤 같은 요청 재개·조회 회복 후 이미 완료된 환불의 미중복 처리·3시간 경계·다른 구매/부분 취소·기존 403 중단 유지다. 로컬 통합 컴파일·bootJar 통과. 소스 1,075개 대조·실제 비밀값 포함 0·컨테이너 22개/볼륨 57개 및 상태 보존·추가/잔여 자원 없음. runs/refund-lookup404-tdd-q205utzl 및 runs/refund-lookup404-oci-1cis4_cs/collected/results/full에 근거가 있다. [상세](pg-test-integration-plan.md#2026-10-28-조회-get-404-뒤-자동-재개-b안)를 따른다. V24 유지, POST 404/다른 오류 정책·다른 모듈 재실행·실제 PG 환불 E2E 완료는 아니다.


2026-10-28 취소 POST 404 자동 재개 B안: 전용 분류의 Red 1건 뒤 기존 미확정 동작을 유지하고 PgCancellationNotFound를 추가했다. 로컬/OCI Payment 단위 269건·OCI 통합 149건 모두 실패/오류/건너뜀 0이다. 새 통합 6건은 반복 404의 10/30/60초·같은 본문/키/첫 준비 유지 후 재개, 전액 완료의 미중복 처리·부분/다른 구매·3시간/GET·후속 403 중단을 검증한다. 로컬 통합 컴파일·bootJar 통과. 소스 1,076개 대조·실제 비밀값 포함 0·컨테이너 22개/볼륨 57개 및 상태 보존·추가/잔여 자원 없음. runs/refund-post404-tdd-0mfmnlca 및 runs/refund-post404-oci-_0gml1ha/collected/results/full에 근거가 있다. [상세](pg-test-integration-plan.md#2026-10-28-취소-post-404-뒤-자동-재개-b안)를 따른다. V24 유지, HTTP는 계약 픽스처이며 실제 PG 환불 E2E·다른 모듈 재실행의 완료가 아니다. 다음은 조회 GET 403 등 나머지 HTTP 오류/장기 대기 운영 기준을 한 쟁점씩 논의한다.


2026-10-28 조회 GET 403 자동 재개 B안: 사전/결과 GET의 식별 Red 2건 뒤 PgPaymentLookupForbidden을 추가하고 기존 미확정 동작을 유지했다. 로컬/OCI Payment 단위 271건·OCI 통합 156건 모두 실패/오류/건너뜀 0이다. 새 통합 7건은 사전 거절 뒤 같은 요청 재개·결과 조회 일정/미중복 완료·FAILED의 추가 증거 대기 후 1회 실패 확인·3시간/GET·부분/다른 구매·기존 POST 403 중단 유지를 검증한다. 로컬 통합 컴파일·bootJar 통과. 소스 1,077개 대조·실제 비밀값 포함 0·컨테이너 22개/볼륨 57개 및 상태 보존·추가/잔여 자원 없음. runs/refund-get403-tdd-a6d6edc_ 및 runs/refund-get403-oci-u43efztz/collected/results/full에 근거가 있다. [상세](pg-test-integration-plan.md#2026-10-28-조회-get-403-뒤-자동-재개-b안)를 따른다. V24 유지, HTTP/후속 경계는 계약 픽스처이며 실제 PG 환불·License 복구 E2E·다른 모듈 재실행의 완료가 아니다. 다음은 나머지 HTTP 오류/장기 대기 운영 기준을 한 쟁점씩 논의한다.


2026-10-28 취소 POST 400 INVALID_REQUEST 자동 중단 A안: 어댑터/중단 커밋 Red 2건 뒤 구현했다. 로컬 Payment 단위 276건·통합 컴파일·bootJar가 통과했다. OCI full의 생성 열 직접 복사 픽스처 1건을 수정하고 corrected 전체 --rerun-tasks에서 Payment 단위 276·통합 166건 모두 실패/오류/건너뜀 0으로 통과했다. 운영 코드/V25는 최초 OCI 이후 변경하지 않았다. 새 통합 10건은 중단/GET·명시적 재개/새 중단과 과거 지시·기한/완료/부분·조회 장애·실제 flush 롤백·늦은 소유자·미지 400·V24 이력/활성 중단의 V25 보존을 검증한다. 소스 1,078개 대조·실제 비밀값 포함 0·컨테이너 22개/볼륨 57개와 상태 보존·추가/잔여 자원 없음. runs/refund-invalid400-tdd-60edk1d3 및 runs/refund-invalid400-oci-3bpzduhu/collected/results/{full,corrected}에 원자료를 보존했다. [상세](pg-test-integration-plan.md#2026-10-28-취소-post-400-invalid_request-자동-중단-a안)를 따른다. 본문 정정·관리자 HTTP/인증·다른 모듈 재검증·실제 PG 환불 E2E의 완료가 아니다. 다음은 나머지 HTTP 오류/장기 대기 운영 기준을 한 쟁점씩 논의한다.


2026-10-28 고객 환불 요청/조회 API: Red 4건 뒤 구현하고 로컬 Order 단위 282/통합 컴파일/bootJar를 통과했다. OCI full의 명세/테스트 설정 4건을 수정하고 기존 명세 불변을 대조한 corrected 전체 --rerun-tasks에서 Order 단위 282·통합 95건 모두 실패/오류/건너뜀 0이다. 새 통합 HTTP 8건이 권한/소유권·전액/상태·중복/동시/거절/심사 대기와 실제 Outbox 롤백을 검증한다. 소스 1,085개·비밀값 포함 0·기존 컨테이너 22개/볼륨 57개 및 상태 보존. runs/refund-customer-api-oci-yd50q5ee/collected/results/{full,schema-update,corrected}에 출처가 있으며 schema-update의 단위 XML은 재실행 수치가 아니다. Order만 runtime 갱신(V19)했고 원래 9,000원 PAID/본인 API 응답·다른 앱 PID·자격 증명을 보존했다. 실제 새 결제/환불 전체 검증은 별도 승인 대기 단계다. [계약](pg-test-integration-plan.md#2026-10-28-고객-환불-api와-새-test-결제-전체-검증-a안).

2026-10-28 새 TEST 주문의 실제 자동 전액 환불 검증 완료: 휴대폰 승인 후 토스페이 테스트용 PAY_APPROVED 페이지의 같은 결제 번호를 확인하고 이동하기를 눌러 PAID까지 진행했다. 새 9,000원 결제에서 실제 지급/ZIP 다운로드→고객 접수·중복→실제 정지→PG 전액 취소 1건→Order COMPLETED·Payment/Order 시도 SUCCEEDED(PG_RESPONSE)→Library SUSPENDED·신규 Download 403을 확인했다. 관련 Outbox 8건 SENT·동일 정지 완료 근거/저장 순서·원 PAID/구매 사실·기존 별도 9,000원 결제를 보존했다. DB 보고서의 존재하지 않는 열 오류를 읽기 전용으로 고쳤고 결제/환불 POST는 추가하지 않았다. 기존 고객 URL 자체의 만료 후 GET은 확인하지 못했고 별도 S3 만료 제어 검사 200→403과 구분한다. runs/refund-live-obe63z8s/e2e-result.json 및 resource-preservation-final.json이 최종 근거이며 초기 오류도 보존했다. 기존 컨테이너 22/볼륨 57 보존·신규 전용 MinIO 1개. [실제 검증 범위](pg-test-integration-plan.md#2026-10-28-새-test-주문의-실제-자동-전액-환불-검증-완료).


2026-10-28 관리자 최초 판정 A안 검증: 도메인 Red 6건 뒤 구현했다. 로컬 Order 단위 290건·통합 컴파일·bootJar 통과. OCI full에서 새 API 명세 차이와 JDBC CHECK 위반 예외 분류 테스트 1건을 보정하고, 명세 갱신 후 corrected 전체 --rerun-tasks에서 Order 단위 290·통합 114건이 모두 실패/오류/건너뜀 0이다. 새 통합 19건은 실제 JWT 권한·페이지/상세·고객 반려 상태·동시 반대/같은 판정·명령의 다른 환불 재사용·지급 경합·실제 Outbox 삽입 후 롤백·기존 정지/결과 흐름·V19 자료 보존을 검증한다. 업무 로직/V20은 최초 OCI 이후 바꾸지 않았으며 후속 운영 코드 변경은 새 API의 명시적 OpenAPI operationId뿐이다. 기존 3개 경로·5개 연산의 명세는 같고 RefundResponse에 REJECTED/rejectionReason과 관리자 3개 API만 추가했다. 소스 1,100개·실제 비밀값 포함 0·기존 컨테이너 23개/볼륨 57개 및 상태 보존. runs/refund-review-oci-d1jx_po3/collected/results/{full,schema-update,corrected}와 runs/refund-review-tdd-brt1d7x3에 근거가 있다. schema-update의 단위 XML은 직전 full의 결과이며 재실행 수치가 아니다. [계약](pg-test-integration-plan.md#2026-10-28-관리자-최초-환불-판정-확정-a안).


2026-10-28 Gateway 관리자 환불 경로 보완: 서비스 직접 호출과 별도로 /api/v1/admin/refunds의 목록/상세/판정이 Order에 도달하도록 라우트와 ADMIN 허용 경계를 추가했다. 미등록 경로/메서드는 거절하고 Bearer 전달·X-Member-Id 제거를 검증했다. 로컬 Red 4건 뒤 Gateway 단위 97건·통합 컴파일·bootJar가 통과했다. OCI Gateway 단위 97·Redis 통합 1건이 실패/오류/건너뜀 0이고 기존 컨테이너 23개/볼륨 57개를 보존했다. 근거는 runs/refund-review-gateway-oci-7l32q55b/collected/results/full, 최초 Red는 runs/refund-review-tdd-brt1d7x3/gateway-red.json이다. HTTP upstream은 Order 계약 픽스처이며 실제 관리자 로그인/수동 PG 환불 전체 검증을 대신하지 않는다. 기존 실제 주문 검증 스택에는 Gateway 프로세스를 새로 기동하지 않았다.

2026-10-28 고객 명시적 재시도 HTTP 첫 번째 안: 미구현 HTTP 경계 Red 8건 뒤 previousAttemptId를 받는 MEMBER API와 실패 ID 조회를 연결했다. 로컬 Order 308·Gateway 100건 및 빌드/통합 컴파일 통과. 초기 Gateway CSRF 기대값과 OCI 중복 구매 픽스처 2건을 보정한 최종 OCI 전체 --rerun-tasks에서 Order 단위 308·통합 124건, Gateway 단위 100·Redis 통합 1건 모두 실패/오류/건너뜀 0이다. 새 HTTP 통합 10건이 현재 실패 식별·동시/중복·후속 실패 뒤 오래된 요청·실제 재정지 선행·복구 전 거절·권한/소유권·실제 flush 롤백·반려/미확정/완료 거절을 확인한다. 기존 6개 경로·8개 연산을 보존하고 재시도 POST와 nullable retryableAttemptId만 추가했으며 최종 명세 대조는 갱신 플래그 없이 수행했다. 소스 1,108개 대조·비밀값 포함 0. runs/refund-retry-http-tdd-w407gjr7 및 runs/refund-retry-http-oci-fw4f42_q/collected/results/{schema-update,full}과 validation-summary.json에 원자료·보존 결과가 있다. 기존 실제 런타임은 미갱신이며 Payment/License/Order upstream 계약 픽스처를 실제 PG 환불 E2E로 확대하지 않는다. [상세](pg-test-integration-plan.md#2026-10-28-고객-명시적-재시도-http-첫-번째-안).

2026-10-28 운영 중단 조회·재개 관리자 API A안: Gateway 경로 Red 3건과 실제 명령 유일 키 경합 Red 1건 뒤 관리자 목록·상세·재개를 연결했다. spy 추상 메서드 호출·잔액 중단 필드 기대값 오류는 업무 Red에서 제외하고 실제 JPA/DB 조율로 보정했다. 로컬 Payment 단위 290·Gateway 106건 및 빌드/통합 컴파일 통과. 최종 OCI --rerun-tasks에서 Payment 단위 290·통합 177, Gateway 단위 106·Redis 통합 1건이 모두 실패/오류/건너뜀 0이다. 새 HTTP 10건과 기존 PG HTTP 픽스처 연계 1건이 권한/감사·현재/과거 목록/상세·같은/다른 명령·새 중단에 대한 옛 영수증·3시간/잔액 변경·동시 명령 ID 충돌·실제 flush 롤백과 같은 키/본문의 재개를 확인한다. 기존 Payment 조회 2개 연산 보존, 관리자 3개 추가와 정상 명세 대조를 확인했다. 소스 1,121개·현재 코드/설정/명세 943개 해시 대조, 비밀값 포함 0, 공유 컨테이너 23개·볼륨 57개 및 상태 보존·추가/잔여 자원 없음이다. runs/refund-resume-admin-tdd-euaclz5i 및 runs/refund-resume-admin-oci-o2_0_er9/collected/results/{schema-update,red-command-collision,full}과 validation-summary.json에 근거가 있다. 실제 런타임/PortOne 금융 동작·실제 ADMIN 계정/운영 화면·PG 장애 후 재개 전체 검증은 별도다. [상세](pg-test-integration-plan.md#2026-10-28-운영-중단-조회재개-관리자-api-a안).

2026-10-29 반려 뒤 고객 재심사: 고객 HTTP Red 2건·Gateway Red 1건 뒤 구현하고 도메인 7건과 로컬 Order 315·Gateway 110건, 빌드/통합 컴파일을 통과했다. OCI full 성공 뒤 발행 본문 전체 대조의 LongNode/IntNode 테스트 오류 1건을 보정한 최종 corrected 전체 --rerun-tasks에서 Order 단위 315·통합 142, Gateway 단위 110·Redis 통합 1건 모두 실패/오류/건너뜀 0이다. 재심사 API 통합 17건과 V20→V21 자료 보존 1건이 기한 경계/잠금 대기/기한 뒤 판정·18회차·공개 범위·접수/판정 경합·실제 flush 롤백·승인/정지/완료/확정 실패/복구/기존 고객 재시도, 전체 정지 본문/키를 검증한다. 기존 7개 경로·9개 연산을 보존하고 4개 경로/연산과 현재 회차 정보를 추가했으며 최종 명세 대조는 갱신 플래그 없이 수행했다. 소스 1,139개·현재 코드/설정/명세 961개 해시 대조, Payment 소스 193개 유지, 비밀값 포함 0·공유 컨테이너 23개/볼륨 57개 및 상태 보존·추가/잔여 테스트 자원 없음이다. runs/refund-reconsideration-tdd-rh3d3gds 및 runs/refund-reconsideration-oci-2fz1w7px/collected/results/{red-http,schema-update,full,final,corrected}와 validation-summary.json에 근거가 있다. 실제 런타임/PG 금융 동작·실제 ADMIN/운영 화면의 재심사 E2E는 별도다. [상세](pg-test-integration-plan.md#2026-10-29-반려-뒤-고객-재심사).

2026-10-29 승인 중단의 최종 전체 회귀: Order 323·통합 158, License 102·통합 59, Gateway 111·Redis 1, 공통 Kafka 213, 총 967건 실패/오류/건너뜀 0. 선별/반복 수치를 더하지 않는다. 실제 실행 단계·픽스처 오류 보정·보존·계약/실제 전체 흐름의 구분은 [승인 중단 검증 기록](pg-test-integration-plan.md#2026-10-29-잘못된-승인-중단과-새-심사-복귀)을 따른다.

2026-10-29 고객 지연 안내/준비 이벤트 최종 전체 회귀: Order 331·통합 170, Payment 292·통합 183, 공통 Kafka 217, 총 1193건 실패/오류/건너뜀 0. Red 6건 및 선별/명세 갱신·전체 재실행의 구분, 기존 금융 Outbox 단언 범위 조정과 실제 검증/미검증 범위는 [지연 안내 검증 기록](pg-test-integration-plan.md#2026-10-29-장기-미확정-고객-안내와-준비-이벤트)을 따른다.

2026-10-29 장기 미확정 운영 조회·조사 기록 A안: 지연 경계·Gateway의 실제 Red 2건 뒤 구현했다. V26→V27 데이터 복사의 자동 생성 열 오류를 테스트 픽스처에서 정정하고 운영 코드는 유지했다. 최종 OCI full의 Payment 단위 304·통합 196 및 Gateway 단위 114·Redis 통합 1, 총 615건은 실패/오류/건너뜀 0이다. 새 HTTP/MySQL 13건이 경계/현재 금융 결과·이력/중복/명령 충돌·잠금 대기/실제 flush 롤백·별도 후속 시도·권한·데이터 보존/DB 제약을 확인한다. 기존 OpenAPI 5개 연산/12개 스키마 보존과 새 4개 연산, 공유 23개 컨테이너/57개 볼륨 보존·비밀값 포함 0을 대조했다. 원자료는 runs/refund-investigation-tdd-n8a8cbw0 및 runs/refund-investigation-oci-7k7xaibd/collected/results/full이다. 실제 런타임/PG/ADMIN 전체 흐름과 Order·License·공통 Kafka 재실행은 이번 범위가 아니다. [상세](pg-test-integration-plan.md#2026-10-29-장기-미확정-운영-조회와-조사-기록-a안).
