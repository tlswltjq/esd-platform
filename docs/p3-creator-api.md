# 이슈 #76: 창작자 등록·심사·출시 API 데모

이 문서는 새 창작자와 별도 심사자가 공개 Gateway API만 호출하는 순서다. 브라우저 화면은 필요 없다. OpenAPI는 Gateway의 `/swagger-ui.html`에서 Studio와 Review를 선택한다. CLI 예시는 `jq`, `curl`, `zip`, `shasum`(Linux에서는 `sha256sum`)을 사용한다. 요청 본문은 [`examples/p3-creator`](examples/p3-creator)에 있다.

## 준비와 계정

1. `POST /api/v1/auth/signup`에 `{"email":"creator@example.test","password":"..."}`를 보내 새 창작자를 만든다. Auth가 `CREATOR` 역할을 발급하고 Studio가 첫 호출에 개인 Workspace를 만든다.
2. Swagger OAuth2 Authorization Code + PKCE로 `studio` 범위의 창작자 access token을 받는다. 별도 `REVIEWER` 계정도 `studio` 범위로 로그인한다. CLI에서는 `BASE=http://127.0.0.1:18080`, `CREATOR_TOKEN`, `REVIEWER_TOKEN` 환경 변수로 두 토큰을 전달한다.
3. 아래 예시의 `productCode`를 매번 고유하게 바꾼다. 모든 창작자 호출에는 `Authorization: Bearer $CREATOR_TOKEN`을 붙인다. 타 Workspace의 프로젝트·revision·출시 목록은 `403`, 제출 심사 상태는 `404`다.

## 프로젝트와 이미지

```sh
jq --arg code "P3-DEMO-$(date +%s)" '.productCode=$code' docs/examples/p3-creator/project.json > /tmp/p3-project.json
GAME_ID=$(curl -fsS -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' -d @/tmp/p3-project.json "$BASE/api/v1/studio/games" | jq -r '.data.gameId')
curl -fsS -H "Authorization: Bearer $CREATOR_TOKEN" "$BASE/api/v1/studio/games"
SCREENSHOT_URL=$(curl -fsS -H "Authorization: Bearer $CREATOR_TOKEN" -F 'file=@screenshot.png;type=image/png' "$BASE/api/v1/studio/projects/$GAME_ID/assets" | jq -r '.data.url')
COVER_URL=$(curl -fsS -H "Authorization: Bearer $CREATOR_TOKEN" -F 'file=@cover.png;type=image/png' "$BASE/api/v1/studio/projects/$GAME_ID/assets" | jq -r '.data.url')
curl -L -fsS "$SCREENSHOT_URL" -o /tmp/p3-screenshot.png
jq --arg screenshot "$SCREENSHOT_URL" --arg cover "$COVER_URL" '.screenshots=[$screenshot] | .coverUrl=$cover' docs/examples/p3-creator/store-page.json > /tmp/p3-store-page.json
```

이미지는 PNG/JPEG, 최대 5MB다. Studio가 형식과 크기를 검사해 MinIO/S3에 저장하고 실제 객체 크기를 확인한다. 반환한 URL은 고정된 공개 경로이며 요청할 때마다 비공개 객체의 짧은 다운로드 URL로 연결된다. 발행과 제출은 같은 프로젝트에 업로드된 스크린샷·커버가 없으면 `400`이다. `STUDIO_ASSET_PUBLIC_BASE_URL`을 외부에서 접근 가능한 Gateway 주소로 설정한다. 로컬 E2E 구성은 `http://127.0.0.1:18080`을 사용한다.

## Windows ZIP 빌드

프로젝트 자격증명을 `POST /api/v1/studio/projects/{gameId}/credentials` (`{"name":"demo-ci"}`)로 발급받고 응답 `data.token`을 `CI_TOKEN`에 넣는다. 자격증명은 이 프로젝트 범위로만 업로드할 수 있다. 다음 ZIP의 `game.exe`는 실제 배포용 바이너리로 교체한다.

```sh
mkdir -p /tmp/p3-game
printf '%s' '{"productVersion":"1.0.0","entrypoint":"game.exe"}' > /tmp/p3-game/manifest.json
cp ./game.exe /tmp/p3-game/game.exe
(cd /tmp/p3-game && zip -q /tmp/p3-game.zip manifest.json game.exe)
SIZE=$(wc -c < /tmp/p3-game.zip | tr -d ' ')
SHA=$(shasum -a 256 /tmp/p3-game.zip | cut -d ' ' -f 1)
```

