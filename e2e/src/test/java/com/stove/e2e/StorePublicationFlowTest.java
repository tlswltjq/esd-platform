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

/**
 * 공개 상품이 Store 사본에 닿고, 사본을 비워도 HTTP 초기화로 다시 채워지며, Catalog가 없을 때는 가진 것으로만 답하고
 * 초기화에 실패하면 준비되지 않았다고 답한다. 테스트가 Store와 Catalog를 직접 멈추고 되살린다(결정
 * e2e-stack-in-junit).
 */
@Tag("store")
@ExtendWith(LocalStackExtension.class)
@LocalStackApps({StackApp.AUTH, StackApp.STUDIO, StackApp.WEB_BFF, StackApp.GATEWAY, StackApp.REVIEW, StackApp.CATALOG,
        StackApp.STORE})
// 자동 대조(기본 1분)가 이벤트 대신 사본을 맞추면 알림 유실이 가려진다. 이 여정의 사본은 이벤트와 기동 때의 HTTP
// 초기화로만 바뀌게 한다.
@LocalStackEnvironment("STORE_SYNC_RECONCILE_INTERVAL=PT1H")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StorePublicationFlowTest {
    private static void publish(long project, Account creator, Account reviewer) {
        long review = request(project, creator, reviewer);
        assertThat(Stove.gateway.post(reviewPath(review) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        waitForResult(project, review, creator, "APPROVED");
        assertThat(Stove.gateway.post(path(project) + "/release-requests", ReviewJourney.submission(path(project), creator.headers()), creator.headers()).status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(data(project, creator).path("currentReleaseRequest").path("status").asText())
                        .isEqualTo("COMPLETED"));
    }
    private static void matchesCatalog(String code) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            E2eClient.Response store = Stove.gateway.get("/api/v1/storefront/products/" + code);
            E2eClient.Response catalog = Stove.gateway.get("/api/v1/products/by-code/" + code);
            assertThat(store.status()).isEqualTo(200);
            assertThat(catalog.status()).isEqualTo(200);
            assertThat(store.data()).isEqualTo(catalog.data());
        });
    }
    /** Kafka 리스너를 끈 Store: 사본은 기동 때의 HTTP 초기화로만 채워진다. */
    private static final String WITHOUT_LISTENER = "--spring.kafka.listener.auto-startup=false";

    @Test
    @Order(1)
    void storeFollowsPublicationsRebuildsOverHttpAndAnswersHonestlyWithoutCatalog(LocalStack stack) throws Exception {
        ReviewJourney.Account creator = account(true);
        ReviewJourney.Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        ReviewJourney.Project project = project(creator);
        String code = data(project.id(), creator).path("productCode").asText();
        String detail = "/api/v1/storefront/products/" + code;
        assertThat(Stove.gateway.get(detail).status()).isEqualTo(404);
        publish(project.id(), creator, reviewer);
        matchesCatalog(code);
        JsonNode first = Stove.gateway.get(detail).data();
        assertThat(first.path("title").asText()).isEqualTo("First title");
        E2eClient.Response search = Stove.gateway.get("/api/v1/storefront/products?q=First&sort=PRICE_ASC&size=1");
        assertThat(search.status()).isEqualTo(200);
        assertThat(search.data().path("totalElements").asLong()).isPositive();
        page(project.id(), creator, "Store second title", 2000);
        assertThat(Stove.gateway.get(detail).data()).isEqualTo(first);
        publish(project.id(), creator, reviewer);
        matchesCatalog(code);
        page(project.id(), creator, "Unreleased draft", 3000);
        JsonNode current = Stove.gateway.get(detail).data();
        assertThat(current.path("title").asText()).isEqualTo("Store second title");
        assertThat(current.path("price").asLong()).isEqualTo(2000);
        assertThat(current.path("publishedAt")).isEqualTo(first.path("publishedAt"));
        assertThat(current.toString()).doesNotContain("storagePath", "ownerSubject", "releaseRequestId");

        // 사본을 비우고 리스너 없이 다시 띄운다: HTTP 초기화만으로 같은 사본이 돌아온다.
        stack.stop(StackApp.STORE);
        // 장르 표가 사본을 참조해 TRUNCATE는 거절된다. 지우면 장르 행도 함께 지워진다(ON DELETE CASCADE).
        stack.sql("DELETE FROM stove_store.store_product");
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_store.store_product")).as("비운 사본").isEqualTo("0");
        stack.start(StackApp.STORE, WITHOUT_LISTENER);
        matchesCatalog(code);
        assertThat(Stove.gateway.get(detail).data()).as("HTTP 초기화로 다시 채운 사본").isEqualTo(current);

        // Catalog가 없어도 이미 가진 사본으로 답한다.
        stack.stop(StackApp.CATALOG);
        E2eClient.Response stored = Stove.gateway.get(detail);
        assertThat(stored.status()).isEqualTo(200);
        assertThat(stored.data()).as("Catalog 없이 저장된 사본").isEqualTo(current);
        assertThat(Stove.gateway.get("/api/v1/storefront/products").status()).isEqualTo(200);

        // Catalog 없이 다시 뜨면 초기화에 실패하고, 준비되지 않았다고 답한다.
        stack.restart(StackApp.STORE, WITHOUT_LISTENER);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            E2eClient.Response result = Stove.gateway.get("/api/v1/storefront/products");
            assertThat(result.status()).isEqualTo(503);
            assertThat(result.errorCode()).isEqualTo("STORE_NOT_READY");
        });

        // Catalog를 되살린 뒤 다시 뜨면 초기화가 된다.
        stack.start(StackApp.CATALOG);
        stack.restart(StackApp.STORE, WITHOUT_LISTENER);
        matchesCatalog(code);
        assertThat(Stove.gateway.get(detail).data()).as("Catalog 복구 뒤 다시 채운 사본").isEqualTo(current);
    }

    /**
     * 브로커가 응답하지 않는 동안 Catalog가 내보내지 못한 공개 알림은 Outbox에 남았다가, 브로커가 돌아오면 Store 사본에
     * 닿는다. 같은 상품 키의 앞 이벤트 하나만 잃어도 드러나게, 자동 대조가 대신 고치지 못하도록 대조 주기를 길게 둔
     * 스택에서 본다({@link LocalStackEnvironment}).
     */
    @Test
    @Order(2)
    void aPublicationReachesStoreAfterABrokerOutage(LocalStack stack) throws Exception {
        // 앞 테스트가 리스너를 끈 채로 끝난다. 이벤트를 받는 기본 설정으로 다시 띄운다.
        stack.restart(StackApp.STORE);
        ReviewJourney.Account creator = account(true);
        ReviewJourney.Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        ReviewJourney.Project project = project(creator);
        String code = data(project.id(), creator).path("productCode").asText();
        String detail = "/api/v1/storefront/products/" + code;
        publish(project.id(), creator, reviewer);
        matchesCatalog(code);
        JsonNode before = Stove.gateway.get(detail).data();

        // 릴레이를 끈 Catalog: 새 공개본은 확정되지만 알림은 Outbox에 남는다.
        stack.restart(StackApp.CATALOG, "--stove.outbox.relay-enabled=false");
        page(project.id(), creator, "Published during outage", 4000);
        long review = request(project.id(), creator, reviewer);
        assertThat(Stove.gateway.post(reviewPath(review) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        waitForResult(project.id(), review, creator, "APPROVED");
        assertThat(Stove.gateway.post(path(project.id()) + "/release-requests",
                ReviewJourney.submission(path(project.id()), creator.headers()), creator.headers()).status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/products/by-code/" + code).data().path("title").asText())
                        .isEqualTo("Published during outage"));
        assertThat(Stove.gateway.get(detail).data()).as("알림이 나가기 전의 Store 사본").isEqualTo(before);

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
                        .as("발행 실패 뒤에도 Outbox에 남은 알림").isPositive();
            });
        } finally {
            stack.resumeKafka();
        }
        stack.awaitReady(StackApp.CATALOG);

        matchesCatalog(code);
        assertThat(Stove.gateway.get(detail).data().path("title").asText()).isEqualTo("Published during outage");
    }
}
