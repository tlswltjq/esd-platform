# 작업: Auth

> 목표 계약은 [requirements.md](requirements.md)·[design.md](design.md). 모듈을 넘는 항목은 [플랫폼 tasks](../platform/tasks.md)(`T`번호)에 있고 여기서는 Auth가 맡는 몫만 적는다.

## 완료 (검증 결과는 근거 색인)

- [x] 계정 가입(MEMBER / MEMBER+CREATOR), 역할 부여, `UserRegistered` 발행 — 2026-10-13
- [x] OAuth2 Authorization Code + PKCE, access token(member_id·roles) 발행, studio/commerce 범위 — 2026-10-13
- [x] Refresh Token 회전(RTR)·7일 절대 상한·재사용 탐지·계열 폐기 — 2026-10-13
- [x] 갱신 시 계정 상태·최신 역할 재확인, 비활성 계정 갱신 거부 — 2026-10-13
- [x] 현재 비밀번호 확인 후 변경, 토큰 계열 폐기, 12~100자 — `docs/auth-bff.md`
- [x] `role_audit_log` 감사 기록 — `docs/security.md`
- [x] 통합 검증: 실제 MySQL에서 OIDC·RTR·재사용·동시 갱신·만료·계정 비활성화·폐기 (관련 132건) — 2026-10-13, `docs/auth-bff.md`
- [x] 실제 역할별 접근 절차 — Gateway 필터 판정 수준 완료(2026-11-05, `RoleAccessGatewayTest`). 하위 서비스 끝단 여정은 각 모듈 통합 테스트 범위.
- [x] 분실 비밀번호 복구의 최소 계약·감사 — 2026-11-05, requirements R6. `RecoverPasswordTest`·`PasswordRecoveryIntegrationTest`(실제 MySQL).
- [x] 계정 상태·권한 관리의 최소 계약·감사 — 2026-11-05, requirements R7. `UserAccountTest`·`ManageAccountTest`·`AccountManagementIntegrationTest`.
- [x] 복구·잠금·역할 회수 후 세션·토큰 정리 여정 — 2026-11-05 실제 Auth·Gateway·Redis·MySQL([근거 색인](../../../docs/evidence.md)). 복구 토큰은 DB 해시 픽스처, access token은 검증용 25초를 썼다.

## 코드와 맞출 것

> 형식: 무엇이 · 어디서 · 목표 · 영향 · 재현 테스트. 이 모듈로 넘어온 `MC-*` 이월 항목은 없다(`module-carryover.md`에 Auth 절 없음). 아래는 이번에 새로 찾은 차이다. Auth에는 돈·보유권을 직접 바꾸는 상태가 없다. 운영자 권한(`ADMIN`)은 환불·정산·공개 제한 운영 API의 관문이라 **간접 돈·보유권**으로 굵게 적는다.

- [ ] **AU-T1. Auth가 자기 운영자 API에서 `ADMIN`을 확인하지 않는다**
  - 어디서: `auth:config/AuthSecurityConfig.java:122-134` — `/api/v1/admin/accounts/**`가 `.anyRequest().authenticated()`에 걸린다. `ADMIN` 판정은 Gateway(`gateway:config/SecurityConfig.java:98-99`)에만 있다. Catalog는 자기 설정에서도 `hasRole("ADMIN")`을 건다(`catalog:config/SecurityConfig.java:65-67`).
  - 목표: R5.3·R7.1. Auth 보안 설정에 `/api/v1/admin/accounts/**` → `hasRole("ADMIN")`.
  - 영향: **간접 돈·보유권(권한 상승)** — 내부망에서 Auth(8091)에 직접 닿는 `MEMBER` 토큰이 자기 외 계정에 `ADMIN`을 부여할 수 있다(자기 자신은 AU-I6이 막지만 공모 계정 둘이면 된다). 그 `ADMIN`으로 관리자 환불·운영 API를 쓴다.
  - 재현: `AccountManagementIntegrationTest`에 `MEMBER` 토큰으로 `POST /api/v1/admin/accounts/{other}/roles/grant` → 지금 200, 목표 403.

