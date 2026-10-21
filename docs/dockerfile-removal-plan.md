# Dockerfile 제거 계획과 검증 기록

작성일·완료일: 2026-10-21. 상태: 완료.

후속 Compose 파일 단순화와 별도 검증은 [Compose 실행 구성](compose-layout.md)에 기록한다.

## 목표와 기준

- Java 실행 대상 12개와 MinIO의 Dockerfile 1개를 제거한다.
- Java 이미지는 기존 Jib 3.5.4 설정으로 생성하고 기본 Compose는 이미지 실행만 담당한다.
- MinIO와 mc는 기존 릴리즈와 S3·권한 계약을 유지한다. 공개 이미지 공급 가능 여부를 먼저 확인하고, 공급이 불가능하면 고정 소스를 빌드해 Dockerfile 없이 패키징한다.
- 실행 스크립트와 업무 검증의 이미지 준비 경로를 함께 전환한다. 프로세스 검증용 bootJar는 유지한다.
- 과거 Dockerfile/Jib 비교 결과는 당시 코드에 고정해 보존한다.
- 코드·빌드·테스트·실행은 로컬, 컨테이너는 OrbStack에서 수행한다. 포트·DB·Compose 프로젝트를 격리하고 기존 자료와 볼륨을 보존한다.

## 실행 순서

1. 이미지 공급 확인과 MinIO의 Dockerfile 없는 생성·확보 절차를 구현한다.
2. Jib 이미지·healthcheck를 기본 앱 Compose에 통합하고 선택적 오버라이드를 제거한다.
3. 로컬·원격 실행과 9개 업무 검증 스크립트, Testcontainers의 이미지 준비 경로를 전환한다.
4. 과거 비교 도구의 소스 버전을 고정하고 현재 실행 가이드를 갱신한다.
5. Dockerfile 13개를 제거하고 정적 검사·단위 테스트·관련 통합 테스트를 실행한다.
6. 격리한 Jib 컨테이너 12개로 출시→구매→다운로드→정산, health·사용자·정상 종료를 검증하고 실제 결과를 기록한다.

## 완료 조건

- 현재 소스의 Dockerfile은 0개다.
- 기본 Compose에 build/dockerfile 항목이 없고 기본 실행 경로가 docker build를 호출하지 않는다.
- 기존 로컬 이미지가 없는 환경에서 필요한 이미지를 생성하거나 확보할 수 있다.
- Jib 컨테이너의 Java 21, UID/GID 100:101, 시간대·JAVA_OPTS와 정상 종료를 유지한다.
- MinIO의 버킷·읽기 전용 권한·서명 ZIP 다운로드 계약을 유지한다.
- 검증 결과와 제외·미확인 범위를 구분해 기록한다.

## 진행 기록

- 시작 시 Git 작업 트리는 깨끗하다.
- 시작 당시 OrbStack에 이미지와 실행 중인 컨테이너가 없어 이전 로컬 이미지에 의존하지 않고 검증했다.
- 기존 `quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z` manifest는 존재하지 않았다.
- MinIO `0d7408fc9969caf07de6a8c3a84f9fbb10a6739e`, mc `b00526b153a31b36767991a4f5ce2cced435ee8e`를 사용한다. `infra/minio/compile.sh`가 체크아웃 후 실제 SHA를 확인하고 Go 컨테이너에서 Linux 정적 바이너리를 생성한다. Jib은 Alpine 기반에 바이너리·라이선스·CA 인증서를 패키징한다.
- 기본 앱 Compose에 이미지 이름·태그·healthcheck를 통합하고 Dockerfile 13개와 선택적 Jib 오버라이드를 제거했다.
- `local-stack.sh`·`remote.sh`는 Jib으로 이미지를 준비한다. `start-apps.sh`는 최대 세 JVM씩 기동하며 Catalog health 확인 후 Store를 시작한다. 로컬 MySQL 부트스트랩 완료도 기다린다.
- 9개 업무 검증 스크립트는 `minio_image.py`를 통해 이미지를 준비한다. 프로세스 장애 주입과 JAR 실행은 기존 검증 경계를 유지한다.
- Studio·Review·Download 통합 테스트는 Gradle 의존성으로 MinIO 이미지를 자동 준비한다. 사전 공급 이미지는 환경변수로 지정할 수 있다.
- 과거 비교 도구는 기준 `2ccad87`과 Jib 설정 `bed7c6b`에 고정했다. 기록된 Jib 설정 세 파일의 SHA-256과 과거 커밋의 일치를 로컬에서 확인했다.
- `./gradlew jibDockerBuild test verifyCommonRuntimeDependencies --no-daemon --console=plain` 성공. Java 앱 12개와 MinIO 이미지 1개를 생성했고 단위·어댑터·아키텍처 테스트 1,015건에서 실패·오류·건너뜀은 0건이다.
- Compose 조합 검증, Bash·POSIX shell·Python 문법 검사와 `git diff --check`를 통과했다.
- 전체 `integrationTest`는 `stove.infraTest.maxParallel=1`과 현재 OrbStack 소켓으로 실행했다. 16분 51초에 성공했고 201건에서 실패·오류·건너뜀은 0건이다. 테스트 태스크의 캐시·UP-TO-DATE 재사용 없이 실행했다.
- 기본값과 사용자 지정 레지스트리·태그에서 Jib과 Compose의 이미지 이름이 일치함을 확인했다. 실제 레지스트리 게시와 원격 배포는 수행하지 않았다.

