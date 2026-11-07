# 요구사항: Order (주문 스냅샷·청구액·결제 결과·전액 환불)

## 개요

Order는 공개 상품 하나의 주문을 받아 **주문 당시의 상품·금액을 불변 스냅샷으로 고정**하고, 결제 생명주기(생성→승인 진행→성공/실패/만료)의 주문 측 상태를 소유한다.
구매자는 JWT sub로 식별하며, 상품 근거는 Catalog 내부 키로만 조회한다. 결제 실행·PG 조회는 Payment가, 실제 이용권 상태는 License가 소유한다.
단일 상품·수량 1·KRW·양수 정가가 현재 제품 범위다. 2026-10-16 MVP 재작성을 완료하고 이후 결제 생명주기(30분 기한·`PAYMENT_PROCESSING`/`EXPIRED`)와 전액 환불까지 확장했다.

## 용어

- **orderNo**: 외부 연결용 불변 UUID 주문번호. 유일하며 변경하지 않는다.
- **buyerSubject**: 구매자 식별자. 검증된 Auth JWT의 sub다. 본문·쿼리·헤더로 선택하지 않는다.
- **requestId**: 클라이언트 UUID 요청 식별자. `(buyerSubject, requestId)` 조합에 유일 제약을 둔다.
- **스냅샷**: 주문 시점에 고정한 productId·productCode·title·contentVersion·listAmount·discountAmount·amount·currency.
- **고정 기한(expiresAt)**: 최초 생성 시각 + 30분. 재진입·재시도·요청 재전송으로 연장하지 않는다.
- **승인 진행 확정**: 서버가 주문 상태·기한·금액을 검사해 `PAYMENT_PROCESSING`으로 기록하는 내부 접수. 실제 PG 송신·성공의 근거가 아니다.
- **publicDetailAllowed**: Catalog 정본이 전달하는 공개 상세 허용 근거. 최종 listed=false이면 false다.

## 요구사항

### R1. 단일 상품 주문 생성과 스냅샷 고정
**사용자 스토리:** 구매자로서, 확인한 공개 상품 하나를 그때의 가격으로 주문하고 싶다. 그래야 이후 가격이 바뀌어도 내 주문 조건이 보존된다.

#### 수용 기준
1. WHEN `POST /api/v1/orders` 요청이 `requestId`·`productCode`·`expectedContentVersion`·`expectedAmount`로 오면 THEN 시스템 SHALL 구매자를 JWT sub로 식별한다.
2. WHEN 신규 요청이면 THEN 시스템 SHALL Catalog 내부 주문 근거(`/internal/v1/order-products/{code}`)를 DB 트랜잭션 밖에서 읽고 상품 식별·현재 공개본·KRW 정가를 확인한다.
3. WHEN 상품 코드·양수 ID/버전·제목·양수 가격·KRW가 확인되면 THEN 시스템 SHALL 기대 버전·기대 금액과 비교한다.
4. WHEN 비교가 일치하면 THEN 시스템 SHALL `listAmount=Catalog.price`·`discountAmount=0`·`amount=listAmount-discountAmount`를 계산하고 스냅샷·`PENDING`·`OrderPlaced` Outbox를 같은 트랜잭션으로 저장한다.
5. THE 주문의 금액·통화·구매자·상품 스냅샷 SHALL 생성 이후 불변이며, 이후 Catalog 가격·제목·버전 변경이나 결과 이벤트로 수정하지 않는다.
6. IF 기대 버전·기대 금액이 Catalog 정본과 다르면 THEN 시스템 SHALL 409로 거절하고 주문·Outbox를 남기지 않는다.
7. WHERE 무료(0원) 상품 맥락에서 THE 시스템 SHALL 양수 가격만 받아 409로 거절한다.
8. WHERE Catalog의 `publicDetailAllowed`가 false인 맥락에서 THE 시스템 SHALL 신규 주문을 404로 거절한다. 같은 requestId의 기존 주문 재조회·기존 주문 승인 접수는 원래 계약을 유지한다.

