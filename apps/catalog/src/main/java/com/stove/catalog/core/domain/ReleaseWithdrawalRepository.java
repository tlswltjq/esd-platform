package com.stove.catalog.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseWithdrawalRepository extends JpaRepository<ReleaseWithdrawal, Long> {
    List<ReleaseWithdrawal> findByRequestIdAndResolvedFalseOrderByWithdrawalIdAsc(Long requestId);
}
