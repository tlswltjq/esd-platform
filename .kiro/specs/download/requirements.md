# 요구사항: Download (보유권·현재 공개 빌드 다운로드 티켓)

## 개요

Download는 구매자가 **자신이 보유한 상품의 현재 공개된 전체 ZIP 빌드**를 실제로 내려받도록 만료 서명 URL(티켓)을 발급한다.
요청마다 License에서 보유권을, Catalog 내부 빌드 API에서 현재 공개본을 조회하고, 저장소(S3/MinIO)를 읽기 전용으로 확인한다.
업무 DB·MongoDB·Kafka 소비/발행·보유권/공개본 사본을 두지 않는 **무상태 서비스**다.
MVP 재작성은 2026-10-18 로컬 OrbStack에서 완료했다(단위 93건·통합 8건, 실제 열 앱 15단계).

## 용어

- **티켓(ticket)**: `GET /api/v1/downloads/{productCode}/ticket`의 응답. 다운로드에 필요한 메타데이터와 서명 URL을 담는다.
- **보유권(보유 상품)**: License가 관리하는 구매자·상품별 영구 권한. Download는 상세의 `ACTIVE`만 확인한다.
- **현재 공개본**: Catalog 조회 시점에 `CatalogProduct`가 가리킨, 현재 `COMPLETED` 출시의 불변 전체 ZIP 빌드.
- **presigned GET**: 저장소가 서명한 만료 있는 GET URL. 가진 사람이 만료 전 반복 사용·공유할 수 있다(일회용 아님).
- **서비스 키**: Catalog 내부 빌드 GET을 보호하는, Catalog·Download가 공유하는 전용 키(`X-Download-Service-Key`). Gateway·공개 OpenAPI에 노출하지 않는다.

## 요구사항

### R1. 본인 확인과 접근 권한
**사용자 스토리:** 구매자로서, 로그인한 내 자격으로만 다운로드 티켓을 요청하고 싶다. 그래야 내가 산 것만 안전하게 받는다.

#### 수용 기준
1. WHEN `GET /api/v1/downloads/{productCode}/ticket` 요청이 오면 THEN 시스템 SHALL 서명·issuer·만료·`esd-api` audience와 `MEMBER` 역할을 검증한다.
2. THE 시스템 SHALL 요청자를 검증된 JWT `sub`로 식별한다.
3. IF 요청이 `X-Member-Id`·`member_id`·별도 buyer 값으로 사용자를 선택하려 하면 THEN 시스템 SHALL 이를 인정하지 않는다.
4. IF JWT가 없거나 서명·issuer·audience가 틀리거나 만료되었으면 THEN 시스템 SHALL `401`로 거절하고 업무 조회·발급을 하지 않는다.
5. IF 역할에 `MEMBER`가 없으면 THEN 시스템 SHALL `403`으로 거절하고 업무 조회·발급을 하지 않는다.

### R2. 요청 입력 계약
**사용자 스토리:** 클라이언트로서, 정해진 단일 입력만으로 티켓을 요청하고 싶다. 그래야 선택지가 늘지 않고 동작이 예측 가능하다.

#### 수용 기준
1. THE `productCode` SHALL `[A-Za-z0-9][A-Za-z0-9._-]{0,49}` 형식이며 시스템 SHALL 원문을 유지한다.
2. IF 상품 코드 형식이 잘못되면 THEN 시스템 SHALL `400`으로 거절한다.
3. WHERE 쿼리 파라미터(구 `platform`·`architecture`·`fromVersion` 포함)가 있는 맥락에서 THE 시스템 SHALL `400`으로 거절한다.
4. THE 업무 경로 SHALL 위 단일 GET만 허용하고 나머지는 기본 거부한다.

### R3. 보유권 확인 (License 조회)
**사용자 스토리:** 구매자로서, 실제로 내가 보유한 상품만 받고 싶다. 그래야 미보유·지급 전 상품은 받을 수 없다.

