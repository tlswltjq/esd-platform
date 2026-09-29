#!/usr/bin/env bash
#
# DB 동시성 실험 여섯 개 — docs/technical-interview-guide.md 의 "[확인됨]" 근거를 재현한다.
#
#   ./scripts/db-lab/run.sh            # 전부 (약 2분)
#   ./scripts/db-lab/run.sh 1 4        # 골라서
#
# 필요한 것은 Docker 하나다. 버리는 MySQL 8.0 컨테이너를 띄우고 끝나면 지운다.
# 앱·Kafka 는 띄우지 않는다 — 여기서 보려는 것은 InnoDB 가 이 저장소의 쿼리와 인덱스에
# 어떤 락을 거는가이고, 그건 SQL 세션 두세 개면 드러난다.
#
# 세션을 겹치는 방법: 한 세션을 백그라운드로 열어 SLEEP 으로 트랜잭션을 붙잡아 두고,
# 그 사이에 다른 세션을 돌린다. SLEEP 은 "Kafka ack 를 기다리는 릴레이" 나
# "처리 중인 컨슈머" 를 흉내 낸다.
#
# 백그라운드 세션의 결과는 그 세션이 끝날 때 한꺼번에 찍힌다. 그래서 순서를 판단할 때는
# 출력 순서가 아니라 각 줄의 `at` (서버 시각) 컬럼을 본다.
set -uo pipefail

CONTAINER=stove-db-lab
IMAGE=mysql:8.0            # docker-compose.yml 과 같은 태그
DIR="$(cd "$(dirname "$0")" && pwd)"

# SQL 은 표준 입력으로 넘긴다. `-e` 로 넘기면 --force 가 있어도 에러 뒤 문장이 실행되지 않는다.
q()  { docker exec -i "$CONTAINER" mysql -uroot -proot --table lab 2>&1 | grep -v "Using a password"; }
qf() { docker exec -i "$CONTAINER" mysql -uroot -proot --table --force lab 2>&1 | grep -v "Using a password"; }
reset_schema() { q < "$DIR/schema.sql"; }
title() { printf '\n=== 실험 %s — %s\n' "$1" "$2"; }
note()  { printf '  %s\n' "$*"; }

RELAY_SQL="SELECT id FROM outbox_event
 WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(6))
 ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED"     # OutboxEventRepository#lockPendingBatch 그대로

OUTBOX_INSERT="INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at)
 VALUES (UUID(),'Order','ORD-NEW','OrderCreated','stove.order.v1','ORD-NEW','{}','PENDING',NOW(6))"   # OutboxRecorder#record

LOCKS_SQL="SELECT index_name, lock_type, lock_mode, COUNT(*) AS locks FROM performance_schema.data_locks
 WHERE object_name='outbox_event' GROUP BY index_name, lock_type, lock_mode ORDER BY index_name, lock_mode"

seed_outbox() {   # $1 = SENT 건수, $2 = PENDING 건수
    reset_schema
    q >/dev/null <<SQL
SET SESSION cte_max_recursion_depth = 200000;
INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at,sent_at)
WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n < $1)
SELECT CONCAT('sent-',n),'Order',CONCAT('ORD',n),'OrderCreated','stove.order.v1',CONCAT('ORD',n),'{}','SENT',NOW(6),NOW(6) FROM s;
INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at)
WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM s WHERE n < $2)
SELECT CONCAT('pend-',n),'Order',CONCAT('ORDP',n),'OrderCreated','stove.order.v1',CONCAT('ORDP',n),'{}','PENDING',NOW(6) FROM s;
ANALYZE TABLE outbox_event;
SQL
}

# 릴레이가 배치를 잠근 채 $2 초 기다리는 동안 락을 세고, 비즈니스 INSERT 가 얼마나 기다리는지 잰다.
relay_vs_insert() {   # $1 = 격리 수준, $2 = 릴레이 대기 초, $3 = INSERT 의 lock_wait_timeout
    ( q >/dev/null <<SQL ) &
SET SESSION transaction_isolation = '$1';
BEGIN; $RELAY_SQL; SELECT SLEEP($2); COMMIT;
SQL
    sleep 1.5
    q <<SQL
$LOCKS_SQL;
SQL
    qf <<SQL
SET @t0 = NOW(6);
SET SESSION transaction_isolation = '$1';
SET SESSION innodb_lock_wait_timeout = $3;
$OUTBOX_INSERT;
SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6)) / 1000000, 2) AS insert_returned_after_seconds;
SQL
    wait
}

start() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1
    docker run -d --rm --name "$CONTAINER" -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=lab "$IMAGE" \
        --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci >/dev/null || exit 1
    printf '버리는 MySQL 을 띄우는 중'
    for _ in $(seq 1 90); do
        if docker exec "$CONTAINER" mysql -uroot -proot -e "SELECT 1" lab >/dev/null 2>&1; then
            echo
            # 문서가 "MySQL 8.0 기본값" 이라고 적은 값들을 같은 컨테이너에서 함께 찍는다.
            q <<'SQL'
