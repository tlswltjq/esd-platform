package com.stove.studio.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionBuildRepository extends JpaRepository<SubmissionBuild, Long> {
    List<SubmissionBuild> findBySubmissionIdOrderById(Long submissionId);
}
