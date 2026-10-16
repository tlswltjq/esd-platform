package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.*;

public interface PaymentRepository extends JpaRepository<Payment,Long> {
    Optional<Payment> findByOrderNo(String orderNo);
    Optional<Payment> findByRequestEventId(String eventId);
    Optional<Payment> findByPaymentId(String paymentId);
    Optional<Payment> findByOrderNoAndBuyerSubject(String orderNo,String buyerSubject);
    Page<Payment> findByBuyerSubject(String buyerSubject,Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentId=:paymentId")
    Optional<Payment> lockByPaymentId(String paymentId);
    @Query("select p.paymentId from Payment p where p.status=:status and p.nextAttemptAt<=:now order by p.nextAttemptAt,p.id")
    List<String> due(PaymentStatus status,Instant now,Pageable pageable);
}