- [ ] **AU-T2. 계정 상태·역할 칸에 DB CHECK가 없다**
  - 어디서: `auth/db/V1__init.sql:7`(`status`), `:18`(`role`).
  - 목표: design 데이터 모델 `chk_user_account_status`·`chk_user_account_role`(원칙 ④, 점검표 2). 이행 전 기존 행 값 확인 SQL을 PR에 적는다.
  - 영향: 수동 SQL·이행 실수로 enum 밖 값이 들어가면 JPA 로딩이 실패해 그 계정의 로그인·갱신이 500이 된다.
  - 재현: 이행 테스트 — `UPDATE user_account SET status='SUSPENDED'`가 거절되는지.

- [ ] **AU-T3. 부트스트랩 계정이 `MEMBER` 없이 생긴다** `[결정 필요 M-AU-1]`
  - 어디서: `auth:core/service/BootstrapAccounts.java:24-25`(`{REVIEWER}`, `{ADMIN, REVIEWER}`), `UserAccount.withRoles`(`auth:core/domain/UserAccount.java:77-79`)는 `MEMBER`를 강제하지 않는다. 한편 `UserAccount.revokeRole`(`:117-129`)의 주석·R7.3은 "`MEMBER`는 모든 계정의 기본 역할"이라 한다.
  - 목표: M-AU-1 결정에 따라 AU-I4를 생성 경로까지 지키거나(역할 집합에 `MEMBER` 강제), 불변식을 "공개 가입 계정은 `MEMBER`"로 좁힌다.
  - 영향: 운영자 계정이 구매·라이브러리 API에서 403. 역할 표에 "`MEMBER` 미보유" 열이 생겨 회수·부여 규칙이 갈린다.
  - 재현: `BootstrapAccountsTest`(없음) — 부트스트랩 뒤 `hasRole(MEMBER)`.

- [ ] **AU-T4. 마지막 운영자 보호가 상태를 무시하고, 잠금이 없고, 상태 변경에는 없다**
  - 어디서: `auth:core/service/ManageAccount.java:60`(`countByRole(ADMIN) <= 1`, `UserAccountRepository.countByRole`은 `LOCKED`·`DISABLED` 운영자도 센다), `:37-47`(`changeStatus`에는 검사 없음).
  - 목표: R7.5·AU-I5. 활성 운영자(`ACTIVE`·`ADMIN`) 집합을 `SELECT … FOR UPDATE`로 잠그고(또는 단일 잠금 행), 상태 변경(`LOCKED`·`DISABLED`)과 `ADMIN` 회수 모두 변경 뒤 활성 운영자 ≥ 1을 확인.
  - 영향: **간접 돈·보유권(운영 불능)** — 운영자 A·B가 동시에 서로를 `LOCKED`하면 둘 다 통과해 활성 운영자 0명. 관리자 환불·보류 종결·DLT 재투입을 할 사람이 없고, 복구는 DB 수동 수정뿐이다. `DISABLED` 운영자가 수에 들어가 실제 마지막 활성 운영자의 `ADMIN` 회수도 통과할 수 있다(그 운영자가 요청자가 아닐 때).
  - 재현: 통합 — 활성 운영자 2명이 서로를 동시에 `LOCKED` → 목표: 한 건만 성공. 단위 — `ADMIN` 3명 중 2명 `DISABLED`일 때 남은 1명의 `ADMIN` 회수(다른 운영자가 요청) → 409.

- [ ] **AU-T5. 감사 기록의 `roles` 칸이 두 뜻이고, 부여·회수 구별이 없고, 멱등 부여도 감사·폐기한다**
  - 어디서: `auth:core/domain/RoleAuditLog.java:59-66`(`STATUS_CHANGED`가 `roles` 칸에 상태 이름), `ROLE_CHANGED` 하나가 부여·회수를 겸함, `auth/db/V4__role_audit_log.sql` CHECK 없음, `ManageAccount.grantRole`(`:50-55`)이 이미 가진 역할에도 감사·`revokeSubject`.
  - 목표: R7.3·R7.7. `status` 칸 추가, `action` enum(`ROLE_ASSIGNED`·`STATUS_CHANGED`·`ROLE_GRANTED`·`ROLE_REVOKED`) + CHECK, 멱등 부여는 무변화. 옛 `ROLE_CHANGED` 행은 이행에서 그대로 두거나(읽기 전용) 값 목록에 포함 — 이행 PR에서 정한다.
  - 영향: 감사 조회가 칸 뜻을 행동별로 해석해야 한다(원칙 ① 취지). 멱등 부여가 대상 사용자의 모든 세션을 끊는다.
  - 재현: `ManageAccountTest` — 이미 `CREATOR`인 계정에 `CREATOR` 부여 → 감사 0행·폐기 호출 0회.

