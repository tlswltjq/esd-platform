# 설계: Auth

## 아키텍처 위치

- 포트 8091, 저장소 MySQL.
- `core/{domain, port, service}` + `api/{controller, listener}` + `adapter/` + `config` 경계를 따른다.
- 프레임워크 SPI 구현(Spring Authorization Server 연동 등)은 `adapter/`에 두고 조립은 `config`가 담당한다.
- Gateway(8080)가 기밀 OAuth 클라이언트 `gateway-bff`로 Authorization Code + PKCE를 수행하고, Auth는 인증·토큰 발급·회전·재사용 탐지를 담당한다.

## 핵심 설계 결정

- **access token에 역할·회원 ID를 담는다.** `member_id`는 Auth 회원 ID, `roles`는 계정 역할. ID token은 API 자격증명으로 쓰지 않는다.
- **범위 분리.** 창작·심사는 `studio`, 커머스는 `commerce` 범위.
- **RTR + 절대 상한.** Refresh Token은 최초 발급부터 7일 상한, 갱신이 상한을 늘리지 않는다. 소비된 토큰 재사용 시 계열 전체 폐기.
- **현재 토큰은 Spring JDBC 인가 저장소, 사용 이력은 SHA-256 해시로 분리 저장.**
- **갱신 시 최신 상태 재확인.** 계정 상태·역할을 DB에서 확인하고 비활성 계정 갱신 거부.

## 데이터 모델

| 테이블 | 내용 |
|---|---|
| 계정·역할 | 회원 식별자, 역할(MEMBER/CREATOR/ADMIN/REVIEWER), 계정 상태 |
| 인가 저장소 | Spring JDBC OAuth2 authorization 저장(현재 토큰) |
| 토큰 사용 이력 | Refresh Token 계열, SHA-256 해시 |
| `role_audit_log` | 가입·부트스트랩 권한 부여 감사 |

모델 속성은 `docs/domain/models/identity.md` 참조.

## API 계약

| 메서드 | 경로 | 역할 | 동작 |
|---|---|---|---|
| POST | `/api/v1/auth/signup` | 공개 | 창작자 가입(MEMBER+CREATOR) |
| POST | `/api/v1/auth/signup/member` | 공개 | 구매자 가입(MEMBER) |
| POST | `/api/v1/auth/password` | 인증 | 현재 비밀번호 확인 후 변경, 토큰 계열 폐기 |
| — | `/login`, `/logout`, `/oauth2/**`, `/.well-known/**` | 공개 | 로그인·승인·토큰·공개 키·OIDC 메타데이터 |

## 이벤트 계약

| 방향 | 이벤트 | 비고 |
|---|---|---|
| 발행 | `UserRegistered` | 회원·창작자 가입 시. Studio는 Auth JWT sub로 소유자를 직접 식별(별도 판매자 연결 없음) |

## 외부 연동

- 외부 업무 포트 없음. Auth는 토큰·계정의 원본이다.
- 소비 측(Gateway·각 서비스)이 서명·issuer·`esd-api` audience를 검증하는 것이 계약.

## 토큰·갱신 동작 요약

- Access Token 기본 15분. 서명·만료·issuer·audience 검증.
- Refresh Token 7일 절대 상한, 갱신이 연장하지 않음.
- 계열별 MySQL 잠금으로 갱신 직렬화, 재사용 시 계열 폐기.
- 로그아웃·재사용 탐지·비밀번호 변경이 Refresh Token을 폐기. 기존 JWT는 만료까지 유효.
