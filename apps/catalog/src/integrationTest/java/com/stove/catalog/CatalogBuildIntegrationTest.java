package com.stove.catalog;

import static com.stove.catalog.CatalogFixtures.envelope;
import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.core.service.PublishProjectRelease;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties={"stove.catalog.download-service-key=internal-test-key","stove.catalog.settlement-service-key=settlement-test-key"})
class CatalogBuildIntegrationTest extends CatalogIntegrationTestBase {
    @Autowired TestRestTemplate http;
    @Autowired PublishProjectRelease publish;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    HttpEntity<Void> key() {
        HttpHeaders headers = new HttpHeaders(); headers.set("X-Download-Service-Key", "internal-test-key"); return new HttpEntity<>(headers);
    }
    ResponseEntity<String> build(String code) { return http.exchange("/internal/v1/products/" + code + "/build", HttpMethod.GET, key(), String.class); }
    void publish(ProjectReleaseRequestedEvent event) { publish.execute(ReleaseSubmissionMapper.from(envelope(event), event)); }

    @Test void onlyConfiguredServiceKeyCanReadCurrentPublishedBuild() throws Exception {
        ProjectReleaseRequestedEvent first = event(id(), 1); publish(first);
        String path = "/internal/v1/products/" + first.partitionKey() + "/build";
        assertThat(http.getForEntity(path, String.class).getStatusCode().value()).isEqualTo(401);
        HttpHeaders wrong = new HttpHeaders(); wrong.setBearerAuth("ordinary-user"); wrong.set("X-Download-Service-Key", "wrong");
        assertThat(http.exchange(path, HttpMethod.GET, new HttpEntity<>(wrong), String.class).getStatusCode().value()).isEqualTo(401);
        JsonNode initial = json.readTree(build(first.partitionKey()).getBody()).path("data");
        assertThat(initial.path("releaseRequestId").asLong()).isEqualTo(first.releaseRequestId());
        assertThat(initial.path("storagePath").asText()).isEqualTo(first.snapshot().storagePath());
        ProjectReleaseRequestedEvent next = event(first.snapshot().projectId(), 2); publish(next);
        JsonNode current = json.readTree(build(first.partitionKey()).getBody()).path("data");
        assertThat(current.path("releaseRequestId").asLong()).isEqualTo(next.releaseRequestId());
        assertThat(current.path("productId")).isEqualTo(initial.path("productId"));
        publish(event(first.snapshot().projectId(), 1));
        assertThat(json.readTree(build(first.partitionKey()).getBody()).path("data")).isEqualTo(current);
        assertThat(http.getForEntity("/v3/api-docs", String.class).getBody()).doesNotContain("/internal/", "PublishedBuildResponse");
        assertThat(http.exchange(path, HttpMethod.POST, key(), String.class).getStatusCode().is4xxClientError()).isTrue();
        assertThat(build("missing-" + id()).getStatusCode().value()).isEqualTo(404);
        assertThat(build("-bad").getStatusCode().value()).isEqualTo(400);
    }

    @Test void corruptOrRejectedCurrentReleaseIsNotReturnedAsPublishedBuild() {
        ProjectReleaseRequestedEvent event = event(id(), 1); publish(event);
        jdbc.update("UPDATE catalog_release SET status='REJECTED' WHERE release_request_id=?", event.releaseRequestId());
        assertThat(build(event.partitionKey()).getStatusCode().is5xxServerError()).isTrue();
    }

    @Test void settlementOwnerKeyIsSeparateFromDownloadAndReturnsOnlyImmutableIdentity() throws Exception {
        ProjectReleaseRequestedEvent event=event(id(),1);publish(event);
        String path="/internal/v1/products/"+event.partitionKey()+"/owner";
        assertThat(http.getForEntity(path,String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.exchange(path,HttpMethod.GET,key(),String.class).getStatusCode().value()).isEqualTo(401);
        HttpHeaders headers=new HttpHeaders();headers.set("X-Settlement-Service-Key","settlement-test-key");
        ResponseEntity<String> response=http.exchange(path,HttpMethod.GET,new HttpEntity<Void>(headers),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        JsonNode owner=json.readTree(response.getBody()).path("data");assertThat(owner.size()).isEqualTo(3);
        assertThat(owner.path("ownerSubject").asText()).isEqualTo(event.snapshot().ownerSubject());
        assertThat(http.exchange("/internal/v1/products/"+event.partitionKey()+"/build",HttpMethod.GET,new HttpEntity<Void>(headers),String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.getForEntity("/v3/api-docs",String.class).getBody()).doesNotContain("/owner","OwnerResponse");
    }
}
