package com.stove.payment.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
