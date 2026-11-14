# 작업: Gateway

## 완료 (검증 결과는 근거 색인)

- [x] 업무 서비스 라우팅과 권한 경계(settlement `/me/**` CREATOR, 나머지 ADMIN 등) — 2026-10-13
- [x] catalog 라우트 `Method=GET` 조건, `POST /api/v1/products/quote`의 Gateway 차단(내부 호출만) — `docs/services.md`
- [x] 외부 노출 통제를 라우트의 부재로 표현, `/api/v1/ops/**` 미노출 — `docs/decisions.md`(18·19번)
- [x] `/v3/api-docs/<service>` 문서 프록시 9개를 GET 전용·한 경로씩만 개방 — `docs/decisions.md`(18번)
- [x] BFF 기밀 클라이언트 `gateway-bff`로 Authorization Code + PKCE 로그인·callback·토큰 교환 — 2026-10-13
- [x] `/bff/session`·`/bff/csrf`·`/bff/logout`, 토큰 Redis 보관, 세션 쿠키 — `docs/auth-bff.md`
- [x] 토큰 릴레이·갱신(Redis 갱신 잠금 15초, 토큰 요청 timeout 5초), 불확실한 전송 실패 시 재로그인 — `docs/auth-bff.md`
- [x] 쿠키 변경 요청 CSRF 요구, Bearer Token API 접근 유지 — `docs/auth-bff.md`
- [x] 다중 Gateway 공유 Redis·세션 namespace·클라이언트 자격증명, LB 공개 주소 구성 — `docs/auth-bff.md`
- [x] 세션 저장소 장애 시 인증 우회 없이 `503 SESSION_STORE_UNAVAILABLE` 반환·복구 — 2026-10-13, `docs/auth-bff.md`
- [x] 통합 검증: 실제 스택에서 자동 로그인·토큰 릴레이·다중 Gateway 동시 갱신·RTR 재사용 탐지·BFF/auth 로그아웃·재시작 유지·Redis 장애 503, JWT 변조/만료/issuer/audience (관련 132건) — 2026-10-13, `docs/auth-bff.md`
- [x] Gateway 경계 — 2026-11-05 실제 Auth·Gateway, 43개 method/path의 역할별 허용·거절·JWT 릴레이·`X-Member-Id` 제거·응답 투명 전달(requirements R1·R4). 하위 업무는 HTTP 픽스처([근거 색인](../../../docs/evidence.md)).
- [x] 실제 서비스 소유권·역할 진입 — 2026-11-05 실제 11개 앱에서 회원별 주문·결제·라이브러리, 창작자별 Studio·정산 격리, 심사자/관리자 진입과 타 역할 거절([근거 색인](../../../docs/evidence.md)).
- [x] 서비스 접근키 수신 경계 — 2026-11-05 Store·Order·Download·Settlement→Catalog, Payment→Order. 단위·아키텍처 464건·실제 DB/HTTP 통합 20건. [`docs/g5-service-key-verification.md`](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g5-service-key-verification.md)를 따른다.
- [x] Compose·검증 실행기의 프로젝트 키 전달 범위 — 2026-11-05. [`docs/g5-credential-distribution-verification.md`](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g5-credential-distribution-verification.md)를 따른다.
- [x] 계정 변경 후 세션 정리 — 단위(`BffAuthenticationFilterTest`·`RoleAccessGatewayTest`)와 실제 Auth·Gateway·Redis·MySQL 여정에서 갱신 거절 시 Redis 토큰·브라우저 세션 삭제·401(requirements R4.6, [근거 색인](../../../docs/evidence.md)).

## 코드와 맞출 것

Gateway에 넘어온 MC-* 이월 항목은 없다(`module-carryover.md`에 Gateway 절 없음). 아래는 이번 재정의에서 새로 찾은 차이다. 영향이 돈·보유권이면 굵게 적는다.

- [ ] **GW-T1. 공급자 통지가 Gateway를 지나면 401이다** [결정 필요 M-GW-1]
  - 어디서: `gateway:config/SecurityConfig.java:127-128`(`/api/v1/payments/**` → `hasRole("MEMBER")`), `application.yml:155-159`(`payment` 라우트가 통지 경로까지 덮음). 지금 통지는 공개 edge가 Payment로 직접 보낸다(`docs/security.md:48`·`:115`).
  - 목표: R7. M-GW-1이 B(Gateway 경유)면 통지 경로 하나·`POST` 하나 익명 허용, CSRF 예외, 본문·서명 헤더 무변경 전달, 그 밖 메서드 거절. A(edge 직접)면 지금 동작을 계약으로 적고 Gateway 경로 표에서 통지 경로를 "Gateway 밖"으로 명시한다. 어느 쪽이든 경로 이름 중립화(플랫폼 T28)와 같은 PR.
  - 영향: 돈 직접 영향 없음 — 통지는 힌트이고 늦은 청구 발견은 Payment 주기 조회가 지킨다(I20). 통지가 닿지 않으면 발견이 다음 조회까지 늦어진다.
  - 재현: Gateway 테스트 — 서명 헤더를 단 익명 `POST /api/v1/payments/webhooks/portone`이 지금 401인지(Red 기준은 결정 후 정함).

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

