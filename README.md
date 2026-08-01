# STOVE Platform — 이벤트 기반 게임 스토어 백엔드

스마일게이트 STOVE 스토어 서버 포지션을 겨냥한 **Java 21 + Spring Boot 3.5 마이크로서비스 모노레포**.
크리에이터 입점부터 판매·지급·정산까지 **9개 서비스**를 EDA 로 연결하고,
**Saga · Transactional Outbox · 멱등성 · 검증 게이트**를 실제 동작하는 코드로 구현한다.

---

## 1. 구성

```
stove/
├── apps/                          # 각 서비스 = 독립 실행 모듈 + 독립 컨테이너
│   ├── gateway/            :8080  Spring Cloud Gateway (라우팅 + 내부 API 차단)
│   │
│   │   # 트랙 A. 크리에이터 (입점~노출)
│   ├── studio/             :8085  게임 프로젝트·빌드 등록, 심의 신청          MySQL
│   ├── review/             :8086  등급분류 심의 상태머신, 자체등급분류 분기    MySQL
│   ├── catalog/            :8081  상품 마스터, 노출 제어, 서버 측 가격 재계산  MySQL + Redis
│   │
│   │   # 트랙 B. 커머스 (구매~결제)
│   ├── store/              :8087  진열·검색·프로모션 (읽기 전용 CQRS)        Elasticsearch + Redis
│   ├── order/              :8082  주문 생성/취소, 금액 검증                  MySQL
│   ├── payment/            :8083  PG 연동, 콜백 금액 대조, 환불              MySQL
│   │
│   │   # 트랙 C. 이용/정산 (지급~배분)
│   ├── license/            :8084  라이선스/CD키 발급·회수 (멱등)             MySQL
│   ├── download/           :8088  패치 매니페스트, CDN 서명 URL              MongoDB
│   └── settlement/         :8089  매출 배분·수수료·세금계산서·환불 역산       MySQL + 배치
│
├── libs/
│   ├── common-core         ApiResponse / ErrorCode / BusinessException
│   ├── common-web          GlobalExceptionHandler, CorrelationIdFilter (자동 구성)
│   ├── common-event        서비스 간 계약: 이벤트 payload + 토픽 + Kafka 헤더 규약
│   ├── common-jpa          BaseTimeEntity, JPA Auditing, Flyway
│   └── common-messaging    Outbox(발행) + Inbox(멱등 수신) 인프라 (자동 구성)
│
├── infra/                  mysql init(스키마 7종), prometheus
├── docker-compose.yml      MySQL · Redis · Kafka(KRaft) · Elasticsearch · MongoDB · Kafka UI · Prometheus · Grafana
└── docker-compose.apps.yml 9개 서비스 + 게이트웨이 컨테이너 실행
```

**저장소 선택 근거** — 트랜잭션·정합성이 중요한 도메인은 MySQL,
스키마가 유동적인 패치 매니페스트는 MongoDB, 검색 트래픽은 Elasticsearch.
서비스마다 스키마를 분리(Database per Service)하고 Flyway 로만 스키마를 바꾼다(`ddl-auto: validate`).

## 2. 이벤트 흐름

```
[등록] studio    ──GameRegistered────▶ review     심의 접수(자체등급분류는 내부 심사 분기)
[승인] review    ──ReviewApproved────▶ catalog    상품 마스터 생성 + 노출 전환
                                     └▶ studio    프로젝트 상태 역전파
[반려] review    ──ReviewRejected────▶ studio     반려 사유 전달
[색인] catalog   ──ProductChanged────▶ store      검색 색인 동기화
                                     └▶ download  productCode ↔ productId 참조
[빌드] studio    ──BuildUploaded─────▶ download   패치 매니페스트 등록
[구매] order     ──OrderCreated──────▶ payment    결제 대기 생성
[결제] payment   ──PaymentCompleted──▶ license    라이선스 발급
                                     ├▶ order     주문 확정
                                     └▶ settlement 매출 집계(자체/입점 구분)
[지급] license   ──LicenseIssued─────▶ download   다운로드 권한 부여
[환불] payment   ──PaymentCancelled──▶ license    라이선스 회수
                                     ├▶ order     주문 취소
                                     └▶ settlement 환불 역산
[회수] license   ──LicenseRevoked────▶ download   다운로드 권한 회수
[보상] license   ──LicenseIssueFailed▶ payment    자동 환불 (Saga 보상 트랜잭션)
```

토픽은 애그리거트 단위(`stove.order.v1` 등), 메시지 키는 **주문번호/상품코드** — 같은 애그리거트의 순서가 보장된다.

## 3. 설계 과제와 해법