`POST /api/v1/studio/ci/projects/{gameId}/upload-sessions`에 `X-Project-Credential: $CI_TOKEN`을 넣고 다음 값을 제출한다. `fileSize`, `sha256`은 위 ZIP에서 구한 값이다.

```json
{"productVersion":"1.0.0","buildNumber":"1","platform":"WINDOWS","architecture":"X86_64","fileName":"p3-game.zip","fileSize":1234,"sha256":"64자리 SHA256","commitSha":"deadbeef","repository":"https://github.com/example/game","sourceRef":"refs/heads/main","ciProvider":"GITHUB","ciRunId":"1","idempotencyKey":"p3-demo-고유값"}
```

응답의 `data.parts[0].uploadUrl`에 ZIP을 `PUT`하고 응답의 `ETag`를 `POST /api/v1/studio/ci/projects/{gameId}/upload-sessions/{sessionId}/complete`의 `{"parts":[{"partNumber":1,"etag":"..."}]}`에 넘긴다. `GET /api/v1/studio/ci/projects/{gameId}/builds/{buildId}`를 폴링해 `VALIDATED`를 확인한다. 악성코드·해시·패키지 검사 실패는 `FAILED`이며 제출할 수 없다. 창작자는 `GET /api/v1/studio/games/{gameId}/builds`로 빌드 이력을 볼 수 있다.

## Revision, 심사, 출시

1. `POST /api/v1/studio/projects/{gameId}/store-page-revisions`에 `/tmp/p3-store-page.json`을 제출한다. `draft:true`면 `PUT` 수정과 `GET .../{revisionId}/preview` 후 `POST .../{revisionId}/publish`한다. 발행 후 revision은 수정할 수 없다. 가격은 `pricing.json`, 국내 등급 설문은 `rating.json`을 각각 `POST .../pricing-revisions`, `POST .../rating-revisions`에 제출한다.
2. `POST /api/v1/studio/projects/{gameId}/submissions`에 `metadataRevisionId`, `pricingRevisionId`, `ratingRevisionId`, `buildId`를 넣는다. 제출은 불변 스냅샷이다. 창작자는 `GET .../{gameId}/submissions`와 `GET /api/v1/studio/projects/submissions/{submissionId}/review-status`에서 게이트·외부 피드백을 확인한다.
3. 심사자는 `GET /api/v1/reviews/cases?submissionId={submissionId}`에서 안건을 찾고 `POST /api/v1/reviews/cases/{caseId}/changes-requested`에 `{"reasonCode":"METADATA","feedback":"소개를 구체화해 주세요."}`를 제출한다. 창작자는 수정된 **새** 상점 revision으로 재제출한다. 심사자는 새 제출의 `RATING`, `STORE_PAGE`, `BUILD_QA`, `LEGAL`, `SDK_COMPLIANCE`, `COMMERCIAL` 안건을 `POST .../approve`로 처리한다. 국내 자체등급의 `RATING` 승인에는 `{"ratingCode":"ALL"}`이 필요하다.
4. 심사 상태가 `READY_FOR_RELEASE`가 되면 `POST /api/v1/studio/projects/submissions/{submissionId}/releases`로 즉시 출시한다. `{"publishAt":"미래 UTC ISO 시각","timeZone":"Asia/Seoul"}`을 보내면 예약한다. `GET /api/v1/studio/projects/{gameId}/releases`에서 이력을 확인한다. `POST /api/v1/studio/projects/releases/{이전 releaseId}/rollback`은 이전 검증 빌드를 가리키는 새 release를 만든다.

누락되거나 미업로드된 이미지, `FAILED` 빌드, 미승인 제출은 거절된다. 실제 HTTP 판정은 [`TrackACreatorFlowTest`](../e2e/src/test/java/com/stove/e2e/TrackACreatorFlowTest.java)에 있고, `./gradlew :e2e:e2eTest`로 가입·업로드·심사·출시·롤백과 다른 Workspace 접근 차단을 재실행할 수 있다.
