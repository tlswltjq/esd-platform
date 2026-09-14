# Technical Interview Guide

이 저장소(STOVE 플랫폼)를 기술면접에서 설명하기 위한 학습 문서다.
코드를 요약하지 않는다. **"면접관이 이 코드를 보고 무엇을 물을까, 그 답을 들으면 다음엔 무엇을 물을까"** 에서
출발해, 그 질문에 답하는 데 필요한 DB·Kafka·분산 시스템 원리를 이 저장소의 코드에 붙여 설명한다.

| | |
|---|---|
| 기준 | `main` @ `1a182b8`, 분석일 2026-09-14 |
| 요구사항의 출처 | 저장소에 `/goal` 문서가 없다. [README](../README.md) 1행의 목표와 3절 "설계 과제와 해법" 표를 요구사항으로 삼았다 |
| 기존 문서와의 관계 | [decisions](decisions.md)·[defects](defects.md)·[code-notes](code-notes.md)·[event-ordering](event-ordering.md) 가 "왜 이렇게 했나"의 원본이다. 이 문서는 그것을 원리와 면접 질문으로 다시 엮고, **그 문서들이 다루지 않은 위험**을 더한다. 같은 설명은 링크로 대신한다 |
| 버전 | Spring Boot 3.5.6(`gradle.properties`)과 그 BOM 이 정하는 Spring Kafka 3.3.10 · kafka-clients 3.9.1 · Hibernate 6.6.29 · HikariCP 6.3.3 · Connector/J 9.4.0. 인프라는 MySQL 8.0 · Kafka 3.9.0 KRaft 단일 브로커(`docker-compose.yml`) |

## 읽기 전에

### 라벨

일반론과 이 프로젝트의 사실을 섞지 않으려고, 프로젝트에 관한 문장에는 근거의 종류를 붙인다.

| 라벨 | 뜻 |
|---|---|
| **[확인됨]** | 코드·설정·실험·라이브러리 소스로 직접 확인했다. 근거(`파일:줄`, 실험 번호)를 옆에 적는다 |
| **[코드상 추정]** | 코드 경로를 따라 추론했지만 그 상황을 실제로 재현하지는 않았다 |
| **[추가 확인 필요]** | 코드와 설정만으로는 판단할 수 없다 — 운영 환경, 부하 재측정, 비즈니스 결정이 필요하다 |
| **[현재 구현의 잠재적 문제]** | 지금 코드에서 사고로 이어질 수 있는 지점 |
| **[권장 개선안]** | 고친다면 이렇게 — 대가를 함께 적는다 |

개념 절은 "쉽게 이해하기 → 일반적인 동작 → 내 프로젝트에서는 → 면접 Q&A" 순서로 쓴다.
일반적인 동작 절의 내용은 이 프로젝트와 무관하게 성립하는 이야기다.

### 실험으로 확인한 것

격리 수준과 락은 말로 설명하면 틀리기 쉽다. 그래서 이 문서의 DB 주장 중 핵심 여섯 개는
MySQL 8.0 컨테이너에서 SQL 세션을 겹쳐 재현했고, [`scripts/db-lab/run.sh`](../scripts/db-lab/README.md)
한 번이면 같은 결과를 볼 수 있다(Docker 만 필요). 본문에서 "실험 N" 이라고 쓰면 그 스크립트의 N번이다.
로컬(Apple M1, Docker Desktop)에서 한 번, **측정용 원격 호스트(OCI Ampere A1, aarch64)에서 CI 를 멈추고 두 번** 돌렸고 세 번 모두 같은 결과였다.

같은 원격 호스트에서 **떠 있는 전체 스택**에도 읽기 전용으로 물었다 — MySQL 의 격리 수준과 커넥션 수, 브로커의 토픽·컨슈머 그룹 설정, 실행 중인 앱의 Kafka 클라이언트 설정.
그리고 R1 의 가설은 격리 수준만 바꾼 부하 A/B 로 확인했다(`scripts/perf/run-isolation-ab.sh`).

라이브러리 기본값도 추측하지 않고 jar 와 소스에서 읽었다 —
kafka-clients 3.9.1 의 `ConsumerConfig`/`ProducerConfig` 기본값, spring-kafka 3.3.10 의
`DeadLetterPublishingRecoverer`·`SeekUtils`·`FailedRecordTracker`·`ContainerProperties`·`KafkaMessageListenerContainer`.

---

## 0. 5시간 학습 우선순위

프로젝트 분석 결과로 다시 매긴 순서다. **"이 프로젝트에 실제 위험이 있는 곳"** 과 **"면접에서 반드시 나오는 곳"** 이 겹치는 주제를 앞에 뒀다.
현재 이해도는 "프레임워크 사용 경험은 있고 내부 동작은 얕다" 는 전제로 적었다.

