package com.stove.catalog.core.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogPublicationJobRepository extends JpaRepository<CatalogPublicationJob, Long> {
    Optional<CatalogPublicationJob> findBySourceEventId(String sourceEventId);
    Optional<CatalogPublicationJob> findFirstByProjectIdAndContentVersionOrderByRequestIdAsc(Long projectId, long contentVersion);
    List<CatalogPublicationJob> findByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscRequestIdAsc(
            String state, Instant now, Pageable page);
    List<CatalogPublicationJob> findByStateOrderByCreatedAtAscRequestIdAsc(String state, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from CatalogPublicationJob j where j.requestId=:id")
    Optional<CatalogPublicationJob> lock(@Param("id") Long id);
}
