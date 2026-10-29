package com.stove.payment.core.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FailedPaymentAttemptRepository extends JpaRepository<FailedPaymentAttempt, Long> {
    Optional<FailedPaymentAttempt> findBySupplierAttemptId(String supplierAttemptId);
    Page<FailedPaymentAttempt> findByPaymentId(String paymentId, Pageable page);
}
