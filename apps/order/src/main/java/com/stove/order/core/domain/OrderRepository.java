package com.stove.order.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByOrderNo(String orderNo);

    List<Order> findByMemberIdOrderByIdDesc(Long memberId);

    /**
     * 결제 창이 지난 미결제 주문. 만료 스윕의 대상이다.
     *
     * <p><b>{@code Pageable} 로 한 번에 집는 수를 묶는다.</b> 이 값이 없으면 첫 회차가
     * 밀린 것을 전부 집는다 — 실측 98,750건이었고, 그걸 한 트랜잭션에 넣으면 락과 언두 로그가
     * 그만큼 커진다. <b>밀린 것을 한 번에 없애는 것이 목적이 아니라, 늘지 않게 하는 것이 목적이다.</b>
     *
     * <p>{@code items} 를 함께 읽지 않는다. 만료는 주문 헤더만 건드리므로 품목을 끌고 오면
     * N+1 이 상태 변경 하나에 붙는다 — {@code findByOrderNo} 가 {@code EntityGraph} 를 쓰는 것과
     * 반대 이유다.
     */
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant threshold, Pageable pageable);

    long countByStatus(OrderStatus status);

    /** 만료 대상이 지금 몇 건 남았는가. 스윕이 밀리고 있는지를 이 값이 말한다. */
    long countByStatusAndCreatedAtBefore(OrderStatus status, Instant threshold);
}