### R2. 요청 멱등과 재구매
**사용자 스토리:** 구매자로서, 재전송으로 주문이 여러 건 생기지 않기를 바라고, 같은 상품도 다시 살 수 있기를 바란다. 그래야 중복 청구가 없고 선물·재구매를 막지 않는다(결정 89).

#### 수용 기준
1. THE 시스템 SHALL `(buyerSubject, requestId)` 유일 제약으로 중복 생성을 막는다.
2. WHEN 같은 구매자·requestId·같은 입력(코드·기대 버전·기대 금액)의 재요청이 오면 THEN 시스템 SHALL Catalog를 다시 호출하지 않고 기존 주문의 현재 상태를 반환한다.
3. IF 같은 구매자·requestId에 다른 입력이 오면 THEN 시스템 SHALL 409로 거절하고 기존 주문을 보존한다.
4. WHEN 같은 요청이 동시에 생성되면 THEN 시스템 SHALL DB 유일 제약으로 한 건만 확정하고, 패한 트랜잭션 종료 후 기존 주문을 다시 읽어 동일 입력/충돌 규칙을 적용한다.
5. WHEN 새 requestId로 같은 상품을 구매하면 THEN 시스템 SHALL 기존 주문의 상태(`PENDING`·`PAYMENT_PROCESSING`·`PAID`·환불 완료 포함)와 무관하게 새 주문을 만든다. 구매를 막는 업무 사유는 판매 금지(R1의 공개·판매 상태)뿐이다(결정 89).
6. THE 중복 청구 방지 SHALL 같은 requestId 멱등(R2-1~4)이 맡고, 이미 가진 상품의 중복 보유는 License가 라이브러리 등록에서 막는다. 같은 상품을 다시 산 구매분은 License 보관함에 남는다(결정 90·93).
7. THE 시스템 SHALL 구매자·상품 단위의 구매 슬롯·유일 제약을 두지 않는다(V28에서 제거).
9. IF Catalog 조회·기대 값 검증이 실패했는데 같은 requestId 주문이 그사이 먼저 확정됐으면 THEN 시스템 SHALL 원래 오류 대신 기존 주문에 동일 입력/충돌 규칙을 적용한다. Catalog 중단 중에도 기존 주문 조회·동일 요청 재조회는 제공한다.

### R3. 결제 생명주기 상태와 30분 고정 기한
**사용자 스토리:** 구매자로서, 결제창을 이탈해도 주문이 안전하게 만료되고, 유효한 주문으로 시작한 결제는 지연이 있어도 완료되기를 바란다.

