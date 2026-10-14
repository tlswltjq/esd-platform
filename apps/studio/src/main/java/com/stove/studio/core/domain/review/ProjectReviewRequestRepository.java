package com.stove.studio.core.domain.review;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface ProjectReviewRequestRepository extends JpaRepository<ProjectReviewRequest, Long> {
    List<ProjectReviewRequest> findByProjectIdOrderByIdDesc(Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProjectReviewRequest r where r.id=:id")
    Optional<ProjectReviewRequest> lock(@Param("id") Long id);
}
