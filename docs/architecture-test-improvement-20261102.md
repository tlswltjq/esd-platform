# 아키텍처 검사 정비

2026-11-02. 사용자가 수정 전 점검 결과의 권장안에 맞춘 테스트 수정을 요청해 적용했다. 기존 업무 책임·API·DB·이벤트 계약은 유지하고 테스트 전용 공통 규칙과 앱의 검사 등록, Settlement Kafka 계약 검증을 변경했다.

## 수정 전 점검 요약 (흡수)

이 문서가 대체한 별도의 수정 전 점검 기록을 여기에 합쳤다. 기준 코드는 `4e22870`이며 당시 등록된 검사 **477건**이 통과했다. 별도 진단 17건으로 **과도한 제약 3유형**(닫힌 동사 이름 목록, config→inbound 거절, 루트 포트만 검사)과 **검사 공백 8유형**(포트 하위 패키지, 제네릭·배열 이벤트 입력, Executor 하위 타입, 앱 간 직접 의존, 소비자 그룹 개별 대응 등)을 재현했다. 당시 판단은 "현재 구조의 큰 틀을 유지하고 검사 규칙·등록 범위만 정비"였고, 업무 코드·아키텍처 규칙·테스트 등록은 그 시점에 바꾸지 않았다. 아래 "적용한 기준"이 이 권장안의 실제 적용 결과다. 수정 전 점검 당시 먼저 커밋한 작업별 단위(정산 책임 분리 V8, PortOne 외부 혜택 V9, 환불 원장 V10, 토스페이 수집 V11 등)는 각 커밋과 해당 완료 문서에 보존돼 있다.

## 적용한 기준

| 영역 | 적용 내용 | 함께 보존한 대조 |
|---|---|---|
| 등록 | 11개 앱이 `AppArchitectureRules`로 패키지·위생·이벤트 입력/순서·그룹 규칙을 함께 등록한다. Auth 기존 설정 경계는 새 `AuthArchitectureTest`에 포함한다. | Payment의 별도 core 추적 라이브러리 규칙, 테스트/Gradle Fixture 제외를 유지한다. 앱 전체 import가 비면 실패하고, 역할에 없는 개별 계층은 빈 대상을 허용한다. |
| 이름·보조 타입 | 닫힌 동사 목록을 제거하고 private 구현 타입을 제외한 서비스 타입의 PascalCase만 검사한다. 동사+대상의 의미는 작성 지침과 코드 검토로 확인한다. | `CollectRows`·private `RowKey` 허용, 소문자로 시작하는 공개 타입 거절. 기존 컨트롤러·리스너·리포지토리 이름 규칙은 유지한다. |
| 객체 조립 | Config → Inbound 의존을 허용하고 앱의 Bean 팩토리는 config에 둔다. | Core → Inbound·Adapter 의존은 계속 거절한다. core·controller·adapter의 Bean 팩토리를 각각 거절하고 config 팩토리는 허용한다. 공통 AutoConfiguration의 조립 경계는 바꾸지 않는다. |
| 포트 | `.core.port`뿐 아니라 그 하위 패키지의 포트 구현 위치도 검사한다. | 루트 포트의 기존 정상/위반 사례, 하위 포트의 core 구현 거절·adapter 구현 허용을 함께 확인한다. |
| 이벤트 입력 | 메서드 인자의 제네릭 구성 타입·배열 요소까지 검사한다. | 직접·List·배열·중첩 Map/Optional/List·wildcard 상하한·타입 변수·제네릭 배열을 각각 확인한다. 내부 입력·공유 스냅샷·이벤트 반환과 재귀 타입 변수는 허용한다. |
| 순서 | 클래스 수준 Async를 거절하고 Executor 하위 타입까지 검사한다. | 직접 Executor·메서드 Async 거절, 동기 리스너 허용을 유지한다. 논블로킹 재시도 금지도 유지한다. |
| 트랜잭션 | 인바운드의 TransactionOperations/TransactionManager 계열 의존을 거절한다. | controller의 TransactionTemplate·listener의 매니저·scheduler의 인터페이스 사용을 각각 거절한다. 서비스와 OAuth 프로토콜 어댑터의 프로그램 방식 트랜잭션은 허용한다. Auth의 실제 어댑터도 앱 구조 검사에 포함한다. |
| 앱 간 경계 | 다른 `com.stove.<앱>` 구현으로의 직접 코드 의존을 거절한다. | 다른 앱을 검사 대상에 직접 import하지 않아도 위반을 잡는다. 자기 앱과 `com.stove.common` 루트/하위 계약은 허용한다. 앱 목록을 하드코딩하지 않는다. |
| 소비자 그룹 | 기존 그룹 집합 검사를 유지하고 Settlement의 개별 리스너/Inbox 대응을 실제 Kafka·MySQL로 확인한다. | OrderPaid와 RefundPaymentConfirmed를 소비한 뒤 각 사건의 Inbox 키를 해당 토픽의 실행 리스너 그룹과 대조한다. |

