package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
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

    /**
     * 취소 착수는 커밋됐는데 확정까지 못 간 건들.
     *
     * <p>{@code CANCELING} 은 <b>돈이 나갔는지 불확실한 상태</b>다 — PG 호출 직전에 멈췄을 수도,
     * 호출은 갔는데 확정 커밋에서 멈췄을 수도 있다. 어느 쪽이든 사람이 볼 때까지 그대로 남는 것이
     * 지금까지의 동작이었다. 재개가 안전한 이유는 PG 취소가 {@code pgTxId} 기준 멱등이기 때문이다
     * ({@link com.stove.payment.core.port.PgClient#cancel} 계약).
     *
     * <p>{@code updatedAt} 기준으로 자른다 — 방금 착수한 건까지 집으면
     * 정상 진행 중인 환불을 옆에서 한 번 더 부른다.
     */
    List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, Instant threshold);

    long countByStatus(PaymentStatus status);
}
