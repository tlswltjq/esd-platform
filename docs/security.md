# 인증·권한 계약

[문서 목록](README.md) · [계정·역할 모델](domain/models/identity.md) · [API 데모](api-demo.md)

Auth가 회원·역할과 서명된 access token을 발행한다. Gateway와 각 서비스는 JWT를 검증하며, 주문 소유권·프로젝트 소유권·다운로드 가능 여부는 담당 서비스가 추가로 확인한다.

## 계정과 사용자 API

- `POST /api/v1/auth/signup/member`는 `MEMBER` 계정을 만든다. 창작자 가입 계정에는 `MEMBER`와 `CREATOR` 역할을 준다. 운영자와 심사자 계정은 부트스트랩 설정을 사용한다.
- OAuth2 Authorization Code + PKCE에서 창작·심사는 `studio`, 커머스는 `commerce` 범위를 요청한다. access token의 `member_id`는 Auth 회원 ID, `roles`는 계정 역할이다. ID token은 API 자격증명이 아니다.
- Gateway와 서비스는 서명·발행자·`esd-api` audience를 검증하고 기본 거부 정책을 적용한다. 주문·고객 환불·결제·라이브러리·다운로드에는 `MEMBER`, 관리자 환불 목록/상세/판정에는 `ADMIN`, 운영자 정산·`/api/v1/ops/**`에는 `ADMIN`이 필요하다. 창작자 자신의 정산을 보는 `/api/v1/settlements/me/**`는 `CREATOR`와 소유권을 확인한다. 공개 상품·스토어 읽기는 로그인 없이 제공한다.
- Catalog MVP는 공개 상품 목록·상세와 접근 제한된 내부 빌드 GET을 제공하며 쓰기 API를 제공하지 않는다. 기존 할인·프로모션 업무는 제거했다. Gateway에 남은 관련 권한 규칙은 이 업무를 다시 활성화하지 않는다.
- Order의 구매자는 JWT sub이며 MEMBER 역할이 필요하다. 본문·X-Member-Id로 선택하지 않는다. 타인의 주문 상세는 404이고 목록은 본인 주문만 반환한다. 고객 전액 환불 POST/GET은 본인 주문으로 제한한다. 관리자 환불 GET 목록/상세와 POST 판정만 Gateway/Order 양쪽에서 ADMIN으로 허용하며 판정자는 JWT sub다. 그 외 관리자 경로/메서드와 결과 주입 API는 닫는다. 재작성 전 member_id·환불 계약은 새 Order의 기준이 아니다. Gateway의 X-Member-Id 제거는 유지한다.
- 공개 상품 상세는 출시된 상품만 반환한다. Catalog MVP에는 출시 전 상품 조회 API가 없으며 공통 `/api/v1/ops/**`도 HTTP로 개방하지 않는다.

## License MVP 경계

내 라이브러리 두 GET은 MEMBER·검증된 JWT sub를 사용한다. 미보유·타인 404, 익명 401, 역할 없음 403이다.
지급·회수·운영 HTTP API는 닫고 조회로 권한을 생성하지 않는다. 주문·결제 연결 ID와 구매 근거는 응답에 노출하지 않는다.
OrderPaid의 봉투·본문·키·값을 mapper에서 검증한다. 내부 PaidOrder의 생성 검증과 기존 입력 전체 비교를 거쳐 출처를 포함한 License·Inbox를 함께 저장한다.
오류는 공통 재시도·DLT에 남기며 자동 환불 이벤트를 만들지 않는다. 실제 지급·정지·복구 완료는 원구매와 요청을 대조한 뒤 업무·Inbox·결과 Outbox를 함께 저장한다.

## Download MVP 경계

단일 ticket GET은 MEMBER·서명·issuer·만료·aud=esd-api가 검증된 JWT sub를 사용한다.
원래 사용자 토큰은 고정된 License 주소에만 전달하고 Catalog·저장소로 보내지 않는다. License 404와 SUSPENDED는 접근 거절 403, 연계 장애는 503이다. 명시적 ACTIVE만 새 URL을 발급한다.
Catalog 내부 빌드 GET은 Catalog·Download가 공유하는 명시적 서비스 키만 허용하며 사용자 JWT만으로 접근할 수 없다.
키 미설정 시 닫고 Gateway·공개 OpenAPI에는 노출하지 않는다. 사용자 토큰·키·서명 URL은 애플리케이션 로그에 기록하지 않는다.

