# infrastructure → adapter 패키지 변경 계획·완료 기록

[문서 목록](README.md) · [작업 기준](../AGENTS.md) · [유스케이스 작성 규칙](use-case-conventions.md) · [아키텍처](architecture.md)

2026-10-18 작성·실행 완료. **패키지 이동·경계 검사 변경과 로컬 OrbStack 회귀 검증을 마쳤다.**
외부 연동 구현의 역할을 이름으로 드러내도록 Java 패키지의 `infrastructure`를 `adapter`로 변경한다.
기존 하위 패키지와 클래스 이름을 유지하는 일괄 명칭 변경으로 진행한다.

## 목표와 변경 규칙

- `core.port`는 호출 모듈이 필요로 하는 외부 기능의 계약을 소유한다.
- `adapter`는 다른 업무 서비스의 HTTP 호출, S3 저장소, PG 및 프레임워크 연동 구현을 담는다.
- `config`는 설정 바인딩·클라이언트 구성·객체 조립을 담당한다.
- 현재 `core`·`api`·`config`의 책임과 의존 방향을 유지한다.
- 클래스 이름·공개 메서드·포트 계약·업무 처리는 유지하고 패키지 선언·경로·참조를 함께 바꾼다.

변경 예시는 다음과 같다.

```text
com.stove.download.infrastructure.LicenseHttpClient
→ com.stove.download.adapter.LicenseHttpClient

com.stove.download.infrastructure.storage.S3BuildStorage
→ com.stove.download.adapter.storage.S3BuildStorage

com.stove.auth.infrastructure.oauth.RotatingRefreshProvider
→ com.stove.auth.adapter.oauth.RotatingRefreshProvider
```

`oauth`, `security`, `storage`, `pg`, `tax` 등 기존 하위 구조를 유지한다.
루트에 있는 HTTP 클라이언트·보조 클래스도 같은 위치 관계로 이동한다.
연결 도메인별 하위 패키지, `adapter.in/out`, 새 포트·공통 어댑터 계층은 추가하지 않는다.
Auth의 프레임워크 SPI 구현에 이번 이름 변경만을 이유로 별도 업무 포트를 도입하지 않는다.

## 조사한 적용 범위

현재 작업본 기준 실제 이동 대상은 **앱 운영 파일 13개·앱 테스트 파일 5개·공통 위반 픽스처 3개**다.
이동 외에 설정 조립·테스트의 import/완전한 클래스 이름과 공통 검사 규칙을 수정한다.
구현 시작 시 파일 목록을 다시 확인한다.

| 모듈 | 운영 파일 | 테스트 경로 이동 | 유지할 하위 구조 |
|---|---:|---:|---|
| Auth | 3 | 0 | oauth, security |
| Studio | 1 | 0 | storage |
| Review | 1 | 2 | storage |
| Store | 1 | 1 | 루트 |
| Order | 1 | 1 | 루트 |
| Payment | 1 | 0 | pg |
| Download | 4 | 1 | 루트, storage |
| Settlement | 1 | 0 | tax |
| 합계 | 13 | 5 | 기존 구조 유지 |

Gateway·Catalog·License에는 현재 이동할 운영 파일이 없다. 공통 규칙을 사용하는 아키텍처 검증에는 포함한다.
Settlement의 MockTaxInvoiceIssuer도 동일하게 패키지만 이동한다. 이는 공통 패키지 규칙을 일관되게 적용하는 작업이며,
Settlement의 업무 재작성·계약 변경·완료 검증은 후속 작업으로 남긴다.
`common/archunit`의 Mock·Stub·Fake 위반 픽스처 3개도 `adapter`로 이동한다.

## 함께 변경할 참조와 검사 규칙

| 대상 | 작업 |
|---|---|
| 앱 소스·테스트 | 디렉터리·package·import·코드 안의 완전한 클래스 이름 변경 |
| config | Auth 보안, 저장소·HTTP·PG Bean 조립의 구현체 참조 변경 |
| 검증 픽스처 | PaymentFaultConfiguration, TokenRotationIntegrationTest 등 직접 구현체를 참조하는 코드 변경 |
| ModulePackageRules | 허용 패키지·계층 이름·의존 방향·core 접근 금지·포트 구현 위치를 adapter 기준으로 변경 |
| ModuleHygieneRules | 스텁 격리 대상 패키지·관련 상수·설명 변경 |
| 공통 규칙 검사 | 규칙 필드 이름 참조·픽스처 이동과 새 패키지의 정상/위반 판정 확인 |
| 실행 참조 | 테스트 필터·리플렉션·클래스 이름 문자열·리소스·빌드 스크립트의 잔여 참조 조사·변경 |
| 현행 문서 | 규약·아키텍처·서비스 설명·현재 모델 및 테스트 링크 변경 |

