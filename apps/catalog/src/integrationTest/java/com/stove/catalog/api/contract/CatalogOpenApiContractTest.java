package com.stove.catalog.api.contract;

import static com.stove.catalog.CatalogFixtures.envelope;
import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.catalog.CatalogIntegrationTestBase;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.core.service.PublishProjectRelease;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.test.OpenApiSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

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
        ProjectReleaseRequestedEvent e = event(id(), 1);
        publish.execute(ReleaseSubmissionMapper.from(envelope(e), e));
        ResponseEntity<String> detail =
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

    @Autowired JdbcTemplate jdbc;

    @Test
    void paginationUsesProductIdAsTieBreakerForEqualPublicationTimes() {
        ProjectReleaseRequestedEvent first = event(id(), 1);
        ProjectReleaseRequestedEvent second = event(id(), 1);
        publish.execute(ReleaseSubmissionMapper.from(envelope(first), first));
        publish.execute(ReleaseSubmissionMapper.from(envelope(second), second));
        jdbc.update(
                "UPDATE catalog_product SET published_at='2100-01-01 00:00:00' WHERE product_code"
                    + " IN (?,?)",
                first.partitionKey(),
                second.partitionKey());
        ResponseEntity<String> page0 = http.getForEntity("/api/v1/products?size=1&page=0", String.class);
        ResponseEntity<String> page1 = http.getForEntity("/api/v1/products?size=1&page=1", String.class);
        assertThat(page0.getBody())
                .contains(second.partitionKey())
                .doesNotContain(first.partitionKey());
        assertThat(page1.getBody())
                .contains(first.partitionKey())
                .doesNotContain(second.partitionKey());
    }
    @Test
    void unconfiguredInternalBuildKeyKeepsTheEndpointClosed() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Download-Service-Key", "any-key");
        assertThat(http.exchange("/internal/v1/products/game/build", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(401);
    }
}
