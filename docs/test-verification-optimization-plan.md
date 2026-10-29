# 테스트 검증 병목 진단과 적용안

2026-10-29. 사용자가 제공한 「AX 시대의 테스트 검증 최적화 전략」을 현재 프로젝트의 코드·Gradle 입력·최근 실행 기록에 대조한 검토다. 첨부 문서는 기술 제안으로 읽었으며, 내부 예제 명령·체크리스트를 이 저장소의 실행 지시나 품질 게이트 변경 승인으로 해석하지 않았다.

최초 검토 산출물은 진단·도입안과 기존 JUnit XML을 재집계하는 도구였다. 해당 검토에서는 업무 코드·빌드 설정·테스트 선택/제외·CI 정책을 변경하지 않았고 전체 회귀나 성능 벤치마크도 실행하지 않았다. 이후 사용자가 개선 실행을 요청해 적용한 범위와 검증은 아래 10절에 별도로 기록한다.

## 1. 결론과 우선순위

현재 병목은 테스트 수 증가만의 문제가 아니다. **반복 검증 순서, 테스트 입력 경계, 반복적인 과거 DB 이행**에 가장 먼저 손댈 근거가 있다.

1. **P0 — 실행 순서와 캐시 입력 정확성:** 전체 회귀 전에 변경을 정리하고 컴파일·아키텍처·관련 단위를 먼저 통과시킨다. 외부 실행 파일/스크립트의 입력 누락을 보완한다.
2. **P1 — 공유 픽스처 분리:** 통합 테스트가 단위 테스트 클래스 전체를 의존하지 않도록 한다.
3. **P1 — 마이그레이션 검사 분리:** 개발 반복용 통합 실행과 역사적 이행 검사를 구분하되 최종 `check`·빌드는 두 종류를 모두 실행한다.
4. **P2 — 무거운 컨텍스트·외부 PG 기동 축소:** DB/이벤트 검사에 필요한 자원만 기동하고 동일 설정의 컨텍스트를 재사용한다.
5. **P3 — 검증 후 병렬도·분산/TIA 검토:** 격리·입력 선언과 비용 기록을 먼저 갖춘 후 효과를 측정한다.

## 2. 현재 이미 적용된 것

- `test`와 `integrationTest` 소스셋이 분리돼 있고 Testcontainers는 통합 계층에만 있다.
- `org.gradle.parallel=true`, 워커 4개, `InfraTestThrottle`의 동시 인프라 태스크 2개 제한이 있다.
- Testcontainers는 `SharedContainers`의 정적 인스턴스로 같은 JVM 안에서 재사용된다. 사례마다 MySQL·Kafka를 새로 띄우는 구조가 아니다.
- 로컬 Gradle 캐시가 켜져 있다. CI 환경변수가 있으면 Test 태스크의 UP-TO-DATE/캐시 재사용을 막아 실제 검증을 강제한다.
- 통합 Hikari 풀을 5개로 제한해 여러 Spring 컨텍스트의 DB 연결 고갈을 방지한다.
- 단위·HTTP/이벤트 계약·루프백 공급자·실제 MySQL/Kafka·별도 E2E가 있다. PIT는 기본 빌드 밖의 수동 실행이다.

따라서 문서의 각 기술을 새 도구로 전부 도입할 필요는 없다. 현재 Gradle 구조의 검증 경계와 실행 대상을 먼저 다듬는 편이 비용 대비 효과가 높다.

## 3. 최근 기록의 수치

근거는 `runs/order-expiry-propagation-20261029/final`의 마지막 성공 XML과 로그다. Order 통합과 Payment 통합은 서로 다른 최종 성공 실행에서 수집됐으며, 통제한 A/B 벤치마크가 아니다.

| 측정 항목 | Order | Payment |
|---|---:|---:|
| 단위 사례 수 | 347 | 347 |
| 단위 사례 시간 합 | 4.514초 | 4.429초 |
| 통합 사례 수 | 190 | 241 |
| 통합 사례 시간 합 | 388.769초 | 346.306초 |
| 과거 DB 이행 사례 수 | 12 | 8 |
| 해당 사례 시간 합 | 276.769초 | 193.171초 |
| 통합 사례 시간에서 이행 검사 비중 | **71.19%** | **55.78%** |
| Spring 기동 로그 수 | 11 | 16 |
| 기동 로그에 표시된 시간 합 | 84.433초 | 111.974초 |
| 로그의 Flyway migration step 수 | 308 | 270 |

