# 요구사항: Gateway (라우팅·BFF 로그인·세션·토큰 릴레이, 내부 API 차단)

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다.

## 개요

Gateway는 플랫폼의 외부 진입점이다. 외부 요청을 라우팅 표의 경로만 업무 모듈로 보내고, 그 밖의 경로(`/internal/**`, 운영 경로, 서비스 간 전용 명령)는 어떤 라우트에도 두지 않는다([P1.2-2](../platform/requirements.md)). 경로마다 요구 역할을 확인하는 1차 경계이고, 소유권·세부 권한은 받는 모듈이 JWT로 다시 판정한다.

Gateway는 BFF(Backend for Frontend)를 겸한다. 기밀 OAuth 클라이언트 `gateway-bff`로 Authorization Code + PKCE 로그인을 하고, Access/Refresh Token을 서버 쪽 세션 저장소(Redis)에만 보관한다. 브라우저는 세션 쿠키만 갖는다. 세션 쿠키 요청은 Gateway가 보관한 Access Token으로 바꿔 업무 모듈에 보내고(토큰 릴레이), 만료가 가까우면 Auth에 갱신을 요청한다. 인증·토큰 발급·회전·재사용 탐지는 Auth의 정본 규칙이다([Auth 스펙](../auth/requirements.md)).

