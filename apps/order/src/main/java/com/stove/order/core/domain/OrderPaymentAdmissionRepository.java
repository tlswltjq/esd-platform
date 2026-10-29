package com.stove.order.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderPaymentAdmissionRepository extends JpaRepository<OrderPaymentAdmission, Long> {
    Optional<OrderPaymentAdmission> findFirstByOrderNoOrderByIdDesc(String orderNo);
    Optional<OrderPaymentAdmission> findByAdmissionId(String admissionId);
}