의존 방향은 `core.service → core.port ← adapter`를 유지한다.
구현체의 외부 조립은 config가 맡고 core는 api·adapter·config를 참조하지 않는다.
규칙의 `Infrastructure` 계층 이름과 상수·규칙 필드 이름도 `Adapter`/`ADAPTER` 및 대응 이름으로 정리한다.
최종 공통 규칙은 `adapter`를 기준으로 검사하며 두 패키지를 동시에 허용하는 유예 규칙은 두지 않는다.

ArchUnit에는 빈 검사 대상을 허용하는 규칙이 있으므로 실행 성공만으로 패키지 전환을 판단하지 않는다.
기존 Mock·Stub·Fake 세 접두사 검사가 새 위치의 위반을 각각 잡는지 확인하고,
포트 구현의 정상 위치·core의 구현체 직접 의존·config 외 계층의 구현체 접근을 대표 픽스처로 검증한다.
이름 변경을 위해 기존 경계 규칙을 느슨하게 만들지 않는다.

## 기존 작업본과 문서 처리

현재 작업 트리에는 완료된 Download 재작업의 수정·삭제·미추적 파일과 별도 스테이징 상태가 있다.
**현재 파일 시스템의 작업본을 변경 기준으로 사용하고 기존 변경과 스테이징 상태를 보존한다.**

- 실행 전에 변경 파일·미추적 파일·이동 전후 대응 목록과 기존 diff를 `runs/`에 기록한다.
- 추적 여부와 관계없이 현재 존재하는 대상 파일을 이동한다. 이미 삭제한 구 Download 구현을 복원하지 않는다.
- 파일 이동·참조 교체와 기존 업무 수정이 섞였는지 대조한다. 이번 변경을 자동으로 stage·commit하지 않는다.
- 업무 소스는 패키지·참조 치환을 정규화해 이동 전 내용과 비교한다. 추가 업무 변경이 발견되면 같은 변경으로 섞지 않고 원인을 확인한다.
- 현재 기준의 `AGENTS.md`, `docs/use-case-conventions.md`, `docs/architecture.md`, `docs/services.md`와 모델 링크를 갱신한다.
- 과거 계획·결정·완료 기록의 당시 패키지 이름·명령·검증 수치는 보존한다. 현재 소스로 연결하는 링크만 새 위치로 맞추고 필요하면 변경 기록을 연결한다.
- `infra/`, InfraContainers, Testcontainers 등 실제 인프라와 라이브러리의 이름은 Java 업무 패키지 변경 대상과 구분한다.

HTTP API·OpenAPI·이벤트·DB/Flyway·설정 키·Bean 이름·스토리지 경로는 기존 계약을 유지한다.
패키지 이름을 저장 값·리플렉션·직렬화 타입 식별자로 쓰는 지점이 있는지도 확인하고 해당 참조의 읽기 동작을 검증한다.
기존 클래스 이름을 남기는 호환 래퍼나 이중 구현은 도입하지 않는다.

## 진행 순서

1. **기준 기록:** 현재 변경·파일 목록을 보존하고 이동 대상·참조 목록을 확정한다.
2. **일괄 이동:** 앱 운영/테스트·공통 픽스처 경로와 package·import·완전한 클래스 이름을 함께 변경한다.
3. **공통 규칙:** adapter 패키지·계층 이름을 적용하고 실제 정상/위반 판정이 유지되는지 검사한다.
4. **문서·잔여 참조:** 현행 규약·링크를 갱신하고 실행 코드의 구 패키지 참조가 없는지 확인한다.
5. **OrbStack 검증:** 아래 컴파일·단위·관련 통합·실제 흐름·운영 JAR 검사를 수행한다.
6. **완료 기록:** 실제 명령·건수·증거·정리 결과를 이 문서에 추가하고 현행 작업 기준을 확정한다.

공통 규칙과 앱 소스를 맞춘 전체 작업본을 검증 단위로 삼는다.
이 작업은 패키지 명칭 정리이며 다음 업무 모듈 재작성 대상은 계속 Settlement다.

## 로컬 OrbStack 검증 계획

