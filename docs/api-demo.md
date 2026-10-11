# API 데모

[문서 목록](README.md) · [개발 환경과 실행](getting-started.md) · [인증·권한 계약](security.md)

전체 흐름을 재현하려면 [자동 검증](#full-demo)을 실행한다. 각 모델을 직접 만들어 보려면 [창작·출시](#creator-demo) → [구매·환불](#payment-simulator) 순서로 수동 실습한다. 업무 의미와 필드는 [도메인·모델 안내](domain/model-guide.md)를 참고한다.

<a id="full-demo"></a>
## 전체 여정 자동 검증

첫 범위는 BASIC / Windows / 한국 / KRW다. 이 절차는 새 스택에서 가입부터 월 마감까지 두 번 연속 실행하고, 각 실행의 식별자와 금액을 JSON으로 남긴다. 결제 승인은 인증된 로컬 시뮬레이터로 재현한다.

### 실행

Docker Compose 2.24 이상, JDK 21, `openssl`이 필요하다. 로컬 18080~18091, 19000, 13200 포트를 사용할 수 있어야 한다. 저장소 루트에서 실행한다.

```bash
bash scripts/api-demo.sh up
bash scripts/api-demo.sh run
bash scripts/api-demo.sh status
bash scripts/api-demo.sh down
```

`up`은 인프라와 앱 JAR를 빌드해 23개 컨테이너를 시작하고 `stack-wait.sh`의 16개 상태 검사를 통과해야 끝난다. 인프라와 앱은 CI 설정으로 호스트 공개 포트를 닫고, E2E에 필요한 포트만 `127.0.0.1`에 연다. 상태 확인은 `status`, 로그 확인은 `bash scripts/local-stack.sh logs <서비스>`로 한다. `down`은 볼륨을 보존한다.
이미 앱 스택이 실행 중이고 이 데모의 비밀 파일이 없다면 `up`은 인증 값을 바꾸지 않고 멈춘다. 기존 스택은 `run`으로 검증하거나 별도 새 환경에서 `up`을 실행한다.

`run`은 스택 검사, 전체 서비스 단위·통합 테스트와 OpenAPI 계약 테스트, 전체 API 여정 두 회차를 순서대로 실행한다. 서비스 테스트는 로컬 캐시의 `UP-TO-DATE` 판정을 쓰지 않고 실제 실행한다. 각 회차의 테스트 57건이 모두 실행되고 건너뛴 항목이 없어야 성공한다. 실패하면 그 자리에서 종료하며, 다음 회차를 통과로 기록하지 않는다. Testcontainers용 `DOCKER_HOST`가 없으면 현재 Docker context에서 읽는다.

결과는 `build/api-demo/<UTC 시각>-<PID>/`에 남는다. `summary.md`와 `stack-gate.log`, `service-tests.log`, `run-1.log`, `run-2.log`, `run-1.json`, `run-2.json`을 확인한다. 다른 경로가 필요하면 `API_DEMO_OUTPUT_DIR`을 지정한다. 두 JSON의 `productCode`가 같으면 게이트가 실패한다.

`up`은 리뷰어·운영자 테스트 계정 비밀번호와 PG 콜백 비밀을 생성해 Git이 무시하는 `build/api-demo/stack-secrets.env`에 소유자만 읽도록 저장한다. `run`이 같은 값을 읽어 로그인과 서명 검증에 쓴다. 파일을 지운 뒤 앱 컨테이너만 재사용하면 인증 값이 달라지므로 다시 `up`을 실행한다. 창작자·구매자 계정은 회차마다 생성하며 비밀번호나 토큰을 결과 JSON에 기록하지 않는다.

원격 호스트는 기존 [remote.sh](../scripts/remote.sh)의 설정을 사용한다.

```bash
./scripts/remote.sh stack up
./scripts/remote.sh demo
./scripts/remote.sh stack status
./scripts/remote.sh stack down
```

원격 `demo`의 결과 디렉터리는 원격 작업본의 `build/api-demo/`에 있다. 원격 작업본의 Git 제외 파일 `.env`에 `AUTH_REVIEWER_PASSWORD`, `AUTH_ADMIN_PASSWORD`, `PG_CALLBACK_SECRET`을 설정하면 Compose와 데모 태스크가 같은 값을 읽는다. `remote.sh sync`는 이 파일을 보존한다. 로컬 `api-demo.sh up`은 테스트 비밀을 자동 생성한다.

### 여정과 판정

| 단계 | 자동 판정 | 실패할 때 확인할 곳 |
|---|---|---|
| 가입·역할 | 새 CREATOR·MEMBER 가입과 OIDC 로그인, REVIEWER·ADMIN 로그인, 타인 데이터 403/404 | `run-N.log`, `auth`·`gateway` 로그 |
| 게시물·파일 | 실제 PNG를 올려 다시 다운로드, Windows ZIP을 multipart 업로드, SHA-256·악성코드·manifest 검증 후 `VALIDATED` | `studio`·`review`·`minio` 로그, CI build 상태 API |
| 심사·출시 | 상점·가격·등급 revision, 수정 요청과 재제출, 필수 심사 승인, `ReleasePublished`의 catalog/store/download 전파 | Studio 제출 review-status, Review case history, `catalog`·`store` 로그 |
| 검색·구매 | 고유 상품코드와 제목 검색, 정가 18,000원, 서버 주문·PG 사전등록·시뮬레이터 승인액 일치 | `/api/v1/storefront/products`, `/api/v1/orders/{orderNo}`, `/api/v1/payments/{orderNo}` |
| 지급·환불 | 라이브러리·티켓 부여, 서명 URL에서 실제 ZIP을 받아 원본 SHA-256 대조, 환불 후 주문·권한·원장 역분개 | `/api/v1/library`, 다운로드 티켓, 주문별 정산 원장, `license`·`download` 로그 |
| 할인·정산 | 판매자/플랫폼 할인 모두 16,000원 청구, 각기 다른 정산 기준·수수료·지급액, 월 마감 후 환불 조정, 대사·CSV | `/api/v1/settlements/reconciliation?month=YYYY-MM`, `/api/v1/settlements/export.csv?month=YYYY-MM`, `settlement` 로그 |
| 서비스 상태 | 16개 스택 게이트, Outbox 적체 0, DLT 유입 0, 결제 trace 전파 | `stack-gate.log`, `run-N.log`, 서비스 actuator/Tempo |

두 할인 주문의 기준값은 아래와 같다. 모두 같은 정가와 2,000원 할인을 사용한다.

| 부담 주체 | 고객 청구 | 정산 기준 | 수수료 30% | 판매자 지급 | 플랫폼 판촉비 |
|---|---:|---:|---:|---:|---:|
| 판매자 | 16,000원 | 16,000원 | 4,800원 | 11,200원 | 0원 |
| 플랫폼 | 16,000원 | 18,000원 | 5,400원 | 12,600원 | 2,000원 |

`run-N.json`은 한 실행의 `gameId`, `productId`, `buildId`, `releaseId`, ZIP SHA-256, 일반 구매와 두 할인 주문번호, 행사 ID, 판매자 ID, 월, 원장 금액, 마감 원본과 조정액, 대사 경고를 담는다. `downloadedObjectMatchesSha256: true`, `reconciliation.balanced: true`, `csvContainsBothPromotionOrders: true`가 자동 판정이다. `taxInvoiceStatus: SIMULATED`는 MockTaxInvoiceIssuer 결과이며 실제 세금계산서 발행을 뜻하지 않는다.

데모는 공개 게이트웨이 API와 인증된 시뮬레이터 제어 API만 사용한다. Tempo·actuator는 상태 진단에만 사용한다. 데이터베이스 수정, Kafka 수동 발행, 내부 견적 API 호출은 이 데모 태스크에 없다. 기존 `:e2e:e2eTest`의 확장 릴리스 운영 테스트는 catalog 내부 비상 API를 검증하므로 데모 태스크에서 제외하고 전체 E2E에는 남겨뒀다.

### 다시 실행하고 결과 검토하기

두 회차는 각각 새 계정, 상품코드, 업로드 객체, 주문번호, 행사 ID를 만들며 볼륨을 비울 필요가 없다. 월은 정산 서비스의 실제 귀속 월을 사용하고 판매자·주문 식별자로 회차를 구분한다. 실패한 회차도 일부 데이터가 남을 수 있으므로 `run`을 다시 실행하면 새 키 공간에서 처음부터 시작한다. 새 환경으로 완전히 초기화하려면 스택을 내린 뒤 해당 Compose 볼륨을 운영자가 명시적으로 제거해야 한다. 이 스크립트는 볼륨을 지우지 않는다.

`summary.md`와 두 JSON, `service-tests.log`, `stack-gate.log`가 자동 판정 증거다. 수동 확인은 이 파일들의 식별자·금액과 실패 시 해당 API·서비스 로그를 보는 것이다. 상점 웹 화면, 브라우저 E2E, 포트원 테스트 채널은 이 검증 범위에 포함되지 않는다.

### 빈 볼륨 실행 기록 (2026-09-30)

기존 스택을 볼륨 삭제 없이 내리고 별도 이름의 빈 MySQL·MongoDB·MinIO·Tempo 볼륨으로 기동했다. 소스 `7873593`에서 스택 게이트 16/16, 캐시 없이 실행한 서비스 단위·통합·OpenAPI 계약 테스트, API 여정 57/57 두 회차가 모두 통과했다. 두 회차 모두 실제 ZIP 다운로드 SHA-256 일치, 판매자·플랫폼 할인 환불 역분개, 월 대사 균형과 CSV 내역 포함을 확인했다.

| 회차 | 상품코드 | gameId | buildId | releaseId |
|---|---|---:|---:|---:|
| 1 | `GAME-E2E-1790711775663-91bd35` | 1 | 1 | 4 |
| 2 | `GAME-E2E-1790711847465-a5b9af` | 2 | 4 | 8 |

로컬 원본 로그와 비밀값을 제외한 JSON은 Git 제외 경로 `build/api-demo/issue81-fresh-20260929T194605Z/`에 있다. 검증 뒤 원래 볼륨으로 스택을 복구했고 상태 게이트 16/16을 다시 통과했다. 새 환경 검증 첫 시도에서는 1초 백오프 경계에 의존하던 서비스 통합 테스트가 시간 경과로 실패하여, SQL의 미래 시각 필터 테스트를 안정화한 뒤 **다른 새 볼륨**에서 위 결과를 얻었다.

<a id="creator-demo"></a>
## 창작·심사·출시 수동 실습

이 문서는 새 창작자와 별도 심사자가 공개 Gateway API만 호출하는 순서다. 브라우저 화면은 필요 없다. OpenAPI는 Gateway의 `/swagger-ui.html`에서 Studio와 Review를 선택한다. CLI 예시는 `jq`, `curl`, `zip`, `shasum`(Linux에서는 `sha256sum`)을 사용한다. 요청 본문은 [`examples/p3-creator`](examples/p3-creator)에 있다.

### 준비와 계정

1. `POST /api/v1/auth/signup`에 `{"email":"creator@example.test","password":"..."}`를 보내 새 창작자를 만든다. Auth가 `MEMBER`·`CREATOR` 역할을 발급하고 Studio가 첫 호출에 개인 Workspace를 만든다.
2. Swagger OAuth2 Authorization Code + PKCE로 `studio` 범위의 창작자 access token을 받는다. 별도 `REVIEWER` 계정도 `studio` 범위로 로그인한다. CLI에서는 `BASE=http://127.0.0.1:18080`, `CREATOR_TOKEN`, `REVIEWER_TOKEN` 환경 변수로 두 토큰을 전달한다.
3. 아래 예시의 `productCode`를 매번 고유하게 바꾼다. 모든 창작자 호출에는 `Authorization: Bearer $CREATOR_TOKEN`을 붙인다. 타 Workspace의 프로젝트·revision·출시 목록은 `403`, 제출 심사 상태는 `404`다.

### 프로젝트와 이미지

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

### Windows ZIP 빌드

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

아래는 ZIP이 한 파트에 들어가는 경우다. 큰 파일은 응답의 파트 크기에 맞춰 나누고 모든 파트의 ETag를 완료 요청에 보낸다. 응답의 `data.parts[0].uploadUrl`에 ZIP을 `PUT`하고 응답의 `ETag`를 `POST /api/v1/studio/ci/projects/{gameId}/upload-sessions/{sessionId}/complete`의 `{"parts":[{"partNumber":1,"etag":"..."}]}`에 넘긴다. `GET /api/v1/studio/ci/projects/{gameId}/builds/{buildId}`를 폴링해 `VALIDATED`를 확인한다. 악성코드·해시·패키지 검사 실패는 `FAILED`이며 제출할 수 없다. 창작자는 `GET /api/v1/studio/games/{gameId}/builds`로 빌드 이력을 볼 수 있다.

### Revision, 심사, 출시

1. `POST /api/v1/studio/projects/{gameId}/store-page-revisions`에 `/tmp/p3-store-page.json`을 제출한다. `draft:true`면 `PUT` 수정과 `GET .../{revisionId}/preview` 후 `POST .../{revisionId}/publish`한다. 발행 후 revision은 수정할 수 없다. 가격은 `pricing.json`, 국내 등급 설문은 `rating.json`을 각각 `POST .../pricing-revisions`, `POST .../rating-revisions`에 제출한다.
2. `POST /api/v1/studio/projects/{gameId}/submissions`에 `metadataRevisionId`, `pricingRevisionId`, `ratingRevisionId`, `buildId`를 넣는다. 제출은 불변 스냅샷이다. 창작자는 `GET .../{gameId}/submissions`와 `GET /api/v1/studio/projects/submissions/{submissionId}/review-status`에서 게이트·외부 피드백을 확인한다.
3. 심사자는 `GET /api/v1/reviews/cases?submissionId={submissionId}`에서 안건을 찾고 `POST /api/v1/reviews/cases/{caseId}/changes-requested`에 `{"reasonCode":"METADATA","feedback":"소개를 구체화해 주세요."}`를 제출한다. 창작자는 수정된 **새** 상점 revision으로 재제출한다. 심사자는 새 제출의 `RATING`, `STORE_PAGE`, `BUILD_QA`, `LEGAL`, `SDK_COMPLIANCE`, `COMMERCIAL` 안건을 `POST .../approve`로 처리한다. 국내 자체등급의 `RATING` 승인에는 `{"ratingCode":"ALL"}`이 필요하다.
4. 심사 상태가 `READY_FOR_RELEASE`가 되면 `POST /api/v1/studio/projects/submissions/{submissionId}/releases`로 즉시 출시한다. `{"publishAt":"미래 UTC ISO 시각","timeZone":"Asia/Seoul"}`을 보내면 예약한다. `GET /api/v1/studio/projects/{gameId}/releases`에서 이력을 확인한다. `POST /api/v1/studio/projects/releases/{이전 releaseId}/rollback`은 이전 검증 빌드를 가리키는 새 release를 만든다.

누락되거나 미업로드된 이미지, `FAILED` 빌드, 미승인 제출은 거절된다. 실제 HTTP 판정은 [`TrackACreatorFlowTest`](../e2e/src/test/java/com/stove/e2e/TrackACreatorFlowTest.java)에 있고, `./gradlew :e2e:e2eTest`로 가입·업로드·심사·출시·롤백과 다른 Workspace 접근 차단을 재실행할 수 있다.

<a id="payment-simulator"></a>
## 구매·결제·환불 수동 실습

### 준비

`docker-compose.apps.yml`, `docker-compose.apps.ci.yml`, `docker-compose.apps.e2e.yml` 순으로 적용한 로컬 스택은 Payment를 `demo` 프로필로 실행하고 Gateway를 `http://127.0.0.1:18080`에 연다. `STOVE_PAYMENT_SIMULATOR_BASE_URL`은 구매자 브라우저에서 접근 가능한 Gateway 주소다. `demo` 또는 `test` 프로필에서만 시뮬레이터 제어 API가 생성되고, `prod` 프로필이 함께 켜져도 생성되지 않는다. 프로필 없이 실행하면 기존 mock 결제창 URL만 반환한다.

먼저 [창작자 출시 절차](api-demo.md#creator-demo)로 BASIC/KRW 상품 한 개를 출시한다. `POST /api/v1/auth/signup/member`로 구매자를 만들고 [OAuth2 Authorization Code + PKCE](security.md)로 `commerce` 범위의 회원 access token을 받는다. 운영자 토큰은 시뮬레이터 제어에만 쓴다. 아래 변수는 `BASE=http://127.0.0.1:18080`, `PRODUCT_CODE`, `MEMBER_TOKEN`, `ADMIN_TOKEN`이다. 각 응답은 `success/data/error` 봉투이며, 명령은 `jq`와 `curl`을 사용한다.

### 구매와 지급

주문 생성 후 `OrderCreated`가 Payment에 반영될 때까지 `GET /api/v1/payments/{orderNo}`를 폴링한다. 아직 `404`라면 잠시 후 다시 조회하고, 결제 기록을 확인한 뒤 `prepare`를 호출한다. 아래 명령은 단계별로 실행한다. 승인 뒤 라이브러리·다운로드 반영도 비동기이므로 상태를 확인한 뒤 다음 조회를 진행한다.

```sh
PRODUCT=$(curl -fsS "$BASE/api/v1/products/by-code/$PRODUCT_CODE" | jq -c '.data')
printf '%s' "$PRODUCT" | jq '{productId,productKind,price,currency,purchasable}'
PRODUCT_ID=$(printf '%s' "$PRODUCT" | jq -r '.productId')
PRICE=$(printf '%s' "$PRODUCT" | jq -r '.price')
ORDER_NO=$(jq -nc --argjson id "$PRODUCT_ID" --argjson price "$PRICE" \
  '{items:[{productId:$id,quantity:1}],expectedAmount:$price}' | \
  curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" -H 'Content-Type: application/json' \
    -d @- "$BASE/api/v1/orders" | jq -r '.data.orderNo')
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" "$BASE/api/v1/orders/$ORDER_NO" | jq '.data'
PREPARED=$(curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"method":"CARD"}' "$BASE/api/v1/payments/$ORDER_NO/prepare" | jq -c '.data')
PG_TX_ID=$(printf '%s' "$PREPARED" | jq -r '.pgTxId')
printf '%s' "$PREPARED" | jq '{amount,currency,redirectUrl}'
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" \
  "$BASE/api/v1/payments/simulator/checkout/$PG_TX_ID" | jq '.data'
curl -fsS -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$BASE/api/v1/payments/simulator/$ORDER_NO/approve" | jq '.data'
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" "$BASE/api/v1/payments/$ORDER_NO" | jq '.data'
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" "$BASE/api/v1/library" | jq '.data'
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" \
  "$BASE/api/v1/downloads/$PRODUCT_CODE/ticket" | jq '.data'
```

주문은 카탈로그의 서버 견적으로 금액을 다시 계산한다. `expectedAmount` 위조는 `PRICE_MISMATCH`로 거절한다. 조회되는 주문의 `lines`에는 주문 시점 상품 ID·이름·판매자·단가·수량이, 주문에는 청구액·통화가 저장된다. `GET /orders/{orderNo}`와 `GET /payments/{orderNo}`의 `status`, `retryable`로 진행 상태를 확인한다. `READY/PENDING` 결제는 처리 중, `FAILED`는 종단 상태이므로 새 주문으로 다시 시도한다. `CANCELING`은 환불 재개 대상이고 `CANCELED`는 완료다. 이벤트 반영 전에는 라이브러리와 주문 상태를 폴링한다.

회원이 직접 환불하려면 `POST /api/v1/payments/{orderNo}/cancel?reason=USER_REFUND`를 회원 토큰으로 호출한다. 시뮬레이터에서 공급자 환불을 재현하려면 운영자 토큰으로 `POST /api/v1/payments/simulator/{orderNo}/refund`를 호출한다. 둘 다 동일한 `RefundFacade`와 `PgClient.cancel` 경로를 거치며, 결제 `CANCELED` 뒤 주문도 `CANCELED`, 라이브러리의 해당 `orderNo` 항목은 제거되고 다운로드 티켓은 `403`이 된다. 반복 환불은 멱등이다.

### 실패 및 격리 재현

새 주문마다 `prepare`를 다시 호출한다. 운영자 토큰으로 `POST /api/v1/payments/simulator/{orderNo}/decline`에 `{"reasonCode":"CARD_DECLINED","reason":"카드 거절"}`을 보내면 결제·주문이 `FAILED`가 된다. `POST .../{orderNo}/timeout`은 `PG_TIMEOUT`으로 같은 종단 상태를 만든다. 승인·거절을 같은 주문에 다시 보내면 중복 결과가 흡수되며, 거절 뒤 승인은 충돌로 거부된다. 일반 회원이 제어 API를 호출하면 `403`, 다른 회원이 `checkout/{pgTxId}` 또는 주문·결제 조회를 하면 `403`이다. 구매자용 결제창 조회는 회원 JWT와 주문 소유권을 모두 확인한다.

### 공급자 어댑터 계약

`PgClient.prepare(orderNo, amount, currency, method)`에는 주문에서 확정된 값만 보낸다. 공급자가 반환한 `pgTxId`를 결제에 저장하고, 승인 사실은 검증된 공급자 콜백 또는 공급자 조회에서만 `PgApproval(orderNo, pgTxId, paidAmount, idempotencyKey)`로 정규화한다. `PaymentService.handleApproval`이 저장된 거래 ID·금액과 대조하고 잠금·멱등 검사를 거친 뒤 `PaymentCompleted`를 기록한다. 결제창 URL이나 브라우저 성공 결과는 이 호출을 만들 권한이 없다. 거절은 `PgDecline(orderNo, pgTxId, reasonCode, reason)`으로 같은 거래 ID를 검증해 `PaymentFailed`를 기록한다. `PgClient.cancel(pgTxId, amount, reason)`은 같은 거래에 여러 번 호출해도 환불이 한 번이어야 한다.

현재 로컬 콜백은 [원문 HMAC·시각 검증 계약](security.md)을 공급자 인증으로 사용한다. 시뮬레이터는 사전등록 거래 기록을 잠그고 해당 기록의 승인·거절·시간 초과 사실을 만든 뒤 위 결제 유스케이스에 전달한다. 포트원 어댑터(#82)는 실제 공급자의 웹훅 서명과 거래 조회로 이 사실을 검증하고 같은 내부 값으로 전달해야 한다. `prod`에는 별도 `PgClient` 구현·공급자 검증 구성이 필요하며 이 데모 시뮬레이터를 사용하지 않는다.

자동 검증은 `./gradlew :apps:payment:integrationTest`, `./gradlew :e2e:e2eTest`로 실행한다. E2E는 출시 상품을 구매해 다운로드한 뒤 회수하며, 승인·중복·거절·시간 초과·타인 접근과 금액 위조를 검증한다. 일반 프로필과 데모 프로필의 Payment OpenAPI 계약은 각각 `payment.json`, `payment-demo.json`에 고정한다.
