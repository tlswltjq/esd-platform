package com.stove.catalog.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    List<Product> findByIdIn(List<Long> ids);

    Optional<Product> findByProductCode(String productCode);

    /**
     * 재색인용 커서 페이징.
     *
     * <p>{@code OFFSET} 대신 마지막 id 를 커서로 쓴다. 재색인은 수 분에 걸쳐 도는데
     * 그 사이 상품이 추가·삭제되면 offset 방식은 <b>행을 건너뛰거나 두 번 읽는다.</b>
     * 건너뛴 행은 색인에서 누락되고, 그 사실이 아무 데도 남지 않는다.
     */
    List<Product> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);
}
