package com.stove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.InfraContainers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "stove.outbox.relay-enabled=false",
        "stove.auth.bff-client-secret=integration-secret",
        "stove.auth.bff-redirect-uri=http://localhost:3000/login/oauth2/code/gateway-bff"})
@Import(InfraContainers.MySql.class)
class PrometheusScrapeIntegrationTest {
    @LocalServerPort private int port;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void anonymousScrapeExportsMetricsInsteadOfRedirectingToLogin() throws Exception {
        HttpResponse<String> response = request("/actuator/prometheus", false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes", "stove_outbox_pending", "application=\"auth\"");
    }

    @Test
    void scrapeAccessDoesNotOpenOtherPathsOrBypassCsrf() throws Exception {
        for (String path : List.of("/actuator/prometheus/extra", "/actuator/env", "/api/v1/auth/session")) {
            assertThat(request(path, false).statusCode()).isIn(401, 302);
        }
        assertThat(request("/actuator/prometheus", true).statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> request(String path, boolean post) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "text/plain");
        if (post) builder.POST(HttpRequest.BodyPublishers.noBody());
        else builder.GET();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