- [ ] **AU-T6. 운영자 명령이 요청자의 현재 권한을 DB에서 확인하지 않고, 상태·역할 변경 뒤에도 access token이 수명까지 유효하다** `[결정 필요 M-AU-6]`
  - 어디서: `auth:core/service/ManageAccount.java:72-78`(요청자는 `subject` 비교에만 씀), `AccountAdminController`(`principal.getName()`만 전달). 결정 84 본문 "정지·역할 변경이 기존 토큰 만료까지 미뤄지지 않게 한다" ↔ 지금 R7.6(옛)·`ManageAccount` 주석 "기존 access token은 수명(기본 15분)까지 유효".
  - 목표: R7.1 — `ManageAccount`가 요청자 계정을 읽어 활성 운영자가 아니면 403(Auth 안에서 끝나는 부분, 결정 없이 진행 가능). R7.6의 access token 즉시 무효는 M-AU-6 결정 뒤.
  - 영향: **간접 돈·보유권** — `ADMIN`을 회수당하거나 `LOCKED`된 운영자가 최대 15분 동안 계정 관리·관리자 환불을 계속할 수 있다. Gateway BFF 세션은 다음 갱신 실패 때 지워지지만(Gateway R4.6) 그 전까지 Redis의 access token이 쓰인다.
  - 재현: 통합 — A가 B의 `ADMIN` 회수 뒤 B의 기존 토큰으로 `…/roles/grant` → 지금 200, 목표(R7.1) 403.

- [ ] **AU-T7. 복구 메일이 DB 트랜잭션 안에서 불리고, 운영 조립이 로그 adapter다** `[결정 필요 M-AU-2]` `[외부 대기]`
  - 어디서: `auth:core/service/RecoverPassword.java:50-57`(`@Transactional request` 안에서 `mail.sendResetToken`), `auth:config/PasswordResetConfig.java:22-25`(언제나 `LoggingPasswordResetMailAdapter`), `PasswordResetMailPort` javadoc의 공급자 이름 예시(`SMTP/SES`, `auth:core/port/PasswordResetMailPort.java:6`)와 adapter javadoc.
  - 목표: R6.7·R9. 토큰 저장 커밋 뒤 발송, 실패 처리는 M-AU-2. 로그 adapter는 운영 조립에서 빼고(실제 adapter가 없으면 시작 실패 또는 명시적 설정으로만), core javadoc의 공급자 이름을 지운다(원칙 ②, 플랫폼 T28 규칙 대상).
  - 영향: 실제 공급자가 느리면 DB 연결을 쥔 채 기다린다(풀 10개). 발송이 예외를 던지면 토큰 저장이 롤백되고 응답이 500이 되어 가입 여부가 드러난다(R6.1 위반). 지금 운영에서는 복구 메일이 실제로 나가지 않는다.
  - 재현: `RecoverPasswordTest` — 메일 포트가 예외를 던져도 응답 경로가 같고 토큰 행 처리가 M-AU-2대로인지. 트랜잭션 밖 호출은 `TransactionSynchronizationManager.isActualTransactionActive()`를 메일 포트 가짜 구현에서 단언.

