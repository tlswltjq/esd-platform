package com.stove.order.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.OrderErrorCode;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.port.CatalogProducts;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class CatalogHttpClient implements CatalogProducts {
    private final RestClient client;
    private final String serviceKey;
    public CatalogHttpClient(RestClient client, String serviceKey) {
        this.client = client;
        this.serviceKey = serviceKey;
    }
    public PublishedProduct detail(String code) {
        if (serviceKey == null || serviceKey.isBlank()) throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        try {
            JsonNode body = client.get().uri("/internal/v1/order-products/{code}", code)
                    .header("X-Order-Service-Key", serviceKey).retrieve()
                    .onStatus(s -> !s.is2xxSuccessful(), (request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) throw new BusinessException(ErrorCode.NOT_FOUND, "공개 상품이 없습니다.");
                        if (status == 401 || status == 403 || status >= 500) throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
                        throw new BusinessException(OrderErrorCode.CATALOG_INVALID_RESPONSE);
                    }).body(JsonNode.class);
            if (body == null || !body.path("success").isBoolean() || !body.path("success").asBoolean()
                    || !body.path("data").isObject()) throw invalid();
            JsonNode p = body.path("data");
            for (String field : new String[]{"productId", "contentVersion", "price"})
                if (!p.path(field).isIntegralNumber() || !p.path(field).canConvertToLong()) throw invalid();
            for (String field : new String[]{"productCode", "title", "currency", "saleStatus"})
                if (!p.path(field).isTextual()) throw invalid();
            if (!code.equals(p.path("productCode").textValue())) throw invalid();
            if (!p.path("publicDetailAllowed").isBoolean()) throw invalid();
            return new PublishedProduct(p.path("productId").longValue(), p.path("productCode").textValue(),
                    p.path("title").textValue(), p.path("contentVersion").longValue(),
                    p.path("price").longValue(), p.path("currency").textValue(), p.path("saleStatus").textValue(), p.path("publicDetailAllowed").booleanValue());
        } catch (ResourceAccessException failure) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        } catch (RestClientException failure) {
            throw invalid();
        }
    }
    private static BusinessException invalid() { return new BusinessException(OrderErrorCode.CATALOG_INVALID_RESPONSE); }
}
