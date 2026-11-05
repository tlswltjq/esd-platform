# 요구사항: Payment (결제 접수·PG 승인/조회·환불 실행)

## 개요

Payment는 Order가 확정한 주문의 결제를 내구성 있게 접수하고, 외부 PG(현재 SIMULATED 또는 PortOne TEST)로 승인·조회를 수행하며, 미확정 건을 주기적으로 재개한다.
확인된 승인만 PAID로 확정해 `OrderPaymentCompleted`/`OrderPaymentFailed`를 발행하고, Order가 그 결과를 검증해 자기 상태를 바꾼다.
Payment는 가격을 다시 산정하지 않고 Order DB를 직접 바꾸지 않는다. 고객 환불 접수는 Order가 받고, Payment는 승인된 환불의 PG 취소·조회만 실행한다.
MVP 재작성은 2026-10-16 완료했고, 외부 모의 PG 분리와 PortOne TEST 승인·웹훅·환불·관리자 복구/조사까지 더했다(2026-10-18~). 실제 PG의 일반 생명주기·LIVE·정산 연결은 부분 구현이며 G1·G3에서 닫는다.

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

### R5. 같은 주문 재결제와 실패 시도 이력
**사용자 스토리:** 구매자로서, 한 번 결제가 실패해도 같은 주문으로 다시 결제하고 싶다. 그래야 새 주문을 만들지 않고 이어 결제한다.

#### 수용 기준
1. WHEN PortOne의 개별 시도가 FAILED면 THEN 시스템 SHALL 이를 별도 이력으로 보존하고 Payment/Order의 최종 실패로 바꾸지 않는다.
2. THE 재결제 SHALL 같은 orderNo·paymentId·최초 확정 금액의 기존 `PENDING` checkout을 다시 사용한다.
3. THE 실패 시도 SHALL 공급자 transactionId를 기준으로 서버에서 원구매·금액과 대조해 검증·보관하며 주문·결제 상태와 분리한다.
4. THE 시스템 SHALL `PAID`와 결과 Outbox를 실패 이력 조회·수집보다 먼저 커밋하고, 이력 장애는 별도 읽기 재개로 처리하며 오래된 `PENDING` 응답이 승인 뒤 예약을 지우지 않게 한다.
5. WHERE `GET /api/v1/payments/{orderNo}/attempt-failures`인 맥락에서 THE 시스템 SHALL 본인 실패 시도 페이지와 수집 상태를 `no-store`로, DB 조회만으로 제공한다.
6. THE 시스템 SHALL 브라우저 닫기·404·타임아웃을 확정 실패 근거에 섞지 않는다.

### R6. 웹훅 접수(조회 힌트)
**사용자 스토리:** 시스템으로서, 결제 알림을 받아도 그것만으로 성공을 단정하지 않고 서버 조회로 확인하고 싶다. 그래야 위조·중복 알림에 속지 않는다.

#### 수용 기준
1. WHERE `PORTONE_TEST` 맥락에서 `POST /api/v1/payments/webhooks/portone`가 오면 THE 시스템 SHALL 원문 크기를 제한해 읽고 Standard Webhooks의 ID·전달 시각·HMAC-SHA256을 검증하며 5분 시각 허용·회전 중 복수 서명을 지원한다.
2. WHEN 중복 헤더·변조·과거/미래 요청이면 THEN 시스템 SHALL 거절한다(무서명·변조 401, 64 KiB 초과 413, GET 405).
3. WHEN 유효한 알림이 오면 THEN 시스템 SHALL 로컬 결제를 잠그고 ID·본문 해시와 재조회 예약을 한 트랜잭션으로 저장하며, 같은 ID·본문은 최초 기록을 유지하고 ID 재사용·다른 본문은 거절한다.
4. THE 웹훅 SHALL `PAID`·`FAILED`·환불 완료를 만들지 않으며, 기존 조회(`ResolvePayment`)가 확인한 `PAID`만 기존 결과·Outbox 경로로 확정한다.
5. THE 시스템 SHALL 외부 HTTP를 기다리며 DB 잠금을 보유하지 않고, 로컬 결제가 없거나 공급자가 다르면 새 업무를 만들지 않는다.

### R7. 환불 실행(PG 취소·조회)
**사용자 스토리:** 시스템으로서, 승인된 환불만 PG에서 취소하고 그 성공을 근거로 확정하고 싶다. 그래야 중복 취소나 임의 실패 없이 환불이 처리된다.

