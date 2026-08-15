# 장애 주입

부하를 건 채로 장애를 넣고, **주문 하나하나가 어떻게 끝났는지** 센다.
측정 결과와 분석은 [docs/chaos.md](../../docs/chaos.md) 에 있다.

`scripts/perf/` 가 "얼마나 빠른가"를 잰다면 여기는 **"틀렸을 때 어떻게 틀리는가"**를 잰다.

## 왜 이걸 재는가

이 시스템에서 돈이 잘못 움직이는 자리는 전부 **경로가 깨졌을 때** 열린다 —
Saga 보상, 컨슈머 재시도, 멱등 가드, DLT. 그 넷은 평상시에 아무 일도 하지 않으므로
**부하 테스트로는 한 번도 실행되지 않는다.**

## 파일

| | |
|---|---|
| `fault.sh` | 이름 붙은 장애를 넣고 뺀다. **그리고 정말 들어갔는지 프로브로 센다** |
| `run-scenario.sh` | 부하 → 주입 → 유지 → 복구 → 드레인 → 주문별 결말 판정 |
| `recover-license.sh` | 원장 유실 복구 런북을 그대로 실행하고 대사한다 |

## 사전 조건

전체 스택이 떠 있어야 한다. 게이트가 통과해야 시작한다.

```bash
docker compose up -d
docker compose -f docker-compose.apps.yml up -d --build
bash scripts/stack-wait.sh      # 14건 전부 통과해야 한다
```

## 실행

```bash
# 지금 무엇이 주입되어 있는가 (지난 회차가 안 풀렸는지부터 본다)
scripts/chaos/fault.sh status

# 대조군 — 장애 없이 같은 부하. 이걸 먼저 돌린다
scripts/chaos/run-scenario.sh --fault none --orders 40 --rate 2 --hold 0 --out runs/control

# 원장 테이블만 끊는다 (보상 경로가 살아 있는 조건)
scripts/chaos/run-scenario.sh --fault license-table-denied --orders 40 --rate 2 \
  --inject-after 5 --hold 60 --out runs/table-denied

# 스키마 전체를 끊는다 (보상 경로도 함께 죽는 조건)
scripts/chaos/run-scenario.sh --fault license-db-denied --orders 40 --rate 2 \
  --inject-after 5 --hold 60 --out runs/db-denied

# 원장 유실 복구 — 절차서의 한 줄을 빼고도 돌려 본다
scripts/chaos/recover-license.sh --seed 200 --skip-inbox-purge
scripts/chaos/recover-license.sh
```

## 결말 네 칸

| 결말 | 결제 | 라이선스 | 뜻 |
|---|---|---|---|
| `fulfilled` | PAID | 있음 | 정상 |
| `parked` | PAID | 없음 | 보류(DLT)에 있어야 한다 — **되돌릴 수 있다** |
| `refunded` | CANCELED | 없음 | 환불됨 — 장애가 원인이면 오지급 환불. **되돌아오지 않는다** |
| `inconsistent` | CANCELED | 있음 | 환불했는데 물건은 줬다 |

판정은 API 폴링이 아니라 **DB 에서 직접** 읽는다. 폴링은 "제한 시간 안에 보였는가"고,
물어야 하는 것은 "결국 어떻게 끝났는가"다.

## 규칙 — 이 회차는 쓸 수 없다

`run-scenario.sh` 는 아래 셋 중 하나라도 걸리면 **종료 코드 2 로 멈춘다.**

1. **시작 상태가 깨끗하지 않다** — 지난 회차의 장애가 안 풀렸으면 '주입 전' 기준선이 없다
2. **주입이 반영되지 않았다** — 프로브가 여전히 200 이면 그 회차의 초록은 "견뎠다"가 아니라 "안 넣었다"다
3. **장애가 번졌다** — 대조 프로브(order)가 200 이 아니면 격리되지 않은 것이라 못 쓴다

2번이 이 하네스의 존재 이유다. 장애 실험의 실패 모드는 "장애가 안 났다"가 아니라
**"장애가 안 났는데 났다고 믿는다"** 이고, 그 상태는 전 지표가 초록이라
"우리 시스템은 장애를 견딘다"로 읽힌다.

> 실제로 첫 회차에서 걸렸다. `REVOKE` 만으로는 아무것도 안 끊겼다 —
> MySQL 의 데이터베이스 단위 권한은 다음 `USE` 에 반영되는데 HikariCP 가 커넥션을 붙들고 있었다.
> `KILL` 로 재접속을 강제해야 했다. **프로브가 없었으면 "견뎠다"고 적을 뻔했다.**

## 장애를 컨테이너 정지로 만들지 않는 이유

서비스 7종이 MySQL 컨테이너 **하나**를 공유한다(스키마만 다르다).
`docker stop stove-mysql` 은 order·payment 까지 죽여서 **장애 중에 결제를 흘리는 것이 불가능해진다** —
재려던 것 자체가 사라진다.

MySQL 권한은 스키마·테이블 단위라 정확히 그 경계를 그어 주고, 되돌리는 것도 `GRANT` 한 줄이다.

| 이름 | 무엇이 끊기나 | 현실의 대응물 |
|---|---|---|
| `license-db-denied` | `stove_license` 스키마 전체 | 레플리카 페일오버 후 권한 부재 |
| `license-table-denied` | `license` 원장만 (outbox·inbox 정상) | 테이블 잠금, 끝나지 않은 DDL |
| `license-stopped` | 프로세스 자체 | 배포 사고, OOM |

## 회차가 끝나면 원상복구를 확인한다

```bash
scripts/chaos/fault.sh status      # 권한이 스키마 전체로 돌아왔는지
```

`run-scenario.sh` 는 정상 종료 시 복구까지 하지만, 중간에 끊으면 장애가 남는다.
**다음 회차의 사전 확인이 그걸 잡는다** — 그래서 사전 확인이 사후 기록보다 먼저다.
