package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.Account;
import static com.stove.e2e.ReviewJourney.JSON;
import static com.stove.e2e.ReviewJourney.Project;
import static com.stove.e2e.ReviewJourney.REVIEWER_EMAIL;
import static com.stove.e2e.ReviewJourney.REVIEWER_PASSWORD;
import static com.stove.e2e.ReviewJourney.account;
import static com.stove.e2e.ReviewJourney.data;
import static com.stove.e2e.ReviewJourney.login;
import static com.stove.e2e.ReviewJourney.path;
import static com.stove.e2e.ReviewJourney.project;
import static com.stove.e2e.ReviewJourney.request;
import static com.stove.e2e.ReviewJourney.reviewPath;
import static com.stove.e2e.ReviewJourney.waitForResult;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 메시징 비교 실험의 부하 드라이버([결정 204] G — 열린 모델로 도착률을 고정한다).
 *
 * <p>상품 하나를 출시하고 구매자 N명을 만든 뒤, 정해진 도착률로 주문을 만든다. 응답을 기다렸다가 다음 요청을
 * 보내는 닫힌 모델은 시스템이 느려지면 요청도 줄어 지연을 과소평가하므로, 요청 시각은 스케줄러가 정하고
 * 보내기는 요청마다 가상 스레드 하나가 맡는다(고정 크기 풀은 응답이 느려지면 대기열에 쌓여 실제 도착률이 줄어든다).
 * 예정 시각보다 늦게 출발한 요청은 {@code lateMs}로 남기고, 수집 쪽이 회차 유효성에 반영한다.
 * 앞쪽 {@code load-warmup-secs} 동안의 주문은 {@code warmup=true}로 표시하고 집계에서 뺀다([결정 204] G 예열 제외). 업무 완료 시간·브로커 구간 지연은 DB 시각으로 따로 잰다
 * ({@code tools/measurement}). 여기서는 주문번호, 요청 시각, HTTP 응답 지연(대조군)과 최종 상태만 남긴다.
 *
 * <p>속성: {@code stove.e2e.load-rate}(초당 주문, 기본 2), {@code load-duration-secs}(기본 60),
 * {@code load-buyers}(기본 20), {@code load-warmup-secs}(기본 10, 측정 앞에 더한다), {@code load-evidence}(결과 JSON 경로, 필수).
 */
@Tag("messaging-load")
class MessagingLoadTest {

    private static final String PASSWORD = "creator-password-1234";

