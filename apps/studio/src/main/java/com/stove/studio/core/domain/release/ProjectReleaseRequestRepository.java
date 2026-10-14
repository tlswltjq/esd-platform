package com.stove.studio.core.domain.release;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface ProjectReleaseRequestRepository
        extends JpaRepository<ProjectReleaseRequest, Long> {
    List<ProjectReleaseRequest> findByProjectIdOrderByIdDesc(Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProjectReleaseRequest r where r.id=:id")
    Optional<ProjectReleaseRequest> lock(@Param("id") Long id);
}
