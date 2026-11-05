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

- [ ] 실제 역할별 접근 절차의 전체 여정 검증 — G5
- [ ] 분실 비밀번호 복구의 최소 계약·감사 — G5
- [ ] 계정 상태(활성/정지 등) 변경과 권한 관리의 최소 계약·감사 — G5
- [ ] 계정 복구/상태 변경 후 세션·토큰 정책과 감사의 여정 확인 — G5 (platform-generalization-plan 6절 "가입/로그인·계정 복구" 행)

## 범위 밖(후속)

- 소셜 로그인 다수.
- 고급 MFA·외부 인증 플랫폼.
- 다른 기기 전체 세션 종료 정책.