    @Test
    void ordersAtAFixedArrivalRate() throws Exception {
        double rate = Double.parseDouble(property("load-rate", "2"));
        int seconds = Integer.parseInt(property("load-duration-secs", "60"));
        int buyerCount = Integer.parseInt(property("load-buyers", "20"));
        int warmupSeconds = Integer.parseInt(property("load-warmup-secs", "10"));
        Path evidence = Path.of(System.getProperty("stove.e2e.load-evidence"));

        JsonNode product = publishProduct();
        AtomicReferenceArray<Account> buyers = new AtomicReferenceArray<>(buyerCount);
        for (int index = 0; index < buyerCount; index++) {
            buyers.set(index, account(false));
        }

        int warmup = (int) Math.round(rate * warmupSeconds);
        int total = warmup + (int) Math.round(rate * seconds);
        long periodNanos = Math.round(1_000_000_000L / rate);
        ConcurrentLinkedQueue<ObjectNode> results = new ConcurrentLinkedQueue<>();
        AtomicInteger sequence = new AtomicInteger();
        CountDownLatch sent = new CountDownLatch(total);
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
        Instant startedAt = Instant.now();
        ScheduledFuture<?> schedule = ticker.scheduleAtFixedRate(() -> {
            int number = sequence.getAndIncrement();
            if (number >= total) {
                return;
            }
            Instant scheduledAt = Instant.now();
            senders.submit(() -> {
                try {
                    results.add(order(product, buyers, number % buyerCount, scheduledAt).put("warmup", number < warmup));
                } finally {
                    sent.countDown();
                }
            });
        }, 0, periodNanos, TimeUnit.NANOSECONDS);
        boolean finished = sent.await(warmupSeconds + seconds + 120L, TimeUnit.SECONDS);
        schedule.cancel(false);
        ticker.shutdownNow();
        senders.shutdown();
        senders.awaitTermination(30, TimeUnit.SECONDS);
        Instant sentAt = Instant.now();

        List<ObjectNode> accepted = new ArrayList<>();
        for (ObjectNode result : results) {
            if (result.path("status").asInt() == 200) {
                accepted.add(result);
            }
        }
        // 끝 상태(PAID·FAILED)에 닿을 때까지만 기다린다. 걸린 시간 자체는 DB 시각으로 잰다.
        Awaitility.await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            int settled = 0;
            for (ObjectNode result : accepted) {
                String state = orderState(result, buyers);
                result.put("finalState", state);
                if ("PAID".equals(state) || "FAILED".equals(state)) {
                    settled++;
                }
            }
            return settled == accepted.size();
        });

        ObjectNode output = JSON.createObjectNode()
                .put("startedAt", startedAt.toString())
                .put("sentAt", sentAt.toString())
                .put("settledAt", Instant.now().toString())
                .put("rate", rate)
                .put("durationSeconds", seconds)
                .put("warmupSeconds", warmupSeconds)
                .put("planned", total)
                .put("accepted", accepted.size())
                .put("productCode", product.path("productCode").asText());
        ArrayNode orders = output.putArray("orders");
        results.forEach(orders::add);
        Files.writeString(evidence, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));

        assertThat(finished).as("계획한 주문을 모두 보냈다").isTrue();
        assertThat(results).hasSize(total);
    }

    /** {@code LicensePurchaseFlowTest#preparePendingPurchase}와 같은 순서로 상품 하나를 출시한다. */
    private static JsonNode publishProduct() throws Exception {
        Account creator = account(true);
        Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        Project project = project(creator);
        long review = request(project.id(), creator, reviewer);
        assertThat(Stove.gateway.post(reviewPath(review) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        waitForResult(project.id(), review, creator, "APPROVED");
        E2eClient.Response release = Stove.gateway.post(path(project.id()) + "/release-requests",
                ReviewJourney.submission(path(project.id()), creator.headers()), creator.headers());
        assertThat(release.status()).as(release.toString()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(
                data(project.id(), creator).path("currentReleaseRequest").path("status").asText()).isEqualTo("COMPLETED"));
        String code = data(project.id(), creator).path("productCode").asText();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                Stove.gateway.get("/api/v1/storefront/products/" + code).status()).isEqualTo(200));
        return Stove.gateway.get("/api/v1/storefront/products/" + code).data();
    }

    private static ObjectNode order(JsonNode product, AtomicReferenceArray<Account> buyers, int index, Instant scheduledAt) {
        ObjectNode result = JSON.createObjectNode().put("scheduledAt", scheduledAt.toString()).put("buyer", index);
        try {
            Map<String, Object> input = Map.of("requestId", UUID.randomUUID().toString(),
                    "productCode", product.path("productCode").asText(),
                    "expectedContentVersion", product.path("contentVersion").asLong(),
                    "expectedAmount", product.path("price").asLong());
            long startedNanos = System.nanoTime();
            result.put("lateMs", Duration.between(scheduledAt, Instant.now()).toNanos() / 1_000_000.0);
            E2eClient.Response response = Stove.gateway.post("/api/v1/orders", input, buyers.get(index).headers());
            if (response.status() == 401) {
                // 토큰 만료. 다시 로그인해 같은 요청을 한 번만 다시 보낸다(같은 requestId라 중복 주문이 생기지 않는다).
                buyers.set(index, login(buyers.get(index).email(), PASSWORD));
                response = Stove.gateway.post("/api/v1/orders", input, buyers.get(index).headers());
            }
            result.put("httpMs", (System.nanoTime() - startedNanos) / 1_000_000.0).put("status", response.status());
            if (response.status() == 200) {
                result.put("orderNo", response.data().path("orderNo").asText());
            } else {
                result.put("error", response.toString());
            }
        } catch (Exception error) {
            result.put("status", -1).put("error", error.toString());
        }
        return result;
    }

    private static String orderState(ObjectNode result, AtomicReferenceArray<Account> buyers) throws Exception {
        int index = result.path("buyer").asInt();
        String path = "/api/v1/orders/" + result.path("orderNo").asText();
        E2eClient.Response response = Stove.gateway.get(path, buyers.get(index).headers());
        if (response.status() == 401) {
            buyers.set(index, login(buyers.get(index).email(), PASSWORD));
            response = Stove.gateway.get(path, buyers.get(index).headers());
        }
        return response.status() == 200 ? response.data().path("status").asText() : "HTTP_" + response.status();
    }

    private static String property(String name, String fallback) {
        String value = System.getProperty("stove.e2e." + name, "");
        return value.isBlank() ? fallback : value;
    }
}
