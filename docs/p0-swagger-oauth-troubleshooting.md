# P0 Swagger OAuth 실습 문제 해결 기록

## 문제

P0 크리에이터 API를 Swagger UI에서 브라우저로 확인하려고 했을 때 다음 문제가 순서대로 발생했다.

- 직접 Auth 포트와 Gateway 포트를 섞어 사용하면 OAuth 리다이렉트와 세션 쿠키의 출처가 달라졌다.
- 로그인 POST가 403이 되거나 `chrome-error://chromewebdata` 프레임 오류가 표시됐다.
- Auth 로그인 화면의 `/default-ui.css`가 Gateway를 통과하지 못해 CSS 대신 JSON 404를 받았다.
- 로컬 스택 기동 명령이 `integrationTest`에서 중단되어 앱 컨테이너가 뜨지 않았다.
- Swagger에서 OAuth 범위와 PKCE 클라이언트를 직접 설정해야 했다.

## 원인과 해결

### Gateway 한 출처 원칙

Swagger, 로그인, OAuth 승인·토큰 교환을 모두 Gateway의 `127.0.0.1:18080`으로 통일했다. Auth 서비스의 내부 포트는 Compose 네트워크에서만 사용한다. Auth의 OAuth 엔드포인트는 Gateway가 전달한 Host를 유지하도록 `PreserveHostHeader`를 적용했다.

### Swagger PKCE 클라이언트

Auth에 공개 클라이언트 `swagger-ui`를 등록하고 Authorization Code + PKCE를 요구한다. `127.0.0.1:18080`, `localhost:18080`, 기존 `8080`의 Swagger callback을 등록해 개발 환경의 주소를 수용한다. Studio와 Review 명세에는 상대 경로 OAuth URL을 사용해 Swagger가 현재 출처를 재사용한다.

### 로그인 프레임과 CORS

Spring Security 기본 `X-Frame-Options: DENY`는 Swagger OAuth 로그인 프레임을 `chrome-error://chromewebdata`로 바꿀 수 있었다. Auth와 Gateway는 같은 출처 프레임만 허용하도록 `SAMEORIGIN`을 사용한다.

Gateway의 전역 CORS가 `chrome-error://chromewebdata` Origin을 로그인 경로에서 거부하면 403이 되므로 CORS 적용 대상을 `/api/**`와 `/oauth2/token`으로 제한했다. 로그인·승인·로그아웃은 같은 출처의 폼 또는 팝업 이동이므로 CORS가 필요하지 않다.

### Auth 정적 리소스

Spring Security 기본 로그인 페이지가 참조하는 `/default-ui.css`를 Gateway의 Auth 브라우저 라우트에 추가했다. 이 경로는 Auth의 `text/css` 응답을 그대로 전달한다.

### 로컬 스택 기동

인프라와 앱 Compose 파일은 CI 격리를 위해 유지하되 `scripts/local-stack.sh`를 로컬 단일 진입점으로 제공한다. Dockerfile이 이미 생성된 JAR를 복사하므로 `up --build` 전에 `bootJar`를 실행한다. `test`만 제외하면 `integrationTest`가 실행되므로 두 테스트 태스크를 모두 제외한다.

## 검증

- `./gradlew bootJar -x test -x integrationTest --no-daemon` 성공
- `./gradlew :apps:gateway:test --tests com.stove.gateway.GatewayRouteTest --no-daemon` 성공
- `GET /default-ui.css` → `200 text/css`
- `POST /login`에 `Origin: chrome-error://chromewebdata`를 넣어도 Gateway CORS 403 없이 Auth의 302 응답 확인
- `OPTIONS /oauth2/token`에 허용된 로컬 Origin을 넣으면 CORS preflight 200 확인
- Swagger에서 `openid`, `profile`, `studio` Scope를 선택하고 PKCE 인증 완료 확인

## 사용법

```bash
./scripts/local-stack.sh up
```

그 다음 `http://127.0.0.1:18080/swagger-ui.html`에서 `7. studio`를 선택하고 Authorize를 실행한다. `localhost`와 `127.0.0.1`을 한 세션에서 섞지 않는다.
