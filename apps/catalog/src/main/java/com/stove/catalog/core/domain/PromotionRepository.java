package com.stove.catalog.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {
    List<Promotion> findByProductIdAndStoppedAtIsNull(Long productId);
    List<Promotion> findByProductIdOrderByStartsAtDesc(Long productId);
}
