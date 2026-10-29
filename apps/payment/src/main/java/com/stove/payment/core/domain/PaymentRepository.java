package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment,Long> {
    Optional<Payment> findByOrderNo(String orderNo);
    Optional<Payment> findByRequestEventId(String eventId);
    Optional<Payment> findByPaymentId(String paymentId);
    Optional<Payment> findByOrderNoAndBuyerSubject(String orderNo,String buyerSubject);
    Page<Payment> findByBuyerSubject(String buyerSubject,Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderNo=:orderNo")
    Optional<Payment> lockByOrderNo(String orderNo);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentId=:paymentId")
    Optional<Payment> lockByPaymentId(String paymentId);
    @Query("select p.paymentId from Payment p where p.provider=:provider "
            + "and ((p.status=:status and p.nextAttemptAt<=:now) or p.nextFailureLookupAt<=:now) order by coalesce(p.nextAttemptAt,p.nextFailureLookupAt),p.id")
    List<String> due(PaymentStatus status,String provider,Instant now,Pageable pageable);
}
