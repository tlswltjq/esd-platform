# db-lab — InnoDB 가 이 저장소의 쿼리에 거는 락을 눈으로 본다

[technical-interview-guide.md](../../docs/technical-interview-guide.md) 의 DB 주장 중 `[확인됨]` 으로 적은 것의 재현 스크립트다.
격리 수준과 락은 말로 설명하면 틀리기 쉬워서, 문장 대신 두세 개의 SQL 세션을 겹쳐 결과를 남겼다.

```bash
./scripts/db-lab/run.sh          # 전부, 약 2분
./scripts/db-lab/run.sh 1 4      # 골라서
```

Docker 만 있으면 된다. 버리는 `mysql:8.0` 컨테이너(`stove-db-lab`)를 띄우고 끝나면 지운다.
스키마([schema.sql](schema.sql))는 새로 설계한 것이 아니라 Flyway 마이그레이션을 합친 최종 형태다.

2026-09-14 에 두 호스트에서 돌렸다. MySQL 은 둘 다 `mysql:8.0` 태그의 8.0.46 이다.

- **로컬** — Apple M1, Docker Desktop 29.4.3 (arm64), 1회
- **OCI** — Ampere A1 4코어 / 23GB (aarch64), Docker 28.1.1. [measuring.md](../../docs/measuring.md) 규칙 11 대로 CI 러너를 멈추고 2회. 결과는 원격의 `runs/db-lab/2026-09-14-oci/`

| 실험 | 묻는 것 | 결과 — 대기 시간은 로컬 / OCI 1 / OCI 2 | 가이드 |
|---|---|---|---|
| 1 | 릴레이가 배치를 잠근 동안 outbox INSERT 가 막히는가 | RR: 넥스트키 락 6, INSERT **4.38 / 4.43 / 4.46초 대기** · RC: 레코드 락만, **0.00 / 0.01 / 0.01초** | 4.5, 8장 R1 |
| 2 | 적체 때 `LIMIT 200` 조회가 몇 행을 잠그는가 | 분포에 따라 계획이 바뀐다 — range+filesort 는 **5,007 + 5,001** 락(INSERT 3.33 / 3.42 / 3.44초 대기), PRIMARY 스캔은 **20,359** 락(대기 없음). 세 번 모두 같은 계획·같은 락 수 | 4.5, R1 |
| 3 | 릴레이 대기가 `innodb_lock_wait_timeout` 을 넘기면 | INSERT 가 **ERROR 1205** 로 실패 (3.03 / 3.00 / 3.01초에 포기, 한도 3초) | 6.4, R1 |
| 4 | 만료 스윕과 결제 확정이 겹치면 | 세 번 모두 에러 없이 `EXPIRED`, `paid_at=NULL` — **Lost Update**. 조건부 UPDATE 면 늦은 쪽이 0건 | 4.4, R3 |
| 5 | 같은 eventId 를 두 트랜잭션이 동시에 처리하면 | 세 번 모두 둘 다 `exists=0`, 뒤 트랜잭션이 앞의 커밋까지 **대기 후 1062** | 4.6, 6.2 |
| 6 | 같은 유니크 키를 셋이 넣고 첫째가 롤백하면 | 세 번 모두 둘이 S 락을 쥐고 X 를 기다리다 **ERROR 1213 데드락** | 4.7 |

시작 부분에 찍히는 서버 변수(`transaction_isolation`, `innodb_lock_wait_timeout`, `innodb_rollback_on_timeout`,
`innodb_flush_log_at_trx_commit`, `sync_binlog`, `binlog_format`, `max_connections` …)도 두 호스트에서 같았다.

**부하에서도 나타나는가** — 실험 1 의 락이 실제 트래픽에서 얼마인지는 이 스크립트가 아니라
[`scripts/perf/run-isolation-ab.sh`](../perf/run-isolation-ab.sh) 로 쟀다. 결과는 가이드 R1 에 있다.

**보는 법**

- `performance_schema.data_locks` 의 `lock_mode` — `X` 는 넥스트키 락(레코드 + 그 앞 갭), `X,REC_NOT_GAP` 은 레코드 락, `X,GAP` 은 갭 락이다.
- 백그라운드 세션의 결과는 그 세션이 끝날 때 찍힌다. 순서는 출력 순서가 아니라 `at` 컬럼으로 판단한다.
- 대기 시간은 기계마다 다르다. 판정 기준은 숫자의 크기가 아니라 **막혔는가(초 단위) / 안 막혔는가(0에 가까움)** 다.

**이 실험이 말하지 않는 것** — 앱을 띄워 부하를 건 결과가 아니다. 락이 걸린다는 것까지는 확인했고,
그것이 API 지연을 몇 ms 만드는지는 [performance.md](../../docs/performance.md) 9-3 절차로 다시 재야 한다.
