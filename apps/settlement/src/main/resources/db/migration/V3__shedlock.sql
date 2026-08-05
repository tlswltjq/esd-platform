-- 월 마감 배치의 분산 락 저장소(ShedLock).
--
-- 인스턴스가 여러 대일 때 @Scheduled 는 대수만큼 동시에 발화한다. 마감은 금전 확정이고
-- 세금계산서 발행이라는 되돌릴 수 없는 외부 호출을 동반하므로, 실행 자체를 한 번으로 묶는다.
--
-- 잠금을 DB 로 잡는 이유는 새 인프라를 늘리지 않기 위해서다. 이 서비스는 이미 MySQL 을 쓴다.
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
