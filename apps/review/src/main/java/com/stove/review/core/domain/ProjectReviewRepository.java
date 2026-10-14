package com.stove.review.core.domain;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectReviewRepository
        extends JpaRepository<ProjectReview, Long>, JpaSpecificationExecutor<ProjectReview> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProjectReview r where r.reviewRequestId=:id")
    Optional<ProjectReview> lock(@Param("id") Long id);
}
