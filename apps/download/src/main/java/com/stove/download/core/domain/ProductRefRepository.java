package com.stove.download.core.domain;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProductRefRepository extends MongoRepository<ProductRef, String> {

    Optional<ProductRef> findByProductId(Long productId);
}
