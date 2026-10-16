package com.stove.e2e;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.awaitility.Awaitility;

import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

import javax.imageio.ImageIO;

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
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
                        price);
        assertThat(Stove.gateway.put(path(id) + "/store-page", page, creator.headers()).status())
                .isEqualTo(200);
    }

    static long request(long id, Account creator, Account reviewer) {
        E2eClient.Response response = Stove.gateway.post(path(id) + "/review-requests", null, creator.headers());
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
                            JsonNode history =
                                    Stove.gateway
                                            .get(
                                                    path(project) + "/review-requests",
                                                    creator.headers())
                                            .data();
                            JsonNode match = null;
                            for (JsonNode r : history) if (r.path("id").asLong() == request) match = r;
                            assertThat(match).isNotNull();
                            assertThat(match.path("status").asText()).isEqualTo(state);
                        });
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
}
