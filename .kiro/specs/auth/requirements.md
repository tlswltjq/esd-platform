# 요구사항: Auth (계정·역할·OAuth2/OIDC 토큰)

## 개요

Auth는 회원과 역할의 원본을 관리하고 OAuth2/OIDC로 서명된 access token을 발행한다.
Gateway와 각 업무 서비스는 이 토큰을 검증하며, 세부 소유권(주문·프로젝트·다운로드)은 담당 서비스가 추가로 확인한다.
MVP 재작성은 Gateway와 함께 2026-10-13 완료했다(관련 테스트 132건 통과).

## 용어

- **MEMBER**: 구매자 기본 역할. 주문·결제·라이브러리·다운로드에 필요.
- **CREATOR**: 창작자 역할. 가입 시 MEMBER와 함께 부여. Studio·본인 정산에 필요.
- **ADMIN / REVIEWER**: 운영자·심사자. 부트스트랩 설정으로 생성.
- **RTR (Refresh Token Rotation)**: 갱신 시 Refresh Token을 회전하고 재사용을 탐지하는 방식.
- **member_id**: access token에 담기는 Auth 회원 ID. `roles`는 계정 역할.

## 요구사항

### R1. 계정 가입과 역할 부여
**사용자 스토리:** 신규 사용자로서, 구매자 또는 창작자로 가입하고 싶다. 그래야 역할에 맞는 기능을 쓸 수 있다.

#### 수용 기준
1. WHEN `POST /api/v1/auth/signup/member` 요청이 오면 THEN 시스템 SHALL `MEMBER` 역할의 계정을 만든다.
2. WHEN `POST /api/v1/auth/signup` 요청이 오면 THEN 시스템 SHALL `MEMBER`와 `CREATOR` 역할의 계정을 만든다.
3. WHERE 운영자·심사자 계정이 필요한 맥락에서 THE 시스템 SHALL 부트스트랩 설정으로만 생성한다.
4. WHEN 회원·창작자 가입이 완료되면 THEN 시스템 SHALL `UserRegistered` 이벤트를 발행한다.
5. WHEN 가입·부트스트랩 권한 부여가 일어나면 THEN 시스템 SHALL `role_audit_log`에 기록한다.

### R2. OAuth2 Authorization Code + PKCE 토큰 발행
**사용자 스토리:** 로그인한 사용자로서, 내 역할·범위에 맞는 access token을 받고 싶다. 그래야 API를 호출할 수 있다.

#### 수용 기준
1. WHEN 창작·심사 흐름이 인증을 요청하면 THEN 시스템 SHALL `studio` 범위를, 커머스 흐름이면 `commerce` 범위를 사용한다.
2. WHEN access token을 발행하면 THEN 토큰 SHALL `member_id`(회원 ID)와 `roles`(계정 역할)를 담는다.
3. THE access token SHALL 기본 15분 만료다.
4. IF 요청이 ID token을 API 자격증명으로 쓰려 하면 THEN 시스템 SHALL 이를 인정하지 않는다.
5. WHERE 공개 상품·스토어 읽기 맥락에서 THE 시스템 SHALL 로그인 없이 접근을 허용한다.

### R3. Refresh Token 회전과 재사용 탐지 (RTR)
**사용자 스토리:** 사용자로서, 세션을 안전하게 갱신하고 싶다. 그래야 토큰 탈취 시 피해가 번지지 않는다.

#### 수용 기준
1. THE Refresh Token SHALL 최초 발급부터 7일의 절대 상한을 가지며, 갱신해도 상한을 연장하지 않는다.
2. WHILE 토큰 계열을 갱신하는 동안 THE 시스템 SHALL 계열별 MySQL 잠금 아래에서 처리한다.
3. WHEN 소비된 Refresh Token이 재사용되면 THEN 시스템 SHALL 그 계열 전체를 폐기한다.
4. WHEN 갱신이 일어나면 THEN 시스템 SHALL DB의 계정 상태와 최신 역할을 확인한다.
5. IF 계정이 비활성이면 THEN 시스템 SHALL 갱신을 거부한다.
6. THE 토큰 사용 이력 SHALL SHA-256 해시로 별도 저장한다.

### R4. 비밀번호 변경
**사용자 스토리:** 사용자로서, 현재 비밀번호를 확인한 뒤 새 비밀번호로 바꾸고 싶다. 그래야 계정을 안전하게 유지한다.

#### 수용 기준
1. WHEN `POST /api/v1/auth/password` 요청이 `currentPassword`·`newPassword`로 오면 THEN 시스템 SHALL 현재 비밀번호를 확인한 뒤 변경한다.
2. THE 새 비밀번호 SHALL 12~100자여야 한다.
3. WHEN 비밀번호가 바뀌면 THEN 시스템 SHALL 해당 회원의 모든 Refresh Token 계열을 폐기한다.
4. WHERE 쿠키 인증의 변경 요청에서 THE 시스템 SHALL CSRF 헤더·토큰을 요구한다.

### R5. 토큰 검증 계약 (소비 측 기준)
**사용자 스토리:** 업무 서비스로서, 신뢰할 수 있는 토큰만 받고 싶다. 그래야 권한을 정확히 판단한다.

#### 수용 기준
1. WHEN 서비스가 토큰을 받으면 THEN 시스템 SHALL 서명·발행자(issuer)·`esd-api` audience를 검증한다.
2. THE 시스템 SHALL 기본 거부 정책을 적용한다(명시적으로 허용한 것만 통과).
3. WHERE 주문·고객 환불·결제·라이브러리·다운로드 맥락에서 THE 시스템 SHALL `MEMBER` 역할을 요구한다.
4. WHERE 관리자 환불·운영자 정산·`/api/v1/ops/**` 맥락에서 THE 시스템 SHALL `ADMIN` 역할을 요구한다.
5. WHERE 창작자 본인 정산(`/api/v1/settlements/me/**`) 맥락에서 THE 시스템 SHALL `CREATOR` 역할과 소유권을 확인한다.

## 범위 밖(후속)

- 소셜 로그인 다수·고급 MFA·외부 인증 플랫폼 연동.
- 분실 비밀번호 복구, 계정 상태/권한 관리의 최소 계약·감사 → 일반화 G5에서 닫음.
- 다른 기기 전체 세션 종료 정책(비밀번호 변경과 별개).