#### 수용 기준
1. WHEN 본인 확인이 끝나면 THEN 시스템 SHALL 검증한 **원래 사용자 access token**으로 License의 내 라이브러리 상세(`GET /api/v1/library/{productCode}`)를 조회한다.
2. THE 시스템 SHALL 원래 사용자 토큰을 **고정된 License 주소에만** 전달하고 Catalog·저장소로 보내지 않는다.
3. THE 시스템 SHALL 보유권 상세가 명시적으로 `ACTIVE`인 경우에만 새 URL 발급을 진행한다.
4. IF License가 `404`를 반환하거나 지급 전·미보유이면 THEN 시스템 SHALL `403`으로 거절하고 Catalog·저장소를 조회하지 않는다.
5. IF 이용권이 `SUSPENDED`이면 THEN 시스템 SHALL `403`으로 거절한다.
6. IF License가 사용자 토큰에 `401`·`403`을 반환하면 THEN 시스템 SHALL 같은 인증·권한 오류로 종료한다.
7. THE 시스템 SHALL 타인의 보유권을 추가 조회하지 않는다.

### R4. 현재 공개 빌드 확인 (Catalog 내부 조회)
**사용자 스토리:** 구매자로서, 지금 공개된 전체 빌드 한 개를 받고 싶다. 그래야 미출시본·심사 중 빌드를 받지 않는다.

#### 수용 기준
1. WHEN 보유가 확인되면 THEN 시스템 SHALL Catalog 내부 빌드 GET(`/internal/v1/products/{productCode}/build`)을 **서비스 키로만** 조회한다.
2. THE 시스템 SHALL 현재 `COMPLETED` 공개본의 전체 ZIP 빌드만 사용하고 미출시 작업본·심사 중 빌드를 제공하지 않는다.
3. THE 시스템 SHALL License의 상품 ID·코드와 Catalog의 상품 ID·코드를 대조하고, 요청 코드 = License 코드 = Catalog 코드이며 License `productId` = Catalog `productId`일 때만 발급한다.
4. IF 보유 확인 후 현재 공개 상품이 없으면 THEN 시스템 SHALL `404`로 거절하고 URL을 발급하지 않는다.
5. IF Catalog가 서비스 키를 거절하거나 상품 ID·코드가 불일치하거나 저장 참조가 잘못되었으면 THEN 시스템 SHALL `503`으로 종료하고 저장소·서명 단계를 실행하지 않는다.
6. WHEN 현재 참조를 읽은 뒤 더 높은 공개본이 확정되어도 THEN 시스템 SHALL 처음 선택한 불변 공개본의 값으로 한 응답을 유지한다.

### R5. 상품 다운로드 제한 (독립 제한) 반영
**사용자 스토리:** 운영자로서, 판매 상태와 별개로 특정 상품의 다운로드를 막고 싶다. 그래야 기존 보유자에게도 신규 발급을 거절할 수 있다.

#### 수용 기준
1. WHEN Catalog 내부 빌드 GET이 `403`·`PRODUCT_DOWNLOAD_RESTRICTED`를 반환하면 THEN 시스템 SHALL 이를 상품 다운로드 제한으로 구분해 `403`으로 거절한다.
2. WHERE 상품 다운로드 제한으로 거절하는 맥락에서 THE 시스템 SHALL 저장소 HEAD·서명을 실행하지 않는다.
3. IF 그 밖의 내부 `403`·인증·응답 오류이면 THEN 시스템 SHALL `503`으로 처리한다.
4. THE 시스템 SHALL 판매 제한(판매자 중지·관리자 판매 BLOCKED) 상태를 다운로드 판단에 사용하지 않고, License `ACTIVE` 검사와 상품 ID·코드 대조만 유지한다.

### R6. 저장소 접근과 티켓 발급
**사용자 스토리:** 구매자로서, 실제로 존재하는 파일의 서명 URL을 받고 싶다. 그래야 잘못된 파일로 안내받지 않는다.

#### 수용 기준
1. THE 시스템 SHALL Catalog가 준 `s3://버킷/객체키` 참조를 SDK의 bucket·key 값으로 사용하고 임의 HTTP URL 접속·경로 추측을 하지 않는다.
2. THE 시스템 SHALL 설정된 빌드 버킷과 비어 있지 않은 키만 허용한다.
3. WHEN 발급 전에 THEN 시스템 SHALL 읽기 전용 계정으로 저장소 HEAD를 수행해 객체 존재·파일 크기를 확인한다.
4. IF 객체가 없거나 HEAD 크기가 공개 스냅샷의 `fileSize`와 불일치하면 THEN 시스템 SHALL `409`로 거절하고 다른 빌드로 대체하지 않는다.
5. WHEN HEAD 확인이 끝나면 THEN 시스템 SHALL 해당 객체의 presigned GET을 발급한다.
6. THE URL 유효기간 기본값 SHALL 5분이며, 설정 허용 범위 SHALL 1초~15분이다.
7. THE `expiresAt` SHALL SDK 서명 결과의 실제 expiration을 사용한다.
8. THE `checksum` SHALL 공개 스냅샷의 SHA-256이며 시스템 SHALL 매 요청마다 전체 파일을 다시 읽어 해시를 계산하지 않는다.
9. IF 저장소가 중단되거나 자격증명을 거절하거나 SDK 통신 오류가 나면 THEN 시스템 SHALL `503`으로 거절하고 URL을 발급하지 않는다.

