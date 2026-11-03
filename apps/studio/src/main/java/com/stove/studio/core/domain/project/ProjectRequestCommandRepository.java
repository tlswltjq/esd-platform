package com.stove.studio.core.domain.project;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRequestCommandRepository extends JpaRepository<ProjectRequestCommand, Long> {
    Optional<ProjectRequestCommand> findByProjectIdAndKindAndRequestKey(
            Long projectId, ProjectRequestKind kind, String requestKey);
    boolean existsByProjectIdAndKindAndTargetRequestIdAndState(
            Long projectId, ProjectRequestKind kind, Long targetRequestId, String state);
    List<ProjectRequestCommand> findByProjectIdAndKindAndTargetRequestIdOrderByIdDesc(
            Long projectId, ProjectRequestKind kind, Long targetRequestId, Pageable page);
}
