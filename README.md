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
├── common/                 경로가 곧 패키지다 — common/core → com.stove.common.core
│   ├── core                ApiResponse / ErrorCode / BusinessException
│   ├── web                 GlobalExceptionHandler, TraceIdResponseFilter, API 문서화 (자동 구성)
│   ├── event               서비스 간 계약: 이벤트 payload + 토픽 + Kafka 헤더 규약
│   ├── jpa                 BaseTimeEntity, JPA Auditing, Flyway
│   ├── kafka               컨슈머 재시도 정책 + DLT + DLT 운영 API (자동 구성)
│   ├── messaging           Outbox(발행) + Inbox(멱등 수신) + 추적 컨텍스트 전파 (자동 구성)
│   ├── archunit            패키지 구조·경계 규칙 (앱당 36개, 12개 모듈에 적용)
│   ├── test                인프라가 필요 없는 테스트 지원 (EventRecords, OpenApiSnapshot)
│   └── testcontainers      공용 컨테이너 (MySQL·Kafka·Redis·ES·MongoDB) — 통합 소스셋 전용
│
├── infra/                  mysql init(스키마 7종), prometheus, tempo, grafana 데이터소스
├── docker-compose.yml      MySQL · Redis · Kafka(KRaft) · Elasticsearch · MongoDB · Kafka UI · kafka-exporter · Prometheus · Tempo · Grafana
└── docker-compose.apps.yml 9개 서비스 + 게이트웨이 컨테이너 실행
```

문서는 전부 [docs/](docs/) 에 있다. 읽는 순서와 각 문서의 성격은 [docs/README.md](docs/README.md).

| 무엇이 궁금하면 | 문서 |
|---|---|
| 서비스별 API·상태머신·이벤트 목록 | [services.md](docs/services.md) |
| 구조를 이렇게 잡은 근거와 **버린 선택지** | [decisions.md](docs/decisions.md) |
| 테스트로 재현한 결함 36건 (살아 있는 것 1건) | [defects.md](docs/defects.md) |
| 리뷰 지적을 어떻게 판정했나 | [review-log.md](docs/review-log.md) |
| 무엇을 어느 층에서 검증하는가 | [testing.md](docs/testing.md) |
| 그 층·부하·스모크·e2e 를 점검하고 채울 순서 | [test-audit.md](docs/test-audit.md) |
| 아래 "같은 애그리거트의 순서 보장"이 어디서 지켜지나 | [event-ordering.md](docs/event-ordering.md) |
| 컨슈머 재시도가 예외 전파에 기대는 이유 | [kafka-consumer-retry.md](docs/kafka-consumer-retry.md) |
| Outbox 릴레이 처리량 측정과 개선, 받는 쪽 랙 측정 | [performance.md](docs/performance.md) |
| 그 숫자를 믿어도 되는지 어떻게 정했나 | [measuring.md](docs/measuring.md) |
| **부하 중에 DB 를 끊었을 때** 보상·재시도·가드·DLT 가 버티는가 | [chaos.md](docs/chaos.md) |
| **서버가 중단됐다 재기동하면** 밀린 일이 이어지는가 — 시나리오와 그것을 지키는 테스트 | [resilience-scenarios.md](docs/resilience-scenarios.md) |
| 원장이 유실됐을 때의 복구 절차 | [runbooks/](docs/runbooks/) |
| 원격 CI 환경을 세운 기록 | [remote-dev-plan.md](docs/remote-dev-plan.md) |
| 이벤트 인프라 학습 인계노트 | [handover.md](docs/handover.md) |

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
[실패] payment   ──PaymentFailed─────▶ order      주문 실패 종료 (돈이 움직인 적 없음)
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
| 이벤트 유실 (DB 커밋 ↔ Kafka 발행 원자성) | **Transactional Outbox** — 비즈니스 변경과 같은 트랜잭션에 이벤트 적재 후 폴링 릴레이가 발행 | `common/messaging/outbox` |
| 중복 수신 (재전송·리밸런싱) | **Inbox 멱등 가드** — `(event_id, consumer_group)` 유니크를 처리와 같은 트랜잭션에서 마킹 | `common/messaging/inbox` |
| 결제 성공 후 지급 실패 | **Saga 보상 트랜잭션** — `LicenseIssueFailed` → payment 자동 환불 | `license/…/PaymentEventListener` |
| 금액 위·변조 | **검증 게이트 4단계 분산 배치** (아래) | |
| 릴레이 다중화 시 중복 발행 | `SELECT … FOR UPDATE SKIP LOCKED` 로 배치 선점 | `OutboxEventRepository` |
| 상품 등록 우회 | 심의 승인 이벤트 없이는 상품이 생성되지 않는 파이프라인 | `review` 상태머신 → `catalog` |
| 읽기 트래픽 집중 | catalog(쓰기) / store(읽기) 분리 + Redis 캐시 2단 | `store`, `catalog/CacheConfig` |
| license 장애가 다운로드 장애로 전이 | 동기 호출 대신 **권한 사본**을 이벤트로 유지 — license 를 정지시키고 확인했다(다운로드 20/20, 9.3ms) | `download/Entitlement` |
| 정산 중복 집계(금전 사고) | Inbox + `(order_no, product_id, record_type)` 유니크 이중 방어 | `settlement_record` |
| 환불 시 정산 역산 | 자기 원장의 SALE 레코드를 부호 반전해 상계 — 다른 서비스에 되묻지 않음 | `SettlementRecordService#recordRefund` |
| 경계가 시간이 지나며 흐려짐 | **ArchUnit 으로 계층·의존 방향을 테스트로 강제** — 규칙을 처음 돌렸을 때 위반이 166건이었다 | `common/archunit` |
| 비동기 발행이 분산 추적을 끊음 | **추적 컨텍스트를 이벤트와 같은 트랜잭션에 저장**했다가 발행 시점에 복원 — 자동 계측은 릴레이 스케줄러의 컨텍스트를 싣는다 | `common/messaging/trace` |
| 재시도를 소진한 메시지가 사라짐 | **DLT + 재투입 API** — 파티션을 막지 않으면서 유실만 없앤다. Outbox `DEAD` 도 HTTP 로 회수한다 | `common/kafka`, `common/messaging/ops` |

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
준비 부담이 적은 순서대로 세 가지 경로가 있고, 어느 쪽을 골라도 같은 빌드가 돈다.

