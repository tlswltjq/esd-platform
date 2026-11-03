package com.stove.studio.core.domain.release;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectReleaseRequestRepository
        extends JpaRepository<ProjectReleaseRequest, Long> {
    Page<ProjectReleaseRequest> findByProjectIdOrderByIdDesc(Long projectId, Pageable page);

    @Query("select r from ProjectReleaseRequest r where r.projectId in :projects and (r.status='REQUESTED' or r.id in :current)")
    List<ProjectReleaseRequest> findForProjects(@Param("projects") List<Long> projects, @Param("current") List<Long> current);
    Optional<ProjectReleaseRequest> findFirstByProjectIdAndStatusOrderByIdAsc(Long projectId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProjectReleaseRequest r where r.id=:id")
    Optional<ProjectReleaseRequest> lock(@Param("id") Long id);
}
