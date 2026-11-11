# 요구사항: Auth (계정·역할·OAuth2/OIDC 토큰, 비밀번호 복구, 운영자 계정 관리)

> 상태: 작성 중([결정 150](../../../docs/decisions.md#spec-foundation)·[결정 178](../../../docs/decisions.md#evaluation-findings-deferred)). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다.

## 개요

Auth는 계정과 역할의 정본이다([플랫폼 design "정본 소유"](../platform/design.md#정본-소유) 행 "계정·역할(`sub`)"). OAuth2 Authorization Server로 로그인·토큰 발급·Refresh Token 회전·재사용 탐지를 맡고, 비밀번호 변경·분실 복구와 운영자의 계정 상태·역할 관리를 맡는다.

- 다른 업무 모듈을 동기 호출하지 않는다([플랫폼 design "동기 의존"](../platform/design.md#동기-의존-http)). Auth를 부르는 모듈은 Gateway 하나다(`/oauth2/token`·`/oauth2/jwks`·`/userinfo`, BFF 로그인·토큰 릴레이).
- 외부 공급자는 메일 발송 하나다. 호출 계약은 `core.port`, 구현은 `adapter`, 조립은 `config`에 둔다([P1.1-3](../platform/requirements.md#p11-경계-코드-수준)·[P1.1-4](../platform/requirements.md#p11-경계-코드-수준)). 메일 발송은 모듈 간 이벤트가 아니다.
- 발행 이벤트는 `UserRegistered` 하나이고 소비자가 없다([플랫폼 design 이벤트 목록 #1](../platform/design.md#eventtype-상수-42개--현행목표-분리안)).
- 이 문서는 **목표 계약**이다. 코드가 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"(`AU-Tn`)에 있다. 정해지지 않은 정책은 `[결정 필요 M-AU-n]`이다.

## 용어

- **계정(`user_account`)**: 로그인 신원. 식별자는 `subject`(JWT `sub`, `usr_` + 32자 16진수, 생성 뒤 바뀌지 않음)와 숫자 `id`(access token `member_id`)다.
- **계정 상태(`AccountStatus`)**: `ACTIVE`(로그인·토큰 발급 가능) · `LOCKED`(운영자 정지, 해제 가능) · `DISABLED`(운영자 종료, 되돌릴 수 없음).
- **역할(`PlatformRole`)**: `MEMBER`(구매자) · `CREATOR`(창작자) · `REVIEWER`(심사자) · `ADMIN`(운영자). 계정은 역할 집합을 갖는다.
- **활성 운영자**: `ACTIVE` 상태이고 `ADMIN` 역할을 가진 계정.
- **토큰 계열(refresh token family)**: 한 번의 로그인(OAuth2 authorization 하나)에서 회전으로 이어지는 Refresh Token들. 식별자는 `authorization_id`.
- **RTR(Refresh Token Rotation)**: 갱신마다 Refresh Token을 새로 발급하고 이전 토큰을 소비 처리하며, 소비된 토큰이 다시 오면 재사용으로 본다.
- **절대 상한**: 계열의 최초 발급 시각부터 7일. 갱신이 늘리지 않는다.
- **복구 토큰(`password_reset_token`)**: 분실 비밀번호 복구용 일회용 비밀값. 원본은 메일로만 나가고 저장소에는 SHA-256 해시만 남는다.
- **계정 토큰 폐기**: 한 계정(`subject`)의 모든 토큰 계열을 폐기 상태로 바꾸는 것(`TokenRevocationPort.revokeSubject`).
- **메일 발송**: 외부 메일 공급자에게 복구 안내를 보내는 동기 외부 연동. 요구사항에는 공급자 이름을 쓰지 않는다(원칙 ②).

## 요구사항

### R1. 계정 가입과 역할 부여
**사용자 스토리:** 신규 사용자로서, 구매자 또는 창작자로 가입하고 싶다. 그래야 역할에 맞는 기능을 쓸 수 있다.

#### 수용 기준
1. WHEN `POST /api/v1/auth/signup/member` 요청이 이메일·비밀번호(12~100자)로 오면 THEN Auth SHALL `ACTIVE` 상태·역할 {`MEMBER`}의 계정을 만든다.
2. WHEN `POST /api/v1/auth/signup` 요청이 오면 THEN Auth SHALL `ACTIVE` 상태·역할 {`MEMBER`, `CREATOR`}의 계정을 만든다.
3. THE 공개 가입 SHALL `ADMIN`·`REVIEWER` 역할을 주지 않는다. `ADMIN`·`REVIEWER`는 부트스트랩 설정(R10)이나 운영자의 역할 부여(R7.3)로만 생긴다.
4. WHEN 가입 트랜잭션이 계정을 만들면 THEN Auth SHALL 같은 트랜잭션에 `UserRegistered` Outbox를 적재한다(R8).
5. WHEN 가입·부트스트랩이 역할을 부여하면 THEN Auth SHALL 같은 트랜잭션에 `role_audit_log`(수행 주체 `system:signup`·`system:bootstrap`, 행동 `ROLE_ASSIGNED`, 부여 뒤 역할 집합)를 남긴다.
6. IF 같은 이메일(대소문자 무시)의 계정이 이미 있으면 THEN Auth SHALL 계정을 만들지 않고 409 `CONFLICT`로 거절한다. 동시 가입도 DB 유일 제약으로 같은 결과가 된다.
7. THE 계정 `subject` SHALL 생성 때 한 번 정해지고 바뀌지 않는다. 다른 모듈이 소유자·구매자로 저장하는 값이 이 `subject`다.
8. THE 모든 계정 SHALL 역할 `MEMBER`를 가진다 `[결정 필요 M-AU-1]` — 부트스트랩 계정이 지금 `MEMBER` 없이 생긴다(AU-T3).

### R2. OAuth2 Authorization Code + PKCE 토큰 발행
**사용자 스토리:** 로그인한 사용자로서, 내 역할·범위에 맞는 access token을 받고 싶다. 그래야 API를 호출할 수 있다.

#### 수용 기준
1. WHEN 클라이언트가 인가 코드를 요청하면 THEN Auth SHALL PKCE(`requireProofKey`)를 요구하고, 범위는 등록된 `openid`·`profile`·`studio`(창작·심사)·`commerce`(커머스) 안에서만 허용한다.
2. WHEN Auth가 access token을 발행하면 THEN 토큰 SHALL `iss`(설정 `stove.auth.issuer`), `aud`=[`esd-api`], `sub`, `member_id`(계정 `id`), `roles`(그 시점 DB 역할, 이름순)를 담는다.
3. THE access token 수명 SHALL 클라이언트 등록의 설정값이고 기본 15분이다(`gateway-bff`는 `stove.auth.bff-access-token-seconds`, 기본 900).
4. IF 요청이 ID token을 API 자격증명으로 쓰면 THEN Auth와 모든 자원 서버 SHALL `aud`에 `esd-api`가 없으므로 거절한다.
5. IF 토큰을 발행할 계정이 `ACTIVE`가 아니면 THEN Auth SHALL access token을 발행하지 않고 `invalid_grant`로 거절한다(로그인·인가 코드 교환·갱신 모두).
6. WHEN 로그인 폼이 이메일·비밀번호를 받으면 THEN Auth SHALL `ACTIVE` 계정의 비밀번호가 맞을 때만 인증한다. `LOCKED`·`DISABLED`는 비밀번호가 맞아도 인증하지 않는다.
7. THE 기밀 클라이언트 `gateway-bff` SHALL Authorization Code·Refresh Token 승인을 쓰고, 공개 클라이언트(`studio-web`·`swagger-ui`) SHALL Refresh Token을 받지 않는다(Gateway design "BFF 토큰 보관" 절의 기존 계약, AU-T9). 공개 클라이언트를 운영에 등록할지는 `[결정 필요 M-AU-7]`.

### R3. Refresh Token 회전과 재사용 탐지 (RTR)
**사용자 스토리:** 사용자로서, 세션을 안전하게 갱신하고 싶다. 그래야 토큰 탈취 시 피해가 번지지 않는다.

#### 수용 기준
1. THE 토큰 계열 SHALL 최초 발급부터 7일의 절대 상한(`refresh_token_family.expires_at`)을 가지며, 갱신으로 받은 Refresh Token의 만료는 계열 상한과 같다(늘지 않는다).
2. WHILE 한 계열을 갱신하는 동안 THE Auth SHALL 그 계열 행을 DB 잠금(`SELECT … FOR UPDATE`) 아래에서 처리해, 인스턴스가 여럿이어도 한 Refresh Token은 한 번만 소비된다.
3. WHEN 이미 소비된 Refresh Token이 다시 오면 THEN Auth SHALL 그 계열을 폐기하고 그 폐기를 커밋한 뒤 `invalid_grant`로 거절한다.
4. WHEN 갱신이 일어나면 THEN Auth SHALL DB의 계정 상태와 현재 역할을 읽고, 새 access token에는 현재 역할을 담는다.
5. IF 계정이 `ACTIVE`가 아니거나, 계열이 상한을 지났거나, 계열이 이미 폐기됐거나, 요청 클라이언트가 계열의 클라이언트와 다르면 THEN Auth SHALL 갱신을 거절한다. 앞의 둘은 계열도 폐기한다.
6. THE Refresh Token 원본 SHALL Auth 저장소의 회전 이력에 남지 않는다. 이력은 SHA-256 해시(`refresh_token_history.token_hash`)로만 저장한다.
7. THE 폐기된 계열 SHALL 다시 사용할 수 없다. 계정이 `LOCKED`에서 `ACTIVE`로 돌아와도 정지 전 계열은 살아나지 않는다.
8. WHEN 클라이언트가 로그아웃·토큰 폐기(`/oauth2/revoke`, OIDC 로그아웃)를 요청하면 THEN Auth SHALL 그 계열을 폐기한다.
9. THE 상한 판정과 소비 판정 SHALL Auth 시계 하나로 한다(원칙 ⑥ 대상 아님 — 한 시계 안의 비교).

### R4. 비밀번호 변경
**사용자 스토리:** 사용자로서, 현재 비밀번호를 확인한 뒤 새 비밀번호로 바꾸고 싶다. 그래야 계정을 안전하게 유지한다.

#### 수용 기준
1. WHEN `POST /api/v1/auth/password` 요청이 로그인한 계정의 `currentPassword`·`newPassword`로 오면 THEN Auth SHALL 계정이 `ACTIVE`이고 현재 비밀번호가 맞을 때만 새 해시를 저장한다.
2. THE 새 비밀번호 SHALL 12~100자다. 어기면 400 `INVALID_REQUEST`.
3. WHEN 비밀번호가 바뀌면 THEN Auth SHALL 같은 트랜잭션에서 그 계정의 모든 토큰 계열을 폐기한다.
4. WHERE 쿠키 인증의 변경 요청에서 THE Auth SHALL CSRF 토큰을 요구한다. Bearer 인증 요청은 CSRF 검사를 하지 않는다.
5. IF 현재 비밀번호가 틀리면 THEN Auth SHALL 403 `FORBIDDEN`, 계정이 없거나 `ACTIVE`가 아니면 401 `UNAUTHORIZED`로 거절하고 아무것도 바꾸지 않는다.
6. WHEN 비밀번호가 바뀌면 THEN Auth SHALL 그 계정의 쓰지 않은 복구 토큰을 처리한다 `[결정 필요 M-AU-3]`.

### R5. 발행 토큰의 계약과 Auth 자신의 접근 통제
**사용자 스토리:** 자원 서버로서, 신뢰할 수 있는 토큰만 받고 싶다. 그래야 권한을 정확히 판단한다.

#### 수용 기준
1. THE Auth SHALL 자원 서버가 검증할 수 있는 것만 토큰 계약으로 낸다: 서명 키(`/oauth2/jwks`), 발행자(`iss` = `stove.auth.issuer`), audience `esd-api`, `sub`·`member_id`·`roles`·만료(R2.2). 자원 서버 각각의 검증과 경로별 역할은 Gateway 스펙과 각 모듈 스펙이 정본이다.
2. THE Auth의 자기 API SHALL 기본 거부다. 공개 경로는 가입 2개, 복구 2개, 로그인·OAuth2·OIDC 메타데이터·공개 키, `GET /actuator/health`·`/actuator/prometheus`·`/actuator/metrics/**`뿐이다.
3. WHERE `/api/v1/admin/accounts/**`에서 THE Auth SHALL 자기 보안 설정에서도 `ADMIN` 역할을 요구한다. Gateway 판정에만 기대지 않는다(AU-T1).
4. WHERE `/api/v1/ops/**`에서 THE Auth SHALL 공통 운영 API(실패 보관 조회·재투입, Outbox `DEAD` 회수, 재발행)를 `ADMIN`에게만 열고 나머지는 거부한다([P2.2-7](../platform/requirements.md#p22-발행--transactional-outbox)·[P2.6-4](../platform/requirements.md#p26-재시도dlt)).
5. THE JWT 서명 키 SHALL Auth 재시작 뒤에도 같아서, 발행된 access token이 만료 전까지 검증된다(`auth_signing_key`).

### R6. 분실 비밀번호 복구
**사용자 스토리:** 비밀번호를 잊은 사용자로서, 이메일로 본인을 증명하고 새 비밀번호를 정하고 싶다. 그래야 현재 비밀번호를 몰라도 계정을 되찾는다.

#### 수용 기준
1. WHEN `POST /api/v1/auth/password/reset-request`가 이메일로 오면 THEN Auth SHALL 가입 여부·계정 상태·발송 결과와 무관하게 같은 본문(`accepted: true`)으로 성공 응답한다([결정 83](../../../docs/decisions.md)).
2. WHERE 복구 토큰을 발급할 계정이면 THE Auth SHALL 256비트 난수 원본을 만들어 해시만 `password_reset_token`에 `ISSUED`로 저장하고, 원본은 메일 발송(R9)으로만 내보낸다. 원본은 로그·DB에 남기지 않는다. 어떤 상태의 계정에 발급하는지는 `[결정 필요 M-AU-5]`.
3. THE 복구 토큰 SHALL 발급 시각부터 설정 `stove.auth.password-reset.lifetime`(기본 PT30M, [결정 83](../../../docs/decisions.md)) 동안만 쓸 수 있다. 발급 시각과 사용 시각은 모두 Auth 시계다.
4. WHEN `POST /api/v1/auth/password/reset`가 토큰·새 비밀번호(12~100자)로 오면 THEN Auth SHALL 그 토큰 행을 잠근 채 `ISSUED`이고 만료 전일 때만 새 해시를 적용한다.
5. WHEN 복구가 성공하면 THEN Auth SHALL 같은 트랜잭션에서 토큰을 `CONSUMED`로 바꾸고 그 계정의 모든 토큰 계열을 폐기한다. 같은 계정의 다른 `ISSUED` 토큰 처리는 `[결정 필요 M-AU-3]`.
6. IF 토큰이 없거나 `ISSUED`가 아니거나 만료됐으면 THEN Auth SHALL 사유를 구분하지 않고 400 `INVALID_REQUEST`("복구 링크가 유효하지 않거나 만료되었습니다")로 거절하고 아무것도 바꾸지 않는다.
7. THE 메일 발송 SHALL `core.port` `PasswordResetMailPort`로만 부르고, 계정·토큰을 바꾸는 DB 트랜잭션 밖에서 부른다([P1.1-3](../platform/requirements.md#p11-경계-코드-수준)·[P1.2-4](../platform/requirements.md#p12-동기-의존-방향)). 발송 보장은 R9.
8. IF 같은 토큰으로 복구 두 건이 동시에 오면 THEN Auth SHALL 한 건만 성공시키고 다른 건은 6과 같이 거절한다.
9. WHILE 같은 이메일로 복구 요청이 반복되면 THE Auth SHALL 발급 수를 제한한다 `[결정 필요 M-AU-4]`. 제한에 걸려도 응답은 1과 같다.

### R7. 계정 상태·권한 관리(운영자)
**사용자 스토리:** 운영자로서, 계정을 정지·해제·종료하고 역할을 부여·회수하고 싶다. 그래야 플랫폼을 안전하게 운영한다.

#### 수용 기준
1. WHERE `/api/v1/admin/accounts/**`에서 THE Auth SHALL 요청자가 `ADMIN` 역할을 가진 토큰을 내야 하고(R5.3), 요청 처리 때 요청자 계정이 DB에서 지금도 활성 운영자인지 확인한다. 아니면 403 `FORBIDDEN`([결정 84](../../../docs/decisions.md) "즉시 끊기", AU-T6).
2. THE 계정 상태 전환 SHALL design "계정 상태 × 트리거" 표를 따른다: `ACTIVE`↔`LOCKED`, `ACTIVE`·`LOCKED`→`DISABLED`, `DISABLED`는 끝 상태, 같은 상태로의 전환은 409 `CONFLICT`([결정 84](../../../docs/decisions.md)).
3. WHEN 운영자가 역할을 부여하면 THEN Auth SHALL 그 역할을 더한다. 이미 가진 역할의 부여는 무변화(멱등)이고 감사·폐기도 하지 않는다(AU-T5). WHEN 운영자가 역할을 회수하면 THEN Auth SHALL 그 역할을 뺀다. `MEMBER` 회수와 가지지 않은 역할의 회수는 409 `CONFLICT`.
4. IF 변경 대상이 요청자 자신이면 THEN Auth SHALL 상태·역할 변경을 403 `FORBIDDEN`으로 거절한다.
5. THE 활성 운영자 수 SHALL 1 이상이다. IF 상태 변경(`LOCKED`·`DISABLED`)이나 `ADMIN` 회수가 활성 운영자를 0명으로 만들면 THEN Auth SHALL 409 `CONFLICT`로 거절한다. 동시에 들어온 변경 둘이 함께 0명을 만들지 못하도록 직렬화한다(AU-T4).
6. WHEN 상태·역할이 바뀌면 THEN Auth SHALL 같은 트랜잭션에서 대상 계정의 모든 토큰 계열을 폐기하고, 계정 접근 변경 사실(가칭 `AccountAccessChanged`, 토픽 `stove.auth.v1`, 키 `subject`, 본문 `subject`·`changedAt`(Auth 시계)·`changeKind`(`STATUS`·`ROLE`))을 Outbox에 쓴다. 이 사실은 "그 계정의 이전 접근 근거가 더는 유효하지 않다"만 뜻한다. 이미 발급된 access token은 Gateway가 이 사실로 거절한다(Gateway R4.6, [결정 180](../../../docs/decisions.md#account-access-revocation)). 무변화(이미 가진 역할의 부여)는 사실을 내지 않는다.
7. WHEN 상태·역할이 바뀌면 THEN Auth SHALL 같은 트랜잭션에 `role_audit_log`를 남긴다: 수행 주체(요청자 `subject`), 행동(`STATUS_CHANGED`·`ROLE_GRANTED`·`ROLE_REVOKED`), 변경 뒤 상태와 변경 뒤 역할 집합을 각자의 칸에(AU-T5).
8. IF 대상 계정이 없으면 THEN Auth SHALL 404 `NOT_FOUND`로 거절한다.

### R8. `UserRegistered` 발행
**사용자 스토리:** 계정 정본으로서, 가입 사실을 다른 모듈이 받을 수 있게 내고 싶다. 그래야 사본을 둘 모듈이 생겨도 정본에서 받는다.

#### 수용 기준
1. WHEN 공개 가입이 계정을 만들면 THEN Auth SHALL 같은 트랜잭션에 `OutboxRecorder.record("UserAccount", subject, UserRegisteredEvent)`로 적재한다. 뜻·토픽 `stove.auth.v1`·파티션 키 `subject`·소비자는 [플랫폼 design 이벤트 목록 #1](../platform/design.md#eventtype-상수-42개--현행목표-분리안)이 정본이다([P2.2-1](../platform/requirements.md#p22-발행--transactional-outbox)·[P2.4-1](../platform/requirements.md#p24-파티션-키)).
2. THE 본문 `occurredAt` SHALL 계정 생성 시각(`user_account.created_at`)과 같다([P2.1-2](../platform/requirements.md#p21-봉투)·[P2.1-3](../platform/requirements.md#p21-봉투)).
3. THE 부트스트랩·운영자 역할 변경 SHALL `UserRegistered`를 내지 않는다(가입 사실이 아니다).
4. THE Auth SHALL 현행 이벤트를 소유한 발행 모듈로서 운영 재발행 경로(`outbox_republish`, `stove.messaging.ops.republish-owner`, ADMIN 운영 API)를 갖는다([P2.2-7](../platform/requirements.md#p22-발행--transactional-outbox), [결정 161](../../../docs/decisions.md#republish-all-current), 플랫폼 T7, AU-T10). 재발행 이벤트의 같은 키 안 발행 순번 헤더와 회수 정렬은 플랫폼 계약을 따른다(1회차 지적 B1, 판정 전).
5. THE Auth SHALL 이벤트를 소비하지 않는다. 소비를 더하면 [P2.3](../platform/requirements.md#p23-소비--inbox와-업무-멱등)·[P2.6](../platform/requirements.md#p26-재시도dlt)를 따른다.

### R9. 복구 메일 발송 (외부 연동)
**사용자 스토리:** 복구를 요청한 사용자로서, 복구 링크를 메일로 받고 싶다. 그래야 R6을 끝낼 수 있다.

#### 수용 기준
1. THE 메일 발송 계약 SHALL `core.port` `PasswordResetMailPort.sendResetToken(email, rawToken)` 하나이고 공급자 이름·형식·오류 분류를 담지 않는다. 공급자 구현은 `adapter/mail`, 주소·키·타임아웃은 `config`에 둔다([P1.1-3](../platform/requirements.md#p11-경계-코드-수준)·[P1.1-4](../platform/requirements.md#p11-경계-코드-수준)).
2. THE 메일 발송 SHALL 모듈 간 이벤트(`common/kafka` 계약·Outbox 토픽)로 내지 않는다. 메일은 Auth가 외부 공급자에게 거는 동기 호출이다.
3. IF 메일 공급자가 응답하지 않거나 거절하면 THEN Auth SHALL 계정·토큰 상태를 추측해 바꾸지 않고, R6.1의 응답을 바꾸지 않는다. 발송 실패를 다시 보낼지·어디에 기록할지는 `[결정 필요 M-AU-2]`.
4. THE 운영 설정 SHALL 실제 발송 adapter를 조립한다. 로그만 남기는 adapter(`LoggingPasswordResetMailAdapter`)는 운영 기본값이 아니다(원칙 ⑦, AU-T7). 실제 공급자 연동은 `[외부 대기]`(tasks).
5. THE 발송 adapter와 로그 SHALL 원본 토큰을 기록하지 않는다.

### R10. 부트스트랩 계정
**사용자 스토리:** 운영자로서, 배포 때 첫 운영자·심사자 계정을 비밀 설정으로 만들고 싶다. 그래야 공개 가입 없이 운영을 시작한다.

#### 수용 기준
1. WHEN Auth가 시작되고 `stove.auth.bootstrap.admin-email`·`admin-password`가 있으면 THEN Auth SHALL 그 이메일의 계정이 없을 때만 역할 {`ADMIN`, `REVIEWER`}(+ `MEMBER` 여부 `[결정 필요 M-AU-1]`) 계정을 `ACTIVE`로 만든다. 심사자(`reviewer-email`·`reviewer-password`)는 {`REVIEWER`}(같은 조건)다.
2. IF 그 이메일의 계정이 이미 있으면 THEN Auth SHALL 상태·역할·비밀번호를 바꾸지 않는다(무변화). 운영 중 계정 변경은 R7로만 한다.
3. WHEN 부트스트랩이 계정을 만들면 THEN Auth SHALL 같은 트랜잭션에 `role_audit_log`(`system:bootstrap`, `ROLE_ASSIGNED`)를 남긴다.
4. IF 이메일과 비밀번호 중 하나만 설정됐으면 THEN Auth SHALL 시작을 실패시킨다(빈 값을 조용히 건너뛰지 않는다, AU-T8).
5. IF 여러 인스턴스가 동시에 시작하면 THEN 부트스트랩 SHALL 이메일 유일 제약으로 계정 하나만 만들고 다른 인스턴스는 시작을 이어 간다.

## 범위 밖(후속)

- 소셜 로그인·고급 MFA·외부 인증 플랫폼 연동.
- 다른 기기 전체 세션 종료 정책(비밀번호 변경과 별개).
- JWT 서명 키 교체(여러 키 게시·폐기 일정) — 지금 키는 `auth_signing_key.key_name='active'` 하나다.
- 로그인 실패 횟수에 따른 자동 잠금. `LOCKED`는 운영자 정지만 뜻한다.
- 가입 409가 이메일 가입 여부를 드러내는 문제(R6.1의 비노출은 복구 요청에만 적용).
- 비밀번호 변경·복구의 감사 기록(`role_audit_log`는 역할·상태 변경만 다룬다).
- 계정 탈퇴·개인정보 삭제(다른 모듈의 `subject` 사본과 함께 정해야 한다).
