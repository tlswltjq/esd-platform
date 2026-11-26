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
import java.time.temporal.ChronoUnit;
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
import org.awaitility.core.ConditionTimeoutException;
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
 * <p>환불({@code load-refund-every} N > 0): 모든 주문이 끝 상태에 닿은 뒤, 결제된 측정 주문 N개마다 하나를 환불 요청한다.
 * 환불은 주문과 달리 열린 모델이 아니다 — 한 요청의 응답을 받은 뒤 남은 주기만큼 쉬고 다음을 보내므로, 응답이 주기보다 느리면
 * 실제 요청률이 목표보다 낮다(PR #216 R4). 순서 검사용 이벤트를 만드는 것이 목적이라 지연·처리량 지표로 쓰지 않는다.
 * 같은 주문(순서 키)에 결제 뒤 환불 이벤트가 이어지므로 같은 키 순서 위반 검사([결정 204] F)가 비교할 이웃이 생긴다.
 * 환불은 끝 상태(COMPLETED·FAILED·REJECTED·PENDING_REVIEW)에 닿을 때까지(최대 180초)만 기다리고, 닿지 않아도 그때 상태를 남긴다.
 * 격리 스택(모의 PG, {@code SIMULATED})에는 환불 취소 구현이 없고 환불 실행이 꺼져 있어 환불은 PG 결과 대기에서 멈춘다(E12 F).
 *
 * <p>속성: {@code stove.e2e.load-rate}(초당 주문, 기본 2), {@code load-duration-secs}(기본 60),
 * {@code load-buyers}(기본 20), {@code load-warmup-secs}(기본 10, 측정 앞에 더한다), {@code load-refund-every}(기본 0 = 환불 없음),
 * {@code load-settle-secs}(기본 180, 0이면 끝 상태를 HTTP로 묻지 않는다), {@code load-evidence}(결과 JSON 경로, 필수).
 *
 * <p>예정 시각은 요청마다 미리 정한다({@code 시작 + n × 주기}). 스케줄러가 늦게 깨어나도 늦은 만큼이 {@code lateMs}에 남는다.
 * 요청마다 예정·실제 요청·응답 시각을 epoch µs({@code scheduledEpochUs}·{@code requestedEpochUs}·{@code respondedEpochUs})로 남기고,
 * 측정 창({@code measuredWindow}: 예정 시각으로 고정한 {@code [측정 첫 예정 시각, + 측정 시간)})에 실제로 보낸 측정 주문 수로
 * 달성률을 낸다(E13·결정 231: 부하 발생기가 목표 요청률을 냈는가). 업무 완료·Outstanding 시계열은 DB 시각으로 따로 잰다.
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
        int refundEvery = Integer.parseInt(property("load-refund-every", "0"));
        int settleSeconds = Integer.parseInt(property("load-settle-secs", "180"));
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
        // 첫 주문의 예정 시각을 바로 남긴다. 장애 주입(tools/verification/support/e2e_faults.py)이 이 시각부터 잰다 —
        // Gradle 기동·상품 출시가 장애 시각에 섞이지 않게.
        Files.writeString(Path.of(evidence + ".started"), Long.toString(epochMicros(startedAt)));
        ScheduledFuture<?> schedule = ticker.scheduleAtFixedRate(() -> {
            int number = sequence.getAndIncrement();
            if (number >= total) {
                return;
            }
            Instant scheduledAt = startedAt.plusNanos(number * periodNanos);
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
        long windowStartUs = epochMicros(startedAt.plusNanos(warmup * periodNanos));
        long windowEndUs = windowStartUs + seconds * 1_000_000L;
        long inWindow = results.stream().filter(result -> !result.path("warmup").asBoolean())
                .filter(result -> result.path("requestedEpochUs").asLong(Long.MAX_VALUE) < windowEndUs).count();
        long measuredTotal = results.stream().filter(result -> !result.path("warmup").asBoolean()).count();

        List<ObjectNode> accepted = new ArrayList<>();
        for (ObjectNode result : results) {
            if (result.path("status").asInt() == 200) {
                accepted.add(result);
            }
        }
        // 끝 상태(PAID·FAILED)에 닿을 때까지만 기다린다. 걸린 시간 자체는 DB 시각으로 잰다. 장애 회차는 끝까지 닿지 않을 수 있으므로
        // 시간이 넘어도 멈추지 않고 마지막 상태를 남긴다. 0이면 묻지 않는다 — 부하 종료 뒤 해소 구간에 HTTP 조회를 더하지 않게.
        boolean settled = settleSeconds <= 0;
        if (!settled) {
            try {
                Awaitility.await().atMost(Duration.ofSeconds(settleSeconds)).pollInterval(Duration.ofSeconds(2)).until(() -> {
                    int finalCount = 0;
                    for (ObjectNode result : accepted) {
                        String state = orderState(result, buyers);
                        result.put("finalState", state);
                        if ("PAID".equals(state) || "FAILED".equals(state)) {
                            finalCount++;
                        }
                    }
                    return finalCount == accepted.size();
                });
                settled = true;
            } catch (ConditionTimeoutException timeout) {
                // 마지막으로 읽은 상태가 finalState 에 남아 있다.
            }
        }

        int refunds = refundEvery > 0 ? refund(accepted, buyers, refundEvery, periodNanos) : 0;

        ObjectNode output = JSON.createObjectNode()
                .put("startedAt", startedAt.toString())
                .put("sentAt", sentAt.toString())
                .put("settledAt", Instant.now().toString())
                .put("rate", rate)
                .put("durationSeconds", seconds)
                .put("warmupSeconds", warmupSeconds)
                .put("planned", total)
                .put("accepted", accepted.size())
                .put("refundEvery", refundEvery)
                .put("refunds", refunds)
                .put("productCode", product.path("productCode").asText())
                .put("settleSeconds", settleSeconds)
                .put("allSettled", settled);
        output.putObject("measuredWindow")
                .put("startEpochUs", windowStartUs).put("endEpochUs", windowEndUs).put("seconds", seconds)
                .put("sentInWindow", inWindow).put("sentAfterWindow", measuredTotal - inWindow)
                .put("achievedRate", (double) inWindow / seconds);
        ArrayNode orders = output.putArray("orders");
        results.forEach(orders::add);
        Files.writeString(evidence, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));

        assertThat(finished).as("계획한 주문을 모두 보냈다").isTrue();
        assertThat(results).hasSize(total);
    }

    /**
     * 결제된 측정 주문 {@code every}개마다 하나를 주문과 같은 도착률로 환불 요청하고, 끝 상태까지 기다린다.
     * 결과는 주문 결과에 {@code refundStatus}(요청 응답)·{@code refundHttpMs}·{@code refundState}(끝 상태)로 남긴다.
     *
     * @return 환불을 요청한 주문 수
     */
    private static int refund(List<ObjectNode> accepted, AtomicReferenceArray<Account> buyers, int every, long periodNanos)
            throws Exception {
        List<ObjectNode> targets = new ArrayList<>();
        int paid = 0;
        for (ObjectNode result : accepted) {
            if (!result.path("warmup").asBoolean() && "PAID".equals(result.path("finalState").asText()) && paid++ % every == 0) {
                targets.add(result);
            }
        }
        for (ObjectNode target : targets) {
            long startedNanos = System.nanoTime();
            target.put("refundRequestedAt", Instant.now().toString());
            E2eClient.Response response = authorized(target, buyers,
                    account -> Stove.gateway.post(refundPath(target), null, account.headers()));
            target.put("refundHttpMs", (System.nanoTime() - startedNanos) / 1_000_000.0).put("refundStatus", response.status());
            long remaining = periodNanos - (System.nanoTime() - startedNanos);
            if (remaining > 0) {
                TimeUnit.NANOSECONDS.sleep(remaining);
            }
        }
        // 끝 상태에 닿지 않아도 멈추지 않는다 — 그때 상태를 결과에 남기고, 수집 쪽이 "환불 미완료"로 회차를 무효 처리한다.
        try {
            awaitRefunds(targets, buyers);
        } catch (ConditionTimeoutException timeout) {
            // 마지막으로 읽은 상태가 refundState 에 남아 있다.
        }
        return targets.size();
    }

    private static void awaitRefunds(List<ObjectNode> targets, AtomicReferenceArray<Account> buyers) {
        Awaitility.await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            int settled = 0;
            for (ObjectNode target : targets) {
                E2eClient.Response response = authorized(target, buyers, account -> Stove.gateway.get(refundPath(target), account.headers()));
                String state = response.status() == 200 ? response.data().path("status").asText() : "HTTP_" + response.status();
                target.put("refundState", state);
                if (List.of("COMPLETED", "FAILED", "REJECTED", "PENDING_REVIEW").contains(state)) {
                    settled++;
                }
            }
            return settled == targets.size();
        });
    }

    private static String refundPath(ObjectNode result) {
        return "/api/v1/orders/" + result.path("orderNo").asText() + "/refund";
    }

    /** 토큰이 만료(401)됐으면 다시 로그인해 한 번만 다시 보낸다. */
    private static E2eClient.Response authorized(ObjectNode result, AtomicReferenceArray<Account> buyers, Call call) throws Exception {
        int index = result.path("buyer").asInt();
        E2eClient.Response response = call.send(buyers.get(index));
        if (response.status() == 401) {
            buyers.set(index, login(buyers.get(index).email(), PASSWORD));
            response = call.send(buyers.get(index));
        }
        return response;
    }

    @FunctionalInterface
    private interface Call {
        E2eClient.Response send(Account account) throws Exception;
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
            Instant requestedAt = Instant.now();
            result.put("scheduledEpochUs", epochMicros(scheduledAt)).put("requestedEpochUs", epochMicros(requestedAt))
                    .put("lateMs", Duration.between(scheduledAt, requestedAt).toNanos() / 1_000_000.0);
            E2eClient.Response response = Stove.gateway.post("/api/v1/orders", input, buyers.get(index).headers());
            if (response.status() == 401) {
                // 토큰 만료. 다시 로그인해 같은 요청을 한 번만 다시 보낸다(같은 requestId라 중복 주문이 생기지 않는다).
                buyers.set(index, login(buyers.get(index).email(), PASSWORD));
                response = Stove.gateway.post("/api/v1/orders", input, buyers.get(index).headers());
            }
            result.put("httpMs", (System.nanoTime() - startedNanos) / 1_000_000.0).put("status", response.status())
                    .put("respondedEpochUs", epochMicros(Instant.now()));
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

    private static long epochMicros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    private static String property(String name, String fallback) {
        String value = System.getProperty("stove.e2e." + name, "");
        return value.isBlank() ? fallback : value;
    }
}
