# 아키텍처와 설계 검토

[문서 목록](README.md) · [도메인·모델 안내](domain/model-guide.md) · [설계 결정](decisions.md)

업무 대상과 속성은 모델 사전에서, 서비스 간 API·이벤트는 [서비스 명세](services.md)에서 찾는다. 공통 라이브러리의 책임과 의존성은 [공통 모듈](common-modules.md)에 있다. 이 문서는 데이터 변경과 이벤트 전달을 어떻게 나누는지 설명하고, 설계 검토에서 나온 고유한 실험·위험 근거를 모은다.

## 서비스 경계와 저장소

재작성 완료 모듈은 Auth·Gateway·Studio·Review·Catalog·Store다. Auth는 계정·역할, Studio는 출시용 상세정보·빌드와 심사·출시 요청 기록의 원본을 관리한다. Review는 제출 스냅샷과 수동 판정의 원본을 관리하고, Gateway는 외부 요청의 진입점이다. Studio의 책임에는 실제 심사 판정·출시·공개 상품 관리가 포함되지 않는다.

Catalog는 승인된 출시 요청으로 공개 상품·공개본의 정본을 관리하고 Store는 공개 상품의 검색·조회 사본을 관리한다.
나머지 모듈은 재작성 미완료이며 다음 구분은 기존 구현의 참고다. Order는 주문, Payment는 결제, License는 이용권, Settlement는 정산의 원본을 관리한다. Download는 공개 파일 목록과 이용권 사본으로 조회·접근을 처리한다. 미완료 모듈의 구현은 새 설계와 검증의 기준으로 삼지 않는다.

MySQL을 쓰는 서비스는 각자의 스키마를 소유하고 Flyway로 변경한다. 같은 MySQL 프로세스를 사용해도 서비스 간 테이블을 직접 조인하거나 하나의 트랜잭션으로 묶지 않는다. Store도 전용 MySQL 스키마를 사용한다. Store 사본은 Catalog 이벤트와 시작 시 공개 HTTP 조회로 구성하며 버전 비교·저장을 같은 트랜잭션에서 처리한다. Download의 MongoDB 등은 미완료 모듈의 기존 조회 저장소다. 원본과 사본 사이에는 이벤트 반영 지연이 있다.

일반적인 서비스의 `core`에는 모델·Spring Data 리포지토리·업무 처리·외부 기능의 포트가 있다. `api`는 HTTP·Kafka·스케줄러 진입점과 처리 단계를 조율하는 파사드를, `infrastructure`는 외부 시스템 어댑터를 담는다. 외부 PG·저장소·심사·세금계산서 연동은 포트 구현을 교체한다. 실제 연동과 mock의 범위는 [서비스 명세](services.md), 모듈 경계의 이유는 [설계 결정](decisions.md)에 있다.

## 트랜잭션과 이벤트 전달

`core.service`의 업무 클래스 명명과 책임 분리는 [유스케이스 작성 규칙](use-case-conventions.md)을 따른다.

| 단계 | 저장하거나 처리하는 것 | 이 단계가 끝나도 남아 있는 일 |
|---|---|---|
| 업무 트랜잭션 | 도메인 변경과 `OutboxEvent`를 같은 DB 트랜잭션으로 저장 | Kafka 발행과 다른 서비스의 반영 |
| Outbox 릴레이 | 발행할 행을 조회하고 Kafka 전송 결과를 기록 | 재전달 가능성, 구독자의 처리 |
| 소비자 트랜잭션 | 이벤트로 도메인을 변경하고 `ProcessedEvent`를 기록 | 그 서비스가 발행한 후속 이벤트의 전파 |
| 조회 | 원본 또는 그 서비스에 반영된 사본을 반환 | 다른 서비스의 상태와 반영 시점이 다를 수 있음 |

DB 커밋과 Kafka 발행은 한 번의 원자적 작업이 아니다. 발행 확인 후 DB 기록 전에 중단되면 같은 이벤트가 다시 전달될 수 있다. Inbox는 같은 `eventId`의 중복을 막고, 도메인의 상태 검사·유니크 제약은 서로 다른 이벤트가 같은 업무를 반복하는 경우를 막는다. 자세한 속성은 [공통 모델](domain/models/shared.md)에 있다.

