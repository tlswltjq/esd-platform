package com.stove.studio.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameProjectRepository extends JpaRepository<GameProject, Long> {

    Optional<GameProject> findByProductCode(String productCode);

    List<GameProject> findBySellerIdOrderByIdDesc(Long sellerId);
}
