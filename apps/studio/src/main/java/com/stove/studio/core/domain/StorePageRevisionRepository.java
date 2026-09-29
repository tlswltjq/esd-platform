package com.stove.studio.core.domain;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorePageRevisionRepository extends JpaRepository<StorePageRevision, Long> {
    Optional<StorePageRevision> findTopByGameIdOrderByRevisionNoDesc(Long gameId);
    List<StorePageRevision> findByGameIdOrderByRevisionNoDesc(Long gameId);
}