같은 키의 정상 흐름에서는 릴레이와 Kafka 파티션이 순서를 지킨다. DEAD 회수·DLT 재투입은 로그 뒤에 새로 보내므로 원래 업무 순서를 복원하지 않는다. 릴레이 인스턴스 수 등의 조건은 [이벤트 순서](event-ordering.md), 관련 위험은 [R2](#r2)·[R13](#r13)을 따른다.

## 결제·지급·환불의 경계

주문은 Catalog 견적을 고정하고, Payment는 검증된 공급자 승인으로 결제 완료를 기록한다. 이용권 지급·주문 확정·정산 기록은 결제 이벤트를 각각 처리한다. 따라서 결제 성공 응답 직후 이용권이나 다운로드 사본이 아직 없을 수 있다. 실제 흐름은 [구매·이용 시나리오](domain/workflows.md#purchase-flow)를 참고한다.

지급이 최종 실패하면 보상 이벤트로 환불을 요청한다. 환불은 처리 의도를 기록하고 PG를 호출한 뒤 완료를 기록하는 단계로 나누며, 중단 후 재시도할 수 있어야 한다. 보상은 이미 완료된 외부 처리를 다른 업무로 되돌리는 것이므로 하나의 DB 롤백과 다르다. [결제 모델](domain/models/order-payment.md)과 [재시작 시나리오](resilience-scenarios.md)에 상태와 검증 근거가 있다.

<a id="tracing"></a>
## 요청과 비동기 처리 추적

HTTP 응답의 `X-Correlation-Id`는 trace ID다. Outbox에 추적 컨텍스트를 보존하고 Kafka 헤더로 전달해 HTTP 요청과 후속 소비를 Tempo에서 연결한다. 결제 승인 → `PaymentCompleted` 소비 → `LicenseIssued` 소비가 여러 서비스에 걸친 한 흐름으로 보인다.

기존 관측 예시에서 소비 시작까지 `+527ms`, 후속 전달까지 `+759ms`가 보였던 것은 DB 처리 시간만이 아니라 릴레이 폴링 대기가 포함된 결과다. 2026-08-13 폴링 주기를 1000ms에서 200ms로 줄인 측정에서는 종단 p95가 1.30초에서 0.56초로 줄었다. 원자료와 CPU 비용은 [성능 측정](performance.md)에 있고, 이 수치는 현재 스택을 새로 측정한 값이 아니다.

## DB 실험으로 확인하는 범위

[DB 락 실험](../scripts/db-lab/README.md)은 MySQL SQL 세션을 겹쳐 여섯 상황을 재현한다. RR·RC의 갭 락 차이, 실행 계획에 따른 잠금 범위, 락 대기 시간 초과, 만료·결제 경합의 갱신 유실, 동시 Inbox 삽입, 유니크 키 삽입의 데드락이다. SQL 실험의 재현과 전체 애플리케이션 경로의 재현은 구별한다.

실제 주문 부하에서 격리 수준만 바꾼 결과는 [성능 측정의 A/B](performance.md#isolation-ab)에 있다. 현재 코드에서 개선 여부를 판정하려면 동일 조건으로 다시 검증해야 한다.

## 설계 위험과 근거

아래 R1~R15는 **2026-09-14, `main`의 `1a182b8` 분석 기록**을 옮긴 것이다. 코드 위치·기본값·서버 구성·미해결 여부는 모두 그 시점의 관찰이며, 이번 문서 통합 시점의 현재 상태를 다시 검증한 목록이 아니다. 아래의 “현재”도 분석 당시를 가리킨다. 이후 업무 정책은 [미결 질문](domain/open-questions.md), 재현·수정된 결함은 [결함 기록](defects.md)을 먼저 대조한다.

`확인됨`은 당시 코드·설정·실험·라이브러리 소스로 확인한 사실, `코드상 추정`은 실제 상황을 재현하지 않은 추론, `추가 확인 필요`는 운영 확인이나 정책 결정이 남은 항목이다. `권장 개선안`은 적용 완료를 뜻하지 않는다. 실험 번호는 [DB 락 실험](../scripts/db-lab/README.md)의 번호다. 본문의 `code-notes` 인용은 [분석 당시 코드 설명](https://github.com/tlswltjq/esd-platform/blob/1a182b8/docs/code-notes.md)을 가리킨다.

| ID | 문제 | 영역 | 영향 | 발생 조건 | 라벨 |
|---|---|---|---|---|---|
| [R1](#r1-릴레이가-갭-락을-쥔-채-kafka-ack-를-기다린다) | 릴레이가 갭 락을 쥔 채 Kafka ack 를 기다린다 | DB · Kafka | 평시 쓰기 p95 +4.7ms, **브로커 장애 시 주문·결제 API 실패** | 평시 상시(작게), 브로커 지연·장애 시 크게 | 락·평시 지연은 확인됨(OCI A/B) / 장애 시 영향은 코드상 추정 |
| [R2](#r2-dlt-재투입과-dead-회수가-같은-키의-순서를-뒤집는다) | DLT 재투입·DEAD 회수가 같은 키의 순서를 뒤집는다 | Kafka | **환불된 주문에 라이선스 지급, 환불된 매출이 정산에 남음** | 지급 실패 → 사용자 환불 → 재투입 | 코드상 추정 |
| [R3](#r3-주문-만료-스윕과-결제-확정의-lost-update) | 주문 만료 스윕과 결제 결과가 경합한다 | DB | 주문 상태가 결제·지급과 어긋남 (조용히 또는 DLT) | order 소비 지연 15분+, 늦은 PG 콜백, DEAD·DLT 회수 | Lost Update 는 확인됨 / 발생 경로는 코드상 추정 |
| [R4](#r4-외부-호출이-트랜잭션-안에-남은-두-자리) | 외부 호출이 트랜잭션 안에 남은 두 자리 | DB · 외부 연동 | 커넥션 점유, PG 잔여 거래, 이중 접수 | 실연동 후 | 코드상 추정 (지금은 스텁) |
| [R5](#r5-paymentcancelled-가-다른-eventid-로-두-번-나갈-수-있다) | `PaymentCancelled` 가 다른 eventId 로 두 번 나갈 수 있다 | Kafka | 하류는 흡수, PG 중복 호출·관측 흐림 | 동시 환불 요청, 만료 승인 환불과 스윕 겹침 | 코드상 추정 |
| [R6](#r6-license-는-dlt-발행이-실패하면-레코드를-버린다) | license 는 DLT 발행이 실패하면 레코드를 버린다 | Kafka | 결제 완료 이벤트 유실 → 지급 누락 | 컨슈머는 읽히는데 DLT 발행만 실패 | 동작은 확인됨 / 발생은 드묾 |
| [R7](#r7-브로커-1대-복제-계수-1-토픽-자동-생성) | 브로커 1대 · 복제 계수 1 · 토픽 자동 생성 | Kafka 운영 | 브로커 디스크 유실 = 미소비 이벤트 유실 | 로컬·CI 구성 | 확인됨 / 운영은 추가 확인 필요 |
| [R8](#r8-outbox-와-inbox-테이블이-줄지-않는다) | outbox · inbox 테이블이 줄지 않는다 | DB | 디스크·백업, **릴레이 실행 계획 변화** | 시간이 지나면 반드시 | 확인됨 |
| [R9](#r9-dlt-보존-기간이-재투입-기한이다) | DLT 보존 기간이 재투입 기한이다 | Kafka 운영 | 7일 안에 재투입하지 않으면 영구 유실 | 긴 장애·휴가철 | 보존 기간은 확인됨(OCI 브로커) |
| [R10](#r10-커넥션-총량이-스케일아웃을-막는다) | 커넥션 총량이 스케일아웃을 막는다 | DB | 인스턴스 2배면 "Too many connections" | 스케일아웃 | 확인됨 |
| [R11](#r11-재시도해도-성공할-수-없는-실패도-네-번-시도한다) | 성공할 수 없는 실패도 네 번 재시도한다 | Kafka | 파티션 7초+ 정지, DLT 지연 | 계약 위반·도메인 거절 | 확인됨 |
| [R12](#r12-정산-귀속-월이-이벤트-시각이-아니라-소비-시각이다) | 정산 귀속 월이 소비 시각 기준이다 | 도메인 · Kafka | 월말 결제가 다음 달로 | 월말 소비 지연 | 확인됨 / 정책은 추가 확인 필요 |
| [R13](#r13-릴레이-1대-제약이-강제되지-않는다) | 릴레이 1대 제약이 강제되지 않는다 | Kafka | 스케일아웃 시 **순서 보장이 조용히 무효** | 서비스 2대 이상 | 확인됨 |
| [R14](#r14-eager-리밸런싱과-한-그룹의-여러-리스너) | Eager 리밸런싱, 한 그룹에 여러 리스너 | Kafka | 배포마다 그룹 전체 정지, 재처리 증가 | 배포·재시작 | 확인됨(OCI 브로커: 조회한 여섯 그룹 모두 `range`) |
| [R15](#r15-주문-생성-api-에-멱등키가-없다) | 주문 생성 API 에 멱등키가 없다 | API | 재시도가 중복 주문 생성 | 클라이언트 타임아웃 후 재시도 | 확인됨 |

---

<a id="r1"></a>
### R1. 릴레이가 갭 락을 쥔 채 Kafka ack 를 기다린다

**[분석 당시 구현의 잠재적 문제]**

**무엇이** — 릴레이의 배치 트랜잭션에는 격리 수준이 지정돼 있지 않아(`MessagingAutoConfiguration.java:90-91`, `new TransactionTemplate(transactionManager)`) MySQL 기본 REPEATABLE READ 로 돈다.
그 트랜잭션 안에서 잠금 조회(`OutboxRelay.java:56`) → Kafka 전송(`:84`) → **타임아웃 없는 ack 대기**(`:119`) → SENT 표시 → 커밋이 차례로 일어난다.

**근거** [확인됨 — 실험 1·2·3]

| 조건 | 결과 |
|---|---|
| RR, 평시 모양(SENT 10만 + PENDING 5), 릴레이가 6초 대기 | `idx_outbox_pending` 에 넥스트키 락 6개, 주문 트랜잭션의 outbox INSERT **4.38초 대기** |
| RC, 같은 데이터 | 레코드 락만, INSERT 대기 **0초** |
| RR, 적체(SENT 10만 + PENDING 5천) | `LIMIT 200` 인데 넥스트키 5,007 + PK 5,001 락, INSERT 3.33초 대기 |
| RR, 릴레이 대기 > INSERT 의 lock wait 한도 | INSERT 가 **ERROR 1205** 로 실패 |

새 outbox 행 `('PENDING', NULL, 새 id)` 가 들어갈 자리가 릴레이가 잠근 갭 안이라서, 릴레이가 커밋할 때까지 **outbox 에 쓰는 모든 트랜잭션**이 기다린다 —
주문 생성, 결제 승인·거절, 환불 확정, 그리고 다음 이벤트를 적재하는 컨슈머(지급, 심의, 상품 반영).

**평시 영향:** 2026-09-14 OCI A/B에서 RR 대비 RC의 주문 쓰기 p95가 약 4.7ms 낮고 행 락 대기가 사라졌다. 회차별 수치·통제 조건·해석 한계는 [성능 측정의 격리 수준 A/B](performance.md#isolation-ab)에 보존한다.

**브로커 장애 시 영향** **[코드상 추정 — 락과 타임아웃 동작은 실험으로 확인]**

```text
브로커 응답 없음
 릴레이 Tx R   잠금 조회 → send() → ack.get() …… delivery.timeout.ms(120초) 뒤 실패 → markFailed → 커밋 → 곧 다음 배치
 결제 승인 Tx  SELECT … FOR UPDATE(결제 행) → INSERT outbox ── 갭 락 대기 ── 50초 → ERROR 1205 → 롤백 → HTTP 500
               대기하는 동안 결제 행 락과 커넥션을 쥐고 있다 → 같은 주문의 재전송 콜백도 줄 선다, 풀 20 이 금방 찬다
```

Outbox 를 고른 이유 중 하나가 **"브로커 장애가 쓰기 API 장애로 번지지 않게"** 인데, 지금 구현에서는 그 격리가 락을 통해 샌다.
기존 장애 실험(chaos.md)은 DB 권한만 끊었고 브로커는 멈춰 본 적이 없어서 드러나지 않았다.

**[권장 개선안]**

| 안 | 내용 | 대가 |
|---|---|---|
| 1. 릴레이만 READ COMMITTED | `TransactionTemplate#setIsolationLevel(ISOLATION_READ_COMMITTED)` — 갭 락이 사라진다(실험 1) | 거의 없다. 릴레이는 한 트랜잭션에서 같은 행을 두 번 읽지 않는다. JPA 트랜잭션 매니저가 커넥션에 격리 수준을 적용하는지 테스트로 확인할 것 |
| 2. 대기에 상한 | `ack.get(timeout)` 또는 짧은 `delivery.timeout.ms` | 느린 브로커에서 실패 판정·재발행이 늘어난다 |
| 3. 트랜잭션 분리 | 짧은 트랜잭션으로 `PENDING → IN_FLIGHT(임대 만료 시각)` 커밋 → 트랜잭션 밖에서 발행 → 짧은 트랜잭션으로 결과 기록 | 임대 만료 회수 로직, 웨이브·`holdUntil` 순서 보장을 새 상태로 다시 검증 |
| 4. CDC 로 발행기 교체 | 잠금 조회 자체가 없어진다. R13 도 함께 풀린다 | Kafka Connect 운영 |

가장 싸고 효과가 큰 것은 1 이고, 브로커 장애 격리까지 완전히 하려면 3 이나 4 가 필요하다.

---

<a id="r2"></a>
### R2. DLT 재투입과 DEAD 회수가 같은 키의 순서를 뒤집는다

**[분석 당시 구현의 잠재적 문제]**

**무엇이** — 파티션 키와 키 웨이브가 지키는 순서는 **"한 번에 흘러갈 때"** 의 순서다. 실패한 메시지를 옆으로 뺐다가 나중에 되돌리는 두 경로에서 그 순서가 뒤집힌다.

| 경로 | 뒤집히는 이유 | 코드 |
|---|---|---|
| 컨슈머 DLT 재투입 | 재시도를 소진한 레코드를 DLT 로 보내고 **같은 키의 다음 레코드를 계속 처리**한다. 재투입은 원본 토픽의 **맨 끝**에 붙는다 | `KafkaConsumerAutoConfiguration`, `KafkaErrorHandlerConfig.java:67-79`, `DltOpsService.java:104-114` |
| Outbox DEAD 회수 | 앞 이벤트가 DEAD 가 되면 `holdUntil` 이 같은 키의 뒤 이벤트를 풀어 준다. 뒤 이벤트가 먼저 나간 뒤 DEAD 를 회수하면 늦게 나간다 | `OutboxEvent.java:138-146`, `OutboxOpsService` |

그리고 **뒤집힌 순서를 받는 컨슈머 둘이 사고를 조용히 삼킨다** — D-013 이 적은 바로 그 모양이다.

- `LicenseService.revoke()` 는 회수할 라이선스가 없으면 조용히 끝나고 Inbox 마킹은 커밋한다(`LicenseService.java:73-76`).
- `SettlementRecordService.recordRefund()` 는 역산할 매출이 없으면 경고 한 줄로 끝나고 역시 마킹한다(`SettlementRecordService.java:68-71`).
- 뒤늦게 온 `issue()`·`recordSale()` 은 **그 주문이 이미 환불됐다는 사실을 모른다.**

**장면** **[코드상 추정 — 재현 테스트 없음]**

```text
license 그룹                                               settlement 그룹도 같은 모양
 PaymentCompleted(E1)  저장소 장애로 4회 실패                 recordSale(E1) 실패 → DLT
   → 관문 1(저장소 장애) → DLT 보류, 오프셋 전진
 사용자: "결제했는데 게임이 없다" → 환불 요청 → CANCELED
 PaymentCancelled(E2)  revoke(): 라이선스 없음 → 조용히 종료   recordRefund(E2): SALE 없음 → warn, 종료
 운영자: 원인 해결 → DLT 재투입 (E1 이 토픽 끝에 붙는다)
 PaymentCompleted(E1)  issue(): Inbox 에 E1 없음 → 지급       recordSale(E1): SALE 적재
                       → LicenseIssued → download 권한 부여
최종: 결제 환불 완료 + 라이선스 ACTIVE + 다운로드 가능          판매자 정산에 환불된 매출이 남는다 — 금전 사고
```

**왜 가능성이 낮지 않은가** — D-027 이후 저장소 장애로 인한 지급 실패의 결말이 "자동 환불"에서 **"DLT 보류 후 사람이 재투입"** 으로 바뀌었다.
보류된 동안 사용자가 보는 것은 "결제했는데 게임이 없다"이고, 그때 가장 자연스러운 행동이 환불 요청이다. 운영자는 절차대로 원인을 고친 뒤 재투입한다.
기존 `DeadLetterReplayRecoveryTest` 는 "보류 → 복구 → 재투입 → 지급"을 확인하지만 **그 사이에 환불이 끼는 경우는 없다** [확인됨 — 테스트 이름과 절차].

**문서와의 차이** — code-notes `DltOpsService` 는 "키가 그대로라 원래 파티션으로 가므로 같은 애그리거트의 순서 보장이 재투입에서도 유지된다"고 쓰고,
decisions.md 19번은 DLT 를 "맞바꿈이 아니라 순수 개선"이라고 쓴다. **파티션은 같아도 로그 안의 위치는 뒤로 간다.**
유실을 막는 데는 순수 개선이 맞지만, 파티션을 막지 않는 대가로 그 키의 순서를 내준 맞바꿈이다.

**[권장 개선안]**

| 안 | 내용 | 대가 |
|---|---|---|
| 1. 절차 (당장) | 재투입 전에 DLT 레코드의 orderNo 로 결제 상태를 확인하고, CANCELED 면 재투입하지 않고 대사로 처리. DEAD 회수도 같은 키의 뒤 이벤트가 이미 SENT 인지 확인 | 사람이 판단한다 — 바쁠 때 빠진다 |
| 2. 소비 측 보류 기록 | license: 회수가 왔는데 대상이 없으면 "회수 예약"을 남기고, 지급 때 예약이 있으면 지급하지 않는다. settlement: SALE 없는 REFUND 를 보류로 적어 두고 SALE 이 오면 함께 상계 (event-ordering.md C-3) | 테이블과 분기가 는다 |
| 3. 키 단위 파킹 | 한 키의 레코드가 DLT 로 가면 `(그룹, 키)` 를 파킹 목록에 올리고, 같은 키의 후속 레코드도 처리하지 않고 DLT 로 보낸다. 재투입은 키 단위로 순서대로 | 파킹 목록 관리, 그 주문 전체가 늦어진다. 발행 측 키 웨이브(D-013)와 같은 설계 언어라 이 저장소에 어울린다 |

---

<a id="r3"></a>
### R3. 주문 만료 스윕과 결제 확정의 Lost Update

**[분석 당시 구현의 잠재적 문제]**

**무엇이** — 주문의 `CREATED` 이후 상태는 **두 가지 힘**이 바꾼다. 결제 결과 이벤트(`confirmPaid`·`confirmCanceled`·`confirmFailed`)와 시간(`OrderExpirySweeper`).
둘 다 잠그지 않고 읽어 JPA 로 쓰고(`OrderExpiryService.java:37-50`, `OrderCommandService.java:60-84`), `@Version` 이 없다 [확인됨].
그리고 `markPaid`·`markFailed` 는 `CREATED` 에서만, `cancel` 은 `CREATED`·`PAID` 에서만 열린다(`Order.java:103-142`).

**세 가지 모습**

| | 순서 | 결과 | 라벨 |
|---|---|---|---|
| (a) 동시에 겹침 | 스윕이 CREATED 를 읽음 → 결제 확정 커밋 → 스윕이 UPDATE | 에러 없이 `EXPIRED`, `paid_at = NULL` — **조용한 Lost Update** | [확인됨] 실험 4 |
| (b) 만료가 먼저 | 만료 커밋 → `PaymentCompleted` 도착 → `markPaid` 가 CONFLICT | 네 번 시도 → DLT. 결제 PAID · 라이선스 지급 · 정산 매출은 반영, **주문만 EXPIRED** | [코드상 추정] |
| (c) 만료 뒤 결제 실패·자동 환불 | 만료 커밋 → `PaymentFailed` 또는 결제창 만료 환불의 `PaymentCancelled` 도착 | `markFailed`·`cancel` 이 EXPIRED 를 거부 → DLT | [코드상 추정] |

(c) 는 문서의 전제가 시간이 지나 무너진 경우다. D-029 후속과 code-notes `handleApproval` 은 만료 뒤 승인의 `PaymentCancelled` 를 두고 "order 는 CREATED 에서 취소된다"고 적었는데,
주문 만료(#43)가 나중에 들어오면서 그 주문은 이미 `EXPIRED` 일 수 있게 됐다.

**창은 언제 열리나** — 평시에는 결제 시작 창 30분 + 결제창 15분 = 45분이 만료 60분보다 짧아 열리지 않는다.
열리는 조건은 **order 컨슈머가 15분 넘게 밀리거나 멈춤**, **PG 콜백이 늦게 재전송됨**(결제 서버가 오래 죽어 있었을 때), **DEAD 회수나 DLT 재투입으로 결제 이벤트가 몇 시간 뒤 도착**하는 경우다.

**문서와의 차이** — `V7__shedlock.sql`(order)과 code-notes `SchedulerLockConfig (order)` 는 "같은 행을 두 인스턴스가 집으면 하나는 CONFLICT 로 튕긴다"고 쓴다.
실제로는 두 스윕 모두 스냅샷으로 `CREATED` 를 읽고 둘 다 UPDATE 에 성공한다(실험 4 와 같은 원리) — 결과가 같은 `EXPIRED` 라 해가 없을 뿐, 튕기지는 않는다.
기존 `OrderExpiryServiceTest#paidOrderIsNotExpirable` 은 결제 확정 **뒤에** 스윕을 부르는 순차 테스트라 경합을 보지 않는다.

**[권장 개선안]**

| 안 | 내용 | 대가 |
|---|---|---|
| 1. 조건부 UPDATE | 만료를 `UPDATE orders SET status='EXPIRED', expired_at=? WHERE id IN (…) AND status='CREATED'` 로 — 영향 행 수로 판정 (실험 4 의 대안) | JPQL 벌크 UPDATE 는 영속성 컨텍스트를 우회한다 |
| 2. `@Version` | 경합 시 늦은 쪽이 낙관적 락 예외 → 컨슈머는 재시도에서 최신 상태로 다시 판단, 스윕은 다음 회차로 | 상태를 바꾸는 모든 경로가 충돌 예외를 다뤄야 한다 |
| 3. 정책 결정 | 만료 뒤 도착한 결제 결과를 어떻게 볼지 정한다 — "돈이 움직였으면 주문을 되살린다(EXPIRED → PAID·CANCELED 허용)" 또는 "만료가 우선, 결제를 되돌린다" | **[추가 확인 필요]** 비즈니스 결정. 지금은 둘 다 아니고 DLT 에서 멈춘다 |

---

<a id="r4"></a>
### R4. 외부 호출이 트랜잭션 안에 남은 두 자리

**[분석 당시 구현의 잠재적 문제]** — 지금은 둘 다 스텁이라 영향이 없다. **실제 PG·게임위를 붙이는 날 드러난다.**

이 저장소는 되돌릴 수 없는 호출(PG 환불, 세금계산서)을 트랜잭션 밖으로 뺐지만, 두 자리가 남았다.

**`PaymentService.prepare`** (`PaymentService.java:69-79`, 클래스 수준 `@Transactional` `:39`)

```text
[TX] SELECT payment (잠그지 않음)
     requireWithinWindow()          — 창 검사
     pgClient.prepare()   ← PG 호출이 트랜잭션 안
     payment.prepare()    ← 상태 검사(READY/PENDING 만)는 PG 호출 **뒤**
     COMMIT
```

1. PG 지연만큼 커넥션을 쥔다. PG 가 3초씩 걸리면 사전등록 20건이 풀 20 을 채우고, 같은 풀을 쓰는 **승인 콜백까지** 커넥션을 못 얻는다.
2. 상태 검사가 PG 호출 뒤라, 이미 PAID·CANCELED·FAILED 인 결제에 사전등록을 요청하면 **PG 거래가 생긴 뒤** 예외로 롤백된다. code-notes 가 만료에 대해 피하려 한 "우리 장부에는 없고 PG 에만 있는 거래"가 상태를 통해 생긴다.
3. 행을 잠그지 않아, 사전등록이 동시에 두 번 오면 PG 호출이 둘 나가고 늦게 커밋한 쪽의 `pg_tx_id` 만 남는다. 먼저 열린 결제창의 거절 콜백은 `pgTxId` 대조에서 `PAYMENT_TX_MISMATCH` 로 튕긴다.

**`ReviewService.receive`** (`ReviewService.java:82`) — 게임물관리위원회 접수가 컨슈머 트랜잭션 안에 있다. 접수가 성공한 뒤 커밋이 실패하면 컨슈머가 재시도하며 **다시 접수**한다.

**[권장 개선안]** — prepare 는 환불과 같은 세 걸음으로 바꾼다: (TX-a) 행 잠금 + 상태·창 검사 + "사전등록 중" 표시 커밋 → PG 호출 → (TX-b) `pg_tx_id` 기록.
그 전에 최소한 상태 검사를 PG 호출 앞으로 옮긴다. 게임위 접수는 멱등키(상품코드 + 신청 회차)를 요구하거나, 접수 의도를 outbox 로 적고 별도 워커가 호출한다.

---

<a id="r5"></a>
### R5. PaymentCancelled 가 다른 eventId 로 두 번 나갈 수 있다

**[분석 당시 구현의 잠재적 문제]** — 영향은 작지만 **"Inbox 가 있는데 도메인 유니크가 왜 필요한가"** 의 실례라 알아 둘 가치가 크다.

**무엇이** — `PaymentService.completeCancel()`(`:159-168`)은 `payment.completeCancel()` 이 이미 CANCELED 라 아무것도 하지 않고 돌아와도(`Payment.java:207-210`) **그와 상관없이** `PaymentCancelledEvent` 를 적재한다.

**어떻게 두 번 오나** **[코드상 추정]**

- 환불 버튼을 두 번 누른 요청이 겹치면, 둘째의 `beginCancel` 도 CANCELING 을 보고 통과한다 → PG 취소 두 번(멱등) → `completeCancel` 두 번 → 둘째도 이벤트를 적재한다.
- 결제창 만료 승인 경로(`handleApproval:102-108`)는 `scheduleFirstCancelRetry` 를 부르지 않아 `next_cancel_attempt_at` 이 NULL 로 남는다. 스윕 조회는 NULL 을 즉시 대상으로 보므로(`PaymentRepository.java:33`), 파사드가 PG 를 부르는 사이 스윕이 돌면 같은 결과가 난다. code-notes 의 "방금 착수한 건은 집지 않는다"는 의도와 어긋난다.

**하류에서** — eventId 가 달라 Inbox 를 통과한다. order 는 "이미 취소", license 는 "회수할 것 없음 → 이벤트도 없음", settlement 는 "REFUND 이미 있음"으로 흡수한다. 금전 영향은 없고, PG 취소 중복 호출과 "몇 번 시도했나" 관측이 흐려지는 것이 남는다.

**[권장 개선안]** `Payment.completeCancel()` 이 실제로 전이했는지 boolean 으로 돌려주고, 전이했을 때만 적재한다. 결제창 만료 경로에도 첫 유예를 예약한다.

---

<a id="r6"></a>
### R6. license 는 DLT 발행이 실패하면 레코드를 버린다

**[분석 당시 구현의 잠재적 문제]**

**무엇이** [확인됨] — license 의 recoverer 는 DLT 발행 실패까지 `sendQuietly` 로 삼킨다(`KafkaErrorHandlerConfig.java:95-103`). 에러 핸들러는 복구가 끝난 것으로 보고 오프셋을 커밋한다.
나머지 8개 서비스의 기본 recoverer 는 반대로, DLT 발행 결과를 기다려 실패하면 예외를 던지고 그 레코드를 다시 seek 한다(spring-kafka 3.3.10 `DeadLetterPublishingRecoverer#verifySendResult`, `SeekUtils#doSeeks`).

**영향** — 버려지는 것이 `PaymentCompleted` 면 지급이 누락되고, 그 사실은 로그와 대사로만 드러난다.
DLT 발행이 실패하는 가장 흔한 원인은 브로커 장애인데, 브로커가 한 대면 그때는 컨슈머도 새로 읽지 못한다.
남는 경우는 브로커가 여러 대일 때 DLT 파티션만 리더가 없는 순간, 또는 레코드가 DLT 쪽에서만 거절되는 경우(예: 예외 헤더가 붙어 `max.request.size` 를 넘음)다 **[코드상 추정]**.

**트레이드오프** — 주석은 "밖으로 나가면 무한 재전송"을 피하려 했다고 적는다. 하지만 그 "무한 재전송(파티션 정지)"은 다른 8개 서비스가 이미 받아들인 동작이고, 알람이 울린다.
**결제 이벤트는 잃는 것보다 멈추는 편이 낫다.** 보상 판단 로직에서 난 예외만 삼키고 DLT 발행 실패는 다시 던지는 것이 개선안이다.

---

<a id="r7"></a>
### R7. 브로커 1대, 복제 계수 1, 토픽 자동 생성

**[확인됨]** `docker-compose.yml` — `KAFKA_NODE_ID: 1` 한 대, `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1`, `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"`, `KAFKA_NUM_PARTITIONS: 3`.
원격 스택 브로커에서도 도메인 토픽 6개와 DLT 2개가 전부 파티션 3 · 복제 계수 1 이고, 설정을 열어 본 `stove.payment.v1` 과 그 DLT 는 `min.insync.replicas=1` 이다.
`acks=all` 이 사실상 `acks=1` 이고, 브로커 디스크를 잃으면 소비되지 않은 이벤트와 컨슈머 오프셋을 함께 잃는다. 로컬·CI 구성이다. **운영 구성은 [추가 확인 필요].**

**[권장 개선안]** 브로커 3대 이상, 복제 계수 3, `min.insync.replicas=2`, 자동 생성을 끄고 토픽을 명시적으로 생성(파티션 수 고정, DLT 보존 기간 연장).

---

<a id="r8"></a>
### R8. outbox 와 inbox 테이블이 줄지 않는다

**[확인됨]** `outbox_event` 의 SENT 행과 `processed_event` 행을 지우는 코드가 없다(삭제 쿼리·스케줄러 전수 검색).

- 디스크·백업 시간·인덱스 크기가 계속 는다.
- **릴레이 조회의 실행 계획이 분포에 따라 바뀐다**(실험 2 — range+filesort 에서 PRIMARY 인덱스 스캔으로). 계획이 바뀌면 잠그는 범위와 INSERT 대기 여부가 함께 바뀐다.
- inbox 를 지울 때는 **재전달이 올 수 있는 기간보다 오래** 남겨야 한다. 너무 일찍 지우면 오프셋 리셋·DLT 재투입이 중복 반영된다.

**[권장 개선안]** SENT 는 N 일 지난 것을 작은 배치로 지우고, inbox 는 "토픽 보존 + 재처리 운영 기간"보다 긴 보존 기간을 정해 같은 방식으로 지운다.

---

<a id="r9"></a>
### R9. DLT 보존 기간이 재투입 기한이다

**[확인됨 — OCI 브로커]** DLT 토픽의 보존 기간을 정한 곳이 없어 브로커 기본값을 따른다 — `stove.payment.v1.DLT` 의 `retention.ms=604800000`(7일). 재투입하지 않은 DLT 레코드는 7일 뒤 **DLT 에서도 사라진다.**
`MessagesDeadLettered` 알람은 "쌓였다"를 알리지만 "오래됐다"는 알리지 않는다.

**[권장 개선안]** DLT 토픽은 보존 기간을 길게(예: 30일) 명시하고, "가장 오래된 DLT 레코드의 나이"를 지표·알람으로 둔다.

---

<a id="r10"></a>
### R10. 커넥션 총량이 스케일아웃을 막는다

**[확인됨]** MySQL 인스턴스 하나에 일곱 서비스의 풀 합계 110, `max_connections` 기본 151. 원격 스택에서 요청이 없을 때 `stove` 커넥션을 세면 **110개**다.
Hikari 는 `minimumIdle` 기본값이 최대치라 **기동하자마자** 전부 연다. 서비스를 두 대씩 띄우면 220 이 필요해, 먼저 뜬 인스턴스가 커넥션을 다 쥐고 나중에 뜨는 쪽이 "Too many connections" 로 실패한다 — 통합 테스트가 같은 벽을 만났다(D-036).

**[권장 개선안]** 배포 단위의 "인스턴스 수 × 풀 크기 합계 < max_connections − 여유"를 배포 게이트에서 검사하고, 풀을 측정 기반으로 줄이거나(측정상 동시 사용 최대 5), 인스턴스가 늘면 커넥션 프록시를 둔다.

---

<a id="r11"></a>
### R11. 재시도해도 성공할 수 없는 실패도 네 번 시도한다

**[확인됨]** 에러 핸들러에 비재시도 예외를 추가하지 않았다. 스프링 기본 비재시도 목록(역직렬화·변환 계열 여섯)에 이 프로젝트의 계약 위반(`EventEnvelope` 의 `IllegalStateException`)과 도메인 거절(`BusinessException`)은 없다.
그래서 헤더가 빠진 메시지도 1·2·4초를 기다려 네 번 시도하는 동안 파티션을 세운 뒤 DLT 로 간다.

**[권장 개선안]** 계약 위반은 전용 예외로 바꿔 `addNotRetryableExceptions` 에 넣는다. `BusinessException` 전체를 넣지는 않는다 — R3 의 CONFLICT 처럼 **순서가 바로잡히면 풀리는** 거절도 있어서, 영구 거절만 골라야 한다.

---

<a id="r12"></a>
### R12. 정산 귀속 월이 이벤트 시각이 아니라 소비 시각이다

**[확인됨]** `SettlementRecordService` 는 원장의 귀속 월을 `YearMonth.from(LocalDate.now())` 로 정한다(`:44`, `:73`). 이벤트에는 `occurredAt` 이 있지만 쓰지 않는다.
8월 31일 23:59 에 승인된 결제가 소비 지연으로 9월 1일에 처리되면 9월 매출이 된다. 금액이 사라지지는 않지만(D-001 의 누적 반영) 귀속 월과 세금계산서의 월이 달라진다.
`LocalDate.now()` 는 JVM 시간대를 따르고, 컨테이너 이미지가 `-Duser.timezone=Asia/Seoul` 로 고정한다 [확인됨 — Dockerfile]. 컨테이너 밖에서 띄우면 머신 시간대를 따른다.

**[추가 확인 필요]** 결제 시각과 처리 시각 중 무엇을 귀속 기준으로 할지는 회계 정책이다. 결제 시각이라면 `PaymentCompleted` 의 `occurredAt` 을 쓰고 시간대를 명시적으로 변환한다.

---

<a id="r13"></a>
### R13. 릴레이 1대 제약이 강제되지 않는다

**[확인됨]** 릴레이 빈은 `stove.outbox.relay-enabled`(기본 true)만 보고 **모든 인스턴스에** 뜬다(`MessagingAutoConfiguration.java:82-92`). 리더 선출도, 락도 없다.
[이벤트 순서](event-ordering.md)와 당시 code-notes는 "릴레이는 서비스당 1대여야 한다"고 적었지만 코드에는 드러나지 않는다. 서비스를 두 대로 늘리는 순간 릴레이도 두 대가 되고, 같은 주문의 이벤트가 두 릴레이로 갈라져 **키 웨이브의 순서 보장이 조용히 무효**가 된다.

**[권장 개선안]**

| 안 | 대가 |
|---|---|
| 배포 설정으로 한 인스턴스만 `relay-enabled=true` | 그 인스턴스가 죽으면 발행이 멈춘다 — 자동 인계 없음 |
| 릴레이 루프에 ShedLock | `lockAtMostFor` 가 최악의 한 회차(브로커 장애 시 배치당 120초 × 최대 10배치)보다 길어야 두 대가 겹치지 않는다 |
| 파티션 키 해시로 워커 고정 배정 ([이벤트 순서](event-ordering.md)) | 워커 수를 바꿀 때 재배치 창 |
| CDC | 운영 대상 추가 (R1 도 함께 해결) |

---

<a id="r14"></a>
### R14. Eager 리밸런싱과 한 그룹의 여러 리스너

**[확인됨 — kafka-clients 기본값, OCI 브로커]** 배정 전략을 정하지 않아 `RangeAssignor` 가 선택되고(원격 브로커에서 조회한 그룹이 모두 `range`), static membership 도 없다(`group.instance.id = null`, 실행 중인 앱 로그).
배포·재시작마다 그룹 전체가 파티션을 내려놓았다 다시 받는다. payment(리스너 2)와 download(리스너 3)는 리스너 하나의 재시작이 같은 그룹의 다른 토픽 소비까지 멈춘다.
중복은 Inbox 가 흡수하므로 정확성 문제는 아니고, **정지 시간과 재처리량**의 문제다.

**[권장 개선안]** `partition.assignment.strategy=CooperativeStickyAssignor`, 롤링 배포가 잦으면 `group.instance.id`.

---

<a id="r15"></a>
### R15. 주문 생성 API 에 멱등키가 없다

**[확인됨]** `CreateOrderRequest` 에 멱등키가 없고 서버도 요청을 식별하지 않는다. 응답을 받지 못한 클라이언트가 재시도하면 주문이 하나 더 생긴다.
결제는 주문마다 사용자가 따로 시작해야 하고 안 쓴 주문은 1시간 뒤 만료되므로 금전 사고로 번지지는 않는다.

**[권장 개선안]** `Idempotency-Key` 헤더와 `(member_id, idempotency_key)` 유니크. 같은 키면 기존 주문을 돌려준다.

---


### 당시 재시도 설명에서 바로잡은 전제

분석 당시 Spring Kafka 3.3.10의 `DefaultErrorHandler`는 백오프 후 seek하고 컨테이너가 다시 poll하는 경로였다. `max.poll.interval.ms`와 비교할 것은 모든 재시도의 백오프 총합이 아니라 시도 한 번의 처리 시간과 백오프다. 당시 소스 확인은 `FailedRecordTracker`·`SeekUtils`·리스너 컨테이너를 기준으로 했다.

Outbox 재시도가 즉시 실패한다고 계산한 “약 8분”도 브로커 무응답 상황에는 그대로 적용되지 않는다. 시도마다 `delivery.timeout.ms` 120초를 기다리면 DEAD까지 약 30분이 될 수 있다는 코드상 추정이 있었으며, 브로커 중단 실험으로 확인한 시간은 아니다.
