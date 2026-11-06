# 요구사항: License (이용 권한·구매 근거·내 라이브러리)

## 개요

License는 결제 완료된 주문을 근거로 구매자·상품별 영구 이용 권한을 부여하고, 구매자가 자신의 보유 상품을 조회하는 흐름을 담당한다.
권한의 정본(ACTIVE/SUSPENDED와 정지·복구 이력)은 License가 소유하며, Download가 상세의 ACTIVE를 확인해 다운로드를 제공한다.
기준은 재작성 완료된 Auth·Gateway·Studio·Review·Catalog·Store·Order·Payment의 계약이다.
MVP와 통합(V5) 재작성은 2026-10-17 완료, 정지·복구와 Order 지급 확인·기존 이벤트 운영 복구(P3 A)까지 검증을 마쳤다.

## 용어

- **buyerSubject**: Auth JWT sub에서 유래한 구매자 식별자. 본문·쿼리·헤더로 선택하지 않는다.
- **productId / productCode**: 주문 당시 Catalog가 부여한 상품 ID와 상품 코드. 둘 다 정확히 비교한다.
- **보유권(License)**: `(buyerSubject, productId)`별 영구 이용 권한 한 개. 출처 전체를 `product_license` 한 곳에 저장한다(V5 통합).
- **구매 근거**: 권한의 근거가 된 결제 완료 주문의 불변 스냅샷. OrderPaid 입력 전체를 보존한다.
- **OrderPaid**: Order가 결제 완료 결과를 자기 주문과 대조해 최초 PAID를 반영했다는 사실을 담아 발행하는 이벤트.
- **Inbox**: `(eventId, consumerGroup)` 기준 수신 멱등 가드. 소비 그룹은 `license-paid-order`다.
- **ACTIVE / SUSPENDED**: 이용권 상태. 환불용 정지·복구의 대상이다.

## 요구사항

### R1. 결제 완료 주문의 이용 권한 지급
**사용자 스토리:** 구매자로서, 결제가 끝난 주문의 상품을 내 라이브러리에서 이용하고 싶다. 그래야 구매한 상품을 쓸 수 있다.

#### 수용 기준
1. WHEN 유효한 `OrderPaid`를 수신하면 THEN 시스템 SHALL `(buyerSubject, productId)`별 이용 권한 한 개를 부여한다.
2. WHEN 권한을 부여하면 THEN 시스템 SHALL 출처(OrderPaid 입력 전체)를 `product_license`의 같은 행에 함께 저장한다.
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
3. IF 다른 주문의 같은 상품(같은 구매자)이 들어오면 THEN 시스템 SHALL 충돌로 처리한다(V5 통합 후 1:1 모델).
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
7. THE 상품 응답 SHALL `licenseId`·`productId`·`productCode`·`title`·`acquiredAt`·`status`(ACTIVE/SUSPENDED)만 노출한다. buyerSubject·구매 근거·금액·이벤트 ID·paymentId·Inbox·내부 id는 노출하지 않는다.
8. WHERE 조회 시점에 OrderPaid 전달이 지연 중인 맥락에서 THE 시스템 SHALL 목록에서 제외하거나 상세 404를 반환하되, 조회로 권한을 생성하지 않는다.
9. IF 페이지 값이나 상품 코드 형식이 잘못되면 THEN 시스템 SHALL 400을 반환한다.
10. WHILE Catalog가 중단되었거나 상품 정보가 변경된 동안 THE 시스템 SHALL 저장된 최초 제목·상품 정보로 기존 보유 상품을 조회한다.

### R5. 지급·정지·복구의 트랜잭션 원자성
**사용자 스토리:** 플랫폼으로서, 수신 이벤트의 처리와 결과 발행을 한 트랜잭션으로 묶고 싶다. 그래야 부분 저장·유실이 없다.

#### 수용 기준
1. WHEN `OrderPaid`·`LicenseSuspensionRequested`·`LicenseRestorationRequested`를 수신하면 THEN 시스템 SHALL 각각 지급·정지·복구를 처리한다.
2. WHEN 지급·정지·복구를 처리하면 THEN 시스템 SHALL 업무 변경·Inbox·결과 Outbox(`LicenseGranted`·`LicenseSuspended`·`LicenseRestored`)를 같은 트랜잭션으로 저장한다.
3. THE 시스템 SHALL 수신 이벤트는 `stove.order.v1` 토픽에서 받는다.
4. IF 지급·정지·복구 트랜잭션의 일부가 실패하면 THEN 시스템 SHALL 전체를 롤백하고 공통 Kafka 재시도의 새 트랜잭션에서 재처리한다.
5. WHILE 리스너가 처리하는 동안 THE 시스템 SHALL 트랜잭션 안에 외부 HTTP 호출을 두지 않는다.

