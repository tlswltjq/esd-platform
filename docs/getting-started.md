# 개발 환경과 실행

[문서 목록](README.md) · [전체 흐름 검증](settlement-guide.md) · [인증·권한 계약](security.md)

명령은 저장소 루트에서 실행한다. 전체 스택에는 여러 JVM과 저장소가 함께 올라가므로 Docker에 충분한 메모리를 할당한다. 필요한 모듈별 격리 검증을 선택한다. 현재 개발·검증은 로컬 OrbStack에서 수행한다.

## 개발 환경과 테스트

Gradle Wrapper를 사용한다. 컴파일 툴체인은 JDK 21이며 `settings.gradle`의 foojay 리졸버가 조달한다. Wrapper 실행용 JVM은 JDK 17 이상이 필요하다. 전체 스택·통합 테스트에는 Docker와 Compose가 필요하고, 전체 흐름 검증은 [Settlement 안내](settlement-guide.md)를 따른다.

| 검증 | 명령 | 실행 조건 |
|---|---|---|
| 단위·어댑터·아키텍처 규칙 | `./gradlew test` | Docker 불필요 |
| 실제 저장소·메시징 통합 | `./gradlew integrationTest` | Testcontainers용 Docker |
| 전체 구매·다운로드·정산 | `python3 tools/verification/verify-jib.py` | 로컬 OrbStack, 이미지 빌드 포함 |
| 테스트 포함 빌드 | `./gradlew build` | Docker 필요 |

macOS에서 Testcontainers가 Docker 소켓을 찾지 못하면 활성 컨텍스트의 값을 전달한다.

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
./gradlew integrationTest
```

머신마다 다른 소켓 절대경로는 저장소나 공용 설정에 고정하지 않는다. 검증 범위와 테스트 작성 규칙은 [테스트 전략](testing.md)을 참고한다.

## 로컬 스택과 Swagger

Compose는 기본 정의 2개와 로컬 오버라이드 2개를 사용한다. 기본 파일은 호스트 포트를 열지 않으며, 로컬·E2E 접속에 필요한 포트는 `127.0.0.1`로만 연다.

| 기본 정의 | 로컬·E2E 오버라이드 |
|---|---|
| `docker-compose.yml` — 인프라 | `docker-compose.local.yml` — 인프라 접속 포트 |
| `docker-compose.apps.yml` — Jib 앱·PG DB | `docker-compose.apps.local.yml` — 앱 접속 포트·OAuth·업로드 주소 |

`local-stack.sh`와 원격 검증 스크립트는 각 기본 파일에 local 오버라이드 하나를 적용한다. CI의 내부 네트워크 실행은 기본 파일만 사용한다. 적용 순서와 검증 기록은 [Compose 실행 구성](compose-layout.md)을 따른다.

신규 빈 스택은 외부 모의 PG의 세 역할 키와 전용 DB 비밀번호, Catalog 정산 조회 키를 먼저 설정한다.
기존 V11 내장 PG 자료가 있으면 [이관 절차](settlement-guide.md#기존-내장-pg-이관)를 거쳐 연결한다.

```bash
export MOCK_PG_PAYMENT_KEY="$(openssl rand -hex 32)"
export MOCK_PG_SETTLEMENT_KEY="$(openssl rand -hex 32)"
export MOCK_PG_CONTROL_KEY="$(openssl rand -hex 32)"
export MOCK_PG_DB_PASSWORD="$(openssl rand -hex 32)"
export MOCK_PG_DB_ROOT_PASSWORD="$(openssl rand -hex 32)"
export CATALOG_SETTLEMENT_SERVICE_KEY="$(openssl rand -hex 32)"
```

재기동할 때도 같은 DB 비밀번호·클라이언트 키 설정을 사용한다.

```bash
./scripts/local-stack.sh up
./scripts/local-stack.sh ps
./scripts/local-stack.sh logs auth
./scripts/local-stack.sh down
```

`up`은 `jibDockerBuild`로 업무 앱·외부 모의 PG·MinIO 이미지를 생성하고 인프라와 앱을 순서대로 시작한다. 앱은 health 성공을 확인하며 최대 세 개씩 시작하고 Store는 Catalog 다음에 시작한다. `down`은 컨테이너를 종료하고 볼륨은 보존한다.
현재 구매·다운로드·정산의 격리된 전체 검증은 `python3 tools/verification/verify-settlement.py`를 사용한다. [정산 실행 안내](settlement-guide.md)를 따른다.

MinIO와 mc는 `:infra:minio:compileMinio`가 고정 커밋의 공식 소스를 Go 컨테이너에서 빌드하고 Jib이 Alpine 이미지에 패키징한다. 호스트에 Go를 설치할 필요는 없다. 첫 실행에는 Go 의존성 다운로드와 컴파일 시간이 추가된다. 파일 업로드용 MinIO 주소는 앱 설정과 같은 `http://127.0.0.1:19000`이다.