**사례 시간 합은 Gradle 명령 경과 시간이 아니다.** JVM/컨테이너·컨텍스트 준비·컴파일·스케줄링·종료 비용이 별도로 있고 로그 집계 간 포함 관계도 다르므로 위 행들을 단순히 더하지 않는다. 71%·56%는 해당 사례의 비용 비중이며 도입 후 전체 실행 시간이 그만큼 단축됐다는 뜻이 아니다.

이행 사례는 XML의 클래스/메서드 이름으로 후보를 추린 뒤 소스의 실제 Flyway 호출과 대조했다. Order는 `OrderMigrationIntegrationTest` 8개와 `RefundExecutionIntegrationTest`의 evidence 4개다. Payment는 전용 클래스 2개와 여러 업무 클래스에 섞인 이행 사례 6개다. 비용 도구의 분류는 검토용 후보이며 자동 테스트 제외 기준이 아니다.

대표적인 반복은 `RefundExecutionIntegrationTest.migrationRecoversOnlyOneLocallyVerifiedOriginalRequest`다. valid/missing/ambiguous/wrongBuyer 각각이 새 스키마에 V1→V14를 실행한 뒤 최신 버전까지 다시 이행하며, 네 사례가 각각 약 26초 걸린다. 네 조건은 다른 불변 조건을 검증하므로 삭제 대상으로 보지 않는다. 이미 파라미터화돼 있어 구문만 정리해도 실행 횟수와 DDL 비용은 줄지 않는다.

같은 작업의 전체 회귀 명령은 8분 58초, 최종 Payment 재검증을 포함한 명령은 7분 59초였다. 두 실행은 입력·자원 구성이 같지 않고 일부 명령은 겹치므로 합산을 사용자 총 대기 시간으로 쓰지 않는다. 다만 전체 검증을 시작한 뒤 경계/구현을 다시 수정해 전체 회귀를 재실행한 작업 순서가 비용을 키운 것은 기록으로 확인된다. 이 실행 순서도 개선 대상이다.

## 4. 우선 해결할 정확성·구조 문제

### 4.1 단위 테스트 한 개가 통합 전체를 무효화하는 의존성

[루트 build.gradle](../build.gradle:93)은 통합 compile/runtime classpath에 `sourceSets.test.output` 전체를 넣는다. Payment의 실제 Gradle 입력 조회에서도 이 포함 관계를 확인했다. 통합 테스트에서 사용하는 몇 개의 Fixture와 실행하지 않는 단위 테스트 클래스가 같은 출력에 묶여 있다.

