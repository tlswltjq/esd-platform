package com.stove.license.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LicenseRestorationRepository extends JpaRepository<LicenseRestoration, Long> {
    Optional<LicenseRestoration> findByRequestEventId(String eventId);
}
