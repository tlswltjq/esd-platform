# 설계: Gateway

## 아키텍처 위치

- 포트 8080, 저장소 Redis. Spring Cloud Gateway 기반이다.
- 플랫폼의 단일 외부 진입점이며, 라우팅과 함께 내부 전용 API를 외부에 노출하지 않는 역할을 한다.
- 재작업 후 BFF를 겸한다. 기밀 OAuth 클라이언트 `gateway-bff`로 Authorization Code + PKCE를 수행하고, Auth(8091)가 인증·토큰 발급·회전·재사용 탐지를 담당한다.
- 표의 포트는 서비스 내부 포트다. 외부 요청은 보통 Gateway의 로컬 E2E 호스트 포트 `18080`을 사용한다.

## 핵심 설계 결정

- **외부 노출 통제는 라우트의 부재로 표현한다.** 필터가 아니라 라우팅 표에 경로를 두지 않는 방식이며, `GatewayRouteTest`가 성질을 고정한다. 명세에 보인다고 호출할 수 있는 것은 아니다. 도달 가능성은 라우팅 표가 정한다.
- **catalog는 GET만 노출한다.** `/api/v1/products/**` 라우트에 `Method=GET` 조건이 걸려 있다. 따라서 주문 금액을 재계산하는 `POST /api/v1/products/quote`는 Gateway를 통해 호출할 수 없고, 서비스 간 내부 호출로만 도달한다.
- **운영 경로는 열지 않는다.** Gateway에는 `/api/v1/ops/**` 라우트가 없다. 운영 기능은 해당 기능이 활성화된 서비스에 ADMIN으로 직접 접근한다.
- **문서 프록시는 좁게 연다.** Swagger UI를 Gateway가 서빙하므로 `/v3/api-docs/<service>` 프록시 라우트를 서비스당 한 경로씩 GET만 올리고 하위 경로로 넓히지 않는다.
- **BFF 토큰 보관.** Access/Refresh Token은 Redis에 두고 브라우저에는 세션 쿠키만 내려준다. Redis 토큰 키는 로그인 때 바뀌는 세션 ID와 독립된 세션 속성에 연결한다.
- **세션 저장소 장애 시 차단.** 세션 저장소에 접근할 수 없으면 인증을 우회하지 않고 `503 SESSION_STORE_UNAVAILABLE`로 반환한다.

## 저장소

- Redis에 BFF 세션과 OAuth Access/Refresh Token을 보관한다. 업무 DB를 두지 않는다.
- Redis에 실리는 값은 `GenericJackson2JsonRedisSerializer`로 직렬화되므로, setter 없는 엔티티를 직접 싣지 않고 직렬화 가능한 모델을 사용한다.
- 현재 로컬 Compose Redis는 메모리 저장이므로 Redis 자체 재시작 시 세션이 사라진다. Gateway·auth의 재시작은 공유 Redis·MySQL 상태를 유지한다.

## 라우팅 표

| 경로 | 대상 | 비고 |
|---|---|---|
| `/api/v1/auth/**`, `/oauth2/**`, `/login`, `/logout`, `/.well-known/**` | auth | 가입·로그인·토큰, 브라우저 경로의 Host 유지 |
| `/api/v1/storefront/**` | store | |
| `/api/v1/products/**` | catalog | **GET만 허용** |
| `/api/v1/promotions/**` | catalog | 판매자 `CREATOR`, 플랫폼 `ADMIN` |
| `/api/v1/orders/**` | order | |
| `/api/v1/payments/**` | payment | |
| `/api/v1/library/**` | license | |
| `/api/v1/downloads/**` | download | |
| `/api/v1/studio/**` | studio | |
| `/api/v1/reviews/**` | review | 운영 |
| `/api/v1/settlements/**` | settlement | `/me/**`는 `CREATOR`, 나머지는 `ADMIN` |
| `/v3/api-docs/<service>` | 각 서비스 | GET 전용 문서 프록시, 서비스당 한 경로 |

## BFF API 계약

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/oauth2/authorization/gateway-bff` | BFF 로그인 시작 |
| GET | `/login/oauth2/code/gateway-bff` | BFF callback; 직접 호출하지 않음 |
| GET | `/bff/session` | 검증된 사용자 식별자와 역할. 토큰은 반환하지 않음 |
| GET | `/bff/csrf` | 변경 요청용 CSRF 헤더 이름과 토큰 |
| POST | `/bff/logout` | Refresh Token 폐기, BFF 세션 제거, auth OIDC 로그아웃 이동 |

- 비밀번호 변경 등 쿠키 인증의 변경 요청에는 `/bff/csrf` 응답에 표시된 헤더와 토큰을 보낸다. HTML 폼 로그아웃은 `_csrf` 필드에 토큰을 넣는다.
- 기존 `studio-web`·`swagger-ui` 공개 클라이언트는 유지한다. 이 클라이언트는 Refresh Token을 받지 않는다. Gateway의 Bearer Token API 접근도 기존대로 지원한다.

## 토큰·갱신 동작 요약

- Access Token 기본 15분. Gateway와 업무 서비스에서 서명·만료·issuer·`esd-api` audience를 검증한다. Gateway 테스트는 JWT 변조·만료·issuer·audience를 검증한다.
- Refresh Token은 최초 발급부터 7일 절대 상한이며 갱신이 상한을 연장하지 않는다(Auth가 회전·재사용 탐지를 담당).
- Redis 갱신 잠금은 기본 15초, 토큰 요청 timeout은 기본 5초다.
- 전송 실패가 불확실하면 이전 Refresh Token으로 자동 재시도하지 않고 재로그인을 요구한다.
- 로그아웃·재사용 탐지·비밀번호 변경이 Refresh Token을 폐기한다. 기존 JWT는 만료까지 유효하다.

## 확장·배포

- 여러 Gateway가 동일 Redis, 세션 namespace, 클라이언트 자격증명을 공유한다.
- 로드밸런서의 공개 주소로 `AUTH_ISSUER`, `JWT_ISSUER_URI`, `BFF_PUBLIC_AUTH_BASE`, `BFF_REDIRECT_URI`를 맞춘다. 인스턴스 내부 주소로 callback을 등록하지 않는다. `AUTH_URI`와 JWK 조회 주소는 내부 auth 주소를 쓸 수 있다.
- HTTPS 배포에서는 신뢰할 수 있는 프록시의 forwarded 헤더를 사용하고 세션 쿠키의 Secure를 보장한다. Redis에는 OAuth 토큰이 저장되므로 접근 인증·암호화 연결·운영 환경에 맞는 영속성을 설정한다. 쿠키 인증에는 CSRF 보호를 유지한다.
- Compose는 기본 정의와 로컬 접속 오버라이드만 둔다(2026-10-21). 기본 파일은 호스트 포트를 열지 않고, local 오버라이드가 `127.0.0.1`로 포트를 연다. 내부 네트워크 실행은 기본 파일만 사용한다. Gateway·Auth·Tempo 포트는 E2E 주소로 통일됐다.
- `bff-local-only`는 로컬 개발용 값이며 배포 시 secret으로 대체한다.

## 외부 연동

- Gateway는 업무용 외부 포트를 열지 않는다. 소비 측으로서 서명·issuer·`esd-api` audience를 검증하는 것이 계약이며, 세부 소유권은 각 업무 서비스가 추가로 확인한다.
