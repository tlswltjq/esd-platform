package com.stove.studio.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricingRevisionRepository extends JpaRepository<PricingRevision, Long> {
    Optional<PricingRevision> findTopByGameIdOrderByRevisionNoDesc(Long gameId);
}
