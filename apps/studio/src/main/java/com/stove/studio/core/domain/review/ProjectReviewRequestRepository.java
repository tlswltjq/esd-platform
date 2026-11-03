package com.stove.studio.core.domain.review;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectReviewRequestRepository extends JpaRepository<ProjectReviewRequest, Long> {
    Page<ProjectReviewRequest> findByProjectIdOrderByIdDesc(Long projectId, Pageable page);

    @Query("select r from ProjectReviewRequest r where r.projectId in :projects and (r.status='REQUESTED' or r.id in :current)")
    List<ProjectReviewRequest> findForProjects(@Param("projects") List<Long> projects, @Param("current") List<Long> current);
    Optional<ProjectReviewRequest> findFirstByProjectIdAndStatusOrderByIdAsc(Long projectId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProjectReviewRequest r where r.id=:id")
    Optional<ProjectReviewRequest> lock(@Param("id") Long id);
}
