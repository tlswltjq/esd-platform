package com.stove.studio.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameBuildRepository extends JpaRepository<GameBuild, Long> {

    List<GameBuild> findByGameIdOrderByIdDesc(Long gameId);

    boolean existsByGameIdAndVersion(Long gameId, String version);
}
