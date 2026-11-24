# 작업: 웹 BFF

> [결정 223](../../../docs/decisions.md#per-client-bff)으로 [Gateway tasks](../gateway/tasks.md)에서 BFF 항목을 옮겨 왔다. **GW-T 번호와 M-GW 번호는 그대로 둔다**(결정·PR이 그 번호로 가리킨다). 본문의 "Gateway"는 옮기기 전 표현이며 웹 BFF를 뜻하고, 파일 경로 `gateway:config/bff/…`는 `web-bff:config/bff/…`로 옮겨졌다.

## 완료 (검증 결과는 근거 색인)

- [x] BFF 기밀 클라이언트 `gateway-bff`로 Authorization Code + PKCE 로그인·callback·토큰 교환 — 2026-10-13
- [x] `/bff/session`·`/bff/csrf`·`/bff/logout`, 토큰 Redis 보관, 세션 쿠키 — `docs/auth-bff.md`
- [x] 토큰 릴레이·갱신(Redis 갱신 잠금 15초, 토큰 요청 timeout 5초), 불확실한 전송 실패 시 재로그인 — `docs/auth-bff.md`
- [x] 쿠키 변경 요청 CSRF 요구, Bearer Token API 접근 유지 — `docs/auth-bff.md`
- [x] 다중 Gateway 공유 Redis·세션 namespace·클라이언트 자격증명, LB 공개 주소 구성 — `docs/auth-bff.md`
- [x] 세션 저장소 장애 시 인증 우회 없이 `503 SESSION_STORE_UNAVAILABLE` 반환·복구 — 2026-10-13, `docs/auth-bff.md`
- [x] 통합 검증: 실제 스택에서 자동 로그인·토큰 릴레이·다중 Gateway 동시 갱신·RTR 재사용 탐지·BFF/auth 로그아웃·재시작 유지·Redis 장애 503, JWT 변조/만료/issuer/audience (관련 132건) — 2026-10-13, `docs/auth-bff.md`
- [x] 계정 변경 후 세션 정리 — 단위(`BffAuthenticationFilterTest`·`RoleAccessGatewayTest`)와 실제 Auth·Gateway·Redis·MySQL 여정에서 갱신 거절 시 Redis 토큰·브라우저 세션 삭제·401(requirements R4.6, [근거 색인](../../../docs/evidence.md)).
- [x] 웹 BFF를 Gateway에서 분리(외부 8080, 세션·토큰 보관·CSRF·Swagger·외부 Authorization 401, `/api/**`는 API 게이트웨이로) — [PR #243](https://github.com/tlswltjq/esd-platform/pull/243), 결정 223·224

## 코드와 맞출 것

- [ ] **GW-T2. 갱신된 Access Token의 `sub`를 세션 subject와 대조하지 않는다**
  - 어디서: `gateway:config/bff/BffTokens.java:141-150`(새 `access_token`을 검증 없이 저장하고 `client.getPrincipalName()`을 그대로 씀).
  - 목표: R4.8, GW-I7. 저장 전에 새 Access Token을 검증하고 `sub`가 기록 subject와 다르면 기록·세션 삭제, 401.
  - 영향: **보유권** — Auth 결함이나 계열 혼선으로 다른 회원의 토큰이 오면 그 회원의 라이브러리·다운로드·주문에 이 세션이 닿는다. 지금은 막는 곳이 없다(원칙 ⑤).
  - 재현: 단위 — Auth 흉내가 다른 `sub`의 토큰을 돌려주면 401·기록 삭제·하위 요청 0건.
- [ ] GW-T3. 2xx지만 토큰이 빠진 갱신 응답을 502로 끝내고 토큰 기록을 남긴다
  - 어디서: `BffTokens.java:145`(`access == null || refresh == null` → `BAD_GATEWAY`, 기록 유지), `:148`(`expires_in` 없으면 NPE → 500).
  - 목표: R4.9. 불확실한 갱신으로 다뤄 기록·세션 삭제, 401.
  - 영향: 다음 요청이 이미 소비된 Refresh Token을 다시 보내 Auth 재사용 탐지가 계열 전체를 폐기한다(다른 기기 세션까지 끊김). 돈 없음.
  - 재현: 단위 — 2xx `{access_token}`만 주면 기록이 지워지고 Auth 호출이 한 번뿐인지.
- [ ] GW-T4. 세션이 끝나도 토큰 기록이 남는 경로가 있다
  - 어디서: `BffAuthenticationFilter.java:52-56`(JWT 검증 실패 → 세션만 무효화), 세션 무활동 만료(Spring Session TTL, 토큰 기록은 `RedisAuthorizedClients.java:47` 7일 TTL로 따로 남음).
  - 목표: R5.5, GW-I4 반대 방향. 세션을 끝내는 모든 경로가 core `EndSession`을 지나 기록을 지운다. 만료 경로의 처리와 버려지는 Refresh Token 폐기는 M-GW-3.
  - 영향: 아무도 쓰지 못하는 유효한 Refresh Token이 Redis에 최대 7일 남는다(Redis 유출 시 노출 면). 돈 없음.
  - 재현: 단위 — Access 검증 실패 뒤 `stove:bff:tokens:<clientKey>`가 없어야 Green.
- [ ] GW-T5. 로그아웃 중 Auth 폐기가 실패하면 로그아웃 전체가 실패한다(코드상 추정) [결정 필요 M-GW-3]
  - 어디서: `BffTokens.java:154-165`(`revoke`의 HTTP 오류가 그대로 전파돼 `.then(clients.delete(key))`가 실행되지 않음), `SecurityConfig.java:81`. 재로그인으로 토큰 기록을 교체할 때 이전 Refresh Token을 폐기하지 않는 것도 같은 문제(design 상태 표 3행).
  - 목표: R5.3·R5.4·R5.5, M-GW-3의 선택.
  - 영향: 사용자는 로그아웃했다고 보지만 세션이 살아 있을 수 있다(공용 PC). 돈 없음.
  - 재현: 통합 — Auth `/oauth2/revoke`가 503일 때 `POST /bff/logout` 뒤 `GET /bff/session`이 401인지(지금은 200 예상).
- [ ] GW-T6. 토큰 기록이 Refresh Token 없음과 형식 오류를 허용한다
  - 어디서: `RedisAuthorizedClients.java:64-79`(`refresh` null 허용 `:71`·`:78`), `:38-41`(역직렬화 예외 전파 → 500), `BffTokens.java:121-122`(null이면 그때 401).
  - 목표: R3.6, design 데이터 모델. 교환 응답에 Refresh Token이 없으면 로그인 실패. 읽은 값의 칸이 빠지면 "기록 없음"(상태 표 12행).
  - 영향: 숨은 하위 상태(원칙 ④). 돈 없음.
  - 재현: 단위 — `refresh` 없는 JSON을 넣으면 401·세션 종료.
- [ ] GW-T7. Gateway → Auth 호출과 Redis 접근이 `config`에 있고 `core.port`가 없다
  - 어디서: `config/bff/BffTokens.java:85`(`WebClient.builder().build()` 직접 생성), `:158`(폐기 주소를 `tokenUri.replace("/token","/revoke")`로 만듦), `config/bff/RedisAuthorizedClients.java`, `config/bff/BffAuthenticationFilter.java`·`BffRelayFilter.java`(들어오는 경계가 config에 있음).
  - 목표: design "패키지(목표)". `core.port.AuthTokenEndpoint`·`BffTokenStore`·`RefreshLease`, `adapter.auth`·`adapter.redis`, 필터는 `api.filter`, 폐기 주소는 설정 키 또는 Auth 메타데이터. ArchUnit `AppArchitectureRules`가 Gateway에도 같은 계층 규칙을 걸게 한다([P1.1-3](../platform/requirements.md)).
  - 영향: 구조. 갱신 규칙을 단위로 시험할 자리가 없다(GW-I5·I6 단위 테스트 없음의 원인).
  - 재현: 아키텍처 — `config` 패키지에 `WebClient` 생성 0건.
- [ ] GW-T9. 갱신 매개변수·토큰 기록 TTL이 코드 상수다
  - 어디서: `BffTokens.java:93`(12초), `:96`(10초), `:102`(15초), `:134`(5초), `RedisAuthorizedClients.java:47`(7일).
  - 목표: R8.3. `config` 바인딩, 기본값 같음, lease > 토큰 요청 timeout을 시작 때 검사.
  - 영향: 운영 조정 불가. 돈 없음.
  - 재현: 설정 바인딩 테스트 — lease ≤ timeout이면 시작 거절.
- [ ] GW-T10. 갱신 기다림 상한 초과 503에 본문 오류 코드가 없다
  - 어디서: `BffTokens.java:104`(`ResponseStatusException(SERVICE_UNAVAILABLE)`).
  - 목표: design "오류 응답". 세션 저장소 장애 503과 구별되는 오류 코드 본문.
  - 영향: 클라이언트가 재시도할 503인지 구별하지 못함. 돈 없음.
  - 재현: 단위 — lease를 다른 소유자가 쥔 채 13초 뒤 503 본문 코드.
- [ ] GW-T12. 하위로 보내는 요청에 Gateway 세션 쿠키·신뢰 헤더가 남는 경로가 있다 [결정 필요 M-GW-5]
  - 어디서: `BffRelayFilter.java:95`(BFF 릴레이 요청만 `Cookie` 제거, Bearer 요청은 쿠키 그대로), `application.yml`의 `store`·`catalog-public`·`promotions`·`studio`·`review-admin`·`settlement-admin`·`auth-*` 라우트(`X-Member-Id` 제거 없음). 지금 운영 코드에서 `X-Member-Id`를 읽는 모듈은 없다(`apps/*/src/main/java` 검색 0건).
  - 목표: R1.10·R2.6, M-GW-5의 선택. 세션 쿠키는 어떤 하위 요청에도 보내지 않는다(R4.1과 같은 뜻).
  - 영향: 하위 서비스 로그·추적에 세션 쿠키가 남는다. 돈 없음.
  - 재현: 라우트 테스트 — Bearer + 세션 쿠키 요청에서 하위가 받은 `Cookie` 헤더가 없음.
- [ ] GW-T13. 세션 저장소 장애 때 Bearer 요청·익명 공개 GET의 동작이 확인되지 않았다 [결정 필요 M-GW-4]
  - 어디서: Spring Security `WebSessionServerSecurityContextRepository`가 요청마다 세션을 읽는다(oauth2Login 구성, `SecurityConfig.java:71-137`). `BffUnavailableHandlerTest`는 세션 저장소 예외 변환만 본다.
  - 목표: R6.5, M-GW-4의 선택.
  - 영향: Redis 장애가 세션을 쓰지 않는 Bearer 클라이언트·상점 조회까지 멈출 수 있다(추정). 돈 없음.
  - 재현: 통합 — Redis 중단 상태에서 Bearer `GET /api/v1/orders`와 익명 `GET /api/v1/storefront/...`의 상태 코드.
- [ ] GW-T14. redirect URI를 요청 Host로 만든다
  - 어디서: `application.yml:33`(`{baseUrl}/login/oauth2/code/{registrationId}`). Auth는 `BFF_REDIRECT_URI`를 등록한다(`apps/auth/src/main/resources/application.yml:43`).
  - 목표: R6.2. Gateway도 같은 설정 키 값 하나로 redirect URI를 만든다(정본 하나, 원칙 ③).
  - 영향: forwarded 헤더가 틀린 배포에서 로그인 실패(Auth가 거절해 안전한 실패). 돈 없음.
  - 재현: 설정 테스트 — `X-Forwarded-Host`를 바꿔도 인가 요청의 `redirect_uri`가 같음.
- [ ] GW-T15. `/bff/session`이 클레임이 없으면 500이다
  - 어디서: `BffController.java:204-205`(`Map.of`에 null `member_id`·`roles`).
  - 목표: R3.3. 검증된 토큰의 클레임 계약(Auth R: `member_id`·`roles` 필수)이 깨지면 세션 종료·401.
  - 영향: 없음(Auth가 항상 넣음, `apps/auth/.../AuthSecurityConfig.java:323-324`).
  - 재현: 단위 — `roles` 없는 JWT.
- [ ] GW-T17. BFF 세션 상태×트리거 표와 갱신 규칙의 단위 테스트가 없다
  - 어디서: 단위는 `BffAuthenticationFilterTest` 1건(7행), `BffUnavailableHandlerTest` 1건(18행). GW-I3·I5·I6은 실제 스택 스크립트(`tools/verification/auth-bff.py`)로만 확인.
  - 목표: design 상태 표 20행 × 3열과 lease 표의 모든 칸을 core `ResolveSessionToken`·`EndSession` 단위 테스트로(GW-T7 뒤).
  - 영향: 회귀를 실제 스택 검증 전에는 모른다.
  - 재현: 표 대조 테스트.
- [ ] **GW-T18. 계정 정지·역할 회수 뒤에도 BFF 세션과 access token이 수명까지 통한다** ([결정 180](../../../docs/decisions.md#account-access-revocation))
  - 어디서: `SecurityConfig` `oauth2ResourceServer().jwt()`(서명·만료·역할만), `BffTokens`(갱신 실패 때만 세션 삭제), Kafka 의존 없음.
  - 목표: R5.6~5.8, design 상태 표 19·20행, 이벤트 "소비". Gateway Kafka 소비(`gateway-account-access`), subject → 세션·토큰 기록 색인, Redis 거절 기준·`eventId` 멱등, JWT `iat` 검사(세션·Bearer), 소비 지연 게이지, 거절 기준 수명 설정 키. Auth AU-T6 발행 뒤 배포. 실패 보관은 M-GW-8 결정 뒤.
  - 영향: **간접 돈·보유권** — `ADMIN`을 회수당한 운영자가 최대 15분 동안 관리자 환불 판정·늦은 취소 반영을 할 수 있다.
  - 재현: 통합 — 로그인한 BFF 세션·Bearer 토큰을 가진 계정의 `ADMIN` 회수 사실을 넣은 뒤 같은 세션·토큰으로 관리자 API → 지금 200, 목표 401.
- [x] **GW-T20. 외부에서 공개 클라이언트로 토큰을 직접 받고 Bearer로 `/api/**`를 쓸 수 있다** — [PR #240](https://github.com/tlswltjq/esd-platform/pull/240) ([결정 219](../../../docs/decisions.md#single-bff)·[결정 220](../../../docs/decisions.md#no-public-clients))
  - 어디서: `application.yml` Auth 라우트 `Path=/oauth2/**,…`(토큰 엔드포인트 포함)·CORS `'[/oauth2/token]'`, `SecurityConfig` `oauth2ResourceServer().jwt()`(`/api/**` 전체 Bearer 허용)·CSRF 예외(`apiBearer`).
  - 목표: 운영 외부 경로는 BFF 세션만. 외부에서 `/oauth2/token`을 쓰지 못하고, 외부 Bearer 요청은 401. 개발·시험도 같다(결정 220): 경로 규칙 테스트·e2e·스크립트·Swagger는 BFF 세션으로 호출한다.
  - 영향: 기기에 Access/Refresh Token이 남는 경로가 열려 있어 "토큰 비공개"가 BFF 경로에서만 성립한다. 차단 장치도 세션·Bearer 두 곳에 필요하다.
  - 재현: 통합 — `studio-web` PKCE로 Gateway 경유 토큰 교환 → 지금 200(토큰 발급), 목표 거절. 그 토큰으로 `/api/v1/library` → 지금 200, 목표 401.
- [ ] **GW-T21. PC 앱 프론트엔드가 BFF 세션을 맺을 방법이 없다** ([결정 219](../../../docs/decisions.md#single-bff)) [결정 필요]
  - 어디서: 세션 ID 전달은 쿠키뿐(`WebSessionIdResolver` 설정 없음), 로그인은 브라우저 redirect로만 끝난다(R3.1·R3.2).
  - 목표: 앱이 시스템 브라우저로 로그인한 결과를 앱 세션으로 넘기는 인계 흐름, 쿠키 밖(헤더) 세션 전달과 그 CSRF 규칙, 앱 세션 수명. 방식·수명은 앱 착수 때 정한다.
  - 영향: 지금 PC 앱을 붙이면 공개 클라이언트 Bearer 경로를 쓸 수밖에 없다(결정 219와 어긋남).
  - 재현: 앱 착수 때 계약 테스트로 정한다.

## 진행 중 / 남음

- [ ] [결정 필요 M-GW-2] Access Token 만료(`exp`) 검증과 판단 원칙 ⑥ — 원칙 문구의 예외로 둘지 확인(동작 변경 없음 추천).
- [ ] [결정 필요 M-GW-8] `AccountAccessChanged` 소비 실패 보관 위치 — Gateway에 DB가 없어 P2.6-4·5(모듈 DB 실패 보관 표·키 정지)를 그대로 따를 수 없다. GW-T18 구현 전.
- [ ] [결정 필요] 즉시 차단(GW-T18)의 소비 위치 — 웹 BFF(세션 삭제)와 API 게이트웨이(`iat` 거절) 중 어디에 둘지 결정 225.
