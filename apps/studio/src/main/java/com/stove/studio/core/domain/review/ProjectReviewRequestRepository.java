package com.stove.studio.core.domain.review;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectReviewRequestRepository extends JpaRepository<ProjectReviewRequest, Long> {
    List<ProjectReviewRequest> findByProjectIdOrderByIdDesc(Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProjectReviewRequest r where r.id=:id")
    Optional<ProjectReviewRequest> lock(@Param("id") Long id);
}
