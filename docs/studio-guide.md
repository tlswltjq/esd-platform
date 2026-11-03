# Studio 실행과 심사·출시 요청

[서비스 명세](services.md#studio) · [모델](domain/models/studio.md) · [단순화 계획](studio-simplification-plan.md)

CREATOR 가입 후 Project를 만들고 상점 내용·썸네일·전체 ZIP을 준비한다.
외부 심사 결과를 출시 요청 조건으로 확인하고 출시 요청·재요청을 보낸다.
심사 판정과 실제 출시는 Studio에서 수행하지 않는다.

## 실행

MVP 재작성은 [기존 완료 기록](studio-simplification-plan.md#재작성-완료-기록), 단일 심사·출시와 철회 후속은 [최신 구현·검증](project-review-withdrawal-verification.md)을 참고한다.
Studio 검증에는 Auth·Gateway·Studio와
MySQL·Redis·Kafka·MinIO만 사용한다. 실제 심사·상점 처리 결과는 Kafka 이벤트 픽스처로 제공한다.

전체 검증은 다음 명령으로 실행한다. JDK 21, Python 3, Docker와 프로젝트 Gradle 의존성이 필요하다.

~~~bash
python3 tools/verification/verify-studio.py
~~~

스크립트는 임시 DB와 별도 포트에서 세 앱을 실행하고 실제 CREATOR 가입·PKCE 로그인,
역할·소유권, 전체 업로드, 반려·재심사, 출시 요청·재요청과 지연 응답 처리를 검증한다.
실행 종료 시 자체 생성한 테스트 앱과 컨테이너를 정리하며 로그 경로를 출력한다.

Auth·Gateway를 직접 실행 중이라면 아래 인프라를 준비하고 Studio를 시작할 수 있다.
첫 MinIO 소스 빌드는 이미지 준비에 시간이 걸린다.

~~~bash
./gradlew :infra:minio:jibDockerBuild
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

내용이 준비되면 프로젝트를 다시 조회해 최신 contentVersion을 CONTENT_VERSION에 저장하고 심사를 요청한다.
requestId는 논리적인 요청마다 새로 만들고, 응답 유실 등의 재전송에는 같은 requestId와 contentVersion을 사용한다.

~~~bash
curl -sS -X POST "$BASE/api/v1/studio/projects/$PROJECT_ID/review-requests" \
  -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"review-001\",\"contentVersion\":$CONTENT_VERSION}"
~~~

심사 요청 기록은 REQUESTED다. Review의 승인·반려·철회 결과는 해당 요청 기록에 반영한다.
프로젝트의 모든 버전을 통틀어 진행 중 심사는 1건이며 철회 접수 중에도 유지한다.
작업본을 수정하면 currentReviewRequest는 비어 있을 수 있지만 activeReviewRequest로 이전 버전의 진행 중 심사를 확인한다.
독립 Studio 검증은 결과 이벤트 픽스처를, 실제 다섯 서비스 여정은 Review·Catalog를 사용한다.

현재 상세정보 버전의 외부 승인 결과를 받아 releaseEligible이 true이면 아래처럼 출시를 요청한다.

~~~bash
curl -sS "$BASE/api/v1/studio/projects/$PROJECT_ID" -H "Authorization: Bearer $CREATOR_TOKEN"
curl -sS -X POST "$BASE/api/v1/studio/projects/$PROJECT_ID/release-requests" \
  -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"release-001\",\"contentVersion\":$CONTENT_VERSION}"
~~~

202는 출시 요청 발행 접수다. 실제 출시의 수행·완료를 의미하지 않는다.
외부 처리 결과는 COMPLETED·REJECTED·WITHDRAWN으로 표시하며 실제 출시·공개 상품 관리는 Catalog 업무다.
출시 명령은 승인본의 즉시 자동 공개를 요청한다. 승인만으로 공개하지 않으며 별도 철회 유예시간은 없다.
프로젝트 전체의 진행 중 출시도 1건이다. activeReleaseRequest는 현재 작업본 버전과 관계없이 진행 중 출시를 표시한다.
다음 버전 편집·심사는 가능하지만 앞선 출시의 최종 결과 확인 전에는 새 버전 출시를 접수하지 않는다.
releaseEligible은 현재 버전의 승인 근거이며, 활성 요청·철회·파일 상태에 대한 서버 검사를 대신하지 않는다.
거절·철회 뒤 새 출시 의사는 새 requestId로 전송한다. 과거 requestId의 재전송은 과거 요청을 반환한다.
프로젝트·빌드·심사/출시 이력 목록은 기존 GET 경로에서 번호 기반 페이지로 조회한다. `page`는 0부터 시작하고 기본값은 0, `size`는 기본 20·최대 100이다. 각 목록은 ID 내림차순이다. 범위 오류·정수 조회 구간 초과는 400이며, 없는 페이지는 항목이 빈 목록과 해당 조건의 총건수를 반환한다.

`ApiResponse.data`는 기존 배열에서 `{items, page, size, totalElements, totalPages}` 객체로 바뀌었다. 항목 정보는 유지하며 호출부는 `data.items`를 읽고 필요하면 다음 페이지를 조회해야 한다. 전체 탐색 중 새 항목이 추가되면 페이지 위치·총건수가 달라질 수 있다. 소유권 검사와 작업본에 독립적인 진행 중 심사/출시 표시는 유지한다. 상세·요청 POST·철회 영수증 응답은 이 목록 전환의 대상이 아니다.

심사·출시 요청 이력은 다음 경로에서 조회한다.

~~~bash
curl -sS "$BASE/api/v1/studio/projects?page=0&size=20" -H "Authorization: Bearer $CREATOR_TOKEN"
curl -sS "$BASE/api/v1/studio/projects/$PROJECT_ID/builds?page=0&size=20" -H "Authorization: Bearer $CREATOR_TOKEN"
curl -sS "$BASE/api/v1/studio/projects/$PROJECT_ID/review-requests?page=0&size=20" -H "Authorization: Bearer $CREATOR_TOKEN"
curl -sS "$BASE/api/v1/studio/projects/$PROJECT_ID/release-requests?page=0&size=20" -H "Authorization: Bearer $CREATOR_TOKEN"
~~~

요청 처리 중에도 상세정보를 수정할 수 있다. 현재 버전이 바뀌면 이전 승인 결과를 출시 요청 조건으로 재사용하지 않는다.
새 심사 요청·재요청과 출시 요청·재요청은 해당 버전의 스냅샷을 사용한다.

## 심사·출시 요청 철회

본인의 진행 중 요청을 명시적으로 지정한다. reason은 선택 입력이며 최대 2000자다.

~~~bash
curl -sS -X POST "$BASE/api/v1/studio/projects/$PROJECT_ID/review-requests/$REVIEW_REQUEST_ID/withdrawals" \
  -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"requestId":"withdraw-review-001"}'
curl -sS -X POST "$BASE/api/v1/studio/projects/$PROJECT_ID/release-requests/$RELEASE_REQUEST_ID/withdrawals" \
  -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"requestId":"withdraw-release-001","reason":"출시 자료 재확인"}'
