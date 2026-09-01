package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    /** 운영 조회용. <b>승인 매칭에는 쓰지 않는다.</b> [D-008] */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** 콜백 처리용. 행을 잠그고 읽어 동시 중복 승인 창을 닫는다. docs/code-notes.md */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderNo = :orderNo")
    Optional<Payment> findByOrderNoForUpdate(@Param("orderNo") String orderNo);

    /**
     * 재개 대상 중 <b>지금 다시 시도해도 되는 것</b>.
     * {@code null} 을 함께 집어야 한다 — 컬럼이 생기기 전 행이 영원히 빠진다. docs/code-notes.md
     */
    @Query("""
            select p from Payment p
            where p.status = :status
              and (p.nextCancelAttemptAt is null or p.nextCancelAttemptAt <= :now)
            order by p.nextCancelAttemptAt asc
            """)
    List<Payment> findDueForCancelRetry(@Param("status") PaymentStatus status, @Param("now") Instant now);

    long countByStatus(PaymentStatus status);

    /** 예산을 넘겨 <b>사람이 봐야 하는</b> 건수. 알람은 이쪽에 건다. docs/code-notes.md */
    long countByStatusAndCancelingSinceBefore(PaymentStatus status, Instant threshold);
}