#### 수용 기준
1. THE 주문 상태 SHALL `PENDING`(승인 진행 확정 전)·`PAYMENT_PROCESSING`(서버가 승인 진행 확정, 결과 대기)·`PAID`·`FAILED`·`EXPIRED` 중 하나다.
2. THE 고정 기한 SHALL `createdAt + 30분`이며, 조회·재시도·요청 재전송으로 연장하지 않는다. 주문 조회에 `expiresAt`을 제공한다.
3. WHEN 승인 진행 확정(`POST /internal/v1/orders/{orderNo}/payment-admissions`) 요청이 오면 THEN 시스템 SHALL Payment 전용 `X-Payment-Service-Key`를 검사하고, 같은 주문 잠금 뒤 서버 시각·구매자·원주문 이벤트·청구액·통화를 대조한다.
4. WHEN 접수가 성공하면 THEN 시스템 SHALL `PAYMENT_PROCESSING`·paymentId 연결과 불변 `order_payment_admission` 영수증을 같은 트랜잭션으로 저장하고 승인 진행 식별자·확정 시각을 별도 보존한다.
5. IF 상태가 `PENDING`이어도 서버 시각이 고정 기한을 지났으면 THEN 시스템 SHALL 먼저 `EXPIRED` 변경을 커밋한 뒤 HTTP 409 `ORDER_EXPIRED`를 반환한다.
6. WHILE 승인 진행이 확정된 동안 THE 시스템 SHALL 시각 경과만으로 `EXPIRED`·`FAILED`로 바꾸지 않으며, 기한 내 확정된 진행은 늦은 실제 승인·통지에도 기존 주문·금액으로 완료한다.
7. WHERE `EXPIRED` 맥락에서 THE 시스템 SHALL 새 승인 접수를 거절하고 현재 조건의 새 requestId 주문으로 안내하며, 기존 주문·시도·금융 근거를 보존한다.
8. WHEN 같은 `admissionId`·전체 입력의 접수가 재전송되면 THEN 시스템 SHALL 만료 시각이나 `PAID` 이후에도 최초 영수증을 반환한다. IF 같은 ID를 다른 내용으로 쓰거나 진행 중 주문에 다른 새 진행을 요청하면 THEN 시스템 SHALL 409로 거절한다.
9. THE 기한 경계 SHALL `expiresAt` 직전 마이크로초까지 접수하고 `expiresAt` 시각부터 새 접수를 거절한다. 승인 영수증에는 그 진행의 원래 만료 시각을 저장한다. 구매자·원주문 이벤트·금액이 맞지 않는 잘못 연결된 접수는 거절만 하고 만료를 일으키지 않는다.
10. WHEN Payment가 검증된 개별 시도 실패를 `POST /internal/v1/orders/{orderNo}/payment-admissions/{admissionId}/failure`로 통지하면 THEN 시스템 SHALL 같은 주문 잠금에서 기한 내면 `PENDING`으로 되돌려 같은 주문 재결제를 허용하고, 기한 후면 `EXPIRED`로 종료한다. 과거 실패 종료 영수증의 재전송은 이후 승인 진행을 바꾸지 않는다.
11. IF `EXPIRED` 주문에 승인 접수 근거 없는 결제 성공이 도착하면 THEN 시스템 SHALL 구매 성공으로 반영하지 않고 Inbox·Outbox를 롤백한다. 그 금융 이상을 자동 취소로 처리하지 않는다.
12. WHEN 자동 스캔·기한 후 승인 접수 거절·검증된 실패 뒤 기한 후 종료가 최초 `EXPIRED`로 전이하면 THEN 시스템 SHALL 같은 트랜잭션에 `OrderExpired` Outbox를 저장하고, 반복 만료·과거 종료 재전송에는 새 통지를 발행하지 않는다.
13. THE 내부 승인 접수·실패 통지 API SHALL `X-Payment-Service-Key`만 허용하며 MEMBER Bearer로 호출할 수 없고 Gateway·공개 명세에 노출하지 않는다.

### R4. 결제 결과 반영
**사용자 스토리:** 구매자로서, 내 결제 결과가 정확히 내 주문에 반영되기를 바란다. 그래야 성공·실패를 신뢰할 수 있다.

