# 요구사항: Payment (결제 접수·PG 승인/조회·환불 실행)

## 개요

Payment는 Order가 확정한 주문의 결제를 내구성 있게 접수하고, 외부 PG(현재 SIMULATED 또는 PortOne TEST)로 승인·조회를 수행하며, 미확정 건을 주기적으로 재개한다.
확인된 승인만 PAID로 확정해 `OrderPaymentCompleted`/`OrderPaymentFailed`를 발행하고, Order가 그 결과를 검증해 자기 상태를 바꾼다.
Payment는 가격을 다시 산정하지 않고 Order DB를 직접 바꾸지 않는다. 고객 환불 접수는 Order가 받고, Payment는 승인된 환불의 PG 취소·조회만 실행한다.
MVP 재작성은 2026-10-16 완료했고, 외부 모의 PG 분리와 PortOne TEST 승인·웹훅·환불·관리자 복구/조사까지 더했다(2026-10-18~). 실제 TEST 생명주기(승인·시도 실패 뒤 재결제·응답 유실 복구·자연 만료)는 2026-10-29 확인했다. 금융 보류는 운영 조회·조사(R12)와 PG 재조회 근거의 운영 종결(R13), 보류 기간에 비례해 늘어나는 보류 재조회 간격(R13.10)까지 더했고, 고객 안내·LIVE·정산 연결은 G1 잔여·G3에서 닫는다.

## 용어

- **paymentId**: Payment가 접수 시 발급하는 고정 UUID 문자열. 생성 이후 바뀌지 않으며 PG 승인/조회의 멱등 키로 쓴다. 공급자 거래 ID와 다르다.
- **requestEventId**: 원래 `OrderPlaced`의 eventId. 구매자가 Order에 보낸 requestId와 다르다.
- **provider**: 결제 공급자. `SIMULATED`(외부 모의 PG) 또는 `PORTONE_TEST`.
- **Inbox / Outbox**: 수신 이벤트 중복 제거 저장과, 결과 이벤트를 같은 트랜잭션으로 저장해 릴레이가 발행하는 발신 저장.
- **attemptId**: 환불 실행의 고정 식별자. PG 취소의 Idempotency-Key로 쓴다.
- **멱등 키 3시간 창**: PortOne이 보장하는 멱등 기간. 승인/환불의 자동 재송신 한계로 쓴다.

## 요구사항

### R1. 주문 결제의 내구 접수
**사용자 스토리:** 구매자로서, 내 주문이 안전하게 결제 대기로 접수되길 원한다. 그래야 중복 청구나 유실 없이 결제가 처리된다.

#### 수용 기준
1. WHEN `OrderPlaced` 이벤트를 받으면 THEN 시스템 SHALL 봉투·본문 ID·종류·키와 가격 일관성(`listAmount - discountAmount == amount`, 양수 KRW)을 저장 전에 검증한다.
2. WHEN 새 orderNo·새 requestEventId의 요청이면 THEN 시스템 SHALL `PENDING` 결제 한 건과 Inbox를 같은 트랜잭션으로 저장한다.
3. WHEN 같은 이벤트 ID·같은 전체 입력이 다시 오면 THEN 시스템 SHALL 기존 결제를 보존하고 새 결제·공급자 결정·Outbox를 만들지 않는다.
4. IF 같은 이벤트 ID가 다른 주문·입력으로 오면 THEN 시스템 SHALL 계약 충돌로 처리하고 신규 결제·Inbox를 바꾸지 않는다.
5. IF 같은 주문에 새 이벤트 ID가 오면 THEN 시스템 SHALL 금액이 같아도 새 결제로 수락하지 않는다.
6. WHILE 동시 최초 접수가 일어나는 동안 THE 시스템 SHALL DB 유일 제약으로 한 건만 확정하고, 패한 트랜잭션은 종료 후 재전달에서 기존 입력과 비교한다.
7. THE 리스너 반환 SHALL 요청의 내구 저장만 뜻하며 PG 호출이나 최종 결과 완료를 뜻하지 않는다.
8. THE 기존 요청 내용·이벤트 ID 재사용 비교 SHALL Inbox 중복 생략보다 먼저 하며, 원래 주문 시각(UTC 마이크로초)과 상품·가격 스냅샷 전체를 포함한다.
9. IF Inbox만 있고 결제가 없거나 기존 결제와 접수 기록이 맞지 않으면 THEN 시스템 SHALL 정상 중복으로 숨기지 않고, 신규 접수의 결제와 Inbox를 함께 롤백한다.
10. IF 금액·ID·버전에 소수·잘못된 숫자 형식이 오면 THEN 시스템 SHALL 정수로 절삭하지 않고 저장 전에 거부하며, 접수 충돌은 공통 Kafka 재시도·DLT로 처리한다(공급자 장애 재개는 저장된 `PENDING` 경로가 담당).
11. WHEN `stove.order.v1`에서 `OrderPaid` 등 비대상 이벤트를 수신하면 THEN 시스템 SHALL 무시하고 새 결제·PG 호출을 만들지 않는다.

### R2. 미확정 건 주기 재개와 트랜잭션 경계
**사용자 스토리:** 시스템으로서, 응답 유실이나 재시작 뒤에도 미확정 결제를 스스로 이어 처리하고 싶다. 그래야 결제가 멈추지 않는다.

