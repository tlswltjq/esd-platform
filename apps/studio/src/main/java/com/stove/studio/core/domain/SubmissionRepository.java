package com.stove.studio.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    Optional<Submission> findTopByGameIdOrderBySequenceNoDesc(Long gameId);
    boolean existsByBuildId(Long buildId);
}
