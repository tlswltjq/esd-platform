package com.stove.studio.core.domain.release;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectReleaseRequestRepository
        extends JpaRepository<ProjectReleaseRequest, Long> {
    List<ProjectReleaseRequest> findByProjectIdOrderByIdDesc(Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProjectReleaseRequest r where r.id=:id")
    Optional<ProjectReleaseRequest> lock(@Param("id") Long id);
}
