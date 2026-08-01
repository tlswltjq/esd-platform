package com.stove.license.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LicenseRepository extends JpaRepository<License, Long> {

    List<License> findByOrderNo(String orderNo);

    List<License> findByMemberIdAndStatus(Long memberId, LicenseStatus status);

    boolean existsByOrderNoAndProductId(String orderNo, Long productId);
}
