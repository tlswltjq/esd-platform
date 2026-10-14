# Studio 실행과 심사·출시 요청

[서비스 명세](services.md#studio) · [모델](domain/models/studio.md) · [단순화 계획](studio-simplification-plan.md)

CREATOR 가입 후 Project를 만들고 상점 내용·썸네일·전체 ZIP을 준비한다.
외부 심사 결과를 출시 요청 조건으로 확인하고 출시 요청·재요청을 보낸다.
심사 판정과 실제 출시는 Studio에서 수행하지 않는다.

## 실행

현재 완료 기준은 Auth·Gateway다. Studio 검증에는 Auth·Gateway·Studio와
MySQL·Redis·Kafka·MinIO만 사용한다. 실제 심사·상점 처리 결과는 Kafka 이벤트 픽스처로 제공한다.

전체 검증은 다음 명령으로 실행한다. JDK 21, Python 3, Docker와 프로젝트 Gradle 의존성이 필요하다.

~~~bash
python3 scripts/verify-studio.py
~~~

스크립트는 임시 DB와 별도 포트에서 세 앱을 실행하고 실제 CREATOR 가입·PKCE 로그인,
역할·소유권, 전체 업로드, 반려·재심사, 출시 요청·재요청과 지연 응답 처리를 검증한다.
실행 종료 시 자체 생성한 테스트 앱과 컨테이너를 정리하며 로그 경로를 출력한다.

Auth·Gateway를 직접 실행 중이라면 아래 인프라를 준비하고 Studio를 시작할 수 있다.
첫 MinIO 소스 빌드는 이미지 준비에 시간이 걸린다.

~~~bash
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d mysql mysql-bootstrap kafka redis minio

export JWT_ISSUER_URI=http://localhost:8080
export STORAGE_ENDPOINT=http://localhost:19000
export STORAGE_PRESIGN_ENDPOINT=http://localhost:19000
export STORAGE_ENCRYPTION_ENABLED=false
./gradlew :apps:studio:bootRun
~~~

Studio는 Auth에서 발급한 JWT의 CREATOR 역할과 sub로 소유권을 확인한다.
Workspace·판매자 ID 조회를 사용하지 않는다.

Swagger에서 auth의 CREATOR 가입 API를 호출하고 OAuth Authorize로 로그인한 뒤 Studio API를 사용한다.
새 HTTP 경로는 /api/v1/studio/projects다. 파일 업로드에는 version과 file을 전달한다.
기본 상한은 ZIP 1GiB, PNG/JPEG 썸네일 5MiB다. MAX_ARTIFACT_BYTES와 MAX_THUMBNAIL_BYTES로
조정하며, 빌드 상한을 늘리면 STUDIO_MAX_FILE_SIZE와 STUDIO_MAX_REQUEST_SIZE도 함께 맞춘다.
BFF 쿠키로 변경 요청을 보낼 때는 /bff/csrf에서 받은 토큰을 지정된 헤더에 넣는다.

## API 호출 예제

아래는 같은 Gateway 주소의 OAuth access token을 CREATOR_TOKEN에 넣은 예제다.
BASE는 실행 중인 Gateway 주소다. 직접 실행의 기본값은 http://localhost:8080이다.

~~~bash
BASE=http://localhost:8080
curl -sS -X POST "$BASE/api/v1/studio/projects" \
  -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"productCode":"MY-GAME","title":"내 게임"}'
~~~

응답 data.id를 PROJECT_ID로 저장한다. 썸네일과 빌드를 업로드한다.

~~~bash
curl -sS -X POST "$BASE/api/v1/studio/projects/$PROJECT_ID/thumbnail" \
  -H "Authorization: Bearer $CREATOR_TOKEN" -F 'file=@thumbnail.png'
curl -sS -X POST "$BASE/api/v1/studio/projects/$PROJECT_ID/builds" \
  -H "Authorization: Bearer $CREATOR_TOKEN" -F 'version=1.0.0' -F 'file=@game.zip'
curl -sS "$BASE/api/v1/studio/projects/$PROJECT_ID" \
  -H "Authorization: Bearer $CREATOR_TOKEN"
