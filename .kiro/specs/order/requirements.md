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

### R2. 요청 멱등과 중복 구매 차단
**사용자 스토리:** 구매자로서, 재전송이나 중복 구매로 주문이 여러 건 생기지 않기를 바란다. 그래야 중복 청구가 없다.

#### 수용 기준
1. THE 시스템 SHALL `(buyerSubject, requestId)` 유일 제약으로 중복 생성을 막는다.
2. WHEN 같은 구매자·requestId·같은 입력(코드·기대 버전·기대 금액)의 재요청이 오면 THEN 시스템 SHALL Catalog를 다시 호출하지 않고 기존 주문의 현재 상태를 반환한다.
3. IF 같은 구매자·requestId에 다른 입력이 오면 THEN 시스템 SHALL 409로 거절하고 기존 주문을 보존한다.
4. WHEN 같은 요청이 동시에 생성되면 THEN 시스템 SHALL DB 유일 제약으로 한 건만 확정하고, 패한 트랜잭션 종료 후 기존 주문을 다시 읽어 동일 입력/충돌 규칙을 적용한다.
5. WHEN 새 requestId로 같은 상품을 구매하려 하고 기존 주문이 `PENDING`이면 THEN 시스템 SHALL 409 `PURCHASE_IN_PROGRESS`로 거절한다.
6. WHEN 새 requestId로 같은 상품을 구매하려 하고 기존 주문이 `PAID`이면 THEN 시스템 SHALL 409 `PRODUCT_ALREADY_PURCHASED`로 거절한다.
7. WHERE 같은 상품의 최종 `FAILED` 주문만 THE 시스템 SHALL 새 주문을 허용한다. 구매자·상품 ID/코드 유일 제약으로 동시 생성도 제한한다.

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
9. WHEN 자동 스캔·기한 후 승인 접수 거절·검증된 실패 뒤 기한 후 종료가 최초 `EXPIRED`로 전이하면 THEN 시스템 SHALL 같은 트랜잭션에 `OrderExpired` Outbox를 저장하고, 반복 만료·과거 종료 재전송에는 새 통지를 발행하지 않는다.

### R4. 결제 결과 반영
**사용자 스토리:** 구매자로서, 내 결제 결과가 정확히 내 주문에 반영되기를 바란다. 그래야 성공·실패를 신뢰할 수 있다.

#### 수용 기준
1. WHEN `OrderPaymentCompleted`/`OrderPaymentFailed`(stove.payment.v1)를 수신하면 THEN 시스템 SHALL `requestEventId`·orderNo·buyerSubject·amount·currency 일치를 검증하고 `PENDING`→`PAID`/`FAILED`로 전이한다.
2. WHEN 결과를 반영하면 THEN 시스템 SHALL 주문 행 잠금·Inbox 가드·상태 변경을 같은 트랜잭션으로 처리한다.
3. IF 원래 eventId·내용이 재전달되면 THEN 시스템 SHALL 무효과로 처리하고 확정 결과를 새 결과로 덮어쓰지 않는다. `PAID`·`FAILED`는 최종 상태다.
4. IF 연결 불일치·결과 충돌·없는 주문이면 THEN 시스템 SHALL 주문을 바꾸지 않고 실패시키며 공통 Kafka 재시도·DLT를 사용한다.
5. WHEN 최초 `PAID`를 반영하면 THEN 시스템 SHALL 같은 트랜잭션에 `OrderPaid` Outbox를 기록한다. `FAILED`에는 발행하지 않고 과거 `PAID`의 자동 소급 발행도 없다.
6. THE 완료 결과의 amount SHALL 고객에게 승인된 금액이며, PG 수수료 공제 후 입금액·판매자 지급액과 비교하지 않는다. 선택적 `pgPaidAmount`·`pgDiscountAmount` 쌍은 합계가 원주문 총액과 다르거나 한쪽만 있으면 거절한다.

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
4. WHEN 최초 내구 접수가 결제 확정 후 정확히 4시간 이내면 THEN 시스템 SHALL 자동 허용하고, 이후에는 관리자 심사로 넘긴다. 처리 지연·재시도로 첫 자격을 초기화하지 않는다.
5. WHEN `GET /api/v1/admin/refunds`(기본 PENDING_REVIEW)·`GET /api/v1/admin/refunds/{refundId}` 요청이 오면 THEN 시스템 SHALL `ADMIN`의 상태별 목록·원구매·처리 상태·최초 판정 상세를 제공한다.
6. WHEN `POST /api/v1/admin/refunds/{refundId}/decision`이 `requestId`·`APPROVED`/`REJECTED`·사유로 오면 THEN 시스템 SHALL JWT sub를 판정자로 기록하고, 먼저 커밋된 판정을 확정한다. 같은 명령·판정자·내용만 최초 영수증으로 재전송 처리하고 판정 변경·다른 명령은 409다.
7. WHEN 승인 또는 자동 허용되면 THEN 시스템 SHALL 실제 License 지급 확인과 정지 요청을 발행하고, 실제 정지 확인 뒤에만 `RefundExecutionRequested`를 발행한다.
8. THE 조회·판정 응답 SHALL no-store이며, 관리자 200은 환불 완료를 의미하지 않는다. 원구매 PAID·원래 금액·상품·구매 근거를 환불 상태로 덮어쓰지 않는다.

## 범위 밖(후속)

- 장바구니·복수 상품·수량 선택·선물·쿠폰·할인 조합·다중 통화·무료 상품 지급.
- 환불 후 재구매(P4) 허용/불허와 새 구매·권한 연결·과거 이벤트 격리 — 후속 논의로 유지.
- 지급 확인 누락의 근거 기반 복구(G2 P3는 License 측 이벤트 복구로 완료, Order 측 연계 확장은 후속).
- 반려 뒤 고객 재심사·잘못된 승인 중단·고객 명시적 재시도 등 환불 확장 계약은 별도 작업에서 정의.
- 부분 환불·다중 PG·조사 종결/담당 배정 등 운영·성능 고도화.
