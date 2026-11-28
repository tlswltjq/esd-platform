# 요구사항: 웹 BFF (브라우저 로그인·세션·토큰 보관·릴레이·CSRF)

> 상태: 작성 중. [결정 223](../../docs/decisions.md#per-client-bff)으로 [Gateway 스펙](../gateway/requirements.md)의 R3~R6을 옮겨 왔다. 결정·다른 스펙이 "Gateway R5.2"처럼 가리키므로 **R 번호는 그대로 둔다**. 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이다.

## 개요

웹 BFF는 브라우저 프론트엔드의 BFF이고, 브라우저가 닿는 유일한 문이다([결정 224](../../docs/decisions.md#api-gateway-internal)). 기밀 OAuth 클라이언트 `gateway-bff`로 Authorization Code + PKCE 로그인을 하고, Access/Refresh Token을 서버 쪽 세션 저장소(Redis)에만 보관한다. 브라우저는 세션 쿠키만 갖는다([결정 219](../../docs/decisions.md#single-bff)). 업무 API 요청은 세션의 Access Token을 `Authorization: Bearer`로 붙여 내부 [API 게이트웨이](../gateway/requirements.md)로 넘기고(토큰 릴레이), 만료가 가까우면 Auth에 갱신을 요청한다. 역할별 경로 규칙과 라우팅 표는 API 게이트웨이의 R1·R2다. 인증·토큰 발급·회전·재사용 탐지는 Auth의 정본 규칙이다([Auth 스펙](../auth/requirements.md)).

경로 줄임: `web-bff:` = `apps/web-bff/src/main/java/com/stove/webbff/`, `application.yml` = `apps/web-bff/src/main/resources/application.yml`. 아래 본문의 "Gateway"는 옮기기 전 표현이며 웹 BFF를 뜻한다(R4.1의 "업무 서비스로 보내면"은 "API 게이트웨이로 보내면"으로 읽는다).

## 용어

- **BFF 세션**: Gateway가 발급한 세션 쿠키가 가리키는 Redis의 Spring Session 항목(`stove:bff:sessions:*`). 상태는 design "상태 모델"의 `BffSessionState`다.
- **토큰 기록**: BFF 세션 하나에 묶인 Access/Refresh Token 한 벌(`stove:bff:tokens:<clientKey>`). `clientKey`는 로그인 때 바뀌는 세션 ID와 독립된 세션 속성 `bff.client-key`다.
- **갱신 lease**: 한 토큰 기록의 갱신을 한 번에 하나만 하도록 Redis에 잡는 만료 있는 잠금(`<토큰 기록 키>:refresh-lock`, 소유자 값 대조 해제).
- **불확실한 갱신**: 갱신 요청을 보냈지만 Auth가 Refresh Token을 소비했는지 알 수 없는 결과(타임아웃·연결 끊김·5xx·불완전한 2xx 본문). Refresh Token은 한 번 쓰면 회전되므로 같은 토큰으로 다시 보내면 Auth 재사용 탐지가 계열 전체를 폐기한다.
- **`esd-api` audience**: 업무 모듈이 Access Token에서 확인하는 대상 값.

## 요구사항

### R3. BFF 로그인과 세션
**사용자 스토리:** 브라우저 사용자로서, 로그인 후 세션 쿠키만으로 API를 쓰고 싶다. 그래야 토큰을 직접 다루지 않는다.

#### 수용 기준
1. WHEN `GET /oauth2/authorization/gateway-bff` 요청이 오면 THEN Gateway SHALL 기밀 클라이언트 `gateway-bff`로 Authorization Code + PKCE 로그인을 시작하고 인가 요청(state·code_verifier·nonce)을 세션에 보관한다.
2. WHEN `GET /login/oauth2/code/gateway-bff` callback이 보관한 인가 요청과 맞으면 THEN Gateway SHALL 토큰 교환을 하고, 세션 ID를 바꾸고, 토큰 기록을 저장한 뒤 세션을 `AUTHENTICATED`로 둔다. IF 인가 요청이 없거나 맞지 않거나 교환이 실패하면 THEN Gateway SHALL 401로 끝내고 토큰 기록을 만들지 않는다.
3. WHEN `GET /bff/session` 요청이 오면 THEN Gateway SHALL 검증된 Access Token의 `sub`·`member_id`·`roles`를 반환하고 토큰 값은 반환하지 않는다.
4. WHEN 로그인하면 THEN Gateway SHALL Access/Refresh Token을 Redis 토큰 기록에만 보관하고 브라우저에는 세션 쿠키만 내려준다.
5. THE 토큰 기록 키 SHALL 로그인 때 바뀌는 세션 ID와 독립된 세션 속성(`bff.client-key`)에 연결한다.
6. THE 토큰 기록 SHALL Refresh Token을 반드시 갖는다. IF 토큰 교환 응답에 Refresh Token이 없으면 THEN Gateway SHALL 로그인을 실패로 끝낸다(`gateway-bff`는 항상 Refresh Token을 받는 클라이언트다. 새 번호, notes I).
7. WHILE 세션이 `AUTHENTICATED`이면 THE Gateway SHALL 그 세션의 토큰 기록이 있다는 것을 요청마다 확인하고, 없으면 세션을 끝내고 401을 반환한다(design 불변식 GW-I4).

### R4. 토큰 릴레이와 갱신
**사용자 스토리:** Gateway로서, 세션 쿠키 요청을 업무 서비스의 토큰 요청으로 바꾸고 싶다. 그래야 브라우저가 토큰을 보관하지 않아도 된다.

#### 수용 기준
1. WHEN `AUTHENTICATED` 세션의 요청을 업무 서비스로 보내면 THEN Gateway SHALL 토큰 기록의 Access Token을 `Authorization: Bearer`로 붙이고 브라우저 `Cookie` 헤더를 하위로 보내지 않는다.
2. WHILE 토큰 기록을 갱신하는 동안 THE Gateway SHALL 갱신 lease(기본 15초)를 잡아 같은 토큰 기록의 갱신을 하나만 한다. lease를 못 잡은 요청은 갱신 결과를 기다리되 기다림 상한(기본 12초)을 넘기면 503으로 끝내고 세션을 바꾸지 않는다.
3. THE Auth 토큰 요청 timeout SHALL 기본 5초이며 lease보다 짧다.
4. IF 갱신이 불확실하면(타임아웃·연결 끊김·5xx) THEN Gateway SHALL 이전 Refresh Token으로 자동 재시도하지 않고 토큰 기록과 세션을 지우고 401(재로그인)을 반환한다.
5. IF 외부 요청에 `Authorization` 헤더가 있으면 THEN Gateway SHALL 경로·세션과 무관하게 401로 끝내고 하위로 보내지 않는다. 외부 인증은 BFF 세션뿐이다([결정 220](../../docs/decisions.md#no-public-clients), GW-T20).
6. IF Auth가 토큰 갱신을 거절하면(4xx: 계정 정지·잠금·역할 회수·재사용 탐지·Refresh Token 상한 도달 등) THEN Gateway SHALL 토큰 기록과 브라우저 세션을 삭제하고 401을 반환하며 하위 요청을 보내지 않는다.
7. WHEN 허용된 요청을 하위 서비스로 전달하면 THEN Gateway SHALL 하위 응답의 상태(202·4xx·503 포함)·본문·`Location`·`Cache-Control`을 바꾸지 않고 전달한다.
8. WHEN 갱신 응답을 받으면 THEN Gateway SHALL 새 Access Token을 검증하고 그 `sub`가 토큰 기록의 subject와 같을 때만 저장한다. IF 다르면 THEN 토큰 기록과 세션을 지우고 401을 반환한다(새 번호, design GW-I7).
9. IF 갱신 응답이 2xx지만 Access Token·Refresh Token·`expires_in` 중 하나라도 없으면 THEN Gateway SHALL 불확실한 갱신(4)으로 다룬다(Refresh Token이 이미 소비됐을 수 있다. 새 번호).
10. IF Access Token의 서명·issuer·audience·만료 검증이 실패하면 THEN Gateway SHALL 토큰 기록과 세션을 지우고 401을 반환한다. 만료(`exp`) 검증이 판단 원칙 ⑥과 맞는지는 [결정 필요 M-GW-2].
11. THE 토큰 신선도 판정(갱신 필요 여부) SHALL Gateway가 토큰을 받은 시각 + `expires_in`으로 자기 시계에서 계산한 만료 시각과 자기 시계로 한다. Auth가 찍은 시각과 비교하지 않는다(원칙 ⑥, 결정 163).

### R5. CSRF와 로그아웃
**사용자 스토리:** 사용자로서, 쿠키 인증의 변경 요청이 안전하기를 바란다. 그래야 세션 탈취 위험이 줄어든다.

#### 수용 기준
1. WHEN `GET /bff/csrf` 요청이 오면 THEN Gateway SHALL 변경 요청용 CSRF 헤더 이름과 토큰을 반환한다.
2. WHERE 쿠키 인증의 변경 요청(`POST`·`PUT`·`PATCH`·`DELETE`)에서 THE Gateway SHALL CSRF 헤더·토큰을 요구한다. Auth에 위임한 브라우저 로그인·가입·비밀번호 복구 경로는 예외다.
3. WHEN `POST /bff/logout` 요청이 오면 THEN Gateway SHALL Auth에 Refresh Token 폐기를 요청하고 토큰 기록과 BFF 세션을 지운 뒤 auth OIDC 로그아웃(`end_session_endpoint`)으로 이동한다.
4. IF 로그아웃 중 Auth 폐기 요청이 실패하면 THEN Gateway SHALL [결정 필요 M-GW-3]을 따른다(새 번호).
5. WHEN BFF 세션이 어떤 경로로 끝나면(로그아웃·갱신 거절·검증 실패·세션 만료·재로그인으로 토큰 기록 교체) THEN Gateway SHALL 그 세션의 토큰 기록도 남기지 않는다. 버려지는 Refresh Token을 Auth에서 폐기할지는 [결정 필요 M-GW-3](새 번호).
6. WHEN Gateway가 Auth의 계정 접근 변경 사실 `AccountAccessChanged`(가칭, 토픽 `stove.auth.v1`, 키 `subject`, 그룹 가칭 `gateway-account-access`)를 처음 받으면 THEN Gateway SHALL 그 `subject`의 모든 BFF 세션과 토큰 기록을 지우고, 세션 저장소(Redis)에 `subject`별 거절 기준 `changedAt`을 최대 access token 수명 이상(설정 키, R8) 남긴다. 같은 `subject`의 기준은 더 늦은 `changedAt`만 덮는다. 멱등은 `eventId`를 기준과 같은 수명으로 Redis에 두어 지킨다(결정 84, [결정 180](../../docs/decisions.md#account-access-revocation)).
7. WHILE `subject`의 거절 기준이 있으면 THE Gateway SHALL JWT `iat`가 기준(초 단위로 올림)보다 이른 세션의 access token이면 401로 거절하고 그 세션을 `NONE`으로 끝낸다(외부 Bearer는 R4.5로 이미 401). `iat`와 `changedAt`은 모두 Auth 시계라 원칙 ⑥ 예외가 아니다([결정 180](../../docs/decisions.md#account-access-revocation)).
8. THE 웹 BFF·API 게이트웨이 SHALL 계정 접근 변경 소비 지연을 낸다: 붙잡고 있는 사실의 나이는 앱 게이지 `stove.account.access.consumer.lag`, 가져오지 못한 적체는 브로커 쪽 그룹 지연(`kafka_consumergroup_lag_sum`, 기존 `ConsumerStalled`·`ConsumerLagGrowing` 경보)으로 본다(PR #246 R3). 소비가 멈추거나 거절 기준을 잃으면 끊기가 access token 수명까지 늦어지기 때문이다. 경보 기준은 측정 뒤 정한다(결정 159). 이 소비는 일시 실패를 성공할 때까지 재시도하고 영구 실패만 DLT로 보낸다([결정 226](../../docs/decisions.md#access-change-consumer-failure), P2.6-4·5의 예외).

### R6. 다중 웹 BFF와 세션 저장소 장애
**사용자 스토리:** 운영자로서, Gateway를 여러 개 띄우고도 세션이 공유되기를 바란다. 그래야 가용성이 유지된다.

#### 수용 기준
1. THE 여러 Gateway SHALL 동일 Redis, 세션 namespace, 클라이언트 자격증명을 사용하고, 어느 인스턴스가 받은 callback·갱신이든 같은 토큰 기록을 본다.
2. WHERE 로드밸런서 공개 주소 맥락에서 THE Gateway SHALL 인가 주소·redirect URI·로그아웃 주소를 설정한 공개 주소로 만들고, 요청 Host나 인스턴스 내부 주소로 만들지 않는다. Auth의 토큰·JWK 주소는 내부 주소를 쓸 수 있다.
3. IF 세션 저장소에 접근할 수 없으면 THEN Gateway SHALL 인증을 우회하지 않고 `503 SESSION_STORE_UNAVAILABLE`로 반환한다.
4. WHEN Gateway·auth가 재시작하면 THEN 세션·토큰 기록 SHALL 공유 Redis·MySQL에 남아 그대로 이어진다.
5. WHILE 세션 저장소에 접근할 수 없으면 THE 익명 공개 `GET`·공급자 통지 경로 SHALL [결정 필요 M-GW-4]에 따라 처리한다(새 번호).

## 운영 설정

API 게이트웨이 스펙 R8을 따른다. 웹 BFF에 해당하는 항목은 Redis 주소·공개 주소·클라이언트 비밀값·CORS 허용 출처(R8.1·R8.2), 갱신 매개변수·토큰 기록 TTL(R8.3), 그리고 `API_GATEWAY_URI`다.
