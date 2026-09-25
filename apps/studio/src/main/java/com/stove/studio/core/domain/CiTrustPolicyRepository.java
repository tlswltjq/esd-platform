package com.stove.studio.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CiTrustPolicyRepository extends JpaRepository<CiTrustPolicy, Long> {
    List<CiTrustPolicy> findByGameIdOrderByIdDesc(Long gameId);
    List<CiTrustPolicy> findByGameIdAndProviderAndRepository(
            Long gameId, String provider, String repository);
}
