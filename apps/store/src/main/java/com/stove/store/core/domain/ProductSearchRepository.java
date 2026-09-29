package com.stove.store.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ProductSearchRepository extends ElasticsearchRepository<ProductDocument, String> {

    @Query("{\"bool\":{\"must\":[{\"match_phrase\":{\"name\":\"?0\"}}],\"filter\":[{\"term\":{\"visible\":true}}]}}")
    List<ProductDocument> findByVisibleTrueAndNameContaining(String keyword, Pageable pageable);

    List<ProductDocument> findByVisibleTrue(Pageable pageable);

    Optional<ProductDocument> findByProductCode(String productCode);
}
