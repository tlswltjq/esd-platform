# 런북 — license 원장이 유실됐을 때

**대상 상황** `stove_license.license` 의 행이 사라졌다. 결제는 정상이었고 카프카에는
`PaymentCompleted` 가 남아 있다. 사용자는 **돈은 냈는데 라이브러리가 비어 있는** 상태다.

**이 문서가 존재하는 이유** — 되읽기만 하면 복구될 것 같지만 **한 건도 복구되지 않는다.**
2번 단계가 빠지면 그렇다. 재현 테스트는 `LicenseReplayRecoveryTest`, 결함 항목은
[D-030](../defects.md#d-030).

---

## 먼저 판단할 것 — 이 런북이 맞는 상황인가

| 상황 | 이 런북 |
|---|---|
| 원장(`license`)만 사라졌다 | **맞다** |
| 스키마 전체를 정합적인 백업에서 복원했다 | **아니다.** 가드 행도 함께 돌아왔으므로 추가 조치가 없다 |
| 원장은 있는데 download 에 권한이 없다 | **아니다.** license 는 멀쩡하다 — D-010 경로다 |

두 번째 줄이 중요하다. 가드(`processed_event`)와 원장은 **같은 트랜잭션**에서 쓰인다.
정합적인 백업이면 둘의 시점이 같아서 아무 문제가 없다. 이 런북이 필요한 것은
**둘의 시점이 어긋난 경우**뿐이다.

---

## 왜 되읽기만으로는 안 되는가

```
PaymentCompleted(event_id=E)
        │
        ▼
  ProcessedEventGuard.firstDelivery(E, "license")
        │
        ├─ 이미 있다 → return false → issue() 가 그대로 종료   ← 여기서 끝난다
        │                              예외도 없고 이벤트도 없다
        └─ 없다     → 지급 + LicenseIssued 재발행
```

가드는 `(event_id, consumer_group)` 로 판정한다. 원장만 지워지고 가드 행이 살아 있으면
되읽은 이벤트가 **전부 "이미 처리함"** 이 된다.

그리고 **운영이 쓸 수 있는 재처리 수단은 셋 다 `event_id` 를 보존한다.**

| 수단 | event_id |
|---|---|
| 컨슈머 그룹 오프셋 리셋 | 원본 그대로 |
| `POST /api/v1/ops/dlt/replay` | 원본 헤더를 그대로 되돌린다 |
| `POST /api/v1/ops/outbox/dead/{id}/requeue` | 적재된 이벤트 그대로 |

즉 **`event_id` 가 바뀌는 경로는 운영에 없다.** 가드를 지우는 것 말고는 방법이 없다.

**가장 나쁜 것은 조용하다는 점이다** — 예외도 실패 지표도 없고 로그는 `info` 다.
0건 복구가 성공과 구분되지 않으므로, 마지막 4번(대사)을 반드시 한다.

---

## 절차

`scripts/chaos/recover-license.sh` 가 아래를 순서대로 한다. 손으로 할 때도 순서는 같다.

### 0. 멈춘다 — 복구 대상 범위를 고정한다

```bash
docker stop stove-apps-license-1
```

**컨슈머가 도는 채로 오프셋을 되돌리면 리셋이 거부된다**(그룹이 활성 상태다).
그리고 복구 중에 새 이벤트가 섞이면 "몇 건이 복구됐는가"를 셀 수 없다.

### 1. 대사 기준을 먼저 센다

```sql
select count(*) from stove_payment.payment where status = 'PAID';   -- 있어야 할 수
select count(distinct order_no) from stove_license.license;          -- 지금 있는 수
```

**차이가 복구 목표다.** 이 숫자를 적어 두지 않으면 4번에서 판정할 것이 없다.

### 2. 가드 행을 지운다 — 빠지면 0건 복구된다

```sql
-- 범위를 좁힌다. 전체 삭제는 정상 처리된 이벤트의 멱등 보호까지 벗긴다.
delete from stove_license.processed_event
where consumer_group = 'license'
  and event_type = 'PaymentCompleted'
  and processed_at >= '<유실 구간 시작>';
```

`event_type` 을 반드시 건다. `PaymentCancelled`(회수) 가드까지 지우면 **되읽기가 회수를
다시 실행**한다. 회수는 지급과 달리 멱등 가드 말고는 막는 것이 없다.

### 3. 오프셋을 되돌린다

```bash
docker exec stove-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:19092 --group license \
  --topic stove.payment.v1 --reset-offsets --to-earliest --execute
```

`--to-earliest` 대신 `--to-datetime <유실 구간 시작>` 으로 좁히는 편이 낫다.
되읽는 양이 곧 복구 시간이다.

### 4. 켜고, **대사로 판정한다**

```bash
docker start stove-apps-license-1
```

```sql
select
  (select count(*) from stove_payment.payment where status = 'PAID')  as 결제완료,
  (select count(distinct order_no) from stove_license.license)        as 라이선스;
```

**두 값이 같아질 때까지가 복구다.** 여기까지 하지 않으면 2번을 건너뛴 채
"복구 절차를 돌렸다" 로 끝나고, 실제로는 0건이 복구된다.

---

## 실측 (2026-08-15, 로컬 전체 스택)

깨끗한 스택에 200건을 넣고 원장만 지운 뒤, `scripts/chaos/recover-license.sh` 를 두 번 돌렸다.
두 회차의 차이는 **2번 단계 한 줄뿐이다.**

```bash
scripts/chaos/recover-license.sh --seed 200 --skip-inbox-purge   # 왼쪽 열
scripts/chaos/recover-license.sh                                  # 오른쪽 열
```

| | 2번 단계 없이 | 2번 단계 포함 |
|---|---:|---:|
| 복구된 주문 | **0** | **200** |
| 대사(결제완료 : 라이선스) | **200 : 0** | 200 : 200 |
| 기동~대사 일치 | 11초(멈춰 있었다) | 12초 |
| 복구 속도 | — | **16.7 건/s** |
| 컨슈머 ERROR 로그 | **0** | 0 |

왼쪽 열의 마지막 줄이 이 런북의 존재 이유다. **아무것도 복구되지 않았는데
아무 데서도 실패가 관측되지 않는다.** 예외도 없고, 실패 지표도 안 오르고, 로그는 `info`
("중복 이벤트 스킵")다. 그래서 4번의 대사를 절차에 넣었다 — **복구를 판정할 수 있는 자리가
거기 하나뿐이다.**

복구 속도는 1초 간격 폴링으로 잰 값이라 하한이다. 재는 목적이 "얼마나 빠른가"가 아니라
**"200건이 몇 분이 아니라 몇 초짜리 작업인가"**이므로 이 정밀도로 충분하다.
