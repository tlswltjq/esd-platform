# 작업: Gateway

## 완료 (MVP·검증 기록 있음)

- [x] 업무 서비스 라우팅과 권한 경계(settlement `/me/**` CREATOR, 나머지 ADMIN 등) — 2026-10-13
- [x] catalog 라우트 `Method=GET` 조건, `POST /api/v1/products/quote`의 Gateway 차단(내부 호출만) — `docs/services.md`
- [x] 외부 노출 통제를 라우트의 부재로 표현, `/api/v1/ops/**` 미노출 — `docs/decisions.md`(18·19번)
- [x] `/v3/api-docs/<service>` 문서 프록시 9개를 GET 전용·한 경로씩만 개방 — `docs/decisions.md`(18번)
- [x] BFF 기밀 클라이언트 `gateway-bff`로 Authorization Code + PKCE 로그인·callback·토큰 교환 — 2026-10-13
- [x] `/bff/session`·`/bff/csrf`·`/bff/logout`, 토큰 Redis 보관, 세션 쿠키 — `docs/auth-bff.md`
- [x] 토큰 릴레이·갱신(Redis 갱신 잠금 15초, 토큰 요청 timeout 5초), 불확실한 전송 실패 시 재로그인 — `docs/auth-bff.md`
- [x] 쿠키 변경 요청 CSRF 요구, Bearer Token API 접근 유지 — `docs/auth-bff.md`
- [x] 외부 웹훅의 재현 가능한 실행 구성(흉내 서버) — 2026-11-09, PortOne 흉내 서버가 서명한 웹훅을 Payment로 직접 보내는 실제 스택 여정 `tools/verification/verify-webhook-journeys.py`([결정 138](../../../docs/decisions.md#fake-portone-webhooks), [근거 색인](../../../docs/evidence.md))
- [x] 다중 Gateway 공유 Redis·세션 namespace·클라이언트 자격증명, LB 공개 주소 구성 — `docs/auth-bff.md`
- [x] 세션 저장소 장애 시 인증 우회 없이 `503 SESSION_STORE_UNAVAILABLE` 반환·복구 — 2026-10-13, `docs/auth-bff.md`
- [x] 통합 검증: 실제 스택에서 자동 로그인·토큰 릴레이·다중 Gateway 동시 갱신·RTR 재사용 탐지·BFF/auth 로그아웃·재시작 유지·Redis 장애 503, JWT 변조/만료/issuer/audience (관련 132건) — 2026-10-13, `docs/auth-bff.md`

## 진행 중 / 남음 (일반화 공백)

- [ ] [결정 필요] 최신 API의 실제 역할별 여정과 오류/진행 응답 확인 — G4. 남은 범위(아래)는 G5 "역할별 전체 여정의 단일 기준"(아래 `[결정 필요]`)과 같은 결정에 묶인다 ([일반화 계획](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/platform-generalization-plan.md) 4절 Gateway 행)
  - [x] Gateway 경계 — 2026-11-05 실제 Auth·Gateway, 43개 method/path의 역할별 허용·거절·JWT 릴레이·`X-Member-Id` 제거·응답 투명 전달(requirements R1·R4). 하위 업무는 HTTP 픽스처([근거 색인](../../../docs/evidence.md)).
  - [x] 실제 서비스 소유권·역할 진입 — 2026-11-05 실제 11개 앱에서 회원별 주문·결제·라이브러리, 창작자별 Studio·정산 격리, 심사자/관리자 진입과 타 역할 거절([근거 색인](../../../docs/evidence.md)).
  - [ ] 남은 범위: 구매·승인·지급·공개 출시·심사 판정·환불 전이를 실제 업무 상태로 잇는 역할별 전체 여정(위 두 검증은 픽스처 기반 구매 근거·HTTP 픽스처를 사용).
- [ ] [외부 대기] 실제 PortOne 웹훅 공개 HTTPS 수신의 재현(2026-10-27 edge 구성 `tools/verification/support/portone-edge/`의 스크립트화와 실제 PortOne 여정) — 전용 가맹점이 생긴 뒤([결정 138](../../../docs/decisions.md#fake-portone-webhooks)·[결정 119](../../../docs/decisions.md#g1-exit-verification))
- [ ] [결정 필요] 계정 복구/상태/운영 권한의 최소 절차와 역할별 전체 여정의 단일 기준 확정 — G5
  - [x] 서비스 접근키 수신 경계 — 2026-11-05 Store·Order·Download·Settlement→Catalog, Payment→Order. 단위·아키텍처 464건·실제 DB/HTTP 통합 20건. [`docs/g5-service-key-verification.md`](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g5-service-key-verification.md)를 따른다.
  - [x] Compose·검증 실행기의 프로젝트 키 전달 범위 — 2026-11-05. [`docs/g5-credential-distribution-verification.md`](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g5-credential-distribution-verification.md)를 따른다.
  - [x] 계정 변경 후 세션 정리 — 단위(`BffAuthenticationFilterTest`·`RoleAccessGatewayTest`)와 실제 Auth·Gateway·Redis·MySQL 여정에서 갱신 거절 시 Redis 토큰·브라우저 세션 삭제·401(requirements R4.6, [근거 색인](../../../docs/evidence.md)).
  - [ ] 남은 범위: 서비스 키 교체·배포 비밀 관리, 다중 Gateway 장애·복구, 실제 메일, 공통 운영 전체.

## 범위 밖(후속)

- 대규모 트래픽 제어.
- 복잡한 배포 토폴로지.