#### 어디서 돌릴지 고르는 기준 — **무엇이 컨테이너를 요구하는가**

A·B·C 는 전부 "이 머신의 Docker 를 어떻게 빌리는가"의 변주라 같은 메모리 천장 아래 있다.
전체 스택(인프라 10 + 앱 10)은 **6.1 GiB 이상**을 쓰므로 8GB 랩탑에서는 산술적으로 안 뜬다.
(6.1 GiB 는 Tempo 를 넣기 전 인프라 9종 기준의 실측치다 — 늘었을 뿐 줄지 않았으므로 결론은 같다.)
그래서 네 번째 경로(D)가 있다 ([decisions.md](docs/decisions.md) 15번).

**무엇이 컨테이너를 요구하는지는 소스셋이 말한다.** `src/test` 는 인프라를 못 띄운다 —
컨테이너 라이브러리가 클래스패스에 없어 임포트가 컴파일되지 않는다.
그래서 `./gradlew test` 는 Docker 없이 어디서나 돈다.

| 무엇을 | 어디서 | 명령 |
|---|---|---|
| 단위·어댑터·ArchUnit 945개 (Docker 불필요) | **로컬** | `./gradlew test` |
| 실 인프라 통합 241개 (Testcontainers) | 로컬 또는 **원격** | `./gradlew integrationTest` |
| 전체 스택 · 게이트 · 인수 · 성능 | **원격** | `./scripts/remote.sh …` |
| 커밋한 것의 최종 검증 | **원격 CI** | `git push` (또는 `gh workflow run ci.yml`) |

```bash
./scripts/remote.sh test                      # 전체 테스트
./scripts/remote.sh test :apps:order          # 모듈 하나 — 실패하면 요약만 낸다
./scripts/remote.sh stack up                  # 전체 스택 21개 (게이트까지 확인하고 끝난다)
./scripts/remote.sh gate                      # 배포 게이트 14건만 다시
./scripts/remote.sh e2e                       # 인수 42건 + 관측 4건 관통 확인
./scripts/remote.sh http GET catalog:8081/api/v1/products
./scripts/remote.sh logs catalog -n 100 -g 승인
./scripts/remote.sh status
```

