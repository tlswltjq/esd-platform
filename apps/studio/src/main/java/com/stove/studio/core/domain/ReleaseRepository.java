package com.stove.studio.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseRepository extends JpaRepository<Release, Long> {
    List<Release> findTop100ByStatusAndPublishAtLessThanEqualOrderByPublishAtAsc(
            ReleaseStatus status, Instant publishAt);
    Optional<Release> findTopByGameIdAndStatusOrderByPublishedAtDesc(Long gameId, ReleaseStatus status);
    Optional<Release> findTopByGameIdAndChannelAndStatusOrderByPublishedAtDesc(
            Long gameId, ReleaseChannel channel, ReleaseStatus status);
    boolean existsByBuildIdAndChannelAndStatus(Long buildId, ReleaseChannel channel, ReleaseStatus status);
}
