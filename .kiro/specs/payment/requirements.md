# 요구사항: Payment (주문 결제 접수·PG 확정/조회·금융 보류·환불 실행)

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다.

## 개요

Payment는 Order가 고정한 주문 한 건의 결제를 내구 접수하고, 결제 공급자(PG)에 승인 확정을 보내고 조회해 금융 결과를 정한다. 확인된 결과만 결과 이벤트로 내고, 설명되지 않는 금융 상황은 보류(운영자 조사·PG 근거 종결)로 드러낸다. 승인된 환불은 PG 취소·조회로 실행하고 금융 확인·실패·귀속을 기록한다.

Payment가 정본인 것: 결제 결과와 PG 결제 구성(`purchase_payment`), 승인 시도와 보류 사유(`payment_approval`), 주문에 연결되지 않은 청구(`unlinked_charge`), 환불 실행·금융 확인·귀속·실패 사실(`refund_execution`과 운영 기록), 환불 취소 결과 확인 지연 시각(`confirmationDelayAt`, [P1.4-4](../platform/requirements.md)). 정본 소유 표는 플랫폼 design "정본 소유"다.

Payment가 하지 않는 것: 가격·결제 기한 판정(Order 정본, P1.4-3), 주문·환불 진행 상태(Order), 보유권(License), PG 비용·매출 배분·정산(Settlement, [결정 165](../../../docs/decisions.md#pg-fee-owner)). Payment는 Order DB를 바꾸지 않고, 시스템은 보류를 이유로 PG 취소를 실행하지 않는다(P3.13-2).

이 모듈이 지키는 모듈 간 흐름은 플랫폼 흐름 2 S2~S9-R([P3.10~P3.14](../platform/requirements.md))과 흐름 3 S5~S10·S13·S14(P3.19~P3.23), 돈 불변식은 I1·I2·I4·I8·I10·I11·I15·I16·I18·I19·I20(P4)이다.

## 용어

- **paymentId**: Payment가 `OrderPlaced` 접수 때 발급하는 주문당 하나의 고정 식별자. PG 결제 식별자와 확정 멱등 키로 쓴다. 공급자 거래 ID와 다르다.
- **requestEventId**: 원래 `OrderPlaced`의 `eventId`. 구매자가 Order에 보낸 `requestId`와 다르다.
- **승인 시도(approval)**: 고객이 결제창에서 인증한 결과 한 건. `approvalId`가 시도 식별자이고, Order 승인 진행 접수의 `admissionId`와 같다. 결제당 진행 중 시도는 하나다.
- **승인 진행 영수증**: Order가 승인 진행 접수(`POST /internal/v1/orders/{orderNo}/payment-admissions`)에 돌려준 영수증(`admissionId`·`paymentId`·`admittedAt`). 영수증이 있는 시도를 "접수된 시도"라 한다(P3.10-3).
- **확정 송신**: 돈을 움직일 수 있는 PG 요청(승인 확정). 접수된 시도만 보낸다(P3.11-2, I15).
- **확정 송신 기한(`confirmDeadline`)**: 같은 멱등 키·본문으로 확정을 다시 보내도 공급자가 한 번만 처리한다고 보장하는 마지막 시각. 결제 공급자 adapter가 시도의 첫 송신 준비 때 계산해 core에 주고, core는 그 값을 시도에 기록해 그 값으로만 판정한다(P1.4-5, [결정 140](../../../docs/decisions.md#defer-real-pg)). 값의 근거는 design "adapter 메모"에만 있다.
- **환불 재송신 기한(`cancellationResendDeadline`)**: 환불 취소에 대한 같은 의미의 기한. adapter가 주고 실행에 기록한다. `confirmationDelayAt`은 이 값이다(P1.4-4).
- **PG 관측 상태**: core enum `READY`·`PENDING`·`PAID`·`FAILED`·`CANCELLED`(전액 취소). 공급자 상태 문자열을 이 값으로 바꾸는 일은 adapter가 한다.
- **보류(금융 보류)**: 설명되지 않는 금융 상황 때문에 시도의 확정 송신을 멈추고 운영자 조사·PG 근거 종결을 기다리는 상태. 사유는 보류 사유 8개 중 하나다(P3.13-1).
- **주문에 연결되지 않은 청구**: 끝난 결제(종결·만료·실패)에 뒤늦게 생긴 PG 청구, 또는 Order가 반영을 거절한 결제 성공. 결제·시도를 다시 열지 않고 별도 기록(`unlinked_charge`)으로 보류 목록에 올린다([결정 153](../../../docs/decisions.md#late-success-rejection)·[결정 154](../../../docs/decisions.md#unlinked-charge-discovery)).
- **attemptId**: Order가 정한 환불 실행 한 건의 고정 식별자. PG 취소 멱등 키로 쓴다.
- **금융 확인**: 원결제 전액이 PG에서 취소됐음을 Payment가 처음 확인한 사실(`refund_confirmed_at`). 근거는 우리 취소 응답 성공이거나 PG 조회로 본 같은 원결제의 전액 취소다(P3.21-1).
- **귀속**: 확인된 전액 취소가 이 실행의 우리 취소 요청 때문인지에 대한 판정. 정본은 Payment 하나다([결정 164](../../../docs/decisions.md#attribution-in-payment)).
- **늦은 취소 확인**: 실패로 확정된 실행 뒤 실제로 나타난 원결제 전액 취소를 ADMIN이 PG 근거로 확인한 기록([결정 157](../../../docs/decisions.md#late-cancellation-confirmation)).
- **Inbox / Outbox**: 공통 메시징의 수신 중복 제거(`processed_event`)와 같은 트랜잭션 발신 저장(`outbox_event`). 규칙은 [P2.2·P2.3](../platform/requirements.md).

## 요구사항

### R1. 주문 결제의 내구 접수
**사용자 스토리:** 구매자로서, 내 주문이 중복 청구나 유실 없이 결제 대기로 접수되길 원한다.

#### 수용 기준
1. WHEN `OrderPlaced`를 받으면 THEN Payment SHALL `api.listener`의 `OrderPaymentMapper.from`에서 봉투·본문 ID·종류·키와 가격 일관성(`listAmount − discountAmount = amount`, 양의 정수, 통화)을 저장 전에 검증한다(P2.1, I1).
2. WHEN 새 `orderNo`·새 `requestEventId`의 요청이면 THEN Payment SHALL `PENDING` 결제 한 건(고정 `paymentId`)과 Inbox(`payment-order-request`)를 같은 트랜잭션에 저장하고 PG를 호출하지 않는다(P3.11-1).
3. WHEN 같은 이벤트 ID·같은 전체 입력이 다시 오면 THEN Payment SHALL 기존 결제를 보존하고 새 결제·Outbox를 만들지 않는다.
4. IF 같은 이벤트 ID가 다른 주문·입력으로 오면 THEN Payment SHALL 계약 충돌(영구 실패)로 처리하고 결제·Inbox를 바꾸지 않는다(P2.3-4).
5. IF 같은 주문에 새 이벤트 ID가 오면 THEN Payment SHALL 금액이 같아도 새 결제로 수락하지 않는다.
6. WHILE 같은 주문의 최초 접수가 동시에 일어나는 동안 THE Payment SHALL DB 유일 제약(`uk_purchase_payment_order`·`uk_purchase_payment_request`)으로 한 건만 확정하고, 진 트랜잭션은 재전달에서 기존 입력과 비교한다.
7. THE 리스너 반환 SHALL 요청의 내구 저장만 뜻하며 PG 호출이나 결과 완료를 뜻하지 않는다.
8. THE 기존 요청 대조와 이벤트 ID 재사용 검사 SHALL Inbox 중복 생략보다 먼저 하며, 원래 주문 시각(UTC 마이크로초)과 상품·가격 스냅샷 전체를 포함한다.
9. IF Inbox만 있고 결제가 없거나 기존 결제와 접수 기록이 맞지 않으면 THEN Payment SHALL 정상 중복으로 숨기지 않고 신규 접수의 결제와 Inbox를 함께 롤백한다.
10. IF 금액·ID·버전에 소수·잘못된 숫자 형식이 오면 THEN Payment SHALL 정수로 절삭하지 않고 저장 전에 거부하며, 계약 위반은 영구 실패로 실패 보관 표에 보낸다(P2.6-3·P2.6-4).
11. WHEN 구독 토픽에서 처리하지 않는 `eventType`이 오면 THEN Payment SHALL 부수효과 없이 건너뛰고 건너뛴 수를 (소비 그룹, 타입) 지표로 낸다(P2.3-7).
12. WHEN `OrderPlaced`에 `expiresAt`이 있으면 THEN Payment SHALL 그 값을 그대로 저장하고 다시 계산하지 않는다. 값은 표시·기록에만 쓰고 자기 시계와 비교하지 않는다(P1.4-3·P3.10-2, [결정 169](../../../docs/decisions.md#checkout-open-until-expiry-fact)). `expiresAt`이 없는 옛 이벤트는 `null`로 저장한다.

### R2. 미확정 결제의 주기 재개와 트랜잭션 경계
**사용자 스토리:** 시스템으로서, 응답 유실·재시작 뒤에도 미확정 결제를 스스로 이어 처리하고 싶다.

#### 수용 기준
1. THE 결제 재개 작업(`PendingPaymentScheduler` → `ResolvePayment`) SHALL 처리 시각에 이른 `PENDING` 결제를 `nextAttemptAt, id` 순으로 고른다. 주기·배치 크기는 설정이다(design "설정").
2. WHEN PG를 호출하면 THEN Payment SHALL DB 트랜잭션·행 잠금·커넥션 밖에서 호출한다(P1.2-4).
3. THE Payment SHALL 접수 때 발급한 `paymentId`를 재시작·응답 유실·롤백·동시 호출에도 같은 값으로 유지한다.
4. WHEN 일시 오류·미확정·잘못된 공급자 응답이 오면 THEN Payment SHALL 다음 시도를 재시도 간격 뒤로 예약하고 결제별로 처리해 한 건의 오류가 배치를 막지 않게 한다.
5. WHEN 재시작하면 THEN Payment SHALL DB의 `PENDING`을 다시 읽어 Kafka 재전달 없이 재개한다.
6. WHERE `stove.payment.processing-enabled=false`로 기동한 경우 THE Payment SHALL 접수만 저장하고 PG 처리를 멈추며, 이 설정을 HTTP API로 노출하지 않는다.
7. THE 예약 저장 SHALL 짧은 트랜잭션에서 `PENDING`을 재확인하고 동시에 확정된 결제를 덮어쓰지 않는다.

### R3. 결과 확정과 원자성
**사용자 스토리:** Order로서, 접수된 시도의 확인된 결과만 받고 싶다.

#### 수용 기준
1. WHEN 접수된 시도의 PG 조회가 `PAID`이고 결제 식별자·주문·금액·통화가 결제와 같으면 THEN Payment SHALL `purchase_payment` → `payment_approval` 순서로 잠근 뒤 그 시도가 여전히 그 결제의 접수된 현재 시도인지 다시 확인하고, 한 트랜잭션에서 결제 `PAID`·시도 `SUCCEEDED`·`OrderPaymentCompleted`(그 시도의 `admissionId` 포함) Outbox를 쓴다([P3.12-1](../platform/requirements.md), I11·I15, D-051).
2. IF 결제 공급자 adapter가 그 결제 전체의 최종 거절(시도 하나의 실패가 아님)을 보고하면 THEN Payment SHALL 결제 `FAILED`와 `OrderPaymentFailed`(사유 필수) Outbox를 커밋한다. 시도 하나의 실패는 R4.13을 따른다. (지금 공급자 adapter는 최종 거절을 보고하지 않는다 — notes F2)
3. IF 예외·연결 실패·응답 유실·미확정이면 THEN Payment SHALL `PENDING`을 유지하고 결과 이벤트를 만들지 않는다.
4. IF 결과의 식별자·주문·금액·통화 불일치나 필수 값 누락이면 THEN Payment SHALL `PENDING`을 유지하고 오류를 분류 기록하며 승인·거절로 해석하지 않는다.
5. IF PG는 확정했으나 Payment 결과 트랜잭션이 실패하면 THEN Payment SHALL `PENDING`으로 남기고 같은 키로 다시 조회해 결과 저장을 재시도한다.
6. THE Payment SHALL 시간 경과·타임아웃·재시도 횟수만으로 결제를 `FAILED`로 바꾸지 않는다(P3.12-3).
7. THE `PAID`·`FAILED`·`EXPIRED` SHALL 끝 상태이며, 결제당 최종 결과는 한 번만 정한다. 같은 결과의 재확정은 무변화, 다른 결과는 `CONFLICT`다(P3.12-4, I8).
8. WHEN 동시 처리가 같은 최종 결과를 내면 THEN Payment SHALL 먼저 커밋한 결과·결과 이벤트를 유지하고 추가 Outbox를 만들지 않는다.
9. THE 끝 상태 SHALL 공급자 거래·결정 시각(값으로만 저장, R7.18)과 결과 이벤트 ID·시각을 함께 갖고, 확정 때 재시도 예약·오류 분류를 정리한다.
10. THE 결과 이벤트 `amount` SHALL 고객 청구액이며 PG 비용 공제 뒤 금액으로 대신 쓰지 않는다.
11. IF 승인 진행 영수증이 없는 시도(접수 전 시도, 실패 통지·종결·만료로 닫힌 시도)에서 PG 결제가 관측되면 THEN Payment SHALL 결과 이벤트를 내지 않고 다른 시도의 성공으로 돌리지 않는다. 접수 전 진행 중 시도는 `PAID_WITHOUT_ADMISSION` 보류로 올리고(R12), 닫힌 시도(`FAILURE_CONFIRMED`·`FAILED`·`EXPIRED`)는 다시 열지 않고 근거 충돌로 거절한다 — 같은 결제의 진행 시도는 조회 근거 대조 실패로 `PG_INVALID_EVIDENCE` 보류가 되고(결제 공급자는 이미 결제된 결제의 두 번째 확정을 거절한다), 결제가 끝나면 R16·R17의 늦은 청구 감시가 찾는다([결정 188](../../../docs/decisions.md#closed-attempt-charge)). 끝난 결제는 R16·R17을 따른다(P3.12-5, I15, 결정 153 A1).
12. THE `OrderPaymentCompleted` SHALL `admissionId`를 필수 칸으로 싣고, `admissionId`가 없는 옛 모양과 구별되도록 새 `schemaVersion`으로 낸다. 배포 순서는 P2.7-5를 따른다(T1, (1회차 지적 A3, 판정 전)).

### R4. 승인 시도와 결제창 정보
**사용자 스토리:** 구매자로서, 결제창에서 인증한 결과가 서버에서 검증돼 반영되길 원한다. 그래야 브라우저 응답이 유실돼도 결제가 끝난다.

#### 수용 기준
1. WHEN 구매자가 `POST /api/v1/payments/{orderNo}/approvals`로 `requestId`·공급자 시도 ID(`supplierAttemptId`)·인증 토큰을 보내면 THEN Payment SHALL 본인·결제 `PENDING`·진행 중 시도 없음을 확인해 `REQUESTED` 시도로 내구 접수하고 202를 돌려주며, 처리 중 PG·Order를 호출하지 않는다.
2. WHEN 접수 응답이 유실돼 같은 `requestId`·같은 내용이 다시 오면 THEN Payment SHALL 최초 영수증에 수렴하고, 다른 내용이면 `CONFLICT`다.
3. THE Payment SHALL Order의 승인 진행 영수증을 저장한 뒤에만 확정 송신을 하며, 송신 응답 요약만으로 결과를 만들지 않고 다시 조회한다(P3.11-2·P3.11-5).
4. THE 확정 송신 본문과 토큰 SHALL 암호화해 보관하고 원문을 로그·응답·Order·Kafka에 전달하지 않는다. 재송신은 복호화한 원래 바이트·같은 멱등 키만 쓴다.
5. WHEN 확정 송신 응답이 유실되면 THEN Payment SHALL 그 시도에 기록한 `confirmDeadline` 전까지 같은 시도 ID·멱등 키·본문으로 재송신하며 조회를 병행하고, 재시작·lease 재획득으로 준비 시각·기한을 다시 정하지 않는다(결정 33·124, (1회차 지적 B7, 판정 전)).
6. WHEN `confirmDeadline`에 이르면 THEN Payment SHALL 확정 송신을 멈추고 조회만 이어가며, 시간만으로 실패·새 키·새 결제를 만들지 않는다. 그 뒤 접수된 시도의 조회가 PG `PENDING`·`READY`이면 조회 직후 `CONFIRM_WINDOW_EXPIRED`로 보류하고, 늦은 `PAID`·`FAILED`는 원래 주문으로 끝낸다([결정 124](../../../docs/decisions.md#confirm-window-expired-hold), P3.13-5).
7. THE Payment SHALL PG `PAID`만 결과로 확정하고 그 밖의 관측은 미확정(재조회 예약)으로 둔다.
8. WHEN 구매자가 `GET /api/v1/payments/{orderNo}/checkout`을 요청하면 THEN Payment SHALL 본인·결제 `PENDING`일 때만 공개 결제창 설정을 `no-store`로 돌려준다. 그 주문의 `OrderExpired`를 받았으면 409 `ORDER_EXPIRED`, 결제가 끝 상태면 409, 진행 중 시도가 있으면 409 `APPROVAL_IN_PROGRESS`(R14.6), 타인·없는 결제는 404다. Payment는 자기 시계를 주문 시각·`expiresAt`과 비교해 거절하지 않는다(P3.10-2, 결정 169).
9. THE 확정 송신 본문 SHALL 시도·금액·통화를 고정하고, PG 조회로 원구매·금액·통화·공급자 시도 ID를 대조한다. `READY`·`FAILED` 응답의 금액 값으로 성공을 추정하지 않는다.
10. WHEN Order가 승인 진행 접수에 `ORDER_EXPIRED`를 응답하면 THEN Payment SHALL 확정을 보내지 않고 시도·결제를 `EXPIRED`로 닫으며 이벤트를 내지 않는다(P3.11-3).
11. WHEN 공급자 거래가 이미 `PAID`이면 THEN Payment SHALL 확정을 다시 보내지 않고 조회 근거로 R3.1을 따른다.
12. IF 확정 송신이 공급자의 형식 갖춘 거절을 받거나, 응답이 시도·결제와 대조되지 않거나, Order 승인 진행 접수·실패 통지가 409(만료·판매 제한 밖)를 돌려주면 THEN Payment SHALL 금융 실패·새 결제를 만들지 않고 송신을 멈춘 뒤 보류(`PG_REQUEST_REJECTED`·`PG_INVALID_EVIDENCE`·`ORDER_ADMISSION_CONFLICT`)로 올린다(결정 114, P3.11-3).
13. WHEN 접수된 시도의 검증된 개별 실패(PG `FAILED`)가 확인되면 THEN Payment SHALL 시도를 `FAILURE_CONFIRMED`로 바꾸고 Order에 시도 실패 통지(`POST …/payment-admissions/{admissionId}/failure`)를 보내며, 실패 영수증을 받은 뒤 시도를 `FAILED`로 닫는다. 결제는 영수증이 `PENDING`이면 `PENDING`(같은 주문 재결제), `EXPIRED`이면 `EXPIRED`다(P3.12-2).
14. WHEN 구매자가 `GET /api/v1/payments/{orderNo}/approvals/{requestId}`를 요청하면 THEN Payment SHALL 상태·접수·기한·종결 시각을 `no-store`로 돌려주고 토큰·거래 원문·암호문을 넣지 않으며 타인은 404다. `REVIEW_REQUIRED`·`FAILURE_CONFIRMED` 시도는 새 인증을 열지 않는다.
15. THE Order 승인 진행 접수·실패 통지 호출 SHALL Payment 서비스 키(`X-Payment-Service-Key`)로만 하고 Gateway·공개 명세에 노출하지 않는다(P1.2-3).
16. THE 결제창 응답 SHALL 요청에서 금액·`paymentId`·통지 주소를 받지 않고, 통지 주소는 설정한 HTTPS 주소만 쓴다.
17. IF 승인 진행 접수·실패 통지 HTTP가 연결 실패·타임아웃·5xx로 끝나면 THEN Payment SHALL 시도 상태를 바꾸지 않고 다음 회차에 같은 `admissionId`로 다시 요청하며, 이 오류를 PG 장애와 다른 분류(Order 연계 장애)로 기록한다(P3.11-4, MC-P6).
18. WHEN Order가 판매 제한(`PRODUCT_SALE_RESTRICTED`)을 응답하면 THEN Payment SHALL 시도를 접수 전으로 두고 다음 회차에 다시 요청한다(P3.11-3). 대기의 상태 표현은 소급 후보 RC-2 답을 따른다(notes E-1).

### R5. 같은 주문 재결제와 실패 시도 이력
**사용자 스토리:** 구매자로서, 한 번 결제가 실패해도 같은 주문으로 다시 결제하고 싶다.

#### 수용 기준
1. WHEN 공급자의 개별 시도가 `FAILED`이면 THEN Payment SHALL 이를 별도 이력으로 보존하고 결제·주문의 최종 실패로 바꾸지 않는다(결정 31).
2. THE 재결제 SHALL 같은 `orderNo`·`paymentId`·최초 청구액의 `PENDING` 결제를 다시 쓴다.
3. THE 실패 시도 이력 SHALL 공급자 거래 ID별로 서버에서 원구매·금액과 대조해 보존하며 결제·시도 상태와 분리한다.
4. THE Payment SHALL `PAID`와 결과 Outbox를 이력 수집보다 먼저 커밋하고, 이력 수집 장애는 별도 예약(`next_failure_lookup_at`)으로 재개한다.
5. WHEN 구매자가 `GET /api/v1/payments/{orderNo}/attempt-failures`를 요청하면 THEN Payment SHALL 본인 실패 시도 페이지와 수집 상태를 `no-store`로, DB 조회만으로 돌려준다.
6. THE Payment SHALL 브라우저 닫기·404·타임아웃을 확정 실패 근거로 쓰지 않는다.
7. THE 실패 시도 수집 SHALL 공급자가 보고한 실패 시각을 값으로만 저장하고, 자기 관측 시각과의 선후로 근거를 거절하지 않는다. 근거의 진위는 결제 식별자·공급자 시도 ID·금액으로 판정한다([결정 163](../../../docs/decisions.md#external-clock-principle), P4.5-3).
8. WHEN 이력 수집이 실패하면 THEN Payment SHALL 다음 수집 시각을 다시 예약하고, `PAID` 뒤 재개는 이력 조회만 하며, 자료가 없으면 실패·수집 시각을 추정하지 않는다.
9. THE 실패 이력 응답 SHALL 요청/실패 시각·내부 ID 역순 페이지와 `checkedAt`·`collectionPending`을 주고, 고객 조회는 PG를 호출하거나 작업을 깨우지 않는다.

### R6. 결제 공급자 통지 접수(조회 힌트)
**사용자 스토리:** 시스템으로서, 결제 통지를 받아도 그것만으로 결과를 단정하지 않고 서버 조회로 확인하고 싶다.

#### 수용 기준
1. WHEN 결제 공급자 통지가 공개 통지 경로(가칭 `POST /api/v1/payments/provider-notifications`, 지금 경로는 공급자 이름을 담는다 — T28)로 오면 THEN Payment SHALL adapter에서 원문 크기 제한·서명·전달 시각·통지 ID를 검증하고, 통과한 통지만 core에 "조회 힌트" 하나로 넘긴다. 검증 규칙은 design "adapter 메모"다(원칙 ②).
2. IF 서명이 없거나 변조됐거나, 전달 시각이 허용 범위 밖이거나, 원문이 크기 제한을 넘거나, 메서드가 다르면 THEN Payment SHALL 업무 상태에 닿기 전에 거절한다(401·413·405).
3. WHEN 유효한 통지가 처음 오면 THEN Payment SHALL 결제를 잠그고 통지 ID·본문 지문과 재조회 앞당김을 한 트랜잭션에 저장한다. 같은 ID·본문은 최초 기록을 유지하고, ID 재사용·다른 본문은 거절한다.
4. THE 통지 SHALL 결제·환불의 상태를 바꾸지 않는다. 다음 조회를 앞당길 뿐이다(P3.11-5).
5. THE Payment SHALL 외부 HTTP를 기다리며 DB 잠금을 쥐지 않고, 로컬 결제가 없으면 새 업무를 만들지 않는다.
6. IF 통지 본문에 중복 키·뒤따르는 추가 본문·필수 연결값 누락·다른 상점이 있으면 THEN Payment SHALL 거절하고, 서명이 유효한 알 수 없는 통지 종류는 200으로 무시한다.
7. WHEN 서명이 검증된 취소 계열 통지를 처음 받으면 THEN Payment SHALL 그 결제의 준비된 미확정 환불 실행의 조회를 다음 회차로 앞당기되 정기 조회 단계는 바꾸지 않는다.
8. WHEN 끝난 결제(종결·만료·실패)의 통지가 오면 THEN Payment SHALL 그 결제의 청구 감시(R16)가 살아 있을 때 다음 조회를 앞당긴다. 결제 상태는 다시 열지 않는다([결정 154](../../../docs/decisions.md#unlinked-charge-discovery), P3.13-8).
9. WHEN 실패로 확정된 환불 실행의 취소 계열 통지가 오면 THEN Payment SHALL 그 실행의 실패 뒤 감시(R19)가 살아 있을 때 다음 조회를 앞당긴다.

### R7. 환불 실행(PG 취소·조회)
**사용자 스토리:** 시스템으로서, 승인된 환불만 PG에서 취소하고 PG 근거로만 확정하고 싶다.

#### 수용 기준
1. WHEN `RefundExecutionRequested`를 받으면 THEN Payment SHALL `RefundExecutionMapper.from` 검증 뒤 원결제 `PAID`와 연결값·금액(원결제 전액)을 대조하고 `attemptId` 하나에 `refund_execution` 한 행만 `PENDING`으로 접수하며, 접수 트랜잭션에서 PG를 호출하지 않는다(P3.19-1, I2).
2. THE 환불 PG 호출 SHALL `attemptId`를 멱등 키로 쓰고 원결제 전액을 취소 요청액으로 명시하며 부분 취소액을 채워 넣지 않는다.
3. IF 송신 직전 PG 조회의 취소 가능 잔액이 원결제액과 다르거나, 송신 응답이 잔액 변화를 알리면 THEN Payment SHALL 그 실행의 자동 송신을 영구히 멈추고(`BALANCE_BLOCKED`) 조회는 유지한다.
4. THE Payment SHALL 송신 응답의 성공 상태 코드만으로 성공을 기록하지 않고, 검증된 전액 취소 조회(성공 취소 ID·합계 = 원결제액·원래 요청 연결) 또는 검증된 취소 완료 응답으로만 금융 확인을 기록한다.
5. WHEN 전액 취소가 처음 증명되면 THEN Payment SHALL 실행에 금융 확인을 한 번만 기록하고 같은 트랜잭션에 `RefundPaymentConfirmed` Outbox를 쓴다. 원 `purchase_payment`의 `PAID`·금액은 바꾸지 않는다(P3.21-1).
6. THE 실행 `FAILED` SHALL 우리 취소 ID의 PG `FAILED`와, 이어진 조회의 원결제 `PAID`·전액 미취소·진행/성공/미지 취소 없음이 함께 있을 때만 `RefundExecutionFailed` Outbox와 함께 저장한다. 취소 ID 누락·부분 취소·조회 장애는 실패를 만들지 않는다. 금융 확인이 먼저 커밋됐으면 늦은 실패 근거로 실패를 만들지 않는다(P3.22-1, I18).
7. THE Payment SHALL 시간 경과·타임아웃·연결 실패만으로 환불 실패·새 키·새 실행을 만들지 않는다.
8. WHEN 송신 응답이 adapter 분류로 "인증·설정 오류"이면 THEN Payment SHALL 조건 회복 뒤 같은 요청을 자동 재개한다. "형식 거절"이면 첫 응답 뒤 자동 송신을 멈추고 보류 기록(`HELD`)을 남긴다. "미확정"이면 계속 재송신한다. 분류표는 adapter가 갖는다([결정 133](../../../docs/decisions.md#refund-formal-rejection-stop), P3.20-2, MC-P9).
9. THE 자동 취소 송신 SHALL 직전 PG 조회로 원결제·주문·통화·PG 거래와 원결제액 = 취소 요청액 = 취소 가능 잔액을 대조한 뒤에만 보낸다. 첫 실행은 취소 이력이 없어야 하고 후속 실행은 저장한 선행 실패 이력과 일치해야 하며, 조회 실패·검증 불가면 그 회에는 보내지 않는다.
10. THE 송신 본문 SHALL 첫 준비 때 커밋한 원문을 재사용하고, 사유 문구는 `refundId` 기반 기술 상관값이다. 멱등 키의 인코딩은 adapter 규칙이다.
11. WHEN 우리 시도의 정상 접수 응답(PG가 취소를 받아 둠·실패로 기록함)이 있으면 THEN Payment SHALL 그 실행의 자동 송신을 멈추고(`RECEIPTED`) 연결 조회만 이어간다.
12. THE 전액 취소 확인 SHALL 중복 없는 양수 성공 취소 ID들의 합계가 원결제액인 경우(여러 건 합산 포함)를 인정하고 실패·접수 중 내역은 성공 합계에서 뺀다. 공급자 취소 시각이 없으면 추정하지 않고 `null`로 둔다.
13. WHEN 조회로 먼저 금융 확인(귀속 미확정, `CONFIRMED_UNATTRIBUTED`)이 기록된 뒤 같은 취소 ID·금액의 직접 성공 응답이 확인되면 THEN Payment SHALL 최초 금융 확인을 유지하고 실행을 `SUCCEEDED`(우리 요청에 귀속)로 바꾼다. 이 귀속은 Payment 기록에만 남고 이벤트로 내지 않는다. 다른 취소 ID의 성공 주장은 거절한다([결정 164](../../../docs/decisions.md#attribution-in-payment), P3.21-3).
14. WHEN 후속 실행 요청(`previousAttemptId`)이 오면 THEN Payment SHALL 같은 `PAID` 결제·같은 `refundId`·검증된 `FAILED` 선행 실행(PG 확정 실패 또는 운영자 종결 실패)·새 정지 완료 ID를 확인하고 선행 실행 하나당 후행 실행 하나만 접수한다. 판정은 식별자와 `refund_execution` 행 잠금으로만 하고 Order 요청 시각과 Payment 실패 확정 시각을 비교하지 않는다(P3.19-2·P3.23-6, U2 PAY-1).
15. WHILE 실행이 `HELD`이면 THE Payment SHALL Kafka 재전달·재송신 작업·내부 호출 어디서도 송신하지 않으며, 조회 회복이나 설정 수정만으로 보류를 풀지 않는다.
16. WHEN ADMIN이 보류를 재개하면 THEN Payment SHALL 현재 보류 ID·금융 미확정·`cancellationResendDeadline` 전·새 접수 응답/완료/부분 취소 없음·실패 종결 진행 중 아님을 확인한 뒤 다음 송신 대상으로만 등록하고 재송신 단계를 초기화하지 않는다. 같은 `requestId`·내용은 최초 영수증, 실패 종결 진행 중이면 409 `REFUND_CLOSURE_BUSY`다(P3.20-3, I19).
17. WHEN 첫 송신을 준비하면 THEN Payment SHALL `firstCancellationPreparedAt`과 adapter가 준 `cancellationResendDeadline`을 한 번만 기록하고, 그 값을 `confirmationDelayAt`으로 실은 `RefundExecutionPrepared`를 같은 트랜잭션에 쓴다(P3.19-3, P1.4-4).
18. THE PG가 찍은 시각(`requestedAt`·`cancelledAt`) SHALL 값으로만 저장한다. Payment는 이 값을 자기 관측 시각과 비교해 근거를 무효로 보지 않고, DB 제약도 이 비교를 하지 않는다. 같은 PG가 찍은 시각끼리의 순서 검사는 허용한다([결정 163](../../../docs/decisions.md#external-clock-principle), P4.5-3, T3·MC-P10).
19. WHILE 실행이 금융 미확정이고 `cancellationResendDeadline`을 넘었으면 THE Payment SHALL 경보 `RefundConfirmationsDelayed`와 ADMIN 지연 목록에 올리고, 시간 경과만으로 실패·복구하지 않는다(P3.20-4).

### R8. 조회·관리자 운영과 최소 보안
**사용자 스토리:** 구매자·운영자로서, 권한에 맞는 결제·환불 상태만 보고 싶다.

#### 수용 기준
1. WHERE `GET /api/v1/payments`·`GET /api/v1/payments/{orderNo}`인 경우 THE Payment SHALL 서명·발급자·만료·`aud=esd-api`·`MEMBER`가 검증된 JWT `sub`를 구매자로 쓴다.
2. THE Payment SHALL 익명 401, 역할 없음 403, 타인·없는 결제 404, 잘못된 페이지 400을 적용하고, 조회가 결제를 만들거나 실행하지 않는다.
3. THE 목록의 임의 `buyerSubject`·`memberId`·`X-Member-Id` SHALL 조회 소유자를 바꾸지 않는다.
4. THE 본인 응답 SHALL `paymentId`·`orderNo`·상품 스냅샷·정가/할인/청구액·통화·상태·생성/갱신/결과 시각·실패 사유·PG 실결제/할인액·`reviewNotice`(R14)를 주고, `requestEventId`·`resultEventId`·Inbox/Outbox·공급자 요청 원문·재시도/내부 오류·공급자 이름은 숨긴다.
5. WHERE `ADMIN`인 경우 THE Payment SHALL 환불 송신 보류 목록·상세·재개(`/api/v1/admin/refund-cancellation-holds`), 환불 지연·귀속 미확정·늦은 취소 조사 목록·상세·조사 기록·종결(`/api/v1/admin/refund-delays`), 승인 보류(`/api/v1/admin/approval-reviews`), 주문에 연결되지 않은 청구(`/api/v1/admin/unlinked-charges`)를 제공한다. 조사 기록은 금융 상태·일정을 바꾸지 않는다(design "API 계약").
6. THE Payment SHALL 옛 prepare·callback·cancel·simulator·승인 주입 업무 API를 두지 않는다.
7. WHILE `OrderPlaced`를 아직 접수하지 못한 동안 THE 상세 조회 SHALL 404를 돌려주고 결제를 만들지 않는다.
8. THE 장기 미확정 목록 SHALL 금융 미확정이고 `cancellationResendDeadline`을 지난 실행이며, 준비 시각을 접수 시각으로 추정하지 않는다. 조사 기록 POST는 같은 `requestId`·내용이면 최초 기록, 다른 내용·결과 확정 뒤 새 기록은 409다.
9. THE ADMIN 목록 항목 SHALL 조사 건수·마지막 조사자·마지막 조사 시각을 보이고, 담당자 배정은 두지 않는다([결정 131](../../../docs/decisions.md#investigation-trail-no-assignee)).

### R9. PG 포트와 비용·정산 경계
**사용자 스토리:** 시스템으로서, PG 연동을 공급자 중립 계약 뒤에 두고 정산 책임과 섞지 않고 싶다.

#### 수용 기준
1. THE PG 승인·조회·취소·통지 계약 SHALL `core.port`에 공급자 중립 이름과 core 값으로 두고, HTTP 구현·응답 분류·보장 기한은 `adapter`, 주소·키·기한 설정 조립은 `config`에 둔다(P1.1-3·P1.1-4).
2. THE 개발·여정·실험·통합 테스트 SHALL 운영과 같은 PG adapter와 기준 시뮬레이터 하나로 돌고, 운영 코드에 시뮬레이터 전용 공급자 값·adapter·분기를 두지 않는다([결정 162](../../../docs/decisions.md#single-reference-simulator), 원칙 ⑦). 이미 저장된 옛 공급자 값은 읽을 수 있어야 한다(결정 104).
3. THE Payment SHALL 새 결제에 PG 비용(정책·요율·금액)을 쓰지 않고, 공급자 승인 응답의 비용을 core 계약으로 받지 않는다. 이미 저장된 비용 값은 읽기만 한다. 칸 제거 시점은 `[결정 필요 M-PA-6]`이다([결정 165](../../../docs/decisions.md#pg-fee-owner), P1.4-2).
4. THE Settlement SHALL PG 명세를 직접 조회하며, Payment에 정산 중계 API를 두지 않는다.
5. THE Payment 승인·조회 키와 Settlement 읽기 키 SHALL 분리하고, 시뮬레이터 제어 키를 업무 앱에 주지 않는다.

### R10. PG 할인과 고객 실결제액 보존
**사용자 스토리:** 구매자·정산으로서, PG가 할인한 거래도 주문 금액을 바꾸지 않고 실결제액을 따로 보고 싶다([결정 34](../../../docs/decisions.md#provider-discount-payment-amounts)).

#### 수용 기준
1. WHEN PG `PAID`를 확정하면 THEN Payment SHALL 주문·원래 시도·주문 총액·미취소를 대조하고 `실결제액 + PG 할인액 = 주문 총액`을 요구한다.
2. THE Payment SHALL PG 실결제액·할인액을 `pg_paid_amount`·`pg_discount_amount`와 응답 `pgPaidAmount`·`pgDiscountAmount`에 보존하고, 원래 `amount`·주문 할인·결과 이벤트 금액은 바꾸지 않는다.
3. THE DB SHALL 금액 쌍의 NULL 일치·`PAID` 상태·음수 금지·합계 일치를 CHECK로 지키고(`ck_payment_provider_amounts`), 확인되지 않은 값은 0이 아니라 NULL이다(I1·I7).
4. IF 같은 결과의 중복 확정이 금액 쌍을 바꾸면 THEN Payment SHALL 거절한다.
5. THE PG 할인액 SHALL PG 비용·판매자 부담으로 분류하지 않는다.

### R11. Order 만료 사실 수신과 순서 역전
**사용자 스토리:** 구매자로서, 주문이 만료되면 결제에도 만료가 보이고 불필요한 조회가 멈추길 원한다.

#### 수용 기준
1. WHEN `OrderExpired`를 받으면 THEN Payment SHALL `OrderExpiryMapper.from` 검증 뒤 Inbox(`payment-order-expiry`)·원구매 대조·주문별 생명주기 잠금·상태 변경을 한 트랜잭션에 저장하고, 금융 실패로 해석하지 않는다. 받은 `expiresAt`은 값으로만 저장하고 `occurredAt`에서 다시 계산한 값과 대조하지 않는다(P3.10-2, T12).
2. THE `OrderPlaced`·`OrderExpired` 수신 SHALL 같은 주문별 생명주기 행(`payment_order_lifecycle`)을 먼저 잠그고, 결제가 아직 없어도 최초 만료 사실을 보존한다. 이 행은 결제 삭제로 지우지 않는다.
3. WHEN 만료 뒤 늦은 `OrderPlaced`가 오면 THEN Payment SHALL 원래 구매 조건과 대조해 승인·조회 예약 없는 `EXPIRED` 결제를 만들고 만료를 다시 열지 않는다(P3.11-1).
4. WHEN 진행 중 시도가 없는 `PENDING` 결제에 만료 사실이 오면 THEN Payment SHALL 결제를 `EXPIRED`로 닫고 조회 예약을 비우며, 과거 실패 이력·구매 스냅샷은 보존하고 금융 실패·환불 이벤트를 만들지 않는다.
5. WHEN 접수 전 진행 중 시도가 있는 결제에 만료 사실이 오면 THEN 승인 작업자 SHALL PG 관측 이력이 없으면 PG 호출 없이 시도·결제를 `EXPIRED`로 닫는다. 이력이 있으면 트랜잭션 밖에서 PG를 한 번 조회해 `PAID` → `PAID_WITHOUT_ADMISSION`, 전액 `CANCELLED` → `PG_CANCELLED`, `PENDING` → `PG_PENDING_AT_ORDER_EXPIRY`로 보류하고, `READY`·`FAILED`면 잠금 안에서 `REQUESTED`를 다시 확인한 뒤 닫는다. 조회 장애는 닫지 않고 다음 회차로 미룬다(P3.13-6, [결정 126](../../../docs/decisions.md#pre-admission-pending-expiry)).
6. WHEN 기한 안 실패 영수증이 `PENDING`이었더라도 이후 만료 사실을 받으면 THEN Payment SHALL 결제를 `EXPIRED`로 닫되 최초 실패 영수증·실패 시도는 바꾸지 않는다.
7. IF 접수된 시도에 만료 사실이 오면 THEN Payment SHALL 확정 송신을 멈추고 `ORDER_EXPIRY_CONFLICT`로 보류한다. IF 이미 `PAID`로 확정된 결제에 만료 사실이 오면 THEN Payment SHALL 결과를 덮어쓰지 않고 만료 사실을 저장하며, 이 조합을 게이지로 세어 경보한다(플랫폼 흐름 2 S8 "상대 상태가 이미 바뀜", MC-P7).
8. WHEN 처음부터 `PG_INVALID_EVIDENCE`로 보류된 접수 전 시도가 접수·송신 준비·실패 기록 없이 원구매와 대조된 `READY`를 관측하고 만료 사실이 있으면 THEN Payment SHALL 같은 잠금·현재 lease 안에서 시도·결제를 `EXPIRED`로 닫는다. 조회 장애·금액 불일치·접수 이력이 있으면 닫지 않는다(결정 114·144).
9. WHILE 생성·만료가 동시에 일어나는 동안 THE Payment SHALL 주문별 잠금으로 직렬화하고, 같은 만료의 중복 수신은 최초 만료 ID·시각을 바꾸지 않는다.

### R12. 승인 보류 운영 조회와 조사 ([결정 114](../../../docs/decisions.md#approval-review-hold-closure) 1부)
**사용자 스토리:** 운영자로서, 송신이 멈춘 승인 보류를 사유·최신 PG 관측과 함께 보고 조사 기록을 남기고 싶다.

#### 수용 기준
1. WHEN ADMIN이 승인 보류 목록을 요청하면 THEN Payment SHALL `REVIEW_REQUIRED` 시도만 페이지·사유 필터로 DB에서 조회하고 `no-store`로 돌려준다. 토큰·보호 본문·암호문은 넣지 않는다.
2. WHEN 승인 작업자가 PG를 조회하면 THEN Payment SHALL 최신 관측 상태·관측 시각을 조회 뒤 별도 잠금 트랜잭션에 저장한다. 최초 보류 시각은 보존한다.
3. WHEN ADMIN이 보류 조사 내용을 기록하면 THEN Payment SHALL JWT `sub`·서버 시각과 500자 이내 내용을 추가 전용으로 저장한다.
4. THE 보류 사유 SHALL 8개(`PAID_WITHOUT_ADMISSION`·`PG_REQUEST_REJECTED`·`PG_INVALID_EVIDENCE`·`ORDER_ADMISSION_CONFLICT`·`ORDER_EXPIRY_CONFLICT`·`PG_CANCELLED`·`CONFIRM_WINDOW_EXPIRED`·`PG_PENDING_AT_ORDER_EXPIRY`)이고, 사유별 들어가는 조건·고객 안내·자동 해제·종결 결과는 design "승인 보류 사유 표"가 정본이다(P3.13-1, MC-P12). 사유는 전용 칸(목표 `review_reason`, DB CHECK)에 두고 오류 코드와 섞지 않는다(소급 후보 RC-2 — notes E-1).
5. THE Payment SHALL 사유별 현재 보류 건수를 게이지로 내고 `PAID_WITHOUT_ADMISSION`은 critical, 나머지는 warning으로 경보한다. 알려진 사유 밖 값은 `OTHER`로 센다.
6. WHEN 상세·조사 이력을 조회하면 THEN Payment SHALL 과거에 보류된 시도도 현재 상태와 함께 주고, 한 번도 보류되지 않은 시도는 404다. 조사 POST는 현재 `REVIEW_REQUIRED`에만 허용한다.
7. WHEN 조회가 실패하면 THEN Payment SHALL 마지막 조회 시각과 오류 종류(`INVALID_EVIDENCE`·`UNAVAILABLE`)를 기록한다. 성공은 오류를 지운다. 최신 관측 상태는 마지막 유효 관측이다. lease를 잃은 뒤에는 저장하지 않는다.
8. THE 종결할 수 없는 과거 보류의 경보를 끄는 방법 SHALL `[결정 필요 M-PA-5]`를 따른다.
9. THE DB SHALL 관측 상태 집합·관측 상태/시각 쌍·보류 시각 순서(같은 Payment 시계)를 CHECK로 지킨다.
10. WHEN 이미 `REVIEW_REQUIRED`인 시도에 새 송신 중단 사유가 오면 THEN Payment SHALL 송신 중단을 유지하고 처음 사유와 `reviewStartedAt`을 바꾸지 않는다. 새 사유가 `PAID_WITHOUT_ADMISSION`이면 그 사유로 한 번 올리고, 그 뒤에는 바꾸지 않는다. 버린 사유는 로그로만 남긴다([결정 144](../../../docs/decisions.md#held-approval-keeps-first-reason), P4.6-1, I10).
11. WHILE 시도가 `REVIEW_REQUIRED`이면 THE 승인 작업자 SHALL 다음 PG 조회 시각을 `now + min(상한, max(시작값, now − reviewStartedAt))`로 잡는다(시작값 5초·상한 10분, [결정 122](../../../docs/decisions.md#approval-review-requery-interval)). `reviewStartedAt`이 없는 옛 행은 상한을 쓴다.
12. WHEN 접수된 보류 시도의 결제가 `PAID`로 커밋되면 THEN Payment SHALL 같은 트랜잭션에서 그 시도를 `SUCCEEDED`로 닫는다(P4.6-2, I11, D-051).

### R13. 승인 보류 운영 종결 ([결정 115](../../../docs/decisions.md#approval-review-closure-outcome), [결정 174](../../../docs/decisions.md#review-closed-event))
**사용자 스토리:** 운영자로서, PG 콘솔에서 처리한 보류 승인을 PG 재조회 근거로 끝내고 싶다.

#### 수용 기준
1. WHEN ADMIN이 `POST /api/v1/admin/approval-reviews/{approvalId}/closure`로 `{requestId(UUID), note(공백 불가·500자 이내)}`를 보내면 THEN Payment SHALL JWT `sub`를 종결자로 기록하고 시도·결제 상태와 종결 기록을 `no-store`로 돌려준다. 같은 `requestId`·같은 내용은 PG를 다시 조회하지 않고 최초 결과를, 다른 내용은 409 `APPROVAL_CLOSURE_CHANGED`를 돌려준다.
2. THE 종결 SHALL 현재 `REVIEW_REQUIRED`인 시도에만 허용하고, 그 밖은 409 `APPROVAL_NOT_HELD`다.
3. WHEN 종결을 시작하면 THEN Payment SHALL 작업자와 같은 lease를 먼저 잡고(살아 있는 lease면 409 `APPROVAL_CLOSURE_BUSY`), 트랜잭션 밖에서 PG를 조회한 뒤 `purchase_payment` → `payment_approval` 잠금 안에서 상태·lease 소유를 다시 확인한다. lease를 잃었으면 아무것도 저장하지 않는다.
4. THE 종결 근거 SHALL PG 관측 전액 `CANCELLED`·`FAILED`·`READY`뿐이다. `PAID`는 409 `CLOSURE_PG_PAID`, `PENDING`은 `CLOSURE_PG_PENDING`, 근거 불일치는 `CLOSURE_PG_INVALID_EVIDENCE`, 조회 장애는 `CLOSURE_PG_UNAVAILABLE`, 결제가 이미 끝 상태면 `CLOSURE_PAYMENT_RESOLVED`다. 거절되면 lease를 돌려주고 원래 다음 조회 시각을 복원한다(P3.13-3).
5. WHEN 근거가 맞으면 THEN Payment SHALL 한 트랜잭션에서 시도를 `FAILED`로 닫고(보류 사유 유지) `approval_closure`(시도 ID·`requestId`·종결자·note·근거 상태·관측 시각·종결 시각)를 남기며 결제를 다음처럼 끝낸다. (a) 접수된 시도 → 결제 `FAILED` + `OrderPaymentReviewClosed`(`admissionId` 포함). (b) 접수 전 시도 + 만료 사실 있음 → 결제 `EXPIRED`, 이벤트 없음. (c) 접수 전 시도 + 만료 사실 없음 → 결제 `FAILED` + `OrderPaymentReviewClosed`(`admissionId` null). `OrderPaymentFailed`로 운영자 종결을 내지 않는다(P3.13-4, 결정 174, T18).
6. THE 종결 SHALL PG 취소·승인을 실행하지 않는다. 종결 뒤 그 시도는 송신·조회하지 않고, 같은 주문의 결제창·새 시도는 409이며 보류 게이지에서 빠진다. 결제가 PG에 요청을 만든 적이 있으면 청구 감시(R16)를 시작한다.
7. THE 상세·조사 이력 조회 SHALL 종결 기록을 함께 준다.
8. WHEN 작업자가 같은 시도의 전액 `CANCELLED`를 관측하면 THEN Payment SHALL 스스로 닫지 않는다. 보류 전 시도는 송신을 멈추고 `PG_CANCELLED`로 보류하며, 이미 보류된 시도는 사유를 유지한 채 관측만 기록한다. 전액은 같은 시도·취소액 = 총액·성공 취소 합계 = 총액일 때만이다.
9. WHILE 종결과 만료 수신이 엇갈리는 동안 THE Payment SHALL 잠금 안에서 만료 사실을 읽는다. 만료가 먼저 커밋되면 (b), 종결이 먼저면 (a)·(c)를 유지하고 늦은 만료 사실은 R11.7대로 저장한다.

### R14. 금융 보류 고객 안내 ([결정 123](../../../docs/decisions.md#approval-review-customer-notice)·[결정 132](../../../docs/decisions.md#reviewnotice-last-observation))
**사용자 스토리:** 구매자로서, 결제가 보류되면 돈이 청구됐는지 알고 싶다. 그래야 같은 상품을 다시 사서 두 번 청구되지 않는다.

#### 수용 기준
1. WHERE 결제 상세(`GET /api/v1/payments/{orderNo}`)와 승인 조회(`GET …/approvals/{requestId}`)인 경우 THE Payment SHALL 시도가 보류 중이거나 운영자 종결로 끝났거나 그 결제에 주문에 연결되지 않은 청구 기록이 있으면 `reviewNotice {code, message, since}`를 돌려주고, 그 밖에는 `null`로 둔다. 결제 목록에는 넣지 않는다.
2. WHILE 시도가 보류 중이면 THE Payment SHALL `PG_INVALID_EVIDENCE`는 관측과 관계없이 `CHARGE_UNCONFIRMED`로 두고, 그 밖의 사유는 마지막 유효 PG 관측으로 정한다: `PAID` → `CHARGED`, 전액 `CANCELLED` → `CHARGE_CANCELLED`, `READY`·`FAILED` → `NOT_CHARGED`, `PENDING`·마지막 조회 실패·관측 없음 → `CHARGE_UNCONFIRMED`.
3. WHEN 운영자 종결로 끝난 시도이면 THEN Payment SHALL 종결 근거로 `CLOSED_CHARGE_CANCELLED`(전액 `CANCELLED`) 또는 `CLOSED_NOT_CHARGED`(`READY`·`FAILED`)를 돌려준다.
4. WHEN 그 결제에 열린 주문에 연결되지 않은 청구 기록이 있으면 THEN Payment SHALL 시도 안내보다 우선해 `CHARGED`를 돌려주고, 그 기록이 전액 `CANCELLED` 근거로 닫혔으면 `CLOSED_CHARGE_CANCELLED`를 돌려준다(저장 `PAID` 결제의 응답 `status`는 R17.7의 `CHARGE_RETURNED`, [결정 190](../../../docs/decisions.md#charge-returned-view)). `since`는 기록 생성 시각이다(결정 154 "고객 안내를 청구됨으로 바꾼다").
5. THE 문구 SHALL 결정 123의 한국어 원문을 그대로 쓰고, 금액은 주문 청구액이다. 처리 시점은 약속하지 않는다.
6. WHEN 결제창이 진행 중 시도로 409 `APPROVAL_IN_PROGRESS`를 내면 THEN Payment SHALL 보류 중 시도에는 2의 문구를, 그 밖에는 "기존 결제 승인을 처리하고 있습니다."를 메시지로 쓴다.
7. THE 고객 응답 SHALL 보류 사유 코드·공급자 시도 ID·토큰을 넣지 않는다.
8. THE 알려진 보류 사유 SHALL 모두 2의 묶음에 매핑되고, 사유 목록과 매핑이 어긋나면 테스트가 실패한다.

### R15. 장기·귀속 미확정 환불 운영 종결 ([결정 127](../../../docs/decisions.md#refund-delay-closure))
**사용자 스토리:** 운영자로서, 오래 미확정이거나 전액 취소는 확인됐지만 우리 요청 때문인지 모르는 환불을 PG 근거로 닫고 귀속 판정을 남기고 싶다.

#### 수용 기준
1. WHEN ADMIN이 `POST /api/v1/admin/refund-delays/{attemptId}/closure`로 `{requestId(UUID), attribution(OUR_REQUEST·EXTERNAL_CANCELLATION·UNDETERMINED), note}`를 보내면 THEN Payment SHALL JWT `sub`를 종결자로 기록하고 실행 상태·최초 금융 확인·종결 기록을 `no-store`로 돌려준다. 같은 `requestId`·내용은 PG를 다시 조회하지 않고 최초 결과를, 다른 내용은 409 `REFUND_CLOSURE_CHANGED`를 돌려준다.
2. THE 종결 대상 SHALL 금융 미확정이면서 `cancellationResendDeadline`이 지난 `PENDING` 실행, 또는 `CONFIRMED_UNATTRIBUTED` 실행이고 종결 기록이 없는 것뿐이다. 그 밖은 409 `REFUND_NOT_CLOSABLE`이며 PG를 조회하지 않는다.
3. WHEN 종결을 시작하면 THEN Payment SHALL 조회 일정이 살아 있는 실행은 작업자와 같은 lease를 먼저 잡고(살아 있는 lease면 409 `REFUND_CLOSURE_BUSY`), 트랜잭션 밖에서 PG를 조회한 뒤 `purchase_payment` → `refund_execution` 잠금 안에서 대상·lease 소유를 다시 확인한다.
4. THE 종결 근거 SHALL 같은 원결제의 전액 취소(R7.12)뿐이다. 아직 결제 상태·부분 취소·진행 중이거나 실패한 취소만 있으면 409 `REFUND_CLOSURE_PG_NOT_CANCELLED`이고 이 조회로 실패를 확정하지 않는다. 근거 불일치는 `CLOSURE_PG_INVALID_EVIDENCE`, 조회 장애는 `CLOSURE_PG_UNAVAILABLE`, 이미 확인한 근거와 다른 취소 내역은 409 `CONFLICT`다.
5. WHEN 근거가 맞으면 THEN Payment SHALL 한 트랜잭션에서 미확정 실행은 R7.5 경로로 금융 확인(`CONFIRMED_UNATTRIBUTED`)과 `RefundPaymentConfirmed`를 쓰고(`outcome=COMPLETED`), 이미 `CONFIRMED_UNATTRIBUTED`인 실행은 금융·이벤트를 바꾸지 않고 판정만 기록한다(`outcome=RECORDED`). 두 경우 모두 `refund_closure`(시도·`requestId` 각각 유일)를 남긴다.
6. THE 종결 SHALL PG 취소를 실행하지 않고, 이미 있는 금융 확인을 덮지 않는다. 귀속 판정은 Payment 기록일 뿐 금액·Order 상태를 바꾸지 않는다.
7. WHERE `GET /api/v1/admin/refund-delays?kind=UNATTRIBUTED`인 경우 THE Payment SHALL `CONFIRMED_UNATTRIBUTED`·종결 기록 없음인 실행을 확인 시각·ID 순으로 준다. `kind`가 없거나 `DELAYED`면 R8.8 목록, `LATE_CANCELLATION`이면 R19.2 목록, 그 밖의 값은 400이다.
8. THE Payment SHALL 귀속 미확정·미종결 건수를 `stove.payment.refund.unattributed.open` 게이지로 내고 경보 `RefundsUnattributed`(`> 0`, warning)를 둔다.
9. THE 귀속의 정본 SHALL Payment(`refund_execution.status`·`refund_closure`) 하나다. Order 환불 시도는 귀속을 갖지 않으므로, 이 종결은 Order에 알릴 사실을 만들지 않는다([결정 164](../../../docs/decisions.md#attribution-in-payment), P3.21-3).

### R16. 끝난 결제의 늦은 청구 감시 ([결정 154](../../../docs/decisions.md#unlinked-charge-discovery))
**사용자 스토리:** 운영자로서, 종결·만료·실패로 끝난 결제에 뒤늦게 생긴 PG 청구를 놓치지 않고 싶다. 고객은 "청구된 금액은 없습니다"를 보고 있는데 돈이 나간 상황을 막기 위해서다.

#### 수용 기준
1. WHEN 결제가 종결·만료·실패로 끝나는 트랜잭션에서 그 결제에 PG 요청이 만들어진 적이 있으면(시도 행이 하나 이상 있음) THEN Payment SHALL 같은 트랜잭션에 청구 감시(`payment_charge_watch`, `WATCHING`)를 만들고, 감시 끝 시각은 adapter가 준 "더 이상 결제될 수 없다고 공급자가 보장하는 시각"으로 기록한다. 값의 근거가 확인되기 전에는 `[외부 대기]`다(P3.13-8).
2. WHILE 감시가 `WATCHING`이고 감시 끝 시각 전이면 THE 청구 감시 작업 SHALL 정해진 간격으로 트랜잭션 밖에서 PG를 조회한다. 간격은 adapter 설정이다(결정 140).
3. WHEN 그 결제의 통지가 오면 THEN Payment SHALL 감시의 다음 조회를 앞당긴다(R6.8). 통지만으로 발견을 보장하지 않는다.
4. WHEN 조회가 PG `PAID`를 관측하면 THEN Payment SHALL 결제·시도를 다시 열지 않고 같은 트랜잭션에서 감시를 `CHARGE_FOUND`로 바꾸고 주문에 연결되지 않은 청구 기록(출처 `LATE_CHARGE`)을 만든다(R17, P3.13-9).
5. WHEN 감시 끝 시각에 이르면 THEN Payment SHALL 감시를 `ENDED`로 닫고 더 조회하지 않는다. 그 뒤의 청구는 공급자 규칙 위반이며 정산 대사(G7)가 마지막 안전망이다.
6. IF 조회가 실패하거나 근거가 대조되지 않으면 THEN Payment SHALL 감시 상태를 바꾸지 않고 다음 회차로 미루며, 오류 종류를 감시에 기록한다.
7. THE 청구 감시 작업 SHALL lease(같은 모듈 시계)로 한 회만 돌고, 조회 결과 저장은 `purchase_payment` → 감시 순서로 잠근 트랜잭션에서 한다.
8. THE Payment SHALL 감시 중 건수와 가장 오래된 감시 경과를 게이지로 낸다.

### R17. 주문에 연결되지 않은 청구 기록과 종결 ([결정 153](../../../docs/decisions.md#late-success-rejection)·[결정 154](../../../docs/decisions.md#unlinked-charge-discovery))
**사용자 스토리:** 운영자로서, 주문에 반영되지 않은 PG 청구를 한 목록에서 보고, 공급자 콘솔에서 취소한 뒤 PG 근거로 닫고 싶다.

#### 수용 기준
1. WHEN Payment가 `OrderPaymentResultRejected`를 처음 받으면 THEN Payment SHALL `OrderPaymentResultRejectedMapper.from`(가칭)에서 주문 연결값 5개·`rejectedResultEventId`·`paymentId`·`admissionId`·`rejectionReason`을 검증하고, 거절된 결과가 이 결제의 `resultEventId`와 같을 때만 Inbox(`payment-order-result-rejection`)와 주문에 연결되지 않은 청구 기록(출처 `ORDER_REJECTED`, `OPEN`)을 같은 트랜잭션에 쓴다. 결제(`PAID`)·시도(`SUCCEEDED`)는 바꾸지 않는다(P3.13-7·P3.14-2, MC-P13).
2. IF 거절된 결과 ID가 이 결제의 결과가 아니거나 주문 연결값이 다르면 THEN Payment SHALL 영구 실패로 거절한다(P2.3-4).
3. WHEN 같은 결제에 같은 출처의 기록이 이미 있으면 THEN Payment SHALL 새 기록을 만들지 않고 무변화로 끝낸다. 다른 출처의 기록은 따로 만든다(결제당 출처별 하나).
4. WHILE 그 결제의 시도가 이미 다른 사유로 보류 중이면 THE Payment SHALL 보류 사유를 바꾸지 않고 기록만 더한다(결정 144, 흐름 2 S9-R "상대 상태가 이미 바뀜").
5. WHEN 기록이 생기면 THEN Payment SHALL 그 기록을 ADMIN 목록(`GET /api/v1/admin/unlinked-charges`)과 게이지 `stove.payment.unlinked-charge.open{source}`에 올리고 critical로 경보하며, 고객 안내를 R14.4로 바꾼다. 시스템은 PG 취소를 실행하지 않는다(P3.13-9).
6. WHEN ADMIN이 `POST /api/v1/admin/unlinked-charges/{chargeId}/closure`(`{requestId, note}`)를 보내면 THEN Payment SHALL 감시 lease를 잡고 트랜잭션 밖에서 PG를 조회해, 원결제가 전액 `CANCELLED`일 때만 같은 트랜잭션에서 기록을 `CLOSED`로 바꾸고 종결자·근거 상태·관측 시각을 남긴다. `PAID`면 409 `CLOSURE_PG_PAID`, 그 밖의 근거는 409 `CLOSURE_PG_NOT_CANCELLED`, 근거 불일치·조회 장애는 `CLOSURE_PG_INVALID_EVIDENCE`·`CLOSURE_PG_UNAVAILABLE`이다. 같은 `requestId`·내용은 최초 결과, 다른 내용은 409 `UNLINKED_CHARGE_CLOSURE_CHANGED`(가칭)다.
7. THE 기록 종결 SHALL 결제·시도 상태를 바꾸지 않는다. WHEN 저장 상태가 `PAID`인 결제의 `ORDER_REJECTED` 기록이 전액 `CANCELLED` 근거로 닫혔으면 THEN 결제 상세 응답 SHALL `status`를 `CHARGE_RETURNED`(가칭, 응답 전용 값)로 계산해 내고, 저장 상태·전이는 바꾸지 않으며 환불 실행을 시작하지 않는다. `LATE_CHARGE`는 저장 상태가 `PAID`가 아니므로 계산하지 않는다([결정 190](../../../docs/decisions.md#charge-returned-view)).
8. WHEN ADMIN이 기록에 조사 내용을 남기면 THEN Payment SHALL R12.3과 같은 규칙으로 추가 전용 저장하고 상태를 바꾸지 않는다.

### R18. 운영자 환불 실패 종결 ([결정 128](../../../docs/decisions.md#refund-failure-closure)·결정 148)
**사용자 스토리:** 운영자로서, PG가 취소를 거절했거나 끝내 확정되지 않은 환불을, 돈이 움직이지 않았음을 PG로 확인했을 때만 실패로 닫아 보유권 복구·고객 재신청에 잇고 싶다.

#### 수용 기준
1. WHEN ADMIN이 `POST /api/v1/admin/refund-delays/{attemptId}/failure-closure`(`{requestId, note}`)를 보내면 THEN Payment SHALL 자동 송신이 멈춘 실행(`dispatch_state` ∈ `HELD`·`BALANCE_BLOCKED`·`WINDOW_ENDED`)이고 금융 미확정·실패 미확정일 때만 대상으로 삼고, 그 밖은 409 `REFUND_NOT_FAILURE_CLOSABLE`이다(P3.23-1).
2. WHEN 종결을 시작하면 THEN Payment SHALL 조회 작업자와 같은 lease를 잡고 송신 쪽에도 종결 표시를 건 뒤 트랜잭션 밖에서 PG를 다시 조회한다. 종결 표시가 살아 있는 동안 송신·재송신·ADMIN 재개는 하지 않는다(P3.20-3, I19).
3. THE 종결 근거 SHALL 원결제 `PAID`·취소액 0·진행 중이거나 성공한 취소 없음이다. 미취소 판정은 adapter가 공급자 응답을 core 값("미취소 확인됨/아님")으로 옮겨 준다. 근거가 아니면 닫지 않고 409로 거절한다.
4. WHEN 근거가 성립하면 THEN Payment SHALL 별도 잠금 트랜잭션에서 lease·종결 표시를 여전히 가졌고 실행이 여전히 대상이며 PG 접수 응답이 조회 전과 같은지 확인한 뒤, 실행을 `FAILED`로 바꾸고 `refund_failure_closure`(근거 종류·중단 원인·조회 해시·연결된 실패 취소 ID·ADMIN·시각)를 저장하고 같은 트랜잭션에서 `RefundFailureClosed`를 쓴다. 같은 `requestId`는 최초 응답, 다른 내용은 409다(P3.23-2·P3.23-3).
5. THE `RefundFailureClosed` SHALL 시도 식별자·`closureId`·`failedCancellationId`(있을 때만)만 싣고, 근거 상세는 `refund_failure_closure`가 정본이다. 한 실행에 `RefundExecutionFailed`와 `RefundFailureClosed`가 함께 나가지 않는다(I16).
6. WHEN 고객 재시도의 사전 확인이 이전 실패를 찾으면 THEN Payment SHALL PG 실패 기록이 없는 운영자 종결 실패도 이전 실패로 받는다(P3.23-6).
7. THE 종결 SHALL PG 취소를 실행하지 않는다.

### R19. 실패 뒤 감시와 늦은 취소 반영 ([결정 128](../../../docs/decisions.md#refund-failure-closure)·[결정 157](../../../docs/decisions.md#late-cancellation-confirmation))
**사용자 스토리:** 운영자로서, 실패로 끝난 환불 뒤 실제로 나타난 PG 전액 취소를 PG 근거로 반영해, 고객이 돈과 게임을 함께 갖거나 판매자가 과지급되지 않게 하고 싶다.

#### 수용 기준
1. WHILE 실행이 `FAILED`(PG 확정 실패·운영자 종결 실패)이면 THE Payment SHALL 낮은 빈도로 PG를 계속 조회한다. 간격은 `min(상한, max(시작값, 경과))`(결정 122 모양, 상한 10분 이하, 환불 전용 설정 키)이고, 상태·보유권·환불을 자동으로 바꾸지 않는다(P3.23-5).
2. WHEN 그 조회가 원결제 전액 취소를 보면 THEN Payment SHALL 실행을 다시 열지 않고 조사 목록(`kind=LATE_CANCELLATION`)과 경보에 올린다. 실패한 실행의 `refund_confirmed_at`은 쓰지 않는다(I18).
3. WHEN ADMIN이 늦은 취소 반영(가칭 `POST /api/v1/admin/refund-delays/{attemptId}/late-cancellation`, `{requestId, note}`)을 요청하면 THEN Payment SHALL 대상이 그 환불의 `FAILED` 실행이고 늦은 취소 확인 기록이 없을 때만, 트랜잭션 밖 PG 재조회에서 원결제가 전액 `CANCELLED`인지 확인한다. IF 같은 환불에 그 실행을 `previousAttemptId`로 가리키는 뒤 실행이 있으면 THEN Payment SHALL PG를 조회하지 않고 409 `LATE_CANCELLATION_SUPERSEDED`(가칭)로 거절하며, 뒤 실행은 자기 경로(R7.3 `BALANCE_BLOCKED` → R7.4 조회 확인, 멈추면 R15 종결)로 끝난다([결정 179](../../../docs/decisions.md#late-cancellation-during-retry), (1회차 지적 A2·B2, 판정 전), MC-P15).
4. WHEN 근거가 성립하면 THEN Payment SHALL 실행 잠금 트랜잭션에서 대상 조건을 다시 확인하고, 늦은 취소 확인 기록(`refund_late_cancellation`: 시도 ID·ADMIN·`requestId`·PG 근거 스냅샷·관측 시각·확인 시각)과 `RefundLateCancellationConfirmed` Outbox를 한 트랜잭션에 쓴다. 근거가 아니면 409로 거절한다(P3.23-7, P4.7-5).
5. THE 시스템 SHALL 늦은 취소 반영을 스스로 하지 않는다. ADMIN 요청만 반영한다(결정 128 C 기각 유지).
6. THE 늦은 취소 반영과 실패 뒤 조회·고객 재시도 사전 확인 SHALL 같은 실행 잠금으로 직렬화하고, 반영 뒤의 재시도 사전 확인은 이미 환불된 결제로 본다(흐름 3 S14 "동시 진행"). WHEN 선행 실행에 늦은 취소 확인 기록이 있는 후속 실행 요청(`RefundExecutionRequested`, `previousAttemptId`)을 받으면 THEN Payment SHALL 실행을 만들지 않고 PG를 부르지 않으며, Inbox와 수신 기록만 남긴다(영구 실패 아님). Order는 같은 늦은 취소 확인으로 그 시도를 `SUPERSEDED`로 닫는다([결정 179](../../../docs/decisions.md#late-cancellation-during-retry)).

### R20. 정방향 응답 대기 ([결정 158](../../../docs/decisions.md#forward-gap-counting)·[결정 159](../../../docs/decisions.md#forward-gap-threshold))
**사용자 스토리:** 운영자로서, Payment가 낸 돈의 사실이 다른 모듈에 끝내 반영되지 않은 건을 Payment 자기 기록으로 세고 싶다.

#### 수용 기준
1. WHEN Payment가 `SettlementRefundRecorded`(가칭)를 처음 받으면 THEN Payment SHALL Inbox(`payment-settlement-refund`)와 그 실행(또는 늦은 취소 확인 기록)의 정산 반영 응답 ID를 같은 트랜잭션에 기록한다(플랫폼 design "정방향 응답 쌍", (1회차 지적 B4, 판정 전)).
2. WHILE 실행에 금융 확인이 있거나 늦은 취소 확인 기록이 있는데 정산 반영 응답이 없으면 THE Payment SHALL 그 건수와 가장 오래 기다린 시간을 게이지로 낸다. 경보 기준 시간은 측정 뒤 정한다(P4.3-4, 결정 159).
3. WHEN Payment가 `OrderPaid`(그룹 `payment-order-paid`)를 처음 받아 `paymentResultEventId`가 결제의 `result_event_id`이고 `paymentId`·`orderNo`가 같으면 THEN Payment SHALL Inbox와 같은 트랜잭션에 그 결제의 Order 반영 응답(`APPLIED`, 응답 `eventId`)을 기록한다. WHEN `OrderPaymentResultRejected`를 받으면 THEN 같은 칸에 `REJECTED`를 기록한다. 응답은 결제마다 하나이고 바꾸지 않으며, 식별자가 다르면 영구 실패다. WHILE 결제가 `PAID`인데 Order 반영 응답이 없으면 THE Payment SHALL 그 건수와 가장 오래 기다린 시간(`result_occurred_at`부터, Payment 시계)을 게이지로 낸다. 경보 기준 시간은 측정 뒤 정한다(결정 159, [결정 189](../../../docs/decisions.md#payment-order-response), (1회차 지적 A1, 판정 전)).
4. THE Payment SHALL 자기가 소유한 현행 이벤트를 같은 `eventId`·본문·키로 다시 낼 수 있는 운영 재발행 경로를 둔다(P2.2-7, [결정 161](../../../docs/decisions.md#republish-all-current), T7).

### R21. 상태 모델과 DB 제약 (원칙 ④⑤)
**사용자 스토리:** 개발자·리뷰어로서, 각 기록의 상태가 enum 하나로 드러나고 DB가 허용 값과 조합을 지키길 원한다.

#### 수용 기준
1. THE 상태 칸(`purchase_payment.status`·`payment_approval.status`·`payment_order_lifecycle.status`·`unlinked_charge.status`·`payment_charge_watch.status`·`refund_execution.status`·`refund_execution.dispatch_state`) SHALL enum 값 목록이고 DB CHECK가 허용 값을 지킨다. 값과 전이는 design "상태 모델"이 정본이다.
2. THE Payment SHALL 보조 칸의 NULL 조합·플래그로 하위 상태를 만들지 않는다. 기술 잠금(lease·종결 표시)은 상태가 아니며 "잠금 중이면 금지" 가드로만 쓴다(P3.1-4).
3. THE 이행 SHALL 옛 행을 새 상태 값으로 옮기는 이행 테스트를 같은 PR에 둔다(결정 104, P2.7-3).

### R22. 공급자 중립 (원칙 ②, [결정 140](../../../docs/decisions.md#defer-real-pg))
**사용자 스토리:** 개발자로서, 공급자를 바꿔도 core를 고치지 않길 원한다.

#### 수용 기준
1. THE core(`core.domain`·`core.service`·`core.port`) SHALL 공급자 이름·공급자 상수·공급자 오류 코드·공급자 보장 기한 값을 쓰지 않는다. IF core가 공급자 이름을 쓰면 THEN 아키텍처 검사 SHALL 실패한다(P1.1-4, T28).
2. THE core SHALL 공급자 차이를 adapter가 준 능력 값·기한 값·분류 결과로만 본다.
3. THE 공개 API 경로·이벤트·응답 SHALL 공급자 이름을 담지 않는다(P2.1-7).

## 범위 밖(후속)

- 실제 PG 연동 확인(승인·취소·오류 타입·통지 공개 수신·보장 기한 값) — G7([결정 140](../../../docs/decisions.md#defer-real-pg)).
- 운영 결제·실제 PG 지급 자료·은행 입금·판매자 송금.
- 다중 PG·다중 작업자 처리량 최적화·분산 임대, 첫 환불 송신의 별도 송신 작업자 전환(G6 지표 기준).
- 카드 정보 저장·결제 수단 다양화·쿠폰·다중 통화·무료 상품·복수 상품/수량.
- 전용 고객 결제 화면.
