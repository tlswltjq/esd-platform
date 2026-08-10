# 부하 테스트

Outbox 릴레이 처리량을 재고, 개선 전후를 같은 조건으로 비교하기 위한 스크립트다.
측정 결과와 분석은 [docs/performance.md](../../docs/performance.md) 에 있다.

## 왜 이걸 재는가

릴레이는 **9개 서비스가 공유하는 단일 병목**이다. 여기가 밀리면
결제 완료 → 라이선스 지급 → 정산까지 전 구간이 함께 밀린다.

그리고 개선 여지가 코드에 그대로 드러나 있다 —
`OutboxRelay.relay()` 가 배치 전체를 `send().get()` 으로 순차 대기한다.

## 어디서 재는가 — 랩탑에서 잰 숫자는 쓸 수 없었다

> **이 절은 한 번 틀렸다.** 예전 절차는 인프라 3종만 띄우고 catalog·order 를 호스트에서
> `bootRun` 하는 것이었다. 그렇게 잰 HTTP 숫자는 [performance.md](../../docs/performance.md)
> **7장에서 스스로 무효 선언**됐다 — 호스트가 포화 상태였고,
> **릴레이를 완전히 꺼도 같은 숫자가 나왔다.** 포화 위에서 잰 값은
> 무엇을 바꾸든 "포화했다"만 알려준다.
>
> 유효한 비교를 얻은 것은 9장이고, 아래는 그 절차다. **7장 환경은 쓰지 않는다.**

| | 7장 (무효) | 9장 (유효) |
|---|---|---|
| 호스트 | macOS 8GB / Docker 4GB | OCI Ampere A1 — 4코어 23GB |
| 스택 | ES·Mongo·Grafana 제외, 서비스 2개 `bootRun` | 인프라 9 + 앱 10 **전부 컨테이너** |
| 스와핑 | `pageouts` 150만 | swap 0~1 MiB |
| k6 | 호스트 | 컨테이너(`stove_default` 네트워크) |

전체 스택은 6.1 GiB 이상을 쓰므로 8GB 랩탑에서는 산술적으로 안 뜬다.
그래서 측정은 원격에서 한다([decisions.md](../../docs/decisions.md) 15번).

## 준비

```bash
# 원격에 작업본을 밀어넣고 전체 스택을 띄운다
./scripts/remote.sh stack up
./scripts/remote.sh smoke        # 경로가 살아 있는지 먼저 — 여기서 깨지면 숫자는 의미 없다
```

이후 명령은 **원격에서** 돈다(`./scripts/remote.sh shell` 로 들어가거나 ssh).
CI compose 는 호스트 포트를 열지 않으므로(공개 IP + 인증 없는 ES·Grafana)
k6 와 수집기도 같은 네트워크의 컨테이너로 띄우고 **서비스 이름으로 부른다.**

```bash
export ORDER_URL=http://order:8082
export CATALOG_URL=http://catalog:8081
export ORDER_ACTUATOR=http://order:8082/actuator/prometheus
```

`order` 는 주문 생성 시 `catalog` 에 동기 HTTP 로 견적을 요청한다(검증 게이트 1단계).
이 홉이 쓰기 경로의 실제 모습이라 빼면 측정이 현실과 멀어진다.

## 실행

```bash
# 1. 경로 확인. 여기서 실패하면 아래 숫자는 의미 없다
docker run --rm --network stove_default -v "$PWD:/w" -w /w \
  -e ORDER_URL -e CATALOG_URL grafana/k6 run scripts/perf/smoke.js

# 2. 한계선 — 계단식 20→400 RPS. 포화 지점을 찾는 데만 쓴다
./scripts/perf/collect-outbox.sh limit-outbox.csv &
docker run --rm --network stove_default -v "$PWD:/w" -w /w \
  -e ORDER_URL grafana/k6 run scripts/perf/order-throughput.js
kill %1

# 3. 비교 — 관측된 한계선의 절반 이하 고정 부하에서만 판정한다
RATE=60 DURATION=60s ...  # order-soak.js 는 RATE/DURATION 을 환경변수로 받는다

# 4. 종단 지연 — 결제 승인에서 라이선스 지급까지. 3번과 **함께** 돌려야 의미가 크다
#    (릴레이가 포화된 상태에서 사용자 체감이 어떻게 되는가)
docker run --rm --network stove_default -v "$PWD:/w" -w /w \
  -e ORDER_URL -e PAYMENT_URL -e LICENSE_URL grafana/k6 run scripts/perf/payment-callback.js
```

**한계선과 비교를 나누는 것이 핵심이다.** 계단식은 포화로 들어가므로 한계 파악에만 쓰고,
구성 간 비교는 반드시 **포화 이전** 구간에서 한다. 9장은 한계선 132 RPS 를 확인한 뒤
60 RPS 고정으로 비교했다.

## 릴레이 유무 대조군

**두 조건이 실제로 달랐다는 증거가 없으면 비교가 아니다.**
7장이 무효였던 것을 알아챈 것도 대조군 덕분이었다.

```bash
docker compose -f docker-compose.apps.yml -f docker-compose.apps.ci.yml \
               -f scripts/perf/relay-off.override.yml up -d --force-recreate order
```

