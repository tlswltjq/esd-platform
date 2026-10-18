# Payment 실행·결제 결과·복구 확인

[문서 목록](README.md) · [MVP 범위·검증 기록](payment-mvp-plan.md) · [주문 실행](order-guide.md) · [모델](domain/models/order-payment.md#payment)

Payment는 **Order가 확정한 금액의 결제 결과**를 보존한다. Catalog·Store 가격을 다시 조회하지 않는다.
이번 실행은 모의 PG이며 실제 자금 이동은 없다. 결과는 Kafka로 Order에 전달하고 주문 상태는 Order가 변경한다.

## 코드와 처리 경계

| 유스케이스 | 책임 |
|---|---|
| ReceiveOrderPayment | 원래 요청 전체 비교, PENDING·Inbox 원자적 접수 |
| ResolvePayment | 처리 시각이 된 PENDING 재개, PG 호출, 최종 상태·결과 Outbox 저장 |
| SearchPayments | JWT sub 구매자의 목록·페이지 조회 |
| ReadPayment | 구매자·주문번호로 상세 조회, 타인·없는 결제 404 |

OrderEventListener → OrderPaymentMapper.from(...) → PaymentOrderRequest → ReceiveOrderPayment 순으로 입력한다.
외부 이벤트의 봉투·본문·키 변환은 api.listener, Inbox·잠금·결과·Outbox 조율은 업무 서비스에 있다.

접수와 PG 호출, 결과 저장은 각각 구분한다. 리스너 반환은 요청 저장 완료를 의미한다.
PendingPaymentScheduler가 매초 최대 20건을 재개한다. 공급자 호출은 Payment 트랜잭션·행 잠금 밖에서 수행한다.
접수 시 발급한 paymentId를 공급자 멱등 키로 고정한다. 공급자의 거래 ID와 결과 이벤트 ID는 별도 값이다.

| 관측한 결과 | 상태·재개 |
|---|---|
| 일치하는 승인 | PAID + OrderPaymentCompleted |
| 일치하는 최종 거절 | FAILED + OrderPaymentFailed |
| 응답 유실·일시 오류·잘못된 결과 | PENDING 유지, 기본 5초 후 같은 키로 재확인 |
| PG 결정 후 결과 저장 실패 | PG 원장은 보존, PENDING에서 같은 결정으로 결과 저장 재시도 |

최종 결과와 Outbox는 같은 트랜잭션이다. 같은 요청·결과 재전달은 최초 상태·ID·시각을 바꾸지 않는다.
PG 수수료 공제액을 청구액으로 대신 쓰지 않는다. 거절 뒤 재구매는 새 Order requestId로 시작한다. 같은 상품의 PENDING·PAID 추가 주문은 Order가 차단하며 Payment가 License 보유 여부를 조회하지 않는다.

## API와 설정

두 GET API 모두 MEMBER access token이 필요하다. 인증은 [Auth·Gateway 안내](auth-bff.md)를 따른다.

```sh
curl -H "Authorization: Bearer $TOKEN" "$GATEWAY/api/v1/payments?page=0&size=20"
curl -H "Authorization: Bearer $TOKEN" "$GATEWAY/api/v1/payments/$ORDER_NO"
```

목록은 최신 생성순이며 같은 시각에는 내부 id 역순이다. page는 0 이상, size는 1~100이다.
내부 요청·결과 이벤트 ID, PG 거래 ID와 재시도 오류는 응답에 노출하지 않는다. provider는 SIMULATED다.
Order 생성 직후에는 Kafka 접수 전이므로 Payment 상세가 잠시 404일 수 있다.
prepare·결제창·콜백·환불·승인 주입·운영 HTTP API는 없다.

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
python3 scripts/verify-payment.py
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

공유 환경에 적용할 때 구 Payment 쓰기·릴레이를 모두 중지하고 [전환 SQL](../scripts/payment-transition.sql)을 실행한다.
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