Gateway는 업무 DB가 없고 이벤트를 발행·소비하지 않는다([플랫폼 design "모듈과 외부 공급자"](../platform/design.md#모듈과-외부-공급자)). 동기 의존은 Gateway → 각 업무 모듈(라우팅)과 Gateway → Auth(토큰 엔드포인트) 두 행이다([플랫폼 design "동기 의존"](../platform/design.md#동기-의존-http)).

경로 줄임: `gateway:` = `apps/gateway/src/main/java/com/stove/gateway/`, `application.yml` = `apps/gateway/src/main/resources/application.yml`.

## 용어

- **BFF 세션**: Gateway가 발급한 세션 쿠키가 가리키는 Redis의 Spring Session 항목(`stove:bff:sessions:*`). 상태는 design "상태 모델"의 `BffSessionState`다.
- **토큰 기록**: BFF 세션 하나에 묶인 Access/Refresh Token 한 벌(`stove:bff:tokens:<clientKey>`). `clientKey`는 로그인 때 바뀌는 세션 ID와 독립된 세션 속성 `bff.client-key`다.
- **갱신 lease**: 한 토큰 기록의 갱신을 한 번에 하나만 하도록 Redis에 잡는 만료 있는 잠금(`<토큰 기록 키>:refresh-lock`, 소유자 값 대조 해제).
- **불확실한 갱신**: 갱신 요청을 보냈지만 Auth가 Refresh Token을 소비했는지 알 수 없는 결과(타임아웃·연결 끊김·5xx·불완전한 2xx 본문). Refresh Token은 한 번 쓰면 회전되므로 같은 토큰으로 다시 보내면 Auth 재사용 탐지가 계열 전체를 폐기한다.
- **라우트의 부재**: 외부 노출 통제를 필터 조건이 아니라 라우팅 표에 경로를 두지 않는 것으로 표현하는 방식.
- **공급자 통지 경로**: 결제 공급자가 서버 대 서버로 보내는 결제 상태 변경 통지(웹훅)를 받는 공개 경로. 통지는 Payment의 조회 힌트일 뿐 상태를 확정하지 않는다([P3.11-5](../platform/requirements.md), [P3.13-8](../platform/requirements.md)).
- **`esd-api` audience**: 업무 모듈이 Access Token에서 확인하는 대상 값.

## 요구사항

### R1. 업무 서비스 라우팅과 권한 경계
**사용자 스토리:** 클라이언트로서, 단일 진입점으로 각 업무 서비스를 호출하고 싶다. 그래야 서비스별 주소를 직접 알 필요가 없다.

#### 수용 기준
1. WHEN `/api/v1/auth/**`, `/oauth2/**`, `/login`, `/logout`, `/.well-known/**`(브라우저 OIDC 경로: `/error`·`/userinfo`·`/connect/**`·`/default-ui.css` 포함) 요청이 오면 THEN Gateway SHALL auth로 라우팅하고 브라우저 경로의 Host를 유지한다.
2. WHEN `/api/v1/storefront/**` 요청이 오면 THEN Gateway SHALL store로 라우팅하고, 익명 `GET`만 허용한다.
3. WHERE `/api/v1/products/**` 공개 라우트에서 THE Gateway SHALL `GET`만 허용한다. 판매자 판매·노출 설정(`/api/v1/products/by-code/*/{sales,listing}`)만 별도 라우트로 `GET`·`PUT`을 연다.
4. WHEN `/api/v1/orders/**`, `/api/v1/payments/**`, `/api/v1/library/**`·`/api/v1/storage/**`, `/api/v1/downloads/**` 요청이 오면 THEN Gateway SHALL 각각 order·payment·license·download로 라우팅하고 `MEMBER` 역할을 요구한다(공급자 통지 경로는 R7).
5. WHEN `/api/v1/studio/**`, `/api/v1/reviews/**` 요청이 오면 THEN Gateway SHALL 각각 studio·review로 라우팅하고, studio는 `CREATOR`(공개 자산 `GET /api/v1/studio/assets/**`만 익명), review는 `REVIEWER` 또는 `ADMIN`을 요구한다.
6. WHERE `/api/v1/settlements/me/**` 맥락에서 THE Gateway SHALL `CREATOR`를, 나머지 `/api/v1/settlements/**`에서는 `ADMIN`을 요구한다.
7. WHERE `/api/v1/promotions/**` 맥락에서 THE Gateway SHALL `/seller/**`는 `CREATOR`, `/platform/**`는 `ADMIN`을 요구하고 그 밖의 하위 경로는 거절한다.
8. WHERE `/api/v1/admin/accounts/**`(auth), `/api/v1/admin/products/*/{sales,listing,downloads}/restriction`·`/api/v1/admin/catalog-release-jobs[/*]`(catalog), `/api/v1/admin/refunds/**`(order) 맥락에서 THE Gateway SHALL `ADMIN` 역할을 요구하고, 경로마다 선언한 메서드만 연다.
9. THE 역할 판정 SHALL 경로에 선언한 역할만 인정하며, `ADMIN`에 `MEMBER`·`CREATOR`·`REVIEWER` 권한을 암묵적으로 부여하지 않는다.
10. WHERE commerce(order·payment·license·download)·Catalog 판매/노출/다운로드 관리·관리자 환불·승인 보류 라우트에서 THE Gateway SHALL 외부 요청의 `X-Member-Id` 헤더를 제거하고, 소유자는 하위 서비스가 JWT `sub`로 판정한다. 모든 라우트로 넓힐지는 [결정 필요 M-GW-5].
11. WHERE `/api/v1/admin/refund-cancellation-holds/**`·`/api/v1/admin/refund-delays/**`·`/api/v1/admin/approval-reviews/**`(payment) 맥락에서 THE Gateway SHALL `ADMIN` 역할과 경로마다 선언한 `GET`·`POST`만 연다(새 번호, notes I).
12. IF 요청 경로·메서드가 R1의 어떤 행에도 맞지 않으면 THEN Gateway SHALL 하위 서비스로 보내지 않고 거절한다(익명 401, 인증됨 403, 공개 허용 경로 아래 라우트 없음 404).
13. THE 관리자 경로를 고객과 같은 공개 진입점에 둘지 SHALL [결정 필요 M-GW-6]을 따른다. 정해지기 전에는 1~11대로 연다.

### R2. 내부 전용 API의 외부 차단
**사용자 스토리:** 운영자로서, 내부 전용 경로가 외부에서 호출되지 않기를 바란다. 그래야 서비스 간 신뢰 경계가 유지된다.

#### 수용 기준
1. THE 외부 노출 통제 SHALL 필터가 아니라 라우트의 부재로 표현한다. `/internal/**`은 어떤 라우트에도 매칭하지 않는다([P1.2-2](../platform/requirements.md)).
2. WHERE `POST /api/v1/products/quote` 맥락에서 THE Gateway SHALL 보안 단계에서 막지 않고 라우트 부재(404)로 끝내, 서비스 간 내부 호출로만 도달하게 한다.
3. THE Gateway SHALL `/api/v1/ops/**` 라우트를 두지 않는다(결정 19).
4. WHERE `/v3/api-docs/<service>` 문서 프록시 맥락에서 THE Gateway SHALL 서비스당 한 경로씩 `GET`만 열고 하위 경로로 넓히지 않는다(결정 18). 명세에 보이는 경로라도 도달 가능성은 R1의 라우팅 표가 정한다.
5. THE Gateway 관리 엔드포인트 SHALL `health`·`info`·`prometheus`만 노출 목록에 둔다. 라우트 조회·갱신(`/actuator/gateway/**`)은 노출 목록에 두지 않는다(보안 거절에만 기대지 않음, 원칙 ⑤).
6. THE 외부 요청의 서비스 키 헤더(`X-<호출모듈>-Service-Key`, [P1.2-3](../platform/requirements.md)) 처리 SHALL `X-Member-Id`와 함께 [결정 필요 M-GW-5]로 정한다. 지금은 `/internal/**` 라우트가 없어 그 헤더가 서비스 키 검사 경로에 닿지 않는다(1).

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
5. THE Bearer Token API 접근 SHALL 세션과 무관하게 지원한다. Bearer 요청은 Gateway가 서명·만료·issuer·`esd-api` audience를 검증한 뒤 받은 헤더 그대로 전달한다.
6. IF Auth가 토큰 갱신을 거절하면(4xx: 계정 정지·잠금·역할 회수·재사용 탐지·Refresh Token 상한 도달 등) THEN Gateway SHALL 토큰 기록과 브라우저 세션을 삭제하고 401을 반환하며 하위 요청을 보내지 않는다.
7. WHEN 허용된 요청을 하위 서비스로 전달하면 THEN Gateway SHALL 하위 응답의 상태(202·4xx·503 포함)·본문·`Location`·`Cache-Control`을 바꾸지 않고 전달한다.
8. WHEN 갱신 응답을 받으면 THEN Gateway SHALL 새 Access Token을 검증하고 그 `sub`가 토큰 기록의 subject와 같을 때만 저장한다. IF 다르면 THEN 토큰 기록과 세션을 지우고 401을 반환한다(새 번호, design GW-I7).
9. IF 갱신 응답이 2xx지만 Access Token·Refresh Token·`expires_in` 중 하나라도 없으면 THEN Gateway SHALL 불확실한 갱신(4)으로 다룬다(Refresh Token이 이미 소비됐을 수 있다. 새 번호).
10. IF Access Token의 서명·issuer·audience·만료 검증이 실패하면 THEN Gateway SHALL Bearer 요청은 401로 거절하고, BFF 세션 요청은 토큰 기록과 세션을 지우고 401을 반환한다. 만료(`exp`) 검증이 판단 원칙 ⑥과 맞는지는 [결정 필요 M-GW-2].
11. THE 토큰 신선도 판정(갱신 필요 여부) SHALL Gateway가 토큰을 받은 시각 + `expires_in`으로 자기 시계에서 계산한 만료 시각과 자기 시계로 한다. Auth가 찍은 시각과 비교하지 않는다(원칙 ⑥, 결정 163).

### R5. CSRF와 로그아웃
**사용자 스토리:** 사용자로서, 쿠키 인증의 변경 요청이 안전하기를 바란다. 그래야 세션 탈취 위험이 줄어든다.

#### 수용 기준
1. WHEN `GET /bff/csrf` 요청이 오면 THEN Gateway SHALL 변경 요청용 CSRF 헤더 이름과 토큰을 반환한다.
2. WHERE 쿠키 인증의 변경 요청(`POST`·`PUT`·`PATCH`·`DELETE`)에서 THE Gateway SHALL CSRF 헤더·토큰을 요구한다. Bearer 요청과 Auth에 위임한 브라우저 로그인·가입·비밀번호 복구 경로는 예외다.
3. WHEN `POST /bff/logout` 요청이 오면 THEN Gateway SHALL Auth에 Refresh Token 폐기를 요청하고 토큰 기록과 BFF 세션을 지운 뒤 auth OIDC 로그아웃(`end_session_endpoint`)으로 이동한다.
4. IF 로그아웃 중 Auth 폐기 요청이 실패하면 THEN Gateway SHALL [결정 필요 M-GW-3]을 따른다(새 번호).
5. WHEN BFF 세션이 어떤 경로로 끝나면(로그아웃·갱신 거절·검증 실패·세션 만료·재로그인으로 토큰 기록 교체) THEN Gateway SHALL 그 세션의 토큰 기록도 남기지 않는다. 버려지는 Refresh Token을 Auth에서 폐기할지는 [결정 필요 M-GW-3](새 번호).
6. WHEN Auth가 계정 상태·역할을 바꿔 Refresh Token 계열을 폐기하면 THEN Gateway SHALL 그 계정의 BFF 세션을 [결정 필요 M-GW-7]에 따라 끝낸다. 정해지기 전에는 다음 갱신(R4.6)에서 끝낸다(결정 84, 새 번호).

### R6. 다중 Gateway와 세션 저장소 장애
**사용자 스토리:** 운영자로서, Gateway를 여러 개 띄우고도 세션이 공유되기를 바란다. 그래야 가용성이 유지된다.

#### 수용 기준
1. THE 여러 Gateway SHALL 동일 Redis, 세션 namespace, 클라이언트 자격증명을 사용하고, 어느 인스턴스가 받은 callback·갱신이든 같은 토큰 기록을 본다.
2. WHERE 로드밸런서 공개 주소 맥락에서 THE Gateway SHALL 인가 주소·redirect URI·로그아웃 주소를 설정한 공개 주소로 만들고, 요청 Host나 인스턴스 내부 주소로 만들지 않는다. Auth의 토큰·JWK 주소는 내부 주소를 쓸 수 있다.
3. IF 세션 저장소에 접근할 수 없으면 THEN Gateway SHALL 인증을 우회하지 않고 `503 SESSION_STORE_UNAVAILABLE`로 반환한다.
4. WHEN Gateway·auth가 재시작하면 THEN 세션·토큰 기록 SHALL 공유 Redis·MySQL에 남아 그대로 이어진다.
5. WHILE 세션 저장소에 접근할 수 없으면 THE Bearer 요청·익명 공개 `GET`·공급자 통지 경로 SHALL [결정 필요 M-GW-4]에 따라 처리한다(새 번호).

### R7. 결제 공급자 통지의 공개 수신 경로 (새 요구사항)
**사용자 스토리:** 운영자로서, 결제 공급자가 보내는 결제 상태 변경 통지가 Payment에 닿기를 바란다. 그래야 끝난 결제의 늦은 청구를 주기 조회보다 먼저 알 수 있다(결정 154).

#### 수용 기준
1. THE 공급자 통지의 공개 수신 경로(Gateway를 지나는지, 공개 edge가 Payment로 직접 보내는지) SHALL [결정 필요 M-GW-1]로 정한다. 실제 공급자 공개 HTTPS 수신 확인은 G7이다(결정 140).
2. IF 통지가 Gateway를 지나면 THEN Gateway SHALL 그 경로 하나·`POST` 하나만 익명으로 열고, CSRF·세션·역할 판정을 적용하지 않으며, 본문 바이트와 서명 헤더를 바꾸지 않고 Payment로 전달하고, 다른 메서드·하위 경로는 열지 않는다. 서명 검증·재전달 판정은 Payment가 한다(Payment R6).
3. THE 통지 경로 이름 SHALL 공급자 이름을 담지 않는다(원칙 ②, [플랫폼 T28](../platform/tasks.md)). 지금 경로 이름은 design "adapter 메모"에만 적는다.
4. THE 통지 수신 SHALL 결제 상태를 확정하지 않는 힌트다. 통지가 Payment에 닿지 않아도 늦은 청구 발견은 Payment의 주기 조회가 지킨다([P3.13-8](../platform/requirements.md), [P4.2-7](../platform/requirements.md), I20). Gateway는 통지 전달 실패를 재시도하지 않는다(재전달은 공급자 책임).

### R8. 운영 설정 (새 요구사항)
**사용자 스토리:** 운영자로서, 배포 환경 값이 빠졌을 때 Gateway가 로컬 주소로 조용히 동작하지 않기를 바란다.

#### 수용 기준
1. THE 운영 설정 SHALL 하위 서비스 주소·Auth 주소·issuer·JWK 주소·공개 주소·Redis 주소·클라이언트 비밀값에 로컬 개발 기본값을 두지 않는다. IF 필수 값이 없으면 THEN Gateway SHALL 시작을 거절한다. 로컬 값은 로컬 Compose 오버라이드에만 둔다(원칙 ⑦).
2. THE CORS 허용 출처 SHALL 설정 키로 받고, 로컬 출처를 운영 설정에 고정하지 않는다.
3. THE 갱신 매개변수(lease 15초, 기다림 상한 12초, 토큰 요청 timeout 5초, 신선도 여유 10초)와 토큰 기록 TTL(7일) SHALL `config`에서 바인딩한 설정 값이며, 운영과 테스트가 같은 기본값을 쓴다.

## 범위 밖(후속)

- 대규모 트래픽 제어(요청 수 제한·회로 차단)·복잡한 배포 토폴로지 → 일반화 이후 확장.
- 실제 업무 상태 전이(구매·승인·지급·공개·심사 판정·환불)를 끝까지 잇는 역할별 전체 여정 → G4/G5.
- 서비스 자격증명 교체·배포 비밀 관리, 다중 Gateway 장애·복구 등 공통 운영 → G5.
- 실제 결제 공급자의 공개 HTTPS 통지 수신·인증서 갱신 → G7(결정 140).
