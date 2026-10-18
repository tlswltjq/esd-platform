package com.stove.order.core.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByBuyerSubjectAndRequestId(String buyerSubject, String requestId);
    Optional<Order> findByOrderNoAndBuyerSubject(String orderNo, String buyerSubject);
    Optional<Order> findByPaymentResultEventId(String eventId);
    Page<Order> findByBuyerSubject(String buyerSubject, Pageable pageable);
    @Query("select o from Order o where o.buyerSubject = :buyer and o.status in :statuses "
            + "and (o.productId = :productId or o.productCode = :code)")
    List<Order> findPurchaseConflicts(String buyer, Long productId, String code, List<OrderStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.orderNo = :orderNo")
    Optional<Order> lockByOrderNo(@Param("orderNo") String orderNo);
}
