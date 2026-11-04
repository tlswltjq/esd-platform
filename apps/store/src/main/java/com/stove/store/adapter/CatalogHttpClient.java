package com.stove.store.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.store.core.domain.CatalogUnavailableException;
import com.stove.store.core.domain.StoreProductValues;
import com.stove.store.core.port.CatalogProducts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public class CatalogHttpClient implements CatalogProducts {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String serviceKey;
    public CatalogHttpClient(RestClient client, ObjectMapper mapper, String serviceKey) {
        this.client = client;
        this.mapper = mapper;
        this.serviceKey = serviceKey;
    }
    @Override public List<String> codes(int page) {
        try { return readCodes(page); }
        catch (ResourceAccessException unavailable) { throw new CatalogUnavailableException(); }
        catch (RestClientResponseException response) { throw rejected(response); }
    }
    private List<String> readCodes(int page) {
        if (page < 0) throw new IllegalArgumentException("Catalog page must be nonnegative");
        if (serviceKey == null || serviceKey.isBlank()) throw new IllegalStateException("Store catalog service key is required");
        JsonNode body = client.get().uri(uri -> uri.path("/internal/v1/store-products").queryParam("page", page)
                .queryParam("size", 100).build()).header("X-Store-Service-Key", serviceKey).retrieve().body(JsonNode.class);
        JsonNode pageData = data(body);
        JsonNode content = pageData.path("content");
        if (!content.isArray()) throw new IllegalStateException("Catalog 상품 목록 형식이 올바르지 않습니다.");
        if (!integer(pageData.path("page")) || pageData.path("page").asLong() != page
                || !integer(pageData.path("size")) || pageData.path("size").asLong() != 100
                || !integer(pageData.path("totalElements")) || pageData.path("totalElements").asLong() < 0
                || !integer(pageData.path("totalPages")) || pageData.path("totalPages").asLong() < 0
                || content.size() != Math.max(0L, Math.min(100L, pageData.path("totalElements").asLong() - (long) page * 100)))
            throw new IllegalStateException("Catalog 상품 페이지 정보가 올바르지 않습니다.");
        long total = pageData.path("totalElements").asLong();
        if (pageData.path("totalPages").asLong() != total / 100 + (total % 100 == 0 ? 0 : 1))
            throw new IllegalStateException("Catalog 전체 페이지 수가 올바르지 않습니다.");
        ArrayList<String> codes = new ArrayList<String>();
        Set<String> unique = new HashSet<>();
        content.forEach(item -> {
            if (!item.path("productCode").isTextual()) throw new IllegalStateException("Catalog 상품 코드가 없습니다.");
            String code = item.path("productCode").asText();
            StoreProductValues.requireCode(code);
            if (!unique.add(code)) throw new IllegalStateException("Catalog 상품 코드가 중복됩니다.");
            codes.add(code);
        });
        return List.copyOf(codes);
    }
    @Override public CatalogProductSnapshot detail(String code) {
        try { return readDetail(code); }
        catch (ResourceAccessException unavailable) { throw new CatalogUnavailableException(); }
        catch (RestClientResponseException response) { throw rejected(response); }
    }
    private CatalogProductSnapshot readDetail(String code) {
        if (serviceKey == null || serviceKey.isBlank()) throw new IllegalStateException("Store catalog service key is required");
        JsonNode body = client.get().uri("/internal/v1/store-products/{code}", code)
                .header("X-Store-Service-Key", serviceKey).retrieve().body(JsonNode.class);
        try {
            JsonNode detail = data(body);
            if (!detail.path("listed").isBoolean()) throw new IllegalStateException("Catalog 노출 값이 없습니다.");
            for (String field : List.of("productId", "price", "contentVersion", "catalogVersion"))
                if (!integer(detail.path(field))) throw new IllegalStateException("Catalog 수치 필드가 올바르지 않습니다.");
            for (String field : List.of("productCode", "title", "shortDescription", "thumbnailUrl", "content", "currency",
                    "buildVersion", "saleStatus", "publishedAt", "updatedAt"))
                if (!detail.path(field).isTextual()) throw new IllegalStateException("Catalog 필수 필드가 올바르지 않습니다.");
            if (detail.hasNonNull("videoUrl") && !detail.path("videoUrl").isTextual())
                throw new IllegalStateException("Catalog 동영상 필드가 올바르지 않습니다.");
            if (!detail.path("genres").isArray()) throw new IllegalStateException("Catalog 장르 배열이 없습니다.");
            for (JsonNode genre : detail.path("genres"))
                if (!genre.isTextual()) throw new IllegalStateException("Catalog 장르 코드가 올바르지 않습니다.");
            CatalogProductSnapshot product = mapper.treeToValue(detail, CatalogProductSnapshot.class);
            StoreProductValues.requireSnapshot(product);
            if (!code.equals(product.productCode())) throw new IllegalStateException("Catalog 상품 코드가 다릅니다.");
            return product;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Catalog 공개 상세를 읽을 수 없습니다.", failure);
        }
    }
    private static boolean integer(JsonNode node) { return node.isIntegralNumber() && node.canConvertToLong(); }
    private static RuntimeException rejected(RestClientResponseException response) {
        if (response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429)
            return new CatalogUnavailableException();
        return new IllegalStateException("Catalog HTTP response was rejected");
    }
    private JsonNode data(JsonNode body) {
        if (body == null || !body.path("success").asBoolean() || !body.path("data").isObject())
            throw new IllegalStateException("Catalog 응답이 올바르지 않습니다.");
        return body.path("data");
    }
}