- [ ] **AU-T8. 운영 설정에 개발용 기본값이 있고, 같은 기본값이 코드와 yml 두 곳에 있다**
  - 어디서: `apps/auth/src/main/resources/application.yml:14-16`(DB `localhost`·`stove1234`), `:43-48`(localhost callback·origin), `auth:config/AuthSecurityConfig.java:159-164`·`:302`·`:311`(`@Value` 기본값 중복), `auth:config/PasswordResetConfig.java:30`(유효시간은 코드 기본값만), `stove.auth.app-origin`(읽는 코드 없음), `auth:core/service/BootstrapAccounts.java:29-31`(이메일·비밀번호 한쪽만 있으면 조용히 건너뜀).
  - 목표: design "설정" 표. 운영 필수 값은 기본값 없이 시작 때 검증(`docs/security.md:74`의 "필수 입력의 빈 값·누락은 시작 전에 거절" 규칙과 같게), 개발 기본값은 로컬 Compose 환경 변수로, 기본값은 yml 한 곳. 7일 상한·15분은 한 상수 묶음으로.
  - 영향: 운영 배포에서 `AUTH_ISSUER`를 빠뜨리면 `iss`=`http://localhost:8091`로 시작해 모든 자원 서버가 토큰을 거절한다(조용한 오설정). 부트스트랩 비밀번호를 빠뜨리면 운영자 없이 시작한다.
  - 재현: 컨텍스트 테스트 — `AUTH_ISSUER` 없이 시작 실패, 부트스트랩 이메일만 있을 때 시작 실패.

- [ ] **AU-T9. 공개 클라이언트 `studio-web`이 Refresh Token을 받는다**
  - 어디서: `auth:config/AuthSecurityConfig.java:171`(`REFRESH_TOKEN` 승인)·`:183`(7일). Gateway design "기존 `studio-web`·`swagger-ui` 공개 클라이언트는 … Refresh Token을 받지 않는다"(`.kiro/specs/gateway/design.md:58`).
  - 목표: R2.7. `studio-web`에서 `REFRESH_TOKEN` 승인 제거. 운영 등록 여부는 M-AU-7.
  - 영향: 브라우저 저장소의 7일 Refresh Token이 탈취되면 BFF 보호(브라우저에는 세션 쿠키만)를 우회한다.
  - 재현: 통합 — `studio-web` 인가 코드 교환 응답에 `refresh_token`이 없는지.

