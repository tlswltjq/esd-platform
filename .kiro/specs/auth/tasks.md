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

- [x] 실제 역할별 접근 절차의 전체 여정 검증 — Gateway 필터 판정 수준 완료(2026-11-05). `RoleAccessGatewayTest`가 MEMBER·ADMIN·CREATOR·REVIEWER 통과/거부, 미인증 차단(GET 401·변경 메서드 CSRF 403), 미선언 경로 도달 불가를 지킨다. 하위 서비스를 실제로 띄운 끝단 여정은 각 모듈 통합 테스트 범위.
- [x] 분실 비밀번호 복구의 최소 계약·감사 — 완료(2026-11-05). 요청/실행 2단계, 30분 유효, 계정 존재 비노출(가입 여부 무관 동일 응답), 일회용 토큰(SHA-256 해시 저장)·만료·일회성, 복구 성공 시 전 토큰 폐기. 메일은 `PasswordResetMailPort`로 추상화(현재 모의, 실제 발송은 후속). 단위 `RecoverPasswordTest`, 통합 `PasswordRecoveryIntegrationTest`(실제 MySQL) 통과. 계약은 requirements R6.
- [x] 계정 상태(활성/정지 등) 변경과 권한 관리의 최소 계약·감사 — 완료(2026-11-05). ADMIN 전용 `/api/v1/admin/accounts/**`. 상태 전환 규칙(ACTIVE↔LOCKED, →DISABLED 단방향), 역할 부여·회수, 자기 보호·마지막 ADMIN 보호, 변경 시 즉시 토큰 폐기 + `role_audit_log` 감사. 단위 `UserAccountTest`·`ManageAccountTest`, 통합 `AccountManagementIntegrationTest`(실제 MySQL) 통과. 계약은 requirements R7.
- [x] 계정 복구/상태 변경 후 세션·토큰 정책과 감사의 여정 확인 — G5 최소 계약 검증 완료(2026-11-05). 기존 단위·MySQL 통합 검증에 더해 실제 Auth·Gateway·Redis·MySQL에서 복구·잠금·역할 회수 HTTP 이후 전체 Refresh Token 계열 폐기, 갱신 거절 401, Redis 토큰·브라우저 세션 삭제와 다음 요청 차단, 상태·역할 변경 주체별 감사를 확인했다. 모의 메일의 복구 토큰 픽스처와 검증용 25초 access token의 범위, 재실행 명령·자원 보존은 `docs/auth-session-journey-verification.md`를 따른다. 실제 메일·공통 운영 G5 전체 완료와 구분한다.

## 범위 밖(후속)

- 소셜 로그인 다수.
- 고급 MFA·외부 인증 플랫폼.
- 다른 기기 전체 세션 종료 정책.
