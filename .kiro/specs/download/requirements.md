# 요구사항: Download (보유권·현재 공개 빌드 확인과 ZIP 만료 URL 발급)

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다. Download가 나오는 플랫폼 행: [P3.8](../platform/requirements.md)(흐름 1 끝의 다운로드 티켓), [P3.18-4](../platform/requirements.md)(정지 중 새 URL 차단), [P1.4-7](../platform/requirements.md)(다운로드 제한의 정본은 Catalog), design "동기 의존" 표의 Download → Catalog·Download → License 두 행, 흐름 1 단계 S10과 S10 실패 행 6개, 정합 조건 RI-6.

## 개요

Download는 MEMBER가 **자기가 보유한 상품의 현재 공개 전체 ZIP 빌드**를 내려받을 수 있도록 만료 서명 URL(티켓)을 발급한다. 요청마다 License에서 보유권을, Catalog 내부 빌드 API에서 현재 공개본과 다운로드 제한 판정을 읽고, 객체 저장소를 읽기 전용 자격으로 확인한 뒤 서명한다.

Download는 **무상태**다. 업무 DB·영속 엔티티·Kafka 소비/발행·Inbox·Outbox·예약 작업·운영자 명령이 없고, 보유권·공개본·제한의 사본이나 조회 캐시·발급 이력을 두지 않는다. 그래서 이 모듈에는 상태 모델이 없고, 계약은 "요청 하나를 어떤 순서로 무엇과 대조해 어떤 응답으로 끝내는가"의 판정 표 하나다([design "상태 모델"](design.md#상태-모델)).

## 용어

- **티켓(ticket)**: `GET /api/v1/downloads/{productCode}/ticket`의 성공 응답. `productCode`·`version`·`fileSize`·`checksum`·`downloadUrl`·`expiresAt` 여섯 값.
- **보유권**: License `license_registration` 한 행(`ACTIVE`·`SUSPENDED`·`ENDED`, 결정 95). Download는 License 라이브러리 상세(`GET /api/v1/library/{productCode}`)가 돌려준 상태 값만 본다. 정본은 License다([design "정본 소유"](../platform/design.md) "구매 근거·보유권 상태" 행).
- **열린 보유권 없음**: 라이브러리 상세가 `404`인 경우. 구매 근거가 없음, 구매 근거가 보관함(`STORED`)에만 있고 등록하지 않음(결정 93), 환불 완료로 `ENDED`(결정 97)가 모두 여기에 든다. Download는 이 셋을 구별할 입력을 받지 못한다([결정 필요 M-DL-1]).
- **현재 공개본**: Catalog `catalog_product.current_release_request_id`가 가리키는 `COMPLETED` `catalog_release`의 불변 스냅샷(전체 ZIP 하나). 정본은 Catalog다(RI-3).
- **다운로드 제한**: Catalog `catalog_product.download_restricted`가 정본인 상품 단위 제한([P1.4-7](../platform/requirements.md), 결정 50). 저장소 객체의 차단 표시는 Catalog가 정본에서 만든 적용 사본이다.
- **만료 서명 URL**: 객체 저장소가 서명한 유효기간 있는 GET URL. 만료 전에는 가진 사람이 반복 사용·공유할 수 있다(일회용 아님, 결정 29 "URL 회수 범위").
- **서비스 키**: Catalog 내부 빌드 GET을 보호하는 Download 전용 키(헤더 `X-Download-Service-Key`, [P1.2-3](../platform/requirements.md)). Gateway·공개 OpenAPI에 노출하지 않는다.

## 요구사항

### R1. 본인 확인과 접근 권한
**사용자 스토리:** 구매자로서, 로그인한 내 자격으로만 다운로드 티켓을 요청하고 싶다. 그래야 내가 산 것만 받는다.

#### 수용 기준
1. WHEN `GET /api/v1/downloads/{productCode}/ticket` 요청이 오면 THEN Download SHALL JWT의 서명·issuer·만료·`esd-api` audience와 `MEMBER` 역할을 검증한다.
2. THE Download SHALL 요청자를 검증된 JWT `sub`로 식별한다.
3. IF 요청이 `X-Member-Id`·`member_id`·별도 buyer 값으로 사용자를 고르려 하면 THEN Download SHALL 그 값을 쓰지 않는다.
4. IF JWT가 없거나 서명·issuer·audience가 틀리거나 만료됐으면 THEN Download SHALL `401`로 거절하고 License·Catalog·저장소를 부르지 않는다.
5. IF 역할에 `MEMBER`가 없으면 THEN Download SHALL `403`으로 거절하고 License·Catalog·저장소를 부르지 않는다.

### R2. 요청 입력 계약
**사용자 스토리:** 클라이언트로서, 정해진 단일 입력만으로 티켓을 요청하고 싶다. 그래야 동작이 예측 가능하다.

#### 수용 기준
1. THE `productCode` SHALL `[A-Za-z0-9][A-Za-z0-9._-]{0,49}` 형식이며 Download SHALL 원문(대소문자 포함)을 그대로 License·Catalog 조회와 대조에 쓴다.
2. IF 상품 코드 형식이 틀리면 THEN Download SHALL `400`(`INVALID_REQUEST`)으로 거절하고 외부를 부르지 않는다.
3. IF 쿼리 파라미터(구 `platform`·`architecture`·`fromVersion` 포함)가 하나라도 있으면 THEN Download SHALL `400`(`INVALID_REQUEST`)으로 거절하고 외부를 부르지 않는다.
4. THE 업무 경로 SHALL 위 GET 하나뿐이고, 나머지 경로·메서드는 기본 거부한다(운영 관측 경로 `/actuator/health`·`/actuator/prometheus`와 API 문서 경로만 예외).

### R3. 보유권 확인 (License 조회)
**사용자 스토리:** 구매자로서, 실제로 보유한 상품만 받고 싶다. 그래야 미보유·정지 상품은 받을 수 없다.

#### 수용 기준
1. WHEN 본인 확인과 입력 검증이 끝나면 THEN Download SHALL 검증한 **원래 사용자 access token**으로 License 라이브러리 상세(`GET /api/v1/library/{productCode}`)를 조회한다(design "동기 의존" Download → License 행).
2. THE Download SHALL 원래 사용자 토큰을 설정된 License 주소에만 보내고, Catalog·저장소에는 보내지 않는다.
3. THE Download SHALL 라이브러리 상세의 상태가 명시적 `ACTIVE`일 때만 다음 단계로 간다(결정 96: Download는 `ACTIVE` 보유권만 권한으로 본다).
4. IF License가 계약된 오류 본문(`success=false`, 오류 코드 `NOT_FOUND`)의 `404`를 돌려주면(열린 보유권 없음 — 구매 근거 없음·보관함 미등록·환불 완료) THEN Download SHALL `403`으로 거절하고 Catalog·저장소를 부르지 않는다. 늦은 취소 회수로 `ACTIVE` 보유권이 끝난 경우도 같다(1회차 지적 A2·B2, 판정 전 — Download는 License 응답만 보고 회수 경로를 구별하지 않는다).
5. IF 라이브러리 상세의 상태가 `SUSPENDED`이면 THEN Download SHALL `403`으로 거절하고 Catalog·저장소를 부르지 않는다([P3.18-4](../platform/requirements.md)).
6. IF License가 사용자 토큰에 `401`·`403`을 돌려주면 THEN Download SHALL 각각 `401`·`403`으로 끝낸다. 이 전달이 맞는지는 [결정 필요 M-DL-2]다.
7. THE Download SHALL 다른 사용자의 보유권을 조회하지 않는다.
8. WHERE Order 주문이 `PAID`여도 License에 구매 근거가 아직 없거나(구매 근거 생성 사실 `PurchaseRecordStored` 전, 결정 175 — 1회차 지적 B9, 판정 전) 고객이 보관함 항목을 등록하지 않았으면 THE Download SHALL 열린 보유권 없음(R3.4)으로 처리하고, Order·Payment를 조회해 임시 권한을 만들지 않는다.
9. (새) IF License의 `404` 본문이 R3.4의 계약 오류 본문이 아니면(본문 없음·형식 오류·다른 오류 코드) THEN Download SHALL 열린 보유권 없음으로 바꾸지 않고 `503`으로 끝낸다(P1.2-5, R9.3).
10. (새) IF 라이브러리 상세 `200`의 상태가 없거나 `ACTIVE`·`SUSPENDED`가 아닌 값(`ENDED`·알 수 없는 값·대소문자 다른 값)이면 THEN Download SHALL `503`으로 끝낸다. 라이브러리 상세는 열린 보유권만 돌려주는 계약이므로 그 밖의 값은 계약 위반이다(License와 맞출 경계, PR 본문).
11. (새) THE `403` 거절의 오류 코드 구분(열린 보유권 없음 R3.4 · 정지 R3.5 · 다운로드 제한 R5.1, 열린 보유권 없음 안의 보관함 미등록) SHALL [결정 필요 M-DL-1]을 따른다. 결정 전에는 R3.4·R3.5가 같은 `FORBIDDEN`(메시지만 다름)이고 R5.1만 `PRODUCT_DOWNLOAD_RESTRICTED`다.

### R4. 현재 공개 빌드 확인 (Catalog 내부 조회)
**사용자 스토리:** 구매자로서, 지금 공개된 전체 빌드 하나를 받고 싶다. 그래야 미출시·심사 중 빌드를 받지 않는다.

#### 수용 기준
1. WHEN 보유권이 `ACTIVE`로 확인되면 THEN Download SHALL Catalog 내부 빌드 GET(`/internal/v1/products/{productCode}/build`)을 **서비스 키로만** 조회한다(design "동기 의존" Download → Catalog 행).
2. THE Download SHALL Catalog가 돌려준 현재 공개본(`COMPLETED`)의 전체 ZIP 빌드만 서명하고, 미출시 작업본·심사 중 빌드를 받거나 고르지 않는다.
3. THE Download SHALL 요청 코드 = License 코드 = Catalog 코드이고 License `productId` = Catalog `productId`일 때만 서명 단계로 간다(RI-6).
4. IF Catalog가 계약된 오류 본문(`success=false`, 오류 코드 `NOT_FOUND`)의 `404`를 돌려주면(현재 공개 상품 없음) THEN Download SHALL `404`로 거절하고 저장소를 부르지 않는다.
5. IF Catalog가 서비스 키를 거절하거나(`401`) 상품 ID·코드가 맞지 않거나 저장 참조가 계약 밖이면 THEN Download SHALL `503`으로 끝내고 저장소 확인·서명을 하지 않는다.
6. WHEN 현재 참조를 읽은 뒤 더 높은 공개본이 확정되어도 THEN Download SHALL 처음 읽은 불변 공개본 값으로 한 응답을 완성한다([P3.8-2](../platform/requirements.md), S10 "동시 진행" 행).
7. (새) IF Catalog의 `404` 본문이 R4.4의 계약 오류 본문이 아니면 THEN Download SHALL 현재 공개 상품 없음으로 바꾸지 않고 `503`으로 끝낸다(P1.2-5, R9.3).

### R5. 상품 다운로드 제한 반영
**사용자 스토리:** 운영자로서, 판매 상태와 별개로 특정 상품의 다운로드를 막고 싶다. 그래야 기존 보유자에게도 새 발급을 거절할 수 있다.

#### 수용 기준
1. WHEN Catalog 내부 빌드 GET이 `403`과 계약된 오류 본문(`success=false`, 오류 코드 `PRODUCT_DOWNLOAD_RESTRICTED`)을 돌려주면 THEN Download SHALL `403`(`PRODUCT_DOWNLOAD_RESTRICTED`)으로 거절한다. 제한인지는 Catalog가 정본(`download_restricted`)과 저장소 적용 상태(`download_storage_status`)로 판정하고(제한 해제 뒤 적용 확인 전에도 제한으로 응답 — Catalog 규칙), Download는 다시 판정하지 않는다([P1.4-7](../platform/requirements.md)).
2. WHERE 다운로드 제한으로 거절하면 THE Download SHALL 저장소 확인·서명을 하지 않는다.
3. IF 그 밖의 Catalog `403`(본문 없음·형식 오류·다른 오류 코드)이면 THEN Download SHALL `503`으로 끝낸다.
4. THE Download SHALL 판매 상태(판매자 중지·관리자 판매 제한, 결정 48)·노출 설정(결정 51·52)을 다운로드 판단에 쓰지 않는다.
5. THE 다운로드 제한 SHALL 현재 전체 ZIP이 업데이트 경로이므로 업데이트 파일 발급에도 적용된다.
6. WHEN Catalog가 제한을 저장소에 적용했다고 확인하면(`APPLIED`) THEN 이미 발급한 URL의 새 GET SHALL 저장소의 Download 읽기 자격 거절로 실패한다. 적용 확인 전·해제 확인 뒤의 만료 전 URL은 다시 쓰일 수 있다(결정 50). 저장소 차단 방식은 design "외부 연동과 adapter 메모"에 둔다.
7. THE Download SHALL 제한을 위해 업무 DB·Kafka·저장소 쓰기 권한을 더하지 않으며, 이미 시작한 전송의 강제 종료·받은 파일의 회수·전역 즉시 차단을 보장하지 않는다(결정 50).

### R6. 저장소 확인과 티켓 발급
**사용자 스토리:** 구매자로서, 실제로 있는 파일의 서명 URL을 받고 싶다. 그래야 잘못된 파일로 안내받지 않는다.

#### 수용 기준
1. THE Download SHALL Catalog 공개본의 저장 참조(`storagePath`)를 설정된 빌드 버킷 안의 객체 키로만 해석하고, 임의 주소 접속·경로 추측을 하지 않는다.
2. IF 저장 참조가 설정된 빌드 버킷 밖이거나 객체 키가 비어 있으면 THEN Download SHALL `503`으로 끝내고 저장소를 부르지 않는다.
3. WHEN 서명 전이면 THEN Download SHALL 읽기 전용 자격으로 객체 메타데이터를 조회해 존재·크기를 확인한다.
4. IF 객체가 없거나 크기가 공개 스냅샷 `fileSize`와 다르면 THEN Download SHALL `409`로 거절하고 다른 빌드로 바꾸지 않는다.
5. WHEN 확인이 끝나면 THEN Download SHALL 그 객체의 만료 서명 URL을 만든다.
6. THE URL 유효기간 기본값 SHALL 5분이며 설정 허용 범위 SHALL 1초~15분이다.
7. THE `expiresAt` SHALL 서명 결과가 돌려준 실제 만료 시각이다.
8. THE `checksum` SHALL 공개 스냅샷의 SHA-256이며, Download SHALL 요청마다 파일 전체를 읽어 해시를 다시 계산하지 않는다.
9. IF 저장소가 응답하지 않거나 자격을 거절하거나 통신 오류가 나면 THEN Download SHALL `503`으로 끝내고 URL을 발급하지 않는다.
10. THE HTTP 연동 SHALL 리다이렉트를 따라가지 않아 사용자 토큰·서비스 키를 다른 주소로 보내지 않는다.
11. THE Download SHALL 기동 때 버킷·파일을 만들지 않는다. 계정·버킷 준비는 실행 환경의 책임이다.
12. THE Download SHALL 메타데이터 확인과 실제 GET 사이의 삭제·같은 크기 내용 변조를 원자적으로 막지 않는다. 공개 파일을 고유 객체 키에 불변 보존하는 것은 저장소 운영 조건이며, 클라이언트가 내려받은 뒤 크기·SHA-256을 대조한다(S10 "동시 진행" 행).

### R7. 응답 계약
**사용자 스토리:** 클라이언트로서, 다운로드에 필요한 최소 정보만 담긴 캐시 금지 응답을 받고 싶다. 그래야 민감 정보가 새거나 캐시되지 않는다.

#### 수용 기준
1. THE 성공 응답 SHALL `ApiResponse` 안에 `productCode`·`version`·`fileSize`·`checksum`·`downloadUrl`·`expiresAt` 여섯 값만 담는다.
2. THE Download SHALL 구매 출처·`buyerSubject`·저장 자격증명·내부 출시/심사/빌드 ID·별도 저장 경로 값을 응답에 넣지 않는다.
3. THE 성공 응답 SHALL `Cache-Control: no-store`를 단다.
4. THE Download SHALL 사용자 access token·서비스 키·서명 URL을 애플리케이션 로그·오류 응답·검증 요약에 남기지 않는다. 예상하지 못한 예외의 메시지에 들어 있어도 같다.

### R8. URL 수명과 반복 사용
**사용자 스토리:** 구매자로서, 받은 URL을 만료 전까지 다시 쓰고 싶다. 그래야 끊기면 처음부터 다시 시작하지 않아도 된다.

#### 수용 기준
1. THE 만료 서명 URL SHALL 만료 전 가진 사람이 반복 GET할 수 있다.
2. THE Download SHALL URL을 일회용·JWT `sub` 결박·공유 차단으로 보장하지 않는다.
3. THE 발급 SHALL 보유권을 소모하지 않으며, Download SHALL 반복 요청·만료 뒤 재요청을 허용한다(S10 "중복 전달" 행).
4. THE Download SHALL 발급 이력을 저장하지 않는다.
5. WHEN URL이 만료되면 THEN 만료 SHALL 새 GET에만 적용되며, 이미 시작한 전송의 강제 종료나 보유권의 만료를 뜻하지 않는다.
6. WHILE License·Catalog가 중단됐거나 보유권이 `SUSPENDED`로 바뀐 동안 THE Download SHALL 새 URL을 발급하지 않되, 이미 발급한 URL은 저장소·파일이 유지되는 동안 만료 전까지 쓰일 수 있다(결정 29 "URL 회수 범위", S10 "상대 상태가 이미 바뀜" 행).

### R9. 무상태·장애 처리
**사용자 스토리:** 운영자로서, Download가 다른 모듈의 상태를 베끼지 않고 요청마다 순서대로 확인하길 바란다. 그래야 장애 중 잘못된 발급이 없다.

#### 수용 기준
1. THE Download SHALL 업무 DB·영속 엔티티·Kafka 소비/발행·Inbox·Outbox·예약 작업·운영자 명령 없이 동작하고, 보유권·공개본·제한의 사본·조회 캐시를 두지 않는다([P3.8-2](../platform/requirements.md)).
2. THE Download SHALL 요청마다 License → Catalog → 저장소 순서로 확인하고, 앞 단계가 통과하지 않으면 뒤 단계를 부르지 않는다.
3. IF License·Catalog가 제한 시간 초과·연결 실패·리다이렉트·5xx·계약 밖 상태 코드·형식이 틀린 성공 응답을 돌려주면 THEN Download SHALL `503`으로 끝내고 미보유·없는 상품으로 바꾸지 않는다([P3.8-3](../platform/requirements.md), P1.2-5).
4. THE Download SHALL HTTP·저장소 호출마다 유한한 제한 시간을 두고(HTTP 연결 2초·읽기 5초, 저장소 호출 전체 5초) HTTP 재시도·배경 작업을 두지 않는다. 저장소 클라이언트의 자체 재시도는 전체 제한 시간 안에서만 일어난다.
5. THE Download SHALL 이벤트를 받거나 내지 않으며, 발급·실패·재시작이 주문·보유권·공개본·제한을 바꾸지 않는다.
6. IF License·Catalog 주소, Catalog 서비스 키, 읽기 전용 저장소 자격·버킷이 없거나 공백이거나 URL 유효기간·주소가 틀리면 THEN Download SHALL 기동을 거절하고 관리자 계정·공용 기본 키로 대신하지 않는다.
7. WHEN Download가 재시작되면 THEN Download SHALL 사본 복구·동기화 없이 바로 발급을 다시 받는다.
8. (새) IF JWT issuer·JWK 주소가 설정되지 않았으면 THEN Download SHALL 기동을 거절하고 로컬 개발 주소로 대신하지 않는다(원칙 ⑦, 결정 86).

## 범위 밖(후속)

- 과거 버전 선택·패치 이력·델타 패치·OS/아키텍처별 빌드·채널·예약 배포·롤백.
- 다운로드 이력·횟수 제한·완료 이벤트·재개 위치 관리·운영자 발급 API.
- DRM·기기/좌석 제한·일회용 URL·공유 차단·사용자별 CDN 토큰·자체 파일 중계.
- 환불·회수·기간제·무료 지급·선물·정산 연계(보유권 상태는 License, 환불 흐름은 [플랫폼 P3.16~P3.27](../platform/requirements.md)).
- License·Catalog 장애 중 계속 발급하기 위한 사본·동기화·다중 인스턴스 조율.
- Order를 조회해 "결제 직후 구매 근거 생성 대기"를 따로 알리는 것(R3.8 — Order 의존을 더하지 않는다).
