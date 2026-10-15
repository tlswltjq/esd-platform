package com.stove.store.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static com.stove.store.StoreFixtures.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.web.response.ApiResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;

class CatalogHttpClientTest {
    @Test void readsExistingPublicEnvelopesWithoutDependingOnCatalogClasses() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var product = product(1, 3, "한글 게임", 2000);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/products", exchange -> {
            Object data = exchange.getRequestURI().getPath().contains("by-code") ? product
                    : Map.of("content", List.of(Map.of("productCode", product.productCode())));
            byte[] body = mapper.writeValueAsBytes(ApiResponse.ok(data));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client = new CatalogHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), mapper);
            assertThat(client.codes(0)).containsExactly(product.productCode());
            assertThat(client.detail(product.productCode())).isEqualTo(product);
            assertThatThrownBy(() -> client.detail("wrong-code")).isInstanceOf(IllegalStateException.class);
        } finally { server.stop(0); }
    }
}
