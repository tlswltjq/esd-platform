package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentOrderLifecycleRepository extends JpaRepository<PaymentOrderLifecycle,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from PaymentOrderLifecycle l where l.orderNo=:orderNo")
    Optional<PaymentOrderLifecycle> lockByOrderNo(String orderNo);
}
