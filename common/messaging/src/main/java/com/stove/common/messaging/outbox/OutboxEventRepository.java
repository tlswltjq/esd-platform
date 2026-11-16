package com.stove.common.messaging.outbox;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 릴레이 인스턴스가 여러 대여도 같은 레코드를 중복 발행하지 않도록
     * MySQL 8 의 {@code FOR UPDATE SKIP LOCKED} 로 배치를 선점한다.
     *
     * <p>{@code next_attempt_at} 이 지난 것만 집는다 — 실패한 이벤트를 곧바로 다시 시도하면
     * 재시도 예산이 폴링 주기만큼의 시간 안에 소진되어 짧은 장애도 넘기지 못한다.
     *
     * <p><b>같은 키의 앞 이벤트가 이번에 나갈 수 없으면 뒤 이벤트도 집지 않는다</b>([결정 155] 발행 쪽).
     * 앞 이벤트가 {@code DEAD}(회수 전까지 그 키는 멈춘다, D-055)이거나 재시도 시각을 기다리는
     * {@code PENDING}(그 배치에 없던 뒤 이벤트가 먼저 나가지 않게, D-056)이면 뒤를 건너뛴다.
     * 앞도 지금 나갈 수 있으면 둘 다 집어 릴레이의 키 웨이브가 순서대로 보낸다.
     * 이 판정은 릴레이가 한 인스턴스라는 전제에서 완전하다. 여러 인스턴스가 {@code SKIP LOCKED}로
     * 같은 키를 나눠 집는 경우는 다루지 않는다([결정 205]).
     * 하위 조회는 {@code idx_outbox_key_order (partition_key, id)}를 쓴다. 바깥 {@code FOR UPDATE}는
     * 하위 조회의 행을 잠그지 않는다.
     */
    @Query(value = """
            SELECT * FROM outbox_event o
             WHERE o.status = 'PENDING'
               AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= NOW(6))
               AND NOT EXISTS (
                     SELECT 1 FROM outbox_event earlier
                      WHERE earlier.partition_key = o.partition_key
                        AND earlier.id < o.id
                        AND (earlier.status = 'DEAD'
                             OR (earlier.status = 'PENDING' AND earlier.next_attempt_at > NOW(6))))
             ORDER BY o.id
             LIMIT :size
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("size") int size);

    long countByStatus(OutboxEvent.OutboxStatus status);

    /** 운영 회수용. 원인을 제거한 뒤 {@link OutboxEvent#requeue()} 로 되살린다. */
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxEvent.OutboxStatus status);

    /**
     * 운영 재발행용 한 건 잠금([결정 129]). 같은 행을 두 운영자가 동시에 재발행해도 한 번만 대기로 돌아간다.
     * 릴레이는 {@code SKIP LOCKED} 로 집으므로 이 잠금을 기다리지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEvent e WHERE e.eventId = :eventId")
    Optional<OutboxEvent> lockByEventId(@Param("eventId") String eventId);
}
