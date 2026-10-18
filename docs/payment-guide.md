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
| PAYMENT_PG_PROVIDER | simulated |
| PAYMENT_PROCESSING_ENABLED | true. false이면 접수·조회만 수행 |
| PAYMENT_POLL_MS | 1000, 허용 100~60000 |
| PAYMENT_BATCH_SIZE | 20, 허용 1~200 |
| PAYMENT_RETRY_MS | 5000, 허용 100~300000 |
| PAYMENT_SIMULATED_RESULT | APPROVED 또는 DECLINED |

공급자 대역은 simulated_payment_charge에 결정을 독립 커밋한다. 설정 변경은 새 키의 결정에만 적용한다.
업무 테이블과 같은 MySQL을 쓰므로 외부 PG의 독립 DB·네트워크 장애까지 재현하지 않는다.
실제 PG 교체는 같은 키의 멱등 결제·원래 결과 재확인과 실제 공급자 인증 계약이 필요하다.

## OCI 검증

로컬은 편집·동기화만 수행한다.

```sh
./scripts/remote.sh gradle :apps:payment:test :apps:payment:integrationTest :apps:gateway:test --no-daemon
./scripts/remote.sh sync
```

독립 흐름은 OCI의 원격 저장소 디렉터리에서 실행한다.

```sh
python3 scripts/verify-payment.py
```

스크립트는 Auth·Gateway·Studio·Review·Catalog·Store·Order·Payment와 임시 인프라를 실행한다.
기본 앱 포트 오프셋은 30000이며 PAYMENT_TEST_PORT_OFFSET으로 변경한다. 기존 데이터 볼륨을 사용하지 않는다.
완료 후 해당 실행의 프로세스·컨테이너·볼륨만 정리하고 로그·payment.json·단계별 JUnit XML을 출력한 임시 경로에 남긴다.

11단계로 접수 후 재시작, 승인·거절, PG 원장 커밋 후 응답 유실, 결과·Outbox 롤백 후 재시작,
Order·Catalog 중단 중 결제, Kafka 중단 중 조회·결과 보존, 원래 결과 발행 복구와 재전달을 검증한다.
장애 주입 클래스는 integrationTest에만 있으며 bootJar에 포함하지 않는다. 공개 장애 주입 API는 없다.

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