#### 수용 기준
1. THE 주기 실행 SHALL 단일 인스턴스 fixed-delay로 처리 시각에 도달한 `PENDING`을 `nextAttemptAt, id` 순으로 고른다. 기본 주기 1초·배치 20건이다.
2. WHEN PG를 호출할 때 THEN 시스템 SHALL Payment DB 트랜잭션·행 잠금·커넥션 밖에서 `charge`/조회를 호출한다.
3. THE 시스템 SHALL 접수 시 발급한 paymentId를 재시작·응답 유실·DB 롤백·동시 호출에도 같은 멱등 키로 유지한다.
4. WHEN 일시 오류·미확정·잘못된 공급자 응답이 오면 THEN 시스템 SHALL 다음 시도를 기본 5초 뒤로 예약하고 결제별로 처리해 오류 하나가 배치 나머지를 막지 않게 한다.
5. WHEN 재시작하면 THEN 시스템 SHALL DB의 `PENDING`을 다시 읽어 Kafka 재전달 없이 재개한다.
6. WHERE 설정 `stove.payment.processing-enabled=false`로 기동한 맥락에서 THE 시스템 SHALL 접수만 저장하고 처리를 멈추며, 이 설정을 회원 HTTP API로 노출하지 않는다.
7. THE 예약 저장 SHALL 짧은 트랜잭션에서 `PENDING`을 재확인하고 동시에 확정된 결제를 덮어쓰지 않으며, 예약 저장 자체가 실패해도 원래 `PENDING`을 보존한다.

### R3. 결과 확정과 원자성
**사용자 스토리:** Order로서, 확인된 결과만 신뢰하고 싶다. 그래야 주문 상태를 정확히 바꾼다.

#### 수용 기준
1. WHEN 공급자가 같은 키·주문·금액·통화의 승인을 확인하면 THEN 시스템 SHALL `PAID`와 `OrderPaymentCompleted` Outbox를 같은 트랜잭션으로 커밋한다.
2. WHEN 같은 연결 정보의 명시적 최종 거절을 확인하면 THEN 시스템 SHALL `FAILED`와 `OrderPaymentFailed` Outbox(reasonCode·reason 필수)를 커밋한다.
3. IF 예외·연결 실패·응답 유실·미확정이면 THEN 시스템 SHALL `PENDING`을 유지하고 실패 결과 이벤트를 만들지 않는다.
4. IF 결과 키·주문·금액·통화 불일치나 필수 값 누락이면 THEN 시스템 SHALL `PENDING`을 유지하고 오류를 분류 기록하며 임의로 승인·거절로 해석하지 않는다.
5. IF 공급자는 확정했으나 Payment 결과 트랜잭션이 실패하면 THEN 시스템 SHALL `PENDING`으로 남겨 같은 키로 재확인 후 결과 저장을 재시도한다.
6. THE 시스템 SHALL 시간 경과·타임아웃·재시도 횟수만으로 `FAILED`로 바꾸지 않는다.
7. THE `PAID`·`FAILED` SHALL 최종 상태이며, 이미 확정된 결과를 다른 공급자 거래·금액·결과로 덮어쓰지 않는다.
8. WHEN 동시 처리가 같은 최종 결과를 내면 THEN 시스템 SHALL 먼저 커밋한 상태·결과 이벤트를 유지하고 추가 Outbox를 만들지 않는다.
9. IF 공급자 결과가 이미 저장된 최종 결과와 상충하면 THEN 시스템 SHALL 기존 최종 상태를 보존하고 충돌을 기록하며 반대 결과를 발행하지 않는다.
10. THE 최종 상태 SHALL 공급자 거래·결정 시각과 결과 이벤트 ID·시각을 함께 가지며(`PAID`는 실패 사유 없음, `FAILED`는 유효한 사유), 확정 시 재시도 예약·오류 분류를 정리한다.
11. THE 결과 이벤트 `amount` SHALL 고객 청구·승인액이며 PG 수수료 공제 후 입금액·판매자 지급액으로 대신 쓰지 않는다.

### R4. PortOne TEST 서버 승인과 결제창 정보
**사용자 스토리:** 구매자로서, 실제 결제창에서 승인한 결과가 서버에서 검증돼 반영되길 원한다. 그래야 브라우저 응답이 유실돼도 결제가 완료된다.