| 검증 | 대상·판정 |
|---|---|
| 정적 점검 | 운영/테스트 package·import·완전한 클래스 이름에 구 infrastructure 참조 없음, 이동 대응·업무 본문 유지 |
| 전체 컴파일 | 모든 앱·공통·E2E의 운영·단위·통합 소스 컴파일. Settlement는 컴파일까지만 포함 |
| 단위·아키텍처 | 완료된 열 앱·공통 모듈의 기존 검사, adapter 정상/위반 픽스처 및 스텁 세 접두사 판정 |
| 관련 통합 | Auth·Studio·Review·Store·Order·Payment·Download의 빈 조립·기존 HTTP/저장소/PG/인증 동작 |
| 공개 계약 | 기존 OpenAPI를 갱신 옵션 없이 대조. API·응답·이벤트 계약 유지 |
| 실제 흐름 | 기존 verify-download.py로 가입·심사·출시·구매·지급·실제 ZIP·만료·장애·재시작 확인 |
| 운영 JAR | 이동 대상 앱을 재빌드하고 새 adapter 클래스 포함·구 infrastructure 클래스 부재 확인 |

새 업무 테스트를 일괄 추가하지 않고 기존 검증을 활용한다. 공통 규칙의 패키지 판정 공백은 대표 검사로 보완한다.
단위 검사는 완료된 Auth·Gateway·Studio·Review·Catalog·Store·Order·Payment·License·Download와 공통 모듈을 대상으로 한다.
실제 열 앱 흐름으로 이전한 HTTP 클라이언트·PG·S3·인증 구현의 조립과 호출을 함께 확인한다.
Settlement의 기존 업무 실행이나 재작성 전 전체 E2E 성공은 완료 조건으로 삼지 않는다.

아래는 예정 명령이며 실행 결과가 아니다. 완료 모듈 단위 검사·대상 bootJar 명령은 실행 시 함께 기록한다.

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
export STOVE_TEST_MINIO_IMAGE=stove/minio:RELEASE.2025-04-22T22-12-26Z-local

./gradlew classes testClasses integrationTestClasses \
  :common:archunit:test verifyCommonRuntimeDependencies --no-daemon

./gradlew :apps:auth:integrationTest :apps:studio:integrationTest \
  :apps:review:integrationTest :apps:store:integrationTest \
  :apps:order:integrationTest :apps:payment:integrationTest \
  :apps:download:integrationTest -Pstove.infraTest.maxParallel=1 --no-daemon

