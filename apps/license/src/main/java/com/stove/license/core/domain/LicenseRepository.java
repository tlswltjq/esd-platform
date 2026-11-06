package com.stove.license.core.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LicenseRepository extends JpaRepository<License, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from License l where l.orderNo = :orderNo")
    Optional<License> lockByOrderNo(@Param("orderNo") String orderNo);

    /** 같은 고객·상품의 구매 근거를 id 순서로 모두 잠근다. 등록·복구의 "쓸 수 있는 보유권 1개" 판단을 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from License l where l.buyerSubject = :buyer and l.productId = :productId order by l.id")
    List<License> lockProductPurchases(@Param("buyer") String buyer, @Param("productId") Long productId);

    Optional<License> findByOrderNo(String orderNo);

    Optional<License> findByBuyerSubjectAndLicenseId(String buyer, String licenseId);

    @Query("select l from License l where l.eventId = :eventId or l.requestEventId = :requestId "
            + "or l.paymentResultEventId = :resultId or l.paymentId = :paymentId")
    List<License> findSourceConflicts(String eventId, String requestId, String resultId, String paymentId);

    /** 보관함: 등록 전 항목과 환불로 정지된 등록 전 항목. 환불이 끝난 항목은 뺀다(결정 97). */
    @Query("select l from License l where l.buyerSubject = :buyer and (l.status = com.stove.license.core.domain.PurchaseStatus.STORED "
            + "or (l.status = com.stove.license.core.domain.PurchaseStatus.SUSPENDED and l.suspendedFrom = com.stove.license.core.domain.PurchaseStatus.STORED))")
    Page<License> findStorage(@Param("buyer") String buyer, Pageable page);
}
