# 요구사항: Gateway (라우팅·BFF 로그인·세션·토큰 관리)

## 개요

Gateway는 플랫폼의 단일 외부 진입점이다. 업무 서비스로 요청을 라우팅하고, 내부 전용 API를 외부에 노출하지 않는 역할을 한다.
재작업 후 BFF(Backend for Frontend)를 겸하며, 기밀 OAuth 클라이언트 `gateway-bff`로 Authorization Code + PKCE 로그인·토큰 교환·갱신을 수행한다.
브라우저는 세션 쿠키를 사용하고, 세션과 토큰은 Redis에 공유한다. 다중 Gateway가 같은 Redis를 공유한다.
MVP 재작성은 Auth와 함께 2026-10-13 완료했다(관련 테스트 132건 통과).

## 용어

- **BFF (Backend for Frontend)**: 브라우저 대신 Gateway가 OAuth 토큰을 보관·사용하는 방식. 브라우저에는 세션 쿠키만 내려준다.
- **gateway-bff**: Gateway가 쓰는 기밀 OAuth 클라이언트. Authorization Code + PKCE를 수행한다.
- **세션 저장소 장애**: 세션·토큰을 보관하는 Redis에 접근할 수 없는 상태. 이 경우 인증을 우회하지 않는다.
- **라우트의 부재**: 외부 노출 통제를 필터가 아니라 라우팅 표에 경로를 두지 않는 것으로 표현하는 방식.
- **esd-api audience**: 업무 서비스가 access token에서 검증하는 대상(audience) 값.

## 요구사항

### R1. 업무 서비스 라우팅과 권한 경계
**사용자 스토리:** 클라이언트로서, 단일 진입점으로 각 업무 서비스를 호출하고 싶다. 그래야 서비스별 포트를 직접 알 필요가 없다.

#### 수용 기준
1. WHEN `/api/v1/auth/**`, `/oauth2/**`, `/login`, `/logout`, `/.well-known/**` 요청이 오면 THEN 시스템 SHALL auth로 라우팅하고 브라우저 경로의 Host를 유지한다.
2. WHEN `/api/v1/storefront/**` 요청이 오면 THEN 시스템 SHALL store로 라우팅한다.
3. WHERE `/api/v1/products/**` 공개 라우트에서 THE 시스템 SHALL `GET` 메서드만 허용한다. 판매자 판매·노출 설정(`/api/v1/products/by-code/*/{sales,listing}`)만 별도 라우트로 `GET`·`PUT`을 연다.
4. WHEN `/api/v1/orders/**`, `/api/v1/payments/**`, `/api/v1/library/**`, `/api/v1/downloads/**` 요청이 오면 THEN 시스템 SHALL 각각 order·payment·license·download로 라우팅한다.
5. WHEN `/api/v1/studio/**`, `/api/v1/reviews/**` 요청이 오면 THEN 시스템 SHALL 각각 studio·review로 라우팅한다.
6. WHERE `/api/v1/settlements/me/**` 맥락에서 THE 시스템 SHALL `CREATOR` 역할을, 나머지 `/api/v1/settlements/**`에서는 `ADMIN` 역할을 요구한다.
7. WHERE `/api/v1/promotions/**` 맥락에서 THE 시스템 SHALL 판매자는 `CREATOR`, 플랫폼은 `ADMIN` 역할을 요구한다.
8. WHERE `/api/v1/admin/accounts/**`(auth), `/api/v1/admin/products/*/{sales,listing,downloads}/restriction`·`/api/v1/admin/catalog-release-jobs`(catalog), `/api/v1/admin/refunds/**`(order) 맥락에서 THE 시스템 SHALL `ADMIN` 역할을 요구한다.
9. THE 역할 판정 SHALL 경로에 선언한 역할만 인정하며, `ADMIN`에 `MEMBER`·`CREATOR` 권한을 암묵적으로 부여하지 않는다.
10. WHERE commerce·Catalog 판매/노출/다운로드 관리·관리자 환불 라우트에서 THE 시스템 SHALL 외부 요청의 `X-Member-Id` 헤더를 제거하고, 소유자는 하위 서비스가 JWT로 판정한다.

### R2. 내부 전용 API의 외부 차단
**사용자 스토리:** 운영자로서, 내부 전용 경로가 외부에서 호출되지 않기를 바란다. 그래야 서비스 간 신뢰 경계가 유지된다.

#### 수용 기준
1. THE 외부 노출 통제 SHALL 필터가 아니라 라우트의 부재로 표현한다.
2. WHERE `POST /api/v1/products/quote` 맥락에서 THE 시스템 SHALL Gateway를 통한 호출을 허용하지 않고 서비스 간 내부 호출로만 도달하게 한다.
3. THE 시스템 SHALL `/api/v1/ops/**` 라우트를 두지 않는다.
4. WHERE `/v3/api-docs/<service>` 문서 프록시 맥락에서 THE 시스템 SHALL 서비스당 한 경로씩 `GET`만 열고 하위 경로로 넓히지 않는다.