python3 scripts/verify-download.py
```

검증은 기존 OrbStack을 사용하되 전용 포트·DB·컨테이너·버킷으로 격리하고 실행 전후 공유 자원을 대조한다.
소스 이동 전 자료·실행 명령·단계별 JUnit XML·JAR 클래스 목록을 별도 `runs/`에 보존한다.
과거 Download의 단위 961건·관련 통합 67건·실제 15단계 통과는 이번 이름 변경의 검증 결과로 재사용하지 않는다.

완료 조건은 기존 변경 보존, 일관된 패키지·참조 변경, 경계 규칙 유지, 예정 검증 통과와 실제 결과 기록이다.

## 완료 기록 — 2026-10-18

### 적용 결과

- 운영 파일 13개·앱 테스트 5개·공통 스텁 위반 픽스처 3개를 이동했다. `oauth`, `security`, `storage`, `pg`, `tax`와 클래스 이름을 유지했다.
- 설정 조립·테스트의 import 및 완전한 클래스 이름, ArchUnit 패키지·계층·규칙 필드 이름을 `adapter`로 맞췄다. 실행 코드·빌드·실행 설정의 구 패키지 참조는 0건이다.
- 공통 검사에 대표 픽스처 7개와 검사 4건을 추가했다. 포트 구현의 adapter 배치와 config 조립·core의 포트 사용은 허용하고, core의 직접 구현체 의존·컨트롤러의 직접 접근·core에 둔 포트 구현은 거부한다. Mock·Stub·Fake의 조건 없는 구현도 각각 거부하는 기존 검사를 통과했다.
- `AGENTS.md`·현행 규약·아키텍처·서비스 설명·모델 링크를 갱신하고 [결정 26](decisions.md#26-외부-연동-구현-패키지를-adapter로-부른다)을 추가했다. 과거 검증 기록의 명칭·수치·명령은 보존했다.
- 이동 전 기존 파일 787개의 작업본·변경 목록·미추적 목록·staged/unstaged diff를 기록했다. 기존 Java 소스 525개를 치환 기준으로 대조했으며 앱 업무 본문은 동일하다. 공통 규칙 검사 추가를 제외한 Java 변경도 명칭 치환과 일치한다.
- 리소스 114개를 그대로 유지했다. 여기에는 OpenAPI 스냅샷 9개와 Flyway 파일 85개가 포함된다. 기존 Download 작업과 별도 스테이징 상태도 유지했다.

### 로컬 OrbStack 검증 결과

Java 21·Gradle 8.14.3·OrbStack Docker를 사용했다. 테스트는 `CI=true`로 결과 캐시를 사용하지 않았고, 통합 테스트 동시 실행 수는 1로 제한했다. OpenAPI 갱신 옵션은 사용하지 않았다.

| 검증 | 실제 결과 |
|---|---|
| 전체 컴파일·공통 런타임 의존 검사 | 전체 앱·공통·E2E 운영/단위/통합 소스 컴파일 및 `verifyCommonRuntimeDependencies` 통과. Settlement는 컴파일·패키징만 확인 |
| 완료 모듈·공통 단위/아키텍처 | **965건 통과**, 실패·오류·건너뛰기 0. 공통 ArchUnit 31건에 신규 경계 검사 4건 포함 |
| 관련 통합 | **101건 통과**, 실패·오류·건너뛰기 0. Auth 8·Studio 6·Review 12·Store 10·Order 35·Payment 22·Download 8 |
| 공개 계약 | 관련 통합 검사의 기존 OpenAPI 대조 통과, 스냅샷 변경 없음 |
| 운영 JAR | Auth·Studio·Review·Store·Order·Payment·Download·Settlement **8개** 재빌드. adapter 클래스 14개(중첩 클래스 포함), 구 infrastructure 클래스 0개 |
| Download 의존성 | 운영 JAR에 Kafka·MongoDB·JPA 및 공통 Messaging 라이브러리 없음 |
| 실제 열 앱 흐름 | `scripts/verify-download.py` **15단계 통과**. 실제 ZIP·체크섬, 기존 보유권의 공개 빌드 교체, 권한 차단, URL 만료·재발급, License/Catalog/저장소 장애, 파일 누락·변경·복구, Download 재시작 확인 |
| 소스·작업본 보존 | 정규화 대조·리소스 동일성·기존 staged diff 동일성·`git diff --check` 통과 |
| 실행 자원 정리 | 검증 전후 실행 중 컨테이너 목록 동일. 검증용 앱 10개의 포트 해제 확인 |

공통 JPA·Testcontainers의 `test`는 소스가 없는 태스크이며 위 검사 건수에 포함하지 않았다. Settlement 업무 재작성·실행 검증은 이번 완료 범위에 포함하지 않는다.

### 명령과 증거

이번 실행 자료는 [별도 검증 폴더](../runs/adapter-rename-20261018-150523/)에 보존했다. 이전 Download 검증 폴더는 덮어쓰지 않았다.

- `compile-command.json`·`unit-command.json`·`integration-command.json`·`jars-command.json`: 실제 Gradle 명령 원문. 공통 옵션은 `-Pstove.infraTest.maxParallel=1 --offline --no-daemon`이다.
- `compile.log`·`unit.log`·`integration.log`·`jars.log`: 단계별 실행 로그.
- `apps/`·`common/` 아래의 단계별 JUnit XML과 `test-counts.json`: 이번 실행의 검사 수·실패·건너뛰기 집계.
- `before/`·`before-manifest.json`·`moves.json`·`index-before.patch`·`worktree-before.patch`: 기존 작업본과 이동 대응.
- `source-checks.json`·`legacy-reference-check.json`·`artifact-checks.json`: 소스·계약·구 패키지 참조·JAR 클래스 및 해시 대조.
- `flow-command.json`·`flow.log`: 기존 실제 흐름 스크립트의 실행 명령과 로그.
- `flow-checks.json`: 실제 열 앱 15단계의 JUnit 검사 및 결과 집계. 원본 로그·단계별 JUnit·요약은 [별도 실제 흐름 폴더](../runs/stove-download-check-ifaxfd2j/)에 있다.
- `environment.json`·`verification-summary.json`·`cleanup-checks.json`: 실행 환경·최종 집계·검증 전후 자원 대조.

후속 업무 모듈 재작성 대상은 계속 **Settlement**다.