| 과제 | 해법 | 코드 |
|---|---|---|
| 이벤트 유실 (DB 커밋 ↔ Kafka 발행 원자성) | **Transactional Outbox** — 비즈니스 변경과 같은 트랜잭션에 이벤트 적재 후 폴링 릴레이가 발행 | `common-messaging/outbox` |
| 중복 수신 (재전송·리밸런싱) | **Inbox 멱등 가드** — `(event_id, consumer_group)` 유니크를 처리와 같은 트랜잭션에서 마킹 | `common-messaging/inbox` |
| 결제 성공 후 지급 실패 | **Saga 보상 트랜잭션** — `LicenseIssueFailed` → payment 자동 환불 | `license/…/PaymentEventListener` |
| 금액 위·변조 | **검증 게이트 4단계 분산 배치** (아래) | |
| 릴레이 다중화 시 중복 발행 | `SELECT … FOR UPDATE SKIP LOCKED` 로 배치 선점 | `OutboxEventRepository` |
| 상품 등록 우회 | 심의 승인 이벤트 없이는 상품이 생성되지 않는 파이프라인 | `review` 상태머신 → `catalog` |
| 읽기 트래픽 집중 | catalog(쓰기) / store(읽기) 분리 + Redis 캐시 2단 | `store`, `catalog/CacheConfig` |
| license 장애가 다운로드 장애로 전이 | 동기 호출 대신 **권한 사본**을 이벤트로 유지 | `download/Entitlement` |
| 정산 중복 집계(금전 사고) | Inbox + `(order_no, product_id, record_type)` 유니크 이중 방어 | `settlement_record` |
| 환불 시 정산 역산 | 자기 원장의 SALE 레코드를 부호 반전해 상계 — 다른 서비스에 되묻지 않음 | `SettlementService#recordRefund` |

### 검증 게이트 4단계

1. **주문 시점** — 클라이언트 금액을 신뢰하지 않고 catalog 가격으로 재계산 (`PlaceOrderService`)
2. **PG 사전등록** — 승인 전에 서버가 결제 금액을 PG 에 먼저 등록 (`Payment#prepare`)
3. **콜백 대조** — PG 승인 금액 ≠ 사전등록 금액이면 승인 거부 (`Payment#approve`)
4. **멱등키** — 중복 콜백은 상태 + `idempotency_key` 유니크 제약으로 흡수

### 멱등성 전략 (서비스 성격에 맞춰 다르게)

| 방식 | 적용 서비스 | 이유 |
|---|---|---|
| Inbox 테이블 + 도메인 유니크 | order, payment, license, settlement, studio, review, catalog | 상태 전이·금전 처리라 "정확히 한 번"이 필요 |
| 자연 멱등 (문서 ID 고정 upsert) | store, download | 색인/사본 갱신은 몇 번 적용해도 결과가 같음 → 추가 테이블 불필요 |

## 4. 실행

### 개발 환경 준비

작업 머신이 바뀌어도 절차가 같도록, 머신마다 다른 값은 리포에 적지 않고 **매번 계산한다.**
준비 부담이 적은 순서대로 두 가지 경로가 있고, 어느 쪽을 골라도 같은 빌드가 돈다.

#### A. Devcontainer (권장) — 머신에 Docker 만 있으면 된다

`.devcontainer/devcontainer.json` 이 JDK·Gradle·Docker 를 모두 정의한다.
VS Code 의 *Reopen in Container*, IntelliJ, `devcontainer` CLI 가 같은 파일을 읽는다.

```bash
devcontainer up --workspace-folder .
```

`docker-in-docker` 를 쓰므로 컨테이너가 자기 Docker 데몬을 갖는다 — Testcontainers 테스트와
`docker compose up` 이 호스트 환경을 가정하지 않고 그대로 돈다.
GitHub Codespaces 도 이 파일을 그대로 사용하므로, 브라우저만으로도 열린다.

#### B. 로컬 직접 실행

머신에 도구 두 개를 깔 수 있다면 이쪽이 가장 빠르다.

| 필요한 것 | 조달 방법 |
|---|---|
| JDK 21 (Gradle 런처용) | `.mise.toml` — `mise install` (또는 SDKMAN `.sdkmanrc`) |
| JDK 21 (컴파일 툴체인) | `settings.gradle` 의 foojay 리졸버가 자동으로 내려받음 |
| Docker 엔드포인트 | `.envrc` 가 활성 docker 컨텍스트에서 소켓 주소를 뽑아 `DOCKER_HOST` 로 export |

```bash
mise install      # JDK 조달
direnv allow      # .envrc 승인 (내용 확인했다는 서명)
./gradlew build
```

direnv 를 쓰지 않는다면 `DOCKER_HOST` 만 직접 잡아주면 된다.

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
```

> `~/.testcontainers.properties` 에 `docker.host` 를 적어두는 방식은 쓰지 않는다.
> 절대경로라 다른 머신에서 없는 소켓을 가리키며 실패한다 — 설정하지 않은 것보다 나쁘다.

### 실행

```bash
# 1) 인프라
docker compose up -d

# 2) 빌드 & 테스트
./gradlew build

# 3-a) 로컬 실행 (서비스별 터미널)
./gradlew :apps:catalog:bootRun     # 8081, 이하 동일