**`remote.sh` 와 CI 는 역할이 다르다.** CI 는 push 해야 돌므로 "고쳤다 → 결과" 루프에
커밋이 끼어든다. `remote.sh` 는 rsync 로 작업본을 밀어넣어 **커밋 없이** 원격에서 돌린다.

첫 사용 전에 한 번만 (머신별 값이라 리포에 두지 않는다 — 10번):

```bash
git config stove.remote <ssh-별칭>     # ~/.ssh/config 의 Host 이름
```

요구 도구는 `bash`·`ssh`·`rsync` 뿐이다. 리포의 설치 요구는 여전히 0개다.

#### A. Devcontainer (권장) — 머신에 Docker 만 있으면 된다

`.devcontainer/devcontainer.json` 이 JDK·Gradle·Docker 를 모두 정의한다.
VS Code 의 *Reopen in Container*, IntelliJ, `devcontainer` CLI 가 같은 파일을 읽는다.

```bash
devcontainer up --workspace-folder .
```

`docker-in-docker` 를 쓰므로 컨테이너가 자기 Docker 데몬을 갖는다 — Testcontainers 테스트와
`docker compose up` 이 호스트 환경을 가정하지 않고 그대로 돈다.
GitHub Codespaces 도 이 파일을 그대로 사용하므로, 브라우저만으로도 열린다.

#### B. `scripts/dev.sh` — Docker 만 있고 아무것도 설치할 수 없을 때

빌려 쓰는 머신처럼 brew·node·JDK 설치가 여의치 않은 환경을 위한 진입점.
devcontainer 와 같은 베이스 이미지를 쓰되 **안쪽에 Docker 를 또 띄우지 않고 호스트 소켓을 빌린다** —
호스트 이미지 캐시를 그대로 쓰므로 첫 실행이 빠르다.

```bash
./scripts/dev.sh                  # 대화형 셸
./scripts/dev.sh ./gradlew build  # 명령 실행 후 종료
```

호스트가 Docker Desktop 이나 OrbStack 이라고 가정한다(활성 docker 컨텍스트에서 소켓을 찾는다).
그 가정을 피하고 싶으면 A 를 쓴다. 애플리케이션 스택 실행은 호스트에서 `docker compose` 로 한다.

#### C. 로컬 직접 실행

머신에 JDK 와 Docker 가 있다면 이쪽이 가장 빠르다. 리포가 요구하는 설치 도구는 없다.

| 필요한 것 | 조달 방법 |
|---|---|
| 컴파일 툴체인 JDK 21 | `settings.gradle` 의 foojay 리졸버. 없으면 내려받는다 |
| Gradle 런처 JVM | 아무 JDK 나. Gradle 8.14 는 17 이상이면 돈다 |
| Docker 엔드포인트 | macOS 는 아래 한 줄. Linux 는 기본 소켓이라 불필요 |

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
./gradlew build
```

`DOCKER_HOST` 는 Testcontainers 가 소켓을 찾는 경로다. macOS + OrbStack 에는
`/var/run/docker.sock` 이 없으므로 활성 컨텍스트에서 뽑아 넘긴다. 셸을 새로 열 때마다
치기 싫으면 `~/.zshrc` 나 direnv 같은 개인 도구에 둔다 — 머신 사정이므로 리포에 두지 않는다.

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
| **Swagger UI** | http://localhost:8080/swagger-ui.html — 9개 서비스를 드롭다운으로 전환 |
| Kafka UI | http://localhost:8090 |
| Elasticsearch | http://localhost:9200 |
| Prometheus | http://localhost:9090 |
| kafka-exporter | http://localhost:9308/metrics — **컨슈머 랙.** 앱 지표로는 판정할 수 없다([D-026](docs/defects.md#d-026)) |
| Tempo (추적 수집) | OTLP/HTTP `localhost:4318`, 조회 API `localhost:3200` |
| Grafana | http://localhost:3000 (anonymous) — Prometheus·Tempo 데이터소스가 미리 등록돼 있다 |
| Actuator | `http://localhost:808X/actuator/health`, `/actuator/prometheus` |

**주문 하나를 9개 서비스에 걸쳐 따라가려면** — Grafana → Explore → Tempo → Search.
응답 헤더 `X-Correlation-Id` 가 그 요청의 traceId 이므로 값을 그대로 넣으면 해당 트레이스로 바로 간다.

