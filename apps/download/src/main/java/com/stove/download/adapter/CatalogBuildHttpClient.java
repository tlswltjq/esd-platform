package com.stove.download.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.*;
import com.stove.download.core.domain.PublishedBuild;
import com.stove.download.core.port.PublishedBuilds;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class CatalogBuildHttpClient implements PublishedBuilds {
    private final RestClient client;
    private final String serviceKey;

    public CatalogBuildHttpClient(RestClient client, String serviceKey) {
        this.client = client;
        this.serviceKey = serviceKey;
    }

    @Override
    public PublishedBuild require(String productCode) {
        try {
            JsonNode body = client.get().uri("/internal/v1/products/{code}/build", productCode)
                    .header("X-Download-Service-Key", serviceKey)
                    .retrieve().onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        if (response.getStatusCode().value() == 404)
                            throw new BusinessException(ErrorCode.NOT_FOUND, "현재 공개된 상품이 없습니다.");
                        throw HttpResponseValues.invalid();
                    }).body(JsonNode.class);
            JsonNode p = HttpResponseValues.data(body);
            PublishedBuild build = new PublishedBuild(HttpResponseValues.number(p, "productId"),
                    HttpResponseValues.text(p, "productCode"), HttpResponseValues.number(p, "releaseRequestId"),
                    HttpResponseValues.number(p, "contentVersion"), HttpResponseValues.number(p, "buildId"),
                    HttpResponseValues.text(p, "version"), HttpResponseValues.number(p, "fileSize"),
                    HttpResponseValues.text(p, "checksum"), HttpResponseValues.text(p, "storagePath"));
            if (!productCode.equals(build.productCode())) throw HttpResponseValues.invalid();
            return build;
        } catch (RestClientException | IllegalArgumentException failure) {
            throw HttpResponseValues.invalid();
        }
    }
}