SELECT VERSION() AS version, @@version_compile_machine AS arch, @@transaction_isolation AS isolation,
       @@innodb_lock_wait_timeout AS lock_wait_timeout_s, @@innodb_rollback_on_timeout AS rollback_on_timeout,
       @@innodb_deadlock_detect AS deadlock_detect, @@max_connections AS max_connections;
SELECT @@innodb_flush_log_at_trx_commit AS flush_log_at_trx_commit, @@sync_binlog AS sync_binlog,
       @@log_bin AS log_bin, @@binlog_format AS binlog_format, @@innodb_doublewrite AS doublewrite,
       @@innodb_autoinc_lock_mode AS autoinc_lock_mode, @@wait_timeout AS wait_timeout_s;
SQL
            return 0
        fi
        printf '.'; sleep 2
    done
    echo " 기동 실패"; exit 1
}
trap 'docker rm -f "$CONTAINER" >/dev/null 2>&1' EXIT

# ───────────────────────────────────────────────────────────────────────────
exp1() {
    title 1 "릴레이가 배치를 잠근 동안 주문 생성의 outbox INSERT 가 막히는가 (RR vs RC)"
    note "코드: OutboxRelay#relayOneBatch — 조회 · Kafka 전송 · ack 대기 · SENT 표시가 한 트랜잭션"
    note "데이터: SENT 100,000 + PENDING 5 (outbox 는 SENT 를 지우지 않으므로 평시 모양이 이렇다)"
    seed_outbox 100000 5
    for iso in REPEATABLE-READ READ-COMMITTED; do
        echo; note "[$iso] 릴레이가 ack 를 6초 기다린다"
        relay_vs_insert "$iso" 6 10
    done
    note "읽는 법: lock_mode 'X' = 넥스트키 락(레코드 + 그 앞 갭), 'X,REC_NOT_GAP' = 레코드 락만."
    note "        RR 에서는 새 PENDING 행이 들어갈 갭이 잠겨 INSERT 가 릴레이 커밋까지 기다린다."
}

exp2() {
    title 2 "적체가 쌓였을 때 LIMIT 200 인 조회가 몇 행을 잠그는가 — 계획은 분포를 따라 바뀐다"
    for shape in "100000 5000" "20000 5000"; do
        set -- $shape
        echo; note "데이터: SENT $1 + PENDING $2"
        seed_outbox "$1" "$2"
        q <<SQL | grep -E " type:|key:|rows:|Extra:"
EXPLAIN $RELAY_SQL\G
SQL
        relay_vs_insert REPEATABLE-READ 5 10
    done
    note "읽는 법: 어느 계획이든 가져가는 것은 200건인데 잠그는 것은 수천~수만 건이다."
    note "        range+filesort 는 LIMIT 으로 스캔을 멈추지 못하고, PRIMARY 스캔은 조건에 안 맞는 SENT 행까지 잠근다."
    note "        INSERT 가 막히는지는 계획에 달렸다 — 보조 인덱스의 갭을 잠그는 앞의 계획에서만 막힌다."
}

exp3() {
    title 3 "릴레이 트랜잭션이 lock_wait_timeout 보다 길면 (브로커 장애 흉내)"
    note "실제 기본값: 릴레이 대기 상한 = delivery.timeout.ms 120s, INSERT 대기 한도 = innodb_lock_wait_timeout 50s"
    note "여기서는 8s / 3s 로 줄여 결말만 본다"
    seed_outbox 100000 5
    relay_vs_insert REPEATABLE-READ 8 3
    note "읽는 법: 1205 는 그 문장만 되돌린다(innodb_rollback_on_timeout=OFF). 스프링은 예외를 받아 트랜잭션 전체를 롤백한다."
}

