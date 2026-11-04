package com.stove.store.adapter;

import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.store.config.CatalogClientConfiguration;
import com.stove.store.core.domain.CatalogUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogHttpValidationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @ParameterizedTest
    @ValueSource(strings = {"price", "catalogVersion", "saleStatus", "listed", "publishedAt", "productId", "genres"})
    void canonicalResponsesCannotSilentlyDefaultMissingFields(String field) throws Exception {
        ObjectNode data = JSON.valueToTree(product(1, 1, "Canonical", 1000));
        data.remove(field);
        serve(data, 200, client -> assertThatThrownBy(() -> client.detail("game-1")).isInstanceOf(IllegalStateException.class));
    }

    @Test void fractionalAmountsAreNotTruncatedIntoAValidCanonicalPrice() throws Exception {
        ObjectNode data = JSON.valueToTree(product(1, 1, "Canonical", 1000));
        data.put("price", 1000.5);
        serve(data, 200, client -> assertThatThrownBy(() -> client.detail("game-1")).isInstanceOf(IllegalStateException.class));
    }

    @Test void aRepeatedOrInconsistentSourcePageCannotMasqueradeAsCompletedSynchronization() throws Exception {
        ObjectNode data = JSON.createObjectNode().put("page", 1).put("size", 100).put("totalElements", 1).put("totalPages", 1);
        data.putArray("content").addObject().put("productCode", "game-1");
        serve(data, 200, client -> assertThatThrownBy(() -> client.codes(0)).isInstanceOf(IllegalStateException.class));
        data.put("page", 0).put("totalElements", 0);
        serve(data, 200, client -> assertThatThrownBy(() -> client.codes(0)).isInstanceOf(IllegalStateException.class));
    }

    @Test void temporaryHttpFailureHasASanitizedRetryableCategory() throws Exception {
        ObjectNode data = JSON.createObjectNode().put("providerMessage", "sensitive provider detail");
        serve(data, 503, client -> assertThatThrownBy(() -> client.codes(0)).isInstanceOf(CatalogUnavailableException.class)
                .hasMessage("Catalog is temporarily unavailable").hasNoCause());
    }

    private void serve(ObjectNode data, int status, Consumer<CatalogHttpClient> assertion) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ObjectNode response = JSON.createObjectNode().put("success", true);
        response.set("data", data);
        byte[] bytes = JSON.writeValueAsBytes(response);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            CatalogHttpClient client = new CatalogClientConfiguration().catalogProducts("http://127.0.0.1:" + server.getAddress().getPort(), "isolated-source-key", JSON);
            assertion.accept(client);
        } finally { server.stop(0); }
    }
}
