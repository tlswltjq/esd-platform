package com.stove.store.infrastructure;

import com.fasterxml.jackson.databind.*;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.store.core.port.CatalogProducts;
import org.springframework.web.client.RestClient;
import java.util.*;

public class CatalogHttpClient implements CatalogProducts {
    private final RestClient client;
    private final ObjectMapper mapper;
    public CatalogHttpClient(RestClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }
    @Override public List<String> codes(int page) {
        JsonNode body = client.get().uri(uri -> uri.path("/api/v1/products").queryParam("page", page)
                .queryParam("size", 100).build()).retrieve().body(JsonNode.class);
        JsonNode content = data(body).path("content");
        if (!content.isArray()) throw new IllegalStateException("Catalog 상품 목록 형식이 올바르지 않습니다.");
        ArrayList<String> codes = new ArrayList<String>();
        content.forEach(item -> {
            String code = item.path("productCode").asText();
            if (code.isBlank()) throw new IllegalStateException("Catalog 상품 코드가 없습니다.");
            codes.add(code);
        });
        return List.copyOf(codes);
    }
    @Override public CatalogProductSnapshot detail(String code) {
        JsonNode body = client.get().uri("/api/v1/products/by-code/{code}", code).retrieve().body(JsonNode.class);
        try {
            CatalogProductSnapshot product = mapper.treeToValue(data(body), CatalogProductSnapshot.class);
            if (!code.equals(product.productCode())) throw new IllegalStateException("Catalog 상품 코드가 다릅니다.");
            return product;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Catalog 공개 상세를 읽을 수 없습니다.", failure);
        }
    }
    private JsonNode data(JsonNode body) {
        if (body == null || !body.path("success").asBoolean() || !body.path("data").isObject())
            throw new IllegalStateException("Catalog 응답이 올바르지 않습니다.");
        return body.path("data");
    }
}