### R6. 환불용 상태와 정지·복구 이력
**사용자 스토리:** 플랫폼으로서, 환불 과정의 이용권 정지·복구를 기록하고 싶다. 그래야 다운로드 차단·복원과 이력 조회가 정확하다.

#### 수용 기준
1. THE 이용권 SHALL `ACTIVE` 또는 `SUSPENDED` 상태를 가진다.
2. WHEN 정지·복구를 처리하면 THEN 시스템 SHALL 원래 구매 출처를 유지하고 정지·복구 이력을 보존한다.
3. WHERE 정지된 상품의 맥락에서 THE 시스템 SHALL 목록·상세에 그 상품을 계속 노출한다.
4. WHEN Download가 이용권을 확인하면 THEN 시스템 SHALL 상세의 `ACTIVE` 여부로 응답한다.
5. WHEN `LicenseRestorationRequested`를 처리하면 THEN 시스템 SHALL 현재 정지와 불변 정지 이력·구매를 대조해 실제 `ACTIVE`·복구 이력을 저장한다. 완료된 복구나 예전 정지의 재전달은 이후의 새 정지를 풀거나 되돌리지 않는다.
6. WHEN `LicenseApprovalCorrectionRequested`(PG 송신 전 잘못된 환불 승인 중단)를 받으면 THEN 시스템 SHALL 현재 정지와 구매를 확인해 `ACTIVE` 복구·감사·Inbox·`LicenseApprovalCorrected` Outbox를 원자 저장하고, PG 실패나 실행 시도를 만들지 않는다.

### R7. 운영 경계와 지급 실패 처리
**사용자 스토리:** 플랫폼으로서, 지급 실패를 안전하게 보존하고 싶다. 그래야 자동 환불이나 조용한 유실이 없다.

#### 수용 기준
1. THE 시스템 SHALL 지급·회수·결과 주입용 운영 HTTP API를 제공하지 않는다.
2. WHEN 지급 처리가 실패하면 THEN 시스템 SHALL 공통 재시도·DLT로 보존하고 자동 환불을 요청하지 않는다.
3. IF 기술적 지급 오류가 발생하면 THEN 시스템 SHALL Payment FAILED·자동 환불·지급 실패 이벤트를 만들지 않는다.
4. WHEN 원래 이벤트를 재투입하면 THEN 시스템 SHALL 원래 eventId·키·헤더·본문을 보존한다.
5. THE 시스템 SHALL OrderPaid 발행을 도입하기 전에 이미 PAID였던 주문을 자동 소급 지급하지 않는다.
6. WHERE 공통 운영 API(DLT 조회·재투입, Outbox DEAD 건별 재큐잉)를 명시적으로 활성화한 맥락에서 THE 시스템 SHALL `ADMIN`만 허용하고 MEMBER·익명과 Outbox 일괄 회수를 차단한다. 기본값은 비활성·Gateway 미노출이다.
7. WHEN DLT 재투입을 실행하면 THEN 시스템 SHALL 모든 재발행 결과를 제한 시간 안에 확인하고, 실제 발행한 레코드의 파티션별 다음 오프셋만 커밋한다. 발행 실패·미확정·중단이면 오프셋을 커밋하지 않는다.
8. IF DLT 발행 자체가 실패하면 THEN 시스템 SHALL 그 실패를 예외로 전파하고 성공으로 계측하지 않는다.
9. IF 운영 요청의 토픽이 아직 없거나 DLT 형식이 아니면 THEN 시스템 SHALL 400으로 안내하고, 실제 브로커·발행 오류를 성공이나 빈 목록으로 바꾸지 않는다.

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

## 범위 밖(후속)

- 선물·기간제·구독·체험판·양도·좌석/기기 권한, 외부 CD키·DRM.
- 이미 받은 파일의 회수, 환불 완료/재구매 정책과 권한·구매 이력 연결 → 일반화 G2 후속(P4)에서 닫는다.
- DB 유실 뒤 전량 재구축, 전용 운영 API·스케줄러·성능 실험.
