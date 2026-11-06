package com.stove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 명시적으로 켠 Auth 인스턴스에서 ADMIN이 고른 DEAD 이벤트 한 건만 발행 대기로 되돌린다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "stove.outbox.relay-enabled=false", "stove.messaging.ops.enabled=true",
        "stove.auth.bff-client-secret=integration-secret",
        "stove.auth.bff-redirect-uri=http://localhost:3000/login/oauth2/code/gateway-bff"})
@Import(InfraContainers.MySql.class)
class RecoveryOpsIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JWKSource<SecurityContext> keys;
    @Autowired private OutboxEventRepository rows;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void adminRequeuesOnlyTheChosenDeadEventAndCannotRequeueEverything() throws Exception {
        String target = deadEvent();
        String untouched = deadEvent();
        String path = "/api/v1/ops/outbox/dead/" + target + "/requeue";
        String member = "Bearer " + OpsAccessIntegrationTest.token(keys, List.of("MEMBER"));
        String admin = "Bearer " + OpsAccessIntegrationTest.token(keys, List.of("ADMIN", "MEMBER"));

        assertThat(send("POST", path, member)).isEqualTo(403);
        assertThat(status(target)).isEqualTo("DEAD");
        assertThat(send("GET", "/api/v1/ops/outbox/dead", admin)).isEqualTo(200);
        assertThat(send("POST", path, admin)).isEqualTo(200);
        assertThat(status(target)).isEqualTo("PENDING");
        assertThat(send("POST", path, admin)).isEqualTo(404);
        assertThat(send("POST", "/api/v1/ops/outbox/dead/requeue-all", admin)).isEqualTo(403);
        assertThat(status(untouched)).isEqualTo("DEAD");
    }

    private String deadEvent() {
        String eventId = UUID.randomUUID().toString();
        rows.saveAndFlush(OutboxEvent.pending(eventId, "OpsFixture", eventId, "OpsFixture",
                "stove.ops-fixture.v1", eventId, "{}"));
        jdbc.update("UPDATE outbox_event SET status='DEAD', retry_count=10 WHERE event_id=?", eventId);
        return eventId;
    }

    private String status(String eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, eventId);
    }

    private int send(String method, String path, String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json").header("Authorization", authorization)
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
