package com.stove.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/** 명시적으로 켠 인스턴스에서 ADMIN이 고른 DEAD 이벤트 한 건만 발행 대기로 되돌린다. */
@TestPropertySource(properties = {"server.address=127.0.0.1", "stove.messaging.ops.enabled=true"})
class RecoveryOpsIntegrationTest extends SettlementIntegrationTestBase {
    @LocalServerPort int port;
    @Autowired OutboxEventRepository rows;
    @Autowired JdbcTemplate jdbc;

    @Test
    void adminRequeuesOnlyTheChosenDeadEventAndCannotRequeueEverything() {
        String target = deadEvent();
        String untouched = deadEvent();
        String path = "/api/v1/ops/outbox/dead/" + target + "/requeue";

        assertThat(http(HttpMethod.POST, path, "MEMBER")).isEqualTo(403);
        assertThat(status(target)).isEqualTo("DEAD");
        assertThat(http(HttpMethod.GET, "/api/v1/ops/outbox/dead", "ADMIN")).isEqualTo(200);
        assertThat(http(HttpMethod.POST, path, "ADMIN")).isEqualTo(200);
        assertThat(status(target)).isEqualTo("PENDING");
        assertThat(http(HttpMethod.POST, path, "ADMIN")).isEqualTo(404);
        assertThat(http(HttpMethod.POST, "/api/v1/ops/outbox/dead/requeue-all", "ADMIN")).isEqualTo(403);
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

    private int http(HttpMethod method, String path, String role) {
        return RestClient.create("http://127.0.0.1:" + port).method(method).uri(path)
                .header("Authorization", "Bearer " + token("operator", role))
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
