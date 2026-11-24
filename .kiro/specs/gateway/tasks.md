# 작업: Gateway

## 완료 (검증 결과는 근거 색인)

- [x] 업무 서비스 라우팅과 권한 경계(settlement `/me/**` CREATOR, 나머지 ADMIN 등) — 2026-10-13
- [x] catalog 라우트 `Method=GET` 조건, `POST /api/v1/products/quote`의 Gateway 차단(내부 호출만) — `docs/services.md`
- [x] 외부 노출 통제를 라우트의 부재로 표현, `/api/v1/ops/**` 미노출 — `docs/decisions.md`(18·19번)
- [x] `/v3/api-docs/<service>` 문서 프록시 9개를 GET 전용·한 경로씩만 개방 — `docs/decisions.md`(18번)
- [x] Gateway 경계 — 2026-11-05 실제 Auth·Gateway, 43개 method/path의 역할별 허용·거절·JWT 릴레이·`X-Member-Id` 제거·응답 투명 전달(requirements R1·R4). 하위 업무는 HTTP 픽스처([근거 색인](../../../docs/evidence.md)).
- [x] 실제 서비스 소유권·역할 진입 — 2026-11-05 실제 11개 앱에서 회원별 주문·결제·라이브러리, 창작자별 Studio·정산 격리, 심사자/관리자 진입과 타 역할 거절([근거 색인](../../../docs/evidence.md)).
- [x] 서비스 접근키 수신 경계 — 2026-11-05 Store·Order·Download·Settlement→Catalog, Payment→Order. 단위·아키텍처 464건·실제 DB/HTTP 통합 20건. [`docs/g5-service-key-verification.md`](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g5-service-key-verification.md)를 따른다.
- [x] Compose·검증 실행기의 프로젝트 키 전달 범위 — 2026-11-05. [`docs/g5-credential-distribution-verification.md`](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g5-credential-distribution-verification.md)를 따른다.
## 코드와 맞출 것

Gateway에 넘어온 MC-* 이월 항목은 없다(`module-carryover.md`에 Gateway 절 없음). 아래는 이번 재정의에서 새로 찾은 차이다. 영향이 돈·보유권이면 굵게 적는다.

- [ ] **GW-T1. 공급자 통지가 Gateway를 지나면 401이다** [결정 필요 M-GW-1]
  - 어디서: `gateway:config/SecurityConfig.java:127-128`(`/api/v1/payments/**` → `hasRole("MEMBER")`), `application.yml:155-159`(`payment` 라우트가 통지 경로까지 덮음). 지금 통지는 공개 edge가 Payment로 직접 보낸다(`docs/security.md:48`·`:115`).
  - 목표: R7. M-GW-1이 B(Gateway 경유)면 통지 경로 하나·`POST` 하나 익명 허용, CSRF 예외, 본문·서명 헤더 무변경 전달, 그 밖 메서드 거절. A(edge 직접)면 지금 동작을 계약으로 적고 Gateway 경로 표에서 통지 경로를 "Gateway 밖"으로 명시한다. 어느 쪽이든 경로 이름 중립화(플랫폼 T28)와 같은 PR.
  - 영향: 돈 직접 영향 없음 — 통지는 힌트이고 늦은 청구 발견은 Payment 주기 조회가 지킨다(I20). 통지가 닿지 않으면 발견이 다음 조회까지 늦어진다.
  - 재현: Gateway 테스트 — 서명 헤더를 단 익명 `POST /api/v1/payments/webhooks/portone`이 지금 401인지(Red 기준은 결정 후 정함).

- [ ] GW-T8. 운영 설정에 로컬 기본값과 로컬 CORS 출처가 있다
  - 어디서: `application.yml:19-20`·`:37-45`·`:62-71`·`:81-199`, `BffClientConfig.java:165`.
  - 목표: R8.1·R8.2. 필수 키 기본값 제거(없으면 시작 거절), CORS 출처는 설정 키, 로컬 값은 `docker-compose.apps.local.yml`로.
  - 영향: 배포에서 키를 빠뜨리면 localhost로 조용히 붙는다. 운영 출처의 CORS를 설정으로 열 수 없다. 돈 없음.
  - 재현: 컨텍스트 테스트 — `AUTH_URI` 없이 시작하면 실패.

- [ ] GW-T11. 관리 엔드포인트 노출 목록에 `gateway`가 있고 보안 거절에만 기댄다
  - 어디서: `application.yml:273`, `GatewayActuatorExposureTest#gatewayRouteListingIsNotExposed`(401만 확인).
  - 목표: R2.5. 노출 목록에서 빼고 테스트는 404(엔드포인트 없음)를 확인. `/actuator/prometheus` 익명 공개(`SecurityConfig.java:90`)는 M-GW-6에 묶는다.
  - 영향: 보안 설정 한 줄 실수로 라우트 표(내부 주소·포트)와 갱신 쓰기가 열린다. 돈 없음.
  - 재현: 위 테스트를 404 기대로.

- [ ] GW-T16. `/internal/**` 비노출을 직접 단언하는 테스트가 없다
  - 어디서: `GatewayRouteTest#unknownPathMatchesNothing`은 임의 경로만 본다.
  - 목표: GW-I1, 플랫폼 P1.2-2. 모든 모듈의 `/internal/v1/**` 실제 경로(플랫폼 design 동기 의존 표)를 넣은 매개변수 테스트. 플랫폼 T27(런타임 의존 대조)과 같은 표를 쓴다.
  - 영향: 라우트를 넓히는 실수(`/api/**` 같은 와일드카드)를 늦게 안다.
  - 재현: 라우트 테스트.

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
## 범위 밖(후속)

- 대규모 트래픽 제어.
- 복잡한 배포 토폴로지.
- 실제 결제 공급자 공개 HTTPS 통지 수신·인증서 갱신 확인 — G7.

BFF 항목(로그인·세션·토큰 보관·갱신·CSRF·즉시 차단·PC 앱 세션, GW-T2~T7·T9·T10·T12~T15·T17·T18·T20·T21, M-GW-2·3·8)은 [웹 BFF tasks](../web-bff/tasks.md)로 옮겼다(결정 223). 번호는 그대로다.
