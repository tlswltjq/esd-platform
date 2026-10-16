# 인증·권한 계약

[문서 목록](README.md) · [계정·역할 모델](domain/models/identity.md) · [API 데모](api-demo.md)

Auth가 회원·역할과 서명된 access token을 발행한다. Gateway와 각 서비스는 JWT를 검증하며, 주문 소유권·프로젝트 소유권·다운로드 가능 여부는 담당 서비스가 추가로 확인한다.

## 계정과 사용자 API

- `POST /api/v1/auth/signup/member`는 `MEMBER` 계정을 만든다. 창작자 가입 계정에는 `MEMBER`와 `CREATOR` 역할을 준다. 운영자와 심사자 계정은 부트스트랩 설정을 사용한다.
- OAuth2 Authorization Code + PKCE에서 창작·심사는 `studio`, 커머스는 `commerce` 범위를 요청한다. access token의 `member_id`는 Auth 회원 ID, `roles`는 계정 역할이다. ID token은 API 자격증명이 아니다.
- Gateway와 서비스는 서명·발행자·`esd-api` audience를 검증하고 기본 거부 정책을 적용한다. 주문·결제·라이브러리·다운로드에는 `MEMBER`, 운영자 정산·`/api/v1/ops/**`에는 `ADMIN`이 필요하다. 창작자 자신의 정산을 보는 `/api/v1/settlements/me/**`는 `CREATOR`와 소유권을 확인한다. 공개 상품·스토어 읽기는 로그인 없이 제공한다.
- Catalog MVP는 공개 상품 목록·상세 GET만 제공하며 쓰기 API를 제공하지 않는다. 기존 할인·프로모션 업무는 제거했다. Gateway에 남은 관련 권한 규칙은 이 업무를 다시 활성화하지 않는다.
- Order의 구매자는 JWT sub이며 MEMBER 역할이 필요하다. 본문·X-Member-Id로 선택하지 않는다. 타인의 주문 상세는 404이고 목록은 본인 주문만 반환한다. 취소·결과 주입 API는 제공하지 않는다. 미완료 Payment·License·Download의 기존 member_id·403·환불 계약은 새 Order의 기준이 아니다. Gateway의 X-Member-Id 제거는 유지한다.
- 공개 상품 상세는 출시된 상품만 반환한다. Catalog MVP에는 출시 전 상품 조회 API가 없으며 공통 `/api/v1/ops/**`도 HTTP로 개방하지 않는다.

## PG 콜백

`POST /api/v1/payments/callback`은 사용자 JWT 대신 PG 서명을 검증한다. `PgCallbackVerifier` 포트로 사업자별 구현을 교체할 수 있다. 현재 로컬 어댑터의 계약은 다음과 같다.

| 항목 | 값 |
|---|---|
| `X-Pg-Timestamp` | Unix epoch 초, 서버 시각과 5분 이내 |
| `X-Pg-Signature` | `HMAC-SHA256(secret, ASCII(timestamp) + "." + raw_body)`의 소문자 16진수 |
| 본문 | 최대 64 KiB의 원본 JSON 바이트 |
| 비밀 | `PG_CALLBACK_SECRET`; 운영 프로필에서 로컬 기본값 사용 금지 |

서명 누락·변조·만료는 `401`이다. 검증된 콜백도 사전등록 `pgTxId`와 승인 금액이 일치해야 한다. 브라우저의 성공 화면은 결제 승인 근거가 아니다. [공급자 어댑터 계약](api-demo.md#공급자-어댑터-계약)을 함께 따른다.

실제 PG 연동은 [후속 요구사항 #82](https://github.com/tlswltjq/esd-platform/issues/82)에 정의돼 있다. 공급자 테스트 채널·결제창, 거래 조회·웹훅 검증, 취소 연동과 스모크 결과가 필요하다. 현재 저장소의 로컬 mock·시뮬레이터는 실거래 연동을 대신하지 않는다.

## 감사와 검증

Auth의 `role_audit_log`는 가입·부트스트랩 권한 부여를, Payment의 `payment_audit_log`는 환불 요청·완료와 주문·회원·사유를 남긴다.

`./gradlew test`, Docker가 있는 환경의 `./gradlew integrationTest`, 전체 스택의 `./scripts/remote.sh e2e`로 검증한다. E2E는 타 회원 토큰, 위조 헤더·본문, 무서명 콜백, 일반 회원의 정산 접근을 확인한다. API 계약이 바뀌면 OpenAPI 스냅샷도 함께 갱신한다.
