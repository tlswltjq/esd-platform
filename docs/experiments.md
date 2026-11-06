# 실험 기록

[문서 목록](README.md) · [결함 기록](defects.md) · [테스트 전략](testing.md) · [아키텍처](architecture.md)

성능·장애 주입·중단 복구·추적·이미지 빌드 실험을 한곳에 모은다. 실험마다 **질문 → 조건 → 결과 → 해석·한계 → 원문** 순서로 적는다. 수치는 원문을 그대로 옮겼고, 회차별 원자료·서사·실패 경위는 원문 고정 링크(`0cc225bf`)에 남아 있다.

**시점 구분이 가장 중요하다.** E1~E9는 **재작성(2026-10) 전 구현**에서 수행했다. 당시 주문·결제 API(`prepare`/`callback`), 보상 환불, `scripts/perf`·`scripts/chaos` 도구는 현재 코드에 없다. 그 수치를 현재 스택의 처리 용량이나 현재 동작의 근거로 일반화하지 않는다. 정리 전 도구는 [정리 전 소스](https://github.com/tlswltjq/esd-platform/blob/e32750ffc95dee1fe5b59b2e248859f84aa77154/scripts/)에서, 현재 검증은 [tools/verification](../tools/verification/README.md)에서 찾는다. E10·E11은 재작성 완료 모듈에서 수행했다.

| ID | 실험 | 시점·환경 | 구현 시점 |
|---|---|---|---|
| [E1](#relay-laptop) | Outbox 릴레이 처리량 개선 | 2026-08, macOS 노트북 8GB | 재작성 전 |
| [E2](#relay-oci) | 같은 개선의 OCI 재측정·비용 모델 | 2026-08, OCI Ampere A1 | 재작성 전 |
| [E3](#poll-interval) | 폴링 간격 1000 → 200ms | 2026-08-13 | 재작성 전 |
| [E4](#http-relay) | HTTP 쓰기 경로와 릴레이 ON/OFF | 2026-08, 노트북(무효) → OCI | 재작성 전 |
| [E5](#consumer) | 받는 쪽: 랙 관측과 `listener.concurrency` 1 ↔ 3 | 2026-08, OCI·Intel 데스크톱 | 재작성 전 |
| [E6](#isolation-ab) | 격리 수준 RR/RC와 주문 쓰기 지연 | 2026-09-14, OCI | 재작성 전 (`1a182b8`) |
| [E7](#kafka-auto-instrumentation) | Kafka 자동 계측 트레이스 | 2026-08-21, OCI | 재작성 전 |
| [E8](#chaos) | 부하 중 장애 주입 (오환불·복구·가격 창·알람) | 2026-08-15~16, Intel 데스크톱 | 재작성 전 |
| [E9](#resilience) | 중단·복구 시나리오 R-01~R-08 | 2026-08-17, OCI | 재작성 전 |
| [E10](#tempo) | Tempo 분산 추적 도입·Payment 추적 복원 | 2026-10-24, 로컬 OrbStack | 재작성 후 (`d7fc8d4`) |
| [E11](#jib-oci) | Dockerfile vs Jib 이미지 빌드·실행 | 2026-10-19, OCI Ampere A1 | 재작성 후 (`2ccad87`) |

<a id="measuring"></a>

## 공통 측정 원칙

재작성 전 실험들이 저지른 실수에서 나온 규칙이다. 새 실험도 이 순서를 따른다. 규칙마다 출처 실험을 붙였다. 원문: [measuring.md](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/measuring.md)

| # | 규칙 | 출처 |
|---|---|---|
| 1 | 비교 전에 **대조군이 다른 숫자를 내는지** 확인한다(예: 릴레이 OFF의 `pending` = 생성 주문 수). A → B → A로 누적 효과를 배제한다 | E4 노트북 회차 무효 |
| 2 | "실패가 없다"와 "부하가 걸렸다"를 구분한다. k6 `checks: rate>0.99`·`dropped_iterations: count<1`, 처리량은 도착률 기반(지연을 재는 시나리오는 VU 기반) | E4·E5 |
| 3 | 판정 지표를 **측정 대상 밖**에서 가져온다(랙은 브로커 오프셋). 모르는 값을 0으로 적지 않는다(`unknown_partitions`) | [D-026](defects.md#d-026) |
| 4 | 한계선 찾기(계단식 20→400 RPS)와 비교(한계선 절반 이하 고정 부하)를 분리한다 | E4·E5 |
| 5 | **압박 증거**를 같이 찍는다. 포화도는 "꽉 찼나"가 아니라 "기다렸나"(`pool_pending`)로 잰다 | E5 13-2 |
| 6 | 조건마다 동일한 초기 상태(DB·큐·캐시·오프셋). 사후(`env.txt` 시작 랙)와 사전(리셋 직후 랙 > 100, 부하 직전 랙 > 500이면 중단) 확인을 둘 다 둔다. 초기화 명령의 종료 코드는 증거가 아니다 | E5 13-8·14-6 |
| 7 | 조건이 **실제로 걸렸는지** 매 회차 전 앱의 환경과 `/actuator/env` 바인딩 소스로 확인한다 | E2 JDBC 배치 |
| 8 | 환경을 결과와 같은 폴더에 남긴다. 같은 개선이 노트북 ×3.08, OCI ×5.25였다 | E1·E2 |
| 9 | 조건당 최소 2회. **조건 안 편차 < 조건 사이 차이**일 때만 유효하고, 반복 없이는 "차이 없음"도 말할 수 없다 | E4·E5 |
| 10 | 세션 비교 전 두 숫자가 **같은 정의**인지 본다(평균 vs 최대). 순서: 정의 → 수집 창 → 그래도 남으면 새 부하 | E5 14-5 |
| 11 | 측정 중 같은 호스트에 다른 부하(CI)를 올리지 않는다. `./gradlew --stop`, 잔여 JVM 정리, `load < 1.5` | E4 59.66ms 회차 |

절차는 가설(틀렸다고 볼 결과 포함) → 판정·압박 지표 선택 → 스모크 → 한계선 → 조건 비교(조건당 2회·동일 초기 상태·A→B→A·조건 적용 확인) → 판정이다.
당시 남은 한계: `dropped_iterations` 임계 캘리브레이션 미완, 측정 도구 해상도(k6 폴링 0.5초가 종단 지연 바닥 517~522ms를 만듦), 부하 시간·워밍업 기준 미정, 배경 부하와 팬아웃의 겹침 시점이 결과를 흔듦.

---

<a id="relay-laptop"></a>

## E1. Outbox 릴레이 처리량 개선 (2026-08, 노트북)

**질문** — 9개 서비스가 공유하는 Outbox 릴레이는 얼마나 빠르고, 어디가 병목인가. `relay()`가 배치 전체를 `send().get()`으로 순차 대기했다.

**조건**
- 호스트 macOS 8GB RAM, Docker 4GB. MySQL 8.0·Kafka 3.9·Redis 7 컨테이너, catalog(8081)·order(8082)는 호스트 `bootRun`. Elasticsearch·MongoDB·Grafana는 메모리 부족으로 제외.
- HTTP 부하는 주문 생성 경로가 먼저 포화(목표 400 RPS → 실제 74 RPS, `dropped_iterations` 15,795, p95 5.08s, 실패 3.34%)해 쓸 수 없었다. 그래서 **DB에 20,000건을 직접 적재하고 배수 속도**를 쟀다(`scripts/perf/relay-drain.sh`).
- 세 단계 모두 같은 스크립트·같은 20,000건·같은 키 분포(전부 다른 키). 단계별 반복 횟수는 원문에 기재가 없다.

**결과**

| 단계 | 처리량 | 직전 대비 | 기준선 대비 | 비고 |
|---|---:|---:|---:|---|
| 기준선 | 138.0 events/s | — | — | 144.9초, 릴레이 1회 p50 0.243s·p95 0.763s (307회, 배치 최대 200) |
| + 키 단위 웨이브 발행 | 156.1 events/s | **×1.13** (+13%) | ×1.13 | 배치 처리 0.47s → 0.28s |
| + 연속 배수(배치가 가득 차면 대기 없이 다음 배치) | **480.5 events/s** | **×3.08** | **×3.48** | 소요 128.1초 → 41.6초 |

기준선은 `주기 = 고정 대기 1.00s + 배치 처리 0.47s = 1.47s`, `200 / 1.47 = 136 events/s`(측정 138)로 설명됐다. 주기의 68%가 유휴였고, 웨이브 발행 뒤에는 고정 1초 대기의 비중이 78%로 올라갔다.

**해석·한계**
- 웨이브 발행은 [D-013](defects.md#d-013) 순서 수정과 같은 변경이다. 같은 `partitionKey`는 순서대로, 다른 키는 동시에 보내 대기 횟수가 배치 크기에서 최장 체인 길이로 줄었다. 앞 이벤트가 실패하면 같은 키의 뒤 이벤트는 그 회차에서 보류된다.
- 연속 배수는 배치마다 트랜잭션을 따로 열고 `max-batches-per-cycle`(기본 10)로 한 회차의 독점을 막는다. 여러 배치를 한 트랜잭션으로 묶으면 `FOR UPDATE SKIP LOCKED` 락 보유가 길어진다.
- "138 → 480"은 두 수정을 합친 값이다. 기여는 웨이브 ×1.13, 연속 배수 ×3.08로 나눠 인용한다.
- 원문: [performance.md 1~6장](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/performance.md)

<a id="relay-oci"></a>

## E2. 같은 개선의 OCI 재측정과 비용 모델 (2026-08)

**질문** — E1의 개선폭이 다른 호스트에서도 같은가. 연속 배수의 적정 상한은 얼마인가.

**조건** — OCI Ampere A1 4코어·23GB, aarch64 Ubuntu 20.04, 당시 컨테이너 19개(현재 전체 스택 구성 수가 아니다). 웨이브 발행은 적용된 상태에서 `max-batches-per-cycle`만 1·3·10으로 바꿨다. `mbpc=1`이 개선 전(연속 배수 없음) 동작과 같다. 즉 이 비교는 **배치 연속 처리 하나의 효과**다.

**결과**

| `max-batches-per-cycle` | 1 | 3 | 10 |
|---|---:|---:|---:|
| 처리량 (events/s) | **180.7** | 451.0 | **948.7** |

- 같은 개선이 OCI에서 **×5.25**(노트북 E1은 ×3.08). 노트북에서 0.28초였던 같은 배치가 OCI에서는 **0.021초**였다 — 0.28초는 브로커(`acks: all` 단일 브로커)가 아니라 노트북의 비용이었다.
- 튜닝 실험의 근사 비용은 `배치당 20ms + 이벤트당 0.48ms`. 폴링을 충분히 줄였을 때의 계산상 상한은 약 2,080 events/s, 관측 최고 약 1,315는 그 63%다.
- 타이머가 관측한 발행 구간은 전체 배치 시간의 12~30%였고 나머지 큰 비용은 커밋 구간이었다. JDBC 배치 50 → 200은 바인딩까지 확인했지만 효과가 없었다.
- 회차당 배치 수 1 → 10과 폴링 200ms(E3)는 적용했고, concurrency 3·배치 크기 400은 후보로 남겼다.

**해석·한계** — 개선폭은 호스트에 따라 달라진다. 느린 기계에서 잰 개선폭은 과소평가였다. 호스트의 다른 워크로드는 실험 전 확인했지만 연속 감시하지는 않았다.
원문: [performance.md 요약·8-3·8-4](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/performance.md), [튜닝 실험 당시 기록](https://github.com/tlswltjq/esd-platform/blob/1a182b8/docs/perf-tuning.md)

<a id="poll-interval"></a>

## E3. 폴링 간격 1000 → 200ms (2026-08-13)

**질문** — 고정 폴링 대기를 줄이면 종단 지연이 얼마나 줄고 무엇을 내는가.

**결과** — `poll-interval-ms` 1000 → 200 적용(`7574ea2`). 종단 p95 **1.30s → 0.56s**, 대가는 MySQL CPU **약 +1.5%p**. 폴링 변경 후 종단 p95의 회차 간 편차도 46% → 0.4%로 줄었다.

**해석·한계**
- 과거 트레이스 예시의 소비 시작 `+527ms`, 후속 전달 `+759ms`에는 DB 처리 시간만이 아니라 릴레이 폴링 대기가 들어 있었다. 상수로 줄일 수 있는 것은 여기까지이고, 더 내리려면 적응형 폴링이 다음 수순이었다.
- 종단 지연을 0.5초 간격으로 조회한 실험의 중앙값 약 528ms에는 관측 해상도의 한계가 있다(`min` 517~522ms 반복).
- 현재 스택을 새로 측정한 값이 아니다. 원문: [performance.md 요약](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/performance.md)

<a id="http-relay"></a>

## E4. HTTP 쓰기 경로와 릴레이 ON/OFF (2026-08)

**질문** — 릴레이 개선이 API에 영향을 주는가. 릴레이는 쓰기 경로 지연에 얼마를 얹는가.

**첫 비교는 무효였다(노트북, 7장).** 개선 후 악화로 보였지만 **릴레이를 꺼도 같은 숫자**였다.

| 구성 | RPS | p95 | 실패율 |
|---|---:|---:|---:|
| 기준선 (개선 전) | 74.1 | 5.08s | 3.34% |
| 개선 후 `max-batches=10` | 44.8 | 10.0s | 36.1% |
| 개선 후 `max-batches=1` | 46.5 | 8.68s | 31.4% |
| 릴레이 완전 비활성 | 44.9 | 9.9s | 38.5% |

원인 정황은 호스트 메모리 압박(`pageouts` 150만, 여유 32%), 첫 기준선만 부팅 직후, 실패 전부 HikariCP 고갈(`total=20, active=20, idle=0, waiting=161`)이다. 세 구성 모두 포화였다.

**재측정(OCI, 9장)** — OCI Ampere A1 4코어 23GB, 인프라 9 + 앱 10 전부 컨테이너, swap 0~1 MiB, k6도 컨테이너. 비교는 `order-soak.js` 60 RPS 고정(한계선의 약 45%), 회차마다 `orders`·`outbox_event`를 비우고 order 재기동, 조건당 2회.

| 계단식 20→400 RPS | RPS | p95 | 실패율 | 최대 VU |
|---|---:|---:|---:|---:|
| 릴레이 ON | 132.5 | 466.42ms | 0.00% | 252 |
| 릴레이 OFF | 133.2 | 84.09ms | 0.00% | 63 |

| 60 RPS 고정 | 회차 | RPS | avg | p95 | 실패 | pending |
|---|---|---:|---:|---:|---:|---:|
| ON | r1 | 60.00 | 21.23ms | **42.60ms** | 0.00% | 0 |
| ON | r2 | 59.98 | 25.28ms | **43.12ms** | 0.00% | 0 |
| OFF | r1 | 59.98 | 18.99ms | **22.37ms** | 0.00% | 5,398 |
| OFF | r2 | 59.97 | 19.08ms | **24.10ms** | 0.00% | 5,398 |

**해석·한계**
- 릴레이는 쓰기 경로 p95에 **약 19ms(1.85배)**를 얹고 처리량은 줄이지 않는다. 조건 안 편차 0.5ms / 1.7ms, 조건 사이 19ms라 유효하다. OFF의 `pending` 5,398 = 생성 주문 수가 대조군 검증이다.
- 비교 중 같은 호스트에서 CI가 돌아 p95 59.66ms로 튄 회차(정상 42.60 / 43.12ms)는 버리고 다시 쟀다.
- 릴레이 **유무** 비교이지 개선 전후 비교가 아니다. "같은 커넥션 풀" 가설은 E5 13-5에서 기각됐고, 원인 하나(갭 락)는 E6에서 쟀다. 이 19ms는 `poll-interval-ms: 1000` 시절 값이다.
- 원문: [performance.md 7~10장](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/performance.md)

<a id="consumer"></a>

## E5. 받는 쪽: 랙 관측과 `listener.concurrency` 1 ↔ 3 (2026-08)

**질문** — 받는 쪽이 보내는 쪽을 따라오는가. 리스너 동시성을 파티션 수(3)로 올리면 무엇을 얻고 무엇을 내는가.

**랙 지표부터 틀렸다.** `kafka_consumer_fetch_manager_records_lag`는 직전 fetch에서 본 값이라 컨슈머가 멈추면 같이 멈춘다. 실제 랙 113,517건 동안 0을 보고했다([D-026](defects.md#d-026)). 이후 판정은 브로커 오프셋(`tools/observability/collect-lag.sh`, `kafka-exporter`)으로 옮겼다.

**조건 공통** — 60 RPS 고정(한계선 약 절반), 회차마다 DB 정리·컨슈머 오프셋 `--to-latest`·대상 앱 재생성, A→B→A 대조군. 12~14장은 Intel i5-8600 6코어 32GB / OrbStack(15.7~16.8GB) 전체 컨테이너.

| 측정 | 결과 |
|---|---|
| payment 한계 (OCI, 2026-08-10) | `concurrency=1` **141.1 events/s** = 릴레이 948.7의 6.7분의 1. `concurrency=3`이면 364.1 events/s |
| 한계선 (12장, 계단식) | 121.7 req/s, p95 1,608.7ms, 실패 0.00%. 릴레이 32,862건·최대 1,574 events/s, `stove_outbox_pending` 최대 271 → 최종 0, **브로커 랙(payment) 최대 27,845 → 최종 27,474**. payment는 초당 31건 소화(50배 차이) |
| 한계선 재현 (14장) | 120.91 RPS, 브로커 랙 최종 27,427, HTTP 실패 0%, outbox 0. 포화 구간에서만 order 풀 `pending` 179 |
| 12장 c=1→3→1 (60초) | 랙 최종 2,285·2,119 / 325·210 / 재확인 2,283. 소비 평균 32.1 → 77.5 events/s (**×2.41**, OCI ×2.55). API p95 평균 207 → 367ms(×1.77) |
| 13장 포화 위 c=1→3→1 (배경 9,000건·150초) | 지급 성공률 **50% → 100%**, 랙 최종 5,756 / 159·546 / 재확인 5,741, 배수 19.0 / 52.6·51.7 / 19.2 per s. 풀 `pending` 5회차 전부 0, `active` 최대 5/20. API p95 c=1 160.7~180.8 안에 c=3 165.5·167.7 |
| 14장 독립 재현 c=1→3→3→1 | 성공률 10% / 100% / 100% / 20%, 랙 최종 4,899 / 0 / 0 / 4,844, 정상 소비 19.0 / 55.0 / 53.7 / 19.0 per s, 풀 `pending` 전부 0. API p95 c=3 158.5·156.9가 c=1 범위 147.4~161.0 안 |

**해석·한계**
- 생산자 지표(실패 0%, `pending` 0)가 전부 초록인 회차에 브로커 랙 2,285건이 남아 있었다. 예전 도구로는 그 회차가 통째로 초록이었다.
- concurrency 3은 소비율 2.41~2.55배, 성공률 10~50% → 100%, 잔여 랙 5,741~5,756 → 159~546을 줬다. 12장의 "API p95 ×1.77" 대가는 표본 2.5배·150초 측정(13·14장)에서 **재현되지 않았다**(재기동 직후 워밍업 가능성). 리스너 처리시간 증가(50.9 → 61.2ms)는 두 측정 모두에서 관측됐다.
- 커넥션 풀 병목 가설은 **60 RPS + 5 VU에서 기각**됐다. 풀 20개가 부족해지는 부하는 재지 않았다.
- c=1 성공률은 겹침 시점에 좌우된다(거의 동시 출발 50%, 배경 부하 25초 뒤 얹으면 10~20%). 조건 없이 인용하지 않는다.
- 13·14장 배수 속도 1.7배 차이는 평균(19.0·52.6)과 최대(33.0·75.3)를 비교한 착오였다. 같은 정의로 읽으면 두 세션이 2% 안에서 일치한다.
- 오염 회차 둘은 버렸다: 시작 랙 5,686(리셋 직후 그룹 멤버 미이탈 경합)과 21,971(bash 내장 변수 `GROUPS`로 오프셋 리셋이 다른 그룹에 `rc=0`으로 성공).
- 결정: `concurrency`는 기본값 1을 유지했다(파티션 3개는 확장 여지). 랙이 0인 평상시에는 변화가 없고, 밀리는 구간에서 올릴 신호는 랙(`ConsumerLagGrowing`)이다.
- 원문: [performance.md 11~14장](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/performance.md)

<a id="isolation-ab"></a>

## E6. 격리 수준과 주문 쓰기 지연 A/B (2026-09-14)

**질문** — 릴레이의 `FOR UPDATE SKIP LOCKED`가 REPEATABLE READ에서 `idx_outbox_pending`에 넥스트키 락을 잡고 Kafka ack를 기다리는 동안, 주문의 outbox INSERT가 기다리는가([아키텍처 R1](architecture.md#r1)).

**조건** — 기준 코드 `1a182b8`, OCI Ampere A1 4코어·23GB. order 커넥션의 기본 격리 수준만 READ COMMITTED로 바꿨다(Hikari `transactionIsolation`, 코드 변경 없음). 60 RPS 주문 생성 90초씩 **RR → RC → RC → RR**(`scripts/perf/run-isolation-ab.sh`). CI 러너 정지. 공용 스택 데이터를 지우지 않으려고 테이블을 비우지 않았고, A-B-B-A 순서와 회차별 초기 행 수로 누적 효과를 대조했다.

| 회차 | order 격리 수준 | p95 | p90 | 평균 | 90초간 InnoDB 행 락 대기 |
|---|---|---:|---:|---:|---|
| rr-1 | REPEATABLE READ ×20 | 26.42ms | 21.41 | 15.67 | **404회**, 누적 3,806ms (건당 9.4ms) |
| rc-1 | READ COMMITTED ×20 | 21.26ms | 18.53 | 14.75 | **0회** |
| rc-2 | READ COMMITTED ×20 | 21.82ms | 18.78 | 14.99 | **0회** |
| rr-2 | REPEATABLE READ ×20 | 26.04ms | 21.01 | 15.48 | **400회**, 누적 3,571ms (건당 8.9ms) |

**해석·한계**
- 네 회차 모두 5,400건·누락 0·실패 0. 조건 안 p95 편차(0.38·0.56ms)가 조건 사이 차이(약 4.7ms)보다 작다. 요약하면 행 락 대기 회차당 400회 → 0회, 쓰기 p95 26.2 → 21.5ms.
- 바꾼 것은 격리 수준 하나다. order에서 범위를 잠그는 문장은 릴레이 조회뿐이므로 400회는 넥스트키 락을 기다린 outbox INSERT다([DB 락 실험 1](../tools/db-lab/README.md)의 메커니즘). 락 대기는 주문 수의 약 7.4%, 평균 9ms, p95 약 4.7ms(×1.22).
- E4의 +19ms(`poll-interval-ms: 1000` 시절)와 크기를 맞대지 않는다. 그 설정에서 락 경합의 몫은 **[추가 확인 필요]**. 브로커 장애 중 실패 전파는 재현하지 않았다.
- 원문: [performance.md 15장](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/performance.md), 도구 [run-isolation-ab.sh](https://github.com/tlswltjq/esd-platform/blob/e32750ffc95dee1fe5b59b2e248859f84aa77154/scripts/perf/run-isolation-ab.sh)

<a id="kafka-auto-instrumentation"></a>

## E7. Kafka 자동 계측만 켜면 트레이스가 틀리는가 (2026-08-21)

**질문** — Outbox 릴레이가 `traceparent`를 직접 복원하는 대신 Spring Kafka 자동 계측(`spring.kafka.template.observation-enabled`)에 맡기면 어떻게 되는가. 이 판단은 켜 보지 않고 적은 것이었다.

**조건** — OCI Ampere A1 전체 스택. payment만 `poll-interval-ms=10000`으로 두어 승인 콜백 3건이 반드시 한 배치에 들어가게 했다. `observation-enabled`만 바꿔 off 2회·on 2회, 네 회차 모두 릴레이가 `outbox relay 처리 3건`을 찍어 배치 조건이 같았음을 확인했다. 판정은 license 지급 로그의 `[license,traceId,spanId]`. 측정 중 CI 러너를 멈췄다.

| 조건 | 회차 | 릴레이 배치의 traceId | 주문 3건의 컨슈머 traceId |
|---|---|---|---|
| off (현재 동작) | 1 | `1c821b29…` | `ad737b86…` · `03a96e64…` · `b6fd9329…` — 셋 다 다르고 릴레이와도 무관 |
| off | 2 | `5912f31a…` | `030b4996…` · `ee1833df…` · `89d89440…` — 셋 다 다름 |
| on | 1 | `0fbf7905…` | 셋 다 `0fbf7905…` — 릴레이 배치와 동일 |
| on | 2 | `25209d79…` | 셋 다 `25209d79…` — 릴레이 배치와 동일 |

**해석** — ① 자동 계측이 릴레이가 복원한 `traceparent`를 덮어쓴다 ② 컨슈머 트레이스가 원 요청이 아니라 릴레이 폴링 회차에 붙는다 ③ 한 배치의 무관한 주문 셋이 같은 traceId를 공유한다. 원복까지 마쳤다(컨테이너 21개 복귀). 이 판단과 대안은 [결정 17](decisions.md#17-추적-컨텍스트를-이벤트와-함께-저장한다)에 있다.

<a id="chaos"></a>

## E8. 부하 중 장애 주입 (2026-08-15~16)

**질문** — Saga 보상·컨슈머 재시도·멱등 가드·DLT는 평상시 아무 일도 하지 않아 부하 테스트로는 실행되지 않는다. 장애 중에 돈과 물건의 짝이 맞는가.

**조건 공통** — Intel i5-8600 6코어 32GB / OrbStack, 인프라 10 + 앱 10 전부 컨테이너, 배포 게이트 14건 통과 상태에서 시작. 결말은 API 폴링이 아니라 DB에서 직접 센다: `fulfilled`(PAID·라이선스 있음), `parked`(PAID·없음, DLT에 있어야 함), `refunded`(CANCELED·없음), `inconsistent`(CANCELED·있음).
장애는 license에만 닿도록 MySQL 권한으로 넣었다(공유 MySQL 컨테이너를 끄면 결제를 흘릴 수 없다). `license-db-denied`는 `stove_license` 스키마 전체, `license-table-denied`는 `license` 원장 테이블만(outbox·inbox 정상), `license-stopped`는 프로세스 정지다. `fault.sh`가 주입 직후 프로브로 실제 차단을 확인한다 — 첫 회차에서 `REVOKE`만으로는 HikariCP가 붙든 커넥션 20개 때문에 아무것도 끊기지 않아 `KILL`이 필요했다.

<a id="chaos-refund"></a>

### 시나리오 1c — 장애 중 정상 결제가 환불되는가 ([D-027](defects.md#d-027)·[D-028](defects.md#d-028))

조건: 주문 40건 @ 2/s, 부하 시작 +5초에 주입, 60초 유지, 복구 후 90초 드레인. 조건당 1회.

| | 대조군(장애 없음) | `license-db-denied` 수정 전 | 수정 후 | `license-table-denied` 수정 전 | 수정 후 |
|---|---:|---:|---:|---:|---:|
| fulfilled | 40 | 39 | 37 | 32 | 33 |
| parked (DLT) | 0 | 1 | 3 | 0 | **7** |
| **refunded** | 0 | 0 | 0 | **8** | **0** |

- 수정 전 스키마 차단에서는 보상 기록(`outbox_event` 쓰기)도 같이 실패해 우연히 안전했고, 테이블 차단에서는 보상이 적재돼 **8건이 환불**됐다. 스키마 차단 회차의 DLT 2건 중 1건은 이미 지급이 끝난 주문이었다(지급 커밋 후 오프셋 커밋 전 재배달 → 재처리 실패 → 보상 시작).
- 수정: 자동 환불 조건을 "실패했고, 저장소 탓(`DataAccessException`·`TransactionException`·`SQLException`, 원인 사슬 끝까지)이 아니며, 실제로 지급되지도 않았다"로 좁혔다. 실측 예외는 `CannotCreateTransactionException`이었고 `DataAccessException`이 아니었다.
- 보류 7건을 `POST /api/v1/ops/dlt/replay?topic=stove.payment.v1.DLT&max=50`(`{"replayed":9}`)로 재투입하자 **1초 안에 7건 전부 지급**, 대사 결제완료 115 : 라이선스 115. 수정 전 회차의 `CANCELED 8`은 되돌아오지 않는다.
- 환불(보류) 건수는 결제량이 아니라 재시도 회전 수에 비례한다. 지속시간을 바꿔 잰 회전 주기(수정 후 보류 건수로 계수 측정):

| 장애 종류 | 장애 시간 → 보류 건수 (초/건) | 계수 |
|---|---|---:|
| `license-table-denied` | 30초 → 4 (7.5), 60초 → 7 (8.6), 120초 → 16 (7.5), 240초 → 31 (7.7) | 평균 **7.8초** |
| `license-db-denied` | 60초 → 3 (20.0), 1020초 → 52 (19.6) | **19.8초** |

  처음 적은 "7초"(백오프 1+2+4만 센 값)는 틀렸다. 차이는 `connection-timeout` 3초 × 4회 시도에서 나온다. 10분 장애의 환불 건수 추정은 77건 / 30건이었다.

<a id="chaos-replay"></a>

### 시나리오 1b — 유실된 원장을 카프카 재처리로 되살릴 수 있는가 ([D-030](defects.md#d-030))

깨끗한 스택에 200건을 넣고 `license` 원장만 지운 뒤 오프셋을 되돌렸다. 두 회차의 차이는 절차의 한 줄(가드 행 삭제)뿐이다.

| | 가드 행 삭제 없이 | 가드 행 삭제 포함 |
|---|---:|---:|
| 복구된 주문 | **0** | **200** |
| 대사(결제완료 : 라이선스) | 200 : 0 | 200 : 200 |
| 복구 속도 | — | 16.7 건/s |
| 컨슈머 ERROR 로그 | **0** | 0 |

0건 복구가 성공과 구분되지 않았다. 운영 재처리 수단 셋(오프셋 리셋·DLT 재투입·Outbox 재적재)은 모두 `event_id`를 보존한다. 산출물은 코드가 아니라 [runbooks/license-db-loss.md](runbooks/license-db-loss.md) 절차다.

### 시나리오 2b·2c·1a — 가격 창·캐시·다운로드 격리

| 시나리오 | 결과 |
|---|---|
| 2b 만료된 가격으로 결제되는가 ([D-029](defects.md#d-029)) | 39,000원 주문 → 상품 78,000원으로 변경 → 주문 나이 30일로 되돌림 → 결제 승인 → PAID 39,000원·라이선스 지급. 창의 크기가 무한이었다. 수정(사전등록 창 `stove.payment.window` 기본 30분) 후 `PAYMENT_WINDOW_EXPIRED`, 만료 주문의 PG 거래 0건, 30분 이내 주문은 변화 없음 |
| 2c Redis 캐시가 만료된 가격을 먹이는가 | **기각.** 캐시를 실제로 데운 뒤 DB만 78,000으로 바꾸자 `GET /products/1`은 39,000(캐시), `POST /products/quote`는 78,000. 금액 확정 경로는 캐시를 타지 않는다. 반대 방향(`PRICE_MISMATCH`로 못 사는 창, 정상 경로는 TTL 5분 상한)은 금전 손실이 없어 결함으로 올리지 않았다 |
| 1a license가 죽어도 다운로드가 되는가 | `license-stopped`(프로브 000) 중 다운로드 티켓 **20/20 성공**, 평균 9.3ms, 매니페스트 200. 대조로 license 라이브러리는 000 |

<a id="chaos-alerts"></a>

### 알람은 실제로 울리는가 (2026-08-15~16)

조건: 부하 2/s, 장애 15분, `tools/observability/watch-alerts.sh`가 10초마다 알람·랙·커밋 오프셋을 한 줄로 기록. 2회차는 `run-scenario.sh --repeat --control`로 대조군을 회차마다 바로 앞에 붙였다.

| | 회차 A `license-stopped` | 회차 B `license-db-denied` |
|---|---|---|
| 커밋 오프셋 | 얼어붙는다 (208 고정) | 움직인다 (800 → 855) |
| `ConsumerStalled` | +297s 발화 (2회차 311s) | 끝까지 안 울림 |
| `MessagesDeadLettered` | 안 울림 (DLT가 안 생긴다) | +52s 발화 (2회차 39s) |
| 결말 1회차 / 2회차 | 600/600 정상 / 600/600 정상 | 748 정상·52 보류·환불 0 / 753 정상·47 보류·환불 0 |
| 대조군(2회차) | 600/600 정상 | 800/800 정상 |

| 장애 → 알람 | 1회차 | 2회차 |
|---|---:|---:|
| DLT 첫 건 → `MessagesDeadLettered`(critical) | 52초 | 39초 |
| 컨슈머 정지 → `ConsumerStalled`(critical) | 297초 | 311초 |
| 랙 500 돌파 → `ConsumerLagGrowing`(warning, 수정 후) | 598초 (장애 후 +884s 발화) | 장애 후 +855s(A) · +865s(B) 발화 |
| 컨테이너 정지 → `ServiceDown`(critical) | — | 140초 (pending 19초) |

- 두 알람은 서로 다른 장애를 잡고, 어느 하나도 둘 다 잡지 못한다. 편차는 스크레이프 격자(10초) 한두 칸(+14s·−13s)이다.
- 수정 전 `ConsumerLagGrowing`(`lag_sum > 500` + `for: 10m`)은 랙 592가 10분 넘게 고정인데 한 번도 울리지 않았다. 랙 표본 63개 중 3개 결측이 `for` 시계를 4회 리셋했고, 결측 5.3%로 60표본 연속 확률은 `0.947^60 ≈ 4%`다([D-031](defects.md#d-031)). `min_over_time(...[10m]) > 500`으로 바꾼 뒤 돌파 후 정확히 10분(+884s)에 발화했다. 결측 원인은 익스포터 플래그였다([D-032](defects.md#d-032)).
- `ConsumerLagMetricGaps` 임계 0.75는 license 그룹 하나(0.95~0.97)만 보고 정해 즉시 발화했다. 10분 창 보존율은 그룹마다 0.750~0.983이었고 임계를 0.5로 낮췄다.
- "보류는 되돌릴 수 있다"의 실제 크기는 **알람 1분 + 재투입 1초**였다.
- 부하·장애 시간 근거: 드레인 90초(최악 회전 19.8초의 4회전 이상)와 장애 15분(가장 느린 알람, 여유 16~45초)은 근거가 있다. 장애 60초는 사후에 근거가 생겼고, 부하 2/s와 주문 건수(40·600·800)는 근거가 없다. 판정 방향(환불 8 → 0)은 1회로 섰지만 시나리오 1c 자체는 조건당 1회다.
- 원문: [chaos.md](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/chaos.md)

<a id="resilience"></a>

## E9. 중단·복구 시나리오 R-01~R-08 (2026-08-17)

**질문** — E8의 판정은 사람이 스크립트를 돌려야 나온다. "서버가 중단됐을 때 보장되는 것"을 CI가 지키는 테스트로 고정할 수 있는가.

| ID | 시나리오 | 보장 | 층·테스트 |
|---|---|---|---|
| R-01a | 배포로 앱이 내려간다 — 설정 | 열 앱 graceful, 컨테이너 유예 > 스프링 종료 단계 | 단위 `DeploymentShutdownContractTest` |
| R-01b | 같은 상황 — 동작 | 진행 중 요청이 실제로 완주 | 단위 `GatewayGracefulShutdownTest` |
| R-02 | 발행 커밋 직후 릴레이 중단·재기동 | 적재를 목격하지 않은 인스턴스가 폴링으로 회수, DEAD를 되살리지 않고 백오프를 건너뛰지 않음 | 통합 `RelayRestartRecoveryTest` |
| R-03 | license 중단 중 결제 완료 | 재기동 후 따라잡고 이중 지급하지 않음 | 통합 `ConsumerRestartCatchUpTest` |
| R-04 | 취소 도중 payment 중단 | 스케줄러가 스스로 확정까지 | 통합 `RefundSweeperWiringTest` |
| R-05 | 구 Order 만료 | 2026-10-16 MVP에서 만료 스케줄러 제거 — 현재는 결과 없이 PENDING 유지 | 범위 변경 ([Order 안내 원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/order-guide.md)) |
| R-06 | license 컨테이너 실제 정지 | 장애가 번지지 않고 복구 | e2e `ServiceOutageChaosTest` (`:e2e:chaosTest`) |
| R-07 | 저장소 장애로 지급 보류 | 재투입하면 지급까지 끝남 | 통합 `DeadLetterReplayRecoveryTest` |
| R-08 | 스윕 인스턴스 여러 대 | 본문이 한 번만 돈다 | 통합 `RefundSweeperSingleExecutionTest` |

**조건·결과** — OCI 4코어 aarch64 / 24GB(로컬 Docker 3.83GB로는 전체 스택 20종 불가). `./gradlew build` **단위 945·통합 230·실패 0**(신규 18건, 5분 37초), `:e2e:e2eTest` 46/46(22초, `chaos` 태그 제외), `:e2e:chaosTest` 5/5(16초), `scripts/stack-wait.sh` 14/14(회차 뒤 원상복구).

**해석·한계**
- 시나리오를 붙이자 [D-034](defects.md#d-034)(gateway만 graceful 없음)·[D-035](defects.md#d-035)(컨테이너 유예 10초 < 스프링 30초)·[D-036](defects.md#d-036)(통합 컨텍스트 증가로 `Too many connections`)이 나왔다. 수정 후 `StopTimeout: 40` 반영을 `docker inspect`로 확인했다.
- R-01b는 처음에 핸들러 2초라 리액터 네티 `disposeNow` 기본 유예 3초 안에 끝나 `immediate`에서도 통과했다. 8초로 올려 차이를 만들었다.
- 하네스 자신의 오류 셋: R-03의 `anyMatch(isRunning)`이 빈 목록에서 거짓 통과, R-07의 `@MockitoSpyBean`이 컨텍스트를 한 벌 더 만들어 같은 그룹에 리스너가 붙음(→ 원장 테이블 RENAME으로 대체), R-08의 `delete from shedlock`이 이후 획득을 조용히 실패시킴(→ 만료로 대체).
- 남은 것: R-06은 CI에 없다. R-01b는 WebFlux 게이트웨이 하나만 본다. 커밋 실패로 생기는 중복 발행 창, 락을 쥔 채 죽었을 때의 최대 5분(`lockAtMostFor = "PT5M"`) 공백은 재지 않았다.
- 원문: [resilience-scenarios.md](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/resilience-scenarios.md)

<a id="tempo"></a>

## E10. Tempo 분산 추적 도입과 Payment 추적 복원 (2026-10-24)

**질문** — 기존 Tempo·Grafana로 HTTP 추적이 실제로 이어지는가. 내구 접수 뒤 다른 스레드·재시작 후 처리되는 Payment에서 원래 요청 trace가 유지되는가([#99](https://github.com/tlswltjq/esd-platform/issues/99)). 수집기 장애·샘플링 0에서도 업무가 끝나는가.

**조건** — 기준 revision `d7fc8d4`, 로컬 OrbStack 격리 Compose(실행별 프로젝트·포트·DB·Kafka·MinIO·Tempo 볼륨), Tempo `2.9.4`, Grafana `12.2.1`, 같은 Jib 앱 이미지, `TRACING_SAMPLE_RATE=1`. 실행 순서는 사용자가 확정한 **공통 기반 검증 → Payment 수정**이다. 시나리오 T01~T14(서로 다른 trace A·B 혼합 금지, 접수 scope 종료 후 별도 스레드, 재전달 시 최초 컨텍스트 유지, 롤백, 헤더 없음·이상 헤더, 프로세스 재시작, PG 응답 유실·재시도, 결과·Outbox 롤백, Kafka 발행 실패, scope·MDC 누출, HTTP 전파, 샘플링 0, Tempo 중단, 재실행). 초기 제한은 인프라 240초·앱 180초·Tempo 조회 90초.

| 단계 | 결과 |
|---|---|
| 수정 전 재현 | 실제 tracer·listener·별도 처리 스레드의 `twoReceiptsKeepTheirOriginalTracesAcrossThreadAndBatchBoundary` 1건이 결과 Outbox의 null 부모로 실패 |
| Payment 단위·통합 | 단위 74건·통합 34건, 실패·오류·건너뜀 0 |
| scope·MDC 보강 | 추적 통합 10건 + 실제 스케줄러 1건(11건), 실패 0 |
| 공통 기반 (`--foundation-only`) | V12 Payment 이미지로 8단계·구매 5건 통과. PG·결제·Payment Outbox·Order Inbox·보유권 각 5건 일치. Tempo 중단·복구, 샘플링 0/1, Grafana 조회, 12개 앱 정상 종료 |
| Payment 프로세스·Tempo | V13 이미지로 16단계 실패 0. 재시작·PG 응답 유실·결과 롤백·Kafka 일시 정지 중 발행 실패·복구·Tempo 중단·추적 비활성·샘플링 0/1 통과. 구매 9건, PG 거래·결제·결과 Outbox·Order Inbox·보유권·추적 메타데이터 각 9건 |
| 기존 업무 회귀 (`verify-jib.py`) | 구매·ZIP 다운로드·배분·소유권 격리·PG 지급 명세 수령·중복 없는 재시도 3단계 통과 |

**해석·한계** — 최초 부모를 `payment_request_trace`에 요청·Inbox와 함께 저장하고 처리 시도마다 그 부모의 자식 span을 만든다([결정 28](decisions.md#28-내구-접수의-추적-부모를-저장하고-처리-시도마다-복원한다)). 부모가 없는 기존 저장분은 독립 root이며 소급 복원하지 않는다. 수집기 중단·추적 비활성 구간의 trace 완전성은 합격 조건이 아니고, 복구 뒤 새 요청 수집을 확인했다. 샘플링 0은 sampled=false 입력이며 원래 trace 조회 404도 확인했다. 도중 실패 회차(11개 JVM 동시 기동의 Auth DB handshake timeout, 브로커 종료로 Docker DNS 제거)는 순차 재기동·Kafka 일시 정지로 도구를 고치고 보존했다. 원자료는 로컬 `runs/`에 있다.
원문: [tempo-tracing-plan.md](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/tempo-tracing-plan.md)

<a id="jib-oci"></a>

## E11. Dockerfile과 Jib의 OCI 비교 (2026-10-19)

**질문** — 기존 `bootJar` → Dockerfile → 레지스트리 게시를 Jib 3.5.4(exploded) 직접 게시로 바꾸면 빌드·이미지·실행이 어떻게 달라지는가.

**조건** — 기준 `2ccad87`의 수정 없는 스냅샷, 실행 모듈 11개 + `simulators/mock-pg`(12개 이미지). OCI Ampere A1 ARM64 4코어·약 24GB, Docker 28.1.1. 두 방식 같은 기반 이미지 digest(Temurin 21.0.12.1+1), UID/GID `100:101`, `JAVA_OPTS`·시간대·종료 유예·healthcheck 유지. 루프백 레지스트리(인터넷 업로드 성능 아님). 빌드는 조건별 3회, 실행은 기준 A1 → Jib B1 → 기준 A2 → Jib B2(회차마다 빈 DB·Kafka·Redis·MinIO, 메모리 상한 512MiB, sample rate 0, 20 RPS 60초). 세션 `20261019T125630Z`.

| 빌드·게시 (중앙값, 범위) | 기존 Dockerfile | Jib | 중앙값 감소 |
|---|---:|---:|---:|
| 출력 삭제 후 12개 이미지 생성·게시 | 91.68초 (89.38~93.36) | 31.75초 (31.13~47.96) | **65.4%** |
| 변경 없는 12개 이미지 재게시 | 21.73초 (21.44~21.82) | 19.47초 (19.21~20.21) | **10.4%** |
| Store 클래스 하나 추가·변경 후 게시 | 20.56초 (19.86~22.25) | 14.41초 (14.15~14.69) | **29.9%** |
| 출력 삭제 조건 호스트 busy CPU | 195.67 CPU초 | 97.70 CPU초 | 50.1% |

| 이미지 (gzip 압축 레이어 바이트) | 기존 Dockerfile | Jib | 해석 |
|---|---:|---:|---|
| Store 이미지 레이어 합계 | 231.70MiB | 150.63MiB | 약 35.0% 감소 |
| 12개 이미지 논리적 합계 | 2,613.04MiB | 1,723.87MiB | 34.0% 감소(공통 기반 중복 포함) |
| 12개 공개본의 고유 blob 합계 | 1,842.92MiB | 532.68MiB | **71.1% 감소** |
| Store 클래스 변경 시 변경 레이어 | 161.69MiB | 17.90KiB | 의존성 재전송 감소(회선 계측값 아님) |

| 실행 (회차별 percentile의 중앙값, 두 회차 범위) | 기존 Dockerfile | Jib | 해석 |
|---|---:|---:|---|
| 12개 앱 HTTP health 준비 완료 | 110.60초 (110.53~110.67) | 94.80초 (92.12~97.48) | 이 조건에서 14.3% 단축 |
| 상품 조회 p50 / p95 | 7.40 / 9.02ms | 7.39 / 8.89ms | 범위 겹침 |
| 상품 조회 p99 | 16.49ms (16.30~16.69) | 11.21ms (10.41~12.00) | 감소 관측, 원인·재현성 미확인 |
| 60초간 앱 12개 CPU 합계 | 25.40 CPU초 | 24.69 CPU초 | 범위 겹침 |
| 평균 working set 합계 | 3,956.81MiB | 3,869.17MiB | 범위 겹침 |
| 본 측정 요청·실패 | 2,400건·0건 | 2,400건·0건 | 목표 20 RPS 충족 |

기계 판독 결과(`jib-oci-results.json`, `complete: true`)에서 옮긴 추가 중앙값: 변경 없는 재게시의 호스트 busy CPU 64.86 → 56.04 CPU초, 클래스 변경 게시 46.47 → 38.80 CPU초(소수 둘째 자리 반올림), Jib 클래스 변경 시 변경 바이트 18,329 bytes. 원본 JSON은 [고정 링크](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/jib-oci-results.json)에 있다.

**해석·한계** — 가장 분명한 효과는 이미지 생성·게시 시간과 변경 레이어·이미지 사이 중복 blob의 감소다. 기존 Store 이미지의 약 80.84MiB JAR 복사·소유권 변경 레이어 중복이 크기 차이에 작용했고, Dockerfile에도 `COPY --chown`·레이어 분리를 적용할 수 있으므로 Jib의 고유 우위로 일반화하지 않는다. clean-output Store digest는 양쪽 모두 3종류라 재현성은 주장하지 않는다. 빌드 시간 감소를 CI 시간 감소로 환산하지 않는다. 예비 회차(인증 필요 readiness 경로, 12개 JVM 동시 기동)는 집계에서 제외했다. 이후 2026-10-21부터 기본 실행은 Jib이며 Dockerfile은 0개다.
원문: [jib-oci-comparison.md](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/jib-oci-comparison.md), 재현 도구 [tools/image-benchmark](../tools/image-benchmark/README.md)
