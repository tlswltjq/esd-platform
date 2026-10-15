package com.stove.store.infrastructure;

import com.fasterxml.jackson.databind.*;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.store.core.port.CatalogProducts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.*;

@Component
public class CatalogHttpClient implements CatalogProducts {
    private final RestClient client;
    private final ObjectMapper mapper;
    public CatalogHttpClient(@Value("${stove.store.catalog-base-url}") String baseUrl, ObjectMapper mapper) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.mapper = mapper;
    }
    @Override public List<String> codes(int page) {
        var body = client.get().uri(uri -> uri.path("/api/v1/products").queryParam("page", page)
                .queryParam("size", 100).build()).retrieve().body(JsonNode.class);
        var content = data(body).path("content");
        if (!content.isArray()) throw new IllegalStateException("Catalog 상품 목록 형식이 올바르지 않습니다.");
        var codes = new ArrayList<String>();
        content.forEach(item -> {
            String code = item.path("productCode").asText();
            if (code.isBlank()) throw new IllegalStateException("Catalog 상품 코드가 없습니다.");
            codes.add(code);
        });
        return List.copyOf(codes);
    }
    @Override public CatalogProductSnapshot detail(String code) {
        var body = client.get().uri("/api/v1/products/by-code/{code}", code).retrieve().body(JsonNode.class);
        try {
            var product = mapper.treeToValue(data(body), CatalogProductSnapshot.class);
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
