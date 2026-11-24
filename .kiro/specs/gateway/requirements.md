# 요구사항: Gateway — API 게이트웨이 (라우팅·역할별 경로 규칙, 내부 API 차단)

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다.

## 개요

API 게이트웨이는 모든 클라이언트별 BFF가 공유하는 내부 진입점이다([결정 223](../../../docs/decisions.md#per-client-bff)). 내부망에만 있고([결정 224](../../../docs/decisions.md#api-gateway-internal)) BFF가 릴레이한 Bearer만 받는다. 요청을 라우팅 표의 경로만 업무 모듈로 보내고, 그 밖의 경로(`/internal/**`, 운영 경로, 서비스 간 전용 명령)는 어떤 라우트에도 두지 않는다([P1.2-2](../platform/requirements.md)). 경로마다 요구 역할을 확인하는 1차 경계이고, 소유권·세부 권한은 받는 모듈이 JWT로 다시 판정한다.

BFF 로그인·세션·토큰 보관·릴레이·CSRF(옛 R3~R6)는 [웹 BFF 스펙](../web-bff/requirements.md)으로 옮겼다. 번호는 그대로 쓴다.

게이트웨이는 업무 DB·세션이 없다. 동기 의존은 게이트웨이 → 각 업무 모듈(라우팅) 한 행이다([플랫폼 design "동기 의존"](../platform/design.md#동기-의존-http)).

경로 줄임: `gateway:` = `apps/gateway/src/main/java/com/stove/gateway/`, `application.yml` = `apps/gateway/src/main/resources/application.yml`.

## 용어

- **라우트의 부재**: 외부 노출 통제를 필터 조건이 아니라 라우팅 표에 경로를 두지 않는 것으로 표현하는 방식.
- **공급자 통지 경로**: 결제 공급자가 서버 대 서버로 보내는 결제 상태 변경 통지(웹훅)를 받는 공개 경로. 통지는 Payment의 조회 힌트일 뿐 상태를 확정하지 않는다([P3.11-5](../platform/requirements.md), [P3.13-8](../platform/requirements.md)).
- **`esd-api` audience**: 업무 모듈이 Access Token에서 확인하는 대상 값.

## 요구사항

### R1. 업무 서비스 라우팅과 권한 경계
**사용자 스토리:** 클라이언트로서, 단일 진입점으로 각 업무 서비스를 호출하고 싶다. 그래야 서비스별 주소를 직접 알 필요가 없다.

#### 수용 기준
1. WHEN `/api/v1/auth/**` 요청이 오면 THEN Gateway SHALL auth로 라우팅한다. 브라우저 OIDC 경로(`/oauth2/authorize`·`/login`·`/logout`·`/.well-known/**`·`/error`·`/connect/**`·`/default-ui.css`)는 웹 BFF가 Auth로 바로 보낸다(결정 223). 토큰·폐기·JWKS·userinfo 엔드포인트는 어느 쪽에도 라우트가 없다(결정 220).
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

### R3~R6. (웹 BFF로 옮김)
BFF 로그인과 세션(R3), 토큰 릴레이와 갱신(R4), CSRF와 로그아웃(R5), 다중 BFF와 세션 저장소 장애(R6)는 [웹 BFF 스펙](../web-bff/requirements.md)에 있다.

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
