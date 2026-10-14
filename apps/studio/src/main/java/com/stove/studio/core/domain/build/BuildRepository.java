package com.stove.studio.core.domain.build;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface BuildRepository extends JpaRepository<Build, Long> {
    List<Build> findByProjectIdOrderByIdDesc(Long projectId);

    Optional<Build> findByProjectIdAndVersion(Long projectId, String version);
}
