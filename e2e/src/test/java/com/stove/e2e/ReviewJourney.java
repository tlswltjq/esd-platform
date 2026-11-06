package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.awaitility.Awaitility;

final class ReviewJourney {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final String REVIEWER_EMAIL = "reviewer-check@review.local";
    static final String REVIEWER_PASSWORD = "reviewer-password-1234";
    static final String ADMIN_EMAIL = "admin-check@review.local";
    static final String ADMIN_PASSWORD = "admin-password-1234";

    record Account(String email, String password, String subject, Map<String, String> headers) {}

    record Project(long id, byte[] zip) {}

    private ReviewJourney() {}

    static Account account(boolean creator) throws Exception {
        String email = "review-" + UUID.randomUUID() + "@example.com",
                password = "creator-password-1234";
        E2eClient.Response signup =
                Stove.gateway.post(
                        creator ? "/api/v1/auth/signup" : "/api/v1/auth/signup/member",
                        Map.of("email", email, "password", password));
        assertThat(signup.status()).isEqualTo(200);
        return login(email, password);
    }

    static Account login(String email, String password) throws Exception {
        String token = OidcLogin.token(email, password);
        String subject =
                JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]))
                        .path("sub")
                        .asText();
        return new Account(email, password, subject, Map.of("Authorization", "Bearer " + token));
    }

    static Project project(Account creator) throws Exception {
        E2eClient.Response created =
                Stove.gateway.post(
                        "/api/v1/studio/projects",
                        Map.of(
                                "productCode",
                                "REVIEW-" + UUID.randomUUID(),
                                "title",
                                "First title"),
                        creator.headers());
        assertThat(created.status()).isEqualTo(201);
        long id = created.data().path("id").asLong();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        assertThat(
                        Stove.gateway
                                .postMultipart(
                                        path(id) + "/thumbnail",
                                        Map.of(),
                                        png.toByteArray(),
                                        "image.png",
                                        "image/png",
                                        creator.headers())
                                .status())
                .isEqualTo(201);
        byte[] zip = zip("first binary");
        upload(id, creator, "1", zip);
        page(id, creator, "First title", 1000);
        return new Project(id, zip);
    }

    static byte[] zip(String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("game.bin"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    static void upload(long id, Account creator, String version, byte[] bytes) {
        assertThat(
                        Stove.gateway
                                .postMultipart(
                                        path(id) + "/builds",
                                        Map.of("version", version),
                                        bytes,
                                        "game.zip",
                                        "application/zip",
                                        creator.headers())
                                .status())
                .isEqualTo(201);
    }

    static void page(long id, Account creator, String title, long price) {
        page(id, creator, title, price, List.of("ACTION", "RPG"));
    }

    static void page(long id, Account creator, String title, long price, List<String> genres) {
        JsonNode p = data(id, creator);
        Map<String, Object> page =
                Map.of(
                        "contentVersion",
                        p.path("contentVersion").asLong(),
                        "title",
                        title,
                        "shortDescription",
                        "Submitted summary",
                        "thumbnailUrl",
                        p.path("storePage").path("thumbnailUrl").asText(),
                        "videoUrl",
                        "https://example.com/video",
                        "content",
                        "Submitted content for " + title,
                        "price",
                        price, "genres", genres);
        assertThat(Stove.gateway.put(path(id) + "/store-page", page, creator.headers()).status())
                .isEqualTo(200);
    }

    static long request(long id, Account creator, Account reviewer) {
        E2eClient.Response response = Stove.gateway.post(path(id) + "/review-requests", ReviewJourney.submission(path(id), creator.headers()), creator.headers());
        assertThat(response.status()).isEqualTo(202);
        long request = response.data().path("id").asLong();
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(
                                                Stove.gateway
                                                        .get(
                                                                reviewPath(request),
                                                                reviewer.headers())
                                                        .status())
                                        .isEqualTo(200));
        return request;
    }

    static void waitForResult(long project, long request, Account creator, String state) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            JsonNode history = listItems(path(project) + "/review-requests", creator.headers());
                            JsonNode match = null;
                            for (JsonNode r : history) if (r.path("id").asLong() == request) match = r;
                            assertThat(match).isNotNull();
                            assertThat(match.path("status").asText()).isEqualTo(state);
                        });
    }

    static ArrayNode listItems(String path, Map<String, String> headers) {
        ArrayNode items = JSON.createArrayNode();
        for (int page = 0; ; page++) {
            E2eClient.Response response = Stove.gateway.get(path + "?page=" + page + "&size=100", headers);
            assertThat(response.status()).as(path).isEqualTo(200);
            JsonNode result = response.data();
            assertThat(result.path("items").isArray()).as(path + " page items").isTrue();
            items.addAll((ArrayNode) result.path("items"));
            if (page + 1 >= result.path("totalPages").asInt()) return items;
        }
    }

    /** A new logical request gets a new key; explicit retry tests retain their original body. */
    static Map<String, Object> submission(String projectPath, Map<String, String> headers) {
        E2eClient.Response current = Stove.gateway.get(projectPath, headers);
        long version = current.status() == 200 ? current.data().path("contentVersion").asLong() : 1;
        return Map.of("requestId", UUID.randomUUID().toString(), "contentVersion", version);
    }

    static JsonNode data(long id, Account creator) {
        E2eClient.Response r = Stove.gateway.get(path(id), creator.headers());
        assertThat(r.status()).isEqualTo(200);
        return r.data();
    }

    static String path(long id) {
        return "/api/v1/studio/projects/" + id;
    }

    static String reviewPath(long id) {
        return "/api/v1/reviews/" + id;
    }

    /**
     * 결정 93: 결제 뒤 보유권은 저절로 생기지 않는다. 보관함 항목이 생길 때까지 기다렸다가 결제한 구매자가 직접 등록하고
     * 라이브러리 상세를 돌려준다. 이미 쓸 수 있는 보유권이 있으면 그대로 돌려준다.
     */
    static JsonNode registeredLibrary(Account buyer, String productCode) {
        Awaitility.await().atMost(Duration.ofSeconds(50)).untilAsserted(() -> {
            if (Stove.gateway.get("/api/v1/library/" + productCode, buyer.headers()).status() == 200) return;
            JsonNode item = registrableItem(buyer, productCode);
            assertThat(item).as("등록할 보관함 항목").isNotNull();
            E2eClient.Response registered = Stove.gateway.post(
                    "/api/v1/storage/" + item.path("licenseId").asText() + "/registration", null, buyer.headers());
            assertThat(registered.status()).as(registered.toString()).isEqualTo(200);
        });
        E2eClient.Response library = Stove.gateway.get("/api/v1/library/" + productCode, buyer.headers());
        assertThat(library.status()).as(library.toString()).isEqualTo(200);
        return library.data();
    }

    /** 보관함에서 상품의 등록 가능한 항목. 없으면 null. */
    static JsonNode registrableItem(Account buyer, String productCode) {
        E2eClient.Response storage = Stove.gateway.get("/api/v1/storage?size=100", buyer.headers());
        assertThat(storage.status()).as(storage.toString()).isEqualTo(200);
        for (JsonNode item : storage.data().path("content")) {
            if (productCode.equals(item.path("productCode").asText()) && item.path("registrable").asBoolean()) return item;
        }
        return null;
    }

    /** 보관함에서 주문 한 건의 항목. 아직 없으면 MissingNode. */
    static JsonNode storageItem(Account buyer, String orderNo) {
        E2eClient.Response storage = Stove.gateway.get("/api/v1/storage?size=100", buyer.headers());
        assertThat(storage.status()).as(storage.toString()).isEqualTo(200);
        for (JsonNode item : storage.data().path("content")) {
            if (orderNo.equals(item.path("orderNo").asText())) return item;
        }
        return storage.data().path("missing");
    }

    /**
     * 결정 89·93: 이미 가진 상품을 다시 산 구매분은 보관함에 남고 등록은 409 PRODUCT_ALREADY_OWNED다. 라이브러리는 1건 그대로다.
     */
    static void repurchaseStaysInStorage(Account buyer, String productCode, String orderNo) {
        JsonNode[] found = new JsonNode[1];
        Awaitility.await().atMost(Duration.ofSeconds(50)).untilAsserted(() -> {
            found[0] = storageItem(buyer, orderNo);
            assertThat(found[0].isMissingNode()).as("재구매 보관함 항목").isFalse();
        });
        assertThat(found[0].path("status").asText()).isEqualTo("STORED");
        assertThat(found[0].path("registrable").asBoolean()).isFalse();
        E2eClient.Response rejected = Stove.gateway.post(
                "/api/v1/storage/" + found[0].path("licenseId").asText() + "/registration", null, buyer.headers());
        assertThat(rejected.status()).as(rejected.toString()).isEqualTo(409);
        assertThat(rejected.errorCode()).isEqualTo("PRODUCT_ALREADY_OWNED");
        E2eClient.Response library = Stove.gateway.get("/api/v1/library", buyer.headers());
        assertThat(library.data().path("content").size()).isEqualTo(1);
        assertThat(library.data().path("content").get(0).path("productCode").asText()).isEqualTo(productCode);
    }
}
