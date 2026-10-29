# Payment 실행·결제 결과·복구 확인

[문서 목록](README.md) · [MVP 범위·검증 기록](payment-mvp-plan.md) · [주문 실행](order-guide.md) · [모델](domain/models/order-payment.md#payment)

Payment는 **Order가 확정한 금액의 결제 결과**를 보존한다. Catalog·Store 가격을 다시 조회하지 않는다.
이 문서의 기존 실행 예제는 모의 PG이며 실제 자금 이동은 없다. 별도로 PortOne TEST 결제창·웹훅과 환불 실행/조회/재송신을 구현했다. 테스트 채널의 현재 계약·실행 환경·제약은 [환불 인계](refund-session-handoff.md)와 [검증 기록](pg-test-integration-plan.md)을 따른다. 결과는 Kafka로 Order에 전달하고 주문 상태는 Order가 변경한다.

## 코드와 처리 경계

| 유스케이스 | 책임 |
|---|---|
| ReceiveOrderPayment | 원래 요청 전체 비교, PENDING·Inbox·추적 메타데이터 원자적 접수 |
| ResolvePayment | 저장된 부모 아래 PENDING 재개, PG 호출, 최종 상태·결과 Outbox 저장, PortOne 실패 시도 이력의 별도 수집/재개 |
| SearchPayments | JWT sub 구매자의 목록·페이지 조회 |
| ReadPayment | 구매자·주문번호로 상세 조회, 타인·없는 결제 404 |
| ReadPaymentFailureHistory | 본인 결제의 검증된 실패 시도 페이지·수집 상태를 DB에서 조회 |

OrderEventListener → OrderPaymentMapper.from(...) → PaymentOrderRequest → ReceiveOrderPayment 순으로 입력한다.
외부 이벤트의 봉투·본문·키 변환은 api.listener, Inbox·잠금·결과·Outbox 조율은 업무 서비스에 있다.

접수와 PG 호출, 결과 저장은 각각 구분한다. 리스너 반환은 요청 저장 완료를 의미한다.
PendingPaymentScheduler가 매초 최대 20건을 재개한다. 공급자 호출은 Payment 트랜잭션·행 잠금 밖에서 수행한다.
접수 시 발급한 paymentId를 공급자 멱등 키로 고정한다. 공급자의 거래 ID와 결과 이벤트 ID는 별도 값이다.

| 관측한 결과 | 상태·재개 |
|---|---|
| 일치하는 승인 | PAID + OrderPaymentCompleted |
| 모의 PG의 일치하는 최종 거절 | FAILED + OrderPaymentFailed |
| PortOne의 개별 FAILED 시도 | 별도 실패 이력, Payment/Order는 PENDING 유지, 동일 checkout으로 재결제 |
| 응답 유실·일시 오류·잘못된 결과 | PENDING 유지, 기본 5초 후 같은 키로 재확인 |
| PG 결정 후 결과 저장 실패 | PG 원장은 보존, PENDING에서 같은 결정으로 결과 저장 재시도 |

최종 결과와 Outbox는 같은 트랜잭션이다. 같은 요청·결과 재전달은 최초 상태·ID·시각을 바꾸지 않는다.
PG 수수료 공제액을 청구액으로 대신 쓰지 않는다. 모의 PG의 최종 FAILED 뒤 재구매는 새 Order requestId로 시작한다. PortOne의 한 시도 실패는 최종 FAILED가 아니며 같은 orderNo·paymentId·확정 금액의 결제창을 다시 사용한다. 같은 상품의 PENDING·PAID 추가 주문은 Order가 차단하며 Payment가 License 보유 여부를 조회하지 않는다.

PortOne의 `/payments/{paymentId}/transactions`를 서버가 조회해 원래 스토어·TEST 채널·주문·금액과 실패 시각을 대조한다. V28의 `failed_payment_attempt`는 공급자 transactionId별 불변 근거를 보존하며 고객에게는 별도 공개 attemptId를 제공한다. 공급자 원문 사유는 저장/노출하지 않는다. PAID와 결과 Outbox를 먼저 커밋하고 이력 수집은 별도 트랜잭션으로 처리한다. 이력 장애는 `next_failure_lookup_at`에 재예약하며, PAID 뒤의 재개는 이력 GET만 수행한다. 과거 자료가 없으면 실패나 수집 시각을 추정하지 않는다.

추적 부모는 V13의 `payment_request_trace`에 별도 저장한다. 유효한 W3C v00 헤더만 보존하고 누락·이상·중복 헤더는 부모 없음으로 처리한다.
최초 접수의 컨텍스트를 중복 입력으로 덮어쓰지 않으며 기존 결제의 미보존 부모를 추정하지 않는다. 재시도마다 원래 부모의 새 자식 span을 만들고 다른 주문·스케줄러 scope와 분리한다.
샘플링·수집기 상태는 결제 상태 전이의 조건이 아니다. [Tempo 조회·검증 실행](getting-started.md#tempo에서-요청-추적-조회) · [정책·검증 기록](tempo-tracing-plan.md)을 따른다.

## API와 설정

고객 GET API는 MEMBER access token이 필요하다. 인증은 [Auth·Gateway 안내](auth-bff.md)를 따른다.

```sh
curl -H "Authorization: Bearer $TOKEN" "$GATEWAY/api/v1/payments?page=0&size=20"
curl -H "Authorization: Bearer $TOKEN" "$GATEWAY/api/v1/payments/$ORDER_NO"
curl -H "Authorization: Bearer $TOKEN" "$GATEWAY/api/v1/payments/$ORDER_NO/checkout"
curl -H "Authorization: Bearer $TOKEN" "$GATEWAY/api/v1/payments/$ORDER_NO/attempt-failures?page=0&size=20"
```

목록은 최신 생성순이며 같은 시각에는 내부 id 역순이다. page는 0 이상, size는 1~100이다.
내부 요청·결과 이벤트 ID, PG 거래 ID와 재시도 오류는 응답에 노출하지 않는다. 이 절의 모의 PG 예제는 provider=SIMULATED이며 현재 PortOne TEST 경로도 별도로 구현돼 있다.
Order 생성 직후에는 Kafka 접수 전이므로 Payment 상세가 잠시 404일 수 있다.
실패 이력 GET은 no-store이며 요청/실패 시각·내부 ID 역순의 페이지를 반환한다. `checkedAt`은 마지막 정상 수집 시각, `collectionPending`은 재수집 대기 여부다. 빈 이력이 PG의 최종 실패나 성공을 뜻하지 않는다. 고객 조회는 PG를 호출하거나 작업을 깨우지 않는다. 결제창 이탈·404·타임아웃으로 주문을 종료하지 않으며 미결제 종료/만료는 별도 G1 후속 범위다. 실제 PortOne 재결제 여정과 HTTP 계약 픽스처 검증을 구분한다.
Payment는 고객의 환불 접수를 받지 않고 Order의 실행 요청을 처리한다. 현재는 PortOne 결제창 정보 GET·서명 웹훅, ADMIN 송신 중단 조회/재개와 장기 미확정 조회/조사 기록 API가 있다. 계약·검증/런타임 범위는 [현재 인계](refund-session-handoff.md), 결제 생명주기·정산 연결의 공백과 다음 순서는 [전체 기능 일반화 계획](platform-generalization-plan.md)을 따른다. 고객이 결제 승인 결과를 주입하는 API는 두지 않는다.

| 환경변수 | 기본값·범위 |
|---|---|
| PAYMENT_PG_PROVIDER | SIMULATED |
| PAYMENT_PROCESSING_ENABLED | true. false이면 접수·조회만 수행 |
| PAYMENT_POLL_MS | 1000, 허용 100~60000 |
| PAYMENT_BATCH_SIZE | 20, 허용 1~200 |
| PAYMENT_RETRY_MS | 5000, 허용 100~300000 |
| MOCK_PG_URI | http://localhost:8092, 외부 모의 PG 주소 |
| MOCK_PG_PAYMENT_KEY | 필수. Payment 전용 승인·조회 키 |

외부 모의 PG는 `simulators/mock-pg`의 별도 프로세스·MySQL에서 결제 결정과 비용을 보존한다.
Payment의 `PgGateway`는 `SimulatedPgHttpGateway`가 구현하며 HTTP 조회에서 404인 경우에만 같은 paymentId로 승인 요청한다.
통신 실패·잘못된 응답은 PENDING을 유지한다. 로컬 승인 폴백은 없다.
PG 비용은 `purchase_payment`의 불변 스냅샷이며 구매자 청구액·기존 결제 이벤트와 구분한다.
Settlement는 PG 승인/비용·지급 명세를 자기 포트로 직접 조회하며 Payment에 정산 중계 API를 두지 않는다.

## 로컬 OrbStack 검증

```sh
export DOCKER_HOST="$(docker context inspect orbstack --format '{{.Endpoints.docker.Host}}')"
./gradlew :apps:payment:test :apps:payment:integrationTest --no-daemon
python3 tools/verification/verify-payment.py
```

스크립트는 여덟 업무 앱과 외부 모의 PG, 전용 PG MySQL을 격리해 실행한다.
키는 실행마다 생성하고 Payment에는 Payment 키만 제공한다. 공급자 정산 제어 키는 업무 앱에 전달하지 않는다.
기본 앱 포트 오프셋은 30000이며 PAYMENT_TEST_PORT_OFFSET으로 변경한다. 기존 공유 데이터·볼륨을 사용하지 않는다.
11단계로 접수 후 재시작, 승인·거절, PG 처리 뒤 응답 유실, 결과·Outbox 롤백,
Order·Catalog·Kafka 중단과 원래 결과 발행·재전달을 검증한다. 장애 주입 클래스는 bootJar에 포함하지 않는다.

독립 PG의 기동·키·정산 회차와 전체 열한 업무 앱 검증은 [Settlement 실행 안내](settlement-guide.md)를 따른다.
포트원 일반 인증 결제에는 결제창·서버 검증 등 해당 흐름을 위한 변경이 필요하며 현재 charge 구현 교체만으로 연동이 완성되지 않는다.

## 기존 DB와 Kafka 전환

이 독립 검증은 기존 공유 환경의 전환을 의미하지 않는다. 구 Flyway V1~V10과 데이터는 그대로 두고 V11로 두 새 테이블을 추가한다.
구 payment·simulated_pg_transaction·payment_audit_log를 자동 변환하거나 과거 주문을 자동 결제하지 않는다.

공유 환경에 적용할 때 구 Payment 쓰기·릴레이를 모두 중지하고 [전환 SQL](../tools/migrations/payment/payment-transition.sql)을 실행한다.
구 PENDING·DEAD Outbox의 원본을 payment_legacy_outbox_archive에 보존하고 구 PENDING을 DEAD로 격리한다.
새 OrderPaymentCompleted/OrderPaymentFailed는 격리하지 않는다. SQL은 반복 실행할 수 있고 기본 기동에서는 실행하지 않는다.

새 소비 그룹 payment-order-request의 시작 오프셋도 별도로 정해야 한다.
과거 OrderPlaced·Order 미발행 Outbox·기존 결제 완료 기록을 대조해 처리 대상과 제외 대상을 확정한 뒤 소비와 자동 처리를 켠다.
새 그룹을 earliest로 바로 실행하거나 구 Inbox를 지워 맞추지 않는다.

결과 Outbox가 DEAD이면 장애 원인을 해소하고 원래 행의 eventId·본문을 유지해 공통 requeue 절차로 회수한다.
새 결제나 결과 이벤트를 만들어 대신 보내지 않는다. 충돌 DLT는 원래 입력·결제·PG 결정을 대조한 뒤 처리한다.

### 내장 모의 PG의 외부 전환

V12는 purchase_payment에 nullable 비용 컬럼만 추가한다. 기존 미산정 비용을 0원으로 채우지 않는다.
기존 V11의 simulated_payment_charge는 보존하지만 신규 결정을 기록하지 않는다.
공유 스택의 처리기를 중지한 뒤 [외부 PG 이관 절차](settlement-guide.md#기존-내장-pg-이관)로 원래 결정을 이관·대조한 후 재개한다.
이관 전 빈 PG로 기존 PENDING을 재개하면 같은 결제번호의 기존 결정을 잃으므로 공유 스택은 자동 전환하지 않는다.
