package com.stove.download.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.download.core.domain.OwnedProduct;
import com.stove.download.core.port.OwnedProducts;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class LicenseHttpClient implements OwnedProducts {
    private final RestClient client;

    public LicenseHttpClient(RestClient client) { this.client = client; }

    @Override
    public OwnedProduct require(String productCode, String accessToken) {
        try {
            JsonNode body = client.get().uri("/api/v1/library/{code}", productCode)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve().onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) throw new BusinessException(ErrorCode.FORBIDDEN, "보유하지 않은 상품입니다.");
                        if (status == 401) throw new BusinessException(ErrorCode.UNAUTHORIZED);
                        if (status == 403) throw new BusinessException(ErrorCode.FORBIDDEN);
                        throw HttpResponseValues.invalid();
                    }).body(JsonNode.class);
            JsonNode data = HttpResponseValues.data(body);
            OwnedProduct product = new OwnedProduct(HttpResponseValues.number(data, "productId"),
                    HttpResponseValues.text(data, "productCode"));
            if (!productCode.equals(product.productCode())) throw HttpResponseValues.invalid();
            return product;
        } catch (RestClientException | IllegalArgumentException failure) {
            throw HttpResponseValues.invalid();
        }
    }
}
