package com.stove.studio.core.domain;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    Optional<Submission> findTopByGameIdOrderBySequenceNoDesc(Long gameId);
    List<Submission> findByGameIdAndWorkspaceIdOrderBySequenceNoDesc(Long gameId, Long workspaceId);
    boolean existsByBuildId(Long buildId);
}
