package com.stove.e2e;

import static com.stove.e2e.DownloadPurchaseFlowTest.approveAndPublish;
import static com.stove.e2e.DownloadPurchaseFlowTest.verifyZip;
import static com.stove.e2e.ReviewJourney.JSON;
import static com.stove.e2e.ReviewJourney.account;
import static com.stove.e2e.ReviewJourney.data;
import static com.stove.e2e.ReviewJourney.login;
import static com.stove.e2e.ReviewJourney.project;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.e2e.ReviewJourney.Account;
import com.stove.e2e.ReviewJourney.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Current public APIs, durable Payment processing, and actual Tempo parent relationships. */
@Tag("tracing")
class TracePropagationE2eTest {
    private static Path evidence() { return Path.of(System.getProperty("stove.e2e.tracing-evidence")); }
    private static ObjectNode saved() throws Exception { return (ObjectNode) JSON.readTree(Files.readString(evidence())); }
    private static void save(ObjectNode state) throws Exception {
        Files.writeString(evidence(), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        Files.setPosixFilePermissions(evidence(), PosixFilePermissions.fromString("rw-------"));
    }
    private static E2eClient tempo() { return new E2eClient(System.getProperty("stove.e2e.tempo-base-url")); }
    private static String scenario() { return System.getProperty("stove.e2e.tracing-scenario", "initial"); }
    private static String freshTraceId() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String parent(String id, boolean sampled) { return "00-" + id + "-" + freshTraceId().substring(0, 16) + (sampled ? "-01" : "-00"); }

    @Test void prepareTwoDurableReceipts() throws Exception {
        Account creator = account(true);
        Project project = project(creator);
        approveAndPublish(project.id(), creator);
        String code = data(project.id(), creator).path("productCode").asText();
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/storefront/products/" + code).status()).isEqualTo(200));
        ObjectNode state = JSON.createObjectNode().put("zip", Base64.getEncoder().encodeToString(project.zip()));
        state.set("product", Stove.gateway.get("/api/v1/products/by-code/" + code).data());
        state.putArray("purchases");
        purchase(state, "initial-A");
        purchase(state, "initial-B");
        save(state);
    }

    @Test void preparePurchase() throws Exception {
        ObjectNode state = saved();
        purchase(state, scenario());
        save(state);
    }

    private static void purchase(ObjectNode state, String name) throws Exception {
        Account buyer = account(false);
        boolean sampled = Boolean.parseBoolean(System.getProperty("stove.e2e.tracing-sampled", "true"));
        String trace = freshTraceId();
        Map<String, String> headers = new HashMap<>(buyer.headers());
        headers.put("traceparent", parent(trace, sampled));
        JsonNode product = state.path("product");
        E2eClient.Response response = Stove.gateway.post("/api/v1/orders", Map.of("requestId", UUID.randomUUID().toString(),
                "productCode", product.path("productCode").asText(), "expectedContentVersion", product.path("contentVersion").asLong(),
                "expectedAmount", product.path("price").asLong()), headers);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().getFirst("X-Correlation-Id")).isEqualTo(trace);
        String number = response.data().path("orderNo").asText();
        String expected = System.getProperty("stove.e2e.tracing-expected-state", "PENDING");
        SettlementPurchaseFlowTest.waitStatus(number, buyer, expected);
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/payments/" + number, buyer.headers()).status()).isEqualTo(200));
        JsonNode payment = Stove.gateway.get("/api/v1/payments/" + number, buyer.headers()).data();
        ((ArrayNode) state.path("purchases")).addObject().put("scenario", name).put("buyerEmail", buyer.email())
                .put("orderNo", number).put("paymentId", payment.path("paymentId").asText()).put("traceId", trace).put("sampled", sampled);
    }

    @Test void completedPurchasesKeepTheirOriginalTraceAndDownloadSeparately() throws Exception { verifyCompleted(true, true); }
    @Test void verifyCommonHttpTracing() throws Exception { verifyCompleted(true, false); }
    @Test void businessCompletesWithoutTraceCollection() throws Exception { verifyCompleted(false, false); }

