# 상품 다운로드 제한과 저장소 적용

2026-11-02 사용자는 판매 중지와 별도로 기존 구매자의 다운로드·업데이트를 제한하는 기술안을 확인하고 `실행`을 지시했다. 일반 판매 중지·관리자 판매 제한과 License의 개인별 환불 정지는 독립이다. 이번 구현·검증은 로컬 OrbStack 격리 자원을 사용한다.

## 계약

- Catalog 상품에 `downloadRestricted`, 독립 `downloadRestrictionVersion`과 `PENDING/APPLIED/FAILED` 저장소 상태·마지막 성공 확인·다음 실행 시각을 둔다. V13은 기존 상품을 제한 없음·버전 1·APPLIED로 이행한다.
- ADMIN GET/PUT `/api/v1/admin/products/{code}/downloads/restriction`. PUT은 `restricted`·`expectedRestrictionVersion`·필수 사유(공백 불가·최대 500자)를 받는다. 관리자 주체는 JWT sub다. 누락/잘못된 값은 400, 오래된 버전은 409, 없는 상품은 404다. 응답은 no-store다.
- PUT의 202는 원하는 상태와 감사 기록을 저장한 접수다. 저장소 완료가 아니다. 상품 행 잠금·독립 버전으로 충돌을 제어하고 `catalog_download_change`의 불변 이력과 상태/작업 예약을 원자 저장한다. 현재 버전의 같은 상태 요청은 새 버전·감사를 만들지 않으며 과거 버전은 같은 본문도 409다.
- 제한 요청 즉시 신규 URL 발급을 거절한다. 해제 요청 뒤에도 저장소 APPLIED 확인 전까지 거절한다. Catalog 내부 build GET의 403/`PRODUCT_DOWNLOAD_RESTRICTED`를 Download 어댑터가 구분하고 저장소 HEAD/서명을 실행하지 않는다. 다른 내부 403·인증/응답 오류는 503이다.
- 판매 제한 상태는 다운로드 판단에 사용하지 않는다. License ACTIVE 검사와 상품 ID·코드 대조를 유지한다. 보유권·주문·결제/환불·Store 공개 사본을 변경하지 않는다. 현재 전체 ZIP이 업데이트 경로이므로 제한은 업데이트 파일 발급에도 적용된다.
- 새 공개본은 제한을 해제하지 않는다. 제한을 한 번이라도 사용한 상품은 공개본 변경 시 저장소 재확인을 예약한다. 제한 없음 상태에서도 해당 확인 전까지 신규 URL을 보류한다.

## 저장소 적용과 복구

Catalog가 소유하는 `core.port.BuildDistribution`을 S3 태그 전용 어댑터가 구현하며 SDK·자격증명 조립은 config에 둔다. Download는 업무 DB/Kafka·저장소 쓰기 권한을 추가하지 않는다.

1. 스케줄러가 DB의 실행 예정 상품을 최대 20개 선택한다(기본 5초 주기). 각 상품의 별도 서비스 트랜잭션에서 상품 행을 잠그고 최신 상태를 다시 읽는다. 같은 상품의 관리자 변경·출시·다른 작업자를 직렬화한다.
2. 해당 프로젝트의 모든 COMPLETED 출시 원장에 저장된 불변 스냅샷을 검증하고 객체 참조를 중복 제거한다. 현재 공개본뿐 아니라 이전 URL이 가리킬 수 있는 과거 공개 빌드도 처리한다. 버킷과 Studio가 생성하는 `projects/{projectId}/` 키 경계를 모든 참조에서 먼저 확인하며 다른 프로젝트/버킷에는 쓰지 않는다. 이 경계를 따르지 않는 저장 자료는 FAILED로 남기고 성공으로 숨기지 않는다.
3. 예약 태그 `stove-download-blocked=true`를 설정하거나 제거한다. 다른 태그는 유지한다. 기존 태그 10개로 공간이 없거나 객체 누락/권한/통신 오류면 실패다. 태그 쓰기를 업무적으로 수정하는 주체는 이 어댑터 하나로 제한하며 기존 고유 객체의 내용/키를 덮어쓰지 않는 저장소 운영 조건을 유지한다.
4. 태그 재조회와 실제 Download 읽기 계정의 1바이트 GET으로 차단 시 AccessDenied, 해제 시 읽기 성공을 확인한 뒤 APPLIED로 저장한다. 태그만 저장됐거나 읽기 계정의 태그 거절 정책이 없는데 APPLIED로 표시하지 않는다. 저장소가 없는 상황·요청 실패를 파일 누락으로 대체하지 않는다.
5. 실패는 FAILED와 다음 시각(+30초)을 저장하고 재시도한다. 성공 뒤에도 +60초 재확인으로 DB 커밋 실패·재시작·응답 유실·외부 상태 이탈을 같은 원하는 상태로 수렴시킨다. 원본 제공사 오류/URL/자격증명을 상태나 로그에 저장하지 않는다. DB 작업 자체가 실패하면 기존 예약이 남는다.

