package com.stove.store.core.service;

import com.stove.common.kafka.contract.genre.GenreCatalog;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.store.core.domain.StoreProductRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SearchStoreProducts {
    private final StoreProductRepository products;
    private final SynchronizeCatalogProducts synchronization;

    @Transactional(readOnly = true)
    public Page<CatalogProductSnapshot> execute(String q, String sort, int page, int size) {
        return execute(q, sort, page, size, List.of());
    }

    @Transactional(readOnly = true)
    public Page<CatalogProductSnapshot> execute(String q, String sort, int page, int size, List<String> genres) {
        List<String> selected;
        try { selected = GenreCatalog.standard().filter(genres); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ErrorCode.INVALID_REQUEST, "장르 검색 조건이 올바르지 않습니다."); }
        String keyword = q == null ? "" : q.strip();
        if (page < 0 || size < 1 || size > 100 || keyword.length() > 100)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "검색어는 100자 이하, page >= 0, size는 1~100이어야 합니다.");
        Sort order = switch (sort) {
            case "NEWEST" -> Sort.by(Sort.Direction.DESC, "publishedAt");
            case "PRICE_ASC" -> Sort.by(Sort.Direction.ASC, "price");
            case "PRICE_DESC" -> Sort.by(Sort.Direction.DESC, "price");
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 정렬입니다.");
        };
        synchronization.requireReady();
        String escaped = keyword.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return products.findAll((root, query, builder) -> {
            List<Predicate> conditions = new ArrayList<>();
            conditions.add(builder.isTrue(root.get("listed")));
            conditions.add(builder.like(builder.lower(root.get("title")), "%" + escaped + "%", '!'));
            for (String genre : selected) conditions.add(builder.isMember(genre, root.<Set<String>>get("genres")));
            return builder.and(conditions.toArray(Predicate[]::new));
        }, PageRequest.of(page, size, order.and(Sort.by(Sort.Direction.DESC, "productId"))))
                .map(p -> p.snapshot());
    }
}
