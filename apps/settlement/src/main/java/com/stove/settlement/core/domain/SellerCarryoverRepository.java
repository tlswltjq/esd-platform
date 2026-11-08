package com.stove.settlement.core.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerCarryoverRepository extends JpaRepository<SellerCarryover,Long> {
    Optional<SellerCarryover> findBySellerSubjectAndSettlementMonth(String seller,String month);
    List<SellerCarryover> findAllBySellerSubjectInAndSettlementMonthIn(Collection<String> sellers,Collection<String> months);
}
