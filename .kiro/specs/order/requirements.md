# 요구사항: Order (주문 스냅샷·청구액·결제 기한·결제 결과 반영·전액 환불 접수와 진행)

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다. 이 문서는 **목표 계약**이다. 지금 코드와 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"에 있다. `(1회차 지적 Xn, 판정 전)`은 [플랫폼 tasks "독립 평가 1회차 지적"](../platform/tasks.md)의 지적이 닿는 자리이며 고친 것으로 치지 않는다([결정 178](../../../docs/decisions.md#evaluation-findings-deferred)).

## 개요

Order는 공개 상품 하나의 주문을 받아 **주문 당시의 상품·금액·결제 기한을 불변 값으로 고정**하고, 결제 생명주기(생성 → 승인 진행 → 성공/실패/만료)의 주문 쪽 상태와 **결제 기한의 정본**을 가진다([P1.4-3](../platform/requirements.md)). Payment가 알려 온 결제 결과를 열린 최신 승인 진행 영수증과 대조해 반영하거나 거절 사실로 드러낸다([P3.14](../platform/requirements.md)). 전액 환불의 접수·자격·심사·진행 상태를 가지며, PG 실행·조회와 취소 귀속은 Payment가, 구매 근거·보유권 상태는 License가 가진다([P3.16~P3.27](../platform/requirements.md)).

구매자는 검증된 JWT `sub`로 식별하고, 상품 근거는 Catalog 내부 조회로만 읽는다. 제품 범위는 단일 상품·수량 1·KRW·양수 정가다.

이 문서는 Order가 **자기 상태·API·내부 규칙**으로 플랫폼 계약을 어떻게 지키는지만 적는다. 이벤트의 뜻·키·순서·소비 그룹 목록·모듈 간 흐름·돈 불변식은 플랫폼 스펙의 행을 링크한다(결정 151).

## 용어

