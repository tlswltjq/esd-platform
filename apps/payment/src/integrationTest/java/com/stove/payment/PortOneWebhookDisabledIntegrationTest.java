package com.stove.payment;

import static com.stove.payment.PortOneFixtures.sign;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.payment.core.port.PortOneWebhookVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "stove.payment.pg.provider=SIMULATED",
        "stove.payment.pg.base-url=http://127.0.0.1:1", "stove.payment.pg.api-key=fixture-disabled-provider-key",
        "stove.outbox.relay-enabled=false", "stove.payment.processing-enabled=false",
        "spring.kafka.listener.auto-startup=false"})
class PortOneWebhookDisabledIntegrationTest extends PaymentDatabaseIntegrationTestBase {
    @Autowired ObjectProvider<PortOneWebhookVerifier> verifiers;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test void aDisabledProviderHasNoVerifierAndCannotAcceptEvenASignedDelivery() {
        assertThat(verifiers.getIfAvailable()).isNull();
        Integer receipts = jdbc.queryForObject("SELECT COUNT(*) FROM payment_pg_webhook", Integer.class);
        Integer payments = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_payment", Integer.class);
        byte[] body = "{\"type\":\"Transaction.Paid\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        for (String role : new String[] {null, "MEMBER", "ADMIN"}) {
            RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).post()
                    .uri("/api/v1/payments/webhooks/portone").contentType(MediaType.APPLICATION_JSON)
                    .header("webhook-id", "msg_disabled").header("webhook-timestamp", timestamp)
                    .header("webhook-signature", sign("msg_disabled", timestamp, body));
            if (role != null) request.header("Authorization", "Bearer " + token("fixture-buyer", role));
            int status = request.body(body).exchange((outgoing, response) -> response.getStatusCode().value());
            assertThat(status).isEqualTo(role == null ? 401 : 403);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_pg_webhook", Integer.class)).isEqualTo(receipts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_payment", Integer.class)).isEqualTo(payments);
    }
}