Gradle 8.14.3은 실행 classpath를 입력으로 추적한다. 따라서 단위 테스트 바이트코드가 바뀌어도 통합 입력이 달라질 수 있다. 이것은 캐시를 꺼서 생기는 문제가 아니라 현재 의존성이 넓어서 생기는 재실행이다. [Gradle 8.14.3 Test 소스](https://raw.githubusercontent.com/gradle/gradle/v8.14.3/platforms/jvm/testing-jvm/src/main/java/org/gradle/api/tasks/testing/Test.java)

적용안은 Payment의 PaymentFixtures·RefundFixtures·PortOneFixtures, Order의 OrderFixtures 등 양쪽에서 쓰는 지원 코드를 `testFixtures` 또는 독립 지원 소스셋으로 옮기는 것이다. 그 뒤 통합 classpath에서 일반 단위 테스트 출력을 제거한다. Gradle의 test-fixtures 플러그인은 이 공유 코드를 별도 소스셋으로 제공한다. [Gradle 테스트 픽스처](https://docs.gradle.org/current/userguide/java_testing.html#sec:java_test_fixtures)

**트레이드오프:** 한 번의 소스셋·의존성 정리가 필요하고 ArchUnit/PIT·결함 재현 태스크의 픽스처 가시성을 확인해야 한다. 공유 Fixture를 남겨 둔 채 test 출력만 캐시 계산에서 숨기면, 필요한 재실행까지 누락될 수 있다.

도입 수용 조건은 단위 테스트 본문/설명만 변경한 경우 통합 태스크가 재사용되고, 실제 Fixture·운영 코드·스키마 변경은 관련 태스크를 다시 실행하는 것이다. 단순 주석 변경이나 `--dry-run`만으로 캐시 무효화 개선을 입증하지 않는다.

### 4.2 반대로 필요한 재실행을 빠뜨릴 수 있는 입력 누락

[Payment build.gradle](../apps/payment/build.gradle:13)은 모의 PG bootJar를 먼저 만들고 `mock.pg.jar`에 고정 경로를 전달한다. [ExternalPgProcess](../apps/payment/src/integrationTest/java/com/stove/payment/ExternalPgProcess.java:33)는 이를 별도 JVM으로 실행한다. 실제 Gradle 8.14.3 메타데이터 조회 결과는 다음과 같다.

| 항목 | 조회 결과 |
|---|---|
| PG bootJar가 명시적 선행 태스크인가 | true |
| PG JAR 내용이 Payment 통합 입력에 포함되는가 | **false** |
| PG JAR가 Payment 테스트 실행 classpath에 있는가 | false — 공급자 분리 계약에 맞음 |
| 실행하는 외부 `migrate.py`가 통합 입력에 포함되는가 | **false** |
| failFast | false |
| maxParallelForks / forkEvery | 1 / 0 |

선행 빌드가 있다는 사실과 그 산출물의 내용이 테스트 입력이라는 사실은 별개다. PG JAR 경로가 그대로인 채 공급자 코드만 바뀌면 로컬 통합 결과가 잘못 재사용될 위험이 있다. 캐시 적중률을 높이기 전에 이 부분을 보완해야 한다.

PG JAR를 테스트 classpath에 넣지 않고 **파일 내용 입력**으로 선언하는 안을 권한다. 외부 이관 스크립트도 입력으로 선언한다. Order·Payment·Catalog의 테스트가 읽는 `tools/migrations/**` SQL 파일도 같은 방식으로 점검한다. 이 검토의 런타임 조회로 확인한 누락은 Payment JAR와 Python 파일이며, 다른 SQL 파일은 소스에서 추가 조사 대상으로 식별했다.

CI의 실제 실행 강제 정책을 곧바로 풀지 않는다. 소스 외 Docker/JDK/이미지/파일 입력을 먼저 정리하고, 실제 인프라 증명이 필요한 실행과 로컬 결과 재사용을 구분한다.

### 4.3 통합 실행 전에 빠른 실패를 확인하는 단계

루트의 `shouldRunAfter(test)`는 두 태스크가 함께 요청될 때의 순서다. 통합 태스크만 요청하면 단위 테스트가 자동으로 실행되는 의존성이 아니다. 컴파일된 단위 테스트 출력이 필요하다는 것과 단위 테스트를 실행했다는 것은 다르다.

반복 개발은 **관련 단위+아키텍처 → 관련 통합 → 변경 정리 후 최종 게이트**로 나눈다. 여러 모듈 변경이면 빠른 단계가 모두 성공한 뒤 느린 단계를 시작한다. 같은 모듈의 전체/집중 검증을 별개 Gradle 명령으로 동시에 실행하면 결과 파일·클래스 출력이 충돌할 수 있다. InfraTestThrottle도 Gradle 실행 단위의 제한이므로 여러 Gradle 프로세스가 전역 제한을 공유한다고 가정하지 않는다.

로컬 실패 수정은 지원되는 `--tests`와 `--fail-fast`를 사용한다. 첫 실패에서 끝난 결과는 부분 결과로 기록한다. 최종 게이트는 필요한 전체 결과를 남긴다. [Gradle 8.14.3 fail-fast 구현](https://raw.githubusercontent.com/gradle/gradle/v8.14.3/platforms/jvm/testing-jvm/src/main/java/org/gradle/api/tasks/testing/Test.java)

## 5. 마이그레이션 검사를 없애지 않고 실행 주기를 나누기

현재는 여러 업무 클래스에 과거 버전 이행 검사가 섞여 있어 해당 클래스만 골라도 수십 초짜리 전체 DDL 재생이 따라온다.

권장 도입안은 다음과 같다.

- 일상 반복용 integrationTest에는 현재 스키마의 실제 제약·잠금·Inbox/Outbox·서비스 계약 검사를 둔다.
- 과거 버전 보존/이관 검사는 migrationTest로 분리한다. 첫 단계는 명시적 태그/전용 클래스, 이후 필요하면 별도 소스셋으로 정리한다.
- **최종 `check`·`build`는 test + integrationTest + migrationTest를 모두 실행한다.** 현재 필수 검증을 야간으로만 밀거나 삭제하는 안이 아니다.
- SQL·엔티티·공통 DB 설정·외부 이관 도구·구매/금융 데이터 모델 변경은 관련 이행 검사를 반드시 포함한다. 단순 서비스 수정의 매 중간 반복에서 과거 전체 버전을 재생하는 빈도는 줄인다.

**트레이드오프:** 명령별 검증 범위가 달라져 전체/부분 결과를 구분해야 한다. 섞인 테스트를 옮길 때 각 불변 조건과 원자료를 대조하고, 실제 CI/병합 절차가 전체 게이트를 호출하는지 확인해야 한다. 현재 체크아웃에서는 실행 가능한 CI 워크플로 정의를 찾지 못했고 build.gradle의 CI 분기만 확인했다. 문서의 과거 CI 설명만으로 현재 외부 게이트가 작동한다고 단정하지 않는다.

이후 최적화로 동일 과거 버전의 준비 상태를 안전하게 재사용하는 실험은 가능하다. 다만 마이그레이션 SQL·Flyway 버전·MySQL 이미지/설정별로 캐시를 구분하고 사례마다 변경 데이터는 격리해야 한다. 정상 경로 하나는 빈 DB→최신 전체 이행을 계속 검증한다. 검증하지 않은 dump나 baseline 처리로 실제 이전 스키마 재현을 대체하지 않는다.

## 6. 컨텍스트와 병렬화

Spring 기동은 Order 11회·Payment 16회 관측됐다. Mock/Spy·DynamicPropertySource·프로퍼티 조합은 컨텍스트 캐시 키에 영향을 준다. 구성의 의미가 같은 그룹부터 이름과 공통 설정을 정리할 수 있다. 캐시 hit/miss 로그는 아직 수집하지 않았으므로 정확한 중복 원인은 후속 계측이 필요하다. [Spring 6.2 컨텍스트 캐시](https://docs.spring.io/spring-framework/reference/6.2/testing/testcontext-framework/ctx-management/caching.html)

PaymentIntegrationTestBase는 JWT 설정과 함께 ExternalPgProcess를 연결한다. DB/만료 통지만 확인하는 사례도 이 베이스를 상속하면 별도 PG MySQL·JVM이 준비될 수 있다. 인증 지원과 실제 공급자 프로세스 지원을 분리하고, PG를 호출하지 않는 검증은 실제 호출이 없음을 확인하는 대역을 쓴다. 실제 PG HTTP·재시작·응답 유실 계약 검사는 독립 프로세스로 유지한다.

현재 테스트는 테이블 전체 DELETE, 고정 consumer group, 정적 HTTP 상태와 Mock/Spy를 공유한다. 우선 메서드/클래스 병렬도를 일괄 올리지 않는다. Spring도 이러한 공유 상태와 Mock/Spy를 병렬 실행 시 주의 대상으로 명시한다. [Spring 6.2 병렬 테스트 지침](https://docs.spring.io/spring-framework/reference/6.2/testing/testcontext-framework/parallel-test-execution.html)

fork를 늘리면 JVM별 컨테이너·컨텍스트 준비 비용도 늘고, 현재 throttle은 태스크 수만 제한한다. 먼저 모듈 단위 병렬화를 유지하고, 이후 worker별 DB·topic·파일·공급자 로그 격리를 갖춘 그룹에서 측정한다. 현재 관측된 컨텍스트 수만을 근거로 캐시 최대 크기를 늘리는 것은 우선하지 않는다.

## 7. 첨부 전략의 현재 적용 판단

| 제안 | 현재 판단 |
|---|---|
| 변경 기반 선택/위험도 기반 게이트 | 즉시 유용. 모듈·파일 유형·계약 소비자를 함께 본다. 불명확하면 범위를 확대 |
| 빌드 캐시 | 이미 있음. 재사용 확대 전에 외부 입력 누락과 과도한 classpath를 수정 |
| Fail-fast·실패 우선 | 로컬 반복에 적용 가능. 실패한 메서드/클래스부터 실행하고 완료 범위를 명시 |
| 초기화 비용/테스트 피라미드 | DB 이행 분리·컨텍스트/PG 지원 분리가 현재 우선 |
| Contract Testing | 기존 이벤트/HTTP 계약·루프백 테스트를 활용. 실제 DB 원자성·최소 전체 여정도 유지 |
| 파라미터화/중복 정리 | 입력이 다른 이행 검증을 삭제하지 않음. 줄 수 감소와 실행 비용 감소를 구분 |
| 병렬/sharding | 격리·자원 예산 정비 후 측정. 태스크/프로세스 수 증가만으로 안전한 단축을 보장하지 않음 |
| Coverage TIA·AI 선택 | 실행 관계 데이터가 없어 후순위. SQL·동적 빈·이벤트 계약은 별도 영향 규칙 필요 |
| Mutation/Property-based | PIT는 이미 별도 존재. 중요한 불변 조건의 검출력 확인에 쓰고 매 수정 기본 경로에 추가하지 않음 |
| Flaky 관리 | 재시도로 녹색을 만들지 않음. 테스트 대기 오류·시간대·업무 결함을 구분해 원자료 보존 |

Gradle 모듈/테스트 필터로 가능한 최적화를 먼저 적용한다. Nx/Bazel 같은 별도 빌드 체계 도입이 현재 병목 해결의 선행 조건이라는 근거는 없다.

## 8. 적용 시 검증 범위와 기록

- 순수 규칙 수정: 관련 단위와 해당 모듈 아키텍처 → 관련 DB/HTTP 경계.
- 승인·환불·권한·가격·멱등 변경: 원구매/권한/원자성/경합/재시작 등 영향을 받는 계약까지 확대하고 최종 모듈 게이트 실행.
- 스키마·이관·테스트 실행 설정 변경: 해당 이행/제약·실제 실행·잘못된 캐시 재사용 여부 확인.
- 공유 이벤트/보안/설정 변경: 생산자·소비자·영향 모듈을 함께 포함. 정적 Gradle 그래프만으로 업무 영향이 전부 잡힌다고 보지 않음.
- 최종 성공 결과 뒤 수정: 어떤 입력/보장이 바뀌었는지 먼저 판단하고 무효화된 검증만 다시 실행. 운영 코드·픽스처 변경과 단위 테스트 설명 추가를 구분.

현재 미커밋 작업이 넓게 누적돼 있어 git diff HEAD만 보면 과거에 검증한 변경까지 매번 포함된다. 마지막 성공의 소스 해시·검증 범위를 함께 사용할 가치가 있다. 단, 기존 source-sha256.json만으로 환경·빌드·외부 파일 입력까지 검증됐다고 보지 않는다. 태스크/필터·입력·JDK/Gradle·실행 환경·수정/재실행 이유를 함께 기록한다.

## 9. 이번 계측 도구와 다음 실행

[비용 집계 도구](../tools/verification/report-test-costs.py)는 기존 XML/로그를 읽기만 한다. 테스트를 실행하거나 자동으로 제외하지 않는다. 빈 결과는 오류로 처리하고 실패/스킵 수도 보존한다. 실제 보존된 XML로 수치 일치를 확인했고, 임시 XML로 빈 입력 거절·실패 보존·이행 후보 분류를 확인했다.

```bash
python3 tools/verification/report-test-costs.py \
  --suite order.integration=runs/order-expiry-propagation-20261029/final/order/integrationTest \
  --suite payment.integration=runs/order-expiry-propagation-20261029/final/payment/integrationTest \
  --output runs/test-verification-audit-20261029/metrics.json
```

이번 분석 원자료는 `runs/test-verification-audit-20261029/{metrics.json,gradle-inputs.json}`이다. 1초짜리 메타데이터 조회의 BUILD SUCCESSFUL은 애플리케이션 테스트 성공이 아니다. 이행 비중 계산·기동 로그 집계와 실제 단축률을 구분한다.

첫 구현 범위로 **외부 파일 입력 선언 + Order/Payment 공유 픽스처 분리**를 권한다. 이후 **migrationTest 분리와 최종 게이트 보존**을 연결한다. 변경 전후 같은 코드/환경에서 첫 실행과 재사용 실행을 구분해 비교하고, 단위만 수정했을 때 불필요 통합 재실행이 줄었는지 확인한다. 목표 시간·단축률은 이 실험 후 확정한다.

## 10. 사용자 승인 후 적용 — 2026-10-29

기존 작업을 기능·기록·진단의 네 커밋으로 저장하고 `origin/main`에 푸시한 뒤 개선을 시작했다. 이 단계는 주문·결제 정책을 변경하지 않는다.

### 적용 범위

- Order·Payment의 공유 Fixture 네 파일을 `src/testFixtures`로 이동했다. 두 모듈의 통합 classpath에서 일반 단위 테스트 출력을 제거했다. 다른 모듈의 Fixture 분리는 이번 범위에 포함하지 않았다.
- 두 모듈은 plain JAR를 만들지 않으므로 Fixture와 단위 테스트가 `main.output`을 직접 참조한다. ArchUnit은 표준 `DoNotIncludeGradleTestFixtures`로 Fixture 산출물만 제외한다. 운영 클래스의 기존 아키텍처 규칙은 유지한다. [ArchUnit 1.3.0 옵션 정의](https://raw.githubusercontent.com/TNG/ArchUnit/v1.3.0/archunit/src/main/java/com/tngtech/archunit/core/importer/ImportOption.java)
- 과거 이행 사례에 `migration` 태그를 부여했다. `integrationTest`는 이 태그를 제외하고 새 `migrationTest`가 전담한다. `check`에는 두 태스크가 모두 연결되며 `build`의 필수 검증도 유지한다. 알려진 결함의 별도 실행과 PIT의 두 테스트 소스셋은 유지한다.
- Payment의 두 태스크는 별도 프로세스 PG JAR 내용을 파일 입력으로 추적한다. Order·Payment의 외부 SQL 및 Payment의 Python importer는 `migrationTest`, Catalog의 외부 SQL은 기존 `integrationTest` 입력으로 선언했다. 공급자 JAR는 테스트 실행 classpath에 추가하지 않았다.
- Payment의 JWT/DB/Kafka 지원과 독립 공급자 기동 지원을 분리했다. PG가 필요 없는 OrderExpiry 테스트는 DB 지원만 사용하고, PG 대역에 호출이 전혀 없음을 매 사례 확인한다. 기존 실제 공급자 HTTP·재시작 검사는 공급자 지원 베이스를 그대로 사용한다.
- CI의 실제 실행 강제·기존 인프라 동시 태스크 제한·공유 상태의 직렬 실행은 유지한다. 별도의 CI/예약 실행을 생성하지 않았다.

### 실행 방법

OrbStack 엔드포인트를 `DOCKER_HOST`로 지정한 로컬 셸에서 실행한다. 빠른 단계가 성공한 뒤 다음 단계로 진행한다. 같은 모듈의 Gradle 명령을 동시에 실행하지 않는다.

```bash
# 인프라 없이 먼저 확인
./gradlew :apps:order:test :apps:payment:test --console=plain

# 개발 중 현재 스키마의 업무·경계 반복 검증
./gradlew :apps:order:integrationTest :apps:payment:integrationTest --console=plain

# 스키마/이관 수정 시 또는 이행 사례의 집중 검증
./gradlew :apps:order:migrationTest :apps:payment:migrationTest --console=plain

# 완료 전 두 종류를 포함한 최종 검증·산출물
./gradlew :apps:order:check :apps:payment:check \
  :apps:order:bootJar :apps:payment:bootJar --console=plain

# 테스트 실행 없이 실제 Gradle 입력·태그·최종 게이트 계약 확인
./gradlew --offline -I tools/verification/verify-test-boundaries.gradle verifyTestBoundaries
```

로컬 실패의 수정 반복에는 태스크의 `--tests`·`--fail-fast`를 사용하고 부분 결과로 기록한다. 최종 성공 뒤 일반 단위 테스트만 수정한 경우 통합 전체를 반복하기 전에 입력 영향부터 확인한다.

### 검증과 비용 해석

- 도입 중 빠른 컴파일에서 plain JAR 비활성화에 따른 main 클래스 경로 누락을 확인하고 출력 경로를 명시했다. 이후 단위 검사에서 Fixture의 ArchUnit 오인식을 확인하고 표준 제외 옵션을 적용했다. 이 실패들은 업무 규칙 TDD Red와 구분한다.
- 빠른 전체 단위·아키텍처와 통합 컴파일, 실제 Gradle 경계 계약 검사가 통과했다. 경계 검사에 단위 출력을 의도적으로 다시 넣은 음성 대조는 실제 실패했다. 검사를 실행하지 않고 통과하는 공허한 진단과 구분한다.
- 개발 중 반복 실행에서 역사 이행 비용은 빠지지만, 최종 `check`에서는 새 JVM/컨테이너 기동 비용이 추가될 수 있다. 전체 빌드의 단축을 보장하는 변경으로 해석하지 않는다.
- 첫 단계는 같은 통합 소스셋 안의 태그 분리다. 통합 테스트 클래스나 공유 Fixture가 바뀌면 두 태스크의 입력이 함께 달라질 수 있다. 메서드 단위 캐시나 자동 영향 분석을 구현한 것은 아니며, 일반 단위 테스트 출력의 결합을 제거한 효과와 구분한다.
- 최종 결과·테스트 사례 보존 대조·입력 무효화 확인은 `runs/test-verification-optimization-20261029`와 아래 완료 기록에 남긴다.
