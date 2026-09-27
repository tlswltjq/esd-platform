package com.stove.studio.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BundleComponentRepository extends JpaRepository<BundleComponent, Long> {
    List<BundleComponent> findByBundleGameIdOrderByComponentGameId(Long bundleGameId);
    List<BundleComponent> findByComponentGameIdOrderByBundleGameId(Long componentGameId);
}
