package com.stove.common.messaging.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