## 최종 검증 결과

| 검증 | 실행 수 | 실패·오류·건너뜀 |
|---|---:|---:|
| 단위·어댑터·아키텍처·배포 계약 | 1,015 | 0 |
| 실제 인프라 통합 테스트 | 201 | 0 |
| Jib 컨테이너의 실제 업무 여정 | 3 | 0 |

최종 여정의 근거는 `runs/jib-validation-59wi3r6v/`다. `result.json`의 `complete=true`, 애플리케이션 12개와 정상 종료 12개를 확인했다.

- 기본 앱 Compose의 이미지·환경 설정·healthcheck·종료 유예를 기반으로, 임시 프로젝트·네트워크·DB와 별도 포트를 사용했다. MySQL·PG MySQL·Redis·Kafka·MinIO 외 관측 인프라는 이번 여정에 포함하지 않았다.
- 앱 12개가 모두 healthy이며 재시작은 0회다. 실행 이미지가 Jib 산출물의 config ID 또는 manifest digest와 일치한다. OrbStack 이미지 저장소가 manifest digest를 사용하는 경우도 대조한다.
- 모든 앱의 UID/GID `100:101`, Java PID 1, `MaxRAMPercentage=75`, 업무 앱 `Asia/Seoul`·모의 PG `UTC` 시간대를 확인했다.
- PG DB 자격증명·제어 키는 PG만, 결제·정산 키는 해당 호출자에게만 제공하는 기본 실행 계약을 검사했다.
- MinIO 읽기 전용 계정의 업로드·삭제 거부를 확인했다. 실제 ZIP 다운로드는 원본 바이트와 대조하는 기존 E2E를 사용했다.
- `initialPurchasesAndDownloads`: 실제 상품 2개 출시, 주문 3개 결제, License 지급·원본 ZIP 다운로드·매출 생성 통과.
- `allocationAndOwnershipAreIsolated`: 판매자 배분·PG 비용·플랫폼 순액과 판매자/관리자 권한 분리 통과.
- `collectsPayoutAndRetriesWithoutDuplicatingAmounts`: 독립 PG의 지급 명세 접수·거래별 대사·모의 수령 확인과 재요청 멱등성 통과.
- SIGTERM 뒤 앱 12개 모두 exit 143으로 종료하고 `Graceful shutdown complete` 로그가 남았다. OOM·강제 종료는 없었다.
- 완료 후 해당 검증 프로젝트의 컨테이너·네트워크·볼륨을 정리했고 최종 `docker ps -a`는 비어 있다. 소스·이미지·로그·측정 자료는 보존했다.

전체 테스트 집계는 `runs/dockerfile-removal-tests.json`, 이미지 생성·단위 검사는 `runs/dockerfile-removal-build.log`, 통합 검사는 `runs/dockerfile-removal-integration.log`에 있다. 최종 여정 폴더에는 단계별 JUnit XML·앱 로그·종료 로그·결과 JSON을 보존했다. 검증용 키가 있는 Compose 파일은 권한 600, 실행 폴더는 700이다.

예비 회차에서 이미지 저장소의 ID 표시 차이, 실행 중 기동 스크립트 수정, Testcontainers의 자동 포트 할당과 충돌을 확인했다. 검증 도구는 이미지 ID/manifest를 함께 검사하고, 기동 스크립트를 실행 폴더에 고정하며, 앱·MinIO 포트를 자동 할당 대역 32768~60999보다 낮게 분리했다. 병행 테스트의 자체 컨테이너 정리도 공유 자료 유실로 오인하지 않도록 했다. 예비 회차는 최종 실행 수에 포함하지 않고 원자료를 보존한다.

현재 소스의 Dockerfile은 0개이며 기본 Compose의 `build`·`dockerfile` 항목도 0개다. 원격 실행 스크립트는 수정·문법 검사를 완료했으며 실제 서비스 실행 검증은 로컬 OrbStack에서 수행했다.

## 실행 방법

```bash
# 앱 12개와 MinIO 이미지를 로컬 OrbStack에 생성한다.
./gradlew jibDockerBuild

# 기본 실행 경로. 별도 스택을 기동할 때는 기존 환경·자료와 포트 충돌을 확인한다.
./scripts/local-stack.sh up

# 전용 프로젝트·DB·포트를 생성하고 종료 후 해당 자원만 정리한다.
python3 tools/verification/verify-jib.py

# 이미 생성한 이미지의 재검증.
python3 tools/verification/verify-jib.py --skip-build
```

`STOVE_JIB_REGISTRY`·`STOVE_IMAGE_TAG`는 앱 이미지의 생성과 Compose 실행에 함께 적용된다. MinIO는 같은 레지스트리와 고정 릴리즈 태그를 사용한다. `STOVE_MINIO_IMAGE`로 사전 공급 이미지를 지정하면 소스 빌드와 패키징을 생략하며, 테스트 전용 지정은 `STOVE_TEST_MINIO_IMAGE`가 우선한다.

과거 Dockerfile/Jib 비교 실험은 Git 이력에서 당시 소스를 추출한다. 이 경로에서만 과거 Dockerfile을 생성·사용하며 현재 소스와 기본 실행 경로에는 Dockerfile 의존성이 없다.
