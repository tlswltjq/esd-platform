package com.stove.review.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectReviewRepository
        extends JpaRepository<ProjectReview, Long>, JpaSpecificationExecutor<ProjectReview> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProjectReview r where r.reviewRequestId=:id")
    Optional<ProjectReview> lock(@Param("id") Long id);
}
