# 인증·권한 계약

[문서 목록](README.md) · [계정·역할 모델](domain/models/identity.md) · [API 데모](api-demo.md)

Auth가 회원·역할과 서명된 access token을 발행한다. Gateway와 각 서비스는 JWT를 검증하며, 주문 소유권·프로젝트 소유권·다운로드 가능 여부는 담당 서비스가 추가로 확인한다.

## 계정과 사용자 API

- `POST /api/v1/auth/signup/member`는 `MEMBER` 계정을 만든다. 창작자 가입 계정에는 `MEMBER`와 `CREATOR` 역할을 준다. 운영자와 심사자 계정은 부트스트랩 설정을 사용한다.
- OAuth2 Authorization Code + PKCE에서 창작·심사는 `studio`, 커머스는 `commerce` 범위를 요청한다. access token의 `member_id`는 Auth 회원 ID, `roles`는 계정 역할이다. ID token은 API 자격증명이 아니다.
- Gateway와 서비스는 서명·발행자·`esd-api` audience를 검증하고 기본 거부 정책을 적용한다. 주문·결제·라이브러리·다운로드에는 `MEMBER`, 운영자 정산·`/api/v1/ops/**`에는 `ADMIN`이 필요하다. 창작자 자신의 정산을 보는 `/api/v1/settlements/me/**`는 `CREATOR`와 소유권을 확인한다. 공개 상품·스토어 읽기는 로그인 없이 제공한다.
- Catalog MVP는 공개 상품 목록·상세 GET만 제공하며 쓰기 API를 제공하지 않는다. 기존 할인·프로모션 업무는 제거했다. Gateway에 남은 관련 권한 규칙은 이 업무를 다시 활성화하지 않는다.
- Order의 구매자는 JWT sub이며 MEMBER 역할이 필요하다. 본문·X-Member-Id로 선택하지 않는다. 타인의 주문 상세는 404이고 목록은 본인 주문만 반환한다. 취소·결과 주입 API는 제공하지 않는다. 재작성 전 License·미완료 Download의 member_id·403·환불 계약은 새 Order의 기준이 아니다. Gateway의 X-Member-Id 제거는 유지한다.
- 공개 상품 상세는 출시된 상품만 반환한다. Catalog MVP에는 출시 전 상품 조회 API가 없으며 공통 `/api/v1/ops/**`도 HTTP로 개방하지 않는다.

## License MVP 경계

내 라이브러리 두 GET은 MEMBER·검증된 JWT sub를 사용한다. 미보유·타인 404, 익명 401, 역할 없음 403이다.
지급·회수·운영 HTTP API는 닫고 조회로 권한을 생성하지 않는다. 주문·결제 연결 ID와 구매 근거는 응답에 노출하지 않는다.
OrderPaid의 봉투·본문·키·값을 mapper에서 검증한다. 내부 PaidOrder의 생성 검증과 기존 입력 전체 비교를 거쳐 출처를 포함한 License·Inbox를 함께 저장한다.
오류는 공통 재시도·DLT에 남기며 자동 환불 이벤트를 만들지 않는다. 구 Outbox 릴레이는 비활성화한다.

## Payment MVP 경계

Payment 목록·상세는 MEMBER와 서명·issuer·audience가 검증된 JWT sub로 제한한다. 타인·없는 결제는 404다.
목록의 임의 buyerSubject·memberId와 X-Member-Id는 조회 소유자를 바꾸지 않는다.
콜백·prepare·승인 주입·환불 API는 없으며 Gateway의 구 콜백 익명·CSRF 예외도 제거했다.

OrderPlaced의 봉투·본문 ID·종류·키, 가격 일관성과 상품·구매자 필드를 저장 전에 검증한다.
중복 수신에서도 전체 입력을 비교하고 Inbox는 결제 접수와 함께 저장한다.
PG 키·주문·청구액·통화·공급자가 일치하는 확정 결과만 반영하고 결과·Outbox는 함께 커밋한다.
결제 기록과 공급자 원장의 권한 분리는 이번 대역의 외부 PG 격리를 의미하지 않는다.

실제 PG·결제창·웹훅 서명·카드 정보·환불은 [후속 범위](payment-mvp-plan.md)다.
과거 HMAC 콜백과 PG_CALLBACK_SECRET은 현재 Payment의 실행 계약이 아니다.

## 감사와 검증

Auth의 role_audit_log는 가입·부트스트랩 권한 부여를 남긴다.
Payment는 원래 주문 입력, 공급자 결정과 최종 결과 이벤트 메타데이터를 보존한다. 구 payment_audit_log는 새 흐름에서 쓰지 않는다.

빌드·테스트는 OCI에서 실행한다. [Payment 검증](payment-guide.md#oci-검증)은 실제 서명 JWT, 소유권, 중복·충돌·DLT, 독립 PG 커밋·응답 유실·롤백과 8개 앱의 재시작을 확인한다.
API 계약이 바뀌면 OpenAPI 스냅샷도 함께 갱신한다.
