# 설계: 웹 BFF

> 상태: 작성 중. [결정 223](../../docs/decisions.md#per-client-bff)으로 Gateway에서 BFF 책임을 옮겨 왔다. 아래 GW-I·R 번호는 Gateway 스펙 시절 번호를 그대로 쓴다(결정·다른 스펙이 그 번호로 가리킨다). 경로 줄임 `web-bff:` = `apps/web-bff/src/main/java/com/stove/webbff/`. 아래 줄 번호는 옮기기 전 Gateway 파일 기준이라 맞지 않을 수 있다.

## 아키텍처 위치

- 포트 8080, Spring Cloud Gateway(WebFlux) + Spring Security(OAuth2 Client) + Spring Session Redis. 업무 DB 없음, 저장소는 Redis 하나.
- 브라우저가 닿는 유일한 문이다([결정 224](../../docs/decisions.md#api-gateway-internal)). OAuth 기밀 클라이언트 `gateway-bff`로 로그인하고([결정 219](../../docs/decisions.md#single-bff)), 세션의 서버 쪽 access token을 붙여 업무 API를 내부 [API 게이트웨이](../gateway/)로 넘긴다. 역할별 경로 규칙과 라우팅 표는 게이트웨이에 있다.
- 의존 방향: 브라우저 → 웹 BFF → {Auth(로그인·토큰 교환·갱신·폐기), API 게이트웨이(업무 API·서비스 명세)}. 업무 모듈과 게이트웨이는 웹 BFF를 부르지 않는다.

### 패키지 (목표)

[P1.1-3](../platform/requirements.md)·AGENTS.md 3절에 맞춰 웹 BFF 자신의 규칙(세션 상태, 토큰 신선도, 갱신 결과 분류)은 core에, Auth HTTP·Redis 구현은 adapter에, Spring Security·라우트 조립은 config에 둔다. 지금은 모두 `config/bff`에 있다(tasks GW-T7).

| 계층 | 목표 클래스 | 지금 |
|---|---|---|
| `api.controller` | `BffController`(`/bff/session`·`/bff/csrf`) | 같음 `web-bff:api/controller/BffController.java` |
| `api.filter` (들어오는 경계) | `BffAuthenticationFilter`(세션 → 검증된 JWT 인증), `BffRelayFilter`(Access Token 부착·쿠키 제거) | `web-bff:config/bff/BffAuthenticationFilter.java`, `BffRelayFilter.java` |
| `core.domain` | `BffSessionState`(enum), `BffTokenRecord`(subject·access·accessExpiresAt·scopes·refresh, 모두 NOT NULL), `RefreshOutcome`(enum `ISSUED`·`REJECTED`·`UNCERTAIN`) | 없음(`RedisAuthorizedClients.Stored`, `BffTokens` 안의 분기) |
| `core.service` | `ResolveSessionToken`(신선하면 그대로, 아니면 lease → 갱신 → 결과별 전이), `EndSession`(토큰 기록 삭제·폐기 요청·세션 무효화) | `web-bff:config/bff/BffTokens.java:91-152` |
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
| 19 | `AccountAccessChanged` 수신(그 세션의 subject, 첫 `eventId`) | 무변화 | 무변화 | 허용 → `NONE`(토큰 기록 삭제, 세션 무효화, 거절 기준 저장 — R5.6) | 같음. 웹 BFF는 subject 색인의 토큰 기록을 지우고(세션은 다음 요청에서 401과 함께 끝남), 거절 기준은 API 게이트웨이가 둔다(결정 225, PR #246) |
| 20 | 세션·Bearer 요청, JWT `iat` < 그 subject의 거절 기준 | 거절 401 | 거절 401 | 허용 → `NONE`(11행과 같음, 401) — Bearer는 상태 없이 401 | 같음. API 게이트웨이가 401(`iat` < 올림한 기준 + 시계 오차 여유, PR #246) |

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

### 라우트

| 라우트 ID | 경로·메서드 | 대상 |
|---|---|---|
| `auth-browser` | `/oauth2/authorize`, `/login`, `/logout`, `/error`, `/.well-known/**`, `/default-ui.css`, `/connect/**` | Auth(`PreserveHostHeader`). 토큰·폐기·JWKS·userinfo는 없다([결정 220](../../docs/decisions.md#no-public-clients)) |
| `api-gateway` | `/api/**` | API 게이트웨이(`API_GATEWAY_URI`). 세션 요청이면 `Authorization: Bearer <서버 쪽 access token>`을 붙이고 브라우저 `Cookie`를 뺀다(R4.1). 세션이 없으면 자격 증명 없이 넘긴다 |
| `api-docs` | `/v3/api-docs/<service>` ×10 GET | API 게이트웨이(서비스별 명세 프록시) |

외부 `Authorization` 헤더는 경로와 무관하게 401이다(R4.5, `ExternalAuthorizationRejectFilter`).

### 오류 응답

| 상태 | 언제 | 본문 |
|---|---|---|
| 401 | 외부 `Authorization` 헤더, 세션 끝남(R4.4·R4.6·R4.8·R4.9·R4.10, R3.7), `/bff/session`·`/bff/logout` 무인증 | 없음 |
| 403 | CSRF 실패 | 없음 |
| 503 | 세션 저장소 접근 실패 | `{"error":"SESSION_STORE_UNAVAILABLE"}`(`BffUnavailableHandler`) |
| 503 | 갱신 기다림 상한 초과(R4.2) | 목표: 오류 코드 본문(GW-T10) |
| 그 밖 | API 게이트웨이 응답(역할 없음 403·무인증 401 포함) | 상태·본문·`Location`·`Cache-Control` 그대로(R4.7) |

## 이벤트

> **배치([결정 225](../../docs/decisions.md#revocation-placement)):** 웹 BFF는 이 사실을 받아 `subject`의 세션·토큰 기록을 지운다. 변경 시각 전 발급 토큰의 거절(`iat` < `changedAt`)과 그 기준 저장은 API 게이트웨이가 따로 소비해 맡는다([Gateway design](../gateway/design.md#이벤트)). 아래 표는 결정 180 당시(Gateway 하나)의 설계이며, 거절 기준 부분은 게이트웨이로 읽는다.

### 소비 — `AccountAccessChanged` (가칭, 목표, [결정 180](../../docs/decisions.md#account-access-revocation))

| 사실 | 그룹 · 리스너 → 매퍼 → 서비스 | 멱등 | 실패 |
|---|---|---|---|
| `AccountAccessChanged`(플랫폼 이벤트 목표 행, Auth 토픽 `stove.auth.v1`, 키 `subject`) | 웹 BFF `web-bff-account-access` · `AccountAccessEventListener` → `AccountAccessChangedMapper.from`(봉투·키·`subject` 검증) → core `EndSubjectSessions` → `RedisSubjectSessions`(색인 `stove:bff:subject-tokens:<subject>`의 토큰 기록 삭제). API 게이트웨이 `gateway-account-access` · 같은 구조 → `RecordAccessChange` → `RedisAccessBaselines` | 웹 BFF: 삭제가 멱등. 게이트웨이: Redis `eventId` 기록(거절 기준과 같은 수명), 기준은 더 늦은 `changedAt`만 덮어 순서·중복과 무관하게 같은 결과 | 재시도 가능(Redis 장애)·영구(봉투·본문 오류). 일시 실패는 성공할 때까지 재시도, 영구 실패만 `stove.auth.v1.DLT`([결정 226](../../docs/decisions.md#access-change-consumer-failure)) |

- 세션 찾기: 토큰 기록을 쓸 때(`RedisAuthorizedClients.write`) `subject → 토큰 기록 키 집합`(`stove:bff:subject-tokens:<subject>`, 토큰 기록과 같은 7일 수명)에 더한다. 삭제 사이에 끼어든 갱신이 토큰을 다시 써도, 그 토큰은 변경 전에 발급돼 게이트웨이가 `iat`로 거절한다.
- 거절 기준 수명: `gateway-bff`의 최대 access token 수명(`BFF_ACCESS_TOKEN_SECONDS`) 이상(공개 클라이언트는 없다 — 결정 220). Auth 설정과 Gateway 설정이 따로 있으므로 Gateway 설정 키 `stove.gateway.access-baseline.ttl`(기본 20분)로 두고, 기동 때 값이 0 이하면 실패한다(R8). 시계 오차 여유는 `stove.gateway.access-baseline.clock-skew`(기본 1초, 음수면 기동 실패).
- 배포 순서: Auth 발행(AU-T6) → Gateway 소비. 소비 전 발행된 사실은 Kafka 보존 기간 안이면 처리되고, 그 사이 끊기는 지금처럼 수명까지다.

## 모듈 안 불변식

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| GW-I3 | 브라우저는 토큰 값을 받지 않는다 | `BffController.java:201-206`(subject·memberId·roles만), `BffRelayFilter.java:94-100`(하위 요청에만 부착) | 없음 → tasks GW-T17(`/bff/session` 본문에 토큰 문자열 없음) |
| GW-I4 | `AUTHENTICATED` 세션 ⇔ 토큰 기록 있음 | `BffAuthenticationFilter.java:41-56`(기록 없음·갱신 거절 → 세션 무효화·401). 반대 방향(세션 끝 → 기록 삭제)은 지키는 곳 없음 | `BffAuthenticationFilterTest#rejectedRefreshInvalidatesBrowserSessionAndStopsRequest`. 반대 방향 없음 → GW-T4 |
| GW-I5 | 한 토큰 기록의 갱신은 동시에 하나 | `BffTokens.coordinate`(`BffTokens.java:98-114`, SET NX 15초 + 소유자 대조 해제) | 단위 없음 → GW-T17. 실제 스택: `tools/verification/auth-bff.py`(다중 Gateway 동시 갱신, [근거 색인](../../docs/evidence.md)) |
| GW-I6 | 소비됐을 수 있는 Refresh Token을 다시 보내지 않는다 | `BffTokens.refresh`(`BffTokens.java:128-140`, 타임아웃·연결 실패·비2xx → 기록 삭제·401) | 단위 없음 → GW-T17. 2xx 불완전 응답은 지키지 않음 → GW-T3 |
| GW-I7 | 갱신으로 세션의 주체가 바뀌지 않는다(새 Access `sub` = 기록 subject) | 없음(`BffTokens.java:141-150`은 `client.getPrincipalName()`을 그대로 씀) | 없음 → GW-T2 |
| GW-I8 | 세션 저장소 장애 때 인증을 우회하지 않는다 | `BffUnavailableHandler.java:123-140` | `BffUnavailableHandlerTest#mapsRawLettuceTimeoutFromTheSessionRepositoryToUnavailable` |
| GW-I9 | 쿠키 인증 변경 요청은 CSRF 토큰이 있어야 하위에 닿는다 | `SecurityConfig.java:58-71` | `PaymentApprovalSessionIntegrationTest#anAuthenticatedRedisSessionNeedsCsrfAndForwardsItsServerSideAccessToken`, `BffLogoutIntegrationTest#csrfCleanupCompletesBeforeRedisSessionInvalidation` |

## 외부 연동과 adapter 메모

### Auth (동기, 웹 BFF → Auth)

| 목표 port 메서드 | 호출 | 지금 | 실패 분류 |
|---|---|---|---|
| `AuthTokenEndpoint.refresh(refreshToken)` → `RefreshOutcome` | `POST <AUTH_URI>/oauth2/token`(`grant_type=refresh_token`, client_secret_basic) | `BffTokens.java:124-151` | 2xx 완전 → `ISSUED`, 4xx → `REJECTED`, 타임아웃·연결·5xx·2xx 불완전 → `UNCERTAIN` |
| `AuthTokenEndpoint.revoke(refreshToken)` | `POST <AUTH_URI>/oauth2/revoke`(`token_type_hint=refresh_token`) | `BffTokens.java:154-165` — 주소를 `tokenUri.replace("/token","/revoke")`로 만든다(GW-T7) | 실패 처리는 M-GW-3 |
| 토큰 교환·사용자 정보·JWK | `/oauth2/token`(authorization_code), `/userinfo`, `/oauth2/jwks` | Spring Security OAuth2 Client·Resource Server | 교환 실패 → 401(R3.2) |
| OIDC 로그아웃 | `end_session_endpoint` = `<BFF_PUBLIC_AUTH_BASE>/connect/logout` | `BffClientConfig.java:171-172` | 브라우저 이동 |

- [P1.2-5](../platform/requirements.md)("응답이 없으면 자기 상태를 바꾸지 않는다")와 R4.4(불확실한 갱신 → 토큰 기록 삭제)가 어긋난다. Refresh Token 회전 때문에 R4.4가 맞다고 보며, 플랫폼 문구에 예외를 두도록 notes F에 남긴다.

## 설정

| 키 | 지금 기본값 | 목표 | 근거 |
|---|---|---|---|
| `REDIS_HOST`·`REDIS_PORT` | `localhost`·`6379` | 필수, 기본값 없음 | R8.1, `application.yml:19-20` |
| `spring.session.timeout` | `7d` | 같음 | R6, `application.yml:14` |
| `spring.session.redis.namespace` | `stove:bff:sessions` | 같음(여러 Gateway가 공유) | R6.1 |
| `BFF_CLIENT_SECRET` | 없음(필수) | 같음 | `application.yml:30` |
| `BFF_PUBLIC_AUTH_BASE` | `http://localhost:8080` | 필수 | R6.2·R8.1, `application.yml:37`, `BffClientConfig.java:165` |
| redirect URI | `{baseUrl}/login/oauth2/code/{registrationId}`(요청 Host·forwarded 헤더로 만듦) | 설정한 공개 주소(Auth `BFF_REDIRECT_URI`와 같은 값) | R6.2, `application.yml:33` → GW-T14 |
| CORS 허용 출처 | `127.0.0.1:18080`·`localhost:18080`·`127.0.0.1:8080`·`localhost:8080` 고정 | 설정 키(목록) | R8.2, `application.yml:62-71` |
| 갱신 lease / 기다림 상한 / 토큰 요청 timeout / 신선도 여유 | 15초 / 12초 / 5초 / 10초 — 코드 상수 | `config` 바인딩 설정 값, 기본값 같음 | R4.2·R4.3·R8.3, `BffTokens.java:93`·`:96`·`:102`·`:134` |
| 토큰 기록 TTL | 7일 코드 상수 | 설정 값(세션 timeout과 같은 값) | R8.3, `RedisAuthorizedClients.java:47` |
| `server.forward-headers-strategy` | `framework` | 같음(신뢰 프록시 뒤 배포 전제) | R6.2 |
| `API_GATEWAY_URI` | `http://localhost:8093` | 필수 | 결정 223·224 |
