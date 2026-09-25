package com.stove.studio.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InternalTesterGrantRepository extends JpaRepository<InternalTesterGrant, Long> {
    Optional<InternalTesterGrant> findByGameIdAndTesterSubjectAndChannel(
            Long gameId, String testerSubject, ReleaseChannel channel);
    List<InternalTesterGrant> findByGameIdOrderByIdDesc(Long gameId);
    boolean existsByGameIdAndTesterSubjectAndChannelAndActiveTrue(
            Long gameId, String testerSubject, ReleaseChannel channel);
}