#### 수용 기준
1. WHERE `PORTONE_TEST` 모드인 맥락에서 THE 시스템 SHALL `POST /api/v1/payments/{orderNo}/approvals`로 requestId·txId·paymentToken을 받아 202로 내구 접수하고, 처리 중 PG·Order를 호출하지 않는다.
2. WHEN 접수 응답이 유실돼 같은 requestId·txId·토큰이 다시 오면 THEN 시스템 SHALL 최초 영수증에 수렴하고 새 UUID로 바꾸지 않는다.
3. THE 승인 접수 SHALL Order의 최초 승인 접수 영수증을 저장한 뒤에만 confirm을 실행하며, 승인 응답 요약만으로 완료 이벤트를 만들지 않고 다시 조회한다.
4. THE 고정 본문과 토큰 SHALL AES-GCM으로 암호화해 보관하고, 원본을 로그·응답·Order·Kafka에 전달하지 않는다. 재송신은 복호화한 원래 바이트·같은 키만 쓴다.
5. WHEN 승인 POST 응답이 유실되면 THEN 시스템 SHALL 최초 송신 준비부터 3시간 미만에 같은 승인 ID·멱등 키·본문으로 자동 재송신하며 조회를 병행하고, 재시작·재시도·lease 재획득으로 준비 시각을 초기화하지 않는다.
6. WHEN 최초 준비부터 3시간이 되면 THEN 시스템 SHALL 자동 POST를 멈추고 GET만 이어가며, 시간만으로 실패·새 키·새 결제를 만들지 않는다.
7. THE 승인 어댑터 SHALL `PAID`만 확정하고 그 밖의 상태는 미확정(`PENDING`·재조회 예약)으로 유지한다.
8. WHERE `GET /api/v1/payments/{orderNo}/checkout`인 맥락에서 THE 시스템 SHALL 본인·`PORTONE_TEST`·로컬 `PENDING`일 때만 공개 SDK 설정(storeId·channelKey·paymentId·금액·`EASY_PAY`·customData.orderNo·noticeUrls)을 `no-store`로 반환하고, 로컬 `PAID`/`FAILED`·다른 공급자는 409, 타인/없는 결제는 404다. 이 API는 결제 예약·PG 실행이 아니다.
9. THE confirm 본문 SHALL txId·currency·totalAmount·isTest를 고정하고, 공급자 거래 조회로 원구매·금액·통화·TEST 채널·txId를 대조한다. READY·FAILED 응답의 `amount.paid` 값으로 성공을 추정하지 않는다.
10. WHEN Order가 승인 접수에 만료를 응답하면 THEN 시스템 SHALL PG 승인을 실행하지 않고 승인 작업·Payment를 `EXPIRED`로 둔다.
11. WHEN 공급자 거래가 이미 `PAID`이면 THEN 시스템 SHALL 같은 승인 POST를 다시 보내지 않고 조회·기존 승인 영수증으로 복구한다.
12. IF HTTP 오류·토큰 오류·응답 유실이나 설명되지 않는 상태이면 THEN 시스템 SHALL 금융 실패·새 결제를 확정하지 않고 추가 POST를 멈춘 뒤 조사 대상(`REVIEW_REQUIRED`)으로 보존한다.
13. WHEN 검증된 개별 실패가 확인되면 THEN 시스템 SHALL Payment/Order를 최종 `FAILED`로 만들지 않고 Order에 해당 승인 진행 종료만 통지해, 기한 내면 `PENDING` 재진입·기한 후면 `EXPIRED`로 조율한다. 과거 종료 통지 재전송은 이후 진행을 바꾸지 않는다.
14. WHERE `GET /api/v1/payments/{orderNo}/approvals/{requestId}` 맥락에서 THE 시스템 SHALL 상태·접수/고정 만료/종결 시각을 `no-store`로 반환하고 토큰·거래 원문·암호문을 넣지 않으며 타인은 404다. `REVIEW_REQUIRED`·`FAILURE_CONFIRMED`는 새 인증을 열지 않고, `FAILURE_CONFIRMED`는 최초 종료 영수증을 확인할 때까지 새 승인 접수를 막는다.
15. THE Order 내부 승인 접수·실패 통지 호출 SHALL Order·Payment가 공유하는 서비스 키만 사용하며 MEMBER Bearer로 호출할 수 없고 Gateway·공개 명세에 노출하지 않는다.
16. THE checkout SHALL 요청에서 금액·paymentId·채널·웹훅 주소를 받지 않고, `noticeUrls`는 설정한 HTTPS 주소만 쓰며 요청 Host에서 만들지 않는다.

### R5. 같은 주문 재결제와 실패 시도 이력
**사용자 스토리:** 구매자로서, 한 번 결제가 실패해도 같은 주문으로 다시 결제하고 싶다. 그래야 새 주문을 만들지 않고 이어 결제한다.

#### 수용 기준
1. WHEN PortOne의 개별 시도가 FAILED면 THEN 시스템 SHALL 이를 별도 이력으로 보존하고 Payment/Order의 최종 실패로 바꾸지 않는다.
2. THE 재결제 SHALL 같은 orderNo·paymentId·최초 확정 금액의 기존 `PENDING` checkout을 다시 사용한다.
3. THE 실패 시도 SHALL 공급자 transactionId를 기준으로 서버에서 원구매·금액과 대조해 검증·보관하며 주문·결제 상태와 분리한다.
4. THE 시스템 SHALL `PAID`와 결과 Outbox를 실패 이력 조회·수집보다 먼저 커밋하고, 이력 장애는 별도 읽기 재개로 처리하며 오래된 `PENDING` 응답이 승인 뒤 예약을 지우지 않게 한다.
5. WHERE `GET /api/v1/payments/{orderNo}/attempt-failures`인 맥락에서 THE 시스템 SHALL 본인 실패 시도 페이지와 수집 상태를 `no-store`로, DB 조회만으로 제공한다.
6. THE 시스템 SHALL 브라우저 닫기·404·타임아웃을 확정 실패 근거에 섞지 않는다.
7. THE 실패 시도 수집 SHALL 서버가 PortOne `/payments/{paymentId}/transactions`를 조회해 스토어·TEST 채널·주문·금액·실패 시각을 대조하고, 공급자 transactionId별 불변 근거를 보존하되 고객에게는 별도 공개 attemptId를 주며 공급자 원문 사유는 저장·노출하지 않는다.
8. WHEN 이력 수집이 실패하면 THEN 시스템 SHALL 다음 수집 시각에 재예약하고, `PAID` 뒤 재개는 이력 GET만 수행하며, 과거 자료가 없으면 실패·수집 시각을 추정하지 않는다.
9. THE 실패 이력 응답 SHALL 요청/실패 시각·내부 ID 역순 페이지와 `checkedAt`(마지막 정상 수집)·`collectionPending`을 제공하고, 빈 이력은 최종 실패나 성공을 뜻하지 않으며 고객 조회는 PG를 호출하거나 작업을 깨우지 않는다.

