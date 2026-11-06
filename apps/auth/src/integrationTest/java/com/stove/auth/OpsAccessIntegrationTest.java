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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Auth의 공통 Outbox·DLT 운영 API는 일반 회원 토큰으로 열리지 않는다.
 * Gateway는 이 경로를 라우팅하지 않으므로 서비스 직접 접근을 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "stove.outbox.relay-enabled=false",
        "stove.auth.bff-client-secret=integration-secret",
        "stove.auth.bff-redirect-uri=http://localhost:3000/login/oauth2/code/gateway-bff"})
@Import(InfraContainers.MySql.class)
class OpsAccessIntegrationTest {
    private static final String ISSUER = "http://localhost:8091";

    @LocalServerPort private int port;
    @Autowired private JWKSource<SecurityContext> keys;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void memberTokenCannotReadOrRequeueDeadEventsOrReplayDeadLetters() throws Exception {
        String eventId = deadEvent();
        String member = "Bearer " + token(List.of("MEMBER"));

        assertThat(send("GET", "/api/v1/ops/outbox/dead", member).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/ops/dlt?topic=stove.auth.v1.DLT", member).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/ops/outbox/dead/" + eventId + "/requeue", member).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/ops/outbox/dead/requeue-all", member).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/ops/dlt/replay?topic=stove.auth.v1.DLT", member).statusCode()).isEqualTo(403);

        assertThat(statusOf(eventId)).isEqualTo("DEAD");
    }

    @Test
    void adminFindsNoOpsApiUntilAnInstanceExplicitlyEnablesIt() throws Exception {
        String admin = "Bearer " + token(List.of("ADMIN", "MEMBER"));

        for (String path : List.of("/api/v1/ops/outbox/dead", "/api/v1/ops/dlt?topic=stove.auth.v1.DLT")) {
            assertThat(send("GET", path, admin).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void anonymousCallerCannotReachOpsApi() throws Exception {
        String eventId = deadEvent();

        for (String path : List.of("/api/v1/ops/outbox/dead", "/api/v1/ops/dlt?topic=stove.auth.v1.DLT")) {
            assertThat(send("GET", path, null).statusCode()).isIn(401, 302);
        }
        assertThat(send("POST", "/api/v1/ops/outbox/dead/requeue-all", null).statusCode()).isIn(401, 302, 403);

        assertThat(statusOf(eventId)).isEqualTo("DEAD");
    }

    private String deadEvent() {
        String eventId = UUID.randomUUID().toString();
        outbox.saveAndFlush(OutboxEvent.pending(eventId, "UserAccount", "ops-access", "AccountFixture",
                "stove.auth.v1", "ops-access", "{}"));
        jdbc.update("UPDATE outbox_event SET status='DEAD', retry_count=10 WHERE event_id=?", eventId);
        return eventId;
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, eventId);
    }

    static String token(JWKSource<SecurityContext> keys, List<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).subject(UUID.randomUUID().toString())
                .audience(List.of("esd-api")).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("roles", roles).build();
        return new NimbusJwtEncoder(keys).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private String token(List<String> roles) {
        return token(keys, roles);
    }

    private HttpResponse<String> send(String method, String path, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
