package com.stove.catalog.api.contract;

import static com.stove.catalog.CatalogFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.stove.catalog.CatalogIntegrationTestBase;
import com.stove.catalog.core.service.PublishProjectRelease;
import com.stove.common.test.OpenApiSnapshot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

class CatalogOpenApiContractTest extends CatalogIntegrationTestBase {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired PublishProjectRelease publish;

    @Test
    void openApiMatchesSnapshot() {
        OpenApiSnapshot.verify(port, "catalog");
    }

    @Test
    void publicApiExposesOnlyPublishedFieldsAndRejectsInvalidPagingAndWrites() {
        var e = event(id(), 1);
        publish.execute(envelope(e), e);
        var detail =
                http.getForEntity("/api/v1/products/by-code/" + e.partitionKey(), String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody())
                .contains(e.partitionKey(), "buildVersion")
                .doesNotContain(
                        "storagePath", "ownerSubject", "releaseRequestId", "reviewRequestId");
        assertThat(http.getForEntity("/api/v1/products?size=100", String.class).getBody())
                .contains("totalElements", "totalPages");
        for (String query : new String[] {"page=-1", "size=0", "size=101", "page=no"})
            assertThat(http.getForEntity("/api/v1/products?" + query, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        http.getForEntity("/api/v1/products/by-code/missing-" + id(), String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(
                        http.postForEntity("/api/v1/products/quote", null, String.class)
                                .getStatusCode()
                                .is4xxClientError())
                .isTrue();
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void paginationUsesProductIdAsTieBreakerForEqualPublicationTimes() {
        var first = event(id(), 1);
        var second = event(id(), 1);
        publish.execute(envelope(first), first);
        publish.execute(envelope(second), second);
        jdbc.update(
                "UPDATE catalog_product SET published_at='2100-01-01 00:00:00' WHERE product_code"
                    + " IN (?,?)",
                first.partitionKey(),
                second.partitionKey());
        var page0 = http.getForEntity("/api/v1/products?size=1&page=0", String.class);
        var page1 = http.getForEntity("/api/v1/products?size=1&page=1", String.class);
        assertThat(page0.getBody())
                .contains(second.partitionKey())
                .doesNotContain(first.partitionKey());
        assertThat(page1.getBody())
                .contains(first.partitionKey())
                .doesNotContain(second.partitionKey());
    }
}