### R6. 웹훅 접수(조회 힌트)
**사용자 스토리:** 시스템으로서, 결제 알림을 받아도 그것만으로 성공을 단정하지 않고 서버 조회로 확인하고 싶다. 그래야 위조·중복 알림에 속지 않는다.

#### 수용 기준
1. WHERE `PORTONE_TEST` 맥락에서 `POST /api/v1/payments/webhooks/portone`가 오면 THE 시스템 SHALL 원문 크기를 제한해 읽고 Standard Webhooks의 ID·전달 시각·HMAC-SHA256을 검증하며 5분 시각 허용·회전 중 복수 서명을 지원한다.
2. WHEN 중복 헤더·변조·과거/미래 요청이면 THEN 시스템 SHALL 거절한다(무서명·변조 401, 64 KiB 초과 413, GET 405).
3. WHEN 유효한 알림이 오면 THEN 시스템 SHALL 로컬 결제를 잠그고 ID·본문 해시와 재조회 예약을 한 트랜잭션으로 저장하며, 같은 ID·본문은 최초 기록을 유지하고 ID 재사용·다른 본문은 거절한다.
4. THE 웹훅 SHALL `PAID`·`FAILED`·환불 완료를 만들지 않으며, 기존 조회(`ResolvePayment`)가 확인한 `PAID`만 기존 결과·Outbox 경로로 확정한다.
5. THE 시스템 SHALL 외부 HTTP를 기다리며 DB 잠금을 보유하지 않고, 로컬 결제가 없거나 공급자가 다르면 새 업무를 만들지 않는다.
6. IF 웹훅 본문에 JSON 중복 키·뒤따르는 추가 JSON·필수 연결값 누락·다른 상점이 있으면 THEN 시스템 SHALL 거절하고, 서명이 유효한 알 수 없는 이벤트 종류는 200으로 무시한다.
7. WHEN 서명이 검증된 `Transaction.CancelPending`·`PartialCancelled`·`Cancelled`를 처음 접수하면 THEN 시스템 SHALL 준비된 미확정 환불 실행의 조회를 다음 스캔으로 앞당기되 정기 기한은 초기화하지 않고, 같은 ID·본문의 재전달과 이미 확정된 금융 사실은 깨우지 않는다.

### R7. 환불 실행(PG 취소·조회)
**사용자 스토리:** 시스템으로서, 승인된 환불만 PG에서 취소하고 그 성공을 근거로 확정하고 싶다. 그래야 중복 취소나 임의 실패 없이 환불이 처리된다.