저장소 호출당 제한 시간은 기본 5초(설정 상한 10초)다. 여러 과거 파일을 처리하는 동안 해당 상품 행 잠금을 유지하므로 이 상품의 관리자 변경·출시가 기다릴 수 있다. DB와 저장소는 원자 트랜잭션이 아니다. 중간 실패 때 일부 객체만 적용될 수 있으며 실패 뒤 재실행한다. 제한과 겹친 이전 발급 요청·저장소 적용 전 기존 URL의 사용, 시간 초과 뒤 늦게 도착하는 외부 변경과 다음 재확인 사이까지 전역 즉시 차단이라고 설명하지 않는다. APPLIED는 마지막 성공 관측이다.

이미 시작한 전송의 강제 종료나 내려받은 파일의 회수는 지원하지 않는다. 차단 해제 후 만료 전의 과거 URL도 다시 사용될 수 있다. URL 자체를 영구 폐기하는 키 회전/프록시 전송을 구현한 것이 아니다.

## 구성과 적용 순서

새 API를 공개하기 전 Catalog/Download/Gateway 코드와 V13, 아래 IAM 정책·전용 계정을 준비한다. Download의 새 업무 오류 해석을 먼저 적용하고 관리자 경로는 뒤에 연다. 기존 런타임에 자동 배포하거나 기존 버킷 정책을 덮어쓰지 않는다.

- `CATALOG_DOWNLOAD_STORAGE_ENABLED=true`, `CATALOG_DOWNLOAD_STORAGE_ENDPOINT`(AWS 기본 주소는 생략), `CATALOG_DOWNLOAD_STORAGE_REGION`, `CATALOG_DOWNLOAD_STORAGE_BUCKET`.
- `CATALOG_DOWNLOAD_STORAGE_ACCESS_KEY/SECRET_KEY`: GetObjectTagging·PutObjectTagging만 허용하는 전용 계정. `infra/minio/policies/catalog-download-tagger.json`은 stove-builds 예시이며 실제 버킷으로 맞춘다. 파일 GET·업로드·삭제·버킷 정책 수정 권한을 주지 않는다.
- `DOWNLOAD_STORAGE_ACCESS_KEY/SECRET_KEY`: Download와 같은 읽기 전용 계정. Catalog는 적용 결과 확인용으로만 사용한다.
- `infra/minio/policies/download-reader.json`: Download 읽기 계정에 부여할 GetObject/ListBucket과 예약 태그=true의 GetObject 명시적 거절이다. 실제 버킷으로 맞추고 기존 IAM 정책의 필요한 권한을 보존한다. 현재 고정 MinIO는 인증 요청에 IAM 정책을 적용하므로 버킷 정책만으로 대체하지 않는다. 런타임 Catalog 계정은 IAM/버킷 정책을 바꾸지 못한다.
- 기능 미설정 시 기존 공개·출시·다운로드는 유지하며 관리자 PUT은 503이다. 제한 이력이 있는 DB에서는 작업자를 끄거나 권한을 제거하지 말고 명시적 해제·APPLIED를 먼저 확인한다.
- Compose는 Catalog에만 태그 계정과 확인용 읽기 계정을 전달한다. 비밀 기본값·root 계정 대체는 없다. 정책/키를 실제 공유 환경에 적용하는 작업은 이번 격리 검증과 별도다.

