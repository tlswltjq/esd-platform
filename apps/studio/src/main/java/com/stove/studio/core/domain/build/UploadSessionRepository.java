package com.stove.studio.core.domain.build;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadSessionRepository extends JpaRepository<UploadSession, Long> {

    Optional<UploadSession> findByBuildId(Long buildId);

    List<UploadSession> findTop100ByStatusAndExpiresAtBeforeOrderByIdAsc(
            UploadSessionStatus status, Instant expiresAt);
}
