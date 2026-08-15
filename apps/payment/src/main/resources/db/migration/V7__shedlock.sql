-- 중단된 취소 재개 스윕의 분산 락 저장소(ShedLock).
--
-- 인스턴스가 여러 대일 때 @Scheduled 는 대수만큼 동시에 발화한다. 재개는 PG 환불이라는
-- 되돌릴 수 없는 외부 호출을 동반하므로 실행 자체를 한 번으로 묶는다.
--
-- **이중 환불을 막는 장치가 아니다.** 그건 PgClient#cancel 의 pgTxId 멱등 계약이 맡는다.
-- 락이 막는 것은 "몇 번 시도했는지 알 수 없게 되는 것" 이다 — 재개 로그와 PaymentCancelled 가
-- 대수만큼 겹치면 PG 연동이 정상인지 보는 창이 닫힌다 (RefundSweeper 주석).
--
-- 잠금을 DB 로 잡는 이유는 새 인프라를 늘리지 않기 위해서다. settlement 와 같은 판단이고
-- 스키마도 같다.
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