#### 수용 기준
1. WHEN `RefundExecutionRequested`를 받으면 THEN 시스템 SHALL 이를 내구 접수하고 고정 attemptId·고정 본문과 최초 준비부터 3시간 창으로 취소/조회를 수행한다.
2. THE 환불 PG 호출 SHALL attemptId를 Idempotency-Key로 쓰고 원결제액을 amount·currentCancellableAmount 양쪽에 명시하며 부분 취소액을 채워 넣지 않는다.
3. IF 현재 취소액이 0보다 크고 원액보다 작으면 THEN 시스템 SHALL 자동 송신을 중단하고, 명시적 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN` 409만 잔액 변화로 관측한다.
4. THE 시스템 SHALL HTTP 2xx만으로 실행 성공을 기록하지 않고, 검증된 전액 취소 조회(SUCCEEDED·취소 ID·합계=원액·원래 요청 연결) 또는 검증된 취소 완료 응답으로만 금융 완료를 확정한다.
5. WHEN 전액 취소가 증명되면 THEN 시스템 SHALL `RefundPaymentConfirmed`를 발행하고 원 `purchase_payment`의 `PAID`·원금액을 바꾸지 않는다.
6. WHEN 검증된 개별 FAILED와 실제 복구가 확인되면 THEN 시스템 SHALL 그 실행을 FAILED로 귀속하고, 같은 orderNo·paymentId로 재결제 경로는 유지한다.
7. THE 시스템 SHALL 시간 경과·타임아웃·연결 실패만으로 환불 실패·새 키·새 시도를 만들지 않고 정지·GET을 유지한다.
8. WHERE 401·GET 403/404·POST 404 맥락에서 THE 시스템 SHALL 기존 조건 회복 후 같은 요청을 재개하고, POST 403·정확한 400 `INVALID_REQUEST`는 자동 POST를 멈추고 GET을 유지한다.

### R8. 조회·관리자 운영과 최소 보안
**사용자 스토리:** 구매자·운영자로서, 각자 권한에 맞는 결제·환불 상태만 보고 싶다. 그래야 소유권과 운영 경계가 지켜진다.

#### 수용 기준
1. WHERE `GET /api/v1/payments`·`GET /api/v1/payments/{orderNo}` 맥락에서 THE 시스템 SHALL 서명·issuer·만료·`aud=esd-api`·`MEMBER`가 검증된 JWT sub를 구매자로 쓴다.
2. THE 시스템 SHALL 익명 401, 역할 없음 403, 타인·없는 결제 404, 잘못된 페이지 400을 적용하고, 조회가 결제를 생성·실행하지 않는다.
3. THE 목록의 임의 buyerSubject·memberId·`X-Member-Id` SHALL 조회 소유자를 바꾸지 않는다.
4. THE 본인 응답 SHALL paymentId·orderNo·상품 스냅샷·정가/할인/청구액·통화·상태·생성/갱신/결과 시각·실패 사유·provider를 제공하고, requestEventId·resultEventId·Inbox/Outbox·공급자 요청 원문·재시도/내부 오류는 숨긴다.
5. WHERE `ADMIN` 맥락에서 THE 시스템 SHALL `/api/v1/admin/refund-cancellation-holds`로 운영 중단 목록·상세·재개를, `/api/v1/admin/refund-delays`로 장기 미확정 목록·상세·조사 이력/기록을 제공하며, 재개는 원래 키·본문·3시간 창을 유지하고 조사 기록은 금융 상태·일정을 바꾸지 않는다.
6. THE 시스템 SHALL 구 prepare·callback·cancel·simulator·승인 주입 업무 API와 이전 Gateway의 익명/CSRF 예외를 제공하지 않는다.

### R9. 모의 PG 포트와 비용·정산 경계
**사용자 스토리:** 시스템으로서, 공급자 결정을 독립적으로 보존하고 정산 책임과 섞지 않고 싶다. 그래야 장애·롤백이 격리된다.

#### 수용 기준
1. THE `core.port.PgGateway` SHALL paymentId·orderNo·amount·currency를 담은 요청으로 승인/조회를 제공하고, 외부 호출·HTTP 구현은 `adapter`, 조립은 `config`에 둔다.
2. THE 모의 공급자(simulators/mock-pg) SHALL 독립 프로세스·전용 MySQL·전용 키로 결정·비용을 보존하고, 같은 키의 반복 호출에 같은 결정을 반환하며 같은 키의 다른 주문·금액·통화는 거절한다.
3. THE 시스템 SHALL PG 비용을 현재 미확정(null)로 두고 0원을 만들지 않는다.
4. THE Settlement SHALL PG 명세를 직접 조회하며, Payment에 정산 중계 API를 두지 않는다.
5. THE Payment 승인·조회 키, Settlement 읽기 키, PG 제어 키 SHALL 서로 분리하고 제어 키를 업무 앱에 제공하지 않는다.

## 범위 밖(후속)

- **G1 남음:** 실제 PortOne TEST 재결제 전체 여정, 결제창 이탈/재진입·안전한 종료 경계(브라우저 닫기·시각 경과만으로 실패 확정하지 않음), 초기 진단 금융 보류 건의 다음 행동.
- **G3:** 같은 공급자 거래/비용·지급 자료로 Settlement까지 연결, 미확정 비용 모델 확정.
- LIVE 결제·실제 PG 지급 자료·은행 입금·판매자 송금.
- 다중 PG·다중 워커 최적화·처리량 향상·분산 임대/ShedLock.
- 카드 정보 저장·결제 수단 다양화·쿠폰/다중 통화/무료 상품 지급·복수 상품/수량.
