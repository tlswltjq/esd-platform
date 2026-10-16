package com.stove.order.core.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByBuyerSubjectAndRequestId(String buyerSubject, String requestId);
    Optional<Order> findByOrderNoAndBuyerSubject(String orderNo, String buyerSubject);
    Optional<Order> findByPaymentResultEventId(String eventId);
    Page<Order> findByBuyerSubject(String buyerSubject, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.orderNo = :orderNo")
    Optional<Order> lockByOrderNo(@Param("orderNo") String orderNo);
}
