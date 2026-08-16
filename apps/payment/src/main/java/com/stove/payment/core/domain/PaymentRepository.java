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
     * 취소 착수는 커밋됐는데 확정까지 못 간 건들 중 <b>지금 다시 시도해도 되는 것</b>.
     *
     * <p>{@code CANCELING} 은 <b>돈이 나갔는지 불확실한 상태</b>다 — PG 호출 직전에 멈췄을 수도,
     * 호출은 갔는데 확정 커밋에서 멈췄을 수도 있다. 재개가 안전한 이유는 PG 취소가
     * {@code pgTxId} 기준 멱등이기 때문이다({@link com.stove.payment.core.port.PgClient#cancel} 계약).
     *
     * <p><b>예전에는 {@code updatedAt} 으로 잘랐다.</b> 그 방식은 "방금 착수한 건을 안 집는다" 는
     * 해결했지만 백오프를 표현하지 못한다 — 재시도할 때마다 {@code updatedAt} 이 갱신되므로
     * 간격이 항상 고정(스윕 주기)이었다. 이제 {@code nextCancelAttemptAt} 이 그 자리를 대신하고,
     * 최초 유예도 그 값으로 표현된다.
     *
     * <p>{@code null} 을 함께 집는 이유는 <b>이 컬럼이 생기기 전에 만들어진 행</b> 때문이다.
     * 빼면 그 행들이 영원히 대상에서 빠진다 — 마이그레이션이 채우지 못하는 유일한 경우다.
     */
    @Query("""
            select p from Payment p
            where p.status = :status
              and (p.nextCancelAttemptAt is null or p.nextCancelAttemptAt <= :now)
            order by p.nextCancelAttemptAt asc
            """)
    List<Payment> findDueForCancelRetry(@Param("status") PaymentStatus status, @Param("now") Instant now);

    long countByStatus(PaymentStatus status);

    /**
     * 예산을 넘겨 <b>사람이 봐야 하는</b> 건수. 알람이 이 값을 본다.
     *
     * <p>{@code countByStatus} 와 나눠 두는 이유 — 전체 {@code CANCELING} 은 정상 환불이
     * 진행 중인 몇 초 동안에도 오르내린다. 그것에 알람을 걸면 잡음이 되고,
     * 잡음은 사람이 알람을 무시하는 법을 가르친다.
     */
    long countByStatusAndCancelingSinceBefore(PaymentStatus status, Instant threshold);
}
