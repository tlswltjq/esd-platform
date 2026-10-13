# auth와 Gateway/BFF

**재작업 완료 모듈: `apps/auth`, `apps/gateway` (2026-10-13).**
관련 테스트 132개가 통과했으며 실제 스택에서 자동 로그인, 토큰 릴레이, 다중 Gateway의
동시 갱신과 RTR 재사용 탐지, BFF·auth 로그아웃, 재시작 유지, Redis 장애 시 503 및 복구를 확인했다.

브라우저는 Gateway의 세션 쿠키를 사용한다. Gateway는 기밀 OAuth 클라이언트
`gateway-bff`로 Authorization Code + PKCE를 수행하고 Access/Refresh Token을 Redis에
보관한다. auth가 인증, 토큰 발급, 회전 및 재사용 탐지를 담당한다.

## 로컬 실행

```sh
docker compose up -d mysql mysql-bootstrap kafka redis
export BFF_CLIENT_SECRET="$(openssl rand -hex 32)"
export AUTH_ISSUER=http://localhost:8080
export JWT_ISSUER_URI=http://localhost:8080
./gradlew :apps:auth:bootRun
```

다른 터미널에서도 **동일한** `BFF_CLIENT_SECRET`, `AUTH_ISSUER`, `JWT_ISSUER_URI`를 설정하고
`./gradlew :apps:gateway:bootRun`을 실행한다. 브라우저에서 `http://localhost:8080/`을 열고
로그인을 누르면 PKCE 생성부터 callback 및 토큰 교환까지 자동 처리된다.
단독 auth 기동에도 BFF 클라이언트 등록을 위한 secret이 필요하다.

| 경로 | 동작 |
|---|---|
| `GET /oauth2/authorization/gateway-bff` | BFF 로그인 시작 |
| `GET /login/oauth2/code/gateway-bff` | BFF callback; 직접 호출하지 않음 |
| `GET /bff/session` | 검증된 사용자 식별자와 역할; 토큰은 반환하지 않음 |
| `GET /bff/csrf` | 변경 요청용 CSRF 헤더 이름과 토큰 |
| `POST /bff/logout` | Refresh Token 폐기, BFF 세션 제거, auth OIDC 로그아웃 이동 |
| `POST /api/v1/auth/password` | 현재 비밀번호 확인 후 변경, 해당 회원의 모든 Refresh Token 계열 폐기 |

비밀번호 변경 JSON은 `currentPassword`, `newPassword`이며 새 비밀번호는 12~100자이다.
쿠키 인증의 변경 요청에는 `/bff/csrf` 응답에 표시된 헤더와 토큰을 보낸다.
로그아웃을 HTML 폼으로 제출하는 경우 `_csrf` 필드에 반환된 토큰을 넣는다.

## 토큰 및 갱신

- Access Token: 기본 15분. Gateway와 업무 서비스에서 서명, 만료, issuer, audience 검증.
- Refresh Token: 최초 발급부터 7일의 절대 상한. 갱신해도 상한을 연장하지 않는다.
- 현재 토큰은 Spring JDBC 인가 저장소에, 사용 이력은 SHA-256 해시로 별도 저장한다.
- 계열별 MySQL 잠금 아래에서 갱신한다. 소비된 토큰 재사용은 계열 전체를 폐기한다.
- 갱신 시 DB의 계정 상태와 최신 역할을 확인한다. 비활성 계정의 갱신은 거부한다.
- Redis 토큰 키는 로그인 때 변경되는 세션 ID와 독립된 세션 속성에 연결한다.
- Gateway의 Redis 갱신 잠금은 15초, 토큰 요청 timeout은 5초이다. 불확실한 전송 실패는
  이전 Refresh Token으로 자동 재시도하지 않고 재로그인을 요구한다.
- 로그아웃·재사용 탐지·비밀번호 변경은 Refresh Token을 폐기한다. 기존 JWT는 만료까지
  유효하다. auth 로그인 세션의 다른 기기 전체 종료는 비밀번호 변경과 별도의 정책이다.

## 확장 및 배포

Gateway 여러 개가 동일 Redis, 세션 namespace, 클라이언트 자격증명을 사용한다.
로드밸런서의 공개 주소로 `AUTH_ISSUER`, `JWT_ISSUER_URI`, `BFF_PUBLIC_AUTH_BASE`,
`BFF_REDIRECT_URI`를 맞춘다. 인스턴스 내부 주소로 callback을 등록하지 않는다.
`AUTH_URI`와 JWK 조회 주소는 내부 auth 주소를 사용할 수 있다.

HTTPS 배포에서는 신뢰할 수 있는 프록시의 forwarded 헤더를 사용하고 세션 쿠키의
Secure를 보장한다. Redis에는 OAuth 토큰이 저장되므로 접근 인증, 암호화 연결과
운영 환경에 맞는 영속성을 설정한다. 쿠키 인증에는 CSRF 보호를 유지한다.
현재 로컬 Compose Redis는 메모리 저장이므로 Redis 자체를 재시작하면 세션이 사라진다.
Gateway와 auth의 재시작은 공유 Redis·MySQL 상태를 유지한다. 세션 저장소 장애는
인증을 우회하지 않고 `503 SESSION_STORE_UNAVAILABLE`로 반환한다.
Docker Compose의 `bff-local-only` 값은 로컬 개발용이며 배포 시 secret으로 대체한다.

기존 `studio-web`, `swagger-ui` 공개 클라이언트는 유지한다. 이 클라이언트는
Refresh Token을 받지 않는다. Gateway의 Bearer Token API 접근도 기존대로 지원한다.

## 검증

```sh
./gradlew :apps:auth:test :apps:auth:integrationTest :apps:gateway:test :apps:gateway:integrationTest :common:web:test
```

auth 통합 테스트는 실제 MySQL에서 OIDC, RTR, 재사용, 동시 갱신, 만료, 계정 비활성화,
폐기를 검증한다. Gateway 테스트는 JWT 변조·만료·issuer·audience를 검증한다.
공유 세션 검증은 두 번째 Gateway를 `--args='--server.port=8081'`로 실행한다.
테스트용 짧은 Access Token은 auth에 `BFF_ACCESS_TOKEN_SECONDS=30`을 설정한다.
두 Gateway가 실행된 상태에서 같은 `BFF_CLIENT_SECRET`을 설정하고
`python3 scripts/verification/auth-bff.py`를 실행하면 callback 인스턴스 변경,
토큰 릴레이, CSRF, 동시 갱신, 재사용 탐지 및 로그아웃을 실제 HTTP로 검증한다.
이 검증은 테스트 회원을 생성하고 테스트 계열에 의도적으로 재사용을 발생시킨다.