#### 수용 기준
1. WHEN `OrderPaymentCompleted`/`OrderPaymentFailed`(stove.payment.v1)를 수신하면 THEN 시스템 SHALL `requestEventId`·orderNo·buyerSubject·amount·currency 일치를 검증하고 `PENDING`→`PAID`/`FAILED`로 전이한다.
2. WHEN 결과를 반영하면 THEN 시스템 SHALL 주문 행 잠금·Inbox 가드·상태 변경을 같은 트랜잭션으로 처리한다.
3. IF 원래 eventId·내용이 재전달되면 THEN 시스템 SHALL 무효과로 처리하고 확정 결과를 새 결과로 덮어쓰지 않는다. `PAID`·`FAILED`는 최종 상태다.
4. IF 연결 불일치·결과 충돌·없는 주문이면 THEN 시스템 SHALL 주문을 바꾸지 않고 실패시키며 공통 Kafka 재시도·DLT를 사용한다.
5. WHEN 최초 `PAID`를 반영하면 THEN 시스템 SHALL 같은 트랜잭션에 `OrderPaid` Outbox를 기록한다. `FAILED`에는 발행하지 않고 과거 `PAID`의 자동 소급 발행도 없다.
6. WHEN 성공·실패가 동시에 도착하면 THEN 시스템 SHALL 행 잠금으로 직렬화해 먼저 수락한 최종 결과를 보존한다. 다른 주문에서 이미 쓴 결과 eventId는 Inbox 중복 판단 전에 거절하고, Inbox만 있고 일치하는 저장 결과가 없으면 정상 중복으로 넘기지 않는다.
7. THE 완료 결과의 amount SHALL 고객에게 승인된 금액이며, PG 수수료 공제 후 입금액·판매자 지급액과 비교하지 않는다. 선택적 `pgPaidAmount`·`pgDiscountAmount` 쌍은 합계가 원주문 총액과 다르거나 한쪽만 있으면 거절한다.
8. WHEN Payment의 운영자 승인 보류 종결 실패(`OrderPaymentFailed`, reasonCode `REVIEW_CLOSED`)가 이미 `EXPIRED`인 주문에 도착하면 THEN 시스템 SHALL 연결값(R4.1)을 검증한 뒤 Inbox만 기록하고 `EXPIRED`를 유지한다. 재전달도 같은 무효과이며 재시도·DLT로 보내지 않는다(결정 115: 종결과 주문 자체 30분 만료가 엇갈려도 끝은 같은 새 주문 안내다). 그 밖의 결과가 `EXPIRED` 주문에 오면 R3.11·R4.4대로 충돌이다. `PENDING`·`PAYMENT_PROCESSING`(같은 paymentId) 주문의 `REVIEW_CLOSED`는 일반 실패처럼 `FAILED`가 된다.

### R5. 내 주문 조회와 최소 보안
**사용자 스토리:** 구매자로서, 내 주문만 목록·상세로 보고 싶다. 그래야 타인 주문이 노출되지 않는다.

#### 수용 기준
1. WHERE 주문 생성·조회 맥락에서 THE 시스템 SHALL `MEMBER` 역할과 JWT 서명·issuer·만료·`aud=esd-api`를 검증한다.
2. WHEN `GET /api/v1/orders` 요청이 오면 THEN 시스템 SHALL `buyerSubject`로 제한한 본인 주문 페이지(page=0·size=20, 1≤size≤100, createdAt·id 내림차순)를 반환한다.
3. WHEN `GET /api/v1/orders/{orderNo}` 요청이 오면 THEN 시스템 SHALL 본인 주문 상세를 반환하고 타인·없는 주문은 404로 처리한다.
4. THE 상세 SHALL orderNo·상품 스냅샷·정가·할인액·청구액·통화·상태·생성/갱신 시각·`expiresAt`·결과 시각·실패 사유를 제공하고, 내부 eventId·Inbox·Outbox·결과 내부 연결 정보는 노출하지 않는다.
5. IF 익명·역할 없음·잘못된 입력이면 THEN 시스템 SHALL 각각 401·403·400으로 처리한다.
6. IF 본문·쿼리·헤더로 소유자·상태를 위조하려 하면 THEN 시스템 SHALL 이를 주문 소유자·상태에 반영하지 않는다.

### R6. 전액 환불 접수·조회·관리자 심사·판정
**사용자 스토리:** 구매자로서, 구매한 상품의 전액 환불을 신청하고 그 상태를 확인하고 싶다. 운영자로서, 환불을 심사·판정하고 싶다.