이벤트 사본을 새로 만들지 않으므로 차단 명령에 Kafka Outbox는 추가하지 않는다. 상품 상태에 저장한 예약 자체가 내구 작업이며 서비스가 트랜잭션을 조율한다.

## 검증 기록

진행 원자료는 Git 제외 `runs/download-restriction-20261102/`에 저장한다. 기존 OrbStack 컨테이너·볼륨은 각각 0개로 확인했다. 전체 prune·공유 compose down·실제 PG 금융 동작은 하지 않는다. 도메인 최초 컴파일 Red와 실제 API/MinIO·DB 실패를 구분한다. 최종 결과는 검증 종료 뒤 아래에 기록한다.

초기 실제 검증에서 버킷 태그 거절 정책만으로는 인증된 읽기가 차단되지 않아 APPLIED가 되지 않는 것을 확인했다. 고정 MinIO의 [인증 처리 소스](https://github.com/minio/minio/blob/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e/cmd/auth-handler.go#L386)는 익명 요청과 인증된 IAM 요청을 구분한다. 읽기 계정 IAM의 명시적 거절로 보정하며 버킷 정책만의 실패 결과를 성공으로 대체 설명하지 않는다.


<a id="verified-20261102"></a>

## 최종 완료 — 2026-11-02 로컬 OrbStack

단위·아키텍처 **304건**, 통합/이행 **44건**, 총 **348건**을 통과했다. 실패·오류·건너뜀은 모두 0건이다. Catalog V13 이행 검사는 integrationTest에 포함한다. 선행 집중 검사·실패 뒤 재실행과 1,730건의 이전 판매 관리 기록을 더하지 않는다.

| 모듈 | 단위·아키텍처 | 통합·이행 | 합계 |
|---|---:|---:|---:|
| Catalog | 62 | 31 | 93 |
| Download | 109 | 11 | 120 |
| Gateway | 133 | 2 | 135 |
| 합계 | 304 | 44 | 348 |

최종 명령은 테스트 필터·명세 갱신 옵션 없이 성공했다. 2분 38초는 해당 명령의 경과 시간이다.

```bash
PATH=/Users/wltjq12039035/.orbstack/bin:$PATH \
JAVA_HOME=/Users/wltjq12039035/.jdks/jdk-21.0.12+8/Contents/Home \
CI=true DOCKER_HOST=unix:///Users/wltjq12039035/.orbstack/run/docker.sock \
STOVE_TEST_MINIO_IMAGE=stove/minio:download-restriction-20261102 \
./gradlew --no-daemon --max-workers=2 -Pstove.infraTest.maxParallel=1 \
  :apps:catalog:check :apps:download:check :apps:gateway:check \
  :apps:catalog:bootJar :apps:download:bootJar :apps:gateway:bootJar --console=plain
```

이미지 별칭은 저장소의 고정 MinIO/mc 소스로 OrbStack에서 만든 기본 이미지의 검증 전용 태그다. 실제 ID는 `sha256:27f0ab097714d0154c001f8cefde2003b2d932c2c40c70e689dbef3c6c1fdc82`다. 빈 환경에서 Catalog도 기본 이미지를 준비하도록 Gradle 의존성을 추가했다. 초기 누락 이미지와 Jib의 Docker 실행 경로 문제는 코드 검증 실패와 구분해 보존하며 후자는 명시적 OrbStack 실행 경로로 해결했다.

검증한 주요 경계는 다음과 같다.

- 실제 Catalog HTTP·MySQL·MinIO에서 ADMIN 경로·400/401/403/409·no-store·독립 버전·동시 변경, 실제 감사 INSERT 제약 실패 뒤 전체 상태/감사 롤백을 확인했다.
- 판매자 STOPPED·관리자 판매 BLOCKED에서도 보유자의 빌드 조회는 허용하고, 독립 다운로드 제한은 신규 조회를 거절했다. 과거 두 공개본의 기존 URL은 적용 전 200→적용 뒤 403, 명시적 해제 뒤 200이며 원본 바이트·다른 태그·무관 상품의 접근을 보존했다.
- 차단 중 새 출시도 제한을 유지하고 새 객체를 재차 차단했다. 읽기 IAM 거절 누락은 FAILED로 남고 30초 이후 재시도로 APPLIED에 수렴했다. 해제 실패는 신규 발급을 계속 막고, 새 서비스 인스턴스가 DB의 예약을 읽어 복구했다. 이 검사는 실제 JVM 재시작을 수행한 전체 앱 장애 여정과 구분한다.
- 60초 이후 외부 태그 이탈 재확인·수렴, 다른 프로젝트/버킷을 전체 참조 검증 단계에서 거절, 태그 계정의 파일 읽기/쓰기 거절과 Download 계정의 쓰기 거절을 확인했다.
- 실제 Download HTTP·JWT 검사와 Catalog HTTP 응답 대역으로 업무 제한 403과 일반 내부 권한/연계 오류 503을 구분하고, 업무 제한에서는 저장소를 호출하지 않는 서비스 검사를 통과했다. Catalog/Gateway 새 ADMIN 경로는 JWT decoder 대역으로 역할을 공급한 HTTP 보안 검사이며 실제 Auth 로그인·운영 화면·전체 열 앱 여정을 새로 수행한 것은 아니다.
- V12의 비어 있지 않은 자료→V13에서 공개본·소유자·판매 상태/버전·관리자 판매 제한을 보존하고 기본 제한 없음·작업 제약을 확인했다. Catalog OpenAPI는 의도한 GET/PUT을 생성한 뒤 최종 일반 게이트에서 갱신 없이 대조했다.

처음 도메인은 미구현 메서드/입력 타입으로 컴파일 Red였다. 버킷 정책만 사용한 첫 저장소 검사는 실제 읽기가 계속 성공해 FAILED를 반환했으며 IAM 정책으로 보정했다. 감사 실패용 트리거는 검증 계정의 SUPER 권한이 없어 해당 상품에만 걸리는 임시 CHECK 제약으로 바꿔 실제 INSERT 실패를 검증했다. 판매 상태 검사 시계가 공개 시각보다 과거였던 픽스처와, 일반 403도 업무 제한으로 반환하던 Download HTTP 대역은 보정한 뒤 최종 전체 게이트를 재실행했다. 최종 게이트 전후 운영 코드 변경은 없고 첫 게이트 뒤 변경은 Download 통합 테스트 한 파일뿐이다.

검증 입력 **385개**의 해시와 최종 파일이 일치했다. 세 bootJar의 해시·최종 XML 사례 수 일치, 운영 JAR 내 테스트/Fixture·시험 라이브러리 부재, Download의 Kafka/MongoDB/JPA 운영 의존성 부재를 확인했다. 정확한 해시는 `verification-summary.json`, 입력 차이는 `followup-input-change.json`, 마지막 입력은 `source-manifest-final.json`에 있다.

기존 컨테이너·볼륨은 각각 **0→0개**다. 개발 이미지/Gradle·Go 캐시는 남기며 전체 prune·공유 down·OCI 접속·배포·실제 PG 금융 동작은 수행하지 않았다. 원자료는 `runs/download-restriction-20261102/`의 명령 로그·실패 XML·최종 XML·입력 manifest·JAR 해시·자원 비교·집계 스크립트다. 기존 미커밋 판매 관리 작업은 보존했다.

이번 완료는 독립 다운로드 제한과 격리 검증 범위다. 기존 공유 환경의 정책/계정 준비·배포, 이미 시작한 전송 종료·다운로드된 파일 회수, 상점 노출 정책·G4 전체 완료와 구분한다.
