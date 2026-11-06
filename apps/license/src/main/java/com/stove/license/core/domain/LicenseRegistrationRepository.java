package com.stove.license.core.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LicenseRegistrationRepository extends JpaRepository<LicenseRegistration, Long> {
    Optional<LicenseRegistration> findFirstByLicenseIdAndStatusIn(String licenseId, Collection<LicenseStatus> statuses);

    boolean existsByBuyerSubjectAndProductIdAndStatus(String buyer, Long productId, LicenseStatus status);

    Page<LicenseRegistration> findByBuyerSubjectAndStatusIn(String buyer, Collection<LicenseStatus> statuses, Pageable page);

    List<LicenseRegistration> findByBuyerSubjectAndProductCodeAndStatusInOrderByIdDesc(String buyer, String code,
            Collection<LicenseStatus> statuses);
}