#### 수용 기준
1. WHEN `POST /api/v1/orders/{orderNo}/refund` 요청이 오면 THEN 시스템 SHALL MEMBER 본인의 서버 원금 전액 환불을 접수하고 신규/진행 중은 202, 기존 최종 결과는 200을 반환한다.
2. WHEN `GET /api/v1/orders/{orderNo}/refund` 요청이 오면 THEN 시스템 SHALL 본인 환불 상태·첫 접수·완료 시각·반려 사유를 제공한다.
3. THE 환불 고객 상태 SHALL `PENDING_REVIEW`·`PROCESSING`·`COMPLETED`·`FAILED`·`REJECTED` 중 하나다. `FAILED`는 검증된 PG 실패와 실제 복구 완료 뒤에만, `REJECTED`는 고객에게 보이는 `rejectionReason`과 함께 표시한다.
4. WHEN 최초 내구 접수 시각이 Payment 최초 결제 결과 시각(`resultOccurredAt`)부터 4시간 이내(정확히 4시간 포함, 마이크로초 정밀도)면 THEN 시스템 SHALL 자동 허용하고, 초과하면 관리자 심사(`PENDING_REVIEW`)로 넘긴다. 이후 처리 지연·자동 재개·재시도·재심사·명시적 재시도로 첫 접수 시각과 자격을 현재 시각 기준으로 다시 판정하지 않는다.
5. WHEN 같은 주문에 접수가 반복·동시에 오면 THEN 시스템 SHALL 주문 행 비관적 잠금과 Refund 주문번호 유일 제약으로 환불 한 건만 만들고 기존 refundId·첫 접수 시각을 반환한다. 외부 호출을 기다리며 잠금을 보유하지 않는다.
6. WHEN `GET /api/v1/admin/refunds`(기본 PENDING_REVIEW)·`GET /api/v1/admin/refunds/{refundId}` 요청이 오면 THEN 시스템 SHALL `ADMIN`의 상태별 목록·원구매·처리 상태·최초 판정 상세를 제공한다.
7. WHEN `POST /api/v1/admin/refunds/{refundId}/decision`이 `requestId`·`APPROVED`/`REJECTED`·사유로 오면 THEN 시스템 SHALL JWT sub를 판정자로 기록하고, 먼저 커밋된 판정을 확정한다. 같은 명령·판정자·내용만 최초 영수증으로 재전송 처리하고 판정 변경·다른 명령은 409다.
8. WHEN 승인 또는 자동 허용되면 THEN 시스템 SHALL 실제 License 지급 확인(`LicenseGranted`)을 확보한 뒤 `LicenseSuspensionRequested`를 발행하고, 실제 정지 완료(`LicenseSuspended`) 확인 뒤에만 첫 `RefundAttempt`·Inbox·`RefundExecutionRequested` Outbox를 같은 트랜잭션으로 저장한다. 심사 중에는 이용권을 유지하며 PG 실행과 정지를 병행하지 않는다.
9. WHEN `LicenseGranted`가 환불 접수보다 먼저 도착하면 THEN 시스템 SHALL 지급 근거를 저장해 두고 이후 허용된 환불이 대기 없이 정지 요청으로 진행하게 한다.
10. IF 정지 완료의 원래 주문·환불·지급·정지 요청 ID·구매 연결값이 Order 근거와 다르거나 원래 요청을 찾을 수 없으면 THEN 시스템 SHALL 업무를 진행하지 않고 거절·격리(DLT)하며 환불 실패·이용권 복구로 바꾸지 않는다. 같은 정지 완료의 재전달은 새 실행 시도를 만들지 않는다.
11. THE 조회·판정 응답 SHALL no-store이며, 관리자 200은 환불 완료를 의미하지 않는다. 원구매 PAID·원래 금액·상품·구매 근거를 환불 상태로 덮어쓰지 않는다.

### R7. 환불 금융 결과 반영과 실패 복구
**사용자 스토리:** 구매자로서, 실제로 돈이 돌아왔을 때만 환불 완료를, 확정 실패면 이용권 복구를 보고 싶다.

