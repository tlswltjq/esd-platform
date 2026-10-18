package com.stove.license.core.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LicenseRepository extends JpaRepository<License, Long> {
    @Query("select l from License l where l.buyerSubject = :buyer and (l.productId = :productId or l.productCode = :code) order by l.id")
    List<License> findProductConflicts(String buyer, Long productId, String code);

    Optional<License> findByOrderNo(String orderNo);

    @Query("select l from License l where l.eventId = :eventId or l.requestEventId = :requestId "
            + "or l.paymentResultEventId = :resultId or l.paymentId = :paymentId")
    List<License> findSourceConflicts(String eventId, String requestId, String resultId, String paymentId);

    Page<License> findByBuyerSubject(String buyer, Pageable page);

    Optional<License> findByBuyerSubjectAndProductCode(String buyer, String code);
}