Studio·Review·Download 통합 테스트도 기본적으로 같은 이미지를 자동 준비한다. 미리 공급한 이미지는 `STOVE_MINIO_IMAGE`로 지정하고, 테스트 전용 변경은 `STOVE_TEST_MINIO_IMAGE`로 지정한다. 지정한 이미지는 필요한 MinIO·mc 명령을 제공해야 한다.

이미지 생성만 수행하려면 `./gradlew jibDockerBuild`, MinIO만 생성하려면 `./gradlew :infra:minio:jibDockerBuild`를 사용한다. 레지스트리·앱 태그는 `STOVE_JIB_REGISTRY`·`STOVE_IMAGE_TAG` 또는 `stove.jib.registry`·`stove.jib.tag` Gradle 프로퍼티로 지정한다. 게시에는 `./gradlew jib`를 사용하고 배포 환경에서는 같은 이미지 이름을 설정해 pull한 뒤 `scripts/start-apps.sh`로 시작한다. 호스트 접속이 필요하면 `-f docker-compose.apps.yml -f docker-compose.apps.local.yml`을 적용한다. MinIO의 태그는 기존 릴리즈 이름을 유지하며 `STOVE_JIB_REGISTRY`를 함께 따른다. `STOVE_MINIO_IMAGE`로 사전 공급 이미지를 지정하면 MinIO 소스 빌드·Jib 패키징을 건너뛴다.

브라우저에서 [Swagger UI](http://127.0.0.1:18080/swagger-ui.html)를 열고 대상 서비스를 선택한다. Authorize는 공개 클라이언트 `swagger-ui`의 Authorization Code + PKCE를 사용한다. 창작·심사는 `studio`, 구매·결제는 `commerce` 범위를 요청하고 필요한 계정으로 로그인한다. 한 세션에서 `localhost`와 `127.0.0.1`을 섞지 않는다.

Studio의 전체 ZIP 업로드·단일 심사·출시·재출시는 [Studio 실행·출시](studio-guide.md)를 따른다.

### Swagger OAuth 문제 해결

| 증상 | 확인할 설정과 이유 |
|---|---|
| 로그인 후 리다이렉트·세션이 끊김 | Swagger·로그인·승인·토큰 교환을 같은 Gateway 주소로 통일. Auth 라우트는 `PreserveHostHeader`, OpenAPI OAuth URL은 상대 경로를 사용 |
| 로그인 프레임에 `chrome-error://chromewebdata`, 로그인 POST가 403 | Auth·Gateway의 `X-Frame-Options: SAMEORIGIN`과 라우트 확인. Gateway CORS 대상은 `/api/**`, `/oauth2/token`; 로그인·승인·로그아웃은 같은 출처의 폼·팝업 이동 |
| 로그인 화면의 CSS가 JSON 404로 응답 | Gateway의 `/default-ui.css` 라우트 확인. 정상 응답은 Auth의 `200 text/css` |
| 이미지 생성과 테스트를 분리해 실행해야 함 | 이미지는 `./gradlew jibDockerBuild`, 검증은 `./gradlew test integrationTest`로 실행 |
| OAuth 클라이언트·범위를 직접 지정해야 함 | `swagger-ui` 클라이언트와 PKCE, 대상 서비스의 `studio`·`commerce` 범위 설정 확인 |

설정을 바꾼 뒤에는 Gateway 라우트 테스트와 브라우저 인증을 확인한다. `/default-ui.css`의 응답 형식, 허용된 로컬 Origin에 대한 `OPTIONS /oauth2/token`, 로그인·승인 리다이렉트를 함께 본다. 관련 테스트는 `:apps:gateway:test --tests com.stove.gateway.GatewayRouteTest`다.

## 선택적 원격 관리 도구

로컬에 `bash`, `ssh`, `rsync`가 필요하다. SSH 접속과 원격 실행 환경을 준비한 뒤 호스트 별칭을 로컬 Git 설정에 저장한다.

```bash
git config stove.remote <ssh-별칭>
./tools/remote/remote.sh test
./tools/remote/remote.sh test :apps:order
./tools/remote/remote.sh stack up
./tools/remote/remote.sh gate
./tools/remote/remote.sh e2e
./tools/remote/remote.sh http GET catalog:8081/api/v1/products
./tools/remote/remote.sh logs catalog -n 100 -g 승인
./tools/remote/remote.sh status
```

[remote.sh](../tools/remote/remote.sh)는 rsync로 현재 작업본을 보내므로 커밋 전 변경도 검증한다. 현재 작업 기준은 로컬 실행이다. 원격 도구는 기존 환경 관리용으로 보존한다. `e2e` 명령은 원격의 기존 이미지로 별도 격리 Jib 검증을 실행한다. 테스트 개수·컨테이너 개수는 실행 결과와 현재 스크립트를 기준으로 확인한다.

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
