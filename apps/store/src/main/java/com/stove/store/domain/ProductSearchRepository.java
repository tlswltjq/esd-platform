package com.stove.store.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ProductSearchRepository extends ElasticsearchRepository<ProductDocument, String> {

    List<ProductDocument> findByStatusAndNameContaining(String status, String keyword, Pageable pageable);

    List<ProductDocument> findByStatusOrderByPriceAsc(String status, Pageable pageable);
}