요청에서 저장 경로·다른 사용자·플랫폼·버전을 선택하지 않는다. License·Catalog 상품 ID·코드가 일치한 현재 공개 빌드만 서명한다.
저장소 계정은 읽기 전용이고 HEAD 후 GET URL을 발급한다. 발급 응답은 no-store다.
URL은 만료 전 가진 사람이 반복 GET할 수 있으며 일회용·사용자 결박·공유 차단을 보장하지 않는다.
[전체 계약](download-mvp-plan.md) · [실행·오류 처리](download-guide.md)

## Payment MVP 경계

Payment 목록·상세는 MEMBER와 서명·issuer·audience가 검증된 JWT sub로 제한한다. 타인·없는 결제는 404다.
목록의 임의 buyerSubject·memberId와 X-Member-Id는 조회 소유자를 바꾸지 않는다.
본인 PENDING/PORTONE_TEST checkout은 JWT로 보호하고 사용자 환불 요청은 Order로 받는다. 구 콜백·prepare·승인 주입 API와 이전 Gateway의 익명/CSRF 예외는 사용하지 않는다.

OrderPlaced의 봉투·본문 ID·종류·키, 가격 일관성과 상품·구매자 필드를 저장 전에 검증한다.
중복 수신에서도 전체 입력을 비교하고 Inbox는 결제 접수와 함께 저장한다.
PG 키·주문·청구액·통화·공급자가 일치하는 확정 결과만 반영하고 결과·Outbox는 함께 커밋한다.
외부 모의 PG는 별도 프로세스·DB·키를 사용한다. Payment의 승인·조회 키와 Settlement의 조회 키를 분리하고 제어 키는 업무 앱에 제공하지 않는다.

PortOne TEST 연동에서는 설정된 웹훅 POST만 서명·스토어·재전달을 검증해 접수한다. 공개 edge가 Payment에 직접 전달하며 웹훅은 최종 상태 대신 서버 조회 힌트로 사용한다. 실제 비밀값은 Git에 넣지 않는다. [현재 계약·검증](refund-session-handoff.md). 카드 정보 저장이나 실제 운영 자금 이동의 검증은 포함하지 않는다.
과거 HMAC 콜백과 PG_CALLBACK_SECRET은 현재 Payment의 실행 계약이 아니다.

## 감사와 검증

Auth의 role_audit_log는 가입·부트스트랩 권한 부여를 남긴다.
Payment는 원래 주문 입력, 공급자 결정과 최종 결과 이벤트 메타데이터를 보존한다. 구 payment_audit_log는 새 흐름에서 쓰지 않는다.

현재 빌드·테스트는 로컬 OrbStack에서 실행한다. 과거 OCI 기록은 당시 검증 근거로 보존한다. [Payment 검증](payment-guide.md#로컬-orbstack-검증)은 실제 서명 JWT, 소유권, 중복·충돌·DLT, 독립 PG 커밋·응답 유실·롤백과 8개 앱의 재시작을 확인한다.
API 계약이 바뀌면 OpenAPI 스냅샷도 함께 갱신한다.

## Settlement와 외부 모의 PG 경계

Settlement는 sub가 없거나 공백·100자 초과인 JWT도 401로 거절한다. CREATOR의 내 매출·월 합계는 JWT sub로 제한하고 PG 비용·플랫폼 잔여액·다른 판매자 정보를 노출하지 않는다.
ADMIN만 전체 조회·지난 월 마감·PG 명세 수집·재대사를 수행한다. 고객이 PG 금액·수령 상태를 입력할 수 없다.
Catalog 소유자 GET에는 Download와 다른 전용 서비스 키를 사용하며 Gateway에 내부·PG 제어 경로를 추가하지 않는다.
Payment에는 PG 정산 중계 API가 없고 Settlement는 PG 공급자 DB를 직접 읽지 않는다.
PG DB 계정은 PG만 사용한다. 키가 없거나 중복이면 PG가 기동하지 않으며 HTTP 오류·PG 지급 보고를 실제 은행 입금 완료로 해석하지 않는다.