# 3-b) 컨테이너 실행 (Dockerfile 사용)
./gradlew build -x test
docker compose -f docker-compose.apps.yml up -d --build
```

| 도구 | 주소 |
|---|---|
| Kafka UI | http://localhost:8090 |
| Elasticsearch | http://localhost:9200 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (anonymous) |
| Actuator | `http://localhost:808X/actuator/health`, `/actuator/prometheus` |

## 5. 전 구간 시나리오 (curl)

```bash
# ── 트랙 A: 등록 → 심의 → 노출 ────────────────────────────────
curl -s -X POST localhost:8085/api/v1/studio/games -H 'Content-Type: application/json' \
  -d '{"productCode":"GAME-INDIE-003","title":"픽셀 던전 크롤러","sellerId":1001,"price":18000,"selfRated":true}'
curl -s -X POST localhost:8085/api/v1/studio/games/1/submit -H 'X-Seller-Id: 1001'   # → GameRegistered
curl -s localhost:8086/api/v1/reviews                                                # 자체등급분류 자동 승인
curl -s -X POST localhost:8081/api/v1/products/4/sale-open                           # 판매 시작 → ProductChanged
curl -s -G localhost:8087/api/v1/storefront/products --data-urlencode 'q=던전'        # 검색 색인 반영 확인

# 빌드 업로드 → 패치 매니페스트
curl -s -X POST localhost:8085/api/v1/studio/games/1/builds -H 'X-Seller-Id: 1001' \
  -H 'Content-Type: application/json' -d '{"version":"1.0.0","fileSize":1073741824,"checksum":"a1b2c3"}'

# ── 트랙 B: 주문 → 결제 ───────────────────────────────────────
ORDER=$(curl -s -X POST localhost:8082/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"memberId":7,"items":[{"productId":1,"quantity":1},{"productId":4,"quantity":1}],"expectedAmount":57000}' \
  | jq -r '.data.orderNo')

curl -s -X POST localhost:8083/api/v1/payments/$ORDER/prepare \
  -H 'Content-Type: application/json' -d '{"method":"STOVE_CASH"}'
curl -s -X POST localhost:8083/api/v1/payments/callback -H 'Content-Type: application/json' \
  -d "{\"orderNo\":\"$ORDER\",\"pgTxId\":\"PG-TX-77\",\"paidAmount\":57000,\"idempotencyKey\":\"IDEM-77\"}"

# ── 트랙 C: 지급 → 다운로드 → 정산 ─────────────────────────────
curl -s localhost:8084/api/v1/library -H 'X-Member-Id: 7'                     # 라이선스 지급 확인
curl -s localhost:8088/api/v1/downloads/GAME-INDIE-003/ticket -H 'X-Member-Id: 7'   # CDN 서명 URL
curl -s localhost:8089/api/v1/settlements/orders/$ORDER                        # 자체 0% / 입점 30%
curl -s -X POST "localhost:8089/api/v1/settlements/close?month=2026-07"        # 월 마감 + 세금계산서

# ── 환불: 회수 + 역산 ─────────────────────────────────────────
curl -s -X POST "localhost:8083/api/v1/payments/$ORDER/cancel?reason=USER_REFUND"
```

**의도적으로 거절되는 요청** — 스켈레톤이 방어하는 지점

```bash
# 금액 위·변조 → 409 PRICE_MISMATCH
curl -s -X POST localhost:8082/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"memberId":7,"items":[{"productId":1,"quantity":1}],"expectedAmount":100}'

# 콜백 금액 불일치 → 409 PAYMENT_AMOUNT_MISMATCH / 중복 콜백 → 무시(이벤트 재발행 없음)
# 미보유 상품 다운로드 → 403 FORBIDDEN
curl -s localhost:8088/api/v1/downloads/GAME-INDIE-003/ticket -H 'X-Member-Id: 99'
# 심의 미승인 상품 판매 시작 → 409 CONFLICT
```

## 6. 외부 연동은 포트로 분리

실연동 대상은 인터페이스(포트)로 두고 로컬에서는 스텁을 쓴다 — 도메인 규칙이 외부 사정에 오염되지 않게.

| 포트 | 스텁 | 실제 대상 |
|---|---|---|
| `PgClient` | `MockPgClient` | PG사 / 스토브캐시 |
| `RatingBoardClient` | `MockRatingBoardClient` | 게임물관리위원회 접수 |
| `BuildStorage` | `MockBuildStorage` | S3 presigned upload |
| `CdnUrlSigner` | HMAC 서명(실동작) | CDN 서명 URL |
| `TaxInvoiceIssuer` | `MockTaxInvoiceIssuer` | 전자세금계산서 |

## 7. 다음 단계 후보

- Kafka DLT + 재처리 운영툴, Outbox `DEAD` 레코드 알람
- Testcontainers 기반 통합 테스트(등록→심의→구매→지급→정산 전 구간)
- 분산 추적(Micrometer Tracing + OTLP)으로 correlationId 를 traceId 로 승격
- 정산 배치 다중 인스턴스 대비 ShedLock, 대량 재색인 페이징/스로틀링
- 다국가·다통화(174개국 서비스) 대응: 통화별 반올림 규칙과 환율 스냅샷
