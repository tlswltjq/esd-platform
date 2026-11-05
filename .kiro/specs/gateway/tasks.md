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
- [x] 다중 Gateway 공유 Redis·세션 namespace·클라이언트 자격증명, LB 공개 주소 구성 — `docs/auth-bff.md`
- [x] 세션 저장소 장애 시 인증 우회 없이 `503 SESSION_STORE_UNAVAILABLE` 반환·복구 — 2026-10-13, `docs/auth-bff.md`
- [x] 통합 검증: 실제 스택에서 자동 로그인·토큰 릴레이·다중 Gateway 동시 갱신·RTR 재사용 탐지·BFF/auth 로그아웃·재시작 유지·Redis 장애 503, JWT 변조/만료/issuer/audience (관련 132건) — 2026-10-13, `docs/auth-bff.md`

## 진행 중 / 남음 (일반화 공백)

- [ ] 최신 API의 실제 역할별 여정과 오류/진행 응답 확인 — G4 (platform-generalization-plan 4절 Gateway 행)
- [ ] 외부 웹훅의 재현 가능한 실행 구성 — G4/G5
- [ ] 계정 복구/상태/운영 권한의 최소 절차와 역할별 전체 여정의 단일 기준 확정 — G5
  - 2026-11-05 `BffAuthenticationFilterTest`: 토큰 갱신 거절 시 브라우저 세션 삭제·401·하위 요청 차단. `RoleAccessGatewayTest`: 계정 상태 변경·역할 부여·회수 세 경로의 ADMIN 통과와 MEMBER·CREATOR·REVIEWER 거절. 이 단위·필터 검증과 아래의 실제 연결 여정을 구분한다.
  - 2026-11-05 실제 Auth·Gateway·Redis·MySQL 연결 검증에서 복구·잠금·역할 회수 뒤 갱신 거절과 Redis 토큰·브라우저 세션 삭제를 확인했다. `docs/auth-session-journey-verification.md`를 따른다. 전체 역할 여정·서비스 자격증명 등 G5 공통 운영 범위는 남아 있으므로 상위 항목은 미완료로 유지한다.

## 범위 밖(후속)

- 대규모 트래픽 제어.
- 복잡한 배포 토폴로지.
