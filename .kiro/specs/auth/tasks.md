# 작업: Auth

## 완료 (MVP·검증 기록 있음)

- [x] 계정 가입(MEMBER / MEMBER+CREATOR), 역할 부여, `UserRegistered` 발행 — 2026-10-13
- [x] OAuth2 Authorization Code + PKCE, access token(member_id·roles) 발행, studio/commerce 범위 — 2026-10-13
- [x] Refresh Token 회전(RTR)·7일 절대 상한·재사용 탐지·계열 폐기 — 2026-10-13
- [x] 갱신 시 계정 상태·최신 역할 재확인, 비활성 계정 갱신 거부 — 2026-10-13
- [x] 현재 비밀번호 확인 후 변경, 토큰 계열 폐기, 12~100자 — `docs/auth-bff.md`
- [x] `role_audit_log` 감사 기록 — `docs/security.md`
- [x] 통합 검증: 실제 MySQL에서 OIDC·RTR·재사용·동시 갱신·만료·계정 비활성화·폐기 (관련 132건) — 2026-10-13, `docs/auth-bff.md`

## 진행 중 / 남음 (일반화 공백)

- [x] 실제 역할별 접근 절차 — Gateway 필터 판정 수준 완료(2026-11-05, `RoleAccessGatewayTest`). 하위 서비스 끝단 여정은 각 모듈 통합 테스트 범위.
- [x] 분실 비밀번호 복구의 최소 계약·감사 — 2026-11-05, requirements R6. `RecoverPasswordTest`·`PasswordRecoveryIntegrationTest`(실제 MySQL).
- [x] 계정 상태·권한 관리의 최소 계약·감사 — 2026-11-05, requirements R7. `UserAccountTest`·`ManageAccountTest`·`AccountManagementIntegrationTest`.
- [x] 복구·잠금·역할 회수 후 세션·토큰 정리 여정 — 2026-11-05 실제 Auth·Gateway·Redis·MySQL([근거 색인](../../../docs/evidence.md)). 복구 토큰은 DB 해시 픽스처, access token은 검증용 25초를 썼다.
- [ ] [결정 필요] 실제 메일 발송 연동(`PasswordResetMailPort`의 실제 어댑터) — 현재 모의 구현.

## 범위 밖(후속)

- 소셜 로그인 다수.
- 고급 MFA·외부 인증 플랫폼.
- 다른 기기 전체 세션 종료 정책.
