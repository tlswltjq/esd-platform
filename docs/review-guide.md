# Review 실행과 수동 심사

[서비스 명세](services.md#review) · [모델](domain/models/review.md) · [개선 계획·완료 기록](review-simplification-plan.md)

CREATOR는 Studio에서 요청하고 REVIEWER·ADMIN은 Review에서 제출 당시 자료와 ZIP을 열람해 승인·반려한다.
Review가 발행한 결과는 Studio의 같은 요청 ID·버전에 비동기로 반영된다.

## 네 모듈 전체 검증

JDK 21, Python 3, Docker와 프로젝트 Gradle 의존성이 필요하다.

~~~bash
python3 scripts/verify-review.py
~~~

Auth·Gateway·Studio·Review와 MySQL·Redis·Kafka·MinIO만 별도 포트·임시 DB에서 실행한다.
기본 앱 포트는 30000을 더하며 REVIEW_TEST_PORT_OFFSET으로 바꿀 수 있다.
실제 PKCE 로그인과 역할·요청 접수·파일 열람·승인/반려·재심사·편집 중 심사·출시 요청을 10건으로 검증한다.
Review 릴레이를 끄고 판정한 뒤 프로세스를 다시 시작해 저장된 판정·미발행 Outbox가 전달되는지 2건으로 확인한다.

MinIO에 별도 읽기 전용 계정을 만들고 실제 업로드·삭제 거절도 확인한다.
스크립트가 만든 앱·컨테이너만 종료하며 로그·재시작 확인 자료의 임시 경로를 출력한다.
Catalog 등 미완료 서비스는 실행하지 않는다.

## 직접 실행

심사자 계정은 Auth를 시작하기 전에 AUTH_REVIEWER_EMAIL·AUTH_REVIEWER_PASSWORD로 설정한다.
ADMIN 계정은 기존 AUTH_ADMIN_EMAIL·AUTH_ADMIN_PASSWORD를 사용한다.
기존 이메일 계정의 역할을 초기화 코드가 변경하지 않으므로 전용 이메일로 준비한다.
일반 회원·크리에이터 가입 API는 심사자 역할을 부여하지 않는다.

Auth·Gateway·Studio가 실행 중이면 동일 DB 서버·Kafka·스토리지와 JWT 발급자를 설정해 Review를 실행한다.
아래 JWT_ISSUER_URI는 실제 Auth의 AUTH_ISSUER와 일치시킨다.

~~~bash
export JWT_ISSUER_URI=http://localhost:8091
export JWT_JWK_SET_URI=http://localhost:8091/oauth2/jwks
export STORAGE_ENDPOINT=http://localhost:19000
export STORAGE_PRESIGN_ENDPOINT=http://localhost:19000
export STORAGE_BUCKET=stove-builds
export REVIEW_STORAGE_ACCESS_KEY=review-reader
export REVIEW_STORAGE_SECRET_KEY='읽기 전용 계정의 비밀키'
./gradlew :apps:review:bootRun
~~~

Review URI의 기본값은 http://localhost:8086이며 Gateway의 REVIEW_URI로 변경할 수 있다.
읽기 전용 저장소 계정에는 같은 버킷의 GetObject와 ListBucket 권한을 부여한다.
Review 전용 두 키는 필수이며 Studio의 쓰기 계정으로 자동 대체하지 않는다.
Docker로 실행할 때도 docker-compose.apps.yml의 Review 서비스에 같은 환경변수를 전달한다.
Review가 버킷을 생성하지 않으므로 Studio에서 버킷과 업로드 파일을 먼저 준비한다.
REVIEW_BUILD_URL_TTL은 기본 PT5M이며 1초~15분 범위다.

## 심사 API

BASE는 Gateway 주소, REVIEWER_TOKEN은 REVIEWER 또는 ADMIN의 OAuth access token이다.
요청 ID는 Studio 심사 요청 응답의 data.id와 같다.

~~~bash
BASE=http://localhost:8080
curl -sS "$BASE/api/v1/reviews?status=REQUESTED&page=0&size=20" \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
curl -sS "$BASE/api/v1/reviews/$REVIEW_REQUEST_ID" \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
curl -sS "$BASE/api/v1/reviews/$REVIEW_REQUEST_ID/build-download" \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
~~~

빌드 응답은 data.url·expiresAt·fileSize·checksum이다. URL에는 별도 Bearer 헤더 없이 GET으로 접근한다.
다운로드한 ZIP의 SHA-256을 반환된 checksum과 대조할 수 있다.
목록은 items·page·size·totalElements·totalPages로 반환하고 projectId로 이력을 필터링한다.
상세·판정 응답에는 내부 저장 경로를 포함하지 않는다.

승인과 반려는 다음과 같다. 판정자는 토큰 sub이며 요청 본문으로 지정하지 않는다.

~~~bash
curl -sS -X POST "$BASE/api/v1/reviews/$REVIEW_REQUEST_ID/approve" \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
curl -sS -X POST "$BASE/api/v1/reviews/$REVIEW_REQUEST_ID/reject" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"feedback":"설명과 빌드 내용을 수정해 주세요."}'
~~~

둘 중 한 판정만 수행한다. 반려 사유는 공백이 아닌 최대 2000자다.
동일 판정·사유의 재전송은 최초 결과를 반환하고 새 이벤트를 만들지 않는다.
확정된 판정·사유를 바꾸면 409이며 재심사는 Creator가 Studio에서 새 요청으로 시작한다.
성공 응답 200은 Review 판정 저장 결과이고 Studio 반영 완료를 의미하지 않는다.

BFF 쿠키로 POST할 때는 기존 Gateway의 CSRF 계약을 따른다.
익명은 401, REVIEWER·ADMIN 없는 토큰은 403, 미접수 요청은 404다.
파일 누락·크기 불일치·잘못된 버킷은 409, 저장소 일시 장애는 503이다.

## 검증 명령과 전환

~~~bash
./gradlew :apps:auth:test :apps:gateway:test :apps:studio:test :apps:review:test :common:kafka:test :common:messaging:test
./gradlew :apps:review:integrationTest
python3 scripts/verify-review.py
~~~

Testcontainers가 Docker 소켓을 자동으로 찾지 못하는 환경에서는 현재 Docker context의 소켓을 DOCKER_HOST로 지정한다.
공개 MinIO 이미지 대신 동일 릴리즈의 로컬 이미지를 사용하려면 STOVE_TEST_MINIO_IMAGE를 설정한다.

새 접수 그룹은 review-project이며 Studio의 ProjectReviewRequested만 처리한다.
구 이벤트와 구 심사 데이터는 새 요청에 자동 연결하지 않는다.
V1~V9 체크섬과 역사 자료를 유지하고 V10으로 새 project_review를 생성한다.
운영 전환 시 Studio 미처리 요청과 접수를 대조하고, Kafka 보관 범위 밖의 요청은 보존된 원본 이벤트로 복구한다.
