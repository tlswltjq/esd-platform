package com.stove.order.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLicenseGrantRepository extends JpaRepository<OrderLicenseGrant, Long> {
    Optional<OrderLicenseGrant> findByOrderNo(String orderNo);
    Optional<OrderLicenseGrant> findByEventId(String eventId);
    Optional<OrderLicenseGrant> findByLicenseId(String licenseId);
}