| Priority | 주제 | 프로젝트 관련성 | 면접 가능성 | 현재 예상 이해도 | 중요도 | 시간 | 절 |
|---|---|---:|---:|---:|---:|---:|---|
| P0 | Kafka + DB 정합성 — Dual Write, Outbox, Inbox, 장애 케이스 | 매우 높음 | 매우 높음 | 낮음 | 매우 높음 | 50분 | [6](#6-kafka--db-consistency) |
| P0 | Offset 커밋과 전달 보장 — at-least-once, exactly-once 의 범위 | 높음 | 매우 높음 | 낮음 | 매우 높음 | 30분 | [5.7](#57-offset) [5.12](#512-delivery-semantics) |
| P0 | 트랜잭션 경계 — `@Transactional` 이 DB 에서 하는 일, 외부 호출의 자리 | 높음 | 높음 | 낮음 | 매우 높음 | 30분 | [4.2](#42-transaction) |
| P0 | 격리 수준 — MySQL REPEATABLE READ 에서 Lost Update 가 나는 이유 | 높음 (위험 R3) | 높음 | 낮음 | 매우 높음 | 25분 | [4.4](#44-isolation-level) |
| P1 | 락 — 레코드·갭·넥스트키 락, `FOR UPDATE SKIP LOCKED` | 높음 (위험 R1) | 중간 | 낮음 | 높음 | 25분 | [4.5](#45-lock) |
| P1 | 순서 보장 — 파티션 키, 세 층위, DLT 재투입이 순서를 뒤집는 경우 | 높음 (위험 R2) | 높음 | 낮음 | 높음 | 20분 | [5.9](#59-ordering) [5.11](#511-dlq--이-프로젝트에서는-dlt) |
| P1 | 재시도와 DLT — Spring Kafka `DefaultErrorHandler` | 높음 | 중간 | 낮음 | 높음 | 15분 | [5.10](#510-retry) |
| P1 | 멱등성 두 겹 — Inbox 와 도메인 유니크 | 높음 | 높음 | 보통 | 높음 | 15분 | [6.2](#62-idempotency) |
| P2 | Consumer Group · 리밸런싱 | 중간 | 높음 | 낮음 | 중간 | 20분 | [5.6](#56-consumer-group) [5.8](#58-rebalancing) |
| P2 | Saga · 보상 트랜잭션 (D-027/D-028) | 높음 | 중간 | 보통 | 중간 | 10분 | [3.3](#33-saga-보상--지급이-최종-실패하면) |
| P2 | MVCC · Undo | 중간 | 중간 | 낮음 | 중간 | 10분 | [4.6](#46-mvcc) |
| P2 | Connection Pool · 커넥션 총량 | 중간 | 중간 | 보통 | 중간 | 10분 | [4.8](#48-connection-pool) |
| P3 | 복제 · ISR · acks | 낮음 (단일 브로커) | 중간 | 낮음 | 낮음 | 10분 | [5.4](#54-producer) |
| P3 | 데드락 | 낮음 | 중간 | 낮음 | 낮음 | 10분 | [4.7](#47-deadlock) |
| P3 | CDC · Kafka 트랜잭션 | 낮음 (미사용) | 중간 | 낮음 | 낮음 | 10분 | [6.3](#63-transactional-outbox) |

주제별 시간을 더하면 4시간 50분이다. 아래 진행 순서는 여기에 1~3장으로 흐름을 잡는 시간과 11장 모범 답변·[14장 핵심 카드](#14-내가-반드시-암기이해해야-하는-핵심-개념)를 소리 내어 말해 보는 시간을 끼워 5시간에 맞췄다. 그래서 표의 시간은 정확한 배분이 아니라 주제 사이의 비중으로 읽는다.

**권장 진행 순서**

| 블록 | 시간 | 할 일 |
|---|---|---|
| 1 | 0:00–0:40 | 1~3장으로 흐름을 머리에 올린다. 3.1 의 트랜잭션 표시(`[TX]`)를 손으로 따라 그려 본다 |
| 2 | 0:40–1:50 | 6장 전체 → 5.7 Offset → 5.12 전달 보장. 6.4 의 장애 케이스를 표를 가리고 말로 설명해 본다 |
| 3 | 1:50–3:00 | 4.2 트랜잭션 → 4.4 격리 수준 → 4.5 락. `scripts/db-lab/run.sh 1 4` 를 직접 돌려 본다 |
| 4 | 3:00–4:00 | 5.9~5.11 → 8장 위험 R1·R2·R3 → 10장 꼬리질문 체인 A·B·D |
| 5 | 4:00–5:00 | 5.6/5.8 리밸런싱, 4.6~4.8 → 11장 모범 답변을 소리 내어 → 14장 카드 |

---

## 1. 프로젝트 한눈에 이해하기

### 1.1 한 줄로

게임 스토어의 **입점 → 심의 → 상품 → 주문 → 결제 → 지급 → 다운로드 → 정산**을 9개 서비스로 나누고,
서비스 사이를 **Kafka 이벤트**로 이은 Java 21 / Spring Boot 모노레포다.
이벤트로 이은 대가(유실·중복·순서·부분 실패)를 **Transactional Outbox · Inbox 멱등 가드 · 파티션 키 순서 · Saga 보상**으로 치른다.

### 1.2 무엇을 해결하는가 — 요구사항과 실제 구현

README 3절이 내건 과제와 해법을 코드와 대조한 결과다. 오른쪽 열이 이 문서가 새로 짚는 부분이다.

| 과제 (README) | 해법 | 구현 | 어긋나거나 조건이 붙는 곳 |
|---|---|---|---|
| DB 커밋과 Kafka 발행의 원자성 | Transactional Outbox | **[확인됨]** `OutboxRecorder` 가 `MANDATORY` 로 비즈니스 트랜잭션 안에서만 적재 (`OutboxRecorder.java:32`) | 릴레이가 REPEATABLE READ 에서 갭 락을 쥔 채 Kafka ack 를 기다린다 → 평시에도 주문 INSERT 의 7% 가 락을 기다리고(OCI 실측), **브로커 지연·장애가 쓰기 API 로 번질 수 있다** ([R1](#r1-릴레이가-갭-락을-쥔-채-kafka-ack-를-기다린다)) |
| 중복 수신 | Inbox `(event_id, consumer_group)` | **[확인됨]** 처리와 같은 트랜잭션에서 마킹 (`ProcessedEventGuard.java:21-33`) | 중복은 막지만 **DLT 재투입이 만드는 순서 역전은 못 막는다** ([R2](#r2-dlt-재투입과-dead-회수가-같은-키의-순서를-뒤집는다)) |
| 결제 후 지급 실패 | Saga 보상 (`LicenseIssueFailed` → 환불) | **[확인됨]** recoverer 에서만 시작 (`KafkaErrorHandlerConfig.java:49-92`) | D-027 이후 저장소 장애는 보상 대신 DLT 로 보류된다. 실측 보상 0건 — "자동 보상"은 드문 경로다 |
| 금액 위·변조 | 검증 게이트 4단계 | **[확인됨]** 서버 재계산 · 사전등록 · 콜백 금액 대조 | README·services.md 는 게이트 4 를 "`idempotency_key` 유니크 제약"이라고 쓰지만 **그 제약은 `V2__scope_idempotency_key.sql` 이 지웠다.** 지금 막는 것은 상태 검사 + `SELECT … FOR UPDATE` 다 |
| 같은 애그리거트의 순서 | 파티션 키 + 릴레이 키 웨이브 | **[확인됨]** `OutboxRelay#publishPreservingOrder` | 발행 층까지는 지킨다. DLT·DEAD 를 되돌리는 순간 뒤집힌다(R2). 릴레이 1대 제약은 문서에만 있고 강제되지 않는다([R13](#r13-릴레이-1대-제약이-강제되지-않는다)) |
| 정산 중복 집계 | Inbox + 원장 유니크 | **[확인됨]** `uk_settlement_record(order_no, product_id, record_type)` | 귀속 월이 이벤트 시각이 아니라 **소비 시각**이다([R12](#r12-정산-귀속-월이-이벤트-시각이-아니라-소비-시각이다)) |
| store·download 멱등 | 문서 ID 고정 upsert(자연 멱등) | **[확인됨]** `StoreService.java:34`, `EntitlementService.java:24-28` | 같은 이벤트의 중복에는 안전하다. **옛 이벤트가 늦게 오면 최신 상태를 덮는다** — 멱등이지만 교환법칙은 성립하지 않는다 |

### 1.3 기술 스택

| 영역 | 사용 | 이 프로젝트에서 맡는 일 |
|---|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 3.5.6, Spring Data JPA(Hibernate 6.6) | 서비스 9종 + 게이트웨이 |
| RDB | MySQL 8.0, Flyway, `ddl-auto: validate` | 서비스마다 스키마 분리(7개). 트랜잭션·정합성이 필요한 도메인 |
| 메시징 | Kafka 3.9 (KRaft), Spring Kafka 3.3 | 토픽 6개(애그리거트 단위) + `*.DLT` |
| 읽기 모델 | Elasticsearch 8, Redis 7 | store 검색 색인, catalog·store 캐시 |
| 문서 저장소 | MongoDB 7 | download 의 매니페스트·권한 사본 |
| 분산 락 | ShedLock 6.9 (MySQL 테이블) | 스케줄러 단일 실행 — 환불 재개, 주문 만료, 월 정산 |
| 관측 | Micrometer, Prometheus, kafka-exporter, Tempo | 지표·알람·분산 추적(Outbox 를 건너는 traceparent 전파) |
| 검증 | JUnit, Testcontainers, ArchUnit, k6 | 계층 규칙·순서 규칙을 테스트로 강제, 부하·장애 측정 |

### 1.4 30초 소개 — 면접 첫 답변

> 게임 스토어 백엔드를 주문·결제·지급·정산까지 9개 서비스로 나누고 Kafka 이벤트로 연결한 프로젝트입니다.
> 서비스를 이벤트로 이으면 한 서비스가 죽어도 다른 서비스가 멈추지 않는 대신, 이벤트가 사라지거나 두 번 오거나
> 순서가 바뀌는 문제가 생깁니다. 저는 그걸 Transactional Outbox 로 유실을 막고, Inbox 테이블과 유니크 제약으로
> 중복을 흡수하고, 주문번호를 파티션 키로 써서 같은 주문의 순서를 지키는 방식으로 풀었습니다.
> 결제처럼 되돌릴 수 없는 외부 호출은 트랜잭션 밖으로 빼고, 중간에 멈춰도 재개할 수 있게 의도를 먼저 기록합니다.
> 장애를 실제로 넣어 보면서 정상 결제가 환불되던 결함 같은 걸 찾아 고쳤습니다.

### 1.5 면접관이 가장 먼저 찌를 곳

| 순위 | 지점 | 왜 찌르나 | 절 |
|---|---|---|---|
| 1 | Outbox 릴레이 | Kafka + DB 조합의 교과서 질문이 전부 여기서 나온다. 중복 발행, 순서, 락, 다중화 | 6.3, R1 |
| 2 | 컨슈머의 DB 커밋 ↔ 오프셋 커밋 | "커밋 뒤 죽으면?" "그럼 exactly-once 인가?" | 5.7, 6.4 |
| 3 | 결제 콜백의 `FOR UPDATE` | 동시 중복 콜백, 비관적/낙관적 락 선택 | 4.5 |
| 4 | Saga 보상 조건 | "실패했으니 환불" 이 왜 틀렸나 (D-027/D-028) | 3.3 |
| 5 | DLT 와 순서 | "DLT 로 빼면 뒤 메시지가 먼저 처리되는데요?" | 5.11, R2 |

---

## 2. 전체 Architecture

### 2.1 시스템 그림

```text
 client ──HTTP──▶ gateway :8080   (라우팅. 내부 API — quote, ops — 는 라우팅하지 않는다)
                     │
     ┌───────┬───────┼────────┬────────┬─────────┬─────────┬──────────┬────────────┐
     ▼       ▼       ▼        ▼        ▼         ▼         ▼          ▼            ▼
  studio  review  catalog   store    order    payment   license   download   settlement
  MySQL   MySQL   MySQL     ES       MySQL    MySQL     MySQL     MongoDB    MySQL
                  +Redis    +Redis
    │ ▲     │ ▲     │ ▲       ▲        │ ▲      │ ▲       │ ▲        ▲            ▲
 발행│ │구독 │ │     │ │       │구독    │ │      │ │       │ │        │ 구독       │ 구독
    ▼ │     ▼ │     ▼ │       │        ▼ │      ▼ │       ▼ │        │            │
 ┌───────────── Kafka 3.9 KRaft · 브로커 1대 · 토픽 6 + *.DLT · 파티션 3 · 복제 계수 1 ─────────────┐
 │ stove.studio.v1  stove.review.v1  stove.catalog.v1  stove.order.v1  stove.payment.v1  stove.license.v1 │
 └──────────────────────────────────────────────────────────────────────────────────────────────────┘

 서비스 사이의 동기 호출  order ──HTTP──▶ catalog  (주문 금액 확정) — 이것 하나뿐이다
 외부 시스템 호출          payment ↔ PG (사전등록·취소 / 승인·거절 웹훅), review → 게임물관리위원회,
                          settlement → 세금계산서 — 셋 다 지금은 스텁이다(services.md "외부 연동 대역")
 store · download · settlement 는 발행하지 않는다. 누가 무엇을 구독하는지는 2.2 표에 있다.
```

**[확인됨]** 서비스 간 동기 호출은 order → catalog 가격 확정 하나뿐이다(`CatalogRestAdapter`, connect 1s / read 2s).
decisions.md 23번의 기준이 그대로 코드에 있다 — "상태 변경 전파는 이벤트, 포트는 이 트랜잭션 전에 확정된 값이 필요할 때만".

### 2.2 토픽과 구독자

| 토픽 | 발행(Outbox 주인) | 파티션 키 | 구독 컨슈머 그룹 (리스너) |
|---|---|---|---|
| `stove.studio.v1` | studio | productCode | review(`StudioEventListener`), download(`DownloadEventListener#onStudioEvent`) |
| `stove.review.v1` | review | productCode | catalog(`ReviewEventListener`), studio(`ReviewEventListener`) |
| `stove.catalog.v1` | catalog | productCode | store(`CatalogEventListener`), download(`#onCatalogEvent`) |
| `stove.order.v1` | order | orderNo | payment(`OrderEventListener`) |
| `stove.payment.v1` | payment | orderNo | order · license · settlement (`PaymentEventListener` ×3) |
| `stove.license.v1` | license | orderNo | payment(`LicenseEventListener`), download(`#onLicenseEvent`) |

**[확인됨]** 리스너 12개, 그룹 이름 = 서비스 이름. 한 서비스가 리스너를 여럿 가지면 **같은 그룹에 멤버가 여럿**이다
(payment 2, download 3). 한 토픽에 여러 이벤트 타입이 섞이고, 리스너는 본문을 파싱하기 전에 `eventType` 헤더로 거른다(`EventEnvelope`).

### 2.3 서비스 한 개의 내부

```text
 api/                                  core/                                  infrastructure/
 ├ controller  (HTTP)    ─┐            ├ service   ← 트랜잭션 경계는 여기뿐     ├ client  (catalog HTTP)
 ├ listener    (Kafka)   ─┼──▶ 호출 ──▶│  @Transactional                         ├ pg / board / tax (스텁)
 ├ scheduler   (스윕)    ─┤            │   ├ domain 엔티티 · 리포지토리           └ storage (S3/MinIO)
 └ application (파사드)  ─┘            │   ├ OutboxRecorder.record()  MANDATORY
    트랜잭션을 열지 않는다              │   └ ProcessedEventGuard      MANDATORY
    = 외부 호출을 트랜잭션 밖에 둘 자리  └ port (외부 시스템 인터페이스)

 common/messaging:  OutboxRelay (@Scheduled 200ms) ── FOR UPDATE SKIP LOCKED ──▶ KafkaTemplate.send() ── ack ──▶ SENT
 common/kafka:      DefaultErrorHandler (1s·2s·4s 재시도) ──소진──▶ <topic>.DLT, 운영 API 로 재투입
```

**[확인됨]** 이 규칙은 문서가 아니라 ArchUnit 이 강제한다 — `트랜잭션_경계는_core_service_다`,
`멱등_가드는_서비스가_소유한다`, `리스너는_다른_스레드로_넘기지_않는다`, `논블로킹_재시도를_쓰지_않는다`.

### 2.4 Database per Service — 면접관이 반드시 되묻는 부분

**[확인됨]** 서비스마다 스키마를 나눴지만(`infra/mysql/init/01-create-schemas.sql`) **로컬 구성은 MySQL 인스턴스 하나를 공유**한다.

| 무엇을 나눴나 | 무엇을 공유하나 |
|---|---|
| 스키마·계정 권한, 서비스 간 조인·외래키 불가, Flyway 이력 | 인스턴스의 CPU·버퍼 풀·디스크, `max_connections`(151), 장애 도메인 |

"논리적으로는 분리, 물리적으로는 공유" 라고 정확히 말하는 편이 낫다.
chaos 실험이 컨테이너를 끄지 않고 권한을 뺏은 이유도 여기 있다(`scripts/chaos/fault.sh` 주석) — MySQL 을 끄면 order·payment 까지 같이 죽는다.

---

## 3. 핵심 Request / Event Flow

### 3.1 구매 → 결제 → 지급 → 정산

`[TX-n]` 은 DB 트랜잭션 하나, `══▶` 는 Kafka 를 건너는 구간이다.

```text
[HTTP] POST /api/v1/orders
  PlaceOrderFacade.place()                        트랜잭션 없음 — 동기 HTTP 가 들어오는 자리
   ├─ CatalogRestAdapter.quote()  ──HTTP──▶ catalog  (서버 가격으로 재계산, 검증 게이트 1)
   └─ OrderCommandService.createOrder()           [TX-1] INSERT orders, order_item
                                                         INSERT outbox_event(OrderCreated, key=orderNo)   ← 같은 커밋
        ↓ 최대 200ms 뒤
  OutboxRelay.relayOneBatch()                     [TX-R] SELECT … FOR UPDATE SKIP LOCKED
                                                         send() → ack 대기 → UPDATE status='SENT'
  ══▶ stove.order.v1
  payment: OrderEventListener → PaymentService.createReady()
                                                  [TX-2] INSERT processed_event(eventId,'payment')
                                                         INSERT payment(READY)

[HTTP] POST /api/v1/payments/{orderNo}/prepare
  PaymentService.prepare()                        [TX-3] SELECT payment
                                                         PG 사전등록  ← 외부 호출이 트랜잭션 안에 있다 (R4)
                                                         UPDATE payment → PENDING

[HTTP] POST /api/v1/payments/callback  (PG 웹훅)
  PaymentCallbackFacade.approve()
   └─ PaymentService.handleApproval()             [TX-4] SELECT … FROM payment WHERE order_no=? FOR UPDATE
                                                         UPDATE payment → PAID (금액 대조, 검증 게이트 3)
                                                         INSERT outbox_event(PaymentCompleted)
  ══▶ stove.payment.v1  — 그룹 셋이 각자 한 번씩 받는다
   ├─ order      OrderCommandService.confirmPaid()      [TX] processed_event + UPDATE orders → PAID
   ├─ license    LicenseService.issue()                 [TX] processed_event + INSERT license
   │                                                          + INSERT outbox_event(LicenseIssued)
   │     ══▶ stove.license.v1 → download: EntitlementService.grant()  (MongoDB upsert, 트랜잭션 없음)
   └─ settlement SettlementRecordService.recordSale()   [TX] processed_event + INSERT settlement_record(SALE)
```

이 흐름에서 외워 둘 것은 셋이다.

1. **HTTP 요청 하나가 끝나는 시점에 Kafka 에는 아무것도 나가지 않았다.** 커밋된 것은 outbox 행이고, 발행은 릴레이가 나중에 한다.
2. **컨슈머 쪽 트랜잭션은 언제나 "Inbox 마킹 + 비즈니스 변경 (+ 다음 이벤트 적재)" 한 덩어리**다.
3. **서비스 사이의 순서는 인과관계가 잡는다.** license 는 `PaymentCompleted` 를 받아야 `LicenseIssued` 를 만들 수 있다.
   따로 지켜야 하는 순서는 한 Outbox 안의 "생성 → 소멸" 짝뿐이다([event-ordering.md](event-ordering.md) 2절).

### 3.2 환불 — 되돌릴 수 없는 호출을 가운데 둔 세 걸음

```text
[HTTP] POST /api/v1/payments/{orderNo}/cancel
  RefundFacade.refund()                            트랜잭션을 열지 않는다
   ├─ PaymentService.beginCancel()          [TX-a] PAID → CANCELING (의도를 먼저 커밋), next_cancel_attempt_at = +2분
   ├─ PgClient.cancel()                            PG 환불 — 트랜잭션 밖. pgTxId 기준 멱등이 계약이다
   └─ PaymentService.completeCancel()       [TX-b] CANCELING → CANCELED + outbox(PaymentCancelled)
  ══▶ stove.payment.v1
   ├─ license    revoke → outbox(LicenseRevoked) ══▶ download: 권한 회수(주문번호 대조, D-012)
   ├─ order      cancel
   └─ settlement recordRefund — 자기 원장의 SALE 을 부호 반전해 REFUND 적재

  어디서 멈추든 흔적이 남는다:
   TX-a 전  → 아무 일도 없었다
   PG 에서  → CANCELING 으로 남는다 → RefundSweeper(1분, ShedLock)가 재개. PG 멱등이라 다시 걸어도 이중 환불이 아니다
   TX-b 에서 → 위와 같다
```

면접에서 이 구조를 한 문장으로 말하면 이렇다 — **"되돌릴 수 없는 외부 호출은 트랜잭션에 넣지 않고, 호출 전에 의도를 커밋하고, 호출이 멱등이라는 계약 위에서 재시도한다."**
같은 모양이 정산 마감(세금계산서 발행, `SettlementCloseFacade`)에도 있다(D-022).

### 3.3 Saga 보상 — 지급이 최종 실패하면

```text
license: PaymentEventListener → LicenseService.issue()   예외 → 트랜잭션 롤백(Inbox 마킹도 함께 롤백)
  DefaultErrorHandler: seek 로 되감아 1s·2s·4s 뒤 재시도 (총 4번 시도)
  재시도 소진 → recoverer (KafkaErrorHandlerConfig)
     관문 1: 원인이 저장소 장애인가? (DataAccessException · TransactionException · SQLException, 원인 사슬 전체)
             예 → 보상하지 않고 DLT 로 보류          ← "판단 불가" 는 "지급 불가" 가 아니다 (D-027)
     관문 2: 이 주문에 라이선스가 이미 있는가?
             예 → 보상하지 않고 DLT 로 보류          ← 커밋 후 오프셋 커밋 전 재배달일 수 있다 (D-028)
     둘 다 아니면 → recordIssueFailure() [REQUIRES_NEW] outbox(LicenseIssueFailed)
  ══▶ stove.license.v1 → payment: RefundFacade.compensate() → 3.2 의 세 걸음
```

**[확인됨]** 실측 license outbox 4,153건 중 `LicenseIssueFailed` 0건([defects.md D-027](defects.md#d-027)).
보상 경로는 살아 있지만 거의 지나가지 않는다. 대신 **지급 실패의 주 경로가 DLT 보류**가 됐고,
그 선택이 [R2](#r2-dlt-재투입과-dead-회수가-같은-키의-순서를-뒤집는다)의 가능성을 키운다.

### 3.4 크리에이터 트랙

```text
studio  submitForReview [TX] → GameRegistered ══▶ review.receive [TX] (자체등급이면 즉시 승인) → ReviewApproved
  ══▶ catalog.upsertFromReview [TX] → ProductChanged ══▶ store 색인(ES upsert) / download 상품 참조(Mongo upsert)
  ══▶ studio.applyApproval [TX]
```

**[확인됨]** `ReviewService.receive()` 도 외부 호출(`RatingBoardClient.submit`, 게임물관리위원회 접수)을
컨슈머 트랜잭션 안에서 부른다(`ReviewService.java:82`). 지금은 스텁이라 영향이 없다 — R4 에서 다룬다.

### 3.5 사용자가 보는 결과적 일관성

**[확인됨]** 주문 생성 응답을 받은 직후 결제 사전등록을 부르면 `PAYMENT_NOT_FOUND` 가 날 수 있다.
결제 대기 행은 `OrderCreated` 가 소비된 뒤에야 생기기 때문이다(`PaymentService.java:218-221`).
인수 테스트도 사전등록 전에 결제 대기가 생길 때까지 폴링한다(`TrackBCommerceFlowTest#createsPendingPayment`, `Await.untilResponse`).

면접에서 "비동기로 바꾸면 사용자는 뭘 보나요?" 가 나오면 이 사례를 쓴다 — 릴레이 폴링 주기(200ms)와 컨슈머 지연만큼
**클라이언트가 기다리거나 재시도해야 하는 창**이 생기고, 그 창을 줄이는 방법(적응형 폴링, CDC)과 드러내는 방법(재시도 가능한 오류 코드)이 설계 선택지다.

---

## 4. Database

### 4.1 Schema

**한 줄 요약** — 정합성의 마지막 방어선을 코드가 아니라 **DB 제약(유니크)** 에 뒀다.
애플리케이션의 "있는지 확인하고 넣기"는 동시에 두 번 오면 둘 다 통과하지만, 유니크 인덱스는 통과시키지 않는다.

**내 프로젝트에서는** [확인됨 — 각 서비스 `db/migration`]

| 서비스 | 테이블 | 정합성을 지키는 제약·인덱스 | 무엇을 막나 |
|---|---|---|---|
| order | `orders` | `uk_orders_order_no`, `idx_orders_status_created (status, created_at)` | 주문번호 중복 / 만료 스윕 조회를 인덱스만으로 |
| payment | `payment` | `uk_payment_order_no`, `idx_payment_idempotency` (**유니크 아님**, V2), `idx_payment_cancel_retry (status, next_cancel_attempt_at)` | 주문당 결제 1건 / 멱등키는 조회용 / 환불 재개 스윕 |
| license | `license` | `uk_license_order_product (order_no, product_id)`, `uk_license_key` | 한 주문의 한 상품은 한 번만 지급 |
| settlement | `settlement_record` | `uk_settlement_record (order_no, product_id, record_type)` | 매출·환불 중복 집계 |
| settlement | `seller_settlement` | `uk_seller_settlement (seller_id, settlement_month)` | 판매자·월 확정본 1건 |
| 7개 서비스 (settlement 는 테이블만 있고 릴레이를 끈다) | `outbox_event` | `uk_outbox_event_id`, `idx_outbox_pending (status, next_attempt_at, id)` | 같은 이벤트 이중 적재 / 릴레이 조회 |
| Inbox 를 쓰는 7개 | `processed_event` | `uk_processed_event (event_id, consumer_group)` | 같은 메시지 두 번 처리 |
| order · payment · settlement | `shedlock` | `PRIMARY KEY (name)` | 스케줄러 동시 실행 |

그 밖에 알아둘 것.

- 금액은 `BIGINT`(원 단위 정수), 수수료율만 `DECIMAL(5,4)` 다. 부동소수 오차를 원천 차단한다.
- 서비스 사이에는 외래키가 없다(스키마가 다르다). 참조 무결성은 이벤트 전달 보장과 멱등 소비로 대신한다.
- 스키마는 Flyway 로만 바꾸고 JPA 는 `ddl-auto: validate` — 엔티티와 스키마가 어긋나면 기동이 실패한다.
- 시각 컬럼에 `DATETIME(6)` 과 `TIMESTAMP(3)` 이 섞여 있다(예: `orders.expired_at`, `payment.next_cancel_attempt_at`).
  `TIMESTAMP` 는 세션 시간대로 변환되고 `DATETIME` 은 그렇지 않다 — 시간대 버그를 물으면 이 차이부터 말한다.

**면접 Q&A**

- **Q (L2)** 애플리케이션에서 존재 여부를 확인하는데 유니크 제약을 왜 또 거나요?
  - 확인과 삽입 사이에 다른 트랜잭션이 끼어들 수 있기 때문입니다. MVCC 스냅샷에서는 상대의 미커밋 행이 보이지 않아서 둘 다 "없음"을 보고 둘 다 넣으려 합니다. 유니크 인덱스는 그 순간 뒤 트랜잭션을 기다리게 했다가 1062 로 거절합니다(실험 5). 애플리케이션 확인은 흔한 경우를 예외 없이 빨리 끝내는 용도이고, 정확성은 제약이 책임집니다.
- **꼬리 (L3)** 유니크 위반이 나면 트랜잭션은 어떻게 되나요?
  - MySQL 은 그 문장만 실패시키고, 스프링이 `DataIntegrityViolationException` 으로 받아 트랜잭션 전체를 롤백합니다. 컨슈머 경로면 에러 핸들러가 재시도하고, 재시도 때는 앞 트랜잭션이 커밋한 행이 보여 "이미 처리"로 끝납니다.
- **Q (L2)** 결제의 멱등키에는 왜 유니크를 안 걸었나요?
  - PG 가 만드는 값이라 전역 유일성을 우리가 보장할 수 없어서입니다. 실제로 재사용된 키가 다른 주문의 승인을 삼키는 결함이 있었습니다(D-008). 그래서 유니크는 주문번호에 걸고, 같은 주문의 중복 콜백은 행 잠금과 상태 검사로 막습니다.

---

### 4.2 Transaction

**한 줄 요약** — 여러 SQL 을 "전부 반영되거나 전부 없던 일"로 묶는 단위다.
이 프로젝트에서 그 단위는 거의 언제나 **"비즈니스 변경 + outbox 적재 (+ inbox 마킹)"** 한 덩어리다.

**쉽게 이해하기**

주문을 저장하는 일과 "주문이 생겼다"는 소식을 남기는 일을 봉투 하나에 넣고 봉인한다고 생각하면 된다.
봉투는 통째로 우체통에 들어가거나(커밋) 통째로 찢어진다(롤백). 주문만 들어가고 소식은 빠진 봉투는 존재할 수 없다.

**일반적인 동작 — `@Transactional` 이 DB 까지 가는 길**

```text
OrderController.create()
 └▶ PlaceOrderFacade.place()                     스프링 빈이지만 @Transactional 이 없다 → 트랜잭션 없음
     └▶ [프록시] OrderCommandService.createOrder()
          TransactionInterceptor
           └ JpaTransactionManager.getTransaction(REQUIRED)
               ├ EntityManager 생성, HikariCP 에서 커넥션 대여
               └ connection.setAutoCommit(false)            ← DB 입장에서 트랜잭션의 시작
          메서드 본문
           ├ orderRepository.save(order)   → INSERT orders, order_item   (IDENTITY 라 save 시점에 바로 나간다)
           └ outboxRecorder.record(...)    → [프록시] MANDATORY 확인 → INSERT outbox_event
          정상 리턴 → flush(더티 체킹으로 모인 UPDATE) → COMMIT → 커넥션 반납
          RuntimeException → ROLLBACK → 커넥션 반납 → 예외는 그대로 위로
```

외워 둘 규칙.

| 규칙 | 내용 | 이 프로젝트에서 |
|---|---|---|
| 프록시 | 같은 클래스 안에서 `this.method()` 로 부르면 트랜잭션이 적용되지 않는다 | `ProductReindexFacade` 가 반복을 다른 클래스로 뺀 이유(code-notes) |
| 롤백 규칙 | 기본은 `RuntimeException`·`Error` 만 롤백. 체크 예외는 커밋된다 | 서비스 예외는 전부 `BusinessException`(런타임) |
| `REQUIRED` | 있으면 참여, 없으면 새로 연다 | 서비스 기본값 |
| `MANDATORY` | 없으면 예외. **혼자서는 절대 트랜잭션을 열지 않는다** | `OutboxRecorder.record`, `ProcessedEventGuard.firstDelivery` — 원자성을 규칙으로 강제 |
| `REQUIRES_NEW` | 바깥을 멈추고 새로 연다. **커넥션을 하나 더 쓴다** | `LicenseService.recordIssueFailure` — 지급 트랜잭션이 롤백된 뒤에도 보상 이벤트는 커밋돼야 한다 |
| `readOnly = true` | 커넥션을 읽기 전용으로 두고 Hibernate 가 더티 체킹 스냅샷을 만들지 않는다 | `*QueryService` |

**InnoDB 에서 벌어지는 일**

- `autocommit=0` 다음 첫 문장부터 트랜잭션이다. 쓰기를 해야 트랜잭션 ID 를 받는다.
- REPEATABLE READ 에서는 **첫 일관 읽기(일반 SELECT) 시점에 스냅샷(Read View)** 이 만들어진다.
- 쓰기는 undo 로그(롤백·옛 버전용)와 redo 로그(내구성용)를 남기고, 바꾼 행의 락은 **커밋이나 롤백 때까지** 쥔다.
- COMMIT 은 redo 를 디스크에 내리고(`innodb_flush_log_at_trx_commit=1`) binlog 를 쓴 뒤(`sync_binlog=1`) 락을 푼다. 둘 다 [확인됨] MySQL 8.0 기본값.

**내 프로젝트에서는 — 트랜잭션 경계 지도** [확인됨]

규칙은 하나다 — **트랜잭션은 `core.service` 에서만 열고, 트랜잭션 밖에 둬야 할 것(외부 호출·대기)이 있으면 `api.application` 파사드가 그 사이를 조율한다**(ArchUnit `트랜잭션_경계는_core_service_다`).

| 진입점 | 트랜잭션 | 안에서 쓰는 것 | 안에 든 외부 I/O | 판정 |
|---|---|---|---|---|
| 주문 생성 | `OrderCommandService.createOrder` | orders, order_item, outbox | 없음 — catalog 호출은 파사드 | 좋음 |
| 결제 대기 생성 (컨슈머) | `PaymentService.createReady` | processed_event, payment | 없음 | 좋음 |
| 결제 사전등록 | `PaymentService.prepare` | payment | **PG 사전등록** (`PaymentService.java:73-74`) | [R4](#r4-외부-호출이-트랜잭션-안에-남은-두-자리) |
| 승인 콜백 | `PaymentService.handleApproval` | payment(`FOR UPDATE`), outbox | 없음 — 만료 환불의 PG 호출은 파사드 | 좋음 |
| 환불 | `beginCancel` → (PG) → `completeCancel` 둘로 쪼갬 | payment, outbox | 없음 — PG 취소가 두 트랜잭션 사이 | 좋음 |
| 지급 (컨슈머) | `LicenseService.issue` | processed_event, license, outbox | 없음 | 좋음 |
| 정산 원장 (컨슈머) | `SettlementRecordService.recordSale/recordRefund` | processed_event, settlement_record | 없음 | 좋음 |
| 월 마감 | `SellerSettlementService.closeSeller` (판매자마다) | settlement_record, seller_settlement | 없음 — 계산서 발행은 파사드 | 좋음 |
| 빌드 등록 | `GameBuildService.upload` | game_build, outbox | S3 presign — SDK 가 로컬에서 서명, 네트워크 없음 | 좋음 |
| 심의 접수 (컨슈머) | `ReviewService.receive` | processed_event, review_request, outbox | **게임위 접수** (`ReviewService.java:82`) | R4 |
| 주문 만료 스윕 | `OrderExpiryService.expireStaleOrders` (500건) | orders | 없음 | 락 없는 갱신 → [R3](#r3-주문-만료-스윕과-결제-확정의-lost-update) |
| **Outbox 릴레이** | `OutboxRelay.relayOneBatch` (배치마다) | outbox_event(`FOR UPDATE SKIP LOCKED`) | **Kafka 전송 + ack 대기** (`OutboxRelay.java:84`, `:119`) | [R1](#r1-릴레이가-갭-락을-쥔-채-kafka-ack-를-기다린다) |

**범위가 너무 넓으면, 너무 좁으면** — 전부 이 저장소에서 실제로 있었던 일이다.

| | 무슨 일이 | 사례 |
|---|---|---|
| 넓으면 | 락과 커넥션을 오래 쥔다 | 릴레이가 Kafka 왕복 동안 갭 락을 쥔다(R1) |
| 넓으면 | 되돌릴 수 없는 호출 뒤에 롤백이 온다 | 월 마감이 한 트랜잭션이던 시절, 87번째 판매자에서 예외 → 앞의 86장 계산서는 이미 발행된 채 장부만 롤백(D-022) |
| 넓으면 | 락·undo 가 한 번에 커진다 | 만료 스윕이 밀린 98,750건을 한 번에 집을 뻔했다 → 회차당 500건 |
| 좁으면 | 원자성이 깨진다 | outbox 적재를 별도 커밋으로 빼면 "주문은 있는데 이벤트는 없는" 유실(Dual Write) |
| 좁으면 | 조용한 유실 | Inbox 마킹을 따로 커밋하면 "마킹은 됐는데 처리는 롤백" → 재배달이 와도 "이미 처리"로 버린다 |
| 좁으면 | 금액이 사라진다 | 정산의 "미마감 원장 읽기"와 "close" 를 나누면 그 사이 들어온 원장이 마감됐는데 어디에도 없게 된다 |

**여러 Repository 호출을 한 트랜잭션에 묶는 이유** — 묶인 쓰기들이 **하나의 사실**을 이루기 때문이다.
"주문이 생겼다"는 사실은 `orders` 행과 `outbox_event` 행이 **둘 다** 있어야 성립하고,
"이 결제 이벤트를 처리했다"는 사실은 `processed_event` 행과 `license` 행이 **둘 다** 있어야 성립한다.

**외부 API 를 트랜잭션 안에서 부르면**

1. 외부 응답을 기다리는 동안 **커넥션과 락을 쥔다.** 외부가 느려지면 풀이 마르고 같은 행을 기다리는 요청이 줄 선다.
2. 외부 호출이 성공한 뒤 커밋이 실패하면 **외부 효과는 롤백되지 않는다.** 환불은 나갔는데 장부는 그대로다(D-006).
3. 외부가 실패하면 **로컬 작업까지 롤백**된다. 재시도하면 외부 호출이 한 번 더 나간다 — 외부가 멱등이 아니면 중복이다.

이 프로젝트의 처방은 [3.2](#32-환불--되돌릴-수-없는-호출을-가운데-둔-세-걸음)의 세 걸음이다 — 의도 커밋 → 트랜잭션 밖 외부 호출(멱등 계약) → 확정 커밋, 멈추면 스윕이 재개.

**`@Transactional` 이 DB 에서 의미하는 것** — `handleApproval` 한 번이 MySQL 에 보내는 것은 대략 이렇다.

```sql
SET autocommit = 0;
SELECT … FROM payment WHERE order_no = 'ORD…' FOR UPDATE;     -- 결제 행 X 락 (동시 콜백은 여기서 줄 선다)
INSERT INTO outbox_event (…) VALUES (…);                      -- PaymentCompleted. IDENTITY 라 save 시점에 즉시
UPDATE payment SET status = 'PAID', pg_tx_id = …, … WHERE id = ?;  -- 엔티티 변경은 커밋 직전 flush 에서
COMMIT;                                                        -- redo/binlog 기록, 락 해제
```

INSERT 와 UPDATE 의 순서는 Hibernate 기본 동작에서 추론한 것이다 **[코드상 추정]** — SQL 로그로 확인하려면 `spring.jpa.show-sql` 을 켠다.

**주의할 점**

- `private` 메서드나 같은 클래스 안 호출에는 트랜잭션이 걸리지 않는다.
- `REQUIRES_NEW` 를 트랜잭션 안에서 부르면 커넥션을 **두 개 동시에** 쥔다. 풀이 작으면 모든 스레드가 두 번째 커넥션을 기다리며 멈춘다(풀 데드락). 이 저장소의 유일한 `REQUIRES_NEW` 는 트랜잭션 밖(recoverer)에서 불려 해당하지 않는다 [확인됨].
- 리스너에 `@Transactional` 을 달면 안 되는 이유가 이 저장소에 있다 — 안쪽 예외가 바깥 트랜잭션을 rollback-only 로 만들어 보상 경로가 `UnexpectedRollbackException` 으로 깨진다(decisions.md 6번).

**면접 Q&A**

- **L1** 트랜잭션이 무엇인가요?
  - 여러 연산을 하나의 논리적 작업으로 묶어서, 모두 반영되거나 하나도 반영되지 않게 하는 단위입니다. DB 는 이를 undo 로그로 되돌리고 redo 로그로 보존하고, 동시 실행은 격리 수준과 락으로 다룹니다.
- **L2** 이 프로젝트에서는 트랜잭션을 어디에 적용했나요?
  - 도메인 서비스 계층 한 곳에만 둡니다. 쓰기 트랜잭션은 거의 전부 "비즈니스 변경과 outbox 적재", 컨슈머라면 여기에 "inbox 마킹"까지 한 커밋입니다. Outbox 기록기와 Inbox 가드는 `MANDATORY` 라서 트랜잭션 밖에서 부르면 바로 예외가 납니다. 외부 호출이 끼는 흐름은 트랜잭션을 열지 않는 파사드가 트랜잭션 두 개 사이에서 호출합니다.
- **L3** 그 트랜잭션이 DB 내부에서는 어떻게 동작하나요?
  - 스프링이 커넥션을 빌려 autocommit 을 끄고, 이후 SQL 이 한 InnoDB 트랜잭션이 됩니다. 변경마다 undo 와 redo 가 쌓이고, 바꾼 행의 락은 커밋 때까지 유지됩니다. 커밋 시 redo 가 fsync 되고 락이 풀리며 커넥션이 풀로 돌아갑니다.
- **L4** 커밋 직후 서버가 죽으면요?
  - 커밋 응답이 나갔다면 redo 가 디스크에 있으니 데이터는 남습니다. outbox 행도 같이 커밋됐으므로 재기동한 릴레이가 발행합니다(R-02, `RelayRestartRecoveryTest`). 커밋 전에 죽었다면 InnoDB 가 복구 때 롤백하고, 클라이언트는 응답을 못 받았으니 재시도할 텐데 — 주문 생성 API 에는 멱등키가 없어서 **재시도가 주문을 하나 더 만들 수 있습니다.** 돈은 결제 단계에서만 움직이고 안 쓰인 주문은 만료되니 금전 사고는 아니지만, 알고 있어야 하는 한계입니다.
- **L5** 트래픽이 10배가 되면 지금 트랜잭션 설계에서 무엇이 먼저 문제가 되나요?
  - 쓰기 트랜잭션 자체는 짧아서 괜찮고, 트랜잭션 안에서 기다리는 곳이 먼저 터집니다. 릴레이가 Kafka ack 를 기다리는 동안 쥔 갭 락이 모든 outbox INSERT 를 줄 세우고(R1), 실제 PG 를 붙이면 사전등록이 PG 지연만큼 커넥션을 붙듭니다(R4). 그다음이 인스턴스를 늘릴 때의 커넥션 총량입니다(4.8).

---

### 4.3 ACID

#### Atomicity — 원자성

| | |
|---|---|
| 쉬운 비유 | 이체에서 출금만 되고 입금이 안 되는 일은 없다 |
| DB 관점 | 변경마다 undo 로그를 남겨 롤백 시 되돌린다. 크래시 복구 때 커밋된 것은 redo 로 다시 적용하고, 커밋 안 된 것은 undo 로 되돌린다 |
| 프로젝트 | `createOrder` 의 orders + outbox, `issue` 의 inbox + license + outbox 가 한 커밋이다. **"주문은 있는데 이벤트는 없는" 상태가 존재할 수 없다** [확인됨] |
| 면접 질문 | 원자성이 Kafka 발행까지 보장하나요? |
| 꼬리질문 | 그럼 릴레이가 발행하고 SENT 로 바꾸는 사이는요? |
| 답변 | DB 트랜잭션의 원자성은 DB 안에서만 성립합니다. 그래서 발행 자체를 트랜잭션에 넣지 않고 "발행해야 한다"는 사실을 outbox 행으로 DB 안에 끌어들였습니다. 대신 릴레이의 "Kafka ack 받음"과 "SENT 커밋"은 원자적이지 않아서, 그 사이에 죽으면 같은 이벤트가 한 번 더 나갑니다. 그 중복은 컨슈머의 Inbox 가 흡수합니다 |

#### Consistency — 일관성

| | |
|---|---|
| 쉬운 비유 | "잔고는 0 이상" 같은 규칙을 어기는 결과는 커밋되지 않는다 |
| DB 관점 | PK·UNIQUE·NOT NULL·FK 같은 제약과 애플리케이션 불변식. 나머지 세 성질(A·I·D)과 제약이 함께 만들어 내는 결과이고, 애플리케이션 몫이 크다 |
| 프로젝트 | 유니크 제약(license, settlement, inbox)과 상태 전이 가드(`Payment.approve` 의 금액 대조). **서비스 사이의 일관성은 ACID 의 C 가 아니다** — 결제는 PAID 인데 라이선스는 아직 없는 몇백 ms 가 정상이다(결과적 일관성) |
| 면접 질문 | MSA 에서 서비스 간 데이터 일관성은 어떻게 보장하나요? |
| 꼬리질문 | 보상까지 실패하면요? |
| 답변 | 트랜잭션은 서비스 경계에서 끝나므로 강한 일관성은 포기하고 결과적 일관성을 설계합니다. Outbox 로 이벤트 전달을 보장하고, 컨슈머는 멱등하게 처리하고, 되돌려야 하면 Saga 보상을 씁니다. 보상도 실패할 수 있어서 재시도하고, 판단이 불확실하면 자동으로 돈을 움직이지 않고 DLT 로 보류해 알람을 울립니다(D-027). 마지막 안전망은 결제 건수와 라이선스 건수를 맞춰 보는 대사입니다 |

#### Isolation — 격리성

| | |
|---|---|
| 쉬운 비유 | 같은 장부를 동시에 고치는 두 사람이 서로의 쓰다 만 메모를 보지 않는다 |
| DB 관점 | InnoDB 는 읽기를 MVCC 로, 쓰기와 잠금 읽기를 락으로 격리한다. 기본은 REPEATABLE READ |
| 프로젝트 | 격리 수준 설정이 없어 기본값 그대로다 [확인됨]. 그 결과 잠그지 않는 read-modify-write 에서 Lost Update 가 난다(실험 4, R3) |
| 면접 질문 | 격리성이 있는데 왜 동시성 버그가 나나요? |
| 꼬리질문 | 그럼 SERIALIZABLE 로 올리면 되지 않나요? |
| 답변 | 격리 수준은 "어떤 이상 현상까지 막는가"의 계약이고, MySQL 의 REPEATABLE READ 는 스냅샷으로 읽고 최신 값에 쓰기 때문에 Lost Update 를 막지 않습니다. SERIALIZABLE 은 일반 SELECT 까지 공유 락을 걸어 처리량이 떨어지고 데드락이 늘어납니다. 전역으로 올리기보다 경합하는 자리만 행 잠금이나 조건부 UPDATE, 버전 컬럼으로 막는 편이 맞습니다 |

#### Durability — 지속성

| | |
|---|---|
| 쉬운 비유 | 커밋 영수증을 받았다면 정전이 나도 기록은 남는다 |
| DB 관점 | WAL(redo 로그)을 커밋마다 fsync(`innodb_flush_log_at_trx_commit=1`), doublewrite 버퍼, binlog `sync_binlog=1` — 셋 다 [확인됨] 기본값 |
| 프로젝트 | MySQL 은 로컬 구성에서 인스턴스 하나다. 서버 크래시에는 버티지만 **디스크를 잃으면 데이터를 잃는다** — 그 복구 절차가 [runbooks/license-db-loss.md](runbooks/license-db-loss.md) 다. Kafka 쪽 내구성은 별개이고 복제 계수 1 이다(5.4) |
| 면접 질문 | 커밋 응답을 받은 직후 DB 서버가 죽으면요? |
| 꼬리질문 | `innodb_flush_log_at_trx_commit` 을 2 로 바꾸면요? |
| 답변 | 커밋 전에 redo 가 fsync 됐으므로 재기동 때 복구됩니다. 2 로 바꾸면 커밋마다 OS 버퍼에만 쓰고 약 1초마다 fsync 해서 빨라지지만, OS 나 전원이 죽으면 최근 1초 정도의 커밋을 잃을 수 있습니다. 결제·정산 원장에는 쓰지 않을 설정입니다 |

---

### 4.4 Isolation Level

**한 줄 요약** — 동시에 도는 트랜잭션이 **서로의 중간 결과를 얼마나 볼 수 있는가**를 정한 약속이다.

**쉽게 이해하기**

시험지를 사진으로 찍어 두고 푼다고 생각하자.

- READ UNCOMMITTED — 옆 사람이 **아직 지우개로 고치는 중인** 답까지 본다.
- READ COMMITTED — 문제를 하나 풀 때마다 **새로 사진을 찍는다.** 그사이 확정된 답은 바뀌어 보인다.
- REPEATABLE READ — 시험 **시작할 때 찍은 사진 한 장**만 본다. 끝날 때까지 같은 답이 보인다.
- SERIALIZABLE — 사실상 **한 사람씩** 들어가 푸는 것과 같은 결과를 보장한다.

**일반적인 동작 — 네 수준과 이상 현상**

| 수준 | Dirty Read | Non-repeatable Read | Phantom Read | Lost Update | InnoDB 구현 |
|---|---|---|---|---|---|
| READ UNCOMMITTED | 발생 | 발생 | 발생 | 발생 | 최신 행을 그대로 읽는다 |
| READ COMMITTED | 막음 | 발생 | 발생 | 발생 | 문장마다 새 스냅샷, 갭 락을 거의 쓰지 않는다 |
| **REPEATABLE READ** (MySQL 기본) | 막음 | 막음 | 일반 SELECT 는 스냅샷이, 잠금 읽기는 넥스트키 락이 막는다 | **MySQL 은 발생** / PostgreSQL 은 직렬화 오류로 막는다 | 첫 읽기 시점 스냅샷 + 넥스트키 락 |
| SERIALIZABLE | 막음 | 막음 | 막음 | 막음 | autocommit 이 꺼진 트랜잭션의 일반 SELECT 도 공유 락을 건다 |

**Dirty Read**

```text
시간 →        t1                           t2                            t3
Tx A   UPDATE product SET price = 0 (미커밋)                            ROLLBACK
Tx B                              SELECT price → 0   (READ UNCOMMITTED 에서만)
→ B 는 한 번도 존재한 적 없는 가격 0 으로 주문 금액을 계산했다
```

**Non-repeatable Read**

```text
Tx A   SELECT status FROM payment WHERE order_no='ORD-1'  → PENDING
Tx B                         UPDATE payment SET status='PAID' …; COMMIT
Tx A   SELECT status FROM payment WHERE order_no='ORD-1'  → READ COMMITTED: PAID  /  REPEATABLE READ: PENDING
```

**Phantom Read**

```text
Tx A   SELECT COUNT(*) FROM orders WHERE status='CREATED' AND created_at < :t      → 500
Tx B                         INSERT INTO orders (… 'CREATED', 과거 시각 …); COMMIT
Tx A   같은 SELECT                               → READ COMMITTED: 501  /  REPEATABLE READ: 500 (스냅샷)
Tx A   같은 조건으로 SELECT … FOR UPDATE          → REPEATABLE READ 에서도 501 — 잠금 읽기는 스냅샷이 아니라 최신 커밋을 읽는다
```

마지막 줄이 InnoDB REPEATABLE READ 의 함정이다. **같은 트랜잭션 안에서도 일반 SELECT(스냅샷 읽기)와 잠금 읽기·UPDATE(현재 읽기)가 서로 다른 데이터를 본다.**
반대로 A 가 처음부터 `FOR UPDATE` 로 범위를 읽었다면, 넥스트키 락이 B 의 INSERT 를 막아 유령 행 자체가 생기지 않는다.

**Lost Update — 이 프로젝트에서 실제로 나는 것** [확인됨 — 실험 4]

```text
시간 →         t0                    t1                          t3
스윕 Tx S   SELECT … status='CREATED'  (스냅샷)                   UPDATE orders SET status='EXPIRED', paid_at=NULL, … WHERE id=1
                                                                  COMMIT   → affected_rows 1, 에러 없음
컨슈머 Tx C                     SELECT … → CREATED
                                UPDATE … status='PAID', paid_at=now
                                COMMIT (t2)
최종: EXPIRED, paid_at = NULL — C 의 결제 확정이 흔적 없이 사라졌다
```

S 의 UPDATE 는 "현재 읽기"라 C 가 커밋한 최신 행 위에 쓰지만, 쓰는 **값**은 t0 스냅샷을 보고 애플리케이션이 계산한 것이다.
Hibernate 는 기본적으로 모든 컬럼을 UPDATE 하므로 `paid_at` 까지 옛 값(NULL)으로 덮는다.
PostgreSQL 의 REPEATABLE READ 라면 S 의 UPDATE 가 "동시 갱신"으로 직렬화 오류를 받는다 — 같은 이름의 격리 수준이 DB 마다 다르게 동작한다는 점이 면접 단골이다.

**InnoDB REPEATABLE READ 의 두 가지 읽기**

| | 스냅샷 읽기 (consistent read) | 현재 읽기 (current read) |
|---|---|---|
| 문장 | 일반 `SELECT` | `SELECT … FOR UPDATE / FOR SHARE`, `UPDATE`, `DELETE`, `INSERT` 의 중복 검사 |
| 보는 것 | 트랜잭션의 첫 읽기 시점 스냅샷 | 가장 최근에 커밋된 행 (필요하면 커밋을 기다린다) |
| 락 | 없음 | S 또는 X, 범위면 넥스트키 락 |

**내 프로젝트에서는**

**[확인됨]** 격리 수준을 정한 곳이 없다 — `application.yml`, JDBC URL, `@Transactional(isolation=…)`, `docker-compose.yml` 의 MySQL `command` 를 전수로 찾았다.
따라서 MySQL 기본값 `REPEATABLE-READ` 이고, 실험 컨테이너의 `@@transaction_isolation` 으로 확인했다.
측정용 원격 호스트에 떠 있는 스택의 `stove-mysql`(8.0.46, aarch64)도 `REPEATABLE-READ` 이고, order 앱의 커넥션 20개가 모두 그 수준으로 열려 있다(`performance_schema.variables_by_thread`) [확인됨 — OCI].
실제 운영 DB 의 파라미터는 **[추가 확인 필요]**.

| 코드 | 읽는 방식 | REPEATABLE READ 에서 벌어지는 일 |
|---|---|---|
| `ProcessedEventGuard.firstDelivery` 의 `existsBy…` (`:28`) | 스냅샷 | 동시 처리 둘 다 "없음" → **유니크 인덱스가 막는다** (실험 5) |
| `OrderExpiryService` 의 조회 + `Order.expire()` | 스냅샷 후 PK 로 UPDATE | 결제 확정과 겹치면 **Lost Update** (실험 4, R3) |
| `PaymentRepository.findByOrderNoForUpdate` (`:22-24`) | 현재 읽기 + X 락 | 동시 콜백이 줄 서고 뒤쪽은 PAID 를 본다 — Lost Update 없음 |
| `OutboxEventRepository.lockPendingBatch` (`:17-25`) | 현재 읽기 + 넥스트키 락 | 새 outbox INSERT 가 갭 락에 막힌다 (실험 1, R1) |

**Lost Update 를 막는 네 가지 방법**

| 방법 | 모양 | 장점 | 대가 | 이 프로젝트에 어울리는 자리 |
|---|---|---|---|---|
| 비관적 락 | `SELECT … FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) | 확실하고 재시도가 필요 없다 | 대기·데드락·커넥션 점유 | 결제 콜백 — 이미 쓴다 |
| 낙관적 락 | `@Version` → `UPDATE … WHERE id=? AND version=?` | 락 대기가 없다 | 충돌 시 예외 → 재시도 설계 필요 | 주문 상태 전이(스윕 vs 컨슈머) |
| 조건부 UPDATE | `UPDATE … WHERE id=? AND status='CREATED'` | 가장 싸고 원자적. 영향 행 수로 판정 | 전이 규칙 일부가 SQL 로 나간다 | 대량 만료 스윕 |
| 격리 수준 상향 | SERIALIZABLE | 코드 변경이 없다 | 전 트랜잭션의 처리량·데드락 비용 | 맞지 않는다 |

**면접 Q&A**

- **L1** 격리 수준 네 가지와 각각 막는 이상 현상을 설명해 주세요. — 위 표.
- **L2** 이 프로젝트의 격리 수준은 무엇이고, 왜 그걸 쓰나요?
  - 따로 설정하지 않아 MySQL 기본값인 REPEATABLE READ 입니다. 정확히는 골랐다기보다 기본값을 그대로 쓴 것이라, 그 기본값이 코드에 어떤 영향을 주는지 실험으로 확인했습니다. 대부분의 경로는 행 잠금이나 유니크 제약으로 정확성을 지키고 있지만, 잠그지 않는 만료 스윕에서 Lost Update 가, 릴레이의 잠금 조회에서 갭 락 경합이 난다는 것을 찾았습니다.
- **L3** REPEATABLE READ 인데 왜 Lost Update 가 나나요?
  - MySQL 은 읽을 때는 트랜잭션 시작 스냅샷을 보고, UPDATE 할 때는 최신 커밋 행에 씁니다. 애플리케이션이 옛 스냅샷으로 계산한 값을 그대로 쓰면 그사이 커밋된 변경을 덮습니다. PostgreSQL 은 같은 이름의 수준에서 이 경우를 오류로 막는데 MySQL 은 막지 않습니다.
- **L4** 만료 스윕과 결제 확정이 정말 겹칠 수 있나요?
  - 평소에는 아닙니다. 결제는 주문 후 30분 안에 시작해야 하고 결제창은 15분이라, 결제 완료 이벤트는 늦어도 45분 안에 나오는데 만료는 60분입니다(창을 넘긴 승인은 자동 환불로 빠집니다). 그런데 order 컨슈머가 15분 이상 밀리거나, 브로커 장애로 결제완료 이벤트가 DEAD 가 됐다가 나중에 회수되면 창이 열립니다. 겹치는 순간이면 조용히 덮어써지고, 만료가 먼저 끝났으면 결제 확정이 CONFLICT 로 DLT 에 갑니다. 어느 쪽이든 결제·라이선스와 주문 상태가 어긋납니다.
- **L5** 격리 수준을 READ COMMITTED 로 바꾸면 무엇이 좋아지고 무엇을 확인해야 하나요?
  - 갭 락이 거의 사라져 릴레이와 INSERT 경합이 없어집니다(실험 1). 대신 같은 트랜잭션에서 같은 데이터를 두 번 읽으면 다른 값을 볼 수 있으니, 두 번 읽고 비교하는 로직이 있는지 봐야 합니다. 복제는 binlog 가 ROW 형식이라 READ COMMITTED 에서도 안전합니다(`binlog_format=ROW` 확인). 전역보다는 릴레이 트랜잭션에만 먼저 적용하는 편이 위험이 작습니다.

---

### 4.5 Lock

**한 줄 요약** — 같은 데이터를 동시에 바꾸려는 트랜잭션을 줄 세우는 장치다. InnoDB 는 **행이 아니라 인덱스 레코드**를 잠근다.

**쉽게 이해하기**

회의실 예약에 빗대면 이렇다.

- **레코드 락** — 특정 회의실 하나를 예약한다.
- **갭 락** — 두 회의실 사이의 빈 공간을 막아 **그 자리에 새 회의실을 못 짓게** 한다.
- **넥스트키 락** — 회의실 하나와 그 앞 빈 공간을 함께 막는다. REPEATABLE READ 의 범위 잠금 기본값이다.
- **Insert intention 락** — "이 빈 공간에 새 회의실을 짓겠다"는 신청서. 누군가 그 공간에 갭 락을 쥐고 있으면 기다린다.

**일반적인 동작**

| 질문 | 답 |
|---|---|
| 왜 필요한가 | MVCC 는 읽기를 해결하지만, **같은 행을 둘이 바꾸는 쓰기 충돌**은 줄을 세워야 한다 |
| Row / Table | InnoDB 는 행(인덱스 레코드) 단위. 테이블에는 "안쪽 어딘가를 잠갔다"는 표시인 인텐션 락(IS/IX)만 건다. DDL 은 메타데이터 락(MDL)을 쓴다 |
| Shared / Exclusive | S 끼리는 함께 쥘 수 있고, X 는 누구와도 함께 쥘 수 없다 |
| 언제 획득 | `UPDATE`·`DELETE`·`INSERT`·`SELECT … FOR UPDATE / FOR SHARE` 가 실행될 때. **일반 SELECT 는 락을 잡지 않는다** |
| 언제 해제 | **커밋이나 롤백 때.** 트랜잭션 도중에는 풀지 않는다(엄격한 2단계 잠금). READ COMMITTED 에서는 조건에 안 맞는 행의 락을 곧바로 푼다 |
| 인덱스와의 관계 | 검색에 쓴 인덱스를 잠근다. 알맞은 인덱스가 없으면 **스캔한 행을 전부** 잠근다. 유니크 인덱스로 한 행을 정확히 찾으면 레코드 락만 건다 |
| 기다리다 못하면 | `innodb_lock_wait_timeout`(기본 50초) 뒤 ERROR 1205. **문장만** 되돌리고 트랜잭션은 열려 있다(`innodb_rollback_on_timeout=OFF`) — 둘 다 [확인됨] |
| 기다리지 않기 | `NOWAIT` 는 즉시 오류, `SKIP LOCKED` 는 잠긴 행을 건너뛰고 나머지를 돌려준다 |

**내 프로젝트에서는 — 락이 걸리는 자리 전수** [확인됨 — `@Lock`·`FOR UPDATE`·`@Version` 전수 검색]

| 자리 | SQL | 잠기는 것 | 목적 |
|---|---|---|---|
| `PaymentRepository.findByOrderNoForUpdate` | `… WHERE order_no=? FOR UPDATE` | 유니크 인덱스로 찾으므로 그 레코드와 PK 레코드(X). 행이 없으면 갭 락 | 동시 중복 콜백, 승인·거절 교차를 직렬화 |
| `OutboxEventRepository.lockPendingBatch` | `… ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED` | REPEATABLE READ 에서 **넥스트키 락**(실험 1·2) | 릴레이 배치 선점 |
| JPA 더티 체킹 UPDATE | `UPDATE … WHERE id=?` | PK 레코드 X, 커밋까지 | 모든 상태 전이 |
| 유니크 인덱스가 있는 INSERT | `INSERT …` | 새 레코드 X(암묵적), 중복 검사 때 S | Inbox·원장 멱등 (실험 5·6) |
| ShedLock | `shedlock` 행의 `lock_until` 을 조건부 UPDATE | 행 하나 | 스케줄러 단일 실행 — DB 락이라기보다 **시간이 정해진 임대 기록** |

`@Version`(낙관적 락)은 **한 곳도 없다** [확인됨].

**`FOR UPDATE` — 결제 콜백이 동시에 두 번 오면**

```text
시간 →        t0                                   t1                 t2
Tx 1   SELECT … FOR UPDATE  → PENDING               UPDATE → PAID
                                                    INSERT outbox(PaymentCompleted)      COMMIT
Tx 2          SELECT … FOR UPDATE ──── 대기 ──────────────────────────────────────▶ PAID 를 읽는다 (현재 읽기)
                                                                                   approve() → 같은 멱등키 → false, 이벤트 없음
```

락이 없었다면(일반 SELECT) 둘 다 PENDING 을 보고 둘 다 승인해 `PaymentCompleted` 가 **서로 다른 eventId 로 두 번** 나간다.
Inbox 는 eventId 가 달라 못 거르고, 하위 서비스의 도메인 유니크가 그나마 막는다. 락은 그 중복을 **원천에서** 닫는다.
단, 기존 통합 테스트(`PaymentIdempotencyTest#duplicateCallbackApprovesOnce`)는 두 콜백을 **순차로** 부른다 — 동시성 자체를 재는 테스트는 없다 **[추가 확인 필요]**.

**`FOR UPDATE SKIP LOCKED` — 릴레이가 잡는 락** [확인됨 — 실험 1·2, 로컬·OCI 에서 같은 결과]

`SKIP LOCKED` 를 쓰는 이유는 릴레이가 여러 대여도 같은 행을 두 번 집지 않게 하려는 것이다(순서 문제는 별개 — event-ordering.md 7절).
그런데 REPEATABLE READ 에서 이 조회는 `idx_outbox_pending` 에 **넥스트키 락**을 건다. 새 outbox 행 `('PENDING', NULL, 새 id)` 이 들어갈 자리가 그 갭 안이다.

```text
시간 →          0s                                         1.5s                                6s
릴레이 Tx R   SELECT … FOR UPDATE SKIP LOCKED   send() … ack 대기 …………………………………………   UPDATE SENT · COMMIT
주문 Tx O                                        INSERT outbox_event ── 갭 락 대기 ───────────▶ 진행
```

| 조건 (실험) | 잡힌 락 | 주문 트랜잭션의 INSERT (로컬 / OCI 1회차 / 2회차) |
|---|---|---|
| RR, SENT 100,000 + PENDING 5 (평시 모양) | `idx_outbox_pending` X ×6, PK X,REC_NOT_GAP ×6 | **4.38 / 4.43 / 4.46초 대기** (릴레이 커밋까지) |
| RC, 같은 데이터 | 레코드 락만 | 0.00 / 0.01 / 0.01초 — 대기 없음 |
| RR, SENT 100,000 + PENDING 5,000 (적체) | 넥스트키 5,007 + PK 5,001 — `LIMIT 200` 인데 | 3.33 / 3.42 / 3.44초 대기 |
| RR, SENT 20,000 + PENDING 5,000 | 계획이 PRIMARY 스캔으로 바뀌어 **20,359행** 잠금 | 0.01 / 0.00 / 0.01초 — 대기 없음 |
| RR, 릴레이 8초 · INSERT 한도 3초 | — | **ERROR 1205 로 실패** (3.03 / 3.00 / 3.01초에 포기) |

**읽는 법** — 가져가는 것은 200건인데 잠그는 것은 수천~수만 건이다. 실행 계획이 데이터 분포에 따라 바뀌고,
보조 인덱스의 갭을 잠그는 계획(평시 모양)에서 새 INSERT 가 막힌다. 대기 시간은 실험이 정한 `SLEEP` 에서 나온 값이라 크기가 아니라 **막혔나, 안 막혔나**를 본다.

**부하에서도 나타나는가** [확인됨 — OCI A/B] — 격리 수준만 바꿔 60 RPS 주문 생성을 걸면, REPEATABLE READ 에서는 90초 동안 행 락 대기가 400회 쌓이고 READ COMMITTED 에서는 0회다. 쓰기 p95 는 26.2 → 21.5ms. 표와 사고 경로는 [R1](#r1-릴레이가-갭-락을-쥔-채-kafka-ack-를-기다린다).

**면접 Q&A**

- **L1** DB 락이 왜 필요한가요? Shared 와 Exclusive 의 차이는요?
  - MVCC 로 읽기끼리의 충돌은 피하지만 같은 행을 둘이 동시에 바꾸면 한쪽 결과가 사라지므로 쓰기는 줄을 세워야 합니다. S 락은 여럿이 함께 쥘 수 있어 읽는 동안 변경을 막고, X 락은 혼자만 쥐어 변경 중인 행에 다른 잠금을 허락하지 않습니다.
- **L2** 이 프로젝트에서 락은 어디에 쓰나요? — 위 전수 표. 명시적 락은 결제 콜백의 `FOR UPDATE` 와 릴레이의 `SKIP LOCKED` 둘뿐이다.
- **L3** `FOR UPDATE` 는 정확히 무엇을 잠그나요? 없는 행을 `FOR UPDATE` 하면요?
  - 쿼리가 쓴 인덱스의 레코드를 잠급니다. 결제는 주문번호 유니크 인덱스로 한 행을 찾으니 그 레코드와 PK 레코드만 X 로 잠급니다. 행이 없으면 그 값이 들어갈 갭을 잠가, 같은 값의 INSERT 를 막습니다.
- **L3** `SKIP LOCKED` 는 여러 릴레이의 중복 발행을 어떻게 막나요? 그럼 릴레이를 늘려도 되나요?
  - 한 릴레이가 잠근 행을 다른 릴레이가 건너뛰므로 같은 이벤트를 둘이 집지 않습니다. 하지만 같은 주문의 이벤트가 두 릴레이로 갈라질 수 있어서 순서가 깨집니다. 그래서 문서상 릴레이는 서비스당 한 대이고, 늘리려면 파티션 키를 해시해 워커에 고정 배정해야 합니다.
- **L4** 이 조회가 REPEATABLE READ 에서 무엇을 잠그는지 확인해 봤나요?
  - 네. MySQL 8.0 에서 같은 인덱스와 쿼리로 재현하니 보조 인덱스에 넥스트키 락이 걸리고, 그동안 새 outbox INSERT 가 릴레이 커밋까지 기다렸습니다. READ COMMITTED 에서는 레코드 락만 걸려 기다리지 않았습니다. 릴레이는 그 트랜잭션 안에서 Kafka ack 를 기다리므로, 브로커 지연이 주문·결제 API 의 INSERT 대기로 번질 수 있습니다.
- **L4** 비관적 락 대신 낙관적 락을 쓰면 결제 콜백은 어떻게 되나요?
  - 동시에 온 두 콜백 중 늦게 커밋하는 쪽이 버전 불일치로 실패합니다. PG 는 실패 응답을 받으면 콜백을 다시 보내니 결국 수렴하지만, 그 실패를 PG 가 이해할 응답 코드로 돌려주는 설계가 필요합니다. 콜백은 같은 주문에만 몰리고 짧게 끝나서 대기 비용이 작으니 비관적 락이 단순합니다.

---

### 4.6 MVCC

**한 줄 요약** — 행의 옛 버전을 남겨 두고 읽는 쪽은 자기 시점의 버전을 읽게 해서, **읽기와 쓰기가 서로 막지 않게** 한다.

**쉽게 이해하기** — 위키 문서의 편집 기록과 같다. 누가 편집하는 동안에도 다른 사람은 자기가 문서를 연 시점의 판을 읽는다.

**일반적인 동작 (InnoDB)**

```text
행: [id=1 | status=PAID | DB_TRX_ID=205 | DB_ROLL_PTR] ──undo──▶ [status=PENDING | TRX_ID=180] ──undo──▶ [status=READY | TRX_ID=120]

Read View (스냅샷을 만든 순간 아직 커밋되지 않은 트랜잭션 = {185, 205})
  TRX_ID=205 → 활성 목록에 있다 → 안 보인다 → undo 를 따라간다
  TRX_ID=180 → 스냅샷 전에 커밋 → 보인다 → status=PENDING 을 읽는다
```

판정 규칙을 보이려고 단순하게 그린 그림이다. 실제 Read View 는 활성 목록과 함께 "가장 작은 활성 ID"·"다음에 발급할 ID" 두 경계로 판정한다.

- 행마다 마지막으로 바꾼 트랜잭션 ID 와 undo 로그의 이전 버전 포인터가 숨어 있다.
- 읽는 트랜잭션은 Read View 규칙으로 "내가 봐도 되는 버전"을 찾을 때까지 undo 체인을 따라간다.
- REPEATABLE READ 는 Read View 를 첫 일관 읽기 때 한 번, READ COMMITTED 는 문장마다 만든다.
- Purge 스레드는 어떤 Read View 도 필요로 하지 않는 옛 버전을 지운다. **오래 열린 트랜잭션은 purge 를 막아** undo 가 쌓이고 읽기가 느려진다.

**Lock 과 MVCC 는 어떤 관계인가**

| 연산 | MVCC | 락 |
|---|---|---|
| 일반 SELECT | 스냅샷 읽기 | 없음 |
| SELECT … FOR UPDATE / FOR SHARE | 쓰지 않는다 — 최신 커밋을 읽는다 | X / S |
| UPDATE / DELETE | 최신 커밋을 읽고 바꾼다. 옛 버전은 undo 로 남긴다 | X |
| INSERT | undo 로 남긴다 | 새 레코드 X, 중복 검사 S |

MVCC 는 읽기를, 락은 쓰기 충돌을 맡는다. 둘은 대체 관계가 아니라 분업이다.

**내 프로젝트에서는**

- **Inbox 는 MVCC 로 중복을 막지 않는다** [확인됨 — 실험 5]. 두 번째 트랜잭션의 `exists` 는 첫 번째의 미커밋 행을 보지 못해 0 을 받고,
  INSERT 에서 유니크 인덱스의 락을 기다렸다가 1062 를 받는다. 중복을 막은 것은 스냅샷이 아니라 인덱스다.
- **만료 스윕은 스냅샷을 믿고 썼다** [확인됨 — 실험 4]. 스냅샷에는 결제 확정이 없었고, UPDATE 는 최신 행을 덮었다.
- **결제 콜백은 `FOR UPDATE` 로 스냅샷을 우회해** 최신 상태를 보고 판단한다 [확인됨 — 코드].
- 조회 서비스는 `readOnly = true` 로 락 없이 스냅샷을 읽는다.
- 오래 열리는 트랜잭션 후보는 **릴레이 트랜잭션**이다. 브로커가 응답하지 않으면 ack 대기가 `delivery.timeout.ms`(120초)까지 늘어 락과 커넥션을 그만큼 쥔다 **[코드상 추정]**.

**면접 Q&A**

- **L1** MVCC 가 무엇인가요? — 한 줄 요약.
- **L2** MVCC 가 있으면 락은 필요 없지 않나요?
  - 읽기와 쓰기의 충돌은 MVCC 로 피하지만, 쓰기끼리의 충돌은 락이 필요합니다. 이 프로젝트의 Inbox 가 좋은 예인데, 동시에 들어온 같은 이벤트를 스냅샷은 둘 다 "처음 본다"고 판단했고 유니크 인덱스의 락이 두 번째를 막았습니다.
- **L3** REPEATABLE READ 에서 같은 트랜잭션의 SELECT 와 SELECT FOR UPDATE 결과가 다를 수 있나요?
  - 네. 일반 SELECT 는 트랜잭션 시작 스냅샷을, FOR UPDATE 는 최신 커밋을 읽기 때문입니다. 그래서 스냅샷으로 판단하고 UPDATE 로 쓰는 코드는 Lost Update 를 만들 수 있습니다.
- **L4** 긴 트랜잭션은 왜 문제인가요?
  - 락을 오래 쥐어 다른 트랜잭션을 세우고, 커넥션을 붙들고, 오래된 스냅샷이 undo purge 를 막아 undo 가 쌓이면서 읽기가 긴 버전 체인을 따라가느라 느려집니다. 큰 트랜잭션은 커밋 때 binlog 도 한 번에 커져 복제 지연을 만듭니다.

---

### 4.7 Deadlock

**한 줄 요약** — 두 트랜잭션이 서로 상대가 쥔 락을 기다려 둘 다 끝나지 않는 상태. InnoDB 는 즉시 감지해 한쪽을 롤백한다.

**쉽게 이해하기** — 좁은 골목에서 마주 선 두 차. 누군가 후진하지 않으면 영원히 못 지나간다. InnoDB 는 한 대를 강제로 후진시킨다.

**일반적인 동작**

```text
Tx A   UPDATE payment … WHERE id=1  (X 1 획득)              UPDATE payment … WHERE id=2 → B 를 기다림
Tx B            UPDATE payment … WHERE id=2  (X 2 획득)              UPDATE payment … WHERE id=1 → A 를 기다림
        → 대기 그래프에 순환 → InnoDB 가 되돌리기 비용이 작은 쪽을 골라 ERROR 1213 (트랜잭션 전체 롤백)
```

- 전형적인 원인: 서로 다른 순서로 행을 잠금, 갭 락과 insert intention 의 교차, **같은 유니크 키를 여럿이 넣다가 S→X 로 올리는 경합**.
- 처리: `innodb_deadlock_detect=ON`(기본, [확인됨])이면 즉시 감지해 희생자를 롤백한다. 끄면 lock wait timeout(1205)까지 기다린다.
- 조사: `SHOW ENGINE INNODB STATUS` 의 `LATEST DETECTED DEADLOCK`, 계속 남기려면 `innodb_print_all_deadlocks`.
- 애플리케이션 대응: **재시도**(연산이 멱등이어야 안전하다), 잠그는 순서 통일, 트랜잭션 짧게, 스캔 범위를 줄이는 인덱스, 필요하면 READ COMMITTED 로 갭 락 줄이기.

**내 프로젝트에서는**

**[코드상 추정]** 한 트랜잭션이 여러 행을 **서로 다른 순서로** 잠그는 코드는 찾지 못했다.
결제는 주문 한 건의 행 하나를, 릴레이는 `ORDER BY id` 한 방향으로, 만료 스윕은 잠그지 않고 읽은 뒤 PK 로 갱신하고, 정산 마감은 판매자 단위로 끝난다.
그래서 이 코드의 실제 위험은 데드락보다 **락 대기(R1)** 쪽이다.

데드락이 날 수 있는 자리는 **Inbox 유니크 키**다 [확인됨 — 실험 6]. 같은 eventId 를 셋이 동시에 넣다가 첫째가 롤백하면,
나머지 둘이 중복 검사용 S 락을 쥔 채 INSERT 용 X 를 서로 기다리다 한쪽이 1213 으로 희생된다.
한 컨슈머 그룹에서 같은 이벤트를 **동시에** 처리하는 일은 드물다. 중복 발행이나 재투입으로 생긴 사본은 같은 파티션에 들어가 한 컨슈머가 차례로 처리하기 때문이다.
주로 리밸런싱 도중, 그룹에서 빠진 컨슈머가 아직 처리 중인데 새 담당이 같은 레코드를 받는 경우에 겹친다.

| 경로 | 1213 이 나면 |
|---|---|
| 컨슈머 | 예외 → `DefaultErrorHandler` 가 1초 뒤 재시도 → 재시도에서 Inbox 가 "이미 처리"로 끝낸다. **Inbox 가 재시도를 안전하게 만든다** |
| HTTP | `GlobalExceptionHandler` 의 마지막 분기가 500 으로 응답한다. 자동 재시도는 없다 [확인됨] |

**면접 Q&A**

- **L1** 데드락은 어떻게 발생하나요? — 서로가 쥔 락을 기다리는 순환.
- **L3** 데드락이 나면 DB 는 어떻게 처리하나요? lock wait timeout 과는 무엇이 다른가요?
  - InnoDB 는 대기 그래프에서 순환을 즉시 찾아 한쪽 트랜잭션을 통째로 롤백하고 1213 을 돌려줍니다. 1205 는 순환이 아니라 단순히 오래 기다린 경우이고, MySQL 기본 설정에서는 그 문장만 되돌리고 트랜잭션은 남겨 둡니다.
- **L2** 이 프로젝트에서 데드락이 날 수 있나요?
  - 여러 행을 다른 순서로 잠그는 트랜잭션이 없어서 흔한 유형은 없습니다. 이론상 가능한 곳은 같은 이벤트가 동시에 셋 이상 처리될 때 Inbox 유니크 키이고, 실험으로 재현도 해 봤습니다. 나더라도 컨슈머가 재시도하고 재시도에서 Inbox 가 걸러서 결과는 한 번 처리로 수렴합니다.
- **L4** 희생된 트랜잭션을 그냥 재시도해도 되나요?
  - 연산이 멱등할 때만 그렇습니다. 컨슈머는 Inbox 와 도메인 유니크가 있어 안전하고, 결제 콜백은 상태 검사와 멱등키로 안전합니다. 반면 주문 생성은 멱등키가 없어서 클라이언트가 무작정 재시도하면 주문이 하나 더 생길 수 있습니다.

---

### 4.8 Connection Pool

**한 줄 요약** — DB 커넥션을 미리 만들어 두고 빌려 쓰는 장치다. **트랜잭션 하나가 커넥션 하나를 처음부터 끝까지 점유**한다.

**쉽게 이해하기** — 택시 승강장. 택시(커넥션)는 20대이고 손님(요청)이 몰리면 줄을 선다.
손님이 택시 안에서 전화 통화(트랜잭션 안의 외부 API)를 오래 하면, 택시가 서 있는데도 줄이 줄지 않는다.

**일반적인 동작 (HikariCP)**

| 설정 | 뜻 | 기본값 |
|---|---|---|
| `maximumPoolSize` | 최대 커넥션 수 | 10 |
| `minimumIdle` | 놀아도 유지할 수 — 따로 안 주면 max 와 같아 **기동하자마자 전부 연다** | = max |
| `connectionTimeout` | 커넥션을 기다리는 한도. 넘으면 예외 | 30초 |
| `maxLifetime` | 커넥션 수명. DB 의 `wait_timeout`(MySQL 기본 8시간, [확인됨])보다 짧아야 끊긴 커넥션을 쥐지 않는다 | 30분 |

- 판정 지표는 `active`(사용 중)가 아니라 **`pending`(기다리는 스레드 수)** 다. active 가 가득 차도 pending 이 0 이면 풀은 충분하다.
- 풀을 키우는 것이 해법이 아닌 경우가 많다. DB 가 동시에 처리할 수 있는 양이 한계이고, 커넥션이 많으면 DB 쪽 문맥 전환과 락 경합이 늘어난다.
- **풀 데드락** — 커넥션을 쥔 스레드가 `REQUIRES_NEW` 로 두 번째 커넥션을 원하면, 풀이 빈 순간 모두가 서로를 기다린다.

**내 프로젝트에서는** [확인됨 — 각 `application.yml`]

| 서비스 | `maximum-pool-size` | `connection-timeout` |
|---|---:|---:|
| order · payment · license · catalog | 20 | 3초 |
| studio · review · settlement | 10 | 3초 |
| **한 대씩 떠 있을 때 합계** | **110** | |

- MySQL `max_connections` 기본 151 을 compose 가 바꾸지 않는다 [확인됨]. **서비스를 두 대씩 띄우면 220 > 151** 이라 "Too many connections" 가 난다. 통합 테스트가 같은 벽을 실제로 만났다 — 컨텍스트 여러 벌 × 풀 20(D-036).
- 측정용 원격 호스트에 **아무 요청도 없이 떠 있는 스택**에서 `stove` 사용자의 커넥션을 세면 **정확히 110개**다 [확인됨 — OCI, `performance_schema.threads`]. `minimumIdle` 기본값이 최대치라 놀아도 전부 열려 있다는 것, 그리고 151 중 110 이 이미 쓰이고 있다는 것을 한 숫자가 보여 준다.
- `connection-timeout: 3s` 는 빨리 실패하려는 값이다. 그 대가로 DB 장애 때 시도당 3초 × 4회가 재시도 한 바퀴를 만든다 — chaos 에서 스키마 차단 시 한 바퀴 19.8초의 대부분이 이것이었다(D-027).
- 측정: 60 RPS 비교 부하에서 `pending` 은 전 회차 0, active 최대 5/20 — 풀은 병목이 아니었다. 포화 구간에서만 order 의 pending 이 179 까지 섰다(performance.md 14장). **포화 전과 후를 나눠 말해야 한다.**
- 트랜잭션 안의 대기가 커넥션을 붙든다 — 릴레이의 Kafka ack(R1), 사전등록의 PG 호출과 심의 접수의 게임위 호출(R4).
- `open-in-view: false` (전 서비스) — 뷰 렌더링 동안 커넥션을 쥐지 않는다.
- 유일한 `REQUIRES_NEW`(`recordIssueFailure`)는 트랜잭션 밖에서 불려 커넥션을 두 개 쥐지 않는다.

**면접 Q&A**

- **L1** 커넥션 풀이 왜 필요한가요? — 커넥션 생성(TCP·인증·세션 설정)이 비싸서 재사용한다.
- **L2** 풀 크기 20 은 어떻게 정했나요?
  - 비교 부하에서 기다리는 스레드가 0 이고 동시 사용이 최대 5 라 여유가 있다는 것까지는 측정했습니다. 다만 크기는 서비스 한 대가 아니라 DB 전체 기준으로 봐야 해서, 지금 일곱 서비스 합계가 110 이고 MySQL 기본 한도가 151 이라 인스턴스를 늘리면 먼저 이 숫자를 조정해야 합니다.
- **L3** 풀이 고갈되면 무슨 일이 생기나요?
  - 3초 기다린 뒤 커넥션을 못 얻어 트랜잭션 시작 자체가 실패합니다. HTTP 는 500 이 되고, 컨슈머는 재시도를 네 번 돌다 DLT 로 갑니다. license 는 이것을 저장소 장애로 분류해 환불하지 않고 보류합니다.
- **L5** 트래픽이 10배면 풀을 200 으로 늘리나요?
  - 아닙니다. DB 가 동시에 처리할 수 있는 양은 그대로라 대기가 DB 안으로 옮겨갈 뿐이고, 총량은 `max_connections` 에 막힙니다. 먼저 트랜잭션 안의 대기를 없애 커넥션 점유 시간을 줄이고, 읽기는 캐시와 복제본으로 돌리고, 인스턴스가 많아지면 ProxySQL 같은 커넥션 프록시를 검토합니다.

---

## 5. Kafka

### 5.1 Kafka를 사용하는 이유

**한 줄 요약** — 서비스끼리 서로를 직접 부르지 않고, **같은 사건 기록(로그)을 각자 읽게** 하려고 쓴다.

**쉽게 이해하기**

전화와 게시판의 차이다. 결제 서비스가 "결제 완료" 공지를 게시판에 붙이면 주문·라이선스·정산이 각자 읽는다.
전화였다면 결제가 셋에게 차례로 걸어야 하고, 한 곳이 안 받으면 결제가 멈춘다.
게시판은 공지를 읽어도 떼지 않으므로, 늦게 온 사람도 처음부터 다시 읽을 수 있다.

**일반적인 동작 — Kafka 는 큐가 아니라 로그다**

| | Kafka | 전통적인 메시지 큐 (RabbitMQ 등) |
|---|---|---|
| 저장 | 파티션별 append-only 로그. **읽어도 지워지지 않는다**(보존 기간까지) | 큐. 소비하고 ack 하면 지워진다 |
| 여러 소비자 | 컨슈머 그룹마다 오프셋이 따로 — 같은 메시지를 여러 서비스가 각자 읽는다 | 큐를 여러 개로 복제하는 라우팅(exchange)이 필요하다 |
| 재처리 | 오프셋을 되돌리면 다시 읽는다 | 이미 지워졌으므로 따로 설계해야 한다 |
| 순서 | 파티션 안에서 보장 | 큐 하나를 컨슈머 하나가 읽을 때 보장 |
| 강한 곳 | 처리량, 보존·재생, 팬아웃 | 유연한 라우팅, 메시지 단위 ack·재전송, 우선순위·지연 큐 |

**내 프로젝트에서는 — 코드가 요구하는 성질** [확인됨]

| 요구 | 코드 근거 |
|---|---|
| 한 사건을 여럿이 독립적으로 소비 | `PaymentCompleted` 를 order·license·settlement 가 각자의 그룹으로 소비 (2.2 표) |
| 한 서비스 장애가 다른 서비스로 번지지 않게 | download 는 license 를 부르지 않고 이벤트로 받은 권한 사본으로 판정 — license 를 멈춘 채 다운로드 20/20 성공(README 3절) |
| 유실을 재처리로 복구 | 오프셋 리셋·DLT 재투입 절차(D-030 런북) |
| 같은 주문 안의 순서 | 주문번호 키 → 결제완료와 환불이 한 파티션에서 순서대로 |
| 소비가 밀려도 생산은 계속 | 컨슈머가 포화돼도 결제 API 는 받는다. 적체는 kafka-exporter 랙으로 관측 |

**치른 대가도 함께 말한다** — 결과적 일관성(3.5), 중복 전달(at-least-once), 순서를 지키는 설계 부담, 운영 대상 증가(브로커·토픽·랙 알람),
그리고 요청 하나를 따라가기 어려워진다는 점(그래서 traceparent 를 outbox 에 저장해 Kafka 구간까지 트레이스를 이었다, decisions.md 17번).

**동기 호출이 맞는 곳도 있다** — order → catalog 가격 조회. 주문 트랜잭션 **전에 확정돼야 하는 값**이라 이벤트로 받을 수 없다.

**면접 Q&A**

- **L1** 왜 Kafka 를 사용했나요? — [11장 모범 답변 1](#답변-1--왜-kafka-를-썼나요).
- **L2** RabbitMQ 로도 되지 않나요?
  - 됩니다. 다만 이 시스템은 결제 완료 하나를 세 서비스가 각자 속도로 읽어야 하고, 장애 뒤 다시 읽어서 복구해야 하고, 주문 단위 순서가 필요합니다. Kafka 는 로그 보존과 그룹별 오프셋, 키 기반 파티션으로 이 셋을 기본 모델로 줍니다. RabbitMQ 였다면 큐를 서비스별로 복제하고 재처리용 저장을 따로 설계했을 겁니다.
- **L4** Kafka 가 죽으면 결제는 어떻게 되나요?
  - 설계 의도는 "결제는 계속된다"입니다. 이벤트는 outbox 에 PENDING 으로 쌓이고 브로커가 돌아오면 릴레이가 보냅니다. 그런데 분석해 보니 릴레이가 REPEATABLE READ 에서 outbox 에 갭 락을 쥔 채 Kafka 응답을 기다려서, 브로커가 응답하지 않으면 결제 승인 트랜잭션의 outbox INSERT 가 락 대기에 걸릴 수 있습니다. 평시에도 그 대기가 있다는 건 부하 A/B 로 확인했고(격리 수준만 바꾸면 락 대기 400회가 0회), 브로커를 실제로 멈춰 보는 재현은 아직입니다. 릴레이 트랜잭션만 READ COMMITTED 로 두거나 Kafka 대기를 트랜잭션 밖으로 빼는 게 개선안입니다(R1).

---

### 5.2 Topic

**쉽게 이해하기** — 주제별 게시판이다. 이 프로젝트에서는 **"애그리거트(주문, 결제, 라이선스…)별 게시판"** 이다.

**일반적인 동작** — 토픽은 논리적인 이름이고 실제 데이터는 파티션들에 나뉘어 저장된다.
보존 정책(시간·크기 기준 삭제, 또는 키별 최신값만 남기는 compaction), 복제 계수, 파티션 수가 토픽 단위 설정이다.

**내 프로젝트에서는**

- **[확인됨]** 토픽 6개, 이름 규칙 `stove.<애그리거트>.v1` (`Topics.java`). 버전 접미사는 깨지는 스키마 변경이나 파티션 이전을 새 토픽으로 할 자리다.
- **[확인됨]** 토픽을 코드로 만들지 않는다(`NewTopic`·`TopicBuilder` 없음). 브로커 자동 생성(`KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"`)에 맡겨 **파티션 3 · 복제 계수 1** 로 생긴다. `*.DLT` 도 첫 DLT 발행 때 같은 방식으로 생긴다.
- **[확인됨]** 보존 기간을 정한 곳이 없다 → 브로커 기본 7일. 원격 스택의 브로커에서 `stove.payment.v1` 과 `stove.payment.v1.DLT` 둘 다 `retention.ms=604800000`(7일), `cleanup.policy=delete` 다(`kafka-configs.sh --describe`).
- 한 토픽에 여러 이벤트 타입이 섞이고 `eventType` 헤더로 가른다.

**왜 이벤트 타입별이 아니라 애그리거트별인가** — Kafka 가 순서를 보장하는 범위가 **한 토픽의 한 파티션**이기 때문이다.
`PaymentCompleted` 와 `PaymentCancelled` 를 다른 토픽에 두면, 같은 주문의 "결제 → 환불" 순서를 Kafka 가 지켜 줄 방법이 없다.

**면접 Q&A**

- **L2** 토픽은 어떤 기준으로 나눴나요? — 순서를 지켜야 하는 단위(애그리거트)로 나눴다. 위 설명.
- **L3** 토픽 자동 생성에 기대면 무엇이 문제인가요?
  - 오타 난 토픽이 조용히 생기고, 파티션 수와 복제 계수를 브로커 기본값이 정합니다. 파티션은 나중에 늘리면 키 매핑이 바뀌어 순서가 깨지니 처음부터 명시하는 게 맞습니다. 운영에서는 자동 생성을 끄고 토픽을 코드나 인프라 설정으로 만듭니다.
- **L4** 이벤트 스키마를 바꿔야 하면요?
  - 필드 추가처럼 옛 컨슈머가 무시할 수 있는 변경은 같은 토픽에서 합니다. 필드 의미가 바뀌는 깨지는 변경은 `v2` 토픽을 만들고, 발행을 옮기기 전에 컨슈머가 두 토픽을 모두 읽게 배포합니다.

---

### 5.3 Partition

**쉽게 이해하기** — 한 게시판을 여러 줄로 나눈 것이다. 줄마다 번호표(오프셋)를 따로 매기고, 한 줄 안에서만 순서가 있다.

**일반적인 동작**

- 파티션은 추가만 되는 로그 파일(세그먼트)이고 오프셋은 그 안의 순번이다.
- 키가 있으면 기본 파티셔너가 `murmur2(키) % 파티션수` 로 고른다 — **같은 키는 언제나 같은 파티션**. 키가 없으면 배치를 채우며 파티션을 옮겨 다닌다.
- 순서는 **파티션 안에서만** 보장된다.
- 한 그룹 안에서 파티션 하나는 컨슈머 하나만 맡으므로, **파티션 수가 병렬 처리의 상한**이다.
- 파티션은 늘릴 수만 있다. 늘리면 `% 파티션수` 가 바뀌어 같은 키가 옛 파티션과 새 파티션으로 갈라진다 — 옛 파티션에 미처리 메시지가 남아 있으면 순서가 깨진다.

**내 프로젝트에서는**

- **[확인됨]** 파티션 3. 키는 outbox 의 `partition_key` — order·payment·license 계열은 주문번호, studio·review·catalog 계열은 상품코드.
- **[확인됨]** `concurrency` 를 정하지 않아 리스너마다 스레드 하나가 파티션 셋을 모두 맡는다.
- 측정 — payment 가 `stove.order.v1` 적체를 소비할 때 `concurrency` 1 → 3 → 1 순서로 141.1 → 364.1 → 144.3 events/s. 두 번의 1 평균 대비 ×2.55 다(perf-tuning.md 4절). 파티션 수까지 올리는 것은 순서에 안전하다: 스레드마다 파티션을 하나씩 맡을 뿐 같은 키는 여전히 한 스레드가 처리한다. 지금은 필요할 때 켤 설정으로 남겨 두었다.
- 키 카디널리티 — 주문번호는 끝없이 늘어나 편중이 없다. 상품코드는 게임 수가 적은 초기에 편중될 수 있다(handover.md 2.6).
- 증설 절차 — 릴레이를 꺼서 outbox 에 쌓아 두고, 모든 그룹의 랙이 0 이 된 뒤 늘리고, 릴레이를 켠다(handover.md 2.8). **Outbox 가 있어서 가능한 절차다.** 동기 발행이었다면 발행을 멈추려면 결제 API 를 멈춰야 한다.

**면접 Q&A**

- **L2** 파티션 키를 왜 주문번호로 했나요?
  - 순서를 지켜야 하는 최소 단위가 주문이기 때문입니다. 같은 주문의 결제 완료 뒤에 환불이 와야 하는데, 다른 주문끼리는 순서가 상관없습니다. 키를 주문번호로 두면 같은 주문은 한 파티션에서 순서대로, 다른 주문은 다른 파티션에서 동시에 처리됩니다.
- **L3** 컨슈머가 느린데 파티션을 늘리면 되나요?
  - 먼저 `concurrency` 를 파티션 수까지 올리는 게 순서입니다. 지금 스레드 하나가 파티션 셋을 맡고 있는데, 3 으로 올렸을 때 처리량이 2.55배가 되는 걸 측정해 두었습니다. 파티션을 늘리는 건 키 매핑이 바뀌어 순서가 깨질 수 있어서, 발행을 멈추고 랙을 비운 뒤에 해야 합니다.
- **L5** 인기 게임 하나에 상품 이벤트가 몰리면요?
  - 상품코드 키가 한 파티션에 몰리는 hot partition 입니다. 순서를 지킬 단위가 상품이라 키를 쪼갤 수는 없어서, 그 파티션을 맡은 컨슈머의 처리 속도를 올리는 쪽으로 풀어야 합니다. 컨슈머를 더 붙여도 한 파티션은 한 컨슈머만 맡으니 늘리는 것만으로는 해결되지 않습니다.

---

### 5.4 Producer

**쉽게 이해하기** — 편지를 부치는 사람이다. 우체국(브로커)이 "받았다"는 영수증(ack)을 준다. 영수증을 몇 곳에서 받아야 안심할지가 `acks` 다.

**일반적인 동작**

```text
send(record)
 └ 직렬화 → 파티셔너(키 해시) → RecordAccumulator (파티션별 배치: batch.size, linger.ms)
     └ Sender 스레드 → 파티션 리더에 ProduceRequest
          acks=0    보내고 끝
          acks=1    리더가 로그에 쓰면 응답
          acks=all  ISR 전원이 받아 적으면 응답 (ISR 이 min.insync.replicas 보다 적으면 거절)
     └ 실패하면 retries — 단 delivery.timeout.ms 안에서만
 ※ 메타데이터를 못 얻거나 버퍼가 차면 send() 를 부른 스레드가 max.block.ms 까지 막힌다
```

- **Leader / Follower / Replication** — 파티션마다 리더 하나와 팔로워들이 있다. 쓰기·읽기는 리더가 받고 팔로워는 리더 로그를 복제한다.
- **ISR (In-Sync Replicas)** — 리더를 제때 따라잡고 있는 복제본 집합. 컨슈머에게는 ISR 전원이 받은 지점(high watermark)까지만 보인다. 리더가 죽으면 ISR 안에서 새 리더를 뽑는다(ISR 밖 복제본은 기본적으로 리더가 될 수 없다 — `unclean.leader.election.enable=false`).
- **멱등 프로듀서** — 프로듀서 ID 와 파티션별 시퀀스 번호로 브로커가 재시도 중복과 순서 역전을 걸러낸다. 범위는 **한 프로듀서 인스턴스의 재시도**까지다. 애플리케이션이 같은 내용을 새로 `send()` 하면 브로커에게는 다른 레코드다.

**내 프로젝트에서는** [확인됨 — kafka-clients 3.9.1 jar 의 `ProducerConfig` 기본값 + `application.yml`]

| 설정 | 3.9.1 기본값 | 이 프로젝트 | 의미 |
|---|---|---|---|
| `acks` | all | all | ISR 전원 기록 확인 |
| `enable.idempotence` | true | true (6개 서비스가 명시) | 재시도 중복·역전 방지 |
| `retries` | 2147483647 | 3 (studio·review·catalog·order·payment·license) | 재시도 상한. 어차피 `delivery.timeout.ms` 가 먼저 끝을 정한다 |
| `delivery.timeout.ms` | 120000 | 기본 | `send()` 뒤 성공·실패가 확정되기까지 최대 2분 |
| `max.block.ms` | 60000 | 기본 | `send()` 호출 자체가 막힐 수 있는 최대 시간 |
| `max.in.flight.requests.per.connection` | 5 | 기본 | 멱등 프로듀서는 5까지 순서를 지킨다 |
| `linger.ms` / `batch.size` | 0 / 16384 | 기본 | 모으지 않고 바로 보낸다 |
| `transactional.id` | 없음 | 없음 | Kafka 트랜잭션을 쓰지 않는다 |

- **도메인 이벤트를 보내는 것은 각 서비스 안의 `OutboxRelay` 하나뿐**이다. 그 밖의 발행은 DLT 발행과 DLT 재투입이다.
- 릴레이는 웨이브마다 `send()` 를 걸어 두고 `ack.get()` 으로 **타임아웃 없이** 기다린다(`OutboxRelay.java:84`, `:119`). 브로커가 응답하지 않으면 `delivery.timeout.ms` 가 끝날 때까지 기다리고, 그동안 배치 트랜잭션이 열려 있다 **[코드상 추정]** → R1.
- 실패하면 `markFailed` → 1초에서 두 배씩 기다렸다 다시 보내고(코드상 상한은 5분이지만 10회 한도 안에서는 256초가 마지막이다), 10번째 실패에 `DEAD` → `OutboxEventsAbandoned` 알람 + 운영 API 로 회수.
  백오프만 더하면 약 8.5분이다. 브로커가 응답하지 않을 때는 시도마다 `delivery.timeout.ms`(2분)까지의 대기가 더해지므로 실제로 버티는 시간은 그보다 길다 **[코드상 추정]**.
- **[확인됨] 브로커 1대, 복제 계수 1**(`docker-compose.yml`, 원격 스택 브로커의 `kafka-topics.sh --describe` 에서 DLT 까지 전 토픽 파티션 3 · 복제 계수 1, 설정을 열어 본 payment 토픽과 DLT 는 `min.insync.replicas=1`). ISR 이 리더 하나뿐이라 `acks=all` 이어도 **`acks=1` 과 같은 내구성**이다. 브로커 디스크를 잃으면 소비되지 않은 이벤트를 잃는다. 로컬·CI 구성이며 운영 구성은 **[추가 확인 필요]**.

**[권장 개선안] 운영이라면** — 브로커 3대, 복제 계수 3, `min.insync.replicas=2`, `acks=all`.
토픽 자동 생성을 끄고 파티션 수·보존 기간을 코드나 인프라 설정으로 명시한다(DLT 는 보존 기간을 길게).

**면접 Q&A**

- **L1** `acks` 0·1·all 의 차이는요? — 위 그림.
- **L3** `acks=all` 이면 메시지가 유실되지 않나요?
  - 복제 계수와 `min.insync.replicas` 가 받쳐 줄 때만입니다. ISR 이 리더 하나로 줄어든 상태면 all 도 리더 하나의 기록만 확인하고, 그 리더 디스크를 잃으면 사라집니다. 이 프로젝트의 로컬 구성은 브로커 한 대에 복제 계수 1 이라 all 이 사실상 1 입니다. 운영이라면 복제 계수 3 에 `min.insync.replicas=2` 로 둡니다.
- **L3** 멱등 프로듀서를 켰으니 중복 발행은 없나요?
  - 프로듀서 내부 재시도로 생기는 중복만 막습니다. 릴레이가 ack 를 받은 뒤 SENT 를 커밋하기 전에 죽으면, 재기동한 릴레이가 같은 이벤트를 새로 보냅니다. 브로커 입장에서는 새 레코드라 멱등 프로듀서가 못 거릅니다. 그 중복은 컨슈머의 Inbox 가 eventId 로 거릅니다.
- **L4** 브로커가 느려지면 릴레이는 어떻게 되나요? — R1.

---

### 5.5 Consumer

**쉽게 이해하기** — 게시판을 읽는 사람이다. 어디까지 읽었는지 책갈피(오프셋)를 꽂아 두고, 다음에 거기서부터 읽는다.

**일반적인 동작**

```text
while (running) {
    records = consumer.poll(timeout)     // 한 번에 최대 max.poll.records(500)건
    for (record : records) process(record)
    commit                               // 자동/수동, 동기/비동기
}
// 별도 heartbeat 스레드가 session.timeout.ms(45초) 안에 "살아 있다"를 알린다
// poll 과 poll 사이가 max.poll.interval.ms(300초)를 넘으면 그룹에서 쫓겨난다
```

Spring Kafka 는 `@KafkaListener` 마다 리스너 컨테이너를 만들고, 전용 스레드가 위 루프를 돌며 레코드마다 우리 메서드를 부른다.
**정상 리턴이면 성공, 예외면 실패**로 판정하고 — 리턴값은 보지 않는다 — 실패는 에러 핸들러로 넘긴다([kafka-consumer-retry.md](kafka-consumer-retry.md) 1절).

**내 프로젝트에서는** [확인됨 — `application.yml`, spring-kafka 3.3.10 소스, kafka-clients 3.9.1 jar, 원격 스택에서 실행 중인 order 앱의 기동 로그(`ConsumerConfig values`·`ProducerConfig values`)]

| 설정 | 값 | 의미 |
|---|---|---|
| `enable-auto-commit` | false | 시간 기반 자동 커밋을 끈다 |
| `listener.ack-mode` | record | **레코드 하나를 정상 처리할 때마다 커밋** (spring-kafka 기본은 BATCH) |
| `syncCommits` | true (spring-kafka 기본) | `commitSync` — 커밋이 끝나야 다음 레코드로 간다 |
| `auto-offset-reset` | earliest | 커밋 기록이 없으면 처음부터 (클라이언트 기본은 latest) |
| `concurrency` | 미설정 → 1 | 리스너 컨테이너당 스레드 하나 |
| `max.poll.records` · `max.poll.interval.ms` · `session.timeout.ms` | 기본 500 · 300초 · 45초 | 한 번에 받는 양 · 처리 시간 한도 · 생존 신호 한도 |

**리스너 12개와 멱등 방식**

| 그룹 | 토픽 | 처리하는 타입 | 멱등 방식 | 트랜잭션 |
|---|---|---|---|---|
| payment | order | `OrderCreated` | Inbox + `uk_payment_order_no` | 있음 |
| payment | license | `LicenseIssueFailed` | Inbox + 결제 상태 | 파사드 — 트랜잭션 둘 사이에 PG |
| order | payment | Completed · Cancelled · Failed | Inbox + 상태 전이 가드 | 있음 |
| license | payment | Completed · Cancelled | Inbox + `uk_license_order_product` | 있음 |
| settlement | payment | Completed · Cancelled | Inbox + `uk_settlement_record` | 있음 |
| catalog | review | `ReviewApproved` | Inbox + 상품코드 upsert | 있음 |
| studio | review | Approved · Rejected | Inbox + 상태 | 있음 |
| review | studio | `GameRegistered` | Inbox + 상태 | 있음 |
| store | catalog | `ProductChanged` | 문서 ID 고정 upsert | 없음 (ES) |
| download ×3 | studio · catalog · license | `BuildUploaded` · `ProductChanged` · `LicenseIssued/Revoked` | 문서 ID 고정 upsert, 회수는 주문번호 대조 | 없음 (Mongo) |

- 처리 속도 — payment 컨슈머가 `concurrency` 1 에서 141.1 events/s(perf-tuning.md 4절), 한 건에 약 7ms 다. 500건 한 묶음이 3.5초 남짓이라 300초 한도에 한참 못 미친다.
- 관심 없는 타입(review 가 받은 `BuildUploaded` 등)은 아무것도 하지 않고 리턴하므로 오프셋만 전진한다.
- 값은 `StringDeserializer` 로 받고 리스너 안에서 Jackson 으로 푼다. 그래서 형식이 깨진 메시지는 컨테이너가 아니라 **리스너 안의 `IllegalStateException`** 으로 드러난다(`EventEnvelope`) — 재시도 분류에 영향이 있다(R11).

**면접 Q&A**

- **L2** 컨슈머는 메시지를 처리한 뒤 무엇을 하나요?
  - DB 트랜잭션을 커밋하고 리스너가 정상 리턴하면, 컨테이너가 그 레코드의 다음 오프셋을 동기로 커밋합니다. 레코드마다 커밋해서 중간에 죽어도 다시 처리되는 범위가 한 건입니다.
- **L3** ack-mode 를 RECORD 로 한 이유와 대가는요?
  - BATCH 는 poll 한 묶음을 다 처리하고 한 번 커밋해서 빠르지만, 중간에 죽으면 묶음 전체가 다시 옵니다. 이 시스템은 Inbox 가 중복을 흡수하니 BATCH 도 정확성은 지키지만, 결제 이벤트 수백 건이 한꺼번에 재처리되는 것보다 한 건만 되풀이되는 편이 장애 때 예측하기 쉽습니다. 대가는 레코드마다 커밋 왕복이 붙는 것입니다.
- **L3** 리스너에서 예외를 catch 하면 어떻게 되나요?
  - 컨테이너는 정상 리턴을 성공으로 보고 오프셋을 커밋하므로 재시도가 한 번도 돌지 않습니다. 실제로 이 저장소에 그 결함이 있었고, DB 가 1초 끊긴 사이 정상 결제가 환불됐습니다(D-002). 보상 같은 최종 처리는 재시도가 끝난 뒤 불리는 recoverer 에 둡니다.

---

### 5.6 Consumer Group

**쉽게 이해하기** — 같은 일을 나눠 하는 팀이다. 팀 안에서는 줄(파티션) 하나를 한 명만 맡고, **팀끼리는 서로 상관없이** 같은 게시판을 각자 읽는다.

**일반적인 동작**

- `group.id` 가 같으면 한 그룹이다. 브로커 하나가 그룹 코디네이터로서 멤버십을 관리한다. classic 프로토콜에서 배정 계산은 멤버 중 하나(그룹 리더 컨슈머)가 하고, 코디네이터는 그 결과를 나눠 준다.
- 그룹 안에서 파티션 하나 ↔ 컨슈머 하나. 컨슈머가 파티션보다 많으면 남는 컨슈머는 논다.
- 오프셋은 그룹마다 따로 `__consumer_offsets` 토픽에 저장된다. 그래서 그룹이 100개여도 모두 같은 메시지를 읽는다.

**내 프로젝트에서는**

- **[확인됨]** 그룹 이름 = 서비스 이름 = Inbox 멱등 키. 각 서비스의 `CONSUMER_GROUP` 상수를 리스너와 가드가 함께 쓰고, `ConsumerGroupRules` 가 둘이 같은 값인지 검사한다.
- `stove.payment.v1` 은 그룹 셋이 각자 읽는다 — 같은 `PaymentCompleted` 를 세 서비스가 한 번씩 받는 것은 중복이 아니다. **Inbox 키에 그룹이 들어가는 이유**가 이것이다.
- **[확인됨]** 한 서비스에 리스너가 여럿이면 **같은 그룹에 멤버가 여럿**이다 — payment 2(order·license 토픽), download 3. 원격 스택 브로커에서 `kafka-consumer-groups.sh --describe --state` 로 보면 `payment` 멤버 2, `download` 멤버 3이고, download 의 세 멤버가 각자 다른 토픽의 파티션 0·1·2 를 맡고 있다. 구독 토픽이 달라도 한 그룹이라, classic 프로토콜에서는 한 멤버의 재시작이 그룹 전체의 리밸런싱을 부른다.
- **D-037** — 통합 테스트 JVM 에 컨텍스트가 두 벌 떠 license 그룹 멤버가 둘이었다. 한쪽 리스너를 멈추자 소비가 멈춘 게 아니라 **파티션이 옆 멤버로 넘어가** "중단 중에 지급이 일어났다". Kafka 에게 이것은 장애가 아니라 리밸런싱이다.
- **[코드상 추정]** 그룹 이름을 바꾸면 오프셋도 Inbox 키도 새로 시작한다. `auto-offset-reset: earliest` 라 보존 기간 안의 이벤트를 전부 다시 처리하는데 Inbox 가 거르지 못한다(키가 다르다). 남는 방어선은 도메인 유니크뿐이다.

**면접 Q&A**

- **L1** Consumer Group 이 무엇인가요? — 위.
- **L2** 결제 이벤트를 세 서비스가 받는데, 멱등 키를 eventId 하나로 하면 안 되나요?
  - 안 됩니다. 먼저 처리한 서비스가 eventId 를 기록하면 나머지 서비스는 "이미 처리했다"고 보고 버리게 됩니다. 처리 여부는 소비하는 쪽마다 따로라서 `(event_id, consumer_group)` 을 키로 둡니다.
- **L3** 파티션 수보다 컨슈머가 많으면요? — 남는 컨슈머는 논다. 파티션이 3이라 인스턴스 수 × `concurrency` 가 3을 넘으면 의미가 없다.

---

### 5.7 Offset

**쉽게 이해하기** — Offset 은 Kafka 에서 **Consumer 가 어디까지 읽었는지 기억하는 위치 정보**라고 생각하면 된다. 책갈피다.

**정확한 정의**

- 파티션 로그 안에서 레코드의 순번이다. 0 부터 늘고, 파티션마다 따로 센다.
- **커밋된 오프셋** = 그룹이 **다음에 읽을** 위치(마지막으로 처리한 오프셋 + 1).
- **누가 관리하나** — 어디까지 처리했는지 판단하고 커밋을 요청하는 것은 **컨슈머(애플리케이션)**, 그 값을 `__consumer_offsets` 토픽에 저장하는 것은 **브로커(그룹 코디네이터)** 다.
  브로커는 메시지를 지우지 않으므로, **"처리 완료"를 뜻하는 표시는 이 커밋 하나뿐**이다.
- **Log-end offset** 은 다음에 쓰일 자리이고, **랙 = log-end offset − 커밋된 오프셋** 이다.
- 커밋 기록이 없을 때 어디서 시작할지는 `auto.offset.reset`(earliest / latest)이 정한다. 그룹이 오래 비어 있으면 커밋 기록 자체가 만료된다(`offsets.retention.minutes`, 기본 7일).

**커밋 시점이 전달 보장을 정한다**

```text
처리 전에 커밋 — at-most-once
  poll → commit(101) → process(100) → 💥
  재시작: 101 부터 → 100 은 처리되지 않았는데 다시 오지 않는다          = 유실

처리 후에 커밋 — at-least-once
  poll → process(100) → 💥 (commit 전)
  재시작: 100 부터 → 이미 처리한 100 이 또 온다                       = 중복
```

자동 커밋(`enable.auto.commit=true`)은 `poll()` 할 때 **이전 poll 에서 받은 것**을 주기적으로 커밋한다.
처리를 poll 루프 안에서 끝내면 대체로 at-least-once 지만, 처리를 다른 스레드로 넘기면 처리 전에 커밋될 수 있어 at-most-once 가 된다.

**내 프로젝트에서는 — 레코드 하나의 일생** [확인됨]

```text
poll() → 레코드 N  (key=ORD-1, PaymentCompleted)
  LicenseService.issue()
    [TX] INSERT processed_event → INSERT license → INSERT outbox_event → COMMIT        ① DB 커밋
  리스너 정상 리턴
  컨테이너: commitSync({stove.payment.v1-0: N+1})                                     ② 오프셋 커밋
```

**①과 ②는 서로 다른 시스템의 커밋이라 원자적이지 않다.** 둘 사이에서 죽으면 N 이 다시 오고, 재처리는 Inbox 가 "이미 처리"로 끝낸다.
오프셋 커밋이 실패하는 경우(리밸런싱 중 `CommitFailedException` 등)도 결과가 같다 — 새로 파티션을 맡은 컨슈머가 N 을 다시 받는다.

- `auto-offset-reset: earliest` — 유실보다 중복을 고른 설정이고, 그 대가를 Inbox 가 치른다.
- 랙은 앱 지표가 아니라 **kafka-exporter**(브로커에 커밋 오프셋과 log-end offset 을 직접 묻는다)로 본다. 앱이 내는 랙 지표는 컨슈머가 멈추면 같이 멈춰, 실제 랙 113,517건 동안 0 을 보고했다(D-026).
- DLT 운영 API 는 처리 그룹과 섞이지 않게 `<앱>-dlt-ops` 그룹을 쓰고, 파티션을 `assign` 으로 직접 잡고, **재발행이 끝난 뒤에만** 커밋한다(`DltOpsService.java:54-66`) — 순서를 뒤집으면 실패한 레코드가 사라진다.

**"메시지를 한 번만 처리한다"는 것의 실제 의미** — Kafka 는 "한 번만 전달"을 약속하지 않는다.
이 프로젝트가 만드는 것은 **"여러 번 전달돼도 결과는 한 번만 반영된다"** 이고, 그 장치가 Inbox 마킹과 비즈니스 변경을 한 DB 커밋에 넣는 것이다.

**면접 Q&A**

- **L1** Offset 은 무엇이고 누가 관리하나요? — 위 정의.
- **L2** 처리 전에 커밋하면? 처리 후에 커밋하면? — 위 그림.
- **L4** Consumer 가 DB 에 커밋한 다음, 오프셋 커밋 전에 죽으면요? — [11장 모범 답변 2](#답변-2--db-커밋-후-오프셋-커밋-전에-죽으면).
- **L5** 오프셋을 DB 에 결과와 같이 저장하면 정확히 한 번이 되지 않나요?
  - DB 커밋과 오프셋 커밋 사이의 틈은 닫힙니다. 결과와 오프셋을 한 DB 트랜잭션에 쓰고, 파티션을 배정받을 때 DB 에 저장한 오프셋으로 seek 하면 됩니다. 다만 리밸런스 리스너에서 seek 을 직접 구현해야 하고, **같은 이벤트가 다른 오프셋으로 다시 들어오는 경우는 못 거릅니다** — 릴레이의 중복 발행이나 DLT 재투입이 그렇습니다. Inbox 는 eventId 로 판단하니 그 경우까지 거르고 파티션 이동과도 무관해서, 이 프로젝트에는 Inbox 가 맞았습니다.

---

### 5.8 Rebalancing

**쉽게 이해하기** — 팀원이 들어오거나 나가면 줄 담당을 다시 나누는 회의다. 방식에 따라 회의 동안 **모두 손을 멈추기도** 한다.

**일반적인 동작**

| 질문 | 답 |
|---|---|
| 언제 일어나나 | 멤버 합류·이탈(배포·재시작·스케일), heartbeat 가 `session.timeout.ms` 동안 끊김, poll 간격이 `max.poll.interval.ms` 초과, 구독·파티션 수 변경 |
| Eager | 모든 멤버가 **모든 파티션을 내려놓고** 다시 받는다 (Range·RoundRobin 배정) — 그동안 그룹 전체가 멈춘다 |
| Cooperative | 옮겨야 하는 파티션만 내려놓는다 (CooperativeSticky) |
| Static membership | `group.instance.id` 를 주면 짧은 재시작을 같은 멤버로 인정해 리밸런싱하지 않는다 |
| 새 프로토콜 | KIP-848(`group.protocol=consumer`) — 브로커가 배정을 주도한다. Kafka 4.0 에서 정식이고 3.9 의 기본은 `classic` |
| 처리 중이던 레코드 | 파티션을 잃은 컨슈머의 커밋이 실패하거나 커밋 전이면, 새 담당이 같은 레코드를 다시 처리한다 → **중복** |
| 처리가 길면 | poll 사이가 한도를 넘어 쫓겨나고 커밋은 실패하고 새 담당이 다시 받는다. 처리가 계속 길면 쫓겨났다 들어오기를 반복하는 **리밸런싱 루프** |

**내 프로젝트에서는**

- **[확인됨 — jar]** `partition.assignment.strategy` 기본값은 `[RangeAssignor, CooperativeStickyAssignor]`, `group.protocol` 은 `classic` 이다. 이 프로젝트는 둘 다 재정의하지 않는다.
  목록에 Eager 만 지원하는 Range 가 들어 있으면 클라이언트는 Eager 방식으로 리밸런싱하고, 모든 멤버가 같은 목록이면 코디네이터도 첫 전략(Range)을 고른다. 결과는 **Eager 리밸런싱**이다 — 원격 스택 브로커에서 조회한 order·payment·license·settlement·download·store 여섯 그룹이 모두 `ASSIGNMENT-STRATEGY range` 로 나온다 **[확인됨 — OCI]**(catalog·studio·review 그룹은 조회하지 않았지만 같은 설정이다).
- **[확인됨]** static membership 없음, `concurrency` 1.
- 가장 흔한 트리거는 **배포**다. 컨테이너가 graceful 하게 멈추며 그룹을 떠나 즉시 리밸런싱이 일어난다. payment·download 처럼 한 그룹에 리스너가 여럿이면 하나의 재시작이 그룹 전체를 멈춘다.
- 중복은 Inbox 가 흡수한다 — R-03 `ConsumerRestartCatchUpTest` 가 "따라잡기가 이중 지급이 되지 않는다"를 지킨다.
- **긴 처리 위험 계산** — 측정된 소비 속도(한 건 약 7ms)로는 500건 한 묶음이 4초 안팎이다. 한도에 닿으려면 레코드당 평균 600ms 가 걸려야 한다. 재시도 한 바퀴(백오프 7초 + 시도마다 커넥션 대기 최대 3초)는 매 시도가 seek 후 다시 poll 하므로 한도를 채우지 않는다.
  락 대기(R1)도 50초를 넘기면 1205 예외로 끝나 같은 경로를 탄다. 한도를 넘기려면 **50초 안에 풀리는 긴 대기가 한 묶음에 여러 번** 쌓여야 한다(건당 40초면 8건째).
  브로커가 죽으면 fetch 도 멈추니, 그런 모양은 브로커가 fetch 는 받아 주면서 ack 만 느릴 때 나온다 **[코드상 추정]**.

**[권장 개선안]** `CooperativeStickyAssignor` 를 단독으로 지정해 배포 때 그룹 전체가 멈추지 않게 한다.
롤링 배포가 잦으면 `group.instance.id` 를, 처리 시간이 길어질 수 있는 리스너는 `max.poll.records` 를 낮춘다.

**면접 Q&A**

- **L1** Rebalancing 이 무엇이고 언제 일어나나요? — 위 표.
- **L2** Consumer 가 죽으면 어떻게 되나요?
  - heartbeat 가 끊기면 `session.timeout.ms` 뒤에 코디네이터가 그 멤버를 빼고 리밸런싱합니다. 그 파티션을 맡게 된 컨슈머는 마지막으로 커밋된 오프셋부터 읽으니, 죽은 컨슈머가 처리했지만 커밋하지 못한 레코드는 다시 처리됩니다. 배포처럼 정상 종료면 그룹을 바로 떠나서 타임아웃을 기다리지 않습니다.
- **L3** 리밸런싱 중에 처리 중이던 메시지는 중복 처리될 수 있나요?
  - 네. 이 프로젝트는 레코드마다 커밋하니 범위는 처리 중이던 한 건이고, 그 한 건은 Inbox 가 "이미 처리"로 거릅니다.
- **L4** 처리 시간이 긴 메시지는 Consumer 에 어떤 영향을 주나요?
  - poll 과 poll 사이가 `max.poll.interval.ms` 를 넘으면 그 컨슈머가 그룹에서 쫓겨나고 커밋은 실패합니다. 새 담당이 같은 레코드를 받아 또 오래 걸리면 계속 쫓겨나는 루프가 됩니다. 지금은 500건 한 묶음이 3.5초 남짓이라 여유가 크지만, 수십 초짜리 DB 락 대기가 한 묶음에 여러 번 붙으면 여유가 사라질 수 있어서 `max.poll.records` 를 낮추는 게 먼저 쓸 수단입니다.

---

### 5.9 Ordering

**한 줄 요약** — Kafka 는 **파티션 안에서 넣은 순서**만 지킨다. 무엇을 같은 파티션에, 어떤 순서로 넣을지는 애플리케이션 몫이다.

**이 프로젝트가 지키는 세 층** — 자세한 설명은 [event-ordering.md](event-ordering.md).

| 층 | 깨지는 원인 | 방어 [확인됨] |
|---|---|---|
| 프로듀서 | 재시도가 앞선 in-flight 요청을 추월 | `enable.idempotence=true` |
| 발행자 (Outbox 릴레이) | 발행 실패한 앞 이벤트를 같은 키의 뒤 이벤트가 추월 | 키 웨이브 발행 + `holdUntil` 전파 (D-013, D-014) |
| 컨슈머 | 스레드풀 오프로드, 논블로킹 재시도 | 받은 스레드에서 순차 처리 — ArchUnit 이 금지를 강제 |

**그래도 순서가 뒤집히는 자리** — 이 문서가 새로 짚는 곳이다.

| 자리 | 어떻게 뒤집히나 | 라벨 |
|---|---|---|
| **DLT 재투입** | 실패한 레코드를 DLT 로 빼고 **같은 키의 다음 레코드를 먼저 처리**한다. 재투입은 원본 토픽의 **맨 끝**에 붙는다 | [현재 구현의 잠재적 문제] [R2](#r2-dlt-재투입과-dead-회수가-같은-키의-순서를-뒤집는다) |
| Outbox DEAD 회수 | 앞 이벤트가 DEAD 가 되면 `holdUntil` 이 뒤 이벤트를 풀어 준다(설계상 탈출구). 나중에 DEAD 를 회수하면 뒤 이벤트보다 늦게 나간다 | R2 |
| 릴레이 다중화 | 서비스를 두 대로 늘리면 릴레이도 두 대가 되고, `SKIP LOCKED` 는 키를 모른다 | [R13](#r13-릴레이-1대-제약이-강제되지-않는다) |
| 같은 키를 동시에 쓰는 두 트랜잭션 | outbox `id` 는 INSERT 순서인데 커밋 순서는 다를 수 있고, 릴레이는 `ORDER BY id` 로 보낸다 | [코드상 추정] 아래 |
| 파티션 증설 | 키 → 파티션 매핑이 바뀐다 | 알려진 제약 (event-ordering.md 5절) |

**outbox id 순서 ≠ 커밋 순서** — 드물지만 CDC 와 폴링의 차이를 설명할 때 좋은 예다.

```text
상품 상태 SUSPENDED 에서 운영자 둘이 거의 동시에 버튼을 누른다 (catalog, @Version 없음)
Tx S (suspend)   INSERT outbox id=100 (SUSPENDED 스냅샷) ……………………………… UPDATE product · COMMIT (t4)
Tx O (openSale)          INSERT outbox id=101 (ON_SALE 스냅샷) · UPDATE product · COMMIT (t3)
DB 최종   = SUSPENDED  (늦게 커밋한 S 가 덮는다)
릴레이가 t4 뒤에 폴링하면 ORDER BY id → 100(SUSPENDED), 101(ON_SALE) → store 최종 = ON_SALE   ← DB 와 반대
```

binlog 를 읽는 CDC 는 **커밋 순서**로 읽으므로 이 문제가 없다(event-ordering.md 6절 B).

**멱등과 교환법칙은 다르다** — store·download 의 문서 ID 고정 upsert 는 **같은 이벤트가 두 번** 와도 결과가 같다(멱등).
하지만 **옛 이벤트가 새 이벤트 뒤에** 오면 옛 상태로 덮는다. A 다음 B 와 B 다음 A 의 결과가 다르므로 교환법칙은 성립하지 않는다.
event-ordering.md 6절 D-2 는 이 upsert 를 "멱등 + 교환법칙"의 예로 드는데, 같은 저장소의 D-013 시나리오 A 가 바로 그 반례다(store 가 `ON_SALE` 을 옛 `APPROVED` 로 덮어 판매 중 상품이 검색에서 사라짐).
교환법칙을 만들려면 이벤트에 버전을 싣고 더 새 것만 적용해야 한다(event-ordering.md C-2).

**면접 Q&A**

- **L1** Kafka 는 메시지 순서를 보장하나요? — 파티션 안에서만. 같은 키는 같은 파티션.
- **L2** 같은 주문의 이벤트 순서는 어떻게 지키나요?
  - 주문번호를 키로 써서 같은 파티션에 넣고, 릴레이가 같은 키의 이벤트를 앞의 것이 성공해야 다음 것을 보내게 했습니다. 컨슈머는 받은 스레드에서 순서대로 처리하고, 비동기 처리나 논블로킹 재시도는 ArchUnit 으로 막았습니다.
- **L3** 컨슈머 `concurrency` 를 올리면 순서가 깨지지 않나요?
  - 파티션 수까지는 안전합니다. 스레드마다 파티션을 통째로 맡고, 같은 키는 한 파티션에만 있으니까요. 깨지는 건 리스너 안에서 다른 스레드로 넘길 때입니다.
- **L4** 재시도가 끝나 DLT 로 간 메시지 뒤에 같은 주문의 메시지가 오면요? — [R2](#r2-dlt-재투입과-dead-회수가-같은-키의-순서를-뒤집는다), [10장 체인 D](#체인-d--dlt-와-순서).

---

### 5.10 Retry

**컨슈머 재시도** [확인됨 — `ConsumerRetryPolicy`, spring-kafka 소스]

- `DefaultErrorHandler` + `ExponentialBackOffWithMaxRetries(3)` — **1초 → 2초 → 4초**(간격 상한 8초), 최초 1회 + 재시도 3회.
- 재시도의 정체는 **seek 되감기**다. 실패한 레코드의 위치로 되돌려 다음 poll 이 같은 레코드를 주게 한다(`SeekUtils`).
- **블로킹 재시도**라 그동안 그 파티션은 앞으로 가지 못한다. 순서와 맞바꾼 것이다.
- `max.poll.interval.ms` 는 재시도 **총합**이 아니라 **시도 한 번**(처리 + 백오프 한 번)에 걸린다. 에러 핸들러가 백오프를 한 번 자고 seek 한 뒤 돌아가면 컨테이너가 다시 poll 하기 때문이다(`FailedRecordTracker#recovered`, `SeekUtils#doSeeks`). kafka-consumer-retry.md 6절의 "총합 5분" 은 이 점에서 부정확하다(8.2).
- 재시도는 예외가 리스너 **밖으로** 나올 때만 돈다(D-002).
- `@RetryableTopic`(논블로킹 재시도)은 실패 메시지를 뒤로 미뤄 순서를 깨므로 ArchUnit 이 금지한다.
- 스프링이 기본으로 재시도하지 않는 예외는 역직렬화·변환 계열 여섯뿐이다(`DeserializationException`, `MessageConversionException`, `ConversionException`, `MethodArgumentResolutionException`, `NoSuchMethodException`, `ClassCastException`).
  그래서 **다시 해도 성공할 수 없는** 계약 위반(`IllegalStateException`)과 도메인 거절(`BusinessException`)도 네 번 시도하며 파티션을 7초 넘게 세운다 → [R11](#r11-재시도해도-성공할-수-없는-실패도-네-번-시도한다).

**세 가지 재시도가 서로 다른 이유**

| | 컨슈머 | Outbox 릴레이 | 환불 재개 스윕 |
|---|---|---|---|
| 간격 | 1 · 2 · 4초 | 1초부터 두 배, 상한 5분 | 2 → 4 → 8 → 16 → 30분 |
| 소진되면 | DLT (license 는 보상 판단) | `DEAD` + 알람 + 운영 회수 | **포기 없음** — 1시간 예산을 넘기면 사람을 부른다 |
| 무엇이 제약인가 | 파티션을 세우므로 poll 한도 안 | 같은 키의 뒤 이벤트를 세운다 | 복구 중인 PG 를 두드리지 않기 |

- 환불에 포기 상태가 없는 이유 — `CANCELING` 은 "돈이 나갔는지 모른다"는 뜻이라 종결시키면 불확실이 해결된 것처럼 보인다(code-notes `RefundFacade`).
- 세 재시도 모두 **지터(무작위 흔들기)가 없다** [확인됨]. 한 원인으로 여러 건이 동시에 실패하면 같은 시각에 다시 몰린다 — 컨슈머·릴레이는 스레드 하나라 영향이 작고, 환불 스윕은 같은 분에 PG 를 한꺼번에 부를 수 있다.

**재시도의 전제는 멱등성이다.** 재시도는 "한 번 더 실행"이고 앞 시도가 부분적으로 성공했을 수 있다.
그래서 컨슈머는 Inbox, PG 취소는 `pgTxId` 멱등 계약, 세금계산서는 `(sellerId, month)` 멱등 계약 위에서만 재시도한다.

**면접 Q&A**

- **L2** 재시도 전략은 어떻게 되나요? — 위 표.
- **L3** 왜 논블로킹 재시도를 쓰지 않았나요?
  - 논블로킹 재시도는 실패한 메시지를 재시도 토픽으로 빼고 원래 파티션을 계속 진행시킵니다. 그러면 같은 주문의 다음 이벤트가 실패한 이벤트를 앞질러 처리됩니다. 결제 완료보다 환불이 먼저 처리되면 라이선스가 남는 사고가 나기 때문에, 파티션을 잠깐 세우는 블로킹 재시도를 택했습니다.
- **L4** 재시도하면 안 되는 실패는 어떻게 다루나요?
  - 지금은 구분하지 않아서 형식이 깨진 메시지도 네 번 시도하고 7초쯤 파티션을 세운 뒤 DLT 로 갑니다. 성공할 수 없는 예외는 `addNotRetryableExceptions` 로 바로 DLT 에 보내는 게 개선안입니다. 다만 DB 장애처럼 기다리면 낫는 실패까지 넣지 않도록 목록을 좁게 잡아야 합니다.

---

### 5.11 DLQ — 이 프로젝트에서는 DLT

**쉽게 이해하기** — 반송함이다. 계속 실패하는 편지를 거기 모아 두고 원인을 고친 사람이 다시 부친다.

**일반적인 동작** — 처리할 수 없는 메시지(poison pill)가 파티션을 영원히 막지 않게 빼내는 곳이다.
보존 기간, 쌓였다는 알람, 되돌리는 도구가 반드시 짝으로 있어야 한다. 셋 중 하나라도 없으면 운영상 유실과 다르지 않다.

**내 프로젝트에서는** [확인됨]

- 이름은 `<원본토픽>.DLT` 이고 **같은 파티션 번호**로 보낸다(`DeadLetterTopics.java:30-32`). 실패 원인과 원본 위치가 헤더로 남고, 계약 헤더와 `traceparent` 도 따라간다.
- **기본 recoverer(8개 서비스)는 DLT 발행이 실패하면 레코드를 버리지 않는다.** `DeadLetterPublishingRecoverer` 가 발행 결과를 기다려 실패 시 예외를 던지고(`failIfSendResultIsError=true`), 에러 핸들러는 그 레코드를 다시 seek 한다 — 유실 대신 파티션 정지를 고른다(spring-kafka `verifySendResult`, `SeekUtils#doSeeks` 소스).
- **license 의 recoverer 는 다르다.** DLT 발행 실패까지 `sendQuietly` 로 삼켜 오프셋이 커밋되고 레코드는 **로그에만 남는다** → [R6](#r6-license-는-dlt-발행이-실패하면-레코드를-버린다).
- 운영 API — `GET /api/v1/ops/dlt`(조회, 커밋하지 않음), `POST /api/v1/ops/dlt/replay`(원본 토픽으로 재발행, `kafka_dlt-*` 헤더를 떼고, 파티션은 키가 다시 정한다). 게이트웨이가 라우팅하지 않아 내부망에서만 부른다. 알람은 `MessagesDeadLettered`.
- 재투입은 중복 수신이다. 이미 성공한 그룹은 Inbox 가 거른다. 반면 **DLT 로 간 그룹은 Inbox 마킹이 롤백돼 없으므로, 재투입된 레코드를 처음 보는 것처럼 처리한다.**
- 보존 기간을 따로 두지 않아 **7일이 지나면 DLT 에서도 사라진다**(원격 브로커 `retention.ms=604800000`) → [R9](#r9-dlt-보존-기간이-재투입-기한이다).

**재투입이 순서를 뒤집는 장면** — 전체 분석은 R2.

```text
stove.payment.v1 파티션 1 (key=ORD-1)       license 그룹이 보는 것
 offset 40  PaymentCompleted  ─ 저장소 장애로 4번 실패 → DLT 로 보류, 오프셋 41 로 전진
            (사용자: "결제했는데 게임이 없다" → 환불 요청)
 offset 57  PaymentCancelled  ─ revoke(): 회수할 라이선스 없음 → 조용히 종료, Inbox 마킹
            (운영자: 원인 해결 후 DLT 재투입)
 offset 63  PaymentCompleted  ─ issue(): Inbox 에 기록 없음 → 라이선스 지급 → LicenseIssued → download 권한 부여
최종: 결제 CANCELED(환불 완료) + 라이선스 ACTIVE + 다운로드 가능
```

**면접 Q&A**

- **L1** DLQ 는 왜 필요한가요? — 처리할 수 없는 메시지 하나가 파티션 전체를 막지 않게.
- **L3** DLT 로 보내면 파티션은 안 막히는데, 대신 무엇을 잃나요?
  - 그 키의 순서를 잃습니다. 같은 주문의 다음 메시지가 먼저 처리되고, 나중에 재투입한 메시지는 토픽 끝에 붙어 더 늦게 처리됩니다. 파티션 번호를 유지해도 로그 안에서의 위치는 되돌릴 수 없습니다.
- **L4** DLT 발행마저 실패하면요?
  - 스프링 기본 recoverer 는 예외를 던져 같은 레코드를 계속 재시도하므로 유실 대신 파티션이 멈춥니다. 이 프로젝트의 license 는 무한 재전송을 피하려고 그 예외를 삼키는데, 그러면 오프셋이 커밋돼 결제 완료 이벤트가 로그 한 줄로만 남습니다. 결제 이벤트라면 파티션이 멈추더라도 기본 동작이 더 안전하다고 봅니다.

---

### 5.12 Delivery Semantics

**한 줄 요약** — "몇 번 **전달**되는가"와 "몇 번 **반영**되는가"는 다른 질문이다.

**세 가지 보장**

| | 전달 | 만드는 방법 | 대가 |
|---|---|---|---|
| At-most-once | 0 또는 1번 | 처리 전에 커밋 | 유실 |
| At-least-once | 1번 이상 | 처리 후에 커밋, 실패하면 재전송 | 중복 → 소비 쪽 멱등이 필요 |
| Exactly-once | 결과가 정확히 1번 | Kafka 안: 멱등 프로듀서 + 트랜잭션 + `read_committed` / Kafka 밖: 멱등 소비, 또는 결과와 오프셋을 한 저장소에 원자적으로 | 복잡도, 지연, 적용 범위의 한계 |

**Kafka 의 exactly-once 는 어디까지인가** — 과장하면 안 되는 부분이다.

- **멱등 프로듀서**는 한 프로듀서의 재시도 중복을 한 파티션 안에서 없앤다.
- **Kafka 트랜잭션**(`transactional.id`)은 여러 파티션에 쓰는 것과 소비 오프셋 커밋(`sendOffsetsToTransaction`)을 한 원자 단위로 묶고, 컨슈머는 `isolation.level=read_committed` 로 커밋된 것만 읽는다.
- 그래서 **Kafka 에서 읽어 Kafka 에 쓰는** 처리(consume-transform-produce)는 exactly-once 가 된다.
- **Kafka 에서 읽어 DB 에 쓰는** 처리는 아니다. DB 커밋과 Kafka 트랜잭션 커밋은 서로 다른 시스템의 커밋이라 하나로 묶을 수 없고, 어느 쪽이 먼저 커밋되든 그 사이에 틈이 남는다.
- 그 틈을 메우는 것은 애플리케이션이다 — 멱등 소비(Inbox·유니크·upsert), 결과와 오프셋을 같은 DB 트랜잭션에 저장, 외부 부수효과(PG)에는 멱등키 계약.

**내 프로젝트에서는** [확인됨]

- Kafka 트랜잭션을 쓰지 않는다(`transaction-id-prefix` 없음). 컨슈머 `isolation.level` 도 기본 `read_uncommitted` 지만 트랜잭션 발행이 없어 영향이 없다.
- **발행은 at-least-once** — 릴레이가 ack 를 받고 SENT 를 커밋하기 전에 죽으면 같은 이벤트가 다시 나간다.
- **소비는 at-least-once** — 처리 후 커밋. 여기에 Inbox 마킹을 결과와 같은 커밋에 넣어 **컨슈머 그룹마다 "결과는 한 번"** 을 만든다.
- 외부 효과 — PG 취소는 `pgTxId` 기준, 세금계산서는 `(sellerId, month)` 기준 멱등이 **계약**이다. 지금은 스텁이라 실연동 때 검증해야 한다 **[추가 확인 필요]**.

**이 "한 번 반영"이 깨지는 조건** — 알고 말하는 것과 모르고 "exactly-once 입니다"라고 말하는 것은 면접에서 크게 갈린다.

| 조건 | 무슨 일이 | 관련 |
|---|---|---|
| Inbox 행이 사라짐 | 재처리가 다시 반영되거나(가드 삭제), 반대로 원장만 잃으면 재처리가 전부 "이미 처리"로 막힌다 | D-030 |
| 같은 사건이 **다른 eventId** 로 두 번 발행 | Inbox 는 못 거르고 도메인 유니크·상태 가드만 남는다 | [R5](#r5-paymentcancelled-가-다른-eventid-로-두-번-나갈-수-있다) |
| 순서가 뒤집혀 도착 | 각각 한 번씩 반영되지만 **결과가 틀린다** | R2 |
| 컨슈머 그룹 이름 변경 | Inbox 키가 달라져 전부 다시 반영 | 5.6 |

**면접 Q&A**

- **L1** 전달 보장 세 가지를 설명해 주세요. — 위 표.
- **L3** Kafka 의 exactly-once 를 켜면 DB 까지 정확히 한 번 반영되나요?
  - 아닙니다. Kafka 트랜잭션이 원자적으로 묶는 것은 Kafka 안의 쓰기와 오프셋 커밋까지입니다. DB 는 별도 시스템이라 DB 커밋과 Kafka 커밋 사이 틈이 남고, 그 틈은 소비 쪽 멱등성이나 오프셋을 DB 에 같이 저장하는 방식으로 메워야 합니다.
- **L4** 그럼 이 프로젝트는 exactly-once 인가요? — [11장 모범 답변 3](#답변-3--그럼-exactly-once-아닌가요).

---

## 6. Kafka + DB Consistency

이 프로젝트에서 가장 중요한 장이다. 면접관이 이 저장소를 보고 던질 질문의 절반이 여기서 나온다.

### 6.1 Dual Write Problem

**한 줄 요약** — 서로 다른 두 저장소(DB 와 Kafka)에 "둘 다 쓰거나 둘 다 안 쓰기"를 원자적으로 할 수 없는 문제다.

**쉽게 이해하기** — 장부에 적고 고객에게 문자도 보내야 하는데 장부와 문자 시스템이 따로다. 순서를 어떻게 바꿔도 **하나만 성공하는 순간**이 반드시 생긴다.

**일반적인 동작 — 순서를 바꿔도 틈은 남는다**

| 방식 | 틈 | 결과 |
|---|---|---|
| DB 커밋 → Kafka 발행 | 커밋 뒤 발행 전에 죽는다 | DB 에만 있고 이벤트는 없다 — **조용한 유실**. 재시도할 근거도 어디에도 없다 |
| Kafka 발행 → DB 커밋 | 발행 뒤 커밋이 실패한다 | 이벤트만 있고 DB 에는 없다 — **유령 이벤트**. 하류는 되돌릴 수 없다 |
| 트랜잭션 안에서 발행 | 발행 성공 뒤 롤백 | 유령 이벤트. 게다가 락을 네트워크 왕복만큼 쥐고, 브로커 장애가 곧 API 장애다 |
| `AFTER_COMMIT` 에서 발행 | 커밋 뒤 발행 전에 죽는다 | 유령은 사라지지만 유실은 남는다 |
| 2PC (XA) | 이 프로젝트가 쓰는 Kafka 3.9 는 XA 참여자가 될 수 없다 | 불가능. 된다 해도 코디네이터 장애 때 참여자가 블로킹된다 |

**내 프로젝트에서는**

**[확인됨]** 도메인 코드는 Kafka 에 직접 보내지 않는다 — `KafkaTemplate` 을 쓰는 곳은 `OutboxRelay`, DLT 발행·재투입, license 의 에러 핸들러 설정뿐이고, `@TransactionalEventListener`·`afterCommit` 훅도 없다.
발행 의무는 `OutboxRecorder.record()`(`MANDATORY`)가 비즈니스 트랜잭션 안에서 outbox 행으로 적는다.

handover.md 2.1 의 정리가 정확하다 — **Outbox 의 본질은 발행이 아니라 "아직 발행되지 않았다"는 사실을 비즈니스 데이터와 같은 커밋에 남기는 것**이다.
그 사실이 DB 에 있어야 재시도·백오프·DEAD 회수·순서 보장·적체 관측이 성립한다.

**면접 Q&A**

- **L2** Dual Write Problem 이 무엇인가요? — 위 표의 첫 두 줄.
- **L3** `@Transactional` 메서드 안에서 `kafkaTemplate.send()` 하면 안 되나요?
  - 발행은 DB 트랜잭션에 참여하지 않아서, 발행이 성공한 뒤 트랜잭션이 롤백되면 이미 나간 이벤트를 되돌릴 수 없습니다. 반대로 발행을 기다리는 동안 DB 락과 커넥션을 쥐고 있어서 브로커가 느려지면 API 가 같이 느려집니다.
- **L3** 커밋 후에 발행하면(`AFTER_COMMIT`) 되지 않나요?
  - 유령 이벤트는 사라지지만, 커밋과 발행 사이에 프로세스가 죽으면 이벤트가 사라지고 그 사실이 어디에도 남지 않습니다. 결제 완료 이벤트가 사라지면 돈은 받았는데 게임은 지급되지 않습니다.
- **L4** Kafka 트랜잭션 매니저와 DB 트랜잭션 매니저를 체인으로 묶으면요?
  - 두 커밋을 차례로 하는 것이라 원자적이지 않습니다. 첫 커밋 뒤 두 번째가 실패하면 불일치가 남습니다. "대부분 성공하는" 방식이지 보장이 아닙니다.

---

### 6.2 Idempotency

**한 줄 요약** — 같은 요청이나 메시지를 여러 번 처리해도 **한 번 처리한 것과 결과가 같은** 성질이다.

**쉽게 이해하기** — 엘리베이터 버튼. 열 번 눌러도 엘리베이터는 한 번 온다. 반대로 "잔액에 1,000원 더하기"는 두 번 누르면 2,000원이 된다 — 멱등하지 않다.

**같은 Kafka 메시지가 두 번 들어오면 내 시스템은 안전한가** — 코드를 따라 판정한 결과다.

| 컨슈머 | 같은 eventId 가 두 번 | 같은 사건이 다른 eventId 로 | 옛 이벤트가 새 이벤트 뒤에 |
|---|---|---|---|
| payment ← `OrderCreated` | 안전 — Inbox | 안전 — `existsByOrderNo` + `uk_payment_order_no` | 해당 없음 |
| order ← `Payment*` | 안전 — Inbox | 안전 — 이미 목표 상태면 무시 | 실패 → DLT. **시끄럽게** 드러난다 |
| license ← `PaymentCompleted` | 안전 — Inbox | 안전 — `uk_license_order_product`, 소유 상태를 다시 알림 | **위험** — 환불 뒤에 오면 지급한다 (R2) |
| license ← `PaymentCancelled` | 안전 — Inbox | 안전 — 이미 회수면 이벤트 없음 | 앞 이벤트가 없으면 **조용히** 끝난다 |
| settlement ← `Payment*` | 안전 — Inbox | 안전 — `uk_settlement_record` | **위험** — 환불이 먼저면 역산 없이 끝나고 매출만 남는다 (R2) |
| store ← `ProductChanged` | 안전 — 같은 문서를 같은 값으로 | 안전 | **위험** — 옛 상태로 덮는다 |
| download ← `LicenseIssued/Revoked` | 안전 — 문서 ID 고정 | 안전 | 회수는 주문번호 대조로 막는다(D-012). 지급은 대조가 없다(handover.md 열린 질문 ①) |
| catalog · studio · review | 안전 — Inbox | 안전 — upsert·상태 가드 | 늦은 심의 결과는 상태 가드가 무시한다(D-017) |

**결론** — **중복에는 전부 안전하고, 순서 역전에는 셋이 안전하지 않다.** 멱등성은 이 시스템에서 거의 완성돼 있고, 남은 구멍은 순서다.

**방법 비교 — 무엇을 언제 쓰나**

| 방법 | 원리 | 강점 | 약점 | 이 프로젝트 |
|---|---|---|---|---|
| Idempotency Key | 요청마다 고유 키를 붙이고 서버가 처리 여부·결과를 기억 | HTTP 재시도에도 쓴다 | 키를 누가 만들고 얼마나 보관할지 정해야 한다 | 이벤트의 eventId, PG 콜백의 멱등키(주문 단위로 좁혀서). **주문 생성 API 에는 없다** |
| Unique Constraint | 같은 자연 키의 두 번째 INSERT 를 DB 가 거절 | 원자적, 경위와 무관 | 삽입형 연산에만 맞고 위반을 예외로 받는다 | license · settlement · payment · inbox |
| Processed Event Table | 처리한 메시지 ID 를 결과와 같은 트랜잭션에 기록 | 어떤 연산에나 붙는다. 재전달·재투입을 흡수 | 테이블이 커진다. 결과와 같은 DB 여야 한다. 다른 eventId 는 못 거른다 | 7개 서비스의 `processed_event` |
| Upsert | 같은 키면 덮어쓴다 | 테이블이 늘지 않는다 | 순서 역전에 약하다 — 버전 비교가 필요 | store · download |
| 상태 기반 | 이미 목표 상태면 아무것도 안 한다 | 도메인 규칙에 자연스럽다 | 상태를 동시에 바꾸면 경합(락 필요) | order · payment · studio · review |
| Redis 중복 방지 | `SET key NX EX` 로 처리 표시 | DB 부하가 없다, 저장소가 달라도 쓴다 | **결과와 원자적이지 않다** — 표시 후 DB 롤백이면 유실, 반대면 중복. 만료·장애 때 빈틈 | 쓰지 않는다. 금전 경로에는 맞지 않는다 |
| Transactional Outbox | 발행 의무를 결과와 같은 커밋에 | 발행 유실이 없다 | at-least-once → 소비 쪽 멱등이 필요 | 발행하는 6개 서비스 (settlement 는 발행하지 않는다) |
| Inbox Pattern | Processed Event Table 을 **처리 트랜잭션 경계 안에서** 쓰는 패턴 | 위와 같다 | 위와 같다 | `ProcessedEventGuard` |

**Inbox 의 내부** [확인됨 — `ProcessedEventGuard.java:21-33`, 실험 5·6]

```java
if (repository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) return false;  // 스냅샷 읽기
repository.save(ProcessedEvent.of(eventId, consumerGroup, eventType));                 // IDENTITY → 즉시 INSERT
```

| 상황 | 결과 |
|---|---|
| 순차로 두 번 | 둘째의 `exists` 가 true → 건너뜀 |
| 동시에 두 번 | 둘 다 `exists` false → 둘째 INSERT 가 첫째 커밋까지 대기 → 1062 → 롤백 → 재시도에서 `exists` true → 건너뜀. 첫째가 롤백했다면 둘째가 성공 — **어느 쪽이든 정확히 하나만 반영** |
| 셋 이상 동시에 + 첫째 롤백 | 1213 데드락 → 희생자가 재시도에서 건너뜀 |
| eventId 가 비어 있음 | 처리 전에 예외 — 판단할 수 없는 것을 "처음 본 것"으로 치지 않는다 |

- **마킹은 처리와 같은 트랜잭션이어야 한다.** 따로 커밋하면 "마킹 커밋 → 처리 롤백 → 재전달이 와도 이미 처리로 버림" = 영구 유실.
- **Inbox 행을 언제 지우나** — 지우는 코드가 없다(R8). 지운다면 **재전달이 올 수 있는 기간보다 오래** 남겨야 한다: 토픽 보존(7일) + DLT 재투입 기한 + 오프셋 리셋 같은 운영 재처리 창.
- **Inbox 가 복구를 막을 수도 있다** — 원장만 잃고 가드 행이 남으면, 오프셋을 되돌려도 전부 "이미 처리"로 조용히 건너뛴다. 복구하려면 가드 행을 범위를 좁혀 지워야 한다(D-030, [런북](runbooks/license-db-loss.md)).

**왜 두 겹인가 (Inbox + 도메인 유니크)** — 막는 대상이 다르다(decisions.md 8번).
Inbox 는 **같은 메시지**의 재전달을, 도메인 유니크는 **경위가 어떻든 같은 결과**를 막는다.
이 저장소에 실례가 있다 — 환불 요청이 겹치면 `PaymentCancelled` 가 **다른 eventId 로 두 번** 나갈 수 있고(R5), 그때 Inbox 는 통과하지만 license 는 "이미 회수됨", settlement 는 REFUND 유니크, order 는 "이미 취소됨"으로 흡수한다.

**HTTP 멱등성**

| API | 멱등 장치 | 판정 |
|---|---|---|
| PG 콜백 | 주문번호 행 잠금 + 상태 + 멱등키 비교(같은 키 재전송은 무시, 다른 키는 예외) | 안전 |
| 환불 | 상태 기반 — 이미 CANCELED 면 아무것도 안 한다 | 순차는 안전, **동시 두 요청은 R5** |
| 주문 생성 | **없음** [확인됨 — `CreateOrderRequest` 에 키 필드 없음] | 타임아웃 뒤 재시도가 주문을 둘 만든다. 결제는 주문마다 따로 시작해야 하고 안 쓴 주문은 만료되므로 금전 사고로 번지지는 않는다 |

**[권장 개선안]** 주문 생성에 `Idempotency-Key` 헤더와 `(member_id, idempotency_key)` 유니크를 둔다. 같은 키의 재요청은 기존 주문을 돌려준다.

**면접 Q&A**

- **L1** 멱등성이 무엇인가요? — 한 줄 요약.
- **L2** 같은 Kafka 메시지가 두 번 들어오면 안전한가요?
  - 네. 컨슈머가 처리할 때 `(eventId, 컨슈머 그룹)` 을 결과와 같은 트랜잭션에 기록하고, 이미 있으면 건너뜁니다. 동시에 두 번 들어와도 유니크 인덱스가 둘째를 막고 재시도에서 걸러집니다. 돈이 걸린 곳은 도메인 유니크 제약을 한 겹 더 둬서, 같은 사건이 다른 ID 로 두 번 와도 막습니다. 다만 멱등성은 중복을 막을 뿐 순서를 지키지는 않아서, DLT 재투입처럼 순서가 뒤집히는 경우는 따로 대비가 필요합니다.
- **L3** Inbox 의 "있는지 확인 후 삽입"은 동시성에 안전하지 않은데 괜찮나요? — 위 표. 확인은 빠른 길이고 정확성은 유니크 인덱스가 책임진다(실험 5).
- **L3** Redis 로 중복을 막으면 더 빠르지 않나요?
  - 빠르지만 DB 결과와 원자적으로 묶이지 않습니다. Redis 에 처리 표시를 한 뒤 DB 트랜잭션이 롤백되면 재전달을 버리게 되고, 반대 순서면 중복이 생깁니다. 결제·지급처럼 결과가 DB 에 있는 처리는 같은 DB 트랜잭션에 기록하는 편이 맞고, Redis 는 결과를 다시 만들어도 되는 캐시성 처리에 씁니다.
- **L4** Inbox 테이블은 언제 지우나요?
  - 지금은 지우지 않아 계속 커집니다. 지운다면 같은 이벤트가 다시 올 수 있는 가장 긴 기간 — 토픽 보존 7일에 DLT 재투입과 오프셋 리셋 같은 운영 재처리 기간을 더한 것 — 보다 오래 두고 오래된 것부터 작은 배치로 지웁니다.

---

### 6.3 Transactional Outbox

**한 줄 요약** — 이벤트를 Kafka 에 바로 보내지 않고 **비즈니스 데이터와 같은 DB 트랜잭션**으로 outbox 테이블에 적은 뒤, 별도 릴레이가 읽어 발행한다.

```text
Application (예: order)
   │
   ├── DB Transaction [TX-1]
   │      ├── Business Data     INSERT orders, order_item
   │      └── Outbox Event      INSERT outbox_event (status=PENDING, event_id, partition_key, payload, trace_parent)
   │
   └── Outbox Publisher = OutboxRelay (같은 JVM, @Scheduled fixedDelay 200ms)
          │  [TX-R] SELECT … status='PENDING' AND 재시도 시각 도래 ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED
          │         파티션 키별 웨이브로 send() → ack 대기 → SENT   (실패: 백오프, 10회면 DEAD)
          ▼
        Kafka  stove.order.v1   key = orderNo   headers = eventId · eventType · occurredAt · traceparent
```

**동작 순서** [확인됨 — `common/messaging/outbox`]

1. `OutboxRecorder.record()` — `MANDATORY` 라 비즈니스 트랜잭션 밖에서는 예외. 아직 요청 스레드이므로 여기서 traceparent 를 붙잡는다.
2. 릴레이 루프 — 배치가 가득 찼으면(=적체가 더 있으면) 쉬지 않고 다음 배치를 돈다(회차당 최대 10배치).
3. 배치마다 트랜잭션 하나 — 잠금 조회 → 웨이브 발행 → 커밋. 배치를 한 트랜잭션에 몰지 않는 이유는 락 보유 시간이다.
4. 웨이브 — 키별 체인의 n 번째 이벤트들을 동시에 `send()` 하고 한 번에 기다린 뒤, **성공한 키만** 다음 웨이브로 간다(D-013).
5. 실패 — `retry_count++`, `next_attempt_at = 지금 + 1초 × 2^(n-1)`(상한 5분), 10회면 `DEAD`. 같은 키의 뒤 이벤트는 앞 이벤트의 재시도 시각까지 보류하되 재시도 횟수는 올리지 않는다(D-014).
6. `DEAD` → `OutboxEventsAbandoned` 알람 → `POST /api/v1/ops/outbox/dead/{eventId}/requeue` 또는 `requeue-all`.

**면접관이 묻는 것들**

**왜 DB 트랜잭션과 Kafka 발행을 하나로 묶기 어려운가** — 두 시스템이 커밋을 따로 하고, 둘을 묶는 표준(XA)에 이 프로젝트의 Kafka 3.9 가 참여할 수 없기 때문이다(6.1).

**Outbox 가 해결하는 것** — "DB 와 Kafka 에 원자적으로 쓰기"라는 풀 수 없는 문제를 **"DB 한 곳에 원자적으로 쓰기"** 로 바꾼다.
발행이 실패해도 outbox 에 남아 재시도할 근거가 있고, 브로커가 죽어도 쓰기 API 는 계속 받을 수 있다(단, 지금 구현에는 R1 이 있다).

**Outbox 에도 중복 발행이 생기나** — 생긴다. 이 설계는 at-least-once 다.

| 경우 | 왜 |
|---|---|
| ack 를 받고 SENT 커밋 전에 죽음 | 행이 PENDING 으로 남아 재기동한 릴레이가 다시 보낸다 |
| SENT 커밋이 실패 (DB 순단) | 같다 |
| ack 타임아웃이지만 브로커는 실제로 기록 | 실패로 기록하고 재시도한다 — 새 `send()` 라 멱등 프로듀서도 못 거른다 |
| 운영자가 회수한 DEAD 가 실제로는 전달됐던 이벤트 | 한 번 더 나간다 |

**컨슈머는 중복을 어떻게 처리하나** — eventId 헤더로 Inbox 가 거른다(6.2). eventId 는 이벤트를 만들 때 한 번 정해져(`UUID`) outbox 에 저장되므로 몇 번 재발행해도 같다.

**Outbox 테이블이 계속 커지면** — 지금은 SENT 를 지우지 않는다(R8). 커지면 디스크·백업 시간·인덱스 크기가 늘고, **실행 계획이 바뀐다** — 실험 2 에서 SENT 와 PENDING 의 비율에 따라 릴레이 조회가 `idx_outbox_pending` range 에서 PRIMARY 인덱스 스캔(EXPLAIN `type: index`)으로 넘어갔다.

| 정리 방법 | 대가 |
|---|---|
| SENT 중 N 일 지난 것을 작은 배치로 삭제 (`… WHERE status='SENT' AND sent_at < ? LIMIT 1000` 반복) | 가장 단순. 삭제도 락·undo 를 만들므로 배치를 작게, 한산할 때 |
| 날짜 파티셔닝 후 `DROP PARTITION` | 삭제 비용이 거의 없다. 대신 MySQL 은 모든 유니크 키에 파티션 컬럼이 들어가야 해서 `uk_outbox_event_id` 를 다시 설계해야 한다 |
| 발행 즉시 삭제 | 테이블이 작게 유지된다. 발행 이력·traceparent 로 추적하던 근거를 잃는다 |

**CDC 와 Outbox 의 차이** — 둘은 경쟁이 아니라 **발행기**의 선택이다. outbox 테이블은 그대로 두고 릴레이 대신 binlog 를 읽는 커넥터(Debezium Outbox Event Router)로 발행할 수 있다.

| | 폴링 릴레이 (지금) | CDC |
|---|---|---|
| 순서 | `ORDER BY id` — id 순서와 커밋 순서가 다를 수 있다 | binlog = 커밋 순서 |
| 지연 | 폴링 주기가 바닥 (200ms) | 거의 실시간 |
| DB 부하·락 | 주기적인 잠금 조회 + UPDATE — **R1 의 갭 락이 여기서 나온다** | 잠금 없음 |
| 다중화 | 1대 제약 (R13) | 커넥터가 binlog 위치를 관리 |
| 운영 | 앱 안에 전부 있다 | Kafka Connect 와 커넥터를 운영해야 한다. binlog 는 ROW 형식이어야 한다(이미 ROW [확인됨]) |

이 저장소가 폴링을 유지하는 이유는 event-ordering.md 6절에 있다 — 순서·처리량·관측을 직접 다루는 것이 프로젝트의 목적이었다.

**측정된 대가**

- 쓰기 경로 p95 +19ms — 60 RPS 에서 릴레이 ON/OFF 비교, 폴링 1000ms 시절 값이다(performance.md 9장).
- 그중 갭 락 경합의 몫 — 지금 설정(200ms)에서 격리 수준만 바꾼 A/B 로 따로 재면 p95 약 4.7ms 다(OCI, R1). 두 숫자는 설정이 달라 빼기로 잇지 않는다.
- 한산할 때 종단 지연의 바닥은 폴링 주기다 — p95 1.30s → 0.56s(1000 → 200ms, perf-tuning.md 3절).
- 릴레이 948.7 events/s(perf-tuning.md 2절) 대 컨슈머 141.1 events/s(4절) — **지금 병목은 소비 쪽**이다.

**면접 Q&A**

- **L2** Outbox 를 왜 썼나요? — [11장 모범 답변 4](#답변-4--outbox-를-왜-썼고-무엇이-어려웠나요).
- **L3** 릴레이는 여러 대 띄워도 되나요?
  - `SKIP LOCKED` 덕분에 같은 행을 두 대가 집지는 않지만, 같은 주문의 이벤트가 두 릴레이로 갈라지면 한쪽 실패 사이에 다른 쪽이 뒤 이벤트를 먼저 보내 순서가 깨집니다. 그래서 서비스당 한 대가 전제이고, 늘리려면 파티션 키 해시로 워커를 고정 배정해야 합니다. 그런데 이 제약이 코드로 강제되지 않아서, 서비스를 두 대로 늘리는 순간 릴레이도 두 대가 됩니다(R13).
- **L4** 릴레이가 발행하고 SENT 로 바꾸기 전에 죽으면요? — 다시 보낸다. 컨슈머 Inbox 가 흡수한다. at-least-once 발행이다.
- **L5** CDC 로 바꾸면 무엇이 좋아지고 무엇을 떠안나요? — 위 표. 이 저장소 기준으로는 순서(커밋 순서), 1대 제약, 갭 락 경합이 한꺼번에 사라진다.

---

### 6.4 Failure Scenarios

각 상황을 **유실 · 중복 · 재처리 · 멱등 · 일관성 · 보상** 여섯 관점으로 판정한다.

#### Case 1 — DB Commit 성공, Kafka Offset Commit 실패

```text
license 컨슈머
  [TX] processed_event(E1) + license + outbox(LicenseIssued) → COMMIT        ✔
  commitSync(N+1)                                                              ✘  리밸런싱 중 CommitFailedException, 브로커 순단 등
  → 파티션이 넘어갔으면 새 담당이 마지막 커밋 N 부터 읽고,
    그대로면 에러 핸들러가 N 으로 되감아 같은 컨슈머가 다시 읽는다
  → issue(E1): Inbox 에 E1 이 있다 → 건너뜀 → commitSync ✔
```

담당이 그대로인데도 N 이 다시 오는 이유 — spring-kafka 는 RECORD 모드에서 리스너 호출 직후 같은 흐름 안에서 커밋하므로(`KafkaMessageListenerContainer#invokeOnMessage` → `ackCurrent`), 커밋 예외도 리스너 예외처럼 에러 핸들러로 가서 seek 된다.
리밸런싱 진행 중에 난 `RebalanceInProgressException` 만은 에러 핸들러로 가지 않는다. 커밋할 값을 들고 있다가 리밸런싱이 끝난 뒤 아직 맡고 있는 파티션만 다시 커밋하고, 넘어간 파티션은 새 담당이 N 부터 읽는다 [확인됨 — spring-kafka 3.3.10 소스].

| 유실 | 중복 | 재처리 | 멱등 | 일관성 | 보상 |
|---|---|---|---|---|---|
| 없음 | 전달 1회 더 | Inbox 조회 한 번 | Inbox 가 흡수 | 유지 | 불필요 |

재처리가 **실패**해 재시도까지 소진하면 license 의 recoverer 로 간다. 저장소 장애면 관문 1(원인)이 보류(DLT)하고, 그 밖의 실패면 관문 2(`isIssued`)가 "이미 지급됨"을 보고 환불하지 않고 DLT 로 보낸다. D-028 이 정확히 이 경우였다 — 관문이 없던 시절 **물건을 준 주문을 환불할 뻔했다.**

#### Case 2 — DB Commit 실패, Kafka Offset Commit 성공

**설계대로라면 일어나지 않는다.** 오프셋은 리스너가 정상 리턴해야 커밋되는데, DB 커밋이 실패하면 예외가 리스너 밖으로 나가 커밋되지 않고 재시도된다.

일어나는 경우는 전부 **설계를 벗어난 경우**다.

| 경우 | 결과 | 이 저장소에서 |
|---|---|---|
| 리스너가 예외를 삼킨다 | DB 롤백 + 오프셋 커밋 = **유실** | D-002 가 그랬다(당시엔 보상까지 발동) |
| 재시도 소진 뒤 recoverer 가 정상 리턴 | 오프셋 커밋. 기본 recoverer 는 DLT 발행이 성공한 뒤라 "유실"이 아니라 "연기" | 8개 서비스 |
| recoverer 가 DLT 발행 실패까지 삼킨다 | **유실** — 로그에만 남는다 | license 의 `sendQuietly` (R6) |
| 처리를 다른 스레드로 넘기고 커밋 | 처리 실패와 무관하게 커밋 = 유실 | ArchUnit 이 금지 |
| COMMIT 요청 뒤 연결이 끊겨 결과를 모른다 | 예외 → 재시도. 실제로 커밋됐으면 Inbox 가 건너뛰고, 아니면 처리한다 | 안전 |

| 유실 | 중복 | 재처리 | 멱등 | 일관성 | 보상 |
|---|---|---|---|---|---|
| 설계대로면 없음. 예외를 삼키면 발생 | 없음 | 에러 핸들러 재시도 | 재시도가 안전하려면 필요 | 예외를 삼키면 깨짐 | 유실은 보상으로 못 찾는다 — 대사로만 드러난다 |

#### Case 3 — DB Commit 성공, Offset Commit 전에 Consumer Crash

Case 1 과 기계적으로 같다. 차이는 누가 이어받느냐다.
같은 인스턴스가 재기동하거나, heartbeat 가 끊긴 뒤 `session.timeout.ms`(45초)가 지나 리밸런싱으로 다른 인스턴스가 받는다.

```text
t0  issue(E1) COMMIT (license + processed_event + outbox)
t1  💥 kill -9 (commitSync 전)
t2  재기동 또는 리밸런싱 → offset N 부터 → issue(E1) → Inbox hit → 건너뜀 → commit
    릴레이도 같은 JVM 이라 함께 죽었다. t0 에 커밋된 outbox(LicenseIssued) 는 죽기 전에 나갔거나 재기동 뒤에 나간다
    — 재처리는 Inbox 에서 끝나 outbox 행을 새로 만들지 않으니, 하류가 받는 LicenseIssued 는 이 한 행에서 나온 것뿐이다
```

| 유실 | 중복 | 재처리 | 멱등 | 일관성 | 보상 |
|---|---|---|---|---|---|
| 없음 | 전달 1회 더 | 1회 | Inbox | 유지 | 불필요 |

이 보장을 지키는 테스트가 R-03 `ConsumerRestartCatchUpTest` 다 — "따라잡기가 이중 지급이 되지 않는다".
말로 할 답은 [11장 모범 답변 2](#답변-2--db-커밋-후-오프셋-커밋-전에-죽으면).

#### Case 4 — 외부 API 성공, DB Commit 실패

이 프로젝트에는 되돌리기 어려운 외부 호출이 넷 있고, 자리가 달라 결과도 다르다.

| 외부 호출 | 자리 | 호출 성공 뒤 커밋이 실패하면 | 복구 |
|---|---|---|---|
| PG 환불 | 트랜잭션 **밖**, 앞에 의도(CANCELING) 커밋 | 돈은 나갔고 결제는 CANCELING | 스윕이 재개 → PG 취소가 멱등이라 다시 걸어도 환불은 한 번 → CANCELED |
| 세금계산서 발행 | 트랜잭션 **밖**, 앞에 확정본 커밋 | 계산서는 나갔고 번호가 없다 | 재실행이 "마감됐는데 계산서 없음"으로 다시 집는다 → `(sellerId, month)` 멱등 발행 |
| PG 사전등록 | 트랜잭션 **안** (`PaymentService.prepare`) | PG 에 거래가 생겼는데 결제는 READY 그대로 | 사용자가 다시 누르면 새 거래. 먼저 생긴 거래는 PG 쪽 잔여로 남아 대사에서 정리 (R4) |
| 게임위 접수 | 트랜잭션 **안** (`ReviewService.receive`, 컨슈머) | 접수는 됐는데 심의 요청이 롤백 | 컨슈머 재시도가 **다시 접수**한다 — 게임위 API 가 멱등이 아니면 이중 접수 (R4) |

| 유실 | 중복 | 재처리 | 멱등 | 일관성 | 보상 |
|---|---|---|---|---|---|
| 로컬 기록 유실 가능 | 외부 호출 중복 가능 | 스윕·재실행·컨슈머 재시도 | **외부 호출이 멱등이어야** 재처리가 안전 | 외부와 장부가 잠시 어긋남 → 대사 | 필요하면 외부 효과를 되돌리는 호출(환불)이 보상 |

원칙은 다섯 줄이다 — **트랜잭션 밖에서 부른다 · 부르기 전에 의도를 커밋한다 · 외부 호출에 멱등 계약을 요구한다 · 멈춘 건을 재개하는 장치를 둔다 · 마지막엔 대사한다.**
결제창이 만료된 뒤 도착한 승인을 "거절하지 않고 받아 적은 뒤 곧바로 환불"하는 경로(`PaymentCallbackFacade`)가 외부 효과를 되돌리는 보상의 실례다.

#### 이 프로젝트에만 있는 장애 케이스

| # | 상황 | 무슨 일이 | 판정 | 라벨 |
|---|---|---|---|---|
| 5 | 릴레이가 ack 받은 뒤 SENT 커밋 전에 죽음 | 같은 이벤트 재발행 → 컨슈머 Inbox 흡수 | 중복, 유실 없음 | [확인됨] 코드 |
| 6 | 지급 재시도 소진 → DLT 보류 → 사용자 환불 → DLT 재투입 | **환불된 주문에 라이선스 지급**, settlement 는 매출만 남음 | 일관성 깨짐 | [코드상 추정] R2 |
| 7 | license DB 60초 장애 | 수정 전 정상 결제 8건 환불 → 수정 후 환불 0, 보류 7, 재투입 1초 내 전부 지급, 대사 115:115 | 보상 오발동을 보류로 바꿈 | [확인됨] D-027 |
| 8 | 브로커 장애 중 결제 승인 콜백 | 의도: outbox 에 적고 200. 실제: 릴레이가 ack 를 기다리며 쥔 갭 락에 승인 INSERT 가 막혀 50초 뒤 1205 → 500 → PG 가 콜백 재전송 | 가용성 저하 | [코드상 추정] R1 — 락 모양은 실험 1·3, 평시 락 대기는 OCI 부하 A/B 로 확인. 브로커 장애 재현은 아직 |
| 9 | order 컨슈머가 오래 멈춤 (막 결제한 주문은 1시간 가까이, 창 끝에 결제한 주문은 15분) | 결제·지급은 끝났는데 주문은 CREATED → 만료 스윕이 EXPIRED → 재기동 후 `PaymentCompleted` 가 CONFLICT 로 DLT | 서비스 간 상태 불일치 | [코드상 추정] R3 |
| 10 | PG 환불 성공 → 확정 커밋 실패 | CANCELING → 2분 뒤 스윕이 재개 → CANCELED + `PaymentCancelled` | 수렴 | [확인됨] `StrandedRefundResumeTest` |

---

## 7. 프로젝트의 주요 Trade-off

"왜 이 기술을 썼나요?"에는 **이 요구사항에서 무엇을 얻고 무엇을 치렀는가**로 답한다. 기술 자체의 장단점 나열은 답이 아니다.

### 7.1 왜 이 선택인가

| 질문 | 선택 | 대안 | 이 프로젝트에서 합리적인 이유 | 치른 대가 |
|---|---|---|---|---|
| 왜 Kafka 인가 | Kafka | RabbitMQ, 동기 HTTP | 결제 한 건을 셋이 각자 읽는 팬아웃, 오프셋·DLT 로 재처리, 주문 단위 순서 | 결과적 일관성, 중복, 브로커 운영 |
| 왜 DB 트랜잭션인가 | 서비스 안은 로컬 트랜잭션 | 서비스 간 분산 트랜잭션 | "비즈니스 변경 + outbox" 를 원자적으로 묶는 곳이 서비스 안에만 있으면 된다 | 서비스 간 일관성은 Saga·대사로 따로 설계 |
| 왜 비동기인가 | 상태 변경 전파는 이벤트 | 동기 호출 체인 | license 가 죽어도 결제·다운로드가 계속된다. 동기가 필요한 곳은 주문 금액 확정 하나 | 사용자에게 보이는 지연 창(3.5) |
| 왜 이 파티션 구조인가 | 토픽 = 애그리거트, 키 = 주문번호·상품코드, 파티션 3 | 이벤트 타입별 토픽, 키 없음 | 순서가 필요한 최소 단위가 애그리거트 | 파티션 증설 시 순서 위험, hot key |
| 왜 이 컨슈머 그룹 구조인가 | 그룹 = 서비스, Inbox 키에 그룹 포함 | 공유 그룹 | 서비스마다 처리 여부와 속도가 독립 | 그룹 이름이 곧 멱등 키라 바꾸기 어렵다 |
| 왜 이 격리 수준인가 | MySQL 기본(REPEATABLE READ) | READ COMMITTED | **정한 적이 없다** — 정확성은 행 잠금·유니크 제약이 지킨다 | Lost Update(R3), 갭 락 경합(R1) |
| 왜 이 재시도 전략인가 | 블로킹 재시도 1·2·4초 → DLT | 논블로킹 재시도 토픽, 무한 재시도 | 순서를 지키면서 짧은 장애를 넘기고, 영구 실패는 파티션을 막지 않는다 | 재시도 동안 파티션 정지, DLT 재투입 시 순서 역전(R2) |
| 왜 이 DB 구조인가 | 서비스별 스키마, 금전 도메인은 MySQL, 매니페스트는 Mongo, 검색은 ES | 공유 스키마 | 서비스 경계를 DB 에서도 강제, 저장소를 성격에 맞춤 | 서비스 간 조인·외래키 없음, 로컬은 인스턴스 공유(2.4) |
| 왜 이 캐시 전략인가 | catalog 단건 `@Cacheable` TTL 5분 + 커밋 후 무효화, store 진열 TTL 1분 + 색인마다 전체 무효화 | 쓰기 시 캐시 갱신, 캐시 없음 | 읽기가 몰리는 곳만, 무효화 지점을 쓰기 서비스 한 곳으로 | 아래 주의 |

**캐시에서 면접관이 파고들 곳** — catalog 는 `transactionAware()` 라 **커밋 뒤에** 무효화한다(롤백된 변경으로 캐시가 비는 것을 막는다).
그래도 cache-aside 의 고전적인 경합은 남는다 — 조회가 캐시 미스로 **옛 값**을 DB 에서 읽은 직후 쓰기가 커밋·무효화하고, 조회가 그 옛 값을 캐시에 넣으면 TTL(5분)까지 옛 값이 보인다 **[일반 동작 기준 추정]**.
store 는 여기에 Elasticsearch 의 준실시간 색인(새로고침 주기 전에는 검색에 안 보임)이 겹친다. 인수 테스트가 색인 반영을 폴링으로 기다리는 이유다.

### 7.2 대안과 비교

| 비교 | 이쪽을 고르는 경우 | 저쪽을 고르는 경우 | 이 프로젝트 |
|---|---|---|---|
| Kafka vs RabbitMQ | 여러 소비자가 같은 사건을 각자 읽고, 다시 읽어야 하고, 키 단위 순서가 필요 | 작업 큐, 메시지 단위 라우팅·우선순위·지연 전송, 소비 후 삭제가 자연스러움 | Kafka — 5.1 |
| 동기 vs 비동기 | 결과가 **지금** 필요(가격 확정, 재고 차감 확인) | 전파·후속 처리, 호출 대상 장애와 분리 | 금액 확정만 동기 |
| DB 트랜잭션 vs Saga | 한 DB 안에서 끝나는 일 | 여러 서비스·외부 시스템에 걸친 일 — 원자성 대신 보상 | 서비스 안은 트랜잭션, 결제→지급 실패는 Saga 보상 |
| Outbox vs 직접 Publish | 발행 유실이 곧 금전 사고, 브로커 장애를 쓰기 경로에서 떼고 싶음 | 유실돼도 되는 알림성 이벤트, 지연이 치명적 | Outbox — 6.3 |

### 7.3 일부러 고른 트레이드오프

| 결정 | 얻은 것 | 버린 것 | 근거 |
|---|---|---|---|
| 저장소 장애면 **보상하지 않고 DLT 보류** | 정상 결제의 오환불 0 (60초 장애: 8건 → 0건) | 자동 보상이 거의 돌지 않는다 — 사람이 재투입해야 한다 | D-027. 환불은 되돌리기 어렵고 보류는 되돌리기 쉽다 |
| **키 단위 웨이브** 발행 | 순서 + 다른 키는 동시에 | 한 키가 영구 실패하면 그 키가 DEAD 까지 멈춘다 | D-013. 전역 정지와 무순서의 중간 |
| 블로킹 재시도 | 컨슈머 층의 순서 | 재시도 동안 파티션 정지 | kafka-consumer-retry.md 6절 |
| DLT 로 넘기고 진행 | 계약 위반 한 건이 뒤를 막지 않는다 | 그 키의 순서(R2) | decisions.md 19번 |
| 폴링 200ms | 앱 안에서 전부 해결, 관측 가능 | 종단 지연의 바닥, 잠금 조회 비용(R1) | 1000 → 200ms 에 MySQL CPU 7.3% → 8.8%, 두 회차 평균 (perf-tuning.md 3절) |
| 권한 **사본** (download) | license 장애와 다운로드 분리 | 사본이 늦거나 순서가 틀릴 수 있다 | README 3절, D-012 |
| catalog(쓰기) / store(읽기) 분리 | 검색 트래픽을 원본에서 떼어냄 | 색인 지연, 순서 역전 시 옛 상태 | code-notes `StoreService` |
| 분산 락을 **DB 테이블**(ShedLock)로 | 새 인프라 없음, DB 시계 사용 | DB 에 의존, 락 보유 시간 추정 필요 | `V7__shedlock.sql` |
| 환불에 **포기 상태 없음** | 불확실한 돈을 방치하지 않는다 | 영원히 재시도할 수 있다 → 예산 알람으로 사람 호출 | code-notes `RefundFacade` |

---

## 8. 잠재적인 문제점

이미 문서화된 결함(defects.md 36건)은 반복하지 않는다. 여기 적은 것은 **이번 분석에서 새로 찾았거나, 문서에 있지만 위험의 크기가 드러나 있지 않은 것**이다.
각 항목에 근거와 라벨을 붙였고, 재현하지 않은 것은 그렇다고 적었다.

### 8.1 위험 대장

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

#### R1. 릴레이가 갭 락을 쥔 채 Kafka ack 를 기다린다

**[현재 구현의 잠재적 문제]**

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

**평시 영향** **[확인됨 — OCI 부하 A/B, 2026-09-14]**

performance.md 9-3 은 릴레이 ON/OFF 비교에서 쓰기 p95 가 22~24ms → 43ms 가 되는 것을 쟀고, 9-4 에서 원인을 "같은 커넥션 풀"로 추정했지만 13-5 의 측정(60 RPS 에서 풀 `pending` 0)이 그 가설을 기각해 원인이 비어 있었다.
그래서 **갭 락만 없애는 조건**을 걸어 쟀다. order 서비스 커넥션의 기본 격리 수준만 READ COMMITTED 로 바꾸고(Hikari `transactionIsolation`, 코드 변경 없음) 나머지는 그대로 둔 채,
performance.md 9-3 과 같은 60 RPS 주문 생성 부하를 90초씩 **RR → RC → RC → RR** 순서로 걸었다(`scripts/perf/run-isolation-ab.sh`).

| 회차 | order 격리 수준 (MySQL 에 커넥션별로 확인) | p95 | p90 | 평균 | 측정 90초간 InnoDB 행 락 대기 |
|---|---|---:|---:|---:|---|
| rr-1 | REPEATABLE READ ×20 | 26.42ms | 21.41 | 15.67 | **404회**, 누적 3,806ms (건당 9.4ms) |
| rc-1 | READ COMMITTED ×20 | 21.26ms | 18.53 | 14.75 | **0회** |
| rc-2 | READ COMMITTED ×20 | 21.82ms | 18.78 | 14.99 | **0회** |
| rr-2 | REPEATABLE READ ×20 | 26.04ms | 21.01 | 15.48 | **400회**, 누적 3,571ms (건당 8.9ms) |

- 네 회차 모두 5,400건 · 누락 0 · 실패 0 이고, 조건 안 p95 편차(0.38 · 0.56ms)가 조건 사이 차이(약 4.7ms)보다 작다 — 비교가 유효하다(measuring.md 규칙 9). 대조군으로 돌아온 rr-2 가 rr-1 을 재현했다.
- **바꾼 것은 격리 수준 하나인데 락 대기가 400회에서 0회가 됐다.** READ COMMITTED 는 갭 락을 끄고, order 에서 범위를 잠그는 문장은 릴레이 조회뿐이므로, 그 400회는 릴레이의 넥스트키 락을 기다린 outbox INSERT 다 — 실험 1 의 메커니즘이 부하에서 그대로 나타났다.
- 락 대기는 주문 수의 약 7.4%(5,400건에 400회)였고 한 번에 평균 9ms 였다. 그만큼 **p95 가 약 4.7ms(×1.22) 올랐고** 평균은 1ms 안쪽으로만 움직였다 — 일부만 기다리는 경합의 전형적인 모양이다.
- performance.md 9-3 의 +19ms 와 크기를 그대로 비교하지는 않는다. 9-3 은 `poll-interval-ms` 가 1000 이던 시절(200 적용은 그 뒤 `7574ea2`) 측정이라 배치가 다섯 배 크고 락을 쥐는 시간도 길었다. 그 설정에서 몫이 얼마였는지는 **[추가 확인 필요]**.
- 측정 동안 CI 러너를 멈췄다(규칙 11). 공용 스택의 데이터를 지우지 않으려고 9-3 과 달리 테이블을 비우지 않았고, 대신 A-B-B-A 순서와 회차별 초기 행 수로 누적 효과를 대조했다.

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

**면접에서** — "Outbox 로 브로커 장애를 쓰기 경로에서 떼어냈다고 생각했는데, 릴레이의 잠금 조회가 REPEATABLE READ 에서 갭 락을 잡고 그 트랜잭션 안에서 Kafka 응답을 기다려 격리가 새고 있었습니다. MySQL 로 재현해 INSERT 가 릴레이 커밋까지 막히는 걸 확인했고, 부하 중에 격리 수준만 바꿔 A/B 로 재 보니 REPEATABLE READ 에서는 90초 동안 락 대기가 400번, READ COMMITTED 에서는 0번이었고 쓰기 p95 가 26ms 에서 21ms 로 내려갔습니다. 평소엔 몇 ms 의 문제지만 브로커가 응답하지 않으면 그 대기가 50초짜리 락 타임아웃이 됩니다. 릴레이 트랜잭션만 READ COMMITTED 로 두는 게 첫 수이고, 완전히 떼려면 Kafka 대기를 트랜잭션 밖으로 뺍니다."

---

#### R2. DLT 재투입과 DEAD 회수가 같은 키의 순서를 뒤집는다

**[현재 구현의 잠재적 문제]**

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

**면접에서** — "DLT 는 파티션을 막지 않는 대신 그 키의 순서를 포기합니다. 재투입된 메시지는 토픽 끝에 붙어서, 결제 완료가 보류된 사이에 환불이 먼저 처리되면 나중에 들어간 결제 완료가 환불된 주문에 게임을 지급할 수 있습니다. 지금은 재투입 전에 결제 상태를 확인하는 절차로 막고, 구조적으로는 회수 예약을 남기거나 한 키가 DLT 로 가면 그 키의 뒤 메시지도 함께 보류하는 방식으로 풀 수 있습니다."

---

#### R3. 주문 만료 스윕과 결제 확정의 Lost Update

**[현재 구현의 잠재적 문제]**

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

**면접에서** — "시간으로 닫는 스케줄러와 이벤트로 닫는 컨슈머가 같은 주문을 바꾸는데 둘 다 잠그지 않았습니다. MySQL REPEATABLE READ 는 스냅샷으로 읽고 최신 행에 쓰기 때문에 이 경합에서 결제 확정이 조용히 덮입니다. 실험으로 확인했고, 조건부 UPDATE 나 버전 컬럼으로 막을 수 있습니다. 그보다 먼저 정해야 하는 건 만료 뒤 도착한 결제를 되살릴지 되돌릴지라는 정책입니다."

---

#### R4. 외부 호출이 트랜잭션 안에 남은 두 자리

**[현재 구현의 잠재적 문제]** — 지금은 둘 다 스텁이라 영향이 없다. **실제 PG·게임위를 붙이는 날 드러난다.**

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

#### R5. PaymentCancelled 가 다른 eventId 로 두 번 나갈 수 있다

**[현재 구현의 잠재적 문제]** — 영향은 작지만 **"Inbox 가 있는데 도메인 유니크가 왜 필요한가"** 의 실례라 알아 둘 가치가 크다.

**무엇이** — `PaymentService.completeCancel()`(`:159-168`)은 `payment.completeCancel()` 이 이미 CANCELED 라 아무것도 하지 않고 돌아와도(`Payment.java:207-210`) **그와 상관없이** `PaymentCancelledEvent` 를 적재한다.

**어떻게 두 번 오나** **[코드상 추정]**

- 환불 버튼을 두 번 누른 요청이 겹치면, 둘째의 `beginCancel` 도 CANCELING 을 보고 통과한다 → PG 취소 두 번(멱등) → `completeCancel` 두 번 → 둘째도 이벤트를 적재한다.
- 결제창 만료 승인 경로(`handleApproval:102-108`)는 `scheduleFirstCancelRetry` 를 부르지 않아 `next_cancel_attempt_at` 이 NULL 로 남는다. 스윕 조회는 NULL 을 즉시 대상으로 보므로(`PaymentRepository.java:33`), 파사드가 PG 를 부르는 사이 스윕이 돌면 같은 결과가 난다. code-notes 의 "방금 착수한 건은 집지 않는다"는 의도와 어긋난다.

**하류에서** — eventId 가 달라 Inbox 를 통과한다. order 는 "이미 취소", license 는 "회수할 것 없음 → 이벤트도 없음", settlement 는 "REFUND 이미 있음"으로 흡수한다. 금전 영향은 없고, PG 취소 중복 호출과 "몇 번 시도했나" 관측이 흐려지는 것이 남는다.

**[권장 개선안]** `Payment.completeCancel()` 이 실제로 전이했는지 boolean 으로 돌려주고, 전이했을 때만 적재한다. 결제창 만료 경로에도 첫 유예를 예약한다.

---

#### R6. license 는 DLT 발행이 실패하면 레코드를 버린다

**[현재 구현의 잠재적 문제]**

**무엇이** [확인됨] — license 의 recoverer 는 DLT 발행 실패까지 `sendQuietly` 로 삼킨다(`KafkaErrorHandlerConfig.java:95-103`). 에러 핸들러는 복구가 끝난 것으로 보고 오프셋을 커밋한다.
나머지 8개 서비스의 기본 recoverer 는 반대로, DLT 발행 결과를 기다려 실패하면 예외를 던지고 그 레코드를 다시 seek 한다(spring-kafka 3.3.10 `DeadLetterPublishingRecoverer#verifySendResult`, `SeekUtils#doSeeks`).

**영향** — 버려지는 것이 `PaymentCompleted` 면 지급이 누락되고, 그 사실은 로그와 대사로만 드러난다.
DLT 발행이 실패하는 가장 흔한 원인은 브로커 장애인데, 브로커가 한 대면 그때는 컨슈머도 새로 읽지 못한다.
남는 경우는 브로커가 여러 대일 때 DLT 파티션만 리더가 없는 순간, 또는 레코드가 DLT 쪽에서만 거절되는 경우(예: 예외 헤더가 붙어 `max.request.size` 를 넘음)다 **[코드상 추정]**.

**트레이드오프** — 주석은 "밖으로 나가면 무한 재전송"을 피하려 했다고 적는다. 하지만 그 "무한 재전송(파티션 정지)"은 다른 8개 서비스가 이미 받아들인 동작이고, 알람이 울린다.
**결제 이벤트는 잃는 것보다 멈추는 편이 낫다.** 보상 판단 로직에서 난 예외만 삼키고 DLT 발행 실패는 다시 던지는 것이 개선안이다.

---

#### R7. 브로커 1대, 복제 계수 1, 토픽 자동 생성

**[확인됨]** `docker-compose.yml` — `KAFKA_NODE_ID: 1` 한 대, `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1`, `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"`, `KAFKA_NUM_PARTITIONS: 3`.
원격 스택 브로커에서도 도메인 토픽 6개와 DLT 2개가 전부 파티션 3 · 복제 계수 1 이고, 설정을 열어 본 `stove.payment.v1` 과 그 DLT 는 `min.insync.replicas=1` 이다.
`acks=all` 이 사실상 `acks=1` 이고, 브로커 디스크를 잃으면 소비되지 않은 이벤트와 컨슈머 오프셋을 함께 잃는다. 로컬·CI 구성이다. **운영 구성은 [추가 확인 필요].**

**[권장 개선안]** 브로커 3대 이상, 복제 계수 3, `min.insync.replicas=2`, 자동 생성을 끄고 토픽을 명시적으로 생성(파티션 수 고정, DLT 보존 기간 연장).

---

#### R8. outbox 와 inbox 테이블이 줄지 않는다

**[확인됨]** `outbox_event` 의 SENT 행과 `processed_event` 행을 지우는 코드가 없다(삭제 쿼리·스케줄러 전수 검색).

- 디스크·백업 시간·인덱스 크기가 계속 는다.
- **릴레이 조회의 실행 계획이 분포에 따라 바뀐다**(실험 2 — range+filesort 에서 PRIMARY 인덱스 스캔으로). 계획이 바뀌면 잠그는 범위와 INSERT 대기 여부가 함께 바뀐다.
- inbox 를 지울 때는 **재전달이 올 수 있는 기간보다 오래** 남겨야 한다(6.2). 너무 일찍 지우면 오프셋 리셋·DLT 재투입이 중복 반영된다.

**[권장 개선안]** SENT 는 N 일 지난 것을 작은 배치로 지우고(6.3 표), inbox 는 "토픽 보존 + 재처리 운영 기간"보다 긴 보존 기간을 정해 같은 방식으로 지운다.

---

#### R9. DLT 보존 기간이 재투입 기한이다

**[확인됨 — OCI 브로커]** DLT 토픽의 보존 기간을 정한 곳이 없어 브로커 기본값을 따른다 — `stove.payment.v1.DLT` 의 `retention.ms=604800000`(7일). 재투입하지 않은 DLT 레코드는 7일 뒤 **DLT 에서도 사라진다.**
`MessagesDeadLettered` 알람은 "쌓였다"를 알리지만 "오래됐다"는 알리지 않는다.

**[권장 개선안]** DLT 토픽은 보존 기간을 길게(예: 30일) 명시하고, "가장 오래된 DLT 레코드의 나이"를 지표·알람으로 둔다.

---

#### R10. 커넥션 총량이 스케일아웃을 막는다

**[확인됨]** MySQL 인스턴스 하나에 일곱 서비스의 풀 합계 110(4.8), `max_connections` 기본 151. 원격 스택에서 요청이 없을 때 `stove` 커넥션을 세면 **110개**다.
Hikari 는 `minimumIdle` 기본값이 최대치라 **기동하자마자** 전부 연다. 서비스를 두 대씩 띄우면 220 이 필요해, 먼저 뜬 인스턴스가 커넥션을 다 쥐고 나중에 뜨는 쪽이 "Too many connections" 로 실패한다 — 통합 테스트가 같은 벽을 만났다(D-036).

**[권장 개선안]** 배포 단위의 "인스턴스 수 × 풀 크기 합계 < max_connections − 여유"를 배포 게이트에서 검사하고, 풀을 측정 기반으로 줄이거나(측정상 동시 사용 최대 5), 인스턴스가 늘면 커넥션 프록시를 둔다.

---

#### R11. 재시도해도 성공할 수 없는 실패도 네 번 시도한다

**[확인됨]** 에러 핸들러에 비재시도 예외를 추가하지 않았다. 스프링 기본 비재시도 목록(역직렬화·변환 계열 여섯)에 이 프로젝트의 계약 위반(`EventEnvelope` 의 `IllegalStateException`)과 도메인 거절(`BusinessException`)은 없다.
그래서 헤더가 빠진 메시지도 1·2·4초를 기다려 네 번 시도하는 동안 파티션을 세운 뒤 DLT 로 간다.

**[권장 개선안]** 계약 위반은 전용 예외로 바꿔 `addNotRetryableExceptions` 에 넣는다. `BusinessException` 전체를 넣지는 않는다 — R3 의 CONFLICT 처럼 **순서가 바로잡히면 풀리는** 거절도 있어서, 영구 거절만 골라야 한다.

---

#### R12. 정산 귀속 월이 이벤트 시각이 아니라 소비 시각이다

**[확인됨]** `SettlementRecordService` 는 원장의 귀속 월을 `YearMonth.from(LocalDate.now())` 로 정한다(`:44`, `:73`). 이벤트에는 `occurredAt` 이 있지만 쓰지 않는다.
8월 31일 23:59 에 승인된 결제가 소비 지연으로 9월 1일에 처리되면 9월 매출이 된다. 금액이 사라지지는 않지만(D-001 의 누적 반영) 귀속 월과 세금계산서의 월이 달라진다.
`LocalDate.now()` 는 JVM 시간대를 따르고, 컨테이너 이미지가 `-Duser.timezone=Asia/Seoul` 로 고정한다 [확인됨 — Dockerfile]. 컨테이너 밖에서 띄우면 머신 시간대를 따른다.

**[추가 확인 필요]** 결제 시각과 처리 시각 중 무엇을 귀속 기준으로 할지는 회계 정책이다. 결제 시각이라면 `PaymentCompleted` 의 `occurredAt` 을 쓰고 시간대를 명시적으로 변환한다.

---

#### R13. 릴레이 1대 제약이 강제되지 않는다

**[확인됨]** 릴레이 빈은 `stove.outbox.relay-enabled`(기본 true)만 보고 **모든 인스턴스에** 뜬다(`MessagingAutoConfiguration.java:82-92`). 리더 선출도, 락도 없다.
event-ordering.md 7절과 code-notes 는 "릴레이는 서비스당 1대여야 한다"고 적었지만 코드에는 드러나지 않는다. 서비스를 두 대로 늘리는 순간 릴레이도 두 대가 되고, 같은 주문의 이벤트가 두 릴레이로 갈라져 **키 웨이브의 순서 보장이 조용히 무효**가 된다.

**[권장 개선안]**

| 안 | 대가 |
|---|---|
| 배포 설정으로 한 인스턴스만 `relay-enabled=true` | 그 인스턴스가 죽으면 발행이 멈춘다 — 자동 인계 없음 |
| 릴레이 루프에 ShedLock | `lockAtMostFor` 가 최악의 한 회차(브로커 장애 시 배치당 120초 × 최대 10배치)보다 길어야 두 대가 겹치지 않는다 |
| 파티션 키 해시로 워커 고정 배정 (event-ordering.md 7절) | 워커 수를 바꿀 때 재배치 창 |
| CDC | 운영 대상 추가 (R1 도 함께 해결) |

---

#### R14. Eager 리밸런싱과 한 그룹의 여러 리스너

**[확인됨 — kafka-clients 기본값, OCI 브로커]** 배정 전략을 정하지 않아 `RangeAssignor` 가 선택되고(원격 브로커에서 조회한 그룹이 모두 `range`, 5.8), static membership 도 없다(`group.instance.id = null`, 실행 중인 앱 로그).
배포·재시작마다 그룹 전체가 파티션을 내려놓았다 다시 받는다. payment(리스너 2)와 download(리스너 3)는 리스너 하나의 재시작이 같은 그룹의 다른 토픽 소비까지 멈춘다.
중복은 Inbox 가 흡수하므로 정확성 문제는 아니고, **정지 시간과 재처리량**의 문제다.

**[권장 개선안]** `partition.assignment.strategy=CooperativeStickyAssignor`, 롤링 배포가 잦으면 `group.instance.id`.

---

#### R15. 주문 생성 API 에 멱등키가 없다

**[확인됨]** `CreateOrderRequest` 에 멱등키가 없고 서버도 요청을 식별하지 않는다. 응답을 받지 못한 클라이언트가 재시도하면 주문이 하나 더 생긴다.
결제는 주문마다 사용자가 따로 시작해야 하고 안 쓴 주문은 1시간 뒤 만료되므로 금전 사고로 번지지는 않는다.

**[권장 개선안]** `Idempotency-Key` 헤더와 `(member_id, idempotency_key)` 유니크. 같은 키면 기존 주문을 돌려준다.

---

### 8.2 문서와 코드가 어긋난 곳

면접관이 README 와 코드를 함께 보면 바로 짚을 수 있는 자리들이다. **"문서가 틀렸다"와 "코드가 틀렸다"를 구분해 말한다.**

| 문서 | 문서의 문장 | 실제 | 어느 쪽을 고칠까 |
|---|---|---|---|
| README 3절 게이트 4, services.md payment, code-notes `PgApproval` | 중복 콜백은 상태 + `idempotency_key` **유니크 제약**으로 흡수 | 유니크 제약은 `V2__scope_idempotency_key.sql` 이 지웠다(D-008). 막는 것은 `SELECT … FOR UPDATE` + 상태 검사 | 문서 |
| code-notes `DltOpsService` | 재투입에서도 같은 애그리거트의 순서 보장이 유지된다 | 파티션은 같지만 로그 끝에 붙어 같은 키의 뒤 메시지보다 늦게 처리된다 | 문서 + R2 개선 |
| decisions.md 19번 | DLT 는 맞바꿈이 아니라 순수 개선 | 유실 방지로는 개선, 대신 키 순서를 내준 맞바꿈 | 문서 |
| event-ordering.md 6절 D-2 | 문서 ID 고정 upsert 는 멱등 + 교환법칙 | 버전 비교가 없어 교환법칙이 성립하지 않는다 — D-013 시나리오 A 가 반례 | 문서 |
| order `V7__shedlock.sql`, code-notes `SchedulerLockConfig (order)` | 같은 행을 두 인스턴스가 집으면 하나는 CONFLICT 로 튕긴다 | 둘 다 성공한다(스냅샷 읽기 후 PK UPDATE) | 문서 (또는 R3 개선으로 사실로 만들기) |
| defects.md D-029 후속, code-notes `handleApproval` | 만료 뒤 승인의 `PaymentCancelled` 는 order 가 CREATED 에서 취소한다 | 주문 만료(#43) 이후 EXPIRED 일 수 있고 `cancel()` 이 거부한다 | 코드 (R3) |
| performance.md 9-4 | 릴레이의 쓰기 지연 원인 후보로 "같은 커넥션 풀" | 13-5 가 60 RPS 에서 풀 `pending` 0 으로 기각했고, 갭 락 경합을 A/B 로 쟀다 — RR 에서 락 대기 400회 → RC 에서 0회, p95 26.2 → 21.5ms(200ms 폴링 기준, R1) | 문서 — 배너로 덧붙였다 |
| migration `V3__outbox_retry_backoff.sql` | 기본 설정에서 감내 시간이 약 8분 | 실패가 즉시 난다고 가정한 계산이다. 브로커가 응답하지 않으면 시도마다 `delivery.timeout.ms`(120초)를 기다리므로 DEAD 까지 대략 30분 가까이 걸린다 **[코드상 추정]** | 문서 (위험은 아니다 — 오히려 오래 버틴다) |
| kafka-consumer-retry.md 6절 "제약" | 블로킹 재시도는 백오프 **총합**이 `max.poll.interval.ms`(5분)를 넘으면 안 된다 | `DefaultErrorHandler` 는 백오프를 한 번 자고 seek 한 뒤 돌아가고, 컨테이너가 다시 poll 해서 같은 레코드를 받는다(`FailedRecordTracker#recovered` → `SeekUtils#doSeeks`). 한도는 시도 한 번(처리 + 백오프 한 번)마다 걸린다 [확인됨 — spring-kafka 3.3.10 소스] | 문서 — "블로킹 재시도가 맞다"는 결론은 그대로다 |

---

## 9. 예상 기술면접 질문

레벨은 L1 기본 개념 · L2 프로젝트 적용 · L3 내부 동작 · L4 장애와 Trade-off · L5 설계다. 답은 오른쪽 절에 있다.

### 프로젝트와 설계

| # | L | 질문 | 답 |
|---|---|---|---|
| P1 | 2 | 프로젝트를 소개해 주세요 | [1.4](#14-30초-소개--면접-첫-답변) |
| P2 | 2 | 왜 MSA 로 나눴고, 서비스 사이는 어떻게 통신하나요? | 1.1, 2.1, 7.1 |
| P3 | 2 | 서비스끼리 동기 호출하는 곳은 없나요? 왜 그곳만 동기인가요? | 2.1, 5.1 |
| P4 | 4 | 비동기로 바꾸면 사용자는 무엇을 보게 되나요? | 3.5 |
| P5 | 5 | 현재 설계의 가장 큰 단점은? | [11장 답변 10](#답변-10--현재-설계의-가장-큰-단점은-무엇인가요) |

### DB 트랜잭션 · 격리 · 락

| # | L | 질문 | 답 |
|---|---|---|---|
| D1 | 1 | 트랜잭션과 ACID 를 설명해 주세요 | 4.2, 4.3 |
| D2 | 2 | 이 프로젝트에서 트랜잭션 경계는 어디인가요? | 4.2 경계 지도 |
| D3 | 3 | `@Transactional` 이 DB 에서 실제로 하는 일은? | 4.2 |
| D4 | 3 | `MANDATORY` · `REQUIRES_NEW` 를 어디에 왜 썼나요? | 4.2 |
| D5 | 4 | 트랜잭션 안에서 외부 API 를 부르면 어떤 문제가 생기나요? 이 프로젝트에 그런 곳이 있나요? | 4.2, R4 |
| D6 | 1 | 격리 수준 네 가지와 이상 현상은? | 4.4 |
| D7 | 2 | 이 프로젝트의 격리 수준은? 왜 그걸 쓰나요? | 4.4, [답변 7](#답변-7--격리-수준은-무엇을-쓰고-무엇이-문제였나요) |
| D8 | 3 | REPEATABLE READ 에서 Lost Update 가 나나요? | 4.4, R3 |
| D9 | 3 | `FOR UPDATE` 는 무엇을 잠그나요? 없는 행이면? | 4.5 |
| D10 | 3 | `SKIP LOCKED` 가 무엇이고 릴레이에서 무엇을 잠그나요? | 4.5, R1 |
| D11 | 2 | MVCC 가 있는데 락은 왜 필요한가요? | 4.6 |
| D12 | 3 | 데드락은 어떻게 나고 InnoDB 는 어떻게 처리하나요? 이 프로젝트에서 날 수 있나요? | 4.7 |
| D13 | 4 | 커넥션 풀이 고갈되면? 인스턴스를 늘리면? | 4.8, R10 |
| D14 | 2 | 유니크 제약을 애플리케이션 확인과 함께 거는 이유는? | 4.1 |

### Kafka

| # | L | 질문 | 답 |
|---|---|---|---|
| K1 | 2 | 왜 Kafka 인가요? RabbitMQ 는요? | 5.1, [답변 1](#답변-1--왜-kafka-를-썼나요) |
| K2 | 2 | 토픽·파티션·키는 어떻게 설계했나요? | 5.2, 5.3 |
| K3 | 3 | `acks=all` 이면 유실이 없나요? | 5.4 |
| K4 | 3 | 멱등 프로듀서가 있으면 중복 발행이 없나요? | 5.4 |
| K5 | 1 | Offset 이 무엇이고 누가 관리하나요? | 5.7 |
| K6 | 2 | 이 프로젝트는 오프셋을 언제 커밋하나요? | 5.5, 5.7 |
| K7 | 3 | 처리 전에 커밋하면? 처리 후에 커밋하면? | 5.7 |
| K8 | 1 | Consumer Group 이 무엇인가요? 파티션보다 컨슈머가 많으면? | 5.6 |
| K9 | 3 | 리밸런싱이 무엇이고 그동안 무슨 일이 일어나나요? | 5.8 |
| K10 | 4 | 처리 시간이 긴 메시지는 컨슈머에 어떤 영향을 주나요? | 5.8 |
| K11 | 2 | 순서는 어떻게 보장하나요? `concurrency` 를 올려도 되나요? | 5.9, [답변 6](#답변-6--순서는-어떻게-보장하나요) |
| K12 | 3 | 재시도는 어떻게 동작하나요? 리스너에서 예외를 잡으면? | 5.10 |
| K13 | 4 | DLT 로 보내면 무엇을 잃나요? DLT 발행이 실패하면? | 5.11, R2, R6 |
| K14 | 1 | At-most / At-least / Exactly-once 를 설명해 주세요 | 5.12 |
| K15 | 4 | Kafka exactly-once 를 쓰면 DB 까지 정확히 한 번인가요? | 5.12, [답변 3](#답변-3--그럼-exactly-once-아닌가요) |

### Kafka + DB 정합성

| # | L | 질문 | 답 |
|---|---|---|---|
| C1 | 2 | Dual Write Problem 이 무엇인가요? | 6.1 |
| C2 | 2 | Outbox 를 왜 썼나요? | 6.3, [답변 4](#답변-4--outbox-를-왜-썼고-무엇이-어려웠나요) |
| C3 | 3 | 릴레이는 어떻게 동작하나요? | 6.3 |
| C4 | 4 | Outbox 에서도 중복 발행이 생기나요? | 6.3 |
| C5 | 4 | 릴레이를 여러 대 띄우면? | 6.3, R13 |
| C6 | 4 | Outbox 테이블이 계속 커지면? | 6.3, R8 |
| C7 | 5 | CDC 와 Outbox 는 무엇이 다른가요? | 6.3 |
| C8 | 2 | 같은 메시지가 두 번 오면 안전한가요? | 6.2, [답변 5](#답변-5--같은-메시지가-두-번-오면-안전한가요) |
| C9 | 3 | Inbox 는 동시에 두 번 들어온 이벤트를 어떻게 막나요? | 6.2, 실험 5 |
| C10 | 4 | DB 커밋 후 오프셋 커밋 전에 죽으면? | 6.4 Case 3, [답변 2](#답변-2--db-커밋-후-오프셋-커밋-전에-죽으면) |
| C11 | 4 | 외부 API 성공 후 DB 커밋이 실패하면? | 6.4 Case 4 |

### Saga · 결제

| # | L | 질문 | 답 |
|---|---|---|---|
| S1 | 2 | 결제는 됐는데 지급이 실패하면? | 3.3, [답변 9](#답변-9--saga-보상은-언제-실행되나요) |
| S2 | 4 | 재시도가 끝나면 환불해도 되지 않나요? | 3.3 (D-027, D-028) |
| S3 | 3 | PG 콜백이 동시에 두 번 오면? | 4.5, [답변 8](#답변-8--결제-콜백이-동시에-두-번-오면요) |
| S4 | 4 | PG 환불이 중간에 실패하면? | 3.2, 6.4 |
| S5 | 4 | 결제창이 만료된 뒤 승인이 오면? | code-notes `handleApproval`, R3 (c) |

### 장애와 확장

| # | L | 질문 | 답 |
|---|---|---|---|
| O1 | 4 | Kafka 브로커가 10분 / 1시간 죽으면? | [12장](#12-장애-상황-질문) |
| O2 | 4 | 컨슈머 하나가 1시간 멈추면? | 12장 |
| O3 | 4 | DB 가 1분 끊기면? | 12장, D-027 |
| O4 | 5 | 트래픽이 10배가 되면? | [13.1](#131-트래픽이-10배가-되면) |
| O5 | 5 | 인스턴스를 세 대로 늘리면 무엇을 바꿔야 하나요? | 13.2 |
| O6 | 5 | PostgreSQL 로 바꾸면 무엇이 달라지나요? | 13.3 |

---

## 10. 예상 꼬리질문

면접관이 실제로 파고드는 순서대로 적었다. 한 줄 답은 말할 뼈대이고, 자세한 근거는 괄호 안의 절에 있다.

### 체인 A — 트랜잭션에서 Exactly-once 까지

> **Q.** `LicenseService.issue()` 에서 트랜잭션을 왜 이렇게 잡았나요?

Inbox 마킹 · 라이선스 저장 · `LicenseIssued` 적재가 **"이 결제 이벤트를 처리했다"는 한 사실**이라서 한 커밋에 묶었습니다. 셋 중 하나만 커밋되면 유실(마킹만 됨)이나 이벤트 없는 지급(적재 누락)이 생깁니다. (4.2)

> **꼬리 1.** 범위를 더 넓히면요? 리스너에 `@Transactional` 을 달면?

리스너가 트랜잭션을 열면 안쪽 예외가 바깥 트랜잭션을 rollback-only 로 만들어 보상 경로가 `UnexpectedRollbackException` 으로 깨집니다(decisions.md 6번). 넓힐수록 락과 커넥션을 오래 쥡니다. 이 프로젝트의 릴레이가 Kafka ack 대기까지 트랜잭션에 넣어서, 부하 중 주문 INSERT 가 락을 기다리는 것을 실제로 쟀습니다. (R1)

> **꼬리 2.** 그럼 Kafka Consumer 에서 DB Transaction 과 Offset Commit 의 순서는 어떻게 해야 하나요?

DB 를 먼저 커밋하고 리스너가 정상 리턴한 뒤 오프셋을 커밋합니다(`ack-mode: record`, `commitSync`). 반대로 하면 커밋된 오프셋 뒤에서 DB 가 실패해 메시지를 잃습니다. 이 순서가 at-least-once 입니다. (5.7)

> **꼬리 3.** DB Commit 성공 후 프로세스가 죽으면요?

오프셋이 커밋되지 않았으니 재시작하거나 리밸런싱된 컨슈머가 같은 메시지를 다시 받습니다. `processed_event` 에 이벤트 ID 가 있어 건너뛰고 오프셋만 커밋합니다. (6.4 Case 3)

> **꼬리 4.** 그럼 Exactly-once 아닌가요?

전달은 at-least-once 이고, 결과 반영이 컨슈머 그룹마다 한 번입니다. Kafka 의 exactly-once 는 Kafka 안의 쓰기와 오프셋을 묶는 것이라 DB 까지는 해당하지 않습니다. Inbox 기록이 사라지거나, 같은 사건이 다른 ID 로 오거나, 순서가 뒤집히면 이 성질이 깨진다는 것도 알고 있습니다. (5.12)

> **꼬리 5.** Inbox 기록이 사라지는 일이 실제로 있나요?

원장 테이블만 잃고 Inbox 는 남은 경우가 반대로 문제였습니다. 오프셋을 되돌려 재처리해도 전부 "이미 처리"로 건너뛰어 200건 중 0건이 복구됐고 에러 로그도 없었습니다. 그래서 복구 절차에 Inbox 행을 범위를 좁혀 지우는 단계를 넣었습니다. (D-030)

> **꼬리 6.** 오프셋을 DB 에 결과와 같이 저장하면 되지 않나요?

Kafka 커밋과의 틈은 사라지지만, 같은 이벤트가 다른 오프셋으로 다시 들어오는 경우 — 릴레이 중복 발행, DLT 재투입 — 를 못 거릅니다. 이벤트 ID 로 판단하는 Inbox 가 그 경우까지 거르고 파티션 이동과도 무관합니다. (5.7 L5)

### 체인 B — Outbox 를 끝까지

> **Q.** Outbox 를 왜 썼나요?

DB 커밋과 Kafka 발행을 원자적으로 묶을 방법이 없어서, "발행해야 한다"는 사실을 비즈니스 데이터와 같은 커밋에 넣었습니다. (6.1)

> **꼬리 1.** 릴레이가 보내고 SENT 로 바꾸기 전에 죽으면요?

PENDING 으로 남아 다시 보냅니다. 중복이고, 컨슈머 Inbox 가 흡수합니다.

> **꼬리 2.** 발행이 실패하면 순서가 꼬이지 않나요?

실패한 이벤트를 같은 주문의 뒤 이벤트가 추월하는 결함이 있었습니다(D-013). 키 단위 웨이브로 앞이 성공해야 뒤를 보내고, 앞의 재시도 시각을 뒤에도 전파해 다음 폴링에서도 추월하지 못하게 했습니다(D-014).

> **꼬리 3.** 릴레이를 두 대 띄우면요?

`SKIP LOCKED` 덕에 같은 행을 둘이 집지는 않지만 같은 주문의 이벤트가 두 릴레이로 갈라져 순서가 깨집니다. 문서상 1대 제약이고, 코드로 강제되지는 않아서 스케일아웃 전에 리더 선출이나 키 해시 배정이 필요합니다. (R13)

> **꼬리 4.** 그 `SKIP LOCKED` 조회가 DB 에서 정확히 무엇을 잠그나요?

REPEATABLE READ 에서 `idx_outbox_pending` 에 넥스트키 락을 겁니다. 새 PENDING 행이 들어갈 갭까지 잠겨서, MySQL 로 재현하면 다른 트랜잭션의 outbox INSERT 가 릴레이 커밋까지 기다립니다. `LIMIT 200` 이어도 적체가 있으면 수천 행을 잠급니다. (4.5, 실험 1·2)

> **꼬리 5.** 그게 실제 성능에 영향이 있나요?

격리 수준만 바꿔 60 RPS 로 A/B 를 재 보니 REPEATABLE READ 에서는 90초에 행 락 대기가 400회, READ COMMITTED 에서는 0회였고 쓰기 p95 가 26ms 에서 21ms 가 됐습니다. 평시엔 p95 로 5ms 정도입니다. 코드상으로는 브로커가 응답하지 않을 때 릴레이가 최대 2분간 락을 쥐어 INSERT 가 50초 뒤 타임아웃으로 실패할 수 있는데, 이 장애는 아직 재현하지 않았습니다. (R1)

> **꼬리 6.** 어떻게 고치나요?

릴레이 트랜잭션만 READ COMMITTED 로 두면 갭 락이 사라집니다. 브로커 장애까지 격리하려면 "짧은 트랜잭션으로 선점 → 트랜잭션 밖에서 발행 → 짧은 트랜잭션으로 결과 기록" 으로 나누거나, binlog 를 읽는 CDC 로 발행기를 바꿉니다. (R1)

> **꼬리 7.** CDC 로 가면 무엇을 떠안나요?

Kafka Connect 와 커넥터 운영, binlog 설정과 스키마 변경 대응입니다. 대신 커밋 순서대로 읽으니 순서 문제·1대 제약·잠금 조회가 한꺼번에 사라집니다. (6.3)

### 체인 C — 결제 콜백 동시성

> **Q.** PG 콜백이 동시에 두 번 오면요?

주문번호로 결제 행을 `SELECT … FOR UPDATE` 로 잠그고 읽습니다. 두 번째는 첫 번째 커밋까지 기다렸다가 PAID 를 보고, 같은 멱등키면 이벤트 없이 끝냅니다. (4.5)

> **꼬리 1.** 왜 낙관적 락이 아니라 비관적 락인가요?

같은 주문에만 몰리는 짧은 경합이라 기다리는 비용이 작고 재시도 로직이 필요 없습니다. 낙관적 락이면 늦은 콜백이 실패하고, PG 가 이해할 응답과 재전송 설계를 따로 해야 합니다.

> **꼬리 2.** 락을 기다리는 사이 PG 가 타임아웃으로 같은 콜백을 또 보내면요?

세 번째 요청도 같은 행 락 뒤에 줄을 서고, 먼저 커밋된 PAID 를 보고 같은 멱등키라 아무것도 하지 않습니다. 결제 행 락은 짧게 끝나야 하는데, 승인 트랜잭션의 outbox INSERT 가 릴레이 갭 락을 기다리면 이 대기가 길어진다는 점은 주의할 부분입니다. (R1)

> **꼬리 3.** 승인 콜백과 거절 콜백이 동시에 오면요?

같은 행 잠금을 쓰므로 순서가 강제되고, 뒤에 온 쪽이 상태 가드에 걸려 예외가 납니다. 엇갈린 콜백이 조용히 흡수되지 않고 드러납니다. (code-notes `handleDecline`)

> **꼬리 4.** 멱등키에 유니크 제약을 걸면 락이 필요 없지 않나요?

처음엔 그랬는데, PG 가 만드는 키라 재사용되면 다른 주문의 승인을 삼키는 결함이 있었습니다(D-008). 그래서 유니크를 지우고 주문번호 단위로 좁혔습니다. README 에는 아직 "유니크 제약으로 흡수"라고 적혀 있어서 문서를 고쳐야 합니다. (8.2)

> **꼬리 5.** 동시 콜백을 테스트로 확인했나요?

기존 테스트는 두 콜백을 순차로 부릅니다. 락이 동시성을 막는다는 건 InnoDB 동작으로 설명할 수 있지만, 동시에 부르는 테스트는 아직 없어서 추가해야 합니다. (4.5)

### 체인 D — DLT 와 순서

> **Q.** 재시도가 끝난 메시지는 어떻게 되나요?

`<원본토픽>.DLT` 의 같은 파티션으로 보내고 오프셋을 넘깁니다. 알람이 울리고, 원인을 고친 뒤 운영 API 로 원본 토픽에 재투입합니다. license 의 지급 실패만 보상 여부를 먼저 판단합니다. (5.11)

> **꼬리 1.** 파티션이 안 막히니 좋은 것 아닌가요?

유실 없이 파티션을 살리는 대가로 **그 키의 순서**를 내줍니다. 같은 주문의 다음 메시지가 먼저 처리되고, 재투입한 메시지는 토픽 끝에 붙습니다.

> **꼬리 2.** 구체적으로 어떤 사고가 나나요?

결제 완료가 DB 장애로 DLT 에 보류된 사이 사용자가 "게임이 없다"며 환불하면, 라이선스 회수는 회수할 게 없어 조용히 끝나고, 나중에 재투입한 결제 완료가 환불된 주문에 게임을 지급합니다. 정산에서는 환불 역산이 먼저 헛돌고 매출만 남습니다. (R2)

> **꼬리 3.** Inbox 가 막아 주지 않나요?

DLT 로 간 그룹은 처리 트랜잭션이 롤백돼 Inbox 기록이 없습니다. 재투입은 그 그룹에게 처음 보는 메시지입니다.

> **꼬리 4.** 어떻게 막나요?

당장은 재투입 전에 결제 상태를 확인하는 절차로 막고, 구조적으로는 "대상 없는 회수"를 기록해 두었다가 늦은 지급을 거르거나, 한 키가 DLT 에 가면 그 키의 뒤 메시지도 함께 보류하는 키 단위 파킹을 씁니다. 발행 쪽 키 웨이브와 같은 발상입니다. (R2)

> **꼬리 5.** 그럼 차라리 파티션을 세우면요?

형식이 깨진 메시지 한 건이 뒤의 정상 결제 이벤트를 전부 막습니다(파티션을 세우지 않은 이유는 decisions.md 19번). 그래서 파티션 전체가 아니라 **키 단위로** 멈추는 쪽이 이 시스템에 맞는 중간값이라고 봅니다. (R2 개선안 3)

> **꼬리 6.** DLT 발행이 실패하면요?

스프링 기본 recoverer 는 예외를 던져 같은 레코드를 계속 재시도하므로 유실 대신 파티션이 멈춥니다. license 는 그 예외를 삼켜 오프셋이 넘어가서 레코드가 로그에만 남습니다. 결제 이벤트는 멈추는 편이 낫다고 봐서 고칠 대상입니다. (R6)

### 체인 E — 컨슈머 그룹과 리밸런싱

> **Q.** 컨슈머를 한 대 더 띄우면 어떻게 되나요?

같은 그룹에 멤버가 늘어 리밸런싱이 일어나고 파티션을 나눠 맡습니다. 파티션이 3개라 그룹당 컨슈머 3개가 상한입니다. (5.6)

> **꼬리 1.** 리밸런싱 동안에는요?

배정 전략을 정하지 않아 RangeAssignor, 즉 Eager 방식입니다 — 원격 스택 브로커에서 조회한 그룹도 모두 `range` 로 나옵니다. 모든 멤버가 파티션을 내려놓고 다시 받으니 그동안 그룹 전체가 멈춥니다. (5.8)

> **꼬리 2.** 처리 중이던 메시지는 중복 처리되나요?

커밋 전이던 한 건은 새 담당이 다시 처리합니다. 레코드마다 커밋해서 범위가 한 건이고 Inbox 가 거릅니다.

> **꼬리 3.** 처리 시간이 길면요?

poll 간격이 `max.poll.interval.ms`(5분)를 넘으면 쫓겨나고 커밋이 실패해 같은 레코드가 다시 옵니다. 지금은 500건 묶음이 3.5초 남짓이라 여유가 크지만, 수십 초짜리 락 대기가 한 묶음에 여러 번 붙으면 여유가 사라집니다. 먼저 `max.poll.records` 를 낮춥니다. 재시도는 시도마다 다시 poll 하므로 백오프 합계는 이 한도와 상관없습니다. (5.8, 5.10)

> **꼬리 4.** 실제로 리밸런싱 때문에 헷갈린 적이 있나요?

통합 테스트에서 컨슈머를 멈췄는데 지급이 계속 일어났습니다. 테스트 JVM 에 같은 그룹의 멤버가 하나 더 있어서 파티션이 그쪽으로 넘어간 것이었습니다. Kafka 에게는 장애가 아니라 리밸런싱이었습니다. (D-037)

> **꼬리 5.** 개선한다면요?

`CooperativeStickyAssignor` 로 옮겨야 할 파티션만 옮기고, 롤링 배포가 잦으면 static membership 으로 짧은 재시작에 리밸런싱을 피합니다. (R14)

### 체인 F — 격리 수준과 만료 스윕

> **Q.** 격리 수준은 무엇을 쓰나요?

설정하지 않아 MySQL 기본 REPEATABLE READ 입니다. 원격 스택에서 order 커넥션 20개가 모두 그 수준인 것도 확인했습니다. (4.4)

> **꼬리 1.** 그럼 동시성 문제는 없나요?

MySQL 의 REPEATABLE READ 는 Lost Update 를 막지 않습니다. 스냅샷으로 읽고 최신 행에 쓰기 때문에, 잠그지 않는 read-modify-write 는 그사이 커밋된 변경을 덮습니다.

> **꼬리 2.** 이 코드 어디서요?

주문 만료 스윕과 결제 확정입니다. 둘 다 잠그지 않고 주문을 읽어 JPA 로 쓰고 버전 컬럼이 없어서, 겹치면 에러 없이 EXPIRED 가 PAID 를 덮습니다. MySQL 로 재현했습니다. (R3, 실험 4)

> **꼬리 3.** 평소에도 겹치나요?

평소엔 결제가 45분 안에 끝나고 만료는 60분이라 안 겹칩니다. order 컨슈머가 15분 넘게 밀리거나, DEAD·DLT 회수로 결제 이벤트가 몇 시간 뒤 오면 겹치거나, 만료가 먼저 끝나 결제 확정이 DLT 로 갑니다.

> **꼬리 4.** PostgreSQL 이면 달랐을까요?

PostgreSQL 의 REPEATABLE READ 는 동시에 갱신된 행을 쓰려 하면 직렬화 오류를 냅니다. 조용히 덮이지 않는 대신 재시도 코드가 필요합니다. 같은 이름의 격리 수준이 DB 마다 다르게 동작합니다. (13.3)

> **꼬리 5.** 어떻게 고치나요? SERIALIZABLE 로 올리면요?

전역으로 올리면 일반 SELECT 까지 공유 락이 걸려 처리량과 데드락 비용이 큽니다. `WHERE status='CREATED'` 를 붙인 조건부 UPDATE 나 `@Version` 으로 이 경합만 막고, 만료 뒤 도착한 결제를 되살릴지 되돌릴지 정책을 정합니다.

### 체인 G — Saga 보상

> **Q.** 결제는 됐는데 라이선스 지급이 실패하면요?

1·2·4초로 세 번 재시도하고, 그래도 실패하면 recoverer 가 판단합니다. 실패 원인이 저장소 장애가 아니고 실제로 지급되지 않았을 때만 `LicenseIssueFailed` 를 내서 결제가 자동 환불합니다. (3.3)

> **꼬리 1.** 재시도가 끝났으면 실패가 확정된 것 아닌가요?

아닙니다. "네 번 물어봤는데 답을 못 받았다"일 뿐입니다. 예전엔 그렇게 판단해서 DB 가 60초 끊긴 사이 정상 결제 8건이 환불됐습니다. 저장소 장애는 환불하지 않고 DLT 로 보류하도록 바꿨고, 같은 조건에서 환불 0건, 재투입 후 1초 안에 전부 지급됐습니다. (D-027)

> **꼬리 2.** 이미 지급됐는지는 왜 다시 보나요?

지급이 커밋된 뒤 오프셋 커밋 전에 재배달되고 그 재처리가 실패하면, "실패"라는 보고가 사실이 아닌데 환불하게 됩니다. 로그에서 같은 주문에 지급 성공과 보상 시작이 함께 남은 것을 보고 찾았습니다. (D-028)

> **꼬리 3.** 보상 이벤트 기록은 왜 `REQUIRES_NEW` 인가요?

지급 트랜잭션이 롤백된 뒤에도 보상 이벤트는 반드시 커밋돼야 해서입니다. 실제 호출 위치는 이미 트랜잭션 밖인 recoverer 라서 새 트랜잭션을 여는 효과이고, 나중에 트랜잭션 안에서 불리게 되더라도 바깥 롤백에 끌려가지 않게 한 방어입니다.

> **꼬리 4.** 환불 PG 호출이 실패하면요?

환불 의도(CANCELING)를 먼저 커밋하고 트랜잭션 밖에서 PG 를 부르므로, 실패하면 CANCELING 으로 남습니다. 스윕이 2분부터 30분까지 간격을 늘리며 다시 걸고, PG 취소가 멱등이라 이중 환불이 되지 않습니다. 1시간을 넘기면 사람을 부릅니다. (3.2)

> **꼬리 5.** 그럼 자동 보상은 잘 돌고 있나요?

세어 보니 license outbox 4,153건 중 `LicenseIssueFailed` 가 0건이었고, 저장소 장애 800건을 넣은 회차도 보류 47 · 환불 0 이었습니다 — 저장소 장애가 전부 보류로 가서, 남는 방아쇠는 저장소와 무관한 실패뿐입니다(defects.md D-027, #46). 틀린 자동 환불보다 느린 수동 처리가 낫다고 판단했고, 대신 그 보류 기간에 사용자가 환불하면 순서가 뒤집히는 위험(R2)이 생긴다는 걸 이번에 찾았습니다.

### 체인 H — 트래픽 10배

> **Q.** 트래픽이 10배가 되면 무엇이 먼저 문제가 되나요?

소비 쪽입니다. payment 컨슈머가 스레드 하나로 초당 141건이고, 파티션이 3개라 `concurrency` 를 3 까지 올려 잰 값이 364건입니다. 릴레이는 948건이라 발행이 먼저 쌓이는 게 아니라 브로커에 랙이 쌓입니다. (13.1)

> **꼬리 1.** 파티션을 늘리면 되지 않나요?

키 매핑이 바뀌어 같은 주문이 옛/새 파티션으로 갈라지므로, 릴레이를 멈추고 랙을 비운 뒤 늘립니다. Outbox 가 있어서 API 를 멈추지 않고 발행만 멈출 수 있습니다. (5.3)

> **꼬리 2.** 인스턴스를 늘리면요?

릴레이도 같이 늘어나 순서 보장이 조용히 풀리고(R13), 풀 합계가 MySQL 한도를 넘습니다 — 지금 놀고 있는 스택에서도 커넥션 110개를 쓰고 있고 한도는 151 입니다(R10).

> **꼬리 3.** 쓰기 API 는요?

동시성이 올라가면 릴레이의 갭 락을 기다리는 INSERT 도 늘 것으로 봅니다. 60 RPS 에서 주문의 7% 가 기다렸습니다. 릴레이 유무 비교(폴링 1000ms 시절)에서는 계단식 부하에서 p95 가 466ms 대 84ms 까지 벌어졌는데, 그 차이 중 락의 몫은 아직 재지 않았습니다. 릴레이 격리 수준을 먼저 바꿉니다. (R1)

> **꼬리 4.** 가장 먼저 할 한 가지는요?

측정 없이 바꾸지 않는다는 전제에서 둘입니다. 설정 한 줄로 순서를 해치지 않고 소비를 2.5배로 올리는 `concurrency` 3, 그리고 릴레이 트랜잭션의 READ COMMITTED 입니다. `concurrency` 는 그 설정 그대로 쟀고, 격리 수준은 order 커넥션 전체를 바꿔 쟀으니 릴레이만 바꾸는 코드 변경은 같은 A/B 로 다시 확인합니다.

---

## 11. 모범 답변

1~2분 안에 말할 수 있게 다듬은 답이다. 외우기보다 **흐름(요구 → 선택 → 대가 → 대응)** 을 익힌다.

#### 답변 1 — 왜 Kafka 를 썼나요?

> 단순히 비동기 처리를 위해서라기보다, 결제 한 건이 여러 서비스의 일을 일으키는 구조였기 때문입니다. 결제가 끝나면 주문 확정, 라이선스 지급, 정산 집계가 일어나야 하는데, 결제 서비스가 셋을 직접 호출하면 라이선스 서비스가 죽었을 때 결제까지 실패합니다.
> 그래서 결제는 "결제 완료" 이벤트만 남기고, 세 서비스가 각자 읽게 했습니다. Kafka 를 고른 이유는 한 이벤트를 여러 서비스가 각자의 오프셋으로 읽을 수 있고, 로그가 남아서 장애 뒤 다시 읽어 복구할 수 있고, 주문번호를 키로 쓰면 같은 주문의 결제 완료와 환불 순서가 지켜지기 때문입니다.
> 다만 Kafka 를 쓰면 이벤트가 중복으로 오거나, DB 커밋과 발행이 어긋나거나, 순서가 뒤집히는 문제가 생깁니다. 저희는 발행을 Transactional Outbox 로 DB 커밋과 묶었고, 소비는 Inbox 테이블과 유니크 제약으로 멱등하게 만들었고, 순서는 파티션 키와 릴레이의 키 단위 발행으로 지켰습니다.

**이어질 질문** — 체인 B(Outbox), 체인 D(DLT 와 순서).

#### 답변 2 — DB 커밋 후 오프셋 커밋 전에 죽으면?

> 그 경우 해당 오프셋이 아직 커밋되지 않았기 때문에, 컨슈머가 다시 뜨거나 리밸런싱으로 다른 컨슈머가 파티션을 맡으면 같은 메시지를 다시 받습니다. DB 작업은 이미 성공했으니 아무 장치 없이 다시 처리하면 라이선스가 두 번 지급될 수 있습니다.
> 그래서 컨슈머 쪽에 멱등성이 필요합니다. 저희는 처리할 때 이벤트 ID 와 컨슈머 그룹을 `processed_event` 테이블에 결과와 같은 트랜잭션으로 기록하고, 다시 온 메시지는 그 기록을 보고 건너뛴 뒤 오프셋만 커밋합니다. 돈이 걸린 곳은 `(주문번호, 상품)` 같은 유니크 제약을 한 겹 더 둡니다.
> DB 커밋과 오프셋 커밋은 서로 다른 시스템이라 원자적으로 묶을 수 없어서, 이 재전달은 막을 대상이 아니라 흡수할 대상이라고 봤습니다. 실제로 커밋 직후 재배달된 레코드의 재처리가 실패하면서 이미 지급된 주문에 환불이 시작될 뻔한 적이 있어서, 보상 전에 지급 여부를 다시 확인하는 단계도 넣었습니다.

#### 답변 3 — 그럼 exactly-once 아닌가요?

> 정확히 말하면 전달은 at-least-once 이고, 반영이 컨슈머 그룹마다 한 번이 되도록 만든 것입니다. Kafka 의 exactly-once 는 트랜잭션으로 Kafka 안의 쓰기와 오프셋 커밋을 원자적으로 묶는 기능이라, Kafka 에서 읽어 DB 에 쓰는 이 구조에는 그대로 적용되지 않습니다. DB 커밋과 Kafka 커밋 사이에는 틈이 남습니다.
> 그래서 "결과를 한 번만 반영"하는 성질을 Inbox 로 만들었습니다. 이게 깨지는 조건도 있습니다. Inbox 기록이 지워지면 다시 반영될 수 있고, 같은 사건이 다른 이벤트 ID 로 두 번 발행되면 Inbox 가 거르지 못해서 도메인 유니크 제약에 기댑니다. 그리고 순서가 뒤집혀 들어오는 경우는 멱등성으로 해결되지 않습니다 — 각각 한 번씩 반영되지만 결과가 틀릴 수 있습니다.

#### 답변 4 — Outbox 를 왜 썼고 무엇이 어려웠나요?

> 결제 승인과 "결제 완료" 이벤트 발행을 원자적으로 해야 했는데, DB 트랜잭션 안에서 Kafka 에 보내면 롤백돼도 이벤트가 나가고, 커밋 후에 보내면 그 사이에 죽을 때 이벤트가 사라집니다. 그래서 이벤트를 결제와 같은 트랜잭션으로 outbox 테이블에 적고, 릴레이가 읽어 발행하게 했습니다.
> 어려웠던 건 세 가지입니다. 첫째, 발행 실패한 이벤트를 같은 주문의 뒤 이벤트가 추월해서 환불된 주문에 라이선스가 남을 수 있었습니다. 주문번호 단위로 앞 이벤트가 성공해야 뒤를 보내게 바꿨습니다. 둘째, 발행을 릴레이 스레드가 하다 보니 분산 추적이 끊겨서, 적재 시점의 추적 컨텍스트를 이벤트와 함께 저장했다가 발행 때 되살렸습니다.
> 셋째는 최근에 찾은 건데, 릴레이의 `FOR UPDATE SKIP LOCKED` 조회가 REPEATABLE READ 에서 갭 락을 잡고 Kafka 응답을 기다리는 동안 주문의 outbox INSERT 를 기다리게 하고 있었습니다. 격리 수준만 바꾼 A/B 로 락 대기 400회가 0회가 되고 p95 가 5ms 줄어드는 걸 확인했고, 브로커 장애 때는 이게 API 실패로 번질 수 있어서 릴레이 트랜잭션 분리를 개선안으로 두고 있습니다.

#### 답변 5 — 같은 메시지가 두 번 오면 안전한가요?

> 네, 중복에는 안전합니다. 컨슈머가 처리할 때 `(이벤트 ID, 컨슈머 그룹)` 을 결과와 같은 트랜잭션에 기록하고 이미 있으면 건너뜁니다. 동시에 두 번 들어오면 둘 다 "없음"을 볼 수 있는데, 유니크 인덱스가 두 번째 INSERT 를 첫 번째 커밋까지 기다리게 했다가 거절하고, 재시도에서 건너뜁니다. MySQL 로 재현해 확인했습니다.
> 그룹을 키에 넣은 이유는 같은 결제 이벤트를 주문·라이선스·정산이 각자 처리해야 하기 때문입니다. 금전이 걸린 곳은 원장 유니크 제약을 한 겹 더 둬서, 같은 사건이 다른 이벤트 ID 로 와도 막습니다. 검색 색인이나 권한 사본처럼 덮어써도 되는 곳은 문서 ID 고정 upsert 로 테이블 없이 멱등하게 했습니다.
> 다만 멱등성은 중복을 막을 뿐 순서를 지키지는 않아서, 옛 이벤트가 늦게 오는 경우는 따로 대비해야 합니다.

#### 답변 6 — 순서는 어떻게 보장하나요?

> Kafka 는 파티션 안의 순서만 지키니까, 순서가 필요한 단위를 키로 정했습니다. 주문 이벤트는 주문번호, 상품 이벤트는 상품코드입니다. 그리고 세 층을 각각 막았습니다. 프로듀서는 멱등 프로듀서로 재시도 역전을 막고, 발행하는 릴레이는 같은 키의 앞 이벤트가 성공해야 뒤를 보내고, 컨슈머는 받은 스레드에서 순서대로 처리하게 하고 비동기 처리와 논블로킹 재시도를 ArchUnit 으로 금지했습니다. 처리량은 파티션 수까지 `concurrency` 를 올리면 순서를 해치지 않습니다.
> 순서가 풀리는 지점도 알고 있습니다. 재시도가 끝나 DLT 로 뺀 메시지나 DEAD 가 된 발행을 나중에 되돌리면, 같은 키의 뒤 메시지보다 늦게 처리됩니다. 그래서 재투입 전 확인 절차가 필요하고, 구조적으로는 키 단위 보류를 검토하고 있습니다. 파티션을 늘리는 것도 키 매핑이 바뀌어서 발행을 멈추고 비운 뒤에 합니다.

#### 답변 7 — 격리 수준은 무엇을 쓰고, 무엇이 문제였나요?

> 따로 설정하지 않아 MySQL 기본값인 REPEATABLE READ 를 씁니다. 대부분의 경합은 결제 콜백의 `FOR UPDATE` 나 유니크 제약으로 정확성을 지키고 있어서 기본값으로 충분하다고 봤는데, 이번에 코드와 설정을 대조하고 MySQL 로 재현해 보니 두 군데가 문제였습니다.
> 하나는 주문 만료 스윕과 결제 확정이 둘 다 잠그지 않고 읽어 쓰는 구조라, 겹치면 에러 없이 EXPIRED 가 PAID 를 덮는 Lost Update 였습니다. MySQL 의 REPEATABLE READ 는 스냅샷으로 읽고 최신 행에 쓰기 때문에 이걸 막지 않습니다. 조건부 UPDATE 나 버전 컬럼으로 막을 수 있습니다.
> 다른 하나는 반대로 격리 수준이 너무 강해서 생긴 문제로, Outbox 릴레이의 잠금 조회가 넥스트키 락을 잡아 주문 INSERT 를 기다리게 했습니다. 격리 수준만 바꾼 부하 A/B 에서 락 대기가 400회에서 0회로, p95 가 26ms 에서 21ms 로 줄었습니다. 그래서 전역 설정을 바꾸기보다 경합 지점마다 필요한 수준을 고르는 게 맞다고 정리했습니다.

#### 답변 8 — 결제 콜백이 동시에 두 번 오면요?

> PG 콜백은 네트워크 재시도 때문에 같은 승인이 동시에 올 수 있습니다. 결제 행을 주문번호로 `SELECT … FOR UPDATE` 로 잠그고 읽어서, 두 번째 요청은 첫 번째가 커밋할 때까지 기다렸다가 이미 PAID 인 상태를 봅니다. 같은 멱등키면 이벤트를 재발행하지 않고 끝내고, 다른 키의 승인이 또 오면 연동 오류로 보고 예외를 냅니다.
> 멱등키에 유니크 제약을 거는 방식도 썼었는데, PG 가 만든 키가 재사용되면서 다른 주문의 승인을 삼키는 결함이 있어 주문 단위로 좁혔습니다. 비관적 락을 고른 건 같은 주문에만 몰리는 짧은 경합이라 대기 비용이 작고 재시도 로직이 필요 없어서입니다. 다만 동시에 부르는 테스트는 아직 없어서 보강할 부분입니다.

#### 답변 9 — Saga 보상은 언제 실행되나요?

> 결제 성공 뒤 라이선스 지급이 최종 실패하면 결제를 자동 환불하는 보상입니다. 조건을 세 단계로 좁혔습니다. 재시도가 모두 끝나야 하고, 그 실패가 DB 같은 저장소 장애가 아니어야 하고, 실제로 그 주문에 라이선스가 없어야 합니다.
> 처음에는 재시도가 끝나면 바로 환불했는데, 장애를 넣어 보니 DB 가 60초 끊긴 사이 정상 결제 8건이 환불됐습니다. 재시도 소진은 "지급할 수 없다"가 아니라 "답을 못 받았다"라서, 판단이 불확실하면 되돌리기 쉬운 보류(DLT)를 택했습니다. 같은 조건에서 환불은 0건이 됐고 재투입 후 1초 안에 전부 지급됐습니다.
> 대가로 자동 보상은 거의 돌지 않게 됐고(세어 본 license outbox 4,153건 중 보상 이벤트 0건) 보류가 주된 경로가 됐습니다. 그래서 보류된 사이 사용자가 환불하면 순서가 뒤집히는 위험이 새로 생겼고, 재투입 전 확인과 키 단위 보류를 개선안으로 정리했습니다.

#### 답변 10 — 현재 설계의 가장 큰 단점은 무엇인가요?

> 실패를 옆으로 빼는 경로에서 순서 보장이 풀린다는 점입니다. 파티션 키와 릴레이의 키 단위 발행으로 정상 흐름의 순서는 지키지만, 재시도가 끝난 메시지를 DLT 로 빼거나 발행을 포기한 이벤트를 나중에 되돌리면, 같은 주문의 뒤 이벤트가 먼저 처리됩니다. 결제 완료가 보류된 사이 환불이 먼저 처리되면 환불된 주문에 게임이 지급될 수 있습니다.
> 두 번째는 단일 인스턴스를 전제한 곳들입니다. 릴레이는 서비스당 한 대여야 순서가 지켜지는데 코드로 강제되지 않고, 요청이 없어도 커넥션을 110개 쥐고 있어 MySQL 기본 한도 151 의 73% 를 이미 쓰고 있습니다. 인스턴스를 늘리면 바로 막힙니다.
> 셋 다 원인과 개선안을 정리해 뒀고, 릴레이 락 문제는 A/B 로 효과까지 쟀습니다. 우선순위를 둔다면 금전 사고로 이어질 수 있는 첫 번째부터 막겠습니다.

---

## 12. 장애 상황 질문

"그 상황에서 무엇이 지키고, 무엇이 새는가"를 한 번에 말할 수 있어야 한다.

| 상황 | 무엇이 지키나 | 무엇이 새나 | 보이는 신호 | 대응 |
|---|---|---|---|---|
| **Kafka 브로커 10분 중단** | 이벤트는 outbox 에 PENDING 으로 남는다. 시도마다 최대 120초씩 기다리므로 10분이면 몇 번 실패하는 데서 끝나고 DEAD 까지 가지 않는다 **[코드상 추정]** | 릴레이 트랜잭션이 갭 락을 쥔 채 기다려 주문·결제의 outbox INSERT 가 락 대기에 걸리고, 50초를 넘기면 1205 로 실패한다(R1) | `OutboxBacklogGrowing`, `ConsumerStalled`, 쓰기 API 5xx, `Innodb_row_lock_waits` | 복구 후 릴레이가 적체를 비우고 컨슈머가 따라잡는다. 중복은 Inbox 가 흡수 |
| **Kafka 브로커 1시간 이상** | 10회 실패하면 DEAD(시도당 대기 때문에 30분 가까이 걸린다) → 알람 | DEAD 사이에 같은 키의 뒤 이벤트가 먼저 나가면 회수 시 순서 역전(R2) | `OutboxEventsAbandoned` | 원인 해소 → 같은 키의 뒤 이벤트가 이미 나갔는지 확인 → `requeue-all`(id 순) |
| **license DB 1분 장애** | 재시도 소진 → 저장소 장애로 판정 → **환불하지 않고 DLT 보류** | 그 사이 사용자 환불이 끼면 재투입 때 순서 역전(R2) | `MessagesDeadLettered`(critical) | 원인 해소 → 결제 상태 확인 → DLT 재투입. 실측: 환불 0, 보류 7, 재투입 1초 내 지급 **[확인됨 D-027]** |
| **license 컨슈머만 1시간 정지** | 결제는 계속된다. 재기동하면 커밋된 오프셋부터 **순서대로** 따라잡고 이중 지급하지 않는다 | 그동안 라이브러리·다운로드에 게임이 없다(결과적 일관성) | `ConsumerLagGrowing` · `ConsumerStalled`(kafka-exporter) | 재기동. 앱 랙 지표는 컨슈머와 함께 멈추므로 보지 않는다(D-026) **[확인됨 R-03]** |
| **order 컨슈머 1시간 정지** | 결제·지급·정산은 진행 | 주문이 만료 스윕에 EXPIRED 가 되고, 재기동 후 결제 확정이 CONFLICT → DLT(R3) | `ConsumerLagGrowing`, `MessagesDeadLettered` | 정책 결정 전까지 수동 보정. 개선은 R3 |
| **PG 환불 API 타임아웃** | CANCELING 으로 남고 스윕이 2→4→…→30분 간격으로 재개, PG 멱등이라 이중 환불 없음 | 사용자는 "환불했다는데 돈이 안 들어왔다"를 본다 | `RefundsStuckInCanceling`, 1시간 넘으면 `RefundsStuckBeyondBudget` | PG 상태 확인. 포기 상태가 없으므로 끝날 때까지 재시도 **[확인됨 StrandedRefundResumeTest]** |
| **결제 서비스 배포 중 콜백** | graceful shutdown(30초) < 컨테이너 종료 유예(40초) — 진행 중 요청은 완주 | 새 요청은 거부 → PG 재전송에 기댄다 | 배포 로그 | 설정 부등호는 테스트가 지킨다 **[확인됨 D-035, R-01]** |
| **DLT 급증** | 레코드는 7일 보존, 운영 API 로 조회·재투입 | 7일이 지나면 사라진다(R9), 재투입은 순서를 뒤집을 수 있다(R2) | `MessagesDeadLettered` | 조회(커밋 안 함)로 원인 파악 → 수정 → 결제 상태 확인 → 재투입 |
| **커넥션 풀 고갈** | 3초 뒤 빠른 실패 | HTTP 500, 컨슈머 재시도 → DLT, 릴레이도 트랜잭션을 못 열어 발행 정지 | `hikaricp_connections_pending` | 트랜잭션 안의 대기(R1·R4) 먼저 확인, 인스턴스 증설은 총량(R10) 확인 후 |
| **락 대기·데드락 급증** | InnoDB 가 데드락은 즉시 끊고, 대기는 50초에 끊는다 | 1205·1213 → HTTP 500 또는 컨슈머 재시도 | `Innodb_row_lock_waits`, `SHOW ENGINE INNODB STATUS` | 브로커 지연과 함께 오르면 R1. 릴레이 트랜잭션 격리 수준 조정 |
| **license 원장 테이블 유실** | Kafka 에 이벤트가 남아 있다(보존 기간 안) | Inbox 가 재처리를 "이미 처리"로 조용히 막는다 | 예외 없음 — 대사로만 드러난다 | 런북: Inbox 를 `event_type` 까지 좁혀 지우고 오프셋 리셋, 대사로 판정 **[확인됨 D-030]** |
| **MySQL 인스턴스 다운** | store·download 의 읽기(ES·Mongo)는 계속 | DB 를 쓰는 7개 서비스의 쓰기 전부 실패, 컨슈머는 재시도 후 DLT 로 대량 보류, 릴레이 정지 **[코드상 추정]** | `ServiceDown`, 5xx, `MessagesDeadLettered` 급증 | 복구 후 DLT 대량 재투입 전에 **키 순서와 결제 상태부터 대사** — 대량 재투입은 R2 의 창을 가장 크게 연다 |

---

## 13. 설계 변경 질문

### 13.1 트래픽이 10배가 되면

지금 측정된 한계선부터 말한다. 추측이 아니라 숫자에서 출발하는 것이 답의 절반이다.

| 지점 | 지금 | 10배에서 | 먼저 할 일 |
|---|---|---|---|
| 컨슈머 처리량 | payment 141 events/s (`concurrency` 1), 3이면 364 (perf-tuning.md 4절) | **가장 먼저 막힌다.** 파티션 3이 병렬도의 천장 | `concurrency` 3 → 파티션 증설(발행 정지·배수 후) → 한 건 처리 비용 줄이기 |
| 릴레이 처리량 | 948.7 events/s (서비스당 1대) | 아래 132.5 RPS 의 10배(초당 주문 1,300건 안팎)면 order 릴레이 하나가 주문 이벤트만으로 넘친다 | `batch-size` 400 · 회차당 10배치(1,307~1,316 events/s 측정, perf-tuning.md) → 1대 제약 해소(키 해시 배정 또는 CDC) |
| 쓰기 경로 락 경합 | 60 RPS 에서 주문의 7% 가 락 대기, p95 +4.7ms | 동시 INSERT 가 늘어 더 커질 것으로 본다 **[추정]**. 릴레이 유무 계단식 비교는 p95 466ms 대 84ms 였지만 그중 락의 몫은 재지 않았다 | 릴레이 READ COMMITTED, 트랜잭션 분리 (R1) |
| API 한계선 | 계단식 20→400 RPS 에서 평균 132.5 RPS 를 실패 0% 로 소화, p95 466ms (릴레이 ON, 원격 전체 스택, performance.md 9-2) | 인스턴스 증설 필요 | 아래 13.2 선행 조건부터 |
| DB 커넥션 | 한 대씩일 때 110 / 151 | 인스턴스 증설과 동시에 초과 | 풀 재산정(측정상 동시 사용 5), 커넥션 프록시 (R10) |
| DB 인스턴스 | MySQL 1대에 7개 스키마 | 한 서비스의 부하가 전부에 번진다 | 서비스별 인스턴스 분리, 읽기 복제본, outbox·inbox 정리 (R8) |
| 브로커 | 1대, 복제 계수 1 | 가용성·내구성 모두 부족 | 3대, 복제 계수 3, `min.insync.replicas=2` (R7) |

**답의 구조** — "소비가 먼저 막히고, 발행은 1대 제약에, 쓰기는 락 경합에, 확장은 커넥션 총량에 막힙니다. 순서는 설정 한 줄로 되는 `concurrency` → 락 → 1대 제약 → 인프라 분리이고, 각 단계는 측정으로 확인한 뒤 넘어갑니다."

### 13.2 서비스를 세 대로 늘리면

| 확인할 것 | 지금 상태 | 해야 할 일 |
|---|---|---|
| Outbox 릴레이 | 모든 인스턴스에서 돈다 — 순서 보장 무효 (R13) | 리더 선출·키 해시 배정·CDC 중 하나 |
| 스케줄러 | 환불 재개·주문 만료·월 정산은 ShedLock 으로 단일 실행 [확인됨] | 그대로 |
| 재색인 중복 기동 가드 | `AtomicBoolean` — 인스턴스 안에서만 유효 (code-notes 가 인정) | 분산 락 또는 운영 절차 |
| 커넥션 총량 | 110 × 3 = 330 > 151 | 풀 축소 또는 프록시 |
| 컨슈머 | 파티션 3이라 서비스당 컨슈머 3개가 상한. Eager 리밸런싱 | Cooperative 배정, static membership |
| 캐시 무효화 | Redis 공유라 인스턴스 간 일관 [확인됨] | 그대로 |

### 13.3 MySQL 을 PostgreSQL 로 바꾸면

| 주제 | MySQL (지금) | PostgreSQL | 이 코드에 미치는 영향 |
|---|---|---|---|
| 기본 격리 수준 | REPEATABLE READ | READ COMMITTED | 스냅샷 시점이 문장마다로 바뀐다 |
| REPEATABLE READ 의 Lost Update | 조용히 덮는다 | 직렬화 오류로 거절 | 만료 스윕 경합이 **예외로 드러난다** — 재시도 설계가 필요 |
| 범위 잠금 | 넥스트키·갭 락 | 갭 락이 없다(SERIALIZABLE 은 막지 않는 조건 기반 추적) | 릴레이 조회가 INSERT 를 막지 않는다 — R1 의 락 대기는 사라진다. Kafka 를 기다리는 긴 트랜잭션은 남고, PostgreSQL 에서는 그것이 VACUUM 을 늦춘다 |
| `FOR UPDATE SKIP LOCKED` | 지원 | 지원 | 릴레이 쿼리는 그대로 쓸 수 있다 |
| CDC | binlog (Debezium MySQL) | WAL 논리 복제 (Debezium PostgreSQL) | 선택지는 같다 |
| JSON 컬럼 | `JSON` | `jsonb` | 엔티티 컨버터 확인 |

### 13.4 Outbox 폴링을 CDC 로 바꾸면

[6.3](#63-transactional-outbox) 의 폴링 릴레이 대 CDC 비교표로 답한다. 이 저장소 기준으로는 순서(커밋 순서), 릴레이 1대 제약(R13), 갭 락 경합(R1)이 함께 풀리고, Kafka Connect 운영을 떠안는다.

### 13.5 Kafka 트랜잭션을 도입하면

| 해결되는 것 | 해결되지 않는 것 |
|---|---|
| DLT 발행과 소비 오프셋 커밋을 원자적으로 묶기 | 릴레이의 "ack 받고 SENT 커밋 전 죽음" 중복 — SENT 는 DB 에 있다 |
| 여러 토픽에 동시에 쓰는 처리의 원자성 (지금은 없다) | 컨슈머의 DB 반영과 오프셋의 원자성 — DB 가 Kafka 트랜잭션에 참여하지 않는다 |

이 시스템은 Kafka 에서 읽어 Kafka 로 쓰는 처리가 거의 없어서 **얻는 것이 작다.** 트랜잭션 코디네이터와 `read_committed` 지연이라는 비용만 늘어난다.

### 13.6 순서를 더 강하게 지키라는 요구가 오면

[R2](#r2-dlt-재투입과-dead-회수가-같은-키의-순서를-뒤집는다) 의 개선안 셋(절차 · 소비 측 보류 기록 · 키 단위 파킹)을 먼저 말하고, 이벤트에 버전을 실어 늦은 이벤트를 버리는 방식(event-ordering.md C-2)을 더한다.

### 13.7 "결제 → 지급을 그냥 동기 호출로 하자"는 제안이 오면

동기로 바꾸면 결제 응답 안에서 지급까지 확정되어 사용자는 즉시 게임을 받는다. 대신 license 장애가 결제 장애가 되고, 결제 트랜잭션이 license 응답을 기다리며 커넥션과 락을 쥔다 — 외부 호출을 트랜잭션에 넣는 것과 같은 문제다.
지금 구조에서 지급까지의 종단 지연은 폴링 주기가 바닥이라 p95 0.56초이고(perf-tuning.md 3절), 그 지연을 줄이는 수단(적응형 폴링, CDC)이 있으므로 **결합을 늘려 지연을 사는 선택은 이득이 작다**고 답한다.

---

## 14. 내가 반드시 암기/이해해야 하는 핵심 개념

카드 한 장을 30초 안에 말할 수 있으면 된다. **"흔한 오답"** 은 면접에서 감점되는 표현이다.

| # | 개념 | 한 문장 | 이 프로젝트에서 | 흔한 오답 |
|---|---|---|---|---|
| 1 | 트랜잭션 경계 | 트랜잭션은 커넥션과 락을 쥐는 범위이고, 되돌릴 수 없는 외부 호출은 그 밖에 둔다 | `core.service` 만 트랜잭션, 파사드가 외부 호출을 트랜잭션 사이에 둔다. 예외 두 곳(R4)과 릴레이(R1) | "`@Transactional` 을 붙이면 안전하다" |
| 2 | DB 커밋 ≠ 오프셋 커밋 | 서로 다른 시스템의 커밋이라 원자적이지 않고, 그 틈이 재전달을 만든다 | DB 커밋 → 리스너 리턴 → `commitSync`. 틈은 Inbox 가 흡수 | "오프셋을 처리 후에 커밋하면 중복이 없다" |
| 3 | Exactly-once 의 범위 | Kafka 의 exactly-once 는 Kafka 안에서만이고, DB 까지는 멱등으로 "한 번 반영"을 만든다 | 발행·소비 at-least-once + 그룹별 Inbox | "Kafka exactly-once 옵션을 켰으니 DB 도 한 번만 반영된다" |
| 4 | Dual Write · Outbox | 두 저장소에 원자적으로 쓸 수 없으니 "발행해야 한다"를 같은 커밋에 남긴다 | `OutboxRecorder`(`MANDATORY`) + 폴링 릴레이 | "커밋 후 바로 발행하면 된다" |
| 5 | 멱등성 두 겹 | Inbox 는 같은 메시지를, 도메인 유니크는 같은 결과를 막는다. 멱등은 순서를 지키지 않는다 | `processed_event` + `uk_license_order_product` 등. R5 가 두 겹이 필요한 실례 | "Inbox 가 있으니 유니크는 필요 없다", "upsert 라 순서와 무관하다" |
| 6 | 파티션 키와 순서 | 순서는 파티션 안에서만이고, 옆으로 뺐다 되돌린 메시지는 뒤로 간다 | 주문번호 키 + 키 웨이브. DLT·DEAD 회수에서 역전(R2) | "Kafka 는 순서를 보장한다", "DLT 에서도 순서가 유지된다" |
| 7 | MySQL REPEATABLE READ | 읽기는 스냅샷, 쓰기와 잠금 읽기는 최신 커밋 — 그래서 Lost Update 를 막지 않는다 | 만료 스윕 vs 결제 확정(실험 4, R3) | "RR 이면 동시성 문제가 없다" |
| 8 | 락은 인덱스에 걸린다 | 넥스트키·갭 락이 "아직 없는 행"의 INSERT 를 막는다 | 릴레이의 `SKIP LOCKED` 가 주문 INSERT 를 기다리게 했다 — A/B 로 락 대기 400 → 0 (R1) | "`SKIP LOCKED` 는 아무도 막지 않는다" |
| 9 | 블로킹 재시도와 리밸런싱 | 재시도는 seek 이고 그동안 파티션이 멈추며, poll 한도를 넘기면 리밸런싱이 중복을 만든다 | 1·2·4초 재시도, Eager(range) 배정, D-037 | "리스너에서 예외를 잡고 로그를 남기면 된다" |
| 10 | Saga 보상의 조건 | 보상은 "실패했다"가 아니라 "실패가 확정됐다"를 보고, 판단할 수 없으면 보류한다 | 저장소 장애는 DLT 보류, 이미 지급이면 보류(D-027, D-028) | "재시도가 끝나면 보상하면 된다" |

---

## 15. 추가 학습이 필요한 부분

### 15.1 이 문서가 확인하지 못한 것

면접에서 "확인해 봤나요?"를 받으면 **여기 적힌 것은 "아직"이라고 답한다.**

| 항목 | 왜 못 했나 | 확인하는 법 |
|---|---|---|
| R1 의 브로커 장애 시 영향(쓰기 API 1205) | 브로커를 멈추는 장애 주입을 하지 않았다 | 부하 중 `docker pause stove-kafka` 로 주문 생성 실패율·`Innodb_row_lock_waits` 를 잰다 |
| R1 의 폴링 1000ms 시절 몫(9-3 의 19ms 중 얼마) | 옛 설정을 재현하지 않았다 | 같은 A/B 를 `poll-interval-ms=1000` 에서 반복 |
| R2 (DLT 재투입 순서 역전) | 재현 테스트가 없다 | `DeadLetterReplayRecoveryTest` 에 "보류 → 환불 → 재투입" 단계를 넣는다 |
| R3 (만료 스윕 경합)의 애플리케이션 수준 재현 | SQL 로만 재현했다 | 스윕과 `confirmPaid` 를 배리어로 겹치는 통합 테스트 |
| 결제 콜백 동시성 | 기존 테스트가 순차다 | `CyclicBarrier` 로 두 콜백을 겹쳐 `PaymentCompleted` 가 1건인지 본다 |
| Hibernate 의 실제 SQL 순서 (4.2 의 INSERT → UPDATE) | 추론이다 | `spring.jpa.show-sql` 또는 datasource-proxy 로 로그 |
| 실 PG·게임위·세금계산서의 멱등 계약 | 전부 스텁이다 | 실연동 문서와 샌드박스 |
| 운영 환경의 MySQL·Kafka 설정 | 로컬·원격 개발 스택만 봤다 | 운영 파라미터 그룹, 브로커 설정 |
| R12 의 정산 귀속 기준 | 회계 정책이다 | 정책 결정 |

### 15.2 더 깊이 볼 자료

| 주제 | 자료 | 이 문서의 어디와 이어지나 |
|---|---|---|
| InnoDB 락 | MySQL 8.0 Reference Manual — *InnoDB Locking*, *Locks Set by Different SQL Statements in InnoDB* (https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html) | 4.5, R1 |
| 격리 수준·MVCC | MySQL 8.0 Reference Manual — *Transaction Isolation Levels*, *Consistent Nonlocking Reads*, *Locking Reads* | 4.4, 4.6 |
| 격리 수준 이론 | Berenson et al., *A Critique of ANSI SQL Isolation Levels* (1995) — Lost Update·Write Skew 의 정의 | 4.4 |
| Kafka 전달 보장 | Apache Kafka Documentation — *Design: Message Delivery Semantics* (https://kafka.apache.org/documentation/#semantics), KIP-98(Exactly Once) | 5.12 |
| 리밸런싱 | KIP-429(Cooperative), KIP-345(Static Membership), KIP-848(새 컨슈머 프로토콜) | 5.8 |
| Spring Kafka 에러 처리 | Spring for Apache Kafka Reference — *Handling Exceptions* (`DefaultErrorHandler`, `DeadLetterPublishingRecoverer`) | 5.10, 5.11 |
| Outbox · CDC | Debezium Documentation — *Outbox Event Router* | 6.3 |
| 커넥션 풀 | HikariCP Wiki — *About Pool Sizing* (https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) | 4.8 |
| 전체 그림 | Martin Kleppmann, *Designing Data-Intensive Applications* 7장(트랜잭션)·11장(스트림 처리) | 4장·5장·6장 |

### 15.3 이 저장소에서 더 읽을 것

| 순서 | 문서 | 이 문서를 읽은 뒤 무엇을 얻나 |
|---|---|---|
| 1 | [event-ordering.md](event-ordering.md) | 순서 세 층과 해법 카탈로그 — 5.9 와 R2 의 원본 |
| 2 | [kafka-consumer-retry.md](kafka-consumer-retry.md) | 재시도가 예외 전파에 기대는 이유를 스프링 내부 코드로 |
| 3 | [defects.md](defects.md) D-002 · D-013 · D-014 · D-027 · D-028 · D-030 · D-037 | 이 문서가 인용한 실제 사고와 재현 테스트 |
| 4 | [code-notes.md](code-notes.md) payment · common 절 | 클래스마다 "왜 이 모양인가" |
| 5 | [measuring.md](measuring.md) | 측정 규칙 — R1 A/B 가 따른 절차의 출처 |
