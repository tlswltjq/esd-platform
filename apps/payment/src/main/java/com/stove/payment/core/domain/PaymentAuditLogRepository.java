package com.stove.payment.core.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAuditLogRepository extends JpaRepository<PaymentAuditLog, Long> {
    long countByOrderNoAndAction(String orderNo, String action);
}
