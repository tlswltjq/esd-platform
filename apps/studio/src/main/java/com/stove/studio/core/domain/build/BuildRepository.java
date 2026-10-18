package com.stove.studio.core.domain.build;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildRepository extends JpaRepository<Build, Long> {
    List<Build> findByProjectIdOrderByIdDesc(Long projectId);

    Optional<Build> findByProjectIdAndVersion(Long projectId, String version);
}
