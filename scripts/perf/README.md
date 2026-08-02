# 부하 테스트

Outbox 릴레이 처리량을 재고, 개선 전후를 같은 조건으로 비교하기 위한 스크립트다.
측정 결과와 분석은 [docs/performance.md](../../docs/performance.md) 에 있다.

## 왜 이걸 재는가

릴레이는 **9개 서비스가 공유하는 단일 병목**이다. 여기가 밀리면
결제 완료 → 라이선스 지급 → 정산까지 전 구간이 함께 밀린다.

그리고 개선 여지가 코드에 그대로 드러나 있다 —
`OutboxRelay.relay()` 가 배치 전체를 `send().get()` 으로 순차 대기한다.

## 준비

```bash
brew install k6

# 인프라 (측정에 필요한 3종만)
docker compose up -d mysql redis kafka

# 서비스 2종. 컨테이너 대신 호스트에서 띄운다 —
# Docker 메모리를 아끼고 재기동이 빠르다.
./gradlew :apps:catalog:bootRun    # 8081
./gradlew :apps:order:bootRun      # 8082
```

`order` 는 주문 생성 시 `catalog` 에 동기 HTTP 로 견적을 요청한다(검증 게이트 1단계).
이 홉이 쓰기 경로의 실제 모습이라 빼면 측정이 현실과 멀어진다.

## 실행

```bash
# 1. 경로 확인. 여기서 실패하면 아래 숫자는 의미 없다
k6 run scripts/perf/smoke.js

# 2. 본 측정 — 릴레이 지표를 같은 타임라인으로 함께 수집한다
./scripts/perf/collect-outbox.sh baseline-outbox.csv &
k6 run --summary-export=baseline-k6.json scripts/perf/order-throughput.js
kill %1

# 3. 지속 부하 — 적체가 해소되는지 확인
./scripts/perf/collect-outbox.sh soak-outbox.csv &
k6 run scripts/perf/order-soak.js
kill %1
```

## 시나리오

| 스크립트 | 부하 | 보는 것 |
|---|---|---|
| `smoke.js` | 1 VU, 20초 | 경로 생존 확인 |
| `order-throughput.js` | 20 → 400 RPS 계단식 | 처리량 한계선 |
| `order-soak.js` | 100 RPS 5분 | 적체 누적 여부 |

**도착률(arrival-rate) 기반**을 쓴다. VU 기반이면 응답이 느려질 때 유입도 같이 줄어
병목이 스스로 가려진다. 부하를 고정해야 적체가 드러난다.

주문 1건 = Outbox 이벤트 1건(`OrderCreated`) 이므로 **k6 의 RPS 가 곧 릴레이 유입량**이다.

## 지표

k6 는 HTTP 만 본다. 릴레이는 배경 스레드라 `collect-outbox.sh` 가 따로 수집한다.

| 지표 | 출처 | 의미 |
|---|---|---|
| `http_req_duration` | k6 | 주문 생성 응답시간 |
| `stove.outbox.published` | actuator | 발행 성공 누적 → 초당 처리량 |
| `stove.outbox.failed` / `.dead` | actuator | 실패·포기 건수 |
| `stove.outbox.pending` | actuator | **적체량. 우상향이면 유입 > 처리** |
| `stove.outbox.relay` | actuator | 릴레이 1회 소요시간 (p50/p95/p99) |

판정은 응답시간보다 **`pending` 의 기울기**를 먼저 본다.
응답이 빨라도 적체가 쌓이고 있으면 시간 문제일 뿐 반드시 터진다.

## 비교의 전제

기준선과 재측정은 **같은 조건**이어야 한다. 조건이 다르면 비교가 아니라
무관한 숫자 두 개다. 그래서 부하 형태를 스크립트에 고정했고,
환경 변수로는 주소만 바꾼다.

측정 전 데이터를 초기화한다.

```bash
docker compose exec mysql mysql -ustove -pstove1234 -e \
  "TRUNCATE stove_order.outbox_event; DELETE FROM stove_order.order_item; DELETE FROM stove_order.orders;"
```

워밍업 구간(첫 20초)은 JIT 컴파일과 커넥션 풀 확보에 쓰이므로
시나리오에 포함돼 있다. 결과를 읽을 때 그 구간은 빼고 본다.

## 환경 변수

| 변수 | 기본값 |
|---|---|
| `ORDER_URL` | `http://localhost:8082` |
| `CATALOG_URL` | `http://localhost:8081` |
| `ORDER_ACTUATOR` | `http://localhost:8082/actuator/prometheus` |
| `INTERVAL` | `1` (수집 주기, 초) |
