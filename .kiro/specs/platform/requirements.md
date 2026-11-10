# 요구사항: Platform (모듈을 넘는 계약)

> 작성 중 — [결정 150](../../../docs/decisions.md#spec-foundation)(목표 계약으로 다시 정의)·[결정 151](../../../docs/decisions.md#platform-spec)(모듈을 넘는 계약의 자리)·[결정 152](../../../docs/decisions.md#spec-evaluation)(점검표와 흐름별 실패 분석으로 평가, 품질 시나리오는 실험 뒤).

## 개요

모듈 둘 이상에 걸친 업무 계약의 정본이다. 모듈 스펙은 자기 상태·API·내부 규칙만 갖고, 여기 있는 행을 링크한다.

## 범위

| 다루는 것 | 다루지 않는 것 |
|---|---|
| 모듈 간 흐름(출시 → 공개, 주문 → 결제 → 지급 → 정산, 환불 → 복구·조정)과 단계별 실패 처리 | 한 모듈 안의 상태 전이·API·검증 규칙 |
| 이벤트 공통 규칙(사실 하나, 소유자, 키, 순서, 멱등, 재시도·DLT) | 이벤트를 받은 모듈 안의 업무 반응 |
| 모듈 간 돈 불변식과 그것을 지키는 곳 | 모듈 하나 안의 불변식 |
| 수치 품질 목표(처리량·지연·복구 시간·정합성 회복 시간) — 실험으로 기준선을 잰 뒤 더한다(결정 152) | 실험 결과(→ [experiments.md](../../../docs/experiments.md)) |

## 요구사항

읽는 법

- 이 문서는 **목표 계약**이다. 코드가 다른 곳은 [tasks.md](tasks.md)의 "코드와 맞출 것"(`T`번호)에, 한 모듈 안에서 끝나는 차이는 그 모듈 스펙의 작업(`MC`번호)에 있다. 구멍(U1~U6)을 정상 동작으로 적지 않는다.
- 수용 기준은 EARS(`WHEN … THEN 시스템 SHALL …`, `IF … THEN …`, `WHILE …`, `THE … SHALL …`)다. `P2.3-4`는 요구사항 P2.3의 4번 수용 기준이다.
- `[결정 필요 PD-n]`은 업무 정책이 아직 정해지지 않은 자리다. 결정되면 `docs/decisions.md`의 결정 번호로 바꾼다. 소급 후보 `RC-n`(결정 147 원칙에 어긋난 옛 결정)은 다시 묻기 전까지 기존 결정이 유효하다.
- 요구사항과 core 계약에는 공급자 이름을 쓰지 않는다("PG", "결제 공급자", "객체 저장소", "메일 발송"). 공급자 규칙은 [design.md](design.md)의 adapter 메모에만 둔다(원칙 ②, 결정 140).
- 판단 원칙 ①~⑦은 [architecture.md](../../../docs/architecture.md)의 "판단 원칙 (결정 147)"이다. 표·SQL·테스트 연결은 design.md에 있다.
- 경로 줄임: `<모듈>:` = `apps/<모듈>/src/main/java/com/stove/<모듈>/`, `<모듈>/db/Vn` = 그 모듈 Flyway 이행 파일.

### P1. 모듈 경계와 의존 방향

용어: **동기 의존** = 한 모듈의 `adapter`가 다른 모듈·외부 공급자의 HTTP API를 부르는 것. **비동기 의존** = 한 모듈이 Outbox로 낸 이벤트를 다른 모듈의 `api.listener`가 소비하는 것. **정본** = 어떤 사실·값을 처음 정하고 바꿀 수 있는 유일한 모듈(원칙 ③).

#### P1.1 경계 (코드 수준)

1. THE 각 업무 모듈 SHALL 다른 업무 모듈의 `com.stove.<앱>` 구현을 직접 참조하지 않고, 모듈 사이에는 `com.stove.common.kafka.contract`의 이벤트 계약과 호출 모듈 `core.port`의 포트만 쓴다(ArchUnit `ModulePackageRules.앱은_다른_앱의_구현을_모른다`).
2. THE 각 업무 모듈 SHALL 자기 MySQL 스키마(`stove_<모듈>`)만 읽고 쓰며, 다른 모듈 테이블을 조인하거나 한 트랜잭션으로 묶지 않는다. DB 계정 권한으로도 지킨다(T29). 운영 도구에도 예외를 두지 않는다. 정방향 누락은 사실을 낸 모듈이 자기 기록으로 센다(P4.3-1). 모든 스키마를 읽는 불변식 검사기(`tools/verification/`)는 실험·여정의 판정 도구로만 쓴다([결정 158](../../../docs/decisions.md#forward-gap-counting)).
3. WHEN 모듈이 다른 모듈이나 외부 공급자를 동기 호출하면 THEN 시스템 SHALL 호출 계약을 호출 모듈의 `core.port`에, HTTP 구현을 `adapter`에, 주소·키·타임아웃 조립을 `config`에 둔다(AGENTS.md 3절).
4. THE 외부 공급자의 이름·응답 형식·오류 분류·보장 기한 SHALL `adapter`와 `config`에만 있다. IF `core` 패키지가 공급자 이름이나 공급자 상수를 쓰면 THEN 아키텍처 검사 SHALL 실패한다(결정 140, T28).

#### P1.2 동기 의존 방향

1. THE 모듈 간 동기 호출 그래프 SHALL 순환이 없다. 허용하는 동기 호출은 design "동기 의존" 표의 행뿐이며, 새 행은 표를 먼저 고친다.
2. WHEN 외부 요청이 들어오면 THEN Gateway SHALL 공개·관리자 경로만 라우팅하고 `/internal/**`은 어떤 라우트에도 매칭하지 않는다(`apps/gateway/src/main/resources/application.yml`, `GatewayRouteTest`).
3. WHEN 모듈이 다른 모듈의 `/internal/v1/**`을 부르면 THEN 받는 모듈 SHALL 호출 모듈별 서비스 키 헤더(`X-<호출모듈>-Service-Key`)를 상수 시간 비교로 확인하고, 경로마다 허용한 호출 모듈 하나만 통과시킨다(`catalog:config/SecurityConfig.java:26-47`, `order:config/SecurityConfig.java:23-32`).
4. THE 동기 호출 SHALL 호출 모듈의 DB 트랜잭션 밖에서 일어난다(예: `order:core/service/AdmitOrderPayment.java:32`).
5. IF 동기 호출 대상이 응답하지 않거나 계약 밖 응답을 주면 THEN 호출 모듈 SHALL 자기 상태를 바꾸지 않고 재시도 가능 실패로 끝내며, 상대 상태를 추측해 기록하지 않는다. 상대 모듈 장애는 외부 공급자 장애와 다른 오류 분류로 기록한다(P3.11-4).
6. THE 동기 호출 SHALL 조회이거나, 받는 모듈이 자기 정본을 바꾸는 명령이다. 명령형 동기 호출은 Payment → Order 승인 진행 접수·시도 실패 통지 두 개뿐이다(결정 32: 결제 기한의 판정자는 Order이고 Payment는 PG 확정 전에 그 판정을 받는다).

#### P1.3 비동기 의존 방향

1. THE 각 이벤트 SHALL 발행 모듈이 하나이고, 발행 모듈은 그 사실의 정본 모듈이다(이벤트 목록은 P2·design).
2. THE 모듈 사이 비동기 순환 SHALL design "비동기 의존" 표의 "요청 → 사실 응답" 쌍으로만 허용한다. 요청 이벤트는 요청한 모듈의 사실("요청을 기록했다"), 응답 이벤트는 받은 모듈의 사실("내 상태를 바꿨다")이며, 한 모듈이 상대 모듈의 상태를 대신 확정하는 이벤트는 두지 않는다.
3. THE 소비 모듈 SHALL 이벤트로 받은 값을 판정 입력으로 쓰되, 그 값을 정한 규칙(기한·금액 계산·공급자 보장 기한)을 다시 계산하지 않는다(원칙 ③).
4. WHEN 같은 사실이 동기 조회와 이벤트 둘로 전달되면 THEN 두 경로의 값 SHALL 같은 정본에서 나오고 같은 버전 식별자(`catalogVersion`·`contentVersion`)를 실어, 받는 쪽이 식별자로 신구를 가린다(Store의 Catalog 사본, P3.7).

#### P1.4 정본 소유

1. THE 사실·값마다 정본 모듈 SHALL design "정본 소유" 표의 한 모듈이다. 다른 모듈의 값은 사본이고, 받은 근거(이벤트 ID·버전)를 함께 저장한다.
2. IF 같은 사실을 두 모듈이 각자 외부 공급자에게서 받아 저장하면 THEN 스펙 SHALL 판정에 쓰는 정본을 정하고, 정본이 아닌 쪽은 판정·금액 계산에 쓰지 않는다. PG 비용의 정본은 Settlement `settlement_pg_fee` 하나이고, Payment는 새 결제에 비용을 쓰지 않는다([결정 165](../../../docs/decisions.md#pg-fee-owner), T17).
3. THE 주문 결제 기한(`expiresAt`) SHALL Order가 정본이다. 기한 길이(지금 30분, 결정 32)는 Order 설정 키 하나에 두고 주문 생성 때 `purchase_order.expires_at`에 저장한다. Payment와 다른 모듈은 `OrderPlaced.expiresAt`·접수 응답으로 받은 값을 기록·표시에만 쓰고 다시 계산하지 않는다(T12).
4. THE 환불 취소 결과 확인 지연 시각(`confirmationDelayAt`) SHALL Payment가 정본이다. Order는 `RefundExecutionPrepared`로 받은 값을 그대로 쓰고 `firstPreparedAt + 3시간`과 대조하지 않는다(T14).
5. THE 결제 공급자의 멱등 재송신 보장 기한(승인 확정 송신 기한·환불 취소 재송신 창, 지금 3시간) SHALL 결제 공급자 adapter 설정이 정본이다. core는 adapter가 준 기한(`confirmDeadline` 등)을 시도에 기록하고 그 값으로만 판정한다(결정 124·140, 원칙 ②, MC-P2).
6. THE 상품 소유자(`ownerSubject`) SHALL Catalog가 정본이며 상품 생성 뒤 바뀌지 않는다. Settlement는 매출 기록 때 받은 소유자를 매출 행에 고정하고 다시 조회하지 않는다(`catalog:core/domain/CatalogProduct.java:125`·`:140`).
7. THE 다운로드 제한 SHALL `catalog_product.download_restricted`가 정본이다. 객체 저장소 태그는 정본에서 생성한 적용 사본이고 적용 상태는 `download_storage_status`(`PENDING`·`APPLIED`·`FAILED`)로 추적한다(`catalog/db/V13__download_restriction.sql`).

#### P1.5 아키텍처 검사와의 관계

1. THE ArchUnit 규칙 SHALL 코드 구조(패키지·계층·앱 간 직접 참조·이벤트 입력·설정)를 강제하고, 런타임 의존(HTTP 대상·이벤트 소비)은 design의 동기·비동기 의존 표와 대조하는 계약 테스트가 지킨다(T27).
2. WHEN 모듈이 새 `adapter` HTTP 클라이언트나 새 `@KafkaListener`를 더하면 THEN 대조 테스트 SHALL 표에 없는 (호출 모듈, 대상 경로) 또는 (소비 그룹, 이벤트 유형) 쌍을 실패로 보고한다.

### P2. 이벤트 계약 공통 규칙

용어: **봉투** = Kafka 레코드의 키·헤더와 본문 공통 칸(`eventId`·`occurredAt`). **소비 그룹** = `@KafkaListener(groupId)` 값이며 `processed_event.consumer_group`에 그대로 쓰는 값. **집합체 키** = 파티션 키로 쓰는 업무 식별자(`orderNo`·`productCode`·`subject`). **사실 시각** = 발행 모듈이 그 사실이 생겼다고 기록한 시각(본문 `occurredAt`).

#### P2.1 봉투

1. WHEN 모듈이 이벤트를 Kafka로 내보낼 때 THEN 시스템 SHALL 토픽 = 그 이벤트 종류에 고정된 `Topics` 상수, 키 = `partitionKey`(P2.4), 값 = payload JSON, 헤더 = `eventId`(UUID, 64자 이하)·`eventType`(`EventType` 값, 60자 이하)·`occurredAt`(ISO-8601 UTC)·`schemaVersion`(이벤트 종류별 정수, 그 이벤트 본문의 모양. 헤더가 없는 옛 메시지는 1, [결정 170](../../../docs/decisions.md#schema-version-header))·`traceparent`(있을 때만)로 만든다(`common/messaging/.../outbox/OutboxRelay.java:146-157`, `common/kafka/.../EventHeaders.java`).
2. 모든 payload SHALL 본문에 `eventId`·`occurredAt`을 담고, 헤더의 같은 이름 값과 같다. 헤더 `occurredAt`에 Outbox 적재 시각을 쓰지 않는다(원칙 ①, T25).
3. 본문 `occurredAt`은 발행 모듈의 사실 시각이다. 소비 모듈 SHALL 이 값을 기록·표시·기간 귀속 입력으로만 쓰고, 자기 시계나 다른 모듈 시각과 선후를 비교해 이벤트를 거절·분기하는 데 쓰지 않는다(P4.5).
4. `eventType` 값 SHALL `common/kafka/.../contract/EventType.java`의 상수만 쓴다. payload `eventType()`과 리스너 `isType(...)`은 상수를 참조하고 문자열 리터럴을 쓰지 않는다(원칙 ③, T26).
5. IF 받은 레코드에 `eventId`·`eventType` 헤더가 없거나 비어 있으면 THEN 시스템 SHALL 업무 처리 전에 거절하고(`EventEnvelope.from`, `common/kafka/.../EventEnvelope.java:30-45`) 재시도 없이 DLT로 보낸다(P2.6-3).
6. payload 칸 하나 SHALL 한 뜻만 갖는다. 열거 값 칸(`reasonCode` 등)은 같은 사실의 하위 분류만 담고, 받는 쪽이 다르게 반응해야 하는 다른 사실을 구별하는 데 쓰지 않는다. 다른 사실은 다른 `eventType`으로 낸다. 받는 쪽이 같은 반응을 하면 합치는 일은 받는 쪽 `api.listener`의 `*Mapper.from`에서 하고, Inbox에는 받은 `eventType`을 그대로 남긴다(원칙 ①).
7. payload·`EventType`·`Topics` SHALL 공급자 이름·공급자 상수를 담지 않는다. PG 식별자는 중립 이름(`providerTransactionId`·`cancellationId`·`lookupHash`)으로, PG 상태는 core enum 값으로 싣는다(원칙 ②).
8. 금액 칸 SHALL 정수 최소 단위(`long`)와 `currency`를 함께 싣고, 확인되지 않은 값은 0이 아니라 `null`이다(I7, `EventContractTest.omittedDetailsAndOmittedPointsDoNotTurnIntoZeroAmounts`).

#### P2.2 발행 — Transactional Outbox

1. WHEN 업무 상태를 바꾸는 트랜잭션이 다른 모듈에 알릴 사실을 만들면 THEN 시스템 SHALL 같은 트랜잭션·같은 DB에 `OutboxRecorder.record(aggregateType, aggregateId, event)`로 `outbox_event`를 적재한다(`Propagation.MANDATORY`, `common/messaging/.../outbox/OutboxRecorder.java:32-43`). 업무 코드는 `KafkaTemplate.send`를 직접 부르지 않는다.
2. THE 이벤트 종류마다, 토픽마다 발행 모듈 SHALL 하나다. 요청 사실(`LicenseSuspensionRequested` 등)은 요청한 모듈의 토픽에 낸다.
3. 릴레이(`OutboxRelay`) SHALL `PENDING`이고 `next_attempt_at`이 지난 행을 `id` 순으로 선점해 at-least-once로 보내며, 브로커 확인(`acks=all`, 멱등 프로듀서) 뒤에만 `SENT`로 바꾼다.
4. WHILE 같은 `partitionKey`의 앞 이벤트가 `PENDING`(재시도 대기)이면 THEN 릴레이 SHALL 그 키의 뒤 이벤트를 보내지 않는다. IF 앞 이벤트가 `DEAD`이면 THEN 릴레이 SHALL 그 키의 뒤 이벤트를 계속 보류하고, ADMIN이 앞 이벤트를 회수(`requeue`)해 `SENT`가 된 뒤에만 `id` 순으로 보낸다([결정 155](../../../docs/decisions.md#key-stop-on-dead), T2).
5. WHEN 발행이 실패하면 THEN 릴레이 SHALL 1초부터 두 배씩 5분 상한으로 다시 시도하고(`OutboxEvent.backOffDelay`), `stove.outbox.max-retry`(기본 10)를 넘기면 `DEAD`로 바꿔 지표로 낸다. 운영자가 `DEAD`를 되돌릴 때 `eventId`·본문·키·`traceParent`를 바꾸지 않는다.
6. 서비스마다 같은 키를 동시에 선점하는 릴레이가 하나뿐이어야 순서가 성립한다. 시스템 SHALL 이 조건을 선점 잠금이나 키 배정으로 코드에서 강제한다(원칙 ⑤, T10).
7. WHEN ADMIN이 Kafka에서 사라진 이벤트를 재발행하면(결정 129) THEN 시스템 SHALL 그 모듈이 소유한 이벤트 종류만, 같은 `eventId`·본문·키로 `SENT → PENDING` 되돌리고 실행자·시각·사유를 남긴다. 받는 쪽은 Inbox로 거른다. 대상은 design 이벤트 목록에서 상태가 "현행"인 모든 이벤트이고, 모듈별 허용 목록은 그 표에서 만든다. 모든 발행 모듈이 재발행 경로(`outbox_republish`, `republish-owner` 설정, ADMIN 운영 API)를 갖는다. 재발행된 이벤트가 정지된 키로 들어가면 회수는 같은 키 안의 발행 순번(헤더)으로 정렬해 원래 순서를 지킨다([결정 161](../../../docs/decisions.md#republish-all-current), T7).

#### P2.3 소비 — Inbox와 업무 멱등

1. WHEN 소비자가 이벤트를 처리하면 THEN 시스템 SHALL 업무 변경과 같은 트랜잭션에서 `ProcessedEventGuard.firstDelivery(eventId, consumerGroup, eventType)`를 부르고, 이미 있으면 부수효과 없이 끝낸다(`processed_event`, 유일 키 `uk_processed_event(event_id, consumer_group)`).
2. Inbox의 `consumer_group` 값 SHALL 받은 `@KafkaListener`의 `groupId`와 같다. 한 그룹 안 여러 서비스가 같은 이벤트를 나눠 처리해도 Inbox 표시는 한 번이다(예: Order `ApplyApprovalCorrection.handleStoppedSuspension`이 `ApplyRefundSuspension.CONSUMER_GROUP` 사용).
3. 소비자 SHALL Inbox와 별도로 업무 멱등 키를 DB 유일 제약으로 둔다(design 이벤트 목록 "멱등 키" 열). Inbox는 같은 `eventId`의 재전달을, 업무 키는 같은 사실이 다른 `eventId`로 다시 온 경우를 막는다(I8).
4. IF 같은 업무 멱등 키로 이미 반영된 사실과 내용이 다른 이벤트가 오면 THEN 소비자 SHALL 덮어쓰지 않고 영구 실패로 거절해 DLT·경보로 보낸다(예: `order:core/domain/Order.java:154-157`, `GrantLicense.verifyRedelivery`). 같은 요청 ID에 다른 스냅샷·대상이 오는 경우도 같다.
5. WHERE 이벤트가 상태 사본(본문이 집합체 전체 공개 사본이고 단조 증가 버전을 싣는 것 — 지금은 Store가 받는 Catalog 사본뿐)이면 소비자 SHALL Inbox 대신 버전 비교로 멱등을 지킬 수 있다. 버전이 작으면 무시, 같으면 내용이 같을 때만 무시, 다르면 거절한다(`store:core/domain/StoreProduct.java:64-77`). 버전은 발행 모듈이 매긴 집합체 버전이며 시각이 아니다.
6. 소비자 SHALL 한 레코드를 리스너 스레드 안에서 끝내고 커밋 뒤 오프셋을 커밋한다(`ack-mode: record`, `enable-auto-commit: false`). 논블로킹 재시도(`@RetryableTopic`)를 쓰지 않는다.
7. IF 구독한 토픽에서 처리하지 않는 `eventType`이 오면 THEN 소비자 SHALL 부수효과 없이 건너뛰고, 건너뛴 수를 (소비 그룹, 타입) 지표로 낸다(T31). 이것이 새 이벤트 종류를 소비자보다 먼저 배포할 수 있는 전제다(P2.7-4).
8. 여러 토픽을 받는 리스너 SHALL (토픽, `eventType`) 짝을 대조해 표에 없는 짝을 거절한다(지금 대조하는 것은 Order `RefundRestorationEventListener:28-29`뿐, MC-ST1).

#### P2.4 파티션 키

1. 시스템 SHALL 파티션 키를 집합체 키로 고정한다: `stove.order.v1`·`stove.payment.v1`·`stove.license.v1` = `orderNo`, `stove.studio.v1`·`stove.review.v1`·`stove.release.v1`·`stove.catalog.v1` = `productCode`, `stove.auth.v1` = `subject`.
2. THE 한 이벤트 종류의 파티션 키 칸 SHALL 바뀌지 않고, 같은 집합체의 모든 이벤트는 같은 키를 쓴다(`EventContractTest.sameOrderSharesPartitionKey`).
3. WHILE 키 순서에 기대는 토픽이 운영 중이면 THEN 시스템 SHALL 그 토픽의 파티션 수를 바꾸지 않는다. 늘려야 하면 새 토픽으로 옮긴다. 토픽 이름의 `vN`은 이 파티션 수·키 규칙의 세대이고, 본문 모양 변경으로는 올리지 않는다(결정 170). 토픽은 자동 생성이 아니라 선언으로 만든다(T11).

#### P2.5 순서 보장 범위

1. THE 시스템 SHALL 같은 토픽·같은 파티션 키 안에서 발행 모듈의 커밋 순서(`outbox_event.id` 순)를 같은 소비 그룹이 받는 순서만 보장한다(`OutboxRelay.java:70-113`, D-014).
2. 시스템은 서로 다른 토픽 사이, 서로 다른 소비 그룹 사이, 서로 다른 모듈 사이, DLT 재투입·Outbox `DEAD` 회수·재발행 뒤의 순서를 보장하지 않는다. 여러 토픽을 받는 소비자 SHALL 도착 순서가 아니라 식별자와 자기 상태로 판정한다.
3. IF 받은 이벤트의 선행 사실이 아직 자기 기록에 없으면 THEN 소비자 SHALL 재시도 가능 실패로, 선행 사실과 식별자가 모순되면 영구 실패로 다룬다(P2.6).
4. THE 모듈 간 인과 순서(앞 이벤트를 소비해야 뒤 이벤트가 생기는 순서) SHALL 발행 쪽 순서 보장이 아니라 소비 결과로 만들고, P3 흐름의 단계별 실패 표가 다룬다.

#### P2.6 재시도·DLT

1. WHEN 소비 처리에서 재시도 가능 실패가 나면 THEN 시스템 SHALL 블로킹 지수 재시도(1·2·4초, 최초 포함 4회, `common/kafka/.../ConsumerRetryPolicy.java`) 뒤 `<원본 토픽>.DLT`의 같은 파티션으로 원본 헤더를 보존해 보낸다(`DeadLetterTopics.of`, `DeadLetterPublisher.to`).
2. IF DLT 발행이 실패하면 THEN 시스템 SHALL 원본 오프셋을 넘기지 않는다(`setFailIfSendResultIsError(true)`). DLT 유입은 발행 성공 뒤 `DeadLetterMetrics`로 센다.
3. IF 실패가 영구 실패(봉투 헤더 없음, 역직렬화 실패, 계약 검증 실패, 식별자 모순, P2.3-4의 내용 불일치)이면 THEN 시스템 SHALL 재시도하지 않고 바로 DLT로 보낸다. 영구 실패는 `common/kafka`의 공통 예외 한 종류로 표시한다(원칙 ③, T20).
4. WHEN 소비자가 재시도를 소진하거나 영구 실패를 만나면 THEN 시스템 SHALL 그 레코드(토픽·파티션·offset·키·원본 헤더·본문·실패 사유)를 소비 모듈 DB의 실패 보관 표에 기한 없이 저장하고, 같은 트랜잭션에서 그 (소비 그룹, 토픽, 키)를 정지하며(5), 저장 1건부터 즉시 경보한다. Outbox `DEAD`도 1건부터 즉시 경보한다. WHEN ADMIN이 재투입하면 THEN 시스템 SHALL 소비 모듈 안에서 보관 표를 키 단위 offset 순으로 읽어 같은 리스너 경로(`*Mapper.from` → 서비스)로 다시 처리하고 결과를 표에 남긴다([결정 160](../../../docs/decisions.md#dead-letter-store)). 이 문서에서 "DLT"는 목표 계약에서 이 실패 보관 표를 뜻한다.
5. WHEN 소비자가 레코드를 실패 보관 표에 저장하면(4) THEN 시스템 SHALL 같은 트랜잭션에서 그 (소비 그룹, 토픽, 키)를 정지하고, 정지된 키의 뒤 레코드는 처리하지 않고 같은 표에 저장한다(정지 목록과 실패 보관은 한 표, 결정 160). WHEN ADMIN이 재투입하면 THEN 시스템 SHALL 키 단위로 보관 표의 원래 offset 순서대로 처리하고, 그 키의 레코드가 모두 처리된 뒤에만 정지를 푼다. 재투입한 레코드가 다시 실패하면 정지를 유지한다. 그래서 같은 키의 뒤 이벤트가 앞 이벤트보다 먼저 처리되지 않는다([결정 155](../../../docs/decisions.md#key-stop-on-dead), T2, `docs/architecture.md` R2). THE 시스템 SHALL 정지된 키 수(발행 `DEAD` 키, 소비 정지 키)를 소비 그룹·모듈별 지표로 내고 경보하며, 회수(`requeue`·재투입) API는 키 단위로 둔다(결정 155).
6. THE 모든 이벤트 SHALL 집합체 키와 업무 식별자를 실어, 사실이 다른 모듈에 끝내 반영되지 않은 것(정방향 누락, U3)을 P4.3의 감지가 셀 수 있게 한다.

#### P2.7 스키마 호환 (결정 104)

1. WHILE `eventType`과 스키마 버전이 같으면 시스템 SHALL 선택 칸 추가만 허용한다. 새 칸은 없을 때 `null`로 읽히고 소비자는 모르는 칸을 무시한다(`EventContractTest.unknownFieldsAreTolerated`).
2. 칸 삭제·이름 변경·타입 변경·뜻 변경·필수화·키 칸 변경 SHALL 같은 `eventType`·같은 버전에서 하지 않는다. 뜻이 바뀌면 새 `eventType`, 모양만 바뀌면 같은 `eventType`의 새 `schemaVersion`으로 낸다. 소비자는 (`eventType`, `schemaVersion`)으로 `*Mapper.from`을 고른다([결정 170](../../../docs/decisions.md#schema-version-header)).
3. WHEN PR이 이벤트 본문이나 소비자의 중복 대조 지문을 바꾸면 THEN 그 PR SHALL 옛 본문 견본·옛 지문으로 새 소비자가 옛 메시지를 받는 테스트를 더하고, 이행 파일이 옛 행의 뜻을 옮기면 그 모듈 `migrationTest`도 같은 PR에 쓴다(결정 104).
4. WHEN 새 `eventType`을 도입하거나 한 이벤트를 둘로 나누면 THEN 배포 순서 SHALL ① 소비자가 옛·새 타입을 모두 받는 버전 ② 발행자가 새 타입으로 바꾼 버전 ③ 옛 타입이 모든 서비스의 `outbox_event`(`PENDING`·`DEAD`)와 토픽 보존 기간에서 사라진 것을 확인한 뒤 옛 처리를 지우는 버전 순이다.
5. WHEN 한 `eventType`의 새 `schemaVersion`을 도입하면 THEN 배포 순서 SHALL ① 소비자가 옛·새 버전을 모두 읽는 판 ② 발행자가 새 버전을 내는 판이고, 옛 버전 읽기는 옛 버전 행이 모든 서비스의 `outbox_event`와 실패 보관 표에서 사라진 뒤에만 지운다([결정 170](../../../docs/decisions.md#schema-version-header)).

#### P2.8 구 계약 처리

1. THE `EventType` 상수마다 design 이벤트 목록 SHALL 상태 하나를 둔다: `현행` · `구 계약`(발행·소비 없음, 유지 여부가 열려 있음) · `제거 대상`(발행·소비 없음, 유지 이유 없음) · `목표 분리안`(현행이지만 원칙 ①에 따라 나눌 것) · `목표`(아직 코드에 없음).
2. WHEN `제거 대상` 상수를 지우면 THEN 그 PR SHALL 모든 서비스 `outbox_event`에 그 타입의 `PENDING`·`DEAD` 행이 없고 모든 소비 모듈의 실패 보관 표(결정 160)에 그 타입 행이 없으며 토픽 보존 기간이 지났다는 근거를 적고, 상수·payload·`EventContractTest.events()`·문서 행을 함께 지운다.
3. THE 시스템 SHALL `구 계약` 상태를 두지 않는다. 발행·소비가 없는 상수는 `제거 대상`이고 2의 절차로 지운다. 옛 기능이 다시 필요하면 그때의 사실로 새 `eventType`을 정의한다([결정 172](../../../docs/decisions.md#remove-legacy-contracts), T30).
4. 시스템 SHALL `EventType` 상수 목록과 design 이벤트 목록 표를 대조하는 테스트를 둔다(결정 151, T27). 문자열 리터럴 이벤트가 상수로 올라온 뒤(T26) 켠다.

### P3. 모듈 간 흐름과 실패 처리

흐름 1 출시 → 공개(P3.2~P3.9), 흐름 2 구매(P3.10~P3.15), 흐름 3 환불(P3.16~P3.27). 단계 목록·이벤트·단계별 실패 표는 [design.md](design.md#모듈-간-흐름)에 있다.

#### P3.1 흐름 공통 규칙

1. THE 각 흐름 SHALL design의 단계 목록으로 정의되고, 단계마다 실패 종류 6가지(중복 전달·순서 역전·늦은 도착·상대 상태가 이미 바뀜·동시 진행·상대 모듈 중단)의 처리 행을 빈칸 없이 갖는다(결정 152). 해당 없는 행은 이유를 적는다.
2. THE 흐름의 모든 모듈 간 이벤트 SHALL P2(봉투·Outbox·Inbox·키·재시도·DLT·재발행)를 따른다. 키는 흐름 1이 `productCode`, 흐름 2·3이 `orderNo`다.
3. THE 흐름의 모든 판정 SHALL 식별자(요청 ID·`requestEventId`·`admissionId`·`attemptId`·정지 요청 ID 등)·행 잠금·lease·단조 버전(`contentVersion`·`catalogVersion`)으로 하고, 다른 모듈이 찍은 시각과의 선후 비교로 하지 않는다(P4.5).
4. THE 흐름의 모든 상태 SHALL 명시적 enum이고 DB CHECK가 허용 값을 지킨다(원칙 ④). 보조 칸의 null 조합으로 하위 상태를 만들지 않는다. 열별 목표 enum은 각 모듈 스펙에 둔다.
5. THE 소비 모듈 SHALL 이벤트로 받은 규칙 값(기한·지연 경계·금액)을 그대로 쓰고 다시 계산하지 않는다(P1.3-3·P1.4).
6. IF 이벤트 입력이 계약을 어기거나 같은 요청 ID에 다른 내용이 오면 THEN 받는 모듈 SHALL 기존 기록을 덮어쓰지 않고 영구 실패로 DLT·경보에 보낸다(P2.3-4·P2.6-3). 조용히 성공 처리하지 않는다.

#### P3.2 심사 요청·철회 (Studio → Review)

1. WHEN Studio가 심사 요청(`POST /api/v1/studio/projects/{id}/review-requests`, `requestId`·`contentVersion`)을 접수하면 THEN Studio SHALL Project 잠금 안에서 불변 `ProjectSnapshot`을 고정하고 `studio_review_request`(`REQUESTED`)·명령 영수증·`ProjectReviewRequested` Outbox를 한 트랜잭션에 저장한다([Studio R3](../studio/requirements.md)).
2. WHEN Review가 `ProjectReviewRequested`를 그룹 `review-project`에서 받으면 THEN Review SHALL `reviewRequestId`를 PK로 `project_review`(`REQUESTED`)를 만들고 받은 스냅샷 전체를 불변으로 보관한다. 같은 `reviewRequestId`·같은 스냅샷이 다른 `eventId`로 다시 오면 새 행·새 결과를 만들지 않는다([Review R1·R2](../review/requirements.md)).
3. THE 프로젝트별 진행 중 심사 SHALL Studio와 Review 각각에서 1건이다(`uk_studio_active_review`, Review V11 활성 유일 키, 결정 76).
4. WHEN Studio가 심사 철회를 접수하면 THEN Studio SHALL 명령(`PENDING`)과 `ProjectReviewWithdrawalRequested` Outbox를 한 트랜잭션에 저장하고 202로 응답하며, 활성 심사 자리는 최종 결과 전까지 유지한다(결정 54).
5. WHEN Review가 철회를 받으면 THEN Review SHALL 판정과 같은 `project_review` 행 잠금 안에서 먼저 커밋된 결과를 유지하고(결정 53), 철회가 이기면 `ProjectReviewWithdrawn`, 판정이 먼저면 `ProjectReviewWithdrawalDenied`(`targetStatus`)를 발행한다(원칙 ①, T21).
6. WHEN 철회가 원요청보다 먼저 Review에 도착하면 THEN Review SHALL 철회 의사를 내구 보관하고 원요청 수신 때 같은 `reviewRequestId`로 대조해 확정하며, 원요청 없이 심사 행을 만들지 않는다.
7. IF 철회 대상의 원요청이 끝내 도착하지 않으면 THEN 철회를 보관한 모듈(Review·Catalog) SHALL 자기 보관 기록을 "원요청 없는 철회"로 센다(결정 158). 경보 기준 시간은 측정 뒤 정하고, 그 전에는 게이지(건수·최장 대기)만 낸다([결정 159](../../../docs/decisions.md#forward-gap-threshold)).

#### P3.3 심사 판정 결과 (Review → Studio)

1. WHEN REVIEWER·ADMIN이 승인·반려하면 THEN Review SHALL 요청 행 잠금 안에서 상태(`APPROVED`/`REJECTED`)·`actor`·`decidedAt`·결과 Outbox(`ProjectReviewApproved`/`ProjectReviewRejected`)를 한 트랜잭션에 저장한다. 판정은 한 요청에 한 번이다.
2. THE 판정 결과의 `reviewRequestId`·`projectId`·`contentVersion`·`productCode` SHALL Review가 저장한 스냅샷 값이다.
3. WHEN Studio가 판정 결과를 그룹 `studio-project`에서 받으면 THEN Studio SHALL 세 식별자가 모두 맞는 `studio_review_request`에만 기록한다. IF 이미 최종인 요청에 같은 결과가 오면 THEN 변화 없이 성공하고, 다른 결과가 오면 기존 기록을 유지하고 영구 실패로 DLT·경보에 보낸다(MC-ST2).
4. THE 출시 요청 근거(`releaseEligible`) SHALL 현재 `contentVersion`의 심사 요청이 `APPROVED`일 때만 참이다([Studio R4.2·R4.3](../studio/requirements.md)).

#### P3.4 출시 요청·철회 (Studio → Catalog)

1. WHEN Studio가 출시 요청을 접수하면 THEN Studio SHALL Project 잠금 안에서 (a) 현재 버전 심사 `APPROVED` (b) 진행 중 출시 없음 또는 같은 요청 (c) 저장소 HEAD 크기 = 스냅샷 `fileSize`를 확인하고, 승인된 심사 요청의 스냅샷 그대로 `ProjectReleaseRequested`(`releaseRequestId`·`reviewRequestId`·`snapshot`)를 Outbox에 기록한다(`studio:core/service/release/RequestProjectRelease.java:44-67`).
2. THE 출시 요청의 스냅샷 SHALL 그 `reviewRequestId`로 Review가 승인한 스냅샷과 값이 같다(RI-2). WHEN Catalog가 출시 요청을 받으면 THEN Catalog SHALL 공개를 확정하기 전에 Review에게서 직접 받은 승인 사실(`ProjectReviewApproved`)과 `(reviewRequestId, projectId, contentVersion, 스냅샷 값)`을 대조한다. 맞는 승인이 아직 없으면 출시를 승인 대기로 보류하고 승인이 오면 같은 잠금 안에서 공개 작업을 연다. 승인과 값이 다르면 공개하지 않고 출시를 거절한다(`ProjectReleaseRejected`). 승인 대기 건수는 Catalog가 센다([결정 167](../../../docs/decisions.md#catalog-verifies-approval)·결정 158, T16).
3. THE 프로젝트별 진행 중 출시 SHALL 1건이며, 앞선 출시의 최종 결과를 Studio가 기록한 뒤에만 다음 출시를 연다(결정 63, `uk_studio_active_release`).
4. WHEN Catalog가 `ProjectReleaseRequested`를 그룹 `catalog-project-release`에서 받으면 THEN Catalog SHALL 공개하지 않고 원요청·불변 스냅샷·Inbox를 `catalog_publication_job`(`PENDING`)으로 먼저 저장한다(결정 62).
5. WHEN Catalog가 출시 철회를 받으면 THEN Catalog SHALL 공개 확정과 같은 요청 잠금(`catalog_request_gate`) 안에서, 공개 확정 전이면 `catalog_release`를 `WITHDRAWN`으로 확정하고 `ProjectReleaseWithdrawn`을, 이미 `COMPLETED`/`REJECTED`이면 결과를 바꾸지 않고 `ProjectReleaseWithdrawalDenied`(`targetStatus`)를 발행한다(결정 57, T21).
6. WHEN 철회가 원요청보다 먼저 Catalog에 도착하면 THEN Catalog SHALL 철회를 보존하고 원요청 접수 때 `WITHDRAWN`으로 확정하며, 그 요청은 이후 재전달로도 공개되지 않는다.
7. THE 출시 철회 SHALL 이전 공개본·심사 승인·판매/노출/다운로드 설정을 바꾸지 않는다. 같은 승인본은 새 `requestId`로 다시 출시할 수 있다(결정 58).

#### P3.5 공개 확정 (Catalog)

1. WHEN 공개 작업자가 `PENDING` 작업을 처리하면 THEN Catalog SHALL 요청 잠금 → 상품 행 잠금 순서로 공개본(`catalog_release`)·현재 참조(`currentReleaseRequestId`)·`catalogVersion`·결과 Outbox·작업 완료를 한 트랜잭션에 저장한다(`catalog:core/service/PublishProjectRelease.java:50-170`).
2. THE 신구 판정 SHALL Studio가 발급한 `contentVersion`으로만 한다: 공개본 없음 → 최초 공개, 더 높음 → 교체, 같은 버전·같은 스냅샷 → 기존 공개본 유지하고 `COMPLETED`(결정 58), 더 낮음 → `STALE_CONTENT_VERSION`으로 `REJECTED`.
3. IF 같은 Project·같은 `contentVersion`에 다른 스냅샷이거나 `projectId`·`productCode`·`ownerSubject` 연결이 기존 상품과 다르면 THEN Catalog SHALL 공개하지 않고 계약 위반으로 처리한다. 같은 Project의 동시 최초 공개는 재시도 가능한 경합으로, 다른 Project의 같은 코드만 계약 위반으로 판정한다(D-042).
4. THE 공개 작업의 일시 실패 SHALL 같은 요청으로 5·15·30·60초 간격 재시도하며 새 요청 ID로 바꾸지 않는다([Catalog R10.3](../catalog/requirements.md)).
5. IF 공개 작업이 재시도로 성공할 수 없는 실패(계약 위반·식별 충돌)이면 THEN Catalog SHALL 그 작업을 재시도 대기(`PENDING`)와 구분되는 정지 상태(`BLOCKED`)로 두고 1건부터 경보한다. 분류는 공통 영구 실패 분류(T20)와 같다. WHEN ADMIN이 원인을 고쳐 재개하면 THEN Catalog SHALL 작업을 `PENDING`으로 되돌린다. WHEN ADMIN이 종결하면 THEN Catalog SHALL 그 출시 요청을 공개 없이 끝내고 같은 트랜잭션에서 운영자 종결 전용 이벤트(가칭 `ProjectReleaseClosed`)를 낸다. `ProjectReleaseRejected`의 `reason`에 싣지 않는다. WHEN Studio가 그 사실을 받으면 THEN Studio SHALL 출시 요청을 끝내고 활성 출시 자리를 푼다([결정 168](../../../docs/decisions.md#publication-blocked-closure), T23).
6. WHEN 공개 사본이 바뀌면 THEN Catalog SHALL 같은 트랜잭션 Outbox에 사실별 공개 사본 이벤트(`CatalogProductFirstPublished`·`CatalogProductReleaseReplaced`, 판매·노출 변경은 `CatalogProductSaleStatusChanged`·`CatalogProductListingChanged`)를 기록하고, 모두 그 시점의 공개 사본 전체와 `catalogVersion`을 싣는다. Store는 네 타입을 `api.listener`에서 같은 내부 입력으로 바꿔 하나의 반영 경로로 합친다. 이행은 P2.7-4 배포 순서를 따른다([결정 171](../../../docs/decisions.md#catalog-copy-events-by-fact), T22).
7. THE `catalogVersion` SHALL 상품마다 단조 증가하고 현재 공개본 `contentVersion`보다 작지 않다(`catalog:core/domain/CatalogProduct.java:133·148`).

#### P3.6 출시 결과 기록 (Catalog → Studio)

1. WHEN Studio가 `ProjectReleaseCompleted`/`ProjectReleaseRejected`를 받으면 THEN Studio SHALL `releaseRequestId`·`projectId`·`contentVersion`·`requestEventId`가 모두 맞는 `studio_release_request`에만 결과를 기록하고 활성 출시 자리를 해제한다.
2. IF `requestEventId`가 Studio가 보낸 원요청 이벤트 ID와 다르거나 최종 요청에 다른 결과가 오면 THEN Studio SHALL 영구 실패로 처리하고 경보한다(MC-ST2).
3. THE Studio 출시 요청의 최종 상태 SHALL Catalog `catalog_release.status`와 같다(RI-4). Studio는 출시 결과로 공개본 참조를 만들지 않는다.

#### P3.7 Store 사본 (Catalog → Store)

1. WHEN Store가 공개 사본 이벤트를 그룹 `store-catalog-publication`에서 받으면 THEN Store SHALL `productId` 행 잠금 안에서 `catalogVersion`으로만 판정한다(P2.3-5). 받는 이벤트가 여럿이어도 `api.listener`에서 `ApplyCatalogProduct` 하나로 합친다.
2. WHEN Store가 시작하거나 대조 주기가 되면 THEN Store SHALL `/internal/v1/store-products`(숨긴 상품 포함)를 끝까지 읽어 누락·구버전·같은 버전 내용 차이를 정본으로 교정하고 `store_reconciliation`에 전후를 남긴다(결정 66·67). 사본을 지우거나 버전을 낮추지 않는다.
3. WHILE 준비 전이면 Store SHALL 업무 조회에 503 `STORE_NOT_READY`를, 준비 뒤에는 Catalog 중단 중에도 저장한 사본을 준다.
4. THE Store SHALL 상품 단위 지연·실패를 대조 회차 성공과 분리한 지표로 낸다(MC-SO1). 허용 지연(경보 기준)은 측정 뒤 정한다([결정 159](../../../docs/decisions.md#forward-gap-threshold)).

#### P3.8 Download (현재 공개 빌드)

1. WHEN MEMBER가 `GET /api/v1/downloads/{productCode}/ticket`을 요청하면 THEN Download SHALL License 보유권 `ACTIVE` → Catalog 내부 빌드(`/internal/v1/products/{code}/build`) → 저장소 HEAD 순으로 확인하고 현재 공개본의 빌드만 서명한다([Download R3·R4·R6](../download/requirements.md)).
2. THE Download SHALL 이벤트를 받지 않고 공개본 사본을 두지 않는다. 처음 읽은 불변 공개본 값으로 한 응답을 완성한다(RI-6).
3. IF Catalog·License·저장소가 응답하지 않거나 형식이 틀리면 THEN Download SHALL 503으로 거절하고 미보유·없는 상품으로 바꾸지 않는다.

#### P3.9 출시 흐름의 모듈 간 정합

1. THE 흐름 SHALL design의 정합 조건 RI-1~RI-9를 지키고, 조건마다 지키는 곳과 확인 테스트를 갖는다(원칙 ⑤).
2. THE 플랫폼 SHALL 모듈을 넘는 정합 조건(RI-1·RI-2·RI-4·RI-5·RI-7)의 정방향 차이를 기준 시간 안에 감지한다. 감지는 요청한 모듈이 응답을 받지 못한 자기 기록을 세는 방식이다: Studio는 결과 없는 `REQUESTED` 요청·`PENDING` 철회 명령, Review·Catalog는 원요청 없는 철회 보관, Store는 Catalog 정본 대조(결정 85)의 상품 단위 지연([결정 158](../../../docs/decisions.md#forward-gap-counting), design "정방향 응답 쌍"). 경보 기준 시간은 측정 뒤 정하고, 그 전에는 게이지(건수·최장 대기)만 낸다([결정 159](../../../docs/decisions.md#forward-gap-threshold)), T6.
3. IF 역방향 위반(뒤 모듈 기록이 앞 모듈 근거 없이 있음: Studio `COMPLETED`인데 Catalog `COMPLETED` 없음, Catalog `COMPLETED`인데 Review `APPROVED` 없음)이 보이면 THEN 감지 SHALL 기준 시간과 무관하게 즉시 경보한다.

#### P3.10 주문과 결제 기한 (Order)

1. WHEN MEMBER가 `POST /api/v1/orders`로 새 `requestId`의 주문을 만들면 THEN Order SHALL 공개본 근거(상품 ID·코드·제목·`contentVersion`)·정가·할인액·청구액(`amount = listAmount − discountAmount > 0`)·통화·`expiresAt`을 한 트랜잭션에 고정하고 이 값을 모두 실은 `OrderPlaced`를 Outbox에 쓴다(결정 32·89, I1). 판매 상태는 Catalog 내부 조회(`/internal/v1/order-products/{code}`)로 트랜잭션 밖에서 확인한다.
2. THE 결제 기한 SHALL P1.4-3을 따른다. Payment는 자기 시계를 `OrderPlaced.occurredAt`·`expiresAt`과 비교해 만료를 판정하지 않고, Order 승인 접수의 `ORDER_EXPIRED` 거절과 `OrderExpired` 수신만 만료로 안다(원칙 ⑥, T12). WHEN 결제창 정보 요청이 오면 THEN Payment SHALL 그 주문의 `OrderExpired`를 받은 뒤에만 409 `ORDER_EXPIRED`로 거절하고, 그 전에는 결제창을 연다. 기한 직후 결제를 시작한 고객은 승인 접수에서 만료 안내를 받을 수 있고 돈은 나가지 않는다([결정 169](../../../docs/decisions.md#checkout-open-until-expiry-fact)).
3. WHEN Payment가 승인 진행 접수(`POST /internal/v1/orders/{orderNo}/payment-admissions`)를 요청하면 THEN Order SHALL 주문 행 잠금(`OrderRepository.lockByOrderNo`) 아래 자기 시계로 기한을 판정한다. 기한 전이고 `PENDING`이면 `PAYMENT_PROCESSING`으로 바꾸고 영수증(`admissionId`·`paymentId`·`admittedAt`)을 저장하며, 기한 뒤면 `EXPIRED`와 `OrderExpired`를 먼저 커밋하고 409 `ORDER_EXPIRED`로 거절한다(결정 32).
4. WHILE 주문이 `PAYMENT_PROCESSING`이면 Order SHALL 시간 경과만으로 `EXPIRED`·`FAILED`로 바꾸지 않는다(결정 32).
5. WHEN Order가 기한이 지난 `PENDING` 주문을 관찰하면(만료 스캔 `CloseExpiredOrders` 또는 승인 접수) THEN Order SHALL `EXPIRED`로 한 번 바꾸고 `OrderExpired`(원 `requestEventId`·구매자·청구액·통화·`expiresAt`)를 같은 트랜잭션에 쓴다. 스캔은 `expires_at <= now`로 고른다.

#### P3.11 결제 접수와 시도 (Order → Payment ↔ Order)

1. WHEN Payment가 `OrderPlaced`를 처음 받으면 THEN Payment SHALL 주문당 하나의 `purchase_payment`(`PENDING`, 고정 `paymentId`)와 Inbox를 저장하고 PG를 호출하지 않는다. IF 그 주문의 `OrderExpired`가 먼저 와 있으면 THEN 결제를 `EXPIRED`로 만들고 시도를 받지 않는다.
2. 시스템(Payment) SHALL 돈을 움직일 수 있는 PG 요청(승인 확정 송신)을 그 시도의 승인 진행 영수증을 저장한 뒤에만 보낸다. 결제 공급자 종류와 무관하며 모의 PG에도 같다(원칙 ⑦, I15, T13).
3. WHEN 접수 전 시도(`REQUESTED`)의 PG 조회가 READY이고 송신 중단이 아니면 THEN Payment SHALL Order에 승인 진행 접수를 요청한다. 영수증을 받으면 시도를 `ADMITTED`로 바꾸고, `ORDER_EXPIRED`면 시도·결제를 `EXPIRED`로 닫고 이벤트를 내지 않으며, 판매 제한(`PRODUCT_SALE_RESTRICTED`)이면 접수 전으로 두고 다음 회차에 다시 요청하고, 그 밖의 409면 보류(`ORDER_ADMISSION_CONFLICT`)로 올린다.
4. IF 승인 접수·시도 실패 통지 HTTP가 연결 실패·타임아웃·5xx로 끝나면 THEN Payment SHALL 시도 상태를 바꾸지 않고 다음 회차에 같은 `admissionId`로 다시 요청하며, 이 오류를 PG 장애와 다른 분류(Order 연계 장애)로 기록한다(MC-P6).
5. WHEN 접수된 시도가 PG READY이면 THEN Payment SHALL P1.4-5의 확정 송신 기한 안이고 lease가 살아 있을 때만 같은 멱등 키·처음 저장한 본문으로 확정을 보낸다(결정 124). 결제 공급자 통지(웹훅)는 다음 조회를 앞당기는 힌트일 뿐 상태를 확정하지 않는다.

#### P3.12 결과 확정 (Payment)

1. WHEN 접수된 시도의 PG 조회가 PAID이고 금액·통화가 결제와 같으면 THEN Payment SHALL 결제 → 시도 순서로 잠근 뒤 그 시도가 여전히 그 결제의 접수된 현재 시도인지 다시 확인하고, 한 트랜잭션에서 결제 `PAID`·시도 `SUCCEEDED`·`OrderPaymentCompleted`(그 시도의 `admissionId` 포함) Outbox를 쓴다(I11·I15, D-051, T1).
2. WHEN 접수된 시도의 PG 조회가 검증된 시도 실패(FAILED)이면 THEN Payment SHALL 시도를 `FAILURE_CONFIRMED`로 바꾸고 Order에 시도 실패 통지(`POST …/payment-admissions/{admissionId}/failure`)를 보낸다. Order는 그 영수증이 최신이고 결과가 없을 때만 기한 전이면 `PENDING`(같은 주문 재결제), 기한 뒤면 `EXPIRED`+`OrderExpired`로 바꾸고 영수증을 실패 종료로 닫는다. Payment는 실패 영수증을 받은 뒤 시도를 `FAILED`로 닫는다.
3. IF PG 결과가 미확정(응답 유실·PG PENDING·조회 장애·결과 트랜잭션 실패)이면 THEN Payment SHALL 상태를 유지하고 다음 조회를 예약하며, 시간 경과나 재시도 횟수만으로 실패를 만들지 않는다.
4. 시스템(Payment) SHALL 결제당 최종 결과를 한 번만 정한다. 같은 결과의 재확정은 무변화, 다른 결과는 `CONFLICT`다(I8).
5. IF 승인 진행 영수증이 없는 시도(접수 전 시도, 실패·종결·만료로 닫힌 시도)에서 PG 결제가 관측되면 THEN Payment SHALL 결과 이벤트를 내지 않고 그 시도를 보류(`PAID_WITHOUT_ADMISSION`)로 올리며, 다른 시도의 성공으로 돌리지 않는다(결정 114·126, I15). 이미 끝난(종결·만료·실패) 결제에 뒤늦게 생긴 청구는 P3.13-8·9로 찾는다.

#### P3.13 승인 보류와 종결 (Payment → Order)

1. WHEN 설명되지 않는 금융 상황(보류 사유 8개: `PAID_WITHOUT_ADMISSION`·`PG_REQUEST_REJECTED`·`PG_INVALID_EVIDENCE`·`ORDER_ADMISSION_CONFLICT`·`ORDER_EXPIRY_CONFLICT`·`PG_CANCELLED`·`CONFIRM_WINDOW_EXPIRED`·`PG_PENDING_AT_ORDER_EXPIRY`)이 처음 생기면 THEN Payment SHALL 그 시도의 확정 송신을 멈추고 `REVIEW_REQUIRED`로 바꾼다. 사유별 조건·사유 유지·재조회 간격·고객 안내(`reviewNotice`)·ADMIN 목록·경보는 Payment 모듈 규칙이다([Payment R12·R13](../payment/requirements.md), 결정 114·122·123·132·144, 사유 표 이관 MC-P12).
2. 시스템 SHALL 보류를 이유로 PG 취소를 실행하지 않는다. 돈을 되돌리는 일은 운영자가 결제 공급자 콘솔에서 하고, 시스템은 PG 재조회로 결과를 확인만 한다(결정 114·115).
3. WHEN ADMIN이 보류 종결(`POST /api/v1/admin/approval-reviews/{approvalId}/closure`)을 요청하면 THEN Payment SHALL 트랜잭션 밖 PG 재조회 근거가 전액 CANCELLED·FAILED·READY일 때만 종결하고, PAID·PENDING·조회 실패·결제 이미 확정이면 409로 거절하고 보류를 그대로 둔다(결정 115).
4. WHEN 종결이 허용되면 THEN Payment SHALL 시도를 `FAILED`로 닫고 운영자·요청 ID·PG 근거를 `approval_closure`에 남기며, 결제·주문을 다음과 같이 끝낸다(같은 주문 재결제 없음, 고객은 새 주문). (a) 접수된 시도 → 결제 `FAILED`, `OrderPaymentReviewClosed`(`admissionId` 포함) → 주문 `FAILED`. (b) 접수 전 시도 + 만료 사실 있음 → 결제 `EXPIRED`, 이벤트 없음. (c) 접수 전 시도 + 만료 사실 없음 → 결제 `FAILED`, `OrderPaymentReviewClosed`(`admissionId` null) → 주문 `PENDING → FAILED`. IF `OrderPaymentReviewClosed`가 이미 `EXPIRED`인 주문에 오면 THEN Order SHALL 만료를 유지하고 종결 사실만 기록한다(결정 115, 전용 이벤트는 [결정 174](../../../docs/decisions.md#review-closed-event), T18).
5. WHEN 접수된 시도가 확정 송신 기한을 넘겼고 PG가 PENDING·READY이면 THEN Payment SHALL `CONFIRM_WINDOW_EXPIRED`로 보류하고 실패·새 멱등 키·새 결제를 만들지 않으며, 그 뒤 PG PAID·FAILED가 오면 원래 주문으로 끝낸다(결정 124).
6. WHEN 접수 전 시도가 만료 사실을 받으면 THEN Payment SHALL PG 관측 이력이 없으면 PG를 부르지 않고 시도·결제를 `EXPIRED`로 닫고, 이력이 있으면 PG를 한 번 조회해 PAID → `PAID_WITHOUT_ADMISSION`, 전액 CANCELLED → `PG_CANCELLED`, PENDING → `PG_PENDING_AT_ORDER_EXPIRY`로 보류하며, READY·FAILED면 잠금 안에서 `REQUESTED`를 다시 확인한 뒤 닫는다(결정 126, D-044).
7. WHEN Payment가 `OrderPaymentResultRejected`를 처음 받으면 THEN Payment SHALL 거절된 결과를 낸 결제에 대해 9의 기록(출처 `ORDER_REJECTED`)을 만들고, PG 취소를 실행하지 않는다(2와 같음). 그 결제에 이미 보류나 기록이 있으면 처음 사유를 유지하고 사실만 더한다(결정 144). 세부 전이·안내 코드는 Payment 모듈 규칙이다([결정 153](../../../docs/decisions.md#late-success-rejection), MC-P13).
8. WHILE 결제가 종결·만료·실패로 끝났고 PG에 그 결제의 요청이 만들어진 적 있으며 공급자가 더 이상 결제될 수 없다고 보장하는 시점 전이면 THEN Payment SHALL 정해진 간격으로 PG를 조회한다. WHEN 그 결제에 웹훅이 오면 THEN Payment SHALL 다음 조회를 앞당긴다. 웹훅만으로 발견을 보장하지 않는다. 간격·기한은 공급자 규칙이라 adapter 설정에 둔다(결정 140, [결정 154](../../../docs/decisions.md#unlinked-charge-discovery), T5).
9. WHEN 8의 조회가 PG `PAID`를 관측하면 THEN Payment SHALL 끝난 결제·시도를 다시 열지 않고 주문에 연결되지 않은 청구 기록(가칭 `UnlinkedCharge`, 명시 상태 `OPEN` → `CLOSED`, 출처 enum `LATE_CHARGE`·`ORDER_REJECTED`)을 만들어 보류 목록·경보에 올리고 고객 안내를 청구됨으로 바꾼다. 시스템 SHALL PG 취소를 실행하지 않는다. 운영자가 공급자 콘솔에서 취소한 뒤 PG 근거(전액 `CANCELLED`)로만 기록을 닫는다(결정 114·115·154, I20).

#### P3.14 결과 반영 (Order)

1. WHEN `OrderPaymentCompleted`가 처음 오면 THEN Order SHALL 주문 행 잠금 아래 연결값(`orderNo`·`requestEventId`·`buyerSubject`·`amount`·`currency`)과 `admissionId`·`paymentId`가 이 주문의 열린 최신 승인 진행 영수증(주문 `PAYMENT_PROCESSING`)과 같을 때만 `PAID`로 바꾸고, 같은 트랜잭션에 Inbox(`order-payment-result`)·결과 식별·반영한 영수증 ID(`purchase_order.payment_admission_id`)·`OrderPaid` Outbox를 쓴다(I1·I3·I12, T1).
2. IF 성공 결과의 주문 연결값은 맞지만 열린 최신 영수증의 성공으로 반영할 수 없으면(주문 `PENDING`·`EXPIRED`·`FAILED`, 영수증 실패 종료, 다른 `admissionId`, 이미 다른 결과로 `PAID`) THEN Order SHALL `PAID`로 반영하지 않고 `OrderPaid`를 만들지 않으며, 같은 트랜잭션에 Inbox·거절 기록과 `OrderPaymentResultRejected`(거절한 결과의 `eventId`·`paymentId`·`admissionId`·거절 사유) Outbox를 쓴다. 이 결과를 409로 DLT에 보내지 않는다. 주문 연결값 자체가 다르면 계약 위반으로 영구 실패다([결정 153](../../../docs/decisions.md#late-success-rejection)).
3. WHEN `OrderPaymentFailed`(결제 공급자가 그 결제를 최종 거절함)·`OrderPaymentReviewClosed`가 처음 오고 1과 같은 연결이 맞으면(`admissionId`가 null이면 영수증 없는 `PENDING` 주문) THEN Order SHALL 주문을 `FAILED`로 바꾸고 사유를 기록한다. `OrderPaymentFailed`는 운영자 종결을 싣지 않는다(원칙 ①).
4. WHEN 결과가 정해진 주문에 결과가 다시 오면 THEN Order SHALL 저장한 결과와 같으면 무변화로 끝낸다. 다른 결과가 성공이면 2를 따르고, 그 밖의 다른 결과이거나 그 결과 `eventId`를 다른 주문이 이미 썼으면 영구 실패로 거절한다(I8).
5. 시스템(Order) SHALL `OrderPaid`를 최초 `PAID` 트랜잭션에서 한 번만 만들고, 재시도·`DEAD` 재큐잉·재발행은 저장된 `eventId`·키·본문을 그대로 쓴다(결정 129).

#### P3.15 구매 근거와 매출 (Order → License · Settlement)

1. WHEN License가 `OrderPaid`를 처음 받으면 THEN License SHALL 구매 근거 한 행(`product_license`, `STORED`)·Inbox(`license-paid-order`)·구매 근거 생성 사실(지금 이름 `LicenseGranted`, 이름 정정은 소급 RC-3) Outbox를 한 트랜잭션에 저장한다. 같은 고객이 같은 상품을 이미 가져도 막지 않고, 식별자가 다른 주문에 쓰였으면 영구 실패다(결정 89·93, I3·I8).
2. WHEN Order가 구매 근거 생성 사실을 처음 받으면 THEN Order SHALL 주문이 `PAID`이고 구매자·상품·`paymentId`가 같을 때만 `order_license_grant`를 저장한다(환불 흐름의 입력, P3.17).
3. WHEN Settlement가 `OrderPaid`를 처음 받으면 THEN Settlement SHALL 트랜잭션 밖에서 PG 승인과 Catalog 불변 소유자를 조회·대조한 뒤, 판매자 잠금(`SellerLockRepository.lock`) 아래 매출·배분·PG 거래·Inbox(`settlement-paid-order`)·월 합계를 한 트랜잭션에 저장한다. 귀속 월이나 그 뒤 월이 `CLOSED`면 현재 KST `OPEN` 월에 반영하고 원래 월을 보존한다(결정 42·130, I5·I6). 매출 귀속 월은 Payment가 승인을 확정한 시각(`OrderPaid`의 가칭 `paymentConfirmedAt` — Order가 `OrderPaymentCompleted.occurredAt`을 그대로 전달)의 KST 월이고, 그 칸이 없는 옛 `OrderPaid`는 `occurredAt`을 쓴다. 환불 귀속 월(결정 42)과 같은 금융 확인 기준이다([결정 166](../../../docs/decisions.md#sale-month-by-payment-confirmation)).
4. IF 매출 기록에 필요한 외부 조회가 실패하거나 대조가 어긋나면 THEN Settlement SHALL 원장·Inbox·월 합계를 만들지 않고 공통 재시도를 받으며, 소진하면 DLT에 원본 그대로 남긴다.
5. WHILE Order 주문이 `PAID`인데 구매 근거 응답(`LicenseGranted`)이나 매출 기록 확인 사실(가칭 `SettlementSaleRecorded`)을 받지 못했으면 THEN Order SHALL 그 건수를 센다(I14, [결정 158](../../../docs/decisions.md#forward-gap-counting)). 경보 기준 시간은 측정 뒤 정하고, 그 전에는 게이지(건수·최장 대기)만 낸다([결정 159](../../../docs/decisions.md#forward-gap-threshold)).

#### P3.16 환불 접수와 자격 (Order)

1. WHEN 고객이 `POST /api/v1/orders/{orderNo}/refund`를 보내면 THEN Order SHALL 주문 행 잠금 뒤 본인·`PAID`·환불 없음을 확인하고 `order_refund` 한 행을 저장한 다음 응답한다. 정지·PG 실행은 응답 뒤 비동기로 진행한다(결정 29).
2. IF 같은 주문에 이미 환불이 있으면 THEN Order SHALL 새 환불을 만들지 않고 기존 `refundId`와 첫 접수 시각을 돌려준다(`uk_order_refund_order`).
3. WHEN 접수가 자동 환불 기한(4시간) 안이면 THEN Order SHALL `automaticRefundEligible=true`로 저장하고 지급 확인 단계로 보내고, 밖이면 `PENDING_REVIEW`로 저장한다. 기한 시작은 Order가 결제 결과를 받아 주문을 처음 `PAID`로 바꾼 자기 시각(가칭 `paid_at`)이고, 같은 Order 시계의 접수 시각과 비교한다. Payment 확정 시각은 값으로만 남는다([결정 173](../../../docs/decisions.md#refund-window-order-clock), 결정 29의 "기한 시작"을 바꿈).
4. WHILE 환불이 `PENDING_REVIEW`이면 Order SHALL 보유권을 정지하지 않는다. ADMIN 판정은 회차별로 먼저 커밋된 것만 확정한다.

#### P3.17 지급 확인 대기 (License → Order)

1. WHEN 환불이 허용되는데 그 주문의 지급 근거(`order_license_grant`)가 없으면 THEN Order SHALL 환불을 `PENDING_LICENSE_GRANT`로 두고 정지 요청을 내지 않는다. License는 지급 전 정지 의도를 보관하지 않는다(결정 29 B).
2. WHEN Order가 구매 근거 생성 사실을 받아 구매 연결이 맞으면 THEN Order SHALL 지급 근거를 저장하고, 기다리던 환불이 있으면 같은 트랜잭션에서 `LicenseSuspensionRequested`를 쓴다.
3. WHILE 환불이 `PENDING_LICENSE_GRANT`·`PENDING_LICENSE_SUSPENSION`·`PENDING_LICENSE_RESTORATION`·`PENDING_APPROVAL_STOP` 중 하나이면 Order SHALL 그 상태에 들어간 Order 시각부터 `stove.order.refund.license-grant-delay`(10분)가 지난 건수를 상태 라벨별 게이지로 내고 경보 `RefundLicenseGrantsDelayed`와 고객 `processingNotice`를 붙인다(결정 125).

#### P3.18 보유권 정지 (Order → License → Order)

1. WHEN Order가 `LicenseSuspensionRequested`를 내면 THEN 본문 SHALL `refundId`·`orderNo`·`buyerSubject`·`productId`·`productCode`·`paymentId`를 담고, License는 이 값을 불변 구매 기록과 대조한다.
2. WHEN License가 대조에 성공하면 THEN License SHALL 구매 근거와 열린 보유권을 정지하고 정지 기록과 `LicenseSuspended`(요청 `eventId` 포함)를 같은 트랜잭션에 쓴다. IF 구매 근거가 다른 요청으로 이미 정지됐거나 끝났으면 THEN 정지하지 않고 영구 실패로 거절한다.
3. WHEN Order가 `LicenseSuspended`를 받아 현재 정지 요청 ID(`suspensionRequestEventId`)·구매 연결이 맞으면 THEN Order SHALL 시도(`RefundAttempt`)를 만들고 `RefundExecutionRequested`를 쓴다. 정지 완료 확인 전에는 PG 실행을 요청하지 않고, 과거 정지 요청을 가리키는 `LicenseSuspended`로는 새 시도를 만들지 않는다.
4. WHILE 보유권이 정지돼 있으면 License SHALL 라이브러리에 정지 표시를 보이고, Download는 새 URL 발급을 막는다. 이미 발급한 서명 URL은 만료까지 유효하다(결정 29).

#### P3.19 실행 접수와 준비 (Order → Payment → Order)

1. WHEN Payment가 `RefundExecutionRequested`를 받으면 THEN Payment SHALL 원결제 `PAID`와 연결값·금액(원결제 전액)을 대조하고 `attemptId` 하나에 `refund_execution` 한 행만 접수하며, 접수 트랜잭션에서 PG를 호출하지 않는다. 같은 `licenseSuspensionEventId`가 다른 실행에 쓰였으면 거절한다.
2. IF 요청이 재시도(`previousAttemptId`)이면 THEN Payment SHALL 이전 시도가 같은 환불·같은 결제의 `FAILED`이고 다음 실행이 아직 없을 때만 접수한다. 판정은 식별자와 `refund_execution` 행 잠금으로만 한다(U2 PAY-1, T3).
3. WHEN Payment가 첫 취소 송신을 준비하면 THEN Payment SHALL `firstCancellationPreparedAt`과 `confirmationDelayAt`을 한 번만 정해 `RefundExecutionPrepared`로 알린다. Order는 식별자만 대조해 두 값을 그대로 저장한다(P1.4-4, T14).
4. WHILE 환불이 `PENDING_PAYMENT_REFUND`이고 현재 시도의 `RefundExecutionPrepared`를 받지 못했으면 THEN Order SHALL 결정 125 게이지에 "실행 준비 미수신" 라벨로 세고, 같은 10분 경계로 경보하며 고객 안내(`processingNotice`)를 낸다. 요청한 모듈이 응답을 받지 못한 자기 기록을 세는 방식이다([결정 158](../../../docs/decisions.md#forward-gap-counting)·결정 125, T15).

#### P3.20 PG 취소 송신·조회·보류·재개 (Payment)

1. WHEN Payment가 PG에 취소를 보내면 THEN Payment SHALL `attemptId`를 멱등 키로, 처음 저장한 본문을 그대로 쓴다. 재송신 간격·조회 간격·보장 창(P1.4-5)은 Payment 모듈 규칙이다([Payment R7·R8](../payment/requirements.md), 결정 29·122·133).
2. WHEN PG가 요청을 받아 판단한 거절을 돌려주면 THEN Payment SHALL 자동 송신을 멈추고 보류 기록을 남긴다. 일시 오류·깨진 본문·타임아웃은 미확정으로 계속 재송신한다. 응답 분류표는 adapter가 갖는다(결정 133, MC-P9).
3. WHILE 운영자 실패 종결(P3.23)이 PG를 다시 조회하는 동안 Payment SHALL 그 실행의 송신·재송신·ADMIN 재개를 하지 않고, 재개 요청은 409 `REFUND_CLOSURE_BUSY`로 거절한다(PR #166 R1 B안, I19).
4. WHEN 미확정이 결과 확인 지연 시각(`confirmationDelayAt`)을 넘으면 THEN Payment SHALL 경보 `RefundConfirmationsDelayed`와 ADMIN 지연 목록에 올리고, Order는 고객 조회에 지연 안내를 붙인다. 시간 경과만으로 실패·복구하지 않는다.

#### P3.21 금융 완료와 귀속 (Payment → Order · Settlement)

1. WHEN Payment가 PG 근거로 원결제 전액 취소를 처음 확인하면 THEN Payment SHALL 실행에 금융 확인을 한 번만 기록하고 같은 트랜잭션에서 `RefundPaymentConfirmed`를 쓴다. 근거는 우리 취소 응답 성공이거나 PG 조회로 확인한 같은 원결제의 전액 취소다(결정 29).
2. `RefundPaymentConfirmed` SHALL "원결제 전액이 PG에서 취소됐음을 Payment가 확인했다"만 뜻한다. 그 취소가 이 시도의 우리 요청 때문인지(귀속)는 싣지 않고, 귀속이 확인되면 `RefundExecutionSucceeded`로 따로 낸다. 우리 응답으로 확인한 경우 두 이벤트를 같은 트랜잭션에 쓴다(원칙 ①, T19).
3. WHEN Order가 `RefundPaymentConfirmed`를 받아 주문·환불·현재 시도·실행 요청·금액(= 주문 청구액)이 식별자로 맞으면 THEN Order SHALL 환불을 `COMPLETED`로 바꾸고 같은 트랜잭션에서 `PurchaseRefunded`를 쓴다. 두 이벤트가 어떤 순서로 와도 첫 금융 확인 ID·시각과 시도 결과는 같다. Order 시도는 귀속을 갖지 않는다. 금융 완료면 시도는 `COMPLETED` 하나이고, 귀속(우리 요청·외부 취소·미확정)과 그 조사·종결은 Payment 기록이 정본이다([결정 164](../../../docs/decisions.md#attribution-in-payment)).
4. WHEN ADMIN이 장기 미확정·귀속 미확정 실행을 종결하면 THEN Payment SHALL 트랜잭션 밖 PG 재조회로 같은 원결제의 전액 취소가 확인될 때만 닫고, 미완료 건은 1과 같은 `RefundPaymentConfirmed`로 완료하며, 귀속 판정·ADMIN·근거를 Payment에 남긴다. 시스템은 PG 취소를 실행하지 않는다(결정 127).
5. IF 금융 확인 뒤 실패 근거가, 실패 확정 뒤 성공 근거가 관측되면 THEN Payment SHALL 이미 정한 끝 상태를 바꾸지 않는다(I18). 실패 뒤 늦은 전액 취소는 P3.23-5.

#### P3.22 PG 확정 실패 (Payment → Order)

1. WHEN Payment가 우리 취소 ID의 FAILED와, 이어진 조회에서 원결제 `PAID`·전액 미취소를 함께 확인하면 THEN Payment SHALL 실행을 `FAILED`로 바꾸고 `RefundExecutionFailed`를 쓴다. 이 이벤트는 "PG가 이 시도의 취소를 실패로 확정했고 원결제가 취소되지 않았다"만 뜻하며 `cancellationId`는 언제나 PG가 돌려준 우리 취소 ID다(결정 147·148).
2. WHEN Order가 `RefundExecutionFailed`(Payment 토픽만)를 받아 구매·현재 시도·실행 요청·금액이 식별자로 맞고 환불이 `PENDING_PAYMENT_REFUND`·금융 확인 없음이면 THEN Order SHALL 시도를 `FAILED`(출처 `PG_CANCELLATION_FAILED`)로, 환불을 `PENDING_LICENSE_RESTORATION`으로 바꾸고 `LicenseRestorationRequested`를 쓴다.
3. Settlement·License SHALL `RefundExecutionFailed`를 소비하지 않는다. 보유권 복구는 Order의 `LicenseRestorationRequested`로만 일어난다.

#### P3.23 운영자 실패 종결과 늦은 취소 반영 (Payment → Order · Settlement, 결정 128·148·157)

1. WHEN ADMIN이 `POST /api/v1/admin/refund-delays/{attemptId}/failure-closure`를 보내면 THEN Payment SHALL 자동 송신이 멈춘 실행(보류·잔액 변화 차단·보장 창 종료)만 대상으로, 조회 작업자와 같은 lease를 잡고 송신 쪽에도 종결 표시를 건 뒤 트랜잭션 밖에서 PG를 다시 조회한다. 재조회 결과가 원결제 `PAID`·취소액 0·진행 중이거나 성공한 취소 없음이 아니면 닫지 않고 409로 거절한다.
2. WHEN 재조회 근거가 성립하면 THEN Payment SHALL 별도 잠금 트랜잭션에서 lease·종결 표시를 여전히 가졌고 실행이 여전히 종결 대상이며 PG 접수 응답이 조회 전과 같은지 확인한 뒤, 실행을 `FAILED`로 바꾸고 `refund_failure_closure`(근거 종류·중단 원인·조회 해시·연결된 FAILED 취소 ID·ADMIN·시각)를 저장하고 같은 트랜잭션에서 `RefundFailureClosed`를 쓴다. 같은 `requestId`는 최초 응답을, 다른 내용은 409를 돌려준다.
3. `RefundFailureClosed` SHALL "운영자가 PG 재조회 근거로 이 시도를 실패로 종결했다"만 뜻하며 `RefundExecutionFailed`로 보내지 않는다. 근거 상세는 Payment `refund_failure_closure`가 정본이고 이벤트에는 시도 식별자·`closureId`·`failedCancellationId`(있을 때만)만 싣는다(원칙 ①③, design "목표 행").
4. WHEN Order가 `RefundFailureClosed`(Payment 토픽만, 그룹 `order-refund-restoration`)를 받으면 THEN Order SHALL `api.listener`에서 `RefundExecutionFailed`와 같은 내부 입력으로 바꾸되 출처(`OPERATOR_FAILURE_CLOSED`)와 `closureId`를 남기고 P3.22-2와 같은 전이로 잇는다. Settlement·License는 소비하지 않는다.
5. WHILE 실행이 실패로 닫힌 뒤에도 Payment SHALL 낮은 빈도로 PG를 조회하고, 늦은 전액 취소가 보이면 조사 목록(`LATE_CANCELLATION`)과 경보로 올리되 상태·보유권·환불을 자동으로 바꾸지 않는다. 그런 취소의 반영은 7·8을 따른다(T4).
6. WHEN 고객 재시도의 사전 확인이 이전 실패를 찾으면 THEN Payment SHALL PG FAILED 기록이 없는 운영자 종결 실패도 이전 실패로 받는다.
7. WHEN ADMIN이 실패로 끝난 환불 시도의 늦은 취소 반영을 요청하면 THEN Payment SHALL 트랜잭션 밖 PG 재조회에서 원결제가 전액 `CANCELLED`일 때만 반영하고, 아니면 409로 거절한다. 반영할 때 Payment SHALL 실패한 시도를 다시 열지 않고 그 시도의 `refund_confirmed_at`도 쓰지 않으며(I18), 늦은 취소 확인 기록(ADMIN·요청 ID·PG 근거 스냅샷·시각)과 `RefundLateCancellationConfirmed` Outbox를 한 트랜잭션에 쓴다. 시스템은 이 반영을 스스로 하지 않는다([결정 157](../../../docs/decisions.md#late-cancellation-confirmation), T4).
8. WHEN Order가 `RefundLateCancellationConfirmed`를 처음 받으면 THEN Order SHALL 그 환불을 명시 전이로 늦은 취소 완료 끝 상태(이름은 Order 모듈 스펙)로 바꾸고 `PurchaseRefunded`를 낸다. WHEN License가 그 `PurchaseRefunded`를 받으면 THEN License SHALL 복구로 `ACTIVE`가 된 보유권도 `REFUNDED`로 바꾼다. WHEN Settlement가 `RefundLateCancellationConfirmed`를 처음 받으면 THEN Settlement SHALL 환불 조정을 기록한다(월 규칙 결정 42·130, 원매출이 없으면 결정 156). 고객에게는 환불 완료와 보유권 회수를 안내한다(결정 157).

#### P3.24 복구와 재신청 (Order → License → Order)

1. WHEN License가 `LicenseRestorationRequested`를 받아 `licenseId`·구매 연결·정지 요청 ID·정지 완료 ID·`refundId`가 현재 정지와 맞으면 THEN License SHALL 정지 전 상태로 되돌리고(같은 고객·상품에 다른 `ACTIVE`가 있으면 옛 보유권을 끝내고 보관함으로) `LicenseRestored`(`returnedToStorage`)를 같은 트랜잭션에 쓴다(결정 94·96·98, I9).
2. WHEN Order가 `LicenseRestored`(License 토픽만)를 받아 복구 요청·실패·정지·`licenseId`가 맞으면 THEN Order SHALL 환불을 `FAILED`로 바꾸고, 고객 응답에 사유 코드(`PAYMENT_NOT_CANCELLED`)와 재신청 안내를 싣는다(결정 134).
3. WHEN 고객이 `POST /api/v1/orders/{orderNo}/refund/retries`(`previousAttemptId`)를 보내면 THEN Order SHALL 지정 시도가 현재 시도이고 실패·복구 완료(`restoredEventId` 있음)일 때만 재정지 요청을 낸다. 새 시도는 고객 재신청으로만 만들고, 재시도의 `LicenseSuspended`는 `retrySuspensionRequestEventId`·`licenseId`가 맞을 때만 `previousAttemptId`를 담은 `RefundExecutionRequested`로 잇는다(결정 29·134).

#### P3.25 회수 (Order → License)

1. WHEN License가 `PurchaseRefunded`를 받아 `licenseId`·구매 연결·`suspensionRequestEventId`가 현재 정지와 맞으면 THEN License SHALL 구매 근거를 `REFUNDED`로 끝내고 열린 보유권을 `ENDED`로 바꾼다. 결과 이벤트는 내지 않는다(결정 96·97).
2. IF 구매 근거가 정지 상태가 아니거나 다른 정지를 가리키면 THEN License SHALL 회수하지 않고 영구 실패로 거절한다.

#### P3.26 정산 환불 조정·이월 (Payment → Settlement)

1. WHEN Settlement가 `RefundPaymentConfirmed`를 받으면 THEN Settlement SHALL `PgRefunds`로 같은 결제를 직접 조회해 전액 취소·성공 취소 ID를 확인하고, 원매출과 판매자 행을 잠근 뒤 원배분을 음수로 상계한 `settlement_refund` 한 행을 쓴다(결정 41, I2).
2. WHEN 환불의 원래 월(`RefundPaymentConfirmed.confirmedAt`의 KST 월)이 `OPEN`이고 그 뒤 월에 `CLOSED`가 없으면 THEN Settlement SHALL 원래 월에 반영하고, 아니면 현재 `OPEN` 월에 반영하며 `refund_month`를 보존한다. 반영 월은 원래 월보다 앞서지 않는다(결정 42·130, I6). 자기 수신 시각이 확인 시각보다 이르다는 이유로 거절하지 않는다(U2 SET-1).
3. WHEN 판매자 월을 마감하면 THEN Settlement SHALL 오래된 월부터 마감하고 `payable = max(0, 순액 + carriedIn)`, 음수는 다음 월로 넘긴다(결정 121·130, I5).
4. Settlement SHALL `RefundExecutionSucceeded`·`RefundExecutionFailed`·`RefundFailureClosed`를 소비하지 않는다. 정산 금액은 첫 금융 확인 하나로 정해진다(U5, MC-S2).
5. THE 모든 실행 환경(개발·여정·실험·통합 테스트 포함) SHALL 같은 `PgRefunds` 계약으로 환불을 정산에 반영한다. 시뮬레이터 전용 거절 경로(`UnavailablePgRefunds`)나 검증 생략 경로를 두지 않고, 개발·시험의 PG는 기준 시뮬레이터 하나다([결정 162](../../../docs/decisions.md#single-reference-simulator), U4, T9).
6. IF 원매출보다 환불 확인이 먼저 도착하면 THEN Settlement SHALL DLT로 보내지 않고 받은 환불 확인을 검증해 "매출 대기 환불"(명시 상태)로 Inbox와 같은 트랜잭션에 저장한다. WHEN 같은 주문의 `OrderPaid`가 매출을 기록하면 THEN Settlement SHALL 같은 트랜잭션에서 대기 환불을 원매출과 대조해 환불 조정까지 반영하고(월 규칙은 결정 42·130), 대조가 맞지 않으면 반영하지 않고 경보한다. THE Settlement SHALL 대기 건수와 가장 오래 기다린 시간을 게이지로 낸다([결정 156](../../../docs/decisions.md#refund-awaiting-sale), T8).

#### P3.27 승인 중단 분기 (Order ↔ License)

1. WHEN ADMIN이 승인한 환불의 승인을 중단하면 THEN Order SHALL `RefundExecutionRequested` 저장 전까지만 받고, 정지 요청을 이미 냈으면 정지 완료를 기다려 `LicenseApprovalCorrectionRequested`로 정정 복구한 뒤 새 심사 회차를 연다(결정 29).
2. Order SHALL 정지 관찰·정정 복구 관찰·회차 재개 판정에 License 시각과 Order 시각의 선후를 쓰지 않는다(U2 ORD-7·ORD-8·ORD-9).

### P4. 모듈 간 돈 불변식

공통: 불변식마다 정의(테이블·컬럼)·걸친 모듈·지키는 곳·확인 테스트·확인 SQL을 [design.md](design.md#돈-불변식과-지키는-곳)에 둔다. 확인 SQL은 모든 스키마를 읽는 읽기 전용 계정에서 돌리며 결과 0행이 통과다. **역방향**(뒤 모듈 기록 → 앞 모듈 근거)은 항상 성립하고, **정방향**(앞 모듈 기록 → 뒤 모듈 반영)은 기준 시간 안에 성립한다(P4.3).

#### P4.1 금액·통화 (I1·I2)

1. THE 고객 청구액 SHALL 주문 생성 때 한 번 정해지며 `purchase_order.amount = list_amount − discount_amount > 0`이고, 같은 `order_no`의 `purchase_payment.amount`·`currency`와 같다(I1).
2. WHEN Order가 결제 결과를 받으면 THEN Order SHALL `orderNo`·`requestEventId`·`buyerSubject`·`amount`·`currency`가 주문과 모두 같을 때만 반영한다(I1).
3. THE PG 결제 구성 SHALL `pg_paid_amount + pg_discount_amount = amount`이고 결제가 `PAID`일 때만 값이 있다(I1).
4. THE 환불 SHALL 원결제 전액이다. `refund_execution.amount`는 `PAID` 결제의 `amount`와 같고, 금융 확인은 PG 취소 합계 = 원결제액일 때만 생기며, `settlement_refund`의 금액·플랫폼 몫·판매자 몫은 원매출과 같다(I2, 결정 41).

#### P4.2 사건 순서와 근거 (I3·I4·I8·I12·I15·I20)

1. WHEN Order가 주문을 처음 `PAID`로 바꾸면 THEN Order SHALL 같은 트랜잭션에서 `OrderPaid`를 쓰고, 그 밖에는 쓰지 않는다(I3).
2. THE 구매 근거(`product_license`) SHALL `OrderPaid`로만 생기고 주문당 1행이며, 보유권 `ACTIVE`는 고객·상품당 1개, 구매 근거당 열린 보유권(`ACTIVE`·`SUSPENDED`)은 1개다(I3).
3. IF `order_refund.status`=`COMPLETED`이면 THEN 같은 `refund_id`의 `refund_execution.refund_confirmed_at` SHALL 있다. IF Order 환불이 늦은 취소 완료 상태이면 THEN Payment의 늦은 취소 확인 기록 SHALL 있다(결정 157). IF `product_license.status`=`REFUNDED`이면 THEN 그 주문의 환불 SHALL `COMPLETED`다. IF `settlement_refund` 행이 있으면 THEN 같은 `refund_id`의 `refund_confirmed_at` SHALL 있다(I4).
4. THE 소비자 SHALL `processed_event`를 업무 변경과 같은 트랜잭션에 쓰고, 업무 기록도 사건 식별자 유일 키로 한 번만 생긴다(I8).
5. THE `PAID` 주문 SHALL 그 주문의 열린 최신 승인 진행 영수증과 같은 `admissionId`의 성공 결과로만 생긴다. 결제 식별자(`paymentId`)만으로 연결을 판정하지 않는다 — 주문당 `paymentId`가 하나로 고정되어 시도를 구분하지 못한다(`payment/db/V11__purchase_payment.sql:29` `uk_purchase_payment_order`). 연결되지 않은 성공은 Order가 반영하지 않고 거절 사실(`OrderPaymentResultRejected`)로 드러내며 Payment가 보류로 올린다([결정 153](../../../docs/decisions.md#late-success-rejection), I12, P3.14-2, P3.13-7, T1).
6. THE Payment SHALL 승인 진행 영수증이 없는 시도의 PG 결제로 결과 이벤트를 내지 않는다(I15, P3.11-2·P3.12-5).
7. THE PG 청구(`PAID` 관측)가 있는 결제 SHALL `PAID` 주문의 결과, 승인 보류, 주문에 연결되지 않은 청구 기록 중 하나에 연결된다. 발견은 웹훅에 기대지 않고 공급자 보장 기한까지의 주기 조회가 지킨다(I20, P3.13-8·9, [결정 154](../../../docs/decisions.md#unlinked-charge-discovery)).

#### P4.3 정방향 반영 (U3·U4·U5 → I14)

1. WHILE 앞 모듈의 기록(`purchase_order.status`=`PAID`, `order_refund.status`=`COMPLETED`, `refund_execution.refund_confirmed_at` 있음)이 있으면 시스템 SHALL 뒤 모듈의 반영(`product_license`·`settlement_sale`, `product_license.status`=`REFUNDED`·`settlement_refund`)을 결국 만든다. 반영이 기준 시간을 넘으면 감지·경보하고, DLT·원본 소실 사건을 같은 이벤트 ID로 다시 반영하는 운영 경로를 둔다(P2.2-7·P2.6-4). 감지는 사실을 낸 모듈이 응답 사실을 받지 못한 자기 기록을 세는 방식이고, 응답 사실이 없는 쌍에는 확인 사실을 새로 둔다(쌍 목록은 design "정방향 응답 쌍", [결정 158](../../../docs/decisions.md#forward-gap-counting)). 기준 시간은 4를 따른다(I14, T6).
2. THE 정산 환불 반영(I4의 Settlement 다리) SHALL 모든 결제 공급자 환경에서 같은 계약으로 성립한다. 시뮬레이터 전용 공급자(`SIMULATED`)는 두지 않는다([결정 162](../../../docs/decisions.md#single-reference-simulator), U4, T9).
3. THE 정산 금액 SHALL 첫 금융 확인(`RefundPaymentConfirmed`) 하나로 정해지고, Settlement는 귀속 보강 사실(`RefundExecutionSucceeded`)을 소비하지 않는다. 취소 귀속은 Settlement 자신의 PG 조회 근거(`settlement_refund_cancellation`)로 기록한다(U5 → P3.26-4).
4. THE 정방향 응답 쌍(design "정방향 응답 쌍")의 경보 기준 시간 SHALL 부하·장애 실험(G6)이 잰 정상 지연 분포로 쌍마다 정하고, 정하기 전에는 게이지(대기 건수·가장 오래 기다린 시간)만 낸다. 결정 125의 10분 경계는 그대로 둔다. 모든 쌍의 기준이 정해지기 전에는 G7(실제 PG 연동)을 완료하지 않는다([결정 159](../../../docs/decisions.md#forward-gap-threshold)).

#### P4.4 정산 합계·마감 (I5·I6·I7)

1. THE `CLOSED` `seller_monthly_settlement`의 `seller_payout_amount`·`refunded_seller_payout_amount`·건수 SHALL 그 `(seller_subject, settlement_month)`의 `settlement_sale`·`settlement_refund` 합계와 같고, 이월 원장은 `net + carried_in = payable + carried_out`, `payable = max(0, net + carried_in)`, `carried_in` = 앞 마지막 `CLOSED` 월의 `carried_out`이다(I5, 결정 121·130).
2. THE `CLOSED` 월 SHALL 합계와 최초 `closed_at`이 바뀌지 않고 `OPEN`으로 돌아가지 않으며, 마감 뒤 도착한 매출·환불은 현재 `OPEN` 월에 붙고 원래 `sale_month`·`refund_month`를 보존한다(I6, 결정 42·130).
3. IF PG 비용이 확인되지 않았으면 THEN 비용과 플랫폼 순액 SHALL 0이 아니라 NULL(`pgCostStatus`=`UNCONFIRMED`)이고, 판매자 월 마감은 PG 비용을 읽지 않는다(I7, 결정 39·142).

#### P4.5 시계와 식별자 (I9 → I13, U2)

1. THE Order·Payment·License·Settlement SHALL 다른 모듈이 찍은 시각(`occurredAt`·`confirmedAt`·`startedAt`·`restoredAt` 등)과 자기 시각, 또는 서로 다른 모듈이 찍은 두 시각의 선후로 이벤트·명령을 거절·분기하지 않는다. 같은 시도·정지·요청인지는 식별자(`attemptId`·`requestEventId`·`suspensionRequestEventId`·`suspensionEventId`·`restorationRequestEventId`·`refundId`·`admissionId`)와 잠금으로만 판정한다(원칙 ⑥, D-052, I13, T3).
2. WHERE 업무 규칙이 다른 모듈의 시각을 값으로 써야 하면(매출 귀속 월 = `OrderPaid.paymentConfirmedAt`(가칭, Payment 승인 확정 시각)의 KST 월(결정 166), 환불 귀속 월 = `RefundPaymentConfirmed.confirmedAt`의 KST 월, 결정 42) 시스템 SHALL 그 값을 그대로 기록에 쓰되 자기 시계와 비교해 거절하지 않는다.
3. WHERE 외부 결제 공급자가 찍은 시각(`requestedAt`·`cancelledAt`)이 근거에 있으면 Payment SHALL 그 값을 그대로 저장하고, 자기 관측 시각과의 선후로 PG 근거를 무효로 보지 않는다. 근거의 진위는 취소 ID·결제 ID·금액·상태로 판정한다([결정 163](../../../docs/decisions.md#external-clock-principle), 원칙 ⑥, T3·MC-P10). PG가 찍은 시각끼리의 순서 검사와 같은 모듈 안 시각끼리의 비교는 이 요구사항의 대상이 아니다.

#### P4.6 Payment 내부 승인 보류 (I10·I11)

1. WHILE 승인이 `REVIEW_REQUIRED`이면 Payment SHALL 처음 보류된 사유와 `review_started_at`을 유지하고, `PAID_WITHOUT_ADMISSION`으로 한 번 올리는 경우만 사유를 바꾼다(I10, 결정 144 — 사유 칸의 뜻 겹침은 소급 RC-2).
2. WHEN 결제가 `PAID`로 커밋되면 THEN Payment SHALL 같은 트랜잭션에서 접수된 열린 승인(`ADMITTED`·`REVIEW_REQUIRED`)을 `SUCCEEDED`로 닫는다(I11, D-051).

#### P4.7 환불 실패·복구 (I16~I19)

1. THE 실패 확정된 환불 시도 SHALL 실패 사실을 정확히 하나 갖는다: PG 확정 실패면 `RefundExecutionFailed`, 운영자 종결이면 `RefundFailureClosed`이고, Order 시도의 출처와 Payment 종결 기록이 서로 맞는다(I16).
2. THE 보유권 복구 SHALL 실패 확정(두 사실 중 하나) 뒤에만 생기고, 정지 1건당 복구 1건이다(I17).
3. THE 같은 환불 시도 SHALL 금융 완료와 실패를 함께 갖지 않는다(I18).
4. WHILE 운영자 실패 종결이 진행 중이면 그 실행의 PG 취소 요청 SHALL 나가지 않는다(I19).
5. THE 실패로 끝난 시도 뒤의 늦은 전액 취소 SHALL ADMIN의 PG 근거 반영(P3.23-7)으로만 Order·License·Settlement에 반영되고, 실패한 시도의 실패 사실과 금융 확인 칸은 바뀌지 않는다(I16·I18, 결정 157).

### P5. 품질 목표 (실험 뒤, 결정 152)

수치 목표(처리량·지연·복구 시간·정합성 회복 시간)는 부하·장애 실험으로 기준선을 잰 뒤 더한다. 실험 가설은 [design.md](design.md#모듈-간-흐름)의 흐름별 실패 표 행이고, 정방향 반영 기준 시간 T(결정 159: 게이지 먼저, 측정 뒤 확정, G7 전 관문)도 그때 수치로 정한다. 실패 보관 표는 기한 없이 보관하므로 재투입 기한은 두지 않는다(결정 160).