#### 수용 기준
1. WHEN `RefundExecutionRequested`를 받으면 THEN 시스템 SHALL 이를 내구 접수하고 고정 attemptId·고정 본문과 최초 준비부터 3시간 창으로 취소/조회를 수행한다.
2. THE 환불 PG 호출 SHALL attemptId를 Idempotency-Key로 쓰고 원결제액을 amount·currentCancellableAmount 양쪽에 명시하며 부분 취소액을 채워 넣지 않는다.
3. IF 현재 취소액이 0보다 크고 원액보다 작으면 THEN 시스템 SHALL 자동 송신을 중단하고, 명시적 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN` 409만 잔액 변화로 관측한다.
4. THE 시스템 SHALL HTTP 2xx만으로 실행 성공을 기록하지 않고, 검증된 전액 취소 조회(SUCCEEDED·취소 ID·합계=원액·원래 요청 연결) 또는 검증된 취소 완료 응답으로만 금융 완료를 확정한다.
5. WHEN 전액 취소가 증명되면 THEN 시스템 SHALL `RefundPaymentConfirmed`를 발행하고 원 `purchase_payment`의 `PAID`·원금액을 바꾸지 않는다.
6. THE 실행 FAILED SHALL 우리 시도에 연결된 PG 취소 ID의 FAILED와, 추가 GET으로 확인한 원결제 PAID·전액 미취소·진행/성공/미지 취소 없음이 함께 있을 때만 `RefundExecutionFailed` Outbox와 함께 저장한다. 취소 ID 누락·부분 취소·조회 장애는 실패를 만들지 않고, FAILED 내역의 reason을 PG 실패 원인으로 표시하지 않는다. 이용권 복구 확인은 Order가 소유한다. IF 금융 완료가 먼저 커밋됐으면 THEN 뒤늦은 실패 근거로 실패·복구를 만들지 않는다.
7. THE 시스템 SHALL 시간 경과·타임아웃·연결 실패만으로 환불 실패·새 키·새 시도를 만들지 않고 정지·GET을 유지한다.
8. WHERE 401·GET 403/404·POST 404 맥락에서 THE 시스템 SHALL 기존 조건 회복 후 같은 요청을 재개하고, POST 403·정확한 400 `INVALID_REQUEST`는 자동 POST를 멈추고 GET을 유지한다.
9. THE 자동 취소 POST SHALL 송신 직전 정상 GET으로 원결제·상점·채널·주문·통화·PG 거래/시각과 원액=취소 요청액=예상 잔액을 대조한 뒤에만 보낸다. 최초 실행은 취소 이력이 없어야 하고 후속 실행은 저장한 선행 FAILED 이력과 정확히 일치해야 하며, 조회 실패·검증 불가면 그 회에는 POST하지 않는다.
10. THE Idempotency-Key SHALL attemptId를 RFC 8941 문자열로 감싼 값이며, 본문은 최초 준비 때 커밋한 UTF-8 원문을 재사용하고 reason은 refundId 기반 기술 상관값이다.
11. WHEN 우리 시도의 REQUESTED/FAILED 정상 접수 응답이 있으면 THEN 시스템 SHALL 같은 시도의 자동 POST를 멈추고 조회만 이어간다. `IDEMPOTENCY_OUTSTANDING_REQUEST`·일반 409는 미확정으로 다룬다.
12. THE 전액 취소 확인 SHALL 중복 없는 양수 성공 취소 ID들의 합계가 원액인 경우(여러 건 합산 포함)를 인정하고 FAILED·REQUESTED 내역은 성공 합계에서 제외한다. 선택 항목 cancelledAt이 없으면 추정하지 않고 NULL로 둔다.
13. WHEN 조회로 먼저 금융 완료(`PG_QUERY_UNATTRIBUTED`)가 확정된 뒤 같은 취소 ID·금액의 직접 성공 응답이 확인되면 THEN 시스템 SHALL 최초 금융 근거를 유지하고 그 이벤트를 참조하는 `RefundExecutionSucceeded`를 한 번만 저장한다. 다른 취소 ID의 성공 주장은 거절한다.
14. WHEN 명시적 후속 실행 요청(previousAttemptId)이 오면 THEN 시스템 SHALL 같은 PAID·refundId·검증된 FAILED 선행 실행·새 실제 정지 완료를 확인하고 선행 실행 하나당 후행 실행 하나만 접수한다. 후행 실행은 새 attemptId·본문·준비 시각·3시간 창을 가진다.
15. WHILE POST 403 또는 정확한 400 `INVALID_REQUEST` 중단이 활성인 동안 THE 시스템 SHALL Kafka 재전달·재송신 스캔·내부 호출 어디서도 POST하지 않으며, GET 회복이나 설정 수정만으로 중단을 풀지 않는다. 그 밖의 400 형태(미지 타입·깨진 JSON 등)는 일반 미확정이다.
16. THE 운영 재개 SHALL 현재 중단 ID·미확정·최초 준비 후 3시간 미만·새 접수/완료/부분 취소 없음을 확인한 뒤 다음 스캔 대상으로만 등록하고 재송신 단계를 초기화하지 않는다. 같은 requestId·내용은 최초 영수증을 반환하고, 예전 재개 지시는 새 중단을 풀지 않는다.

### R8. 조회·관리자 운영과 최소 보안
**사용자 스토리:** 구매자·운영자로서, 각자 권한에 맞는 결제·환불 상태만 보고 싶다. 그래야 소유권과 운영 경계가 지켜진다.

#### 수용 기준
1. WHERE `GET /api/v1/payments`·`GET /api/v1/payments/{orderNo}` 맥락에서 THE 시스템 SHALL 서명·issuer·만료·`aud=esd-api`·`MEMBER`가 검증된 JWT sub를 구매자로 쓴다.
2. THE 시스템 SHALL 익명 401, 역할 없음 403, 타인·없는 결제 404, 잘못된 페이지 400을 적용하고, 조회가 결제를 생성·실행하지 않는다.
3. THE 목록의 임의 buyerSubject·memberId·`X-Member-Id` SHALL 조회 소유자를 바꾸지 않는다.
4. THE 본인 응답 SHALL paymentId·orderNo·상품 스냅샷·정가/할인/청구액·통화·상태·생성/갱신/결과 시각·실패 사유·provider를 제공하고, requestEventId·resultEventId·Inbox/Outbox·공급자 요청 원문·재시도/내부 오류는 숨긴다.
5. WHERE `ADMIN` 맥락에서 THE 시스템 SHALL `/api/v1/admin/refund-cancellation-holds`로 운영 중단 목록·상세·재개를, `/api/v1/admin/refund-delays`로 장기 미확정 목록·상세·조사 이력/기록을 제공하며, 재개는 원래 키·본문·3시간 창을 유지하고 조사 기록은 금융 상태·일정을 바꾸지 않는다.
6. THE 시스템 SHALL 구 prepare·callback·cancel·simulator·승인 주입 업무 API와 이전 Gateway의 익명/CSRF 예외를 제공하지 않는다.
7. WHILE `OrderPlaced`를 아직 접수하지 못한 동안 THE 상세 조회 SHALL 404를 반환하며 결제를 만들지 않는다(주문 생성 직후 잠시 404일 수 있다).
8. THE 장기 미확정 대상 SHALL 최초 준비 + 3시간 이상이고 `PENDING`·금융 미확정인 PortOne 환불 시도이며 준비 시각을 접수 시각으로 추정하지 않는다. 전액 취소 조회로 완료된 건은 현재 목록에서 빠지되 조사 표시는 보존한다. 조사 기록 POST는 같은 requestId·내용이면 최초 기록을 반환하고, 다른 내용·결과 확정 뒤 새 기록은 409다.

### R9. 모의 PG 포트와 비용·정산 경계
**사용자 스토리:** 시스템으로서, 공급자 결정을 독립적으로 보존하고 정산 책임과 섞지 않고 싶다. 그래야 장애·롤백이 격리된다.

#### 수용 기준
1. THE `core.port.PgGateway` SHALL paymentId·orderNo·amount·currency를 담은 요청으로 승인/조회를 제공하고, 외부 호출·HTTP 구현은 `adapter`, 조립은 `config`에 둔다.
2. THE 모의 공급자(simulators/mock-pg) SHALL 독립 프로세스·전용 MySQL·전용 키로 결정·비용을 보존하고, 같은 키의 반복 호출에 같은 결정을 반환하며 같은 키의 다른 주문·금액·통화는 거절한다.
3. THE 시스템 SHALL PG 비용을 현재 미확정(null)로 두고 0원을 만들지 않는다.
4. THE Settlement SHALL PG 명세를 직접 조회하며, Payment에 정산 중계 API를 두지 않는다.
5. THE Payment 승인·조회 키, Settlement 읽기 키, PG 제어 키 SHALL 서로 분리하고 제어 키를 업무 앱에 제공하지 않는다.
6. THE `SimulatedPgHttpGateway` SHALL 조회가 404일 때만 같은 paymentId로 승인을 요청하고, 통신 실패·잘못된 응답은 `PENDING`을 유지하며 로컬 승인 폴백을 두지 않는다.
7. THE 모의 공급자 SHALL 결정 저장 뒤 설정이 바뀌어도 같은 키의 결과를 바꾸지 않고, 금액·상품 코드의 특정 값으로 성공/실패를 숨겨 결정하지 않는다.

### R10. PG 할인과 고객 실결제액 보존
**사용자 스토리:** 구매자·정산으로서, PG가 할인한 거래도 주문 금액을 바꾸지 않고 실제 결제액을 따로 보고 싶다. 그래야 주문·결제 금액이 엇갈리지 않는다([결정 34](../../../docs/decisions.md#provider-discount-payment-amounts)).

#### 수용 기준
1. WHEN PortOne `PAID`를 확정할 때 THEN 시스템 SHALL 상점·TEST 채널·통화·주문·원래 승인 시도·주문 총액·미취소를 대조하고 `실결제액 + PG 할인액 = 주문 총액`을 요구한다.
2. THE 시스템 SHALL PG 실결제액·할인액을 `pg_paid_amount`·`pg_discount_amount`와 공개 조회 `pgPaidAmount`·`pgDiscountAmount`에 보존하고, 원래 `amount`·주문 할인·완료 이벤트 금액은 바꾸지 않는다.
3. THE DB SHALL 금액 쌍의 NULL 여부 일치·`PAID` 상태·음수 금지·합계 일치를 CHECK로 검사하고, 확인되지 않은 과거 값은 NULL로 두어 0원으로 추정하지 않는다.
4. IF 같은 결과의 중복 확정이 금액 쌍을 바꾸면 THEN 시스템 SHALL 거절한다.
5. THE PG 할인액 SHALL PG 비용·판매자 부담으로 분류하지 않는다.

### R11. Order 만료 수신과 순서 역전
**사용자 스토리:** 구매자로서, 주문이 만료되면 내 결제에도 만료가 보이고 불필요한 조회가 멈추길 원한다. 그래야 새 주문으로 다시 구매한다.

#### 수용 기준
1. WHEN `OrderExpired`(orderNo·원 OrderPlaced ID·구매자·청구액·통화·고정 기한·만료 확정 시각)를 받으면 THEN 시스템 SHALL 봉투·본문·키를 검증하고 Inbox·원구매 대조·주문별 잠금·상태 변경을 한 트랜잭션으로 저장하며, 금융 실패로 해석하지 않는다.
2. THE `OrderPlaced`·`OrderExpired` 수신 SHALL 모두 같은 주문별 생명주기 행(`payment_order_lifecycle`)을 먼저 잠그고, Payment가 아직 없어도 최초 만료 근거를 보존한다. 이 행은 Payment 삭제 cascade로 지우지 않는다.
3. WHEN 만료 뒤 늦은 `OrderPlaced`가 오면 THEN 시스템 SHALL 원래 구매 조건과 대조해 승인·조회 예약 없는 `EXPIRED` Payment를 만들고 기존 만료를 다시 열지 않는다.
4. WHEN 승인 작업이 없는 `PENDING`이 만료되면 THEN 시스템 SHALL `EXPIRED`로 정리하고 승인 결과·실패 이력 조회 예약을 비우며, 과거 실패 이력·구매 스냅샷은 보존하고 금융 실패·환불·권한 회수 이벤트를 만들지 않는다.
5. WHEN 접수만 된 `REQUESTED` 승인 작업이 있으면 THEN 작업자 SHALL Order 만료 근거를 확인해 PG 호출 없이 `EXPIRED`로 닫고 암호문을 제거한다. 이미 진행 중인 작업은 수신기가 즉시 종료하지 않고 작업자가 실패·원래 종료 영수증을 확인한다.
6. WHEN 기한 내 실패 종료 영수증이 `PENDING`이었더라도 이후 실제 만료 통지를 받으면 THEN 시스템 SHALL 실패 확인을 끝낸 Payment를 `PENDING`으로 남기지 않되, 최초 종료 영수증·실패 시도는 바꾸지 않는다.
7. IF 만료와 승인 진행이 모순되거나 확정 금융 결과가 이미 있으면 THEN 시스템 SHALL 추가 POST를 멈추고 GET·기존 근거를 유지하며, 확정 결과를 덮어쓰지 않고 만료 통지와 충돌 표시를 보관한다.
8. WHEN `PG_INVALID_EVIDENCE`로 송신 중단된 `REVIEW_REQUIRED` 작업이 승인 접수·송신 준비·실패 종료 기록 없이 원구매와 대조된 새 READY를 관측하고 확정 만료 통지가 있으면 THEN 시스템 SHALL 같은 Payment 잠금·현재 lease 안에서 승인 작업·Payment를 `EXPIRED`로 바꾸고 암호문·조회 예약을 제거한다. 시각 경과·조회 장애·금액 불일치·승인 접수 이력이 있으면 이 경로로 닫지 않고, 관측한 `PAID_WITHOUT_ADMISSION` 사유는 덮어쓰지 않는다.
9. WHILE 생성·만료가 동시에 일어나는 동안 THE 시스템 SHALL 주문별 잠금으로 직렬화하고, 같은 만료의 중복 수신은 최초 만료 ID·시각을 바꾸지 않는다.

### R12. 승인 보류 운영 조회와 조사 (결정 114 1부)

#### 수용 기준
1. WHEN ADMIN이 승인 보류 목록을 요청하면 THEN 시스템 SHALL REVIEW_REQUIRED만 페이지·사유 필터로 DB에서 조회하고 no-store를 반환한다. 토큰·보호 본문·암호문은 공개하지 않는다.
2. WHEN 승인 작업자가 PG를 조회하면 THEN 시스템 SHALL 최신 상태와 관측 시각을 inspect 후 별도 잠금 트랜잭션에 저장한다. 최초 보류 시각은 보존하며 기존 행의 알 수 없는 시각은 NULL로 둔다.
3. WHEN ADMIN이 보류 조사 내용을 기록하면 THEN 시스템 SHALL JWT sub·서버 시각과 500자 이내 내용을 append-only로 저장한다. 입력 본문 신원은 사용하지 않는다.
4. WHILE 승인 보류가 존재하면 THEN 시스템 SHALL 6개 알려진 사유(`PAID_WITHOUT_ADMISSION`·`PG_REQUEST_REJECTED`·`PG_INVALID_EVIDENCE`·`ORDER_ADMISSION_CONFLICT`·`ORDER_EXPIRY_CONFLICT`·`PG_CANCELLED`)와 OTHER로 제한한 게이지와 ADMIN API 안내 경보를 제공한다. `PG_CANCELLED`는 R13.8(결정 115)에서 더했다.
5. THE 시스템 SHALL 보류 종결·재시도 간격·고객 메시지·금융 동작 정책을 이 기능에서 정하지 않는다. 종결은 R13(결정 115), 재조회 간격은 R13.10(결정 122)이 정한다.

6. WHEN 상세·조사 이력을 조회하면 THEN 시스템 SHALL 과거에 보류된 승인도 현재 status와 함께 제공하고, 한 번도 보류되지 않은 승인은 404로 거절한다. POST는 현재 REVIEW_REQUIRED에만 허용한다.
7. WHEN inspect가 실패하면 THEN 시스템 SHALL lastInspectedAt과 INVALID_EVIDENCE 또는 UNAVAILABLE을 기록하고 원래 예외를 유지한다. 성공은 오류를 지운다. latestPgState는 마지막 유효 관측(the last valid observation)이다. lease 상실 뒤에는 관측·오류를 저장하지 않는다.
8. THE 시스템 SHALL 스크레이프당 GROUP BY 한 번으로 사유별 보류를 집계하고 PAID_WITHOUT_ADMISSION은 critical, 나머지는 warning으로 경보한다. 알려진 과거 보류는 Alertmanager silence를 사용한다.
9. THE 시스템 SHALL 유효 PG 상태 집합·상태/시각 쌍·보류 시각 순서를 DB CHECK로 보장한다. 추가 열과 과거 자료 보존 근거는 [설계](design.md#승인-보류-운영-조회-결정-112-part-1)를 따른다.

### R13. 승인 보류 운영 종결 (결정 115, 결정 114 2부)
**사용자 스토리:** 운영자로서, PG 콘솔에서 처리한 보류 승인을 PG 재조회 근거로 끝내고 싶다. 그래야 주문이 끝나고 고객을 새 주문으로 안내할 수 있다.

#### 수용 기준
1. WHEN ADMIN이 `POST /api/v1/admin/approval-reviews/{approvalId}/closure`로 `{requestId(UUID), note(공백 불가·500자 이내)}`를 보내면 THEN 시스템 SHALL JWT sub를 종결자로 기록하고 승인·결제 상태와 종결 기록을 `no-store`로 반환한다. 토큰·보호 본문·암호문은 넣지 않는다. 같은 requestId·같은 내용(대상·종결자·note)은 PG를 다시 조회하지 않고 최초 결과를 반환하고, 다른 내용은 409 `APPROVAL_CLOSURE_CHANGED`다. 익명 401·역할 없음 403·없는 승인 404·잘못된 입력 400이다. PortOne 승인만 보류되므로 `PORTONE_TEST` 모드에만 있다.
2. THE 종결 SHALL 현재 `REVIEW_REQUIRED`인 승인에만 허용하고, 그 밖은 409 `APPROVAL_NOT_HELD`다.
3. WHEN 종결을 시작하면 THEN 시스템 SHALL 작업자와 같은 lease를 먼저 잡는다. 다음 조회 시각은 기다리지 않지만, 작업자가 lease를 쥐고 있으면 409 `APPROVAL_CLOSURE_BUSY`(나중에 다시 시도)다. lease를 잡은 뒤 DB 트랜잭션 없이 PortOne을 조회하고, payment→approval 잠금 안에서 상태와 lease 소유를 다시 확인한다. lease를 잃었으면 아무것도 저장하지 않는다.
4. THE 종결 근거 SHALL 전액 CANCELLED·FAILED·READY(보류로 송신이 멈춰 `confirm`을 보내지 않음)뿐이다. PAID는 409 `CLOSURE_PG_PAID`(콘솔에서 먼저 취소), PENDING/PAY_PENDING은 `CLOSURE_PG_PENDING`, 근거 불일치는 `CLOSURE_PG_INVALID_EVIDENCE`, 조회 장애는 `CLOSURE_PG_UNAVAILABLE`, 이미 최종 결과가 있는 결제는 `CLOSURE_PAYMENT_RESOLVED`다. 모든 시도의 관측은 R12.7처럼 최신 PG 상태·조회 시각·오류 종류로 남긴다. 거절되면 lease를 돌려주고 작업자의 원래 다음 조회 시각을 유지한다.
5. WHEN 근거가 맞으면 THEN 시스템 SHALL 한 트랜잭션에서 승인을 `FAILED`로 끝내고(보류 사유 `lastErrorCode` 유지, 일정·암호문·lease 제거) append-only 종결 기록(approvalId·requestId·종결자·note·근거 상태·관측 시각·종결 시각)을 남긴다. 접수된 승인이거나 주문 만료 통지를 받지 않았으면 결제를 `FAILED`(reasonCode `REVIEW_CLOSED`)로 만들고 `OrderPaymentFailed`를 같은 Outbox에 저장한다. 접수 전이고 만료 통지를 이미 받았으면 결제를 `EXPIRED`로 두고 이벤트를 만들지 않는다.
6. THE 종결 SHALL PG 취소·승인을 실행하지 않는다. 종결 뒤 그 승인은 PortOne POST·조회를 다시 하지 않고, 같은 주문의 checkout·새 승인 접수는 409이며, 보류 게이지에서 빠진다.
7. THE 상세·조사 이력 조회 SHALL 종결 기록을 함께 제공한다.
8. WHEN 작업자가 전액 CANCELLED를 관측하면 THEN 시스템 SHALL 스스로 닫지 않는다. 보류 전 승인은 송신을 멈추고 사유 `PG_CANCELLED`로 `REVIEW_REQUIRED`가 되고, 이미 보류된 승인은 원래 사유를 유지한 채 관측만 기록한다. 전액 CANCELLED는 같은 시도·`amount.cancelled == amount.total`·성공 취소 합계 = 총액일 때만이며, 부분 취소·합계 불일치·미확정 취소 내역·다른 시도의 취소는 잘못된 근거다. `PG_CANCELLED`는 게이지의 알려진 사유이고 경보는 warning이다(돈은 이미 돌아감).
9. WHILE 종결과 Payment의 주문 만료 수신이 엇갈리는 동안 THE 시스템 SHALL 잠금 안에서 만료 통지를 읽는다. 만료가 먼저 커밋되면 `EXPIRED`(이벤트 없음)로, 종결이 먼저면 `FAILED`를 유지하고 늦은 만료 통지는 충돌 표시와 함께 보존한다(R11.7). 주문이 스스로 만료된 뒤 도착한 `REVIEW_CLOSED`는 Order가 만료를 유지하는 무효과로 받는다(Order R4.8).
10. WHILE 승인이 `REVIEW_REQUIRED`인 동안 THE 작업자 SHALL 다음 PG 조회 시각을 `now + min(상한, max(시작값, now − reviewStartedAt))`로 잡는다(시작값 5초·상한 10분, [결정 122](../../../docs/decisions.md#approval-review-requery-interval)). `reviewStartedAt`이 없는 옛 행은 상한을 쓰며, 새 열·이행은 없다. 보류가 아닌 승인과 일반 결제 재시도는 `PAYMENT_RETRY_MS`를 그대로 쓴다. 종결(R13.3)은 이 시각을 기다리지 않고, 거절되면 이 시각으로 돌아간다(R13.4). 보류가 저절로 풀리는 경우(PAID·FAILED·READY 만료)도 이 간격의 조회에서 같은 규칙으로 처리한다. 고객 안내 문구는 이 기능에서 정하지 않는다. 결과 이벤트의 reason은 고객 문구가 아닌 기술 사유다.

## 범위 밖(후속)

- **G1 남음:** 금융 보류(`REVIEW_REQUIRED`) 건의 고객 안내 문구(결정 114·115), G1 종료 조건 대조. 운영 종결은 R13, 재조회 간격은 R13.10으로 정했다.
- **G3:** 같은 공급자 거래/비용·지급 자료로 Settlement까지 연결, 미확정 비용 모델 확정.
- LIVE 결제·실제 PG 지급 자료·은행 입금·판매자 송금.
- 다중 PG·다중 워커 최적화·처리량 향상·분산 임대/ShedLock.
- 카드 정보 저장·결제 수단 다양화·쿠폰/다중 통화/무료 상품 지급·복수 상품/수량.