- [ ] GW-T8. 운영 설정에 로컬 기본값과 로컬 CORS 출처가 있다
  - 어디서: `application.yml:19-20`·`:37-45`·`:62-71`·`:81-199`, `BffClientConfig.java:165`.
  - 목표: R8.1·R8.2. 필수 키 기본값 제거(없으면 시작 거절), CORS 출처는 설정 키, 로컬 값은 `docker-compose.apps.local.yml`로.
  - 영향: 배포에서 키를 빠뜨리면 localhost로 조용히 붙는다. 운영 출처의 CORS를 설정으로 열 수 없다. 돈 없음.
  - 재현: 컨텍스트 테스트 — `AUTH_URI` 없이 시작하면 실패.

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

- [ ] GW-T11. 관리 엔드포인트 노출 목록에 `gateway`가 있고 보안 거절에만 기댄다
  - 어디서: `application.yml:273`, `GatewayActuatorExposureTest#gatewayRouteListingIsNotExposed`(401만 확인).
  - 목표: R2.5. 노출 목록에서 빼고 테스트는 404(엔드포인트 없음)를 확인. `/actuator/prometheus` 익명 공개(`SecurityConfig.java:90`)는 M-GW-6에 묶는다.
  - 영향: 보안 설정 한 줄 실수로 라우트 표(내부 주소·포트)와 갱신 쓰기가 열린다. 돈 없음.
  - 재현: 위 테스트를 404 기대로.

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

- [ ] GW-T16. `/internal/**` 비노출을 직접 단언하는 테스트가 없다
  - 어디서: `GatewayRouteTest#unknownPathMatchesNothing`은 임의 경로만 본다.
  - 목표: GW-I1, 플랫폼 P1.2-2. 모든 모듈의 `/internal/v1/**` 실제 경로(플랫폼 design 동기 의존 표)를 넣은 매개변수 테스트. 플랫폼 T27(런타임 의존 대조)과 같은 표를 쓴다.
  - 영향: 라우트를 넓히는 실수(`/api/**` 같은 와일드카드)를 늦게 안다.
  - 재현: 라우트 테스트.

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
- [ ] **GW-T19. 하류 서비스가 막 닫는 유휴 연결을 다시 써 간헐 500이 난다** ([D-053](../../../docs/defects.md#d-053))
  - 어디서: `apps/gateway/src/main/resources/application.yml`에 `spring.cloud.gateway.server.webflux.httpclient.pool.max-idle-time` 없음(기본 무제한), 하류 Tomcat keep-alive 유휴 닫기 기본 60초.
  - 목표: Gateway 풀 `max-idle-time` < 하류 유휴 닫기(예: 30초), 설정 계약 테스트.
  - 영향: 간헐 500. `POST`는 클라이언트가 처리 여부를 모른다.
  - 재현: 유휴 K초 넘은 연결의 요청을 응답 없이 끊는 가짜 하류로 "요청 → K초 대기 → 요청" — 지금 500, 목표 200.

## 진행 중 / 남음

- [ ] [결정 필요] 최신 API의 실제 역할별 여정과 오류/진행 응답 확인 — G4. 남은 범위: 구매·승인·지급·공개 출시·심사 판정·환불 전이를 실제 업무 상태로 잇는 역할별 전체 여정(위 완료 두 검증은 픽스처 기반 구매 근거·HTTP 픽스처를 사용). G5 "역할별 전체 여정의 단일 기준"과 같은 결정에 묶인다([일반화 계획](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/platform-generalization-plan.md) 4절 Gateway 행).
- [ ] [결정 필요 M-GW-1] 결제 공급자 통지의 공개 수신 경로와 재현 가능한 실행 구성 — G4/G5·G7. 수신 경로(Gateway 경유/edge 직접)를 먼저 정하고(GW-T1), 그다음 기준 시뮬레이터가 서명한 통지를 실제 스택으로 재현할지, 실제 공급자 공개 HTTPS 수신(전용 가맹점 `[외부 대기]`)을 스크립트로 재현할지 정한다.
- [ ] [결정 필요] 계정 복구/상태/운영 권한의 최소 절차와 역할별 전체 여정의 단일 기준 확정 — G5. 관리자 경로의 공개 진입점 분리는 [결정 필요 M-GW-6](로드맵 3절 "운영 API 구조"), 상태·역할 변경 때 BFF 세션을 끊는 시점은 [결정 180](../../../docs/decisions.md#account-access-revocation)으로 정함(GW-T18).
  - [ ] 남은 범위: 서비스 키 교체·배포 비밀 관리, 다중 Gateway 장애·복구, 실제 메일, 공통 운영 전체.
- [ ] [결정 필요 M-GW-2] Access Token 만료(`exp`) 검증과 판단 원칙 ⑥ — 원칙 문구의 예외로 둘지 확인(동작 변경 없음 추천).
- [ ] [결정 필요 M-GW-8] `AccountAccessChanged` 소비 실패 보관 위치 — Gateway에 DB가 없어 P2.6-4·5(모듈 DB 실패 보관 표·키 정지)를 그대로 따를 수 없다. GW-T18 구현 전.

## 범위 밖(후속)

- 대규모 트래픽 제어.
- 복잡한 배포 토폴로지.
- 실제 결제 공급자 공개 HTTPS 통지 수신·인증서 갱신 확인 — G7.
