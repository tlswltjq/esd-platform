package com.stove.studio.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionGateRepository extends JpaRepository<SubmissionGate, Long> {
    Optional<SubmissionGate> findBySubmissionIdAndReviewType(Long submissionId, String reviewType);
    List<SubmissionGate> findBySubmissionId(Long submissionId);
}