~~~

202는 내구 접수 영수증이며 초기 state는 PENDING이다. 응답의 id를 WITHDRAWAL_ID로 저장해
같은 withdrawals 경로의 /$WITHDRAWAL_ID에서 조회한다. 컬렉션 GET은 page·size(최대 100)로 이력을 반환한다.
접수·조회 응답은 no-store다. 같은 종류·requestId의 대상이나 사유를 바꾸면 409다.

최종 state=WITHDRAWN이면 철회 완료, DENIED이면 심사 판정이나 공개가 먼저 확정된 것이다.
finalStatus·finalActor·finalFeedback·finalizedAt으로 정본 결과를 확인한다.
이미 Studio가 최종 결과를 알고 있으면 새 철회 접수는 409이며, 과거 철회 명령 재전송은 기존 영수증을 반환한다.
심사 철회 후 동일 자료도 새 requestId로 심사받을 수 있고, 출시 철회 후 변경 없는 승인본은 새 출시 requestId로 재사용한다.
이미 공개된 상품을 되돌리거나 판매·노출·다운로드 설정을 변경하지 않는다.

상태·원자성·이행·검증 범위는 [단일 심사와 명시적 철회](project-review-withdrawal-analysis.md)를 따른다.

## 검증

~~~bash
./gradlew :apps:auth:test :apps:gateway:test :apps:studio:test :common:kafka:test
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
./gradlew :apps:studio:integrationTest
python3 tools/verification/verify-studio.py
~~~

단위 테스트는 Auth·Gateway 회귀와 Studio 역할·소유권·편집 충돌·상태 전이를 확인한다.
Studio 통합 테스트는 실제 MySQL·MinIO에서 파일 저장·요청 스냅샷·재요청·지연 응답·이관을 확인한다.
Auth·Gateway·Studio E2E 7건은 실제 로그인·API·Kafka 경계를 검증한다.
미완료 모듈의 동작을 완료로 간주하거나 전체 서비스 E2E를 현재 기준으로 사용하지 않는다.
