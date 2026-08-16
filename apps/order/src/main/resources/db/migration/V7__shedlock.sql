-- 만료 스윕의 분산 락 저장소(ShedLock).
--
-- 인스턴스가 여러 대면 @Scheduled 는 대수만큼 동시에 발화한다. 만료는 되돌릴 수 없는 외부 호출을
-- 동반하지 않으므로 **payment 의 환불 재개와 달리 동시 실행이 사고는 아니다** --
-- 같은 행을 두 인스턴스가 집으면 하나는 CONFLICT 로 튕긴다.
--
-- 그런데도 잠그는 이유는 **배치 크기가 뜻을 잃기 때문**이다. 대수만큼 동시에 돌면 한 회차에
-- 만료되는 수가 batch-size 가 아니라 batch-size × 인스턴스 수가 된다. 밀린 것을 나눠서
-- 처리하려고 둔 상한인데 그 상한이 인스턴스 수에 따라 달라지면 상한이 아니다.
--
-- 잠금을 DB 로 잡는 이유는 새 인프라를 늘리지 않기 위해서다. settlement · payment 와 같은 판단이고
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