#### 수용 기준
1. WHEN 원래 구매·환불·실행 요청과 일치하는 `RefundPaymentConfirmed`(source `PG_RESPONSE`/`PG_QUERY_UNATTRIBUTED`)를 받으면 THEN 시스템 SHALL 환불 `COMPLETED`·최초 금융 확인 지문·Inbox를 원자 저장하고, 원구매 `PAID`·금액·정지 상태는 바꾸지 않는다. 조회로 확인된 완료는 실행 성공 귀속이 미확정이므로 시도에 조사 표시를 남긴다.
2. WHEN `RefundExecutionSucceeded`가 금융 확인보다 늦게 또는 먼저 도착하면 THEN 시스템 SHALL 도착 순서와 무관하게 최초 금융 확인 ID·시각·출처와 시도 `SUCCEEDED`를 보존하고 조사 표시만 해제한다. 같은 이벤트·내용의 재전달은 무효과다.
3. WHEN 검증된 `RefundExecutionFailed`를 받으면 THEN 시스템 SHALL 시도 `FAILED`·Inbox·`LicenseRestorationRequested` Outbox·`PENDING_LICENSE_RESTORATION`을 원자 저장하고, 현재 요청·시도·정지·구매와 일치하는 실제 `LicenseRestored`를 확인한 뒤에만 환불 `FAILED`로 마친다. 복구 요청 저장만으로 복구 완료라 하지 않는다.
4. THE 시스템 SHALL 과거 정지·복구 메시지로 다른(새) 정지를 바꾸지 않고, 확정 실패 뒤 새 실행 시도를 자동으로 만들지 않는다. 시간 경과·타임아웃만으로 실패·복구하지 않는다.
5. WHEN 환불을 처음 `COMPLETED`로 확정하면 THEN 시스템 SHALL 같은 트랜잭션에 `PurchaseRefunded`(refundId·licenseId·orderNo·구매자·상품·paymentId·현재 정지 요청 ID, 키 orderNo) Outbox를 저장해 License가 구매를 끝내게 한다. 재전달은 다시 발행하지 않는다(결정 96). `PurchaseRefunded` 도입 전에 끝난 환불은 V30이 같은 본문으로 한 번 채운다(결정 99).
6. WHEN 복구 완료(`LicenseRestored`·`LicenseApprovalCorrected`)의 `returnedToStorage`가 true면 THEN 시스템 SHALL 환불에 남기고 고객 환불 응답의 `purchaseReturnedToStorage`로 보여 준다. 따로 알리지 않는다(결정 98).

### R8. 환불 확장: 명시적 재시도·재심사·잘못된 승인 중단·지연 안내
**사용자 스토리:** 구매자로서, 실패·반려 뒤 다시 요청하고 지연 사유를 알고 싶다. 운영자로서, 잘못 승인한 환불을 PG 실행 전에 멈추고 싶다.

#### 수용 기준
1. WHERE 현재 시도가 검증된 `FAILED`이고 실제 이용권 복구가 끝난 맥락에서만 THE 시스템 SHALL 고객 조회에 `retryableAttemptId`를 제공하고 `POST /api/v1/orders/{orderNo}/refund/retries`(`previousAttemptId`)를 202로 접수한다. 같은 previousAttemptId 재전송은 최초 재시도 영수증(refundId·previousAttemptId·receivedAt)을 반환하고 후속 시도가 또 실패한 뒤 와도 새 실행을 만들지 않는다.
2. WHEN 명시적 재시도를 접수하면 THEN 시스템 SHALL refundId·첫 접수 시각·원래 자동 자격·이전 실패/복구 이력을 보존하고, 새 정지 요청 → 실제 새 정지 완료 뒤에만 다음 순번의 새 attemptId와 `previousAttemptId`를 담은 `RefundExecutionRequested`를 저장한다. 실제 복구 전 요청은 409로 거절하며, 결과 미확정·`REJECTED`·Payment 운영 중단은 이 재시도 대상이 아니다.
3. WHEN `REJECTED` 뒤 고객이 `POST /api/v1/orders/{orderNo}/refund/reconsiderations`(requestId·previousReviewId·reason)를 보내면 THEN 시스템 SHALL 마지막 반려 저장 시각 + 7×24시간(경계 포함) 이내·사유 필수(공백 불가, 최대 500자)일 때 별도 심사 회차로 접수한다. 횟수 상한은 없고 같은 신청 재전송은 새 회차로 세지 않으며, 기한 내 접수는 심사 지연으로 효력을 잃지 않는다.
4. WHEN ADMIN이 `POST /api/v1/admin/refunds/{refundId}/reconsiderations/{reviewId}/decision`으로 회차를 판정하면 THEN 시스템 SHALL 회차별로 먼저 커밋된 판정을 확정하고 과거 회차의 명령 재전송이 이후 회차를 바꾸지 않게 한다. 고객·ADMIN `GET .../reviews`는 페이지로 전체 이력을 제공하며 고객 응답에는 관리자 명령 ID·판정자·승인 사유를 노출하지 않는다.
5. WHEN ADMIN이 `POST /api/v1/admin/refunds/{refundId}/approval-stops`(requestId·현재 승인 reviewId·필수 사유)를 보내면 THEN 시스템 SHALL Order가 `RefundExecutionRequested`를 저장하기 전에만 202 최초 영수증으로 접수(`PENDING_APPROVAL_STOP`)하고, 실행 요청이 먼저 저장됐으면 PG 송신 전이어도 409로 거절한다. 중단과 정지 완료는 같은 주문 잠금으로 순서를 정한다.
6. WHEN 승인 중단이 접수되면 THEN 시스템 SHALL 정지 요청 전이면 바로, 정지 요청 뒤면 실제 정지 완료와 그 정지의 승인 정정 복구(`LicenseApprovalCorrectionRequested`→`LicenseApprovalCorrected`) 완료 뒤 같은 환불의 관리자 정정 회차로 복귀한다. 늦은 정지 완료는 PG 실행 요청이 아니라 정정 복구로 연결하며, 복구 완료 전에는 새 판정을 받지 않고 과거 승인·중단·최초 접수/자격을 보존한다.
7. WHEN 검증된 `RefundExecutionPrepared`(최초 준비 시각·원래 3시간 경계)를 받으면 THEN 시스템 SHALL 근거와 Inbox를 원자 저장하고, 현재 시도가 금융 미확정인 동안 그 경계부터 고객 조회에 `processingNotice`(code·message·since)를 제공한다. 준비 근거가 없으면 접수 시각으로 추정하지 않고 null이며, 완료·실패/복구·새 시도에서는 오래된 안내를 내지 않는다. 고객 조회는 Payment HTTP를 호출하지 않는다.

