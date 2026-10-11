package com.stove.studio.core.domain.revision;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorePageRevisionRepository extends JpaRepository<StorePageRevision, Long> {
    Optional<StorePageRevision> findTopByGameIdOrderByRevisionNoDesc(Long gameId);
    List<StorePageRevision> findByGameIdOrderByRevisionNoDesc(Long gameId);
}