- **orderNo**: 외부 연결용 불변 UUID 주문번호. 이벤트 파티션 키다([P2.4-1](../platform/requirements.md)).
- **buyerSubject**: 검증된 Auth JWT의 `sub`. 본문·쿼리·헤더로 고르지 않는다.
- **requestId**: 클라이언트 UUID 요청 식별자. `(buyerSubject, requestId)`가 유일하다.
- **스냅샷**: 주문 시점에 고정한 `productId`·`productCode`·`title`·`contentVersion`·`listAmount`·`discountAmount`·`amount`·`currency`.
- **결제 기한(`expiresAt`)**: 주문 생성 시각 + 설정 `stove.order.payment-window`(지금 30분, 결정 32). 생성 때 `purchase_order.expires_at`에 저장하고 바꾸지 않는다. Order가 정본이며 `OrderPlaced`·`OrderExpired`로 다른 모듈에 값으로 전달한다([P1.4-3](../platform/requirements.md), T12).
- **승인 진행 영수증**: Payment의 승인 진행 접수(`admissionId`)를 Order가 확정해 남긴 `order_payment_admission` 한 행. 상태는 `OPEN`·`ATTEMPT_FAILED`·`RESULT_APPLIED`다(design "상태 모델").
- **열린 최신 영수증**: 그 주문의 영수증 가운데 가장 최근에 만든 행이고 상태가 `OPEN`인 것. 주문이 `PAYMENT_PROCESSING`인 동안에만 있다.
- **결과 종류(`PaymentResultKind`)**: Order가 반영한 결제 결과의 사실 종류 — `COMPLETED`(Payment 최종 승인), `DECLINED`(결제 공급자 최종 거절), `REVIEW_CLOSED`(운영자의 승인 보류 종결). 이벤트 타입에서 정하고 사유 문자열로 정하지 않는다([결정 174](../../../docs/decisions.md#review-closed-event)).
- **결과 거절 기록**: 주문 연결값은 맞지만 열린 최신 영수증의 성공으로 반영할 수 없는 성공 결과를 Order가 반영하지 않았다는 기록. `OrderPaymentResultRejected`와 같은 트랜잭션에 쓴다([결정 153](../../../docs/decisions.md#late-success-rejection)).
- **결제 반영 시각(`paidAt`)**: Order가 결제 결과를 받아 주문을 처음 `PAID`로 바꾼 Order 시계 시각. 자동 환불 기한의 시작이다([결정 173](../../../docs/decisions.md#refund-window-order-clock)).
- **결제 확정 시각(`resultOccurredAt`)**: Payment가 결과 이벤트에 찍은 사실 시각. 값으로만 저장하고 판정에 쓰지 않는다(매출 귀속 월 전달용, [결정 166](../../../docs/decisions.md#sale-month-by-payment-confirmation)).
- **환불 상태 / 고객 상태**: 내부 `RefundStatus`(10개)와 고객에게 보이는 상태(`PENDING_REVIEW`·`PROCESSING`·`COMPLETED`·`FAILED`·`REJECTED`). 대응은 design "상태 모델".
- **환불 시도(`RefundAttempt`)**: 실제 보유권 정지를 확인한 뒤 만든 PG 실행 요청 한 건(`attemptId`). 상태는 `PENDING`·`COMPLETED`·`FAILED`·`LATE_CANCELLED`·`SUPERSEDED`(직전 시도의 늦은 취소로 PG 실행 전에 닫힘, [결정 179](../../../docs/decisions.md#late-cancellation-during-retry))이고, 취소 귀속(누가 취소했나)은 갖지 않는다([결정 164](../../../docs/decisions.md#attribution-in-payment)).
- **실패 출처(`failureSource`)**: 시도를 실패로 확정한 사실의 종류 — `PG_CANCELLATION_FAILED`(`RefundExecutionFailed`), `OPERATOR_FAILURE_CLOSED`(`RefundFailureClosed`)([P3.22](../platform/requirements.md)·[P3.23-4](../platform/requirements.md)).
- **늦은 취소 완료(`LATE_CANCELLATION_COMPLETED`)**: 실패로 확정된 시도 뒤 실제로 나타난 원결제 전액 취소를 Payment가 ADMIN의 PG 근거로 확인해(`RefundLateCancellationConfirmed`) Order가 환불을 끝낸 끝 상태([결정 157](../../../docs/decisions.md#late-cancellation-confirmation)). 상태 이름은 결정 157이 Order 모듈 스펙에 맡겼다.
- **심사 회차(`RefundReviewRound`)**: 최초(`INITIAL`)·고객 재심사(`CUSTOMER_RECONSIDERATION`)·관리자 정정(`APPROVAL_CORRECTION`) 판정 한 번의 기록. 판정의 정본이다.
- **승인 중단(`RefundApprovalStop`)**: ADMIN이 PG 실행 요청 저장 전에 잘못된 승인을 멈춘 기록. 단계는 `RECEIVED`·`SUSPENSION_OBSERVED`·`REOPENED`다.
- **대기 진입 시각**: 환불이 License·Payment 응답을 기다리는 상태에 들어간 Order 시계 시각(`licenseGrantWaitStartedAt` 등, 결정 125).
- **응답 대기**: Order가 낸 사실의 응답 사실(구매 근거 생성·매출 기록·보유권 회수)을 아직 받지 못한 자기 기록([결정 158](../../../docs/decisions.md#forward-gap-counting)).

## 요구사항

### R1. 단일 상품 주문 생성과 스냅샷 고정
**사용자 스토리:** 구매자로서, 확인한 공개 상품 하나를 그때의 가격으로 주문하고 싶다. 그래야 이후 가격이 바뀌어도 내 주문 조건이 보존된다.

#### 수용 기준
1. WHEN `POST /api/v1/orders` 요청이 `requestId`·`productCode`·`expectedContentVersion`·`expectedAmount`로 오면 THEN Order SHALL 구매자를 JWT `sub`로 식별한다.
2. WHEN 신규 요청이면 THEN Order SHALL Catalog 내부 주문 근거(`/internal/v1/order-products/{code}`)를 DB 트랜잭션 밖에서 읽고 상품 식별·현재 공개본·KRW 정가·판매 상태·`publicDetailAllowed`를 확인한다([P1.2-4](../platform/requirements.md)).
3. WHEN 상품 코드·양수 ID/버전·제목·양수 가격·KRW가 확인되면 THEN Order SHALL 기대 버전·기대 금액과 비교한다.
4. WHEN 비교가 일치하면 THEN Order SHALL `listAmount = Catalog 정가`·`discountAmount = 0`·`amount = listAmount − discountAmount`·`expiresAt = 생성 시각 + stove.order.payment-window`를 계산하고, 스냅샷·`PENDING`·`expires_at`과 이 값을 모두 실은 `OrderPlaced` Outbox를 같은 트랜잭션으로 저장한다([P3.10-1](../platform/requirements.md), I1, T12).
5. THE 주문의 금액·통화·구매자·상품 스냅샷·`expiresAt` SHALL 생성 이후 불변이며, 이후 Catalog 변경·설정 변경·결과 이벤트로 수정하지 않는다.
6. IF 기대 버전·기대 금액이 Catalog 정본과 다르면 THEN Order SHALL 409로 거절하고 주문·Outbox를 남기지 않는다.
7. WHERE 무료(0원) 상품 맥락에서 THE Order SHALL 양수 가격만 받아 409로 거절한다.
8. WHERE Catalog의 `publicDetailAllowed`가 false이거나 판매 상태가 판매 금지(`STOPPED`)인 맥락에서 THE Order SHALL 신규 주문을 각각 404·409 `PRODUCT_NOT_ON_SALE`로 거절한다. 같은 `requestId`의 기존 주문 재조회·기존 주문 승인 접수는 원래 계약을 유지한다(결정 89).

### R2. 요청 멱등과 재구매
**사용자 스토리:** 구매자로서, 재전송으로 주문이 여러 건 생기지 않기를 바라고, 같은 상품도 다시 살 수 있기를 바란다. 그래야 중복 청구가 없고 재구매를 막지 않는다(결정 89).

#### 수용 기준
1. THE Order SHALL `(buyer_subject, request_id)` 유일 제약(`uk_purchase_order_request`)으로 중복 생성을 막는다.
2. WHEN 같은 구매자·`requestId`·같은 입력(코드·기대 버전·기대 금액)의 재요청이 오면 THEN Order SHALL Catalog를 다시 호출하지 않고 기존 주문의 현재 상태를 반환한다.
3. IF 같은 구매자·`requestId`에 다른 입력이 오면 THEN Order SHALL 409로 거절하고 기존 주문을 보존한다.
4. WHEN 같은 요청이 동시에 생성되면 THEN Order SHALL DB 유일 제약으로 한 건만 확정하고, 패한 트랜잭션이 끝난 뒤 기존 주문을 다시 읽어 2·3을 적용한다.
5. WHEN 새 `requestId`로 같은 상품을 구매하면 THEN Order SHALL 기존 주문의 상태(`PENDING`·`PAYMENT_PROCESSING`·`PAID`·환불 완료 포함)와 무관하게 새 주문을 만든다. 구매를 막는 업무 사유는 판매 금지(R1.8)뿐이다(결정 89).
6. THE 중복 청구 방지 SHALL 같은 `requestId` 멱등(R2.1~4)이 맡고, 이미 가진 상품의 중복 보유는 License가 등록에서 막는다(결정 90·93).
7. THE Order SHALL 구매자·상품 단위의 구매 슬롯·유일 제약을 두지 않는다(V28에서 제거).
9. IF Catalog 조회·기대 값 검증이 실패했는데 같은 `requestId` 주문이 그사이 먼저 확정됐으면 THEN Order SHALL 원래 오류 대신 기존 주문에 2·3을 적용한다. Catalog 중단 중에도 기존 주문 조회·같은 요청 재조회는 제공한다.

### R3. 결제 기한과 승인 진행
**사용자 스토리:** 구매자로서, 결제창을 이탈해도 주문이 안전하게 만료되고, 유효한 주문으로 시작한 결제는 지연이 있어도 완료되기를 바란다.

#### 수용 기준
1. THE 주문 상태 SHALL `PENDING`·`PAYMENT_PROCESSING`·`PAID`·`FAILED`·`EXPIRED` 중 하나이고, DB CHECK가 이 값만 허용한다(원칙 ④, MC-O1).
2. THE 결제 기한 SHALL R1.4에서 저장한 `expires_at`이며, 조회·재시도·요청 재전송·설정 변경으로 바꾸지 않는다. 주문 조회에 `expiresAt`을 제공한다. 다른 모듈은 이 값을 다시 계산하지 않는다([P1.4-3](../platform/requirements.md)·[P3.10-2](../platform/requirements.md)).
3. WHEN 승인 진행 접수(`POST /internal/v1/orders/{orderNo}/payment-admissions`)가 오면 THEN Order SHALL `X-Payment-Service-Key`를 상수 시간 비교로 검사하고, 판매 제한 여부를 Catalog에서 트랜잭션 밖으로 읽은 뒤, 주문 행 잠금 아래 Order 시계로 기한을 판정하고 구매자·원주문 이벤트(`requestEventId`)·청구액·통화를 대조한다([P3.10-3](../platform/requirements.md)).
4. WHEN 접수가 성공하면 THEN Order SHALL 주문 `PAYMENT_PROCESSING`·`paymentId` 연결과 `OPEN` 영수증(`admissionId`·`paymentId`·`admittedAt`·원래 `expiresAt`)을 같은 트랜잭션으로 저장한다.
5. IF 상태가 `PENDING`이어도 Order 시계가 `expiresAt`에 이르렀으면 THEN Order SHALL 먼저 `EXPIRED`와 `OrderExpired`를 커밋한 뒤 HTTP 409 `ORDER_EXPIRED`를 반환한다.
6. WHILE 주문이 `PAYMENT_PROCESSING`이면 THE Order SHALL 시각 경과만으로 `EXPIRED`·`FAILED`로 바꾸지 않으며, 기한 안에 연 영수증의 결과는 기한 뒤에 와도 원래 주문·금액으로 반영한다([P3.10-4](../platform/requirements.md), 결정 32).
7. WHERE 주문이 `EXPIRED`인 맥락에서 THE Order SHALL 새 승인 접수를 409 `ORDER_EXPIRED`로 거절하고 새 `requestId` 주문으로 안내하며, 기존 주문·영수증을 보존한다.
8. WHEN 같은 `admissionId`·같은 전체 입력의 접수가 재전송되면 THEN Order SHALL 만료·`PAID` 이후에도 최초 영수증을 반환한다. IF 같은 `admissionId`를 다른 내용으로 쓰거나 `PAYMENT_PROCESSING`·`PAID`·`FAILED` 주문에 새 `admissionId`를 요청하면 THEN Order SHALL 409로 거절한다.
9. THE 기한 경계 SHALL `expiresAt` 직전 마이크로초까지 접수하고 `expiresAt`부터 새 접수를 거절한다. 구매자·원주문 이벤트·금액이 맞지 않는 접수는 거절만 하고 만료를 일으키지 않는다.
10. WHEN Payment가 검증된 개별 시도 실패를 `POST /internal/v1/orders/{orderNo}/payment-admissions/{admissionId}/failure`로 통지하면 THEN Order SHALL 주문 행 잠금 아래 그 영수증이 이 주문의 열린 최신 영수증이고 결과가 없을 때만, Order 시계가 기한 전이면 `PENDING`(같은 주문 재결제 허용), 기한 뒤면 `EXPIRED` + `OrderExpired`로 바꾸고 영수증을 `ATTEMPT_FAILED`로 닫는다. 통지의 `failedAt`(Payment·결제 공급자 시각)은 값으로만 저장하고 Order 시계와 비교하지 않는다([P4.5-1](../platform/requirements.md), 원칙 ⑥). 이미 닫힌 영수증의 같은 통지 재전송은 최초 영수증을 반환하고 이후 승인 진행을 바꾸지 않으며, 다른 내용이면 409다.
11. IF `EXPIRED` 주문에 성공 결과가 도착하면 THEN Order SHALL 구매 성공으로 반영하지 않고 R4.9의 결과 거절 기록과 `OrderPaymentResultRejected`를 남긴다. 409로 DLT에 보내지 않고, 자동 취소도 하지 않는다([결정 153](../../../docs/decisions.md#late-success-rejection)).
12. WHEN 만료 스캔·기한 후 승인 접수 거절·기한 후 시도 실패 통지가 주문을 처음 `EXPIRED`로 바꾸면 THEN Order SHALL 같은 트랜잭션에 `OrderExpired`(원 `requestEventId`·구매자·청구액·통화·`expiresAt`)를 쓰고, 반복 만료·재전송에는 새 통지를 내지 않는다([P3.10-5](../platform/requirements.md)).
13. THE 내부 승인 접수·시도 실패 통지 API SHALL `X-Payment-Service-Key`만 허용하며 MEMBER Bearer로 부를 수 없고 Gateway·공개 명세에 노출하지 않는다([P1.2-2·3](../platform/requirements.md)).
14. WHEN 만료 스캔(`OrderExpiryScheduler`)이 돌면 THEN Order SHALL `status = 'PENDING' AND expires_at <= now`(Order 시계)인 주문만 고르고, 잠금 뒤 상태·기한을 다시 확인해 바꾼다([P3.10-5](../platform/requirements.md)).

### R4. 결제 결과 반영
**사용자 스토리:** 구매자로서, 내 결제 결과가 정확히 내 주문에 반영되기를 바란다. 그래야 성공·실패를 신뢰할 수 있다.

#### 수용 기준
1. WHEN `OrderPaymentCompleted`가 처음 오면 THEN Order SHALL 주문 행 잠금 아래 주문 연결값 5개(`orderNo`·`requestEventId`·`buyerSubject`·`amount`·`currency`)와 `admissionId`·`paymentId`가 이 주문의 열린 최신 영수증과 같을 때만(주문 `PAYMENT_PROCESSING`) 주문을 `PAID`로, 영수증을 `RESULT_APPLIED`로 바꾸고 결과 식별(`paymentResultEventId`·`resultOccurredAt`·결과 종류 `COMPLETED`)·반영한 영수증 ID(`payment_admission_id`)·`paidAt`·Inbox(`order-payment-result`)·`OrderPaid` Outbox를 같은 트랜잭션에 쓴다([P3.14-1](../platform/requirements.md), I12). (1회차 지적 A3, 판정 전)
2. THE 결과 반영 SHALL 주문 행 잠금·결과 이벤트 ID 교차 조회·Inbox 가드·상태 변경·Outbox를 같은 트랜잭션으로 처리한다.
3. IF 이미 반영한 결과의 `eventId`·내용이 재전달되면 THEN Order SHALL 무변화로 끝내고 확정 결과를 덮어쓰지 않는다. `PAID`·`FAILED`는 끝 상태다.
4. IF 결과의 주문 연결값이 주문과 다르거나, 결과가 가리키는 주문이 없거나, 같은 결과 `eventId`를 다른 주문이 이미 썼거나(반영 결과·거절 기록·종결 기록 모두), 이미 반영한 결과와 같은 `eventId`로 다른 내용이 오면 THEN Order SHALL 주문을 바꾸지 않고 영구 실패로 거절해 실패 보관·경보로 보낸다([P3.1-6](../platform/requirements.md)·[P2.6-3](../platform/requirements.md)).
5. WHEN 주문을 처음 `PAID`로 바꾸면 THEN Order SHALL 같은 트랜잭션에서만 `OrderPaid`를 쓴다. `FAILED`·거절·재전달에는 쓰지 않는다([P3.14-5](../platform/requirements.md), I3).
6. WHEN 같은 주문의 결과가 동시에 도착하면 THEN Order SHALL 주문 행 잠금으로 직렬화해 먼저 커밋한 결과를 보존한다. Inbox만 있고 맞는 반영 결과·거절 기록·종결 기록이 없으면 정상 중복으로 넘기지 않고 영구 실패로 거절한다.
7. THE 완료 결과의 `amount` SHALL 고객 청구액이며 수수료 공제 뒤 입금액·판매자 지급액과 비교하지 않는다. 선택적 `pgPaidAmount`·`pgDiscountAmount`는 둘 다 있고 합계가 `amount`일 때만 받는다(I1).
8. WHEN `OrderPaymentReviewClosed`(운영자의 승인 보류 종결, [결정 174](../../../docs/decisions.md#review-closed-event))가 처음 오면 THEN Order SHALL 주문 연결값을 대조한 뒤 (a) `PAYMENT_PROCESSING`이고 `admissionId`·`paymentId`가 열린 최신 영수증과 같거나 (b) `PENDING`이고 `admissionId`가 null이면 주문을 `FAILED`(결과 종류 `REVIEW_CLOSED`)로 바꾸고, (c) `EXPIRED`이면 주문을 `EXPIRED`로 둔 채 종결 기록(`order_expired_review_closure`)과 Inbox를 같은 트랜잭션에 남긴다([P3.13-4](../platform/requirements.md), 결정 115). 같은 종결의 재전달은 무변화다. 같은 주문의 다른 종결 결과, (a)·(b)·(c) 밖의 조합은 영구 실패다. 이행 기간에는 `OrderPaymentFailed`(`reasonCode = REVIEW_CLOSED`)를 `api.listener`에서 같은 내부 입력으로 바꿔 받는다([P2.7-4](../platform/requirements.md)).
9. IF 성공 결과(`OrderPaymentCompleted`)의 주문 연결값은 맞지만 R4.1로 반영할 수 없으면(주문 `PENDING`·`EXPIRED`·`FAILED`, 영수증 `ATTEMPT_FAILED`, 다른 `admissionId`, 이미 다른 결과로 `PAID`) THEN Order SHALL 주문·영수증을 바꾸지 않고 `OrderPaid`를 만들지 않으며, 같은 트랜잭션에 Inbox·결과 거절 기록(`order_payment_result_rejection`)·`OrderPaymentResultRejected`(주문 연결값 5개·`rejectedResultEventId`·`paymentId`·`admissionId`·`rejectionReason` ∈ {`NO_OPEN_ADMISSION`, `ADMISSION_MISMATCH`, `ORDER_CLOSED`}) Outbox를 쓴다. 같은 거절 결과의 재전달은 무변화이고 Outbox를 다시 쓰지 않는다([P3.14-2](../platform/requirements.md), [결정 153](../../../docs/decisions.md#late-success-rejection), I12). (1회차 지적 A1, 판정 전)
10. WHEN `OrderPaymentFailed`(결제 공급자가 그 결제를 최종 거절함)가 처음 오고 주문 연결값이 맞으면 THEN Order SHALL (a) `PAYMENT_PROCESSING`이고 `admissionId`·`paymentId`가 열린 최신 영수증과 같거나 (b) `PENDING`이고 `admissionId`가 null일 때 주문을 `FAILED`(결과 종류 `DECLINED`, 사유 코드·사유 저장)로 바꾸고 영수증이 있으면 `RESULT_APPLIED`로 닫는다([P3.14-3](../platform/requirements.md)). IF 주문이 `EXPIRED`이면 THEN Order SHALL [결정 필요 M-OR-2]를 따른다. 그 밖의 조합은 영구 실패다. (1회차 지적 B6, 판정 전)
11. THE 반영한 결과의 사실 종류 SHALL 결과 종류 enum 열(`payment_result_kind`)에 저장하고 DB CHECK가 `COMPLETED`·`DECLINED`·`REVIEW_CLOSED`만 허용한다. 종류를 사유 문자열(`failure_reason_code`)로 판정하지 않는다(원칙 ①④, [결정 174](../../../docs/decisions.md#review-closed-event)).
12. WHEN `OrderPaid`를 쓰면 THEN Order SHALL 본문에 저장한 결과 시각(`resultOccurredAt`)을 그대로 `paymentConfirmedAt`(가칭)으로 싣는다. Order는 이 값을 판정에 쓰지 않는다([P3.15-3](../platform/requirements.md), [결정 166](../../../docs/decisions.md#sale-month-by-payment-confirmation)). (1회차 지적 A6, 판정 전)
13. WHEN 주문을 처음 `PAID`로 바꾸면 THEN Order SHALL 그 트랜잭션의 Order 시계 시각을 `paid_at`에 저장하고, DB CHECK가 `status <> 'PAID' OR paid_at IS NOT NULL`을 지킨다. 이 값은 자동 환불 기한(R6.4)과 응답 대기(R10)의 시작이다([결정 173](../../../docs/decisions.md#refund-window-order-clock), MC-O4).
14. WHEN 결과 이벤트에 `admissionId`가 없으면(이 칸을 싣기 전의 옛 본문) THEN Order SHALL [결정 필요 M-OR-3]을 따른다. 소비자는 (`eventType`, `schemaVersion`)으로 `PaymentResultMapper`를 고른다([P2.7-2](../platform/requirements.md), [결정 170](../../../docs/decisions.md#schema-version-header)). (1회차 지적 A3, 판정 전)

### R5. 내 주문 조회와 최소 보안
**사용자 스토리:** 구매자로서, 내 주문만 목록·상세로 보고 싶다. 그래야 타인 주문이 노출되지 않는다.

#### 수용 기준
1. WHERE 주문 생성·조회 맥락에서 THE Order SHALL `MEMBER` 역할과 JWT 서명·issuer·만료·`aud=esd-api`를 검증한다.
2. WHEN `GET /api/v1/orders` 요청이 오면 THEN Order SHALL `buyerSubject`로 제한한 본인 주문 페이지(page=0·size=20, 1≤size≤100, `createdAt`·`id` 내림차순)를 반환한다.
3. WHEN `GET /api/v1/orders/{orderNo}` 요청이 오면 THEN Order SHALL 본인 주문 상세를 반환하고 타인·없는 주문은 404로 처리한다.
4. THE 상세 SHALL `orderNo`·상품 스냅샷·정가·할인액·청구액·통화·상태·생성/갱신 시각·`expiresAt`·결과 시각·실패 사유를 제공하고, 내부 `eventId`·Inbox·Outbox·영수증·거절 기록·결과 내부 연결 정보는 노출하지 않는다.
5. IF 익명·역할 없음·잘못된 입력이면 THEN Order SHALL 각각 401·403·400으로 처리한다.
6. IF 본문·쿼리·헤더로 소유자·상태를 위조하려 하면 THEN Order SHALL 이를 주문 소유자·상태에 반영하지 않는다.

### R6. 전액 환불 접수·조회·관리자 심사·판정
**사용자 스토리:** 구매자로서, 구매한 상품의 전액 환불을 신청하고 그 상태를 확인하고 싶다. 운영자로서, 환불을 심사·판정하고 싶다.

#### 수용 기준
1. WHEN `POST /api/v1/orders/{orderNo}/refund` 요청이 오면 THEN Order SHALL 주문 행 잠금 뒤 MEMBER 본인·`PAID`·환불 없음을 확인하고 서버 원금 전액 환불 한 건을 저장한 다음 응답한다. 신규·진행 중은 202, 끝 상태는 200이다([P3.16-1](../platform/requirements.md)).
2. WHEN `GET /api/v1/orders/{orderNo}/refund` 요청이 오면 THEN Order SHALL 본인 환불의 고객 상태·첫 접수 시각·완료 시각·반려 사유·안내(`failureNotice`·`processingNotice`·`completionNotice`)를 제공한다.
3. THE 환불 상태 SHALL `PENDING_REVIEW`·`PENDING_LICENSE_GRANT`·`PENDING_LICENSE_SUSPENSION`·`PENDING_APPROVAL_STOP`·`PENDING_PAYMENT_REFUND`·`PENDING_LICENSE_RESTORATION`·`FAILED`·`REJECTED`·`COMPLETED`·`LATE_CANCELLATION_COMPLETED` 중 하나이고 DB CHECK가 이 값만 허용한다. 고객 상태는 `PENDING_REVIEW`·`PROCESSING`·`COMPLETED`·`FAILED`·`REJECTED` 중 하나다(대응은 design). `FAILED`는 실패 확정과 실제 보유권 복구 뒤에만 보이며 고객용 실패 안내 `failureNotice`(code `PAYMENT_NOT_CANCELLED`·"다시 신청하면 환불됩니다")를 함께 보인다. code는 현재 시도의 실패 출처에서 옮기고, 옮길 수 없는 값은 추측하지 않고 409다. `REJECTED`는 고객에게 보이는 `rejectionReason`과 함께 보인다. 내부 코드·PG 오류 원문은 보이지 않는다([결정 134](../../../docs/decisions.md#refund-failure-customer-notice)·[98](../../../docs/decisions.md#storage-return-no-notification)).
4. WHEN 최초 접수가 오면 THEN Order SHALL 주문 행 잠금 전 Order 시계로 요청 수신 시각을 정해 처음 저장하고, 그 시각이 같은 Order 시계의 `paid_at` + 4시간 이내(정확히 4시간 포함, 마이크로초 정밀도)면 자동 허용(`automaticRefundEligible = true`)하고, 넘으면 관리자 심사(`PENDING_REVIEW`)로 보낸다. Payment 결제 확정 시각(`resultOccurredAt`)과 비교하지 않는다. 이후 처리 지연·재시도·재심사·명시적 재시도로 첫 접수 시각과 자격을 다시 판정하지 않는다([P3.16-3](../platform/requirements.md), [결정 173](../../../docs/decisions.md#refund-window-order-clock), 결정 29).
5. WHEN 같은 주문에 접수가 반복·동시에 오면 THEN Order SHALL 주문 행 비관적 잠금과 `uk_order_refund_order`로 환불 한 건만 만들고 기존 `refundId`·첫 접수 시각을 반환한다. 외부 호출을 기다리며 잠금을 보유하지 않는다([P3.16-2](../platform/requirements.md)).
6. WHEN `GET /api/v1/admin/refunds`(기본 `PENDING_REVIEW`)·`GET /api/v1/admin/refunds/{refundId}` 요청이 오면 THEN Order SHALL `ADMIN`에게 상태별 목록·원구매·처리 상태·최초 판정 상세를 제공한다.
7. WHEN `POST /api/v1/admin/refunds/{refundId}/decision`이 `requestId`·`APPROVED`/`REJECTED`·사유로 오면 THEN Order SHALL JWT `sub`를 판정자로 1회차 심사 회차에 기록하고, 먼저 커밋된 판정을 확정한다. 같은 명령·판정자·내용만 최초 영수증으로 재전송 처리하고 판정 변경·다른 명령은 409다. 판정의 정본은 심사 회차 하나다(원칙 ③).
8. WHEN 승인 또는 자동 허용되면 THEN Order SHALL 실제 구매 근거 생성 사실(`PurchaseRecordStored`, 지금 이름 `LicenseGranted`, [결정 175](../../../docs/decisions.md#purchase-record-stored))을 확보한 뒤 `LicenseSuspensionRequested`를 쓰고, 실제 정지 완료(`LicenseSuspended`)를 확인한 뒤에만 첫 `RefundAttempt`·Inbox·`RefundExecutionRequested` Outbox를 같은 트랜잭션으로 저장한다. 심사 중에는 보유권을 유지하고 PG 실행과 정지를 병행하지 않는다([P3.17](../platform/requirements.md)·[P3.18](../platform/requirements.md)).
9. WHEN 구매 근거 생성 사실이 환불 접수보다 먼저 도착하면 THEN Order SHALL 지급 근거(`order_license_grant`)를 저장해 두고 이후 허용된 환불이 대기 없이 정지 요청으로 진행하게 한다.
10. IF 정지 완료의 주문·환불·지급·정지 요청 ID·구매 연결값이 Order 근거와 다르거나 원래 요청을 찾을 수 없으면 THEN Order SHALL 업무를 진행하지 않고 영구 실패로 거절하며 환불 실패·보유권 복구로 바꾸지 않는다. 같은 정지 완료의 재전달은 새 실행 시도를 만들지 않는다.
11. THE 조회·판정 응답 SHALL `no-store`이며, 관리자 200은 환불 완료를 뜻하지 않는다. 원구매 `PAID`·원래 금액·상품·구매 근거를 환불 상태로 덮어쓰지 않는다.

### R7. 환불 금융 결과 반영·실패 복구·늦은 취소
**사용자 스토리:** 구매자로서, 실제로 돈이 돌아왔을 때만 환불 완료를, 확정 실패면 보유권 복구를 보고 싶다.

#### 수용 기준
1. WHEN 주문·환불·현재 시도·실행 요청·금액(= 주문 청구액)이 식별자로 맞는 `RefundPaymentConfirmed`를 처음 받으면 THEN Order SHALL 환불 `COMPLETED`·시도 `COMPLETED`·최초 금융 확인 지문·완료 시각(Payment 확인 시각, 값으로만)·`purchase_refunded_at`(Order 시계)·Inbox를 원자 저장하고, 원구매 `PAID`·금액은 바꾸지 않는다. 시도에 취소 귀속을 기록하지 않으며 `source` 칸으로 분기하지 않는다([P3.21-3](../platform/requirements.md), [결정 164](../../../docs/decisions.md#attribution-in-payment)).
2. THE Order SHALL `RefundExecutionSucceeded`를 소비하지 않는다. 이 이벤트가 오면 `api.listener`가 업무 처리 없이 건너뛰고 건너뛴 수를 지표로 낸다([결정 164](../../../docs/decisions.md#attribution-in-payment), [P2.3-7](../platform/requirements.md)). (1회차 지적 A4·B5, 판정 전)
3. WHEN 구매·현재 시도·실행 요청·금액이 식별자로 맞는 `RefundExecutionFailed`(Payment 토픽만) 또는 `RefundFailureClosed`(Payment 토픽만)를 처음 받고 환불이 `PENDING_PAYMENT_REFUND`·금융 확인 없음·시도 `PENDING`이면 THEN Order SHALL 두 이벤트를 `api.listener`에서 같은 실패 확정 입력으로 바꾸되 실패 출처(`PG_CANCELLATION_FAILED`·`OPERATOR_FAILURE_CLOSED`)와 `closureId`(운영자 종결일 때만)를 남기고, 시도 `FAILED`·Inbox·`LicenseRestorationRequested` Outbox·환불 `PENDING_LICENSE_RESTORATION`을 원자 저장한다. 현재 요청·시도·정지·구매와 맞는 실제 `LicenseRestored`(License 토픽만)를 확인한 뒤에만 환불을 `FAILED`로 바꾼다. 복구 요청 저장만으로 복구 완료라 하지 않는다([P3.22-2](../platform/requirements.md)·[P3.23-4](../platform/requirements.md)·[P3.24-2](../platform/requirements.md), I16·I17).
4. THE Order SHALL 과거 정지·복구 메시지로 다른(새) 정지를 바꾸지 않고, 실패 확정 뒤 새 실행 시도를 자동으로 만들지 않는다. 시간 경과·타임아웃만으로 실패·복구하지 않는다.
5. WHEN 환불을 처음 `COMPLETED` 또는 `LATE_CANCELLATION_COMPLETED`로 확정하면 THEN Order SHALL 같은 트랜잭션에 `PurchaseRefunded`(`refundId`·`licenseId`·`orderNo`·구매자·상품·`paymentId`·그 시도의 정지 요청 ID, 키 `orderNo`) Outbox를 저장한다. 재전달은 다시 쓰지 않는다(결정 96). `PurchaseRefunded` 도입 전에 끝난 환불은 V30이 같은 본문으로 한 번 채웠다(결정 99).
6. WHEN 복구 완료(`LicenseRestored`·`LicenseApprovalCorrected`)의 `returnedToStorage`가 true면 THEN Order SHALL 환불에 남기고 고객 환불 응답의 `purchaseReturnedToStorage`로 보여 준다. 따로 알리지 않는다(결정 98).
7. WHEN `RefundLateCancellationConfirmed`(Payment 토픽, 그룹 `order-refund-restoration`)를 처음 받고 지정 시도가 이 환불의 현재 시도이며 실패 확정된 시도이고 환불이 `PENDING_LICENSE_RESTORATION` 또는 `FAILED`이면 THEN Order SHALL 시도를 `LATE_CANCELLED`로, 환불을 `LATE_CANCELLATION_COMPLETED`로 바꾸고 늦은 취소 확인 식별(`eventId`·Payment 확인 ID)·`purchase_refunded_at`·Inbox·`PurchaseRefunded` Outbox를 같은 트랜잭션에 쓴다. 시도의 실패 사실(`failureEventId`·실패 출처)은 지우지 않는다. 고객 상태는 `COMPLETED`이고 `completionNotice`로 환불 완료와 보유권 회수를 안내한다([P3.23-8](../platform/requirements.md), [결정 157](../../../docs/decisions.md#late-cancellation-confirmation), I4·P4.7-5, MC-O3). IF 같은 환불에 고객 재신청이 이미 접수돼 Payment가 아직 그 재신청을 모르면(① 환불이 재시도 정지 대기 `PENDING_LICENSE_SUSPENSION`이고 지정 시도가 현재 실패 시도, ② 환불이 `PENDING_PAYMENT_REFUND`이고 지정 시도가 현재 `PENDING` 시도의 `previousAttemptId`) THEN Order SHALL 같은 전이로 끝내되 ②의 현재 시도를 `SUPERSEDED`로 닫고, `PurchaseRefunded`의 정지 요청 ID는 지정 시도의 것을 쓴다. 그 뒤 오는 재신청 정지(`LicenseSuspended`)는 기록만 하고 새 시도를 만들지 않는다. 그 밖의 조합(지정 시도가 직전 실패 시도도 현재 시도도 아님, 또는 현재 시도가 실패로 끝났는데 지정 ≠ 현재)은 영구 실패다 — Payment가 그 환불의 마지막 `FAILED` 실행에만 반영하기 때문이다([결정 179](../../../docs/decisions.md#late-cancellation-during-retry)). (1회차 지적 A2·B2, 판정 전) (1회차 지적 A5, 판정 전)
8. THE Order SHALL 환불 이벤트의 시도·정지·복구·정정 동일성을 식별자(`attemptId`·`executionRequestEventId`·`suspensionRequestEventId`·`suspensionEventId`·`restorationRequestEventId`·`failureEventId`·`previousAttemptId`·`licenseId`)·상태·주문 행 잠금으로만 판정하고, 다른 모듈·결제 공급자가 찍은 시각(`occurredAt`·`firstPreparedAt`·`providerCancelledAt` 등)을 Order 시각이나 다른 모듈 시각과 비교해 거절하지 않는다. 받은 시각은 값으로만 저장한다([P4.5-1](../platform/requirements.md), U2 ORD-1~ORD-10, T3).

### R8. 환불 확장: 명시적 재시도·재심사·잘못된 승인 중단·지연 안내·재발행
**사용자 스토리:** 구매자로서, 실패·반려 뒤 다시 요청하고 지연 사유를 알고 싶다. 운영자로서, 잘못 승인한 환불을 PG 실행 전에 멈추고, 사라진 이벤트를 같은 ID로 다시 내고 싶다.

#### 수용 기준
1. WHERE 현재 시도가 실패 확정되고 실제 보유권 복구가 끝난(`restoredEventId` 있음) 환불 `FAILED` 맥락에서만 THE Order SHALL 고객 조회에 `retryableAttemptId`를 제공하고 `POST /api/v1/orders/{orderNo}/refund/retries`(`previousAttemptId`)를 202로 접수한다. 같은 `previousAttemptId` 재전송은 최초 재시도 영수증(`refundId`·`previousAttemptId`·`receivedAt`)을 반환하고 후속 시도가 또 실패한 뒤 와도 새 실행을 만들지 않는다([P3.24-3](../platform/requirements.md)).
2. WHEN 명시적 재시도를 접수하면 THEN Order SHALL `refundId`·첫 접수 시각·원래 자동 자격·이전 실패/복구 이력을 보존하고, 새 정지 요청 → 실제 새 정지 완료 뒤에만 다음 순번의 새 `attemptId`와 `previousAttemptId`를 담은 `RefundExecutionRequested`를 저장한다. 실제 복구 전 요청은 409로 거절한다. 복구 완료는 `restoredEventId`로 판정하고 License 시각과 비교하지 않는다(U2 ORD-5·ORD-6). 결과 미확정·`REJECTED`·`LATE_CANCELLATION_COMPLETED`·Payment 운영 중단은 재시도 대상이 아니다.
3. WHEN `REJECTED` 뒤 고객이 `POST /api/v1/orders/{orderNo}/refund/reconsiderations`(`requestId`·`previousReviewId`·`reason`)를 보내면 THEN Order SHALL 마지막 반려 저장 시각(Order 시계) + 7×24시간(경계 포함) 이내·사유 필수(공백 불가, 최대 500자)일 때 새 심사 회차로 접수한다. 횟수 상한은 없고 같은 신청 재전송은 새 회차로 세지 않으며, 기한 안 접수는 심사 지연으로 효력을 잃지 않는다.
4. WHEN ADMIN이 `POST /api/v1/admin/refunds/{refundId}/reconsiderations/{reviewId}/decision`으로 회차를 판정하면 THEN Order SHALL 회차별로 먼저 커밋된 판정을 확정하고 과거 회차의 명령 재전송이 이후 회차를 바꾸지 않게 한다. 고객·ADMIN `GET .../reviews`는 페이지로 전체 이력을 제공하며 고객 응답에는 관리자 명령 ID·판정자·승인 사유를 노출하지 않는다.
5. WHEN ADMIN이 `POST /api/v1/admin/refunds/{refundId}/approval-stops`(`requestId`·현재 승인 `reviewId`·필수 사유)를 보내면 THEN Order SHALL `RefundExecutionRequested`를 저장하기 전(첫 시도 전)에만 202 최초 영수증으로 접수하고, 실행 요청이 먼저 저장됐으면 PG 송신 전이어도 409로 거절한다. 중단과 정지 완료는 같은 주문 잠금으로 순서를 정한다([P3.27-1](../platform/requirements.md)).
6. WHEN 승인 중단이 접수되면 THEN Order SHALL 정지 요청 전이면 같은 트랜잭션에서 바로, 정지 요청 뒤면 실제 정지 완료와 그 정지의 승인 정정 복구(`LicenseApprovalCorrectionRequested` → `LicenseApprovalCorrected`) 완료 뒤 같은 환불의 관리자 정정 회차(`PENDING_REVIEW`)로 돌아간다. 늦은 정지 완료는 PG 실행 요청이 아니라 정정 복구로 잇고, 복구 완료 전에는 새 판정을 받지 않으며 과거 승인·중단·최초 접수/자격을 보존한다. 정지 관찰·복구 관찰·재개 판정에 License 시각을 쓰지 않는다([P3.27-2](../platform/requirements.md), U2 ORD-7·8·9).
7. WHEN 식별자가 맞는 `RefundExecutionPrepared`를 처음 받으면 THEN Order SHALL 받은 최초 준비 시각과 결과 확인 지연 시각(`confirmationDelayAt`)을 그대로 Inbox와 함께 원자 저장하고, 두 값을 다시 계산하거나 서로·Order 시각과 비교해 거절하지 않는다([P1.4-4](../platform/requirements.md)·[P3.19-3](../platform/requirements.md), T14, U2 ORD-1). 현재 시도가 `PENDING`인 동안 Order 시계가 `confirmationDelayAt`에 이르면 고객 조회에 `processingNotice`(code·message·since)를 제공한다. 준비 근거가 없으면 접수 시각으로 추정하지 않고 null이며, 완료·실패/복구·새 시도에서는 오래된 안내를 내지 않는다. 고객 조회는 Payment HTTP를 호출하지 않는다. (1회차 지적 B7, 판정 전)
8. WHEN 환불이 `PENDING_LICENSE_GRANT`에 들어가면(자동 허용 접수, 최초 승인, 재심사·정정 회차 승인) THEN Order SHALL 그 Order 시각을 `licenseGrantWaitStartedAt`으로 저장하고, 다시 들어가면 새 시각으로 바꾼다. 대기를 벗어나도 운영 조회용으로 남긴다. ADMIN 환불 목록·상세는 이 시각을 보인다(결정 125).
9. WHILE 환불이 `PENDING_LICENSE_GRANT`이고 진입 시각 + `stove.order.refund.license-grant-delay`(기본 10분, 경계 포함)가 지났으면 THE Order SHALL 게이지 `stove.order.refund.license.grant.delayed`에 세고, 고객 조회에 `processingNotice`(code `LICENSE_GRANT_CONFIRMATION_DELAYED`·message·since=경계 시각)를 제공한다. 고객 상태는 `PROCESSING` 그대로이며, 지급 확인이 반영되면 집계·안내에서 빠진다. 재처리는 결정 37 절차(재큐잉·재투입)·R8.15 재발행으로 하고 새 이벤트를 만들지 않는다([P3.17-3](../platform/requirements.md)).
10. WHEN 원본이 Kafka에서 사라져 ADMIN이 Outbox 운영 API(`POST /api/v1/ops/outbox/{eventId}/republish`, 필수 사유 1~500자)로 `SENT` `OrderPaid`를 재발행하면 THEN Order SHALL 원래 `event_id`·본문·키를 그대로 둔 채 발행 대기로 돌리고 실행자·시각·사유·이전 발행 시각을 `outbox_republish`에 같은 트랜잭션으로 남긴다. 없는 행·`SENT`가 아닌 행은 404, 허용 목록 밖 타입·사유 없음은 400으로 거절한다. License가 같은 ID로 재발행한 구매 근거 생성 사실이 미처리면 `PENDING_LICENSE_GRANT`를 정지 요청으로 잇고, 이미 처리됐으면 효과가 없다(결정 129).
11. WHEN 환불이 `PENDING_LICENSE_SUSPENSION`(지급 확인 — 자동 접수·심사 승인·재심사 승인 때 지급 근거가 이미 있는 경우 포함 — 또는 명시적 재시도), `PENDING_LICENSE_RESTORATION`(실패 확정), `PENDING_APPROVAL_STOP`(승인 중단)에 들어가면 THEN Order SHALL 그 Order 시각을 상태별로 `licenseSuspensionWaitStartedAt`·`licenseRestorationWaitStartedAt`·`approvalStopWaitStartedAt`에 저장하고, 다시 들어가면 새 시각으로 바꾸며, 벗어나도 남긴다. ADMIN 환불 목록·상세는 세 시각을 보인다(결정 125 범위 확장).
12. WHILE 환불이 위 세 상태 중 하나이고 그 상태의 진입 시각 + `stove.order.refund.license-grant-delay`가 지났으면 THE Order SHALL 게이지 `stove.order.refund.license.grant.delayed`의 `status` 라벨(`PENDING_LICENSE_GRANT`·`PENDING_LICENSE_SUSPENSION`·`PENDING_LICENSE_RESTORATION`·`PENDING_APPROVAL_STOP`)별로 세고, 고객 조회에 같은 모양의 `processingNotice`(code `LICENSE_SUSPENSION_CONFIRMATION_DELAYED`·`LICENSE_RESTORATION_CONFIRMATION_DELAYED`·`APPROVAL_STOP_CONFIRMATION_DELAYED`, since=경계 시각)를 제공한다. 복구 대기 문구는 PG 실패가 확정돼 돈이 돌아가지 않았고 보유권이 돌아오면 다시 신청할 수 있다는 사실을 알린다. 경보 `RefundLicenseGrantsDelayed`는 식을 바꾸지 않고 상태별 시계열마다 울린다.
13. WHEN ADMIN이 Order에서 `SENT` `LicenseSuspensionRequested`·`LicenseRestorationRequested`·`LicenseApprovalCorrectionRequested`를 재발행하면 THEN Order SHALL R8.10과 같이 처리한다. Order는 자기가 소유한 이벤트만 재발행하고 다른 모듈 소유 타입의 행은 400으로 거절한다. License가 같은 ID로 재발행한 `LicenseSuspended`가 미처리면 `PENDING_LICENSE_SUSPENSION`을 PG 실행 요청으로, `LicenseRestored`는 `PENDING_LICENSE_RESTORATION`을 `FAILED`로, `LicenseApprovalCorrected`는 `PENDING_APPROVAL_STOP`을 정정 회차로 잇고, 이미 처리됐으면 Inbox에서 효과가 없다(결정 129 범위 확장).
14. WHILE 환불이 `PENDING_PAYMENT_REFUND`이고 현재 시도의 `RefundExecutionPrepared`를 받지 못했으면 THE Order SHALL 시도 시작 시각(`startedAt`, Order 시계) + `stove.order.refund.license-grant-delay`부터 같은 게이지에 `status` 라벨 `PENDING_PAYMENT_REFUND`로 세고, 같은 경보 규칙을 적용하며, 고객 조회에 `processingNotice`(code `REFUND_EXECUTION_RECEIPT_DELAYED`, since=경계 시각)를 제공한다. 준비 사실이 반영되면 집계·안내에서 빠진다([P3.19-4](../platform/requirements.md), 결정 158·125 적용, T15).
15. THE Order 재발행 허용 목록 SHALL 플랫폼 design 이벤트 목록에서 소유가 Order이고 상태가 "현행"인 이벤트 전부(`OrderPlaced`·`OrderPaid`·`OrderExpired`·`LicenseSuspensionRequested`·`RefundExecutionRequested`·`PurchaseRefunded`·`LicenseRestorationRequested`·`LicenseApprovalCorrectionRequested`, 도입 뒤 `OrderPaymentResultRejected`)이며 그 표에서 만들거나 표와 대조하는 테스트로 지킨다. 재발행 규칙은 R8.10과 같다([P2.2-7](../platform/requirements.md), [결정 161](../../../docs/decisions.md#republish-all-current), T7).

### R9. 환불 금액 상세
**사용자 스토리:** 구매자·운영자로서, 주문 금액과 확인된 반환 구성을 한 응답에서 보고 싶다.

#### 수용 기준
1. THE 고객·ADMIN 환불 응답 SHALL 기존 `amount`(원주문 금액)·`COMPLETED` 의미를 유지하고 `amountDetails`(`orderAmount`·`pgPaidAmount`·`pgDiscountAmount`·`confirmedCancellationAmount`·`confirmedPointRefundAmount`·`confirmedPaymentMethodRefundAmount`·`reportedAt`·`source`·`unconfirmedFields`)를 저장된 근거로만 제공한다([결정 36](../../../docs/decisions.md#refund-amount-details)).
2. THE Order SHALL 미확인 값을 0원으로 채우지 않는다. 성공 취소 중 하나라도 포인트 보고가 없으면 `confirmedPointRefundAmount`는 null이고, 직접 근거가 없는 결제수단 반환액은 승인 실결제액·취소 총액에서 계산하지 않고 null로 둔다. 미확인은 `COMPLETED` 보류 사유가 아니다([P2.1-8](../platform/requirements.md)).
3. WHEN 금액 근거가 여러 이벤트로 도착하면 THEN Order SHALL 먼저 도착한 정상 근거를 보존하고 후속 근거로 미확인 값만 보완하며, 확인된 총액·취소 ID 집합·승인 구성·포인트·취소 시각과 모순되면 거절한다. 확인된 값을 null로 지우거나 `reportedAt`을 과거로 옮기지 않고, 금액 사본 보완으로 최초 완료 시각·금융 확인·원구매 `PAID`를 덮어쓰지 않는다. 결제 공급자가 찍은 취소 시각은 값으로만 저장하고 Payment 시각과 비교해 거절하지 않는다(원칙 ⑥).

### R10. 정방향 응답 대기 (결정 158)
**사용자 스토리:** 운영자로서, Order가 낸 결제 완료·환불 완료가 License·Settlement에 끝내 반영되지 않은 건을 세어 보고 싶다. 그래야 보유권 미지급·정산 누락이 조용히 남지 않는다.

#### 수용 기준
1. WHILE 주문이 `PAID`인데 그 주문의 구매 근거 생성 사실(`order_license_grant`)이 없으면 THE Order SHALL 그 건수와 `paid_at`부터의 가장 긴 대기 시간을 게이지로 낸다([P3.15-5](../platform/requirements.md), I14). (1회차 지적 B4, 판정 전)
2. WHEN Settlement의 매출 기록 확인 사실(가칭 `SettlementSaleRecorded`, 그룹 `order-settlement-sale`)을 처음 받아 주문 연결값이 맞으면 THEN Order SHALL 응답 기록(`order_forward_response`, 주문당 종류별 하나)과 Inbox를 저장한다. WHILE 주문이 `PAID`인데 이 응답이 없으면 THE Order SHALL 건수와 가장 긴 대기 시간을 게이지로 낸다. 같은 응답의 재전달은 무변화, 같은 주문의 다른 응답은 영구 실패다.
3. WHEN License의 보유권 회수 확인 사실(가칭 `LicenseRefundApplied`, 그룹 `order-license-refund`)을 처음 받아 환불·구매 연결값이 맞으면 THEN Order SHALL 응답 기록과 Inbox를 저장한다. WHILE 환불이 `COMPLETED` 또는 `LATE_CANCELLATION_COMPLETED`인데 이 응답이 없으면 THE Order SHALL `purchase_refunded_at`부터 건수와 가장 긴 대기 시간을 게이지로 낸다.
4. THE 응답 대기 게이지의 경보 기준 시간 SHALL 실험 측정 뒤 정하고, 그 전에는 게이지만 낸다. 환불의 License·Payment 단계 대기(R8.9·R8.12·R8.14)의 10분 경계는 그대로다([결정 159](../../../docs/decisions.md#forward-gap-threshold), [P4.3-4](../platform/requirements.md)).
5. THE 응답 대기 SHALL 주문·환불 상태를 바꾸지 않는다. 재반영은 재발행(R8.15)과 상대 모듈의 실패 보관 재처리로 한다.

### R11. 상태 제약과 옛 자료 이행
**사용자 스토리:** 운영자로서, 잘못된 상태 값이나 숨은 하위 상태가 DB에 남지 않기를 바란다. 그래야 불변식 판정이 보조 칸 조합에 기대지 않는다.

#### 수용 기준
1. THE `purchase_order.status`·`order_payment_admission.status`·`order_refund.status`·`order_refund_attempt.status`·`order_refund_review_round.decision`·승인 중단 단계(`stop_stage`)·`payment_result_kind`·`failure_source`·거절 사유 SHALL 각각 enum 값 목록을 갖고 DB CHECK가 그 값만 허용한다(원칙 ④, MC-O1·MC-O2).
2. THE Order SHALL 보조 칸의 null 조합으로 하위 상태를 만들지 않는다. 영수증의 열림/닫힘·시도의 귀속 미확정·승인 중단 단계는 상태 열로 표현한다(지금 코드와 다른 곳은 tasks).
3. WHEN 이행 파일이 새 상태 열·`paid_at`·`expires_at`·`failure_source`를 더하면 THEN 그 이행 SHALL 옛 행을 기록된 Order 사실로만 채우고(규칙은 design "데이터 모델 — 이행"), 다른 모듈 시각으로 채우지 않으며, 같은 PR에 옛 행 호환 테스트(`OrderMigrationIntegrationTest`)를 쓴다(결정 104). 영수증 없이 결과가 반영된 옛 주문 행의 `payment_admission_id`는 [결정 필요 M-OR-4]를 따른다.
4. THE 심사 판정의 정본 SHALL `order_refund_review_round` 하나다. `order_refund`의 1회차 판정 사본 칸(`review_request_id`·`review_decision`·`review_operator_subject`·`review_reason`·`reviewed_at`)은 새 행에 쓰지 않고, 옛 행 읽기 호환 뒤 지운다(원칙 ③).

## 범위 밖(후속)

- 장바구니·복수 상품·수량 선택·선물·쿠폰·할인 조합·다중 통화·무료 상품 지급.
- Outbox 행까지 없는 이벤트(발행 기록 자체가 없음)의 종결 — 재발행으로 덮이지 않는다(결정 161, 결정 125 후속).
- 환불 조사 이후 조치·종결 기준(Payment 정본, 결정 127·128·131), 실제 ADMIN 계정·운영 화면의 심사·재심사·승인 중단·늦은 취소 반영 전체 여정.
- 기한 정책 값(4시간·30분·7일) 변경의 과거 주문 적용 기준(Q9) — 값을 바꾸는 첫 PR에서 묻는다.
- 부분 환불·다중 PG·조사 담당 배정(결정 131)·추가 알림.
