package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    /**
     * 운영 조회용. 승인 매칭에는 쓰지 않는다 —
     * 멱등키는 PG 가 만드는 값이라 다른 주문의 결제를 물어올 수 있기 때문이다(D-008).
     */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * 콜백 처리용. 결제 행을 잠그고 읽는다.
     *
     * <p>같은 주문의 콜백이 동시에 두 번 들어오면 둘 다 {@code PENDING} 을 읽고 둘 다 승인해
     * {@code PaymentCompleted} 가 두 번 나갈 수 있다. 잠금이 그 창을 닫는다 —
     * 두 번째는 첫 번째 커밋 뒤에 읽으므로 {@code PAID} 를 보고 중복으로 판정된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderNo = :orderNo")
    Optional<Payment> findByOrderNoForUpdate(@Param("orderNo") String orderNo);
}
