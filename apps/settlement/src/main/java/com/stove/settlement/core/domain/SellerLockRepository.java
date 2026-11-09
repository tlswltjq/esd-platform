package com.stove.settlement.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerLockRepository extends JpaRepository<SellerLock,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SellerLock l where l.sellerSubject=:seller")
    Optional<SellerLock> lock(@Param("seller") String seller);
}
