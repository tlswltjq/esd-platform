package com.stove.studio.core.domain.revision;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricingRevisionRepository extends JpaRepository<PricingRevision, Long> {
    Optional<PricingRevision> findTopByGameIdOrderByRevisionNoDesc(Long gameId);
    List<PricingRevision> findByGameIdOrderByRevisionNoDesc(Long gameId);
}
