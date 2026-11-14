# 결함 대장

> 이 문서의 결함은 대부분 **재작성(2026-10) 전 구현**에서 재현했다. 당시 위치·재현 테스트 중 상당수는 현재 코드에 없다(아래 "현재 저장소에 남은 재현 테스트" 참고). 구 데모·성능·장애 실험 명령은 [정리 전 소스](https://github.com/tlswltjq/esd-platform/blob/e32750ffc95dee1fe5b59b2e248859f84aa77154/scripts/)에, 측정 서사는 [실험 기록](experiments.md)에, 항목별 원문 전체는 [정리 전 결함 대장](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/defects.md)에 있다. 현재 검증은 [tools/verification](../tools/verification/README.md)를 사용한다.

테스트로 재현에 성공한 결함만 적는다. **추측은 넣지 않는다** —
모든 항목에 실패하는 테스트가 하나씩 붙어 있고, 그 테스트가 통과하면 항목을 닫는다.

```bash
./gradlew build         # 결함 테스트 제외하고 전건 통과
./gradlew defectTest    # 남아 있는 결함만 재현. 현재 1건 ([D-026](#d-026))
```

## 빌드 연동

- 고치기 전의 재현 테스트는 `@Tag("known-defect")`로 먼저 커밋한다. 루트 `build.gradle`이 `test`·`integrationTest` **두 태스크에 각각** 이 태그를 제외한다(`withType`으로 걸면 `defectTest`까지 걸러져 0건으로 초록이 된다). `integrationTest` 제외는 통합 소스셋에 들어온 [D-026](#d-026) 때문에 추가됐다.
- `defectTest`는 단위·통합 소스셋을 모두 훑어 `known-defect` 테스트만 돌리고, 루트 태스크가 전 모듈 결과를 합산한다. 0건이면 "통과가 아니라 미실행", 실패가 있으면 "아직 살아 있다", 전부 통과하면 "태그를 떼고 회귀 방어선으로 승격"을 출력한다.
- 수정이 끝난 항목은 재현 테스트에서 태그를 떼어 기본 빌드가 지키는 회귀 방어선으로 옮긴다. 테스트 이름은 `[D-0NN]`으로 이 문서와 연결한다. 작성 규칙과 태그 운용은 [testing.md](testing.md) 참고.
- **현재 남은 결함은 [D-026](#d-026) 1건**이다(`ConsumerLagMetricTest#appMetricGoesSilent`는 의도적으로 영구 실패).

**현재 저장소에 남은 재현 테스트**(2026-11-06 파일 검색 기준): `OutboxEventTest`·`OutboxRelayTest`·`OutboxPendingQueryTest`(D-003·D-013·D-014), `EventEnvelopeTest`·`ProcessedEventGuardTest`(D-004), `BusinessExceptionStatusTest`(D-015 참조), `ArchRuleEnforcementTest`(D-021), `UnknownPropertyExceptionHandlerTest`(D-024·D-025), `ConsumerLagMetricTest`(D-026), `PaymentApprovalIntegrationTest`(D-044·D-051, 회귀 방어선), `SettlementIntegrationTest`·`SettlementMigrationIntegrationTest`(D-045·D-046, 회귀 방어선), `StoreIntegrationTest`(D-047, 회귀 방어선), `RefundWorkerTracingIntegrationTest`(D-050, 회귀 방어선), `LicenseRestorationIntegrationTest`·`LicenseApprovalCorrectionIntegrationTest`·`LicenseMigrationIntegrationTest`(D-052, 회귀 방어선), `DeploymentShutdownContractTest`·`GatewayGracefulShutdownTest`(D-034·D-035), `GatewayActuatorExposureTest`(닫힌 항목). 나머지 항목의 재현 테스트는 재작성 때 해당 코드와 함께 제거됐다.

---

## 요약

발견 경로는 본문에 근거가 있는 경우만 적고, 없으면 `—`로 둔다.

| ID | 제목 | 영향 | 상태 | 발견 경로 |
|---|---|---|---|---|
| [D-001](#d-001) | 마감 후 도착한 정산 원장이 지급 대상에서 누락 | 금전 손실 | **수정됨** | — |
| [D-002](#d-002) | 일시 장애 한 번에 Saga 보상 환불 발동 | 금전 손실 | **수정됨** | — |
| [D-003](#d-003) | 브로커 장애가 길어지면 Outbox 이벤트 영구 유실 | 데이터 유실 | **수정됨** | — |
| [D-004](#d-004) | 계약 헤더 없는 메시지의 실패가 트랜잭션 한복판으로 미뤄짐 | 가용성 | **수정됨** | — |
| [D-006](#d-006) | 롤백된 환불 트랜잭션이 PG 환불은 실행 | 금전 불일치 | **수정됨** | — |
| [D-007](#d-007) | 상태 불일치 보상 요청이 무한 재시도 | 가용성 | **수정됨** | — |
| [D-008](#d-008) | 재사용된 PG 멱등키가 다른 주문의 승인을 삼킴 | 금전 손실 | **수정됨** | — |
| [D-009](#d-009) | 서버 측 금액 재계산에 수량 검증 없음 | 금액 조작 | **수정됨** | — |
| [D-010](#d-010) | 재처리해도 소유 이벤트가 재발행되지 않음 | 복구 불가 | **수정됨** | — |
| [D-011](#d-011) | 변화 없는 회수가 이벤트를 재발행 | 잡음 | **수정됨** | — |
| [D-012](#d-012) | 지각 회수 이벤트가 새 구매 권한을 거둠 | 사용자 영향 | **수정됨** | — |
| [D-013](#d-013) | 발행 실패한 이벤트를 같은 애그리거트의 뒤 이벤트가 추월 | 순서 역전 | **수정됨** | D-003 수정 중 |
| [D-014](#d-014) | 백오프로 빠진 앞 이벤트를 다음 회차에서 뒤 이벤트가 추월 | 순서 역전 | **수정됨** | D-013 수정의 사각지대(대역에 시간 필터 추가) |
| [D-015](#d-015) | 형식이 깨진 요청이 400 이 아니라 500 으로 응답 | 오분류·알람 잡음 | **수정됨** | 컨트롤러 테스트 추가 |
| [D-016](#d-016) | 진행 중인 심의에 재신청이 오면 컨슈머가 멈춤 | 가용성 | **수정됨** | 서비스 계층 테스트 추가 |
| [D-017](#d-017) | 지각 심의 결과가 승인된 프로젝트를 강등 | 사용자 영향 | **수정됨** | 도메인 테스트 추가 |
| [D-018](#d-018) | 결제 없는 주문의 보상 요청이 무한 재배달 | 가용성 | **수정됨** | 서비스 계층 테스트 추가 |
| [D-019](#d-019) | order 의 수량 검증이 어댑터에만 존재 | 금액 조작 | **수정됨** | 서비스 계층 테스트 추가 |
| [D-020](#d-020) | 범위를 벗어난 페이지 파라미터가 400 이 아니라 500 | 오분류·알람 잡음 | **수정됨** | testing.md 6절 위험 공백 보강 |
| [D-021](#d-021) | 스텁 격리 규칙이 실제 스텁을 한 번도 검사하지 않음 | 규칙의 공허 통과 | **수정됨** | testing.md 6절 위험 공백 보강 |
| [D-022](#d-022) | 롤백된 마감 트랜잭션이 세금계산서는 발행 | 금전 불일치 | **수정됨** | — |
| [D-023](#d-023) | 배포 게이트가 컨테이너 0개에도 통과 | 게이트의 공허 통과 | **수정됨** | — |
| [D-024](#d-024) | 알 수 없는 정렬 속성이 400 이 아니라 500 | 오분류·알람 잡음 | **수정됨** | e2e 목록 판정에 `sort` 사용 |
| [D-025](#d-025) | 정렬이 결정적이지 않아 페이지 경계에서 상품 중복·유실 | 데이터 정합성 | **수정됨** | D-024 수정 코드 리뷰 |
| [D-026](#d-026) | 컨슈머가 멈추면 랙 지표도 같이 멈춰 적체가 관측되지 않음 | 관측 불가 | **우회함** | 성능 측정 ([E5](experiments.md#consumer)) |
| [D-027](#d-027) | 인프라 장애를 지급 불가로 판정해 정상 결제를 환불 | 금전 손실 | **수정됨** | 장애 주입 ([E8](experiments.md#chaos-refund)) |
| [D-028](#d-028) | 이미 지급된 주문에 보상 환불이 발동 | 금전 손실 | **수정됨** | 장애 주입 회차 로그 ([E8](experiments.md#chaos-refund)) |
| [D-029](#d-029) | 미결제 주문에 만료가 없어 옛 가격으로 영구히 결제 | 금액 조작 | **수정됨** | 장애 주입 시나리오 2b ([E8](experiments.md#chaos)) |
| [D-030](#d-030) | 이벤트 재처리로 유실을 복구할 수 없다 — Inbox 가드가 먼저 막음 | 복구 불가 | **절차로 닫음** | 장애 주입 시나리오 1b ([E8](experiments.md#chaos-replay)) |
| [D-031](#d-031) | 랙 알람이 조건이 참인 내내 한 번도 울리지 않음 | 관측 불가 | **수정됨** | 알람 발화 측정 ([E8](experiments.md#chaos-alerts)) |
| [D-032](#d-032) | 익스포터가 스크레이프마다 지표를 정확히 한 줄씩 흘림 | 관측 불가 | **수정됨** | D-031 원인 추적 |
| [D-033](#d-033) | 인수 테스트가 데이터가 적을 때만 통과 — 21회차부터 영구 실패 | 게이트 마비 | **수정됨** | `main` e2e 실패 |
| [D-034](#d-034) | 게이트웨이만 graceful shutdown 이 없어 배포마다 요청이 끊김 | 사용자 영향 | **수정됨** | R-01 시나리오 추가 ([E9](experiments.md#resilience)) |
| [D-035](#d-035) | 컨테이너 종료 유예가 짧아 graceful shutdown 이 SIGKILL 로 잘림 | 사용자 영향 | **수정됨** | R-01 시나리오 추가 ([E9](experiments.md#resilience)) |
| [D-036](#d-036) | 통합 테스트 컨텍스트가 늘면 관계없는 기존 클래스가 빨개짐 | 게이트 마비 | **수정됨** | R-04 실행 중 ([E9](experiments.md#resilience)) |
| [D-037](#d-037) | 장애를 주입했는데 컨슈머 그룹 멤버가 둘이라 파티션만 옆으로 옮겨 감 | 게이트 마비 | **수정됨** | CI의 R-03 실패 |
| [D-038](#d-038) | 검증 도구 9개가 실행 JAR 대신 `-test-fixtures.jar`를 고를 수 있음 | 검증 무효 | **수정됨** | PR #116 `verify-license.py` 실패 |
| [D-039](#d-039) | 앞 실행이 남긴 TIME_WAIT만으로 검증 도구가 "포트 사용 중"으로 멈춤 | 검증 마비 | **수정됨** | 결정 105 적용 중 연속 실행 |
| [D-040](#d-040) | Store 최초 삽입 경합에서 같은 상품을 코드 충돌로 오인 | HTTP 회차 실패·Kafka 지연 | **수정됨** | main CI 42249703875·교차 리뷰 R1 |
| [D-041](#d-041) | Review 다운로드 테스트가 서명 초 경계에서 조기 만료로 실패 | 초 경계 CI 불안정 | **수정됨** | CI run 42373571409 |
| [D-042](#d-042) | Catalog 최초 공개 경합에서 같은 Project를 상품 코드 충돌로 오인 | 공개·접수 회차 실패와 계약 위반 오분류 | **수정됨** | CI run 42397961475 |
| [D-043](#d-043) | 공유 테스트 컨테이너가 기본 시작 대기(60초)만 기다려 부하 시 모듈 통합 테스트 전체가 실패 | 부하 시 CI 불안정 | **수정됨** | PR #137 CI run 42410179110 |
| [D-044](#d-044) | 접수 전 PAY_PENDING 승인이 주문 만료로 PG 재조회 없이 닫혀 늦은 PAID를 놓침 | 금전 손실(주문 없는 청구가 보류·경보 없이 남음) | **수정됨** | G1 조합 조사 코드 판독 뒤 재현 테스트 |
| [D-045](#d-045) | 뒤 월이 CLOSED인데 늦은 앞 월 매출·환불이 앞 월 OPEN 행을 만들어 판매자 과지급 | 금전 손실(과지급)·판매자 마감 정지 | **수정됨** | G3 늦은 앞 월 조사 코드 판독 뒤 재현 테스트 |
| [D-046](#d-046) | 뒤 월 마감 뒤 앞 월 마감이 같은 출처 이월을 다시 받음 | 금전 손실(과소 지급) | **수정됨** | G3 늦은 앞 월 조사 코드 판독 뒤 재현 테스트 |
| [D-047](#d-047) | Store 최초 삽입 flush의 MySQL 교착(1213)이 재시도를 지나쳐 회차가 실패 | HTTP 대조 회차 실패·Kafka 지연 | **수정됨** | PR #127 교차 리뷰 R3 → Store `tasks.md` |
| [D-050](#d-050) | 환불 조회·재송신 작업자가 저장된 환불 추적 부모를 복원하지 않아 확정 뒤 흐름이 새 trace로 갈라짐 | 진단 단절(업무 영향 없음) | **수정됨** | `verify-refund-tracing.py` 실제 스택 2회 |
| [D-051](#d-051) | 결제 PAID 확정 뒤에도 접수된 보류 승인이 다음 보류 재조회까지 남아 고객에게 옛 안내가 나감 | 고객 안내 모순(PAID 건에 재구매 안내 가능, 돈 이동 없음) | **수정됨** | 결정 143 1부 payment 찾은 불일치 3 |
| [D-052](#d-052) | License 복구·승인 정정이 모듈 간 시계 차이로 정상 요청을 거부해 보유권이 정지된 채 남음 | 보유권 정지 유지(시계 차이가 있을 때) | **수정됨** | License `design.md` 상태×이벤트 표의 찾은 불일치 1(결정 143 1부) |
| [D-053](#d-053) | Gateway가 하류 서비스가 막 닫는 유휴 연결(약 60초)을 다시 써 요청이 응답 전에 끊기고 500이 남 | 간헐 500(조회는 재시도로 회복, `POST`는 처리 여부 불명) | **원인 확인 · 미수정** | main 야간 CI run 42916206138 |
| [D-054](#d-054) | Kafka 시작 스크립트가 복사 중 실행돼 `Text file busy`(126)로 테스트 컨텍스트 시작 실패 | CI 불안정(업무 코드 영향 없음) | **수정됨** | PR #196 CI run 43001134598 |

반복된 부류가 둘 있다. **고쳐 본 자리를 다시 밟는다** — D-027~D-030은 이미 한 번씩 등장한 자리(D-002·D-003·D-010·D-013)에서 나왔고, D-018·D-019는 D-007·D-009가 같은 메서드·같은 이름의 다른 값 객체에 남아 있던 경우다. D-042는 D-040과 같은 최초 삽입 경합이 Catalog에 남아 있던 경우다. **장치가 있는데 그 자리를 지키지 않는다(공허 통과)** — D-021(규칙이 대상 0건 검사)·D-023(게이트가 컨테이너 0개에도 통과)·D-030(회귀 테스트가 없는 경로를 지킴)·D-031(알람이 조건이 참인 내내 침묵)·D-035(설정은 있는데 SIGKILL)이고, D-033은 같은 원인이 반대로 영원히 실패한 경우다.

---

<a id="d-001"></a>
## D-001 마감 후 도착한 정산 원장이 지급 대상에서 누락

**상태** 수정됨 · **영향** 금전 손실 · 자동 복구 불가 · **위치** `apps/settlement/.../core/service/SellerSettlementService.java`
**재현** `SettlementCloseTest#lateRecordShouldBeSettledOnNextClose`, `#everyClosedRecordIsReflectedInSettlement` (`expected: 105000L but was: 70000L`)

- **증상** 마감 뒤 도착한 5월 매출 50,000이 재마감에서 반영되지 않고 closed 도장만 찍혀, 재실행으로도 복구되지 않는다(차액 net 35,000 영구 소실).
- **원인** 확정본이 있는 판매자를 집계에서 제외하면서 `targets.forEach(SettlementRecord::close)`는 제외된 판매자의 원장까지 close했다.
- **수정** 판매자 제외 필터를 없애고 기존 확정본에 더한다(`SellerSettlement#accumulate`). 발행된 확정본 금액이 바뀌면 수정세금계산서 대상으로 `log.warn`. 불변식 "close된 원장은 반드시 어떤 확정본에 들어 있다"를 테스트로 고정.

---

<a id="d-002"></a>
## D-002 일시 장애 한 번에 Saga 보상 환불 발동

**상태** 수정됨 · **영향** 금전 손실 · 사용자 영향 · **위치** `apps/license/.../api/listener/PaymentEventListener.java`
**재현** `PaymentEventListenerTest#propagatesTransientFailureForRetry`, `#doesNotCompensateOnTransientFailure`, `KafkaErrorHandlerConfigTest#recoversByRequestingCompensation`

- **증상** DB 커넥션이 1초 끊기는 정도의 일시 장애에 정상 결제가 환불됐다.
- **원인** 리스너가 예외를 `catch`해 곧바로 보상을 커밋하고 정상 리턴했다. 스프링 카프카 재시도는 예외가 리스너 밖으로 나올 때만 작동하므로 재시도가 0회였다. 2차로 커스텀 `ErrorHandler`가 없어 기본값 `FixedBackOff(0L, 9L)`(간격 0ms 총 10회)이었다.
- **수정** `try/catch`를 걷어내고 보상 트리거를 `KafkaErrorHandlerConfig`의 recoverer(재시도 소진 후에만 호출)로 옮겼다. `ExponentialBackOffWithMaxRetries(3)`(1초 → 2초 → 4초, 총 7초)를 명시하고 총 대기가 `max.poll.interval.ms`(5분) 안인지 테스트로 고정. recoverer 안의 예외는 로그로 끝낸다.
- 후속: "무엇을 보고 보상하는가"는 [D-027](#d-027)에서 고쳤다.

---

<a id="d-003"></a>
## D-003 브로커 장애가 길어지면 Outbox 이벤트 영구 유실

**상태** 수정됨 · **영향** 데이터 유실 · 자동 복구 불가 · **위치** `common/messaging/.../outbox/OutboxEvent.java`, `OutboxEventRepository`
**재현** `OutboxEventTest#toleranceCoversRealisticOutage`, `#deadCanBeRequeued`, `OutboxBackOffQueryTest#backedOffEventIsSkippedUntilItsTime` (현재 `OutboxPendingQueryTest`의 `[D-003]`)

- **증상** 재시도가 폴링 주기(1초)로 고정되고 `retryCount >= maxRetry`면 되돌릴 수 없는 `DEAD`가 되어, 감내 시간이 기본 약 10초였다. 브로커 롤링 재시작 중 대기 이벤트가 한꺼번에 DEAD가 된다.
- **수정** 재시도 예산을 시간으로 잡았다 — `next_attempt_at`을 1초 → 2초 → 4초 …(상한 5분)로 미루고 조회에 `next_attempt_at IS NULL OR next_attempt_at <= NOW(6)`을 붙여 감내 시간이 **약 8분 30초**가 됐다. `OutboxEvent#requeue()`로 DEAD를 되돌린다. 시간 조건은 실제 MySQL 테스트가 맡는다. 마이그레이션은 outbox를 쓰는 7개 서비스에 동일(`V*__outbox_retry_backoff.sql`).
- **남은 부분** `relay()`가 트랜잭션 안에서 배치를 순차 대기하는 구조는 처리량 문제로 남겼다([E1](experiments.md#relay-laptop)).

---

<a id="d-004"></a>
## D-004 계약 헤더 없는 메시지의 실패가 트랜잭션 한복판으로 미뤄짐

**상태** 수정됨 · **영향** 가용성 (poison message) · **위치** `common/event/.../kafka/EventEnvelope.java`, `common/messaging/.../inbox/ProcessedEventGuard.java`
**재현** `EventEnvelopeTest#rejectsRecordWithoutEventId`, `#rejectsRecordWithoutEventType`, `ProcessedEventGuardTest#rejectsNullEventId`

- **증상** `eventId` 헤더가 없으면 업무 로직이 실행된 뒤 `event_id NOT NULL`에서 롤백되고 재전송된다. `eventType`이 없으면 어떤 분기도 타지 않고 흔적 없이 정상 리턴한다.
- **원인** `EventEnvelope.from()`이 null을 그대로 반환하고, 가드도 null을 "처음 본 이벤트"로 허용했다. 컨슈머 에러 핸들러 정책이 어느 서비스에도 없었다.
- **수정** 봉투 생성 시점에 계약 위반을 `IllegalStateException`(topic/partition/offset/key 포함)으로 거부하고 가드도 빈 `eventId`를 거부한다. 지수 백오프(1→2→4초) 기본 `DefaultErrorHandler`를 자동 구성했다. 이후 [결정 19](decisions.md#19-포기한-메시지를-버리지-않고-되살릴-문을-단다)로 정책이 `common:kafka`로 옮겨져 9개 서비스 전부에 적용되고, 소진 시 `<원본토픽>.DLT`로 보낸다.

---

<a id="d-006"></a>
## D-006 롤백된 환불 트랜잭션이 PG 환불은 실행

**상태** 수정됨 · **영향** 금전 불일치 · 이중 환불 위험 · **위치** `apps/payment/.../core/service/PaymentService.java`
**재현** `PaymentCancelTest#brokenFinalizationLeavesObservableState`, `#interruptedCancelCompletesOnRetry`

- **증상** Outbox 기록이나 커밋이 실패하면 DB는 `PAID`로 롤백되는데 PG 환불은 이미 나갔다. 재시도하면 이중 환불한다.
- **원인** 되돌릴 수 없는 `pgClient.cancel`이 트랜잭션 안에 있었다.
- **수정** `RefundFacade`가 세 걸음을 조율한다: ① `PAID → CANCELING` 커밋 ② 트랜잭션 밖 PG 환불 ③ `CANCELED` + `PaymentCancelled` 적재 커밋. 중간에 멈추면 `CANCELING`으로 관측된다. 재시도 안전을 위해 `PgClient#cancel`의 `pgTxId` 기준 멱등을 계약으로 명시.

---

<a id="d-007"></a>
## D-007 상태 불일치 보상 요청이 무한 재시도

**상태** 수정됨 · **영향** 가용성 · **위치** `apps/payment/.../core/service/PaymentService.java`
**재현** `PaymentCancelTest#compensationOnInconsistentStateDoesNotThrow`, `#compensationOnInconsistentStateChangesNothing`

- **증상** `PAID`가 아닌 결제에 보상 요청이 오면 같은 이벤트가 영원히 재전송되고 파티션이 멈춘다.
- **원인** `Payment.cancel()`의 `CONFLICT` 예외가 가드 마킹까지 롤백했다. 결제는 스스로 `PAID`가 될 수 없어 재시도로 풀리지 않는다.
- **수정** `Payment#cancelable()`로 값으로 묻고, 취소 불가면 `log.error` 후 아무것도 하지 않고 끝내 가드 마킹을 커밋한다.

---

<a id="d-008"></a>
## D-008 재사용된 PG 멱등키가 다른 주문의 승인을 삼킴

**상태** 수정됨 · **영향** 금전 손실 · 관측 불가 · **위치** `apps/payment/.../core/service/PaymentService.java`
**재현** `PaymentCallbackLookupTest#reusedIdempotencyKeyMustNotHijackAnotherOrder`, `#secondDistinctApprovalIsRejectedLoudly`, `PaymentTest#secondApprovalWithDifferentKeyIsRejected`

- **증상** PG가 멱등키를 재사용하면 주문 Y의 승인이 주문 X의 "중복 콜백"으로 조용히 무시되어 Y가 `PENDING`에 남고, 지급·확정·정산이 모두 누락됐다. 예외도 경고도 없다.
- **원인** 외부가 만드는 멱등키를 주문번호보다 먼저 조회했다.
- **수정** 주문번호로 찾고, 저장된 멱등키와 비교해 같으면 재전송, 다르면 예외로 드러낸다. 전역 유니크 제약을 걷어내고(`V2__scope_idempotency_key.sql`) 동시 중복 방어는 `PaymentRepository#findByOrderNoForUpdate`로 옮겼다.

---

<a id="d-009"></a>
## D-009 서버 측 금액 재계산에 수량 검증 없음

**상태** 수정됨 · **영향** 금액 조작 · **위치** `apps/catalog/.../core/domain/QuoteItem.java`
**재현** `ProductQuoteTest#nonPositiveQuantityIsRejected`, `#negativeQuantityCannotReduceTotal`

- **증상** 음수 수량이 통과하면 총액을 임의로 낮출 수 있다(`100,000원 × 1 + 10,000원 × (-9) = 10,000원`). 이 총액이 이후 모든 금액 대조의 기준이 된다.
- **원인** `@Min(1)`이 HTTP DTO에만 있고 core 진입점 `quote(List<QuoteItem>)`에는 없었다. 유일한 호출자가 컨트롤러라 터지지 않았을 뿐이다.
- **수정** `QuoteItem` compact 생성자에서 검증해 잘못된 값 객체가 존재할 수 없게 했다. DTO 검증은 400 응답을 위해 유지.

---

<a id="d-010"></a>
## D-010 재처리해도 소유 이벤트가 재발행되지 않음

**상태** 수정됨 · **영향** 하위 서비스 복구 불가 · **위치** `apps/license/.../core/service/LicenseService.java`
**재현** `LicenseIssueEventTest#replayShouldRepublishOwnershipEvent`, `#revokedOrderDoesNotRepublishOwnership`

- **증상** download가 최초 `LicenseIssued`를 놓치면 재처리해도 권한 사본을 복구할 수 없어, 라이브러리에는 보이는데 다운로드만 안 됐다.
- **원인** 새로 지급된 라이선스가 없으면(`issued.isEmpty()`) 이벤트를 발행하지 않았다.
- **수정** `LicenseIssued`가 변화가 아니라 현재 소유 상태(그 주문의 ACTIVE 전체)를 싣고 재발행한다. 전부 회수된 주문에는 발행하지 않는다.
- 후속: 이 회귀 테스트가 운영에 없는 재처리 경로(새 `event_id`)를 쓰고 있었다는 사실은 [D-030](#d-030)에서 드러났다.

---

<a id="d-011"></a>
## D-011 변화 없는 회수가 이벤트를 재발행

**상태** 수정됨 · **영향** 잡음 · 하위 서비스 불필요 처리 · **위치** `apps/license/.../core/service/LicenseService.java`
**재현** `LicenseIssueEventTest#repeatedRevokeShouldNotRepublish`

- **증상·원인** 이미 전부 `REVOKED`인데도 목록이 비어 있지 않다는 이유로 `LicenseRevoked`를 무조건 발행했다(지급 경로와의 비대칭).
- **수정** `License#revoke`가 실제 전이 여부를 boolean으로 돌려주고, 하나라도 바뀌었을 때만 발행한다. 정책: **지급은 상태를 알리고, 회수는 변화를 알린다.**

---

<a id="d-012"></a>
## D-012 지각 회수 이벤트가 새 구매 권한을 거둠

**상태** 수정됨 · **영향** 사용자 영향 · 원인 추적 어려움 · **위치** `apps/download/.../core/service/EntitlementService.java`
**재현** `DownloadEntitlementTest#staleRevokeMustNotAffectNewEntitlement`, `#revokeOfOwningOrderStillWorks`

- **증상** 주문1 환불 → 주문2 재구매 뒤 주문1의 지각 `LicenseRevoked`가 주문2 권한을 회수했다(license에는 ACTIVE).
- **원인** 권한 문서 키가 `memberId:productId`뿐이라 어느 주문의 회수인지 구분하지 못했다.
- **수정** `revoke`가 `orderNo`를 받아 `Entitlement#belongsTo`로 대조하고, 다른 주문이면 건드리지 않고 로그를 남긴다.

---

<a id="d-013"></a>
## D-013 발행 실패한 이벤트를 같은 애그리거트의 뒤 이벤트가 추월

**상태** 수정됨 (D-003 수정 중 발견) · **영향** 순서 역전 · **위치** `common/messaging/.../outbox/OutboxRelay.java`
**재현** `OutboxRelayTest#failureHoldsLaterEventsOfSameAggregate`, `#failureOfOneKeyDoesNotBlockOtherKeys`, `#sameKeyIsPublishedInOrder`

- **증상** 같은 키의 이벤트 2건이 한 배치에 있고 앞의 것이 발행에 실패하면 뒤 것이 먼저 나간다. 예: catalog `APPROVED`가 `ON_SALE`을 덮어 판매 중 상품이 검색에서 사라짐, 릴레이 복구 직후 `PaymentCancelled`가 `PaymentCompleted`를 추월해 환불됐는데 라이선스가 남음. 컨슈머의 방어 코드가 이 사고를 조용히 삼킨다.
- **원인** 깨지는 지점은 수신이 아니라 발행이다. 릴레이가 실패한 건을 건너뛰고 계속 발행했다. D-003 백오프가 추월 창을 1초에서 최대 5분으로 넓혔다.
- **수정** 배치를 `partitionKey`로 묶어 웨이브 단위로 발행하고 성공한 키만 다음 웨이브로 넘긴다. 이 변경이 처리량도 138.0 → 156.1 events/s(×1.13)로 올렸다([E1](experiments.md#relay-laptop)). 보장은 릴레이 1대일 때만 성립한다([이벤트 순서](event-ordering.md)).

---

<a id="d-014"></a>
## D-014 백오프로 빠진 앞 이벤트를 다음 회차에서 뒤 이벤트가 추월

**상태** 수정됨 (D-013 수정의 사각지대) · **영향** 순서 역전 · **위치** `common/messaging/.../outbox/OutboxRelay.java`
**재현** `OutboxRelayTest#backOffDoesNotLetLaterEventOvertakeAcrossCycles`

- **증상** 앞 이벤트는 실패로 `next_attempt_at`이 미래가 되어 조회에서 빠지고, 보류된 뒤 이벤트는 `NULL`이라 다음 회차에 혼자 발행됐다.
- **원인** 키 순서는 대역 테스트(`OutboxRelayTest`), 시간 조건은 실 MySQL 테스트가 따로 검증해 둘이 만나는 지점을 아무도 보지 않았다. 대역에 시간 필터를 넣자 바로 재현됐다.
- **수정** 실패 시 같은 키의 뒤 이벤트에 앞의 `next_attempt_at`을 전파한다(`OutboxRelay#holdRemainder` → `OutboxEvent#holdUntil`). `retryCount`는 늘리지 않는다. 앞 이벤트가 DEAD가 되면 키를 푼다(영구 정지보다 낫다, DEAD 알람이 짝).

---

<a id="d-015"></a>
## D-015 형식이 깨진 요청이 400 이 아니라 500 으로 응답

**상태** 수정됨 (컨트롤러 테스트를 붙이다 발견) · **영향** 오분류 · 알람 잡음 · **위치** `common/web/.../GlobalExceptionHandler.java`
**재현** `StudioControllerTest#missingRequiredHeaderIsClientError`

- **증상** 필수 헤더·쿼리 파라미터 누락, 경로 변수 타입 불일치, 깨진 JSON이 전부 500이었다. 클라이언트가 재시도하고 5xx 알람·스택 트레이스가 실제 장애 신호를 묻는다.
- **원인** `BusinessException`·`MethodArgumentNotValidException`·`ConstraintViolationException` 외에는 `Exception` 핸들러로 흘렀다.
- **수정** `MissingRequestHeaderException`·`MissingServletRequestParameterException`·`MethodArgumentTypeMismatchException`·`HttpMessageNotReadableException`을 400으로 묶고 `log.warn`, 스택 트레이스 없음. `common:web`이라 9개 서비스에 한 번에 적용.

---

<a id="d-016"></a>
## D-016 진행 중인 심의에 재신청이 오면 컨슈머가 멈춤

**상태** 수정됨 (서비스 계층 테스트를 붙이다 발견) · **영향** 가용성 · 자동 복구 불가 · **위치** `apps/review/.../core/service/ReviewService.java`
**재현** `ReviewReceiveTest#resubmitOnLiveRequestMustNotStallTheConsumer`, `#resubmitOnApprovedRequestMustNotStallTheConsumer`

- **증상** `IN_REVIEW`·`APPROVED` 건에 새 `eventId`의 재신청이 오면 금지 전이 예외로 파티션이 무한 재시도에 빠져 다른 게임 심의까지 멈췄다.
- **원인** `receive()`가 상태를 보지 않고 `reopen()`을 불렀다. 멱등 가드는 같은 `eventId`만 거른다.
- **수정** 재신청은 `REJECTED`에만 적용하고 그 외는 `log.warn` 후 넘어간다. **컨슈머 경로에서는 재시도로 풀리지 않는 거절을 예외로 하지 않는다**(D-007·D-018과 같은 기준).

---

<a id="d-017"></a>
## D-017 지각 심의 결과가 승인된 프로젝트를 강등

**상태** 수정됨 (도메인 테스트를 붙이다 발견) · **영향** 사용자 영향 · 서비스 간 상태 불일치 · **위치** `apps/studio/.../core/domain/GameProject.java`
**재현** `GameProjectTest#lateRejectionMustNotDemoteApprovedProject`, `#approvalWithoutSubmissionIsIgnored`

- **증상** 지각 `ReviewRejected`가 `APPROVED` 프로젝트를 `REJECTED`로 바꿨다. catalog는 계속 판매하고 창작자는 스스로 되돌릴 수 없다.
- **원인** 사람이 부르는 `submit()`에만 상태 가드가 있고 컨슈머가 부르는 `approve()`·`reject()`에는 없었다.
- **수정** `SUBMITTED`에서만 전이하고 아니면 `false`를 반환해 서비스가 `log.warn` 후 소비를 진행한다(무시를 관측 가능하게).

---

<a id="d-018"></a>
## D-018 결제 없는 주문의 보상 요청이 무한 재배달

**상태** 수정됨 · **영향** 가용성 · 자동 복구 불가 · **위치** `apps/payment/.../core/service/PaymentService.java`
**재현** `PaymentCancelTest#compensationForUnknownOrderMustNotStallTheConsumer`, `#compensationForUnknownOrderKeepsTheGuardMark`

- **증상·원인** D-007과 같은 메서드 한 줄 위, 결제 자체가 없는 경로가 `PAYMENT_NOT_FOUND` 예외로 남아 파티션을 멈췄다. D-007 재현 테스트가 `PENDING` 결제로만 검증했다.
- **수정** `findByOrderNo(...).orElse(null)`로 받아 없으면 `log.error` 후 `PaymentCancellation.none()`을 반환한다.

---

<a id="d-019"></a>
## D-019 order 의 수량 검증이 어댑터에만 존재

**상태** 수정됨 · **영향** 금액 조작 (잠재) · **위치** `apps/order/.../core/domain/QuoteItem.java`
**재현** `QuoteItemTest#zeroQuantityIsRejectedByTheDomain`, `#negativeQuantityIsRejectedByTheDomain`, `#missingProductIdIsRejectedByTheDomain`

- **증상·원인** D-009를 catalog `QuoteItem`에서만 고치고 order의 같은 이름 값 객체를 놓쳤다. catalog가 거절하므로 조작까지는 가지 않지만 HTTP 왕복 뒤 4xx가 `UPSTREAM_UNAVAILABLE`(503)로 보고됐다.
- **수정** 같은 가드를 넣어 보내는 쪽에서 400으로 끊는다. 서비스마다 값 객체를 따로 두는 구조의 대가로, 한쪽을 고칠 때 같은 이름의 다른 쪽을 함께 본다.

---

<a id="d-020"></a>
## D-020 범위를 벗어난 페이지 파라미터가 400 이 아니라 500 으로 응답

**상태** 수정됨 · **영향** 오분류·알람 잡음 (D-015 계열) · **위치** `apps/store/.../core/service/StoreService.java`
**재현** `StoreControllerTest#negativePageIsRejected`, `#zeroSizeIsRejected`

- **증상** 공개 검색 `?page=-1`·`?size=0`이 `PageRequest.of`의 `IllegalArgumentException`으로 500.
- **원인** D-015 목록에 범위를 벗어난 값이 없었고, 컨트롤러 테스트가 서비스를 mock으로 둬 `PageRequest.of`가 실행되지 않았다.
- **수정** `StoreService.search`에서 범위를 검사해 `INVALID_REQUEST`. 재현 테스트는 실제 서비스를 태운 MockMvc로 돌린다.

---

<a id="d-021"></a>
## D-021 스텁 격리 규칙이 실제 스텁을 한 번도 검사하지 않음

**상태** 수정됨 · **영향** 규칙의 공허 통과 (운영 사고 잠재) · **위치** `common/archunit/.../ModuleHygieneRules.java`
**재현** `ArchRuleEnforcementTest#everyStubPrefixIsChecked`

- **증상** `Mock*`·`Stub*`·`Fake*`가 `@Profile`/`@ConditionalOnProperty`로 격리됐는지 보는 규칙이 `Fake*`만 검사했다. 실제 스텁 넷(`MockPgClient`·`MockRatingBoardClient`·`MockBuildStorage`·`MockTaxInvoiceIssuer`)은 한 번도 평가되지 않았다.
- **원인** ArchUnit 유창한 `and()`/`or()`는 우선순위 없이 왼쪽부터 결합한다. `allowEmptyShould(true)` 때문에 대상이 비어도 통과했다.
- **수정** 술어를 `DescribedPredicate`로 명시적으로 묶고, 규칙 자체를 위반·준수 픽스처로 검사하는 `ArchRuleEnforcementTest`를 추가했다. 접두사 셋은 각각 따로 확인한다.

---

<a id="d-022"></a>
## D-022 롤백된 마감 트랜잭션이 세금계산서는 발행

**상태** 수정됨 · **영향** 금전 불일치 (외부 시스템) · **위치** `apps/settlement/.../core/service/SellerSettlementService.java`
**재현** `SettlementCloseFacadeTest#failedIssuanceKeepsTheClosing`, `#oneSellerFailureDoesNotBlockOthers`, `#failedIssuanceIsRetriedOnNextRun`

- **증상** 마감 트랜잭션이 롤백돼도 세금계산서는 이미 발행됐다. 단일 인스턴스에서도 100명 중 87번째 실패면 86장이 발행된 채 마감 기록만 사라진다.
- **원인** 클래스 레벨 `@Transactional` 안에서 외부 `taxInvoiceIssuer.issue`를 호출했다(D-006과 같은 모양). 주석은 이를 다중 인스턴스 문제로 한 칸 얕게 짚었다.
- **수정** `SettlementCloseFacade`(트랜잭션 없음)가 판매자마다 ① 확정본 커밋 ② 트랜잭션 밖 발행 ③ 발행 번호 커밋을 조율한다. `TaxInvoiceIssuer#issue`에 `(sellerId, month)` 멱등을 명시하고, 마감은 됐는데 계산서가 없는 판매자(`findAwaitingTaxInvoice`)도 대상에 넣었다. 그다음 `@SchedulerLock`(`settlement-close-month`)을 붙였다.

---

<a id="d-023"></a>
## D-023 배포 게이트가 컨테이너 0개에도 통과

**상태** 수정됨 · **영향** 게이트의 공허 통과 · **위치** `scripts/smoke-stack.sh` 0장 → `scripts/stack-wait.sh`로 옮기며 수정
**재현** 셸이라 태그 대신 관측 출력을 남겼다 — 앱 10종을 내리고 옛 판정식을 돌리면 `✓ unhealthy 없음`

- **증상** 스택이 전부 내려가 있어도, 브로커만 내려가도 게이트가 초록이었다.
- **원인** `docker ps --filter health=unhealthy`의 빈 결과를 정상으로 읽었다(죽은 컨테이너는 목록에 없다). compose 20종 중 7종은 헬스체크가 없고, Kafka가 죽어도 액추에이터 10종은 UP이었다.
- **수정** 기대 컨테이너 20종 이름과 대조해 없는 것을 이름으로 말하고, `kafka-broker-api-versions.sh`로 브로커를 직접 찌르고, 액추에이터 10종을 앞에 둔다. 종료 코드 2. 수정 후 같은 절차에서 `통과 2 / 실패 12`, 브로커만 내리면 2건으로 빨개짐을 확인했다. (현재는 `tools/verification/stack-status.py`가 Compose 프로젝트별로 검사한다.)

---

<a id="d-024"></a>
## D-024 알 수 없는 정렬 속성이 400 이 아니라 500 으로 응답

**상태** 수정됨 · **영향** 오분류·알람 잡음 (D-015 계열) · **위치** `apps/catalog/.../core/domain/ProductSort.java`, `common/web/.../UnknownPropertyExceptionHandler.java`
**재현** `ProductSortTest` (통합 소스셋)

- **증상** 공개 `GET /api/v1/products?sort=productId,desc`가 500(`No property 'productId' found for type 'Product'`), 응답에 없는 내부 이름 `id,desc`만 200이었다. 계약이 뒤집혀 있었고 엔티티 타입명이 공개 경로로 나갔다.
- **원인** 요청 문자열이 검사 없이 엔티티 속성으로 해석됐고 `PropertyReferenceException`이 핸들러 목록에 없었다.
- **수정** ① `ProductSort`가 허용 키(`productId → id`, `productCode`·`name`·`price`)를 명시하고 그 밖은 `INVALID_REQUEST`(`ProductQueryService`가 호출) ② 안전망 `UnknownPropertyExceptionHandler`가 400으로 받는다. `id`는 거절한다. 안전망은 `@Order` 없이는 catch-all에 밀려 실행되지 않았으므로 테스트가 불리한 순서로 세운다. `ProductSortKeysTest`가 허용 표를 응답(`ProductResponse`)·엔티티 속성과 전수 대조한다.

---

<a id="d-025"></a>
## D-025 정렬 결과가 결정적이지 않아 페이지 경계에서 상품이 중복·유실

**상태** 수정됨 · **영향** 데이터 정합성 — 페이지 경계 중복·유실 · **위치** `apps/catalog/.../core/domain/ProductSort.java`
**재현** `ProductSortKeysTest`, `ProductSortTest` (통합 소스셋)

- **증상** `price`·`name`처럼 유일하지 않은 키나 정렬 없는 요청에서 동값이 페이지를 넘으면 상품이 두 페이지에 나오거나 어디에도 안 나온다. e2e는 "22번째 상품에서 터졌다"며 호출 쪽만 피해 갔다.
- **수정** 표를 통과한 정렬 끝에 `id desc`를 붙인다(정렬 없으면 최신순). 함께 막은 것: 되싣는 요청 문자열의 제어문자 제거·길이 제한(`EchoedInput.safe`, 로그 줄 위조 방지), 표보다 많은 `sort` 절 거절, 안전망 로그에 타입명 기록. 상위 클래스 필드를 보도록 전수 대조를 고쳤다.
- 확인: 꼬리표 줄을 지우면 단위 3건, `apply`가 정렬을 떨어뜨리면 통합 5건이 빨개진다. `EchoedInput`을 빼면 로그 어펜더 테스트가 빨개진다.

---

<a id="d-026"></a>
## D-026 컨슈머가 멈추면 랙 지표도 같이 멈춰 적체가 관측되지 않는다

**상태** 우회함 — 지표 자체는 고칠 수 없다. 판정 근거를 브로커로 옮겼다 · **영향** 관측 불가 · **위치** `kafka_consumer_fetch_manager_records_lag` (Kafka 클라이언트 지표)
**재현** `ConsumerLagMetricTest#appMetricGoesSilent` (`expected: 5000L but was: 0L`) · **대조** `ConsumerLagMetricTest#brokerOffsetsSeeBacklog`

- **증상** 실제 랙(브로커) 113,517건 동안 지표 0, 로컬 60 RPS soak에서 실제 2,479건에 지표 최대 209([E5](experiments.md#consumer)).
- **원인** 지표는 컨슈머가 직전 fetch에서 본 값이라 fetch가 멈추면 같이 멈춘다. 틀린 것은 이 지표를 판정 근거로 고른 선택(측정 도구·성능 문서)이었고, 컨슈머 적체 알람은 없었다.
- **수정** 측정은 `tools/observability/collect-lag.sh`, 상시·알람은 `kafka-exporter` → `infra/prometheus/alerts.yml`의 `ConsumerLagGrowing`·`ConsumerStalled`. `collect-consumer.sh`의 열은 `lag_reported`로 바꿔 판정에 쓰지 않는다.
- **닫히는 조건** 재현 테스트는 통과할 수 없다. 앱 지표를 랙 판정에 쓰는 자리가 하나도 남지 않으면 태그를 떼지 않고 테스트를 지운다. 진짜 방어선은 태그 없는 `brokerOffsetsSeeBacklog`다. 이 항목이 `integrationTest`의 `known-defect` 제외 누락을 드러냈다(위 "빌드 연동").

---

<a id="d-027"></a>
## D-027 인프라 장애를 지급 불가로 판정해 정상 결제를 환불

**상태** 수정됨 · **영향** 금전 손실 · 사용자 영향 · **위치** `apps/license/.../config/KafkaErrorHandlerConfig.java`
**재현** `KafkaErrorHandlerConfigTest#storageFailureIsParkedNotCompensated`, `#transactionFailureIsStorageFailure`, `#nestedSqlExceptionIsStorageFailure`
**실측** [E8 시나리오 1c](experiments.md#chaos-refund) — 주문 40건, 이용권(`license`) 테이블 60초 차단에 정상 결제 **8건 환불 → 수정 후 0건**, 보류 7건 재투입 후 1초 이내 전부 지급

- **원인** D-002가 "언제"는 고쳤지만 "무엇을 보고" 보상하는지는 그대로였다. 재시도 소진(= 네 번 물어 답을 못 받음)을 지급 불가로 취급했고, D-002 회귀 테스트가 "커넥션풀이 마르면 환불한다"를 고정하고 있었다. 스키마 전체 차단은 보상 기록도 실패해 우연히 안전했다.
- **수정** 보상 관문 둘: 원인이 저장소 장애(`DataAccessException`·`TransactionException`·`SQLException`, 원인 사슬 끝까지)면 DLT, 이미 지급됐으면([D-028](#d-028)) DLT. 실측 예외 `CannotCreateTransactionException`은 `DataAccessException`이 아니었다. 근거는 비용의 비대칭(환불은 되돌리기 어렵고 보류는 쉽다)이며 `MessagesDeadLettered`(critical)가 사람을 부른다.
- **남은 부분** 자동 보상이 사실상 수동에 가까워졌다. license outbox 4,153건 중 `LicenseIssueFailed` 0건이었지만 경로는 남기고 `stove.payment.compensated` 카운터·`PaymentCompensationTest`로 살아 있음을 보장했다. 회전 주기는 테이블 차단 7.8초·스키마 차단 19.8초다.

---

<a id="d-028"></a>
## D-028 이미 지급된 주문에 보상 환불이 발동

**상태** 수정됨 · **영향** 금전 손실 — 물건을 받은 주문의 환불 · **위치** `apps/license/.../config/KafkaErrorHandlerConfig.java`, `core/service/LicenseService.java`
**재현** `KafkaErrorHandlerConfigTest#alreadyIssuedOrderIsNotCompensated` · **실측** [E8](experiments.md#chaos-refund) 장애 회차 로그

- **증상** 같은 주문번호로 `라이선스 지급` INFO와 `지급 최종 실패 — 보상으로 종결` ERROR가 함께 남았다. 테이블만 끊긴 조건이었다면 `inconsistent`(환불했는데 물건은 줬다)가 된다.
- **원인** 트랜잭션 커밋 후 오프셋 커밋 전에 끊겨 재배달됐고, 재처리 실패에 보상이 시작됐다. 보상이 "정말 실패했는지"를 확인하지 않았다.
- **수정** 보상 직전 `LicenseService#isIssued(orderNo)`로 원장을 확인하고 있으면 DLT로 보낸다(재투입 시 멱등 `issue()`가 소유 이벤트를 재발행). 회수된 라이선스도 '있음'으로 센다. 확인 질의 실패는 관문 1이 받는다.

---

<a id="d-029"></a>
## D-029 미결제 주문에 만료가 없어 옛 가격으로 영구히 결제

**상태** 수정됨 · **영향** 금액 조작 — 지난 가격으로 구매 · **위치** `apps/payment/.../core/domain/Payment.java`, `core/service/PaymentService.java`
**재현** `PaymentWindowTest` · `PaymentCheckoutWindowTest` · `StrandedRefundResumeTest` (통합 소스셋) 15건 · **실측** [E8 시나리오 2b](experiments.md#chaos) — 30일 지난 주문이 옛 가격 39,000원으로 승인

- **원인** 금액은 주문 생성 때 한 번만 서버 가격으로 확정되고 이후 단계는 "아까 정한 금액과 같은가"만 봤다. `Order`·`Payment`에 만료 개념이 없었다.
- **수정** `stove.payment.window`(기본 30분)를 넘긴 주문의 사전등록을 PG 호출 전에 막는다(`PAYMENT_WINDOW_EXPIRED`, 기준 `payment.created_at`). 승인 콜백 시점 검사·order 스케줄러·재견적은 버렸다.
- **후속 1** 사전등록 이후 창: `prepared_at`(`V6__payment_checkout_window.sql`)과 `stove.payment.checkout-window`(15분). 만료 후 승인이 오면 거절하지 않고 PAID로 적은 뒤 같은 트랜잭션에서 `CANCELING`, 트랜잭션 밖 PG 환불, `CANCELED`. `PaymentCompleted`는 내보내지 않는다. `prepared_at`이 없으면 만료가 아니라고 답한다. 지표 `stove.payment.auto-refunded`, 알람 `AutoRefundsRising`(15분 5건). 재현 `PaymentCheckoutWindowTest` 5건.
- **후속 2** `CANCELING`에 멈춘 건: `RefundSweeper`가 1분마다 `refund-resume-after`(2분)를 넘긴 건을 원래 사유로 재개한다(근거는 `PgClient#cancel` 멱등, ShedLock은 시도 횟수 가시성용). 알람 `RefundsStuckInCanceling`(게이지 `stove.payment.canceling`, 10분). 재현 `StrandedRefundResumeTest` 6건.
- 주문 쪽 `CREATED` 누적은 `OrderStatus.EXPIRED`·`OrderExpirySweeper`로 닫았다(당시 `CREATED` 98,750건 중 98,746건이 부하 시험 잔재).

---

<a id="d-030"></a>
## D-030 이벤트 재처리로 유실을 복구할 수 없다 — Inbox 가드가 먼저 막는다

**상태** 절차로 닫음 (코드 변경 없음) · **영향** 복구 불가 — 조용히 실패 · **위치** `common/messaging/.../inbox/ProcessedEventGuard.java`와 호출부
**재현** `LicenseReplayRecoveryTest` (통합 소스셋) 4건 · **절차** [runbooks/license-db-loss.md](runbooks/license-db-loss.md) · **실측** [E8 시나리오 1b](experiments.md#chaos-replay) — 200건 유실 후 오프셋 리셋으로 **0건 복구, 예외 0건**

- **원인** 원장만 지워지고 가드 행이 살아 있으면 되읽은 이벤트가 전부 "이미 처리함"(`info` 로그)이 된다. 운영 재처리 수단 셋(오프셋 리셋·`DltOpsService#replay`·Outbox 재적재)은 모두 `event_id`를 보존한다. [D-010](#d-010) 회귀 테스트는 운영에 없는 새 `event_id` 경로를 지키고 있었다.
- **버린 안** 가드 제거(`revoke()`는 멱등이 아님), 재투입 시 새 `event_id`(다른 컨슈머의 멱등 붕괴), 인박스 일괄 삭제 API.
- **수정** 런북: 가드 행을 `consumer_group='license' and event_type='PaymentCompleted'`로 좁혀 지우고, 마지막에 대사로 판정한다. 가드 삭제 포함 시 200건 복구, 16.7건/s, 대사 200 : 200.

---

<a id="d-031"></a>
## D-031 랙 알람이 조건이 참인 내내 한 번도 울리지 않는다

**상태** 수정됨 · **영향** 관측 불가 — 알람의 공허 통과 · **위치** `infra/prometheus/alerts.yml`의 `ConsumerLagGrowing`
**재현** [E8 알람 측정](experiments.md#chaos-alerts) 회차 A — 랙 592가 10분 넘게 고정인데 발화 0회 · **실측** 스크레이프 133회 중 랙 표본 126개(결측 5.3%), `up`은 내내 1

- **원인** 익스포터가 응답은 하면서 그 그룹의 `lag_sum`만 빠뜨렸고, `for`는 결측을 거짓으로 취급해 시계를 리셋했다(pending 4회 리셋). 60표본 연속 확률 `0.947^60 ≈ 0.04`. 규칙은 `promtool check rules`만 통과했고 발화는 확인되지 않았다.
- **수정** `kafka_consumergroup_lag_sum > 500` + `for: 10m` → `min_over_time(kafka_consumergroup_lag_sum[10m]) > 500`. 결측 1회의 대가가 시계 전체 리셋에서 없음으로 바뀌었다. 결측률 감시는 `ConsumerLagMetricGaps`.
- 원인 추측("그룹을 통째로 생략")은 틀렸다 — [D-032](#d-032)가 규명했다.

---

<a id="d-032"></a>
## D-032 익스포터가 스크레이프마다 지표를 정확히 한 줄씩 흘린다

**상태** 수정됨 · **영향** 관측 불가 — [D-031](#d-031)의 원인 · **위치** `docker-compose.yml`의 `kafka-exporter` (원인은 `danielqsj/kafka-exporter:v1.9.0` 안)
**재현** 익스포터를 1초 간격으로 직접 긁는다 — 60회 중 25회가 그룹 12개가 아니라 11개 · **실측** 정상 응답 635줄 / 결측 응답 634줄 (30회 관측)

- **원인 추적** Prometheus를 거치지 않아도 재현, 두 그룹이 동시에 빠진 적 0회, 빠진 그룹도 `current_offset`·파티션별 `lag`·`current_offset_sum`은 있고 토픽 블록의 마지막 전송인 `lag_sum`만 없음, 로그·HTTP 코드 차이 없음. 기본값(`--concurrent.enable` 꺼짐)의 결과 공유 경로가 마지막 전송을 놓친다.
- **수정** `--concurrent.enable` 추가. 온전한 스크레이프 35/60 → 40/40, 실환경 12개 그룹 전부 60/60(보존율 0.750~0.983 → 1.000). 측정 중 CI가 플래그를 되돌린 순간 29/60으로 돌아와 대조가 됐다. 브로커 1대·그룹 12개라 "큰 클러스터에서는 끄라"는 경고는 적용하지 않았고, 클러스터가 커지면 다시 본다.
- D-031의 `min_over_time`과 `ConsumerLagMetricGaps`는 그대로 둔다.

---

<a id="d-033"></a>
## D-033 인수 테스트가 데이터가 적을 때만 통과했다

**상태** 수정됨 · **영향** 게이트 마비 — `main`이 8/15부터 계속 빨감 · **위치** `e2e/.../TrackACreatorFlowTest#appearsInSearchIndex`
**재현** e2e를 21회 이상 돌린 스택에서 인수 시나리오를 돌린다 · **실측** 응답 20건(`productId` 15~34 오름차순), 이번 회차 상품은 41번

- **증상** 46건 중 1건이 60초 대기 끝에 실패. store 색인은 제때 됐다.
- **원인** `q=인수`로 페이지 지정 없이 찾았고, 지난 회차들의 "인수 시나리오 게임"이 기본 size 20을 채워 21번째 회차부터 페이지 1 밖으로 밀렸다. 스탬프가 `productCode`·`memberId`·`pgTxId`에는 있고 검색 대상인 제목에만 없었다.
- **수정** 제목에 스탬프(`"인수 시나리오 게임 " + STAMP`)를 넣고 `q=<STAMP>`로 묻는다. 남은 것: storefront 응답에 페이지 정보가 없다.

---

<a id="d-034"></a>
## D-034 게이트웨이만 graceful shutdown 이 없다

**상태** 수정됨 · **영향** 사용자 영향 — 재배포마다 진행 중 요청이 끊긴다 · **위치** `apps/gateway/src/main/resources/application.yml`
**재현** `DeploymentShutdownContractTest#everyAppShutsDownGracefully` · **실측** 앱 10종 중 9종 `server.shutdown: graceful`, gateway만 미설정

- **증상·원인** 모든 트래픽이 지나는 문만 빠져 있어 하류 아홉의 graceful이 무의미했다. 라우팅 표 위주인 설정 파일이라 "원래 없는 설정"처럼 읽혔고, 배포 순간에만 나타나 로그·지표에 남지 않는다.
- **수정** `server.shutdown: graceful`. 계약을 테스트로 옮겨 앱이 늘면 자동으로 검사 대상이 된다([E9](experiments.md#resilience) R-01).

---

<a id="d-035"></a>
## D-035 graceful shutdown 이 SIGKILL 로 잘린다 — 앱 10종 전부

**상태** 수정됨 · **영향** 사용자 영향 — 설정은 있는데 동작하지 않는다 · **위치** `docker-compose.apps.yml` + 앱 10종의 `application.yml`
**재현** `DeploymentShutdownContractTest#containerGraceOutlastsSpringShutdownPhase` · **실측** 컨테이너 유예 10초(도커 기본값) ≤ 스프링 종료 단계 30초(스프링 기본값) — 10종 전부

- **원인** 둘 다 기본값이면 도커가 10초에 SIGKILL을 보내 graceful이 처음부터 20초를 잃는다. 어긋남이 두 파일 사이에만 있어 한 파일로는 보이지 않는다.
- **수정** `stop_grace_period: 40s`(x-app 앵커)와 `spring.lifecycle.timeout-per-shutdown-phase: 30s`를 명시하고 부등호를 테스트가 지킨다. 배포 컨테이너에서 `StopTimeout: 40`을 확인했다.

---

<a id="d-036"></a>
## D-036 통합 테스트에 클래스를 하나 더하자 관계없는 기존 클래스가 빨개졌다

**상태** 수정됨 · **영향** 게이트 마비 — 원인과 증상이 다른 파일에 있다 · **위치** 루트 `build.gradle`의 `integrationTest` 태스크
**재현** payment 통합 소스셋에 프로퍼티 조합이 다른 `@SpringBootTest`를 하나 더한다 · **실측** `StrandedRefundResumeTest` 6건 중 5건 실패(`Too many connections`)

- **원인** 운영용 `maximum-pool-size: 20` × Hikari `minimumIdle` 기본값(= 최대) × 프로퍼티 조합마다 캐시되는 컨텍스트. MySQL `max_connections` 151이면 여덟 벌이 벽이고, payment는 통합 클래스 13개로 이미 그 언저리였다.
- **수정** `systemProperty 'spring.datasource.hikari.maximum-pool-size', '5'`. 시스템 프로퍼티는 컨텍스트 캐시 키에 들어가지 않는다. 한계선을 미룬 것이며 다음 수는 공유 기반 클래스로 조합을 줄이는 것이다.

---

<a id="d-037"></a>
## D-037 장애를 주입했는데 파티션만 옆으로 옮겨 갔다 — 컨슈머 그룹 멤버가 둘이었다

**상태** 수정됨 · **영향** 장애 주입 무효화 · **위치** `apps/license/src/integrationTest/.../LicenseOpenApiContractTest`
**재현** license 통합 회차에서 `ConsumerRestartCatchUpTest`의 중단 판정을 돌린다 · **실측** 리스너는 전부 `isRunning=false`인데 중단 중에 지급이 일어났다

- **원인** license 통합 소스셋의 두 컨텍스트가 모두 그룹 `license` 멤버라 한쪽을 멈추면 파티션이 다른 쪽으로 리밸런싱됐다. 합류 순서에 따라 원격에서는 통과하고 CI에서 실패하는 간헐 실패였다. 하네스의 "중단 중 미지급" 판정이 이것을 잡았다.
- **수정** `LicenseOpenApiContractTest`에 `spring.kafka.listener.auto-startup=false`(폴링을 재운 것과 같은 처방, 배선 검증은 유지). 캐시 컨텍스트가 쥐는 배경 스레드·커넥션 풀(D-036)·그룹 멤버십의 세 번째 사례다.

<a id="d-038"></a>
## D-038 검증 도구 9개가 실행 JAR 대신 `-test-fixtures.jar`를 고를 수 있었다

**상태** 수정됨 · **영향** 검증 무효 · **위치** `tools/verification/verify-*.py`
**재현** `test_ports.ApplicationJarUseTest` — 수정 전 `glob("*.jar")`를 직접 쓰는 도구 9개로 실패 · **실측** Order·Payment의 `build/libs`에 `*-SNAPSHOT.jar`와 `*-test-fixtures.jar`가 함께 있다

- **원인** PR #116에서 `verify-license.py`만 `support/boot_jars.application_jar`로 고쳤고, 나머지 도구는 `-plain.jar`만 빼는(또는 아무것도 빼지 않는) 옛 선택을 복사해 쓰고 있었다. 파일 순서에 따라 테스트 픽스처 JAR로 앱을 띄운다.
- **수정** 모든 도구가 `application_jar`를 쓴다. 테스트가 직접 `glob("*.jar")`를 막는다.

<a id="d-039"></a>
## D-039 앞 실행이 남긴 TIME_WAIT만으로 검증 도구가 "포트 사용 중"으로 멈췄다

**상태** 수정됨 · **영향** 검증 마비 · **위치** `tools/verification/support/ports.py`, 각 `verify-*.py`의 자체 포트 확인
**재현** `test_ports.PortOffsetsTest#test_port_left_in_time_wait_by_a_previous_run_is_not_reported_busy` — 수정 전 `application ports in use`로 실패 · **실측** `verify-studio.py`를 끝내자마자 다시 돌리면 30085·30091이 TIME_WAIT로 남아 시작 전에 멈췄다

- **원인** 빈 포트 확인이 `SO_REUSEADDR` 없이 bind했다. 앱(Tomcat·Netty)은 이 옵션으로 listen하므로 TIME_WAIT 포트를 쓸 수 있는데, 확인만 막혔다. 도구 6개의 자체 확인도 같은 이유로 연속 실행을 막았다(옵션을 켠 도구 5개는 무사).
- **수정** 공용 확인에 `SO_REUSEADDR`를 켜고, 도구마다 중복된 자체 bind 확인을 지웠다. 리눅스는 TIME_WAIT를 남긴 쪽도 옵션을 켰어야 다시 bind를 허용하므로, 재현 테스트의 서버도 옵션을 켠다.

<a id="d-040"></a>
## D-040 Store 최초 삽입 경합에서 같은 상품을 코드 충돌로 오인

**상태** 수정됨 · **영향** HTTP 동기화 회차 실패·Kafka 처리 지연과 ERROR 로그 · **위치** `ApplyCatalogProduct.execute`, `ReconcileCatalogProduct.apply`
**재현** `StoreIntegrationTest#sameProductCommittedBetweenIdAndCodeReadsConverges`, `#reconciliationConvergesWhenEventCommitsBetweenIdAndCodeReads` — 각각 known-defect Red 1건, `BusinessException: 상품 코드가 다른 상품에 연결되어 있습니다.` · **실측** main CI run 42249703875의 `concurrentHttpAndEventInsertsConvergeToHighestVersion` 실패. HTTP 경로는 독립 교차 리뷰 R1에서 추가 확인했다.

- **원인** READ_COMMITTED에서 빈 ID 잠금 조회 뒤 다른 트랜잭션이 같은 ID를 커밋하면 코드 조회에 같은 상품이 보인다. 두 서비스가 ID를 비교하지 않아 다른 상품으로 오인한다. BusinessException은 서비스 내부 최대 3회 재시도 루프에서는 제외된다. CI 테스트는 리스너를 거치지 않고 서비스를 직접 호출하므로 예외가 곧 테스트 실패가 된다.
- **운영 영향** 이벤트는 `CatalogEventListener` → `ApplyCatalogProduct` 경로다. Store 자체 CommonErrorHandler가 없어 `KafkaConsumerAutoConfiguration`의 DefaultErrorHandler와 `ConsumerRetryPolicy`(1·2·4초)가 적용되고 BusinessException도 재시도된다. 따라서 옛 코드도 첫 실패 뒤 약 1초 후 재배달로 v3에 수렴하며 짧은 지연과 ERROR 로그가 발생한다. HTTP 초기화·복구는 `SynchronizeCatalogProducts` → `ReconcileCatalogProduct` 경로로, 해당 동기화 회차가 실패하고 기본 5초 뒤 다음 회차를 시도한다. 첫 동기화라면 그동안 storefront가 NOT_READY다. 영구적으로 낮은 버전에 머문다고 단정하지 않는다.
- **수정** 두 서비스에서 코드 조회 결과의 productId를 비교한다. 같은 ID면 ConcurrencyFailureException으로 기존 최대 3회 새 트랜잭션 재시도를 사용해 잠금 조회부터 다시 수행한다. 다른 ID의 CONFLICT는 유지한다. 로직 통합 리팩터링은 하지 않았다. 두 결정적 테스트는 PausePoints로 빈 ID 조회 → 상대 v1 커밋 → v3 반영 순서를 고정하고 known-defect 태그를 떼어 기본 회귀 검사로 옮겼다. 실제 Kafka 경합은 `#kafkaDeliveryConvergesWhenSameProductIsCommittedBetweenReads`로 검증한다. 옛 Apply 코드로 이 테스트 1건이 통과했고 `Seeking to offset 0`, `Record in retry and not yet recovered` 로그 뒤 v3 수렴을 확인했다.
- **망가뜨리기 확인(결정 102)** Apply 수정 제거 시 `ApplyCatalogProduct.execute`에서 새 회귀 1건이 같은 BusinessException으로 실패했다. Reconcile 수정만 제거해도 `ReconcileCatalogProduct.apply`에서 HTTP 회귀 1건이 같은 메시지로 실패(Gradle exit 1)한다. 행 번호 대신 메서드 이름으로 식별한다.

<a id="d-041"></a>
## D-041 Review 다운로드 테스트가 서명 초 경계에서 조기 만료됐다

**상태** 수정됨 · **영향** 초 경계 CI 불안정 — 빠른 첫 요청도 403으로 실패 · **위치** `apps/review/src/integrationTest/java/com/stove/review/adapter/storage/ReviewBuildDownloadIntegrationTest.java#oneSecondContentUrlAtSecondBoundary`
**재현** `known-defect` 태그로 먼저 커밋. 초 미만 값이 0.95초 이상일 때 1초 URL을 서명하고, 실제 `X-Amz-Date + 1.05초` 뒤 첫 GET의 200을 기대하면 `expected: 200 but was: 403`으로 실패한다. 서명 중 다음 초로 넘어가도 URL에 기록된 시각을 기준으로 기다려 만료를 결정적으로 확인한다. · **실측** [CI run 42373571409](https://github.com/tlswltjq/esd-platform/actions/runs/42373571409) 1차 보고서 testcase `time="0.304"`, 첫 GET `expected: 200 but was: 403`; 같은 코드의 실패 작업 재실행 2차 통과.

- **원인** 고정 MinIO의 [서명 검증](https://github.com/minio/minio/blob/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e/cmd/signature-v4.go#L225-L227)은 초 단위 `X-Amz-Date`를 읽고 `현재 - 서명시각 > Expires`이면 만료로 판정한다. 1초 URL의 실제 수명은 서명 순간의 초 미만 부분만큼 짧아져 약 0~1초다. 예를 들어 00.95초에 서명하면 약 0.05초 뒤 만료하므로 0.304초 실행에서도 실패한다. 이전의 "느린 러너에서 1초 이상 지연" 설명은 잘못됐다. 운영 코드 결함이 아니라 내용·만료 검증을 같은 1초 URL로 수행한 테스트 설계 문제다([결정 113](decisions.md#flaky-tests)).
- **수정** `downloadedBytesMatchTheSubmittedBuild`는 `X-Amz-Expires=300`과 바이트·SHA-256을 확인하고 고정 지연을 제거했다. `validDownloadUrlExpires`는 컨테이너의 실제 자격 증명으로 3초 URL을 만들고 첫 GET 200 뒤 `403 + Request has expired`를 최대 10초 안에 확인한다. 초 경계 재현은 만료 오류를 확인하는 회귀 테스트로 승격하고 태그를 제거했다. 운영 코드는 변경하지 않았다.

<a id="d-042"></a>
## D-042 Catalog 최초 공개 경합에서 같은 Project를 상품 코드 충돌로 오인

**상태** 수정됨 · **영향** 공개 작업·출시 접수 회차가 계약 위반(CONFLICT)으로 실패하고 다음 재시도까지 지연, 실패 사유 오분류 · **위치** `PublishProjectRelease.execute`, `ReceiveProjectRelease.requireStableIdentity`
**재현** `CatalogPublicationIntegrationTest#sameProjectFirstPublicationCommittedBetweenProjectAndCodeReadsConverges`(공개), `#receiptConvergesWhenSameProjectFirstPublicationCommitsBetweenProjectAndCodeReads`(접수) — 수정 전 코드에서 각 1건 Red, `BusinessException: 상품 코드가 다른 Project에 연결되어 있습니다.` · **실측** [CI run 42397961475](https://github.com/tlswltjq/esd-platform/actions/runs/42397961475)(PR #135, Catalog 코드는 main `dc793705`과 같음)의 `concurrentFirstPublicationAndDuplicateDeliveriesConvergeToHighestVersion` 실패. 보고서상 v1 스레드가 `PublishProjectRelease.execute`에서 같은 메시지로 실패했다.

- **원인** 출시 요청 게이트는 요청 ID별이라 같은 Project의 다른 요청(v1·v2)은 서로 직렬화되지 않는다. READ_COMMITTED의 `lockByProjectId`는 행이 없으면 아무것도 잠그지 않아 둘 다 빈 결과를 받는다. 한쪽이 최초 `catalog_product`를 커밋한 뒤 다른 쪽의 `findByProductCode`가 그 행을 보는데, projectId를 비교하지 않아 다른 Project의 코드 충돌로 판정한다. 두 요청이 함께 삽입까지 가면 유일 키 위반으로 재시도되어 통과하므로, 상대 커밋이 두 읽기 사이에 들어올 때만 실패하는 간헐 실패였다. 기존 테스트의 `retry` 도우미는 `DataAccessException`·`NoSuchElementException`만 다시 시도하므로 BusinessException이 곧 테스트 실패가 된다. 접수의 식별 검사도 같은 두 읽기를 쓴다.
- **운영 영향** 공개는 `PublicationScheduler` → `PublishQueuedReleases`가 작업마다 트랜잭션을 열어 `PublishProjectRelease`를 부르는 경로뿐이다. 모든 RuntimeException을 잡아 같은 요청을 5·15·30·60초 뒤 다시 시도하므로(requirements R10.3) 옛 코드도 다음 회차에 v1이 STALE로 거절되며 v2로 수렴한다. 그동안 WARN 로그와 ADMIN 작업 조회의 `lastError`에 계약 위반 메시지가 남는다. 한 인스턴스의 스케줄러는 작업을 차례로 처리하므로 이 경합은 Catalog 인스턴스가 둘 이상일 때 생긴다. 접수는 `StudioReleaseEventListener` → `ReceiveProjectRelease` 경로다. Catalog 자체 CommonErrorHandler가 없어 공통 DefaultErrorHandler(1·2·4초 재시도 뒤 DLT)가 BusinessException도 재시도하므로, 첫 실패 뒤 약 1초 후 재배달에서 접수된다. 이쪽은 한 인스턴스에서도 리스너 스레드와 스케줄러 스레드 사이에서 생긴다(예: 중단 뒤 v1·v2 이벤트를 연달아 따라잡을 때). 영구히 낮은 버전에 머물거나 DLT로 간다고 단정하지 않는다.
- **수정** 두 서비스에서 코드 조회 결과의 projectId를 비교한다. 같은 Project면 `ConcurrencyFailureException`으로 트랜잭션 전체를 롤백하고, 같은 요청이 새 트랜잭션에서 잠금 조회부터 다시 하게 한다(requirements R2.5). 재시도 장치는 바꾸지 않았다(공개는 작업 재시도, 접수는 Kafka 재배달). 다른 Project의 CONFLICT는 유지하며 `#differentProjectWithSameProductCodeStaysConflict`가 공개·접수 양쪽을 지킨다. 같은 트랜잭션에서 다시 잠그고 계속하는 방식은 쓰지 않았다. 코드 조회로 이미 영속성 컨텍스트에 올라온 엔티티를 잠금 조회가 새로 읽는다는 보장에 기대야 하기 때문이다. 기존 경합 테스트는 바꾸지 않았다. 새 예외는 `DataAccessException`이므로 기존 `retry` 도우미가 다시 시도한다. 두 결정적 테스트는 PausePoints로 빈 Project 잠금 조회 → 상대 최초 공개 커밋 → 재개 순서를 고정한다. 롤백(출시 기록·작업·Inbox 없음)을 확인한 뒤 같은 요청을 다시 처리해 v2·상품 1행·Outbox(`CatalogProductPublished` v2, v2 `ProjectReleaseCompleted`, v1 `ProjectReleaseRejected` STALE)를 확인한다.
- **망가뜨리기 확인(결정 102)** 공개 수정 제거 시 공개 재현 1건이 `PublishProjectRelease.execute`에서 같은 BusinessException으로 실패했다. 접수 수정 제거 시 접수 재현 1건이 `ReceiveProjectRelease.requireStableIdentity`에서 같은 메시지로 실패했다. projectId 비교를 항상 참으로 바꾸면 경계 테스트가 공개·접수 각각에서 `ConcurrencyFailureException`(기대 BusinessException)으로 실패했다. 두 실행 모두 Gradle exit 1이었고, 복원한 운영 코드 diff는 수정 패치와 같다. 행 번호 대신 메서드 이름으로 식별한다.

<a id="d-043"></a>
## D-043 공유 테스트 컨테이너가 기본 시작 대기만 기다려 부하 시 통합 테스트 전체가 실패했다

**상태** 수정됨 · **영향** 부하가 몰린 CI 러너에서 한 모듈의 통합 테스트 컨텍스트가 모두 실패(코드와 무관한 PR이 병합 대기) · **위치** `common/testcontainers/src/main/java/com/stove/common/testcontainers/SharedContainers.java`
**재현** `common/testcontainers/src/test/java/com/stove/common/testcontainers/SharedContainersTest.java` 2건을 `known-defect` 태그로 먼저 커밋했다. 옛 코드에서 실제 대기 객체(`getWaitStrategy()`)의 시작 대기가 MySQL 2분·Kafka·Redis·Mongo 1분이라 `containsExactlyEntriesOf`가 실패하고, Redis가 공유 기본 대기 객체와 같아 `Expected not same: HostPortWaitStrategy`로 실패한다. 러너 부하 자체는 결정적으로 재현할 수 없다. 대신 늦은 시작은 결정적으로 만든다(PR #138 교차 리뷰 R2, 사용자 확정): `SharedContainersStartupIntegrationTest`가 Kafka 준비 로그를 65초 뒤에 내는 가짜 컨테이너(`redis:7-alpine`)에 `SharedContainers.kafkaReady()`를 건다. 대기를 라이브러리 기본 60초로 되돌리면 61.5초에 `ContainerLaunchException: Container startup failed`로 실패하고, 3분 대기에서는 66.4초에 통과한다. · **실측** PR #137 CI run 42410179110: Settlement `PgStatementIntegrationTest` 10건이 컨텍스트 로딩 실패. 원인은 `Container startup failed for image confluentinc/cp-kafka:7.8.0` — `Timed out waiting for log output matching '.*Transitioning from RECOVERY to RUNNING.*'`. 같은 run의 다른 Kafka 시작은 5~9초였고, 실패한 컨테이너는 60초 동안 로그 0줄이었다.

- **원인** CI 실패(PR #137 run 42410179110)의 직접 원인은 Kafka가 기본 60초 안에 준비 로그를 내지 못한 것이다. 늦어진 이유는 러너 부하로 **추정**한다(같은 run의 다른 Kafka 시작은 5~9초, 실패 컨테이너는 60초 동안 로그 없음). 이 수정은 60초를 넘는 늦은 시작을 견디게 할 뿐, 시작이 3분을 넘거나 늦어진 원인이 부하가 아니면 다시 실패한다. 구조적 원인: `SharedContainers`는 Elasticsearch만 3분 대기를 두고, 나머지는 Testcontainers 기본값(로그·포트 60초, JDBC 120초)을 썼다. 공유 정적 컨테이너라 하나가 늦으면 그 Gradle 모듈의 통합 테스트 컨텍스트가 모두 실패한다. 또 Testcontainers 1.21의 `GenericContainer`는 필드가 공유 기본값이면 `ContainerDef`의 대기 객체를 쓰는데, 일반 `GenericContainer`(Redis)의 그 객체는 모든 컨테이너가 나눠 쓰는 정적 `DEFAULT_WAIT_STRATEGY`다. 그래서 Redis에 `withStartupTimeout`만 걸면 다른 기본 컨테이너의 대기까지 바뀐다. `ConfluentKafkaContainer`도 모든 인스턴스가 정적 `KafkaHelper.WAIT_STRATEGY`를 나눠 쓴다(PR #138 교차 리뷰 R1).
- **수정** `STARTUP_TIMEOUT = 3분`을 모든 공유 컨테이너에 건다. MySQL은 `withStartupTimeoutSeconds`, Elasticsearch·Mongo는 `withStartupTimeout`(인스턴스마다 대기 객체를 새로 만든다)을 쓴다. 공유 정적 대기 객체를 쓰는 Redis와 Kafka에는 같은 조건의 새 대기 객체를 준다: Redis는 `Wait.forListeningPort()`, Kafka는 `Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1)`. 재현 테스트 두 건의 태그를 떼어 기본 회귀 검사로 옮겼다. 그 밖에 테스트 두 건으로 3분이 다른 컨테이너로 번지지 않는지 확인한다. 하나는 네 컨테이너가 공유 기본 대기 객체를 쓰지 않고 그 객체의 60초도 그대로인지 본다. 다른 하나는 같은 종류의 새 컨테이너와 대기 객체가 다르고, 새 Kafka·Redis·Mongo는 라이브러리 기본 60초를 그대로 갖는지 본다.
- **운영 영향** 테스트 전용 모듈이라 운영 코드와 배포에는 영향이 없다. 대기는 시작이 늦을 때만 길어지고 정상 시작(보통 10초 안)에는 그대로다.

<a id="d-044"></a>
## D-044 접수 전 PAY_PENDING 승인이 주문 만료로 PG 재조회 없이 닫혀 늦은 PAID를 놓침

**상태** 수정됨(결정 126, PR #145) · **영향** PG가 나중에 PAID가 되면 돈은 나갔는데 주문은 `EXPIRED`, 결제·승인도 `EXPIRED`로 끝나 보류·게이지·경보·ADMIN 목록 어디에도 보이지 않았다. 고객이 새 주문으로 다시 사면 이중 결제가 된다(결정 114가 막으려던 상황) · **위치** `ResolvePaymentApproval.cycle`의 접수 전 만료 분기(`current.getStatus()==REQUESTED && admittedAt==null && orderExpired` → `approval.expire`·`payment.expireUnpaid`)
**재현** `PaymentApprovalIntegrationTest#aPaidProviderPaymentAfterAnUnadmittedPendingExpiryIsHeldAsPaidWithoutAdmission` — 수정 전 known-defect Red, `expected: REVIEW_REQUIRED but was: EXPIRED`. 루프백 PG가 접수 전 `PAY_PENDING`을 돌려주면 승인은 `REQUESTED`·최신 PG 상태 `PENDING`으로 남는다. 주문 만료 통지 뒤 PG를 `PAID`로 바꾸고 작업자를 돌리면 PG 조회 0회로 승인 `EXPIRED`가 됐다. 지금은 태그를 뗀 회귀 테스트이고, 같은 클래스의 `anUnadmittedPendingObservationStillPendingAtOrderExpiryIsHeld`(PENDING 보류)·`anUnadmittedPendingObservationThatIsReadyAtOrderExpiryClosesAfterOneLookup`(READY 닫힘)·`anUnadmittedApprovalWithOnlyAFailedLookupStillClosesOnOrderExpiryWithoutPgTraffic`(관측 없음, PG 0회)가 함께 지킨다.

- **원인** 접수 전 만료 분기가 PG 조회(`inspect`)보다 앞에 있었다. 이 분기는 Payment R11.5("접수만 된 `REQUESTED` 작업은 PG 호출 없이 `EXPIRED`로 닫는다")를 구현한 것인데, 한 번 조회해 PG `PENDING`을 이미 본 승인도 같은 `REQUESTED`라 같은 분기를 탔다. 닫힌 뒤에는 승인이 종결되어 다시 조회하지 않았다. 웹훅도 `Payment.requestRecheck`가 `PENDING`이 아닌 결제를 무시하고 `ResolvePayment`가 `EXPIRED`를 건너뛰므로 늦은 PAID를 살리지 못했다.
- **수정** [결정 126](decisions.md#pre-admission-pending-expiry). `latestPgState`가 있는 접수 전 `REQUESTED` 승인은 만료로 닫기 전에 `ResolvePaymentApproval.expireAfterLookup`이 PG를 한 번 조회한다. PAID → `PAID_WITHOUT_ADMISSION` 보류, 전액 CANCELLED → `PG_CANCELLED` 보류(결정 115), PENDING → 새 사유 `PG_PENDING_AT_ORDER_EXPIRY` 보류, READY·FAILED → 기존처럼 `EXPIRED`. `latestPgState`가 없으면 R11.5대로 PG 호출 없이 닫는다. 보류 게이지 사유 목록(`ApprovalReviewReason`)과 경보 테스트(`infra/prometheus/tests/approval-review.yml`, warning)에 새 사유를 더했다. Red는 운영 분기를 잠깐 되돌려 3건 실패로 확인했다(결정 102).
- **실제 PortOne에서 생기는가** 어댑터(`PortOnePaymentApprovalGateway`)는 `PAY_PENDING`을 `PENDING`으로 매핑하고, 접수 전에는 우리가 `confirm`을 보내지 않는다. 그러므로 이 상태는 PG가 우리 confirm 없이 승인 대기로 간 경우다. 실제 PortOne이 그런 응답을 주는지는 저장소에 근거가 없다(payment `tasks.md`의 `[외부 대기]` 항목과 같은 미확인).

## D-045 뒤 월이 CLOSED인데 늦은 앞 월 매출·환불이 앞 월 OPEN 행을 만들어 판매자 과지급

**상태** 수정됨(결정 42·130 적용) · **영향** 8월 마감 → 10월 마감 뒤 9/30 확인 환불(판매자 −8,100)이 11/3에 도착하면 행이 없던 9월에 OPEN 행이 생겨 환불이 9월(`CONFIRMED_MONTH`)에 붙는다. 그러면 11월 마감은 "이전 월 OPEN" 409로 멈추고, 9월을 먼저 마감해도 11월은 출처가 10월이라 9월 −8,100을 받지 못해 8,100원을 과지급한다. 늦은 앞 월 매출도 같은 9월 행을 만든다 · **위치** `RecordPaidSale.record`·`RecordConfirmedRefund.record`의 `lockMonth`(원래 월 행이 없으면 바로 OPEN 행 생성, 뒤 월 CLOSED 미확인), `CloseSellerMonth.close`의 앞 월 OPEN 검사(잠그지 않는 읽기, 월 행 하나만 잠금)
**재현** `SettlementIntegrationTest#lateRefundOfAMonthWithoutRowBeforeALaterClosedMonthMovesToTheCurrentMonth`(`expected ["2026-09","2026-11","CLOSED_MONTH_ROLLOVER"] but was ["2026-09","2026-09","CONFIRMED_MONTH"]`), `#lateSaleOfAMonthWithoutRowBeforeALaterClosedMonthMovesToTheCurrentMonth`(반영 월 `2026-09`), 경합 2건 `#aLateEarlierMonthRefundWaitsForTheLaterCloseAndMovesToTheCurrentMonth`·`#aLaterCloseWaitsForAnEarlierMonthSaleAndThenRequiresThatMonthFirst`(상대가 기다리지 않음 `Expecting value to be true but was false`) — 수정 전 known-defect Red 4건. 지금은 태그를 뗀 회귀 테스트다.

- **원인** "원래 월이 CLOSED"만 마감 경계로 봤다. 결정 130은 행 없는 월을 건너뛰고 마감하므로, 뒤 월이 CLOSED라는 것은 그 앞 월을 이미 지나 마감했다는 뜻인데 반영 서비스는 이를 보지 않았다. 마감의 앞 월 OPEN 검사와 반영의 월 행 생성은 서로 다른 행을 잠가 커밋 전 행을 보지 못했다.
- **수정** `RecordPaidSale`·`RecordConfirmedRefund`가 원래 월 행을 잠그거나 만들기 전에 `existsBySellerSubjectAndStatusAndSettlementMonthGreaterThan(seller,"CLOSED",month)`를 확인하고, 참이면 원래 월 행 없이 현재 KST 월로 넘긴다(`CLOSED_MONTH_ROLLOVER`, `saleMonth`·확인 월 보존). 판매자 잠금 행(V14 `settlement_seller_lock`, `SellerLockRepository.lock` `PESSIMISTIC_WRITE`)을 반영·마감이 가장 먼저 잠가 판정과 행 생성을 직렬화한다. 운영 분기를 되돌린 확인(결정 102): 뒤 월 판정 제거 시 환불 2건·매출 1건 단언 실패, 판매자 잠금의 `@Lock` 제거 시 경합 테스트 6건이 `stillInsideAfter` 단언 실패.

## D-046 뒤 월 마감 뒤 앞 월 마감이 같은 출처 이월을 다시 받음

**상태** 수정됨(결정 130 적용) · **영향** 10월 carriedOut −9,000을 12월 마감이 받은 뒤 행 없는 11월 마감을 요청하면 0원 CLOSED 11월 행이 생기고 출처 10월의 −9,000을 다시 받는다. 앞 월에 늦은 매출이 있었다면 같은 음수를 두 번 빼서 판매자에게 덜 지급한다 · **위치** `CloseSellerMonth.close`(뒤 월 CLOSED 미확인, 행 없는 대상 월 생성), `CloseSellerMonth.carry`(출처 사용 여부 미확인), V13 원장에 `source_month` 유일 제약 없음
**재현** `SettlementIntegrationTest#closingAnEarlierMonthAfterALaterMonthClosedIsRejectedWithoutReusingTheCarry`(`expected: 409 but was: 200`), `#theCarryLedgerAcceptsEachSourceMonthOncePerSeller`(같은 판매자·같은 출처 두 번째 삽입이 성공) — 수정 전 known-defect Red 2건. 지금은 태그를 뗀 회귀 테스트이고, `SettlementMigrationIntegrationTest#v14AndV15SerializeSellersAndRejectAReusedCarrySourceWithoutChangingTheLedger`가 이행을 지킨다.

- **수정** `CloseSellerMonth`는 대상 월이 CLOSED가 아니고 뒤 월이 CLOSED면 409("이후 월이 이미 마감됐습니다")를 내고 행을 만들지 않는다. 이미 CLOSED인 대상 월의 재요청은 이 검사보다 먼저 최초 결과를 돌려준다(R3.4). 추가 방어선으로 V15가 `uk_seller_carryover_source (seller_subject, source_month)`를 둔다. 409 검사를 되돌리면 V15 유일 키가 두 번째 사용을 막아 재시도 뒤 500이 됐다(결정 102, DB 제약 예외로 인정). 그 경우 원장은 바뀌지 않았다.

<a id="d-047"></a>
## D-047 Store 최초 삽입 flush의 MySQL 교착(1213)이 재시도를 지나쳐 회차가 실패

**상태** 수정됨 · **영향** HTTP 정본 대조 회차 실패, Kafka 반영 지연과 ERROR 로그 · **위치** `ApplyCatalogProduct.execute`, `ReconcileCatalogProduct.execute`의 재시도 catch
**재현** `StoreIntegrationTest#eventInsertDeadlockVictimRetries`, `#reconciliationInsertDeadlockVictimRetries` — known-defect Red 2건. 둘 다 `jakarta.persistence.OptimisticLockException: org.hibernate.exception.LockAcquisitionException: … Deadlock found when trying to get lock`(MySQL 1213) · **발견 경로** PR #127 교차 리뷰 R3의 의심을 Store `tasks.md`에 남긴 항목

- **원인** 두 서비스는 새 상품 INSERT를 주입받은 `EntityManager.flush()`로 직접 실행한다. 이 호출은 Spring의 리포지토리 예외 변환을 거치지 않고, Hibernate가 교착(`LockAcquisitionException`)을 `jakarta.persistence.OptimisticLockException`으로 감싸 던진다. 재시도 catch는 Hibernate `ConstraintViolationException`·`LockAcquisitionException` 자체와 Spring `DataIntegrityViolationException`·`TransientDataAccessException`만 잡으므로, 감싼 예외는 최대 3회 새 트랜잭션 재시도를 지나 그대로 실패한다(리포지토리 호출·커밋 시 flush는 Spring이 `CannotAcquireLockException`으로 바꿔 잡힌다).
- **재현 방법** READ COMMITTED에서 두 작업을 "없음" 확인(`lockById`·`findByProductCode`) 뒤에 `PausePoints`로 세운다. 그 사이 세 번째 트랜잭션이 같은 상품 ID를 커밋하지 않은 채 삽입한다. 두 작업의 INSERT가 그 행의 공유 잠금을 기다리다(`performance_schema.data_lock_waits` 2건), 세 번째가 롤백되면 둘 다 배타 잠금을 요청해 교착이 나고 InnoDB가 한쪽을 1213으로 되돌린다(MySQL의 세 세션 중복 삽입 교착). 실제로 교착이 났는지는 `performance_schema.events_errors_summary_global_by_error`의 1213 발생 수로 확인한다.
- **운영 영향** 같은 상품을 세 곳(이벤트·초기 동기화·정본 대조)이 거의 동시에 처음 넣고 그중 하나가 롤백될 때만 생긴다. 이벤트 경로는 Kafka 기본 오류 처리기의 재배달(1·2·4초)로 결국 수렴하지만 지연과 ERROR 로그가 남는다. HTTP 대조는 그 회차가 실패로 끝나 다음 회차까지 사본 반영이 늦는다.
- **수정** 두 서비스의 재시도 catch를 `RuntimeException`으로 받고, 새 `RetryableWriteFailure.retryable`이 원인 사슬(최대 16단계)에서 기존 네 타입(Spring `DataIntegrityViolationException`·`TransientDataAccessException`, Hibernate `ConstraintViolationException`·`LockAcquisitionException`)을 찾을 때만 새 트랜잭션으로 다시 시도한다. 재시도 대상은 넓히지 않았고 감싼 예외만 찾는다. 업무 충돌(`BusinessException`)·원인 없는 `OptimisticLockException`(오래된 버전)은 그대로 실패한다. Order `CreateOrder`도 같은 catch 모양이지만 `saveAndFlush`(리포지토리)로 저장해 Spring 변환을 거치므로 해당하지 않는다.
- **망가뜨리기 확인(결정 102)** `retryable`이 원인 사슬을 보지 않게 바꾸면 두 재현 테스트가 같은 `OptimisticLockException(LockAcquisitionException)`으로 실패(Gradle exit 1)하고, 되돌리면 통과한다.

<a id="d-050"></a>
## D-050 환불 조회·재송신 작업자가 저장된 환불 추적 부모를 복원하지 않아 확정 뒤 흐름이 새 trace로 갈라짐

**상태** 수정됨 · **영향** 수정 전에는 첫 환불 송신의 응답이 유실되거나 PG가 5xx로 답해 작업자가 환불을 확정하면, 고객의 환불 요청 trace에는 PG 재조회·재송신과 그 뒤 Order `PurchaseRefunded`·License 환불 반영·Settlement 환불 조정이 없다. 업무 결과(주문 `COMPLETED`, 보유권 `REFUNDED`, 정산 환불 1건)는 맞다. 장애 뒤 환불을 Tempo에서 한 trace로 따라갈 수 없다([결정 28](decisions.md#28-내구-접수의-추적-부모를-저장하고-처리-시도마다-복원한다), Order 테스트 R32) · **위치** `RefundExecutionTraceContexts.find`(운영 코드 호출 0곳; `ReceiveRefundExecution`이 `refund_execution_trace`에 저장만 함), `ResolveRefundLookup`의 `executor` 작업·`ResolveRefundRetry`의 재송신(부모 복원 없이 실행), 그 안에서 적재하는 Payment `RefundPaymentConfirmed` Outbox
**재현** `python3 -B tools/verification/verify-refund-tracing.py`(흉내 PortOne + 격리 Tempo, 샘플링 1) 2026-11-09 2회 실행 모두 작업자 경로에서 같은 끊김(1회차는 지급 확인 대기를 넣기 전 도구). 정상 환불은 Order·License·Payment 환불 Outbox 6행과 PG 취소·조회 span이 환불 HTTP trace 하나에 있다. 응답 유실(`arm-cancel LOST_RESPONSE`, 첫 재조회 503)과 PG 오류(`arm-cancel PG_ERROR`)에서는 `refund_execution_trace`의 부모가 환불 trace인데도 작업자의 PG 호출이 span 1개짜리 새 root(`payment:http get`·`payment:http post`)가 되고, `RefundPaymentConfirmed.trace_parent`가 NULL, Order의 `PurchaseRefunded`가 `order:stove.payment.v1 receive` root의 새 trace에 생긴다. 근거는 실행 디렉터리 `runs/refund-tracing-*/refund-tracing.json`과 PR 본문.
재현 테스트 `RefundWorkerTracingIntegrationTest`(실제 Kafka·OTel 메모리 exporter·루프백 PG) — 수정 전 `known-defect` Red 2건: `#theLookupWorkerConfirmsALostResponseUnderTheSavedRefundParent`·`#theRetryWorkerResendsUnderTheSavedRefundParent` 모두 `[RefundPaymentConfirmed trace_parent] Expecting actual not to be null`. 지금은 태그를 뗀 회귀 테스트이고, 같은 클래스의 `#aRefundWithoutSavedParentIsConfirmedUnderANewRoot`가 부모 없는 옛 접수의 새 root 동작을 지킨다.

- **원인** 구매 결제는 `ResolvePayment`가 `PaymentTracing.run(traceContexts.find(paymentId), …)`로 처리 시도마다 저장 부모를 복원하지만, 환불 작업자에는 같은 감싸기가 없다. Kafka 리스너 스레드에서 끝나는 첫 송신만 소비 span 아래에 있어 이어진다.
- **수정** `ResolveRefundLookup`·`ResolveRefundRetry`가 executor 작업마다 `RefundExecutionTraceContexts.find(attemptId)`로 저장 부모를 읽고 `PaymentTracing.run` 안에서 PG 호출·결과 Outbox 적재를 한다(구매 `ResolvePayment`와 같은 포트·방식, 처리 시도마다 새 자식 span `payment.process`, 스케줄러 scope 비상속). 부모가 없거나 읽기에 실패하면 새 root로 진행하고 조회 lease 해제·재송신 claim은 그대로다. 수정 뒤 `verify-refund-tracing.py`가 세 경우 모두 통과했다(Outbox 18행 모두 환불 trace).
- **망가뜨리기 확인(결정 102)** 같은 재현 테스트를 수정 전 운영 코드로 돌리면 위 Red 2건, 수정 코드로 Green 2건이다.

<a id="d-051"></a>
## D-051 결제가 PAID로 확정된 뒤에도 접수된 보류 승인이 다음 보류 재조회까지 남아 고객에게 옛 안내가 나감

**상태** 수정됨 · **영향** 결제 `PAID`·주문 완료인 건의 고객 결제 상세와 승인 조회에 보류 안내가 남는다. 예: READY를 본 `CONFIRM_WINDOW_EXPIRED` 보류 건은 `NOT_CHARGED`("현재까지 청구된 금액은 없습니다. … 새 주문으로 다시 구매하실 수 있습니다")가 나가 돈이 나간 뒤 다시 구매하라고 안내한다(결정 123의 중복 구매 방지와 반대). PAID를 본 건이면 `CHARGED`("확인이 끝나면 청구된 금액은 취소되고 이 주문은 종료됩니다")가 나간다. 같은 시간 동안 ADMIN 보류 목록·보류 게이지(`REVIEW_REQUIRED` 기준)에도 남는다. 남는 시간은 다음 보류 재조회 간격(결정 122, 최대 10분)이다. 돈은 움직이지 않는다 · **위치** `ResolvePayment.confirm`(결제만 `PAID`로 바꾸고 승인은 건드리지 않음), `AdmittedPortOnePgGateway.charge`(`REVIEW_REQUIRED` 승인으로도 PAID 결과를 줌), `ResolvePaymentApproval.cycle`의 PAID 분기(승인을 닫는 유일한 곳)
**재현** `PaymentApprovalIntegrationTest#aHeldAdmittedApprovalClosesInThePaidPaymentCommitSoNoStaleHeldNoticeRemains`(루프백 PG·Order, 실제 MySQL) — 수정 전 known-defect Red 1건, `[approval status right after the PAID commit] expected: SUCCEEDED but was: REVIEW_REQUIRED`. 승인을 접수·첫 송신 503으로 둔 뒤 3시간 창에서 `CONFIRM_WINDOW_EXPIRED` 보류로 올리고(PG READY, 고객 안내 `NOT_CHARGED` 확인), PG를 PAID로 바꿔 결제 재조회(`ResolvePayment.execute(paymentId)`, `PendingPaymentScheduler`·웹훅 재조회 경로)를 돌리면 결제는 `PAID`·Outbox 1행인데 승인은 `REVIEW_REQUIRED`였다. 지금은 태그를 뗀 회귀 테스트이고, 같은 테스트가 고객 두 조회의 `reviewNotice` 없음, ADMIN 보류 목록 제외, 뒤이은 작업자 회차의 PG 조회 0회·송신 1회 유지·Outbox 1행을 함께 지킨다 · **발견 경로** 결정 143 1부 payment `design.md` 찾은 불일치 3(PR #182)

- **원인** 결제 `PAID`는 두 곳에서 커밋된다. 승인 작업자(`ResolvePaymentApproval.completeObservation`)는 `ResolvePayment.execute` 뒤 같은 회차에 승인을 `succeed`하지만, 결제 재조회 스케줄러와 웹훅(`Payment.requestRecheck` → 같은 스케줄러)은 결제만 확정한다. 그러면 승인은 다음 claim에서 `cycle`의 첫 분기(`PAID && admittedAt!=null` → `succeed`)를 만나야 닫히는데, 보류 승인의 다음 claim은 보류 기간에 따라 늘어나는 재조회 간격 뒤다. 그동안 `ApprovalReviewNotice.of`는 승인 상태 `REVIEW_REQUIRED`와 옛 `latestPgState`로 안내를 계산한다.
- **수정** `ResolvePayment.confirm`이 결제를 새로 `PAID`로 바꾸고 결과 Outbox를 적재한 그 트랜잭션에서, 같은 결제의 최신 `ADMITTED`·`REVIEW_REQUIRED` 승인을 잠그고(`purchase_payment` → `payment_approval`, 작업자와 같은 순서) 접수됐으면(`admittedAt` 있음) `PaymentApproval.succeed`한다. 규칙은 작업자의 PAID 분기와 같고 시점만 결제 커밋으로 당겼다. 접수 전 승인은 PortOne 게이트웨이가 거절하므로 여기 오지 않고 `PAID_WITHOUT_ADMISSION` 보류로 남는다. 작업자 쪽 분기는 그대로 두어 이미 쌓인 건(배포 전 PAID·보류)도 다음 회차에 닫힌다. 버린 안: 안내 조회가 결제 `PAID`를 먼저 보게 하는 것(고객 두 조회는 맞지만 ADMIN 목록·게이지·결제창 진행 판정이 계속 `REVIEW_REQUIRED`를 셈), PAID 커밋 뒤 승인 `nextAt`을 당겨 작업자를 깨우는 것(작업자 주기만큼 같은 모순이 남고 PG 조회가 한 번 더 듦).
- **망가뜨리기 확인(결정 102)** 같은 재현 테스트를 수정 전 운영 코드로 돌리면 위 Red 1건(Gradle `defectTest` 실패 1), 수정 코드로 Green 1건이다.

<a id="d-052"></a>
## D-052 License 복구·승인 정정이 모듈 간 시계 차이로 정상 요청을 거부해 보유권이 정지된 채 남음

**상태** 수정됨 · **영향** 수정 전에는 Order·License 호스트 시계가 어긋나면 PG 확정 실패 뒤의 복구(`LicenseRestorationRequested`)와 PG 송신 전 승인 정정(`LicenseApprovalCorrectionRequested`)이 공통 재시도 뒤 DLT로 가고, 구매 근거는 `SUSPENDED`, 보유권은 `SUSPENDED`로 남아 고객이 이미 산 상품을 내려받지 못한다. 시계 차이가 없으면 생기지 않는다 · **위치** `ApplyLicenseRestoration.execute`·`ApplyApprovalCorrection.execute`의 `input.occurredAt().isBefore(suspension.getSuspendedAt())`(→ `CONFLICT`), `LicenseRestoration.record`·`LicenseApprovalCorrection.record`의 `!at.isBefore(input.occurredAt())`(→ `INVALID_REQUEST`), V8 `chk_license_restoration_time`·V9 `chk_license_correction_time`(`restored_at >= request_occurred_at`)
**재현** 고정 `Clock`(`@MockitoBean`)과 실제 MySQL·Kafka 컨테이너. `LicenseRestorationIntegrationTest`·`LicenseApprovalCorrectionIntegrationTest`의 `#restoresWhenTheLicenseClockIsBehindTheOrderClock`(요청 `occurredAt` = License 처리 시각 + 5초)·`#restoresWhenTheOrderClockIsBehindTheLicenseClock`(요청 `occurredAt` = 정지 시각 − 5초) — 수정 전 `known-defect` Red 4건: `BusinessException: 복구 근거가 필요합니다.`·`복구 시각이 올바르지 않습니다.`(INVALID_REQUEST), `복구 요청의 환불·실제 정지 근거가 다릅니다.`·`승인 정정 대상의 실제 정지 근거가 다릅니다.`(CONFLICT). 지금은 태그를 뗀 회귀 테스트다.

- **원인** 요청 `occurredAt`은 Order 시계, `suspendedAt`·`restoredAt`은 License 시계다. 두 가드와 CHECK는 서로 다른 호스트 시계의 선후를 업무 규칙처럼 강제했다. 같은 정지에 대한 요청인지는 이미 `licenseId`·`suspensionRequestEventId`·`suspensionEventId`(정지 결과 eventId)·`refundId` 대조, `License.restore`의 현재 정지 ID 대조, 이력 테이블의 `suspension_request_event_id` 유일 키·FK로 확인하므로 시각 비교가 막는 낡은 요청은 따로 없다. 요구사항 R8-3("발생 시각의 모듈 간 선후관계나 수신 시각만으로 정상 입력을 거부하지 않는다")과 어긋난다.
- **수정** 두 서비스의 시각 비교와 두 기록 생성 가드의 시각 비교를 뺐다(마이크로초 정밀도 검사는 남김). V12 `V12__restoration_cross_module_time.sql`이 두 CHECK를 지운다. `request_occurred_at`(받은 값)·`restored_at`(License 처리 시각)의 저장 값·뜻은 바꾸지 않았다. 옛 행은 옛 CHECK를 이미 만족하므로 그대로다(`LicenseMigrationIntegrationTest#v12KeepsExistingRestorationAndCorrectionRowsAndAllowsRestoredAtBeforeTheOrderClock`).
- **망가뜨리기 확인(결정 102)** 운영 코드 수정 전에는 위 Red 4건. 수정 뒤 V12만 빼고 `#restoresWhenTheLicenseClockIsBehindTheOrderClock` 2건을 돌리면 `JpaSystemException: … Check constraint 'chk_license_restoration_time' is violated.`·`'chk_license_correction_time' is violated.`로 실패하고, 되돌리면 통과한다.

<a id="d-053"></a>
## D-053 Gateway가 하류 서비스가 막 닫는 유휴 연결(약 60초)을 다시 써 요청이 응답 전에 끊기고 500이 남

**상태** 원인 확인 · 미수정(재현 테스트 없음) · **영향** Gateway → 업무 서비스 연결이 약 60초 쉬다가 다시 쓰이는 순간 하류 Tomcat이 그 연결을 닫으면, Gateway가 `PrematureCloseException: Connection prematurely closed BEFORE response`를 받고 500을 돌려준다. 조회는 다시 부르면 회복되지만 `POST`(결제 승인 접수 등)는 클라이언트가 처리 여부를 알 수 없다. 돈·상태는 바뀌지 않았다(하류가 요청을 처리하기 전에 닫힘).
**발견** main 야간 예약 CI run 42916206138(`4227def0`, 2026-11-10 22:37 UTC)의 "fake PortOne approval expiry (31 min)". 최근 야간 5회 중 1회.

- **근거** 산출물 `approval-expiry-run`: `verify-expiry-journeys.py`의 대기 루프가 60초마다 주문·결제를 조회한다(`time.sleep(min(60, …))`). 두 번째 회차(22:44:13쯤)의 마지막 Order 요청 뒤 약 60초가 지난 세 번째 회차 첫 요청(22:45:13.4, `GET /api/v1/orders/ff8aa8e5-…`)에서 Gateway 로그에 `[152448c5-4, L:/127.0.0.1:47188 ! R:127.0.0.1:18082] … PrematureCloseException`, 이어 `500 Server Error`. Order 로그에는 그 시각 오류가 없고, 바로 뒤의 종료 기록은 실패 뒤 정리다.
- **원인(판독)** Gateway(Spring Cloud Gateway 4.3.0)의 `spring.cloud.gateway.server.webflux.httpclient.pool.max-idle-time` 기본값이 null(제한 없음)이고 `apps/gateway/src/main/resources/application.yml`에도 없다. 하류 서비스의 Tomcat 10.1.46은 keep-alive 유휴 시간이 `connectionTimeout` 기본값 60000ms(`org.apache.coyote.http11.Constants.DEFAULT_CONNECTION_TIMEOUT`)이고 각 서비스 `application.yml`에 Tomcat 설정이 없다. 조회 주기(60초)와 유휴 닫기(60초)가 겹쳐 Tomcat이 닫는 순간 Gateway가 그 연결을 고르는 짧은 경합에서만 실패한다.
- **수정 방향(미착수)** Gateway 풀 `max-idle-time`을 하류 유휴 닫기보다 짧게(예: 30초) 둔다. 재현 테스트: 유휴 K초를 넘긴 연결의 요청을 응답 없이 끊는 가짜 하류로 "요청 → K초 대기 → 요청"이 지금 500·수정 뒤 200(Red→Green). 설정 계약 테스트: Gateway `max-idle-time` < 하류 유휴 닫기(D-035 방식). 검증 스크립트에 재시도를 넣지 않는다(결정 113). 코드 PR은 Codex 교차 리뷰 대상.

<a id="d-054"></a>
## D-054 Kafka 시작 스크립트가 복사 중 실행돼 테스트 컨텍스트 시작에 실패

**상태** 수정됨 · **영향** 테스트 인프라(CI 불안정), 업무 코드 변경 없음 · **위치** `common/testcontainers` · [PR #198](https://github.com/tlswltjq/esd-platform/pull/198)

- **실측** [PR #196 CI run 43001134598](https://github.com/tlswltjq/esd-platform/actions/runs/43001134598)의 `PaymentAttemptFailureIntegrationTest#v28PreservesOriginalPurchasesAndSchedulesOnlyReadOnlyHistoryWithoutInventingAttempts`가 컨텍스트 초기화 중 실패했다. Kafka stderr는 `sh: /tmp/testcontainers_start.sh: Text file busy`, 종료 코드는 126이었다. 3분 준비 대기 뒤 보고됐으며 D-043의 단순 시작 지연과 구분한다.
- **원인** Testcontainers 1.21.4의 `KafkaHelper.COMMAND`는 시작 파일의 존재만 기다린 뒤 실행한다. `ConfluentKafkaContainer.containerIsStarting`은 같은 경로에 Docker archive로 스크립트를 복사한다. 파일이 보이는 시점과 쓰기가 닫히는 시점 사이에서 실행하면 열린 쓰기 FD 때문에 실행이 거부된다.
- **재현** `KafkaStarterPublicationIntegrationTest#executingAFileOpenForWritingFailsWithTextFileBusy`(전제 확인, 수정 코드를 거치지 않음): FIFO로 제어하는 별도 셸이 실행 파일의 쓰기 FD를 보유한 상태에서 실행해 126·`Text file busy`를 확인한다. `#kafkaStartsOnlyAfterTheStarterCopyHasClosedItsWriter`: 실제 Kafka의 복사 후 훅에서 실행 경로가 아직 없음을 확인하고, 임시 파일의 inode를 기록한 뒤 쓰기 FD를 잡아 같은 거부를 확인하고 닫는다. 기동 뒤 archive 복사 목적지가 임시 경로 한 번뿐이고(실행 경로로는 복사하지 않음), 실행 경로의 inode가 임시 파일과 같고 임시 파일이 남지 않아야 하며(이름 변경으로 게시), 실제 브로커 기동·내장 CLI 조회가 성공해야 한다. 전송 속도·러너 부하를 확률적으로 반복하는 대신 파일 가시성·복사 목적지·inode·쓰기 FD를 제어한다.
- **수정** `AtomicStarterKafkaContainer`가 시작 파일만 같은 디렉터리의 `.copying` 이름으로 복사하고 복사 반환 후 `mv`로 실행 경로에 원자적으로 게시한다. Docker가 root 소유 파일을 만들고 `/tmp`에 sticky bit가 있으므로 이 고정 경로의 이름 변경만 `ExecConfig.user("root")`로 실행한다. Kafka 본체의 실행 사용자·이미지·리스너 설정·준비 로그·3분 대기는 유지한다. 다른 경로의 복사는 기본 구현에 위임한다. 모든 archive 복사는 `copyArchive`를 거치므로 테스트가 목적지를 확인할 수 있다.
- **Red → Green·반례** 직접 실행 경로에 복사하면 `consumer path ... expected: 1 but was: 0`으로 실패한다. 재현 상태는 `eca046a1`에 `known-defect` 태그로 보존했다. 원자 게시를 제거한 반례도 같은 단언에서 실패한다. 게시를 `mv` 대신 `cp`로 바꾼 반례는 실행 경로가 새 inode라 `Publication must rename the staged copy` 단언에서 실패한다. 게시 뒤 실행 경로로 한 번 더 복사한 반례는 `The starter must be copied once, to the staging path only` 단언에서 실패한다. 원복 후 태그를 제거한 기본 검사가 통과한다. 최종 로컬 검증은 근거 조각과 PR 본문에 기록한다.
- **운영 범위** 공유 테스트 픽스처에만 적용한다. 실제 운영 Kafka나 결제 상태·금융 동작은 바꾸지 않는다.

---

## 닫힌 항목

### 결함이 아니었던 것

**게이트웨이 actuator 노출** — `exposure.include`에 `gateway`가 있어 라우트 목록과 `refresh`가 인증 없이 열렸다고 보고 재현 테스트를 먼저 썼는데 통과했다. Spring Cloud Gateway 4.x는 `management.endpoint.gateway.enabled` 기본 `false`, `access` 기본 `none`이다. 라이브러리 기본값이 막고 있으므로 `GatewayActuatorExposureTest`로 남겨 누군가 켜면 걸리게 했다.

### 결번

- **D-005** — 작성 중 철회. "DEAD 전이를 관측할 수 있어야 한다"는 [D-003](#d-003)의 일부라 합쳤다. 번호는 재사용하지 않는다(기존 커밋·주석의 참조가 흔들린다).