    private static void verifyCompleted(boolean inspectTraces, boolean inspectPayment) throws Exception {
        ObjectNode state = saved();
        for (JsonNode purchase : state.path("purchases")) {
            String name = purchase.path("scenario").asText();
            if (!name.startsWith(scenario())) continue;
            Account buyer = login(purchase.path("buyerEmail").asText(), "creator-password-1234");
            String number = purchase.path("orderNo").asText();
            SettlementPurchaseFlowTest.waitStatus(number, buyer, "PAID");
            String code = state.path("product").path("productCode").asText();
            Awaitility.await().atMost(Duration.ofSeconds(50)).untilAsserted(() ->
                    assertThat(Stove.gateway.get("/api/v1/library/" + code, buyer.headers()).status()).isEqualTo(200));
            String downloadTrace = freshTraceId();
            Map<String, String> headers = new HashMap<>(buyer.headers());
            headers.put("traceparent", parent(downloadTrace, purchase.path("sampled").asBoolean()));
            E2eClient.Response ticket = Stove.gateway.get("/api/v1/downloads/" + code + "/ticket", headers);
            assertThat(ticket.status()).isEqualTo(200);
            verifyZip(ticket.data(), Base64.getDecoder().decode(state.path("zip").asText()));
            ((ObjectNode) purchase).put("downloadTraceId", downloadTrace).put("zipChecksum", ticket.data().path("checksum").asText());
            Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                    assertThat(SettlementPurchaseFlowTest.sales(SettlementPurchaseFlowTest.admin()).path("content").findValuesAsText("orderNo")).contains(number));
            if (inspectTraces) {
                String trace = purchase.path("traceId").asText();
                JsonNode original = awaitTrace(trace, inspectPayment
                        ? Set.of("gateway", "order", "payment", "license", "settlement", "catalog")
                        : Set.of("gateway", "order", "catalog"), spans -> {
                    assertHttpParent(spans, "gateway", "order");
                    assertHttpParent(spans, "order", "catalog");
                    if (inspectPayment) assertPaymentTrace(spans, trace, purchase.path("paymentId").asText());
                });
                JsonNode download = awaitTrace(downloadTrace, Set.of("gateway", "download", "license", "catalog"), spans -> {
                    assertHttpParent(spans, "gateway", "download");
                    assertHttpParent(spans, "download", "license");
                    assertHttpParent(spans, "download", "catalog");
                });
                assertThat(downloadTrace).isNotEqualTo(trace);
                writeTrace(name + "-purchase", original);
                writeTrace(name + "-download", download);
                ((ObjectNode) purchase).put("traceVerified", true);
            }
            ((ObjectNode) purchase).put("businessVerified", true);
            if (scenario().equals("sampling-zero")) {
                Awaitility.await().during(Duration.ofSeconds(7)).atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        assertThat(tempo().get("/api/traces/" + purchase.path("traceId").asText(),
                                Map.of("Accept", "application/json")).status()).isEqualTo(404));
                ((ObjectNode) purchase).put("unsampledNotExported", true);
            }
        }
        assertThat(state.path("purchases").findValuesAsText("scenario").stream().anyMatch(s -> s.startsWith(scenario()))).isTrue();
        save(state);
    }

    private static boolean isClient(TraceSpan span) { return span.kind().equals("SPAN_KIND_CLIENT") || span.kind().equals("3"); }

    private static void assertHttpParent(List<TraceSpan> spans, String caller, String receiver) {
        assertThat(spans.stream().filter(s -> s.service().equals(receiver)).anyMatch(s ->
                spans.stream().anyMatch(p -> p.service().equals(caller) && p.id().equals(s.parent()) && isClient(p))))
                .as("HTTP parent %s -> %s", caller, receiver).isTrue();
    }

    private static void assertPaymentTrace(List<TraceSpan> spans, String trace, String paymentId) {
        List<TraceSpan> processing = spans.stream().filter(s -> s.service().equals("payment") && s.name().equals("payment.process")).toList();
        assertThat(processing).isNotEmpty().allSatisfy(s -> {
            assertThat(s.traceId()).isEqualTo(trace);
            assertThat(s.attributes().get("payment.id")).isEqualTo(paymentId);
            assertThat(spans.stream().anyMatch(p -> p.id().equals(s.parent()) && p.service().equals("order"))).isTrue();
        });
        for (String downstream : List.of("license", "settlement")) {
            assertThat(spans.stream().filter(s -> s.service().equals(downstream))
                    .anyMatch(s -> processing.stream().anyMatch(p -> hasAncestor(spans, s, p.id()))))
                    .as("%s is a descendant of Payment %s", downstream, paymentId).isTrue();
        }
        assertThat(spans.stream().filter(s -> s.service().equals("payment") && isClient(s))
                .anyMatch(s -> processing.stream().anyMatch(p -> hasAncestor(spans, s, p.id()))))
                .as("PG HTTP call is inside the payment attempt").isTrue();
        assertHttpParent(spans, "settlement", "catalog");
    }

    private static JsonNode awaitTrace(String traceId, Set<String> services, Consumer<List<TraceSpan>> assertions) {
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            E2eClient.Response response = tempo().get("/api/traces/" + traceId, Map.of("Accept", "application/json"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(spans(response.body()).stream().map(TraceSpan::service).collect(Collectors.toSet())).containsAll(services);
            assertions.accept(spans(response.body()));
        });
        JsonNode trace = tempo().get("/api/traces/" + traceId, Map.of("Accept", "application/json")).body();
        assertThat(spans(trace)).isNotEmpty().allSatisfy(s -> assertThat(s.traceId()).isEqualTo(traceId));
        return trace;
    }

    private static void writeTrace(String label, JsonNode trace) throws Exception {
        Files.writeString(evidence().resolveSibling(label + "-trace.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
    }

    static List<TraceSpan> spans(JsonNode trace) {
        List<TraceSpan> result = new ArrayList<>();
        JsonNode batches = trace.has("batches") ? trace.path("batches") : trace.path("resourceSpans");
        for (JsonNode batch : batches) {
            String service = attributes(batch.path("resource").path("attributes")).getOrDefault("service.name", "");
            JsonNode scopes = batch.has("scopeSpans") ? batch.path("scopeSpans") : batch.path("instrumentationLibrarySpans");
            for (JsonNode scope : scopes) for (JsonNode span : scope.path("spans")) {
                result.add(new TraceSpan(service, span.path("name").asText(), hex(span.path("traceId").asText(), 32),
                        hex(span.path("spanId").asText(), 16), hex(span.path("parentSpanId").asText(), 16),
                        span.path("kind").asText(), attributes(span.path("attributes"))));
            }
        }
        return result;
    }

    private static String hex(String id, int length) {
        if (id.isEmpty() || (id.length() == length && id.matches("[0-9a-f]+"))) return id;
        return HexFormat.of().formatHex(Base64.getDecoder().decode(id));
    }

    private static Map<String, String> attributes(JsonNode values) {
        Map<String, String> result = new HashMap<>();
        for (JsonNode value : values) result.put(value.path("key").asText(), value.path("value").path("stringValue").asText());
        return result;
    }

    private static boolean hasAncestor(List<TraceSpan> spans, TraceSpan child, String ancestor) {
        Set<String> visited = new HashSet<>();
        String parent = child.parent();
        while (!parent.isEmpty() && visited.add(parent)) {
            if (parent.equals(ancestor)) return true;
            String current = parent;
            parent = spans.stream().filter(s -> s.id().equals(current)).map(TraceSpan::parent).findFirst().orElse("");
        }
        return false;
    }

    record TraceSpan(String service, String name, String traceId, String id, String parent, String kind, Map<String, String> attributes) { }
}
