package com.stove.studio.core.domain;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RatingRevisionRepository extends JpaRepository<RatingRevision, Long> {
    Optional<RatingRevision> findTopByGameIdOrderByRevisionNoDesc(Long gameId);
    List<RatingRevision> findByGameIdOrderByRevisionNoDesc(Long gameId);
}
