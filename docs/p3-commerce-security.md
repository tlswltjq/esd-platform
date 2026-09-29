# 이슈 #75: 커머스 인증·권한 경계

## 실행 계획

1. Auth가 구매자 계정과 서명된 access token의 `member_id`·`roles`를 발행한다.
2. Gateway와 각 커머스 서비스에서 JWT의 발행자·대상(`esd-api`)·역할을 검증하고 기본 거부 정책을 적용한다.
3. 주문·결제·라이브러리·다운로드는 토큰의 회원 ID만 사용한다. 주문번호 소유권과 다운로드 권한은 담당 서비스가 확인한다.
4. PG 콜백을 일반 사용자 API에서 분리하고 원문 서명·시각·사전등록 거래 ID를 검증한다. 권한 부여와 환불 기록을 보존한다.
5. 단위·통합·OpenAPI 계약·역할별 E2E 테스트로 정상 호출과 우회 시도를 확인한다.

## 사용자 API

- `POST /api/v1/auth/signup/member`는 `MEMBER` 계정을 만든다. 창작자 가입 계정에는 `MEMBER`와 `CREATOR` 역할을 준다. 운영자와 심의자 계정은 부트스트랩 설정을 사용한다.
- OAuth2 Authorization Code + PKCE에서 `commerce` 범위를 요청한다. **access token**의 `member_id`는 Auth의 회원 기본 키이며, `roles`는 현재 계정 역할이다. ID token은 커머스 API 자격증명이 아니다.
- Gateway와 서비스는 서명, 발행자, `esd-api` audience를 검증한다. 주문·결제·라이브러리·다운로드에는 `MEMBER` 역할이 필요하다. 정산 및 운영용 `/api/v1/ops/**`에는 `ADMIN`이 필요하다. 상품·스토어 읽기만 공개한다. 할인 운영 API를 추가할 때도 `ADMIN` 규칙을 적용한다.
- 주문 생성 본문에는 회원 ID가 없다. 다른 회원의 주문번호로 주문 조회·취소 또는 결제 조회·준비·환불을 요청하면 `403`이다. `X-Member-Id`는 사용하지 않으며 Gateway가 해당 헤더를 제거한다. 라이브러리 목록과 다운로드 티켓은 토큰 회원 ID에 대해서만 조회한다. 다운로드 티켓에는 해당 상품 라이선스도 필요하다.
- 카탈로그 공개 상품 상세는 출시된 상품만 반환한다. 출시 전 상품의 내부 조회는 별도 서비스 메서드를 사용한다.

## PG 콜백 계약

`POST /api/v1/payments/callback`에는 사용자 JWT 대신 PG 서명이 필요하다. `PgCallbackVerifier` 포트를 통해 사업자별 검증 구현을 교체할 수 있다. 현재 로컬 어댑터는 다음 계약을 사용한다.

| 항목 | 값 |
|---|---|
| `X-Pg-Timestamp` | Unix epoch 초, 서버 시각과 5분 이내 |
| `X-Pg-Signature` | `HMAC-SHA256(secret, ASCII(timestamp) + "." + raw_body)`의 소문자 16진수 |
| 본문 | 최대 64 KiB의 원본 JSON 바이트 |
| 비밀 | `PG_CALLBACK_SECRET`; 운영 프로필에서 로컬 기본값 사용 금지 |

서명 누락·변조·만료는 `401`이며, 검증된 콜백도 사전등록에서 받은 `pgTxId`와 승인 금액이 일치해야 한다. 로컬 mock PG는 운영 프로필에서 사용하지 않는다. 실제 PG 선택과 실거래 연동은 후속 작업이다.

## 감사와 검증

- Auth의 `role_audit_log`는 가입·부트스트랩 권한 부여를 기록한다. Payment의 `payment_audit_log`는 환불 요청·완료를 주문·회원·사유와 함께 기록한다.
- `./gradlew test`, 원격 `integrationTest`, `./scripts/remote.sh e2e`를 실행한다. E2E는 다른 회원 토큰과 위조 헤더·본문, 무서명 콜백, 일반 회원의 정산 접근을 거부하는지 확인한다. OpenAPI 스냅샷은 변경된 요청·응답 계약과 함께 갱신한다.
