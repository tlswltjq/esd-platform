package com.stove.studio.core.domain;

import java.util.Optional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadSessionRepository extends JpaRepository<UploadSession, Long> {

    Optional<UploadSession> findByBuildId(Long buildId);

    List<UploadSession> findTop100ByStatusAndExpiresAtBeforeOrderByIdAsc(
            UploadSessionStatus status, Instant expiresAt);
}
