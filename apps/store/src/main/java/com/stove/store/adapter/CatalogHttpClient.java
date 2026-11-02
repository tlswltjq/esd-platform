package com.stove.store.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.store.core.port.CatalogProducts;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;

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
        if (serviceKey == null || serviceKey.isBlank()) throw new IllegalStateException("Store catalog service key is required");
        JsonNode body = client.get().uri(uri -> uri.path("/internal/v1/store-products").queryParam("page", page)
                .queryParam("size", 100).build()).header("X-Store-Service-Key", serviceKey).retrieve().body(JsonNode.class);
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
        if (serviceKey == null || serviceKey.isBlank()) throw new IllegalStateException("Store catalog service key is required");
        JsonNode body = client.get().uri("/internal/v1/store-products/{code}", code)
                .header("X-Store-Service-Key", serviceKey).retrieve().body(JsonNode.class);
        try {
            JsonNode detail = data(body);
            if (!detail.path("listed").isBoolean()) throw new IllegalStateException("Catalog 노출 값이 없습니다.");
            CatalogProductSnapshot product = mapper.treeToValue(detail, CatalogProductSnapshot.class);
            if (!code.equals(product.productCode())) throw new IllegalStateException("Catalog 상품 코드가 다릅니다.");
            return product;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Catalog 공개 상세를 읽을 수 없습니다.", failure);
        }
    }
    private JsonNode data(JsonNode body) {
        if (body == null || !body.path("success").asBoolean() || !body.path("data").isObject())
            throw new IllegalStateException("Catalog 응답이 올바르지 않습니다.");
        return body.path("data");
    }
}
