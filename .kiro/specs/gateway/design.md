# 설계: Gateway

> 상태: 작성 중(결정 150·178). 판단 기준은 [판단 원칙](../../../docs/architecture.md#판단-원칙-결정-147)(결정 147)이다. 줄 번호는 2026-11-10 `spec-foundation` 작업 폴더 기준이다. 경로 줄임은 requirements와 같다(`gateway:` = `apps/gateway/src/main/java/com/stove/gateway/`).

## 아키텍처 위치

- 포트 8080, Spring Cloud Gateway(WebFlux) + Spring Security(OAuth2 Client·Resource Server) + Spring Session Redis. 업무 DB 없음, 저장소는 Redis 하나.
- 플랫폼 안 자리: [플랫폼 design "모듈과 외부 공급자"](../platform/design.md#모듈과-외부-공급자)의 Gateway 행, [동기 의존](../platform/design.md#동기-의존-http)의 "Gateway → 각 모듈"·"Gateway → Auth" 두 행. 비동기 의존 없음(발행·소비 이벤트 0). 다른 업무 모듈의 구현을 참조하지 않는다([P1.1-1](../platform/requirements.md)).
- 의존 방향: 브라우저·외부 클라이언트 → Gateway → {Auth, Store, Catalog, Order, Payment, License, Download, Studio, Review, Settlement}. 업무 모듈은 Gateway를 부르지 않는다. 순환 없음([P1.2-1](../platform/requirements.md)).

### 패키지 (목표)

[P1.1-3](../platform/requirements.md)·AGENTS.md 3절에 맞춰 Gateway 자신의 규칙(세션 상태, 토큰 신선도, 갱신 결과 분류)은 core에, Auth HTTP·Redis 구현은 adapter에, Spring Security·라우트 조립은 config에 둔다. 지금은 모두 `config/bff`에 있다(tasks GW-T7).

| 계층 | 목표 클래스 | 지금 |
|---|---|---|
| `api.controller` | `BffController`(`/bff/session`·`/bff/csrf`) | 같음 `gateway:api/controller/BffController.java` |
| `api.filter` (들어오는 경계) | `BffAuthenticationFilter`(세션 → 검증된 JWT 인증), `BffRelayFilter`(Access Token 부착·쿠키 제거) | `gateway:config/bff/BffAuthenticationFilter.java`, `BffRelayFilter.java` |
| `core.domain` | `BffSessionState`(enum), `BffTokenRecord`(subject·access·accessExpiresAt·scopes·refresh, 모두 NOT NULL), `RefreshOutcome`(enum `ISSUED`·`REJECTED`·`UNCERTAIN`) | 없음(`RedisAuthorizedClients.Stored`, `BffTokens` 안의 분기) |
| `core.service` | `ResolveSessionToken`(신선하면 그대로, 아니면 lease → 갱신 → 결과별 전이), `EndSession`(토큰 기록 삭제·폐기 요청·세션 무효화) | `gateway:config/bff/BffTokens.java:91-152` |
| `core.port` | `AuthTokenEndpoint`(refresh·revoke), `BffTokenStore`(read·write·delete), `RefreshLease`(acquire·release) | 없음 |
| `adapter` | `adapter.auth.AuthTokenHttpClient`(WebClient, timeout), `adapter.redis.RedisBffTokenStore`, `adapter.redis.RedisRefreshLease`(SET NX PX + 소유자 대조 해제 스크립트) | `BffTokens.java:85`(`WebClient.builder()` 직접 생성)·`:116-118`, `RedisAuthorizedClients.java` |
| `config` | `SecurityConfig`(경로·역할·CSRF), `BffClientConfig`, 라우트(`application.yml`), 갱신 매개변수 바인딩 | `SecurityConfig.java`, `BffClientConfig.java`(매개변수는 상수) |

## 데이터 모델

업무 DB가 없어 Flyway 이행 파일이 없다. 상태는 Redis 키의 존재와 값으로 표현하고, DB CHECK 자리는 값 스키마 검증(역직렬화 때 필수 칸 확인)이 대신한다.

| 키 | 값 | 만료 | 제약(목표) | 지금 |
|---|---|---|---|---|
| `stove:bff:sessions:sessions:<sessionId>` (Spring Session, namespace `stove:bff:sessions`) | 세션 속성: `SPRING_SECURITY_CONTEXT`(`OAuth2AuthenticationToken`), `bff.client-key`(UUID), CSRF 토큰, OAuth2 인가 요청 | 무활동 7일(`spring.session.timeout`) | 로그인 성공 때 세션 ID 변경, `bff.client-key`는 유지 | `application.yml:13-16`, `RedisAuthorizedClients.java:32-35` |
| `stove:bff:tokens:<clientKey>` | JSON `BffTokenRecord`(`subject`, `access`, `issued`, `expires`, `scopes`, `refresh`, `refreshIssued`) | 마지막 쓰기부터 7일 | 모든 칸 NOT NULL. `refresh` 없는 기록은 쓰지 않는다(R3.6). 읽을 때 칸이 빠졌거나 형식이 틀리면 "기록 없음"으로 다룬다(GW-I4) | `RedisAuthorizedClients.java:44-49`·`:64-79` — `refresh`가 null 가능(`:71`), 형식 오류는 예외 전파 |
| `stove:bff:tokens:<clientKey>:refresh-lock` | 소유자 UUID | 15초 | 소유자 값이 같을 때만 지운다(Lua 대조 삭제) | `BffTokens.java:100-118` |

- 직렬화: Redis에 실리는 값은 직렬화 가능한 record만 쓰고 setter 없는 프레임워크 객체(`OAuth2AuthorizedClient`)를 직접 싣지 않는다.
- `expires`는 Gateway가 토큰을 받은 자기 시각 + `expires_in`이다(R4.11). Auth가 찍은 `exp`를 복사하지 않는다.
- 로컬 Compose Redis는 메모리 저장이라 Redis 재시작 시 세션이 사라진다. 운영 Redis의 접근 인증·암호화 연결·영속성은 배포 설정이다(R6.4).

## 상태 모델

### BFF 세션 — `BffSessionState`

| 값 | 뜻 | 저장 표현 |
|---|---|---|
| `NONE` | 세션이 없다(쿠키 없음, 무효화됨, 만료됨) | 세션 키 없음 |
| `ANONYMOUS` | 세션은 있으나 인증되지 않았다(CSRF 토큰이나 진행 중 인가 요청만 있음) | 세션 키 있음, 보안 문맥 없음 |
| `AUTHENTICATED` | 로그인을 마쳤고 토큰 기록이 있다 | 보안 문맥 `OAuth2AuthenticationToken` + `bff.client-key` + 토큰 기록 키 있음 |

- 직교 칸 `pendingAuthorization`(인가 요청 보관 여부, 원칙 ④가 허용하는 직교 필드): 로그인 시작이 쓰고 callback이 소비한다. 세션 상태를 바꾸지 않는다.
- "보안 문맥은 인증됐지만 토큰 기록이 없음"은 상태가 아니다. 요청이 이 조합을 만나면 `NONE`으로 끝낸다(GW-I4). 지금은 Access Token 검증 실패 경로가 세션만 지우고 토큰 기록을 남겨, 반대 조합(세션 없음 + 토큰 기록 있음)이 생긴다(tasks GW-T4).
- 상태는 저장된 enum 칸이 아니라 위 저장 표현에서 core `BffSession.state()` 한 곳이 계산한다(목표). 상태를 나타내는 별도 칸을 Redis에 두지 않는 것은 정본이 키 존재 하나이기 때문이다(원칙 ③).

#### 상태 × 트리거

"세션 요청" = `/api/**` 또는 `/bff/session`에 세션 쿠키로 온 요청. 공개 허용 경로(R1)는 상태와 무관하게 무변화로 전달하므로 표에서 뺀다. 지금 코드와 다른 칸은 끝 열에 tasks 번호를 단다.

| # | 트리거 | `NONE` | `ANONYMOUS` | `AUTHENTICATED` | 지금 코드 |
|---|---|---|---|---|---|
| 1 | `GET /bff/csrf` | 허용 → `ANONYMOUS`(세션 생성, CSRF 토큰 저장) | 무변화(같은 CSRF 토큰 반환) | 무변화(CSRF 토큰 반환) | 같음 |
| 2 | `GET /oauth2/authorization/gateway-bff` | 허용 → `ANONYMOUS` + 인가 요청 보관, 302 Auth 인가 주소 | 무변화(상태) + 인가 요청 교체, 302 | 무변화(상태) + 인가 요청 보관, 302 | 같음 |
| 3 | callback: 보관한 인가 요청과 맞고 교환 성공(Refresh Token 있음) | 거절 401(인가 요청 없음 → 4행) | 허용 → `AUTHENTICATED`(세션 ID 변경, `bff.client-key` 생성, 토큰 기록 저장, 302 `/`) | 허용 → `AUTHENTICATED`(같은 `clientKey`의 토큰 기록 교체. 버려지는 이전 Refresh Token 처리는 [결정 필요 M-GW-3]) | 교체 때 이전 토큰 처리 없음(GW-T5) |
| 4 | callback: 인가 요청 없음·불일치, 교환 실패, Refresh Token 없음 | 거절 401, 무변화 | 거절 401, 인가 요청 소비, `ANONYMOUS` 유지 | 거절 401, 기존 토큰 기록 유지 | Refresh Token 없음을 받아들임(GW-T6) |
| 5 | 세션 요청, Access 신선(만료 10초 전보다 이름) | 보호 경로 거절 401 | 보호 경로 거절 401 | 무변화, Access Token 릴레이(R4.1) | 같음 |
| 6 | 세션 요청, 갱신 필요, lease 획득, Auth 2xx 완전 응답, 새 Access `sub` = 기록 subject | 거절 401(토큰 기록 없음, 5와 같음) | 거절 401(5와 같음) | 무변화(상태) + 토큰 기록 교체, 릴레이 | `sub` 대조 없음(GW-T2) |
| 7 | 세션 요청, Auth 갱신 거절(4xx) | 거절 401 | 거절 401 | 허용 → `NONE`(토큰 기록 삭제, 세션 무효화, 401, 하위 요청 없음) | 같음 |
| 8 | 세션 요청, 갱신 불확실(타임아웃 5초·연결 실패·5xx·2xx인데 Access/Refresh/`expires_in` 누락) | 거절 401 | 거절 401 | 허용 → `NONE`(7과 같음, 재시도 없음) | 2xx 누락은 502·토큰 유지(GW-T3) |
| 9 | 세션 요청, 갱신 결과 `sub` ≠ 기록 subject | 거절 401 | 거절 401 | 허용 → `NONE`(7과 같음) | 검사 없음(GW-T2) |
| 10 | 세션 요청, lease를 못 잡고 기다림 상한(12초) 초과 | 거절 401 | 거절 401 | 무변화, 503(재시도 가능, 본문 오류 코드) | 503 본문 없음(GW-T10) |
| 11 | 세션 요청, Access Token 검증 실패(서명·issuer·audience·만료) | 거절 401 | 거절 401 | 허용 → `NONE`(토큰 기록 삭제, 세션 무효화, 401) | 토큰 기록 남음(GW-T4) |
| 12 | 세션 요청, 토큰 기록 없음·형식 오류(TTL 만료, 다른 인스턴스가 삭제) | 거절 401 | 거절 401 | 허용 → `NONE`(세션 무효화, 401) | 없음은 같음, 형식 오류는 500(GW-T6) |
| 13 | Bearer 요청(`Authorization: Bearer`) | 무변화(세션 무시, Bearer 검증 후 전달 / 실패 401) | 무변화(같음) | 무변화(같음, Bearer가 우선하고 세션은 읽지 않음) | 세션 쿠키가 하위로 전달됨(GW-T12) |
| 14 | `POST /bff/logout`(CSRF 유효), Auth 폐기 성공 | 거절 401 | 거절 401 | 허용 → `NONE`(폐기 → 토큰 기록 삭제 → 세션 무효화 → 302 `end_session_endpoint`) | 같음 |
| 15 | `POST /bff/logout`(CSRF 유효), Auth 폐기 실패·타임아웃 | 거절 401 | 거절 401 | [결정 필요 M-GW-3]. 지금(추정): 거절 5xx, `AUTHENTICATED` 유지 | GW-T5 |
| 16 | 쿠키 인증 변경 요청에 CSRF 없음·틀림(로그아웃 포함) | 거절 403, 무변화 | 거절 403, 무변화 | 거절 403, 무변화 | 같음 |
| 17 | 세션 무활동 7일(Redis TTL) | 무변화 | 허용 → `NONE` | 허용 → `NONE`. 토큰 기록은 자기 TTL까지 남음 — R5.5·[결정 필요 M-GW-3] | 토큰 기록 남음(GW-T4) |
| 18 | 세션 저장소 접근 실패 | 거절 503 `SESSION_STORE_UNAVAILABLE`, 무변화 | 같음 | 같음(인증 우회 없음) | 같음. Bearer·공개 GET도 503인지 미확인(GW-T13, M-GW-4) |
| 19 | Auth가 계정 상태·역할 변경으로 Refresh Token 계열 폐기(Gateway 밖 사실) | 무변화 | 무변화 | 무변화 — 다음 갱신(7행)에서 `NONE`. 결정 84 "즉시 끊기"와의 차이는 [결정 필요 M-GW-7] | 같음 |

### 갱신 lease — 직교 필드

토큰 기록 하나에 붙는 lease(`FREE`·`HELD(owner)`)는 세션 상태와 직교한다.

| 트리거 | `FREE` | `HELD`(이 요청이 소유) | `HELD`(다른 요청이 소유) |
|---|---|---|---|
| 갱신 필요 | 허용 → `HELD`(15초), 기록을 다시 읽어 아직 신선하지 않을 때만 갱신 | 해당 요청 안에서 다시 오지 않음 → 무변화 | 무변화, 100ms 간격으로 기록을 다시 읽고 신선해지면 그 토큰 사용, 상한 12초 넘기면 503(10행) |
| 갱신 끝남(성공·실패·취소) | 무변화 | 허용 → `FREE`(소유자 대조 삭제) | 무변화(남의 lease를 지우지 않음) |
| 15초 경과 | 무변화 | 허용 → `FREE`(Redis TTL). 토큰 요청 timeout 5초가 lease보다 짧아 갱신 중 만료되지 않는다 | 허용 → `FREE` |

## API 계약

### BFF 경로

| 메서드 | 경로 | 인증 | 응답 | 오류 |
|---|---|---|---|---|
| GET | `/oauth2/authorization/gateway-bff` | 익명 | 302 Auth 인가 주소(PKCE `S256`, state, nonce) | 503 `SESSION_STORE_UNAVAILABLE` |
| GET | `/login/oauth2/code/gateway-bff` | 익명(인가 요청 보관 필요) | 302 `/`, 새 세션 쿠키 | 401(인가 요청 없음·불일치·교환 실패), 503 |
| GET | `/bff/session` | 세션 | 200 `{subject, memberId, roles}` — 토큰 값 없음 | 401, 503 |
| GET | `/bff/csrf` | 익명 | 200 `{headerName, token}` | 503 |
| POST | `/bff/logout` | 세션 + CSRF | 302 `end_session_endpoint`(`post_logout_redirect_uri` = `{baseUrl}/`) | 401, 403(CSRF), 폐기 실패는 M-GW-3 |

- 멱등: 로그아웃은 같은 세션에 두 번 오면 두 번째가 401이다(이미 `NONE`). 로그인·callback은 인가 요청 하나에 한 번만 성공한다(state 소비).
- CSRF 예외(`SecurityConfig.java:58-70`): Bearer 요청, Auth에 위임한 `/login`·`/logout`·`/oauth2/**`·`/connect/**`·`/userinfo`·가입·비밀번호 복구 경로, `/actuator/**`. 공급자 통지 경로를 Gateway가 받게 되면(M-GW-1 B) 그 경로 하나를 더한다.

### 라우팅 표

| 라우트 ID | 경로·메서드 | 대상 | 요구 역할(`SecurityConfig.java:89-129`) | `X-Member-Id` 제거 |
|---|---|---|---|---|
| `auth-api` | `/api/v1/auth/**` | auth | 가입·비밀번호 복구 익명, `/password`·`/session` 인증, 나머지 거절 | 아니오 |
| `auth-admin-accounts` | `/api/v1/admin/accounts/**` POST | auth | `ADMIN`(`*/status`·`*/roles/grant`·`*/roles/revoke`) | 예 |
| `auth-browser` | `/oauth2/**`, `/login`, `/logout`, `/error`, `/.well-known/**`, `/default-ui.css`, `/userinfo`, `/connect/**` | auth | 익명(`PreserveHostHeader`) | 아니오 |
| `store` | `/api/v1/storefront/**` | store | `GET` 익명, 그 밖 거절 | 아니오 |
| `catalog-sales`·`catalog-listing-seller` | `/api/v1/products/by-code/*/{sales,listing}` GET·PUT | catalog | `CREATOR` | 예 |
| `catalog-sales-admin`·`catalog-listing-admin`·`catalog-download-restriction-admin` | `/api/v1/admin/products/*/{sales,listing,downloads}/restriction` GET·PUT | catalog | `ADMIN` | 예 |
| `catalog-publication-jobs-admin` | `/api/v1/admin/catalog-release-jobs[/*]` GET | catalog | `ADMIN` | 예 |
| `catalog-public` | `/api/v1/products/**` GET | catalog | 익명 | 아니오 |
| `promotions` | `/api/v1/promotions/**` | catalog | `/seller/**` `CREATOR`, `/platform/**` `ADMIN`, 나머지 거절 | 아니오 |
| `order` | `/api/v1/orders/**` | order | `MEMBER` | 예 |
| `payment` | `/api/v1/payments/**` | payment | `MEMBER`(공급자 통지 경로 포함 — R7, GW-T1) | 예 |
| `license` | `/api/v1/library/**`, `/api/v1/storage/**` | license | `MEMBER` | 예 |
| `download` | `/api/v1/downloads/**` | download | `MEMBER` | 예 |
| `studio` | `/api/v1/studio/**`(응답 timeout 300초) | studio | `CREATOR`, `GET /assets/**` 익명 | 아니오 |
| `payment-refund-admin` | `/api/v1/admin/{refund-cancellation-holds,refund-delays,approval-reviews}/**` GET·POST | payment | `ADMIN`(경로·메서드별 선언) | 예 |
| `order-refund-admin` | `/api/v1/admin/refunds/**` GET·POST | order | `ADMIN`(경로·메서드별 선언) | 예 |
| `review-admin` | `/api/v1/reviews/**` | review | `REVIEWER` 또는 `ADMIN` | 아니오 |
| `settlement-admin` | `/api/v1/settlements/**` | settlement | `/me/**` `CREATOR`, 나머지 `ADMIN` | 아니오 |
| `docs-<service>` ×10 | `/v3/api-docs/<service>` GET → `SetPath=/v3/api-docs` | 각 서비스 | 익명 | 아니오 |

업무 라우트 21개 + 문서 프록시 10개 = 31개(`application.yml:80-247`). 플랫폼 design의 "라우트 22개"와 다르다(notes F).

### 오류 응답

| 상태 | 언제 | 본문 |
|---|---|---|
| 401 | 인증 없음, 세션 끝남(R4.4·R4.6·R4.8·R4.9·R4.10, R3.7), Bearer 검증 실패, 표에 없는 경로(익명) | 없음 |
| 403 | 역할 없음, CSRF 실패, 표에 없는 경로(인증됨) | 없음 |
| 404 | 공개 허용 경로 아래 라우트 없음(`POST /api/v1/products/quote`) | 없음 |
| 503 | 세션 저장소 접근 실패 | `{"error":"SESSION_STORE_UNAVAILABLE"}`(`BffUnavailableHandler.java:136-139`) |
| 503 | 갱신 기다림 상한 초과(R4.2) | 목표: 오류 코드 본문(이름은 구현 PR에서 정한다). 지금 본문 없음(GW-T10) |
| 그 밖 | 하위 서비스 응답 | 상태·본문·`Location`·`Cache-Control` 그대로(R4.7) |

## 이벤트

Gateway는 이벤트를 발행·소비하지 않는다. [플랫폼 design 이벤트 목록](../platform/design.md)에 Gateway가 발행 모듈·소비 그룹인 행이 없고, Outbox·Inbox 표도 없다. 공급자 통지(웹훅)는 이벤트가 아니라 HTTP 전달이다(R7).

## 모듈 안 불변식

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| GW-I1 | 라우팅 표 밖 경로(`/internal/**`, `/api/v1/ops/**`, `POST /api/v1/products/quote`)는 하위 서비스에 닿지 않는다 — 플랫폼 [P1.2-2](../platform/requirements.md) | `application.yml` 라우트, `SecurityConfig.java:129`(`anyExchange().denyAll()`) | `GatewayRouteTest#unknownPathMatchesNothing`·`#catalogWriteEndpointsAreUnreachable`·`#catalogBuildMetadataCannotBeRoutedThroughTheGateway`, `RoleAccessGatewayTest#undeclaredPathsAreUnreachable`. `/internal/**` 전용 단언은 없음 → tasks GW-T16 |
| GW-I2 | 역할은 계층이 아니다: 경로에 선언한 역할만 통과 | `SecurityConfig.java:97-128`(`hasRole`·`hasAnyRole`만, 역할 계층 bean 없음) | `RoleAccessGatewayTest#missingRoleIsForbidden`·`#multiRoleTokenPassesEachOwnedRole` |
| GW-I3 | 브라우저는 토큰 값을 받지 않는다 | `BffController.java:201-206`(subject·memberId·roles만), `BffRelayFilter.java:94-100`(하위 요청에만 부착) | 없음 → tasks GW-T17(`/bff/session` 본문에 토큰 문자열 없음) |
| GW-I4 | `AUTHENTICATED` 세션 ⇔ 토큰 기록 있음 | `BffAuthenticationFilter.java:41-56`(기록 없음·갱신 거절 → 세션 무효화·401). 반대 방향(세션 끝 → 기록 삭제)은 지키는 곳 없음 | `BffAuthenticationFilterTest#rejectedRefreshInvalidatesBrowserSessionAndStopsRequest`. 반대 방향 없음 → GW-T4 |
| GW-I5 | 한 토큰 기록의 갱신은 동시에 하나 | `BffTokens.coordinate`(`BffTokens.java:98-114`, SET NX 15초 + 소유자 대조 해제) | 단위 없음 → GW-T17. 실제 스택: `tools/verification/auth-bff.py`(다중 Gateway 동시 갱신, [근거 색인](../../../docs/evidence.md)) |
| GW-I6 | 소비됐을 수 있는 Refresh Token을 다시 보내지 않는다 | `BffTokens.refresh`(`BffTokens.java:128-140`, 타임아웃·연결 실패·비2xx → 기록 삭제·401) | 단위 없음 → GW-T17. 2xx 불완전 응답은 지키지 않음 → GW-T3 |
| GW-I7 | 갱신으로 세션의 주체가 바뀌지 않는다(새 Access `sub` = 기록 subject) | 없음(`BffTokens.java:141-150`은 `client.getPrincipalName()`을 그대로 씀) | 없음 → GW-T2 |
| GW-I8 | 세션 저장소 장애 때 인증을 우회하지 않는다 | `BffUnavailableHandler.java:123-140` | `BffUnavailableHandlerTest#mapsRawLettuceTimeoutFromTheSessionRepositoryToUnavailable` |
| GW-I9 | 쿠키 인증 변경 요청은 CSRF 토큰이 있어야 하위에 닿는다 | `SecurityConfig.java:58-71` | `PaymentApprovalSessionIntegrationTest#anAuthenticatedRedisSessionNeedsCsrfAndForwardsItsServerSideAccessToken`, `BffLogoutIntegrationTest#csrfCleanupCompletesBeforeRedisSessionInvalidation` |
| GW-I10 | 하위 응답을 바꾸지 않는다 | 라우트에 응답 필터 없음(`application.yml:76-247`) | `PaymentApprovalGatewayTest#approvalPreservesTheBodyLocationAndBearerAndRemovesASpoofedMemberHeader`, `RefundRetryGatewayTest#memberRetryPreservesThePathBodyBearerReceiptAndPollingLocation`, `RefundAdminGatewayTest#administratorRefundAmountsAndUnknownValuesPassThroughWithoutChangingTheirMeaning` |
| GW-I11 | 지정 라우트에서 외부 `X-Member-Id`가 하위에 닿지 않는다 | 라우트 `RemoveRequestHeader=X-Member-Id` | `*AdminGatewayTest#…WithoutForgedMemberHeader`, `PaymentApprovalGatewayTest#approvalPreserves…RemovesASpoofedMemberHeader` |
| GW-I12 | 라우트 조회·갱신 관리 엔드포인트는 외부에서 쓸 수 없다 | `SecurityConfig.java:90-91`(health·prometheus만 허용), 노출 목록은 `gateway` 포함(`application.yml:273`) | `GatewayActuatorExposureTest#gatewayRouteListingIsNotExposed`·`#gatewayRefreshIsNotExposed`(보안 거절 401만 확인) → GW-T11 |

모듈을 넘는 불변식: Gateway가 지키는 플랫폼 I 번호는 없다. P1.2-2(`/internal/**` 비노출)는 GW-I1로 지킨다. I20(청구 발견)은 공급자 통지에 기대지 않으므로 Gateway의 통지 전달 여부와 무관하다([P4.2-7](../platform/requirements.md)).

## 외부 연동과 adapter 메모

### Auth (동기, Gateway → Auth)

| 목표 port 메서드 | 호출 | 지금 | 실패 분류 |
|---|---|---|---|
| `AuthTokenEndpoint.refresh(refreshToken)` → `RefreshOutcome` | `POST <AUTH_URI>/oauth2/token`(`grant_type=refresh_token`, client_secret_basic) | `BffTokens.java:124-151` | 2xx 완전 → `ISSUED`, 4xx → `REJECTED`, 타임아웃·연결·5xx·2xx 불완전 → `UNCERTAIN` |
| `AuthTokenEndpoint.revoke(refreshToken)` | `POST <AUTH_URI>/oauth2/revoke`(`token_type_hint=refresh_token`) | `BffTokens.java:154-165` — 주소를 `tokenUri.replace("/token","/revoke")`로 만든다(GW-T7) | 실패 처리는 M-GW-3 |
| 토큰 교환·사용자 정보·JWK | `/oauth2/token`(authorization_code), `/userinfo`, `/oauth2/jwks` | Spring Security OAuth2 Client·Resource Server | 교환 실패 → 401(R3.2) |
| OIDC 로그아웃 | `end_session_endpoint` = `<BFF_PUBLIC_AUTH_BASE>/connect/logout` | `BffClientConfig.java:171-172` | 브라우저 이동 |

- [P1.2-5](../platform/requirements.md)("응답이 없으면 자기 상태를 바꾸지 않는다")와 R4.4(불확실한 갱신 → 토큰 기록 삭제)가 어긋난다. Refresh Token 회전 때문에 R4.4가 맞다고 보며, 플랫폼 문구에 예외를 두도록 notes F에 남긴다.

### 결제 공급자 통지 (들어옴)

- 지금 흐름: 공개 HTTPS edge가 Payment로 직접 보낸다(`docs/security.md:48`·`:97-115`). Gateway를 지나면 `/api/v1/payments/**`의 `MEMBER` 요구로 익명 통지가 401이다(`SecurityConfig.java:127-128`). 어느 쪽이 목표인지는 [결정 필요 M-GW-1].
- Gateway를 지나게 될 때 Gateway가 지킬 것(R7.2): 본문 바이트 그대로(Payment가 원문으로 서명을 검증한다), 서명 헤더 그대로, `POST` 하나, 쿠키·CSRF·역할 없음.

> adapter 메모(공급자 이름은 여기만): 지금 통지 경로는 `/api/v1/payments/webhooks/portone`(`payment:api/controller/PortOneWebhookController.java:28`)이고 공급자 이름을 담아 결정 140·플랫폼 T28 중립화 대상이다. 서명은 Standard Webhooks 방식 헤더 `webhook-id`·`webhook-timestamp`·`webhook-signature`(HMAC-SHA256)를 Payment `HmacPortOneWebhookVerifier`가 원문으로 검증하고, 본문 상한 64 KiB도 Payment가 지킨다(Payment R6.1). 통지 주소 결정 기록은 결정 29 하위 "웹훅 수신 주소: 인스턴스 공개 IP + IP 인증서"다. 실제 PortOne 공개 수신 확인은 G7(결정 140).

## 설정

| 키 | 지금 기본값 | 목표 | 근거 |
|---|---|---|---|
| `REDIS_HOST`·`REDIS_PORT` | `localhost`·`6379` | 필수, 기본값 없음 | R8.1, `application.yml:19-20` |
| `spring.session.timeout` | `7d` | 같음 | R6, `application.yml:14` |
| `spring.session.redis.namespace` | `stove:bff:sessions` | 같음(여러 Gateway가 공유) | R6.1 |
| `BFF_CLIENT_SECRET` | 없음(필수) | 같음 | `application.yml:30` |
| `BFF_PUBLIC_AUTH_BASE` | `http://localhost:8080` | 필수 | R6.2·R8.1, `application.yml:37`, `BffClientConfig.java:165` |
| redirect URI | `{baseUrl}/login/oauth2/code/{registrationId}`(요청 Host·forwarded 헤더로 만듦) | 설정한 공개 주소(Auth `BFF_REDIRECT_URI`와 같은 값) | R6.2, `application.yml:33` → GW-T14 |
| `AUTH_URI` | `http://localhost:8091` | 필수 | R8.1, `application.yml:38-40`·`:81-91` |
| `JWT_ISSUER_URI`·`JWT_JWK_SET_URI` | `http://localhost:8091`·`…/oauth2/jwks` | 필수 | R8.1, `application.yml:44-45` |
| `<SERVICE>_URI` ×9 | `http://localhost:808x` | 필수 | R8.1, `application.yml:100-199` |
| CORS 허용 출처 | `127.0.0.1:18080`·`localhost:18080`·`127.0.0.1:8080`·`localhost:8080` 고정 | 설정 키(목록) | R8.2, `application.yml:62-71` |
| 갱신 lease / 기다림 상한 / 토큰 요청 timeout / 신선도 여유 | 15초 / 12초 / 5초 / 10초 — 코드 상수 | `config` 바인딩 설정 값, 기본값 같음 | R4.2·R4.3·R8.3, `BffTokens.java:93`·`:96`·`:102`·`:134` |
| 토큰 기록 TTL | 7일 코드 상수 | 설정 값(세션 timeout과 같은 값) | R8.3, `RedisAuthorizedClients.java:47` |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,gateway` | `health,info,prometheus` | R2.5, `application.yml:273` |
| `server.forward-headers-strategy` | `framework` | 같음(신뢰 프록시 뒤 배포 전제) | R6.2 |
| `TRACING_SAMPLE_RATE` | `1.0` | 같음(하위와 같은 값) | `application.yml:276` |

운영/테스트 차이: 테스트는 `@DynamicPropertySource`로 하위 주소·비밀값을 넣고(`*GatewayTest`, `BffLogoutIntegrationTest.java:31`), 운영 계약에 테스트 전용 경로나 프로파일은 없다. 다만 위 localhost 기본값은 로컬 개발 값이 운영 설정에 들어 있는 것이라 원칙 ⑦ 점검에서 미충족으로 본다(GW-T8).
