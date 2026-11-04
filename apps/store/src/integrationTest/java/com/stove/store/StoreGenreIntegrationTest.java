package com.stove.store;

import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.store.core.domain.StoreProductRepository;
import com.stove.store.core.port.CatalogProducts;
import com.stove.store.core.service.ApplyCatalogProduct;
import com.stove.store.core.service.SynchronizeCatalogProducts;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.store.sync.enabled=false", "spring.kafka.listener.auto-startup=false"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class StoreGenreIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired StoreProductRepository products;
    @Autowired ApplyCatalogProduct apply;
    @Autowired SynchronizeCatalogProducts sync;
    @MockitoBean CatalogProducts catalog;
    @LocalServerPort int port;

    @BeforeEach void ready() {
        products.deleteAll();
        when(catalog.codes(0)).thenReturn(List.of());
        sync.execute();
    }

    @Test void genreVocabularyIsAnonymousAndContainsTheTenDefinitions() {
        JsonNode body = http().get().uri("/api/v1/storefront/genres").retrieve().body(JsonNode.class);
        assertThat(body.path("data").isArray()).isTrue();
        assertThat(body.path("data").size()).isEqualTo(10);
        assertThat(body.path("data").get(0).path("code").asText()).isEqualTo("ACTION");
        assertThat(body.path("data").get(0).path("label").asText()).isEqualTo("액션");
        assertThat(body.path("data").get(0).path("selectable").asBoolean()).isTrue();
    }

    @Test void allSelectedGenresAreAppliedBeforePaginationAndCounts() throws Exception {
        publish(901, "ACTION");
        publish(902, "RPG");
        publish(903, "ACTION", "RPG");
        JsonNode data = http().get().uri("/api/v1/storefront/products?genres=ACTION&genres=RPG&size=1")
                .retrieve().body(JsonNode.class).path("data");
        assertThat(data.path("totalElements").asInt()).isEqualTo(1);
        assertThat(data.path("content").get(0).path("productCode").asText()).isEqualTo("game-903");
        assertThat(data.path("content").get(0).path("genres").size()).isEqualTo(2);
        JsonNode next = http().get().uri("/api/v1/storefront/products?genres=ACTION&genres=RPG&size=1&page=1")
                .retrieve().body(JsonNode.class).path("data");
        assertThat(next.path("totalElements").asLong()).isEqualTo(1);
        assertThat(next.path("content").size()).isZero();
        JsonNode none = http().get().uri("/api/v1/storefront/products?q=902&genres=ACTION&genres=RPG")
                .retrieve().body(JsonNode.class).path("data");
        assertThat(none.path("totalElements").asLong()).isZero();
    }

    @Test void unknownGenreFiltersAreRejected() {
        int status = http().get().uri("/api/v1/storefront/products?genres=UNKNOWN")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).isEqualTo(400);
        for (String condition : List.of("genres=", "genres=ACTION&genres=ACTION")) {
            int invalid = http().get().uri("/api/v1/storefront/products?" + condition)
                    .exchange((request, response) -> response.getStatusCode().value());
            assertThat(invalid).as(condition).isEqualTo(400);
        }
    }

    private void publish(long id, String... genres) throws Exception {
        ObjectNode tree = json.valueToTree(product(id, 1, "Genre " + id, 1000));
        tree.set("genres", json.valueToTree(genres));
        apply.execute(json.treeToValue(tree, CatalogProductSnapshot.class));
    }

    private RestClient http() { return RestClient.create("http://localhost:" + port); }
}