Config → Inbound 허용은 config의 객체 조립 책임에 따른 것이다. config에 업무 처리를 옮기라는 뜻이 아니다. 또한 트랜잭션 검사는 인바운드 업무 조율을 대상으로 하며 Auth OAuth 저장 책임까지 포괄 금지하지 않는다.

`PgStatementRowKey`나 기존 타이머 구현은 옮기지 않았다. 검사 수정만을 이유로 업무 타입을 이동하거나 서비스를 더 분해하지 않았다.

## 검증 방법과 범위

모든 빌드·테스트는 `ssh sng`의 `/tmp/esd-architecture-rules-20261102.c7xnRH1d/work`에서 수행했다. 소스 전송 대상은 Git 추적/신규 코드와 빌드 입력 **1,304개**이며 전후 해시를 확인했다. 비밀 설정·Git 이력·문서는 제외했다. Testcontainers가 새 MySQL/Kafka와 임의 포트를 사용하고 공유 서비스·기존 검증 디렉터리는 보존했다.

규칙 수정 전에 새 회귀 검사 21건을 기존 규칙에 실행해 **11건 실패**를 확인했다. 이름·private 보조 타입·config 조립과 포트 하위 패키지·Executor 하위 타입·6가지 이벤트 포장 형태가 실패했다. 직접 이벤트·타입 변수 입력과 기존 정상/거절 대조는 통과했다. 이 Red 횟수를 최종 성공 사례 수에 합산하지 않는다.

최초 수정본은 ArchUnit 1.3.0에 없는 `haveSimpleNameMatching` 호출로 컴파일에 실패했다. 기존 방식의 작은 `ArchCondition`으로 고쳐 같은 버전에서 실행했다. 라이브러리를 올리거나 실패 사례를 제외하지 않았다.

| 실행 | 결과 |
|---|---|
| 최종 11개 앱 구조 검사 | **518건**, 실패·오류·건너뜀 0. 각 앱 47건, Payment는 기존 추가 규칙을 포함해 48건 |
| 최종 공통 규칙 자체 검사 | **67건**, 실패·오류·건너뜀 0. 기존 31건과 신규 회귀 36건 |
| 공통 Messaging·Security·Web 구조 검사 | **30건**, 실패·오류·건너뜀 0 |
| 위 세 범위 합계·실행 의존성 | **615건**과 `verifyCommonRuntimeDependencies`, `:common:archunit:check` 통과 |
| Settlement 집중 Kafka/DB 계약 | **3건** 통과. 새 리스너/Inbox 대응과 기존 매출 DLT 원본 재처리·환불 역순/중복 재처리 |
| 두 리스너의 그룹을 교환한 반례 | 정적 구조 **47건은 통과**, 새 실제 계약 **1건은 그룹 불일치로 실패**. 이 실패가 예상 결과임을 XML로 대조 |
| 반례 원복 뒤 최종 게이트 | 위 615건과 새 실제 계약 **1건 재검증** 통과. 전체 명령 1분 35초 |