### R7. 응답 계약
**사용자 스토리:** 클라이언트로서, 다운로드에 필요한 최소 정보만 담긴 캐시 금지 응답을 받고 싶다. 그래야 민감 정보가 새거나 캐시되지 않는다.

#### 수용 기준
1. THE 성공 응답 SHALL `ApiResponse` 안에 `productCode`·`version`·`fileSize`·`checksum`·`downloadUrl`·`expiresAt` 여섯 필드를 담는다.
2. THE 시스템 SHALL 구매 출처·`buyerSubject`·저장 자격증명·내부 출시/심사/빌드 ID·별도 `storagePath` 필드를 응답에 추가하지 않는다.
3. THE URL 응답 SHALL `Cache-Control: no-store`를 적용한다.
4. THE 시스템 SHALL 사용자 access token·서비스 키·서명 URL을 애플리케이션 로그와 검증 요약에 남기지 않는다.

### R8. URL 수명과 반복 사용
**사용자 스토리:** 구매자로서, 발급받은 URL을 만료 전까지 다시 쓰고 싶다. 그래야 끊기면 처음부터 다시 시작하지 않아도 된다.

#### 수용 기준
1. THE presigned URL SHALL 만료 전 가진 사람이 반복 GET할 수 있다.
2. THE 시스템 SHALL URL을 일회용·JWT `sub` 결박·공유 차단으로 보장하지 않는다.
3. THE URL 발급 SHALL 보유권을 소모하지 않으며 시스템 SHALL 반복 요청·만료 후 재요청을 허용한다.
4. THE 시스템 SHALL 발급 이력을 저장하지 않는다.
5. WHEN URL이 만료되면 THEN 만료 SHALL 새 GET 요청에만 적용되며 이미 시작한 전송의 강제 종료나 License 영구 보유권의 만료를 뜻하지 않는다.

### R9. 무상태·장애 처리
**사용자 스토리:** 운영자로서, Download가 다른 모듈의 상태를 베끼지 않고 요청마다 순서대로 확인하길 바란다. 그래야 장애 중 잘못된 발급이 없다.

#### 수용 기준
1. THE 시스템 SHALL 업무 DB·저장 엔티티·Kafka 소비/발행·Inbox·Outbox 없이 무상태로 동작하고 보유권·공개본 사본·조회 캐시를 두지 않는다.
2. THE 시스템 SHALL 요청마다 License → Catalog → 저장소 순서로 조회·확인한다.
3. IF License·Catalog가 타임아웃·연결 실패·5xx·형식이 잘못된 성공 응답을 반환하면 THEN 시스템 SHALL `503`으로 처리하고 미보유로 바꾸지 않는다.
4. THE 시스템 SHALL HTTP·SDK 연결·읽기에 유한한 제한 시간을 두고(초기 HTTP 연결 2초·읽기 5초, 저장소 API 전체 호출 5초 기준) HTTP 재시도·배경 작업을 추가하지 않는다.
5. THE 시스템 SHALL 외부 이벤트를 수신·발행하지 않으며 발급·실패·재시작이 주문·보유권·공개본을 변경하지 않는다.

## 범위 밖(후속)

- 과거 버전 선택·패치 이력·델타 패치·OS/아키텍처별 빌드·채널·예약 배포·롤백.
- 다운로드 이력·횟수 제한·완료 이벤트·재개 위치 관리·운영자 발급 API.
- DRM·기기/좌석 제한·일회용 URL·공유 차단·사용자별 CDN 토큰·자체 파일 중계.
- 환불·회수·기간제·무료 지급·선물·정산 연계와 기존 결제의 소급 지급.
- License·Catalog 장애 중 계속 발급하기 위한 사본·동기화·다중 인스턴스 조율.
- **결제/지급 대기·정지·파일 없음의 구분, 업데이트·판매 중지 정책과 기존 보유자 접근의 전체 여정** → 일반화 G4에서 닫음.