- [ ] **AU-T10. Auth에 운영 재발행 경로가 없다** (플랫폼 [T7](../platform/tasks.md), [결정 161](../../../docs/decisions.md#republish-all-current))
  - 어디서: `application.yml:37-40`(`stove.messaging.ops.enabled: false`, `republish-owner` 없음), `auth/db/` `outbox_republish` 이행 없음, `common/messaging/.../ops/OutboxRepublishTargets.java:27-37`에 Auth 목록 없음.
  - 목표: R8.4·R5.4. 플랫폼 T7 PR에서 Auth 몫(이행·설정·허용 목록 `UserRegistered`·ADMIN 경로)을 함께. 발행 순번 헤더·회수 정렬은 플랫폼 계약대로(1회차 지적 B1, 판정 전).
  - 영향: `UserRegistered` 소비자가 생긴 뒤 원본이 사라지면 다시 낼 수 없다(지금 소비자 없음).
  - 재현: Auth에서 `SENT` 행 재발행 API → 지금 404(운영 API 꺼짐), 목표 200.

- [ ] **AU-T11. 복구 토큰 상태가 BOOLEAN이고, 동시 사용·계정 상태를 막지 않는다** `[결정 필요 M-AU-3]` `[결정 필요 M-AU-5]`
  - 어디서: `auth/db/V6__password_reset.sql:6`(`consumed` BOOLEAN), `auth:core/service/RecoverPassword.java:68-76`(잠금 없는 조회 → `usable` → `consume`), `:72-75`(계정 상태 확인 없음), 새 발급·성공·비밀번호 변경이 다른 `ISSUED` 토큰을 건드리지 않음.
  - 목표: R6.4·R6.5·R6.8·AU-I13. `status` enum + `chk_password_reset_status`, 잠금 조회 또는 조건부 `UPDATE` 갱신 행 수로 일회성 판정, 계정 상태 판정은 M-AU-5, 남은 토큰은 M-AU-3.
  - 영향: **계정 탈취 경로** — 같은 토큰으로 동시에 두 비밀번호가 적용될 수 있다(마지막 커밋이 이김). 이메일을 잠깐 읽은 공격자가 복구 성공 뒤에도 남은 이전 토큰으로 30분 안에 다시 복구할 수 있다. `LOCKED` 계정의 비밀번호가 바뀐다.
  - 재현: 통합 — 같은 토큰 두 요청을 동시에 → 목표 하나만 200. 단위 — 토큰 T1·T2 발급, T2로 복구 뒤 T1 → M-AU-3 결정대로.

- [ ] **AU-T12. 토큰 계열·회전 이력의 상태가 BOOLEAN이다**
  - 어디서: `auth/db/V5__refresh_token_rotation.sql:6`(`revoked`), `:12`(`consumed`), `:4`(`subject` VARCHAR(200), 계정은 64), `adapter/oauth/RotatingAuthorizationService.java:43`·`:78`·`:82`.
  - 목표: design `refresh_token_family.status`(`ACTIVE`·`REVOKED`)·`refresh_token_history.status`(`CURRENT`·`CONSUMED`) + CHECK, `subject` 64자.
  - 영향: 동작은 같다. 상태 모델과 DB 표현을 맞춘다(점검표 2).
  - 재현: 이행 테스트 — 기존 `revoked=TRUE` 행이 `REVOKED`로, `consumed=TRUE`가 `CONSUMED`로 옮겨지는지, 그 뒤 `TokenRotationIntegrationTest` 전체.

- [ ] **AU-T13. 토큰 발급 자격 판정이 adapter·config 세 곳에 흩어져 있다**
  - 어디서: `adapter/oauth/RotatingRefreshProvider.java:38-41`(계정 `ACTIVE`·상한·소비 판정), `config/AuthSecurityConfig.java:316-327`(`jwtTokenCustomizer`가 `ACTIVE` 확인·역할 클레임), `adapter/security/AccountUserDetailsService.java:27`.
  - 목표: core 서비스 하나(예: `core.service.TokenEligibility`)가 "이 `subject`가 지금 토큰을 받을 수 있나, 받는다면 어떤 역할로"를 판정하고 세 곳이 그것을 부른다(원칙 ②, AU-I9).
  - 영향: 규칙을 바꿀 때 한 곳을 빠뜨리면 경로마다 다르게 동작한다(예: M-AU-5 결정 반영).
  - 재현: 단위 — 판정 서비스 표(`ACTIVE`·`LOCKED`·`DISABLED`·없음)와, 인가 코드 교환 경로에서 `LOCKED` 계정이 `invalid_grant`인 통합 테스트(지금 없음).

- [ ] **AU-T14. `UserRegistered.occurredAt`이 계정 생성 시각이 아니다**
  - 어디서: `common/kafka/.../payload/UserRegisteredEvent.java:24`(`Instant.now()`), `RegisterAccount.java:48-49`.
  - 목표: R8.2 — 본문 `occurredAt` = `user_account.created_at`(같은 트랜잭션 값). 헤더 `occurredAt`=본문은 플랫폼 T25.
  - 영향: 작다(같은 트랜잭션 안 몇 ms). 사실 시각의 정본을 하나로 둔다(원칙 ③).
  - 재현: `RegisterAccountTest#signupCreatesCreatorAndEvent`에 `occurredAt == createdAt` 단언.

- [ ] **AU-T15. 불변식 확인 테스트가 없는 곳**
  - 어디서: AU-I2(`subject` 불변), AU-I16(계정 저장이 롤백되면 Outbox도 없음 — `role_audit_log` 저장 실패 시).
  - 목표: design "모듈 안 불변식" 표의 "없음" 칸을 채운다.
  - 영향: 회귀를 CI가 모른다.
  - 재현: AU-I2 — `UserAccount`를 저장·상태 변경·역할 변경·비밀번호 변경한 뒤 `subject`가 처음 값과 같은지(리포지토리 통합 테스트), AU-I16 — `RegisterAccountTest`에서 `role_audit_log` 저장이 예외를 던지면 `outbox_event` 0건·계정 0건.

- [ ] **AU-T16. 원본 토큰이 저장·로그되지 않음을 단언하는 테스트가 불명확하다**
  - 어디서: `RecoverPasswordTest#requestForKnownEmailIssuesTokenAndSendsMail`, `PasswordRecoveryIntegrationTest`(DB 해시 픽스처 사용).
  - 목표: AU-I14 — 저장된 `token_hash` ≠ 메일로 나간 원본, 원본의 SHA-256 = `token_hash`, 로그 캡처에 원본 없음.
  - 영향: 회귀 시 복구 토큰이 DB·로그로 샌다.
  - 재현: 위 단언 추가.

- [ ] **AU-T17. 복구 요청 응답 시간이 가입 여부를 드러낸다** (R6.1)
  - 어디서: `RecoverPassword.request`(`:51-57`) — 가입된 이메일만 난수·해시·INSERT·메일 호출을 한다.
  - 목표: R6.1. M-AU-2에서 발송을 응답 경로 밖으로 빼면 차이가 INSERT 하나로 줄어든다. 남는 차이를 없앨지(미가입에도 같은 작업)는 M-AU-2와 함께 정한다.
  - 영향: 실제 메일 공급자가 붙으면 수백 ms 차이로 가입 여부가 드러난다.
  - 재현: 실험 항목(응답 시간 분포 비교) — 단위 테스트로는 재지 않는다.

- [ ] **AU-T18. 기준 문서가 지금 계약과 다르다**
  - 어디서: `docs/domain/models/identity.md:17`("`LOCKED`, `DISABLED`도 정의되어 있으나 모델에 전환 메서드는 없음" — `UserAccount.changeStatus`가 있다), `docs/security.md:53`(감사가 가입·부트스트랩만이라 적음 — 운영자 변경도 남긴다).
  - 목표: 기준 문서를 이 스펙 링크로 고친다(문서만).
  - 영향: 읽는 사람이 잘못된 상태 모델을 믿는다.
  - 재현: 해당 없음(문서).

## 진행 중 / 남음

- [ ] [결정 필요 M-AU-2] [외부 대기] 실제 메일 발송 연동(`PasswordResetMailPort`의 실제 adapter, 공급자 계정·발신 도메인은 사용자가 준비) — 발송 보장 방식은 M-AU-2, 코드 차이는 AU-T7·AU-T17.
- [ ] [결정 필요 M-AU-1] 모든 계정의 `MEMBER` 보유 여부 → AU-T3.
- [ ] [결정 필요 M-AU-3] 쓰지 않은 복구 토큰의 무효화 시점 → AU-T11.
- [ ] [결정 필요 M-AU-4] 복구 요청 발급 수 제한 → R6.9.
- [ ] [결정 필요 M-AU-5] `LOCKED`·`DISABLED` 계정의 복구와 `DISABLED` 계정의 역할 변경 → AU-T11, 상태 표.
- [ ] [결정 필요 M-AU-6] 상태·역할 변경 즉시 access token 무효(결정 84 본문과 지금 동작의 차이, 소급 후보 notes E-1) → AU-T6. Gateway·모든 자원 서버에 닿으므로 플랫폼 스펙에 행이 필요할 수 있다(notes F-1).
- [ ] [결정 필요 M-AU-7] 공개 클라이언트 `studio-web`·`swagger-ui`의 운영 등록 → AU-T8·AU-T9.
- [ ] 결정이 필요 없는 항목(바로 진행 가능): AU-T1, AU-T2, AU-T4, AU-T5, AU-T6의 R7.1 부분, AU-T8(M-AU-7 제외), AU-T9, AU-T10(플랫폼 T7과 함께), AU-T12, AU-T13, AU-T14, AU-T15, AU-T16, AU-T18.

## 범위 밖(후속)

- 소셜 로그인 다수.
- 고급 MFA·외부 인증 플랫폼.
- 다른 기기 전체 세션 종료 정책.
- JWT 서명 키 교체·여러 키 게시, 개인 키 암호화 저장(notes F-5).
- 만료된 복구 토큰·폐기된 계열 행 정리 예약 작업(notes F-6).
- 로그인 실패 횟수 자동 잠금, 가입 409의 가입 여부 노출, 비밀번호 변경·복구 감사, 계정 탈퇴·개인정보 삭제.