### R3. BFF 로그인과 세션
**사용자 스토리:** 브라우저 사용자로서, 로그인 후 세션 쿠키만으로 API를 쓰고 싶다. 그래야 토큰을 직접 다루지 않는다.

#### 수용 기준
1. WHEN `GET /oauth2/authorization/gateway-bff` 요청이 오면 THEN 시스템 SHALL 기밀 클라이언트 `gateway-bff`로 Authorization Code + PKCE 로그인을 시작한다.
2. WHEN `GET /login/oauth2/code/gateway-bff` callback이 오면 THEN 시스템 SHALL 토큰 교환을 수행한다.
3. WHEN `GET /bff/session` 요청이 오면 THEN 시스템 SHALL 검증된 사용자 식별자와 역할을 반환하고 토큰은 반환하지 않는다.
4. WHEN 로그인하면 THEN 시스템 SHALL Access/Refresh Token을 Redis에 보관하고 브라우저에는 세션 쿠키를 내려준다.
5. THE Redis 토큰 키 SHALL 로그인 때 바뀌는 세션 ID와 독립된 세션 속성에 연결한다.

### R4. 토큰 릴레이와 갱신
**사용자 스토리:** Gateway로서, 세션 쿠키 요청을 업무 서비스의 토큰 요청으로 바꾸고 싶다. 그래야 브라우저가 토큰을 보관하지 않아도 된다.

#### 수용 기준
1. WHEN 세션이 있는 요청을 업무 서비스로 보내면 THEN 시스템 SHALL Redis에 보관한 Access Token을 릴레이한다.
2. WHILE 토큰을 갱신하는 동안 THE 시스템 SHALL 기본 15초의 Redis 갱신 잠금을 적용한다.
3. THE 토큰 요청 timeout SHALL 기본 5초다.
4. IF 전송 실패가 불확실하면 THEN 시스템 SHALL 이전 Refresh Token으로 자동 재시도하지 않고 재로그인을 요구한다.
5. THE Bearer Token API 접근 SHALL 기존대로 지원한다.
6. IF Auth가 토큰 갱신을 거절하면(계정 복구·잠금·역할 회수 등) THEN 시스템 SHALL Redis 토큰과 브라우저 세션을 삭제하고 401을 반환하며 하위 요청을 보내지 않는다.
7. WHEN 허용된 요청을 하위 서비스로 전달하면 THEN 시스템 SHALL 하위 응답의 상태(202·4xx·503 포함)·본문·`Location`·`Cache-Control`을 바꾸지 않고 전달한다.

### R5. CSRF와 로그아웃
**사용자 스토리:** 사용자로서, 쿠키 인증의 변경 요청이 안전하기를 바란다. 그래야 세션 탈취 위험이 줄어든다.

#### 수용 기준
1. WHEN `GET /bff/csrf` 요청이 오면 THEN 시스템 SHALL 변경 요청용 CSRF 헤더 이름과 토큰을 반환한다.
2. WHERE 쿠키 인증의 변경 요청에서 THE 시스템 SHALL CSRF 헤더·토큰을 요구한다.
3. WHEN `POST /bff/logout` 요청이 오면 THEN 시스템 SHALL Refresh Token을 폐기하고 BFF 세션을 제거한 뒤 auth OIDC 로그아웃으로 이동한다.

### R6. 다중 Gateway와 세션 저장소 장애
**사용자 스토리:** 운영자로서, Gateway를 여러 개 띄우고도 세션이 공유되기를 바란다. 그래야 가용성이 유지된다.

#### 수용 기준
1. THE 여러 Gateway SHALL 동일 Redis, 세션 namespace, 클라이언트 자격증명을 사용한다.
2. WHERE 로드밸런서의 공개 주소 맥락에서 THE 시스템 SHALL issuer·redirect 등 공개 주소를 그 값으로 맞추고 인스턴스 내부 주소로 callback을 등록하지 않는다.
3. IF 세션 저장소에 접근할 수 없으면 THEN 시스템 SHALL 인증을 우회하지 않고 `503 SESSION_STORE_UNAVAILABLE`로 반환한다.
4. WHEN Gateway·auth가 재시작하면 THEN 시스템 SHALL 공유 Redis·MySQL 상태를 유지한다.

## 범위 밖(후속)

- 대규모 트래픽 제어·복잡한 배포 토폴로지 → 일반화 이후 확장.
- 실제 업무 상태 전이(구매·승인·지급·공개·심사 판정)를 끝까지 잇는 역할별 전체 여정, 외부 웹훅의 재현 가능한 실행 구성 → 일반화 G4/G5.
- 서비스 자격증명 교체·배포 비밀 관리, 다중 Gateway 장애·복구 등 공통 운영 → G5.
