package com.stove.studio.core.domain.build;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BuildRepository extends JpaRepository<Build, Long> {
    Page<Build> findByProjectIdOrderByIdDesc(Long projectId, Pageable page);

    Optional<Build> findByProjectIdAndVersion(Long projectId, String version);
}