exp4() {
    title 4 "만료 스윕 vs 결제 확정 — @Version 없는 read-modify-write (Lost Update)"
    note "코드: OrderExpiryService#expireStaleOrders ↔ OrderCommandService#confirmPaid, 둘 다 잠그지 않고 읽는다"
    reset_schema
    q <<'SQL'
INSERT INTO orders(order_no,member_id,status,total_amount,created_at,updated_at)
VALUES ('ORD-A',7,'CREATED',39000,NOW(6)-INTERVAL 61 MINUTE,NOW(6)-INTERVAL 61 MINUTE);
SQL
    # 스윕: 스냅샷으로 CREATED 를 읽고, 잠시 뒤 Hibernate 처럼 모든 컬럼을 자기 스냅샷 값으로 쓴다
    ( q <<'SQL' ) &
BEGIN;
SELECT TIME(NOW(3)) AS at, '[sweep] read' AS who, status, paid_at
  FROM orders WHERE status='CREATED' AND created_at < NOW(6) - INTERVAL 60 MINUTE;
DO SLEEP(3);
UPDATE orders SET status='EXPIRED', expired_at=NOW(3), paid_at=NULL, updated_at=NOW(6) WHERE id=1;
SELECT TIME(NOW(3)) AS at, '[sweep] update' AS who, ROW_COUNT() AS affected_rows;
COMMIT;
SQL
    sleep 1
    q <<'SQL'
BEGIN;
SELECT TIME(NOW(3)) AS at, '[confirm] read' AS who, status FROM orders WHERE order_no='ORD-A';
UPDATE orders SET status='PAID', paid_at=NOW(6), expired_at=NULL, updated_at=NOW(6) WHERE id=1;
COMMIT;
SELECT TIME(NOW(3)) AS at, '[confirm] PAID committed' AS who;
SQL
    wait
    q <<'SQL'
SELECT 'final' AS result, status, paid_at, expired_at FROM orders WHERE id=1;
SQL
    note "읽는 법: at 순서는 sweep read → confirm commit → sweep update. 에러가 하나도 없는데 PAID 가 사라졌다."

    echo; note "대안 — 조건부 UPDATE(compare-and-set): 늦게 온 쪽이 0건으로 '진다'"
    reset_schema
    q <<'SQL'
INSERT INTO orders(order_no,member_id,status,total_amount,created_at,updated_at)
VALUES ('ORD-A',7,'CREATED',39000,NOW(6)-INTERVAL 61 MINUTE,NOW(6)-INTERVAL 61 MINUTE);
UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=1 AND status='CREATED';
SELECT '[confirm]' AS who, ROW_COUNT() AS affected_rows;
UPDATE orders SET status='EXPIRED', expired_at=NOW(3) WHERE id=1 AND status='CREATED';
SELECT '[sweep]' AS who, ROW_COUNT() AS affected_rows;
SELECT 'final' AS result, status, paid_at, expired_at FROM orders WHERE id=1;
SQL
}

exp5() {
    title 5 "같은 eventId 를 두 트랜잭션이 동시에 처리 — Inbox 는 무엇으로 막는가"
    note "코드: ProcessedEventGuard#firstDelivery — existsBy... 로 확인한 뒤 save"
    reset_schema
    ( q <<'SQL' ) &
BEGIN;
SELECT TIME(NOW(3)) AS at, '[A] exists' AS who, COUNT(*) AS n
  FROM processed_event WHERE event_id='E1' AND consumer_group='license';
INSERT INTO processed_event(event_id,consumer_group,event_type,processed_at) VALUES ('E1','license','PaymentCompleted',NOW(6));
DO SLEEP(3);
COMMIT;
SELECT TIME(NOW(3)) AS at, '[A] committed' AS who;
SQL
    sleep 1
    qf <<'SQL'
BEGIN;
SELECT TIME(NOW(3)) AS at, '[B] exists' AS who, COUNT(*) AS n
  FROM processed_event WHERE event_id='E1' AND consumer_group='license';
INSERT INTO processed_event(event_id,consumer_group,event_type,processed_at) VALUES ('E1','license','PaymentCompleted',NOW(6));
SELECT TIME(NOW(3)) AS at, '[B] insert returned' AS who;
ROLLBACK;
SQL
    wait
    note "읽는 법: B 의 exists 는 0 이다 — A 의 미커밋 행은 B 의 스냅샷에 없다. 막은 것은 유니크 인덱스다."
    note "        B 의 INSERT 는 A 가 커밋할 때까지 기다린 뒤 1062 를 받는다(A 가 롤백했다면 성공한다)."
}

exp6() {
    title 6 "같은 유니크 키를 세 트랜잭션이 넣다가 첫째가 롤백하면 — 데드락"
    reset_schema
    INS="INSERT INTO processed_event(event_id,consumer_group,event_type,processed_at) VALUES ('E9','license','PaymentCompleted',NOW(6))"
    ( q >/dev/null <<SQL ) &
BEGIN; $INS; DO SLEEP(3); ROLLBACK;
SQL
    sleep 1
    ( q <<SQL ) &
BEGIN; $INS; DO SLEEP(1); COMMIT; SELECT '[T2] committed' AS result;
SQL
    ( q <<SQL ) &
BEGIN; $INS; DO SLEEP(1); COMMIT; SELECT '[T3] committed' AS result;
SQL
    wait
    docker exec "$CONTAINER" mysql -uroot -proot -e "SHOW ENGINE INNODB STATUS\\G" 2>/dev/null \
        | sed -n '/LATEST DETECTED DEADLOCK/,/WE ROLL BACK/p' | grep -E "HOLDS|WAITING|lock mode|lock_mode|WE ROLL BACK"
    note "읽는 법: 둘 다 중복 검사용 S 락을 쥔 채 INSERT 용 X(insert intention) 를 기다린다 → 한쪽이 1213 으로 희생된다."
}

# ───────────────────────────────────────────────────────────────────────────
selected=("$@"); [ ${#selected[@]} -eq 0 ] && selected=(1 2 3 4 5 6)
for n in "${selected[@]}"; do
    case "$n" in 1|2|3|4|5|6) ;; *) echo "알 수 없는 실험 번호: $n (1~6)"; exit 2 ;; esac
done
start
for n in "${selected[@]}"; do "exp$n"; done
echo; echo "끝. 컨테이너는 지운다."
