# 설계: Gateway

> 상태: 작성 중(결정 150·178). 판단 기준은 [판단 원칙](../../../docs/architecture.md#판단-원칙-결정-147)(결정 147)이다. 줄 번호는 2026-11-10 `spec-foundation` 작업 폴더 기준이다. 경로 줄임은 requirements와 같다(`gateway:` = `apps/gateway/src/main/java/com/stove/gateway/`).

## 아키텍처 위치

- 포트 8093, Spring Cloud Gateway(WebFlux) + Spring Security(Resource Server). 업무 DB·세션·Redis 없음.
- 내부망에만 둔다([결정 224](../../../docs/decisions.md#api-gateway-internal)). 부르는 쪽은 클라이언트별 BFF뿐이고(지금은 [웹 BFF](../web-bff/), [결정 223](../../../docs/decisions.md#per-client-bff)), BFF가 릴레이한 Bearer(JWT 서명·issuer·`esd-api` audience·만료)만 받는다. 쿠키·CSRF·CORS는 없다.
- 맡는 일: 역할별 경로 규칙(모든 BFF 공통), 라우팅 표, 지정 라우트의 `X-Member-Id` 제거, 서비스 명세 프록시.
- 플랫폼 안 자리: [플랫폼 design "모듈과 외부 공급자"](../platform/design.md#모듈과-외부-공급자)의 Gateway 행, [동기 의존](../platform/design.md#동기-의존-http)의 "Gateway → 각 모듈" 행. 다른 업무 모듈의 구현을 참조하지 않는다([P1.1-1](../platform/requirements.md)).
- 의존 방향: BFF → API 게이트웨이 → {Auth, Store, Catalog, Order, Payment, License, Download, Studio, Review, Settlement}. 업무 모듈은 게이트웨이를 부르지 않는다.

BFF 세션·토큰 기록·갱신 lease·BFF 경로·Auth 토큰 연동은 [웹 BFF design](../web-bff/design.md)으로 옮겼다.

## API 계약

### 라우팅 표

| 라우트 ID | 경로·메서드 | 대상 | 요구 역할(`SecurityConfig.java:89-129`) | `X-Member-Id` 제거 |
|---|---|---|---|---|
| `auth-api` | `/api/v1/auth/**` | auth | 가입·비밀번호 복구 익명, `/password`·`/session` 인증, 나머지 거절 | 아니오 |
| `auth-admin-accounts` | `/api/v1/admin/accounts/**` POST | auth | `ADMIN`(`*/status`·`*/roles/grant`·`*/roles/revoke`) | 예 |
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

업무 라우트 20개 + 문서 프록시 10개 = 30개(`application.yml`). `auth-browser`는 웹 BFF로 옮겼다(결정 223, [웹 BFF design](../web-bff/design.md#라우트)). 플랫폼 design의 "라우트 22개"와 다르다(notes F).

### 오류 응답

| 상태 | 언제 | 본문 |
|---|---|---|
| 401 | Bearer 없음·검증 실패, 표에 없는 경로(익명) | 없음 |
| 403 | 역할 없음, 표에 없는 경로(인증됨) | 없음 |
| 404 | 공개 허용 경로 아래 라우트 없음(`POST /api/v1/products/quote`) | 없음 |
| 그 밖 | 하위 서비스 응답 | 상태·본문·`Location`·`Cache-Control` 그대로(R4.7) |

## 이벤트

지금은 발행·소비하지 않는다. 목표([결정 225](../../../docs/decisions.md#revocation-placement)): 계정 접근 변경 사실(결정 180, Auth 토픽 `stove.auth.v1`, 키 `subject`)을 웹 BFF와 다른 그룹으로 소비해 `subject`별 거절 기준(`changedAt`)을 Redis에 두고, `iat`가 기준보다 이른 Bearer를 모든 BFF 경로에서 401로 거절한다. 멱등은 `eventId`, 실패는 [결정 226](../../../docs/decisions.md#access-change-consumer-failure)(일시 실패는 성공까지 재시도, 영구 실패만 DLT). 구현은 GW-T18. 공급자 통지(웹훅)는 이벤트가 아니라 HTTP 전달이다(R7).

## 모듈 안 불변식

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| GW-I1 | 라우팅 표 밖 경로(`/internal/**`, `/api/v1/ops/**`, `POST /api/v1/products/quote`)는 하위 서비스에 닿지 않는다 — 플랫폼 [P1.2-2](../platform/requirements.md) | `application.yml` 라우트, `SecurityConfig.java:129`(`anyExchange().denyAll()`) | `GatewayRouteTest#unknownPathMatchesNothing`·`#catalogWriteEndpointsAreUnreachable`·`#catalogBuildMetadataCannotBeRoutedThroughTheGateway`, `RoleAccessGatewayTest#undeclaredPathsAreUnreachable`. `/internal/**` 전용 단언은 없음 → tasks GW-T16 |
| GW-I2 | 역할은 계층이 아니다: 경로에 선언한 역할만 통과 | `SecurityConfig.java:97-128`(`hasRole`·`hasAnyRole`만, 역할 계층 bean 없음) | `RoleAccessGatewayTest#missingRoleIsForbidden`·`#multiRoleTokenPassesEachOwnedRole` |
| GW-I10 | 하위 응답을 바꾸지 않는다 | 라우트에 응답 필터 없음(`application.yml:76-247`) | `PaymentApprovalGatewayTest#approvalPreservesTheBodyLocationAndBearerAndRemovesASpoofedMemberHeader`, `RefundRetryGatewayTest#memberRetryPreservesThePathBodyBearerReceiptAndPollingLocation`, `RefundAdminGatewayTest#administratorRefundAmountsAndUnknownValuesPassThroughWithoutChangingTheirMeaning` |
| GW-I11 | 지정 라우트에서 외부 `X-Member-Id`가 하위에 닿지 않는다 | 라우트 `RemoveRequestHeader=X-Member-Id` | `*AdminGatewayTest#…WithoutForgedMemberHeader`, `PaymentApprovalGatewayTest#approvalPreserves…RemovesASpoofedMemberHeader` |
| GW-I12 | 라우트 조회·갱신 관리 엔드포인트는 외부에서 쓸 수 없다 | `SecurityConfig.java:90-91`(health·prometheus만 허용), 노출 목록은 `gateway` 포함(`application.yml:273`) | `GatewayActuatorExposureTest#gatewayRouteListingIsNotExposed`·`#gatewayRefreshIsNotExposed`(보안 거절 401만 확인) → GW-T11 |

BFF 쪽 불변식(GW-I3~I9)은 [웹 BFF design](../web-bff/design.md#모듈-안-불변식)에 있다.

## 외부 연동과 adapter 메모

### 결제 공급자 통지 (들어옴)

- 지금 흐름: 공개 HTTPS edge가 Payment로 직접 보낸다(`docs/security.md:48`·`:97-115`). 게이트웨이는 결정 224로 내부망에만 있으므로, 통지를 Gateway 계층으로 받는다면 공개 입구(웹 BFF 또는 별도 입구)부터 정해야 한다. 어느 쪽이 목표인지는 [결정 필요 M-GW-1].
- Gateway를 지나게 될 때 Gateway가 지킬 것(R7.2): 본문 바이트 그대로(Payment가 원문으로 서명을 검증한다), 서명 헤더 그대로, `POST` 하나, 쿠키·CSRF·역할 없음.

> adapter 메모(공급자 이름은 여기만): 지금 통지 경로는 `/api/v1/payments/webhooks/portone`(`payment:api/controller/PortOneWebhookController.java:28`)이고 공급자 이름을 담아 결정 140·플랫폼 T28 중립화 대상이다. 서명은 Standard Webhooks 방식 헤더 `webhook-id`·`webhook-timestamp`·`webhook-signature`(HMAC-SHA256)를 Payment `HmacPortOneWebhookVerifier`가 원문으로 검증하고, 본문 상한 64 KiB도 Payment가 지킨다(Payment R6.1). 통지 주소 결정 기록은 결정 29 하위 "웹훅 수신 주소: 인스턴스 공개 IP + IP 인증서"다. 실제 PortOne 공개 수신 확인은 G7(결정 140).

## 설정

BFF 설정(Redis·세션·클라이언트 비밀·공개 주소·CORS·갱신 매개변수)은 [웹 BFF design](../web-bff/design.md#설정)에 있다.

| 키 | 지금 기본값 | 목표 | 근거 |
|---|---|---|---|
| `AUTH_URI` | `http://localhost:8091` | 필수 | R8.1, `application.yml:38-40`·`:81-91` |
| `JWT_ISSUER_URI`·`JWT_JWK_SET_URI` | `http://localhost:8091`·`…/oauth2/jwks` | 필수 | R8.1, `application.yml:44-45` |
| `<SERVICE>_URI` ×9 | `http://localhost:808x` | 필수 | R8.1, `application.yml:100-199` |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,gateway` | `health,info,prometheus` | R2.5, `application.yml:273` |
| `TRACING_SAMPLE_RATE` | `1.0` | 같음(하위와 같은 값) | `application.yml:276` |

운영/테스트 차이: 테스트는 `@DynamicPropertySource`로 하위 주소·비밀값을 넣고(`*GatewayTest`, `BffLogoutIntegrationTest.java:31`), 운영 계약에 테스트 전용 경로나 프로파일은 없다. 다만 위 localhost 기본값은 로컬 개발 값이 운영 설정에 들어 있는 것이라 원칙 ⑦ 점검에서 미충족으로 본다(GW-T8).
