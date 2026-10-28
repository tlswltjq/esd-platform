package com.stove.order.core.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    Optional<Refund> findByOrderNo(String orderNo);
    Optional<Refund> findByRefundId(String refundId);
    Page<Refund> findByStatus(RefundStatus status, Pageable pageable);
    @Query("select r.orderNo from Refund r where r.refundId = :refundId")
    Optional<String> findOrderNoByRefundId(@Param("refundId") String refundId);
    @Query("select r.refundId from Refund r where r.reviewRequestId = :requestId")
    Optional<String> findRefundIdByReviewRequestId(@Param("requestId") String requestId);
}
