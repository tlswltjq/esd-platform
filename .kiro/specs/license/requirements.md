# 요구사항: License (구매 근거·보관함·보유권·라이브러리, 환불 정지·복구·회수)

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다. 이벤트의 뜻·키·순서·소비 그룹·흐름·모듈 간 불변식은 플랫폼 스펙 행을 따른다(원칙 ③). 이 문서는 License가 그 계약을 **자기 상태·API·내부 규칙으로 어떻게 지키는지**를 적는다.

## 개요

License는 결제 완료 주문 1건을 근거로 **구매 근거**(보관함 항목, `product_license`)를 만들고, 고객이 보관함 항목을 라이브러리에 등록하면 **보유권**(`license_registration`)을 만든다([결정 89~98](../../../docs/decisions.md#purchase-blocked-only-by-sale-ban)). 환불 흐름에서는 구매 근거 단위로 정지·복구·회수한다([결정 94](../../../docs/decisions.md#refund-suspends-purchase-evidence)·[96](../../../docs/decisions.md#register-during-pending-refund)·[157](../../../docs/decisions.md#late-cancellation-confirmation)).

- 정본: 구매 근거·보유권의 상태와 정지·복구·정정·회수 이력([플랫폼 design "정본 소유"](../platform/design.md#정본-소유)).
- 받는 사실: Order의 `OrderPaid`·`LicenseSuspensionRequested`·`LicenseRestorationRequested`·`LicenseApprovalCorrectionRequested`·`PurchaseRefunded`(모두 `stove.order.v1`).
- 내는 사실: `PurchaseRecordStored`(지금 이름 `LicenseGranted`, [결정 175](../../../docs/decisions.md#purchase-record-stored))·`LicenseSuspended`·`LicenseRestored`·`LicenseApprovalCorrected`·`LicenseRefundApplied`(가칭, [결정 158](../../../docs/decisions.md#forward-gap-counting)) — 모두 `stove.license.v1`.
- 동기 호출: 받기만 한다(고객·Download의 `GET /api/v1/library/{productCode}`). License는 다른 업무 모듈을 동기 호출하지 않는다([플랫폼 design "동기 의존"](../platform/design.md#동기-의존-http)).
- 모듈 이름은 License로 유지한다([결정 92](../../../docs/decisions.md#license-module-name-kept)). 맡은 일(보관함·라이브러리)은 이 문서의 책임 설명으로 드러낸다.

## 용어

- **buyerSubject**: Auth JWT `sub`에서 온 구매자 식별자. 본문·쿼리·헤더로 고르지 않는다.
- **productId / productCode**: 주문 당시 Catalog가 부여한 상품 ID와 코드. 둘 다 정확히(바이너리) 비교한다.
- **구매 근거(보관함 항목)**: 결제 완료 주문 1건의 불변 스냅샷(`OrderPaid` 13개 값)과 그 구매분의 상태. 공개 식별자 `licenseId`(UUID)는 주문 1건에 하나다. 상태는 design "상태 모델"의 `PurchaseStatus`.
- **보유권(등록)**: 구매 근거 하나를 라이브러리에 등록한 한 번의 영구 이용 권한. 등록마다 새 행이다([결정 95](../../../docs/decisions.md#license-row-per-registration)). 상태는 `ACTIVE`·`SUSPENDED`·`ENDED`.
- **쓸 수 있는 보유권**: `ACTIVE` 보유권. 고객·상품당 최대 1개.
- **열린 보유권**: `ACTIVE` 또는 `SUSPENDED` 보유권. 구매 근거당 최대 1개.
- **현재 정지**: 구매 근거가 정지 상태일 때 그 정지를 만든 정지 요청 ID(`suspensionRequestEventId` = 그 `LicenseSuspensionRequested.eventId`).
- **정지 전 상태**: 정지될 때의 구매 근거 상태(`STORED` 또는 `REGISTERED`). 복구가 돌아갈 곳을 정한다. 표현 방식은 `[결정 필요 M-LI-1]`(소급 RC-17).
- **처리 기록**: 정지·복구·승인 정정 요청 한 건을 처리한 불변 기록(`license_suspension`·`license_restoration`·`license_approval_correction`). 상태 열이 없다.
- **늦은 취소 회수**: 실패로 끝나 복구된 환불 시도 뒤 실제 전액 취소가 ADMIN 근거로 반영돼 Order가 낸 `PurchaseRefunded`로 구매 근거를 끝내는 것([결정 157](../../../docs/decisions.md#late-cancellation-confirmation), MC-L1).
- **Inbox**: `(eventId, consumerGroup)` 수신 멱등 가드(`processed_event`). 소비 그룹은 `license-paid-order`·`license-suspension`·`license-restoration`·`license-approval-correction`·`license-purchase-refund` 다섯이다.

## 요구사항

### R1. 결제 완료 주문의 구매 근거(보관함 항목) 생성
**사용자 스토리:** 구매자로서, 결제가 끝난 주문의 상품이 내 보관함에 들어오기를 바란다. 그래야 라이브러리에 등록하거나 남겨 둘 수 있다.

#### 수용 기준
1. WHEN 유효한 `OrderPaid`를 처음 받으면 THEN License SHALL 주문 1건당 구매 근거 한 행을 `STORED`로 만들고, Inbox(`license-paid-order`)와 구매 근거 생성 사실 `PurchaseRecordStored`(지금 이름 `LicenseGranted`) Outbox를 같은 트랜잭션에 쓴다. 보유권은 만들지 않는다([P3.15-1](../platform/requirements.md), 결정 93·175, 1회차 지적 B9, 판정 전).
2. WHEN 구매 근거를 만들면 THEN License SHALL 출처(`OrderPaid` 13개 값)를 `product_license`의 같은 행에 저장한다.
3. THE 보유권 SHALL 만료·기기 수·좌석 제한이 없는 영구 이용 권한이다.
4. WHEN 구매 근거를 만들면 THEN License SHALL `licenseId`·제목·`acquiredAt`(License가 구매 근거를 만든 자기 시각)을 확정하고 이후 덮어쓰지 않는다.
5. THE 구매 근거 SHALL `OrderPaid`로만 생긴다. 조회·등록·운영 API·다른 이벤트로 만들지 않는다([P4.2-2](../platform/requirements.md), I3).
6. IF 입력의 상품·구매자·금액 등 필수 값이 유효하지 않으면 THEN License SHALL 저장 전에 영구 실패로 거절한다(R8).

### R2. 구매 근거의 불변 보존과 출처 통합
**사용자 스토리:** 플랫폼으로서, 각 결제 완료 주문의 구매 근거를 그대로 남기고 싶다. 그래야 분쟁·환불·조사에서 사실을 재구성한다.

#### 수용 기준
1. WHEN 구매 근거를 저장하면 THEN License SHALL 주문별 불변 근거(`OrderPaid` 13개 값 전체)를 보존한다.
2. THE License SHALL 출처를 `product_license` 한 곳에 저장한다(V5 통합). `license_purchase`는 과거 자료로만 보존하고 읽거나 쓰지 않는다.
3. WHILE 조회를 처리하는 동안 License SHALL Catalog·Order·Payment를 호출하지 않는다.
4. THE 제목·버전·금액·발생 시각 SHALL 구매 근거의 스냅샷으로 남고 현재 Catalog 값으로 바뀌지 않는다.

### R3. 중복과 충돌 판정
**사용자 스토리:** 플랫폼으로서, 같은 이벤트의 재전달과 실제 충돌을 구분하고 싶다. 그래야 중복 지급·근거 위조를 막는다.

#### 수용 기준
1. WHEN 같은 주문의 같은 입력이 다시 오면 THEN License SHALL 기존 `licenseId`·근거·`acquiredAt`·`receivedAt`을 유지하고 무변화로 끝내며 결과 이벤트를 다시 내지 않는다.
2. IF 같은 주문에 `eventId`·본문·발생 시각 중 하나라도 다른 입력이 오면 THEN License SHALL 덮어쓰지 않고 영구 실패로 거절한다([P2.3-4](../platform/requirements.md)).
3. WHEN 같은 구매자의 같은 상품에 다른 주문이 오면 THEN License SHALL 별도 구매 근거로 보관함에 넣는다. 이미 가진 상품인지는 등록에서 판단한다([결정 89](../../../docs/decisions.md#purchase-blocked-only-by-sale-ban)).
4. IF 다른 주문이 `eventId`·`requestEventId`·`paymentResultEventId`·`paymentId`를 다시 쓰면 THEN License SHALL 영구 실패로 거절한다.
5. WHEN 다른 구매자가 같은 상품을 사면 THEN License SHALL 그 구매자의 별도 구매 근거를 만든다.
6. IF Inbox만 있고 구매 근거가 없거나, 구매 근거만 있고 Inbox가 없으면 THEN License SHALL 자료 불일치로 영구 실패 처리하고 정상 중복으로 숨기지 않는다. 정지·복구·정정·회수의 처리 기록과 Inbox도 같은 규칙이다.

### R4. 내 라이브러리 조회 (두 GET)
**사용자 스토리:** 구매자로서, 내가 보유한 상품을 목록과 상세로 보고 싶다. 그래야 무엇을 가졌는지 확인한다.

#### 수용 기준
1. WHEN `GET /api/v1/library?page=&size=` 요청이 오면 THEN License SHALL 검증된 JWT `sub` 본인의 열린 보유권 페이지를 반환한다.
2. WHEN `GET /api/v1/library/{productCode}` 요청이 오면 THEN License SHALL 본인의 그 상품 보유권 한 개를 반환한다.
3. WHERE 조회 맥락에서 License SHALL `MEMBER` 역할을 요구하고 서명·issuer·만료·`aud=esd-api`를 검증한다.
4. IF 요청이 익명이면 THEN License SHALL 401을, 역할이 없으면 403을, 타인·미보유 상품이면 404를 반환한다.
5. THE License SHALL 본문·쿼리·`X-Member-Id`·`member_id`로 조회 주체를 바꾸지 않는다.
6. THE 목록 SHALL `page ≥ 0`, `1 ≤ size ≤ 100`, 등록 시각(`registeredAt`) 내림차순·`id` 내림차순으로 정렬하고 본인 자료만 반환한다.
7. THE 상품 응답 SHALL `licenseId`(구매 근거 ID)·`productId`·`productCode`·`title`·`acquiredAt`·`status`(`ACTIVE`/`SUSPENDED`)만 노출한다. 라이브러리 응답의 `acquiredAt`은 그 보유권의 등록 시각(`registeredAt`)이다. 라이브러리는 `ACTIVE`·`SUSPENDED` 보유권만 보이고 `ENDED`는 뺀다([결정 97](../../../docs/decisions.md#refunded-items-only-in-order-history)). 상세는 같은 상품에 `ACTIVE`가 있으면 그것을, 없으면 가장 최근 `SUSPENDED`를 반환한다. buyerSubject·근거·금액·이벤트 ID·`paymentId`·Inbox·내부 id는 노출하지 않는다.
8. WHERE `OrderPaid` 전달이 늦어 구매 근거가 아직 없는 맥락에서 License SHALL 목록에서 빼거나 상세 404를 반환하고, 조회로 구매 근거·보유권을 만들지 않는다.
9. IF 페이지 값이나 상품 코드 형식이 잘못되면 THEN License SHALL 400을 반환한다.
10. WHILE Catalog가 중단되었거나 상품 정보가 바뀐 동안 License SHALL 저장된 최초 제목·상품 정보로 조회한다.
11. WHEN Download가 고객 토큰으로 상세를 조회하면 THEN License SHALL 같은 규칙으로 응답하고, Download는 `status=ACTIVE`일 때만 이용 권한으로 본다([P3.8-1](../platform/requirements.md)).

### R5. 수신 처리와 결과 발행의 트랜잭션 원자성
**사용자 스토리:** 플랫폼으로서, 받은 이벤트의 처리와 결과 발행을 한 트랜잭션으로 묶고 싶다. 그래야 부분 저장·유실이 없다.

#### 수용 기준
1. WHEN `OrderPaid`·`LicenseSuspensionRequested`·`LicenseRestorationRequested`·`LicenseApprovalCorrectionRequested`·`PurchaseRefunded`를 받으면 THEN License SHALL 각각 구매 근거 생성·정지·복구·승인 정정 복구·회수를 처리한다.
2. WHEN 위 처리가 상태를 바꾸면 THEN License SHALL 업무 변경·처리 기록·Inbox·결과 Outbox(`PurchaseRecordStored`·`LicenseSuspended`·`LicenseRestored`·`LicenseApprovalCorrected`·`LicenseRefundApplied`)를 같은 트랜잭션에 저장한다([P2.2-1](../platform/requirements.md)·[P2.3-1](../platform/requirements.md)).
3. THE License SHALL 받는 이벤트를 모두 `stove.order.v1`에서, 키 = `orderNo`로 받는다([P2.4-1](../platform/requirements.md)).
4. IF 처리 트랜잭션의 일부가 실패하면 THEN License SHALL 전체를 롤백한다. 선행 사실이 아직 없으면(구매 근거 없음) 재시도 가능 실패로, 계약 위반·식별자 모순·내용 불일치면 영구 실패로 다뤄 [P2.6](../platform/requirements.md)의 재시도·실패 보관·키 정지를 따른다([P2.5-3](../platform/requirements.md), [결정 155](../../../docs/decisions.md#key-stop-on-dead)·[160](../../../docs/decisions.md#dead-letter-store)).
5. WHILE 리스너가 처리하는 동안 License SHALL 트랜잭션 안에 외부 HTTP 호출을 두지 않는다.
6. THE License SHALL 결과 이벤트를 상태를 바꾼 첫 처리에서만 내고, 재전달·재처리로는 다시 내지 않는다. 결과 유실은 R7-10의 재발행으로 잇는다.

### R6. 환불 정지·복구와 이력
**사용자 스토리:** 플랫폼으로서, 환불 과정의 이용 권한 정지·복구를 기록하고 싶다. 그래야 다운로드 차단·복원과 이력 조회가 정확하다.

#### 수용 기준
1. THE 정지·복구 대상 SHALL 보유권이 아니라 구매 근거다([결정 94](../../../docs/decisions.md#refund-suspends-purchase-evidence)). 등록 전이면 등록을 막고, 등록됐으면 열린 보유권도 `SUSPENDED`로 바꾼다.
2. WHEN `LicenseSuspensionRequested`를 처음 받아 `orderNo`·`buyerSubject`·`productId`·`productCode`·`paymentId`가 구매 근거와 같고 구매 근거가 `STORED`·`REGISTERED`이면 THEN License SHALL 정지하고 정지 전 상태를 남기며, 정지 기록과 `LicenseSuspended`(요청 `eventId`·`refundId` 포함)를 같은 트랜잭션에 쓴다([P3.18-1·2](../platform/requirements.md)).
3. IF 구매 근거가 다른 요청으로 이미 정지됐거나 끝났으면 THEN License SHALL 정지하지 않고 영구 실패로 거절한다([P3.18-2](../platform/requirements.md)).
4. WHERE 정지된 상품의 맥락에서 License SHALL 라이브러리(등록 후 정지) 또는 보관함(등록 전 정지)에 그 항목을 계속 보이고 정지 표시를 한다([P3.18-4](../platform/requirements.md)).
5. WHEN `LicenseRestorationRequested`를 받아 `licenseId`·구매 연결·정지 요청 ID·정지 완료 ID(`suspensionEventId`)·`refundId`가 현재 정지와 그 정지 기록에 맞으면 THEN License SHALL 같은 고객·상품의 구매 근거를 잠근 뒤 정지 전 상태로 되돌린다. 정지 전 `STORED`면 `STORED`로, 정지 전 `REGISTERED`이고 같은 고객·상품에 다른 `ACTIVE`가 없으면 `REGISTERED`·보유권 `ACTIVE`로, 다른 `ACTIVE`가 있으면 옛 보유권을 `ENDED`로 끝내고 `STORED`로 돌려보낸다([결정 96](../../../docs/decisions.md#register-during-pending-refund)·[98](../../../docs/decisions.md#storage-return-no-notification), [P3.24-1](../platform/requirements.md)). 복구 기록과 `LicenseRestored.returnedToStorage`에 보관함 반환 여부를 남긴다.
6. WHEN `LicenseApprovalCorrectionRequested`(PG 취소 송신 전 승인 중단, [P3.27-1](../platform/requirements.md))를 받으면 THEN License SHALL R6-5와 같은 대조·세 갈래로 복구하고 정정 기록·Inbox·`LicenseApprovalCorrected`(`returnedToStorage` 포함) Outbox를 원자 저장하며, PG 실패나 실행 시도를 만들지 않는다.
7. IF 복구·정정 요청이 이미 끝난 정지나 현재가 아닌 정지를 가리키면 THEN License SHALL 아무것도 바꾸지 않고 영구 실패로 거절한다. 끝난 복구·예전 정지의 재전달은 그 뒤의 새 정지를 풀지 않는다. 정지 1건당 복구 기록은 1건, 정정 기록은 1건이며 둘 중 하나만 그 정지를 끝낸다(I17).
8. THE 정지·회수 처리 SHALL 대상 구매 근거 한 행만 잠그고, 등록·복구 SHALL 같은 고객·상품의 구매 근거 전체를 id 순서로 잠근다. "쓸 수 있는 보유권 1개" 판단(등록·복구)은 대상 행을 포함한 전체 잠금 아래에서만 하므로, 대상 행 잠금만 잡는 정지·회수와도 직렬화된다(design "잠금 범위").

### R7. 운영 경계와 실패 처리
**사용자 스토리:** 플랫폼으로서, 처리 실패를 안전하게 보존하고 되살리고 싶다. 그래야 자동 환불이나 조용한 유실이 없다.

#### 수용 기준
1. THE License SHALL 구매 근거 생성·회수·결과 주입용 업무 HTTP API를 두지 않는다.
2. WHEN 구매 근거 생성이 실패하면 THEN License SHALL [P2.6](../platform/requirements.md)의 재시도·실패 보관으로 보존하고 자동 환불을 요청하지 않는다.
3. IF 기술적 처리 오류가 나면 THEN License SHALL 결제 실패·자동 환불·지급 실패 이벤트를 만들지 않는다.
4. WHEN 원래 이벤트를 재처리하면 THEN License SHALL 원래 `eventId`·키·헤더·본문을 그대로 쓴다.
5. THE License SHALL `OrderPaid` 도입 전에 이미 PAID였던 주문을 자동 소급하지 않는다.
6. WHERE 공통 운영 API(실패 보관 조회·재투입, Outbox `DEAD` 회수, `SENT` 재발행)를 명시적으로 켠 맥락에서 License SHALL `ADMIN`만 허용하고 MEMBER·익명과 일괄 회수를 막는다. 기본값은 꺼짐·Gateway 미노출이다.
7. WHEN ADMIN이 License 소비 그룹의 실패 보관 레코드를 재투입하면 THEN License SHALL 키 단위 원래 순서로 같은 리스너 경로(`*Mapper.from` → 서비스)로 처리하고, 그 키를 다 처리한 뒤에만 정지를 푼다([P2.6-4·5](../platform/requirements.md), 결정 155·160).
8. IF 재투입한 레코드가 다시 실패하면 THEN License SHALL 키 정지를 유지하고 그 결과를 실패 보관 표에 남기며 성공으로 계측하지 않는다.
9. IF 운영 요청의 대상이 없거나 형식이 틀리면 THEN License SHALL 400·404로 안내하고, 실제 저장·발행 오류를 성공이나 빈 목록으로 바꾸지 않는다.
10. WHEN ADMIN이 `POST /api/v1/ops/outbox/{eventId}/republish`(사유 1~500자)를 보내고 그 행이 License가 소유한 현행 이벤트의 `SENT` 행이면 THEN License SHALL 원래 `event_id`·본문·키를 그대로 둔 채 발행 대기로 돌리고 실행자·시각·사유·이전 발행 시각을 `outbox_republish`에 같은 트랜잭션으로 남긴다. 없는 행·`SENT`가 아닌 행은 404, 허용 목록 밖 타입·사유 없음은 400이며 행을 바꾸지 않는다([P2.2-7](../platform/requirements.md), [결정 129](../../../docs/decisions.md#orphan-license-grant-republish)·[161](../../../docs/decisions.md#republish-all-current)).
11. THE License 재발행 허용 목록 SHALL [플랫폼 design 이벤트 목록](../platform/design.md#이벤트-목록)에서 소유 모듈이 License이고 상태가 "현행"인 이벤트로 만든다. Order 소유 타입은 400으로 거절한다. 이미 처리한 요청 이벤트가 다시 오면 Inbox에서 효과가 없고 결과를 다시 내지 않으므로, 결과 유실은 License 쪽 결과 행 재발행으로 잇는다.

### R8. 입력 경계 검증
**사용자 스토리:** 플랫폼으로서, 받은 이벤트를 저장 전에 검증하고 싶다. 그래야 잘못된 근거로 권한이 생기지 않는다.

#### 수용 기준
1. WHEN 이벤트를 받으면 THEN License SHALL 리스너의 `*Mapper.from`에서 이벤트 종류, 봉투·본문 `eventId`·`eventType` 일치, Kafka 키 = 본문 `orderNo`를 검증한 뒤 내부 입력(`PaidOrder`·`SuspensionRequest`·`RestorationRequest`·`ApprovalCorrectionRequest`·`PurchaseRefund`)으로 바꾼다.
2. IF 필수 값 누락·ID/금액 범위 오류·잘못된 상품 코드·시각 정밀도 오류·소수의 정수 절삭이 필요한 값이면 THEN License SHALL 저장 전에 영구 실패로 거절한다.
3. THE License SHALL 다른 모듈이 찍은 시각(`occurredAt` 등)과 자기 시각, 또는 다른 모듈 시각끼리의 선후로 받은 이벤트를 거절·분기하지 않는다. 그 시각은 값으로만 저장하고 같은 정지·요청인지는 식별자와 잠금으로만 판정한다([P4.5-1](../platform/requirements.md), I9·I13, D-052).
4. THE License SHALL 같은 주문의 `OrderPaid`를 **13개 값 전체**로 비교하며, Inbox만 먼저 보고 내용 비교를 생략하지 않는다.
5. THE 구매 시 `contentVersion` SHALL 근거 보존용이며 특정 빌드·버전만 허용하는 조건이 아니다. 같은 보유권으로 뒤 공개본도 이용한다.
6. WHEN 받은 레코드의 `schemaVersion` 헤더가 있으면 THEN License SHALL (`eventType`, `schemaVersion`)으로 매퍼를 고르고, 헤더가 없으면 버전 1로 읽는다([P2.7-2](../platform/requirements.md), [결정 170](../../../docs/decisions.md#schema-version-header)).

### R9. 과거 자료와 전환 경계
**사용자 스토리:** 운영자로서, 구 모델 자료를 잃지 않고 새 모델로 전환하고 싶다. 그래야 과거 근거를 임의로 바꾸지 않는다.

#### 수용 기준
1. THE License SHALL 구 Outbox 릴레이를 끄고 `LicenseIssued`·`LicenseIssueFailed`·`LicenseRevoked`를 내지 않으며, 구 소비 그룹·구 Inbox를 새 구매 근거로 바꾸지 않는다(세 상수는 제거 대상, [P2.8](../platform/requirements.md)).
2. IF V5 전환 대상에 복수·누락·불일치 출처, 상품·제목 불일치, 누락·고아·다른 유형 Inbox가 있으면 THEN 이행 SHALL 업무 컬럼 추가 전에 전환을 거부한다.
3. THE 이행 SHALL 복수 구매 근거를 임의로 한 출처로 줄이거나 PAID 주문을 삭제·실패 처리해 제약을 통과시키지 않는다.
4. WHEN 공유 환경에 전환하기 전 THEN 운영자 SHALL 쓰기를 멈춘 대상 DB에 읽기 전용 점검 SQL을 실행하고 미발행 `OrderPaid` Outbox·Kafka 미소비·실패 보관 자료를 대조한다.
5. WHEN 상태 표현을 바꾸는 이행(M-LI-1)을 하면 THEN 이행 SHALL 기존 행의 뜻을 그대로 옮기고 `migrationTest`로 옛 행 → 새 값 대응을 확인한다([P2.7-3](../platform/requirements.md), 결정 104).

### R10. 보관함과 라이브러리 등록
**사용자 스토리:** 구매자로서, 산 상품을 바로 라이브러리에 넣을지 보관함에 둘지 고르고 싶다. 그래야 이미 가진 상품을 다시 사도 나중에 쓸 수 있다([결정 90](../../../docs/decisions.md#storage-box-in-p4)·[93](../../../docs/decisions.md#register-after-payment-choice)).

#### 수용 기준
1. WHEN `GET /api/v1/storage?page=&size=` 요청이 오면 THEN License SHALL 본인의 `STORED` 구매 근거와 정지 전 `STORED`로 정지된 구매 근거를 `licenseId`·`orderNo`·`productId`·`productCode`·`title`·`purchasedAt`(= 구매 근거 `acquiredAt`)·`status`·`registrable`로, `acquiredAt` 내림차순·`id` 내림차순으로 반환한다. `REFUNDED`와 등록된 구매 근거는 뺀다([결정 97](../../../docs/decisions.md#refunded-items-only-in-order-history)).
2. THE `registrable` SHALL 구매 근거가 `STORED`이고 같은 고객·상품의 `ACTIVE` 보유권이 없을 때만 true다.
3. WHEN `POST /api/v1/storage/{licenseId}/registration` 요청이 오면 THEN License SHALL 같은 고객·상품의 구매 근거 전체를 id 순서로 잠근 뒤, 대상이 `STORED`이고 `ACTIVE` 보유권이 없을 때 새 보유권을 `ACTIVE`로 만들고 구매 근거를 `REGISTERED`로 바꾼다.
4. IF 같은 상품의 `ACTIVE` 보유권이 이미 있으면 THEN License SHALL 409 `PRODUCT_ALREADY_OWNED`로 거절하고 구매분을 보관함에 남긴다.
5. IF 구매 근거가 정지·환불 완료 상태이면 THEN License SHALL 409 `PURCHASE_NOT_REGISTRABLE`로 거절한다. 이미 등록돼 `ACTIVE`인 같은 구매 근거의 재요청은 그 보유권을 그대로 반환한다.
6. THE License SHALL `ACTIVE` 보유권을 고객·상품당 1개, 열린 보유권을 구매 근거당 1개로 DB 유일 제약으로도 막는다.
7. WHERE 이전 구매의 환불이 끝나지 않은 맥락에서도 License SHALL 새 구매분의 등록을 허용한다([결정 96](../../../docs/decisions.md#register-during-pending-refund)).
8. THE 보관함·등록 API SHALL R4-3~5의 인증·소유 규칙을 따르고, 타인·없는 구매 근거는 404, `licenseId` 형식 오류는 400이다.

### R11. 회수(환불 완료)
**사용자 스토리:** 플랫폼으로서, 환불이 끝난 구매가 다시 살아나지 않고, 회수했다는 사실이 Order에 돌아가기를 바란다. 그래야 지각 이벤트가 새 보유권을 건드리지 않고 누락을 셀 수 있다.

#### 수용 기준
1. WHEN `PurchaseRefunded`(그룹 `license-purchase-refund`)를 처음 받아 `licenseId`·`buyerSubject`·`productId`·`productCode`·`paymentId`가 구매 근거와 같고 `suspensionRequestEventId`가 현재 정지이면 THEN License SHALL 구매 근거를 `REFUNDED`로, 열린 보유권을 `ENDED`로 끝내고 Inbox·회수 근거와 함께 회수 응답 사실 `LicenseRefundApplied`(가칭) Outbox를 같은 트랜잭션에 쓴다([P3.25-1](../platform/requirements.md), [결정 158](../../../docs/decisions.md#forward-gap-counting), 1회차 지적 B4, 판정 전).
2. THE `REFUNDED` 구매 근거 SHALL 이후 등록·정지·복구·정정으로 바뀌지 않는다. 같은 회수의 재전달은 아무것도 바꾸지 않고 `LicenseRefundApplied`를 다시 내지 않는다.
3. THE 모든 정지·복구·정정·회수 처리 SHALL 주문번호와 구매 근거 ID로 대상을 찾는다. 이전 주문의 이벤트는 다른 주문의 구매 근거·보유권을 바꾸지 않는다.
4. WHEN `PurchaseRefunded`의 `suspensionRequestEventId`가 이 구매 근거의 정지 기록이고 그 정지가 **복구 기록(`license_restoration`)으로 끝난** 정지이면(늦은 취소 회수) THEN License SHALL 구매 근거가 `STORED`·`REGISTERED`여도 `REFUNDED`로 끝내고, 열린 보유권(`ACTIVE` 포함)을 `ENDED`로 바꾸며, R11-1과 같이 Inbox·회수 근거·`LicenseRefundApplied`를 같은 트랜잭션에 쓴다([P3.23-8](../platform/requirements.md), [결정 157](../../../docs/decisions.md#late-cancellation-confirmation), MC-L1, 1회차 지적 A2·B2, 판정 전). 구매 근거가 그 사이 같은 `refundId`의 재신청 정지로 다시 정지돼 있어도 같은 회수를 적용하고 현재 정지를 비운다(`정지·R`이면 `SUSPENDED` 보유권을 `ENDED`로). WHEN `REFUNDED` 구매 근거에 회수와 같은 `refundId`의 새 정지 요청(`LicenseSuspensionRequested`)이 오면(정지 요청과 회수는 소비 그룹이 달라 회수가 먼저 처리될 수 있다) THEN License SHALL 아무것도 바꾸지 않고 Inbox만 쓰며 `LicenseSuspended`를 내지 않는다. 다른 `refundId`의 정지 요청은 지금처럼 영구 실패다([결정 179](../../../docs/decisions.md#late-cancellation-during-retry)).
5. IF `PurchaseRefunded`가 구매 근거의 어떤 정지 기록도 가리키지 않거나, 승인 정정으로 끝난 정지를 가리키거나, 구매 연결이 다르면 THEN License SHALL 회수하지 않고 영구 실패로 거절한다([P3.25-2](../platform/requirements.md)).

### R12. 상태 표현과 DB 제약
**사용자 스토리:** 플랫폼으로서, 구매 근거·보유권의 상태가 명시적 값이고 DB가 허용 값을 지키기를 바란다. 그래야 보조 칸 조합으로 숨은 상태가 생기지 않는다(원칙 ④, [P3.1-4](../platform/requirements.md)).

#### 수용 기준
1. THE 구매 근거·보유권 상태 SHALL enum 값이고, DB CHECK가 허용 값을 지킨다(`chk_product_license_status`·`chk_license_registration_status`).
2. THE 정지 전 상태 SHALL 복구 갈래를 정하는 정보로 명시적으로 표현하고 DB가 상태와의 조합을 지킨다. 표현 방식(정지 상태 둘로 나눔 / 직교 칸 + CHECK)은 `[결정 필요 M-LI-1]`(소급 RC-17).
3. THE 상태에 딸린 식별자 칸 SHALL 상태와 함께만 값을 가진다: 현재 정지 요청 ID는 정지 상태일 때만, 회수 근거(정지 요청 ID·회수 시각·`PurchaseRefunded` 식별자)는 `REFUNDED`일 때만, `ended_at`은 `ENDED`일 때만 있다(DB CHECK).
4. THE 처리 기록(정지·복구·정정) SHALL 상태 열이 없는 불변 기록이고, 요청 `eventId`·대상 정지·결과 `eventId`가 각각 유일하다.

## 범위 밖(후속)

- 선물·기간제·구독·체험판·양도·좌석/기기 권한, 외부 CD키·DRM. 보관함은 선물을 위한 구조지만 선물 자체는 정책 미정이다(로드맵 "제품 확장 검토", G2 비대상).
- 이미 받은 파일의 회수(서명 URL은 만료까지 유효, 결정 29).
- 보관함 반환·환불 결과의 고객 알림([결정 98](../../../docs/decisions.md#storage-return-no-notification): 따로 알리지 않고 환불 상세에 표시. 알림 기능 자체는 업무 이벤트 구독 방식으로 별도 설계).
- DB 유실 뒤 전량 재구축, 성능 실험(품질 목표는 [플랫폼 P5](../platform/requirements.md)).
