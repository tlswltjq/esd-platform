# 테스트 전략

<a id="scope-index"></a>
## 바뀐 범위별 검증 (먼저 읽는 색인)

바뀐 범위에 맞는 줄만 고른다. Gradle 명령은 `python3 tools/ci/gradle_result.py [--repeat N] -- <명령>`으로 돌려 판정한다([결정 213](decisions.md#ai-procedure-tools)). 순서는 단위·아키텍처 → 필요한 통합 → 최종 게이트(CI)다. 자세한 배경은 링크한 본문 절에 있다([결정 214](decisions.md#agent-context-cost)).

| 바뀐 범위 | 명령 | 본문 |
|---|---|---|
| 앱 하나의 `core`(도메인·서비스)·`api` 입력 변환 | `./gradlew :apps:<앱>:test` | [2. 테스트 계층](#2-테스트-계층) |
| 앱의 `adapter`·DB·리스너·Outbox/Inbox 연결 | 위 + `./gradlew :apps:<앱>:integrationTest` (MinIO를 쓰면 [이미지 재사용](#minio-image)) | [2. 테스트 계층](#2-테스트-계층) |
| Order·Payment의 Flyway 이행 파일 | 위 + `./gradlew :apps:<앱>:migrationTest` | [2. 테스트 계층](#2-테스트-계층) |
| `common/<모듈>` | `./gradlew :common:<모듈>:test` + 그 모듈을 쓰는 앱의 `test`. 메시징(`contract`·`messaging`·`messaging-kafka`)은 위 + `:common:messaging:integrationTest`(실 Kafka·MySQL 전달 보장 `KafkaDeliveryIntegrationTest`·격리 재투입·릴레이)·`:common:messaging-kafka:integrationTest` | [2. 테스트 계층](#2-테스트-계층) |
| 패키지 이동·ArchUnit 규칙·모듈 의존 | 구조 검사 묶음 | [구조·보안 경계 검사 실행](#structure-checks) |
| 배포 비밀·Compose·서비스 접근키·웹훅 경계 | 같은 절의 Python 검사와 `ServiceKey`·`PortOneWebhookDisabled` 통합 테스트 | [구조·보안 경계 검사 실행](#structure-checks) |
| 결함 재현 | `./gradlew defectTest` (실패 = 결함이 살아 있음) | [3. 결함 재현 테스트](#3-결함-재현-테스트) |
| 여러 모듈을 잇는 여정 | `python3 tools/verification/verify-jib.py`(격리 실제 스택), 스택이 떠 있으면 `./gradlew :e2e:e2eTest` | [2. 테스트 계층](#2-테스트-계층) |
| 장애 주입 | `./gradlew :e2e:chaosTest` (스택을 실제로 멈춘다) | [실험 E9](experiments.md#resilience) |
| 성능·메시징 측정 | `tools/ci/measure_guard.py before/after`로 감싼 측정 회차 | [실험](experiments.md) |
| 층 1 E2E 측정 하네스(`verify-jib.py --measure`·`e2e_baseline.py`·`support/e2e_faults.py`) | `tools/verification`·`tools/measurement` 단위 시험. 실제 스택으로 계측·장애 주입이 맞물리는지는 `python3 -B tools/measurement/e2e_baseline.py smoke`(회차마다 `measure_guard.py`로 감쌈, 30~45분) | [실험 E13](experiments.md#kafka-baseline-layer1) |
| `benchmarks/messaging`(층 2 메시징 벤치) | `./gradlew :benchmarks:messaging:test`(단위·ArchUnit). 실 Kafka·MySQL로 계측이 맞물리는지는 `measure_guard.py`로 감싼 `python3 -B tools/measurement/bench_messaging.py smoke`(CI 밖, [결정 234](decisions.md#benchmark-messaging-module)) | [실험 E13](experiments.md#kafka-baseline) |
| `tools/ci/` | `python3 -B -m unittest discover -s tools/ci -p 'test_*.py'` | [tools/README.md](../tools/README.md) |
| `tools/verification/`·`tools/measurement/` | `python3 -B -m unittest discover -s tools/verification -p 'test_<대상>.py'`. CI는 build 작업이 둘 다 늘 돌리고, 이 경로만 바뀐 PR은 Gradle을 생략한다 | [결정 216](decisions.md#ci-scope-non-gradle-paths) |
| 문서만(`*.md`) | 로컬 Gradle 없음. `python3 tools/ci/decision_numbers.py --root . --base-ref origin/main`(결정을 고쳤을 때) | CI가 Gradle을 생략한다([결정 207](decisions.md#ci-build-scope)) |

## 현행 Payment·Tempo 추적 검증

`python3 tools/verification/verify-tracing.py`는 기존 Jib 실행 기반에 격리된 Tempo·Grafana를 추가한다.
`--foundation-only`는 Payment 추적 저장·복원에 의존하지 않고 공통 HTTP 전파·수집·조회·Tempo 중단·샘플링 전환을 먼저 검증한다.
Payment 추적 통합은 실제 Micrometer/OpenTelemetry와 메모리 exporter로 접수/작업 스레드·부모·scope·HTTP client span을 대조한다. 별도 스케줄러 통합 검사는 처리 메서드를 직접 호출하지 않는다.
`:e2e:tracingE2eTest`는 현재 공개 구매 API·실제 ZIP 다운로드와 Tempo의 span/parent 관계를 판정하며, 원래 콜백 기반 `TracePropagationE2eTest`를 대체한다. 일반 `e2eTest`에서는 tracing 태그를 제외한다.
전용 실행 도구는 요청 저장 뒤 Payment 재시작·PG 응답 유실·결과 롤백·브로커 중단·Tempo 중단·추적 비활성·샘플링 0을 단계별로 주입한다. 운영 어댑터는 항상 승인하므로 PG 응답 유실은 검증 전용 `PaymentFaultConfiguration`으로만 만든다([결정 in-process-pg-approval](decisions.md#in-process-pg-approval)). 필수 검사 0건·건너뜀은 통과로 판정하지 않는다.
범위·시나리오·실행 수는 [실험 E10](experiments.md#tempo), 조회 방법은 [실행 안내](getting-started.md#tempo에서-요청-추적-조회)를 따른다.

<a id="structure-checks"></a>
## 구조·보안 경계 검사 실행

아키텍처 범위만 다시 돌릴 때는 공통 규칙 자체 검사·공통 라이브러리·11개 앱의 `*ArchitectureTest`와 실행 의존성을 함께 실행한다. `CI=true`로 테스트 결과 캐시를 쓰지 않는다. `--tests`는 모듈 태스크마다 따로 붙인다.

```bash
CI=true ./gradlew :common:archunit:test \
  :common:messaging:test --tests '*ArchitectureTest' :common:security:test --tests '*ArchitectureTest' \
  :common:web:test --tests '*ArchitectureTest' \
  :apps:<앱>:test --tests '*ArchitectureTest'   # auth·catalog·download·gateway·license·order·payment·review·settlement·store·studio 각각
  verifyCommonRuntimeDependencies :common:archunit:check --console=plain
```

배포 비밀·서비스 접근키·웹훅 경계([보안 계약](security.md#deployment-secrets))는 다음으로 확인한다. Python 검사는 알려진 픽스처 값만 쓰고 실제 `.env`를 읽지 않는다. 통합 검사의 MinIO 이미지는 [아래](#minio-image)를 따른다.

```bash
python3 -B -m unittest discover -s tools/verification -p test_protected_compose.py -v
python3 -B -m unittest discover -s tools/verification -p test_service_credentials.py -v
python3 -B -m unittest discover -s tools/verification -p test_boot_jars.py -v
python3 -B -m unittest discover -s tools/verification -p test_ports.py -v   # 도구별 포트 간격·JAR 선택(결정 105)
./gradlew :common:test:test --no-daemon --max-workers=2          # Compose 배포 계약
./gradlew :apps:catalog:integrationTest --tests 'com.stove.catalog.*ServiceKey*IntegrationTest' \
  :apps:order:integrationTest --tests 'com.stove.order.*ServiceKey*IntegrationTest' --no-daemon --max-workers=2
./gradlew :apps:payment:integrationTest --tests 'com.stove.payment.PortOneWebhookDisabledIntegrationTest' --no-daemon --max-workers=2  # 웹훅 검증기가 조립되지 않아 서명된 POST도 거절
python3 tools/verification/verify-gateway-ownership.py            # 실제 11개 앱의 키 수신자 기록(이름만)
```

<a id="minio-image"></a>
### 통합 테스트의 MinIO 이미지

Studio·Review·Download·Catalog 통합 테스트는 `:infra:minio:jibDockerBuild`가 고정 MinIO/mc 소스로 만든 로컬 이미지를 쓴다. 이름은 루트 `build.gradle`의 `minioImageSelection`이 한 번만 정하고, 통합 테스트에 `STOVE_TEST_MINIO_IMAGE`로 넘긴다. `tools/verification/support/minio_image.py`도 같은 규칙을 따른다.

- 빌드 이름은 `-Pstove.minio.image`, 없으면 `<registry>/minio:RELEASE.2025-04-22T22-12-26Z-local`(registry는 `-Pstove.jib.registry`·`STOVE_JIB_REGISTRY`, 기본 `localhost/stove`)이다. Compose·게시도 이 이름을 쓰며, 테스트 전용 변수는 이 이름을 바꾸지 않는다.
- `STOVE_TEST_MINIO_IMAGE`가 고정 기본 이름 `localhost/stove/minio:…-local`인데 빌드 이름과 다르면(registry를 바꾼 경우), 빌드 이름으로 만든 뒤 `:infra:minio:tagTestImage`가 고정 이름을 붙인다. 이때는 재사용하지 않는다.
- 예전 기본값 `stove/minio:…`처럼 Docker Hub의 `stove` 계정을 가리키는 이름은 받지 않고 거절한다([결정 135](decisions.md#local-image-registry)). 예전 이름의 이미지가 로컬에 있으면 `docker tag stove/minio:RELEASE.2025-04-22T22-12-26Z-local localhost/stove/minio:RELEASE.2025-04-22T22-12-26Z-local`로 새 이름을 붙여 다시 쓸 수 있다(내용이 같다).
- 로컬 이미지 재사용은 `jibDockerBuild`만 건너뛴다. 같은 실행에 게시(`jib`)·tar(`jibBuildTar`)가 있으면 소스 빌드를 한다.
- 변수를 지정하지 않으면 지금처럼 빌드한다. 빈 Docker 환경에서도 이미지를 만든다.
- `STOVE_TEST_MINIO_IMAGE`에 빌드 이름을 지정하면, 그 이미지가 로컬에 있을 때만 빌드를 건너뛴다. 없으면 빌드하며, registry에서 받지 않는다. `STOVE_MINIO_IMAGE`가 함께 있어도 같다.
- `STOVE_TEST_MINIO_IMAGE`에 다른 이름을 지정하면 빌드하지 않고 Testcontainers가 그 이미지를 받는다. `STOVE_TEST_MINIO_IMAGE` 없이 `STOVE_MINIO_IMAGE`만 있으면 Compose용 공급 이미지로 보고 빌드를 건너뛴다.
- MinIO 빌드 입력(`infra/minio/**`, 빌더·기반 이미지, `STOVE_JIB_REGISTRY`)을 바꾼 브랜치에서는 재사용하지 않는다. 바뀌기 전 이미지를 검증하게 된다.

```bash
STOVE_TEST_MINIO_IMAGE=localhost/stove/minio:RELEASE.2025-04-22T22-12-26Z-local ./gradlew :apps:review:integrationTest --console=plain
python3 -B -m unittest discover -s tools/verification -p 'test_*image*.py' -v   # 이름 선택·Docker Hub 거절(Python 쪽)
./gradlew :common:testcontainers:test :common:test:test --tests '*InfraContainersTest' --tests '*LocalImageRegistryContractTest'
```

빌드 컨테이너(`compile.sh`)는 끝날 때 결과물·Go 캐시를 마운트 폴더의 주인에게 돌려준다. 컨테이너가 강제 종료(SIGKILL, `docker kill`)되면 돌려주지 못해 지울 수 없는 파일이 남을 수 있다. 다음 빌드가 끝나면 다시 돌려주지만, 그 전에 지워야 하면 다음처럼 주인을 바꾼다.

```bash
docker run --rm --mount type=bind,source="$PWD/infra/minio/build",target=/w alpine:3.21 \
  sh -c "chown -R $(id -u):$(id -g) /w && chmod -R u+w /w"
```

> 이 문서의 구 데모·성능·장애 실험 명령과 결과는 재작성 전 기록이다. 해당 실행 코드는 2026-10-21 정리했으며 [정리 전 소스](https://github.com/tlswltjq/esd-platform/blob/e32750ffc95dee1fe5b59b2e248859f84aa77154/scripts/)에서 확인할 수 있다. 현재 검증은 [tools/verification](../tools/verification/README.md)를 사용한다.


무엇을 어느 층에서 검증하고, 왜 그 층인지 적는다.
개별 결함 목록은 [defects.md](defects.md) 에 있다.

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
| **L2 어댑터·계약** | 봉투 파싱, 라우팅, 실패 분기, 직렬화 | 없음(대역) | `test` | `apps/*/api/listener`, `common/contract`·`common/messaging-*` | ms | 매 빌드 |
| **L3 메시징 인프라** | 릴레이 재시도, 멱등 가드 | 없음(대역) | `test` | `common/messaging` | ms | 매 빌드 |
| **L4 구조** | 패키지 경계, 의존 방향, 순서 보장 전제 | 없음 | `test` | `*ArchitectureTest` | ms | 매 빌드 |
| **L5 기동** | 빈 구성, Flyway ↔ 엔티티 정합 | 전체 | `integrationTest` | `*ContextTest` | 십수 초 | 매 빌드 |
| **L6 인수** | 서비스 *사이* — 이벤트가 건너가는가, 되감기는가 | 배포된 스택 21종 | `e2e` 모듈 | `e2e/src/test` | 46건에 36초 | **CI 밤마다·수동** (`verify-jib.py`) |
| **L7 장애 주입** | 서비스를 정지시켰을 때 격리되는가, 복구되는가 | **망가뜨려도 되는** 스택 | `e2e` 모듈 (`chaos` 태그) | `ServiceOutageChaosTest` | 회차에 수 분 | 손으로 |

CI(`.github/workflows/ci.yml`, 결정 101)는 GitHub 호스팅 러너에서 main push마다 `./gradlew build`(L0~L5·과거 이행)를, PR에서는 바뀐 범위만 돌린다([결정 207](decisions.md#ci-build-scope): 문서만이면 생략, `apps/<앱>/` 안만이면 그 앱의 `build`, 그 밖은 전체. `e2e/`·`benchmarks/messaging/`만이면 그 모듈의 `build`, 루트 `docker-compose*.yml`만이면 `:common:test:test`, `scripts/`만이면 생략 — [결정 ci-scope-leaf-paths](decisions.md#ci-scope-leaf-paths)). 또 매일 03:00 KST와 수동 실행 때 L6 실제 스택(`verify-jib.py`, `:e2e:purchaseE2eTest`의 구매→지급→다운로드)을 돌린다. PG 연동은 걷어내 결제 실패·환불 야간 작업은 없다([결정 in-process-pg-approval](decisions.md#in-process-pg-approval)). 공개 저장소라 self-hosted 러너를 쓰지 않고, 비밀 값은 CI에 넣지 않는다.

L6·L7 이 `build` 밖이다. 스택이 떠 있어야 돌기 때문이고, 그 조건을 기본 빌드에 넣으면
컨테이너가 없는 로컬에서 전체 빌드가 항상 빨개진다. 대신 `./gradlew :e2e:e2eTest` 로 따로 부른다.

**L7 을 L6 에서 뗀 이유는 자원이 다르기 때문이다.** L6 은 떠 있는 스택이면 되지만
L7 은 그 스택을 **정지시킨다.** 한 회차에 두면 저니 중간에 서비스가 사라져 관계없는 판정이
무더기로 빨개지고, 그러면 "인수가 깨졌다" 와 "일부러 죽였다" 가 리포트에서 같은 모양이 된다.
시나리오와 판정 근거는 [실험 E9](experiments.md#resilience).
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
./gradlew test              Docker 불필요 · 단위/아키텍처
./gradlew integrationTest   Testcontainers · Order/Payment의 과거 이행 검사는 별도
./gradlew :apps:order:migrationTest :apps:payment:migrationTest
./gradlew build             단위·통합·과거 이행을 모두 포함
./gradlew :e2e:e2eTest      46건 · 떠 있는 스택이 필요하다
./gradlew :e2e:chaosTest    5건 · 스택을 실제로 정지시킨다 (L7)
```

단위/인프라 경계를 태그로만 가르지 않은 이유가 여기 있다. 태그는 붙이는 것을 잊을 수 있고,
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
// build-logic 의 stove.java-base 플러그인, integrationTest 태스크
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

## 6. 테스트를 쓸 때

- **테스트를 붙일 모듈은 `pitest` 로 확인한다.** 통과하는 테스트와 잡아내는 테스트는 다르다(5절).
  위 표의 "위험"은 대부분 *통과하지만 못 잡는* 자리다
- **`targetTests` 를 지정하지 않으면 pitest 가 `targetClasses` 패턴을 테스트 선택에도 쓴다.**
  그래서 한동안 `api.*` 의 테스트(컨트롤러·리스너·파사드)가 뮤테이션 판정에
  **한 번도 참여하지 못했다** — 전 모듈을 통틀어 뮤턴트를 죽인 테스트가
  `core.service` 와 `core.domain` 두 패키지에서만 나왔다.
  파사드가 지키는 규칙은 전부 "생존"으로 보고되고 있었고, 그건 테스트 공백이 아니라
  **측정 도구의 사각지대**였다. 공통 빌드 구성(지금은 `build-logic` 의 `stove.java-base`)에서 `targetTests = ['com.stove.*']` 로 고쳤다.
  (settlement 기준 31/40 → 33/40, `api.application` 테스트가 11개를 죽인다)
  뮤테이션 점수를 읽을 때는 **무엇이 측정에 포함됐는지**를 먼저 본다
- **새 통합 테스트는 망가뜨려 본다**([결정 102](decisions.md#integration-test-break-check)).
  Green 뒤 그 테스트가 지키는 운영 코드의 핵심 줄을 잠깐 망가뜨려 실패를 확인하고 되돌린다.
  단언 실패와 업무 코드에서 난 예외(DB 제약 등)를 인정한다 — pitest 의 "killed" 와 같은 기준이다.
  컨텍스트 로딩·Docker 연결 실패는 인정하지 않는다(3절). 망가뜨린 위치와 실패 메시지는 PR 본문에 남기고,
  예외가 먼저 막아 실행되지 못한 단언이 있으면 그것도 적는다
- **상태를 바꾸는 두 기능의 엇갈림은 멈춤 지점으로 고정한다**([결정 103](decisions.md#interleaving-pause-point)).
  "동시에 출발"은 순서를 운에 맡긴다. `common:test` 의 `PausePoints` 를 테스트 설정에 등록하면 지정한 저장소의
  메서드가 끝날 때마다 `"타입.메서드"` 지점을 지난다. 요청 1을 이름 붙은 스레드로 시작해 지점에서 세우고,
  요청 2를 실제 서비스로 끝낸 뒤 푼다. **잠금 전 읽기** 지점은 잠근 뒤 다시 읽는지, **잠금 뒤** 지점은 상대가
  잠금 호출에 **들어간 뒤** 나오지 못하는지(`watch(...).stillInsideAfter`) 본다. "작업이 아직 안 끝났다"만 보면
  상대가 늦게 시작한 것과 잠금에 막힌 것을 가르지 못한다(PR #118 리뷰). `reset()` 은 세운 스레드를 모두 풀어,
  테스트가 중간에 실패해도 잠금을 쥔 채 남지 않게 한다. 예: `PurchaseInterleavingIntegrationTest`.
  다른 방어선(보유권 상태 확인·DB 유일 키)이 먼저 막는 순서는 지키려는 줄을 증명하지 못하므로,
  그 줄만 남는 사례(예: 보유권 없는 보관함 구매)를 따로 둔다
- **옛 데이터·옛 메시지를 바꾸는 PR은 호환 테스트를 같이 쓴다**([결정 104](decisions.md#upgrade-compat-on-change)).
  ① 이행 파일이 옛 행을 바꾸거나 의미를 옮기면 `Flyway.target(직전 버전)`까지 올린 DB에 옛 행을 넣고
  끝까지 올려 확인한다(`OrderMigrationIntegrationTest` 방식, `@Tag("migration")`).
  ② 이벤트 본문·지문이 바뀌면 옛 본문 견본과 옛 지문 값으로 새 코드가 받아들이는지 확인한다
  (`RestorationFingerprintCompatibilityTest` 방식). 새 표·새 칸만 더하는 이행은 제외한다.
  `migrationTest` 가 없는 모듈은 첫 해당 PR에서 틀을 만든다
- **단언은 값으로 한다.** `anyString()` 은 "무엇이 전달되는가"가 검증 대상일 때 쓰지 않는다
- **Outbox 단언을 `count()` 차이로 끝내지 않는다.** 잘못된 이벤트를 발행해도 `count` 는 똑같이 1이다.
  `eventType`·`aggregateId`·`partitionKey` 와 페이로드를 읽는다
- **`@Cacheable`/`@CacheEvict` 는 프록시 위에서만 동작한다.** `new` 로 만든 인스턴스에 걸면
  캐시를 안 지워도 통과하는 테스트가 된다
- 데이터 격리는 삭제가 아니라 **키 공간 분리**다(4절). `SettlementCloseTest` 의 `uniqueMonth()` 처럼
  순번을 쓰는 헬퍼는 **소진 한계**를 확인하고 늘린다
- **긴 검증의 종료는 명령 이름이 아니라 PID 로 기다린다.**
  `while pgrep -f "verify-jib.py --prometheus-only"; do sleep 5; done` 은 대기 셸 자신의
  명령줄에도 같은 글자가 있어 **스스로를 찾는다**. 검증이 실패로 끝나도 대기가 끝나지 않고,
  제한 시간에 걸릴 때까지 결과가 보고되지 않았다(2026-11-06 Prometheus 수집 검증, 두 대기 모두 30분 제한).
  같은 이름의 다음 재실행도 잡는다. 시작한 프로세스의 PID 를 받아
  `until ! kill -0 "$PID"; do sleep 5; done` 으로 기다리거나, 별도 대기 없이 검증 명령 자체를
  백그라운드로 실행해 종료 알림을 받는다. 제한 시간은 직전 실행 시간의 2~3배로 잡는다