```bash
curl -si -X POST localhost:8082/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"memberId":7,"items":[{"productId":1,"quantity":1}],"expectedAmount":39000}' \
  | grep -i x-correlation-id
```

Kafka 를 건너는 구간까지 한 트레이스로 이어진다 — Outbox 가 발행을 다른 스레드로 미루므로
자동 계측만으로는 끊기고, 적재 시점에 붙잡은 컨텍스트를 릴레이가 되살려서 잇는다
([decisions.md](docs/decisions.md) 17번).

원격 전체 스택에서 확인한 결제 콜백 1건의 실제 스팬 트리 — **6개 서비스, Kafka 2회 통과**:

```
gateway   http post                                    ROOT
 └ payment  http post /api/v1/payments/callback          18ms
    ├ order       stove.payment.v1 receive              +527ms   ← Kafka 1홉
    ├ license     stove.payment.v1 receive              +527ms
    ├ settlement  stove.payment.v1 receive              +527ms
    └ (license 가 발행)
       ├ payment    stove.license.v1 receive            +759ms   ← Kafka 2홉
       └ download   stove.license.v1 receive            +759ms
```

세 컨슈머의 부모가 **릴레이 스케줄러가 아니라 원래 HTTP 요청 스팬**이라는 점이 요점이다.
`+527ms` 간격이 Outbox 폴링 지연이고, 그것이 그대로 눈에 보인다.

> 이 트레이스는 `poll-interval-ms: 1000` 이던 시절에 잡은 것이다. 2026-08-13 에 **200 으로 낮췄으므로**
> 지금 같은 경로를 잡으면 저 간격이 그만큼 줄어든다([perf-tuning.md](docs/perf-tuning.md) 3절).
> 요점인 스팬 트리의 **모양** — 부모가 릴레이가 아니라 HTTP 요청 스팬이라는 것 — 은 그대로다.

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
# 콜백은 result 로 승인/거절이 갈린다. 기본값이 없으므로 빠뜨리면 400 이다.
curl -s -X POST localhost:8083/api/v1/payments/callback -H 'Content-Type: application/json' \
  -d "{\"result\":\"APPROVED\",\"orderNo\":\"$ORDER\",\"pgTxId\":\"PG-TX-77\",\"paidAmount\":57000,\"idempotencyKey\":\"IDEM-77\"}"

# 승인 거절이면 결제가 FAILED 로 끝나고 PaymentFailed 가 주문을 실패 종료시킨다.
# (pgTxId 는 사전등록이 돌려준 값이어야 한다 — 거절에는 멱등키가 없어 이 값이 유일한 거래 식별자다)
# -d "{\"result\":\"DECLINED\",\"orderNo\":\"$ORDER\",\"pgTxId\":\"$PG_TX\",\"reasonCode\":\"REJECT_CARD_COMPANY\",\"reason\":\"카드사 거절\"}"

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

**실패한 메시지 되살리기** — 유실이 아니라 연기다

재시도를 소진한 메시지는 버려지지 않는다. 수신 실패는 `<원본토픽>.DLT` 로, 발행 실패는
Outbox `DEAD` 로 남고, 둘 다 HTTP 로 되살린다. **게이트웨이가 라우팅하지 않으므로 서비스 포트로
직접(내부망에서) 부른다.** 각 서비스의 Swagger UI 에도 그대로 뜬다.

```bash
# 발행을 포기한 이벤트 — 사유와 traceparent 가 같이 나온다
curl -s localhost:8083/api/v1/ops/outbox/dead | jq
curl -s -X POST localhost:8083/api/v1/ops/outbox/dead/EVT-77/requeue      # 한 건
curl -s -X POST localhost:8083/api/v1/ops/outbox/dead/requeue-all         # 원인이 하나였을 때

# 수신을 포기한 메시지 — 조회는 커밋하지 않으므로 몇 번을 봐도 대상이 그대로다
curl -s -G localhost:8089/api/v1/ops/dlt --data-urlencode 'topic=stove.payment.v1.DLT' | jq
curl -s -X POST "localhost:8089/api/v1/ops/dlt/replay?topic=stove.payment.v1.DLT"
```