### R9. 환불 금액 상세
**사용자 스토리:** 구매자·운영자로서, 주문 금액과 확인된 반환 구성을 한 응답에서 보고 싶다.

#### 수용 기준
1. THE 고객·ADMIN 환불 응답 SHALL 기존 `amount`(원주문 금액)·`COMPLETED` 의미를 유지하고 `amountDetails`(orderAmount·pgPaidAmount·pgDiscountAmount·confirmedCancellationAmount·confirmedPointRefundAmount·confirmedPaymentMethodRefundAmount·reportedAt·source·unconfirmedFields)를 저장된 근거로만 제공한다.
2. THE 시스템 SHALL 미확인 값을 0원으로 채우지 않는다. 성공 취소 중 하나라도 포인트 보고가 없으면 confirmedPointRefundAmount는 null이고, 직접 근거가 없는 결제수단 반환액은 승인 실결제액·취소 총액에서 계산하지 않고 null로 둔다. 미확인은 `COMPLETED` 보류 사유가 아니다.
3. WHEN 금액 근거가 여러 이벤트로 도착하면 THEN 시스템 SHALL 먼저 도착한 정상 근거를 보존하고 후속 근거로 미확인 값만 보완하며, 확인된 총액·취소 ID 집합·승인 구성·포인트·취소 시각과 모순되면 거절한다. 확인된 값을 null로 지우거나 reportedAt을 과거로 옮기지 않고, 금액 사본 보완으로 최초 completedAt·금융 확인·원구매 `PAID`를 덮어쓰지 않는다.

## 범위 밖(후속)

- 장바구니·복수 상품·수량 선택·선물·쿠폰·할인 조합·다중 통화·무료 상품 지급.
- 지급 확인 누락의 근거 기반 복구(G2 P3는 License 측 이벤트 복구로 완료, Order 측 연계 확장은 후속).
- 환불 조사 이후 조치·종결 기준(조사 기록과 금융/이용권 조치·종결의 구분), 실제 ADMIN 계정·운영 화면의 수동 심사·재심사·승인 중단 전체 여정.
- 부분 환불·다중 PG·조사 종결/담당 배정 등 운영·성능 고도화.
