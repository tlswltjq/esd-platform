# 설계: Auth

> 목표 계약이다. 코드가 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"(`AU-Tn`)에 있다. 모듈을 넘는 계약은 [플랫폼 스펙](../platform/design.md)이 정본이다.
> 경로 줄임: `auth:` = `apps/auth/src/main/java/com/stove/auth/`, `auth/db/Vn` = `apps/auth/src/main/resources/db/migration/Vn__*.sql`.

## 아키텍처 위치

- 포트 8091, 저장소 MySQL 스키마 `stove_auth`(다른 스키마를 읽거나 쓰지 않는다, [P1.1-2](../platform/requirements.md#p11-경계-코드-수준)).
- 패키지: `core/{domain, port, service}` + `api/controller` + `adapter/{oauth, security, mail}` + `config`. `api/listener`는 없다(소비 이벤트 없음).
- 의존 방향([플랫폼 design "모듈과 외부 공급자"·"동기 의존"](../platform/design.md#모듈-경계와-의존-방향))
  - 들어옴: Gateway → Auth `/oauth2/token`·`/oauth2/jwks`·`/userinfo`(BFF 로그인·토큰 릴레이), Gateway 라우트 `/api/v1/auth/**`·`/oauth2/**`·`/login`·`/logout`·`/.well-known/**`·`/api/v1/admin/accounts/**`. 모든 자원 서버는 `/oauth2/jwks`의 공개 키로 토큰을 검증한다.
  - 나감: 다른 업무 모듈 동기 호출 없음. 외부 공급자는 메일 하나(`core.port.PasswordResetMailPort` → `adapter/mail`).
  - 비동기: `UserRegistered` 발행만, 소비 없음([플랫폼 design "비동기 의존"](../platform/design.md#비동기-의존-요청--사실-응답-쌍-p13-2) 단방향 행).
- Spring Authorization Server SPI 구현(`OAuth2AuthorizationService`·`AuthenticationProvider`·`UserDetailsService`)은 프레임워크 연동이라 `adapter`에 둔다. **토큰을 받을 자격(계정 `ACTIVE`, 현재 역할)의 판정은 업무 규칙이라 core 서비스가 갖고, adapter·config는 그 서비스를 부른다**(원칙 ②). 지금은 판정이 `adapter/oauth/RotatingRefreshProvider.java:38-39`, `config/AuthSecurityConfig.java:316-327`, `adapter/security/AccountUserDetailsService.java:27`에 흩어져 있다(AU-T13).
- 조립: 설정 바인딩·`RegisteredClient` 등록·서명 키·`RecoverPassword` 조립은 `config`(AGENTS.md 3절).

## 데이터 모델

| 표 | 칸 | 제약(목표) | 지금(차이) |
|---|---|---|---|
| `user_account` | `id` BIGINT PK, `subject` VARCHAR(64), `email` VARCHAR(200, 소문자 저장), `password_hash` VARCHAR(200, `{bcrypt}` 등 위임 인코더 접두), `status` VARCHAR(20), `created_at`·`updated_at` | `uk_user_account_subject`, `uk_user_account_email`, **`chk_user_account_status` `status IN ('ACTIVE','LOCKED','DISABLED')`** | CHECK 없음(`auth/db/V1:7`, AU-T2) |
| `user_account_role` | `user_account_id` FK, `role` VARCHAR(20) | PK `(user_account_id, role)`, `fk_user_role_account`, **`chk_user_account_role` `role IN ('MEMBER','CREATOR','REVIEWER','ADMIN')`** | CHECK 없음(`auth/db/V1:18`, AU-T2) |
| `role_audit_log` | `id`, `subject`, `actor` VARCHAR(80), `action` VARCHAR(40), `roles` VARCHAR(200, 변경 뒤 역할 집합 `,` 이름순), **`status` VARCHAR(20, 변경 뒤 상태)**, `occurred_at` | **`chk_role_audit_action` `action IN ('ROLE_ASSIGNED','STATUS_CHANGED','ROLE_GRANTED','ROLE_REVOKED')`**, `status` CHECK(계정 상태 3값), 행은 수정·삭제하지 않음 | `roles` 칸이 `STATUS_CHANGED`일 때 상태 이름을 담는다(한 칸 두 뜻, `auth:core/domain/RoleAuditLog.java:65`), 부여·회수가 같은 `ROLE_CHANGED`, CHECK 없음(AU-T5) |
| `password_reset_token` | `token_hash` CHAR(64) PK(원본 SHA-256), `subject`, `expires_at`(Auth 시계), **`status` VARCHAR(20)** | **`chk_password_reset_status` `status IN ('ISSUED','CONSUMED','REVOKED')`**(`REVOKED`는 M-AU-3 선택에 따라), `idx_password_reset_subject` | 상태가 `consumed` BOOLEAN(`auth/db/V6:6`, AU-T11) |
| `refresh_token_family` | `authorization_id` PK(= `oauth2_authorization.id`), `client_id`, `subject` VARCHAR(64), `expires_at`(절대 상한), **`status` VARCHAR(20)** | **`chk_refresh_family_status` `status IN ('ACTIVE','REVOKED')`**, `idx_refresh_subject` | 상태가 `revoked` BOOLEAN, `subject` VARCHAR(200)(`auth/db/V5:4-6`, AU-T12) |
| `refresh_token_history` | `token_hash` CHAR(64) PK, `authorization_id` FK, **`status` VARCHAR(20)**, `created_at` | **`chk_refresh_history_status` `status IN ('CURRENT','CONSUMED')`**, 계열마다 `CURRENT` 최대 1행(코드 가드 `RotatingAuthorizationService.save`, 잠금 아래) | 상태가 `consumed` BOOLEAN(`auth/db/V5:12`, AU-T12) |
| `oauth2_registered_client`·`oauth2_authorization`·`oauth2_authorization_consent` | Spring Authorization Server JDBC 표준 스키마 | 프레임워크 소유. Auth 업무 규칙은 이 표의 칸에 두지 않는다 | `auth/db/V2` |
| `auth_signing_key` | `key_name` PK(`active`), `private_jwk`, `created_at` | 행 1개 | `auth/db/V2:71-77`. 개인 키 평문 저장은 notes F-5 |
| `outbox_event`·`processed_event` | 공통 Outbox·Inbox([P2.2](../platform/requirements.md#p22-발행--transactional-outbox)) | 공통 스키마 | `auth/db/V3`. `processed_event`는 소비가 없어 쓰지 않는다 |
| `outbox_republish` (목표) | 공통 재발행 기록([P2.2-7](../platform/requirements.md#p22-발행--transactional-outbox)) | 공통 스키마 | 없음(AU-T10) |
| 복구 메일 발송 기록 (`[결정 필요 M-AU-2]`) | M-AU-2에서 B를 고르면 `password_reset_mail`(토큰 해시 FK, `status` `PENDING`·`SENT`·`FAILED`, 시도 수, 다음 시도 시각) | 고른 뒤 정한다 | 없음 |

금액 칸 없음. 다른 모듈 표와 FK·조인 없음.

## 상태 모델

### 계정 상태 `AccountStatus` (`user_account.status`)

| 값 | 뜻 |
|---|---|
| `ACTIVE` | 로그인·토큰 발급·갱신·비밀번호 변경·복구 가능 |
| `LOCKED` | 운영자가 정지함. 로그인·토큰 발급·갱신 불가. 운영자가 `ACTIVE`로 되돌릴 수 있다 |
| `DISABLED` | 운영자가 종료함. 끝 상태([결정 84](../../../docs/decisions.md)) |

보조 칸으로 하위 상태를 만들지 않는다. `enabled()`는 `status == ACTIVE`의 이름일 뿐이다.

### 계정 상태 × 트리거

"없음" = 그 이메일·`subject`의 계정이 없음. 오류 코드는 `common/web` `ErrorCode`(HTTP 상태).

| 트리거 \ 상태 | 없음 | `ACTIVE` | `LOCKED` | `DISABLED` |
|---|---|---|---|---|
| HTTP `POST /api/v1/auth/signup`·`/signup/member` | 허용 → `ACTIVE`(역할 R1.1·R1.2, `UserRegistered` Outbox, 감사) | 거절 409 `CONFLICT`(같은 이메일) | 거절 409 `CONFLICT` | 거절 409 `CONFLICT` |
| 시작 시 부트스트랩(R10) | 허용 → `ACTIVE`(감사, 이벤트 없음) | 무변화 | 무변화 | 무변화 |
| 로그인 폼 `POST /login` | 거절(인증 실패, 계정 유무를 구분하지 않는 같은 응답) | 허용, 무변화(비밀번호가 맞을 때) | 거절(인증 실패) | 거절(인증 실패) |
| 인가 코드 교환 → access token 발행(`/oauth2/token` `authorization_code`) | 거절 `invalid_grant` | 허용, 무변화(현재 역할로 발행) | 거절 `invalid_grant` | 거절 `invalid_grant` |
| 토큰 갱신(`/oauth2/token` `refresh_token`) | 거절 `invalid_grant` + 계열 폐기 | 허용, 무변화(R3, 아래 계열 표) | 거절 `invalid_grant` + 계열 폐기 | 거절 `invalid_grant` + 계열 폐기 |
| HTTP `POST /api/v1/auth/password`(비밀번호 변경) | 거절 401 `UNAUTHORIZED` | 허용, 무변화(해시 교체, 계정 토큰 폐기, 복구 토큰은 M-AU-3) / 현재 비밀번호 불일치는 거절 403 `FORBIDDEN` | 거절 401 `UNAUTHORIZED` | 거절 401 `UNAUTHORIZED` |
| HTTP `POST /api/v1/auth/password/reset-request` | 무변화(같은 응답, 아무것도 저장하지 않음) | 허용, 무변화(복구 토큰 `ISSUED`, 메일 발송 R9) | `[결정 필요 M-AU-5]`(응답은 어느 쪽이든 같음) | 무변화(발급하지 않음, 같은 응답) — 끝 상태 |
| HTTP `POST /api/v1/auth/password/reset`(유효한 토큰) | 거절 400 `INVALID_REQUEST`(같은 문구) | 허용, 무변화(해시 교체, 토큰 `CONSUMED`, 계정 토큰 폐기) | `[결정 필요 M-AU-5]` | 거절 400 `INVALID_REQUEST`(같은 문구) |
| 운영자 `POST /api/v1/admin/accounts/{subject}/status` `ACTIVE` | 거절 404 `NOT_FOUND` | 거절 409 `CONFLICT`(같은 상태) | 허용 → `ACTIVE`(감사, 계정 토큰 폐기 — 정지 전 계열은 살아나지 않음) | 거절 409 `CONFLICT`(끝 상태) |
| 운영자 `…/status` `LOCKED` | 거절 404 `NOT_FOUND` | 허용 → `LOCKED`(감사, 계정 토큰 폐기). 활성 운영자가 0명이 되면 거절 409 `CONFLICT` | 거절 409 `CONFLICT`(같은 상태) | 거절 409 `CONFLICT`(끝 상태) |
| 운영자 `…/status` `DISABLED` | 거절 404 `NOT_FOUND` | 허용 → `DISABLED`(감사, 계정 토큰 폐기). 활성 운영자가 0명이 되면 거절 409 `CONFLICT` | 허용 → `DISABLED`(감사, 계정 토큰 폐기) | 거절 409 `CONFLICT`(같은 상태) |
| 운영자 `…/roles/grant`·`…/roles/revoke` | 거절 404 `NOT_FOUND` | 아래 "역할 × 트리거" 표, 상태 무변화 | 아래 "역할 × 트리거" 표, 상태 무변화 | `[결정 필요 M-AU-5]`(끝 상태 계정의 역할 변경) |
| 운영자 명령의 대상이 요청자 자신 | 거절 403 `FORBIDDEN`(자기 검사가 조회보다 먼저) | 거절 403 `FORBIDDEN` | 거절 403 `FORBIDDEN` | 거절 403 `FORBIDDEN` |
| 운영자 명령의 요청자가 DB에서 활성 운영자가 아님(R7.1) | 거절 403 `FORBIDDEN` | 거절 403 `FORBIDDEN` | 거절 403 `FORBIDDEN` | 거절 403 `FORBIDDEN` |

소비 이벤트 트리거 없음. 예약 작업 트리거 없음(계정 상태를 시간으로 바꾸지 않는다).

### 역할 × 트리거 (`user_account_role`, 계정 상태 `ACTIVE`·`LOCKED`일 때)

| 트리거 \ (역할, 보유) | `MEMBER` 보유 | `MEMBER` 미보유(M-AU-1 전 부트스트랩 계정) | `CREATOR`·`REVIEWER` 보유 | `CREATOR`·`REVIEWER` 미보유 | `ADMIN` 보유 | `ADMIN` 미보유 |
|---|---|---|---|---|---|---|
| 운영자 부여 | 무변화(멱등, 감사·폐기 없음) | 허용 → 보유(감사 `ROLE_GRANTED`, 계정 토큰 폐기) | 무변화(멱등) | 허용 → 보유(감사, 폐기) | 무변화(멱등) | 허용 → 보유(감사, 폐기) |
| 운영자 회수 | 거절 409 `CONFLICT`(기본 역할) | 거절 409 `CONFLICT`(가지지 않은 역할) | 허용 → 미보유(감사 `ROLE_REVOKED`, 폐기) | 거절 409 `CONFLICT`(가지지 않은 역할) | 허용 → 미보유(감사, 폐기). 대상이 활성 운영자이고 회수 뒤 활성 운영자가 0명이면 거절 409 `CONFLICT` | 거절 409 `CONFLICT` |
| 부트스트랩(이미 있는 계정) | 무변화(R10.2) | 무변화 | 무변화 | 무변화 | 무변화 | 무변화 |

공개 가입·부트스트랩의 계정 생성 때 역할 집합은 R1.1·R1.2·R10.1이 정하고, 이미 있는 계정에는 가입이 닿지 않는다(계정 표 409). `DISABLED` 계정의 역할 변경은 계정 표의 M-AU-5 칸이다.

### 복구 토큰 상태 (`password_reset_token.status`)

| 값 | 뜻 |
|---|---|
| `ISSUED` | 발급됨, 아직 쓰지 않음. 만료 전이면 복구에 쓸 수 있다 |
| `CONSUMED` | 복구 한 번에 쓰임. 끝 상태 |
| `REVOKED` | 쓰지 않은 채 무효가 됨(새 발급·다른 경로의 비밀번호 변경 등). 끝 상태. 값의 존재와 진입 트리거는 `[결정 필요 M-AU-3]` |

만료는 저장 상태가 아니라 직교 조건이다: `expires_at`(발급 때 Auth 시계 + `lifetime`)과 Auth 시계 `now`를 비교한다(한 시계 안의 비교, 원칙 ⑥ 대상 아님). 만료된 `ISSUED` 행은 동작상 쓸 수 없는 행이고 다른 상태로 옮기지 않는다.

| 트리거 \ 상태 | `ISSUED`·만료 전 | `ISSUED`·만료 뒤 | `CONSUMED` | `REVOKED` |
|---|---|---|---|---|
| HTTP `POST …/password/reset`(이 토큰, 계정 `ACTIVE`) | 허용 → `CONSUMED`(행 잠금 아래, 해시 교체·계정 토큰 폐기) | 거절 400 `INVALID_REQUEST` | 거절 400 `INVALID_REQUEST`(같은 문구) | 거절 400 `INVALID_REQUEST`(같은 문구) |
| HTTP `POST …/password/reset`(이 토큰, 계정 `LOCKED`·`DISABLED`) | 계정 표 따름: `LOCKED` M-AU-5, `DISABLED` 거절 400 | 거절 400 | 거절 400 | 거절 400 |
| 같은 토큰의 동시 복구 두 번째 요청(첫 요청이 잠금을 쥐고 커밋) | 거절 400(잠금 뒤 다시 읽으면 `CONSUMED`) | 거절 400 | 거절 400 | 거절 400 |
| 같은 계정의 새 복구 요청(새 토큰 발급) | `[결정 필요 M-AU-3]`(지금: 무변화, 둘 다 유효) | 무변화 | 무변화 | 무변화 |
| 같은 계정의 다른 토큰으로 복구 성공 | `[결정 필요 M-AU-3]`(지금: 무변화, 남은 토큰으로 다시 복구 가능) | 무변화 | 무변화 | 무변화 |
| HTTP 비밀번호 변경(R4) 성공 | `[결정 필요 M-AU-3]`(지금: 무변화) | 무변화 | 무변화 | 무변화 |
| 운영자 상태 변경(`LOCKED`·`DISABLED`) | `[결정 필요 M-AU-3]`·M-AU-5와 함께(지금: 무변화) | 무변화 | 무변화 | 무변화 |

예약 작업 없음(만료 행 정리는 범위 밖, notes F-6).

### 토큰 계열 상태 (`refresh_token_family.status`)

| 값 | 뜻 |
|---|---|
| `ACTIVE` | 갱신에 쓸 수 있는 계열. 상한(`expires_at`) 전이어야 실제로 갱신된다 |
| `REVOKED` | 폐기됨. 끝 상태(R3.7) |

상한은 직교 조건(Auth 시계 `now < expires_at`).

| 트리거 \ 상태 | `ACTIVE`·상한 전 | `ACTIVE`·상한 뒤 | `REVOKED` |
|---|---|---|---|
| 갱신: 이 계열의 `CURRENT` 토큰, 같은 클라이언트, 계정 `ACTIVE` | 허용, 무변화(이전 토큰 `CONSUMED`, 새 토큰 `CURRENT`, 만료 = 계열 상한, 새 access token은 현재 역할) | 거절 `invalid_grant` → `REVOKED` | 거절 `invalid_grant`(무변화) |
| 갱신: 이 계열의 `CONSUMED` 토큰(재사용) | 거절 `invalid_grant` → `REVOKED`(폐기를 커밋한 뒤 오류) | 거절 → `REVOKED` | 거절(무변화) |
| 갱신: 다른 클라이언트가 이 계열 토큰을 냄 | 거절 `invalid_grant`(무변화) | 거절(무변화) | 거절(무변화) |
| 갱신: 계정이 `ACTIVE`가 아님(없음 포함) | 거절 `invalid_grant` → `REVOKED` | 거절 → `REVOKED` | 거절(무변화) |
| 갱신: 이력에 없는 토큰 | 해당 계열 없음 → 거절 `invalid_grant`(어떤 계열도 바꾸지 않음) | 같음 | 같음 |
| 로그아웃·토큰 폐기(`/oauth2/revoke`, OIDC 로그아웃 → `OAuth2AuthorizationService.remove`/무효화된 Refresh Token 저장) | 허용 → `REVOKED` | 허용 → `REVOKED` | 무변화(멱등) |
| 계정 토큰 폐기(R4.3·R6.5·R7.6, `revokeSubject`) | 허용 → `REVOKED`(같은 트랜잭션) | 허용 → `REVOKED` | 무변화(멱등) |
| 새 로그인(같은 계정의 새 authorization) | 무변화(새 계열을 따로 만든다) | 무변화 | 무변화 |

### 회전 이력 상태 (`refresh_token_history.status`)

| 트리거 \ 상태 | `CURRENT` | `CONSUMED` |
|---|---|---|
| 같은 계열의 회전 저장(`RotatingAuthorizationService.save`, 계열 잠금 아래) | 허용 → `CONSUMED`(새 해시 행이 `CURRENT`) | 무변화 |
| 이 토큰으로 갱신 요청 | 계열 표 1행 | 계열 표 2행(재사용) |
| 계열 폐기 | 무변화(계열 `REVOKED`가 판정) | 무변화 |

## API 계약

공통 응답은 `common/web` `ApiResponse`, 오류는 `ErrorCode` 이름·HTTP 상태. 쿠키 인증 변경 요청은 CSRF 토큰이 필요하고 Bearer 요청은 면제(R4.4).

| 메서드 | 경로 | 역할(Auth 자기 판정) | 요청 | 응답 | 오류 | 멱등 |
|---|---|---|---|---|---|---|
| POST | `/api/v1/auth/signup` | 공개(CSRF 면제) | `email`(형식), `password`(12~100) | `subject`·`memberId`·`email` | 400 `INVALID_REQUEST`, 409 `CONFLICT`(이메일 중복) | 이메일 유일 제약 — 같은 요청 재시도는 409 |
| POST | `/api/v1/auth/signup/member` | 공개(CSRF 면제) | 같음 | 같음 | 같음 | 같음 |
| GET | `/api/v1/auth/session` | 인증 | — | `subject` | 401 | 조회 |
| POST | `/api/v1/auth/password` | 인증(`ACTIVE`) | `currentPassword`, `newPassword`(12~100) | `changed: true` | 400, 401 `UNAUTHORIZED`, 403 `FORBIDDEN`(현재 비밀번호 불일치) | 아님(성공마다 계열 폐기) |
| POST | `/api/v1/auth/password/reset-request` | 공개(CSRF 면제) | `email`(형식) | 언제나 `accepted: true` | 400(형식만) | 발급 수 제한은 M-AU-4 |
| POST | `/api/v1/auth/password/reset` | 공개(CSRF 면제) | `token`, `newPassword`(12~100) | `reset: true` | 400 `INVALID_REQUEST`(사유 구분 없음) | 토큰 일회성 — 두 번째는 400 |
| POST | `/api/v1/admin/accounts/{subject}/status` | `ADMIN` + DB 활성 운영자 | `status`(`ACTIVE`·`LOCKED`·`DISABLED`) | `changed: true` | 403(자기 자신·활성 운영자 아님), 404, 409(전환 규칙·활성 운영자 0명) | 아님 — 같은 상태는 409 |
| POST | `/api/v1/admin/accounts/{subject}/roles/grant` | 같음 | `role` | `granted: true` | 403, 404, 409(M-AU-5) | 이미 보유면 무변화 200 |
| POST | `/api/v1/admin/accounts/{subject}/roles/revoke` | 같음 | `role` | `revoked: true` | 403, 404, 409(`MEMBER`·미보유·활성 운영자 0명) | 아님 |
| GET·POST | `/login`, `/logout` | 공개 | 폼 | 리다이렉트 | — | — |
| — | `/oauth2/authorize`·`/oauth2/token`·`/oauth2/revoke`·`/oauth2/jwks`·`/userinfo`·`/connect/logout`·`/.well-known/openid-configuration` | Spring Authorization Server | OAuth2/OIDC 표준 | 표준 | `invalid_grant`·`invalid_client` 등 | 표준 |
| GET·POST | `/api/v1/ops/dlt`·`/api/v1/ops/outbox/dead`·`…/requeue`·재발행 | `ADMIN`(나머지 `/api/v1/ops/**` 거부) | 공통 운영 API | 공통 | 공통 | 공통 |

등록 클라이언트(목표)

| `client_id` | 인증 | 승인 | 범위 | access token | Refresh Token |
|---|---|---|---|---|---|
| `gateway-bff` | `client_secret_basic`(`stove.auth.bff-client-secret`) | `authorization_code`, `refresh_token` | `openid`·`profile`·`studio`·`commerce` | `stove.auth.bff-access-token-seconds`(기본 900) | 7일 절대 상한, 재사용 안 함 |
| `studio-web` | 없음(공개, PKCE) | `authorization_code` | 같음 | 15분 | **받지 않음**(AU-T9). 운영 등록 여부 M-AU-7 |
| `swagger-ui` | 없음(공개, PKCE) | `authorization_code` | 같음 | 15분 | 받지 않음. 운영 등록 여부 M-AU-7 |

## 이벤트

### 발행 — `UserRegistered`

- 계약 정본: [플랫폼 design 이벤트 목록 #1](../platform/design.md#eventtype-상수-42개--현행목표-분리안)(사실 "계정이 가입을 마쳤다", 토픽 `stove.auth.v1`, 키 `subject`, 소비자 없음, 상태 현행). 봉투·Outbox·재시도·`DEAD`는 [P2.1](../platform/requirements.md#p21-봉투)·[P2.2](../platform/requirements.md#p22-발행--transactional-outbox).
- 이 모듈이 지키는 방법: `RegisterAccount.register`(`auth:core/service/RegisterAccount.java:35-52`) 한 트랜잭션 안에서 `saveAndFlush`(이메일 유일 제약) → `OutboxRecorder.record("UserAccount", subject, UserRegisteredEvent)` → `role_audit_log` 저장. 계정이 롤백되면 이벤트도 없다.
- 본문: `eventId`·`occurredAt`·`subject`·`email`(`common/kafka/.../payload/UserRegisteredEvent.java`). 목표 `occurredAt` = `user_account.created_at`(지금은 `UserRegisteredEvent.of`의 `Instant.now()`, AU-T14). `email` 칸은 notes F-2.
- 재발행: [P2.2-7](../platform/requirements.md#p22-발행--transactional-outbox) — Auth는 `stove.messaging.ops.republish-owner=auth`, `outbox_republish` 이행, ADMIN 경로를 갖고, 허용 목록은 플랫폼 이벤트 표의 Auth 현행 행(`UserRegistered`)에서 만든다(AU-T10). 같은 키 안 발행 순번 헤더와 회수 정렬은 플랫폼 계약(1회차 지적 B1, 판정 전).

### 소비

없음. `processed_event` 표는 공통 이행에 있으나 쓰지 않는다.

## 모듈 안 불변식

모듈을 넘는 불변식(I1~I20)은 Auth에 닿지 않는다. 계정 정본 하나는 [플랫폼 design "정본 소유"](../platform/design.md#정본-소유) 행이 정본이다.

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| AU-I1 | 이메일(소문자)마다 계정 하나 | `uk_user_account_email`(`auth/db/V1:12`), `UserAccount` 생성자 소문자화(`auth:core/domain/UserAccount.java:63`), `RegisterAccount.register`의 `DataIntegrityViolationException` → 409 | `RegisterAccountTest#knownDuplicateIsRejected`, `#concurrentDuplicateIsRejected` |
| AU-I2 | `subject`는 유일하고 바뀌지 않는다 | `uk_user_account_subject`, `UserAccount`에 `subject` 변경 메서드 없음 | 없음 → tasks AU-T15 |
| AU-I3 | 계정 상태는 3값이고 전환은 상태 표를 따른다(`DISABLED` 끝 상태) | `UserAccount.changeStatus`(`:99-110`), 목표 `chk_user_account_status` | `UserAccountTest#activeAndLockedAreReversible`·`#canDisableFromActiveOrLocked`·`#disabledCannotBeReverted`·`#sameStatusIsRejected`; DB CHECK 테스트 없음 → AU-T2 |
| AU-I4 | 모든 계정은 `MEMBER`를 가진다(M-AU-1 결정 뒤 확정) | `UserAccount.revokeRole`(`:121-129`); 생성 경로 가드 없음(부트스트랩 위반) | `UserAccountTest#memberRoleCannotBeRevoked`; 부트스트랩 경로 없음 → AU-T3 |
| AU-I5 | 활성 운영자(`ACTIVE`·`ADMIN`)는 1명 이상 | 목표: `ManageAccount`가 활성 운영자 집합을 잠근 뒤(`SELECT … FOR UPDATE`) 상태 변경·`ADMIN` 회수 후 수를 센다. 지금은 `countByRole(ADMIN)`(상태 무시, 잠금 없음, 회수에만, `auth:core/service/ManageAccount.java:60`) | `ManageAccountTest#cannotRevokeLastAdmin`, `AccountManagementIntegrationTest#lastAdminRoleCannotBeRevoked`; 상태 경로·동시성 없음 → AU-T4 |
| AU-I6 | 운영자는 자기 계정의 상태·역할을 바꾸지 못한다 | `ManageAccount.requireDifferentTarget`(`:72-78`) | `ManageAccountTest#cannotChangeOwnStatus`, `#cannotRevokeOwnRole` |
| AU-I7 | 계정 상태·역할 변경마다 같은 트랜잭션에 감사 한 행 | `ManageAccount.changeStatus`·`grantRole`·`revokeRole`(`@Transactional`) | `ManageAccountTest#changeStatusRevokesAndAudits`·`#grantRoleRevokesAndAudits`, `AccountManagementIntegrationTest#changeStatusPersistsRevokesTokensAndAudits`·`#grantAndRevokeRolePersistsAndAudits` |
| AU-I8 | 계정 상태·역할·비밀번호가 바뀌면 그 계정의 `ACTIVE` 계열은 같은 트랜잭션에서 0이 된다 | `TokenRevocationPort.revokeSubject`(`RotatingAuthorizationService.revokeSubject`, 같은 `DataSource` 트랜잭션), 호출: `ManageAccount`·`ChangePassword`·`RecoverPassword.reset` | `TokenRotationIntegrationTest#passwordChangeRevokesExistingRefreshTokens`·`#roleGrantRevokesPreviouslyIssuedRefreshToken`·`#roleRemovalRejectsPreviouslyIssuedRefreshTokenAndAudits`·`#passwordRecoveryRejectsExistingRefreshToken`, `AccountManagementIntegrationTest#changeStatusPersistsRevokesTokensAndAudits` |
| AU-I9 | `ACTIVE`가 아닌 계정은 새 access token·Refresh Token을 받지 못한다 | 목표: core 판정 서비스 하나(AU-T13). 지금 `AccountUserDetailsService`(`disabled`), `jwtTokenCustomizer`(`AuthSecurityConfig.java:320-322`), `RotatingRefreshProvider.authenticate`(`:38-41`) | `TokenRotationIntegrationTest#disabledAccountCannotRefresh`; 인가 코드 교환 경로 없음 → AU-T13 |
| AU-I10 | 계열의 Refresh Token 만료는 최초 발급 + 7일을 넘지 않는다 | `refresh_token_family.expires_at`(`INSERT IGNORE`로 최초 값 고정, `RotatingAuthorizationService.java:36-40`), `AuthSecurityConfig.tokenGenerator`의 `cappedRefresh`(`:277-284`), `RotatingRefreshProvider`의 상한 검사 | `TokenRotationIntegrationTest#rotationReplayRevokesLatestAndLifetimeDoesNotExtend`, `#expiredFamilyCannotRefresh` |
| AU-I11 | 한 Refresh Token은 한 번만 소비되고, 소비된 토큰의 재사용은 계열을 폐기한다 | 계열 행 `FOR UPDATE`(`RotatingAuthorizationService.lock`·`familyForToken`), `READ_COMMITTED` 회전 트랜잭션, `RotatingRefreshProvider` 재사용 분기 | `TokenRotationIntegrationTest#simultaneousRefreshConsumesOnlyOnce`, `#rotationReplayRevokesLatestAndLifetimeDoesNotExtend` |
| AU-I12 | 폐기된 계열은 되살아나지 않는다 | `revokeSubject`·`revoke`는 `revoked=TRUE`만 쓴다(되돌리는 코드 없음), `findByToken`이 폐기 계열을 숨김 | `TokenRotationIntegrationTest#lockingThenUnlockingDoesNotRestoreOldRefreshToken`, `#revocationPreventsRefresh` |
| AU-I13 | 복구 토큰은 한 번만 쓰이고 만료 뒤에는 쓰이지 않는다 | `PasswordResetToken.usable`·`consume`; 목표: `findByTokenHash`를 잠금 조회로 하거나 `UPDATE … SET status='CONSUMED' WHERE token_hash=? AND status='ISSUED'`의 갱신 행 수로 판정(지금 잠금 없음) | `RecoverPasswordTest#resetWithConsumedTokenIsRejected`·`#resetWithExpiredTokenIsRejected`, `PasswordRecoveryIntegrationTest#expiredTokenCannotReset`; 동시 사용 없음 → AU-T11 |
| AU-I14 | 복구 토큰·Refresh Token 원본은 Auth 저장소·로그에 남지 않는다 | `ResetTokens.hash`, `RotatingAuthorizationService.hash`, `LoggingPasswordResetMailAdapter`(길이만 기록). Spring `oauth2_authorization.refresh_token_value`는 프레임워크 표준 저장(notes F-5) | `RecoverPasswordTest#requestForKnownEmailIssuesTokenAndSendsMail`(해시 저장 여부 단언 확인 필요) → AU-T16 |
| AU-I15 | access token은 `aud`=[`esd-api`]와 발행 시점 DB 역할을 담고, ID token은 API 자격이 아니다 | `jwtTokenCustomizer`(`AuthSecurityConfig.java:316-327`), 자원 서버 audience 검사(`:115-121`) | `TokenRotationIntegrationTest#refreshedAccessTokenUsesCurrentRoles`, `#idTokenCannotAuthenticateAnApiRequest` |
| AU-I16 | 계정 생성과 `UserRegistered` Outbox는 함께 커밋되거나 함께 없다 | `RegisterAccount`(`@Transactional`, `OutboxRecorder` `Propagation.MANDATORY`) | `RegisterAccountTest#signupCreatesCreatorAndEvent`(롤백 경로 없음 → AU-T15) |

## 외부 연동과 adapter 메모

| 포트(`core.port`) | 뜻 | adapter | 조립 |
|---|---|---|---|
| `PasswordResetMailPort.sendResetToken(email, rawToken)` | 복구 안내를 메일로 보낸다 | 지금 `adapter/mail/LoggingPasswordResetMailAdapter`(로그만). 목표: 실제 공급자 adapter(`[외부 대기]`), 로그 adapter는 운영 조립에서 뺀다(AU-T7) | `config/PasswordResetConfig.java:22-25` |
| `TokenRevocationPort.revokeSubject(subject)` | 한 계정의 모든 토큰 계열 폐기 | `adapter/oauth/RotatingAuthorizationService`(JDBC, 호출자 트랜잭션에 참여) | `config/AuthSecurityConfig.java:242-260` |

adapter 메모(공급자·프레임워크 규칙은 여기만)

- **메일 공급자**: 공급자 이름·API 형식·오류 분류·발송 한도·재시도 가능 여부는 adapter와 `config`에만 둔다. 포트는 성공/실패만 돌려준다(실패의 재시도 여부를 core에 공급자 코드로 노출하지 않는다 — M-AU-2에서 고를 안이 재시도를 쓰면 adapter가 "재시도 가능/영구 실패" 두 값으로 번역). 호출은 계정·토큰 DB 트랜잭션이 커밋된 뒤 한다([P1.2-4](../platform/requirements.md#p12-동기-의존-방향)). 공급자 장애를 계정 상태로 옮기지 않는다([P1.2-5](../platform/requirements.md#p12-동기-의존-방향)). 지금 `RecoverPassword.request`는 `@Transactional` 안에서 메일을 부른다(`auth:core/service/RecoverPassword.java:50-57`, AU-T7).
- **Spring Authorization Server**: `RotatingAuthorizationService`는 `JdbcOAuth2AuthorizationService`를 감싸 계열·이력 표를 같은 트랜잭션에 쓴다. 회전 트랜잭션은 `READ_COMMITTED`이고 계열 행 잠금 뒤 이력 행도 잠금 조회한다(반복 읽기 스냅샷이 다른 요청의 회전을 숨기지 않게, `:58-60`). `RotatingRefreshProvider`는 기본 `OAuth2RefreshTokenAuthenticationProvider`를 감싸 재사용 폐기를 오류 반환 전에 커밋한다. 토큰 직렬화 Mixin(`PersistedValueMixin`)은 프레임워크 저장 형식 문제라 core와 무관하다.
- **비밀번호 인코더**: `PasswordEncoderFactories.createDelegatingPasswordEncoder()`(접두 `{bcrypt}`). 인코딩 방식은 adapter·config 결정이다.
- **서명 키**: RSA 3072, `auth_signing_key` 한 행, 여러 인스턴스 동시 시작은 유일 키로 승자 하나(`SigningKeyStore.loadOrCreate`).

## 설정

| 키 | 기본값(지금) | 뜻 | 운영/테스트 차이 |
|---|---|---|---|
| `stove.auth.issuer` (`AUTH_ISSUER`) | `http://localhost:8091`(yml과 `@Value` 두 곳, `AuthSecurityConfig.java:302`·`:311`) | 토큰 `iss`, OIDC 발행자 | 목표: 운영 필수 값, 코드 기본값 없음(AU-T8) |
| `stove.auth.bff-client-secret` (`BFF_CLIENT_SECRET`) | 없음(필수) | `gateway-bff` 비밀 | 같음 |
| `stove.auth.bff-redirect-uri` | `http://localhost:8080/login/oauth2/code/gateway-bff`(yml·`@Value`) | BFF callback | 목표: 운영 필수, 코드 기본값 없음(AU-T8) |
| `stove.auth.bff-access-token-seconds` | 900 | `gateway-bff` access token 수명 | 같은 키. 여정 검증의 25초는 실행 환경 값이지 다른 코드 경로가 아니다 |
| `stove.auth.redirect-uri` (`STUDIO_REDIRECT_URI`) | `http://localhost:3000/callback` | `studio-web` callback | M-AU-7 |
| `stove.auth.swagger-redirect-uris` | localhost 주소 3개(yml·`@Value`) | `swagger-ui` callback | M-AU-7 |
| `stove.auth.app-origin` | `http://localhost:3000` | (yml에만 있고 코드가 읽지 않음 — AU-T8에서 확인·제거) | — |
| `stove.auth.password-reset.lifetime` | PT30M(코드 `@Value` 기본값만, yml에 없음) | 복구 토큰 유효시간([결정 83](../../../docs/decisions.md)) | 목표: yml에 명시, 기본값 한 곳 |
| `stove.auth.bootstrap.{admin,reviewer}-{email,password}` | 빈 값 | 부트스트랩(R10) | 목표: 짝이 안 맞으면 시작 실패(AU-T8) |
| `spring.datasource.*` (`DB_HOST`·`DB_USER`·`DB_PASSWORD`) | `localhost`·`stove`·`stove1234` | DB 연결 | 목표: 운영 비밀번호 기본값 없음(AU-T8, 원칙 ⑦) |
| `stove.outbox.{relay-enabled,batch-size,poll-interval-ms,max-retry}` | true·200·200·10 | 공통 릴레이([P2.2-3·5](../platform/requirements.md#p22-발행--transactional-outbox)) | 같음 |
| `stove.messaging.ops.enabled` / `republish-owner` / `republish-enabled` | false / 없음 / 없음 | 공통 운영 API | 목표: 재발행 경로 켬(AU-T10) |
| 메일 adapter 설정(주소·키·타임아웃·발신자) | 없음 | `[외부 대기]` | adapter 설정에만 |
| 클라이언트별 토큰 수명 | `studio-web`·`swagger-ui` 15분, Refresh 7일 — 코드 상수(`AuthSecurityConfig.java:182-183`·`:201`·`:219`) | R2.3·R3.1 | 목표: 7일 상한은 계약 값이라 상수여도 되지만 한 곳에 둔다(AU-T8) |

운영 계약에 테스트 전용 경로는 없다. 테스트 편의로 넓어진 것으로 보이는 것은 localhost 기본값·DB 비밀번호 기본값·운영 등록되는 `swagger-ui` 클라이언트(M-AU-7)·로그 메일 adapter(AU-T7)다.