> **원인을 먼저 고친다.** 고치지 않고 재투입하면 같은 실패를 반복해 DLT 로 돌아온다.
> 재투입은 중복 수신이지만 Inbox 멱등 가드가 흡수한다.
> `stove.outbox.dead` · `stove.kafka.dead-lettered` 에 Prometheus 알람이 걸려 있다
> (`infra/prometheus/alerts.yml`).
>
> **받는 쪽 적체에도 알람이 있다** — `ConsumerLagGrowing` · `ConsumerStalled`.
> 이쪽 지표만 출처가 앱이 아니라 `kafka-exporter` 다. 앱이 내는 랙 지표는
> 컨슈머가 멈추면 같이 멈춰서, 알람이 가장 필요한 상태에서 침묵한다([D-026](docs/defects.md#d-026)).

## 6. 외부 연동은 포트로 분리

실연동 대상은 인터페이스(포트)로 두고, 구현이 없는 것만 스텁을 쓴다 — 도메인 규칙이 외부 사정에 오염되지 않게.
**다섯 포트 중 둘은 실제 어댑터가 이미 있고, 설정으로 고른다.**

| 포트 | 스텁 | 실제 어댑터 | 고르는 법 |
|---|---|---|---|
| `BuildStorage` (studio) | `MockBuildStorage` | **`S3BuildStorage`** | `stove.storage.provider` = `mock`(기본) / `s3` |
| `DownloadUrlSigner` (download) | — | **`CdnUrlSigner`**(HMAC 실서명) · **`S3PresignedUrlSigner`** | `stove.download.url-strategy` = `cdn`(기본) / `s3` |
| `PgClient` (payment) | `MockPgClient` | 없음 — PG사 / 스토브캐시 | — |
| `RatingBoardClient` (review) | `MockRatingBoardClient` | 없음 — 게임물관리위원회 접수 | — |
| `TaxInvoiceIssuer` (settlement) | `MockTaxInvoiceIssuer` | 없음 — 전자세금계산서 | — |

스텁은 전부 `@Profile("!prod")` 라 운영에서 조용히 도는 일이 없고, **그 조건은 ArchUnit 이 검사한다**
(`스텁_어댑터는_격리한다`). 어느 것이 실동작이고 어느 것이 흉내인지는
[docs/services.md](docs/services.md) 의 "외부 연동 대역" 에 자세히 있다.

## 7. 다음 단계 후보

- ~~Kafka DLT + 재처리 운영툴, Outbox `DEAD` 레코드 알람~~ → **했다.**
  적용하다 store·download 에는 재시도 정책조차 없었다는 것이 드러나 컨슈머 정책을 `common:kafka` 로
  분리했다 — 이제 9개 서비스가 같은 실패 처리를 갖는다 ([decisions.md](docs/decisions.md) 19번)
- ~~전 구간 시나리오 관통(등록→심의→구매→지급→정산)~~ → **했고, CI 의 판정 조건이 됐다.**
  셸 스모크였던 것을 `:e2e` 모듈로 옮겼다 — 트랙 A~C·환불·결제 거절을 **게이트웨이 경유**로
  42건 관통하고, 거기에 관측 4건(트레이스 연결·적체 수렴·DLT·종단 지연)이 얹혀 main push 에서 돈다. 배포 게이트(컨테이너·인프라·라우팅 차단)는 성질이 달라
  `scripts/stack-wait.sh` 로 갈라져 `stack up` 에 붙었다
  ([test-audit.md](docs/test-audit.md) 4절, [decisions.md](docs/decisions.md) 21번)
- ~~분산 추적(Micrometer Tracing + OTLP)으로 correlationId 를 traceId 로 승격~~ → **했다.**
  Kafka 구간이 끊기던 원인은 헤더를 안 실어서만이 아니라 **Outbox 가 발행을 다른 스레드로 미루기
  때문**이었다 — 자동 계측은 `send()` 를 부른 스레드(릴레이 스케줄러)의 컨텍스트를 싣는다.
  적재 시점에 붙잡아 `outbox_event.trace_parent` 에 저장했다가 발행 때 되살린다
  ([decisions.md](docs/decisions.md) 17번)
- 다국가·다통화(174개국 서비스) 대응: 통화별 반올림 규칙과 환율 스냅샷
- 대량 재색인의 비동기화 — 지금은 페이지 단위 커밋 + 스로틀로 동기 실행이라,
  카탈로그가 10만 건을 넘으면 운영자의 HTTP 요청이 그만큼 오래 붙잡힌다
