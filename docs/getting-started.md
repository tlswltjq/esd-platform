# 개발 환경과 실행

[문서 목록](README.md) · [전체 API 데모](api-demo.md) · [인증·권한 계약](security.md)

명령은 저장소 루트에서 실행한다. 전체 스택에는 여러 JVM과 저장소가 함께 올라가므로 Docker에 충분한 메모리를 할당한다. 자원이 부족하면 원격 실행 경로를 사용한다.

## 개발 환경과 테스트

Gradle Wrapper를 사용한다. 컴파일 툴체인은 JDK 21이며 `settings.gradle`의 foojay 리졸버가 조달한다. Wrapper 실행용 JVM은 JDK 17 이상이 필요하다. 전체 스택·통합 테스트에는 Docker와 Compose가 필요하고, 자동 API 데모의 추가 조건은 [데모 준비](api-demo.md#full-demo)를 따른다.

| 검증 | 명령 | 실행 조건 |
|---|---|---|
| 단위·어댑터·아키텍처 규칙 | `./gradlew test` | Docker 불필요 |
| 실제 저장소·메시징 통합 | `./gradlew integrationTest` | Testcontainers용 Docker |
| 전체 API 인수 검증 | `./gradlew :e2e:e2eTest` | 실행 중인 전체 스택과 테스트 인증 설정 |
| 테스트 포함 빌드 | `./gradlew build` | Docker 필요 |

macOS에서 Testcontainers가 Docker 소켓을 찾지 못하면 활성 컨텍스트의 값을 전달한다.

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
./gradlew integrationTest
```

머신마다 다른 소켓 절대경로는 저장소나 공용 설정에 고정하지 않는다. 검증 범위와 테스트 작성 규칙은 [테스트 전략](testing.md)을 참고한다.

## 로컬 스택과 Swagger

```bash
./scripts/local-stack.sh up
./scripts/local-stack.sh ps
./scripts/local-stack.sh logs auth
./scripts/local-stack.sh down
```

`up`은 인프라를 띄우고 `bootJar -x test -x integrationTest`로 앱을 빌드한 뒤 앱 컨테이너를 시작한다. `down`은 컨테이너를 종료하고 볼륨은 보존한다. 일괄 상태 검사와 가입부터 정산까지 자동 검증하려면 [API 데모](api-demo.md#full-demo)를 사용한다.

로컬 스크립트는 `docker-compose.local.yml`을 적용해 MinIO의 지정 버전 공식 소스로 이미지를 빌드한다. 공개 이미지 저장소 접근 오류를 피하기 위한 설정이며, 첫 실행에는 Go 의존성 다운로드와 컴파일 시간이 추가된다. 파일 업로드용 MinIO 주소는 앱 설정과 같은 `http://127.0.0.1:19000`이다.

저장소 통합 테스트에서도 이 이미지를 쓰려면 `STOVE_TEST_MINIO_IMAGE=stove/minio:RELEASE.2025-04-22T22-12-26Z-local`을 설정한다. 환경변수가 없으면 기존 공개 이미지 릴리즈를 사용한다.

브라우저에서 [Swagger UI](http://127.0.0.1:18080/swagger-ui.html)를 열고 대상 서비스를 선택한다. Authorize는 공개 클라이언트 `swagger-ui`의 Authorization Code + PKCE를 사용한다. 창작·심사는 `studio`, 구매·결제는 `commerce` 범위를 요청하고 필요한 계정으로 로그인한다. 한 세션에서 `localhost`와 `127.0.0.1`을 섞지 않는다.

Studio의 전체 ZIP 업로드·단일 심사·출시·재출시는 [Studio 실행·출시](studio-guide.md)를 따른다.

### Swagger OAuth 문제 해결

| 증상 | 확인할 설정과 이유 |
|---|---|
| 로그인 후 리다이렉트·세션이 끊김 | Swagger·로그인·승인·토큰 교환을 같은 Gateway 주소로 통일. Auth 라우트는 `PreserveHostHeader`, OpenAPI OAuth URL은 상대 경로를 사용 |
| 로그인 프레임에 `chrome-error://chromewebdata`, 로그인 POST가 403 | Auth·Gateway의 `X-Frame-Options: SAMEORIGIN`과 라우트 확인. Gateway CORS 대상은 `/api/**`, `/oauth2/token`; 로그인·승인·로그아웃은 같은 출처의 폼·팝업 이동 |
| 로그인 화면의 CSS가 JSON 404로 응답 | Gateway의 `/default-ui.css` 라우트 확인. 정상 응답은 Auth의 `200 text/css` |
| 빌드 중 통합 테스트가 실행되어 앱 기동 중단 | 직접 JAR를 만들 때는 `./gradlew bootJar -x test -x integrationTest --no-daemon` 사용. `test`만 제외하면 통합 테스트는 실행됨 |
| OAuth 클라이언트·범위를 직접 지정해야 함 | `swagger-ui` 클라이언트와 PKCE, 대상 서비스의 `studio`·`commerce` 범위 설정 확인 |

설정을 바꾼 뒤에는 Gateway 라우트 테스트와 브라우저 인증을 확인한다. `/default-ui.css`의 응답 형식, 허용된 로컬 Origin에 대한 `OPTIONS /oauth2/token`, 로그인·승인 리다이렉트를 함께 본다. 관련 테스트는 `:apps:gateway:test --tests com.stove.gateway.GatewayRouteTest`다.

## 원격 실행

로컬에 `bash`, `ssh`, `rsync`가 필요하다. SSH 접속과 원격 실행 환경을 준비한 뒤 호스트 별칭을 로컬 Git 설정에 저장한다.

```bash
git config stove.remote <ssh-별칭>
./scripts/remote.sh test
./scripts/remote.sh test :apps:order
./scripts/remote.sh stack up
./scripts/remote.sh gate
./scripts/remote.sh e2e
./scripts/remote.sh http GET catalog:8081/api/v1/products
./scripts/remote.sh logs catalog -n 100 -g 승인
./scripts/remote.sh status
```

[remote.sh](../scripts/remote.sh)는 rsync로 현재 작업본을 보내므로 커밋 전 변경도 검증한다. 원격 전체 데모와 인증 값 준비는 [API 데모](api-demo.md#full-demo)를 따른다. 테스트 개수·컨테이너 개수는 실행 결과와 현재 스크립트를 기준으로 확인한다.

## 장애 확인과 재처리

응답의 `X-Correlation-Id`로 로그와 트레이스를 찾는다. [추적 방식](architecture.md#tracing), [이벤트 순서와 재투입 조건](event-ordering.md)을 먼저 확인한 뒤 실패 원인을 해결하고 재처리한다.

운영 API는 `ADMIN` access token이 필요하다. 아래는 로컬 E2E 포트 구성의 조회 예시다. Gateway가 운영 경로를 공개하지 않으므로 해당 서비스 포트로 호출한다.

```bash
curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://127.0.0.1:18083/api/v1/ops/outbox/dead
curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" \
  'http://127.0.0.1:18089/api/v1/ops/dlt?topic=stove.payment.v1.DLT'
```

Outbox 회수·DLT 재투입 API의 요청 계약은 [운영 API 명세](services.md#운영-api)에 있다. 재투입은 원래 순서를 복원하지 않으므로 현재 주문·환불 상태도 확인해야 한다. 이용권 원장 자체가 유실되었다면 [DB 복구 절차](runbooks/license-db-loss.md)를 따른다.