~~~

마지막 조회의 data.contentVersion과 data.storePage.thumbnailUrl을 사용해 상점 내용을 PUT으로 수정한다.
상점 요청 JSON에는 contentVersion, title, shortDescription, thumbnailUrl, videoUrl, content, price를 넣는다.
영상은 선택값이며 가격은 0 이상의 KRW 정수다. 오래된 contentVersion은 409다.

조회한 값을 CONTENT_VERSION과 THUMBNAIL_URL에 저장한 뒤 상점 내용을 작성한다.

~~~bash
curl -sS -X PUT "$BASE/api/v1/studio/projects/$PROJECT_ID/store-page" \
  -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"contentVersion\":$CONTENT_VERSION,\"title\":\"내 게임\",\"shortDescription\":\"게임 소개\",\"thumbnailUrl\":\"$THUMBNAIL_URL\",\"videoUrl\":null,\"content\":\"게임 상세 설명\",\"price\":10000}"
~~~

내용이 준비되면 심사를 요청한다.

~~~bash
curl -sS -X POST "$BASE/api/v1/studio/projects/$PROJECT_ID/review-requests" \
  -H "Authorization: Bearer $CREATOR_TOKEN"
~~~

심사 요청 기록은 REQUESTED다. 외부 승인·반려 결과는 해당 요청 기록에 반영하며,
미완료 심사 모듈의 API는 현재 사용·검증 범위에 포함하지 않는다.
자동 검증에서는 결과 이벤트를 실제 Kafka로 전송해 반려·재심사·승인 경계를 확인한다.

현재 상세정보 버전의 외부 승인 결과를 받아 releaseEligible이 true이면 아래처럼 출시를 요청한다.

~~~bash
curl -sS "$BASE/api/v1/studio/projects/$PROJECT_ID" -H "Authorization: Bearer $CREATOR_TOKEN"
curl -sS -X POST "$BASE/api/v1/studio/projects/$PROJECT_ID/release-requests" \
  -H "Authorization: Bearer $CREATOR_TOKEN"
~~~

202는 출시 요청 발행 접수다. 실제 출시의 수행·완료를 의미하지 않는다.
외부 처리 결과는 currentReleaseRequest의 COMPLETED·REJECTED로 표시하며 실제 출시·공개 상품 관리는 외부 업무다.
거절 후 같은 POST를 보내면 새 출시 요청을 발행한다. 심사·출시 요청 이력은 다음 경로에서 조회한다.

~~~bash
curl -sS "$BASE/api/v1/studio/projects/$PROJECT_ID/review-requests" -H "Authorization: Bearer $CREATOR_TOKEN"
curl -sS "$BASE/api/v1/studio/projects/$PROJECT_ID/release-requests" -H "Authorization: Bearer $CREATOR_TOKEN"
~~~

요청 처리 중에도 상세정보를 수정할 수 있다. 현재 버전이 바뀌면 이전 승인 결과를 출시 요청 조건으로 재사용하지 않는다.
새 심사 요청·재요청과 출시 요청·재요청은 해당 버전의 스냅샷을 사용한다.

## 검증

~~~bash
./gradlew :apps:auth:test :apps:gateway:test :apps:studio:test :common:kafka:test
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
docker build -t stove/minio:RELEASE.2025-04-22T22-12-26Z-local infra/minio
export STOVE_TEST_MINIO_IMAGE=stove/minio:RELEASE.2025-04-22T22-12-26Z-local
./gradlew :apps:studio:integrationTest
python3 scripts/verify-studio.py
~~~

단위 테스트는 Auth·Gateway 회귀와 Studio 역할·소유권·편집 충돌·상태 전이를 확인한다.
Studio 통합 테스트는 실제 MySQL·MinIO에서 파일 저장·요청 스냅샷·재요청·지연 응답·이관을 확인한다.
Auth·Gateway·Studio E2E 7건은 실제 로그인·API·Kafka 경계를 검증한다.
미완료 모듈의 동작을 완료로 간주하거나 전체 서비스 E2E를 현재 기준으로 사용하지 않는다.
