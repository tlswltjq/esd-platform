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
     */
    @Query(value = """
            SELECT * FROM outbox_event
             WHERE status = 'PENDING'
               AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(6))
             ORDER BY id
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
