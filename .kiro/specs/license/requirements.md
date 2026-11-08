# 요구사항: License (이용 권한·구매 근거·내 라이브러리)

## 개요

License는 결제 완료된 주문을 근거로 구매 근거(보관함 항목)를 만들고, 구매자가 보관함 항목을 라이브러리에 등록해 영구 이용 권한(보유권)을 얻는 흐름과 보관함·라이브러리 조회를 담당한다([결정 89~98](../../../docs/decisions.md#purchase-blocked-only-by-sale-ban)).
구매 근거·보유권의 정본(상태와 정지·복구·환불 완료 이력)은 License가 소유하며, Download가 상세의 ACTIVE를 확인해 다운로드를 제공한다.
기준은 재작성 완료된 Auth·Gateway·Studio·Review·Catalog·Store·Order·Payment의 계약이다.
MVP와 통합(V5) 재작성은 2026-10-17 완료, 정지·복구와 Order 지급 확인·기존 이벤트 운영 복구(P3 A)까지 검증을 마쳤다.

## 용어

- **buyerSubject**: Auth JWT sub에서 유래한 구매자 식별자. 본문·쿼리·헤더로 선택하지 않는다.
- **productId / productCode**: 주문 당시 Catalog가 부여한 상품 ID와 상품 코드. 둘 다 정확히 비교한다.
- **구매 근거(보관함 항목)**: 결제 완료 주문 1건의 불변 스냅샷. OrderPaid 입력 전체를 `product_license`에 보존한다. 공개 식별자 `licenseId`는 주문 1건에 하나다. 상태는 `STORED`(보관함)·`REGISTERED`(등록됨)·`SUSPENDED`(환불 정지, 이전 상태 기억)·`REFUNDED`(환불 완료, 종료).
- **보유권(License 등록)**: 구매 근거 하나를 라이브러리에 등록한 한 번의 영구 이용 권한. 등록마다 `license_registration`에 새 행이 생긴다(결정 95). 상태는 `ACTIVE`·`SUSPENDED`·`ENDED`.
- **OrderPaid**: Order가 결제 완료 결과를 자기 주문과 대조해 최초 PAID를 반영했다는 사실을 담아 발행하는 이벤트.
- **Inbox**: `(eventId, consumerGroup)` 기준 수신 멱등 가드. 소비 그룹은 `license-paid-order`다.
- **쓸 수 있는 보유권**: `ACTIVE` 보유권. 고객·상품당 최대 1개다.

## 요구사항

### R1. 결제 완료 주문의 구매 근거(보관함 항목) 생성
**사용자 스토리:** 구매자로서, 결제가 끝난 주문의 상품이 내 보관함에 들어오기를 바란다. 그래야 라이브러리에 등록하거나 남겨 둘 수 있다.

#### 수용 기준
1. WHEN 유효한 `OrderPaid`를 수신하면 THEN 시스템 SHALL 주문 1건당 구매 근거 한 개를 `STORED`로 만들고 `LicenseGranted`(구매 근거 생성 사실, Order의 지급 확인)를 같은 트랜잭션의 Outbox로 발행한다. 보유권은 만들지 않는다(결정 93).
2. WHEN 구매 근거를 만들면 THEN 시스템 SHALL 출처(OrderPaid 입력 전체)를 `product_license`의 같은 행에 함께 저장한다.
3. THE 이용 권한 SHALL 만료·기기 수·좌석 제한이 없는 영구 보유권이다.
4. WHEN 권한을 부여하면 THEN 시스템 SHALL 최초 `licenseId`·제목·`acquiredAt`을 확정하고 이후 덮어쓰지 않는다.
5. WHERE PENDING 또는 FAILED 주문의 맥락에서 THE 시스템 SHALL 권한을 부여하지 않는다.
6. IF 신규 입력의 상품·구매자·금액 등 필수 값이 유효하지 않으면 THEN 시스템 SHALL 저장 전에 거부한다.

### R2. 구매 근거의 불변 보존과 출처 통합
**사용자 스토리:** 플랫폼으로서, 각 결제 완료 주문의 구매 근거를 그대로 남기고 싶다. 그래야 분쟁·환불·조사에서 사실을 재구성한다.

#### 수용 기준
1. WHEN 권한을 저장하면 THEN 시스템 SHALL 주문별 불변 구매 근거(OrderPaid의 13개 입력 필드 전체)를 보존한다.
2. THE 시스템 SHALL 권한의 출처를 `product_license` 한 곳에 저장한다(V5 통합). `license_purchase`는 과거 자료로만 보존한다.
3. WHILE 조회를 처리하는 동안 THE 시스템 SHALL Catalog·Order·Payment를 HTTP로 호출하지 않는다.
4. THE 시스템 SHALL 제목·버전·금액·발생 시각을 구매 근거의 스냅샷으로 보존하고 현재 Catalog 값으로 교체하지 않는다.

### R3. 중복과 충돌 판정
**사용자 스토리:** 플랫폼으로서, 같은 이벤트의 재전달과 실제 충돌을 구분하고 싶다. 그래야 중복 지급·근거 위조를 막는다.

#### 수용 기준
1. WHEN 같은 주문의 같은 입력이 재전달되면 THEN 시스템 SHALL 기존 `licenseId`·구매 근거·획득 시각을 유지하고 정상 중복으로 종료한다.
2. IF 같은 주문의 eventId·본문·발생 시각이 변경되면 THEN 시스템 SHALL 충돌로 처리한다.
3. WHEN 다른 주문의 같은 상품(같은 구매자)이 들어오면 THEN 시스템 SHALL 별도 구매 근거로 보관함에 넣는다. 이미 가진 상품인지는 구매가 아니라 등록에서 판단한다(결정 89).
4. IF 다른 주문이 eventId·requestEventId·paymentResultEventId·paymentId를 재사용하면 THEN 시스템 SHALL 충돌로 처리한다.
5. WHEN 다른 구매자가 같은 상품을 구매하면 THEN 시스템 SHALL 그 구매자의 별도 이용 권한을 생성한다.
6. IF Inbox만 있고 일치하는 권한이 없거나, 권한만 있고 Inbox가 없으면 THEN 시스템 SHALL 자료 불일치로 거부하고 정상 중복으로 숨기지 않는다.

### R4. 내 라이브러리 조회 (두 GET)
**사용자 스토리:** 구매자로서, 내가 보유한 상품을 목록과 상세로 보고 싶다. 그래야 무엇을 샀는지 확인한다.

#### 수용 기준
1. WHEN `GET /api/v1/library?page=&size=` 요청이 오면 THEN 시스템 SHALL 검증된 JWT sub 본인의 보유 상품 페이지를 반환한다.
2. WHEN `GET /api/v1/library/{productCode}` 요청이 오면 THEN 시스템 SHALL 본인이 보유한 상품 한 개를 반환한다.
3. WHERE 조회 맥락에서 THE 시스템 SHALL `MEMBER` 역할을 요구하고 서명·issuer·만료·`aud=esd-api`를 검증한다.
4. IF 요청이 익명이면 THEN 시스템 SHALL 401을, 역할이 없으면 403을, 타인/미보유 상품이면 404를 반환한다.
5. THE 시스템 SHALL 본문·쿼리·`X-Member-Id`·`member_id`로 조회 주체를 바꾸지 않는다.
6. THE 목록 SHALL `page ≥ 0`, `1 ≤ size ≤ 100`, `acquiredAt DESC, id DESC` 정렬을 적용하고 본인 자료만 반환한다.
7. THE 상품 응답 SHALL `licenseId`(구매 근거 ID)·`productId`·`productCode`·`title`·`acquiredAt`(등록 시각)·`status`(ACTIVE/SUSPENDED)만 노출한다. 라이브러리는 `ACTIVE`·`SUSPENDED` 보유권만 보여 주고 `ENDED`는 빼며(결정 97), 상세는 같은 상품의 `ACTIVE`가 있으면 그것을, 없으면 가장 최근 `SUSPENDED`를 반환한다. buyerSubject·구매 근거·금액·이벤트 ID·paymentId·Inbox·내부 id는 노출하지 않는다.
8. WHERE 조회 시점에 OrderPaid 전달이 지연 중인 맥락에서 THE 시스템 SHALL 목록에서 제외하거나 상세 404를 반환하되, 조회로 권한을 생성하지 않는다.
9. IF 페이지 값이나 상품 코드 형식이 잘못되면 THEN 시스템 SHALL 400을 반환한다.
10. WHILE Catalog가 중단되었거나 상품 정보가 변경된 동안 THE 시스템 SHALL 저장된 최초 제목·상품 정보로 기존 보유 상품을 조회한다.

### R5. 지급·정지·복구의 트랜잭션 원자성
**사용자 스토리:** 플랫폼으로서, 수신 이벤트의 처리와 결과 발행을 한 트랜잭션으로 묶고 싶다. 그래야 부분 저장·유실이 없다.

#### 수용 기준
1. WHEN `OrderPaid`·`LicenseSuspensionRequested`·`LicenseRestorationRequested`·`PurchaseRefunded`를 수신하면 THEN 시스템 SHALL 각각 구매 근거 생성·정지·복구·환불 완료를 처리한다.
2. WHEN 지급·정지·복구를 처리하면 THEN 시스템 SHALL 업무 변경·Inbox·결과 Outbox(`LicenseGranted`·`LicenseSuspended`·`LicenseRestored`)를 같은 트랜잭션으로 저장한다.
3. THE 시스템 SHALL 수신 이벤트는 `stove.order.v1` 토픽에서 받는다.
4. IF 지급·정지·복구 트랜잭션의 일부가 실패하면 THEN 시스템 SHALL 전체를 롤백하고 공통 Kafka 재시도의 새 트랜잭션에서 재처리한다.
5. WHILE 리스너가 처리하는 동안 THE 시스템 SHALL 트랜잭션 안에 외부 HTTP 호출을 두지 않는다.

### R6. 환불용 상태와 정지·복구 이력
**사용자 스토리:** 플랫폼으로서, 환불 과정의 이용권 정지·복구를 기록하고 싶다. 그래야 다운로드 차단·복원과 이력 조회가 정확하다.

#### 수용 기준
1. THE 정지·복구 대상 SHALL 보유권이 아니라 구매 근거다(결정 94). 등록 전이면 등록을 막고, 등록됐으면 현재 보유권도 `SUSPENDED`로 바꾼다.
2. WHEN 정지·복구를 처리하면 THEN 시스템 SHALL 원래 구매 출처를 유지하고 정지·복구 이력을 보존한다.
3. WHERE 정지된 상품의 맥락에서 THE 시스템 SHALL 목록·상세에 그 상품을 계속 노출한다.
4. WHEN Download가 이용권을 확인하면 THEN 시스템 SHALL 상세의 `ACTIVE` 여부로 응답한다.
5. WHEN `LicenseRestorationRequested`를 처리하면 THEN 시스템 SHALL 같은 고객·상품의 구매 근거를 잠근 뒤 현재 정지와 불변 정지 이력·구매를 대조해 복구한다. 등록 전이었으면 `STORED`로, 등록됐었고 다른 `ACTIVE` 보유권이 없으면 `REGISTERED`·보유권 `ACTIVE`로, 다른 `ACTIVE`가 있으면 옛 보유권을 `ENDED`로 끝내고 `STORED`로 돌려보낸다(결정 96). 복구 이력과 `LicenseRestored.returnedToStorage`에 보관함 반환 여부를 남긴다. 완료된 복구나 예전 정지의 재전달은 이후의 새 정지를 풀거나 되돌리지 않는다.
6. WHEN `LicenseApprovalCorrectionRequested`(PG 송신 전 잘못된 환불 승인 중단)를 받으면 THEN 시스템 SHALL R6-5와 같은 세 갈래로 복구하고 감사·Inbox·`LicenseApprovalCorrected`(returnedToStorage 포함) Outbox를 원자 저장하며, PG 실패나 실행 시도를 만들지 않는다.

### R7. 운영 경계와 지급 실패 처리
**사용자 스토리:** 플랫폼으로서, 지급 실패를 안전하게 보존하고 싶다. 그래야 자동 환불이나 조용한 유실이 없다.

#### 수용 기준
1. THE 시스템 SHALL 지급·회수·결과 주입용 운영 HTTP API를 제공하지 않는다.
2. WHEN 지급 처리가 실패하면 THEN 시스템 SHALL 공통 재시도·DLT로 보존하고 자동 환불을 요청하지 않는다.
3. IF 기술적 지급 오류가 발생하면 THEN 시스템 SHALL Payment FAILED·자동 환불·지급 실패 이벤트를 만들지 않는다.
4. WHEN 원래 이벤트를 재투입하면 THEN 시스템 SHALL 원래 eventId·키·헤더·본문을 보존한다.
5. THE 시스템 SHALL OrderPaid 발행을 도입하기 전에 이미 PAID였던 주문을 자동 소급 지급하지 않는다.
6. WHERE 공통 운영 API(DLT 조회·재투입, Outbox DEAD 건별 재큐잉, `SENT` 건별 재발행)를 명시적으로 활성화한 맥락에서 THE 시스템 SHALL `ADMIN`만 허용하고 MEMBER·익명과 Outbox 일괄 회수를 차단한다. 기본값은 비활성·Gateway 미노출이다.
7. WHEN DLT 재투입을 실행하면 THEN 시스템 SHALL 모든 재발행 결과를 제한 시간 안에 확인하고, 실제 발행한 레코드의 파티션별 다음 오프셋만 커밋한다. 발행 실패·미확정·중단이면 오프셋을 커밋하지 않는다.
8. IF DLT 발행 자체가 실패하면 THEN 시스템 SHALL 그 실패를 예외로 전파하고 성공으로 계측하지 않는다.
9. IF 운영 요청의 토픽이 아직 없거나 DLT 형식이 아니면 THEN 시스템 SHALL 400으로 안내하고, 실제 브로커·발행 오류를 성공이나 빈 목록으로 바꾸지 않는다.
10. WHEN ADMIN이 `POST /api/v1/ops/outbox/{eventId}/republish`(필수 사유 1~500자)를 보내고 그 행이 `SENT`인 `LicenseGranted`이면 THEN 시스템 SHALL 원래 `event_id`·본문·키를 그대로 둔 채 발행 대기로 돌리고 실행자·시각·사유·이전 발행 시각을 `outbox_republish`에 같은 트랜잭션으로 남긴다. 없는 행·`SENT`가 아닌 행은 404, 허용 목록 밖 타입·사유 없음은 400으로 거절하고 행을 바꾸지 않는다. 이미 처리된 `OrderPaid`의 재전달은 기존 구매 근거·Inbox 대조로 효과가 없다([결정 129](../../../docs/decisions.md#orphan-license-grant-republish)).
11. WHEN ADMIN이 License에서 `SENT` `LicenseSuspended`·`LicenseRestored`·`LicenseApprovalCorrected`를 재발행하면 THEN 시스템 SHALL R7.10과 같이 처리한다. License는 자기가 소유한 이벤트(`LicenseGranted`와 이 세 결과)만 재발행하고 Order 소유 타입의 행은 400으로 거절한다. 이미 처리한 `LicenseSuspensionRequested`·`LicenseRestorationRequested`·`LicenseApprovalCorrectionRequested`가 다시 오면 Inbox에서 효과가 없고 결과 이벤트를 다시 내지 않는다. 그래서 결과 이벤트 유실은 License 쪽 결과 행을 재발행해 잇는다(결정 125·129 범위 확장).

### R8. 입력 경계 검증
**사용자 스토리:** 플랫폼으로서, 외부 이벤트를 저장 전에 검증하고 싶다. 그래야 잘못된 근거로 권한이 생기지 않는다.

#### 수용 기준
1. WHEN OrderPaid를 수신하면 THEN 시스템 SHALL 이벤트 종류, 봉투·본문 eventId 일치, Kafka 키 = orderNo를 검증한 뒤 내부 `PaidOrder`로 변환한다.
2. IF 필수 값 누락·ID/금액 범위 오류·잘못된 상품 코드·시각 정밀도 오류·소수의 정수 절삭이 필요한 값이면 THEN 시스템 SHALL 저장 전에 거부한다.
3. THE 시스템 SHALL 발생 시각의 모듈 간 선후관계나 수신 시각만으로 정상 입력을 거부하지 않는다.
4. THE 시스템 SHALL 같은 주문의 입력을 **13개 필드 전체**로 비교하며, Inbox만 먼저 확인해 내용 비교를 생략하지 않는다.
5. THE 구매 시 `contentVersion` SHALL 근거 보존용이며 특정 빌드·버전만 허용하는 조건으로 쓰이지 않는다. 같은 보유권으로 후속 공개본도 이용한다.

### R9. 과거 자료와 전환 경계
**사용자 스토리:** 운영자로서, 구 모델 자료를 잃지 않고 새 모델로 전환하고 싶다. 그래야 과거 근거를 임의로 바꾸지 않는다.

#### 수용 기준
1. THE 시스템 SHALL 구 License Outbox 릴레이를 끄고 `LicenseIssued`·`LicenseIssueFailed`·`LicenseRevoked`를 발행하지 않으며, 구 소비 그룹·구 Inbox를 새 구매 근거로 변환하지 않는다.
2. IF V5 전환 대상에 복수/누락/불일치 출처, 상품·제목 불일치, 누락/고아/다른 유형 Inbox가 있으면 THEN 시스템 SHALL 업무 컬럼 추가 전에 전환을 거부한다.
3. THE 시스템 SHALL 복수 구매 근거를 임의로 한 출처로 축약하거나 PAID 주문을 삭제·실패 처리해 제약을 통과시키지 않는다.
4. WHEN 공유 환경에 전환하기 전 THEN 운영자 SHALL 쓰기를 멈춘 대상 DB에 읽기 전용 점검 SQL을 실행하고 미발행 OrderPaid Outbox·Kafka 미소비·DLT 자료를 대조한다.

### R10. 보관함과 라이브러리 등록
**사용자 스토리:** 구매자로서, 산 상품을 바로 라이브러리에 넣을지 보관함에 둘지 고르고 싶다. 그래야 이미 가진 상품을 다시 사도 나중에 쓸 수 있다(결정 90·93).

#### 수용 기준
1. WHEN `GET /api/v1/storage?page=&size=` 요청이 오면 THEN 시스템 SHALL 본인의 `STORED` 구매 근거와 등록 전에 환불로 정지된 구매 근거를 `licenseId`·`orderNo`·`productId`·`productCode`·`title`·`purchasedAt`·`status`·`registrable`로 반환한다. `REFUNDED`는 뺀다(결정 97).
2. THE `registrable` SHALL `STORED`이고 같은 고객·상품의 `ACTIVE` 보유권이 없을 때만 true다.
3. WHEN `POST /api/v1/storage/{licenseId}/registration` 요청이 오면 THEN 시스템 SHALL 같은 고객·상품의 구매 근거 전체를 id 순서로 잠근 뒤, `STORED`이고 `ACTIVE` 보유권이 없을 때 새 보유권을 `ACTIVE`로 만들고 구매 근거를 `REGISTERED`로 바꾼다.
4. IF 같은 상품의 `ACTIVE` 보유권이 이미 있으면 THEN 시스템 SHALL 409 `PRODUCT_ALREADY_OWNED`로 거절하고 구매분을 보관함에 남긴다.
5. IF 구매 근거가 정지·환불 완료 상태이면 THEN 시스템 SHALL 409 `PURCHASE_NOT_REGISTRABLE`로 거절한다. 이미 등록돼 `ACTIVE`인 같은 구매 근거의 재요청은 그 보유권을 그대로 반환한다.
6. THE 시스템 SHALL `ACTIVE` 보유권을 고객·상품당 1개로 DB 유일 제약으로도 막는다.
7. WHERE 이전 구매의 환불이 끝나지 않은 맥락에서도 THE 시스템 SHALL 새 구매분의 등록을 허용한다(결정 96).
8. THE 보관함·등록 API SHALL R4-3~5의 인증·소유 규칙을 따르고 타인·미존재 구매 근거는 404다.

### R11. 환불 완료
**사용자 스토리:** 플랫폼으로서, 환불이 끝난 구매가 다시 살아나지 않기를 바란다. 그래야 지각 이벤트가 새 보유권을 건드리지 않는다.

#### 수용 기준
1. WHEN Order의 `PurchaseRefunded`(`stove.order.v1`, 소비 그룹 `license-purchase-refund`)를 받으면 THEN 시스템 SHALL 구매 근거·구매자·상품·결제와 현재 정지 요청 ID를 대조해 구매 근거를 `REFUNDED`로, 현재 보유권을 `ENDED`로 끝내고 Inbox와 함께 저장한다.
2. THE `REFUNDED` 구매 근거 SHALL 이후 등록·정지·복구로 바뀌지 않는다. 같은 환불 완료의 재전달은 아무것도 바꾸지 않는다.
3. THE 모든 정지·복구·환불 완료 처리 SHALL 주문번호·구매 근거 ID로 대상을 찾는다. 이전 주문의 이벤트는 다른 주문의 보유권을 바꾸지 않는다.

## 범위 밖(후속)

- 선물·기간제·구독·체험판·양도·좌석/기기 권한, 외부 CD키·DRM. 보관함은 선물을 위한 구조지만 선물 자체는 후속이다.
- 이미 받은 파일의 회수.
- 보관함 반환·환불 결과의 고객 알림(결정 98). 알림 기능은 업무 이벤트 구독 방식으로 별도 설계한다.
- DB 유실 뒤 전량 재구축, 전용 운영 API·스케줄러·성능 실험.