판정은 `stove_outbox_pending` 으로 한다 — 릴레이를 끈 조건에서 이 값이
**생성된 주문 수와 같아야** 한다(이벤트가 하나도 발행되지 않고 쌓였다는 뜻).
9장에서 OFF 는 5,398, ON 은 0 이었다.

## 측정 위생 — 지키지 않으면 숫자가 아니라 잡음이다

7장이 남긴 규칙이고, 9장에서 **알면서 한 번 어겼다** — 비교 도중 같은 호스트에서 CI 를
띄웠고 그 회차의 p95 가 42.6ms 대신 59.7ms 로 튀었다. 즉시 버리고 다시 쟀다.

- 측정 전 `./gradlew --stop`, 잔여 JVM 정리, 데이터 초기화
- 각 조건마다 **동일한 초기 상태**에서 시작 (회차마다 `orders`·`outbox_event` 를 비우고 order 재기동)
- **한 조건당 최소 2회** 반복해 재현성 확인 — 회차 간 편차가 조건 간 차이보다 작아야 유효하다
- **측정 중 호스트에 다른 부하를 올리지 않는다** (CI 포함)
```

## 시나리오

| 스크립트 | 부하 | 보는 것 |
|---|---|---|
| `smoke.js` | 1 VU, 20초 | 경로 생존 확인 |
| `order-throughput.js` | 20 → 400 RPS 계단식 | 처리량 한계선 |
| `order-soak.js` | `RATE` RPS `DURATION` 동안 (기본 100 / 5분) | 적체 누적 여부, **구성 간 비교** |
| `payment-callback.js` | 5 VU, 2분 | **종단 지연** — 결제 승인 → 라이선스 지급 |

앞의 셋은 **도착률(arrival-rate) 기반**이다. VU 기반이면 응답이 느려질 때 유입도 같이 줄어
병목이 스스로 가려진다. 부하를 고정해야 적체가 드러난다.

주문 1건 = Outbox 이벤트 1건(`OrderCreated`) 이므로 **k6 의 RPS 가 곧 릴레이 유입량**이다.

### `payment-callback.js` 만 VU 기반인 이유

이 시나리오가 재는 것은 처리량이 아니라 **한 건이 끝까지 가는 데 걸리는 시간**이다.
한 반복이 주문 → 대기 → 사전등록 → 승인 → 지급 확인까지 가고 마지막 폴링이 최대 60초라,
도착률 기반으로 두면 유입이 완료를 앞질러 VU 가 무한히 쌓인다.

**부하를 올리며 `e2e_fulfillment_latency` 가 무너지는 지점을 보는 것**이 사용법이다.
앞의 셋으로 릴레이를 포화시켜 두고 이것을 함께 돌리면, 포화가 사용자 체감으로 번지는 모습이 나온다.

`http_req_duration` 은 주문 API 응답까지고 `stove.outbox.pending` 은 적체의 대리 지표다 —
**둘 다 초록인데 종단 지연이 30초일 수 있다.** 결제 완료에서 지급까지 Kafka 홉이 두 번,
릴레이 폴링이 두 번 끼기 때문이다. 그 구간을 재는 것이 이 시나리오뿐이다.

## 지표

k6 는 HTTP 만 본다. 릴레이는 배경 스레드라 `collect-outbox.sh` 가 따로 수집한다.

| 지표 | 출처 | 의미 |
|---|---|---|
| `http_req_duration` | k6 | 주문 생성 응답시간 |
| `e2e_fulfillment_latency` | k6 (`payment-callback.js`) | **종단 지연 — 결제 승인 → 라이브러리 반영** |
| `e2e_fulfillment_ok` | k6 (`payment-callback.js`) | 60초 안에 지급이 확인된 비율. 지연 분포는 *도달한 것만* 말한다 |
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
docker compose exec -T mysql mysql -ustove -pstove1234 -e \
  "TRUNCATE stove_order.outbox_event; DELETE FROM stove_order.order_item; DELETE FROM stove_order.orders;"
```

워밍업 구간(첫 20초)은 JIT 컴파일과 커넥션 풀 확보에 쓰이므로
시나리오에 포함돼 있다. 결과를 읽을 때 그 구간은 빼고 본다.

## 환경 변수

기본값은 **호스트에서 포트가 열린 경우**를 가정한다. 위 9장 절차(전체 스택 컨테이너)에서는
호스트 포트를 열지 않으므로 서비스 이름으로 덮어야 한다.

| 변수 | 기본값 | 9장 절차에서 |
|---|---|---|
| `ORDER_URL` | `http://localhost:8082` | `http://order:8082` |
| `CATALOG_URL` | `http://localhost:8081` | `http://catalog:8081` |
| `PAYMENT_URL` | `http://localhost:8083` | `http://payment:8083` (`payment-callback.js`) |
| `LICENSE_URL` | `http://localhost:8084` | `http://license:8084` (`payment-callback.js`) |
| `ORDER_ACTUATOR` | `http://localhost:8082/actuator/prometheus` | `http://order:8082/actuator/prometheus` |
| `INTERVAL` | `1` (수집 주기, 초) | 그대로 |
| `RATE` / `DURATION` | `100` / `5m` (`order-soak.js`) | 포화 이전 구간으로 (9장은 60 / 60s) |
