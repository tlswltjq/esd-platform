package com.stove.license.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LicenseSuspensionRepository extends JpaRepository<LicenseSuspension, Long> {
    Optional<LicenseSuspension> findByRequestEventId(String eventId);
}
