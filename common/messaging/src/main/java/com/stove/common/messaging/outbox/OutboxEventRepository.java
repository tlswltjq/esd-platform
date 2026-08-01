package com.stove.common.messaging.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 릴레이 인스턴스가 여러 대여도 같은 레코드를 중복 발행하지 않도록
     * MySQL 8 의 {@code FOR UPDATE SKIP LOCKED} 로 배치를 선점한다.
     */
    @Query(value = """
            SELECT * FROM outbox_event
             WHERE status = 'PENDING'
             ORDER BY id
             LIMIT :size
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("size") int size);

    long countByStatus(OutboxEvent.OutboxStatus status);
}
