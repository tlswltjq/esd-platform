package com.stove.studio.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorePageRevisionRepository extends JpaRepository<StorePageRevision, Long> {
    Optional<StorePageRevision> findTopByGameIdOrderByRevisionNoDesc(Long gameId);
}