그룹 교환은 OCI의 격리 사본에만 적용하고 `finally`에서 두 소스를 복원했다. 정상 데이터의 실제 소비·업무 저장 뒤 `processed_event.consumer_group`을 해당 토픽의 실행 리스너 그룹과 비교한다. 그룹 값 집합이 같아도 개별 대응이 틀릴 수 있다는 점을 실제 실패로 확인했다. 최종 615건에 이전 성공 실행 614건을 더하지 않으며 Kafka 3건에 원복 뒤 같은 계약 1건을 중복 합산하지 않는다.

모든 Test 태스크는 `CI=true`로 실제 실행했고 컴파일/산출물 캐시는 허용했다. 공통 루트 패키지를 앱으로 오인하지 않는 정상 대조를 추가한 뒤 최종 검사 전체를 확인했다. 업무·공통 실행 소스/리소스 **885개는 수정 전과 동일**하다. 공유 컨테이너 **23개**의 ID·시작 시각·재시작·실행/건강 상태·마운트와 볼륨 **57개**가 전후 동일하다. 종료 직후 보였던 정리 컨테이너 1개도 자동 종료한 뒤 재대조했으며 새 잔여 자원은 없다.

커밋 전 신규 Java 테스트 파일 28개의 EOF 빈 줄만 정리한 뒤 공통 규칙 67건을 다시 실행해 통과했다. 테스트 클래스 파일 **70개의 바이트코드가 전후 동일**함을 확인했으므로 앱 검사와 Kafka 계약은 반복하지 않았다. 실행 당시의 `final-source-manifest.json`과 서식 정리 후의 `formatted-source-manifest.json`·`formatting-only.json`·`formatting-verification.json`을 구분해 보존했다.

다음은 격리 작업 디렉터리에서 아키텍처 범위를 재실행하는 명령이다. 실제 최종 게이트에는 Kafka 대응 계약 재검증도 포함했으며 전체 명령은 원자료의 `final-command.json`에 보존했다.

```bash
CI=true ./gradlew \
  :common:archunit:test \
  :common:messaging:test --tests '*ArchitectureTest' \
  :common:security:test --tests '*ArchitectureTest' \
  :common:web:test --tests '*ArchitectureTest' \
  :apps:auth:test --tests '*ArchitectureTest' \
  :apps:catalog:test --tests '*ArchitectureTest' \
  :apps:download:test --tests '*ArchitectureTest' \
  :apps:gateway:test --tests '*ArchitectureTest' \
  :apps:license:test --tests '*ArchitectureTest' \
  :apps:order:test --tests '*ArchitectureTest' \
  :apps:payment:test --tests '*ArchitectureTest' \
  :apps:review:test --tests '*ArchitectureTest' \
  :apps:settlement:test --tests '*ArchitectureTest' \
  :apps:store:test --tests '*ArchitectureTest' \
  :apps:studio:test --tests '*ArchitectureTest' \
  verifyCommonRuntimeDependencies :common:archunit:check --console=plain
```

원자료는 Git 제외 `runs/architecture-rules-20261102/`와 위 OCI 디렉터리의 소스 manifest·실행 명령 JSON·로그·Red/최종/그룹 교환 XML·전후 자원 스냅샷이다. 이전 점검 477건 및 진단 17건과 이번 수정 후 실행을 구분한다.

이 검사는 코드 구조를 보장하는 범위다. 입력 값 검증·업무 원자성·외부 호출 위치와 실제 금융 처리를 모두 증명하지 않는다. 기존 단위·DB/Kafka 계약과 최종 check의 통합/이행 포함 정책을 유지한다. 변경하지 않은 업무 전체 check·PG 여정·배포는 이번에 반복하지 않는다. 그룹 개별 대응을 새로 실행한 범위는 Settlement의 두 리스너이며 모든 앱의 모든 리스너를 검증한 것으로 확대하지 않는다.
