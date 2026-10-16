package com.stove.order.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.*;
import com.stove.order.core.domain.*;
import com.stove.order.core.port.CatalogProducts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class CatalogHttpClient implements CatalogProducts {
    private final RestClient client;
    public CatalogHttpClient(@Value("${stove.order.catalog-base-url}") String baseUrl,
            @Value("${stove.order.catalog-connect-timeout-ms:3000}") int connectTimeout,
            @Value("${stove.order.catalog-read-timeout-ms:10000}") int readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout); factory.setReadTimeout(readTimeout);
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
    public PublishedProduct detail(String code) {
        try {
            var body = client.get().uri("/api/v1/products/by-code/{code}", code).retrieve()
                    .onStatus(s -> s.isError(), (request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) throw new BusinessException(ErrorCode.NOT_FOUND, "공개 상품이 없습니다.");
                        if (status >= 500) throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
                        throw new BusinessException(OrderErrorCode.CATALOG_INVALID_RESPONSE);
                    }).body(JsonNode.class);
            if (body == null || !body.path("success").isBoolean() || !body.path("success").asBoolean()
                    || !body.path("data").isObject()) throw invalid();
            var p = body.path("data");
            for (String field : new String[]{"productId", "contentVersion", "price"})
                if (!p.path(field).isIntegralNumber() || !p.path(field).canConvertToLong()) throw invalid();
            for (String field : new String[]{"productCode", "title", "currency"})
                if (!p.path(field).isTextual()) throw invalid();
            if (!code.equals(p.path("productCode").textValue())) throw invalid();
            return new PublishedProduct(p.path("productId").longValue(), p.path("productCode").textValue(),
                    p.path("title").textValue(), p.path("contentVersion").longValue(),
                    p.path("price").longValue(), p.path("currency").textValue());
        } catch (ResourceAccessException failure) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        } catch (RestClientException failure) {
            throw invalid();
        }
    }
    private static BusinessException invalid() { return new BusinessException(OrderErrorCode.CATALOG_INVALID_RESPONSE); }
}
