package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.Account;
import static com.stove.e2e.ReviewJourney.REVIEWER_EMAIL;
import static com.stove.e2e.ReviewJourney.REVIEWER_PASSWORD;
import static com.stove.e2e.ReviewJourney.account;
import static com.stove.e2e.ReviewJourney.data;
import static com.stove.e2e.ReviewJourney.login;
import static com.stove.e2e.ReviewJourney.page;
import static com.stove.e2e.ReviewJourney.path;
import static com.stove.e2e.ReviewJourney.project;
import static com.stove.e2e.ReviewJourney.request;
import static com.stove.e2e.ReviewJourney.reviewPath;
import static com.stove.e2e.ReviewJourney.waitForResult;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("catalog")
@ExtendWith(LocalStackExtension.class)
@LocalStackApps({StackApp.AUTH, StackApp.STUDIO, StackApp.WEB_BFF, StackApp.GATEWAY, StackApp.REVIEW, StackApp.CATALOG})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogPublicationFlowTest {
    private static void approve(long project, Account creator, Account reviewer) {
        long review = request(project, creator, reviewer);
        assertThat(
                        Stove.gateway
                                .post(reviewPath(review) + "/approve", null, reviewer.headers())
                                .status())
                .isEqualTo(200);
        waitForResult(project, review, creator, "APPROVED");
    }

    private static long release(long project, Account creator) {
        E2eClient.Response response =
                Stove.gateway.post(path(project) + "/release-requests", ReviewJourney.submission(path(project), creator.headers()), creator.headers());
        assertThat(response.status()).isEqualTo(202);
        return response.data().path("id").asLong();
    }

    private static void completed(long project, long release, Account creator) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            // 출시 요청 이력은 페이지로 온다({items, totalPages}).
            JsonNode history = ReviewJourney.listItems(path(project) + "/release-requests", creator.headers());
            boolean found = false;
            for (JsonNode item : history) {
                if (item.path("id").asLong() == release) {
                    assertThat(item.path("status").asText()).isEqualTo("COMPLETED");
                    found = true;
                }
            }
            assertThat(found).as("출시 요청 %d 이 이력에 있다", release).isTrue();
        });
    }

    @Test
    @Order(1)
    void publishesOnlyApprovedRequestedSnapshotsAndReplacesAfterNewReview() throws Exception {
        ReviewJourney.Account creator = account(true);
        ReviewJourney.Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        ReviewJourney.Project p = project(creator);
        String code = data(p.id(), creator).path("productCode").asText();
        String detail = "/api/v1/products/by-code/" + code;
        assertThat(Stove.gateway.get(detail).status()).isEqualTo(404);
        assertThat(
                        Stove.gateway
                                .post(path(p.id()) + "/release-requests", ReviewJourney.submission(path(p.id()), creator.headers()), creator.headers())
                                .status())
                .isEqualTo(409);
        approve(p.id(), creator, reviewer);
        assertThat(Stove.gateway.get(detail).status()).isEqualTo(404);
        long first = release(p.id(), creator);
        completed(p.id(), first, creator);
        E2eClient.Response published = Stove.gateway.get(detail);
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.data().path("title").asText()).isEqualTo("First title");
        assertThat(published.data().toString())
                .doesNotContain("storagePath", "ownerSubject", "releaseRequestId");
        assertThat(release(p.id(), creator)).isEqualTo(first);
        page(p.id(), creator, "Second title", 2000);
        assertThat(Stove.gateway.get(detail).data().path("title").asText())
                .isEqualTo("First title");
        assertThat(
                        Stove.gateway
                                .post(path(p.id()) + "/release-requests", ReviewJourney.submission(path(p.id()), creator.headers()), creator.headers())
                                .status())
                .isEqualTo(409);
        approve(p.id(), creator, reviewer);
        long second = release(p.id(), creator);
        page(p.id(), creator, "Unreleased draft", 3000);
        completed(p.id(), second, creator);
        JsonNode current = Stove.gateway.get(detail).data();
        assertThat(current.path("title").asText()).isEqualTo("Second title");
        assertThat(current.path("price").asLong()).isEqualTo(2000);
        assertThat(current.path("productId")).isEqualTo(published.data().path("productId"));
        assertThat(current.path("publishedAt")).isEqualTo(published.data().path("publishedAt"));
        assertThat(data(p.id(), creator).path("releaseEligible").asBoolean()).isFalse();
        assertThat(Stove.gateway.get("/api/v1/products").data().path("totalElements").asLong())
                .isPositive();
        assertThat(Stove.gateway.get("/api/v1/products?size=101").status()).isEqualTo(400);
    }

    /**
     * 공개는 끝났지만 결과를 Studio에 내보내지 못한 채 Catalog가 내려가고, 다시 뜬 동안 브로커까지 응답하지 않아도 결과는
     * Outbox에 남았다가 브로커가 돌아오면 전달된다. 테스트가 Catalog와 브로커를 직접 멈춘다(결정 e2e-stack-in-junit).
     */
    @Test
    @Order(2)
    void aPublicationResultSurvivesRestartsAndABrokerOutage(LocalStack stack) throws Exception {
        stack.restart(StackApp.CATALOG, "--stove.outbox.relay-enabled=false");
        ReviewJourney.Account creator = account(true);
        ReviewJourney.Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        ReviewJourney.Project p = project(creator);
        approve(p.id(), creator, reviewer);
        long release = release(p.id(), creator);
        String code = data(p.id(), creator).path("productCode").asText();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/products/by-code/" + code).status()).isEqualTo(200));
        assertThat(data(p.id(), creator).path("currentReleaseRequest").path("status").asText())
                .as("결과가 아직 Studio에 닿지 않았다").isEqualTo("REQUESTED");

        stack.stop(StackApp.CATALOG);
        stack.pauseKafka();
        try {
            stack.startWithoutWaiting(StackApp.CATALOG,
                    "--spring.kafka.producer.properties.max.block.ms=1000",
                    "--spring.kafka.producer.properties.delivery.timeout.ms=2000",
                    "--spring.kafka.producer.properties.request.timeout.ms=1000");
            Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                assertThat(stack.log(StackApp.CATALOG)).as("브로커 무응답으로 발행이 실패했다")
                        .contains("not present in metadata after 1000 ms");
                assertThat(Integer.parseInt(stack.sql(
                        "SELECT COUNT(*) FROM stove_catalog.outbox_event WHERE status='PENDING'")))
                        .as("발행 실패 뒤에도 Outbox에 남은 결과").isPositive();
            });
        } finally {
            stack.resumeKafka();
        }
        stack.awaitReady(StackApp.CATALOG);

        completed(p.id(), release, creator);
        assertThat(Stove.gateway.get("/api/v1/products/by-code/" + code).status()).isEqualTo(200);
        assertThat(release(p.id(), creator)).as("같은 요청을 다시 보내도 같은 출시 요청이다").isEqualTo(release);
    }
}
